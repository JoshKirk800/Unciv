package com.unciv.logic.civilization

import com.unciv.logic.BackwardCompatibility.migrateGreatPersonPointsToCities
import com.unciv.logic.city.City
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Great People from points as in the original game: CvCityCitizens::DoSpecialists and GetSpecialistUpgradeThreshold */
@RunWith(GdxTestRunner::class)
class GreatPersonProgressTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var capital: City
    private lateinit var otherCity: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(8)
        game.setSpeed("Standard")
        civ = game.addCiv()
        capital = game.addCity(civ, game.getTile(0, 0))
        otherCity = game.addCity(civ, game.getTile(5, 0))
    }

    private fun gain(unit: String) = civ.greatPeople.countGreatPersonGained(game.ruleset.units[unit]!!)
    private fun required(unit: String) = civ.greatPeople.getPointsRequiredForGreatPerson(unit)
    private fun greatPeopleIn(city: City, unit: String) = civ.units.getCivUnits().count { it.name == unit && it.getTile() == city.getCenterTile() }

    @Test
    fun `each Great Person of a group adds 100 to what the next one needs`() {
        assertEquals(100, required("Great Scientist"))
        gain("Great Scientist")
        assertEquals(200, required("Great Scientist"))
        gain("Great Engineer")
        assertEquals("100 + 100 × 2, not doubled twice", 300, required("Great Scientist"))
    }

    @Test
    fun `Writers, Artists and Musicians count apart from the other Great People and from each other`() {
        gain("Great Scientist")
        gain("Great Merchant")
        gain("Great Writer")
        assertEquals(300, required("Great Engineer"))
        assertEquals(200, required("Great Writer"))
        assertEquals(100, required("Great Artist"))
        assertEquals(100, required("Great Musician"))
    }

    @Test
    fun `game speed scales the threshold`() {
        game.setSpeed("Quick")
        gain("Great Scientist")
        assertEquals("200 × 67%", 134, required("Great Scientist"))
    }

    @Test
    fun `points of two cities do not add up, and a Great Person is born in the city that reached the threshold`() {
        capital.greatPersonProgress["Great Writer"] = 60
        otherCity.greatPersonProgress["Great Writer"] = 60
        civ.greatPeople.spawnGreatPeopleFromPoints()
        assertEquals(0, civ.units.getCivUnits().count { it.name == "Great Writer" })

        otherCity.greatPersonProgress["Great Writer"] = 130
        civ.greatPeople.spawnGreatPeopleFromPoints()
        assertEquals(1, greatPeopleIn(otherCity, "Great Writer"))
        assertEquals("points beyond the threshold are lost", 0, otherCity.greatPersonProgress["Great Writer"])
        assertEquals(60, capital.greatPersonProgress["Great Writer"])
        assertEquals(200, required("Great Writer"))
    }

    @Test
    fun `a free Great Person raises the threshold of its group`() {
        civ.greatPeople.freeGreatPeople = 1
        assertTrue(civ.greatPeople.chooseFreeGreatPerson("Great Artist") != null)
        assertEquals(200, required("Great Artist"))
        assertEquals(100, required("Great Scientist"))
    }

    @Test
    fun `Prophets and Generals do not raise the threshold of anyone`() {
        gain("Great Prophet")
        gain("Great General")
        assertEquals(100, required("Great Scientist"))
        assertTrue(civ.greatPeople.greatPeopleCreated.isEmpty())
    }

    @Test
    fun `a city that changes hands loses its progress`() {
        val other = game.addCiv()
        game.addCity(other, game.getTile(-5, 0))
        otherCity.greatPersonProgress["Great Scientist"] = 80
        otherCity.moveToCiv(other)
        assertEquals(0, otherCity.greatPersonProgress["Great Scientist"])
    }

    @Test
    fun `old saves keep the number of Great People and move the points to the capital`() {
        civ.greatPeople.pointsForNextGreatPersonCounter["Great Person"] = 800
        civ.greatPeople.greatPersonPointsCounter["Great Scientist"] = 150
        game.gameInfo.migrateGreatPersonPointsToCities()
        assertEquals("800 = 100 doubled three times", 3, civ.greatPeople.greatPeopleCreated["Great Person"])
        assertEquals(150, capital.greatPersonProgress["Great Scientist"])
        assertTrue(civ.greatPeople.pointsForNextGreatPersonCounter.isEmpty())
        assertTrue(civ.greatPeople.greatPersonPointsCounter.isEmpty())
    }
}
