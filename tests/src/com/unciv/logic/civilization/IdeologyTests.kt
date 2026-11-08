package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class IdeologyTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var other: Civilization
    private lateinit var third: Civilization
    private lateinit var city: City

    private val freedom get() = game.ruleset.policyBranches["Freedom"]!!
    private val order get() = game.ruleset.policyBranches["Order"]!!
    private val autocracy get() = game.ruleset.policyBranches["Autocracy"]!!

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        civ = game.addCiv()
        other = game.addCiv()
        third = game.addCiv()
        city = game.addCity(civ, game.tileMap[0, 0], replacePalace = true)
        game.addCity(other, game.tileMap[0, 6], replacePalace = true)
        game.addCity(third, game.tileMap[0, -6], replacePalace = true)
    }

    private fun reachEra(who: Civilization, era: String) {
        for (tech in game.ruleset.technologies.values.filter { it.era() == era }.take(1)) who.tech.addTechnology(tech.name)
    }

    private fun tenet(name: String): Policy = game.ruleset.policies[name]!!

    private fun tenetsOfLevel(branch: PolicyBranch, level: Int) = branch.policies.filter { it.tenetLevel == level }

    /** Pays with culture, as the AI and a player would */
    private fun buy(who: Civilization, policy: Policy) {
        who.policies.storedCulture = who.policies.getCultureNeededForNextPolicy()
        who.policies.adopt(policy)
    }

    //region When an ideology can be chosen
    @Test
    fun `an ideology is available in the Modern era, or in the Industrial era with three Factories`() {
        reachEra(civ, "Industrial era")
        val gate = freedom.getMatchingUniques(UniqueType.Unavailable, com.unciv.models.ruleset.unique.GameContext.IgnoreConditionals).toList()
        assertFalse("era ${civ.getEra().name}, unavailable uniques $gate, apply ${gate.map { u -> u.modifiers.map { m -> com.unciv.models.ruleset.unique.Conditionals.conditionalApplies(u, m, civ.state) } }}, factories ${com.unciv.models.ruleset.unique.Countables.getCountableAmount("[Factory] Buildings", civ.state)}, era number ${civ.getEraNumber()}", civ.policies.isAdoptable(freedom))
        assertFalse(civ.policies.canChooseIdeology())
        val factory = game.ruleset.buildings["Factory"]!!
        city.cityConstructions.addBuilding(factory)
        val second = game.addCity(civ, game.tileMap[3, -2])
        val last = game.addCity(civ, game.tileMap[-3, 2])
        second.cityConstructions.addBuilding(factory)
        assertFalse("two factories", civ.policies.isAdoptable(freedom))
        last.cityConstructions.addBuilding(factory)
        assertTrue("three factories", civ.policies.isAdoptable(freedom))
        assertTrue(civ.policies.canChooseIdeology())

        reachEra(other, "Modern era")
        assertTrue("the Modern era, no factories", other.policies.isAdoptable(freedom))
    }

    @Test
    fun `an ideology is not available before the Industrial era, even with factories`() {
        reachEra(civ, "Medieval era")
        assertFalse(civ.policies.isAdoptable(order))
    }

    @Test
    fun `choosing an ideology costs nothing and does not make the next policy dearer`() {
        reachEra(civ, "Modern era")
        val cost = civ.policies.getCultureNeededForNextPolicy()
        assertTrue(civ.policies.canAdopt(freedom))
        assertEquals(0, civ.policies.storedCulture)
        civ.policies.adopt(freedom)
        assertEquals(freedom, civ.policies.getIdeology())
        assertEquals(0, civ.policies.storedCulture)
        assertEquals(cost, civ.policies.getCultureNeededForNextPolicy())
    }

    @Test
    fun `only one ideology at a time`() {
        reachEra(civ, "Modern era")
        civ.policies.adopt(freedom)
        assertFalse(civ.policies.isAdoptable(order))
        assertFalse(civ.policies.isAdoptable(autocracy))
        assertFalse(civ.policies.canChooseIdeology())
    }

    @Test
    fun `the first civilization gets two free tenets, the second one, later ones none`() {
        for (who in listOf(civ, other, third)) reachEra(who, "Modern era")
        civ.policies.adopt(order)
        assertEquals(2, civ.policies.freeTenets)
        other.policies.adopt(order)
        assertEquals(1, other.policies.freeTenets)
        third.policies.adopt(order)
        assertEquals(0, third.policies.freeTenets)
    }
    //endregion

    //region Tenets
    @Test
    fun `a free tenet pays for a tenet, not for another policy, and does not make the next one dearer`() {
        reachEra(civ, "Modern era")
        civ.policies.adopt(freedom)
        assertEquals(2, civ.policies.freeTenets)
        val level1 = tenetsOfLevel(freedom, 1)
        val cost = civ.policies.getCultureNeededForNextPolicy()

        assertTrue(civ.policies.canPayFor(level1.first()))
        civ.policies.adopt(level1.first())
        assertEquals(1, civ.policies.freeTenets)
        assertEquals(cost, civ.policies.getCultureNeededForNextPolicy())

        val ordinary = game.ruleset.policyBranches.values.first { !it.isIdeology }
        assertFalse("free tenets are for tenets", civ.policies.canPayFor(ordinary))
    }

    @Test
    fun `a free policy pays for a tenet too, and free tenets are spent before free policies`() {
        reachEra(civ, "Modern era")
        civ.policies.adopt(freedom)
        civ.policies.freePolicies = 1
        civ.policies.adopt(tenetsOfLevel(freedom, 1)[0])
        civ.policies.adopt(tenetsOfLevel(freedom, 1)[1])
        assertEquals(0, civ.policies.freeTenets)
        assertEquals(1, civ.policies.freePolicies)
        civ.policies.adopt(tenetsOfLevel(freedom, 1)[2])
        assertEquals(0, civ.policies.freePolicies)
    }

    @Test
    fun `a tenet of a level above the first needs two more tenets of the level below than it has of its own`() {
        reachEra(civ, "Modern era")
        civ.policies.adopt(freedom)
        val level1 = tenetsOfLevel(freedom, 1)
        val level2 = tenetsOfLevel(freedom, 2)
        civ.policies.freeTenets = 20
        civ.policies.adopt(level1[0])
        assertFalse("one tenet of the first level", civ.policies.isAdoptable(level2[0]))
        civ.policies.adopt(level1[1])
        assertTrue("two", civ.policies.isAdoptable(level2[0]))
        civ.policies.adopt(level2[0])
        assertFalse("two below, one here", civ.policies.isAdoptable(level2[1]))
        civ.policies.adopt(level1[2])
        assertTrue("three below, one here", civ.policies.isAdoptable(level2[1]))
    }

    @Test
    fun `the third level follows the second in the same way`() {
        reachEra(civ, "Modern era")
        civ.policies.adopt(order)
        civ.policies.freeTenets = 30
        for (t in tenetsOfLevel(order, 1)) civ.policies.adopt(t)
        val level2 = tenetsOfLevel(order, 2)
        val level3 = tenetsOfLevel(order, 3)
        civ.policies.adopt(level2[0])
        assertFalse(civ.policies.isAdoptable(level3[0]))
        civ.policies.adopt(level2[1])
        assertTrue(civ.policies.isAdoptable(level3[0]))
    }


    @Test
    fun `each ideology has the tenets of the original, 7 6 and 3 by level, and they can all be adopted to complete it`() {
        reachEra(civ, "Modern era")
        for (branch in listOf(freedom, order, autocracy).also { assertTrue(it.all { b -> b.isIdeology }) }) {
            val tenets = branch.policies.filter { it.isTenet }
            assertEquals(branch.name, listOf(7, 6, 3), (1..3).map { level -> tenets.count { it.tenetLevel == level } })
            assertEquals("no two on the same cell", tenets.size, tenets.map { it.row to it.column }.toSet().size)
        }
        civ.policies.adopt(order)
        civ.policies.freeTenets = 100
        for (level in 1..3) for (t in tenetsOfLevel(order, level)) {
            assertTrue("${t.name} can be adopted", civ.policies.isAdoptable(t))
            civ.policies.adopt(t)
        }
        assertTrue(civ.policies.isAdopted("Order Complete"))
    }
    //endregion

    //region Changing the ideology
    private fun civWithBoughtTenets(count: Int) {
        reachEra(civ, "Modern era")
        civ.policies.adopt(freedom)
        civ.policies.freeTenets = 0
        for (t in tenetsOfLevel(freedom, 1).take(count)) buy(civ, t)
    }

    @Test
    fun `a change of ideology loses the tenets, keeps all but two as free tenets, loses the stored culture and starts anarchy`() {
        civWithBoughtTenets(4)
        civ.policies.storedCulture = 123
        civ.policies.switchIdeology(order)

        assertEquals(order, civ.policies.getIdeology())
        assertTrue(civ.policies.getAdoptedTenets(freedom).isEmpty())
        assertFalse(civ.policies.isAdopted("Freedom"))
        assertEquals("2 are left of 4, and the first to choose Order gets 2", 4, civ.policies.freeTenets)
        assertEquals(0, civ.policies.storedCulture)
        assertTrue(civ.hasFlag(CivFlags.Anarchy.name))
    }

    @Test
    fun `after a change of ideology the next policy costs as much as it should have for what is bought`() {
        civWithBoughtTenets(4)
        val before = civ.policies.getCultureNeededForNextPolicy()
        civ.policies.switchIdeology(order)
        // 4 bought, 2 tenets lost, and the 2 free tenets of the first Order adopter are not bought
        assertEquals(before, civ.policies.getCultureNeededForNextPolicy())

        // a third adopter of Order has none of those
        val late = game.addCiv()
        game.addCity(late, game.tileMap[3, -3], replacePalace = true)
        for (who in listOf(other, late)) { reachEra(who, "Modern era"); who.policies.adopt(order) }
        reachEra(third, "Modern era")
        third.policies.adopt(freedom)
        third.policies.freeTenets = 0
        for (t in tenetsOfLevel(freedom, 1).take(4)) buy(third, t)
        third.policies.switchIdeology(order)
        assertEquals(civ.policies.getPolicyCultureCost(2), third.policies.getCultureNeededForNextPolicy())
        assertEquals("only the 2 that were not lost count as free", 2, third.policies.freeTenets)
    }

    @Test
    fun `few tenets are all lost, and become no free tenets`() {
        civWithBoughtTenets(1)
        civ.policies.switchIdeology(order)
        assertEquals("only what the first adopter of Order gets", 2, civ.policies.freeTenets)
    }

    @Test
    fun `there is no income during anarchy but the costs go on`() {
        reachEra(civ, "Modern era")
        city.cityConstructions.addBuilding(game.createBuilding("[+3 Culture] [in this city]").also { it.cost = 1 })
        city.cityStats.update()
        val before = city.cityStats.currentCityStats
        assertTrue(before.culture >= 3f)

        civ.addFlag(CivFlags.Anarchy.name, 2)
        city.cityStats.update()
        val during = city.cityStats.currentCityStats
        assertEquals(0f, during.culture, 0f)
        assertEquals(0f, during.science, 0f)
        assertEquals(0f, during.faith, 0f)
        assertTrue(during.gold <= 0f)
        assertEquals(before.food, during.food, 0.001f)

        civ.removeFlag(CivFlags.Anarchy.name)
        city.cityStats.update()
        assertEquals(before.culture, city.cityStats.currentCityStats.culture, 0.001f)
    }
    //endregion

    //region Filters and tourism
    @Test
    fun `the same and a different ideology are civilization filters`() {
        for (who in listOf(civ, other, third)) reachEra(who, "Modern era")
        civ.policies.adopt(freedom)
        other.policies.adopt(freedom)
        third.policies.adopt(order)
        assertTrue(other.matchesFilter("Same Ideology", civ.state))
        assertFalse(third.matchesFilter("Same Ideology", civ.state))
        assertTrue(third.matchesFilter("Different Ideology", civ.state))
        assertFalse(other.matchesFilter("Different Ideology", civ.state))
        val without = game.addCiv()
        assertFalse("without an ideology there is neither", without.matchesFilter("Different Ideology", civ.state))
        assertFalse(without.matchesFilter("Same Ideology", civ.state))
    }

    private fun tourismToward(who: Civilization, target: Civilization): Int {
        val gallery = game.createBuilding("Has [10] [Art/Artifact] Great Work slots")
        who.cities.first().cityConstructions.addBuilding(gallery)
        repeat(5) {
            val work = game.createGreatWork()
            who.cities.first().greatWorks.place(com.unciv.models.ruleset.GreatWorkInstance(work.name, who.civName, "Ancient era", 1),
                com.unciv.logic.city.GreatWorkSlot(who.cities.first(), gallery, it))
        }
        who.diplomacyFunctions.makeCivilizationsMeet(target)
        who.getDiplomacyManager(target)!!.makePeace()
        return who.culture.getInfluencePerTurn(target) // 10 without modifiers
    }

    @Test
    fun `different ideologies make a third less tourism, the same one a policy can add to`() {
        for (who in listOf(civ, other, third)) reachEra(who, "Modern era")
        civ.policies.adopt(freedom)
        other.policies.adopt(order)
        third.policies.adopt(freedom)
        assertEquals("10 less 34%", 6, tourismToward(civ, other))
        civ.diplomacyFunctions.makeCivilizationsMeet(third)
        civ.getDiplomacyManager(third)!!.makePeace()
        assertEquals("the same ideology has no base modifier", 10, civ.culture.getInfluencePerTurn(third))
    }

    @Test
    fun `a policy that adds to the tourism towards the same ideology does so`() {
        val sharer = game.addCiv("[+34]% Tourism towards civilizations with [Shared Ideology]")
        game.addCity(sharer, game.tileMap[4, -4], replacePalace = true)
        for (who in listOf(sharer, other, third)) reachEra(who, "Modern era")
        sharer.policies.adopt(freedom)
        other.policies.adopt(order)
        third.policies.adopt(freedom)
        tourismToward(sharer, third)
        assertEquals("10 and 34% more", 13, sharer.culture.getInfluencePerTurn(third))
        sharer.diplomacyFunctions.makeCivilizationsMeet(other)
        sharer.getDiplomacyManager(other)!!.makePeace()
        assertEquals("10 less 34% for a different ideology, nothing from the policy", 6, sharer.culture.getInfluencePerTurn(other))
    }
    //endregion

    @Test
    fun `Space Procurements is what lets spaceship parts be bought`() {
        val part = game.ruleset.units["SS Booster"]!!
        assertTrue(part.hasUnique(UniqueType.CannotBePurchased, city.state))
        reachEra(civ, "Modern era")
        civ.policies.adopt(freedom)
        civ.policies.adopt(tenet("Space Procurements"))
        city.state.let { assertFalse(part.hasUnique(UniqueType.CannotBePurchased, civ.cities.first().state)) }
    }
}
