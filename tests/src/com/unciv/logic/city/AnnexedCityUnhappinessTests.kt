package com.unciv.logic.city

import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/** CvPlayer::GetUnhappinessFromOccupiedCities: a citizen of an occupied city costs UNHAPPINESS_PER_OCCUPIED_POPULATION (1.34), nothing else per city */
@RunWith(GdxTestRunner::class)
class AnnexedCityUnhappinessTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)

    private fun conqueredCity(population: Int): City {
        game.makeHexagonalMap(3)
        val founder = game.addCiv()
        val conqueror = game.addCiv()
        game.addCity(conqueror, game.getTile(HexCoord(2, 0)))   // a capital of its own, so that the conquered city is not it
        val city = game.addCity(founder, game.getTile(HexCoord.Zero), initialPopulation = population)
        city.moveToCiv(conqueror)
        city.isPuppet = false
        city.cityStats.update()
        return city
    }

    @Test
    fun `an annexed city costs 1_34 per citizen and no flat term in the Civ V rulesets`() {
        val city = conqueredCity(8)
        assertTrue(city.cityStats.hasExtraAnnexUnhappiness())
        val unhappinessPerCitizen = city.civ.getDifficulty().unhappinessModifier
        assertEquals(-8 * 1.34f * unhappinessPerCitizen, city.cityStats.happinessList["Population"]!!, 0.01f)
        assertNull(city.cityStats.happinessList["Occupied City"])
    }

    @Test
    fun `a ruleset without the constants keeps double citizens and 2 more`() {
        val city = conqueredCity(8)
        val constants = city.civ.gameInfo.ruleset.modOptions.constants
        constants.annexedCityCitizenUnhappinessFactor = 2f
        constants.annexedCityFlatUnhappiness = 2f
        city.cityStats.update()
        val unhappinessPerCitizen = city.civ.getDifficulty().unhappinessModifier
        assertEquals(-16 * unhappinessPerCitizen, city.cityStats.happinessList["Population"]!!, 0.01f)
        assertEquals(-2f, city.cityStats.happinessList["Occupied City"]!!, 0.001f)
    }
}
