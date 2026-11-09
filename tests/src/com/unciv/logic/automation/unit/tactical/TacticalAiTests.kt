package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
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
        TacticalAi.automate(civ, civ.units.getCivUnits().toList(), cache)
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

    @Test
    fun `a unit with the explore unit AI is never commandeered, however near the enemy, while a unit with another military AI is`() {
        game.addUnit("Warrior", enemy, tile(3))
        val scout = game.addUnit("Scout", civ, tile(1))
        val explorer = game.addUnit("Warrior", civ, tile(0))
        val soldier = game.addUnit("Warrior", civ, tile(2))
        UnitAiTypes.set(explorer, UnitAiType.EXPLORE)
        assertEquals(UnitAiType.EXPLORE, UnitAiTypes.current(scout))
        see()
        assertEquals(listOf(soldier), TacticalAi.commandeerUnits(civ, listOf(scout, explorer, soldier), AutomationCache()))
    }

    @Test
    fun `a unit that can paradrop is commandeered with no enemy near, unless it has moved or stands outside friendly territory`() {
        val home = game.addCity(civ, tile(-12), initialPopulation = 1)
        val ready = game.addUnit("Paratrooper", civ, home.getCenterTile())
        val moved = game.addUnit("Paratrooper", civ, home.getCenterTile().neighbors.first()).also { it.currentMovement = 1f }
        val abroad = game.addUnit("Paratrooper", civ, tile(14))
        val walker = game.addUnit("Warrior", civ, home.getCenterTile().neighbors.last())
        see()
        assertEquals(listOf(ready), TacticalAi.commandeerUnits(civ, listOf(ready, moved, abroad, walker), AutomationCache()))
    }

    // endregion
    // region Safety and healing in the turn

    @Test
    fun `a hurt unit under ranged fire flees and is marked processed, a healthy unit with nothing to attack closes on the enemy`() {
        val hurt = game.addUnit("Warrior", civ, tile(0))
        hurt.health = 60
        val healthy = game.addUnit("Warrior", civ, tile(6))
        game.addUnit("Archer", enemy, tile(2))
        val cache = run()
        assertTrue(hurt in cache.processedUnits)
        assertNotEquals(tile(0), hurt.getTile())
        assertTrue("a healthy unit with no attack to make repositions next to the enemy (PlotRepositionMoves)", healthy in cache.processedUnits)
        assertTrue(healthy.getTile().aerialDistanceTo(tile(2)) < 4)
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

    private fun highPriorityTargets() =
        TacticalTargets.find(civ, civ.ai.tactical.map, civ.ai.plotDanger).all.filter { it.type == TacticalTargetType.HighPriorityUnit }

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
        assertTrue("the scenario has attackers that can take the city", highPriorityTargets().isNotEmpty())
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
        assertTrue(highPriorityTargets().isNotEmpty())
        run()
        assertTrue(capital.cityConstructions.isBuilt("Walls"))
        assertEquals(1, archers())
    }

    // endregion
    // region Defensive airlifts (CvTacticalAI::PlotDefensiveAirlifts)

    private fun airportCity(y: Int): City = game.addCity(civ, tile(y)).also { it.cityConstructions.addBuilding("Airport") }

    /** Enemy swordsmen three tiles out on the side of [city] away from the other city, where they threaten it but leave its doors free */
    private fun besiege(city: City, count: Int = 6) {
        val ring = city.getCenterTile().getTilesAtDistance(3).filter { it.position.y > city.getCenterTile().position.y }
        for (tile in ring.take(count)) game.addUnit("Swordsman", enemy, tile)
    }

    private fun context(vararg units: MapUnit): TacticalContext {
        see()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), units.toList(), TacticalTargets.find(civ, map, danger))
    }

    @Test
    fun `a unit in one airport city is airlifted next to the threatened airport city and is finished for the turn`() {
        val threatened = airportCity(0)
        val home = airportCity(-10)
        besiege(threatened)
        val unit = game.addUnit("Warrior", civ, home.getCenterTile())
        val ctx = context(unit)
        val zone = civ.ai.tactical.map.zoneOf(threatened.getCenterTile())!!
        assertNotEquals(TacticalDominance.Friendly, zone.dominance)
        assertTrue(zone.enemyUnitCount > 0)

        assertEquals(listOf(unit), TacticalAi.plotDefensiveAirlifts(ctx, zone))
        assertEquals(1, unit.getTile().aerialDistanceTo(threatened.getCenterTile()))
        assertEquals(0f, unit.currentMovement)
        assertTrue(unit in ctx.cache.processedUnits)
        assertTrue(unit !in ctx.units)
    }

    @Test
    fun `several units go to different tiles next to the city`() {
        val threatened = airportCity(0)
        val home = airportCity(-10)
        besiege(threatened)
        val units = home.getCenterTile().neighbors.take(3).map { game.addUnit("Warrior", civ, it) }.toList() + game.addUnit("Archer", civ, home.getCenterTile())
        val ctx = context(*units.toTypedArray())
        val zone = civ.ai.tactical.map.zoneOf(threatened.getCenterTile())!!
        assertEquals(units, TacticalAi.plotDefensiveAirlifts(ctx, zone))
        assertEquals(4, units.map { it.getTile() }.toSet().size)
        assertTrue(units.all { it.getTile().aerialDistanceTo(threatened.getCenterTile()) == 1 })
    }

    @Test
    fun `nothing is airlifted without an airport in the threatened city, with no enemy in its zone, from a unit that moved, or from the city itself`() {
        val threatened = game.addCity(civ, tile(0))
        val home = airportCity(-10)
        besiege(threatened)
        val unit = game.addUnit("Warrior", civ, home.getCenterTile())
        val zone = civ.ai.tactical.map.zoneOf(threatened.getCenterTile())!!
        assertTrue("no airport in the threatened city", TacticalAi.plotDefensiveAirlifts(context(unit), zone).isEmpty())

        threatened.cityConstructions.addBuilding("Airport")
        unit.currentMovement = 1f
        assertTrue("the unit has moved", TacticalAi.plotDefensiveAirlifts(context(unit), civ.ai.tactical.map.zoneOf(threatened.getCenterTile())!!).isEmpty())

        unit.currentMovement = unit.getMaxMovement().toFloat()
        val inside = game.addUnit("Warrior", civ, threatened.getCenterTile())
        assertTrue("the unit is already in the threatened city", TacticalAi.plotDefensiveAirlifts(context(inside), civ.ai.tactical.map.zoneOf(threatened.getCenterTile())!!).isEmpty())

        for (enemyUnit in enemy.units.getCivUnits().toList()) enemyUnit.destroy()
        val quietZone = context(unit).map.zoneOf(threatened.getCenterTile())!!
        assertEquals(0, quietZone.enemyUnitCount)
        assertTrue("no enemy near", TacticalAi.plotDefensiveAirlifts(context(unit), quietZone).isEmpty())
    }

    @Test
    fun `the tactical turn airlifts to a threatened city that we do not dominate, and not to one that we do`() {
        val threatened = airportCity(0)
        val home = airportCity(10)
        besiege(threatened)
        val unit = game.addUnit("Warrior", civ, home.getCenterTile())
        see()
        assertNotEquals(TacticalDominance.Friendly, civ.ai.tactical.map.zoneOf(threatened.getCenterTile())!!.dominance)
        val cache = run()
        assertTrue(unit in cache.processedUnits)
        assertEquals(1, unit.getTile().aerialDistanceTo(threatened.getCenterTile()))
    }

    @Test
    fun `a threatened city we dominate gets no airlift`() {
        val strong = airportCity(0)
        val home = airportCity(10)
        for (tile in strong.getCenterTile().getTilesAtDistance(1)) game.addUnit("Swordsman", civ, tile)
        game.addUnit("Warrior", enemy, tile(3))
        val unit = game.addUnit("Warrior", civ, home.getCenterTile())
        see()
        val zone = civ.ai.tactical.map.zoneOf(strong.getCenterTile())!!
        assertEquals(TacticalDominance.Friendly, zone.dominance)
        assertEquals(1, zone.enemyUnitCount)
        run()
        assertTrue("it did not go to the city", unit.getTile().aerialDistanceTo(strong.getCenterTile()) > 1)
    }

    // endregion
}
