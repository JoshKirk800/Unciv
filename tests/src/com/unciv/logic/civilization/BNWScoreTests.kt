package com.unciv.logic.civilization

import com.unciv.logic.city.GreatWorkSlot
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class BNWScoreTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        civ = game.addCiv()
    }

    @Test
    fun `BNW cities score eight per city before map scaling, and works four per occupied slot`() {
        val first = game.addCity(civ, game.getTile(0, 0))
        game.addCity(civ, game.getTile(0, 5))
        // CvGame::DoCacheMapScoreMod: the standard world has 4160 tiles
        val mapFactor = 4160.0 / game.tileMap.mapParameters.numberOfTiles()
        val adjustedFactor = (mapFactor - 1) / 3 + 1
        assertEquals(16 * adjustedFactor, civ.calculateScoreBreakdown()["Cities"]!!, 0.00001)
        assertEquals(8 * adjustedFactor, civ.calculateScoreBreakdown()["Population"]!!, 0.00001)

        val wonder = game.createWonder()
        first.cityConstructions.addBuilding(wonder)
        val gallery = game.createBuilding("Has [2] [Art/Artifact] Great Work slots")
        first.cityConstructions.addBuilding(gallery)
        repeat(2) { slot ->
            val work = game.createGreatWork()
            first.greatWorks.place(
                GreatWorkInstance(work.name, civ.civName, "Ancient era", 1),
                GreatWorkSlot(first, gallery, slot)
            )
        }
        game.createGreatWork() // Not displayed in a city: does not score.
        val breakdown = civ.calculateScoreBreakdown()
        assertEquals(25.0, breakdown["Wonders"]!!, 0.0)
        assertEquals(8.0, breakdown["Great Works"]!!, 0.0)
    }

    @Test
    fun `ordinary policy branch scores the opener, purchased policies, and free finisher once each`() {
        game.addCity(civ, game.getTile(0, 0))
        val tradition = game.ruleset.policyBranches["Tradition"]!!
        civ.policies.freePolicies = 6
        assertEquals(0.0, civ.calculateScoreBreakdown()["Policies"]!!, 0.0)
        civ.policies.adopt(tradition)
        assertEquals(4.0, civ.calculateScoreBreakdown()["Policies"]!!, 0.0)
        for (name in listOf("Aristocracy", "Oligarchy", "Legalism", "Landed Elite", "Monarchy"))
            civ.policies.adopt(game.ruleset.policies[name]!!)
        assertEquals(28.0, civ.calculateScoreBreakdown()["Policies"]!!, 0.0)
    }

    @Test
    fun `choosing an ideology does not score a fictitious opener, but a free tenet does`() {
        game.addCity(civ, game.getTile(0, 0))
        civ.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == "Modern era" }.name)
        val freedom = game.ruleset.policyBranches["Freedom"]!!
        civ.policies.adopt(freedom)
        assertEquals(0.0, civ.calculateScoreBreakdown()["Policies"]!!, 0.0)
        civ.policies.adopt(game.ruleset.policies["Civil Society"]!!)
        assertEquals(4.0, civ.calculateScoreBreakdown()["Policies"]!!, 0.0)
    }

    @Test
    fun `pantheon does not score, but a founded religion counts each belief and cities of any owner`() {
        val ownCity = game.addCity(civ, game.getTile(0, 0))
        val foreigner = game.addCiv()
        val foreignCity = game.addCity(foreigner, game.getTile(0, 5))
        val rivalCity = game.addCity(foreigner, game.getTile(0, -5))
        val religion = game.addReligion(civ)
        religion.addBelief(game.ruleset.beliefs["God of the Sea"]!!)
        ownCity.religion.addPressure(religion.name, 1000)
        assertEquals(0.0, civ.calculateScoreBreakdown()["Religion"]!!, 0.0)

        religion.addBeliefs(listOf(game.ruleset.beliefs["Church Property"]!!, game.ruleset.beliefs["Peace Gardens"]!!))
        assertEquals(61.0, civ.calculateScoreBreakdown()["Religion"]!!, 0.0)
        foreignCity.religion.addPressure(religion.name, 1000)
        assertEquals(62.0, civ.calculateScoreBreakdown()["Religion"]!!, 0.0)
        val rival = game.addReligion(foreigner)
        rival.addBelief(game.ruleset.beliefs["Tithe"]!!)
        rivalCity.religion.addPressure(rival.name, 1000)
        assertEquals(21.0, foreigner.calculateScoreBreakdown()["Religion"]!!, 0.0)
        religion.addBeliefs(listOf(game.ruleset.beliefs["Itinerant Preachers"]!!, game.ruleset.beliefs["Sacred Sites"]!!))
        assertEquals(102.0, civ.calculateScoreBreakdown()["Religion"]!!, 0.0)
    }

    @Test
    fun `Vanilla and Gods and Kings retain ten city points and no BNW policy or religion terms`() {
        for (baseRuleset in listOf(BaseRuleset.Civ_V_Vanilla, BaseRuleset.Civ_V_GnK)) {
            val olderGame = TestGame(baseRuleset = baseRuleset)
            olderGame.makeHexagonalMap(8)
            val olderCiv = olderGame.addCiv()
            olderGame.addCity(olderCiv, olderGame.getTile(0, 0))
            val baseTiles = if (baseRuleset == BaseRuleset.Civ_V_GnK) 4160.0 else 1276.0  // Vanilla keeps Unciv's medium map
            val mapFactor = baseTiles / olderGame.tileMap.mapParameters.numberOfTiles()
            assertEquals(10 * ((mapFactor - 1) / 3 + 1), olderCiv.calculateScoreBreakdown()["Cities"]!!, 0.00001)
            olderCiv.policies.freePolicies = 1
            olderCiv.policies.adopt(olderGame.ruleset.policyBranches["Tradition"]!!)
            val breakdown = olderCiv.calculateScoreBreakdown()
            assertFalse(breakdown.containsKey("Policies"))
            assertFalse(breakdown.containsKey("Religion"))
        }
    }
}
