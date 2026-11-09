package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.unit.tactical.TacticalTargetType
import com.unciv.logic.automation.unit.tactical.TacticalTargets
import com.unciv.logic.battle.Nuke
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine

/**
 *  The naval operations of CvAIOperation.cpp: CvAINavalOperation (CvAIOperationNavalBombardment, CvAIOperationNavalSuperiority,
 *  CvAIOperationPureNavalCityAttack), CvAIOperationNavalAttack and its descendants NavalSneakAttack / NavalCityStateAttack (an invasion:
 *  land units that embark, with warships), and CvAIOperationNukeAttack: Init, target finding, ArmyInPosition, CheckOnTarget. The base
 *  machinery is [AiOperationLogic], the ships are moved by [com.unciv.logic.automation.unit.NavalOperationMoves].
 *
 *  As in the original, land units embark themselves (there are no transports): an invasion army is the land units and the warships of its
 *  slots (UNITAI_ASSAULT_SEA is the Frigate, Galleass and the like), all moving as a formation on water.
 *  Not ported: the colonization party of CvAINavalEscortedOperation (AI_OPERATION_COLONIZE), which no caller in the original ever creates.
 *  Deviations, with the reason: an army's operation area is the one the original gives it
 *  (bombardment, superiority and pure naval attacks: the water at the muster point, which no city lies on, so cities do not train for
 *  them, their ships come from the reserves and the last one is bought; invasions: the landmass of the target); FindBestTarget of the
 *  invasions and the pure naval attack is "obsolete" in the original and is not ported (the target is the city chosen by the military
 *  AI); a naval target is visible to the original's whole-map loops only when revealed, as here.
 */
object NavalOperationLogic {

    private fun tileAt(civ: Civilization, coord: com.unciv.logic.map.HexCoord?): Tile? = coord?.let { civ.gameInfo.tileMap.getOrNull(it.x, it.y) }

    /** CvPlot::isShallowWater */
    fun isShallowWater(tile: Tile) = tile.isWater && tile.getBaseTerrain().isCoast

    /** CvPlot::isCoastalLand: a land tile next to water */
    fun isCoastalLand(tile: Tile) = tile.isLand && tile.neighbors.any { it.isWater }

    /** CvAINavalOperation::GetDeployRange, CvAIOperationPureNavalCityAttack::GetDeployRange */
    fun deployRange(civ: Civilization, operation: AiOperation): Int {
        val rules = civ.gameInfo.ruleset.ai
        return if (operation.type == AiOperationType.PURE_NAVAL_CITY_ATTACK) rules.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE)
        else rules.define(AiDefine.AI_OPERATIONAL_NAVAL_BOMBARDMENT_DEPLOY_RANGE)
    }

    /**
     *  CvMilitaryAI::GetCoastalPlotAdjacentToTarget with an army: the first shallow water tile next to [target] that the army's first unit can
     *  get to (without an army, the first shallow water tile).
     */
    fun coastalPlotAdjacentToTarget(civ: Civilization, target: Tile, army: AiArmy?): Tile? {
        val first = army?.let { AiOperationLogic.armyUnits(civ, it).firstOrNull()?.second }
        for (adjacent in target.neighbors) {
            if (!isShallowWater(adjacent)) continue
            if (first == null) return adjacent
            if (AiOperationLogic.turnsToReach(first, adjacent) != Int.MAX_VALUE) return adjacent
        }
        return null
    }

    //region Init

    /** CvAINavalOperation::GetOperationStartCity: the start city, else the first coastal city */
    fun startCity(civ: Civilization, operation: AiOperation): City? {
        operation.startCityId?.let { id -> civ.cities.firstOrNull { it.id == id }?.let { return it } }
        return civ.cities.firstOrNull { it.isCoastal() }
    }

    /** CvAINavalOperation::FindInitialUnit: the first of our ships that may attack at sea, not scouting and in no army */
    fun findInitialUnit(civ: Civilization): MapUnit? {
        val rules = civ.gameInfo.ruleset.ai
        return civ.units.getCivUnits().firstOrNull {
            UnitAiTypes.current(it) != UnitAiType.EXPLORE_SEA && UnitAiTypes.canHave(it.baseUnit, UnitAiType.ATTACK_SEA, rules) &&
                civ.ai.operations.armyOf(it) == null
        }
    }

    private fun initialUnitOf(civ: Civilization, operation: AiOperation): MapUnit? {
        val army = operation.firstArmy ?: return findInitialUnit(civ)
        return AiOperationLogic.armyUnits(civ, army).firstOrNull()?.second ?: findInitialUnit(civ)
    }

    /**
     *  CvAINavalOperation::SelectInitialMusterPoint: for each water tile next to the start port, the sea path to the army's goal; walking back
     *  from the goal the tile [deployRange] steps from it is the muster point (the first port with such a path decides).
     */
    private fun selectNavalMusterPoint(civ: Civilization, operation: AiOperation, army: AiArmy): Tile? {
        val start = startCity(civ, operation) ?: return null
        val goal = tileAt(civ, army.goalTile) ?: return null
        val range = deployRange(civ, operation)
        for (adjacent in start.getCenterTile().neighbors) {
            if (!adjacent.isWater) continue
            val path = civ.ai.military.steps.path(civ, AiOperationLogic.enemy(civ, operation), adjacent, goal, sea = true) ?: continue
            // spaces from the target: the last tile is 0
            val index = path.size - 1 - range
            if (index >= 0) return path[index]
        }
        return null
    }

    /** CvAIOperationNavalBombardment::Init and CvAIOperationNavalSuperiority::Init: [AiOperationType.NAVAL_BOMBARDMENT], [AiOperationType.NAVAL_SUPERIORITY] */
    fun initFleet(civ: Civilization, operation: AiOperation) {
        val operations = civ.ai.operations
        if (startCity(civ, operation) == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.NO_MUSTER); return }
        val army = operations.newArmy(operation, AiOperationLogic.formationName(civ, operation.type))
        val target = findFleetTarget(civ, operation)
        if (target == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.LOST_TARGET); return }
        operation.targetTile = target.position
        army.goalTile = target.position
        val muster = selectNavalMusterPoint(civ, operation, army)
        if (muster == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.NO_MUSTER); return }
        operation.musterTile = muster.position
        army.position = muster.position
        operation.area = muster.getContinent()
        operations.buildListOfUnitsWeStillNeedToBuild(operation)
        // try to get as many units as possible from existing units that are waiting around
        if (AiOperationLogic.grabUnitsFromTheReserves(civ, operation, muster, muster)) {
            army.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
            // the superiority fleet is on its way as soon as it is together, it never gathers on its own
            operation.state = if (operation.type == AiOperationType.NAVAL_SUPERIORITY) OperationState.MOVING_TO_TARGET else OperationState.GATHERING_FORCES
        } else operation.state = OperationState.RECRUITING_UNITS
    }

    /**
     *  CvAIOperationPureNavalCityAttack::Init: the fleet musters on a shallow water tile next to our port [muster], its goal is the enemy city
     *  [target].
     */
    fun initPureNavalAttack(civ: Civilization, operation: AiOperation, target: City?, muster: City?) {
        val operations = civ.ai.operations
        val army = operations.newArmy(operation, AiOperationLogic.formationName(civ, operation.type))
        if (target == null || muster == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.LOST_TARGET); return }
        operation.targetTile = target.getCenterTile().position
        army.goalTile = target.getCenterTile().position
        operation.startCityId = muster.id
        // Muster just off the coast
        val coastalMuster = coastalPlotAdjacentToTarget(civ, muster.getCenterTile(), army)
        if (coastalMuster == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.NO_MUSTER); return }
        operation.area = coastalMuster.getContinent()
        operation.musterTile = coastalMuster.position
        army.position = coastalMuster.position
        operations.buildListOfUnitsWeStillNeedToBuild(operation)
        if (AiOperationLogic.grabUnitsFromTheReserves(civ, operation, coastalMuster, coastalMuster)) {
            army.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
            operation.state = OperationState.GATHERING_FORCES
        } else operation.state = OperationState.RECRUITING_UNITS
    }

    /**
     *  CvAIOperationNavalAttack::Init (also the sneak attack and the city-state attack): the invasion leaves [muster], our port, for the enemy
     *  city [target]; units are taken from the reserves for the muster (no target check, as in the original).
     */
    fun initNavalInvasion(civ: Civilization, operation: AiOperation, target: City?, muster: City?) {
        val operations = civ.ai.operations
        if (muster == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.NO_MUSTER); return }
        operation.startCityId = muster.id
        val army = operations.newArmy(operation, AiOperationLogic.formationName(civ, operation.type))
        if (target == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.LOST_TARGET); return }
        val targetTile = target.getCenterTile()
        operation.targetTile = targetTile.position
        operation.area = targetTile.getContinent()
        army.goalTile = targetTile.position
        operation.musterTile = muster.getCenterTile().position
        army.position = muster.getCenterTile().position
        operations.buildListOfUnitsWeStillNeedToBuild(operation)
        if (AiOperationLogic.grabUnitsFromTheReserves(civ, operation, muster.getCenterTile(), null)) {
            army.state = ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP
            operation.state = OperationState.GATHERING_FORCES
        } else operation.state = OperationState.RECRUITING_UNITS
    }

    /**
     *  CvAIOperationNukeAttack::Init: the nuke and enemy city of [FindBestTarget] fill the operation's one slot and, if it can, the nuke goes off at
     *  once (the operation is static: it only works with the unit already in place).
     */
    fun initNuke(civ: Civilization, operation: AiOperation) {
        val operations = civ.ai.operations
        val army = operations.newArmy(operation, AiOperationLogic.formationName(civ, operation.type))
        val best = findBestNukeTarget(civ, operation)
        if (best == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.LOST_TARGET); return }
        val (unit, city) = best
        operation.targetTile = city.getCenterTile().position
        army.goalTile = city.getCenterTile().position
        operation.musterTile = unit.getTile().position
        army.position = unit.getTile().position
        operations.addUnit(army, unit, 0)
        operation.state = OperationState.AT_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        nukeInPosition(civ, operation, army)
    }
    //endregion

    //region Targets

    /** The enemy units on [tile] (CvUnit::isEnemy for our team: at war with us, barbarians included) */
    private fun enemyUnitsOn(civ: Civilization, tile: Tile) = tile.getUnits().filter { it.civ != civ && civ.isAtWarWith(it.civ) }.toList()

    /** CvAIOperationNavalBombardment::FindBestTarget / CvAIOperationNavalSuperiority::FindBestTarget */
    fun findFleetTarget(civ: Civilization, operation: AiOperation): Tile? {
        val unit = initialUnitOf(civ, operation) ?: return null
        return if (operation.type == AiOperationType.NAVAL_BOMBARDMENT) findBombardmentTarget(civ, unit) else findSuperiorityTarget(civ, unit)
    }

    /**
     *  The shallow water tile next to a coastal land tile with an enemy defender (cities are left alone, they fire back) that [unit] reaches
     *  in the fewest turns.
     */
    private fun findBombardmentTarget(civ: Civilization, unit: MapUnit): Tile? {
        var best: Tile? = null
        var bestTurns = Int.MAX_VALUE
        for (tile in civ.gameInfo.tileMap.tileList) {
            if (!civ.hasExplored(tile) || !isCoastalLand(tile)) continue
            // Enemy defender here?
            if (TacticalTargets.visibleEnemyDefender(civ, tile) == null) continue
            for (adjacent in tile.neighbors) {
                if (!isShallowWater(adjacent)) continue
                val turns = AiOperationLogic.turnsToReach(unit, adjacent)
                if (turns < bestTurns) { bestTurns = turns; best = adjacent }
            }
        }
        return best
    }

    /**
     *  The water tile of an enemy unit (embarked ones count 2/3) or trader at sea (1/3) weighted by base moves x distance from [unit]; of the lightest the first one
     *  [unit] can reach, fewer turns winning among equal weights (GetReachablePlot). None: a water tile next to the nearest coastal enemy city,
     *  else next to the nearest coastal barbarian camp, that it can reach (the last such one).
     */
    private fun findSuperiorityTarget(civ: Civilization, unit: MapUnit): Tile? {
        val weighted = ArrayList<Pair<Tile, Int>>()
        var enemyCity: City? = null
        var enemyCityDistance = Int.MAX_VALUE
        var camp: Tile? = null
        var campDistance = Int.MAX_VALUE
        val baseMoves = unit.baseUnit.movement
        for (tile in civ.gameInfo.tileMap.tileList) {
            if (!civ.hasExplored(tile)) continue
            if (tile.isWater) {
                val units = enemyUnitsOn(civ, tile)
                val traders = civ.tradeRoutes.getEnemyTradersAt(tile)
                if (units.isEmpty() && traders.isEmpty()) continue
                val distance = unit.getTile().aerialDistanceTo(tile)
                for (enemy in units) {
                    var score = baseMoves * distance
                    if (enemy.isEmbarked()) score = score * 2 / 3 // we want to take out embarked units more than ships
                    weighted.add(tile to score)
                }
                // we want to plunder trade routes if possible
                repeat(traders.size) { weighted.add(tile to baseMoves * distance / 3) }
            } else if (tile.isCityCenter() && isCoastalLand(tile)) { // Backup plan is a coastal enemy city
                val city = tile.getCity() ?: continue
                if (civ.isAtWarWith(city.civ) || city.civ.isBarbarian) {
                    val distance = unit.getTile().aerialDistanceTo(tile)
                    if (distance < enemyCityDistance) { enemyCityDistance = distance; enemyCity = city }
                }
            } else if (isCoastalLand(tile) && tile.isBarbarianEncampment()) {
                val distance = unit.getTile().aerialDistanceTo(tile)
                if (distance < campDistance) { campDistance = distance; camp = tile }
            }
        }
        reachablePlot(unit, weighted)?.let { return it }
        // None found, patrol over near closest enemy coastal city, or if not that a water tile adjacent to a camp
        val patrolled = enemyCity?.getCenterTile() ?: camp ?: return null
        var result: Tile? = null
        for (adjacent in patrolled.neighbors)
            if (isShallowWater(adjacent) && AiOperationLogic.turnsToReach(unit, adjacent) != Int.MAX_VALUE) result = adjacent
        return result
    }

    /** GetReachablePlot: the lightest plot [unit] can get to, among plots of that weight the one in the fewest turns */
    private fun reachablePlot(unit: MapUnit, plots: List<Pair<Tile, Int>>): Tile? {
        var found: Tile? = null
        var foundWeight = 0
        var foundTurns = 0
        for ((plot, weight) in plots.sortedBy { it.second }) {
            if (found != null && weight > foundWeight) break // Already found one of a lower weight
            val turns = AiOperationLogic.turnsToReach(unit, plot)
            if (turns == Int.MAX_VALUE) continue
            if (found == null || turns < foundTurns) {
                found = plot; foundWeight = weight; foundTurns = turns
                if (foundTurns == 1) break // Not getting better than this
            }
        }
        return found
    }

    /**
     *  CvAIOperationNukeAttack::FindBestTarget: of our nukes and the enemy's cities in the nuke's range, the pair with the best value: the city's
     *  population less a fifth of its damage; each tile in the blast radius adds or removes by who owns it (ours -1, -5 with an improvement, -1000
     *  with a resource; the enemy's +1, +2, +5; a third party's -1000, which would start a war), each unit on it (ours -2, the enemy's +2, a third
     *  party's -1000); a capital counts double.
     */
    fun findBestNukeTarget(civ: Civilization, operation: AiOperation): Pair<MapUnit, City>? {
        val enemy = AiOperationLogic.enemy(civ, operation) ?: return null
        var best: Pair<MapUnit, City>? = null
        var bestValue = 0
        for (nuke in civ.units.getCivUnits()) {
            if (!canNuke(nuke)) continue
            for (city in enemy.cities) {
                if (nuke.getTile().aerialDistanceTo(city.getCenterTile()) > nuke.getRange()) continue
                val value = nukeCityValue(civ, nuke, city)
                if (value > bestValue) { bestValue = value; best = nuke to city }
            }
        }
        return best
    }

    /** CvUnit::canNuke: a nuclear weapon that can still move */
    fun canNuke(unit: MapUnit) = unit.isNuclearWeapon() && unit.hasMovement()

    /** The value of the blast on [city] (see [findBestNukeTarget]) */
    fun nukeCityValue(civ: Civilization, nuke: MapUnit, city: City): Int {
        var value = city.population.population - (city.getMaxHealth() - city.health) * 100 / city.getMaxHealth() / 5
        city.getCenterTile().forEachTileInDistance(nuke.getNukeBlastRadius()) { tile ->
            val owner = tile.getOwner()
            if (owner == civ) {
                value -= 1
                if (tile.improvement != null && !tile.improvementIsPillaged) {
                    value -= 5
                    if (tile.resource != null) value -= 1000 // we aren't nuking our own resources
                }
            } else if (owner != null && civ.isAtWarWith(owner)) {
                value += 1
                if (tile.improvement != null && !tile.improvementIsPillaged) {
                    value += 2
                    if (tile.resource != null) value += 5 // we like nuking their resources
                }
            } else if (owner != null) value -= 1000 // this will trigger a war
            for (unit in tile.getUnits()) {
                if (unit.civ == civ) value -= 2
                else if (civ.isAtWarWith(unit.civ)) value += 2
                else if (owner != null) value -= 1000 // this will trigger a war
            }
        }
        if (city.isCapital()) value *= 2
        return value
    }
    //endregion

    //region ArmyInPosition

    /** CvAIOperationNavalAttack::ArmyInPosition: within twice the usual range of the target the invasion is handed to the tactical AI */
    fun navalInvasionInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        if (operation.state != OperationState.MOVING_TO_TARGET) return AiOperationLogic.baseArmyInPosition(operation, army)
        val target = AiOperationLogic.targetTile(civ, operation) ?: return false
        val com = AiOperationLogic.centerOfMass(civ, army, sea = true)
        // Are we within tactical range of our target? (larger than usual range for a naval attack)
        if (com != null && com.aerialDistanceTo(target) <= civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE) * 2) {
            // Notify Diplo AI we're in place for attack
            AiOperationLogic.enemy(civ, operation)?.let { civ.ai.diplomacy.memoryOf(it).musteringForAttack = true }
            // Notify tactical AI to focus on this area
            civ.ai.tactical.addTemporaryZone(target, TacticalTargetType.City, navalInvasion = true)
            operation.state = OperationState.SUCCESSFUL_FINISH
            return true
        }
        return false
    }

    /**
     *  CvAIOperationNavalBombardment::ArmyInPosition: gathered, a better target may have shown up; arrived (the army is at its goal), the
     *  tactical AI gets a bombardment zone around the target.
     */
    fun bombardmentInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        when (operation.state) {
            OperationState.GATHERING_FORCES -> {
                val changed = AiOperationLogic.baseArmyInPosition(operation, army)
                retargetFleet(civ, operation, army)
                return changed
            }
            OperationState.MOVING_TO_TARGET -> {
                if (army.position == army.goalTile) {
                    val target = AiOperationLogic.targetTile(civ, operation)
                    if (target != null) civ.ai.tactical.addTemporaryZone(target, TacticalTargetType.BombardmentZone,
                        turns = civ.gameInfo.ruleset.ai.define(AiDefine.AI_TACTICAL_MAP_BOMBARDMENT_ZONE_TURNS))
                    operation.state = OperationState.SUCCESSFUL_FINISH
                }
                return false
            }
            else -> return AiOperationLogic.baseArmyInPosition(operation, army)
        }
    }

    /** CvAIOperationNavalSuperiority::ArmyInPosition: the fleet never reaches a final target, it looks for the next one every turn */
    fun superiorityInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        if (operation.state != OperationState.GATHERING_FORCES && operation.state != OperationState.MOVING_TO_TARGET)
            return AiOperationLogic.baseArmyInPosition(operation, army)
        val changed = AiOperationLogic.baseArmyInPosition(operation, army)
        retargetFleet(civ, operation, army)
        return changed
    }

    /** The shared tail of the bombardment's and superiority's ArmyInPosition: revisit the target, abort with none, else move the goal */
    private fun retargetFleet(civ: Civilization, operation: AiOperation, army: AiArmy) {
        val better = findFleetTarget(civ, operation)
        if (better == null) { AiOperationLogic.abortOperation(operation, OperationAbortReason.NO_TARGET); return }
        if (better.position == operation.targetTile) return
        // Reset our destination to be a few plots shy of the final target
        val position = tileAt(civ, army.position) ?: return
        val deploy = civ.ai.military.steps.xPlotsFromEnd(civ, AiOperationLogic.enemy(civ, operation), position, better, deployRange(civ, operation), sea = true)
        if (deploy != null) { army.goalTile = deploy.position; operation.targetTile = better.position }
    }

    /**
     *  CvAIOperationPureNavalCityAttack::ArmyInPosition: gathered, the target must still be the enemy's; within 2 tiles of it the fleet is
     *  handed to the tactical AI with a zone around the city.
     */
    fun pureNavalAttackInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        val target = AiOperationLogic.targetTile(civ, operation)
        when (operation.state) {
            OperationState.GATHERING_FORCES -> {
                val changed = AiOperationLogic.baseArmyInPosition(operation, army)
                // Is target still under enemy control?
                if (target?.getOwner()?.civID != operation.enemyCivId) AiOperationLogic.abortOperation(operation, OperationAbortReason.TARGET_ALREADY_CAPTURED)
                return changed
            }
            OperationState.MOVING_TO_TARGET -> {
                val position = tileAt(civ, army.position)
                if (position != null && target != null && position.aerialDistanceTo(target) < 2) {
                    // Notify tactical AI to focus on this area
                    civ.ai.tactical.addTemporaryZone(target, TacticalTargetType.City)
                    operation.state = OperationState.SUCCESSFUL_FINISH
                }
                return false
            }
            else -> return AiOperationLogic.baseArmyInPosition(operation, army)
        }
    }

    /** CvAIOperationNukeAttack::ArmyInPosition: the nuke goes off at the target if it can */
    fun nukeInPosition(civ: Civilization, operation: AiOperation, army: AiArmy): Boolean {
        val target = AiOperationLogic.targetTile(civ, operation)
        val nuke = AiOperationLogic.armyUnits(civ, army).firstOrNull()?.second
        if (target != null && nuke != null && canNuke(nuke) && Nuke.mayUseNuke(MapUnitCombatant(nuke), target)) {
            AiDecisionLog.log(civ) { "${nuke.name} nukes ${target.getCity()?.name} at ${target.position}" }
            Nuke.NUKE(MapUnitCombatant(nuke), target)
            operation.state = OperationState.SUCCESSFUL_FINISH
        }
        return true
    }
    //endregion

    //region CheckOnTarget

    /**
     *  CvAIOperation::CheckOnTarget for the naval move types. Escort: gathering when the center of mass is within the gather tolerance of the
     *  muster and no unit is further than three times that; moving when it is within twice the city attack range of the target and
     *  no unit further than three times that range. Freeform: the army in position is evaluated every turn. Fleet: as the original's enemy
     *  territory check with the center of mass at sea.
     *  @return whether an army was found in position
     */
    fun checkOnTarget(civ: Civilization, operation: AiOperation): Boolean {
        val range = civ.gameInfo.ruleset.ai.define(AiDefine.AI_OPERATIONAL_CITY_ATTACK_DEPLOY_RANGE)
        for (army in operation.armies.toList()) {
            if (army.numSlotsFilled() < 1) continue
            val com = AiOperationLogic.centerOfMass(civ, army, sea = true)
            val muster = AiOperationLogic.musterTile(civ, operation)
            val target = AiOperationLogic.targetTile(civ, operation)
            when (operation.type.moveType) {
                OperationMoveType.NavalEscort -> when (operation.state) {
                    OperationState.GATHERING_FORCES -> {
                        val tolerance = gatherTolerance(civ, operation, army)
                        if (com != null && muster != null && com.aerialDistanceTo(muster) <= tolerance &&
                            AiOperationLogic.furthestUnitDistance(civ, army, muster) <= tolerance * 3) {
                            AiOperationLogic.armyInPosition(civ, operation, army); return true
                        }
                    }
                    OperationState.MOVING_TO_TARGET ->
                        if (com != null && target != null && com.aerialDistanceTo(target) <= range * 2 &&
                            AiOperationLogic.furthestUnitDistance(civ, army, target) <= range * 3) {
                            AiOperationLogic.armyInPosition(civ, operation, army); return true
                        }
                    else -> Unit
                }
                OperationMoveType.FreeformNaval -> if (operation.state == OperationState.GATHERING_FORCES || operation.state == OperationState.MOVING_TO_TARGET) {
                    // We want to recompute a new target each turn. So call ArmyInPosition regardless of return status
                    AiOperationLogic.armyInPosition(civ, operation, army)
                    val tolerance = gatherTolerance(civ, operation, army)
                    val newTarget = AiOperationLogic.targetTile(civ, operation)
                    if (com != null && newTarget != null && muster != null && com.aerialDistanceTo(newTarget) <= tolerance &&
                        AiOperationLogic.furthestUnitDistance(civ, army, muster) <= tolerance * 3 / 2) return true
                }
                OperationMoveType.NavalFleet -> when (operation.state) {
                    OperationState.GATHERING_FORCES -> {
                        val tolerance = gatherTolerance(civ, operation, army)
                        if (com != null && muster != null && com.aerialDistanceTo(muster) <= tolerance &&
                            AiOperationLogic.furthestUnitDistance(civ, army, muster) <= tolerance * 3 / 2) {
                            AiOperationLogic.armyInPosition(civ, operation, army); return true
                        }
                    }
                    OperationState.MOVING_TO_TARGET ->
                        if (com != null && target != null && com.aerialDistanceTo(target) <= range &&
                            AiOperationLogic.furthestUnitDistance(civ, army, target) <= range * 3 / 2) {
                            AiOperationLogic.armyInPosition(civ, operation, army); return true
                        }
                    else -> Unit
                }
                else -> Unit
            }
        }
        return false
    }

    /**
     *  CvAIOperation::GetGatherTolerance for naval armies: the gather range for the number of units if the muster tile and the ones around it
     *  can take them (usable for gathering, water, and no ocean unless the whole army can cross it), else 3.
     *  The original checks the muster cell for every tile around it, so all of the tiles count or none does.
     */
    fun gatherTolerance(civ: Civilization, operation: AiOperation, army: AiArmy): Int {
        val units = army.numSlotsFilled()
        if (units < 1) return 0
        val range = AiOperationLogic.gatherRangeForXUnits(units)
        val muster = AiOperationLogic.musterTile(civ, operation) ?: return range
        val map = civ.ai.tactical.map
        var usable = map.canUseForOperationGathering(muster) && muster.isWater
        if (usable && !allOceanGoing(civ, army) && map.isOcean(muster)) usable = false
        val valid = if (usable) (2 * range + 1) * (2 * range + 1) else 0
        return if (valid > units) range else 3
    }

    /** CvArmyAI::IsAllOceanGoing: every ship of the army can enter the ocean (land units do not count) */
    fun allOceanGoing(civ: Civilization, army: AiArmy): Boolean =
        AiOperationLogic.armyUnits(civ, army).all { !it.second.baseUnit.isWaterUnit || !it.second.cache.cannotEnterOceanTiles }
    //endregion
}
