package com.unciv.logic.automation.unit

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.ArchaeologyFunctions
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType
import java.util.WeakHashMap

/**
 * Archaeologists (CvHomelandAI::PlotArchaeologistMoves / ExecuteArchaeologistMoves / FindArchaeologistTarget, CvEconomicAI::DisbandExtraArchaeologists).
 * Each turn the sites are listed once per civilization; every archaeologist in turn takes the safe site it reaches in the fewest turns, which
 * no other archaeologist can then take. The dig itself decides what to do with the finds ([ArchaeologyFunctions.chooseForAi]).
 */
object ArchaeologistAutomation {

    /** The sites still free this turn (CvHomelandAI::m_TargetedAntiquitySites) and which unit took which, per civilization */
    private class TurnTargets(val turn: Int, val free: ArrayList<Tile>) {
        val taken = HashMap<Int, Tile>()
    }

    private val targetsByCiv = WeakHashMap<Civilization, TurnTargets>()

    /** @return whether the unit has been dealt with this turn */
    fun automate(unit: MapUnit): Boolean = timeThis("AI.archaeologistMoves") {
        if (!unit.hasUnique(UniqueType.CanExcavate)) return false
        val civ = unit.civ
        val constants = civ.gameInfo.ruleset.modOptions.constants
        val tile = unit.getTile()

        // A dig that is complete and waits for a choice, which the AI normally makes at once
        if (ArchaeologyFunctions.hasCompletedDig(tile)) {
            ArchaeologyFunctions.execute(civ, tile, ArchaeologyFunctions.chooseForAi(civ, tile,
                ArchaeologyFunctions.countOtherArchaeologists(civ, unit), ArchaeologyFunctions.getVisibleSites(civ).size), unit)
            return true
        }

        val dig = civ.gameInfo.ruleset.tileImprovements[constants.archaeologicalDigImprovement] ?: return false
        if (tile.improvementInProgress == dig.name) return true // keep working
        if (!unit.hasMovement()) return true

        val target = findTarget(unit) ?: return false
        if (tile != target) unit.movement.headTowards(target)
        if (unit.getTile() == target && unit.canBuildImprovement(dig, target))
            target.startWorkingOnImprovement(dig, civ, unit)
        return true
    }

    /**
     * CvHomelandAI::FindArchaeologistTarget: the site without danger that [unit] reaches in the fewest turns, the first in map order on a tie.
     * It is then no longer available to the other archaeologists of the civilization this turn.
     */
    private fun findTarget(unit: MapUnit): Tile? {
        val targets = getTurnTargets(unit.civ)
        targets.taken[unit.id]?.let { return it }
        var best: Tile? = null
        var bestTurns = Int.MAX_VALUE
        for (site in targets.free) {
            if (isInDanger(unit.civ, site)) continue
            val turns = turnsToReach(unit, site)
            if (turns < bestTurns) {
                best = site
                bestTurns = turns
            }
        }
        if (best != null) {
            targets.free.remove(best)
            targets.taken[unit.id] = best
        }
        return best
    }

    /**
     * CvHomelandAI::FindHomelandTargets, antiquity sites: the tiles we see now with a site we know of, not in the lands of a civilization we promised
     * not to dig in. Sites one of our archaeologists is digging already are left out, as a second one would add nothing in Unciv.
     */
    private fun getTurnTargets(civ: Civilization): TurnTargets {
        val turn = civ.gameInfo.turns
        targetsByCiv[civ]?.let { if (it.turn == turn) return it }
        val digName = civ.gameInfo.ruleset.modOptions.constants.archaeologicalDigImprovement
        val free = ArrayList<Tile>()
        for (site in ArchaeologyFunctions.getVisibleSites(civ)) {
            if (site !in civ.viewableTiles) continue
            if (site.improvementInProgress == digName && site.getUnits().any { it.civ == civ && it.hasUnique(UniqueType.CanExcavate) }) continue
            val owner = site.getOwner()
            if (owner != null && owner != civ && owner.getDiplomacyManager(civ)?.hasFlag(DiplomacyFlags.AgreedToNotDigInOurLands) == true) continue
            free.add(site)
        }
        return TurnTargets(turn, free).also { targetsByCiv[civ] = it }
    }

    /** CvHomelandAI::TurnsToReachTarget: turns of the shortest path, [Int.MAX_VALUE] if there is none */
    private fun turnsToReach(unit: MapUnit, target: Tile): Int {
        if (unit.getTile() == target) return 0
        val path = unit.movement.getShortestPath(target)
        return if (path.isEmpty()) Int.MAX_VALUE else path.size
    }

    /**
     * CvPlayer::GetPlotDanger > 0: a military unit we can see of a civilization we are at war with could attack the tile next turn
     * (its movement plus its range, 1 for melee), or the tile is within the range of a city of such a civilization.
     */
    private fun isInDanger(civ: Civilization, tile: Tile): Boolean {
        for (enemy in civ.gameInfo.civilizations) {
            if (enemy == civ || !civ.isAtWarWith(enemy)) continue
            for (city in enemy.cities)
                if (city.getCenterTile().aerialDistanceTo(tile) <= city.getBombardRange()) return true
            for (enemyUnit in enemy.units.getCivUnits()) {
                if (enemyUnit.isCivilian() || enemyUnit.getTile() !in civ.viewableTiles) continue
                val reach = enemyUnit.getMaxMovement() + (if (enemyUnit.baseUnit.isRanged()) enemyUnit.getRange() else 1)
                if (enemyUnit.getTile().aerialDistanceTo(tile) <= reach) return true
            }
        }
        return false
    }

    /**
     * CvEconomicAI::DisbandExtraArchaeologists: scraps the first archaeologist when there are more than half the sites on the map plus one
     * (the hidden sites count once the civilization can see them). Archaeologists in production count too.
     */
    fun disbandExtraArchaeologists(civ: Civilization) {
        val gameInfo = civ.gameInfo
        val constants = gameInfo.ruleset.modOptions.constants
        val hiddenResource = gameInfo.ruleset.tileResources[constants.archaeologyHiddenSiteResource]
        // CvGame::GetNumArchaeologySites and GetNumHiddenArchaeologySites are -1 before the sites appear
        var numSites = if (gameInfo.archaeologyTriggered) gameInfo.tileMap.values.count { it.resource == constants.archaeologySiteResource } else -1
        if (hiddenResource != null && civ.canSeeResource(hiddenResource))
            numSites += if (gameInfo.archaeologyTriggered) gameInfo.tileMap.values.count { it.resource == hiddenResource.name } else -1
        val numArchaeologists = ArchaeologyFunctions.countOtherArchaeologists(civ, null)
        if (numSites * 0.5 + 1 >= numArchaeologists) return
        civ.units.getCivUnits().firstOrNull { it.hasUnique(UniqueType.CanExcavate) }?.disband()
    }
}
