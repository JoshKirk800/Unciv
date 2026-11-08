package com.unciv.logic.automation.unit

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.AiOperationType
import com.unciv.logic.automation.strategy.NavalOperationLogic
import com.unciv.logic.automation.strategy.OperationState
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.view.MapUnitView

/**
 *  Where and when AI settlers go: CvHomelandAI::PlotFirstTurnSettlerMoves and the launch of the found-city operations of
 *  EconomicAIHelpers::IsTestStrategy_FoundCity (which area, escorted or not). The operations themselves - target from
 *  CvPlayer::GetBestSettlePlot, the escort recruited from the reserves or trained by a city (slot commitments of
 *  [com.unciv.logic.automation.strategy.AiOperations]), retargeting when the site is lost, founding on arrival - are
 *  CvAIOperationFoundCity / CvAIOperationQuickColonize in [AiOperationLogic], moved by [OperationMoves].
 */
object SettlerAutomation {

    fun automateSettler(unitView: MapUnitView, cache: AutomationCache) = timeThis("AI.Settler") {
        val unit = unitView.getUnit()
        val civ = unit.civ

        // CvHomelandAI::PlotFirstTurnSettlerMoves: with no city yet, found where it stands.
        // City-states of a ruleset with CityStatesSearchForFirstCitySite look for a site like other settlers instead.
        if (civ.cities.isEmpty() && !(civ.isCityState && civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.CityStatesSearchForFirstCitySite))
            && SettleAreas.canFound(unit, unit.getTile())) {
            if (unitView.tryInvokeUnitAction(UnitActionType.FoundCity)) return@timeThis
        }

        // The operation normally exists and has moved the settler already (OperationMoves.doTurn); a settler of a human's automation, or one
        // that came to our civilization this turn, starts and moves it here.
        val operations = civ.ai.operations
        operations.reconcileUnits()
        val operation = operations.operationFor(unit) ?: launchOperation(unit) ?: run {
            AiDecisionLog.log(civ) { "settler ${unit.id} at ${unit.getTile().position}: no settle site" }
            return@timeThis
        }
        OperationMoves.moveOperation(civ, operation, cache)
        operations.sweep()
    }

    /**
     *  EconomicAIHelpers::IsTestStrategy_FoundCity, its operation launch for a loose settler: no settle area worth it - no operation.
     *  Once the player can embark and has more than one city, a roll 0..5 picks the area: 0-1 the best site anywhere (escorted if there is
     *  none unescorted), 2 the second-best area, 3-5 the best area (escorted when that area is safe for a quick colony, as the original
     *  sets it); before that always the best area, escorted. Escorted = CvAIOperationFoundCity, unescorted = CvAIOperationQuickColonize.
     *  @return the operation (it may have aborted at once: no site, see [AiOperationLogic.addSettlerOperation]), null if there is no area to settle
     */
    fun launchOperation(unit: MapUnit): AiOperation? {
        val civ = unit.civ
        if (civ.cities.isEmpty()) return null
        val areas = SettleAreas.bestSettleAreas(civ, civ.gameInfo.ruleset.ai.define(AiDefine.AI_STRATEGY_MINIMUM_SETTLE_FERTILITY))
        if (areas.count == 0) return null

        val area: Int
        val wantEscort: Boolean
        if (civ.tech.unitsCanEmbark && civ.cities.size > 1) {
            val roll = civ.ai.random("FoundCity").nextInt(6)
            when {
                roll <= 1 -> {
                    area = -1
                    wantEscort = SettleAreas.bestSettlePlot(unit, false, -1, civ.ai.operations.settleTargets(null)) == null
                }
                roll == 2 -> {
                    area = areas.second
                    wantEscort = SettleAreas.isAreaSafeForQuickColony(civ, areas.second)
                }
                else -> {
                    area = areas.first
                    wantEscort = SettleAreas.isAreaSafeForQuickColony(civ, areas.first)
                }
            }
        } else {
            area = areas.first
            wantEscort = true
        }
        // An escorted settler whose best site is overseas sails with a colonization party (CvAINavalEscortedOperation)
        if (wantEscort && civ.tech.unitsCanEmbark && !unit.getTile().isWater) NavalOperationLogic.tryStartColonization(civ, unit)?.let { party ->
            AiDecisionLog.log(civ) { "settler ${unit.id} at ${unit.getTile().position}: ${party.type} to ${party.targetTile} (${party.state})" }
            return party
        }
        val operation = AiOperationLogic.addSettlerOperation(civ, if (wantEscort) AiOperationType.FOUND_CITY else AiOperationType.QUICK_COLONIZE, unit, area)
        AiDecisionLog.log(civ) {
            "settler ${unit.id} at ${unit.getTile().position}: ${operation.type} to ${operation.targetTile} (area $area, " +
                "${if (operation.escorted) "escorted" else "alone"}, ${operation.state})"
        }
        return operation
    }

    /** Settlers without an operation of their own that the operation pass launches one for: loose units with the SETTLE unit AI */
    fun isLooseSettler(unit: MapUnit): Boolean =
        unit.civ.ai.operations.armyOf(unit) == null && UnitAiTypes.current(unit) == UnitAiType.SETTLE && unit.hasUnique(UniqueType.FoundCity)

    /** True if [settler] runs a found-city operation that has not found its escort yet (still recruiting or gathering with one) */
    fun wantsEscort(settler: MapUnit): Boolean =
        settler.civ.ai.operations.operationFor(settler)?.let { it.type == AiOperationType.FOUND_CITY && it.escorted && it.state != OperationState.MOVING_TO_TARGET } == true
}
