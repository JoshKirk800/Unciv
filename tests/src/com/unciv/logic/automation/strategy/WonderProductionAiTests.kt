package com.unciv.logic.automation.strategy

import com.unciv.json.json
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.ai.EconomicStrategyDef
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  CvWonderProductionAI::m_WonderAIWeights (BNW XML values): the flavors are added again at every flavor broadcast and never reset.
 *  Stonehenge has Building_Flavors RELIGION 75, WONDER 25, GREAT_PEOPLE 10 (flavors with no personality value here contribute 0).
 */
@RunWith(GdxTestRunner::class)
class WonderProductionAiTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var stonehenge: Building
    private var shouldBeActive = true

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        stonehenge = game.ruleset.buildings["Stonehenge"]!!
        game.addCity(civ, game.tileMap[0, 0])
        civ.tech.addTechnology("Calendar")
        StrategyTests.register("TEST_STRATEGY_WONDER_BROADCAST") { _, _ -> shouldBeActive }
        game.ruleset.ai.economicStrategies.clear()
        game.ruleset.ai.militaryStrategies.clear()
        game.ruleset.ai.economicStrategies.add(EconomicStrategyDef().apply {
            name = "TEST_STRATEGY_WONDER_BROADCAST"
            checkTriggerTurnCount = 1
            playerFlavors = linkedMapOf("Religion" to 10)
        })
    }

    private fun turn(n: Int) {
        game.gameInfo.turns = n
        civ.ai.doTurn()
    }

    @Test
    fun `a personality starts the weights at the flavors added once`() {
        civ.ai.setPersonalityFlavor(Flavor.Religion, 4)
        assertEquals(4 * 75, WonderProductionAi.weight(civ, stonehenge))
    }

    @Test
    fun `every strategy adopted or dropped adds the flavors again`() {
        civ.ai.setPersonalityFlavor(Flavor.Religion, 4)
        turn(1)
        assertTrue(civ.ai.isEconomicStrategyActive("TEST_STRATEGY_WONDER_BROADCAST"))
        // 4 at the start, then the broadcast with the strategy's +10: (4 + 14) × 75
        assertEquals((4 + 14) * 75, WonderProductionAi.weight(civ, stonehenge))
        shouldBeActive = false
        turn(2)
        assertTrue(!civ.ai.isEconomicStrategyActive("TEST_STRATEGY_WONDER_BROADCAST"))
        // dropping broadcasts the personality alone again
        assertEquals((4 + 14 + 4) * 75, WonderProductionAi.weight(civ, stonehenge))
        shouldBeActive = true
        turn(3)
        assertEquals((4 + 14 + 4 + 14) * 75, WonderProductionAi.weight(civ, stonehenge))
        turn(4) // nothing changed: no broadcast
        assertEquals((4 + 14 + 4 + 14) * 75, WonderProductionAi.weight(civ, stonehenge))
    }

    @Test
    fun `the engineer's total weight of buildable wonders is the accumulated one`() {
        civ.ai.setPersonalityFlavor(Flavor.Religion, 4)
        turn(1)
        val choice = WonderProductionAi.chooseWonderForGreatEngineer(civ)!!
        assertEquals("Stonehenge", choice.wonder.name)
        assertEquals((4 + 14) * 75, choice.totalWeight)
    }

    @Test
    fun `weights are serialized with the civilization's AI and an old save starts at the current flavors once`() {
        civ.ai.setPersonalityFlavor(Flavor.Religion, 4)
        turn(1)
        val reloaded = json().fromJson(CivAi::class.java, json().toJson(civ.ai))
        assertEquals((4 + 14) * 75, reloaded.wonderWeights["Stonehenge"])
        civ.ai.wonderWeights.clear()
        assertEquals((4 + 10) * 75, WonderProductionAi.weight(civ, stonehenge))
    }
}
