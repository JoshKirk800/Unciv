package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Decision-level checks of the CityStrategyAIHelpers::IsTestCityStrategy_* ports and their flavors (BNW XML values) */
@RunWith(GdxTestRunner::class)
class CityStrategyAiTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(6)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 0)
    }

    private fun city(x: Int, y: Int, population: Int = 1): City = game.addCity(civ, game.tileMap[x, y], initialPopulation = population)

    @Test
    fun `city size strategies split at 2, 5 and 12 and the capital is never medium`() {
        val capital = city(0, 0, 6)
        val other = city(4, 0, 1)
        assertTrue(CityStrategyAi.isTinyCity(other))
        other.population.setPopulation(2)
        assertTrue(CityStrategyAi.isSmallCity(other) && !CityStrategyAi.isTinyCity(other))
        other.population.setPopulation(4)
        assertTrue(CityStrategyAi.isSmallCity(other))
        other.population.setPopulation(5)
        assertTrue(CityStrategyAi.isMediumCity(other) && !CityStrategyAi.isSmallCity(other))
        other.population.setPopulation(11)
        assertTrue(CityStrategyAi.isMediumCity(other) && !CityStrategyAi.isLargeCity(other))
        other.population.setPopulation(12)
        assertTrue(CityStrategyAi.isLargeCity(other) && !CityStrategyAi.isMediumCity(other))
        assertFalse(CityStrategyAi.isMediumCity(capital))
    }

    @Test
    fun `spaceship parts get no weight in small cities but do in a large capital`() {
        civ.ai.setPersonalityFlavor(Flavor.Spaceship, 8)
        val capital = city(0, 0, 12)
        val small = city(4, 0, 3)
        capital.ai.doTurn()
        small.ai.doTurn()
        assertTrue(small.ai.isCityStrategyActive("AICITYSTRATEGY_SMALL_CITY"))
        assertTrue(capital.ai.isCityStrategyActive("AICITYSTRATEGY_LARGE_CITY"))
        val booster = game.ruleset.units["SS Booster"]!!
        // SMALL_CITY: SPACESHIP -300, so 8 - 300 reads as 0
        assertEquals(0, ProductionAi.unitWeight(small, booster))
        // Unit_Flavors SS Booster SPACESHIP 150; LARGE_CITY has no spaceship flavor
        assertEquals(150 * 8, ProductionAi.unitWeight(capital, booster))
    }

    @Test
    fun `science and commerce specializations suppress spaceship flavor`() {
        civ.ai.setPersonalityFlavor(Flavor.Spaceship, 60)
        val capital = city(0, 0, 12)
        capital.ai.changeSpecialization("CITYSPECIALIZATION_SCIENCE")
        // CitySpecialization_Flavors SCIENCE: SPACESHIP -100
        assertEquals(0, capital.ai.flavor(Flavor.Spaceship))
        capital.ai.changeSpecialization("CITYSPECIALIZATION_PRODUCTION_SPACESHIP")
        assertEquals(60 + 30, capital.ai.flavor(Flavor.Spaceship))
    }

    @Test
    fun `first culture building holds until the city has culture from buildings`() {
        city(0, 0)
        val other = city(4, 0)
        assertTrue(CityStrategyAi.isFirstCultureBuilding(other))
        other.cityConstructions.addBuilding("Monument")
        other.cityStats.update()
        assertFalse(CityStrategyAi.isFirstCultureBuilding(other))
        assertFalse(CityStrategyAi.isFirstCultureBuildingEmergency(other))
    }

    @Test
    fun `need culture building counts the empty slots of the cheapest culture building's type`() {
        city(0, 0)
        val other = city(4, 0)
        game.createBuilding("Has [1] [Literature] Great Work slots").apply { cost = 10 }
        assertEquals("Literature", CityStrategyAi.firstAvailableCultureBuildingSlotType(other))
        // 0 empty Literature slots in the empire: at most 2
        assertTrue(CityStrategyAi.needCultureBuilding(other))
        val library = game.createBuilding("Has [3] [Literature] Great Work slots").apply { cost = 500 }
        other.cityConstructions.addBuilding(library)
        assertEquals(3, CityStrategyAi.numAvailableGreatWorkSlots(civ, "Literature"))
        assertFalse(CityStrategyAi.needCultureBuilding(other))
        // Empty slots of another type do not count
        other.cityConstructions.removeBuilding(library.name)
        other.cityConstructions.addBuilding(game.createBuilding("Has [3] [Music] Great Work slots").apply { cost = 600 })
        assertTrue(CityStrategyAi.needCultureBuilding(other))
    }

    @Test
    fun `good great person city needs 8 points a turn`() {
        val capital = city(0, 0)
        val building = game.createBuilding()
        building.greatPersonPoints.add("Great Writer", 7)
        capital.cityConstructions.addBuilding(building)
        assertFalse(CityStrategyAi.isGoodGreatPersonCity(capital))
        building.greatPersonPoints.add("Great Writer", 1)
        assertTrue(CityStrategyAi.isGoodGreatPersonCity(capital))
    }

    @Test
    fun `capital need settler compares turn times 4 with 130 minus 10 per expansion plus 2 per defense`() {
        val capital = city(0, 0)
        val def = game.ruleset.ai.cityStrategies.first { it.name == "AICITYSTRATEGY_CAPITAL_NEED_SETTLER" }
        fun threshold() = def.weightThreshold + civ.ai.weightThresholdModifier(def)
        game.gameInfo.turns = 32
        assertFalse(CityStrategyAi.isCapitalNeedSettler(capital, threshold())) // 128 > 130 is false
        game.gameInfo.turns = 33
        assertTrue(CityStrategyAi.isCapitalNeedSettler(capital, threshold()))
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 5)
        civ.ai.setPersonalityFlavor(Flavor.Defense, 5) // 130 - 50 + 10 = 90
        game.gameInfo.turns = 22
        assertFalse(CityStrategyAi.isCapitalNeedSettler(capital, threshold())) // 88
        game.gameInfo.turns = 23
        assertTrue(CityStrategyAi.isCapitalNeedSettler(capital, threshold())) // 92
    }

    @Test
    fun `hill city needs two owned hills within two tiles`() {
        val capital = city(0, 0)
        game.setTileTerrainAndFeatures(game.tileMap[1, 0].position, Constants.grassland, Constants.hill)
        assertFalse(CityStrategyAi.isHillCity(capital))
        game.setTileTerrainAndFeatures(game.tileMap[0, 1].position, Constants.grassland, Constants.hill)
        assertTrue(CityStrategyAi.isHillCity(capital))
    }

    @Test
    fun `first faith building needs religion flavor above 6, or above 4 with a pantheon`() {
        val capital = city(0, 0, 2)
        civ.ai.setPersonalityFlavor(Flavor.Religion, 6)
        assertFalse(CityStrategyAi.isFirstFaithBuilding(capital))
        civ.ai.setPersonalityFlavor(Flavor.Religion, 7)
        assertTrue(CityStrategyAi.isFirstFaithBuilding(capital))
        civ.ai.setPersonalityFlavor(Flavor.Religion, 5)
        game.addReligion(civ)
        assertTrue(CityStrategyAi.isFirstFaithBuilding(capital))
    }

    @Test
    fun `an adopted city strategy adds its flavors to the city`() {
        civ.ai.setPersonalityFlavor(Flavor.Culture, 4)
        city(0, 0)
        val other = city(4, 0)
        other.ai.doTurn()
        // FIRST_CULTURE_BUILDING CULTURE +150 (no culture from buildings yet); TINY_CITY has no culture flavor
        assertTrue(other.ai.isCityStrategyActive("AICITYSTRATEGY_FIRST_CULTURE_BUILDING"))
        assertEquals(4 + 150, other.ai.flavor(Flavor.Culture))
    }
}
