package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The city accessors the strategy tests read, as the original computes them (BNW data): GetJONSCulturePerTurnFromBuildings and
 *  GetFaithPerTurnFromBuildings (FIRST_CULTURE_BUILDING, FIRST_FAITH_BUILDING), IsAnyPlotBlockaded (UNDER_BLOCKADE), the great person
 *  points of GoodGPCity and GetCultureFromWonders (NEED_TOURISM_BUILDING).
 */
@RunWith(GdxTestRunner::class)
class CityAccessorStrategyTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8, "Grassland")
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
    }

    private fun tile(x: Int, y: Int): Tile = game.tileMap[HexCoord(x, y)]

    private fun capital(population: Int = 1): City = game.addCity(civ, tile(0, 0), initialPopulation = population)

    private fun colony(population: Int = 1): City {
        capital()
        return game.addCity(civ, tile(5, 0), initialPopulation = population)
    }

    /** The city's majority religion, founded by [civ], with the beliefs of the ruleset called [beliefs] */
    private fun adoptReligion(city: City, vararg beliefs: String) {
        val religion = game.addReligion(civ)
        religion.addBeliefs(beliefs.map { game.ruleset.beliefs[it]!! })
        city.religion.addPressure(religion.name, 2000)
        city.cityStats.update()
    }

    @Test
    fun `culture from buildings is what the buildings give, not what a belief adds per building`() {
        val city = colony(5)
        city.cityConstructions.addBuilding("Shrine")
        adoptReligion(city, "Ancestor Worship")
        // The stat tree's building branch holds the belief's culture; the original sums it apart from the buildings'
        assertEquals(1, CityStrategyAi.baseYieldFromBuildings(city, Stat.Culture))
        assertTrue(CityStrategyAi.isFirstCultureBuilding(city))
        assertTrue(CityStrategyAi.isFirstCultureBuildingEmergency(city))
        city.cityConstructions.addBuilding("Monument")
        city.cityStats.update()
        assertFalse(CityStrategyAi.isFirstCultureBuilding(city))
        assertFalse(CityStrategyAi.isFirstCultureBuildingEmergency(city))
    }

    @Test
    fun `the capital stays a first culture building city with the Palace alone, a Monument ends it`() {
        val capital = capital(5)
        capital.cityStats.update()
        assertTrue(CityStrategyAi.isFirstCultureBuilding(capital))
        assertFalse(CityStrategyAi.isFirstCultureBuildingEmergency(capital))
        capital.cityConstructions.addBuilding("Monument")
        capital.cityStats.update()
        assertFalse(CityStrategyAi.isFirstCultureBuilding(capital))
    }

    @Test
    fun `culture emergency needs 5 citizens`() {
        val city = colony(4)
        assertFalse(CityStrategyAi.isFirstCultureBuildingEmergency(city))
        city.population.addPopulation(1)
        assertTrue(CityStrategyAi.isFirstCultureBuildingEmergency(city))
    }

    @Test
    fun `faith from buildings is what the buildings give, not what God-King adds to the Palace`() {
        civ.ai.setPersonalityFlavor(Flavor.Religion, 7)
        val capital = capital(2)
        adoptReligion(capital, "God-King")
        assertEquals(1, CityStrategyAi.baseYieldFromBuildings(capital, Stat.Faith))
        assertTrue(CityStrategyAi.isFirstFaithBuilding(capital))
        capital.cityConstructions.addBuilding("Shrine")
        capital.cityStats.update()
        assertFalse(CityStrategyAi.isFirstFaithBuilding(capital))
    }

    @Test
    fun `only experience for every land unit makes a training facility, not the Ducal Stable's for mounted units`() {
        val city = colony(3)
        assertEquals(0, CityStrategyAi.landFreeExperience(city))
        city.cityConstructions.addBuilding("Ducal Stable")
        assertEquals(0, CityStrategyAi.landFreeExperience(city))
        city.cityConstructions.addBuilding("Barracks")
        assertEquals(15, CityStrategyAi.landFreeExperience(city))
    }

    private val blockadeEnemy by lazy {
        game.addCiv().also {
            civ.diplomacyFunctions.makeCivilizationsMeet(it)
            civ.getDiplomacyManager(it)!!.declareWar()
        }
    }

    /**
     *  A city at (0, 0); a water tile [shore] at the edge of its range (3 tiles away), and a water tile [far] two tiles from [shore] but
     *  outside the city's range, the tiles [between] them land. An enemy trireme on [far].
     */
    private class BlockadeSetup(val city: City, val shore: Tile, val far: Tile, val between: List<Tile>)

    private fun blockadeSetup(): BlockadeSetup {
        val city = capital(3)
        val shore = game.tileMap.values.first { it.aerialDistanceTo(city.getCenterTile()) == 3 }
        game.setTileTerrain(shore.position, "Coast")
        val far = game.tileMap.values.first { it.aerialDistanceTo(shore) == 2 && it.aerialDistanceTo(city.getCenterTile()) >= 4 }
        game.setTileTerrain(far.position, "Coast")
        val between = far.neighbors.filter { it.aerialDistanceTo(shore) == 1 }.toList()
        game.addUnit("Trireme", blockadeEnemy, far)
        game.gameInfo.turns++
        return BlockadeSetup(city, shore, far, between)
    }

    @Test
    fun `an enemy ship on the same water within 2 tiles of a water tile of the city blockades it`() {
        val setup = blockadeSetup()
        for (water in setup.between) game.setTileTerrain(water.position, "Coast")
        game.gameInfo.turns++
        assertTrue(CityStrategyAi.isUnderBlockade(setup.city))
    }

    @Test
    fun `an enemy ship on another body of water does not blockade, even 2 tiles away`() {
        val setup = blockadeSetup()
        assertFalse(CityStrategyAi.isUnderBlockade(setup.city))
        // Joining the two bodies makes it a blockade
        for (water in setup.between) game.setTileTerrain(water.position, "Coast")
        game.gameInfo.turns++
        assertTrue(CityStrategyAi.isUnderBlockade(setup.city))
    }

    private fun greatPersonCity(): City = capital(3)

    @Test
    fun `good great person city compares the points without rounding them per kind`() {
        val city = greatPersonCity()
        val writers = game.createBuilding().also { it.greatPersonPoints.add("Great Writer", 6) }
        val bonus = game.createBuilding("[+25]% Great Person generation [in this city]")
        city.cityConstructions.addBuilding(writers)
        city.cityConstructions.addBuilding(bonus)
        // 6 x 1.25 = 7.5 points a turn: below 8 (the city screen shows 8)
        assertEquals(8, city.getGreatPersonPoints()["Great Writer"])
        assertFalse(CityStrategyAi.isGoodGreatPersonCity(city))
        writers.greatPersonPoints.add("Great Writer", 1)
        assertTrue(CityStrategyAi.isGoodGreatPersonCity(city))
    }

    @Test
    fun `good great person city adds up the kinds`() {
        val city = greatPersonCity()
        val building = game.createBuilding()
        building.greatPersonPoints.add("Great Writer", 4)
        building.greatPersonPoints.add("Great Artist", 3)
        city.cityConstructions.addBuilding(building)
        assertFalse(CityStrategyAi.isGoodGreatPersonCity(city))
        building.greatPersonPoints.add("Great Artist", 1)
        assertTrue(CityStrategyAi.isGoodGreatPersonCity(city))
    }

    private fun addWonders(city: City, vararg names: String) {
        for (name in names) {
            assertTrue(name, game.ruleset.buildings[name]!!.isWonder)
            city.cityConstructions.addBuilding(name)
        }
    }

    @Test
    fun `need tourism building needs more than 10 culture from world wonders`() {
        val city = colony(1)
        // Oracle 3, Louvre 2, Hanging Gardens, Pyramids, Great Wall, Colossus, Chichen Itza 1 each: 10
        addWonders(city, "The Oracle", "The Louvre", "Hanging Gardens", "The Pyramids", "Great Wall", "Colossus", "Chichen Itza")
        assertEquals(10, civ.culture.getCultureOfWonders(city))
        assertEquals(0, civ.culture.getCityTourism(city))
        assertFalse(CityStrategyAi.needTourismBuilding(city))
        addWonders(city, "Terracotta Army")
        assertEquals(11, civ.culture.getCultureOfWonders(city))
        assertTrue(CityStrategyAi.needTourismBuilding(city))
    }

    @Test
    fun `need tourism building adds the base tourism of the city to the culture`() {
        val city = colony(1)
        // Parthenon 4 (it comes with a Great Work: 2 tourism), Oracle 3, Louvre 2
        addWonders(city, "Parthenon", "The Oracle")
        assertEquals(7, civ.culture.getCultureOfWonders(city))
        assertEquals(2, civ.culture.getCityTourism(city))
        assertFalse(CityStrategyAi.needTourismBuilding(city))
        addWonders(city, "The Louvre")
        assertTrue(CityStrategyAi.needTourismBuilding(city))
    }

    @Test
    fun `need tourism building does not count national wonders`() {
        val city = colony(1)
        addWonders(city, "The Oracle", "The Louvre", "Hanging Gardens", "The Pyramids", "Great Wall", "Colossus", "Chichen Itza")
        for (name in listOf("Oxford University", "National College", "Heroic Epic")) {
            assertTrue(name, game.ruleset.buildings[name]!!.isNationalWonder)
            city.cityConstructions.addBuilding(name)
        }
        assertFalse(CityStrategyAi.needTourismBuilding(city))
    }

    @Test
    fun `culture a policy or the league gives per world wonder counts for the tourism building test`() {
        val city = colony(1)
        addWonders(city, "The Oracle", "The Louvre", "Hanging Gardens")
        assertFalse(CityStrategyAi.needTourismBuilding(city))
        val policy = game.createPolicy("[+3 Culture] from every [World Wonder]",
            "[100]% of the Culture from wonders, natural wonders and improved tiles becomes Tourism [in all cities]")
        game.createPolicyBranch(policy = policy)
        civ.policies.adopt(policy)
        city.cityStats.update()
        // 3 + 2 + 1 + 3 x 3
        assertEquals(15, civ.culture.getCultureOfWonders(city))
        assertEquals(15, civ.culture.getCityTourism(city))
        assertTrue(CityStrategyAi.needTourismBuilding(city))
    }
}
