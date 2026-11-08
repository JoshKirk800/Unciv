package com.unciv.logic.automation.unit

import com.unciv.view.TileView
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.Automation
import com.unciv.logic.automation.unit.CivilianUnitAutomation.tryRunAwayIfNeccessary
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.tile.TerrainType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.logic.map.mapunit.actions.UnitActionsFromUniques
import com.unciv.view.GameView
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

    fun automateSettlerActions(unitView: MapUnitView, dangerousTiles: HashSet<TileView>, cache: AutomationCache) {
        val unit = unitView.getUnit()
        // City-state starts are predetermined by map gen / editor — trust that tile by default.
        // Mods can opt out via CityStatesSearchForFirstCitySite.
        if (unit.civ.isCityState && unit.civ.cities.isEmpty()
            && !unit.civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.CityStatesSearchForFirstCitySite)
        ) {
            if (unitView.tryInvokeUnitAction(UnitActionType.FoundCity)) return
            // Cannot found here (blocked / invalid) — fall through to normal search.
        }

        // If we don't have any cities, we are probably at the start of the game with only one settler
        // If we are at the start of the game lets spend a maximum of 3 turns to settle our first city
        // As our turns progress lets shrink the area that we look at to make sure that we stay on target
        // If we have gone more than 3 turns without founding a city lets search a wider area
        // TODO: Figure out a way to not use turns as it might not be the best metric, what if we are a civ starting in the middle of a game?
        val rangeToSearch = if (unit.civ.cities.isEmpty() && unit.civ.gameInfo.turns < 4) (3 - unit.civ.gameInfo.turns).coerceAtLeast(1) else null

        // It's possible that we'll see a tile "over the sea" that's better than the tiles close by, but that's not a reason to abandon the close tiles!
        // Also this lead to some routing problems, see https://github.com/yairm210/Unciv/issues/3653
        val minimumTileValue = unit.civ.gameInfo.ruleset.modOptions.constants.minimumCityLocationTileValue // To prevent AI from settling snow regions, could use a better implementation
        val bestTilesInfo = CityLocationTileRanker.getBestTilesToFoundCity(unit, rangeToSearch, minimumTileValue)
        var bestCityLocation: Tile? = null

        if (unit.civ.gameInfo.turns == 0 && unit.civ.cities.isEmpty() && bestTilesInfo.tileRankMap.containsKey(unit.getTile())) {   // Special case, we want AI to settle in place on turn 1.
            // Depending on era and difficulty we might start with more than one settler. In that case settle the one with the best location
            val allUnsettledSettlers = unit.civ.units.getCivUnits().filter { it.hasMovement() && it.baseUnit == unit.baseUnit }

            // Don't settle immediately if we only have one settler, look for a better location
            val bestSettlerInRange = allUnsettledSettlers.maxByOrNull {
                if (bestTilesInfo.tileRankMap.containsKey(it.getTile()))
                    bestTilesInfo.tileRankMap[it.getTile()]!!
                else -1f
            }
            if (bestSettlerInRange == unit && unitView.tryInvokeUnitAction(UnitActionType.FoundCity)) return
            // Since this settler is not in the best location, lets assume the best settler will found their city where they are
            if (bestSettlerInRange != null)
                bestTilesInfo.tileRankMap = HashMap(bestTilesInfo.tileRankMap.filter { it.key.aerialDistanceTo(bestSettlerInRange.getTile()) > 4 })
        }


        // If the tile we are currently on is close to the best tile, then lets just settle here instead
        if (bestTilesInfo.tileRankMap.containsKey(unit.getTile())
                && (bestTilesInfo.bestTile == null || bestTilesInfo.tileRankMap[unit.getTile()]!! >= bestTilesInfo.bestTileRank - 2)) {
                bestCityLocation = unit.getTile()
        }

        //Shortcut, if the best tile is nearby than lets just take it
        if (bestCityLocation == null && bestTilesInfo.bestTile != null && unit.movement.getShortestPath(bestTilesInfo.bestTile!!).size in 1..3) {
            bestCityLocation = bestTilesInfo.bestTile
        }

        if (bestCityLocation == null) {
            // Find the best tile that is within
            @Readonly
            fun isTileRankOK(it: Map.Entry<Tile, Float>): Boolean {
                if (unitView.getTile().getTileMap().getTile(it.key) in dangerousTiles && it.key != unit.getTile()) return false
                val pathSize = unit.movement.getShortestPath(it.key).size
                return pathSize in 1..3
            }

            bestCityLocation = bestTilesInfo.tileRankMap.entries.asSequence()
                .filter { bestTilesInfo.bestTile == null || it.value >= bestTilesInfo.bestTileRank - 5 }
                .sortedByDescending { it.value }
                .firstOrNull(::isTileRankOK)
                ?.key
        }

        // We still haven't found a best city tile within 3 turns so lets just head to the best tile
        // Note that we must check that the shortest path exists or else an error will be thrown
        if (bestCityLocation == null && bestTilesInfo.bestTile != null && unit.movement.getShortestPath(bestTilesInfo.bestTile!!).size in 1..8) {
            bestCityLocation = bestTilesInfo.bestTile
        }

        if (bestCityLocation == null) { // We got a badass over here, all tiles within 5 are taken?
            // Try to move towards the frontier

            /** @return the number of tiles 4 (un-modded) out from this city that could hold a city, ie how lonely this city is */
            @Readonly
            fun getFrontierScore(city: City): Int {
                var frontierScore = 0
                city.getCenterTile().forEachTileAtDistance(city.civ.gameInfo.ruleset.modOptions.constants.minimalCityDistance + 1) {
                    if (it.canBeSettled(unit.civ)) frontierScore++
                }
                return frontierScore
            }

            val frontierCity = unit.civ.cities.maxByOrNull { getFrontierScore(it) }
            if (frontierCity != null && getFrontierScore(frontierCity) > 0  && unit.movement.canReach(frontierCity.getCenterTile()))
                unit.movement.headTowards(frontierCity.getCenterTile())
            if (UnitAutomation.tryExplore(unitView, cache)) return // try to find new areas
            UnitAutomation.wander(unitView, tilesToAvoid = dangerousTiles) // go around aimlessly
            return
        }

        val foundCityAction = UnitActionsFromUniques.getFoundCityAction(unit, bestCityLocation)
        if (foundCityAction?.action == null) { // this means either currentMove == 0 or city within 3 tiles
            if (unit.hasMovement() && !unit.civ.isOneCityChallenger()) // therefore, city within 3 tiles
                throw Exception("City within distance")
            return
        }

        val shouldSettle = (unit.getTile() == bestCityLocation && unit.hasMovement())
        if (shouldSettle) return foundCityAction.action.invoke()
        //Settle if we're already on the best tile, before looking if we should retreat from barbarians
        if (tryRunAwayIfNeccessary(unitView, cache)) return 
        unit.movement.headTowards(bestCityLocation)
        val shouldSettleNow = (unit.getTile() == bestCityLocation && unit.hasMovement())
        if (shouldSettleNow) foundCityAction.action.invoke() 
    }

    /** @return whether there was any progress in placing the improvement. A return value of `false`
     * can be interpreted as: the unit doesn't know where to place the improvement or is stuck. */
    fun automateImprovementPlacer(unit: MapUnit) : Boolean {
        val improvementBuildingUnique = unit.getMatchingUniques(UniqueType.ConstructImprovementInstantly).firstOrNull()
            ?: return false

        val improvementName = improvementBuildingUnique.params[0]
        val improvement = unit.civ.gameInfo.ruleset.tileImprovements[improvementName]
            ?: return false
        val relatedStat = improvement.maxByOrNull { it.value }?.key ?: Stat.Culture

        val citiesByStatBoost = unit.civ.cities.sortedByDescending {
            it.cityStats.statPercentBonusTree.totalStats[relatedStat]
        }

        val averageTerrainStatsValue = unit.civ.gameInfo.ruleset.terrains.values.asSequence()
            .filter { it.type == TerrainType.Land }
            .map { Automation.rankStatsValue(it, GameView(unit.civ.gameInfo, unit.civ).civView) }
            .average()

        for (city in citiesByStatBoost) {
            val applicableTiles = city.getWorkableTiles().filter {
                it.isLand && (it.tileResource?.isImprovedBy(improvementName) != false) && !it.isCityCenter()
                    && (unit.currentTile == it || unit.movement.canMoveTo(it))
                    // okay if we replace regular improvements by great improvements, but not the other way around
                    && (it.tileImprovement == null || !it.tileImprovement!!.hasUnique(UniqueType.GreatImprovement))
                    && it.improvementFunctions.canBuildImprovement(improvement, unit.cache.state)
                    && (!improvement.hasUnique(UniqueType.GreatImprovement) || Automation.rankStatsValue(it.getBaseTerrain().cloneStats(), GameView(unit.civ.gameInfo, unit.civ).civView) > averageTerrainStatsValue)
            }

            if (applicableTiles.none()) continue

            val pathToCity = unit.movement.getShortestPath(city.getCenterTile())

            if (pathToCity.isEmpty()) continue
            if (pathToCity.size > 2 && unit.getTile().getCity() != city) {
                // Radius 5 is quite arbitrary. Few units have such a high movement radius although
                // streets might modify it. Also there might be invisible units, so this is just an
                // approximation for relative safety and simplicity.
                val enemyUnitsNearby = unit.getTile().getTilesInDistance(5).any { tileNearby ->
                    tileNearby.getUnits().any { unitOnTileNearby ->
                        unitOnTileNearby.isMilitary() && unitOnTileNearby.civ.isAtWarWith(unit.civ)
                    }
                }
                // Don't move until you're accompanied by a military unit if there are enemies nearby.
                if (unit.getTile().militaryUnit == null && enemyUnitsNearby) return true
                unit.movement.headTowards(city.getCenterTile())
                return true
            }

            // if we got here, we're pretty close, start looking!
            val chosenTile = applicableTiles.sortedByDescending {
                Automation.rankTile(
                    it,
                    GameView(unit.civ.gameInfo, unit.civ).civView,
                )
            }
                .firstOrNull { unit.movement.canReach(it) }
                ?: continue // to another city

            val unitTileBeforeMovement = unit.currentTile
            unit.movement.headTowards(chosenTile)
            if (unit.currentTile == chosenTile) {
                if (unit.currentTile.isPillaged())
                    UnitActions.invokeUnitAction(unit, UnitActionType.Repair)
                else UnitActions.invokeUnitAction(unit, UnitActionType.CreateImprovement)
                return true
            }
            return unitTileBeforeMovement != unit.currentTile
        }
        // No city needs this improvement.
        return false
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
