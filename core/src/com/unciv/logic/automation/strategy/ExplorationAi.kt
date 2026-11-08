package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType

/**
 *  The exploration part of CvEconomicAI that the homeland AI's explorers read: [scoreExplorePlot] (ScoreExplorePlot) and [ExplorationPlots]
 *  (UpdatePlots with its goody hut / barbarian camp assignments, GetUnitTargetGoodyPlot, ClearUnitTargetGoodyStepPlot).
 *
 *  Deviations: the original's pathfinder flags (MOVE_TERRITORY_NO_ENEMY, MOVE_UNITS_IGNORE_DANGER) have no counterpart, a path is
 *  [com.unciv.logic.map.mapunit.movement.UnitMovement.getShortestPath]; the original's `canSeePlot` is the line of sight of [Tile.getViewableTilesList].
 */
object ExplorationAi {
    private const val ADJACENCY_BONUS = 1
    private const val BAD_SCORE = 10
    private const val GOOD_SCORE = 100
    private const val REALLY_GOOD_SCORE = 200

    /**
     *  CvEconomicAI::ScoreExplorePlot: the value of standing on [plot] with sight [range] for [civ]. Every plot in range that [civ] has not
     *  revealed counts: 100 when no neighbor of it is revealed either; when one is, it is seen only if a revealed neighbor of it within
     *  range has a line of sight to it (beyond distance 1), and then it is worth by what it is (reading the terrain the civilization has not
     *  seen, as the original does): for a land unit 10 for mountains and water, 200 hills, 100 else; for a ship 10 for water and impassable
     *  features, 100 for mountains, hills and view-blocking terrain, 200 else. Each also gets (range - distance) for being near.
     */
    fun scoreExplorePlot(civ: Civilization, plot: Tile, range: Int, forShip: Boolean): Int {
        var result = 0
        // The tile and its disc, as the original's plotXYWithRangeCheck loop; the viewable tiles are only worked out when needed
        var viewable: List<Tile>? = null
        plot.forEachTileInDistance(range) { eval ->
            if (eval == plot || civ.hasExplored(eval)) return@forEachTileInDistance
            val distance = plot.aerialDistanceTo(eval)
            if (hasRevealedNeighbor(civ, eval)) {
                if (distance > 1) {
                    var viewBlocked = true
                    for (adjacent in eval.neighbors) {
                        if (!civ.hasExplored(adjacent) || plot.aerialDistanceTo(adjacent) > range) continue
                        // "this cheats, because we can't be sure that between the target and the viewer": the original sees it when it is in sight
                        val tilesInSight = viewable ?: plot.getViewableTilesList(range).also { viewable = it }
                        if (eval in tilesInSight) { viewBlocked = false; break }
                    }
                    if (viewBlocked) return@forEachTileInDistance
                }
                result += if (forShip) shipValue(eval) else landValue(eval)
            } else result += GOOD_SCORE
            result += (range - distance) * ADJACENCY_BONUS
        }
        return result
    }

    private fun hasRevealedNeighbor(civ: Civilization, tile: Tile): Boolean {
        for (neighbor in tile.neighbors) if (civ.hasExplored(neighbor)) return true
        return false
    }

    /** CvPlot::isMountain: the terrain or a feature of the tile forms chains, as the map generator's mountains do */
    private fun isMountain(tile: Tile) = tile.allTerrains.any { it.hasUnique(UniqueType.OccursInChains) }

    private fun blocksSight(tile: Tile) = tile.terrainFeatureObjects.any { it.hasUnique(UniqueType.BlocksLineOfSightAtSameElevation) }

    private fun shipValue(tile: Tile): Int = when {
        tile.isWater || tile.terrainFeatureObjects.any { it.impassable } -> BAD_SCORE
        isMountain(tile) || tile.isHill() || blocksSight(tile) -> GOOD_SCORE
        else -> REALLY_GOOD_SCORE
    }

    private fun landValue(tile: Tile): Int = when {
        isMountain(tile) || tile.isWater -> BAD_SCORE
        tile.isHill() -> REALLY_GOOD_SCORE
        else -> GOOD_SCORE
    }
}

/**
 *  CvEconomicAI::UpdatePlots for one civilization: every revealed plot whose [ExplorationAi.scoreExplorePlot] with range 1 is positive
 *  ([plots] with their [ratings], domain by the plot being water), the revealed goody huts without a visible enemy unit and the barbarian
 *  camps with no defender ([goodyPlots]), and the assignment of [explorers] to them: with at least as many explorers as plots each plot
 *  takes the explorer that would arrive first (AssignExplorersToHuts), else each
 *  explorer takes the plot it would reach first (AssignHutsToExplorers).
 */
class ExplorationPlots(private val civ: Civilization, explorers: List<MapUnit>) {
    val plots = ArrayList<Tile>()
    val ratings = ArrayList<Int>()
    val goodyPlots = ArrayList<Tile>()
    private val assignedUnitIds: IntArray

    init {
        timeThis("AI.ExplorationPlots") {
            val ruleset = civ.gameInfo.ruleset
            val countsGoodies = !civ.isCityState && !civ.isBarbarian
            for (tile in civ.gameInfo.tileMap.values) {
                if (!civ.hasExplored(tile)) continue
                if (countsGoodies) {
                    if (ruleset.tileImprovements[tile.improvement]?.isAncientRuinsEquivalent() == true && !hasVisibleEnemyUnit(tile)) goodyPlots.add(tile)
                    else if (tile.isBarbarianEncampment() && tile.militaryUnit == null) goodyPlots.add(tile)
                }
                if (!hasUnexploredNeighbor(tile)) continue // nothing in range 1 to count
                val score = ExplorationAi.scoreExplorePlot(civ, tile, 1, tile.isWater)
                if (score <= 0) continue
                plots.add(tile)
                ratings.add(score)
            }
        }
        assignedUnitIds = IntArray(goodyPlots.size) { -1 }
        if (goodyPlots.isNotEmpty() && explorers.isNotEmpty()) {
            if (explorers.size >= goodyPlots.size) assignExplorersToHuts(explorers) else assignHutsToExplorers(explorers)
        }
    }

    private fun hasUnexploredNeighbor(tile: Tile): Boolean {
        for (neighbor in tile.neighbors) if (!civ.hasExplored(neighbor)) return true
        return false
    }

    private fun hasVisibleEnemyUnit(tile: Tile): Boolean =
        tile.getUnits().any { it.civ != civ && civ.isAtWarWith(it.civ) && it.isVisibleTo(civ) }

    /** Turns estimated from the hex distance and the movement points; [Int.MAX_VALUE] for a unit that cannot move */
    private fun estimateTurns(unit: MapUnit, tile: Tile): Int {
        val moves = unit.getMaxMovement()
        return if (moves >= 1) unit.getTile().aerialDistanceTo(tile) / moves else Int.MAX_VALUE
    }

    /** CvEconomicAI::AssignExplorersToHuts (PATH_PLAN_LAST): per hut, the explorers by estimated turns, the first with a path takes it */
    private fun assignExplorersToHuts(explorers: List<MapUnit>) {
        val free = ArrayList(explorers)
        for ((index, goody) in goodyPlots.withIndex()) {
            val byTurns = free.filter { it.getMaxMovement() >= 1 }.sortedBy { estimateTurns(it, goody) }
            for (unit in byTurns) {
                val path = unit.movement.getShortestPath(goody)
                if (path.isEmpty()) continue
                assignedUnitIds[index] = unit.id
                free.remove(unit)
                break
            }
        }
    }

    /** CvEconomicAI::AssignHutsToExplorers: per explorer, the hut with the fewest estimated turns (the first of equals) it has a path to */
    private fun assignHutsToExplorers(explorers: List<MapUnit>) {
        val freeHuts = goodyPlots.indices.toMutableList()
        for (unit in explorers) {
            var bestHut = -1
            var bestTurns = Int.MAX_VALUE
            for (index in freeHuts) {
                val goody = goodyPlots[index]
                val turns = estimateTurns(unit, goody)
                if (turns < bestTurns && unit.movement.getShortestPath(goody).isNotEmpty()) { bestTurns = turns; bestHut = index }
            }
            if (bestHut == -1) continue
            assignedUnitIds[bestHut] = unit.id
            freeHuts.remove(bestHut)
        }
    }

    /** CvEconomicAI::GetUnitTargetGoodyPlot: the hut or camp assigned to [unit] (the original also hands out the plot where the path ends the turn; [MapUnit.movement] finds that again) */
    fun targetGoodyPlot(unit: MapUnit): Tile? {
        val index = assignedUnitIds.indexOf(unit.id)
        return if (index < 0) null else goodyPlots[index]
    }
}
