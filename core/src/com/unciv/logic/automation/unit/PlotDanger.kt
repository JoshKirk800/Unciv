package com.unciv.logic.automation.unit

import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.ceil

/**
 * The danger [civ] sees on a tile: CvDangerPlots::UpdateDanger / GetDanger (CvPlayer::GetPlotDanger), worked out lazily for the tiles that are
 * asked about and remembered for the turn the instance was made in. One instance per civilization and turn is held by
 * [com.unciv.logic.automation.strategy.CivAi.plotDanger] (and handed out by `AutomationCache.plotDanger`); code that changed the map
 * since the instance was made (tests, mid-turn kills) makes a new one.
 *
 * Sources: the visible units able to attack of every player not ignored (CvDangerPlots::ShouldIgnorePlayer/ShouldIgnoreUnit, air units skipped)
 * within their base moves (plus their range if ranged): strength considering damage x100 / turns away; revealed cities within CITY_ATTACK_RANGE:
 * city strength x100; tiles next to a revealed hostile citadel: the power of our strongest buildable land unit x50. Each value is scaled by
 * CvDangerPlots::ModifyDangerByRelationship (AI_DANGER_* by approach, our own territory zeroed for those we only distrust).
 * The lowest bit of a value tells that the danger is within one move of the source ([isUnderImmediateThreat], CvDangerPlots::IsUnderImmediateThreat).
 * [cityThreat] is CvCity::getThreatValue, the sum of the values within AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT of a city (GetCityDanger).
 *
 * Deviations: turns away is distance / base moves rounded up instead of the ignore-units path finder, and a unit only threatens tiles of its own
 * domain (the path finder's result for the other domain), its own tile always; the strength is the unit's base strength (GetMaxAttackStrength
 * without modifiers); the player's wounded-unit modifier is not applied.
 */
class PlotDanger(private val civ: Civilization) {
    private val rules = civ.gameInfo.ruleset.ai
    private val units = ArrayList<MapUnit>()
    private val cities = ArrayList<City>()
    private val citadels = ArrayList<Tile>()
    /** Danger by [Tile.zeroBasedIndex], [NOT_COMPUTED] until asked */
    private val danger = IntArray(civ.gameInfo.tileMap.tileList.size) { NOT_COMPUTED }
    private val cityThreats = HashMap<String, Int>()
    private val cityRange = rules.define(AiDefine.CITY_ATTACK_RANGE)
    private val threatRange = rules.define(AiDefine.AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT)
    private val citadelValue: Int by lazy { strongestBuildableLandUnitPower() * 50 }

    init {
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || shouldIgnorePlayer(other)) continue
            for (unit in other.units.getCivUnits())
                if (unit.isMilitary() && !unit.baseUnit.isAirUnit() && unit.isVisibleTo(civ)) units.add(unit)
            for (city in other.cities)
                if (city.getCenterTile().isExplored(civ)) cities.add(city)
            if (civ.isAtWarWith(other)) for (city in other.cities) for (tile in city.getTiles())
                if (tile.isExplored(civ) && tile.getUnpillagedTileImprovement()?.hasUnique(UniqueType.DamagesAdjacentEnemyUnits) == true) citadels.add(tile)
        }
    }

    /** CvPlayer::GetPlotDanger */
    fun get(tile: Tile): Int {
        val index = tile.zeroBasedIndex
        var value = danger[index]
        if (value == NOT_COMPUTED) {
            value = compute(tile)
            danger[index] = value
        }
        return value
    }

    /** CvDangerPlots::IsUnderImmediateThreat (CvPlayer::IsPlotUnderImmediateThreat): some source can reach the tile within one move */
    fun isUnderImmediateThreat(tile: Tile): Boolean = get(tile) and 1 != 0

    /** CvCity::getThreatValue, set by CvDangerPlots::UpdateDanger from GetCityDanger: the sum of the danger of the tiles around [city] */
    fun cityThreat(city: City): Int = cityThreats.getOrPut(city.id) {
        var sum = 0
        city.getCenterTile().forEachTileInDistance(threatRange) { sum += get(it) }
        sum
    }

    private fun compute(tile: Tile): Int {
        var value = 0
        for (unit in units) {
            val unitTile = unit.getTile()
            val distance = tile.aerialDistanceTo(unitTile)
            val baseMoves = unit.baseUnit.movement.coerceAtLeast(1)
            var range = baseMoves
            if (unit.baseUnit.rangedStrength > 0) range += unit.baseUnit.range
            if (distance > range) continue
            if (tile != unitTile && unit.baseUnit.isLandUnit != tile.isLand) continue
            val turnsAway = maxOf(1, ceil(distance / baseMoves.toFloat()).toInt())
            val modified = modifyByRelationship(unit.civ, tile, strengthConsideringDamage(unit, rules) * 100 / turnsAway)
            value += withinOneMoveBit(modified, turnsAway <= 1)
        }
        for (city in cities) {
            if (tile.aerialDistanceTo(city.getCenterTile()) > cityRange) continue
            val modified = modifyByRelationship(city.civ, tile, CityCombatant(city).getCityStrength() * 100)
            value += withinOneMoveBit(modified, false)
        }
        for (citadel in citadels)
            if (tile.aerialDistanceTo(citadel) == 1) value += withinOneMoveBit(citadelValue, true)
        return value
    }

    /** CvDangerPlots::AddDanger: the lowest bit of a positive value marks danger within one move */
    private fun withinOneMoveBit(value: Int, withinOneMove: Boolean): Int = when {
        value <= 0 -> value
        withinOneMove -> value or 1
        else -> value and 1.inv()
    }

    /** CvDangerPlots::ShouldIgnorePlayer: between a major and a city-state, friends are ignored, and a major ignores city-states at peace */
    private fun shouldIgnorePlayer(other: Civilization): Boolean {
        if (civ.isCityState == other.isCityState || civ.isBarbarian || other.isBarbarian) return false
        val minor = if (civ.isCityState) civ else other
        val major = if (civ.isCityState) other else civ
        if (minor.getDiplomacyManager(major)?.isRelationshipLevelGE(RelationshipLevel.Friend) == true) return true
        return !civ.isCityState && !civ.isAtWarWith(other)
    }

    /** CvDangerPlots::ModifyDangerByRelationship */
    private fun modifyByRelationship(owner: Civilization, tile: Tile, danger: Int): Int {
        if (civ.isAtWarWith(owner)) return danger
        if (civ.isHuman()) return 0
        var result = danger
        var ignoreInOwnTerritory = false
        if (civ.isCityState) ignoreInOwnTerritory = true
        else if (!owner.isCityState) {
            val approach = civ.ai.diplomacy.approachTowards(owner)
            result = (result * rules.defineFloat(majorApproachDefine(approach))).toInt()
            ignoreInOwnTerritory = approach != MajorCivApproach.War
        } else {
            val approach = civ.ai.minorCivApproaches[owner.civID] ?: MinorCivApproach.Ignore
            val define = when (approach) {
                MinorCivApproach.Friendly -> AiDefine.AI_DANGER_MINOR_APPROACH_FRIENDLY
                MinorCivApproach.Bully -> AiDefine.AI_DANGER_MINOR_APPROACH_BULLY
                MinorCivApproach.Conquest -> AiDefine.AI_DANGER_MINOR_APPROACH_CONQUEST
                else -> AiDefine.AI_DANGER_MINOR_APPROACH_NEUTRAL
            }
            result = (result * rules.defineFloat(define)).toInt()
            ignoreInOwnTerritory = approach == MinorCivApproach.Ignore || approach == MinorCivApproach.Friendly
        }
        if (ignoreInOwnTerritory && tile.getOwner() == civ) result = 0
        return result
    }

    private fun majorApproachDefine(approach: MajorCivApproach) = when (approach) {
        MajorCivApproach.War -> AiDefine.AI_DANGER_MAJOR_APPROACH_WAR
        MajorCivApproach.Hostile -> AiDefine.AI_DANGER_MAJOR_APPROACH_HOSTILE
        MajorCivApproach.Deceptive -> AiDefine.AI_DANGER_MAJOR_APPROACH_DECEPTIVE
        MajorCivApproach.Guarded -> AiDefine.AI_DANGER_MAJOR_APPROACH_GUARDED
        MajorCivApproach.Afraid -> AiDefine.AI_DANGER_MAJOR_APPROACH_AFRAID
        MajorCivApproach.Friendly -> AiDefine.AI_DANGER_MAJOR_APPROACH_FRIENDLY
        MajorCivApproach.Neutral -> AiDefine.AI_DANGER_MAJOR_APPROACH_NEUTRAL
    }

    /** CvMilitaryAI::GetPowerOfStrongestBuildableUnit(DOMAIN_LAND), with Unciv's force evaluation as the unit power */
    private fun strongestBuildableLandUnitPower(): Int = civ.gameInfo.ruleset.units.values.asSequence()
        .filter { it.isLandUnit && it.isMilitary && it.isBuildable(civ) }
        .maxOfOrNull { it.getForceEvaluation() } ?: 0

    companion object {
        private const val NOT_COMPUTED = -1

        /** CvUnit::GetBaseCombatStrengthConsideringDamage (the unit's base strength, see the class comment) */
        fun strengthConsideringDamage(unit: MapUnit, rules: AiRuleset): Int {
            val strength = unit.baseUnit.strength
            val damageMod = (100 - unit.health) * rules.define(AiDefine.WOUNDED_DAMAGE_MULTIPLIER) / 100
            return strength - strength * damageMod / 100
        }
    }
}
