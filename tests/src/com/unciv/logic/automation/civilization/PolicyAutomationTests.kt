package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/** Decision-level checks of the CvPolicyAI / CvPlayerPolicies AI port against the DLL formulas (BNW XML values) */
@RunWith(GdxTestRunner::class)
class PolicyAutomationTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        game.gameInfo.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys)
        civ = neutralCiv()
        game.addCity(civ, game.tileMap[0, 0], replacePalace = true)
        civ.stats.happiness = 0
    }

    /** A major whose personality flavors are all zero */
    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        return civ
    }

    private fun policy(name: String): Policy = game.ruleset.policies[name]!!
    private fun branch(name: String): PolicyBranch = game.ruleset.policyBranches[name]!!
    private fun weight(name: String) = PolicyAutomation.policyWeight(civ, policy(name))

    private fun reachEra(who: Civilization, era: String) {
        who.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == era }.name)
    }

    /** The weight [name] has in the pool of ChooseNextPolicy, null if it is not a candidate */
    private fun choiceWeight(name: String): Int? {
        val choices = PolicyAutomation.weighChoices(civ)
        val index = choices.candidates.indexOfFirst { it.name == name }
        return if (index < 0) null else choices.weights[index]
    }

    //region Policy weights (AddFlavorAsStrategies, AddFlavorWeights, PropagateWeights)
    @Test
    fun `a policy weighs its Policy_Flavors times the personality and grand strategy flavor, a quarter going to its prerequisite`() {
        civ.ai.setPersonalityFlavor(Flavor.Culture, 7)
        // Legalism: CULTURE 20; Oligarchy is its prerequisite and has no culture
        assertEquals(140, weight("Legalism"))
        assertEquals(35, weight("Oligarchy"))
        // the culture grand strategy adds its FlavorMod CULTURE +2
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        PolicyAutomation.flavorUpdate(civ)
        // and its GOLD +1 weighs Monarchy (GOLD 10), a quarter of which goes to Legalism
        assertEquals(180 + 10 / 4, weight("Legalism"))
    }

    @Test
    fun `propagation passes on the propagated weight, prerequisite after prerequisite`() {
        civ.ai.setPersonalityFlavor(Flavor.MilitaryTraining, 4)
        // Professional Army (MILITARY_TRAINING 15) = 60 requires Military Caste (none) requires Discipline (none)
        assertEquals(60, weight("Professional Army"))
        assertEquals(15, weight("Military Caste"))
        assertEquals(3, weight("Discipline"))
    }

    @Test
    fun `at war defense and city defense count 3 more`() {
        val enemy = game.addCiv()
        game.addCity(enemy, game.tileMap[0, 6], replacePalace = true)
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        PolicyAutomation.flavorUpdate(civ)
        assertEquals(0, weight("Oligarchy"))
        civ.getDiplomacyManager(enemy)!!.declareWar()
        PolicyAutomation.flavorUpdate(civ)
        // Oligarchy: DEFENSE 10; Their Finest Hour: CITY_DEFENSE 44
        assertEquals(30, weight("Oligarchy"))
        assertEquals(132, weight("Their Finest Hour"))
    }

    @Test
    fun `an unhappy empire counts happiness 5 more`() {
        civ.stats.happiness = -1
        PolicyAutomation.flavorUpdate(civ)
        // Landed Elite: HAPPINESS 20
        assertEquals(100, weight("Landed Elite"))
    }

    @Test
    fun `losing money counts gold 5 more, not the gold flavor of the strategy`() {
        civ.ai.economicStrategies["ECONOMICAISTRATEGY_LOSING_MONEY"] = 0
        PolicyAutomation.flavorUpdate(civ)
        // Oligarchy GOLD 10 = 50, plus Monarchy (GOLD 10 = 50) → 12 to Legalism → 3 to Oligarchy. The strategy's own GOLD +25 is not used
        assertEquals(53, weight("Oligarchy"))
    }

    @Test
    fun `weights stay those of the last flavor change until the strategies change`() {
        PolicyAutomation.flavorUpdate(civ)
        civ.stats.happiness = -1
        assertEquals("not recomputed for the unhappiness alone", 0, weight("Landed Elite"))
        civ.ai.setPersonalityFlavor(Flavor.Offense, 0) // any change of the flavors
        assertEquals(100, weight("Landed Elite"))
    }
    //endregion

    //region Choosing (ChooseNextPolicy, WeighBranch, IsBranchEffectiveInGame)
    @Test
    fun `opening a branch weighs its opener and its policies without prerequisites, at 22 percent`() {
        civ.ai.setPersonalityFlavor(Flavor.Culture, 7)
        civ.policies.storedCulture = 100000
        // Tradition: opener CULTURE 5 = 35, Oligarchy 35 (from Legalism), Aristocracy 0: 70 × 22%
        assertEquals(15, choiceWeight("Tradition"))
        // Liberty: Citizenship 17 (from Representation's 70); Honor: Discipline 17 (from Military Caste's 70)
        assertEquals(3, choiceWeight("Liberty"))
        assertEquals(3, choiceWeight("Honor"))
    }

    @Test
    fun `under the culture grand strategy a new branch weighs a third`() {
        civ.ai.setPersonalityFlavor(Flavor.Culture, 7)
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        PolicyAutomation.flavorUpdate(civ)
        civ.policies.storedCulture = 100000
        val branchWeight = weight("Tradition") + weight("Aristocracy") + weight("Oligarchy")
        assertEquals(branchWeight * 22 / 100 / 3, choiceWeight("Tradition"))
    }

    @Test
    fun `the policy that completes a branch weighs the finisher too`() {
        civ.ai.setPersonalityFlavor(Flavor.Growth, 3)
        civ.policies.freePolicies = 5
        for (name in listOf("Tradition", "Aristocracy", "Oligarchy", "Legalism")) civ.policies.adopt(policy(name))
        civ.policies.freePolicies = 0
        civ.policies.storedCulture = 100000
        // Landed Elite and Monarchy (no growth): neither completes the branch yet
        assertEquals(0, choiceWeight("Monarchy"))
        civ.policies.freePolicies = 1
        civ.policies.adopt(policy("Landed Elite"))
        // Tradition Complete: GROWTH 10
        assertEquals(30, choiceWeight("Monarchy"))
    }

    @Test
    fun `Patronage is worth nothing in a game without city-states`() {
        civ.ai.setPersonalityFlavor(Flavor.Diplomacy, 7)
        reachEra(civ, "Classical era")
        civ.policies.storedCulture = 100000
        assertEquals(0, choiceWeight("Patronage"))
        game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        // Philantropy 70 + 8 (Scholasticism) + 2 (Cultural Diplomacy), Consulates 140, opener 140: 360 × 22%
        assertEquals(79, choiceWeight("Patronage"))
    }

    private fun adoptTenetsUpToLevel3(ideology: PolicyBranch) {
        civ.policies.adopt(ideology)
        civ.policies.freeTenets = 10
        for (tenet in ideology.policies.filter { it.tenetLevel == 1 }.take(3)) civ.policies.adopt(tenet)
        for (tenet in ideology.policies.filter { it.tenetLevel == 2 }.take(2)) civ.policies.adopt(tenet)
        civ.policies.freeTenets = 0
        civ.policies.storedCulture = 100000
    }

    @Test
    fun `a level-3 tenet with the flavor of the grand strategy is taken at once`() {
        reachEra(civ, "Modern era")
        adoptTenetsUpToLevel3(branch("Freedom"))
        assertNull("no grand strategy", PolicyAutomation.weighChoices(civ).immediate)
        civ.ai.grandStrategy = GrandStrategyAi.SPACESHIP
        assertEquals("Space Procurements", PolicyAutomation.chooseNextPolicy(civ, Random(1))!!.name)
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        assertEquals("Media Culture", PolicyAutomation.chooseNextPolicy(civ, Random(1))!!.name)
        civ.ai.grandStrategy = GrandStrategyAi.CONQUEST // Freedom has no level-3 tenet with OFFENSE
        assertNull(PolicyAutomation.weighChoices(civ).immediate)
    }

    @Test
    fun `with free tenets only tenets are candidates`() {
        reachEra(civ, "Modern era")
        civ.policies.adopt(branch("Order"))
        civ.policies.storedCulture = 100000
        civ.policies.freeTenets = 1
        val candidates = PolicyAutomation.weighChoices(civ).candidates
        assertTrue(candidates.isNotEmpty())
        assertTrue(candidates.all { it.isTenet })
        civ.policies.freeTenets = 0
        assertTrue(PolicyAutomation.weighChoices(civ).candidates.any { it.name == "Tradition" })
    }

    @Test
    fun `the pick is weighted among the top choices only, or uniform when nothing weighs`() {
        val weights = listOf(10, 50, 40, 0)
        val picks = (0 until 200).map { PolicyAutomation.pickFromTop(weights, 2, Random(it)) }.toSet()
        assertEquals(setOf(1, 2), picks)
        val uniform = (0 until 200).map { PolicyAutomation.pickFromTop(listOf(0, 0, 0), 2, Random(it)) }.toSet()
        assertEquals(setOf(0, 1, 2), uniform)
    }
    //endregion

    //region Ideology (DoChooseIdeology)
    private val ideologies get() = PolicyAutomation.ideologiesOf(game.ruleset)!!

    /** The random add-ons of DoChooseIdeology for Freedom, Autocracy, Order with this seed */
    private fun randoms(seed: Int) = Random(seed).let { r -> IntArray(3) { r.nextInt(10) } }

    private fun setPriorities(conquest: Int, unitedNations: Int, spaceship: Int, culture: Int) {
        civ.ai.grandStrategyPriorities[GrandStrategyAi.CONQUEST] = conquest
        civ.ai.grandStrategyPriorities[GrandStrategyAi.UNITED_NATIONS] = unitedNations
        civ.ai.grandStrategyPriorities[GrandStrategyAi.SPACESHIP] = spaceship
        civ.ai.grandStrategyPriorities[GrandStrategyAi.CULTURE] = culture
    }

    @Test
    fun `the grand strategies split 300 points over the sum of the three ideology totals, a clear preference rules one out`() {
        // free tenets 2 × 36 and public opinion (100 - 0) × 3 for each (no buildings, no relations)
        val base = 72 + 300
        setPriorities(100, 100, 100, 100)
        var r = randoms(5)
        var scores = PolicyAutomation.scoreIdeologies(civ, ideologies, Random(5))
        // each total 300 of 900
        assertEquals(listOf(100 + base + r[0], 100 + base + r[1], 100 + base + r[2]), scores.toList())

        setPriorities(0, 0, 100, 0)
        r = randoms(6)
        scores = PolicyAutomation.scoreIdeologies(civ, ideologies, Random(6))
        // Freedom 100, Autocracy 0, Order 100 of 200; science clearly first: no Autocracy
        assertEquals(listOf(150 + base + r[0], 0, 150 + base + r[2]), scores.toList())
    }

    @Test
    fun `free tenets are expected for the first and second adopter, if the ideology is not of an era before the start`() {
        val freedom = branch("Freedom")
        assertEquals(2, PolicyAutomation.numFreeTenets(civ, freedom))
        val first = game.addCiv()
        game.addCity(first, game.tileMap[0, 6], replacePalace = true)
        first.policies.adopt(freedom)
        assertEquals(1, PolicyAutomation.numFreeTenets(civ, freedom))
        val second = game.addCiv()
        game.addCity(second, game.tileMap[0, -6], replacePalace = true)
        second.policies.adopt(freedom)
        assertEquals(0, PolicyAutomation.numFreeTenets(civ, freedom))
        assertEquals(2, PolicyAutomation.numFreeTenets(civ, branch("Order")))
        game.gameInfo.gameParameters.startingEra = "Modern era"
        assertEquals(0, PolicyAutomation.numFreeTenets(civ, branch("Order")))
    }

    @Test
    fun `the approach shown to a major with an ideology scores for or against it`() {
        val other = game.addCiv()
        game.addCity(other, game.tileMap[0, 6], replacePalace = true)
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        other.policies.adopt(branch("Autocracy"))
        setPriorities(0, 0, 0, 0)
        fun delta(approach: MajorCivApproach): List<Int> {
            civ.ai.diplomacy.setApproach(other, MajorCivApproach.Neutral)
            val before = PolicyAutomation.scoreIdeologies(civ, ideologies, Random(3))
            civ.ai.diplomacy.setApproach(other, approach)
            val after = PolicyAutomation.scoreIdeologies(civ, ideologies, Random(3))
            return after.indices.map { after[it] - before[it] }
        }
        // Freedom, Autocracy, Order
        assertEquals(listOf(8, 0, 8), delta(MajorCivApproach.Hostile))
        assertEquals(listOf(4, 0, 4), delta(MajorCivApproach.Guarded))
        assertEquals(listOf(0, 6, 0), delta(MajorCivApproach.Afraid))
        assertEquals(listOf(0, 12, 0), delta(MajorCivApproach.Friendly))
        assertEquals("war (its face neutral) counts nothing", listOf(0, 0, 0), delta(MajorCivApproach.War))
    }

    @Test
    fun `building happiness of the tenets counts per tenet that gives it, conquered-city buildings aside`() {
        val city = civ.cities.first()
        val second = game.addCity(civ, game.tileMap[3, -2])
        for (c in listOf(city, second)) c.cityConstructions.addBuilding(game.ruleset.buildings["Monument"]!!)
        city.cityConstructions.addBuilding(game.ruleset.buildings["Courthouse"]!!)
        city.cityConstructions.addBuilding(game.ruleset.buildings["Heroic Epic"]!!)
        // Order: Socialist Realism +2 per Monument, Universal Healthcare +1 per Heroic Epic
        assertEquals(5, PolicyAutomation.branchBuildingHappiness(civ, branch("Order")))
        // Socialist Realism, Young Pioneers, Universal Healthcare (Order), Academy of Sciences
        assertEquals(4, PolicyAutomation.numHappinessPolicies(civ, branch("Order")))
        // Autocracy: Police State's Courthouse counts for the happiness, not as a happiness tenet
        assertEquals(1 + 3, PolicyAutomation.branchBuildingHappiness(civ, branch("Autocracy")))
        assertEquals(3, PolicyAutomation.numHappinessPolicies(civ, branch("Autocracy")))
        setPriorities(0, 0, 0, 0)
        val r = randoms(2)
        val scores = PolicyAutomation.scoreIdeologies(civ, ideologies, Random(2))
        // Freedom: Heroic Epic 1 × 3 / 3 tenets; Autocracy 4 × 3 / 3; Order 5 × 3 / 4
        assertEquals(listOf(72 + 300 + 1 + r[0], 72 + 300 + 4 + r[1], 72 + 300 + 3 + r[2]), scores.toList())
    }

    @Test
    fun `the public opinion an ideology would bring counts against it`() {
        val autocrat = game.addCiv()
        game.addCity(autocrat, game.tileMap[0, 6], replacePalace = true)
        civ.cities.first().population.setPopulation(25)
        civ.diplomacyFunctions.makeCivilizationsMeet(autocrat)
        civ.totalCultureForContests = 100
        autocrat.policies.adopt(branch("Autocracy"))
        autocrat.culture.addInfluence(civ, 200)
        assertEquals(InfluenceLevel.Dominant, autocrat.culture.getInfluenceLevel(civ))
        val opinion = civ.culture.publicOpinion
        // dissatisfaction 5 (Dominant over Unknown): 4 per city or 1 per 3 citizens
        assertEquals(8, opinion.hypotheticalUnhappiness(branch("Freedom")))
        assertEquals(8, opinion.hypotheticalUnhappiness(branch("Order")))
        assertEquals(0, opinion.hypotheticalUnhappiness(branch("Autocracy")))
        setPriorities(0, 0, 0, 0)
        val r = randoms(4)
        val scores = PolicyAutomation.scoreIdeologies(civ, ideologies, Random(4))
        // Autocracy has one adopter: 1 free tenet
        assertEquals(listOf(72 + 92 * 3 + r[0], 36 + 300 + r[1], 72 + 92 * 3 + r[2]), scores.toList())
    }
    //endregion

    //region Ideology switch (DoConsiderIdeologySwitch)
    private lateinit var autocrat: Civilization

    /** We follow Freedom, a dominant Autocracy makes 20 public opinion unhappiness (60 citizens / 3) */
    private fun unhappyWithFreedom() {
        reachEra(civ, "Modern era")
        civ.policies.adopt(branch("Freedom"))
        autocrat = game.addCiv()
        game.addCity(autocrat, game.tileMap[0, 6], replacePalace = true)
        civ.diplomacyFunctions.makeCivilizationsMeet(autocrat)
        civ.totalCultureForContests = 100
        autocrat.policies.adopt(branch("Autocracy"))
        autocrat.culture.addInfluence(civ, 200)
        civ.cities.first().population.setPopulation(60)
        civ.culture.publicOpinion.update()
        assertEquals(20, civ.culture.publicOpinion.unhappiness)
        civ.stats.happiness = -25
    }

    @Test
    fun `a very unhappy AI changes to the ideology its people prefer`() {
        unhappyWithFreedom()
        civ.stats.happiness = -19
        assertFalse("not below SUPER_UNHAPPY_THRESHOLD", PolicyAutomation.considerIdeologySwitch(civ))
        civ.stats.happiness = -20
        assertTrue(PolicyAutomation.considerIdeologySwitch(civ))
        assertEquals("Autocracy", civ.getIdeology()!!.name)
    }

    @Test
    fun `the building happiness of the current ideology's tenets counts in full against a switch`() {
        unhappyWithFreedom()
        val second = game.addCity(civ, game.tileMap[3, -2])
        // Capitalism (Mint, Bank, Stock Exchange) and Urbanization (Water Mill, Hospital, Medical Lab): 6 per city
        for (c in civ.cities) for (name in listOf("Mint", "Bank", "Stock Exchange", "Water Mill", "Hospital", "Medical Lab"))
            c.cityConstructions.addBuilding(game.ruleset.buildings[name]!!)
        civ.culture.publicOpinion.update()
        assertEquals(20, civ.culture.publicOpinion.unhappiness)
        civ.stats.happiness = -25
        // 20 + Autocracy's 0 - Freedom's 12 = 8 < 10 (a per-tenet average, 12 / 3, would let it switch)
        assertFalse(PolicyAutomation.considerIdeologySwitch(civ))
        second.cityConstructions.removeBuilding("Medical Lab")
        second.cityConstructions.removeBuilding("Hospital")
        civ.stats.happiness = -25
        assertTrue("20 + 0 - 10 = 10", PolicyAutomation.considerIdeologySwitch(civ))
    }

    @Test
    fun `no switch to the ideology a clear victory preference rules out`() {
        unhappyWithFreedom()
        setPriorities(100, 100, 126, 100) // science more than 25% ahead of each: not Autocracy
        assertFalse(PolicyAutomation.considerIdeologySwitch(civ))
        setPriorities(100, 100, 125, 100) // not more than 25%
        assertTrue(PolicyAutomation.considerIdeologySwitch(civ))
    }
    //endregion
}
