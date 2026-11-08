package com.unciv.logic.automation.unit

import com.unciv.view.isMilitary
import com.unciv.view.isAutomatingRoadConnection
import com.unciv.logic.automation.civilization.ThreatManager
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.view.CivView
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.logic.map.mapunit.actions.UnitActionModifiers
import com.unciv.logic.map.mapunit.actions.UnitActionModifiers.canUse
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.view.MapUnitView
import com.unciv.view.TileView
import yairm210.purity.annotations.Readonly
import com.unciv.logic.automation.Timers.Companion.timeThis

object CivilianUnitAutomation {

    @Readonly
    fun shouldClearTileForAddInCapitalUnits(unit: MapUnitView, tile: TileView) =
        tile.isCityCenter() && tile.getCity()?.isCapital() == true
        && !unit.hasUnique(UniqueType.AddInCapital)
        && unit.civ().getUnits().any { it.hasUnique(UniqueType.AddInCapital) }

    fun automateCivilianUnit(unit: MapUnitView, dangerousTiles: HashSet<TileView>, cache: AutomationCache): Unit = timeThis("automateCivilianUnit") {
        // Temporary - logic-layer automation below still works on MapUnit
        val mapUnit = unit.getUnit()

        // To allow "found city" actions that can only trigger a limited number of times
        
        // Slightly modified getUsableUnitActionUniques() to allow for settlers with *conditional* settling uniques
        @Readonly
        fun hasSettlerAction(uniqueType: UniqueType) =
            mapUnit.getMatchingUniques(uniqueType, GameContext.IgnoreConditionals)
                .filter { unique -> !unique.hasModifier(UniqueType.UnitActionExtraLimitedTimes) }
                .any { canUse(mapUnit, it) }
        
        val hasSettlerUnique = hasSettlerAction(UniqueType.FoundCity) || hasSettlerAction(UniqueType.FoundPuppetCity)
        
        if (hasSettlerUnique && !(unit.civ().isCityState && unit.isMilitary()))
            return SettlerAutomation.automateSettler(unit, dangerousTiles, cache)

        if (tryRunAwayIfNeccessary(unit, cache)) return

        if (shouldClearTileForAddInCapitalUnits(unit, unit.getTile())) {
            // First off get out of the way, then decide if you actually want to do something else
            val tilesCanMoveTo = unit.getDistanceToTiles()
                .filter { unit.canMoveTo(it.key) }
            if (tilesCanMoveTo.isNotEmpty())
                unit.tryMoveToTile(tilesCanMoveTo.minByOrNull { it.value }!!.key)
        }

        if (mapUnit.hasUnique(UniqueType.EstablishTradeRoutes))
            return TradeRouteAutomation.automateTradeUnit(mapUnit)

        if (mapUnit.hasUnique(UniqueType.CanExcavate) && ArchaeologistAutomation.automate(mapUnit))
            return

        if (unit.isAutomatingRoadConnection())
            return mapUnit.civ.getWorkerAutomation().roadToAutomation.automateConnectRoad(unit, dangerousTiles)

        if (unit.hasUnique(UniqueType.BuildImprovements))
            return mapUnit.civ.getWorkerAutomation().automateWorkerAction(unit, dangerousTiles)

        if (unit.hasUniqueToCreateWaterImprovements()) {
            if (!mapUnit.civ.getWorkerAutomation().automateWorkBoats(unit))
                UnitAutomation.tryExplore(unit, cache)
            return
        }

        if (unit.hasUnique(UniqueType.MayFoundReligion)
            && mapUnit.civ.religionManager.religionState < ReligionState.Religion
            && mapUnit.civ.religionManager.mayFoundReligionAtAll()
        )
            return ReligiousUnitAutomation.foundReligion(unit)
        
        if (unit.hasUnique(UniqueType.MayFoundReligion) && unit.civ().isCityState){
            // We have literally nothing to do with this unit, at least stop costing money
            unit.tryDisband()
            return 
        }
            

        if (unit.hasUnique(UniqueType.MayEnhanceReligion)
            && mapUnit.civ.religionManager.religionState < ReligionState.EnhancedReligion
            && mapUnit.civ.religionManager.mayEnhanceReligionAtAll()
        )
            return ReligiousUnitAutomation.enhanceReligion(unit)

        // We try to add any unit in the capital we can, though that might not always be desirable
        // For now its a simple option to allow AI to win a science victory again
        if (unit.hasUnique(UniqueType.AddInCapital))
            return SpecificUnitAutomation.automateAddInCapital(unit)

        // Great People but Prophets: the directives of CvPlayerAI and the moves of CvHomelandAI. A general near a visible enemy is the
        // tactical AI's (CvTacticalAI::CommandeerUnits), for which following our troops stands in
        val greatPersonRole = GreatPersonAutomation.roleOf(mapUnit.baseUnit, mapUnit.civ.gameInfo.ruleset)
        if (greatPersonRole == GreatPersonRole.General && GreatPersonAutomation.isNearVisibleEnemy(mapUnit)
            && SpecificUnitAutomation.automateGreatGeneral(unit))
            return
        if (greatPersonRole != null && greatPersonRole != GreatPersonRole.Prophet
            && GreatPersonAutomation.automate(mapUnit) { unit.getTile().getTileMap().getTile(it) in dangerousTiles })
            return

        if (mapUnit.civ.religionManager.maySpreadReligionAtAll(mapUnit))
            return ReligiousUnitAutomation.automateMissionary(unit)

        if (unit.hasUnique(UniqueType.PreventSpreadingReligion) || unit.hasUnique(UniqueType.CanRemoveHeresy))
            return ReligiousUnitAutomation.automateInquisitor(unit)

        if (unit.hasUnique(UniqueType.GainFreeBuildings)) {
            val unique = mapUnit.getMatchingUniques(UniqueType.GainFreeBuildings).first()
            val buildingName = unique.params[0]
            // Choose the city that is closest in distance and does not have the building constructed.
            val cityToGainBuilding = unit.civ().cities().filter {
                !it.constructions.getCityConstructions().containsBuildingOrEquivalent(buildingName)
                    && (unit.canMoveTo(it.getCenterTile()) || unit.getTile() == it.getCenterTile())
            }.map {
                val path = unit.getShortestPath(it.getCenterTile())
                // We want to calc path once, but still filter out unreachable cities
                it to path.size
            }.filter { it.second > 0 }.minByOrNull { it.second }?.first

            if (cityToGainBuilding != null) {
                if (unit.getTile() == cityToGainBuilding.getCenterTile()) {
                    UniqueTriggerActivation.triggerUnique(unique, mapUnit.civ, unit = mapUnit, tile = mapUnit.currentTile)
                    UnitActionModifiers.activateSideEffects(mapUnit, unique)
                    return
                }
                else unit.tryHeadTowards(cityToGainBuilding.getCenterTile())
            }
            return
        }

        if (SpecificUnitAutomation.automateImprovementPlacer(mapUnit) { unit.getTile().getTileMap().getTile(it) in dangerousTiles }) return
        
        val goldenAgeAction = UnitActions.getUnitActions(mapUnit, UnitActionType.TriggerUnique)
            .filter { it.action != null && it.associatedUnique?.type in listOf(UniqueType.OneTimeEnterGoldenAge,
                UniqueType.OneTimeEnterGoldenAgeTurns) }.firstOrNull()
        if (goldenAgeAction != null) {
            goldenAgeAction.action?.invoke()
            return
        }

        return // The AI doesn't know how to handle unknown civilian units
    }

    /** Returns whether the civilian spends its turn hiding and not moving */
    fun tryRunAwayIfNeccessary(unit: MapUnitView, cache: AutomationCache): Boolean {
        // This is a little 'Bugblatter Beast of Traal': Run if we can attack an enemy
        // Cheaper than determining which enemies could attack us next turn
        val enemyUnitsInWalkingDistance = unit.getDistanceToTiles().keys
            .filter { ThreatManager.doesTileHaveMilitaryEnemy(unit.civ(), it) }

        if (enemyUnitsInWalkingDistance.isNotEmpty() && !unit.getBaseUnit().isMilitary
            && unit.getTile().militaryUnit == null && !unit.getTile().isCityCenter()) {
            runAway(unit, cache)
            return true
        }

        return false
    }

    private fun runAway(unit: MapUnitView, cache: AutomationCache) {
        val mapUnit = unit.getUnit()
        val reachableTiles = mapUnit.movement.getDistanceToTiles()
        val enterableCity = reachableTiles.keys
            .firstOrNull { it.isCityCenter() && mapUnit.movement.canMoveTo(it) }
        if (enterableCity != null) {
            mapUnit.movement.moveToTile(enterableCity)
            return
        }
        val defensiveUnit = reachableTiles.keys
            .firstOrNull {
                it.militaryUnit != null && it.militaryUnit!!.civ == mapUnit.civ && it.civilianUnit == null
            }
        if (defensiveUnit != null) {
            mapUnit.movement.moveToTile(defensiveUnit)
            return
        }

        val dangerousTiles = ThreatManager.getDangerousTiles(cache, unit)
        val unitTileView = unit.getTile()
        val tileClosestToDanger = dangerousTiles
            // Priotirize capture threat over ranged attack
            .sortedByDescending { ThreatManager.getEnemyUnitsOnTiles(unit.civ(), listOf(it)).isNotEmpty() }
            .minByOrNull { it.aerialDistanceTo(unitTileView) } ?: unitTileView
        val tileFurthestFromDanger = reachableTiles.keys
            .filter {
                mapUnit.movement.canMoveTo(it)
                    && mapUnit.getDamageFromTerrain(it) < unit.unitHealth
                    && unitTileView.getTileMap().getTile(it) !in dangerousTiles }
            .sortedWith(compareByDescending<Tile> { unitTileView.getTileMap().getTile(it).aerialDistanceTo(tileClosestToDanger) } // As far away from threat
                .thenByDescending { it.isFriendlyTerritory(mapUnit.civ) }) // Priotirize friendly territory
            .firstOrNull() ?: return // can't move anywhere!

        mapUnit.movement.moveToTile(tileFurthestFromDanger)
    }
}
