package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The tactical AI's turn (Brave New World data): which units it commandeers (CvTacticalAI::CommandeerUnits), withdrawing from enemy land
 *  (PlotWithdrawMoves), and the emergency purchases of PlotEmergencyPurchases / CvMilitaryAI::BuyEmergencyBuilding.
 */
@RunWith(BaseTestRunner::class)
class TacticalAiTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(16)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(y: Int) = game.tileMap[0, y]

    private fun see() {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
    }

    private fun run(cache: AutomationCache = AutomationCache()): AutomationCache {
        see()
        TacticalAi.automate(civ, civ.units.getCivUnits().toList(), cache) { false }
        return cache
    }

    // region Commandeering

    @Test
    fun `a unit is commandeered when its tile has danger, an enemy is within 8 tiles, and not otherwise`() {
        val quiet = game.addUnit("Warrior", civ, tile(-12))
        val near = game.addUnit("Warrior", civ, tile(12))
        game.addUnit("Warrior", enemy, tile(5)) // 7 from `near`, 17 from `quiet`
        val inDanger = game.addUnit("Warrior", civ, tile(3)) // two tiles from the enemy
        see()
        val commandeered = TacticalAi.commandeerUnits(civ, listOf(quiet, near, inDanger), AutomationCache())
        assertEquals(setOf(near, inDanger), commandeered.toSet())
    }

    @Test
    fun `an enemy city within 8 tiles counts like an enemy unit`() {
        val unit = game.addUnit("Warrior", civ, tile(0))
        game.addCity(enemy, tile(8))
        see()
        assertEquals(listOf(unit), TacticalAi.commandeerUnits(civ, listOf(unit), AutomationCache()))
        assertTrue(TacticalAi.nearVisibleEnemy(unit, 8))
        assertFalse(TacticalAi.nearVisibleEnemy(unit, 7))
    }

    @Test
    fun `civilians, units that cannot move and units already moved are not commandeered, a Great General is`() {
        game.addUnit("Warrior", enemy, tile(4))
        val worker = game.addUnit("Worker", civ, tile(2))
        val general = game.addUnit("Great General", civ, tile(1))
        val spent = game.addUnit("Warrior", civ, tile(0)).also { it.currentMovement = 0f }
        val processed = game.addUnit("Warrior", civ, tile(-1))
        val cache = AutomationCache()
        cache.processedUnits.add(processed)
        see()
        assertEquals(listOf(general), TacticalAi.commandeerUnits(civ, listOf(worker, general, spent, processed), cache))
    }

    @Test
    fun `an enemy we cannot see does not pull units in`() {
        val unit = game.addUnit("Warrior", civ, tile(0))
        game.addUnit("Warrior", enemy, tile(6))
        civ.viewableTiles = emptySet()
        civ.ai.invalidateTurnCaches()
        assertTrue(TacticalAi.commandeerUnits(civ, listOf(unit), AutomationCache()).isEmpty())
    }

    // endregion
    // region Safety and healing in the turn

    @Test
    fun `a hurt unit under ranged fire flees and is marked processed, a healthy unit with nothing to do is left to the rest of the AI`() {
        val hurt = game.addUnit("Warrior", civ, tile(0))
        hurt.health = 60
        val healthy = game.addUnit("Warrior", civ, tile(6))
        game.addUnit("Archer", enemy, tile(2))
        val cache = run()
        assertTrue(hurt in cache.processedUnits)
        assertNotEquals(tile(0), hurt.getTile())
        assertFalse(healthy in cache.processedUnits)
        assertTrue(healthy.currentMovement > 0f)
    }

    @Test
    fun `a hurt unit that nothing threatens heals where it stands`() {
        val hurt = game.addUnit("Warrior", civ, tile(0))
        hurt.health = 60
        game.addUnit("Warrior", enemy, tile(7)) // in sight but out of reach, so the unit is commandeered but in no danger
        val cache = run()
        assertTrue(hurt in cache.processedUnits)
        assertEquals(tile(0), hurt.getTile())
        assertTrue(hurt.isFortified())
    }

    @Test
    fun `the unit AI's attack stage comes before withdrawing, fleeing and healing`() {
        val hurt = game.addUnit("Warrior", civ, tile(0))
        hurt.health = 60
        game.addUnit("Warrior", enemy, tile(6))
        see()
        val attackers = ArrayList<MapUnit>()
        TacticalAi.automate(civ, listOf(hurt), AutomationCache()) { attackers.add(it); true }
        assertEquals(listOf(hurt), attackers)
        assertFalse("it attacked, so it was not told to heal", hurt.isFortified())
    }

    // endregion
    // region Withdrawing

    @Test
    fun `units in enemy land the enemy dominates fall back towards our nearest city`() {
        val home = game.addCity(civ, tile(-10))
        val enemyCity = game.addCity(enemy, tile(6))
        val unit = game.addUnit("Warrior", civ, tile(5))
        assertEquals("the scenario puts our unit in their land", enemy, unit.getTile().getOwner())
        // behind their city, so that the way home is free
        for (tile in enemyCity.getCenterTile().getTilesAtDistance(2).filter { it.position.y >= 6 }.take(4)) game.addUnit("Swordsman", enemy, tile)
        see()
        val zone = civ.ai.tactical.map.zoneOf(unit.getTile())!!
        assertEquals(TacticalTerritory.Enemy, zone.territory)
        assertEquals(TacticalDominance.Enemy, zone.dominance)

        val before = unit.getTile().aerialDistanceTo(home.getCenterTile())
        val cache = run()
        assertTrue(unit in cache.processedUnits)
        assertEquals("it fell back", true, unit.getTile().aerialDistanceTo(home.getCenterTile()) < before)
        assertEquals(TacticalPosture.Withdraw, civ.ai.tactical.findPosture(enemy.civName, false, enemyCity.id))
    }

    @Test
    fun `units stay in enemy land that an operation just reached`() {
        game.addCity(civ, tile(-10))
        val enemyCity = game.addCity(enemy, tile(6))
        val unit = game.addUnit("Warrior", civ, tile(5))
        for (tile in enemyCity.getCenterTile().getTilesAtDistance(2).filter { it.position.y >= 6 }.take(4)) game.addUnit("Swordsman", enemy, tile)
        civ.ai.tactical.addTemporaryZone(enemyCity.getCenterTile(), TacticalTargetType.City)
        run()
        assertNotEquals(TacticalPosture.Withdraw, civ.ai.tactical.findPosture(enemy.civName, false, enemyCity.id))
    }

    // endregion
    // region Emergency purchases

    private fun cityWithEnemiesAround(position: Int, enemies: Int = 4, unit: String = "Swordsman"): City {
        val city = game.addCity(civ, tile(position))
        val around = city.getCenterTile().getTilesInDistance(3).filter { it != city.getCenterTile() }.sortedBy { it.aerialDistanceTo(city.getCenterTile()) }
        for (tile in around.take(enemies)) game.addUnit(unit, enemy, tile)
        return city
    }

    private fun setGold(amount: Int) = civ.addGold(amount - civ.gold)

    private fun gold(amount: Int) {
        setGold(amount)
        civ.tech.addTechnology("Masonry")
        civ.tech.addTechnology("Archery")
    }

    private fun archers() = civ.units.getCivUnits().count { it.name == "Archer" }

    @Test
    fun `an own city with enemies near that we do not dominate buys its walls first and then a ranged unit`() {
        val city = cityWithEnemiesAround(0)
        gold(5000)
        see()
        val zone = civ.ai.tactical.map.zoneOf(city.getCenterTile())!!
        assertNotEquals("the scenario has us under pressure", TacticalDominance.Friendly, zone.dominance)
        run()
        assertTrue(city.cityConstructions.isBuilt("Walls"))
        assertEquals(1, archers())
        assertTrue(civ.gold < 5000)
    }

    @Test
    fun `with gold for the walls only the building is bought`() {
        val city = cityWithEnemiesAround(0)
        gold(0)
        val walls = game.ruleset.buildings["Walls"]!!
        setGold(walls.getStatBuyCost(city, Stat.Gold)!!)
        run()
        assertTrue(city.cityConstructions.isBuilt("Walls"))
        assertEquals(0, archers())
        assertEquals(0, civ.gold)
    }

    @Test
    fun `a city we dominate buys nothing`() {
        val city = game.addCity(civ, tile(0))
        for (tile in city.getCenterTile().getTilesAtDistance(1).take(5)) game.addUnit("Swordsman", civ, tile)
        game.addUnit("Warrior", enemy, tile(4))
        gold(5000)
        see()
        val zone = civ.ai.tactical.map.zoneOf(city.getCenterTile())!!
        assertEquals(TacticalDominance.Friendly, zone.dominance)
        assertEquals(1, zone.enemyUnitCount)
        run()
        assertFalse(city.cityConstructions.isBuilt("Walls"))
        assertEquals(0, archers())
        assertEquals(5000, civ.gold)
    }

    @Test
    fun `a city without enemy units near buys nothing`() {
        val city = game.addCity(civ, tile(0))
        gold(5000)
        run()
        assertFalse(city.cityConstructions.isBuilt("Walls"))
        assertEquals(5000, civ.gold)
    }

    @Test
    fun `a city the enemy can take next turn is given up unless it is the capital`() {
        val capital = game.addCity(civ, tile(-12))
        val doomed = cityWithEnemiesAround(0, enemies = 18, unit = "Horseman")
        gold(5000)
        see()
        assertTrue("the scenario has attackers that can take the city", CityAttackers.highPriorityUnits(civ).isNotEmpty())
        run()
        assertFalse(doomed.cityConstructions.isBuilt("Walls"))
        assertEquals(0, archers())
        assertEquals(5000, civ.gold)
        assertTrue("the capital was not the city given up", capital.isCapital())
    }

    @Test
    fun `the capital is defended to the last`() {
        val capital = cityWithEnemiesAround(0, enemies = 18, unit = "Horseman")
        gold(5000)
        see()
        assertTrue(CityAttackers.highPriorityUnits(civ).isNotEmpty())
        run()
        assertTrue(capital.cityConstructions.isBuilt("Walls"))
        assertEquals(1, archers())
    }

    // endregion
}
