package com.unciv.logic.automation.unit

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.ArchaeologyFunctions
import com.unciv.models.ruleset.unique.UniqueType

/** An archaeologist walks to the nearest site it can see, digs there, and then (the dig decides this by itself) chooses what to do with the finds */
object ArchaeologistAutomation {

    /** @return whether the unit has been dealt with this turn */
    fun automate(unit: MapUnit): Boolean {
        if (!unit.hasUnique(UniqueType.CanExcavate)) return false
        val civ = unit.civ
        val constants = civ.gameInfo.ruleset.modOptions.constants
        val tile = unit.getTile()

        // A dig that is complete and waits for a choice, which the AI normally makes at once
        if (ArchaeologyFunctions.hasCompletedDig(tile)) {
            val others = civ.units.getCivUnits().count { it.hasUnique(UniqueType.CanExcavate) && it != unit }
            ArchaeologyFunctions.execute(civ, tile, ArchaeologyFunctions.chooseForAi(civ, tile, others, ArchaeologyFunctions.getVisibleSites(civ).size), unit)
            return true
        }

        val dig = civ.gameInfo.ruleset.tileImprovements[constants.archaeologicalDigImprovement] ?: return false
        if (tile.improvementInProgress == dig.name) return true // keep working
        if (!unit.hasMovement()) return true

        if (tile in ArchaeologyFunctions.getVisibleSites(civ) && unit.canBuildImprovement(dig, tile)) {
            tile.startWorkingOnImprovement(dig, civ, unit)
            return true
        }

        val target = ArchaeologyFunctions.getVisibleSites(civ)
            .filter { unit.movement.canReach(it) }
            .minByOrNull { it.aerialDistanceTo(tile) } ?: return false
        unit.movement.headTowards(target)
        return true
    }
}
