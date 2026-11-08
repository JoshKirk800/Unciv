package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.unit.GreatPersonAutomation
import com.unciv.logic.automation.unit.PlotDanger
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.unique.UniqueType

/** CvTacticalTarget: one thing the tactical AI may act on this turn. The [type] changes while priorities are identified. */
class TacticalTarget(
    var type: TacticalTargetType,
    val tile: Tile,
    /** The [DominanceZone.id] of the tile's zone, -1 for none (CvTacticalTarget::GetDominanceZone) */
    val zoneId: Int,
    /** The player the target belongs to (CvTacticalTarget::GetTargetPlayer) */
    val targetCiv: Civilization? = null,
    /** The unit of a unit or civilian target */
    val unit: MapUnit? = null,
    /** The city of a [TacticalTargetType.City] or [TacticalTargetType.CityToDefend] target */
    val city: City? = null,
    /** CvTacticalTarget::GetAuxIntData: what the targets are sorted by (damage of a unit, threat of a city); some moves store the damage required here */
    var aux: Int = 0,
) {
    /** CvTacticalTarget::IsTargetStillAlive: only unit and city targets can die */
    fun isAlive(civ: Civilization): Boolean = when (type) {
        TacticalTargetType.LowPriorityUnit, TacticalTargetType.MediumPriorityUnit, TacticalTargetType.HighPriorityUnit ->
            TacticalTargets.visibleEnemyDefender(civ, tile) != null
        TacticalTargetType.City -> tile.isCityCenter() && tile.getCity()?.civ == targetCiv
        else -> false
    }

    /** CvTacticalTarget::IsTargetValidInThisDomain */
    fun isValidInDomain(water: Boolean): Boolean = when (type) {
        TacticalTargetType.DefensiveBastion, TacticalTargetType.BarbarianCamp, TacticalTargetType.Improvement,
        TacticalTargetType.ImprovementToDefend, TacticalTargetType.AncientRuins, TacticalTargetType.TradeUnitLand,
        TacticalTargetType.TradeUnitLandPlot, TacticalTargetType.Citadel, TacticalTargetType.ImprovementResource -> !water
        TacticalTargetType.BlockadeResourcePoint, TacticalTargetType.BombardmentZone, TacticalTargetType.EmbarkedCivilian,
        TacticalTargetType.EmbarkedMilitaryUnit, TacticalTargetType.TradeUnitSea, TacticalTargetType.TradeUnitSeaPlot -> water
        TacticalTargetType.City, TacticalTargetType.CityToDefend, TacticalTargetType.LowPriorityCivilian,
        TacticalTargetType.MediumPriorityCivilian, TacticalTargetType.HighPriorityCivilian, TacticalTargetType.VeryHighPriorityCivilian,
        TacticalTargetType.LowPriorityUnit, TacticalTargetType.MediumPriorityUnit, TacticalTargetType.HighPriorityUnit -> true
        TacticalTargetType.None -> false
    }

    val isUnitTarget get() = type == TacticalTargetType.LowPriorityUnit || type == TacticalTargetType.MediumPriorityUnit ||
        type == TacticalTargetType.HighPriorityUnit

    override fun toString() = "$type at ${tile.position}"
}

/**
 *  The targets of the tactical AI for one civilization and turn (CvTacticalAI::FindTacticalTargets and the post-processing it runs:
 *  IdentifyPriorityTargets, IdentifyPriorityTargetsByType), sorted by [TacticalTarget.aux] (the most damaged unit, the most threatened city first).
 *
 *  Deviations: only tiles we see are looked at, as in the original; trade routes are not map units in Unciv, so there are no trade unit,
 *  trade plot or trade route targets; the barbarian passes (IdentifyPriorityBarbarianTargets, the release turn) are not here, barbarians keep
 *  their own AI; naval blockade points belong to the homeland AI; improvements that cannot be pillaged are not targets; a unit "reaches" the
 *  city it threatens if the city is within its base moves on its landmass (a ship needs a coastal city), as in [PlotDanger]; the fire support
 *  of the defending city's units is not subtracted from what an attacker does to it.
 */
class TacticalTargets private constructor(val all: List<TacticalTarget>) {

    /**
     *  CvTacticalAI::ExtractTargetsForZone: the targets of one dominance zone (those on its tiles of the zone's domain, and those within 2 tiles of
     *  the zone's city), or all of them for null.
     */
    fun forZone(zone: DominanceZone?): List<TacticalTarget> {
        if (zone == null) return all
        val city = zone.closestCity?.getCenterTile()
        return all.filter {
            it.isValidInDomain(zone.isWater) && (it.zoneId == zone.id || (city != null && city.aerialDistanceTo(it.tile) <= TARGETS_NEAR_CITY))
        }
    }

    companion object {
        /** The tiles around a zone's city whose targets belong to the zone as well */
        private const val TARGETS_NEAR_CITY = 2

        /** The mean of the damage roll: expected damage is the original's getCombatDamage without randomness */
        const val EXPECTED_RANDOMNESS = 0.5f

        /** The targets of [type] in [targets] (CvTacticalAI::GetFirstZoneTarget / GetNextZoneTarget) */
        fun ofType(targets: List<TacticalTarget>, type: TacticalTargetType) = targets.filter { it.type == type }

        /** CvPlot::getVisibleEnemyDefender: the military unit on the tile that fights for a civilization at war with [civ], if we see it and it can defend */
        fun visibleEnemyDefender(civ: Civilization, tile: Tile): MapUnit? {
            val unit = tile.militaryUnit ?: return null
            if (unit.civ == civ || !civ.isAtWarWith(unit.civ) || !unit.isVisibleTo(civ)) return null
            return unit.takeIf { TacticalSafety.canDefend(it) }
        }

        /** CvTacticalAI::FindTacticalTargets, then the priority identification */
        fun find(civ: Civilization, map: TacticalMap, danger: PlotDanger): TacticalTargets = timeThis("AI.TacticalTargets") {
            val targets = ArrayList<TacticalTarget>()
            val visible = civ.viewableTiles
            for (tile in civ.gameInfo.tileMap.tileList) {
                if (tile !in visible) continue
                val zoneId = map.zoneOf(tile)?.id ?: -1
                val city = if (tile.isCityCenter()) tile.getCity() else null
                if (city != null) {
                    if (city.civ == civ) targets.add(TacticalTarget(TacticalTargetType.CityToDefend, tile, zoneId, city = city, aux = danger.cityThreat(city)))
                    else if (civ.isAtWarWith(city.civ)) targets.add(TacticalTarget(TacticalTargetType.City, tile, zoneId, city.civ, city = city))
                    continue
                }
                targetOfTile(civ, map, danger, tile, zoneId)?.let { targets.add(it) }
            }
            identifyPriorityTargets(civ, targets)
            identifyPriorityTargetsByType(targets)
            // std::stable_sort by aux data, the highest first
            TacticalTargets(targets.sortedByDescending { it.aux })
        }

        /** The branches of FindTacticalTargets for a tile without a city, in the original's order: the first that applies makes the target */
        private fun targetOfTile(
            civ: Civilization, map: TacticalMap, danger: PlotDanger, tile: Tile, zoneId: Int
        ): TacticalTarget? {
            val owner = tile.getOwner()
            val ownerAtWar = owner != null && owner != civ && civ.isAtWarWith(owner)

            // ... enemy unit?
            visibleEnemyDefender(civ, tile)?.let {
                return TacticalTarget(TacticalTargetType.LowPriorityUnit, tile, zoneId, it.civ, unit = it, aux = 100 - it.health)
            }
            // ... undefended camp?
            if (tile.isBarbarianEncampment())
                return TacticalTarget(TacticalTargetType.BarbarianCamp, tile, zoneId, civ.gameInfo.getBarbarianCivilization())
            // ... goody hut?
            if (!civ.isCityState && tile.tileImprovement?.isAncientRuinsEquivalent() == true)
                return TacticalTarget(TacticalTargetType.AncientRuins, tile, zoneId)
            // Or citadels!
            val improvement = tile.getUnpillagedTileImprovement()
            if (ownerAtWar && improvement?.hasUnique(UniqueType.DamagesAdjacentEnemyUnits) == true)
                return TacticalTarget(TacticalTargetType.Citadel, tile, zoneId, owner)
            // ... enemy improvement?
            if (ownerAtWar && improvement != null && tile.canPillageTileImprovement()) {
                val resource = tile.tileResource
                val isImportant = resource != null && (resource.resourceType == ResourceType.Strategic || resource.resourceType == ResourceType.Luxury)
                // On land, civs prioritize improvements built on resources
                if (isImportant || tile.isWater || civ.isBarbarian)
                    return if (civ.isBarbarian && tile.isWater) null // barbarians can't target naval improvements
                    else TacticalTarget(TacticalTargetType.ImprovementResource, tile, zoneId, owner)
                return TacticalTarget(TacticalTargetType.Improvement, tile, zoneId, owner)
            }
            // ... enemy civilian (or embarked) unit?
            civilianTarget(civ, tile, zoneId)?.let { return it }
            // ... defensive bastion?
            if (owner == civ && map.defenseModifier(tile) > 0 && danger.get(tile) > 0) {
                val defenseCity = tile.neighbors.firstNotNullOfOrNull { n -> if (n.isCityCenter()) n.getCity()?.takeIf { it.civ == civ } else null }
                if (defenseCity != null)
                    return TacticalTarget(TacticalTargetType.DefensiveBastion, tile, zoneId, aux = danger.cityThreat(defenseCity) + danger.get(tile))
            }
            // ... friendly improvement?
            if (owner == civ && improvement != null)
                return TacticalTarget(TacticalTargetType.ImprovementToDefend, tile, zoneId)
            return null
        }

        /** The civilian branch of FindTacticalTargets: a unit of an enemy that cannot defend itself, with its priority tier */
        private fun civilianTarget(civ: Civilization, tile: Tile, zoneId: Int): TacticalTarget? {
            val unit = listOfNotNull(tile.militaryUnit, tile.civilianUnit).firstOrNull { it.civ != civ && civ.isAtWarWith(it.civ) && it.isVisibleTo(civ) }
                ?: return null
            if (unit.isDestroyed || TacticalSafety.canDefend(unit)) return null
            val type = when {
                unit.isEmbarked() -> if (unit.isMilitary()) TacticalTargetType.EmbarkedMilitaryUnit else TacticalTargetType.EmbarkedCivilian
                isVeryHighPriorityCivilian(unit) -> TacticalTargetType.VeryHighPriorityCivilian
                isHighPriorityCivilian(civ, unit) -> TacticalTargetType.HighPriorityCivilian
                isMediumPriorityCivilian(unit) -> TacticalTargetType.MediumPriorityCivilian
                else -> TacticalTargetType.LowPriorityCivilian
            }
            return TacticalTarget(type, tile, zoneId, unit.civ, unit = unit)
        }

        /** CvTacticalAI::IsVeryHighPriorityCivilianTarget: great generals and admirals */
        fun isVeryHighPriorityCivilian(unit: MapUnit): Boolean = UnitAiTypes.current(unit).let { it == UnitAiType.GENERAL || it == UnitAiType.ADMIRAL }

        /**
         *  CvTacticalAI::IsHighPriorityCivilianTarget: units whose type says so (great people and spaceship parts; settlers in the early game, the
         *  first third of the game speed), settlers of a small (under 5 cities), early game or cramped owner; anything for a barbarian.
         */
        fun isHighPriorityCivilian(civ: Civilization, unit: MapUnit): Boolean {
            val game = civ.gameInfo
            val earlyGame = game.turns < GreatPersonAutomation.estimateEndTurn(game) / 3
            when (game.ruleset.ai.unitAiTypesOf(unit.name)?.civilianAttackPriority) {
                CIVILIAN_ATTACK_PRIORITY_HIGH -> return true
                CIVILIAN_ATTACK_PRIORITY_HIGH_EARLY_GAME_ONLY -> if (earlyGame) return true
            }
            if (UnitAiTypes.current(unit) == UnitAiType.SETTLE && (unit.civ.cities.size < SMALL_PLAYER_CITIES || earlyGame || unit.civ.ai.isCramped)) return true
            return civ.isBarbarian // always high priority for barbarians
        }

        /** CvTacticalAI::IsMediumPriorityCivilianTarget: embarked civilians, settlers later in the game, workers early on */
        fun isMediumPriorityCivilian(unit: MapUnit): Boolean {
            val game = unit.civ.gameInfo
            val earlyGame = game.turns < GreatPersonAutomation.estimateEndTurn(game) / 3
            return when {
                unit.isEmbarked() && !unit.isMilitary() -> true
                UnitAiTypes.current(unit) == UnitAiType.SETTLE -> !earlyGame
                UnitAiTypes.current(unit) == UnitAiType.WORKER -> earlyGame
                else -> false
            }
        }

        private const val CIVILIAN_ATTACK_PRIORITY_HIGH = "CIVILIAN_ATTACK_PRIORITY_HIGH"
        private const val CIVILIAN_ATTACK_PRIORITY_HIGH_EARLY_GAME_ONLY = "CIVILIAN_ATTACK_PRIORITY_HIGH_EARLY_GAME_ONLY"
        private const val SMALL_PLAYER_CITIES = 5

        /**
         *  CvTacticalAI::IdentifyPriorityTargets: of each of our cities, what every visible enemy unit that can hit it next turn would do to it is
         *  added up. Over its hit points, all those units are high priority targets (a ranged one only medium); else they are medium priority.
         */
        private fun identifyPriorityTargets(civ: Civilization, targets: List<TacticalTarget>) {
            val unitTargets = targets.filter { it.isUnitTarget }
            if (unitTargets.isEmpty()) return
            for (city in civ.cities) {
                val combatant = CityCombatant(city)
                val attackers = ArrayList<TacticalTarget>()
                var total = 0
                for (target in unitTargets) {
                    val damage = expectedDamageToCity(target.unit!!, combatant)
                    if (damage > 0) {
                        total += damage
                        attackers.add(target)
                    }
                }
                // If they can take the city down and they are a melee unit, then they are a high priority target
                if (total > city.health) {
                    for (target in attackers)
                        target.type = if (target.unit!!.baseUnit.isRanged()) TacticalTargetType.MediumPriorityUnit else TacticalTargetType.HighPriorityUnit
                } else for (target in attackers)
                    // If they can damage a city they are a medium priority target
                    if (target.type != TacticalTargetType.HighPriorityUnit) target.type = TacticalTargetType.MediumPriorityUnit
            }
        }

        /** What one enemy unit does to [city] next turn: shooting it if it is a ranged unit within range, else attacking it if it can get there */
        private fun expectedDamageToCity(enemy: MapUnit, city: CityCombatant): Int {
            val cityTile = city.getTile()
            val distance = enemy.getTile().aerialDistanceTo(cityTile)
            val base = enemy.baseUnit
            if (base.rangedStrength > 0 && base.rangedStrength > base.strength) {
                if (distance > enemy.getRange()) return 0
            } else {
                if (distance > base.movement.coerceAtLeast(1)) return 0
                if (base.isLandUnit && enemy.getTile().getContinent() != cityTile.getContinent()) return 0
                if (base.isWaterUnit && !city.city.isCoastal()) return 0
            }
            return BattleDamage.calculateDamageToDefender(MapUnitCombatant(enemy), city, enemy.getTile(), EXPECTED_RANDOMNESS)
        }

        /**
         *  CvTacticalAI::IdentifyPriorityTargetsByType: ranged units are medium priority targets (a high or low one is set to medium, as the
         *  original does), and units standing on a citadel in their own territory are high priority.
         */
        private fun identifyPriorityTargetsByType(targets: List<TacticalTarget>) {
            for (target in targets) {
                val unit = target.unit ?: continue
                if (target.type == TacticalTargetType.HighPriorityUnit || target.type == TacticalTargetType.LowPriorityUnit)
                    if (unit.baseUnit.isRanged()) target.type = TacticalTargetType.MediumPriorityUnit
                if (target.type == TacticalTargetType.MediumPriorityUnit || target.type == TacticalTargetType.LowPriorityUnit) {
                    val tile = unit.getTile()
                    if (tile.getOwner() == unit.civ && tile.getUnpillagedTileImprovement()?.hasUnique(UniqueType.DamagesAdjacentEnemyUnits) == true)
                        target.type = TacticalTargetType.HighPriorityUnit
                }
            }
        }
    }
}
