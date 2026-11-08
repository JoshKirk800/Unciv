package com.unciv.logic.automation.strategy

import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Decision-level checks of the CvCitySpecializationAI port (BNW XML values) */
@RunWith(GdxTestRunner::class)
class CitySpecializationAiTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(7)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 0)
        game.addCity(civ, game.tileMap[0, 0])
        game.addCity(civ, game.tileMap[4, 0])
    }

    private fun yieldWeight(weights: CitySpecializationAi.SpecializationWeights, index: Int) = weights.yields.first { it.key == index }.weight

    @Test
    fun `gold, science and general economic weights follow the flavors and the grand strategy yields`() {
        civ.ai.setPersonalityFlavor(Flavor.Gold, 3)
        civ.ai.setPersonalityFlavor(Flavor.Science, 4)
        civ.ai.setPersonalityFlavor(Flavor.Spaceship, 2)
        var weights = CitySpecializationAi.weightSpecializations(civ)
        assertEquals(3 * 20, yieldWeight(weights, 2))
        assertEquals(4 * 20 + 2 * 10, yieldWeight(weights, 3))
        assertEquals(200, yieldWeight(weights, 4))
        // AIGrandStrategy_Yields of CULTURE: gold +50, science +50
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        weights = CitySpecializationAi.weightSpecializations(civ)
        assertEquals(3 * 20 + 50, yieldWeight(weights, 2))
        assertEquals(4 * 20 + 2 * 10 + 50, yieldWeight(weights, 3))
    }

    @Test
    fun `wonder subtype weight is the smaller of 200 per wonder flavor and a fifth of the next wonders weight`() {
        civ.ai.citySpecialization.nextWonderWeight = 2000
        civ.ai.setPersonalityFlavor(Flavor.Wonder, 1)
        fun wonderWeight() = CitySpecializationAi.weightProductionSubtypes(civ, civ.ai.flavor(Flavor.Wonder), 0)
            .first { it.key == CitySpecializationAi.SUBTYPE_WONDER }.weight
        assertEquals(200, wonderWeight())
        civ.ai.setPersonalityFlavor(Flavor.Wonder, 5)
        assertEquals(400, wonderWeight())
    }

    @Test
    fun `military training weight is 10 per offense plus 10 per war approach bias`() {
        civ.ai.setPersonalityFlavor(Flavor.Offense, 6)
        civ.ai.setMajorCivApproachBias(MajorCivApproach.War, 3)
        val training = CitySpecializationAi.weightProductionSubtypes(civ, 0, 0).first { it.key == CitySpecializationAi.SUBTYPE_MILITARY_TRAINING }
        assertEquals(6 * 10 + 3 * 10, training.weight)
    }

    @Test
    fun `specializations are assigned from turn 25 with a science city for a science-minded civ`() {
        civ.ai.setPersonalityFlavor(Flavor.Science, 20)
        game.gameInfo.turns = 24
        CitySpecializationAi.doTurn(civ)
        assertTrue(civ.cities.all { it.ai.specialization == null })
        game.gameInfo.turns = 25
        CitySpecializationAi.doTurn(civ)
        assertTrue(civ.cities.all { it.ai.specialization != null })
        // science 20 × 20 = 400 is the highest yield weight: one city is the science city
        assertTrue(civ.cities.any { it.ai.specialization == "CITYSPECIALIZATION_SCIENCE" })
        assertNotNull(civ.ai.citySpecialization.nextSpecializationDesired)
    }

    @Test
    fun `humans get no specializations`() {
        val human = game.addCiv(isPlayer = true)
        game.addCity(human, game.tileMap[-4, 0])
        game.addCity(human, game.tileMap[0, -4])
        game.gameInfo.turns = 30
        CitySpecializationAi.doTurn(human)
        assertTrue(human.cities.all { it.ai.specialization == null })
    }

    @Test
    fun `the wonder city is the most productive city that can build the next wonder`() {
        val wonder = game.createWonder().apply { cost = 100 }
        civ.ai.citySpecialization.nextWonderDesired = wonder.name
        val (first, second) = civ.cities
        first.cityStats.currentCityStats.production = 5f
        second.cityStats.currentCityStats.production = 9f
        assertEquals(second, CitySpecializationAi.findBestWonderCity(civ))
        civ.ai.citySpecialization.nextWonderDesired = null
        assertNull(CitySpecializationAi.findBestWonderCity(civ))
    }
}
