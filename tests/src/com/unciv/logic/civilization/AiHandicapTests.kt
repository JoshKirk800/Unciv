package com.unciv.logic.civilization

import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.nation.Difficulty
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The handicap's research and policy percentages apply to every player (CvTeamTechs::GetResearchCost, CvPlayerPolicies::GetNextPolicyCost):
 *  a human has the chosen difficulty's, an AI the one of its AI level ([Difficulty.aiDifficultyLevel]). */
@RunWith(BaseTestRunner::class)
class AiHandicapTests {
    private val game = TestGame()
    private lateinit var human: Civilization
    private lateinit var ai: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(2)
        // new objects: the ruleset's own levels are shared with the other tests
        val aiLevel = Difficulty().apply { name = "AI level"; researchCostModifier = 0.5f; policyCostModifier = 0.5f; isSelectable = false }
        val chosen = Difficulty().apply { name = "Chosen"; researchCostModifier = 0.8f; policyCostModifier = 0.8f; aiDifficultyLevel = aiLevel.name }
        game.ruleset.difficulties[aiLevel.name] = aiLevel
        game.ruleset.difficulties[chosen.name] = chosen
        game.setDifficulty(chosen.name)
        human = game.addCiv(isPlayer = true)
        ai = game.addCiv()
        // a civilization without cities counts as defeated, which makes the science modifier of a tech a NaN
        game.addCity(human, game.getTile(-2, 0))
        game.addCity(ai, game.getTile(2, 0))
    }

    @Test
    fun `a human pays the research percentage of the chosen difficulty, an AI that of its AI level`() {
        val humanCost = human.tech.costOfTech("Radar")
        val aiCost = ai.tech.costOfTech("Radar")

        assertTrue("human $humanCost, ai $aiCost", aiCost < humanCost)
        assertEquals(0.5 / 0.8, aiCost.toDouble() / humanCost, 0.02)
    }

    @Test
    fun `every AI of the Brave New World ruleset has the AI Default handicap, whatever the difficulty`() {
        val bnw = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        bnw.makeHexagonalMap(2)
        val selectable = bnw.ruleset.difficulties.values.filter { it.isSelectable }
        assertTrue(selectable.size >= 8)
        for (level in selectable) {
            bnw.setDifficulty(level.name)
            val civ = bnw.addCiv()
            assertEquals(level.name, "AI Default", civ.getDifficulty().name)
            assertEquals(level.name, 0.9f, civ.getDifficulty().unhappinessModifier, 0.0001f)
        }
        assertTrue(bnw.ruleset.difficulties["AI Default"]!!.isSelectable.not())
    }

    @Test
    fun `a human pays the policy percentage of the chosen difficulty, an AI that of its AI level`() {
        assertEquals(20, human.policies.getPolicyCultureCost(0)) // 25 * 0.8
        assertEquals(10, ai.policies.getPolicyCultureCost(0)) // 25 * 0.5 = 12, in steps of 5
    }
}
