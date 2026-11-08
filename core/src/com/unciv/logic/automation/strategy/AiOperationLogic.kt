package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.unit.SettleAreas
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.HexMath
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.logic.automation.unit.tactical.CityThreat
import com.unciv.logic.automation.unit.tactical.TacticalTargetType
import com.unciv.view.GameView
import kotlin.math.max

/**
 *  The behaviour of CvAIOperation and its subclasses (CvAIOperation.cpp): Init of each operation type, the state machine (CheckOnTarget,
 *  ArmyInPosition, ShouldAbort, UnitWasRemoved, DoDelayedDeath), recruiting from the reserves (GrabUnitsFromTheReserves,
 *  FindBestFitReserveUnit) and BuyFinalUnit. Operation and army data are in [AiOperations]; the units are moved by [OperationMoves].
 *
 *  Covered: CvAIOperationFoundCity, QuickColonize, BasicCityAttack, SneakCityAttack (and the Quick one), SmallCityAttack, CityStateAttack,
 *  DestroyBarbarianCamp, PillageEnemy, CityCloseDefense, RapidResponse, and the naval attacks as plain land attacks (see [AiOperationType]).
 *
 *  Deviations, with the reason: the step finder is [StepFinder]; "path length" of a unit's route (CvIgnoreUnitsPathFinder) is the unit's turns
 *  (`getShortestPath`), which respects units; the naval formation consistency flag (MultiUnitFormations.RequiresNavalUnitConsistency)
 *  is not in the ruleset data; the original's EnemyTerritoryOperation::Init overwrites its LOST_PATH abort when no deploy point is found
 *  (the abort is kept here); a barbarian camp with no land route from the start city is not a target (the original's step distance would be negative).
 */
object AiOperationLogic {

    //region Helpers

    /** CvArmyAI's TurnsToReachTarget: the turns [unit] needs to [tile], [Int.MAX_VALUE] if it cannot get there */
    fun turnsToReach(unit: MapUnit, tile: Tile): Int {
        if (unit.getTile() == tile) return 0
        if (!unit.movement.canReach(tile)) return Int.MAX_VALUE
        return unit.movement.getShortestPath(tile).size.coerceAtLeast(1)
    }

    private fun tileAt(civ: Civilization, coord: HexCoord?): Tile? = coord?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) }

    fun targetTile(civ: Civilization, operation: AiOperation) = tileAt(civ, operation.targetTile)
    fun musterTile(civ: Civilization, operation: AiOperation) = tileAt(civ, operation.musterTile)
    fun enemy(civ: Civilization, operation: AiOperation): Civilization? = operation.enemyCivId?.let { civ.gameInfo.getCivilizationOrNull(it) }

    /** The units of [army] that exist (CvArmyAI::GetFirstUnit ...) with their slot index */
    fun armyUnits(civ: Civilization, army: AiArmy): List<Pair<Int, MapUnit>> {
        val result = ArrayList<Pair<Int, MapUnit>>(army.slots.size)
        for ((index, slot) in army.slots.withIndex()) {
            if (slot.unitId == -1) continue
            civ.units.getUnitById(slot.unitId)?.let { result.add(index to it) }
        }
        return result
    }

    /** CvAIOperation::GetDeployRange */
    fun deployRange(civ: Civilization, operation: AiOperation): Int {
        val rules = civ.gameInfo.ruleset.ai
        return when (operation.type) {
            AiOperationType.DESTROY_BARBARIAN_CAMP -> rules.define(AiDefine.AI_OPERATIONAL_BARBARIAN_CAMP_DEPLOY_RANGE)
            AiOperationType.PILLAGE_ENEMY -> rules.define(AiDefine.AI_OPERATIONAL_PILLAGE_ENEMY_DEPLOY_RANGE)
            else -> rules.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE)
        }
    }

    /** Whether the city attack formations are the bigger ones: handicap above 4 on a map that is not an archipelago (GetMap().GetAIMapHint() & 1) */
    private fun biggerForces(civ: Civilization) =
        EconomicAi.gameHandicapIndex(civ.gameInfo) > 4 && SettleAreas.aiMapHint(civ.gameInfo) and 1 == 0

    /** CvAIOperation::GetFormation of the operation types (a formation the ruleset lacks is replaced by the basic city attack force) */
    fun formationName(civ: Civilization, type: AiOperationType): String {
        val name = when (type) {
            AiOperationType.FOUND_CITY -> "MUFORMATION_SETTLER_ESCORT"
            AiOperationType.QUICK_COLONIZE -> "MUFORMATION_QUICK_COLONY_SETTLER"
            AiOperationType.BASIC_CITY_ATTACK -> if (biggerForces(civ)) "MUFORMATION_BIGGER_CITY_ATTACK_FORCE" else "MUFORMATION_BASIC_CITY_ATTACK_FORCE"
            AiOperationType.SNEAK_CITY_ATTACK ->
                if (civ.gameInfo.turns < 50 && civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) >= 5) "MUFORMATION_EARLY_RUSH" // CvAIOperationQuickSneakCityAttack
                else if (biggerForces(civ)) "MUFORMATION_BIGGER_CITY_ATTACK_FORCE" else "MUFORMATION_BASIC_CITY_ATTACK_FORCE"
            AiOperationType.SMALL_CITY_ATTACK ->
                if (EconomicAi.gameHandicapIndex(civ.gameInfo) > 4) "MUFORMATION_BASIC_CITY_ATTACK_FORCE" else "MUFORMATION_SMALL_CITY_ATTACK_FORCE"
            AiOperationType.CITY_STATE_ATTACK -> if (biggerForces(civ)) "MUFORMATION_BIGGER_CITY_ATTACK_FORCE" else "MUFORMATION_CITY_STATE_ATTACK_FORCE"
            AiOperationType.DESTROY_BARBARIAN_CAMP -> "MUFORMATION_ANTI_BARBARIAN_TEAM"
            AiOperationType.PILLAGE_ENEMY -> "MUFORMATION_FAST_PILLAGERS"
            AiOperationType.CITY_CLOSE_DEFENSE -> "MUFORMATION_CLOSE_CITY_DEFENSE"
            AiOperationType.RAPID_RESPONSE -> "MUFORMATION_RAPID_RESPONSE_FORCE"
            AiOperationType.NAVAL_ATTACK, AiOperationType.NAVAL_SNEAK_ATTACK -> "MUFORMATION_NAVAL_INVASION"
            AiOperationType.CITY_STATE_NAVAL_ATTACK -> "MUFORMATION_CITY_STATE_INVASION"
        }
        return if (civ.ai.operations.formationDef(name) != null) name else "MUFORMATION_BASIC_CITY_ATTACK_FORCE"
    }

    /**
     *  CvAIOperation::GetOperationStartCity: the city the operation starts at - the one at its start city plot, else the capital, else our
     *  biggest city on the landmass with the most of our and the enemy's cities together.
     */
    fun startCity(civ: Civilization, operation: AiOperation): City? {
        operation.startCityId?.let { id -> civ.cities.firstOrNull { it.id == id }?.let { return it } }
        civ.getCapital(firstCityIfNoCapital = false)?.let { return it }
        val enemy = enemy(civ, operation)
        var bestArea = -1
        var bestTotal = 0
        for (area in civ.cities.map { it.getCenterTile().getContinent() }.distinct()) {
            val mine = civ.cities.count { it.getCenterTile().getContinent() == area }
            var theirs = 0
            if (enemy != null && !enemy.isBarbarian) {
                theirs = enemy.cities.count { it.getCenterTile().getContinent() == area }
                if (theirs == 0) continue
            }
            if (mine + theirs > bestTotal) { bestTotal = mine + theirs; bestArea = area }
        }
        if (bestArea == -1) return null
        return civ.cities.filter { it.getCenterTile().getContinent() == bestArea }.maxByOrNull { it.population.population }
    }

    private fun logStart(civ: Civilization, operation: AiOperation) {
        civ.ai.operations.statsLaunched++
        AiDecisionLog.log(civ) {
            val army = operation.firstArmy
            "operation ${operation.type} #${operation.id} started: target ${operation.targetTile}, muster ${operation.musterTile}, " +
                "state ${operation.state}, formation ${army?.formation} filled ${army?.numSlotsFilled()}/${army?.slots?.size}, " +
                "to build ${operation.slotsToBuild.size}, enemy ${operation.enemyCivId}"
        }
    }

    private fun abort(operation: AiOperation, reason: OperationAbortReason) {
        operation.state = OperationState.ABORTED
        operation.abortReason = reason
    }
    //endregion

    //region Init (CvPlayer::addAIOperation)

    /**
     *  CvPlayer::addAIOperation for the operations of the military AI: creates the operation with its army, finds its target and muster,
     *  recruits what it can from the reserves and sets its state. The operation is returned even when it aborted at once (state ABORTED;
     *  the original removes it in the next sweep, [doTurn]).
     *  @param target the city to attack, [muster] our city it starts from (city attacks); [defend] only [enemy] for the other types
     */
    fun addOperation(civ: Civilization, type: AiOperationType, enemy: Civilization? = null, target: City? = null, muster: City? = null): AiOperation {
        val operations = civ.ai.operations
        val operation = operations.newOperation(type, enemy)
        when {
            type.isCityAttack -> initCityAttack(civ, operation, target, muster)
            type == AiOperationType.DESTROY_BARBARIAN_CAMP || type == AiOperationType.PILLAGE_ENEMY -> initEnemyTerritory(civ, operation)
            else -> initDefense(civ, operation)
        }
        if (operation.state != OperationState.ABORTED) logStart(civ, operation)
        else AiDecisionLog.log(civ) { "operation ${operation.type} #${operation.id} aborted at start: ${operation.abortReason}" }
        return operation
    }

    /** CvAIOperationBasicCityAttack::Init (also Sneak, Small, CityState and the naval attacks) */
    private fun initCityAttack(civ: Civilization, operation: AiOperation, target: City?, muster: City?) {
        val operations = civ.ai.operations
        if (muster == null) { abort(operation, OperationAbortReason.NO_MUSTER); return }
        operation.startCityId = muster.id
        val army = operations.newArmy(operation, formationName(civ, operation.type))
        if (target == null) { abort(operation, OperationAbortReason.LOST_TARGET); return }
        val targetTile = target.getCenterTile()
        val musterTile = muster.getCenterTile()
        operation.targetTile = targetTile.position
        army.goalTile = targetTile.position
        operation.musterTile = musterTile.position
        army.position = musterTile.position
        operation.area = musterTile.getContinent()
        // A few plots shy of the target; a landing by sea has no land route, the army heads for the target itself
        val deploy = civ.ai.military.steps.xPlotsFromEnd(civ, enemy(civ, operation), musterTile, targetTile, deployRange(civ, operation) / 2)
            ?: if (operation.type.mixedLandNaval) targetTile else null
        if (deploy == null) { abort(operation, OperationAbortReason.LOST_PATH); return }
        army.goalTile = deploy.position
        startRecruiting(civ, operation, army, musterTile, targetTile)
    }

    /** The end of the Init functions: list what is still to build, take what the reserves have, gathering if that is all of it */
    private fun startRecruiting(civ: Civilization, operation: AiOperation, army: AiArmy, muster: Tile?, target: Tile?) {
        civ.ai.operations.buildListOfUnitsWeStillNeedToBuild(operation)
        if (grabUnitsFromTheReserves(civ, operation, muster, target)) {
            army.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
            operation.state = OperationState.GATHERING_FORCES
        } else operation.state = OperationState.RECRUITING_UNITS
    }

    /** CvAIEnemyTerritoryOperation::Init (barbarian camp, pillage) */
    private fun initEnemyTerritory(civ: Civilization, operation: AiOperation) {
        val operations = civ.ai.operations
        val army = operations.newArmy(operation, formationName(civ, operation.type))
        val target = findBestTarget(civ, operation)
        if (target == null) { abort(operation, OperationAbortReason.LOST_TARGET); return }
        operation.targetTile = target.position
        army.goalTile = target.position
        val muster = selectInitialMusterPoint(civ, operation, army)
        if (muster == null) { abort(operation, OperationAbortReason.NO_MUSTER); return }
        operation.musterTile = muster.position
        army.position = muster.position
        operation.area = muster.getContinent()
        if (operation.area == target.getContinent()) {
            val deploy = civ.ai.military.steps.xPlotsFromEnd(civ, enemy(civ, operation), muster, target, deployRange(civ, operation) / 2)
            if (deploy == null) { abort(operation, OperationAbortReason.LOST_PATH); return }
            army.goalTile = deploy.position
        }
        startRecruiting(civ, operation, army, muster, target)
    }

    /** CvAIOperationCityCloseDefense::Init / CvAIOperationRapidResponse::Init: gather at the point to defend */
    private fun initDefense(civ: Civilization, operation: AiOperation) {
        val army = civ.ai.operations.newArmy(operation, formationName(civ, operation.type))
        val target = findBestTarget(civ, operation) ?: run { abort(operation, OperationAbortReason.LOST_TARGET); return }
        operation.targetTile = target.position
        army.goalTile = target.position
        operation.musterTile = target.position // gather directly at the point we're trying to defend
        army.position = target.position
        operation.area = target.getContinent()
        startRecruiting(civ, operation, army, target, null)
    }

    /** The target of the operation types that look for one themselves (the FindBestTarget functions) */
    fun findBestTarget(civ: Civilization, operation: AiOperation): Tile? = when (operation.type) {
        AiOperationType.DESTROY_BARBARIAN_CAMP -> findBarbarianCampTarget(civ, operation)
        AiOperationType.PILLAGE_ENEMY -> findPillageTarget(civ, operation)
        AiOperationType.CITY_CLOSE_DEFENSE, AiOperationType.RAPID_RESPONSE -> findDefenseTarget(civ, operation)
        else -> operation.targetTile?.let { tileAt(civ, it) }
    }

    /** CvAIOperationCityCloseDefense/RapidResponse::FindBestTarget: the most threatened city, else our city nearest the enemy capital */
    private fun findDefenseTarget(civ: Civilization, operation: AiOperation): Tile? {
        CityThreat.mostThreatenedCity(civ)?.let { return it.getCenterTile() }
        val enemy = enemy(civ, operation) ?: return null
        val enemyCapital = enemy.getCapital() ?: return null
        return civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(enemyCapital.getCenterTile()) }?.getCenterTile()
    }

    /**
     *  CvAIOperationDestroyBarbarianCamp::FindBestTarget: a barbarian-held settler, worker or archaeologist that was ours (rescue) nearest
     *  to the start city by step distance, else the nearest revealed barbarian camp.
     */
    private fun findBarbarianCampTarget(civ: Civilization, operation: AiOperation): Tile? {
        operation.civilianRescue = false
        val start = startCity(civ, operation)?.getCenterTile() ?: return null
        val steps = civ.ai.military.steps
        val barbarians = civ.gameInfo.civilizations.firstOrNull { it.isBarbarian }
        val captives = (barbarians?.units?.getCivUnits() ?: emptySequence()).filter {
            it.originalOwner == civ.civID && UnitAiTypes.current(it).let { type ->
                type == UnitAiType.SETTLE || type == UnitAiType.WORKER || type == UnitAiType.ARCHAEOLOGIST
            }
        }.toList()
        var best: Tile? = null
        var bestDistance = Int.MAX_VALUE
        if (captives.isNotEmpty()) {
            val lengths = steps.distances(civ, null, start, captives.map { it.getTile() })
            for ((index, captive) in captives.withIndex()) {
                if (lengths[index] < 0 || lengths[index] >= bestDistance) continue
                bestDistance = lengths[index]
                best = captive.getTile()
                operation.civilianRescue = true
                operation.unitToRescue = captive.id
            }
        }
        if (best != null) return best
        val camps = civ.gameInfo.tileMap.tileList.filter { civ.hasExplored(it) && it.isBarbarianEncampment() }
        if (camps.isEmpty()) return null
        val lengths = steps.distances(civ, null, start, camps)
        for ((index, camp) in camps.withIndex()) {
            if (lengths[index] < 0 || lengths[index] >= bestDistance) continue
            bestDistance = lengths[index]
            best = camp
        }
        return best
    }

    /** CvAIOperationPillageEnemy::FindBestTarget: the enemy city on the start city's landmass with the most improved tiles per step */
    private fun findPillageTarget(civ: Civilization, operation: AiOperation): Tile? {
        val enemy = enemy(civ, operation)?.takeIf { !it.isDefeated() } ?: return null
        val start = startCity(civ, operation)?.getCenterTile() ?: return null
        val cities = enemy.cities.filter { it.getCenterTile().getContinent() == start.getContinent() }
        if (cities.isEmpty()) return null
        val lengths = civ.ai.military.steps.distances(civ, enemy, start, cities.map { it.getCenterTile() })
        var best: City? = null
        var bestValue = 0
        for ((index, city) in cities.withIndex()) {
            var value = city.getWorkableTiles().count { it.improvement != null && it.getOwner() == enemy }
            if (lengths[index] > 0) value = value * 100 / lengths[index]
            if (value > bestValue) { bestValue = value; best = city }
        }
        return best?.getCenterTile()
    }

    /**
     *  CvAIEnemyTerritoryOperation::SelectInitialMusterPoint: on another landmass than the goal the start city; else walking back from the goal
     *  along the step path the first tile of ours, or the deploy point when less than AI_OPERATIONAL_PERCENT_DANGER_FOR_FORWARD_MUSTER percent
     *  of the tiles on the way are dangerous.
     */
    private fun selectInitialMusterPoint(civ: Civilization, operation: AiOperation, army: AiArmy): Tile? {
        val start = startCity(civ, operation)?.getCenterTile() ?: return null
        val goal = tileAt(civ, army.goalTile) ?: return null
        if (start.getContinent() != goal.getContinent()) return start
        val path = civ.ai.military.steps.path(civ, enemy(civ, operation), start, goal) ?: return null
        val range = deployRange(civ, operation)
        val danger = civ.ai.plotDanger
        var spaces = 0
        var deploy: Tile? = null
        var dangerous = 0
        var muster: Tile? = null
        for (index in path.indices.reversed()) {
            val tile = path[index]
            if (spaces == range) deploy = tile
            if (tile.getOwner() == civ) { muster = tile; break }
            if (danger.get(tile) > 0) dangerous++
            spaces++
        }
        val limit = civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_PERCENT_DANGER_FOR_FORWARD_MUSTER)
        if (spaces > 0 && dangerous * 100 / spaces < limit && deploy != null) muster = deploy
        return muster
    }

    /**
     *  CvAIOperationFoundCity::Init and CvAIOperationQuickColonize::Init: the settler [civilian] (a loose settler of ours) goes to the best
     *  site of [targetArea] (-1 = anywhere), with an escort recruited from the reserves if possible; with none a player with more than one
     *  city or boldness over 5 sends it alone to the best site found with the safe-path search, else the operation recruits the escort.
     */
    fun addSettlerOperation(civ: Civilization, type: AiOperationType, civilian: MapUnit, targetArea: Int): AiOperation {
        val operations = civ.ai.operations
        val operation = operations.newOperation(type, null)
        operation.targetArea = targetArea
        val target = findSettleTarget(civ, operation, civilian, onlySafePaths = false)
        val army = operations.newArmy(operation, formationName(civ, type))
        if (target == null) { abort(operation, OperationAbortReason.LOST_TARGET); return operation }
        operation.targetTile = target.position
        army.goalTile = target.position
        val musterTile = civilian.getTile()
        operation.musterTile = musterTile.position
        army.position = musterTile.position
        operation.area = musterTile.getContinent()
        operations.addUnit(army, civilian, 0)

        if (type == AiOperationType.QUICK_COLONIZE) {
            operation.escorted = false
            army.state = ArmyState.MOVING_TO_DESTINATION
            operation.state = OperationState.MOVING_TO_TARGET
            logStart(civ, operation)
            return operation
        }
        // FOUND_CITY: the escort is slot 1
        operation.escorted = true
        operation.slotsToBuild.clear()
        operation.slotsToBuild.add(SlotRef(operation.id, army.id, 1))
        grabUnitsFromTheReserves(civ, operation, musterTile, target)
        if (army.numSlotsFilled() > 1) {
            army.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
            operation.state = OperationState.GATHERING_FORCES
        } else {
            var safe: Tile? = null
            if (civ.cities.size > 1 || civ.ai.diplomacyValue(DiplomacyPersonality.Boldness) > 5)
                safe = findSettleTarget(civ, operation, civilian, onlySafePaths = true)
            if (safe == null) {
                // we'll wait it out for an escort
                operation.slotsToBuild.clear()
                operation.slotsToBuild.add(SlotRef(operation.id, army.id, 1))
                operation.state = OperationState.RECRUITING_UNITS
            } else {
                operation.escorted = false
                operation.slotsToBuild.clear()
                army.goalTile = safe.position
                operation.targetTile = safe.position
                army.state = ArmyState.MOVING_TO_DESTINATION
                operation.state = OperationState.MOVING_TO_TARGET
            }
        }
        logStart(civ, operation)
        return operation
    }

    /** CvAIOperationFoundCity::FindBestTarget / CvAIOperationQuickColonize::FindBestTarget: in the operation's area, else anywhere */
    fun findSettleTarget(civ: Civilization, operation: AiOperation, unit: MapUnit, onlySafePaths: Boolean): Tile? {
        val targeted = civ.ai.operations.settleTargets(operation)
        val safe = onlySafePaths && operation.type == AiOperationType.FOUND_CITY
        SettleAreas.bestSettlePlot(unit, safe, operation.targetArea, targeted)?.let { return it }
        operation.targetArea = -1
        return SettleAreas.bestSettlePlot(unit, safe, -1, targeted)
    }
    //endregion

    //region Recruiting from the reserves

    /**
     *  CvAIOperation::GrabUnitsFromTheReserves: every slot still to build is offered the best fitting unit of the reserves; slots that
     *  stay empty and are required are kept in the list, optional ones are given up.
     *  @return whether all required slots are filled
     */
    fun grabUnitsFromTheReserves(civ: Civilization, operation: AiOperation, muster: Tile?, target: Tile?): Boolean {
        val operations = civ.ai.operations
        val pending = ArrayList(operation.slotsToBuild)
        operation.slotsToBuild.clear()
        var allRequired = true
        for (slot in pending) {
            val army = operation.armyById(slot.armyId) ?: continue
            if (findBestFitReserveUnit(civ, operation, army, slot.slotId, muster, target)) continue
            val required = operations.slotDefs(army).getOrNull(slot.slotId)?.required ?: false
            if (required) {
                allRequired = false
                operation.slotsToBuild.add(slot)
            } else operations.setEstimatedTurn(army, slot.slotId, ArmySlot.NOT_INCLUDED)
        }
        return allRequired
    }

    /** CvUnit::GetUnitAIType-based check shared by recruiting and the fillable-slot count: scouts never serve */
    private fun isExploring(unit: MapUnit) = UnitAiTypes.current(unit).let { it == UnitAiType.EXPLORE || it == UnitAiType.EXPLORE_SEA }

    /**
     *  Whether [unit] is free for an operation slot of the formation entry's unit AI [aiName]: not exploring, not in an army, at least
     *  AI_OPERATIONAL_PERCENT_HEALTH_FOR_OPERATION health (combat units), not deployed from an operation in the last
     *  AI_TACTICAL_MAP_TEMP_ZONE_TURNS turns, able to go by sea if the operation needs it (CvAIOperation::FindBestFitReserveUnit and
     *  MilitaryAIHelpers::NumberOfFillableSlots share these tests).
     */
    fun isFreeForOperation(civ: Civilization, unit: MapUnit, naval: Boolean): Boolean {
        if (isExploring(unit)) return false
        val operations = civ.ai.operations
        if (operations.armyOf(unit) != null) return false
        if (unit.isMilitary() && unit.health < operations.minHealth()) return false
        val rules = civ.gameInfo.ruleset.ai
        if (operations.deployFromOperationTurn(unit.id) + rules.define(AiDefine.AI_TACTICAL_MAP_TEMP_ZONE_TURNS) >= civ.gameInfo.turns) return false
        if (naval && !unit.baseUnit.isWaterUnit && (unit.cache.cannotEmbark || !civ.tech.unitsCanEmbark)) return false
        return true
    }

    /**
     *  CvAIOperation::FindBestFitReserveUnit: among the free units with the slot's primary unit AI, else its secondary one, the nearest
     *  (hex distance to the muster, doubled for a land unit on another landmass; to the target without a muster) that can get there
     *  joins the slot.
     */
    private fun findBestFitReserveUnit(civ: Civilization, operation: AiOperation, army: AiArmy, slot: Int, muster: Tile?, target: Tile?): Boolean {
        val def = civ.ai.operations.slotDefs(army).getOrNull(slot) ?: return false
        val rules = civ.gameInfo.ruleset.ai
        val goal = if (muster == null && target != null && operation.type.mixedLandNaval) target else target
        for (aiName in listOf(def.primaryUnitAi, def.secondaryUnitAi)) {
            val type = UnitAiType.ofXmlName(aiName) ?: continue
            val candidates = civ.units.getCivUnits()
                .filter { UnitAiTypes.canHave(it.baseUnit, type, rules) && isFreeForOperation(civ, it, operation.type.mixedLandNaval) }
                .map { unit ->
                    var distance = when {
                        muster != null -> unit.getTile().aerialDistanceTo(muster)
                        goal != null -> unit.getTile().aerialDistanceTo(goal)
                        else -> Int.MAX_VALUE
                    }
                    if (muster != null && unit.baseUnit.isLandUnit && unit.getTile().getContinent() != muster.getContinent()) distance *= 2
                    unit to distance
                }.sortedBy { it.second }.toList()
            val best = closestUnit(civ, candidates, muster, goal, needToCheckTarget(operation.type)) ?: continue
            civ.ai.operations.addUnit(army, best, slot)
            return true
        }
        return false
    }

    /** CvAIOperation::NeedToCheckPathToTarget: false for the city attacks, pillaging and nukes */
    private fun needToCheckTarget(type: AiOperationType) = !(type.isCityAttack || type == AiOperationType.PILLAGE_ENEMY)

    /** GetClosestUnit: [sorted] by raw distance; the first whose route (in turns) is no longer than that distance, else the shortest route */
    private fun closestUnit(civ: Civilization, sorted: List<Pair<MapUnit, Int>>, muster: Tile?, target: Tile?, checkTarget: Boolean): MapUnit? {
        var best: MapUnit? = null
        var bestDistance = Int.MAX_VALUE
        val estimate = civ.gameInfo.ruleset.ai.define(AiDefine.AI_HOMELAND_ESTIMATE_TURNS_DISTANCE)
        for ((unit, distance) in sorted) {
            var pathDistance = Int.MAX_VALUE
            if (muster != null) {
                pathDistance = turnsToReach(unit, muster)
                if (pathDistance == Int.MAX_VALUE) continue
            }
            if (target != null && checkTarget) {
                val toTarget = turnsToReach(unit, target)
                if (toTarget == Int.MAX_VALUE) continue
                if (muster == null) pathDistance = toTarget
            }
            if (pathDistance <= distance && pathDistance <= bestDistance) return unit
            if (pathDistance < bestDistance) { best = unit; bestDistance = pathDistance }
            if (distance >= estimate) break
        }
        return best
    }
    //endregion

    //region State machine

    /** CvAIOperation::CheckOnTarget: armies that have gathered move out, those at the target hand over; run after the army moved */
    fun checkOnTarget(civ: Civilization, operation: AiOperation): Boolean {
        val army = operation.firstArmy ?: return false
        when (operation.type.moveType) {
            OperationMoveType.SingleHex -> {
                if (army.numSlotsFilled() < 1) return false
                val units = armyUnits(civ, army)
                val civilian = units.firstOrNull { it.first == 0 }?.second
                val civilianTile = civilian?.getTile()
                if (operation.state == OperationState.MOVING_TO_TARGET && civilianTile != null && civilianTile.position == operation.targetTile) {
                    armyInPosition(civ, operation, army)
                    return true
                }
                if (operation.state == OperationState.GATHERING_FORCES) {
                    if (army.numSlotsFilled() == 1) { armyInPosition(civ, operation, army); return true }
                    val escortTile = units.firstOrNull { it.first != 0 }?.second?.getTile()
                    if (civilianTile != null && civilianTile == escortTile) { armyInPosition(civ, operation, army); return true }
                }
            }
            OperationMoveType.EnemyTerritory -> for (a in operation.armies.toList()) {
                if (a.numSlotsFilled() < 1) continue
                val com = centerOfMass(civ, a) ?: continue
                when (operation.state) {
                    OperationState.GATHERING_FORCES -> {
                        val muster = musterTile(civ, operation) ?: continue
                        val tolerance = gatherTolerance(civ, a)
                        if (com.aerialDistanceTo(muster) <= tolerance && furthestUnitDistance(civ, a, muster) <= tolerance * 3 / 2) {
                            armyInPosition(civ, operation, a)
                            return true
                        }
                    }
                    OperationState.MOVING_TO_TARGET -> {
                        val target = targetTile(civ, operation) ?: continue
                        val tolerance = civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE)
                        if (com.aerialDistanceTo(target) <= tolerance && furthestUnitDistance(civ, a, target) <= tolerance * 3 / 2) {
                            armyInPosition(civ, operation, a)
                            return true
                        }
                    }
                    else -> Unit
                }
            }
            OperationMoveType.None -> Unit
        }
        return false
    }

    /** CvAIOperation::GetGatherTolerance for land armies: the gather range for the number of units (OperationalAIHelpers::GetGatherRangeForXUnits) */
    fun gatherTolerance(civ: Civilization, army: AiArmy): Int = if (army.numSlotsFilled() < 1) 0 else gatherRangeForXUnits(army.numSlotsFilled())

    /** OperationalAIHelpers::GetGatherRangeForXUnits */
    fun gatherRangeForXUnits(units: Int) = when {
        units <= 2 -> 1
        units <= 6 -> 2
        units <= 10 -> 3
        else -> 4
    }

    /** CvArmyAI::GetCenterOfMass: the average position of the army's units (nudged to land or sea if it falls elsewhere) */
    fun centerOfMass(civ: Civilization, army: AiArmy): Tile? {
        val units = armyUnits(civ, army).map { it.second }
        if (units.isEmpty()) return null
        val map = civ.gameInfo.tileMap
        val reference = units[0].getTile().position
        var sumX = 0
        var sumY = 0
        for (unit in units) {
            var position = unit.getTile().position
            if (map.mapParameters.worldWrap) {
                val unwrapped = map.getUnwrappedPosition(position)
                if (HexMath.getDistance(unwrapped, reference) < HexMath.getDistance(position, reference)) position = unwrapped
            }
            sumX += position.x; sumY += position.y
        }
        val averageX = (sumX + units.size / 2) / units.size
        val averageY = (sumY + units.size / 2) / units.size
        val center = map.getOrNull(averageX, averageY) ?: return units[0].getTile()
        val wantWater = units.all { it.baseUnit.isWaterUnit }
        if (center.isWater == wantWater) return center
        return center.neighbors.firstOrNull { it.isWater == wantWater } ?: units[0].getTile()
    }

    /** CvArmyAI::GetFurthestUnitDistance */
    fun furthestUnitDistance(civ: Civilization, army: AiArmy, tile: Tile): Int =
        armyUnits(civ, army).maxOfOrNull { it.second.getTile().aerialDistanceTo(tile) } ?: 0

    /** CvAIOperation::ArmyInPosition and the overrides of the subclasses; true if the state changed */
    fun armyInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        val type = operation.type
        return when {
            type.isCityAttack -> cityAttackInPosition(civ, operation, army)
            type.isEscorted -> escortedInPosition(civ, operation, army)
            type == AiOperationType.DESTROY_BARBARIAN_CAMP -> campInPosition(civ, operation, army)
            type == AiOperationType.PILLAGE_ENEMY -> {
                if (operation.state == OperationState.MOVING_TO_TARGET) {
                    if (army.position == army.goalTile) operation.state = OperationState.SUCCESSFUL_FINISH
                    false
                } else baseArmyInPosition(operation, army)
            }
            type == AiOperationType.RAPID_RESPONSE ->
                if (operation.state == OperationState.MOVING_TO_TARGET) { retargetDefensiveArmy(civ, operation, army); false }
                else baseArmyInPosition(operation, army)
            else -> baseArmyInPosition(operation, army)
        }
    }

    private fun baseArmyInPosition(operation: AiOperation, army: AiArmy): Boolean {
        when (operation.state) {
            OperationState.GATHERING_FORCES -> {
                operation.state = OperationState.MOVING_TO_TARGET
                army.state = ArmyState.MOVING_TO_DESTINATION
                return true
            }
            OperationState.MOVING_TO_TARGET -> if (army.position == operation.targetTile) {
                operation.state = OperationState.AT_TARGET
                return true
            }
            else -> Unit
        }
        return false
    }

    /** CvAIOperationBasicCityAttack::ArmyInPosition */
    private fun cityAttackInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        val target = targetTile(civ, operation)
        when (operation.state) {
            OperationState.GATHERING_FORCES -> {
                val changed = baseArmyInPosition(operation, army)
                // Is target still under enemy control?
                if (target?.getOwner()?.civID != operation.enemyCivId) abort(operation, OperationAbortReason.TARGET_ALREADY_CAPTURED)
                return changed
            }
            OperationState.MOVING_TO_TARGET -> {
                val com = centerOfMass(civ, army)
                // Are we within tactical range of our target?
                if (com != null && target != null &&
                    com.aerialDistanceTo(target) <= civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE)) {
                    enemy(civ, operation)?.let { civ.ai.diplomacy.memoryOf(it).musteringForAttack = true }
                    // Notify tactical AI to focus on this area
                    civ.ai.tactical.addTemporaryZone(target, TacticalTargetType.City)
                    operation.state = OperationState.SUCCESSFUL_FINISH
                }
                return false
            }
            else -> return baseArmyInPosition(operation, army)
        }
    }

    /** CvAIOperationDestroyBarbarianCamp::ArmyInPosition */
    private fun campInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        when (operation.state) {
            OperationState.GATHERING_FORCES -> {
                val changed = baseArmyInPosition(operation, army)
                // Now revisit target
                val better = findBestTarget(civ, operation)
                if (better == null) abort(operation, OperationAbortReason.LOST_TARGET)
                else if (better.position != operation.targetTile) {
                    val position = tileAt(civ, army.position)
                    if (position != null && position.getContinent() == better.getContinent()) {
                        val deploy = civ.ai.military.steps.xPlotsFromEnd(civ, null, position, better,
                            civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_BARBARIAN_CAMP_DEPLOY_RANGE))
                        if (deploy != null) { army.goalTile = deploy.position; operation.targetTile = better.position }
                    } else {
                        // Coming in from the sea: just head to the camp
                        army.goalTile = better.position
                        operation.targetTile = better.position
                    }
                }
                return changed
            }
            OperationState.MOVING_TO_TARGET -> {
                val position = tileAt(civ, army.position)
                val goal = tileAt(civ, army.goalTile)
                val target = targetTile(civ, operation)
                if (position != null && goal != null && position.aerialDistanceTo(goal) <= 1) {
                    if (target != null) civ.ai.tactical.addTemporaryZone(target, TacticalTargetType.BarbarianCamp)
                    operation.state = OperationState.SUCCESSFUL_FINISH
                }
                return false
            }
            else -> return baseArmyInPosition(operation, army)
        }
    }

    /** CvAIOperationPillageEnemy::ArmyMoved: every move check for a better pillage target */
    fun armyMoved(civ: Civilization, operation: AiOperation, army: AiArmy) {
        if (operation.type != AiOperationType.PILLAGE_ENEMY || operation.state != OperationState.MOVING_TO_TARGET) return
        val better = findBestTarget(civ, operation)
        if (better == null) { abort(operation, OperationAbortReason.NO_TARGET); return }
        if (better.position == operation.targetTile) return
        operation.targetTile = better.position
        val position = tileAt(civ, army.position) ?: return
        army.goalTile = (civ.ai.military.steps.xPlotsFromEnd(civ, enemy(civ, operation), position, better,
            civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_PILLAGE_ENEMY_DEPLOY_RANGE)) ?: better).position
    }

    /** CvAIOperationRapidResponse::RetargetDefensiveArmy */
    fun retargetDefensiveArmy(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        val better = findBestTarget(civ, operation)
        if (better == null) { abort(operation, OperationAbortReason.NO_TARGET); return false }
        if (better.position != operation.targetTile) {
            operation.targetTile = better.position
            army.goalTile = better.position
        }
        army.state = ArmyState.MOVING_TO_DESTINATION
        operation.state = OperationState.MOVING_TO_TARGET
        return true
    }

    /** CvAIOperationFoundCity::ArmyInPosition: merge settler and escort and move out, found the city on arrival */
    private fun escortedInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        val units = armyUnits(civ, army)
        val settler = units.firstOrNull { it.first == 0 }?.second
        val escort = units.firstOrNull { it.first == 1 }?.second
        when (operation.state) {
            OperationState.GATHERING_FORCES -> {
                if (!operation.escorted) return baseArmyInPosition(operation, army)
                if (escort == null) { abort(operation, OperationAbortReason.ESCORT_DIED); return true }
                if (settler != null && settler.getTile() == escort.getTile()) {
                    // let's see if the target still makes sense (modified from RetargetCivilian)
                    val better = findSettleTarget(civ, operation, settler, onlySafePaths = true)
                    if (better == null) { abort(operation, OperationAbortReason.NO_TARGET); return false }
                    operation.targetTile = better.position
                    army.goalTile = better.position
                    return baseArmyInPosition(operation, army)
                }
            }
            OperationState.MOVING_TO_TARGET, OperationState.AT_TARGET -> {
                val changed = baseArmyInPosition(operation, army)
                if (settler == null) return changed
                val target = targetTile(civ, operation) ?: return changed
                val owner = target.getOwner()
                val unusable = (owner != null && owner != civ) || target.neighbors.any { val o = it.getOwner(); o != null && o != civ }
                if (unusable) {
                    retargetCivilian(civ, operation, army, settler)
                    settler.currentMovement = 0f
                    escort?.currentMovement = 0f
                } else if (settler.getTile() == target && settler.hasMovement() && SettleAreas.canFound(settler, target)) {
                    // If the settler made it, we don't care about the entire army
                    if (GameView(civ.gameInfo, civ).getMapUnitView(settler).tryInvokeUnitAction(UnitActionType.FoundCity))
                        operation.state = OperationState.SUCCESSFUL_FINISH
                } else if (settler.getTile() == target && !SettleAreas.canFound(settler, target)) {
                    // someone else beat us to this area: move back out, picking a new target
                    retargetCivilian(civ, operation, army, settler)
                    settler.currentMovement = 0f
                    escort?.currentMovement = 0f
                }
                return changed
            }
            else -> return baseArmyInPosition(operation, army)
        }
        return false
    }

    /** CvAIEscortedOperation::RetargetCivilian: look for another site (with the safe-path search if the settler is escorted) */
    fun retargetCivilian(civ: Civilization, operation: AiOperation, army: AiArmy, civilian: MapUnit): Boolean {
        val better = findSettleTarget(civ, operation, civilian, onlySafePaths = operation.escorted)
        if (better == null) { abort(operation, OperationAbortReason.NO_TARGET); return false }
        if (better.position == operation.targetTile) {
            abort(operation, OperationAbortReason.REPEAT_TARGET)
            return false
        }
        operation.targetTile = better.position
        army.goalTile = better.position
        army.state = ArmyState.MOVING_TO_DESTINATION
        operation.state = OperationState.MOVING_TO_TARGET
        return true
    }

    /**
     *  CvAIOperation::ShouldAbort with the subclasses' tests. True when the operation is to be removed (the sweep kills it): it ended, or
     *  its purpose is gone (target city taken, camp cleared or rescue over, camp stronger than our units). Units of a successful
     *  operation are marked deployed (CvUnit::SetDeployFromOperationTurn).
     */
    fun shouldAbort(civ: Civilization, operation: AiOperation): Boolean {
        val operations = civ.ai.operations
        if (operation.state == OperationState.SUCCESSFUL_FINISH)
            for (army in operation.armies) for ((_, unit) in armyUnits(civ, army)) operations.deployFromOperationTurns[unit.id] = civ.gameInfo.turns
        if (operation.state == OperationState.ABORTED || operation.state == OperationState.SUCCESSFUL_FINISH) return true

        val type = operation.type
        when {
            type.isCityAttack -> {
                // See if our target city is still owned by our enemy: success, the city was captured or destroyed
                val target = targetTile(civ, operation)
                if (target == null || target.getCity() == null || target.getOwner()?.civID != operation.enemyCivId) return true
            }
            type == AiOperationType.DESTROY_BARBARIAN_CAMP -> return campShouldAbort(civ, operation)
            type.isEscorted -> escortedShouldAbort(civ, operation)
        }
        return false
    }

    /** CvAIOperationDestroyBarbarianCamp::ShouldAbort */
    private fun campShouldAbort(civ: Civilization, operation: AiOperation): Boolean {
        val target = targetTile(civ, operation) ?: return true
        if (!operation.civilianRescue) {
            // The camp is gone: success
            if (!target.isBarbarianEncampment()) return true
        } else {
            // Is the unit rescued?
            val captive = civ.gameInfo.civilizations.firstOrNull { it.isBarbarian }?.units?.getUnitById(operation.unitToRescue) ?: return true
            if (captive.originalOwner != civ.civID || UnitAiTypes.current(captive).let { it != UnitAiType.SETTLE && it != UnitAiType.WORKER }) return true
        }
        if (!operation.civilianRescue && operation.state != OperationState.RECRUITING_UNITS) {
            // If down below the strength of the camp, abort
            val army = operation.firstArmy
            val defender = target.getUnits().filter { it.isMilitary() }.maxByOrNull { MilitaryAi.unitPower(it) }
            if (army != null && defender != null) {
                val power = armyUnits(civ, army).sumOf { MilitaryAi.unitPower(it.second) }
                if (power < MilitaryAi.unitPower(defender)) return true
            }
        }
        return false
    }

    /** CvAIOperationFoundCity::ShouldAbort: an escort that does not come: after 15 turns, or off our land, with no city committed, go alone */
    private fun escortedShouldAbort(civ: Civilization, operation: AiOperation) {
        val army = operation.firstArmy ?: return
        val operations = civ.ai.operations
        if (operation.state == OperationState.RECRUITING_UNITS && operation.escorted) {
            val muster = musterTile(civ, operation)
            if (muster?.getOwner() != civ || civ.gameInfo.turns - army.slots[0].estimatedTurn > ESCORT_WAIT_TURNS) {
                if (operation.committedCities.isEmpty()) {
                    // try to get the escort from existing units that are waiting around
                    grabUnitsFromTheReserves(civ, operation, muster, targetTile(civ, operation))
                    if (army.numSlotsFilled() > 1) {
                        army.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
                        operation.state = OperationState.GATHERING_FORCES
                    } else {
                        // we are going by our lonesome
                        operation.escorted = false
                        operation.slotsToBuild.clear()
                        army.state = ArmyState.MOVING_TO_DESTINATION
                        operation.state = OperationState.MOVING_TO_TARGET
                    }
                }
            }
        } else if (operation.state == OperationState.GATHERING_FORCES && operation.escorted) {
            val settler = army.slots[0].unitId.takeIf { it != -1 }?.let { civ.units.getUnitById(it) }
            if (settler != null && settler.getTile().isWater) {
                // we are going by our lonesome
                operation.escorted = false
                army.state = ArmyState.MOVING_TO_DESTINATION
                operation.state = OperationState.MOVING_TO_TARGET
            }
        }
    }

    /** CvAIOperation::UnitWasRemoved (and CvAIEscortedOperation's): recruiting wants the slot again; below half strength the operation aborts */
    fun unitWasRemoved(operations: AiOperations, operation: AiOperation, army: AiArmy, slot: Int) {
        if (operation.type.isEscorted) {
            // Assumes the civilian is in the first slot of the formation
            if (slot == 0) abort(operation, OperationAbortReason.LOST_CIVILIAN) else operation.escorted = false
            return
        }
        when (operation.state) {
            OperationState.RECRUITING_UNITS -> operation.slotsToBuild.add(SlotRef(operation.id, army.id, slot))
            OperationState.GATHERING_FORCES, OperationState.MOVING_TO_TARGET, OperationState.AT_TARGET -> {
                val entries = operations.slotDefs(army).size
                if (army.numSlotsFilled() <= 0 || army.numSlotsFilled() < entries / 2) abort(operation, OperationAbortReason.HALF_STRENGTH)
            }
            else -> Unit
        }
    }

    /**
     *  CvAIOperation::PercentFromMusterPointToTarget: how far along its route the army that is furthest along is, 0..100 (step distances);
     *  0 while recruiting or gathering, 100 once at the target.
     */
    fun percentFromMusterPointToTarget(civ: Civilization, operation: AiOperation): Int {
        when (operation.state) {
            OperationState.GATHERING_FORCES, OperationState.ABORTED, OperationState.RECRUITING_UNITS -> return 0
            OperationState.AT_TARGET, OperationState.SUCCESSFUL_FINISH -> return 100
            OperationState.MOVING_TO_TARGET -> Unit
        }
        var result = 0
        val muster = musterTile(civ, operation) ?: return 0
        val enemy = enemy(civ, operation)
        val steps = civ.ai.military.steps
        for (army in operation.armies) {
            val goal = tileAt(civ, army.goalTile) ?: continue
            val com = centerOfMass(civ, army) ?: continue
            val musterToTarget = steps.distance(civ, enemy, muster, goal)
            val currentToTarget = steps.distance(civ, enemy, com, goal)
            if (musterToTarget <= 0) return 0
            if (currentToTarget in 0..2) return 100
            if (currentToTarget < 0) continue
            result = max(result, 100 - 100 * currentToTarget / musterToTarget)
        }
        return result
    }

    /** CvAIOperation::DoDelayedDeath: remove the operation if it is to go away */
    fun doDelayedDeath(civ: Civilization, operation: AiOperation): Boolean {
        if (!shouldAbort(civ, operation)) return false
        civ.ai.operations.kill(operation,
            if (operation.state == OperationState.SUCCESSFUL_FINISH) OperationAbortReason.SUCCESS else OperationAbortReason.KILLED)
        return true
    }

    /** CvAIOperation::BuyFinalUnit: the one slot an operation still needs is bought in its start city, with the primary unit AI of the slot */
    fun buyFinalUnit(civ: Civilization, operation: AiOperation): Boolean {
        val city = startCity(civ, operation) ?: return false
        val slot = operation.slotsToBuild.lastOrNull() ?: return false
        val army = operation.armyById(slot.armyId) ?: return false
        val entry = civ.ai.operations.slotDefs(army).getOrNull(slot.slotId) ?: return false
        val type = UnitAiType.ofXmlName(entry.primaryUnitAi) ?: return false
        val unit = UseGoldAutomation.buyEmergencyUnit(civ, type, city) ?: return false
        civ.ai.operations.addUnit(army, unit, slot.slotId)
        operation.slotsToBuild.removeAt(operation.slotsToBuild.lastIndex)
        if (operation.slotsToBuild.isEmpty() && operation.committedCities.isEmpty() && operation.state == OperationState.RECRUITING_UNITS) {
            operation.state = OperationState.GATHERING_FORCES
            for (a in operation.armies) a.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
        }
        return true
    }
    //endregion

    /** Turns a FOUND_CITY settler waits for an escort before it goes alone (CvAIOperationFoundCity::ShouldAbort) */
    private const val ESCORT_WAIT_TURNS = 15
}
