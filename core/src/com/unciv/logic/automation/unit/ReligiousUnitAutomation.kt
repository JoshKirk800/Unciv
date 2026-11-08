package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.logic.automation.civilization.ReligionAutomation
import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.view.MapUnitView

/** The moves of religious units: CvHomelandAI::ExecuteMissionaryMoves / ExecuteInquisitorMoves / ExecuteProphetMoves with the targets of CvReligionAI */
object ReligiousUnitAutomation {

    /** CvHomelandAI::ExecuteMissionaryMoves: to the plot from [chooseTargetPlot] next to CvReligionAI::ChooseMissionaryTargetCity, then spread */
    fun automateMissionary(unitView: MapUnitView) {
        if (unitView.religion != unitView.civ().getReligion()?.name || unitView.religion == null) {
            // A unit of another religion would spread that one - nothing for us to do with it
            unitView.tryDisband()
            return
        }
        @Suppress("DEPRECATION") val unit = unitView.getUnit()
        val city = ReligionAutomation.chooseMissionaryTargetCity(unit.civ, unit) ?: return
        val target = chooseTargetPlot(unit, city) { it.getCity() == city } ?: return
        moveAndAct(unitView, unit, target, UnitActionType.SpreadReligion)
    }

    /** CvHomelandAI::ExecuteInquisitorMoves: to the plot next to CvReligionAI::ChooseInquisitorTargetCity, then remove heresy */
    fun automateInquisitor(unitView: MapUnitView) {
        if (unitView.religion != unitView.civ().getReligion()?.name || unitView.religion == null) {
            // No need to keep a unit we can't use, as it only blocks religion spreads of religions other that its own
            unitView.tryDisband()
            return
        }
        @Suppress("DEPRECATION") val unit = unitView.getUnit()
        val religion = unit.religion!!
        val city = ReligionAutomation.chooseInquisitorTargetCity(unit.civ, unit) ?: return
        val target = chooseTargetPlot(unit, city) { it.getCity() == city && hasReligionOtherThan(city, religion) } ?: return
        moveAndAct(unitView, unit, target, UnitActionType.RemoveHeresy)
    }

    /** CvCityReligions::IsReligionHereOtherThan */
    private fun hasReligionOtherThan(city: City, religion: String): Boolean =
        city.religion.getPressures().keys.any { it != religion && it != Constants.noReligionName }

    private enum class ProphetDirective { SpreadReligion, UsePower, ConstructImprovement }

    /**
     *  CvPlayerAI::GetDirectiveProphet and CvHomelandAI::ExecuteProphetMoves. Enhanced religion: spread if CvReligionAI::ChooseProphetConversionCity
     *  finds a city, else build an improvement; founded religion: spread if a city needs it critically, else use the prophet's power (enhance);
     *  no religion: found it, or build an improvement when locked out.
     *  @return false for "build an improvement" (the Holy Site), which the caller's Great Person improvement code handles
     */
    fun automateGreatProphet(unitView: MapUnitView): Boolean {
        @Suppress("DEPRECATION") val unit = unitView.getUnit()
        val civ = unit.civ
        val religionManager = civ.religionManager
        val religion = ReligionAutomation.religionToSpread(civ)
        val directive = when {
            religion != null && religionManager.religionState == ReligionState.EnhancedReligion ->
                if (ReligionAutomation.chooseProphetConversionCity(civ, false) != null) ProphetDirective.SpreadReligion
                else ProphetDirective.ConstructImprovement
            religion != null ->
                if (ReligionAutomation.chooseProphetConversionCity(civ, true) != null) ProphetDirective.SpreadReligion
                else ProphetDirective.UsePower
            religionManager.remainingFoundableReligions() <= 0 -> ProphetDirective.ConstructImprovement
            else -> ProphetDirective.UsePower
        }
        when (directive) {
            ProphetDirective.ConstructImprovement -> return false
            ProphetDirective.SpreadReligion -> spreadReligionWithProphet(unitView)
            ProphetDirective.UsePower -> useProphetPower(unitView, unit)
        }
        return true
    }

    /** ExecuteProphetMoves, GREAT_PEOPLE_DIRECTIVE_SPREAD_RELIGION: to CvReligionAI::ChooseProphetTargetPlot, then spread */
    fun spreadReligionWithProphet(unitView: MapUnitView) {
        @Suppress("DEPRECATION") val unit = unitView.getUnit()
        val city = ReligionAutomation.chooseProphetConversionCity(unit.civ, false) ?: return
        val target = chooseTargetPlot(unit, city) { it.getCity() == city } ?: return
        moveAndAct(unitView, unit, target, UnitActionType.SpreadReligion)
    }

    /**
     *  ExecuteProphetMoves, GREAT_PEOPLE_DIRECTIVE_USE_POWER: found or enhance the religion here if possible, else move to the nearest
     *  (in turns) of our cities without a civilian in it. Only cities where the prophet could then act are considered, so that it does not
     *  shuttle between holy cities, where no religion can be founded.
     */
    private fun useProphetPower(unitView: MapUnitView, unit: MapUnit) {
        val religionManager = unit.civ.religionManager
        val tile = unit.getTile()
        if (religionManager.mayFoundReligionHere(tile)) {
            unitView.tryInvokeUnitAction(UnitActionType.FoundReligion)
            return
        }
        if (religionManager.mayEnhanceReligionHere(tile)) {
            unitView.tryInvokeUnitAction(UnitActionType.EnhanceReligion)
            return
        }
        var best: Tile? = null
        var bestTurns = Int.MAX_VALUE
        for (city in unit.civ.cities) {
            val center = city.getCenterTile()
            if (center.civilianUnit != null) continue
            if (!religionManager.mayFoundReligionHere(center) && !religionManager.mayEnhanceReligionHere(center)) continue
            val turns = turnsToReach(unit, center)
            if (turns < bestTurns) {
                bestTurns = turns
                best = center
            }
        }
        if (best != null) unit.movement.tryHeadTowards(best)
    }

    /** CvHomelandAI's TurnsToReachTarget: 0 = this turn, [Int.MAX_VALUE] = unreachable */
    private fun turnsToReach(unit: MapUnit, tile: Tile): Int {
        if (tile == unit.getTile()) return 0
        if (!unit.movement.canMoveTo(tile)) return Int.MAX_VALUE
        val path = unit.movement.getShortestPath(tile)
        return if (path.isEmpty()) Int.MAX_VALUE else path.size - 1
    }

    /**
     *  CvReligionAI::ChooseMissionaryTargetPlot / ChooseInquisitorTargetPlot / ChooseProphetTargetPlot: our own city without a civilian in it
     *  is entered directly; otherwise the plot next to [city] without foreign units where the unit can act ([canActOn]), fewest turns first,
     *  then the shortest distance, water plots counting double.
     */
    internal fun chooseTargetPlot(unit: MapUnit, city: City, canActOn: (Tile) -> Boolean): Tile? {
        val center = city.getCenterTile()
        if (city.civ == unit.civ && (center.civilianUnit == null || center.civilianUnit == unit) && turnsToReach(unit, center) < Int.MAX_VALUE)
            return center

        var best: Tile? = null
        var bestTurns = Int.MAX_VALUE
        var bestDistance = Int.MAX_VALUE
        for (tile in center.neighbors) {
            if (tile.getUnits().any { it.civ != unit.civ }) continue
            if (!canActOn(tile)) continue
            val turns = turnsToReach(unit, tile)
            if (turns == Int.MAX_VALUE) continue
            var distance = unit.getTile().aerialDistanceTo(tile)
            if (tile.isWater) distance *= 2 // those are dangerous
            if (turns < bestTurns || turns == bestTurns && distance < bestDistance) {
                bestTurns = turns
                bestDistance = distance
                best = tile
            }
        }
        return best
    }

    /** Execute*Moves: move to [target]; act once there */
    private fun moveAndAct(unitView: MapUnitView, unit: MapUnit, target: Tile, action: UnitActionType) {
        if (unit.getTile() != target) unit.movement.tryHeadTowards(target)
        if (unit.getTile() == target) unitView.tryInvokeUnitAction(action)
    }
}
