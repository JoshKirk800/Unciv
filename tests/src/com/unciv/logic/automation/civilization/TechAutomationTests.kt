package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.GrandStrategyDef
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.random.Random

/**
 *  Decision-level checks of the CvTechAI / CvPlayerTechs port against the DLL formulas, with BNW Technology_Flavors and defines
 *  (TECH_WEIGHT_PROPAGATION_PERCENT 50, TECH_PRIORITY_UNIQUE_ITEM 5, AI_RESEARCH_WEIGHT_BASE_MOD 0.15, AI_RESEARCH_WEIGHT_MOD_PER_TURN_LEFT 0.025).
 *  Religion flavors (Technology_Flavors): Pottery 1, Calendar 2, Philosophy 2, Theology 6. Nuclear Fusion (Offense 15, Nuke 5) is only a
 *  prerequisite of Future Tech (Science only).
 */
@RunWith(GdxTestRunner::class)
class TechAutomationTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(3)
        game.setSpeed("Standard") // 500 turns: game progress p = turn × 2 per mille
    }

    /** A civ (knowing Agriculture, as [TestGame.addCiv] gives) whose personality is zero except [flavors] */
    private fun civWith(vararg flavors: Pair<Flavor, Int>, nation: String? = null): Civilization {
        val civ = if (nation == null) game.addCiv() else game.addCiv(game.ruleset.nations[nation]!!)
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for ((flavor, value) in flavors) civ.ai.setPersonalityFlavor(flavor, value)
        return civ
    }

    /** Gives [civ] a city at the map center, so it is alive and its tech costs are real; returns the civ */
    private fun withCity(civ: Civilization): Civilization {
        game.addCity(civ, game.getTile(0, 0))
        return civ
    }

    private fun weight(civ: Civilization, tech: String) = TechAutomation.weightOf(civ, tech)

    @Test
    fun `each visible nearby resource we cannot improve yet multiplies the weight of the tech that lets us improve it`() {
        // Gold 10 -> blend 10; Animal Husbandry has Gold 3 and allows the Pasture that improves Cattle. Two Cattle tiles of our city: × (1 + 2)
        val civ = withCity(civWith(Flavor.Gold to 10))
        TechAutomation.rebuildWeights(civ)
        val husbandryBefore = weight(civ, "Animal Husbandry")
        val potteryBefore = weight(civ, "Pottery")
        val cattle = game.ruleset.tileResources["Cattle"]!!
        game.getTile(1, 0).setTileResource(cattle)
        game.getTile(0, 1).setTileResource(cattle)
        TechAutomation.rebuildWeights(civ)
        assertEquals(husbandryBefore + 3 * 10 * (3 - 1), weight(civ, "Animal Husbandry"))
        assertEquals(potteryBefore, weight(civ, "Pottery"))
    }

    @Test
    fun `tech weight sums its flavors times the current flavors scaled to 10 at the largest`() {
        // Offense 4, Nuke 2: current flavors scale to 10 and 5; at turn 0 (p = 100) the blend is (10×900 + 4×100)/1000 = 9 and (5×900 + 2×100)/1000 = 4
        val civ = civWith(Flavor.Offense to 4, Flavor.Nuke to 2)
        TechAutomation.rebuildWeights(civ)
        assertEquals(15 * 9 + 5 * 4, weight(civ, "Nuclear Fusion"))
        assertEquals(0, weight(civ, "The Internet")) // Culture 50 only
    }

    @Test
    fun `half of a tech's weight passes to each prerequisite, recursively, beyond three levels`() {
        // Religion 10 -> blend 10: own weights Pottery 10, Calendar 20, Philosophy 20, Theology 60.
        // Theology [Philosophy, Drama and Poetry], Philosophy [Calendar, Writing], Calendar and Writing [Pottery], Drama [Writing], Pottery [Agriculture]
        val civ = civWith(Flavor.Religion to 10)
        TechAutomation.rebuildWeights(civ)
        assertEquals(60, weight(civ, "Theology"))
        assertEquals(20 + 30, weight(civ, "Philosophy"))
        assertEquals(30, weight(civ, "Drama and Poetry"))
        assertEquals(20 + 15 + 10, weight(civ, "Calendar"))
        assertEquals(15 + 15 + 10, weight(civ, "Writing"))
        assertEquals(10 + 7 + 7 + 7 + 5 + 5 + 10, weight(civ, "Pottery"))
        // Theology -> Philosophy -> Calendar -> Pottery -> Agriculture: the DLL's post-incremented level never stops a first-prerequisite chain
        assertEquals(3 + 3 + 3 + 2 + 2 + 5 + 5, weight(civ, "Agriculture"))
    }

    @Test
    fun `techs unlocking our unique units get five times their flavor weight, and so does what they pass on`() {
        // MilitaryTraining 10 -> blend 10; Bronze Working has MilitaryTraining 5 and unlocks the Greek Hoplite
        val plain = civWith(Flavor.MilitaryTraining to 10)
        val greece = civWith(Flavor.MilitaryTraining to 10, nation = "Greece")
        TechAutomation.rebuildWeights(plain)
        TechAutomation.rebuildWeights(greece)
        assertEquals(weight(plain, "Bronze Working") + (5 - 1) * 5 * 10, weight(greece, "Bronze Working"))
        assertEquals(weight(plain, "Mining") + (5 - 1) * 5 * 10 / 2, weight(greece, "Mining"))
        assertEquals(weight(plain, "Iron Working"), weight(greece, "Iron Working"))
    }

    @Test
    fun `flavors drift from current needs to personality and grand strategy as the game progresses`() {
        // Religion 2 only: current flavor scales to 10; Theology = 6 × (10 × (1000 − p) + personality × p) / 1000
        val civ = civWith(Flavor.Religion to 2)
        fun theologyAt(turn: Int): Int {
            game.gameInfo.turns = turn
            TechAutomation.rebuildWeights(civ)
            return weight(civ, "Theology")
        }
        assertEquals(6 * ((10 * 900 + 2 * 100) / 1000), theologyAt(0))     // p clamped up to 100
        assertEquals(6 * ((10 * 500 + 2 * 500) / 1000), theologyAt(250))   // p = 500
        assertEquals(6 * ((10 * 100 + 2 * 900) / 1000), theologyAt(480))   // p = 960, clamped to 900
        // a grand strategy whose only FlavorMod is Religion +1 (BNW CULTURE has it among others that would reach Theology's weight too)
        val grandStrategies = game.ruleset.ai.grandStrategies
        grandStrategies[grandStrategies.indexOfFirst { it.name == GrandStrategyAi.CULTURE }] =
            GrandStrategyDef().apply { name = GrandStrategyAi.CULTURE; flavorMods = linkedMapOf("Religion" to 1) }
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        assertEquals(6 * ((10 * 100 + 3 * 900) / 1000), theologyAt(480))
    }

    @Test
    fun `research weights are divided by turns to the power of 0_15 plus 0_025 per turn`() {
        val civ = withCity(civWith(Flavor.Religion to 10, Flavor.Gold to 10))
        civ.stats.statsForNextTurn = Stats(science = 7f)
        civ.tech.techsInProgress["Pottery"] = 20
        TechAutomation.rebuildWeights(civ)
        val candidates = TechAutomation.researchCandidates(civ, freeTech = false)
        assertTrue(candidates.size >= 4)
        assertTrue("research time must differ between candidates",
            candidates.map { civ.tech.costOfTech(it.first.name) - civ.tech.researchOfTech(it.first.name) }.toSet().size > 1)
        for ((tech, reweighted) in candidates) {
            val turns = ceil((civ.tech.costOfTech(tech.name) - civ.tech.researchOfTech(tech.name)) / 7.0).toInt()
            val expected = (weight(civ, tech.name) / turns.toDouble().pow(0.15 + 0.025 * turns)).toInt()
            assertEquals(tech.name, expected, reweighted)
        }
        assertEquals(candidates.sortedByDescending { it.second }, candidates)
    }

    @Test
    fun `research weights are multiplied instead of divided while the science overflow is more than twice the science`() {
        val civ = withCity(civWith(Flavor.Religion to 10, Flavor.Gold to 10))
        civ.stats.statsForNextTurn = Stats(science = 7f)
        civ.tech.techsInProgress["Pottery"] = 20
        TechAutomation.rebuildWeights(civ)
        civ.tech.addScience(14)   // no current research: goes to the overflow; 14 is exactly twice the science, not more
        assertEquals(14, civ.tech.getOverflowScience())
        for ((tech, reweighted) in TechAutomation.researchCandidates(civ, freeTech = false)) {
            val turns = maxOf(1, ceil(civ.tech.remainingScienceToTech(tech.name) / 7.0).toInt())
            assertEquals(tech.name, (weight(civ, tech.name) / turns.toDouble().pow(0.15 + 0.025 * turns)).toInt(), reweighted)
        }
        civ.tech.addScience(1)    // 15 > 2 x 7: CvTechAI::ReweightByCost now wants the expensive techs
        val candidates = TechAutomation.researchCandidates(civ, freeTech = false)
        assertTrue(candidates.size >= 4)
        for ((tech, reweighted) in candidates) {
            val turns = maxOf(1, ceil(civ.tech.remainingScienceToTech(tech.name) / 7.0).toInt())
            assertEquals(tech.name, (weight(civ, tech.name) * turns.toDouble().pow(0.15 + 0.025 * turns)).toInt(), reweighted)
        }
    }

    @Test
    fun `the choice is weighted among the best two candidates only`() {
        game.setDifficulty("Prince") // HandicapInfos.TechNumOptionsConsidered 2
        val civ = withCity(civWith(Flavor.Religion to 10, Flavor.Gold to 10, Flavor.Production to 10))
        civ.stats.statsForNextTurn = Stats(science = 5f)
        TechAutomation.rebuildWeights(civ)
        val candidates = TechAutomation.researchCandidates(civ, freeTech = false)
        assertTrue("needs a third positive candidate", candidates.count { it.second > 0 } >= 3 && candidates[1].second > candidates[2].second)
        val chosen = (0 until 200).map { TechAutomation.chooseNextTech(civ, freeTech = false, Random(it))!! }.toSet()
        assertEquals(setOf(candidates[0].first, candidates[1].first), chosen)
    }

    @Test
    fun `free techs ignore research time, take the best weight and reset the research queue`() {
        val civ = withCity(civWith(Flavor.Religion to 10)) // Pottery is the only researchable tech with weight
        civ.stats.statsForNextTurn = Stats() // no science: every research weight divides to 0
        TechAutomation.rebuildWeights(civ)
        assertNull(TechAutomation.chooseNextTech(civ, freeTech = false, Random(0)))
        assertEquals("Pottery", TechAutomation.chooseNextTech(civ, freeTech = true, Random(0))?.name)

        civ.stats.statsForNextTurn = Stats(science = 5f)
        civ.tech.freeTechs = 1
        civ.tech.techsToResearch.add("Archery")
        TechAutomation.chooseTechToResearch(civ)
        assertTrue(civ.tech.isResearched("Pottery"))
        assertEquals(0, civ.tech.freeTechs)
        // the queue was cleared by the free tech, then refilled from the best two research candidates (Calendar and Writing, by weight 45 and 40)
        val bestTwo = TechAutomation.researchCandidates(civ, freeTech = false).take(2).map { it.first.name }
        assertTrue(bestTwo.toString(), bestTwo.toSet() == setOf("Calendar", "Writing"))
        assertTrue(civ.tech.techsToResearch.toString(), civ.tech.techsToResearch.single() in bestTwo)
    }
}
