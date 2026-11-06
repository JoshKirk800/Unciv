package com.unciv.logic.automation.unit

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionModifiers
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType

/** A Great Musician goes on tour where it helps most: to the civilization we are least influential over, and plays as soon as it is in its territory */
object ConcertTourAutomation {
    private const val MAX_TILES_TRIED = 5

    /** @return whether the unit has been dealt with this turn */
    fun automate(unit: MapUnit): Boolean {
        val unique = unit.getMatchingUniques(UniqueType.OneTimeConcertTour).firstOrNull() ?: return false
        val civ = unit.civ
        val limit = civ.gameInfo.ruleset.modOptions.constants.influencePercentInfluential
        val targets = civ.culture.getOtherMajorCivs()
            .filter { civ.culture.canInfluence(it) && civ.culture.getInfluencePercent(it) < limit }
            .sortedBy { civ.culture.getInfluencePercent(it) }
        if (targets.isEmpty()) return false
        if (!unit.hasMovement()) return true

        if (unit.getTile().getOwner() in targets
            && UniqueTriggerActivation.triggerUnique(unique, civ, unit = unit, tile = unit.currentTile)) {
            UnitActionModifiers.activateSideEffects(unit, unique)
            return true
        }

        for (target in targets) {
            val destination = target.cities.flatMap { it.getTiles() }
                .sortedBy { it.aerialDistanceTo(unit.getTile()) }
                .take(MAX_TILES_TRIED)
                .firstOrNull { unit.movement.canReach(it) } ?: continue
            unit.movement.headTowards(destination)
            return true
        }
        return false
    }
}
