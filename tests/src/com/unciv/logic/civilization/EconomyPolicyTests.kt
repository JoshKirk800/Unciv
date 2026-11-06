package com.unciv.logic.civilization

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Economy and culture policies with rules that the original engine implements outside the policy table */
@RunWith(GdxTestRunner::class)
class EconomyPolicyTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
    }

    private val ironCurtainUnique get() =
        game.ruleset.policies["Iron Curtain"]!!.uniques.single { "upon acquiring a city" in it }

    @Test
    fun `Iron Curtain gives a free Courthouse in every acquired city, conquered or traded, but not in a founded one`() {
        val owner = game.addCiv(ironCurtainUnique)
        val founded = game.addCity(owner, game.tileMap[0, 0], replacePalace = true)
        assertFalse("founding does not count", founded.cityConstructions.isBuilt("Courthouse"))

        val victim = game.addCiv()
        game.addCity(victim, game.tileMap[0, 6], replacePalace = true)
        val traded = game.addCity(victim, game.tileMap[4, 0])
        val conquered = game.addCity(victim, game.tileMap[-4, 0])
        assertFalse(traded.cityConstructions.isBuilt("Courthouse"))

        traded.moveToCiv(owner)
        assertTrue("trade", traded.cityConstructions.isBuilt("Courthouse"))
        assertEquals(owner, traded.civ)

        owner.diplomacyFunctions.makeCivilizationsMeet(victim)
        conquered.puppetCity(owner)
        assertTrue("conquest", conquered.cityConstructions.isBuilt("Courthouse"))
    }

    @Test
    fun `without Iron Curtain an acquired city gets no Courthouse`() {
        val owner = game.addCiv()
        game.addCity(owner, game.tileMap[0, 0], replacePalace = true)
        val victim = game.addCiv()
        game.addCity(victim, game.tileMap[0, 6], replacePalace = true)
        val other = game.addCity(victim, game.tileMap[4, 0])
        other.moveToCiv(owner)
        assertFalse(other.cityConstructions.isBuilt("Courthouse"))
    }

    private var nextX = -6

    private fun populationUnhappiness(vararg uniques: String, specialists: Int): Float {
        val civ = game.addCiv(*uniques)
        nextX += 2
        val city = game.addCity(civ, game.tileMap[nextX, 0], replacePalace = true)
        city.population.setPopulation(6)
        city.population.specialistAllocations.clear()
        if (specialists > 0) city.population.specialistAllocations["Scientist"] = specialists
        city.cityStats.update()
        return -city.cityStats.happinessList["Population"]!!
    }

    @Test
    fun `Universal Suffrage removes half of the specialist unhappiness rounded up`() {
        val suffrage = game.ruleset.policies["Universal Suffrage"]!!.uniques.single { "Unhappiness from" in it }
        val plain = populationUnhappiness(specialists = 3)
        // 3 specialists: (3 + 1) / 2 = 2 citizens stop causing unhappiness, 6 -> 4
        assertEquals(plain * 4 / 6, populationUnhappiness(suffrage, specialists = 3), 0.001f)
        // 4 specialists: exactly 2 less
        assertEquals(populationUnhappiness(specialists = 4) * 4 / 6, populationUnhappiness(suffrage, specialists = 4), 0.001f)
        // no specialists, no change
        assertEquals(populationUnhappiness(specialists = 0), populationUnhappiness(suffrage, specialists = 0), 0.001f)
    }

    @Test
    fun `a gold bonus on City-State routes, as Merchant Confederacy gives, applies to routes to City-States only`() {
        val bonusUnique = game.ruleset.policies["Merchant Confederacy"]!!.uniques.single { "City-State" in it }
        val major = game.addCiv("[+4] Trade Route slots")
        val rival = game.addCiv()
        val cs = game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        val origin = game.addCity(major, game.tileMap[0, 0], replacePalace = true)
        val toCityState = game.addCity(cs, game.tileMap[0, 5], replacePalace = true)
        val toMajor = game.addCity(rival, game.tileMap[5, 0], replacePalace = true)
        for (other in listOf(rival, cs)) {
            major.diplomacyFunctions.makeCivilizationsMeet(other)
            major.getDiplomacyManager(other)!!.makePeace()
        }
        for (tile in game.tileMap.values) for (civ in listOf(major, rival, cs)) tile.setExplored(civ, true)
        val caravan = game.createBaseUnit("Civilian", "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn")

        fun gold(destination: com.unciv.logic.city.City): Float {
            val unit = game.addUnit(caravan.name, major, origin.getCenterTile())
            val route = major.tradeRoutes.establish(unit, destination, com.unciv.models.TradeRouteKind.International)!!
            val result = route.originYields.gold
            major.tradeRoutes.routes.clear()
            major.tradeRoutes.updateYields()
            return result
        }

        val cityStateBefore = gold(toCityState)
        val majorBefore = gold(toMajor)
        origin.cityConstructions.addBuilding(game.createBuilding(bonusUnique + " <in this city>"))
        assertEquals(cityStateBefore + 2f, gold(toCityState), 0.02f)
        assertEquals(majorBefore, gold(toMajor), 0.02f)
    }
}
