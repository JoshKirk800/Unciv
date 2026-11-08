package com.unciv.logic.automation.unit

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.ProductionAi
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
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
    /** The city that committed to train the escort (CvAIOperation::m_viListOfUnitsCitiesHaveCommittedToBuild), null if none has */
    var escortCityId: String? = null
    /** The unit type that city trains (CvCity::m_unitBeingBuiltForOperation) and the unit AI type it gets (the escort slot's primary or secondary one) */
    var escortUnit: String? = null
    var escortUnitAi: String? = null
    /** The escort trained for this operation, who joins the settler (CvAIOperation: the escort slot of the army); -1 until it exists */
    var escortUnitId = -1

    @Readonly
    fun clone(): SettlerOperation {
        @LocalState val toReturn = SettlerOperation()
        toReturn.target = target
        toReturn.area = area
        toReturn.foundCity = foundCity
        toReturn.escorted = escorted
        toReturn.turnStarted = turnStarted
        toReturn.escortCityId = escortCityId
        toReturn.escortUnit = escortUnit
        toReturn.escortUnitAi = escortUnitAi
        toReturn.escortUnitId = escortUnitId
        return toReturn
    }
}

/**
 *  Where and when AI settlers go: CvHomelandAI::PlotFirstTurnSettlerMoves, the dispatch of EconomicAIHelpers::IsTestStrategy_FoundCity
 *  (which area, escorted or not) and the settler side of CvAIOperationFoundCity / CvAIOperationQuickColonize (target from
 *  CvPlayer::GetBestSettlePlot, retarget when the site is lost, found on arrival).
 *
 *  The escort of a found-city operation is recruited as the original does: a city asks CvCity::GetUnitForOperation ([settlerAwaitingEscort],
 *  [escortUnitFor]) when it chooses its production ([com.unciv.logic.automation.city.ConstructionAutomation]), commits to train it
 *  ([commitEscort]), and the unit it trains becomes the escort ([onUnitTrained], [tryJoinSettler]). Not ported (the other operations and
 *  armies are out of scope): GrabUnitsFromTheReserves - Unciv's
 *  [com.unciv.logic.automation.civilization.NextTurnAutomation.automateSettlerEscorting] drafts a unit near a waiting settler instead.
 */
object SettlerAutomation {
    /** Turns a FOUND_CITY settler waits for an escort before it goes alone (CvAIOperationFoundCity::ShouldAbort) */
    private const val ESCORT_WAIT_TURNS = 15

    fun automateSettler(unitView: MapUnitView, cache: AutomationCache) = timeThis("AI.Settler") {
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
            operation = startOperation(unit) ?: run {  // No site: a settler outside an operation stays where it is
                AiDecisionLog.log(civ) { "settler ${unit.id} at ${unit.getTile().position}: no settle site" }
                return@timeThis
            }
            val started = operation
            unit.settlerOperation = started
            val site = civ.gameInfo.tileMap[started.target!!]
            target = site
            AiDecisionLog.log(civ) {
                "settler ${unit.id} at ${unit.getTile().position}: ${if (started.foundCity) "found city" else "quick colony"} at ${site.position} " +
                    "(area ${started.area}, ${if (started.escorted) "escorted" else "alone"}, distance ${site.aerialDistanceTo(unit.getTile())})"
            }
        }

        // CvAIEscortedOperation: the escort died on its way - abort, a new operation starts next turn
        if (operation.escortUnitId != -1 && assignedEscort(unit) == null) {
            unit.settlerOperation = null
            AiDecisionLog.log(civ) { "settler ${unit.id}: escort died, operation aborted" }
            return@timeThis
        }
        // CvAIOperationFoundCity::ShouldAbort: no escort after 15 turns (or waiting outside our borders), and no city has committed to train
        // one - go alone
        if (operation.foundCity && operation.escorted && !hasEscort(unit) && operation.escortUnitId == -1
            && (civ.gameInfo.turns - operation.turnStarted > ESCORT_WAIT_TURNS || unit.getTile().getOwner() != civ)
            && committedCity(civ, operation) == null) operation.escorted = false

        // CvAIOperationFoundCity::ArmyInPosition: the site was taken or can no longer hold a city - find another one.
        // No path to the new site either (GetBestSettlePlot does not check paths): AI_ABORT_LOST_PATH, a new operation starts next turn
        if (!isStillValidTarget(unit, target) || !unit.movement.canReach(target)) {
            val lost = target
            val newTarget = retarget(unit, operation, lost)
            if (newTarget == null || !unit.movement.canReach(newTarget)) {
                unit.settlerOperation = null
                AiDecisionLog.log(civ) {
                    "settler ${unit.id} at ${unit.getTile().position}: site ${lost.position} lost, " +
                        if (newTarget == null) "no other site" else "no path to ${newTarget.position}"
                }
                return@timeThis
            }
            target = newTarget
        }

        if (unit.getTile() == target && unit.hasMovement()) {
            unitView.tryInvokeUnitAction(UnitActionType.FoundCity)
            return@timeThis
        }
        // Waiting for the escort (AI_OPERATION_STATE_RECRUITING_UNITS): stay put until it joins
        if (operation.foundCity && operation.escorted && !hasEscort(unit)) return@timeThis
        // CvHomelandAI::PlotMovesToSafety: a settler travelling alone flees from a plot with danger (one with an escort moves with it)
        if (unit.getTile().militaryUnit?.civ != civ && CivilianSafety.fleeIfThreatened(unit)) return@timeThis
        if (!CivilianSafety.headTowardsAvoidingDanger(unit, target)) return@timeThis
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

    /** The escort trained for the settler's operation, while it lives (the unit in the army's escort slot) */
    fun assignedEscort(settler: MapUnit): MapUnit? {
        val id = settler.settlerOperation?.escortUnitId ?: return null
        if (id < 0) return null
        return settler.civ.units.getCivUnits().firstOrNull { it.id == id }
    }

    /** The city that has committed to train the escort of [operation] and still does (CvAIOperation::m_viListOfUnitsCitiesHaveCommittedToBuild) */
    private fun committedCity(civ: Civilization, operation: SettlerOperation): City? {
        val id = operation.escortCityId ?: return null
        val city = civ.cities.firstOrNull { it.id == id } ?: return null
        return if (city.cityConstructions.currentConstructionName() == operation.escortUnit) city else null
    }

    /** CvCity::IsBuildingUnitForOperation: [city] has committed to train the escort of a settler's operation and still does */
    fun isBuildingUnitForOperation(city: City): Boolean =
        city.civ.units.getCivUnits().any { settler -> settler.settlerOperation?.let { committedCity(city.civ, it) } == city }

    /**
     *  The settler whose operation CvCity::GetUnitForOperation would serve in [city]: CvPlayer::PeekAtNextUnitToBuildForOperationSlot(area of
     *  the city) - an escorted found-city operation still waiting for its escort (AI_OPERATION_STATE_RECRUITING_UNITS), mustering on the
     *  city's landmass, that no city has committed to yet.
     */
    fun settlerAwaitingEscort(city: City): MapUnit? {
        val civ = city.civ
        val area = city.getCenterTile().getContinent()
        return civ.units.getCivUnits().firstOrNull { settler ->
            val operation = settler.settlerOperation
            operation != null && operation.foundCity && operation.escorted && operation.escortUnitId == -1 && !hasEscort(settler)
                && settler.getTile().getContinent() == area && committedCity(civ, operation) == null
        }
    }

    /**
     *  CvCity::GetUnitForOperation for the escort slot of MUFORMATION_SETTLER_ESCORT: the unit CvUnitProductionAI::RecommendUnit picks for the
     *  slot's primary unit AI (UNITAI_DEFENSE), else for its secondary one (UNITAI_COUNTER), with that AI type; null if [city] can train neither.
     */
    fun escortUnitFor(city: City): Pair<BaseUnit, UnitAiType>? {
        ProductionAi.recommendUnit(city, UnitAiType.DEFENSE)?.let { return it to UnitAiType.DEFENSE }
        return ProductionAi.recommendUnit(city, UnitAiType.COUNTER)?.let { it to UnitAiType.COUNTER }
    }

    /** CvCity::CommitToBuildingUnitForOperation: [city] trains [unit] as the escort of [settler]'s operation, the unit AI [unitAi] it gets */
    fun commitEscort(settler: MapUnit, city: City, unit: BaseUnit, unitAi: UnitAiType) {
        val operation = settler.settlerOperation ?: return
        operation.escortCityId = city.id
        operation.escortUnit = unit.name
        operation.escortUnitAi = unitAi.xmlName
    }

    /**
     *  CvCity::CreateUnit with bUseToSatisfyOperation: a unit [city] trained for a settler's operation takes the unit AI of the slot and
     *  becomes its escort (CvPlayer::CityFinishedBuildingUnitForOperationSlot).
     *  @return whether [unit] was the escort of an operation - the original then skips the recon assignment of units just trained
     */
    fun onUnitTrained(unit: MapUnit, city: City): Boolean {
        for (settler in unit.civ.units.getCivUnits()) {
            val operation = settler.settlerOperation ?: continue
            if (operation.escortCityId != city.id || operation.escortUnit != unit.name) continue
            UnitAiType.ofXmlName(operation.escortUnitAi)?.let { UnitAiTypes.set(unit, it) }
            operation.escortCityId = null
            operation.escortUnit = null
            operation.escortUnitAi = null
            operation.escortUnitId = unit.id
            return true
        }
        return false
    }

    /** The escort of a settler's operation joins the settler where it waits and then follows it (the army moves together) */
    fun tryJoinSettler(unit: MapUnit): Boolean {
        val settler = unit.civ.units.getCivUnits().firstOrNull { it.settlerOperation?.escortUnitId == unit.id } ?: return false
        val settlerTile = settler.getTile()
        if (unit.getTile() != settlerTile && unit.movement.canReach(settlerTile)) unit.movement.headTowards(settlerTile)
        return true
    }

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
