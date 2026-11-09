package com.unciv.logic.map.mapunit.actions

import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitAction
import com.unciv.models.UnitActionType

/**
 *  The airlift button (INTERFACEMODE_AIRLIFT of CvUnit::canAirlift): offered to a unit that [com.unciv.logic.map.mapunit.movement.UnitMovement.canAirliftFromHere],
 *  enabled while some city of ours has a tile it can be airlifted to. Like the paradrop it then waits for the player to choose a tile, the move itself
 *  is [com.unciv.logic.map.mapunit.movement.UnitMovement.airliftTo].
 */
object UnitActionsAirlift {
    internal fun getActions(unit: MapUnit, tile: Tile): Sequence<UnitAction> {
        if (!unit.movement.canAirliftFromHere()) return emptySequence()
        return sequenceOf(UnitAction(UnitActionType.Airlift,
            isCurrentAction = unit.isPreparingAirlift(),
            action = {
                if (unit.isPreparingAirlift()) unit.action = null
                else unit.action = UnitActionType.Airlift.value
            }.takeIf { unit.movement.getAirliftDestinations().any() }))
    }
}
