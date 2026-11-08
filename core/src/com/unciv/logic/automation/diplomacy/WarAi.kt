package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.ai.MultiUnitFormationDef

/**
 *  War-making of CvDiplomacyAI: MakeWar (targets by war projection and proximity²), DoMakeWarOnPlayer (one war with majors at a time,
 *  WarGoal PREPARE, the attack operation, declaring war when the army is in position), DeclareWar, DoMakePeaceWithMinors.
 *
 *  Unciv has no AI operations (CvAIOperation, out of this port's scope). Their equivalent here: the operation exists when the army is ready
 *  (CvMilitaryAI::IsAttackReady: the required slots of the city attack formation can be filled by healthy units), the target city is the
 *  nearest known city of the target (FindBestAttackTarget simplified), the muster city our city nearest to it; the unit AI gathers the army
 *  there while the war goal is PREPARE ([com.unciv.logic.automation.unit.UnitAutomation]). War is declared when that army stands within
 *  AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE of the muster city (the original: the operation is 85% of the way, or mustered).
 */
object WarAi {

    /** CvDiplomacyAI::MakeWar */
    fun makeWar(civ: Civilization, rules: AiRuleset) {
        val ai = civ.ai.diplomacy
        val targets = civ.gameInfo.civilizations.filter { (it.isMajorCiv || it.isCityState) && WarStateAi.isPlayerValid(civ, it) }
            .map { target ->
                var weight = (ai.warProjection(target)?.ordinal ?: -1) + 1
                // Square the distance enum to make it crucial
                val proximity = 1 + DiplomacyTurn.proximity(civ, target)
                weight *= proximity
                weight *= proximity
                if (target.isMajorCiv) {
                    if (ai.opinionOf(target) == MajorCivOpinion.Unforgivable) weight *= 2
                    weight *= 10  // majors before city-states
                }
                target to weight
            }.sortedByDescending { it.second }
        for ((target, _) in targets) doMakeWarOnPlayer(civ, target, rules)
        CoopWarAi.doCoopWarCounters(civ, rules)
    }

    /** MilitaryAIHelpers::IsTestStrategy_AtWar: at war with at least one major */
    fun isAtWarWithAMajor(civ: Civilization): Boolean = civ.getCivsAtWarWith().any { it.isMajorCiv }

    /** CvDiplomacyAI::DoMakeWarOnPlayer */
    fun doMakeWarOnPlayer(civ: Civilization, target: Civilization, rules: AiRuleset) {
        if (!WarStateAi.isPlayerValid(civ, target)) return
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(target)
        if (memory.warGoal == WarGoal.Demand) return
        if (civ.isAtWarWith(target)) return
        val atWarWithAMajor = isAtWarWithAMajor(civ)
        val wantToAttack = if (target.isCityState) {
            !atWarWithAMajor && civ.ai.minorCivApproaches[target.civID] == MinorCivApproach.Conquest
        } else {
            val approach = ai.approachTowards(target)
            // Let's not get into another war right now
            (approach == MajorCivApproach.War || (approach == MajorCivApproach.Deceptive && civ.ai.isGoingForWorldConquest())) && !atWarWithAMajor
        }
        val canDeclare = civ.getDiplomacyManager(target)?.canDeclareWar() == true
        if (memory.warGoal != WarGoal.Prepare) {
            // Not yet readying an attack
            if (canDeclare && wantToAttack) memory.warGoal = WarGoal.Prepare
            return
        }
        if (!wantToAttack) {
            // We were planning an attack but changed our minds
            memory.warGoal = null
            memory.attackOperation = false
            return
        }
        if (!canDeclare) return
        if (!memory.attackOperation) {
            // CvMilitaryAI::RequestSneakAttack: one sneak attack at a time; the operation starts when the army can be formed
            if (!target.isCityState && ai.memories.any { (id, other) -> other.attackOperation && id != target.civID &&
                    civ.gameInfo.getCivilizationOrNull(id)?.isMajorCiv == true }) return
            val targetCity = targetCity(civ, target) ?: return
            val muster = musterCity(civ, targetCity) ?: return
            val formation = formationOf(civ, target, muster, targetCity, rules) ?: return
            if (!isAttackReady(civ, formation.first, formation.second, rules)) return
            memory.attackOperation = true
        }
        // The army is in position: let loose the dogs of war
        if (isArmyInPosition(civ, target, rules)) {
            memory.attackOperation = false
            declareWar(civ, target)
        }
    }

    /** CvDiplomacyAI::DeclareWar */
    fun declareWar(civ: Civilization, target: Civilization) {
        val diplomacy = civ.getDiplomacyManager(target) ?: return
        if (civ.isAtWarWith(target)) return
        diplomacy.declareWar(DeclareWarReason(WarType.DirectWar))
    }

    /** CvDiplomacyAI::DoMakePeaceWithMinors: a war goal of PEACE long enough, not locked into the war */
    fun makePeaceWithMinors(civ: Civilization) {
        val ai = civ.ai.diplomacy
        for (minor in civ.getCivsAtWarWith().filter { it.isCityState && WarStateAi.isPlayerValid(civ, it) }) {
            if (!ai.isWantsPeaceWith(minor) || ai.memoryOf(minor).turnsLockedIntoWar != 0) continue
            // CvMinorCivAI::IsPeaceBlocked: a city-state allied to someone at war with us stays at war
            val ally = minor.allyCiv
            if (ally != null && ally.isAtWarWith(civ)) continue
            civ.getDiplomacyManager(minor)!!.makePeace()
            DiplomacyEvents.onPeace(civ, minor)
        }
    }

    //region The attack operation stand-in

    /** FindBestAttackTarget simplified: the known city of [target] nearest to one of our cities */
    fun targetCity(civ: Civilization, target: Civilization): City? {
        if (civ.cities.isEmpty()) return null
        return target.cities.filter { it.getCenterTile().isExplored(civ) }
            .minByOrNull { city -> civ.cities.minOf { it.getCenterTile().aerialDistanceTo(city.getCenterTile()) } }
    }

    /** The muster city: ours, nearest to [targetCity] */
    fun musterCity(civ: Civilization, targetCity: City): City? = civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(targetCity.getCenterTile()) }

    /** The formation of RequestSneakAttack / RequestCityStateAttack (by sea when the target is on another landmass) */
    private fun formationName(civ: Civilization, target: Civilization, bySea: Boolean): String {
        if (target.isCityState) return if (bySea) "MUFORMATION_CITY_STATE_INVASION" else "MUFORMATION_CITY_STATE_ATTACK_FORCE"
        if (bySea) return "MUFORMATION_NAVAL_INVASION"
        // Handicap above Prince (HANDICAP index > 4 in the original's table) on a map that is not mostly water
        val difficulties = civ.gameInfo.ruleset.difficulties.keys.toList()
        val bigger = difficulties.indexOf(civ.gameInfo.difficulty) > 4
        return if (bigger) "MUFORMATION_BIGGER_CITY_ATTACK_FORCE" else "MUFORMATION_BASIC_CITY_ATTACK_FORCE"
    }

    /** The formation for the attack from [muster] on [targetCity] and whether it goes by sea, null if the ruleset has no such formation */
    private fun formationOf(civ: Civilization, target: Civilization, muster: City, targetCity: City, rules: AiRuleset): Pair<MultiUnitFormationDef, Boolean>? {
        val bySea = muster.getCenterTile().getContinent() != targetCity.getCenterTile().getContinent()
        val formation = rules.multiUnitFormations.firstOrNull { it.name == formationName(civ, target, bySea) } ?: return null
        return formation to bySea
    }

    /** Whether our army against [target] is formed and gathered at the muster city (see the class comment) */
    fun isArmyInPosition(civ: Civilization, target: Civilization, rules: AiRuleset): Boolean {
        val targetCity = targetCity(civ, target) ?: return false
        val muster = musterCity(civ, targetCity) ?: return false
        val (formation, bySea) = formationOf(civ, target, muster, targetCity, rules) ?: return false
        val range = rules.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE)
        val musterTile = muster.getCenterTile()
        return isAttackReady(civ, formation, bySea, rules) { unitTile -> unitTile.aerialDistanceTo(musterTile) <= range }
    }

    /**
     *  CvMilitaryAI::IsAttackReady / MilitaryAIHelpers::NumberOfFillableSlots: every required slot of [formation] can be filled by our units
     *  (not scouts, at least AI_OPERATIONAL_PERCENT_HEALTH_FOR_OPERATION health, able to move by sea if needed) that pass [isInPlace].
     *  A scout is a unit whose AI type is UNITAI_EXPLORE(_SEA); Unciv units have no AI type of their own, so it is the unit's default one.
     */
    fun isAttackReady(civ: Civilization, formation: MultiUnitFormationDef, bySea: Boolean, rules: AiRuleset,
                      isInPlace: (com.unciv.logic.map.tile.Tile) -> Boolean = { true }): Boolean {
        val slots = formation.slots.toMutableList()
        var filled = 0
        val minHealth = rules.define(AiDefine.AI_OPERATIONAL_PERCENT_HEALTH_FOR_OPERATION)
        for (unit in civ.units.getCivUnits()) {
            if (slots.isEmpty()) break
            val types = unitAiTypesOf(rules, unit.name)
            if (types.isEmpty()) continue
            val defaultAi = defaultUnitAiOf(rules, unit.name)
            if (defaultAi == "UNITAI_EXPLORE" || defaultAi == "UNITAI_EXPLORE_SEA") continue
            if (unit.health < minHealth) continue
            if (bySea && !unit.baseUnit.isWaterUnit && unit.cache.cannotEmbark) continue
            if (!isInPlace(unit.getTile())) continue
            val index = slots.indexOfFirst { it.primaryUnitAi in types || it.secondaryUnitAi in types }
            if (index < 0) continue
            slots.removeAt(index)
            filled++
        }
        val required = filled + slots.count { it.required }
        return filled >= required
    }

    private var unitAiIndexOwner: AiRuleset? = null
    private var unitAiIndex: Map<String, List<String>> = emptyMap()
    private var defaultUnitAiIndex: Map<String, String?> = emptyMap()

    private fun indexUnitAis(rules: AiRuleset) {
        if (unitAiIndexOwner === rules) return
        unitAiIndex = rules.unitAiTypes.associate { def -> def.name to (def.unitAiTypes + listOfNotNull(def.defaultUnitAi)).distinct() }
        defaultUnitAiIndex = rules.unitAiTypes.associate { def -> def.name to def.defaultUnitAi }
        unitAiIndexOwner = rules
    }

    /** Unit_AITypes of a unit (its default AI type included) */
    fun unitAiTypesOf(rules: AiRuleset, unitName: String): List<String> {
        indexUnitAis(rules)
        return unitAiIndex[unitName] ?: emptyList()
    }

    /** Units.DefaultUnitAI of a unit */
    fun defaultUnitAiOf(rules: AiRuleset, unitName: String): String? {
        indexUnitAis(rules)
        return defaultUnitAiIndex[unitName]
    }
    //endregion
}
