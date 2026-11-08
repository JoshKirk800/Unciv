package com.unciv.logic.automation.strategy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.FormationSlotDef
import com.unciv.models.ruleset.ai.MultiUnitFormationDef
import com.unciv.models.ruleset.unit.BaseUnit
import kotlin.math.max

/**
 *  How the tactical AI moves the armies of an operation (AIOperationMovementType); [None] = the original's INVALID / STATIC type: never moved.
 *  [NavalFleet] is the original's ENEMY_TERRITORY type as the naval bombardment and pure naval city attack set it: the fleet is moved by the
 *  naval formation code ([com.unciv.logic.automation.unit.NavalOperationMoves]), which places ships on water, instead of the land army's.
 */
enum class OperationMoveType { None, SingleHex, EnemyTerritory, NavalEscort, FreeformNaval, NavalFleet }

/**
 *  AIOperationTypes this port covers. [moveType] is what CvAIOperation::m_eMoveType is set to by the operation's Init;
 *  [mixedLandNaval] = CvAIOperation::IsMixedLandNavalOperation, [allNaval] = IsAllNavalOperation;
 *  [tacticalMayInterrupt] = CanTacticalAIInterruptOperation (true only for the naval superiority and the pure naval city attack).
 */
enum class AiOperationType(
    val moveType: OperationMoveType,
    val mixedLandNaval: Boolean = false,
    val tacticalMayInterrupt: Boolean = false,
    val allNaval: Boolean = false,
) {
    FOUND_CITY(OperationMoveType.SingleHex),
    QUICK_COLONIZE(OperationMoveType.SingleHex),
    BASIC_CITY_ATTACK(OperationMoveType.EnemyTerritory),
    SNEAK_CITY_ATTACK(OperationMoveType.EnemyTerritory),
    SMALL_CITY_ATTACK(OperationMoveType.EnemyTerritory),
    CITY_STATE_ATTACK(OperationMoveType.EnemyTerritory),
    DESTROY_BARBARIAN_CAMP(OperationMoveType.EnemyTerritory),
    PILLAGE_ENEMY(OperationMoveType.EnemyTerritory),
    CITY_CLOSE_DEFENSE(OperationMoveType.None),
    RAPID_RESPONSE(OperationMoveType.None),
    /** AI_OPERATION_COLONIZE: CvAINavalEscortedOperation, a settler with a land escort and naval escorts overseas */
    NAVAL_COLONIZE(OperationMoveType.NavalEscort, mixedLandNaval = true),
    NAVAL_ATTACK(OperationMoveType.NavalEscort, mixedLandNaval = true),
    NAVAL_SNEAK_ATTACK(OperationMoveType.NavalEscort, mixedLandNaval = true),
    CITY_STATE_NAVAL_ATTACK(OperationMoveType.NavalEscort, mixedLandNaval = true),
    NAVAL_BOMBARDMENT(OperationMoveType.NavalFleet, allNaval = true),
    NAVAL_SUPERIORITY(OperationMoveType.FreeformNaval, tacticalMayInterrupt = true, allNaval = true),
    PURE_NAVAL_CITY_ATTACK(OperationMoveType.NavalFleet, tacticalMayInterrupt = true, allNaval = true),
    /** CvAIOperationNukeAttack: the original's STATIC move type, it only works with the nuke already in place */
    NUKE_ATTACK(OperationMoveType.None);

    /** The operations CvCity-bound settlers run (CvAIEscortedOperation) */
    val isEscorted get() = this == FOUND_CITY || this == QUICK_COLONIZE
    /** CvAIEnemyTerritoryOperation and its descendants that walk (the land ones) */
    val isEnemyTerritory get() = moveType == OperationMoveType.EnemyTerritory
    /** The attacks on a city by land (CvAIOperationBasicCityAttack and its subclasses) */
    val isCityAttack get() = isEnemyTerritory && this != DESTROY_BARBARIAN_CAMP && this != PILLAGE_ENEMY
    /** CvAIOperationNavalAttack and its subclasses: transports with escorts to a coastal city */
    val isNavalInvasion get() = this == NAVAL_ATTACK || this == NAVAL_SNEAK_ATTACK || this == CITY_STATE_NAVAL_ATTACK
    /** CvAINavalOperation and its subclasses: the operations of ships only */
    val isNavalFleet get() = allNaval
    /** CvAINavalEscortedOperation and its subclasses (the colonization party and the invasions) */
    val isNavalEscorted get() = this == NAVAL_COLONIZE || isNavalInvasion
}

/** AIOperationStates */
enum class OperationState { RECRUITING_UNITS, GATHERING_FORCES, MOVING_TO_TARGET, AT_TARGET, SUCCESSFUL_FINISH, ABORTED }

/** AIOperationAbortReason (the ones the ported operations use) */
enum class OperationAbortReason {
    NONE, SUCCESS, NO_TARGET, REPEAT_TARGET, LOST_TARGET, TARGET_ALREADY_CAPTURED, NO_ROOM_DEPLOY, HALF_STRENGTH, NO_MUSTER,
    LOST_CIVILIAN, ESCORT_DIED, KILLED, WAR_STATE_CHANGE, DIPLO_OPINION_CHANGE, LOST_PATH, NO_NUKES
}

/** ArmyAIState */
enum class ArmyState { WAITING_FOR_UNITS_TO_REINFORCE, WAITING_FOR_UNITS_TO_CATCH_UP, MOVING_TO_DESTINATION }

/** OperationSlot: one slot of an army's formation (CvAIOperation::m_viListOfUnitsWeStillNeedToBuild entries) */
data class SlotRef(var operationId: Int = -1, var armyId: Int = -1, var slotId: Int = -1) : IsPartOfGameInfoSerialization

/** A slot a city has committed to fill (CvAIOperation::m_viListOfUnitsCitiesHaveCommittedToBuild plus CvCity::m_unitBeingBuiltForOperation) */
class SlotCommitment : IsPartOfGameInfoSerialization {
    var slot = SlotRef()
    var cityId = ""
    /** The unit type the city trains and the unit AI type it gets (the slot's primary or secondary one) */
    var unitName = ""
    var unitAi = ""

    fun clone() = SlotCommitment().also { it.slot = slot.copy(); it.cityId = cityId; it.unitName = unitName; it.unitAi = unitAi }
}

/** CvArmyFormationSlot */
class ArmySlot : IsPartOfGameInfoSerialization {
    /** [MapUnit.id] of the unit in the slot, -1 = empty */
    var unitId = -1
    /** Game turn the slot's unit is expected at the muster point: [UNKNOWN_TURN], [NOT_INCLUDED] or a turn */
    var estimatedTurn = UNKNOWN_TURN
    var startedOnOperation = false

    fun clone() = ArmySlot().also { it.unitId = unitId; it.estimatedTurn = estimatedTurn; it.startedOnOperation = startedOnOperation }

    companion object {
        /** ARMYSLOT_UNKNOWN_TURN_AT_CHECKPOINT */
        const val UNKNOWN_TURN = -1
        /** ARMYSLOT_NOT_INCLUDING_IN_OPERATION */
        const val NOT_INCLUDED = -2
    }
}

/** CvArmyAI: the units an operation has put together, one per formation slot */
class AiArmy : IsPartOfGameInfoSerialization {
    var id = -1
    var operationId = -1
    /** MultiUnitFormation name */
    var formation = ""
    var state = ArmyState.WAITING_FOR_UNITS_TO_REINFORCE
    /** CvArmyAI::GetGoalPlot: where the army heads (a few plots short of the target for attacks) */
    var goalTile: HexCoord? = null
    /** CvArmyAI::Plot: the army's current rally point (the muster, later this turn's center of mass checkpoint) */
    var position: HexCoord? = null
    var slots = ArrayList<ArmySlot>()

    fun clone(): AiArmy {
        val toReturn = AiArmy()
        toReturn.id = id; toReturn.operationId = operationId; toReturn.formation = formation; toReturn.state = state
        toReturn.goalTile = goalTile; toReturn.position = position
        for (slot in slots) toReturn.slots.add(slot.clone())
        return toReturn
    }

    /** CvArmyAI::GetNumSlotsFilled */
    fun numSlotsFilled() = slots.count { it.unitId != -1 }
    fun unitIds(): List<Int> = slots.mapNotNull { if (it.unitId == -1) null else it.unitId }
}

/** CvAIOperation with its state; the logic is in [AiOperations] */
class AiOperation : IsPartOfGameInfoSerialization {
    var id = -1
    var type = AiOperationType.BASIC_CITY_ATTACK
    /** civID of the enemy (CvAIOperation::GetEnemy), null = none (barbarians, settling) */
    var enemyCivId: String? = null
    var state = OperationState.ABORTED
    var abortReason = OperationAbortReason.NONE
    var targetTile: HexCoord? = null
    var musterTile: HexCoord? = null
    /** [City.id] of the start city (CvAIOperation::GetStartCityPlot) */
    var startCityId: String? = null
    /** CvAIOperation::m_iDefaultArea: the landmass cities must be on to supply units, [Tile.getContinent] */
    var area = -1
    /** CvAIEscortedOperation::m_iTargetArea: the landmass the settler looks for a site on, -1 = anywhere */
    var targetArea = -1
    var armies = ArrayList<AiArmy>()
    /** The slots still to fill, the next to build is the last */
    var slotsToBuild = ArrayList<SlotRef>()
    var committedCities = ArrayList<SlotCommitment>()
    var lastTurnMoved = -1
    var createdTurn = 0
    /** CvAIEscortedOperation::m_bEscorted */
    var escorted = true
    /** CvAIOperationDestroyBarbarianCamp::m_bCivilianRescue and the barbarian unit's [MapUnit.id] */
    var civilianRescue = false
    var unitToRescue = -1

    fun clone(): AiOperation {
        val toReturn = AiOperation()
        toReturn.id = id; toReturn.type = type; toReturn.enemyCivId = enemyCivId; toReturn.state = state
        toReturn.abortReason = abortReason; toReturn.targetTile = targetTile; toReturn.musterTile = musterTile
        toReturn.startCityId = startCityId; toReturn.area = area; toReturn.targetArea = targetArea
        for (army in armies) toReturn.armies.add(army.clone())
        for (slot in slotsToBuild) toReturn.slotsToBuild.add(slot.copy())
        for (commitment in committedCities) toReturn.committedCities.add(commitment.clone())
        toReturn.lastTurnMoved = lastTurnMoved; toReturn.createdTurn = createdTurn; toReturn.escorted = escorted
        toReturn.civilianRescue = civilianRescue; toReturn.unitToRescue = unitToRescue
        return toReturn
    }

    /** CvAIOperation::GetFirstArmyID */
    val firstArmy get() = armies.firstOrNull()
    /** CvAIOperation::HasOneMoreSlotToFill */
    fun hasOneMoreSlotToFill() = slotsToBuild.size == 1 && state == OperationState.RECRUITING_UNITS
    fun armyById(armyId: Int) = armies.firstOrNull { it.id == armyId }
}

/**
 *  The operations and armies of a civilization (`civ.ai.operations`): CvPlayer's m_AIOperations / m_AIArmies with CvPlayerAI's
 *  PeekAtNextUnitToBuildForOperationSlot, CityCommitToBuildUnitForOperationSlot, CityUncommitToBuildUnitForOperationSlot and
 *  CityFinishedBuildingUnitForOperationSlot, plus the per-unit army id (CvUnit::getArmyID, kept in the army slots) and
 *  CvUnit::m_iDeployFromOperationTurn. The operation classes' behaviour is in [AiOperationLogic].
 *
 *  Serialized with the civilization; old saves have none (their per-settler operation is dropped: the settler starts a new one).
 */
class AiOperations : IsPartOfGameInfoSerialization {
    var operations = ArrayList<AiOperation>()
    var nextOperationId = 1
    var nextArmyId = 1
    /** CvUnit::GetDeployFromOperationTurn by [MapUnit.id], units that finished a successful operation */
    var deployFromOperationTurns = HashMap<Int, Int>()

    @Transient lateinit var civ: Civilization
    @Transient var statsLaunched = 0
    @Transient var statsSucceeded = 0
    @Transient var statsAborted = 0
    /** Operations created and succeeded per type (for the simulation report): [created, succeeded] */
    @Transient val statsByType = HashMap<AiOperationType, IntArray>()

    fun clone(): AiOperations {
        val toReturn = AiOperations()
        for (operation in operations) toReturn.operations.add(operation.clone())
        toReturn.nextOperationId = nextOperationId
        toReturn.nextArmyId = nextArmyId
        toReturn.deployFromOperationTurns.putAll(deployFromOperationTurns)
        return toReturn
    }

    fun setTransients(civ: Civilization) {
        this.civ = civ
    }

    //region Queries (CvPlayer::haveAIOperationOfType, numOperationsOfType, getAIOperation, CvUnit::getArmyID)

    /** CvPlayer::haveAIOperationOfType: the first operation of [type] (against [enemy], with target [target]) */
    fun firstOfType(type: AiOperationType, enemy: Civilization? = null, target: Tile? = null): AiOperation? =
        operations.firstOrNull { it.type == type && (enemy == null || it.enemyCivId == enemy.civID) && (target == null || it.targetTile == target.position) }

    fun operationsOfType(type: AiOperationType): List<AiOperation> = operations.filter { it.type == type }

    /** CvPlayer::numOperationsOfType */
    fun numOperationsOfType(type: AiOperationType) = operations.count { it.type == type }

    fun operationById(id: Int) = operations.firstOrNull { it.id == id }

    /** The army [unit] serves in, CvUnit::getArmyID */
    fun armyOf(unit: MapUnit): AiArmy? = armyOfUnitId(unit.id)

    fun armyOfUnitId(unitId: Int): AiArmy? {
        for (operation in operations) for (army in operation.armies) for (slot in army.slots) if (slot.unitId == unitId) return army
        return null
    }

    /** The operation [unit]'s army belongs to */
    fun operationFor(unit: MapUnit): AiOperation? {
        for (operation in operations) for (army in operation.armies) for (slot in army.slots) if (slot.unitId == unit.id) return operation
        return null
    }

    fun slotIndexOf(army: AiArmy, unit: MapUnit) = army.slots.indexOfFirst { it.unitId == unit.id }

    /** CvArmyAI::CanTacticalAIInterruptUnit: while the army assembles, by all means; later only if the operation allows it */
    fun canTacticalAiInterrupt(unit: MapUnit): Boolean {
        val operation = operationFor(unit) ?: return true
        val army = operation.armies.first { a -> a.slots.any { it.unitId == unit.id } }
        if (army.state != ArmyState.MOVING_TO_DESTINATION) return true
        return operation.type.tacticalMayInterrupt
    }

    /** CvUnit::GetDeployFromOperationTurn, -100 (the original's initial value) if never */
    fun deployFromOperationTurn(unitId: Int) = deployFromOperationTurns[unitId] ?: NEVER_DEPLOYED

    companion object {
        const val NEVER_DEPLOYED = -100
    }

    /** CvPlayer::IsCityAlreadyTargeted: an operation heading for [city] that is less than [percentToTarget] of the way (for [mixedLandNaval] or land ones) */
    fun isCityAlreadyTargeted(city: City, naval: Boolean?, percentToTarget: Int, ignoreOperationId: Int = -1): Boolean {
        for (operation in operations) {
            if (operation.id == ignoreOperationId || operation.targetTile != city.getCenterTile().position) continue
            if (AiOperationLogic.percentFromMusterPointToTarget(civ, operation) >= percentToTarget) continue
            if (naval == null || naval == operation.type.mixedLandNaval) return true
        }
        return false
    }

    /** CvPlayer::IsPlotTargetedForCity: a settler operation heads for a plot within 2 of [tile] */
    fun isPlotTargetedForCity(tile: Tile): Boolean = operations.any { operation ->
        operation.type.isEscorted && operation.targetTile?.let { civ.gameInfo.tileMap[it].aerialDistanceTo(tile) <= 2 } == true
    }

    /** The target tiles of the settler operations of this civilization other than [except] */
    fun settleTargets(except: AiOperation?): List<Tile> = operations
        .filter { it !== except && it.type.isEscorted }
        .mapNotNull { operation -> operation.targetTile?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) } }
    //endregion

    //region Army and operation lifecycle (CvPlayer::addAIOperation, CvAIOperation::Kill, CvArmyAI)

    internal fun newOperation(type: AiOperationType, enemy: Civilization?): AiOperation {
        val operation = AiOperation()
        operation.id = nextOperationId++
        operation.type = type
        operation.enemyCivId = enemy?.civID
        operation.createdTurn = civ.gameInfo.turns
        statsByType.getOrPut(type) { IntArray(2) }[0]++
        operations.add(operation)
        return operation
    }

    internal fun newArmy(operation: AiOperation, formation: String): AiArmy {
        val army = AiArmy()
        army.id = nextArmyId++
        army.operationId = operation.id
        army.formation = formation
        army.state = ArmyState.WAITING_FOR_UNITS_TO_REINFORCE
        val def = formationDef(formation)
        repeat(def?.slots?.size ?: 0) { army.slots.add(ArmySlot()) }
        operation.armies.add(army)
        return army
    }

    fun formationDef(name: String): MultiUnitFormationDef? = civ.gameInfo.ruleset.ai.multiUnitFormations.firstOrNull { it.name == name }

    /** CvArmyAI::AddUnit: [unit] takes slot [slot] of [army], leaving the army it was in; its estimated arrival at the muster is computed */
    fun addUnit(army: AiArmy, unit: MapUnit, slot: Int) {
        removeFromArmy(unit)
        army.slots[slot].unitId = unit.id
        val muster = army.position?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) } ?: return
        val turns = AiOperationLogic.turnsToReach(unit, muster)
        if (turns != Int.MAX_VALUE) setEstimatedTurn(army, slot, turns)
    }

    /** CvArmyAI::SetEstimatedTurn */
    fun setEstimatedTurn(army: AiArmy, slot: Int, turns: Int) {
        army.slots[slot].estimatedTurn =
            if (turns == ArmySlot.NOT_INCLUDED || turns == ArmySlot.UNKNOWN_TURN) turns else civ.gameInfo.turns + turns
    }

    /** CvPlayer::removeFromArmy: the unit leaves its army; its operation is told (CvArmyAI::RemoveUnit) */
    fun removeFromArmy(unit: MapUnit) = removeUnitId(unit.id)

    internal fun removeUnitId(unitId: Int) {
        for (operation in operations.toList()) for (army in operation.armies.toList()) {
            val index = army.slots.indexOfFirst { it.unitId == unitId }
            if (index < 0) continue
            army.slots[index].unitId = -1
            AiOperationLogic.unitWasRemoved(this, operation, army, index)
        }
    }

    /** CvAIOperation::Kill with CvAIOperation::Uninit: the armies are dissolved, their units are free again */
    fun kill(operation: AiOperation, reason: OperationAbortReason) {
        if (operation.abortReason == OperationAbortReason.NONE) operation.abortReason = reason
        AiDecisionLog.log(civ) {
            "operation ${operation.type} #${operation.id} ended: ${operation.abortReason} (started t${operation.createdTurn}, target ${operation.targetTile})"
        }
        if (operation.abortReason == OperationAbortReason.SUCCESS) {
            statsSucceeded++
            statsByType.getOrPut(operation.type) { IntArray(2) }[1]++
        } else statsAborted++
        operation.armies.clear()
        operation.slotsToBuild.clear()
        operation.committedCities.clear()
        operations.remove(operation)
    }

    /** Kills every operation of [type] (optionally against [enemy]) */
    fun killAll(type: AiOperationType, reason: OperationAbortReason, enemy: Civilization? = null) {
        for (operation in operations.toList())
            if (operation.type == type && (enemy == null || operation.enemyCivId == enemy.civID)) kill(operation, reason)
    }
    //endregion

    //region City demand (CvPlayerAI::PeekAtNextUnitToBuildForOperationSlot ... CityFinishedBuildingUnitForOperationSlot, CvCity::GetUnitForOperation)

    /** The slot, the unit type to train and the unit AI it takes: what CvCity::GetUnitForOperation names for [city] */
    class SlotOffer(val slot: SlotRef, val unit: BaseUnit, val unitAi: UnitAiType, val unitAiSecondary: Boolean)

    /** CvPlayerAI::PeekAtNextUnitToBuildForOperationSlot: the next slot to fill on landmass [areaId] (first operation that needs one) */
    fun peekAtNextUnitToBuild(areaId: Int): SlotRef? {
        for (operation in operations) if (operation.area == areaId) operation.slotsToBuild.lastOrNull()?.let { return it }
        return null
    }

    /**
     *  CvCity::GetUnitForOperation: for the next open slot on [city]'s landmass the unit CvUnitProductionAI::RecommendUnit picks for the slot's
     *  primary unit AI, else its secondary one; null if no slot waits or the city can train neither.
     */
    fun nextUnitForOperationSlot(city: City): SlotOffer? {
        val slot = peekAtNextUnitToBuild(city.getCenterTile().getContinent()) ?: return null
        val army = operationById(slot.operationId)?.armyById(slot.armyId) ?: return null
        val entry = formationDef(army.formation)?.slots?.getOrNull(slot.slotId) ?: return null
        UnitAiType.ofXmlName(entry.primaryUnitAi)?.let { ai ->
            ProductionAi.recommendUnit(city, ai)?.let { return SlotOffer(slot, it, ai, false) }
        }
        UnitAiType.ofXmlName(entry.secondaryUnitAi)?.let { ai ->
            ProductionAi.recommendUnit(city, ai)?.let { return SlotOffer(slot, it, ai, true) }
        }
        return null
    }

    /**
     *  CvCity::CommitToBuildingUnitForOperation / CvAIOperation::CommitToBuildNextUnit: [city] trains the [offer]'s unit for the slot, which
     *  leaves the list still to build; the unit is expected [turns] + half the distance from the city to the muster at the army's rally point.
     */
    fun commitToBuild(city: City, offer: SlotOffer, turns: Int) {
        val operation = operationById(offer.slot.operationId) ?: return
        if (!operation.slotsToBuild.remove(offer.slot)) return
        val commitment = SlotCommitment()
        commitment.slot = offer.slot.copy()
        commitment.cityId = city.id
        commitment.unitName = offer.unit.name
        commitment.unitAi = offer.unitAi.xmlName
        operation.committedCities.add(commitment)
        val army = operation.armyById(offer.slot.armyId) ?: return
        val rally = army.position?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) }
        val guess = if (rally == null) 0 else city.getCenterTile().aerialDistanceTo(rally) / 2
        setEstimatedTurn(army, offer.slot.slotId, turns + guess)
    }

    /** CvPlayerAI::CityUncommitToBuildUnitForOperationSlot: the slot is to be built again */
    fun uncommit(commitment: SlotCommitment) {
        val operation = operationById(commitment.slot.operationId) ?: return
        if (operation.committedCities.remove(commitment)) operation.slotsToBuild.add(commitment.slot)
    }

    /** The slot [city] has committed to fill, if it still trains that unit */
    fun commitmentOf(city: City): SlotCommitment? {
        for (operation in operations) for (commitment in operation.committedCities)
            if (commitment.cityId == city.id) return commitment
        return null
    }

    /** CvCity::IsBuildingUnitForOperation */
    fun isBuildingUnitForOperation(city: City): Boolean = commitmentOf(city) != null

    /**
     *  A city that no longer trains the unit it committed to (production changed, city lost) uncommits
     *  (CvCity::popOrder / CvCity::PostKill: CityUncommitToBuildUnitForOperationSlot).
     */
    fun refreshCommitments() {
        for (operation in operations) for (commitment in operation.committedCities.toList()) {
            val city = civ.cities.firstOrNull { it.id == commitment.cityId }
            if (city == null || city.cityConstructions.currentConstructionName() != commitment.unitName) uncommit(commitment)
        }
    }

    /**
     *  CvCity::CreateUnit with bUseToSatisfyOperation / CvPlayerAI::CityFinishedBuildingUnitForOperationSlot: a unit [city] trained for a
     *  slot takes the unit AI of the slot and joins the army; the operation moves on to gathering when nothing is left to build.
     *  @return whether [unit] was trained for an operation - the original then skips the recon assignment of units just trained
     */
    fun finishedBuilding(unit: MapUnit, city: City): Boolean {
        val commitment = commitmentOf(city)?.takeIf { it.unitName == unit.name } ?: return false
        val operation = operationById(commitment.slot.operationId) ?: return false
        val army = operation.armyById(commitment.slot.armyId) ?: return false
        UnitAiType.ofXmlName(commitment.unitAi)?.let { UnitAiTypes.set(unit, it) }
        addUnit(army, unit, commitment.slot.slotId)
        operation.committedCities.remove(commitment)
        if (operation.slotsToBuild.isEmpty() && operation.state == OperationState.RECRUITING_UNITS) {
            operation.state = OperationState.GATHERING_FORCES
            for (a in operation.armies) a.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
        }
        return true
    }

    /** CvAIOperation::BuildListOfUnitsWeStillNeedToBuild: every slot of the armies still waiting for units */
    internal fun buildListOfUnitsWeStillNeedToBuild(operation: AiOperation) {
        operation.committedCities.clear()
        operation.slotsToBuild.clear()
        for (army in operation.armies) {
            if (army.state != ArmyState.WAITING_FOR_UNITS_TO_REINFORCE) continue
            for (index in 0 until (formationDef(army.formation)?.slots?.size ?: 0))
                operation.slotsToBuild.add(SlotRef(operation.id, army.id, index))
        }
    }
    //endregion

    /** The slot entries of [army]'s formation */
    fun slotDefs(army: AiArmy): List<FormationSlotDef> = formationDef(army.formation)?.slots ?: emptyList()

    /** AI_OPERATIONAL_PERCENT_HEALTH_FOR_OPERATION in hit points (of 100) */
    fun minHealth() = civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_PERCENT_HEALTH_FOR_OPERATION)

    /** The operation is over, failed: CvAIOperation::SetToAbort; the sweep removes it */
    fun abort(operation: AiOperation, reason: OperationAbortReason) {
        operation.state = OperationState.ABORTED
        operation.abortReason = reason
    }

    /** Units that died or were captured leave their army (CvArmyAI::RemoveUnit as CvUnit::kill does) */
    fun reconcileUnits() {
        val alive = civ.units.getCivUnits().mapTo(HashSet()) { it.id }
        for (operation in operations.toList()) for (army in operation.armies.toList()) for (slot in army.slots.toList())
            if (slot.unitId != -1 && slot.unitId !in alive) removeUnitId(slot.unitId)
    }

    /**
     *  The deletion sweeps of CvPlayer::doTurnUnits: operations that are over ([AiOperationLogic.doDelayedDeath]) are killed until none is,
     *  then armies without units (that are not still waiting for their first ones, CvArmyAI::DoDelayedDeath) are dissolved.
     */
    fun sweep() {
        do {
            val killed = operations.toList().any { AiOperationLogic.doDelayedDeath(civ, it) }
        } while (killed)
        for (operation in operations) operation.armies.removeAll { it.numSlotsFilled() == 0 && it.state != ArmyState.WAITING_FOR_UNITS_TO_REINFORCE }
    }
}
