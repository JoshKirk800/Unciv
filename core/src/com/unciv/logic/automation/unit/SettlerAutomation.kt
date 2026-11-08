package com.unciv.logic.automation.unit

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.unit.CivilianUnitAutomation.tryRunAwayIfNeccessary
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.view.MapUnitView
import com.unciv.view.TileView
import yairm210.purity.annotations.LocalState
import yairm210.purity.annotations.Readonly

/**
 *  The settler operation a unit runs (CvAIOperationFoundCity, escorted, or CvAIOperationQuickColonize), serialized with the unit
 *  ([MapUnit.settlerOperation]).
 */
class SettlerOperation : IsPartOfGameInfoSerialization {
    /** The plot to settle (CvAIOperation::GetTargetPlot) */
    var target: HexCoord? = null
    /** m_iTargetArea: the continent the site must be on, -1 = anywhere */
    var area = -1
    /** True for CvAIOperationFoundCity (wants an escort), false for CvAIOperationQuickColonize */
    var foundCity = false
    /** m_bEscorted: the settler travels with (or waits for) an escort */
    var escorted = false
    /** Game turn the operation started, for the 15-turn wait for an escort (CvAIOperationFoundCity::ShouldAbort) */
    var turnStarted = 0

    @Readonly
    fun clone(): SettlerOperation {
        @LocalState val toReturn = SettlerOperation()
        toReturn.target = target
        toReturn.area = area
        toReturn.foundCity = foundCity
        toReturn.escorted = escorted
        toReturn.turnStarted = turnStarted
        return toReturn
    }
}

/**
 *  Where and when AI settlers go: CvHomelandAI::PlotFirstTurnSettlerMoves, the dispatch of EconomicAIHelpers::IsTestStrategy_FoundCity
 *  (which area, escorted or not) and the settler side of CvAIOperationFoundCity / CvAIOperationQuickColonize (target from
 *  CvPlayer::GetBestSettlePlot, retarget when the site is lost, found on arrival).
 *
 *  Not ported (operations and armies are out of scope): the escort is not recruited by the operation - Unciv's
 *  [com.unciv.logic.automation.civilization.NextTurnAutomation.automateSettlerEscorting] sends one to settlers whose operation wants it.
 */
object SettlerAutomation {
    /** Turns a FOUND_CITY settler waits for an escort before it goes alone (CvAIOperationFoundCity::ShouldAbort) */
    private const val ESCORT_WAIT_TURNS = 15

    fun automateSettler(unitView: MapUnitView, dangerousTiles: HashSet<TileView>, cache: AutomationCache) = timeThis("AI.Settler") {
        val unit = unitView.getUnit()
        val civ = unit.civ

        // CvHomelandAI::PlotFirstTurnSettlerMoves: with no city yet, found where it stands.
        // City-states of a ruleset with CityStatesSearchForFirstCitySite look for a site like other settlers instead.
        if (civ.cities.isEmpty() && !(civ.isCityState && civ.gameInfo.ruleset.modOptions.hasUnique(UniqueType.CityStatesSearchForFirstCitySite))
            && SettleAreas.canFound(unit, unit.getTile())) {
            if (unitView.tryInvokeUnitAction(UnitActionType.FoundCity)) return@timeThis
        }

        var operation = unit.settlerOperation
        var target = operation?.target?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) }
        if (operation == null || target == null) {
            operation = startOperation(unit) ?: return@timeThis  // No site: a settler outside an operation stays where it is
            unit.settlerOperation = operation
            target = civ.gameInfo.tileMap[operation.target!!]
        }

        // CvAIOperationFoundCity::ShouldAbort: waiting for an escort that never came - go alone after 15 turns
        if (operation.foundCity && operation.escorted && !hasEscort(unit)
            && civ.gameInfo.turns - operation.turnStarted > ESCORT_WAIT_TURNS) operation.escorted = false

        // CvAIOperationFoundCity::ArmyInPosition: the site was taken or can no longer hold a city - find another one
        if (!isStillValidTarget(unit, target) || !unit.movement.canReach(target)) {
            target = retarget(unit, operation, target) ?: run {
                unit.settlerOperation = null
                return@timeThis
            }
        }

        if (unit.getTile() == target && unit.hasMovement()) {
            unitView.tryInvokeUnitAction(UnitActionType.FoundCity)
            return@timeThis
        }
        // Waiting for the escort (AI_OPERATION_STATE_RECRUITING_UNITS): stay put until it joins
        if (operation.foundCity && operation.escorted && !hasEscort(unit)) return@timeThis
        if (tryRunAwayIfNeccessary(unitView, cache)) return@timeThis
        unit.movement.headTowards(target)
        if (unit.getTile() == target && unit.hasMovement()) unitView.tryInvokeUnitAction(UnitActionType.FoundCity)
    }

    /** True if [tile] is still a site the settler can found on: not owned by another player, not next to another player's territory */
    private fun isStillValidTarget(unit: MapUnit, tile: Tile): Boolean {
        val owner = tile.getOwner()
        if (owner != null && owner != unit.civ) return false
        if (tile.neighbors.any { val neighborOwner = it.getOwner(); neighborOwner != null && neighborOwner != unit.civ }) return false
        return SettleAreas.canFound(unit, tile)
    }

    /** A military land unit of ours on the settler's tile */
    private fun hasEscort(unit: MapUnit): Boolean = unit.getTile().militaryUnit?.civ == unit.civ

    /** Sites other settlers of [civ] are heading to (CvPlayer::IsPlotTargetedForCity) */
    private fun targetedSites(civ: Civilization, except: MapUnit): List<Tile> = civ.units.getCivUnits()
        .filter { it != except }
        .mapNotNull { other -> other.settlerOperation?.target?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) } }
        .toList()

    /** CvAIOperationFoundCity::FindBestTarget / CvAIOperationQuickColonize::FindBestTarget: in the operation's area, else anywhere */
    private fun findBestTarget(unit: MapUnit, operation: SettlerOperation, escorted: Boolean): Tile? {
        val targeted = targetedSites(unit.civ, unit)
        val inArea = SettleAreas.bestSettlePlot(unit, escorted, operation.area, targeted)
        if (inArea != null) return inArea
        operation.area = -1
        return SettleAreas.bestSettlePlot(unit, escorted, -1, targeted)
    }

    /** CvAIEscortedOperation::RetargetCivilian: the best site found with "escorted" = not m_bEscorted (as the original passes it) */
    private fun retarget(unit: MapUnit, operation: SettlerOperation, oldTarget: Tile): Tile? {
        val escortedSearch = if (operation.foundCity) !operation.escorted else false
        val better = findBestTarget(unit, operation, escortedSearch) ?: return null
        if (better == oldTarget) return null  // AI_ABORT_REPEAT_TARGET
        operation.target = better.position
        return better
    }

    /**
     *  EconomicAIHelpers::IsTestStrategy_FoundCity, its operation launch for a loose settler: no settle area worth it - no operation.
     *  Once the player can embark and has more than one city, a roll 0..5 picks the area: 0-1 the best site anywhere (escorted if there is
     *  none unescorted), 2 the second-best area, 3-5 the best area (escorted when that area is safe for a quick colony, as the original
     *  sets it); before that always the best area, escorted. Escorted = CvAIOperationFoundCity::Init: target found unescorted; without
     *  an escort at hand, a player with more than one city or boldness over 5 sends the settler alone to the best escorted-search site,
     *  otherwise it waits for an escort; unescorted = CvAIOperationQuickColonize::Init.
     */
    internal fun startOperation(unit: MapUnit): SettlerOperation? {
        val civ = unit.civ
        if (civ.cities.isEmpty()) return null
        val areas = SettleAreas.bestSettleAreas(civ, civ.gameInfo.ruleset.ai.define(AiDefine.AI_STRATEGY_MINIMUM_SETTLE_FERTILITY))
        if (areas.count == 0) return null

        val operation = SettlerOperation()
        operation.turnStarted = civ.gameInfo.turns
        val canEmbark = civ.tech.unitsCanEmbark
        if (canEmbark && civ.cities.size > 1) {
            val roll = civ.ai.random("FoundCity").nextInt(6)
            when {
                roll <= 1 -> {
                    operation.area = -1
                    operation.foundCity = SettleAreas.bestSettlePlot(unit, false, -1, targetedSites(civ, unit)) == null
                }
                roll == 2 -> {
                    operation.area = areas.second
                    operation.foundCity = SettleAreas.isAreaSafeForQuickColony(civ, areas.second)
                }
                else -> {
                    operation.area = areas.first
                    operation.foundCity = SettleAreas.isAreaSafeForQuickColony(civ, areas.first)
                }
            }
        } else {
            operation.area = areas.first
            operation.foundCity = true
        }

        var target = findBestTarget(unit, operation, escorted = false) ?: return null
        if (operation.foundCity) {
            operation.escorted = true
            if (hasEscort(unit)) {
                findBestTarget(unit, operation, escorted = true)?.let { target = it }
            } else if (civ.cities.size > 1 || civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) > 5) {
                val safeTarget = findBestTarget(unit, operation, escorted = true)
                if (safeTarget != null) {
                    target = safeTarget
                    operation.escorted = false
                }
            }
        }
        operation.target = target.position
        return operation
    }

    /** True if [settler] runs a settler operation that wants an escort (Unciv's escort drafting serves only these) */
    fun wantsEscort(settler: MapUnit): Boolean = settler.settlerOperation?.let { it.foundCity && it.escorted } == true
}
