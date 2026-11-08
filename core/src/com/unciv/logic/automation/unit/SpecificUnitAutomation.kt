package com.unciv.logic.automation.unit

import com.unciv.view.TileView
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
import yairm210.purity.annotations.Readonly

object SpecificUnitAutomation {

    fun automateGreatGeneral(unitView: MapUnitView): Boolean {
        //try to follow nearby units. Do not garrison in city if possible
        val maxAffectedTroopsTile = getBestAffectedTroopsTile(unitView)
            ?: return false

        unitView.tryHeadTowards(maxAffectedTroopsTile)
        return true
    }

    /**
     * Find a tile for accompanying a military unit where the total bonus for all affected units is maximized.
     */
    @Readonly
    private fun getBestAffectedTroopsTile(general: MapUnitView): TileView? {
        // Normally we have only one Unique here. But a mix is not forbidden,
        // (imagine several GreatGeneralAura uniques - +50% at radius 1, +25% at radius 2, +5% at radius 3 - possibly learnable from promotions via buildings or natural wonders?)
        // However, rang-1 generals are difficult to use fully without getting it killed, so it's probably best placed to support 2nd and 3rd row ranged units
        // a 3 or more range generals are less sensitive to positioning, and thus probably suffice with 2-range general logic

        val civ = general.civ()
        val militaryUnitTilesInDistance = general.getDistanceToTiles().keys
            .filter { tile ->
                tile.militaryUnit?.civ() == civ
                    && (tile.civilianUnit == null || tile.civilianUnit == general)
            }

        // Temporary bridge: getEnemyCitiesByPriority still returns raw cities
        val closestReachableEnemyCityTile = HeadTowardsEnemyCityAutomation.getEnemyCitiesByPriority(general)
            .map { general.getTile().getTileMap().getTile(it.getCenterTile()) }
            .firstOrNull { general.canReach(it) }
        // Send generals to the same place as our units, so they don't get stuck at the wrong side of our empire. Update this when changing global unit movement

        val militaryUnitTile = militaryUnitTilesInDistance.maxByOrNull { unitTile ->
            val nearbyTiles = unitTile.getExploredTilesInDistance(2).toList()
            (2 * nearbyTiles.count { it.militaryUnit?.civ() == civ }
                - nearbyTiles.count { it.militaryUnit?.civ() != civ }
                - if (closestReachableEnemyCityTile != null) 3 * unitTile.aerialDistanceTo(closestReachableEnemyCityTile) else 0)
            // Scoring here is found to help AI defeat former AI,
            // a more robust scoring may be necessary to avoid leaving generals en-prise,
            // and improve handling of multiple generals, e.g. use one to support bomber stacks and the other for frontline troops.
        }
        return militaryUnitTile
    }

    /**
     *  CvHomelandAI::ExecuteWorkerMove for a Great Person directed to construct its improvement: the builder directive of
     *  [BuilderTaskingAi.evaluateBuilder] over the improvements the unit creates; it moves to the plot, or creates the improvement there.
     *  @param isDangerous the plots the unit must avoid (the original's plot danger)
     *  @return whether there was a directive; `false`: the unit knows no plot for its improvement
     */
    fun automateImprovementPlacer(unit: MapUnit, isDangerous: (Tile) -> Boolean = { false }): Boolean {
        if (unit.getMatchingUniques(UniqueType.ConstructImprovementInstantly).none()) return false
        return unit.civ.getWorkerAutomation().executeWorkerMove(unit, isDangerous)
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
