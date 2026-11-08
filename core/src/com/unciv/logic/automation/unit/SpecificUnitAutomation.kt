package com.unciv.logic.automation.unit

import com.unciv.logic.city.City
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.logic.map.mapunit.actions.UnitActionsFromUniques
import com.unciv.view.MapUnitView

object SpecificUnitAutomation {

    /**
     *  CvHomelandAI::ExecuteWorkerMove for a Great Person directed to construct its improvement: the builder directive of
     *  [BuilderTaskingAi.evaluateBuilder] over the improvements the unit creates; it moves to the plot, or creates the improvement there.
     *  @return whether there was a directive; `false`: the unit knows no plot for its improvement
     */
    fun automateImprovementPlacer(unit: MapUnit): Boolean {
        if (unit.getMatchingUniques(UniqueType.ConstructImprovementInstantly).none()) return false
        return unit.civ.getWorkerAutomation().executeWorkerMove(unit)
    }

    fun automateAddInCapital(unit: MapUnitView) {
        val capitalTile = unit.civ().cities().firstOrNull { it.isCapital() }?.getCenterTile() ?: return // safeguard
        if (unit.canReach(capitalTile))
            unit.tryHeadTowards(capitalTile)
        if (unit.canSwapTo(capitalTile))
            unit.trySwapMoveToTile(capitalTile)
        if (unit.getTile() == capitalTile) {
            unit.tryInvokeUnitAction(UnitActionType.AddInCapital)
        }
    }

}
