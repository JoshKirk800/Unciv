package com.unciv.logic.automation.unit

import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionModifiers
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType

/** Great Persons that can create a Great Work do so as soon as there is a place for it, which beats their other uses */
object GreatWorkAutomation {

    /** @return whether the unit has been dealt with this turn: it created its work, or is on its way to a city with a free slot */
    fun automate(unit: MapUnit): Boolean {
        val unique = unit.getMatchingUniques(UniqueType.OneTimeCreateGreatWork).firstOrNull() ?: return false
        val definition = GreatWorkFunctions.getWorkOfUnit(unit) ?: return false
        val civ = unit.civ
        val slotType = definition.getSlotType()
        if (GreatWorkFunctions.getNumFreeSlots(civ, slotType) == 0) return false
        if (!unit.hasMovement()) return true

        if (GreatWorkFunctions.canCreateWork(unit)) {
            if (!UniqueTriggerActivation.triggerUnique(unique, civ, unit = unit, tile = unit.currentTile)) return false
            UnitActionModifiers.activateSideEffects(unit, unique)
            return true
        }

        val city = civ.cities.filter { it.greatWorks.getFreeSlot(slotType) != null && unit.movement.canReach(it.getCenterTile()) }
            .minByOrNull { it.getCenterTile().aerialDistanceTo(unit.getTile()) } ?: return false
        unit.movement.headTowards(city.getCenterTile())
        return true
    }
}
