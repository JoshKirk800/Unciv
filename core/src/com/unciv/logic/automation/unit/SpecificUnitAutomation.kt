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

    fun automateCitadelPlacer(unitView: MapUnitView): Boolean {
        val unit = unitView.getUnit()
        // Keep at least 2 generals alive
        if (unit.hasUnique(UniqueType.StrengthBonusInRadius) 
                && unit.civ.units.getCivUnits().count { it.hasUnique(UniqueType.StrengthBonusInRadius) } < 3) 
            return false
        // try to revenge and capture their tiles
        val enemyCities = unit.civ.getKnownCivs()
                .filter { unit.civ.getDiplomacyManager(it)!!.hasModifier(DiplomaticModifiers.StealingTerritory) }
                .flatMap { it.cities }
        // find the suitable tiles (or their neighbours)
        val tileToSteal = enemyCities.flatMap { it.getTiles() } // City tiles
                .filter { it.neighbors.any { tile -> tile.getOwner() != unit.civ } } // Edge city tiles
                .flatMap { it.neighbors } // Neighbors of edge city tiles
                .filter {
                    it in unit.civ.viewableTiles // we can see them
                            && it.neighbors.any { tile -> tile.getOwner() == unit.civ }// they are close to our borders
                }
                .sortedBy { it.aerialDistanceTo(unit.currentTile) } // get closest tiles
                .firstOrNull { unit.movement.canReach(it) } // canReach is performance-heavy and always a last resort
        // if there is a good tile to steal - go there
        if (tileToSteal != null) {
            unit.movement.headTowards(tileToSteal)
            if (unit.hasMovement() && unit.currentTile == tileToSteal)
                UnitActionsFromUniques.getImprovementConstructionActionsFromGeneralUnique(unit, unit.currentTile).firstOrNull()?.action?.invoke()
            return true
        }

        // keep in mind that citadels can be stolen by other citadels.
        // you need 1 more general than your opponent, or be able to place it on a totally unreachable tile,
        // for it to make sense to make the first move as the defender.
        // so, let's focus only on the stealing part for now (the old defensive citadel placement logic turned out negative in simulation result)

        return false
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

    /** @return whether there was any progress in conducting the trade mission. A return value of
     * `false` can be interpreted as: the unit doesn't know where to go or there are no city
     * states. */
    fun conductTradeMission(unitView: MapUnitView): Boolean {
        if (unitView.tryInvokeUnitAction(UnitActionType.ConductTradeMission)) return true

        val civ = unitView.civ()
        val unitTile = unitView.getTile()
        val relevantTiles = civ.getKnownCivs().asSequence()
            .filter { it.isCityState && !it.isAtWarWith(civ) }
            .mapNotNull { it.cities().firstOrNull() }
            .flatMap { it.getTiles() }
            .filter { it.isExplored() }
            .sortedBy { unitTile.aerialDistanceTo(it) }
            .toList()

        val closestCityStateTile = relevantTiles.firstOrNull { tile ->
            if (!unitView.canPassThrough(tile)) return@firstOrNull false
            val path = unitView.getShortestPath(tile)
            // 0 is unreachable, 10 is too far away
            path.size in 1..10
        }
        if (closestCityStateTile == null) return false

        unitView.tryHeadTowards(closestCityStateTile)

        return unitTile != unitView.getTile()
    }

    /**
     * If there's a city nearby that can construct a wonder, walk there an get it built. Typically I
     * like to build all wonders in the same city to have the bonuses accumulate (and it typically ends
     * up being my capital), but that would need too much logic (e.g. how far away is the capital,
     * is the wonder likely still available by the time I'm there, is this particular wonder even
     * buildable in the capital, etc.)
     *
     * @return whether there was any progress in speeding up a wonder construction. A return value
     * of `false` can be interpreted as: the unit doesn't know where to go or is stuck. */
    fun speedupWonderConstruction(unit: MapUnit): Boolean {
        val nearbyCityWithAvailableWonders = unit.civ.cities.filter { city ->
            // Don't speed up construction in small cities. There's a risk the great
            // engineer can't get it done entirely and then it takes forever for the small
            // city to finish the rest.
            city.population.population >= 3 &&
            (unit.movement.canMoveTo(city.getCenterTile()) || unit.currentTile == city.getCenterTile())
                    && getWonderThatWouldBenefitFromBeingSpedUp(city) != null
        }.mapNotNull { city ->
            val path = unit.movement.getShortestPath(city.getCenterTile())
            if (path.any() && path.size <= 5) city to path.size else null
        }.minByOrNull { it.second }
            ?.first ?: return false

        if (unit.currentTile == nearbyCityWithAvailableWonders.getCenterTile()) {
            val wonderToHurry =
                    getWonderThatWouldBenefitFromBeingSpedUp(nearbyCityWithAvailableWonders)!!
            nearbyCityWithAvailableWonders.cityConstructions.editQueue { add(0, wonderToHurry.name) }
            return UnitActions.invokeUnitAction(unit, UnitActionType.HurryBuilding)
                || UnitActions.invokeUnitAction(unit, UnitActionType.HurryWonder)
        }

        // Walk towards the city.
        val tileBeforeMoving = unit.getTile()
        unit.movement.headTowards(nearbyCityWithAvailableWonders.getCenterTile())
        return tileBeforeMoving != unit.currentTile
    }

    @Readonly
    private fun getWonderThatWouldBenefitFromBeingSpedUp(city: City): Building? {
        return city.cityConstructions.getBuildableBuildings().filter { building ->
            building.isWonder && !building.hasUnique(UniqueType.CannotBeHurried)
                    && city.cityConstructions.turnsToConstruction(building.name) >= 5
        }.minByOrNull { -city.cityConstructions.getRemainingWork(it.name) }
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
