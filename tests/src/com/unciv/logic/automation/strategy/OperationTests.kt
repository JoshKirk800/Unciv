package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.city.ConstructionAutomation
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.unit.tactical.OperationalMoves
import com.unciv.logic.automation.unit.tactical.CityThreat
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.TileMap
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 *  Decision-level checks of the operation and army port (CvAIOperation, CvArmyAI, the operation side of CvMilitaryAI) against the
 *  DLL formulas with the BNW XML values: the city demand for open slots, recruiting from the reserves, the state machine, launch and abort
 *  conditions, attack readiness with the land reserve, and the target score.
 */
@RunWith(GdxTestRunner::class)
class OperationTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City
    private lateinit var enemyCity: City

    private val ops get() = civ.ai.operations

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        game.addBarbarianCiv()
        civ = neutralCiv()
        enemy = neutralCiv()
        city = game.addCity(civ, game.getTile(HexCoord(0, 0)))
        enemyCity = game.addCity(enemy, game.getTile(HexCoord(8, 0)))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        for (tile in game.gameInfo.tileMap.tileList) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
    }

    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    /** A military unit of the civilization; on the first tile around the capital that has none unless [at] is given (one military unit per tile) */
    private fun unit(name: String, at: HexCoord? = null): MapUnit {
        val tile = at?.let { game.getTile(it) } ?: game.getTile(HexCoord(0, 0)).getTilesInDistance(4).first { it.militaryUnit == null && it.civilianUnit == null }
        return game.addUnit(name, civ, tile)
    }

    /** Puts [units] on separate tiles around [center] */
    private fun teleport(units: List<Pair<Int, MapUnit>>, center: HexCoord) {
        val tiles = game.getTile(center).getTilesInDistance(2).filter { it.militaryUnit == null || units.any { (_, u) -> u === it.militaryUnit } }.toList()
        for ((index, pair) in units.withIndex()) { pair.second.removeFromTile(); pair.second.putInTile(tiles[index]) }
    }

    private fun closeDefense() = AiOperationLogic.addOperation(civ, AiOperationType.CITY_CLOSE_DEFENSE, enemy)

    private fun slotCandidate(): ConstructionAutomation.Buildable? =
        ConstructionAutomation(city.cityConstructions).getBuildables().firstOrNull { it.slotOffer != null }

    private fun threatenSecondCity(): City {
        val second = game.addCity(civ, game.getTile(HexCoord(-3, 0)))
        civ.getDiplomacyManager(enemy)!!.declareWar()
        game.addUnit("Warrior", enemy, game.getTile(HexCoord(-4, 0)))
        civ.ai.invalidateTurnCaches()
        return second
    }

    //region City demand (CvCity::GetUnitForOperation)

    @Test
    fun `a city offers the unit of the next open slot with the operation unit weight`() {
        civ.ai.setPersonalityFlavor(Flavor.Offense, 4)
        closeDefense()
        val candidate = slotCandidate()
        assertNotNull(candidate)
        // Required slots COUNTER/DEFENSE and RANGED/DEFENSE: the last open one is built first; no archery yet, so the secondary AI DEFENSE
        assertEquals("Warrior", candidate!!.construction.name)
        assertEquals(UnitAiType.DEFENSE, candidate.slotOffer!!.unitAi)
        assertEquals(2, candidate.slotOffer!!.slot.slotId)
        val warrior = game.ruleset.units["Warrior"]!!
        // 1500 + 250 x (OFFENSE 4 + 0 skipped over) + the unit's own weight
        assertEquals(1500 + 250 * 4 + ProductionAi.unitWeight(city, warrior), candidate.weight)
    }

    @Test
    fun `every offer that is not taken raises the next one by the flavor multiplier`() {
        civ.ai.setPersonalityFlavor(Flavor.Offense, 4)
        closeDefense()
        val first = slotCandidate()!!.weight
        assertEquals(1, civ.ai.military.numberOfTimesOpsBuildSkippedOver)
        assertEquals(first + 250, slotCandidate()!!.weight)
        assertEquals(2, civ.ai.military.numberOfTimesOpsBuildSkippedOver)
    }

    @Test
    fun `units for an attack army weigh 750 plus the offense flavor multiplier`() {
        civ.ai.setPersonalityFlavor(Flavor.Offense, 4)
        civ.ai.military.requestArmy(bySea = false)
        civ.tech.addTechnology("Archery")
        val unit = MilitaryOperations.unitForArmy(city)!!
        assertEquals("Archer", unit.name)
        val difficultyIndex = game.gameInfo.ruleset.difficulties.keys.indexOf(game.gameInfo.difficulty).coerceAtLeast(0)
        val buildables = ConstructionAutomation(city.cityConstructions).getBuildables()
        val army = buildables.first { it.construction.name == "Archer" && it.slotOffer == null }
        assertEquals(750 + 250 * 4 * maxOf(1, difficultyIndex - 5) + ProductionAi.unitWeight(city, unit), army.weight)
    }

    @Test
    fun `a committed city takes its slot off the list, and gives it back when it builds something else`() {
        val other = game.addCity(civ, game.getTile(HexCoord(4, 0)))
        val operation = closeDefense()
        assertEquals(2, operation.slotsToBuild.size)
        val offer = ops.nextUnitForOperationSlot(city)!!
        city.cityConstructions.setCurrentConstruction("Warrior")
        ops.commitToBuild(city, offer, 3)
        assertTrue(ops.isBuildingUnitForOperation(city))
        assertEquals(1, operation.slotsToBuild.size)
        // The other city is offered the other slot, not the same one
        assertTrue(ops.nextUnitForOperationSlot(other)!!.slot != offer.slot)
        // The estimated arrival of the unit: the turns plus half the distance from the city to the army's rally point
        val army = operation.firstArmy!!
        val rally = game.gameInfo.tileMap[army.position!!]
        assertEquals(game.gameInfo.turns + 3 + city.getCenterTile().aerialDistanceTo(rally) / 2, army.slots[offer.slot.slotId].estimatedTurn)
        city.cityConstructions.setCurrentConstruction("Monument")
        ops.refreshCommitments()
        assertFalse(ops.isBuildingUnitForOperation(city))
        assertEquals(2, operation.slotsToBuild.size)
    }

    @Test
    fun `the unit a city trained for a slot joins the army with the slot's unit AI, and the last one starts the gathering`() {
        val operation = closeDefense()
        val warrior = game.ruleset.units["Warrior"]!!
        for (index in 0 until 2) {
            val offer = ops.nextUnitForOperationSlot(city)!!
            city.cityConstructions.setCurrentConstruction("Warrior")
            ops.commitToBuild(city, offer, 2)
            assertEquals(OperationState.RECRUITING_UNITS, operation.state)
            val trained = unit(warrior.name)
            assertTrue(ops.finishedBuilding(trained, city))
            assertEquals(trained.id, operation.firstArmy!!.slots[offer.slot.slotId].unitId)
            assertEquals(UnitAiType.DEFENSE, UnitAiTypes.current(trained))
            assertEquals(index == 1, operation.state == OperationState.GATHERING_FORCES)
        }
        assertEquals(ArmyState.WAITING_FOR_UNITS_TO_CATCH_UP, operation.firstArmy!!.state)
        // A unit trained for nothing is not part of any operation
        assertFalse(ops.finishedBuilding(unit("Warrior"), city))
    }

    @Test
    fun `the final unit bought for an operation fills the last slot, not the one its city committed to`() {
        civ.tech.addTechnology("Bronze Working")
        val operation = closeDefense()
        val offer = ops.nextUnitForOperationSlot(city)!!
        // The city trains a spearman for its slot, the very unit the purchase for the other (COUNTER) slot recommends
        val spearman = game.ruleset.units["Spearman"]!!
        city.cityConstructions.setCurrentConstruction(spearman.name)
        ops.commitToBuild(city, AiOperations.SlotOffer(offer.slot, spearman, UnitAiType.DEFENSE, true), 3)
        val last = operation.slotsToBuild.single()
        assertTrue(operation.hasOneMoreSlotToFill())
        civ.addGold(1000)
        assertTrue(AiOperationLogic.buyFinalUnit(civ, operation))
        // CvMilitaryAI::BuyEmergencyUnit creates the unit without bUseToSatisfyOperation: the city still trains its own one
        assertTrue(ops.isBuildingUnitForOperation(city))
        assertTrue(operation.slotsToBuild.isEmpty())
        assertEquals(-1, operation.firstArmy!!.slots[offer.slot.slotId].unitId)
        assertTrue(operation.firstArmy!!.slots[last.slotId].unitId != -1)
    }

    @Test
    fun `an idle city that chooses its production while the final unit is bought does not take the slot`() {
        civ.tech.addTechnology("Bronze Working")
        val other = game.addCity(civ, game.getTile(HexCoord(4, 0)))
        val operation = closeDefense()
        val offer = ops.nextUnitForOperationSlot(other)!!
        other.cityConstructions.setCurrentConstruction("Warrior")
        ops.commitToBuild(other, offer, 3)
        city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Idle.name)
        civ.addGold(1000)
        assertTrue(AiOperationLogic.buyFinalUnit(civ, operation))
        assertTrue(operation.slotsToBuild.isEmpty())
        assertFalse(ops.isBuildingUnitForOperation(city))
    }
    //endregion

    //region Recruiting from the reserves

    @Test
    fun `an operation takes the free units that fit its slots and starts gathering when all required slots are filled`() {
        val warrior = unit("Warrior")
        val archer = unit("Archer", HexCoord(1, 0))
        val operation = closeDefense()
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        assertTrue(operation.slotsToBuild.isEmpty())
        assertEquals(operation.firstArmy, ops.armyOf(warrior))
        assertEquals(operation.firstArmy, ops.armyOf(archer))
        assertTrue(ops.canTacticalAiInterrupt(warrior)) // assembling armies may be interrupted
    }

    @Test
    fun `scouts, wounded units and units that just finished an operation are no reserves`() {
        val explorer = unit("Warrior")
        UnitAiTypes.set(explorer, UnitAiType.EXPLORE)
        val wounded = unit("Archer", HexCoord(1, 0))
        wounded.health = 79
        val deployed = unit("Warrior", HexCoord(0, 1))
        ops.deployFromOperationTurns[deployed.id] = game.gameInfo.turns
        val operation = closeDefense()
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertEquals(2, operation.slotsToBuild.size)
        assertNull(ops.armyOf(explorer)); assertNull(ops.armyOf(wounded)); assertNull(ops.armyOf(deployed))
        // The turn after the cooldown is over, AI_TACTICAL_MAP_TEMP_ZONE_TURNS (4) turns after the deploy turn, the unit is free again
        game.gameInfo.turns += 5
        assertTrue(AiOperationLogic.isFreeForOperation(civ, deployed, naval = false))
    }

    @Test
    fun `losing units of an operation that is still recruiting asks for them again, below half strength it aborts`() {
        val operation = closeDefense()
        ops.nextUnitForOperationSlot(city)
        val warrior = unit("Warrior")
        val archer = unit("Archer")
        ops.addUnit(operation.firstArmy!!, warrior, 0)
        ops.addUnit(operation.firstArmy!!, archer, 2)
        operation.slotsToBuild.clear()
        operation.state = OperationState.RECRUITING_UNITS
        warrior.destroy()
        ops.reconcileUnits()
        assertEquals(listOf(SlotRef(operation.id, operation.firstArmy!!.id, 0)), operation.slotsToBuild)
        // Gathering: the formation has 4 slots, half strength is 2 units
        operation.state = OperationState.GATHERING_FORCES
        operation.slotsToBuild.clear()
        archer.destroy()
        ops.reconcileUnits()
        assertEquals(OperationState.ABORTED, operation.state)
        assertEquals(OperationAbortReason.HALF_STRENGTH, operation.abortReason)
    }
    //endregion

    //region Operations against cities

    private fun fillAttackReserves(archers: Int = 1, warriors: Int = 2) {
        repeat(archers) { unit("Archer") }
        repeat(warriors) { unit("Warrior") }
    }

    private fun attack(type: AiOperationType = AiOperationType.BASIC_CITY_ATTACK) =
        AiOperationLogic.addOperation(civ, type, enemy, enemyCity, city)

    @Test
    fun `a city attack musters at the start city and heads for a few plots short of the target`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        val operation = attack()
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        assertEquals(city.getCenterTile().position, operation.musterTile)
        assertEquals(enemyCity.getCenterTile().position, operation.targetTile)
        // GetDeployRange 5 / 2 = 2 plots from the end of the route
        val goal = game.gameInfo.tileMap[operation.firstArmy!!.goalTile!!]
        assertEquals(2, goal.aerialDistanceTo(enemyCity.getCenterTile()))
        assertEquals("MUFORMATION_BASIC_CITY_ATTACK_FORCE", operation.firstArmy!!.formation)
    }

    @Test
    fun `an attack without the required units recruits, and only the required slots are built`() {
        val operation = attack()
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        // Required: CITY_BOMBARD/RANGED, ATTACK/DEFENSE, ATTACK/COUNTER
        assertEquals(3, operation.slotsToBuild.size)
        val optional = operation.firstArmy!!.slots.count { it.estimatedTurn == ArmySlot.NOT_INCLUDED }
        assertEquals(4, optional)
    }

    @Test
    fun `progress from the muster to the target counts the steps of the route`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        val operation = attack()
        assertEquals(0, AiOperationLogic.percentFromMusterPointToTarget(civ, operation))
        operation.state = OperationState.MOVING_TO_TARGET
        val army = operation.firstArmy!!
        // The goal is 6 steps from the muster: an army centered 3 steps along is half way
        val units = AiOperationLogic.armyUnits(civ, army)
        teleport(units, HexCoord(3, 0))
        assertEquals(50, AiOperationLogic.percentFromMusterPointToTarget(civ, operation))
        // Within 2 steps of the goal the army counts as there
        teleport(units, HexCoord(5, 0))
        assertEquals(100, AiOperationLogic.percentFromMusterPointToTarget(civ, operation))
        operation.state = OperationState.AT_TARGET
        assertEquals(100, AiOperationLogic.percentFromMusterPointToTarget(civ, operation))
    }

    @Test
    fun `an army that gathered moves out, and one within the deploy range of its target hands over and sets the war goal`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        val operation = attack()
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation)) // all at the muster point
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        assertEquals(ArmyState.MOVING_TO_DESTINATION, operation.firstArmy!!.state)
        teleport(AiOperationLogic.armyUnits(civ, operation.firstArmy!!), HexCoord(4, 0))
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        assertTrue(civ.ai.diplomacy.memoryOf(enemy).musteringForAttack)
        assertEquals(1, civ.ai.tactical.temporaryZones.size)
        // The sweep ends the operation as a success and marks its units as deployed
        val unitIds = AiOperationLogic.armyUnits(civ, operation.firstArmy!!).map { it.second.id }
        ops.sweep()
        assertTrue(ops.operations.isEmpty())
        assertTrue(unitIds.all { ops.deployFromOperationTurn(it) == game.gameInfo.turns })
    }

    @Test
    fun `an army unit with no path to its muster point stays where it is`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        val operation = attack()
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        val (_, stranded) = AiOperationLogic.armyUnits(civ, operation.firstArmy!!).first()
        val island = game.getTile(HexCoord(-8, 0))
        for (neighbor in island.neighbors) game.setTileTerrain(neighbor.position, "Ocean") // no embarking without Optics
        stranded.removeFromTile(); stranded.putInTile(island)
        OperationalMoves.moveOperation(civ, operation, AutomationCache())
        assertEquals(island, stranded.getTile())
    }

    @Test
    fun `a city attack ends when the target city is no longer the enemy's`() {
        fillAttackReserves()
        val operation = attack()
        assertFalse(AiOperationLogic.shouldAbort(civ, operation))
        enemyCity.moveToCiv(civ)
        assertTrue(AiOperationLogic.shouldAbort(civ, operation))
    }

    @Test
    fun `an operation gathering at a target that was captured aborts`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        val operation = attack()
        enemyCity.moveToCiv(civ)
        AiOperationLogic.checkOnTarget(civ, operation)
        assertEquals(OperationState.ABORTED, operation.state)
        assertEquals(OperationAbortReason.TARGET_ALREADY_CAPTURED, operation.abortReason)
    }

    @Test
    fun `the formation of a sneak attack is the early rush for a bold civilization in the first 50 turns`() {
        game.gameInfo.turns = 10
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 5)
        assertEquals("MUFORMATION_EARLY_RUSH", AiOperationLogic.formationName(civ, AiOperationType.SNEAK_CITY_ATTACK))
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 4)
        assertEquals("MUFORMATION_BASIC_CITY_ATTACK_FORCE", AiOperationLogic.formationName(civ, AiOperationType.SNEAK_CITY_ATTACK))
    }
    //endregion

    //region Attack readiness and launching (CvMilitaryAI::IsAttackReady, RequestSpecificAttack)

    @Test
    fun `an attack needs its land units to fit into the reserve beyond the mandatory one`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        civ.ai.military.updateBaseData(civ)
        val target = MilitaryTarget(city, enemyCity)
        // Three units fill the three required slots but all of them are needed for defense
        assertTrue(MilitaryOperations.landReservesAvailable(civ) < 3)
        assertFalse(MilitaryOperations.requestSpecificAttack(civ, target, 0))
        assertTrue(ops.operations.isEmpty())
        repeat(6) { unit("Warrior") }
        civ.ai.military.updateBaseData(civ)
        assertTrue(MilitaryOperations.landReservesAvailable(civ) >= 3)
        assertTrue(MilitaryOperations.requestSpecificAttack(civ, target, 0))
        assertEquals(1, ops.numOperationsOfType(AiOperationType.BASIC_CITY_ATTACK))
    }

    @Test
    fun `units in armies do not count as reserves`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        fillAttackReserves()
        repeat(6) { unit("Warrior") }
        civ.ai.military.updateBaseData(civ)
        assertTrue(MilitaryOperations.requestSpecificAttack(civ, MilitaryTarget(city, enemyCity), 0))
        civ.ai.military.updateBaseData(civ)
        assertEquals(ops.operations.first().firstArmy!!.numSlotsFilled(), civ.ai.military.numLandUnitsInArmies)
        // The running attack is past recruiting: IsAttackReady says no to a second one of the same type
        assertFalse(MilitaryOperations.isAttackReady(civ, "MUFORMATION_BASIC_CITY_ATTACK_FORCE", AiOperationType.BASIC_CITY_ATTACK))
    }

    @Test
    fun `an attack that is one required unit short starts if the civilization is willing to build one`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        unit("Warrior")
        repeat(8) { unit("Archer") } // two fill the bombard slots, the others are spare land units for the reserve
        civ.ai.military.updateBaseData(civ)
        val fillable = MilitaryOperations.numberOfFillableSlots(civ, ops.formationDef("MUFORMATION_BASIC_CITY_ATTACK_FORCE")!!, false)
        assertEquals(1, fillable.required - fillable.filled) // the ATTACK/COUNTER slot
        assertTrue(fillable.landReservesUsed <= MilitaryOperations.landReservesAvailable(civ))
        assertFalse(MilitaryOperations.requestSpecificAttack(civ, MilitaryTarget(city, enemyCity), 0))
        assertTrue(MilitaryOperations.requestSpecificAttack(civ, MilitaryTarget(city, enemyCity), 1))
        val operation = ops.firstOfType(AiOperationType.BASIC_CITY_ATTACK)!!
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertEquals(1, operation.slotsToBuild.size)
    }
    //endregion

    //region Barbarian camps and the kill rules (CvMilitaryAI::UpdateOperations)

    private fun placeCamp(at: HexCoord = HexCoord(4, 0)) {
        game.getTile(at).setImprovement("Barbarian encampment")
        civ.ai.military.scanForBarbarians(civ)
    }

    private fun activate(vararg strategies: String) { for (name in strategies) civ.ai.militaryStrategies[name] = 0 }

    @Test
    fun `a barbarian camp operation starts when eradicating, at peace, not in critical defense and willing to take the risk`() {
        placeCamp()
        civ.ai.military.totalThreatWeight = 1
        MilitaryOperations.updateOperations(civ)
        assertEquals(0, ops.numOperationsOfType(AiOperationType.DESTROY_BARBARIAN_CAMP)) // not eradicating
        activate(MilitaryOperations.ERADICATE_BARBARIANS)
        activate(MilitaryOperations.EMPIRE_DEFENSE_CRITICAL)
        MilitaryOperations.updateOperations(civ)
        assertEquals(0, ops.numOperationsOfType(AiOperationType.DESTROY_BARBARIAN_CAMP)) // critical defense
        civ.ai.militaryStrategies.remove(MilitaryOperations.EMPIRE_DEFENSE_CRITICAL)
        MilitaryOperations.updateOperations(civ)
        val camp = ops.firstOfType(AiOperationType.DESTROY_BARBARIAN_CAMP)!!
        assertEquals(game.getTile(HexCoord(4, 0)).position, camp.targetTile)
        // Only one runs at a time
        MilitaryOperations.updateOperations(civ)
        assertEquals(1, ops.numOperationsOfType(AiOperationType.DESTROY_BARBARIAN_CAMP))
    }

    @Test
    fun `a barbarian camp operation is not started when the barbarian threat is smaller than half of the total threat`() {
        placeCamp()
        civ.ai.military.totalThreatWeight = 100 // 100 / 2 >= the camp's major threat weight 3
        activate(MilitaryOperations.ERADICATE_BARBARIANS)
        MilitaryOperations.updateOperations(civ)
        assertEquals(0, ops.numOperationsOfType(AiOperationType.DESTROY_BARBARIAN_CAMP))
        // A barbarian that holds one of our workers makes up our mind
        val captive = game.addUnit("Worker", game.gameInfo.getBarbarianCivilization(), game.getTile(HexCoord(5, 0)))
        captive.originalOwner = civ.civID
        MilitaryOperations.updateOperations(civ)
        assertEquals(1, ops.numOperationsOfType(AiOperationType.DESTROY_BARBARIAN_CAMP))
        assertTrue(ops.firstOfType(AiOperationType.DESTROY_BARBARIAN_CAMP)!!.civilianRescue)
    }

    @Test
    fun `a barbarian camp operation is killed when war breaks out or the camp is gone`() {
        placeCamp()
        civ.ai.military.totalThreatWeight = 1
        activate(MilitaryOperations.ERADICATE_BARBARIANS)
        MilitaryOperations.updateOperations(civ)
        assertEquals(1, ops.numOperationsOfType(AiOperationType.DESTROY_BARBARIAN_CAMP))
        game.getTile(HexCoord(4, 0)).removeImprovement()
        ops.sweep()
        assertEquals(0, ops.numOperationsOfType(AiOperationType.DESTROY_BARBARIAN_CAMP))

        placeCamp(HexCoord(3, 0))
        MilitaryOperations.updateOperations(civ)
        assertEquals(1, ops.numOperationsOfType(AiOperationType.DESTROY_BARBARIAN_CAMP))
        activate(MilitaryOperations.AT_WAR)
        MilitaryOperations.updateOperations(civ)
        assertEquals(0, ops.numOperationsOfType(AiOperationType.DESTROY_BARBARIAN_CAMP))
    }

    @Test
    fun `a camp operation whose units are weaker than the camp's defender is dropped, once recruiting is over`() {
        placeCamp()
        game.addUnit("Swordsman", game.gameInfo.getBarbarianCivilization(), game.getTile(HexCoord(4, 0)))
        unit("Warrior"); unit("Archer", HexCoord(1, 0))
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.DESTROY_BARBARIAN_CAMP)
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        // FAST_ATTACK/DEFENSE is the required slot: the Warrior fills it (DEFENSE), 2 more power than the Archer? the Swordsman is stronger
        assertTrue(AiOperationLogic.shouldAbort(civ, operation))
    }
    //endregion

    //region City defense operations (CvAIOperationCityCloseDefense, UpdateOperations kill rules)

    @Test
    fun `the defense operation targets the most threatened city, else the city nearest the enemy capital`() {
        val second = threatenSecondCity()
        civ.ai.invalidateTurnCaches()
        assertTrue(civ.ai.plotDanger.cityThreat(second) > 0)
        assertEquals(second, CityThreat.mostThreatenedCity(civ))
        assertEquals(second.getCenterTile().position, closeDefense().targetTile)
        // Without a threat: the city closest to the enemy's capital (the capital at (0, 0) is nearer to (8, 0) than (-3, 0))
        game.gameInfo.tileMap[HexCoord(-4, 0)].getUnits().toList().forEach { it.destroy() }
        civ.ai.invalidateTurnCaches()
        assertNull(CityThreat.mostThreatenedCity(civ))
        ops.operations.clear()
        assertEquals(city.getCenterTile().position, closeDefense().targetTile)
    }

    @Test
    fun `a defense operation is killed when its city is no longer threatened, and is not launched by the update`() {
        val second = threatenSecondCity()
        activate(MilitaryOperations.AT_WAR)
        val operation = closeDefense()
        assertEquals(second.getCenterTile().position, operation.targetTile)
        MilitaryOperations.updateOperations(civ)
        assertEquals("a threatened city keeps its operation", 1, ops.numOperationsOfType(AiOperationType.CITY_CLOSE_DEFENSE))
        game.gameInfo.tileMap[HexCoord(-4, 0)].getUnits().toList().forEach { it.destroy() }
        civ.ai.invalidateTurnCaches()
        MilitaryOperations.updateOperations(civ)
        assertEquals(0, ops.numOperationsOfType(AiOperationType.CITY_CLOSE_DEFENSE))
        // BNW's UpdateOperations never launches close defense or rapid response (commented out)
        MilitaryOperations.updateOperations(civ)
        assertEquals(0, ops.operations.size)
    }

    @Test
    fun `without a war all attack and defense operations are killed`() {
        fillAttackReserves()
        attack()
        closeDefense()
        assertEquals(2, ops.operations.size)
        MilitaryOperations.updateOperations(civ)
        assertTrue(ops.operations.isEmpty())
    }
    //endregion

    //region Target selection (CvMilitaryAI::ScoreTarget, EvaluateMilitaryApproaches, ShouldAttackBySea)

    private fun target(path: Int = 5, bySea: Boolean = false): MilitaryTarget {
        val target = MilitaryTarget(city, enemyCity)
        target.musterNearbyUnitPower = 100000
        target.targetNearbyUnitPower = 0
        target.pathLength = path
        target.attackBySea = bySea
        return target
    }

    private fun score(target: MilitaryTarget, type: AiOperationType = AiOperationType.BASIC_CITY_ATTACK) = MilitaryTargets.scoreTarget(civ, target, type)

    @Test
    fun `a shorter route scores 16, 8, 4 or 2 times the base`() {
        val short = score(target(9))
        assertTrue(abs(short - 2 * score(target(10))) <= 2)
        assertTrue(abs(score(target(10)) - 2 * score(target(15))) <= 2)
        assertTrue(abs(score(target(15)) - 2 * score(target(20))) <= 2)
        assertTrue(short > 0)
    }

    @Test
    fun `a muster city with an Airport doubles the score of a land target, not of one reached by sea`() {
        val land = score(target(25))
        val sea = score(target(25, bySea = true))
        assertTrue(land > 0 && sea > 0)
        city.cityConstructions.addBuilding("Airport")
        assertTrue("land ${score(target(25))} plain $land", abs(score(target(25)) - 2 * land) <= 2)
        assertEquals(sea, score(target(25, bySea = true)))
    }

    @Test
    fun `a sneak attack cubes the distance factor, a recapture and the original capital multiply the score`() {
        enemyCity.isOriginalCapital = false
        val plain = score(target())
        assertTrue("sneak ${score(target(9), AiOperationType.SNEAK_CITY_ATTACK)} plain ${score(target(9))}",
            score(target(9), AiOperationType.SNEAK_CITY_ATTACK) > 100 * score(target(9)))
        enemyCity.isOriginalCapital = true
        assertTrue("capital ${score(target())} plain $plain", abs(score(target()) - plain * 250 / 100) <= 2)
        enemyCity.isOriginalCapital = false
        enemyCity.foundingCivObject = civ
        assertTrue("recapture ${score(target())} plain $plain", abs(score(target()) - plain * 150 / 100) <= 2)
    }

    @Test
    fun `a city another operation already heads for scores a tenth`() {
        val plain = score(target())
        val operation = attack()
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertTrue(abs(score(target()) - plain / 10) <= 2)
    }

    @Test
    fun `the force ratio is capped at ten times and weighs the score`() {
        val strong = target(); strong.musterNearbyUnitPower = 10_000_000
        val capped = score(strong)
        val weak = target(); weak.musterNearbyUnitPower = 1
        assertTrue(score(weak) < capped / 100)
        assertTrue(capped > 0)
    }

    @Test
    fun `approaches are counted by the unusable tiles around the city`() {
        val center = enemyCity.getCenterTile()
        assertEquals(CityAttackApproach.UNRESTRICTED, MilitaryTargets.evaluateMilitaryApproaches(enemyCity, true, false))
        center.neighbors.take(3).forEach { game.setTileTerrain(it.position, "Coast") }
        // Water is blocked unless the attack comes by sea
        assertEquals(CityAttackApproach.NEUTRAL, MilitaryTargets.evaluateMilitaryApproaches(enemyCity, true, false))
        assertEquals(CityAttackApproach.UNRESTRICTED, MilitaryTargets.evaluateMilitaryApproaches(enemyCity, true, true))
        center.neighbors.drop(3).forEach { game.setTileTerrain(it.position, "Coast") }
        assertEquals(CityAttackApproach.NONE, MilitaryTargets.evaluateMilitaryApproaches(enemyCity, true, false))
    }

    @Test
    fun `a land route more than twice the straight distance or none at all sends the attack by sea`() {
        civ.tech.addTechnology("Optics") // units can embark
        val short = target(); MilitaryTargets.shouldAttackBySea(civ, short, 10)
        assertFalse(short.attackBySea); assertEquals(10, short.pathLength)
        val long = target(); MilitaryTargets.shouldAttackBySea(civ, long, 17) // the cities are 8 apart
        assertTrue(long.attackBySea); assertEquals(8, long.pathLength)
        val none = target(); MilitaryTargets.shouldAttackBySea(civ, none, -1)
        assertTrue(none.attackBySea)
    }

    @Test
    fun `without embarkation a missing land route calls the attack off`() {
        val none = target(); MilitaryTargets.shouldAttackBySea(civ, none, -1)
        assertFalse(none.attackBySea); assertEquals(-1, none.pathLength)
    }

    @Test
    fun `the best attack target is found over the land route of the step finder`() {
        civ.getDiplomacyManager(enemy)!!.declareWar()
        unit("Warrior")
        val (target, score) = MilitaryTargets.findBestAttackTarget(civ, AiOperationType.BASIC_CITY_ATTACK, enemy)!!
        assertEquals(enemyCity, target.targetCity)
        assertEquals(city, target.musterCity)
        assertFalse(target.attackBySea)
        assertEquals(8, target.pathLength)
        assertTrue(score > 0)
    }

    @Test
    fun `no revealed city of the enemy, no target`() {
        for (tile in game.gameInfo.tileMap.tileList) tile.setExplored(civ, false)
        assertNull(MilitaryTargets.findBestAttackTarget(civ, AiOperationType.BASIC_CITY_ATTACK, enemy))
    }
    //endregion

    //region Settlers

    /** Grassland everywhere, so that settle sites exist: the civilization values food and expansion */
    private fun fertileLand() {
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        civ.ai.setPersonalityFlavor(Flavor.Growth, 5)
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 3)
        civ.viewableTiles = game.tileMap.values.toSet()
    }

    @Test
    fun `a settler operation waits for an escort when it must, and a unit that finished building joins it`() {
        fertileLand()
        val settler = unit("Settler")
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, settler, -1)
        assertFalse("aborted: ${operation.abortReason}", operation.state == OperationState.ABORTED)
        // No escort at hand and a single city: wait for one (boldness 0)
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertTrue(operation.escorted)
        assertEquals(listOf(SlotRef(operation.id, operation.firstArmy!!.id, 1)), operation.slotsToBuild)
        assertEquals(operation.firstArmy, ops.armyOf(settler))
        // The settler is lost: the operation aborts at once
        settler.destroy()
        ops.reconcileUnits()
        assertEquals(OperationAbortReason.LOST_CIVILIAN, operation.abortReason)
    }

    @Test
    fun `a settler that finds an escort among the reserves gathers with it`() {
        val warrior = unit("Warrior", HexCoord(1, 0))
        fertileLand()
        val settler = unit("Settler")
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, settler, -1)
        assertFalse("aborted: ${operation.abortReason}", operation.state == OperationState.ABORTED)
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        assertEquals(operation.firstArmy, ops.armyOf(warrior))
        assertEquals(1, ops.slotIndexOf(operation.firstArmy!!, warrior))
        // The escort comes to the settler, then both move on
        OperationalMoves.moveOperation(civ, operation, AutomationCache())
        assertEquals(settler.getTile(), warrior.getTile())
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
    }

    @Test
    fun `a quick colony needs no escort and moves at once`() {
        fertileLand()
        val settler = unit("Settler")
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.QUICK_COLONIZE, settler, -1)
        assertFalse("aborted: ${operation.abortReason}", operation.state == OperationState.ABORTED)
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
        assertFalse(operation.escorted)
        assertTrue(operation.slotsToBuild.isEmpty())
    }
    //endregion
}
