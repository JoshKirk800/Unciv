package com.unciv.logic.automation.strategy

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.unit.NavalOperationMoves
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
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

/**
 *  The naval operations and the nukes (CvAIOperation: CvAINavalOperation and its subclasses, CvAINavalEscortedOperation, CvAIOperationNavalAttack,
 *  CvAIOperationNukeAttack; CvMilitaryAI: RequestPureNavalAttack, the naval part of UpdateOperations, RequestNukeAttack and its trigger;
 *  CvTacticalAI: the naval formation moves) with the BNW XML values. A channel of coast water separates our city at x=0 from the enemy's at x=14.
 */
@RunWith(GdxTestRunner::class)
class NavalOperationTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City
    private lateinit var enemyCity: City

    private val ops get() = civ.ai.operations

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(20)
        game.addBarbarianCiv()
        civ = neutralCiv()
        enemy = neutralCiv()
        for (x in 1..13) for (y in -6..6) game.tileMap.getOrNull(x, y)?.let { game.setTileTerrain(it.position, "Coast") }
        city = game.addCity(civ, tile(0))
        enemyCity = game.addCity(enemy, tile(14))
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        civ.tech.unitsCanEmbark = true
        for (tile in game.gameInfo.tileMap.tileList) { tile.setExplored(civ, true); tile.setExplored(enemy, true) }
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    private fun tile(x: Int, y: Int = 0) = game.getTile(HexCoord(x, y))

    /** Putting units on the map recomputes what we see: after placing them, we see all of it again */
    private fun seeAll() {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    private fun ship(name: String, x: Int, y: Int = 0): MapUnit = game.addUnit(name, civ, tile(x, y))

    /** The four required ships of the pure naval formation: two that attack at sea, two assault ships */
    private fun fleet(): List<MapUnit> = listOf(ship("Quinquereme", 2, 0), ship("Quinquereme", 2, 1), ship("Galleass", 3, 0), ship("Galleass", 3, 1))

    // region Pure naval city attack

    @Test
    fun `a pure naval attack starts when all but the number of units we are willing to build of the required slots are filled`() {
        val ships = fleet()
        assertTrue(MilitaryOperations.requestPureNavalAttack(civ, enemy, 0))
        val operation = ops.firstOfType(AiOperationType.PURE_NAVAL_CITY_ATTACK)!!
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        assertTrue(ships.all { ops.armyOf(it) != null })
        assertEquals("the target is the enemy city", enemyCity.getCenterTile().position, operation.targetTile)
        assertTrue("it musters on water next to our port", game.gameInfo.tileMap[operation.musterTile!!].isWater)
        // no city trains units for it: its area is the water at the muster
        assertEquals(-1, operation.area)
    }

    @Test
    fun `one missing ship is built when willing, two missing are too many`() {
        val ships = listOf(ship("Quinquereme", 2, 0), ship("Quinquereme", 2, 1), ship("Galleass", 3, 0))
        assertFalse("one short and not willing", MilitaryOperations.requestPureNavalAttack(civ, enemy, 0))
        assertNull(ops.firstOfType(AiOperationType.PURE_NAVAL_CITY_ATTACK))
        assertTrue(MilitaryOperations.requestPureNavalAttack(civ, enemy, 1))
        val operation = ops.firstOfType(AiOperationType.PURE_NAVAL_CITY_ATTACK)!!
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertEquals(1, operation.slotsToBuild.size)
        assertEquals(3, ships.count { ops.armyOf(it) != null })
        // the one more slot is bought in the start city
        assertTrue(operation.hasOneMoreSlotToFill())
        ops.kill(operation, OperationAbortReason.KILLED)
        ships[2].destroy()
        assertFalse("two short", MilitaryOperations.requestPureNavalAttack(civ, enemy, 1))
    }

    @Test
    fun `a pure naval attack ends in success once the fleet is within a tile of the city, the tactical AI gets a zone there`() {
        fleet()
        MilitaryOperations.requestPureNavalAttack(civ, enemy, 0)
        val operation = ops.firstOfType(AiOperationType.PURE_NAVAL_CITY_ATTACK)!!
        val army = operation.firstArmy!!
        operation.state = OperationState.MOVING_TO_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        army.position = tile(13).position // next to the city
        AiOperationLogic.armyInPosition(civ, operation, army)
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        assertTrue(civ.ai.tactical.temporaryZones.any { it.position == enemyCity.getCenterTile().position })
    }

    @Test
    fun `a pure naval attack is called off when its city is no longer the enemy's`() {
        fleet()
        MilitaryOperations.requestPureNavalAttack(civ, enemy, 0)
        val operation = ops.firstOfType(AiOperationType.PURE_NAVAL_CITY_ATTACK)!!
        assertFalse(AiOperationLogic.shouldAbort(civ, operation))
        enemyCity.getCenterTile().setOwningCity(null)
        enemyCity.getCenterTile().getOwner() // the owner of the tile went with the city
        enemyCity.moveToCiv(civ)
        assertTrue(AiOperationLogic.shouldAbort(civ, operation))
    }
    // endregion

    // region Superiority and bombardment

    /** The required slots of the naval squadron: an attack ship and an assault ship (a second assault ship takes the optional second attack slot first) */
    private fun squadron(y: Int): List<MapUnit> = listOf(ship("Quinquereme", 2, y), ship("Galleass", 3, y), ship("Galleass", 4, y))

    @Test
    fun `naval superiority starts with a squadron at hand when a colonization is under way or we are at war, never otherwise`() {
        civ.ai.setPersonalityFlavor(Flavor.Naval, 10)
        squadron(0)
        game.addUnit("Trireme", enemy, tile(5, 2))
        MilitaryOperations.launchOpportunityNavalOperations(civ)
        assertNull("no reason", ops.firstOfType(AiOperationType.NAVAL_SUPERIORITY))
        civ.ai.militaryStrategies[MilitaryOperations.AT_WAR] = 1
        MilitaryOperations.launchOpportunityNavalOperations(civ)
        val operation = ops.firstOfType(AiOperationType.NAVAL_SUPERIORITY)!!
        assertEquals("the enemy ship is the target", tile(5, 2).position, operation.targetTile)
        assertEquals("it sails at once", OperationState.MOVING_TO_TARGET, operation.state)
    }

    @Test
    fun `the number of superiority and bombardment operations is held to half the naval flavor`() {
        civ.ai.setPersonalityFlavor(Flavor.Naval, 0)
        civ.ai.militaryStrategies[MilitaryOperations.AT_WAR] = 1
        squadron(0)
        game.addUnit("Trireme", enemy, tile(5, 2))
        MilitaryOperations.launchOpportunityNavalOperations(civ) // 0 running <= 0
        assertEquals(1, ops.numOperationsOfType(AiOperationType.NAVAL_SUPERIORITY))
        squadron(3)
        MilitaryOperations.launchOpportunityNavalOperations(civ) // 1 running > 0
        assertEquals(1, ops.numOperationsOfType(AiOperationType.NAVAL_SUPERIORITY))
    }

    @Test
    fun `the superiority target is the lightest reachable enemy ship, embarked units counting two thirds`() {
        ship("Quinquereme", 1, 0)
        game.addUnit("Trireme", enemy, tile(7, 3)) // far
        game.addUnit("Trireme", enemy, tile(3, -2)) // near
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.NAVAL_SUPERIORITY)
        assertEquals(tile(3, -2).position, operation.targetTile)
    }

    @Test
    fun `with no ship to hunt a superiority fleet patrols next to the nearest coastal enemy city`() {
        ship("Quinquereme", 1, 0)
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.NAVAL_SUPERIORITY)
        val target = game.gameInfo.tileMap[operation.targetTile!!]
        assertTrue(target.isWater)
        assertEquals(1, target.aerialDistanceTo(enemyCity.getCenterTile()))
    }

    @Test
    fun `a bombardment fleet targets the shallow water next to an enemy unit on the coast, cities are left alone`() {
        ship("Quinquereme", 2, 1)
        ship("Galleass", 2, 0)
        game.addUnit("Warrior", enemy, tile(0, 3)) // on the coast of our side, next to water at x=1
        seeAll()
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.NAVAL_BOMBARDMENT)
        val target = game.gameInfo.tileMap[operation.targetTile!!]
        assertTrue(target.isWater)
        assertEquals(1, target.aerialDistanceTo(tile(0, 3)))
    }

    @Test
    fun `bombardment without a defender on the coast aborts`() {
        ship("Quinquereme", 2, 1)
        ship("Galleass", 2, 0)
        seeAll()
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.NAVAL_BOMBARDMENT)
        assertEquals(OperationState.ABORTED, operation.state)
        assertEquals(OperationAbortReason.LOST_TARGET, operation.abortReason)
    }

    @Test
    fun `a bombardment fleet that reaches its goal gives the tactical AI a one turn bombardment zone`() {
        ship("Quinquereme", 2, 1)
        ship("Galleass", 2, 0)
        game.addUnit("Warrior", enemy, tile(0, 3))
        seeAll()
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.NAVAL_BOMBARDMENT)
        val army = operation.firstArmy!!
        operation.state = OperationState.MOVING_TO_TARGET
        army.position = army.goalTile
        AiOperationLogic.armyInPosition(civ, operation, army)
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        val zone = civ.ai.tactical.temporaryZones.single()
        assertEquals(game.gameInfo.turns + 1, zone.lastTurn)
    }
    // endregion

    // region Invasions

    /** The five required slots of the naval invasion: a bombard unit, a second one, a melee unit and two ships */
    private fun invasionForce(): List<MapUnit> = listOf(
        game.addUnit("Catapult", civ, tile(0, 1)), game.addUnit("Spearman", civ, tile(0, -1)), game.addUnit("Swordsman", civ, tile(-1, 0)),
        ship("Quinquereme", 2, 0), ship("Galleass", 2, 1),
    )

    private fun invade(type: AiOperationType = AiOperationType.NAVAL_ATTACK): AiOperation =
        AiOperationLogic.addOperation(civ, type, enemy, enemyCity, city)

    @Test
    fun `an invasion fills its slots from ships and land units that can embark and gathers at our port`() {
        val force = invasionForce()
        val operation = invade()
        assertEquals(OperationState.GATHERING_FORCES, operation.state)
        assertTrue(force.all { ops.armyOf(it) != null })
        assertEquals(city.getCenterTile().position, operation.musterTile)
        assertEquals("the area is the target's landmass", enemyCity.getCenterTile().getContinent(), operation.area)
        assertEquals("MUFORMATION_NAVAL_INVASION", operation.firstArmy!!.formation)
    }

    @Test
    fun `land units that cannot embark do not fill an invasion`() {
        civ.tech.unitsCanEmbark = false
        invasionForce()
        val operation = invade()
        assertEquals(OperationState.RECRUITING_UNITS, operation.state)
        assertEquals(3, operation.slotsToBuild.size) // only the two ships are there
    }

    @Test
    fun `once ships may cross the ocean a coast-bound ship no longer fills an invasion, an ocean-going one does`() {
        civ.tech.embarkedUnitsCanEnterOcean = true
        val coastal = ship("Trireme", 2, 0)
        val ocean = ship("Frigate", 2, 1)
        val operation = invade()
        assertNull("a Trireme cannot enter the ocean", ops.armyOf(coastal))
        assertNotNull(ops.armyOf(ocean))
        ops.kill(operation, OperationAbortReason.KILLED)
        civ.tech.embarkedUnitsCanEnterOcean = false
        invade()
        assertNotNull("without the ocean tech the formation takes it", ops.armyOf(coastal))
    }

    @Test
    fun `an invasion gathering at the port puts its ships on water and its land units on land, one unit to a tile`() {
        val force = invasionForce()
        val operation = invade()
        val cache = AutomationCache()
        NavalOperationMoves.moveOperation(civ, operation, cache)
        assertTrue(force.all { it in cache.processedUnits })
        assertEquals(force.size, force.map { it.getTile() }.toSet().size)
        for (unit in force) assertEquals(unit.name, unit.baseUnit.isWaterUnit, unit.getTile().isWater)
    }

    @Test
    fun `the army moves to the waters next to the enemy city at the pace of its slowest ship`() {
        val force = invasionForce()
        val operation = invade()
        val army = operation.firstArmy!!
        operation.state = OperationState.MOVING_TO_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        // everyone embarks and sails: put the force on water at our end of the channel
        val start = listOf(tile(1, 0), tile(1, 1), tile(1, -1), tile(2, 0), tile(2, 1))
        for ((unit, tile) in force.zip(start)) { unit.removeFromTile(); unit.putInTile(tile) }
        val before = force.map { it.getTile().aerialDistanceTo(enemyCity.getCenterTile()) }
        NavalOperationMoves.moveOperation(civ, operation, AutomationCache())
        val after = force.map { it.getTile().aerialDistanceTo(enemyCity.getCenterTile()) }
        assertTrue("the force advanced: $before -> $after", after.sum() < before.sum() && before.zip(after).all { (b, a) -> a <= b })
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
    }

    @Test
    fun `an invasion within twice the city attack range of its target hands over to the tactical AI with a naval invasion zone`() {
        val force = invasionForce()
        val operation = invade()
        val army = operation.firstArmy!!
        operation.state = OperationState.MOVING_TO_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        val near = listOf(tile(9, 0), tile(9, 1), tile(9, -1), tile(10, 0), tile(10, 1))
        for ((unit, tile) in force.zip(near)) { unit.removeFromTile(); unit.putInTile(tile) }
        assertTrue(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        assertTrue(civ.ai.tactical.temporaryZones.single { it.position == enemyCity.getCenterTile().position }.navalInvasion)
    }

    @Test
    fun `an invasion still far from its target keeps moving`() {
        val force = invasionForce()
        val operation = invade()
        val army = operation.firstArmy!!
        operation.state = OperationState.MOVING_TO_TARGET
        army.state = ArmyState.MOVING_TO_DESTINATION
        val far = listOf(tile(1, 0), tile(1, 1), tile(1, -1), tile(2, 0), tile(2, 1))
        for ((unit, tile) in force.zip(far)) { unit.removeFromTile(); unit.putInTile(tile) }
        assertFalse(AiOperationLogic.checkOnTarget(civ, operation))
        assertEquals(OperationState.MOVING_TO_TARGET, operation.state)
    }

    @Test
    fun `a city-state invasion and a sneak invasion use their own formations`() {
        assertEquals("MUFORMATION_CITY_STATE_INVASION", AiOperationLogic.formationName(civ, AiOperationType.CITY_STATE_NAVAL_ATTACK))
        assertEquals("MUFORMATION_NAVAL_INVASION", AiOperationLogic.formationName(civ, AiOperationType.NAVAL_SNEAK_ATTACK))
        assertTrue(AiOperationType.NAVAL_ATTACK.isNavalInvasion && AiOperationType.NAVAL_ATTACK.mixedLandNaval)
    }
    // endregion

    // region Escorting embarked units

    @Test
    fun `a warship goes beside the embarked unit that is alone and out of moves, the most endangered one`() {
        val safe = game.addUnit("Warrior", civ, tile(3, 0))
        val endangered = game.addUnit("Warrior", civ, tile(3, 4))
        safe.currentMovement = 0f
        endangered.currentMovement = 0f
        game.addUnit("Trireme", enemy, tile(5, 5)) // threatens the one at (3, 4)
        val escort = ship("Quinquereme", 5, 2)
        seeAll()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        val ctx = com.unciv.logic.automation.unit.tactical.TacticalContext(
            civ, map, danger, AutomationCache(), listOf(escort), com.unciv.logic.automation.unit.tactical.TacticalTargets.find(civ, map, danger))
        com.unciv.logic.automation.unit.tactical.NavalMoves.plotEscortEmbarkedMoves(ctx)
        assertEquals(1, escort.getTile().aerialDistanceTo(endangered.getTile()))
        assertTrue(escort in ctx.cache.processedUnits)
    }

    // endregion

    // region Nukes

    private fun atWarWithNukes() = game.addUnit("Atomic Bomb", civ, tile(0)).also { civ.ai.militaryStrategies[MilitaryOperations.AT_WAR] = 1 }

    @Test
    fun `a nuke is launched at an enemy that nuked us or that we nuked`() {
        atWarWithNukes()
        assertFalse(MilitaryOperations.shouldLaunchNuke(civ, enemy))
        enemy.ai.diplomacy.memoryOf(civ).numTimesNuked = 1 // we were nuked by them... as the original reads it: they nuked us
        assertTrue(MilitaryOperations.shouldLaunchNuke(civ, enemy))
        enemy.ai.diplomacy.memoryOf(civ).numTimesNuked = 0
        civ.ai.diplomacy.memoryOf(enemy).numTimesNuked = 1
        assertTrue(MilitaryOperations.shouldLaunchNuke(civ, enemy))
    }

    @Test
    fun `without nukes or at peace there is no launch`() {
        civ.ai.diplomacy.memoryOf(enemy).numTimesNuked = 1
        assertFalse("no nukes", MilitaryOperations.shouldLaunchNuke(civ, enemy))
        atWarWithNukes()
        civ.getDiplomacyManager(enemy)!!.makePeace()
        assertFalse("at peace", MilitaryOperations.shouldLaunchNuke(civ, enemy))
    }

    @Test
    fun `a war that is projected to end in destruction rolls for the nuke every turn, with the use nuke flavor`() {
        atWarWithNukes()
        civ.ai.diplomacy.memoryOf(enemy).warProjection = com.unciv.logic.automation.diplomacy.WarProjection.Destruction
        civ.ai.setPersonalityFlavor(Flavor.UseNuke, 0)
        assertFalse("two rolls of 0..9 below 0", MilitaryOperations.shouldLaunchNuke(civ, enemy))
        civ.ai.setPersonalityFlavor(Flavor.UseNuke, 10)
        assertTrue("always below 10", MilitaryOperations.shouldLaunchNuke(civ, enemy))
    }

    @Test
    fun `a projection that got worse also rolls, a steady one does not`() {
        atWarWithNukes()
        civ.ai.setPersonalityFlavor(Flavor.UseNuke, 10)
        val memory = civ.ai.diplomacy.memoryOf(enemy)
        memory.lastWarProjection = com.unciv.logic.automation.diplomacy.WarProjection.Good
        memory.warProjection = com.unciv.logic.automation.diplomacy.WarProjection.Good
        assertFalse(MilitaryOperations.shouldLaunchNuke(civ, enemy))
        memory.warProjection = com.unciv.logic.automation.diplomacy.WarProjection.Defeat
        assertTrue(MilitaryOperations.shouldLaunchNuke(civ, enemy))
        memory.lastWarProjection = com.unciv.logic.automation.diplomacy.WarProjection.Unknown
        assertFalse("the last one is not known", MilitaryOperations.shouldLaunchNuke(civ, enemy))
    }

    @Test
    fun `the nuke goes for the enemy city with the best blast value in range`() {
        // Atomic Bomb range 10; the nuke in our city reaches both of these cities, not the far one
        val nuke = game.addUnit("Atomic Bomb", civ, tile(0))
        val big = game.addCity(enemy, tile(-1, 8))
        val small = game.addCity(enemy, tile(0, 7))
        big.population.setPopulation(8)
        small.population.setPopulation(3)
        val probe = AiOperation().also { it.type = AiOperationType.NUKE_ATTACK; it.enemyCivId = enemy.civID }
        val best = NavalOperationLogic.findBestNukeTarget(civ, probe)!!
        assertEquals(nuke, best.first)
        assertEquals("big ${NavalOperationLogic.nukeCityValue(civ, nuke, big)} small ${NavalOperationLogic.nukeCityValue(civ, nuke, small)} chose ${best.second.getCenterTile().position}", big, best.second)
    }

    @Test
    fun `a blast that would hit a third party or our own units scores low, and a capital counts double`() {
        val nuke = game.addUnit("Atomic Bomb", civ, tile(0))
        val clean = NavalOperationLogic.nukeCityValue(civ, nuke, enemyCity)
        val bystander = neutralCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(bystander)
        val bystanderCity = game.addCity(bystander, tile(16, 0))
        game.addUnit("Warrior", bystander, bystanderCity.getCenterTile())
        assertTrue("a third party's unit within the blast: -1000", NavalOperationLogic.nukeCityValue(civ, nuke, enemyCity) < clean - 900)
        // our own unit within the blast costs 2, doubled with the rest of the value in a capital
        val withBystander = NavalOperationLogic.nukeCityValue(civ, nuke, enemyCity)
        val ours = game.addUnit("Warrior", civ, tile(14, 1))
        val withOurs = NavalOperationLogic.nukeCityValue(civ, nuke, enemyCity)
        assertEquals("$clean $withBystander $withOurs", 4, withBystander - withOurs)
    }

    @Test
    fun `a nuke operation uses the nuke at once and ends in success`() {
        val nuke = game.addUnit("Atomic Bomb", civ, tile(0))
        game.addCity(enemy, tile(0, 7)).population.setPopulation(6)
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.NUKE_ATTACK, enemy)
        assertEquals(OperationState.SUCCESSFUL_FINISH, operation.state)
        assertTrue("the bomb self-destructs", nuke.isDestroyed)
    }

    @Test
    fun `a nuke operation with no nuke in range of an enemy city aborts`() {
        game.addCity(civ, tile(-13))
        game.addUnit("Atomic Bomb", civ, tile(-13))
        val operation = AiOperationLogic.addOperation(civ, AiOperationType.NUKE_ATTACK, enemy)
        assertEquals(OperationState.ABORTED, operation.state)
    }
    // endregion
}
