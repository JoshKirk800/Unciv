package com.unciv.logic.automation.strategy

import com.unciv.json.json
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.EconomicStrategyDef
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.GrandStrategyDef
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.stats.Stats
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Decision-level checks of the CvGrandStrategyAI, CvFlavorManager and strategy-loop ports against the DLL formulas (BNW XML values) */
@RunWith(GdxTestRunner::class)
class GrandStrategyAiTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        game.gameInfo.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys)
    }

    private fun def(name: String): GrandStrategyDef = game.ruleset.ai.getGrandStrategies().first { it.name == name }

    /** A major whose personality is all zero except what the test sets */
    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 0)
        return civ
    }

    @Test
    fun `base priority is the grand strategy flavors times the personality`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Culture, 7)
        civ.ai.setPersonalityFlavor(Flavor.Offense, 3) // not a Culture grand strategy flavor
        // AIGrandStrategy_Flavors: CULTURE has FLAVOR_CULTURE 10
        assertEquals(70, GrandStrategyAi.baseGrandStrategyPriority(civ, def(GrandStrategyAi.CULTURE)))
        // CONQUEST has FLAVOR_OFFENSE 9
        assertEquals(27, GrandStrategyAi.baseGrandStrategyPriority(civ, def(GrandStrategyAi.CONQUEST)))
    }

    @Test
    fun `conquest priority scales boldness and approach biases by the era`() {
        val civ = neutralCiv()
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 8)
        civ.ai.setMajorCivApproachBias(MajorCivApproach.War, 7)
        civ.ai.setMajorCivApproachBias(MajorCivApproach.Hostile, 4)
        civ.ai.setMajorCivApproachBias(MajorCivApproach.Deceptive, 9)
        civ.ai.setMajorCivApproachBias(MajorCivApproach.Friendly, 5)
        // (boldness + max(deceptive, hostile, war) - friendly) * (12 - era 0)
        assertEquals((8 + 9 - 5) * 12, GrandStrategyAi.conquestPriority(civ, def(GrandStrategyAi.CONQUEST)))
    }

    @Test
    fun `culture priority adds the culture-ahead weight over the flavor term`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Culture, 5)
        val other1 = game.addCiv()
        val other2 = game.addCiv()
        for (c in listOf(civ, other1, other2)) game.addCity(c, game.tileMap.values.first { it.getCity() == null && it.neighbors.none { n -> n.getCity() != null } })
        civ.stats.statsForNextTurn = Stats(culture = 10f)
        other1.stats.statsForNextTurn = Stats(culture = 3f)
        other2.stats.statsForNextTurn = Stats(culture = 3f)
        // (10 - era) * flavor * 200 / 100 = 100; ahead of both of 2: AI_GS_CULTURE_AHEAD_WEIGHT 50 * 2 / 2; tourism all equal: nothing
        assertEquals(150, GrandStrategyAi.culturePriority(civ, def(GrandStrategyAi.CULTURE)))
        other2.stats.statsForNextTurn = Stats(culture = 30f)
        // ahead of 1, behind 1: no longer ahead of more than behind
        assertEquals(100, GrandStrategyAi.culturePriority(civ, def(GrandStrategyAi.CULTURE)))
    }

    @Test
    fun `united nations priority before the congress weights the diplomacy flavor`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Diplomacy, 6)
        // (10 - era) * flavor * 150 / 100
        assertEquals(90, GrandStrategyAi.unitedNationsPriority(civ, def(GrandStrategyAi.UNITED_NATIONS)))
        game.gameInfo.gameParameters.victoryTypes.remove("Diplomatic")
        assertEquals(-100, GrandStrategyAi.unitedNationsPriority(civ, def(GrandStrategyAi.UNITED_NATIONS)))
    }

    @Test
    fun `spaceship priority gets the Apollo bonus`() {
        val apollo = game.addCiv("Enables construction of Spaceship parts")
        for (flavor in Flavor.entries) apollo.ai.setPersonalityFlavor(flavor, 0)
        assertEquals(150, GrandStrategyAi.spaceshipPriority(apollo, def(GrandStrategyAi.SPACESHIP)))
        assertEquals(0, GrandStrategyAi.spaceshipPriority(neutralCiv(), def(GrandStrategyAi.SPACESHIP)))
    }

    @Test
    fun `priorities are reduced by the share of met players guessed to pursue the same strategy`() {
        val civ = neutralCiv()
        val a = game.addCiv()
        val b = game.addCiv()
        // only living majors count as met players
        game.addCity(civ, game.tileMap[0, 0])
        game.addCity(a, game.tileMap[3, 0])
        game.addCity(b, game.tileMap[0, 3])
        civ.diplomacyFunctions.makeCivilizationsMeet(a)
        civ.diplomacyFunctions.makeCivilizationsMeet(b)
        civ.ai.grandStrategyGuesses[a.civID] = GrandStrategyGuess(GrandStrategyAi.CULTURE, GuessConfidence.Likely)
        civ.ai.grandStrategyGuesses[b.civID] = GrandStrategyGuess(GrandStrategyAi.CULTURE, GuessConfidence.Unsure)
        val defs = game.ruleset.ai.getGrandStrategies()
        for (d in defs) civ.ai.grandStrategyPriorities[d.name] = 200
        GrandStrategyAi.applyCrowding(civ, defs)
        // 200 * 50 * 2 guessed / 3 met (us included) / 100 = 66
        assertEquals(134, civ.ai.grandStrategyPriority(GrandStrategyAi.CULTURE))
        assertEquals(200, civ.ai.grandStrategyPriority(GrandStrategyAi.CONQUEST))
    }

    @Test
    fun `an active grand strategy is kept for 10 turns before it can change`() {
        val civ = neutralCiv()
        game.addCity(civ, game.tileMap[0, 0])
        civ.ai.setPersonalityFlavor(Flavor.Culture, 20) // culture: 200 base + 400 flavor term, far above anything else
        civ.ai.grandStrategy = GrandStrategyAi.CONQUEST
        civ.ai.turnsSinceGrandStrategySet = 8
        GrandStrategyAi.doTurn(civ)
        assertEquals(GrandStrategyAi.CONQUEST, civ.ai.grandStrategy)
        assertEquals(9, civ.ai.turnsSinceGrandStrategySet)
        GrandStrategyAi.doTurn(civ)
        assertEquals(GrandStrategyAi.CULTURE, civ.ai.grandStrategy)
        assertEquals(0, civ.ai.turnsSinceGrandStrategySet)
        assertTrue(civ.ai.isGoingForCultureVictory())
    }

    @Test
    fun `guess confidence follows the positive and likely thresholds`() {
        val civ = neutralCiv()
        assertEquals(GuessConfidence.Positive, GrandStrategyAi.guessConfidence(civ, 120))
        assertEquals(GuessConfidence.Likely, GrandStrategyAi.guessConfidence(civ, 119))
        assertEquals(GuessConfidence.Likely, GrandStrategyAi.guessConfidence(civ, 70))
        assertEquals(GuessConfidence.Unsure, GrandStrategyAi.guessConfidence(civ, 69))
    }

    @Test
    fun `randomized personality stays within 2 of the leader, keeps zeros, and survives save and load`() {
        val nation = game.ruleset.nations.values.first { it.isMajorCiv && it.personality != null }
        val leader = game.ruleset.personalities[nation.personality]!!
        val civ = game.addCiv(nation)
        for (flavor in Flavor.entries) {
            val original = leader.flavors[flavor.name] ?: continue
            if (flavor == Flavor.Expansion || flavor == Flavor.Growth) continue // also adjusted for the map
            val value = civ.ai.personality(flavor)
            if (original == 0) assertEquals(0, value)
            else assertTrue("$flavor $original -> $value", value in maxOf(0, original - 2)..minOf(20, original + 2))
        }
        val reloaded = json().fromJson(CivAi::class.java, json().toJson(civ.ai))
        reloaded.setTransients(civ)
        assertTrue(civ.ai.personalityFlavorVector().contentEquals(reloaded.personalityFlavorVector()))
        assertEquals(civ.ai.diplomacyValue(DiplomacyPersonality.Boldness), reloaded.diplomacyValue(DiplomacyPersonality.Boldness))
    }

    @Test
    fun `strategies are only dropped on check turns after the minimum, and their flavors follow`() {
        val civ = neutralCiv()
        val city = game.addCity(civ, game.tileMap[0, 0])
        var shouldBeActive = true
        StrategyTests.register("TEST_STRATEGY_HYSTERESIS") { _, _ -> shouldBeActive }
        game.ruleset.ai.economicStrategies.clear() // only the test strategy, so no grand-strategy flavors mix in
        game.ruleset.ai.economicStrategies.add(EconomicStrategyDef().apply {
            name = "TEST_STRATEGY_HYSTERESIS"
            checkTriggerTurnCount = 3
            minimumNumTurnsExecuted = 5
            playerFlavors = linkedMapOf("Gold" to 10)
            cityFlavors = linkedMapOf("Gold" to 100)
        })
        fun turn(n: Int) { game.gameInfo.turns = n; civ.ai.doTurn() }

        turn(0)
        assertTrue(civ.ai.isEconomicStrategyActive("TEST_STRATEGY_HYSTERESIS"))
        assertEquals(10, civ.ai.flavor(Flavor.Gold))
        assertEquals(100, city.ai.flavor(Flavor.Gold))
        shouldBeActive = false
        for (n in 1..5) {
            turn(n) // 3 is a check turn but before the minimum of 5; 4 and 5 are no check turns
            assertTrue("turn $n", civ.ai.isEconomicStrategyActive("TEST_STRATEGY_HYSTERESIS"))
        }
        turn(6)
        assertTrue(!civ.ai.isEconomicStrategyActive("TEST_STRATEGY_HYSTERESIS"))
        assertEquals(0, civ.ai.flavor(Flavor.Gold))
        assertEquals(0, city.ai.flavor(Flavor.Gold))
    }

    @Test
    fun `flavors of an object without a table entry are derived from its stats`() {
        val building = game.createBuilding()
        building.culture = 2f
        building.cityStrength = 5.0
        val flavors = game.ruleset.ai.flavorsOf(building)
        assertEquals(20, flavors[Flavor.Culture.ordinal])
        assertEquals(20, flavors[Flavor.CityDefense.ordinal])
        assertEquals(0, flavors[Flavor.Science.ordinal])
    }

    @Test
    fun `generated AI data loads for the three base rulesets without validation problems`() {
        for (base in listOf(BaseRuleset.Civ_V_Vanilla, BaseRuleset.Civ_V_GnK, BaseRuleset.Civ_V_BNW)) {
            val ruleset = RulesetCache[base.fullName]!!
            assertTrue(base.fullName, ruleset.ai.economicStrategies.isNotEmpty() && ruleset.ai.grandStrategies.size == 4)
            val problems = ruleset.getErrorList().filter { "AI.json" in it.text || it.text.startsWith("Personality ") }
            assertTrue(problems.joinToString { it.text }, problems.isEmpty())
            // every major has leader flavors through its personality
            for (nation in ruleset.nations.values.filter { it.isMajorCiv })
                assertTrue(nation.name, ruleset.personalities[nation.personality]?.flavors?.isNotEmpty() == true)
        }
    }
}
