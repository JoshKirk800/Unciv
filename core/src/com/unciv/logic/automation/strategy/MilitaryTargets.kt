package com.unciv.logic.automation.strategy

import com.unciv.Constants

import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.stats.Stat
import kotlin.math.max
import kotlin.math.min

/** CvMilitaryTarget: an attack from [musterCity] on [targetCity] */
class MilitaryTarget(val musterCity: City, val targetCity: City) {
    /** Power of our units within 5 tiles of the muster city and of theirs near the target (iMusterNearbyUnitPower / iTargetNearbyUnitPower) */
    var musterNearbyUnitPower = 0
    var targetNearbyUnitPower = 0
    var attackBySea = false
    /** Step path length from the muster to the target city, -1 = no path (call off) */
    var pathLength = 0
}

/** CityAttackApproaches, best to worst */
enum class CityAttackApproach(val multiplier: Int) { UNRESTRICTED(10), OPEN(8), NEUTRAL(4), LIMITED(2), RESTRICTED(1), NONE(0) }

/**
 *  The step finder of the original (GC.getStepFinder(): a hex path over terrain that ignores units) as far as the military AI needs it:
 *  a land route an army of [civ] could walk against [enemy] - explored, not water, not impassable, and territory it may enter (its own,
 *  the enemy's, friendly land, city-state land and that of civilizations it is at war with) - or a route over explored water under the
 *  same rules, where deep ocean counts as impassable until [civ] can embark onto it. One path length is a step.
 *  Scratch arrays are reused between calls; not thread safe, one instance per civilization AI.
 */
class StepFinder {
    private var stamp = IntArray(0)
    private var parent = IntArray(0)
    private var dist = IntArray(0)
    private var queue = IntArray(0)
    private var counter = 0

    private fun ensure(size: Int) {
        if (stamp.size >= size) return
        stamp = IntArray(size); parent = IntArray(size); dist = IntArray(size); queue = IntArray(size)
        counter = 0
    }

    /**
     *  StepValid: [tile] can be walked on by an army of [civ] heading for [enemy]. Land that has an owner is valid when the owner is
     *  [enemy], the tile is friendly territory for [civ], or the owner is a city-state or at war with [civ]: the original lets any civilization
     *  cross city-state land and only a major that is neither the enemy, a friend nor at war blocks the route. This is the route planner's
     *  rule, not the movement rules ([com.unciv.logic.civilization.diplomacy.DiplomacyFunctions.canPassThroughTiles]), which the units walk by.
     *  The original stays within one area (the land of the start, or its water), which here is land or water by the kind of route.
     */
    fun isValid(civ: Civilization, enemy: Civilization?, tile: Tile): Boolean = !tile.isWater && isOpen(civ, enemy, tile)

    /** StepValid for a route over water: the same rules for the tile, in the water area of the start */
    private fun seaValid(civ: Civilization, enemy: Civilization?, tile: Tile) = tile.isWater && isOpen(civ, enemy, tile)

    /**
     *  StepValid beyond the area check: nothing impassable (mountains, ice), no deep ocean unless the team can embark onto it
     *  (GetEmbarkedAllWaterPassage, [com.unciv.logic.civilization.managers.TechManager.embarkedUnitsCanEnterOcean]), and the territory rule of [isValid].
     *  Explored tiles only, the AI does not know the rest.
     */
    private fun isOpen(civ: Civilization, enemy: Civilization?, tile: Tile): Boolean {
        if (tile.isImpassible() || !civ.hasExplored(tile)) return false
        if (tile.isOcean && !civ.tech.embarkedUnitsCanEnterOcean) return false
        val owner = tile.getOwner() ?: return true
        return owner == enemy || tile.isFriendlyTerritory(civ) || owner.isCityState || owner.isBarbarian || civ.isAtWarWith(owner)
    }

    /** Breadth-first search from [start]; the visited tiles are left in the scratch arrays */
    private fun search(civ: Civilization, enemy: Civilization?, start: Tile, sea: Boolean, maxSteps: Int, stopAt: Tile?): Boolean {
        val tiles = civ.gameInfo.tileMap.tileList
        ensure(tiles.size)
        counter++
        var head = 0
        var tail = 0
        val startIndex = start.zeroBasedIndex
        stamp[startIndex] = counter; dist[startIndex] = 0; parent[startIndex] = -1
        queue[tail++] = startIndex
        while (head < tail) {
            val current = tiles[queue[head++]]
            if (current === stopAt) return true
            if (dist[current.zeroBasedIndex] >= maxSteps) continue
            for (neighbor in current.neighbors) {
                val index = neighbor.zeroBasedIndex
                if (stamp[index] == counter) continue
                if (neighbor !== stopAt && !(if (sea) seaValid(civ, enemy, neighbor) else isValid(civ, enemy, neighbor))) continue
                stamp[index] = counter
                dist[index] = dist[current.zeroBasedIndex] + 1
                parent[index] = current.zeroBasedIndex
                queue[tail++] = index
            }
        }
        return stopAt == null
    }

    /** The step length from [start] to each of [targets], -1 where there is no route (one search for all) */
    fun distances(civ: Civilization, enemy: Civilization?, start: Tile, targets: List<Tile>): IntArray {
        search(civ, enemy, start, sea = false, maxSteps = Int.MAX_VALUE, stopAt = null)
        return IntArray(targets.size) { if (stamp[targets[it].zeroBasedIndex] == counter) dist[targets[it].zeroBasedIndex] else -1 }
    }

    /** GetStepDistanceBetweenPoints / DoesPathExist: the step length, -1 if none */
    fun distance(civ: Civilization, enemy: Civilization?, from: Tile, to: Tile, sea: Boolean = false): Int =
        if (search(civ, enemy, from, sea, maxSteps = Int.MAX_VALUE, stopAt = to)) dist[to.zeroBasedIndex] else -1

    /** GeneratePath: the tiles from [from] to [to] inclusive, null if none */
    fun path(civ: Civilization, enemy: Civilization?, from: Tile, to: Tile, sea: Boolean = false): List<Tile>? {
        if (!search(civ, enemy, from, sea, maxSteps = Int.MAX_VALUE, stopAt = to)) return null
        val tiles = civ.gameInfo.tileMap.tileList
        val result = ArrayList<Tile>()
        var index = to.zeroBasedIndex
        while (index != -1) {
            result.add(tiles[index])
            index = parent[index]
        }
        result.reverse()
        return result
    }

    /** DoesPathExist over water between two sea tiles */
    fun seaPathExists(civ: Civilization, enemy: Civilization?, from: Tile, to: Tile): Boolean = search(civ, enemy, from, sea = true, maxSteps = Int.MAX_VALUE, stopAt = to)

    /**
     *  GetXPlotsFromEnd: the tile [x] steps before the end of the path from [from] to [to] (the start if the path is shorter);
     *  null if there is no path. With [sea] the path runs over explored water (the naval operations' steps).
     */
    fun xPlotsFromEnd(civ: Civilization, enemy: Civilization?, from: Tile, to: Tile, x: Int, sea: Boolean = false): Tile? {
        val path = path(civ, enemy, from, to, sea) ?: return null
        return path[max(0, path.size - 1 - x)]
    }
}

/**
 *  The target selection of CvMilitaryAI: FindBestAttackTarget, ShouldAttackBySea, ScoreTarget, EvaluateMilitaryApproaches,
 *  GetNearestCoastalCity, GetCoastalPlotAdjacentToTarget and GetMostThreatenedCity.
 *
 *  Deviations: the step finder is [StepFinder] (own routes only, no ignore-units variant).
 */
object MilitaryTargets {

    /** CvUnit::IsCombatUnit units of [owner] within 5 tiles of [city] add their power; x1.1 with a Great General there */
    private fun nearbyPower(owner: Civilization, city: City): Int {
        val tile = city.getCenterTile()
        var power = 0
        var general = false
        for (unit in owner.units.getCivUnits()) {
            val distance = unit.getTile().aerialDistanceTo(tile)
            if (distance > 5) continue
            if (unit.isMilitary()) power += MilitaryAi.unitPower(unit)
            if (unit.isGreatPersonOfType("War")) general = true
        }
        return if (general) power * 11 / 10 else power
    }

    /**
     *  CvMilitaryAI::FindBestAttackTarget: for every pair of one of our cities and one revealed city of [enemy] the power of the units
     *  near both and the way there (land route length, or by sea - [shouldAttackBySea]); the 25 shortest routes are scored
     *  ([scoreTarget]) and one of the best quarter chosen by weight.
     *  @return the target and its score, null (call off the attack) if there is none
     */
    fun findBestAttackTarget(civ: Civilization, type: AiOperationType, enemy: Civilization): Pair<MilitaryTarget, Int>? {
        val steps = civ.ai.military.steps
        val ourPower = civ.cities.associateWith { nearbyPower(civ, it) }
        val theirCities = enemy.cities.filter { it.getCenterTile().isExplored(civ) }
        if (theirCities.isEmpty()) return null
        val theirPower = theirCities.associateWith { nearbyPower(enemy, it) }
        val theirTiles = theirCities.map { it.getCenterTile() }

        val preliminary = ArrayList<Pair<MilitaryTarget, Int>>()
        for (friendly in civ.cities) {
            val lengths = steps.distances(civ, enemy, friendly.getCenterTile(), theirTiles)
            for ((index, target) in theirCities.withIndex()) {
                val candidate = MilitaryTarget(friendly, target)
                candidate.musterNearbyUnitPower = ourPower.getValue(friendly)
                candidate.targetNearbyUnitPower = theirPower.getValue(target)
                if (type == AiOperationType.PURE_NAVAL_CITY_ATTACK) {
                    candidate.attackBySea = true
                    if (friendly.isCoastal() && target.isCoastal())
                        candidate.pathLength = friendly.getCenterTile().aerialDistanceTo(target.getCenterTile())
                } else {
                    shouldAttackBySea(civ, candidate, lengths[index])
                    if (!friendly.isCoastal() && candidate.attackBySea) continue
                }
                if (candidate.pathLength > 0) preliminary.add(candidate to 10000 - candidate.pathLength)
            }
        }
        preliminary.sortByDescending { it.second }  // stable: ties keep our city / their city order, as the original's stable sort

        val scored = ArrayList<Pair<MilitaryTarget, Int>>()
        for ((target, _) in preliminary) {
            if (scored.size >= 25) break
            if (target.attackBySea) {
                if (!target.musterCity.isCoastal() || !target.targetCity.isCoastal()) continue
                val nearMuster = coastalPlotAdjacentToTarget(target.musterCity.getCenterTile()) ?: continue
                val nearTarget = coastalPlotAdjacentToTarget(target.targetCity.getCenterTile()) ?: continue
                if (!steps.seaPathExists(civ, enemy, nearMuster, nearTarget)) continue
            }
            scored.add(target to scoreTarget(civ, target, type))
        }
        if (scored.isEmpty()) return null
        scored.sortByDescending { it.second }
        if (scored.sumOf { it.second } <= 0) return null
        val numChoices = max(1, scored.size * 25 / 100)
        val chosen = WonderProductionAi.chooseFromTopChoices(civ, scored, numChoices, "AttackTarget" + enemy.civID)
        return chosen to scoreTarget(civ, chosen, type)
    }

    /**
     *  CvMilitaryAI::ShouldAttackBySea. [landLength] is the step length of the land route from the muster to the target city (-1 = none).
     *  With embarkation known: by sea on different landmasses, without a land route, or when the route is over twice the straight distance;
     *  without it a missing route calls the attack off (path length -1).
     */
    fun shouldAttackBySea(civ: Civilization, target: MilitaryTarget, landLength: Int) {
        val straight = target.musterCity.getCenterTile().aerialDistanceTo(target.targetCity.getCenterTile())
        if (civ.tech.unitsCanEmbark) {
            val differentLandmasses = target.musterCity.getCenterTile().getContinent() != target.targetCity.getCenterTile().getContinent()
            if (differentLandmasses || landLength < 0 || landLength > 2 * straight) {
                target.attackBySea = true
                target.pathLength = straight
                return
            }
        } else if (landLength < 0) {
            target.pathLength = -1
            return
        }
        target.attackBySea = false
        target.pathLength = landLength
    }

    /** CvMilitaryAI::ScoreTarget: high = more desirable (path, approaches, force ratio, the city's worth) */
    fun scoreTarget(civ: Civilization, target: MilitaryTarget, type: AiOperationType): Int {
        val rules = civ.gameInfo.ruleset.ai
        var value = 1L
        if (!target.attackBySea) {
            value *= when {
                target.pathLength < 10 -> 16
                target.pathLength < 15 -> 8
                target.pathLength < 20 -> 4
                else -> 2
            }
            // Double if the troops can be assembled in the muster city by airlift
            if (target.musterCity.canAirlift()) value *= 2
        } else {
            value *= when {
                target.pathLength < 12 -> 5
                target.pathLength < 20 -> 3
                target.pathLength < 30 -> 2
                else -> 1
            }
            // Coming over sea, inland cities are trickier
            if (!target.targetCity.getCenterTile().isAdjacentToCoast()) value /= 2
        }
        // A sneak attack wants the edge of the empire: cube the distance factor
        if (type == AiOperationType.SNEAK_CITY_ATTACK || type == AiOperationType.NAVAL_SNEAK_ATTACK) value = value * value * value

        value *= evaluateMilitaryApproaches(target.targetCity, attackByLand = true, attackBySea = target.attackBySea).multiplier

        // Don't want to start at a city that isn't connected to our capital
        if (!target.musterCity.isConnectedToCapital() && !target.musterCity.isCapital()) value /= 4

        val friendly = max(1, target.musterNearbyUnitPower)
        val enemyStrength = max(1, target.targetNearbyUnitPower + CityCombatant(target.targetCity).getCityStrength() * 2)
        value *= min(1000, friendly * 100 / enemyStrength)

        if (target.targetCity.isOriginalCapital) value = value * rules.define(AiDefine.AI_MILITARY_CAPTURING_ORIGINAL_CAPITAL) / 100
        if (target.targetCity.foundingCivObject == civ) value = value * rules.define(AiDefine.AI_MILITARY_RECAPTURING_OWN_CITY) / 100

        // Don't want it to already be targeted by an operation that's not well on its way
        if (civ.ai.operations.isCityAlreadyTargeted(target.targetCity, null, 50)) value /= 10
        value /= 1000

        val city = target.targetCity
        val stats = city.cityStats.currentCityStats
        var economic = 1L + city.population.population / 3
        for (stat in listOf(Stat.Food, Stat.Production, Stat.Science, Stat.Gold, Stat.Culture, Stat.Faith))
            economic += (stats[stat] * 100).toInt() / 10
        value *= economic
        value /= 10
        return min(10000000L, value and 0x7fffffffL).toInt()
    }

    /** CvMilitaryAI::EvaluateMilitaryApproaches: how many of the six tiles around [city] cannot be used to get at it */
    fun evaluateMilitaryApproaches(city: City, attackByLand: Boolean, attackBySea: Boolean): CityAttackApproach {
        var blocked = 0
        val center = city.getCenterTile()
        val neighbors = center.neighbors.toList()
        blocked += 6 - neighbors.size // the edge of the map
        for (neighbor in neighbors) {
            when {
                neighbor.isWater && neighbor.getBaseTerrain().isFreshwater -> blocked++ // no one coming over a lake
                neighbor.isWater && !attackBySea -> blocked++
                !neighbor.isWater && (!attackByLand || neighbor.isImpassible() || neighbor.baseTerrain == Constants.mountain) -> blocked++
            }
        }
        return when (blocked) {
            0 -> CityAttackApproach.UNRESTRICTED
            1, 2 -> CityAttackApproach.OPEN
            3 -> CityAttackApproach.NEUTRAL
            4 -> CityAttackApproach.LIMITED
            5 -> CityAttackApproach.RESTRICTED
            else -> CityAttackApproach.NONE
        }
    }

    /** CvMilitaryAI::GetCoastalPlotAdjacentToTarget without an army: the first shallow water tile next to [target] */
    fun coastalPlotAdjacentToTarget(target: Tile): Tile? =
        target.neighbors.firstOrNull { it.isWater && it.getBaseTerrain().isCoast }
}
