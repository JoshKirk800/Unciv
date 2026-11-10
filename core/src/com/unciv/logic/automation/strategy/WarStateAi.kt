package com.unciv.logic.automation.strategy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import kotlin.math.pow

/** CvDiplomacyAI WarStateTypes, worst to best (the original's order matters: comparisons use it) */
enum class WarState { NearlyDefeated, Defensive, Stalemate, Calm, Offensive, NearlyWon }

/** CvDiplomacyAI StateAllWars: the overall assessment of all wars against majors */
enum class StateAllWars { Neutral, Winning, Losing }

/** CvDiplomacyAI TargetValueTypes, hardest to easiest (order matters) */
enum class TargetValue { Impossible, Bad, Average, Favorable, Soft }

/** CvDiplomacyAI StrengthTypes: another player's military strength compared to ours, weakest to strongest (order matters) */
enum class StrengthLevel { Pathetic, Weak, Poor, Average, Strong, Powerful, Immense }

/**
 *  What CvDiplomacyAI keeps about wars (`civ.ai.warStates`), updated once per turn by [WarStateAi.doTurn]. Keys are civIDs.
 *  Old saves have none: everything starts empty (no war state, neutral overall state, 0 turns at war).
 */
class WarStateMemory : IsPartOfGameInfoSerialization {
    /** CvDiplomacyAI::m_paiPlayerNumTurnsAtWar */
    var turnsAtWar = HashMap<String, Int>()
    /** CvDiplomacyAI::m_paeWarState: only players we are at war with have an entry (NO_WAR_STATE_TYPE = no entry) */
    var warStates = HashMap<String, WarState>()
    /** CvDiplomacyAI::m_eStateAllWars */
    var stateAllWars = StateAllWars.Neutral
    /** CvDiplomacyAI::m_paePlayerTargetValue */
    var targetValues = HashMap<String, TargetValue>()
    /** CvDiplomacyAI::m_paePlayerMilitaryStrengthComparedToUs */
    var militaryStrengths = HashMap<String, StrengthLevel>()

    fun clone() = WarStateMemory().also {
        it.turnsAtWar.putAll(turnsAtWar)
        it.warStates.putAll(warStates)
        it.stateAllWars = stateAllWars
        it.targetValues.putAll(targetValues)
        it.militaryStrengths.putAll(militaryStrengths)
    }
}

/**
 *  The war assessment of CvDiplomacyAI (CvDiplomacyAI.cpp), run each turn for major civilizations, humans included, as CvDiplomacyAI::DoTurn
 *  does: DoUpdatePlayerMilitaryStrengths, DoUpdatePlayerTargetValues, DoUpdateWarStates, plus the turns-at-war counter of DoCounters.
 *
 *  Queries (read the state of the last [doTurn]):
 *  - [warState] (CvDiplomacyAI::GetWarState): null if not at war with that player;
 *  - [stateAllWars] (CvDiplomacyAI::GetStateAllWars);
 *  - [targetValue] (CvDiplomacyAI::GetPlayerTargetValue), [militaryStrengthComparedToUs] (GetPlayerMilitaryStrengthComparedToUs),
 *    [turnsAtWar] (GetPlayerNumTurnsAtWar);
 *  - [numberCivsAtWarWith] (CvMilitaryAI::GetNumberCivsAtWarWith), [isPlayerValid] (CvDiplomacyAI::IsPlayerValid).
 *
 *  Unciv mapping: unit power is [com.unciv.models.ruleset.unit.BaseUnit.getAiPower] (CvUnitEntry::GetPower) × (1000 + 125 × level) / 1000
 *  × health / 100 (CvUnit::GetPower, level = 1 + promotions); city power is the city's combat strength ^ 1.5 (CvCity::GetPower);
 *  CvPlayer::getPower is [CivAi.militaryMight] plus the economic might (5 + population + production + gold of all cities).
 *  Unciv has no teams: "team" checks are per civilization.
 */
object WarStateAi {

    //region Queries

    /** CvDiplomacyAI::GetWarState: null (NO_WAR_STATE_TYPE) if not at war with [other] or not evaluated yet */
    fun warState(civ: Civilization, other: Civilization): WarState? = civ.ai.warStates.warStates[other.civID]

    /** CvDiplomacyAI::GetStateAllWars */
    fun stateAllWars(civ: Civilization): StateAllWars = civ.ai.warStates.stateAllWars

    /** CvDiplomacyAI::GetPlayerTargetValue: null (NO_TARGET_VALUE) before the first evaluation or for invalid players */
    fun targetValue(civ: Civilization, other: Civilization): TargetValue? = civ.ai.warStates.targetValues[other.civID]

    /** CvDiplomacyAI::GetPlayerMilitaryStrengthComparedToUs: null (NO_STRENGTH_VALUE) before the first evaluation or for invalid players */
    fun militaryStrengthComparedToUs(civ: Civilization, other: Civilization): StrengthLevel? = civ.ai.warStates.militaryStrengths[other.civID]

    /** CvDiplomacyAI::GetPlayerNumTurnsAtWar */
    fun turnsAtWar(civ: Civilization, other: Civilization): Int = civ.ai.warStates.turnsAtWar[other.civID] ?: 0

    /** CvMilitaryAI::GetNumberCivsAtWarWith: the valid majors we have a war state with */
    fun numberCivsAtWarWith(civ: Civilization): Int =
        civ.gameInfo.civilizations.count { it.isMajorCiv && it != civ && isPlayerValid(civ, it) && warState(civ, it) != null }

    /** CvDiplomacyAI::IsPlayerValid: alive, with at least one city, met, not ourselves (Unciv has no teams) */
    fun isPlayerValid(civ: Civilization, other: Civilization): Boolean =
        other != civ && !other.isBarbarian && !other.isSpectator && other.isAlive() && other.cities.isNotEmpty() && civ.knows(other)
    //endregion

    /** The per-turn update, in CvDiplomacyAI::DoTurn order: turns at war (DoCounters), military strengths, target values, war states */
    fun doTurn(civ: Civilization) {
        val memory = civ.ai.warStates
        val rules = civ.gameInfo.ruleset.ai
        for (other in civ.gameInfo.civilizations) {
            if (!isPlayerValid(civ, other)) continue
            if (civ.isAtWarWith(other)) memory.turnsAtWar[other.civID] = (memory.turnsAtWar[other.civID] ?: 0) + 1
            else memory.turnsAtWar.remove(other.civID)
        }
        for (other in civ.gameInfo.civilizations) {
            if (isPlayerValid(civ, other)) memory.militaryStrengths[other.civID] = militaryStrengthOf(civ, other, rules)
            else memory.militaryStrengths.remove(other.civID)
        }
        val myPower = getPower(civ).coerceAtLeast(1)
        for (other in civ.gameInfo.civilizations) {
            if (isPlayerValid(civ, other)) memory.targetValues[other.civID] = targetValueOf(civ, other, myPower, rules)
            else memory.targetValues.remove(other.civID)
        }
        updateWarStates(civ, memory, rules)
    }

    /** CvDiplomacyAI::DoFirstContactInitRelationship: DoUpdateOnePlayerMilitaryStrength then DoUpdateOnePlayerTargetValue for the new contact [other] */
    fun updateOnContact(civ: Civilization, other: Civilization) {
        val rules = civ.gameInfo.ruleset.ai
        civ.ai.warStates.militaryStrengths[other.civID] = militaryStrengthOf(civ, other, rules)
        civ.ai.warStates.targetValues[other.civID] = targetValueOf(civ, other, getPower(civ).coerceAtLeast(1), rules)
    }

    //region CvDiplomacyAI::DoUpdateOnePlayerMilitaryStrength

    private fun militaryStrengthOf(civ: Civilization, other: Civilization, rules: AiRuleset): StrengthLevel {
        val base = rules.define(AiDefine.MILITARY_STRENGTH_BASE)
        val ours = base + civ.ai.militaryMight()
        val theirs = other.ai.militaryMight() + base
        val ratio = theirs * rules.define(AiDefine.MILITARY_STRENGTH_RATIO_MULTIPLIER) / ours
        return when {
            ratio >= rules.define(AiDefine.MILITARY_STRENGTH_IMMENSE_THRESHOLD) -> StrengthLevel.Immense
            ratio >= rules.define(AiDefine.MILITARY_STRENGTH_POWERFUL_THRESHOLD) -> StrengthLevel.Powerful
            ratio >= rules.define(AiDefine.MILITARY_STRENGTH_STRONG_THRESHOLD) -> StrengthLevel.Strong
            ratio >= rules.define(AiDefine.MILITARY_STRENGTH_AVERAGE_THRESHOLD) -> StrengthLevel.Average
            ratio >= rules.define(AiDefine.MILITARY_STRENGTH_POOR_THRESHOLD) -> StrengthLevel.Poor
            ratio >= rules.define(AiDefine.MILITARY_STRENGTH_WEAK_THRESHOLD) -> StrengthLevel.Weak
            else -> StrengthLevel.Pathetic
        }
    }
    //endregion

    //region CvDiplomacyAI::DoUpdateOnePlayerTargetValue

    private fun targetValueOf(civ: Civilization, other: Civilization, myPower: Int, rules: AiRuleset): TargetValue {
        var otherStrength = getPower(other)
        var cityDamage = 0
        var numCities = 0
        for (city in other.cities) {
            otherStrength += cityPower(city) * rules.define(AiDefine.MILITARY_STRENGTH_CITY_MOD) / 100
            cityDamage += city.getMaxHealth() - city.health
            numCities++
        }
        if (numCities > 0) {
            // Average damage as a percentage of a city's hit points; all cities at no health only halve the strength
            cityDamage = cityDamage / numCities * 100 / rules.define(AiDefine.MAX_CITY_HIT_POINTS)
            otherStrength -= cityDamage * otherStrength / 200
        }
        var targetValue = otherStrength * rules.define(AiDefine.MILITARY_STRENGTH_RATIO_MULTIPLIER) / myPower

        // Already at war with others (majors only; minus our own war with them)
        var warCount = other.getCivsAtWarWith().count { it.isMajorCiv }
        if (civ.isAtWarWith(other)) warCount--
        targetValue += warCount * rules.define(AiDefine.TARGET_ALREADY_WAR_EACH_PLAYER)

        val proximity = civ.getProximity(other)
        targetValue += when (proximity) {
            Proximity.Neighbors -> rules.define(AiDefine.TARGET_NEIGHBORS)
            Proximity.Close -> rules.define(AiDefine.TARGET_CLOSE)
            Proximity.Far -> rules.define(AiDefine.TARGET_FAR)
            Proximity.Distant -> rules.define(AiDefine.TARGET_DISTANT)
            Proximity.None -> 0
        }

        // The friends of a city-state back it up
        if (other.isCityState) {
            val protectors = other.cityStateFunctions.getProtectorCivs()
            for (third in civ.gameInfo.civilizations) {
                if (!third.isMajorCiv || !isPlayerValid(civ, third)) continue
                if (other.allyCiv != third && third !in protectors) continue
                var thirdValue = when (civ.ai.warStates.militaryStrengths[third.civID]) {
                    StrengthLevel.Pathetic -> rules.define(AiDefine.TARGET_MINOR_BACKUP_PATHETIC)
                    StrengthLevel.Weak -> rules.define(AiDefine.TARGET_MINOR_BACKUP_WEAK)
                    StrengthLevel.Poor -> rules.define(AiDefine.TARGET_MINOR_BACKUP_POOR)
                    StrengthLevel.Average -> rules.define(AiDefine.TARGET_MINOR_BACKUP_AVERAGE)
                    StrengthLevel.Strong -> rules.define(AiDefine.TARGET_MINOR_BACKUP_STRONG)
                    StrengthLevel.Powerful -> rules.define(AiDefine.TARGET_MINOR_BACKUP_POWERFUL)
                    StrengthLevel.Immense -> rules.define(AiDefine.TARGET_MINOR_BACKUP_IMMENSE)
                    null -> 0
                }
                thirdValue *= when (civ.getProximity(third)) {
                    Proximity.Distant -> rules.define(AiDefine.TARGET_MINOR_BACKUP_DISTANT)
                    Proximity.Far -> rules.define(AiDefine.TARGET_MINOR_BACKUP_FAR)
                    Proximity.Close -> rules.define(AiDefine.TARGET_MINOR_BACKUP_CLOSE)
                    Proximity.Neighbors -> rules.define(AiDefine.TARGET_MINOR_BACKUP_NEIGHBORS)
                    Proximity.None -> 0
                }
                targetValue += thirdValue / 100
            }
        }

        var value = when {
            targetValue >= rules.define(AiDefine.TARGET_IMPOSSIBLE_THRESHOLD) -> TargetValue.Impossible
            targetValue >= rules.define(AiDefine.TARGET_BAD_THRESHOLD) -> TargetValue.Bad
            targetValue >= rules.define(AiDefine.TARGET_AVERAGE_THRESHOLD) -> TargetValue.Average
            targetValue >= rules.define(AiDefine.TARGET_FAVORABLE_THRESHOLD) -> TargetValue.Favorable
            else -> TargetValue.Soft
        }
        // An aggressive expander is an easier target
        if (value < TargetValue.Soft && isPlayerRecklessExpander(civ, other)) value = TargetValue.entries[value.ordinal + 1]
        // A long war makes a target harder
        if (value > TargetValue.Impossible && turnsAtWar(civ, other) > rules.define(AiDefine.TARGET_INCREASE_WAR_TURNS))
            value = TargetValue.entries[value.ordinal - 1]
        // Too far away to be soft (PLAYER_PROXIMITY_FAR or DISTANT)
        if (value == TargetValue.Soft && proximityRank(proximity) <= proximityRank(Proximity.Far)) value = TargetValue.Favorable
        return value
    }

    /** CvDiplomacyAI::IsPlayerRecklessExpander */
    fun isPlayerRecklessExpander(civ: Civilization, other: Civilization): Boolean {
        if (proximityRank(civ.getProximity(other)) < proximityRank(Proximity.Close)) return false
        val numCities = other.cities.size
        if (numCities < 4) return false
        var players = 0
        var totalCities = 0
        for (major in civ.gameInfo.civilizations) {
            if (!major.isMajorCiv || !major.isAlive() || major == other) continue
            players++
            totalCities += major.cities.size
        }
        if (players == 0) return false
        if (numCities < totalCities.toDouble() / players * 1.5) return false
        val strength = civ.ai.warStates.militaryStrengths[other.civID]
        return strength == null || strength < StrengthLevel.Average
    }

    /** PlayerProximityTypes order of the original: DISTANT 0, FAR 1, CLOSE 2, NEIGHBORS 3; no cities (NO_PLAYER_PROXIMITY) -1 */
    fun proximityRank(proximity: Proximity): Int = when (proximity) {
        Proximity.None -> -1
        Proximity.Distant -> 0
        Proximity.Far -> 1
        Proximity.Close -> 2
        Proximity.Neighbors -> 3
    }
    //endregion

    //region CvDiplomacyAI::DoUpdateWarStates

    private fun updateWarStates(civ: Civilization, memory: WarStateMemory, rules: AiRuleset) {
        var stateAllWarsValue = 0
        var losing = false
        memory.warStates.clear()
        val homeFrontRange = rules.define(AiDefine.AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT)
        for (other in civ.gameInfo.civilizations) {
            if (!isPlayerValid(civ, other) || !civ.isAtWarWith(other)) continue

            var myLocal = 0
            var enemyInHisLands = 0
            var myForeign = 0
            var enemyInMyLands = 0
            for (unit in civ.units.getCivUnits()) {
                val tile = unit.getTile()
                if (isHomeFrontFor(tile, civ, homeFrontRange)) myLocal += unitPower(unit)
                if (isHomeFrontFor(tile, other, homeFrontRange)) myForeign += unitPower(unit)
            }
            for (unit in other.units.getCivUnits()) {
                val tile = unit.getTile()
                if (isHomeFrontFor(tile, civ, homeFrontRange)) enemyInMyLands += unitPower(unit)
                if (isHomeFrontFor(tile, other, homeFrontRange)) enemyInHisLands += unitPower(unit)
            }
            for (city in civ.cities) myLocal += cityPower(city) * healthPercent(city) / 100 / 100
            for (city in other.cities) enemyInHisLands += cityPower(city) * healthPercent(city) / 100 / 100

            if (myLocal == 0) myLocal = 1
            val localRatio = myLocal * 100 / (myLocal + enemyInMyLands)
            if (enemyInHisLands == 0) enemyInHisLands = 1
            val foreignRatio = myForeign * 100 / (myForeign + enemyInHisLands)

            val calmThreshold = rules.define(AiDefine.WAR_STATE_CALM_THRESHOLD_FOREIGN_FORCES)
            var warState = if (foreignRatio < calmThreshold && localRatio > 100 - calmThreshold) WarState.Calm
            else {
                val warStateValue = (localRatio + foreignRatio) / 2
                when {
                    warStateValue >= rules.define(AiDefine.WAR_STATE_THRESHOLD_NEARLY_WON) -> WarState.NearlyWon
                    warStateValue >= rules.define(AiDefine.WAR_STATE_THRESHOLD_OFFENSIVE) -> WarState.Offensive
                    warStateValue >= rules.define(AiDefine.WAR_STATE_THRESHOLD_STALEMATE) -> WarState.Stalemate
                    warStateValue >= rules.define(AiDefine.WAR_STATE_THRESHOLD_DEFENSIVE) -> WarState.Defensive
                    else -> WarState.NearlyDefeated
                }
            }

            // A calm war against an easy target counts as offense
            if (warState == WarState.Calm) {
                val target = memory.targetValues[other.civID]
                if (target != null && target >= TargetValue.Favorable) warState = WarState.Offensive
            }
            // A few enemies near us while we vastly outnumber them is no defense
            if (warState <= WarState.Defensive && myLocal >= enemyInMyLands + enemyInMyLands) warState = WarState.Stalemate

            if (!other.isCityState) {
                when (warState) {
                    WarState.NearlyWon -> stateAllWarsValue += 2
                    WarState.Offensive -> stateAllWarsValue += 1
                    WarState.Defensive -> {
                        stateAllWarsValue -= 1
                        val capital = civ.getCapital(firstCityIfNoCapital = false)
                        if (capital != null && capital.health < capital.getMaxHealth()) losing = true
                    }
                    WarState.NearlyDefeated -> losing = true
                    else -> {}
                }
            }
            memory.warStates[other.civID] = warState
        }
        memory.stateAllWars = when {
            stateAllWarsValue < 0 || losing -> StateAllWars.Losing
            stateAllWarsValue > 0 -> StateAllWars.Winning
            else -> StateAllWars.Neutral
        }
    }

    /** CvPlot::IsHomeFrontForPlayer: owned by [civ], or closer than [range] to one of its cities */
    fun isHomeFrontFor(tile: Tile, civ: Civilization, range: Int): Boolean {
        if (tile.getOwner() == civ) return true
        return civ.cities.any { it.getCenterTile().aerialDistanceTo(tile) < range }
    }
    //endregion

    //region Power (CvUnit::GetPower, CvCity::GetPower, CvPlayer::getPower)

    fun unitPower(unit: MapUnit): Int {
        var power = unit.baseUnit.getAiPower()
        if (power == 0) return 0
        val level = 1 + unit.promotions.numberOfPromotions
        power = power * (1000 + level * 125) / 1000
        return power * unit.health / 100
    }

    fun cityPower(city: City): Int = CityCombatant(city).getCityStrength().toDouble().pow(1.5).toInt()

    private fun healthPercent(city: City): Int = city.health * 100 / city.getMaxHealth()

    /** CvPlayer::getPower: military might plus economic might (calculateEconomicMight: 5 + population + production + gold of the cities) */
    fun getPower(civ: Civilization): Int = civ.ai.militaryMight() + economicMight(civ)

    /** CvPlayer::calculateEconomicMight: 5 + the population, production and gold of all cities */
    fun economicMight(civ: Civilization): Int {
        var economicMight = 5
        for (city in civ.cities) {
            economicMight += city.population.population
            economicMight += city.cityStats.currentCityStats.production.toInt()
            economicMight += city.cityStats.currentCityStats.gold.toInt()
        }
        return economicMight
    }
    //endregion
}
