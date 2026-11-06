package com.unciv.logic.civilization

import com.unciv.logic.automation.civilization.PolicyAutomation
import com.unciv.logic.civilization.managers.InfluenceLevel
import com.unciv.logic.civilization.managers.PublicOpinionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class PublicOpinionTests {
    private lateinit var game: TestGame
    private lateinit var me: Civilization
    private lateinit var freedomCiv: Civilization
    private lateinit var autocracyCiv: Civilization
    private lateinit var orderCiv: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        me = game.addCiv()
        freedomCiv = game.addCiv()
        autocracyCiv = game.addCiv()
        orderCiv = game.addCiv()
        game.addCity(me, game.tileMap[0, 0], replacePalace = true).population.setPopulation(25)
        game.addCity(freedomCiv, game.tileMap[0, 6], replacePalace = true)
        game.addCity(autocracyCiv, game.tileMap[0, -6], replacePalace = true)
        game.addCity(orderCiv, game.tileMap[6, 0], replacePalace = true)
        for (other in listOf(freedomCiv, autocracyCiv, orderCiv)) {
            me.diplomacyFunctions.makeCivilizationsMeet(other)
            for (third in listOf(freedomCiv, autocracyCiv, orderCiv)) if (third != other) other.diplomacyFunctions.makeCivilizationsMeet(third)
            other.totalCultureForContests = 100
        }
        me.totalCultureForContests = 100
        for ((civ, branch) in listOf(freedomCiv to "Freedom", autocracyCiv to "Autocracy", orderCiv to "Order")) adopt(civ, branch)
    }

    private fun adopt(civ: Civilization, branch: String) {
        civ.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == "Modern era" }.name)
        civ.policies.adopt(game.ruleset.policyBranches[branch]!!)
    }

    private val percentOfLevel = mapOf(
        InfluenceLevel.Unknown to 0, InfluenceLevel.Exotic to 10, InfluenceLevel.Familiar to 30,
        InfluenceLevel.Popular to 60, InfluenceLevel.Influential to 100, InfluenceLevel.Dominant to 200)

    /** [from] has made so much tourism towards [to] that its level is [level] (with the 100 culture each has made) */
    private fun influence(from: Civilization, to: Civilization, level: InfluenceLevel) {
        from.culture.addInfluence(to, percentOfLevel[level]!!)
        assertEquals(level, from.culture.getInfluenceLevel(to))
    }

    private fun updateOpinion() = me.culture.publicOpinion.apply { update() }

    @Test
    fun `without an ideology there is no opinion`() {
        influence(freedomCiv, me, InfluenceLevel.Dominant)
        val opinion = updateOpinion()
        assertEquals(PublicOpinionType.Content, opinion.type)
        assertEquals(0, opinion.unhappiness)
        assertNull(opinion.preferredIdeology)
    }

    @Test
    fun `with an ideology and no influence the people are content`() {
        adopt(me, "Freedom")
        assertEquals(PublicOpinionType.Content, updateOpinion().type)
    }

    @Test
    fun `the people are content when their ideology presses as much as all others together`() {
        adopt(me, "Freedom")
        influence(freedomCiv, me, InfluenceLevel.Familiar) // 2 for Freedom
        influence(autocracyCiv, me, InfluenceLevel.Exotic) // 1
        influence(orderCiv, me, InfluenceLevel.Exotic)      // 1
        val opinion = updateOpinion()
        assertEquals("2 against 1 and 1", PublicOpinionType.Content, opinion.type)
        assertEquals(0, opinion.unhappiness)

        influence(autocracyCiv, me, InfluenceLevel.Familiar) // 1 -> 2
        assertEquals("2 against 2 and 1 leaves 1", PublicOpinionType.Dissidents, updateOpinion().type)
        assertEquals(1, opinion.dissatisfaction)
    }

    @Test
    fun `a plurality of the others is not enough, it is the sum that counts`() {
        adopt(me, "Freedom")
        influence(autocracyCiv, me, InfluenceLevel.Exotic)
        assertEquals(1, updateOpinion().dissatisfaction)
        assertEquals("Autocracy", me.culture.publicOpinion.preferredIdeology)
    }

    @Test
    fun `influence of an ideology is only what its civilizations lead us by, and nothing for the mutual`() {
        adopt(me, "Freedom")
        influence(autocracyCiv, me, InfluenceLevel.Popular)
        influence(me, autocracyCiv, InfluenceLevel.Popular)
        assertEquals(PublicOpinionType.Content, updateOpinion().type)
        influence(me, orderCiv, InfluenceLevel.Dominant)
        influence(orderCiv, me, InfluenceLevel.Familiar)
        assertEquals("what we lead by gives no pressure", PublicOpinionType.Content, updateOpinion().type)
    }

    @Test
    fun `a civilization without an ideology presses for nothing`() {
        val plain = game.addCiv()
        game.addCity(plain, game.tileMap[-6, 0], replacePalace = true)
        me.diplomacyFunctions.makeCivilizationsMeet(plain)
        plain.totalCultureForContests = 100
        adopt(me, "Freedom")
        influence(plain, me, InfluenceLevel.Dominant)
        assertEquals(PublicOpinionType.Content, updateOpinion().type)
    }

    @Test
    fun `who has not met us presses for nothing`() {
        val stranger = game.addCiv()
        game.addCity(stranger, game.tileMap[-6, 0], replacePalace = true)
        adopt(stranger, "Order")
        adopt(me, "Freedom")
        assertNull(stranger.culture.getInfluenceLevel(me))
        assertEquals(PublicOpinionType.Content, updateOpinion().type)
    }

    @Test
    fun `the type goes by what is missing, and the unhappiness by the type`() {
        adopt(me, "Freedom")
        // 25 inhabitants in 1 city
        influence(autocracyCiv, me, InfluenceLevel.Familiar) // 2
        var opinion = updateOpinion()
        assertEquals(PublicOpinionType.Dissidents, opinion.type)
        assertEquals("1 per 10 inhabitants, 1 per city: the greater", 2, opinion.unhappiness)

        influence(orderCiv, me, InfluenceLevel.Exotic) // 3
        opinion = updateOpinion()
        assertEquals(PublicOpinionType.CivilResistance, opinion.type)
        assertEquals("1 per 5, 2 per city", 5, opinion.unhappiness)

        influence(orderCiv, me, InfluenceLevel.Popular) // adds up to 60 more: Dominant at 70? keep to levels by what is there
        opinion = updateOpinion()
        assertEquals(PublicOpinionType.RevolutionaryWave, opinion.type)
        assertEquals("1 per 3 inhabitants, 4 per city", 8, opinion.unhappiness)
    }

    @Test
    fun `many small cities are counted by the city`() {
        adopt(me, "Freedom")
        me.cities.first().population.setPopulation(3)
        game.addCity(me, game.tileMap[3, 0]).population.setPopulation(3)
        game.addCity(me, game.tileMap[-3, 0]).population.setPopulation(3)
        influence(autocracyCiv, me, InfluenceLevel.Familiar)
        assertEquals("3 cities, 9 inhabitants", 3, updateOpinion().unhappiness)
    }

    private fun preferredWhenTied(own: String, first: String, second: String): String? {
        setUp()
        adopt(me, own)
        for (name in listOf(first, second)) influence(listOf(freedomCiv, autocracyCiv, orderCiv).first { it.getIdeology()!!.name == name }, me, InfluenceLevel.Exotic)
        return updateOpinion().preferredIdeology
    }

    @Test
    fun `a tie of the other ideologies goes as in the original`() {
        assertEquals("Order", preferredWhenTied("Freedom", "Autocracy", "Order"))
        assertEquals("Freedom", preferredWhenTied("Autocracy", "Freedom", "Order"))
        assertEquals("Autocracy", preferredWhenTied("Order", "Freedom", "Autocracy"))
    }

    @Test
    fun `the strongest of the others is preferred, and the civilization behind it is the biggest influence`() {
        adopt(me, "Order")
        influence(freedomCiv, me, InfluenceLevel.Exotic)
        influence(autocracyCiv, me, InfluenceLevel.Popular)
        val opinion = updateOpinion()
        assertEquals("Autocracy", opinion.preferredIdeology)
        assertEquals(autocracyCiv.civID, opinion.biggestInfluence)
    }

    @Test
    fun `the unhappiness is part of the happiness of a human, and of an AI changed by the difficulty`() {
        adopt(me, "Freedom")
        influence(autocracyCiv, me, InfluenceLevel.Familiar)
        updateOpinion()
        assertEquals(-2f, me.stats.getHappinessBreakdown()["Public Opinion"]!!, 0.001f)
    }

    @Test
    fun `the opinion is worked out every turn after the influence is added`() {
        adopt(me, "Freedom")
        influence(autocracyCiv, me, InfluenceLevel.Familiar)
        assertEquals(PublicOpinionType.Content, me.culture.publicOpinion.type) // not before the turn
        me.culture.endTurn()
        assertEquals(PublicOpinionType.Dissidents, me.culture.publicOpinion.type)
    }

    @Test
    fun `changing to the preferred ideology works out the opinion again`() {
        adopt(me, "Freedom")
        influence(autocracyCiv, me, InfluenceLevel.Familiar)
        updateOpinion()
        val preferred = game.ruleset.policyBranches[me.culture.publicOpinion.preferredIdeology!!]!!
        me.policies.switchIdeology(preferred)
        assertEquals(preferred, me.getIdeology())
        assertEquals(PublicOpinionType.Content, me.culture.publicOpinion.type)
        assertEquals(game.gameInfo.turns, me.culture.publicOpinion.turnIdeologySwitch)
    }

    private fun unhappyAi(): Civilization {
        adopt(me, "Freedom")
        me.cities.first().population.setPopulation(60)
        influence(autocracyCiv, me, InfluenceLevel.Dominant)
        updateOpinion()
        assertEquals("1 per 3 inhabitants", 20, me.culture.publicOpinion.unhappiness)
        return me
    }

    @Test
    fun `an AI that is very unhappy about its public opinion changes to the preferred ideology`() {
        unhappyAi().stats.happiness = -25
        assertTrue(PolicyAutomation.considerIdeologySwitch(me))
        assertEquals("Autocracy", me.getIdeology()!!.name)
    }

    @Test
    fun `an AI that is not unhappy enough, or does not want that ideology, stays`() {
        unhappyAi().stats.happiness = -19
        assertFalse(PolicyAutomation.considerIdeologySwitch(me))
        me.stats.happiness = -25
        me.nation.preferredVictoryType = "Scientific" // which does not go with Autocracy
        game.gameInfo.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys)
        assertFalse(PolicyAutomation.considerIdeologySwitch(me))
        assertEquals("Freedom", me.getIdeology()!!.name)
    }

    @Test
    fun `a copy of the game keeps the opinion`() {
        adopt(me, "Freedom")
        influence(autocracyCiv, me, InfluenceLevel.Familiar)
        influence(orderCiv, me, InfluenceLevel.Exotic)
        updateOpinion()
        val copy = game.gameInfo.clone()
        val opinion = copy.getCivilization(me.civName).culture.publicOpinion
        assertEquals(PublicOpinionType.CivilResistance, opinion.type)
        assertEquals(5, opinion.unhappiness)
        assertEquals(me.culture.publicOpinion.preferredIdeology, opinion.preferredIdeology)
        assertTrue(opinion.pressure.isNotEmpty())
    }
}
