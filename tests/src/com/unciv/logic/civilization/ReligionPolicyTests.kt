package com.unciv.logic.civilization

import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.BeliefType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ReligionPolicyTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(5)
    }

    @Test
    fun `Reformation belief is offered once after founding and never as an ordinary enhancer`() {
        val civ = game.addCiv()
        game.addCity(civ, game.getTile(0, 0))
        val manager = civ.religionManager
        val reformation = game.ruleset.beliefs["Sacred Sites"]!!
        assertEquals(BeliefType.Reformation, reformation.type)
        assertFalse(BeliefType.Enhancer.includes(reformation.type))
        assertFalse(BeliefType.Any.includes(reformation.type))

        val religion = game.addReligion(civ)
        religion.addBeliefs(listOf(game.ruleset.beliefs["Church Property"]!!))
        manager.checkReformationBelief()
        assertEquals(0, manager.freeBeliefs[BeliefType.Reformation.name])
        civ.policies.freePolicies = 3
        civ.policies.adopt(game.ruleset.policyBranches["Piety"]!!)
        civ.policies.adopt(game.ruleset.policies["Organized Religion"]!!)
        civ.policies.adopt(game.ruleset.policies["Free Religion"]!!)
        civ.policies.freePolicies = 1
        civ.policies.adopt(game.ruleset.policies["Reformation"]!!)
        assertEquals(1, manager.freeBeliefs[BeliefType.Reformation.name])
        manager.freeBeliefs.clear()
        religion.addBeliefs(listOf(reformation))
        assertTrue(religion.hasBelief(reformation.name))
        manager.checkReformationBelief()
        assertEquals(0, manager.freeBeliefs[BeliefType.Reformation.name])
    }

    @Test
    fun `Evangelism erodes other pressure and Unity retains pressure after a prophet spread`() {
        val civ = game.addCiv()
        val other = game.addCiv()
        val city = game.addCity(other, game.getTile(0, 0), initialPopulation = 5)
        val evangelical = game.addReligion(civ)
        val retained = game.addReligion(other)
        evangelical.addBeliefs(listOf(game.ruleset.beliefs["Church Property"]!!, game.ruleset.beliefs["Evangelism"]!!))
        retained.addBeliefs(listOf(game.ruleset.beliefs["Tithe"]!!, game.ruleset.beliefs["Unity of the Prophets"]!!))
        city.religion.addPressure(retained.name, 1000)
        city.religion.addPressureFromSpreading(evangelical.name, 400)
        assertEquals(600, city.religion.getPressures()[retained.name])
        city.religion.removeAllPressuresExceptFor(evangelical.name)
        assertEquals(300, city.religion.getPressures()[retained.name])
    }

    @Test
    fun `Sacred Sites grants tourism in a foreign city following the founder religion`() {
        val founder = game.addCiv()
        val owner = game.addCiv()
        val religion = game.addReligion(founder)
        religion.addBeliefs(listOf(game.ruleset.beliefs["Sacred Sites"]!!))
        val city = game.addCity(owner, game.getTile(0, 0))
        city.cityConstructions.addBuilding(game.ruleset.buildings["Pagoda"]!!)
        assertEquals(0, owner.culture.getCityTourismBeforeModifiers(city))
        city.religion.addPressure(religion.name, 1000)
        assertEquals(2, owner.culture.getCityTourismBeforeModifiers(city))
    }

    @Test
    fun `Candi faith rises with each major religion that has a follower in the city`() {
        val civ = game.addCiv(game.ruleset.nations["Indonesia"]!!)
        val other = game.addCiv()
        val city = game.addCity(civ, game.getTile(0, 0), initialPopulation = 6)
        city.cityConstructions.addBuilding(game.ruleset.buildings["Candi"]!!)
        val first = game.addReligion(civ)
        val second = game.addReligion(other)
        first.addBeliefs(listOf(game.ruleset.beliefs["Church Property"]!!))
        second.addBeliefs(listOf(game.ruleset.beliefs["Tithe"]!!))
        fun faith() = city.cityStats.finalStatList.values.sumOf { it.faith.toDouble() }
        city.cityStats.update()
        val none = faith()
        city.religion.addPressure(first.name, 1200)
        city.cityStats.update()
        assertEquals(none + 2, faith(), 0.01)
        city.religion.addPressure(second.name, 1000)
        city.cityStats.update()
        assertEquals(none + 4, faith(), 0.01)
    }

    @Test
    fun `Arabian outgoing trade route doubles natural pressure only in its direction`() {
        val arabia = game.addCiv(game.ruleset.nations["Arabia"]!!)
        val other = game.addCiv()
        val origin = game.addCity(arabia, game.getTile(0, 0))
        val destination = game.addCity(other, game.getTile(0, 5))
        val islam = game.addReligion(arabia)
        val otherReligion = game.addReligion(other)
        islam.addBeliefs(listOf(game.ruleset.beliefs["Church Property"]!!))
        otherReligion.addBeliefs(listOf(game.ruleset.beliefs["Tithe"]!!))
        origin.religion.addPressure(islam.name, 1000)
        destination.religion.addPressure(otherReligion.name, 1000)
        val incoming = destination.religion.getPressuresFromSurroundingCities()[islam.name]
        val outgoing = origin.religion.getPressuresFromSurroundingCities()[otherReligion.name]
        assertTrue(incoming > 0)
        assertTrue(outgoing > 0)
        arabia.diplomacyFunctions.makeCivilizationsMeet(other)
        arabia.getDiplomacyManager(other)!!.makePeace()
        for (tile in game.tileMap.values) {
            tile.setExplored(arabia, true)
            tile.setExplored(other, true)
        }
        val trader = game.createBaseUnit("Civilian", "Can establish trade routes with a base range of [10] tiles, travelling [2] tiles per turn")
        val unit = game.addUnit(trader.name, arabia, origin.getCenterTile())
        assertTrue(arabia.tradeRoutes.establish(unit, destination, com.unciv.models.TradeRouteKind.International) != null)
        assertEquals(incoming * 2, destination.religion.getPressuresFromSurroundingCities()[islam.name])
        assertEquals(outgoing, origin.religion.getPressuresFromSurroundingCities()[otherReligion.name])
    }

    @Test
    fun `Free Religion applies the secondary religion pantheon to city yields`() {
        val owner = game.addCiv()
        val other = game.addCiv()
        val city = game.addCity(owner, game.getTile(0, 0), initialPopulation = 8)
        city.cityConstructions.addBuilding(game.ruleset.buildings["Shrine"]!!)
        val majority = game.addReligion(owner)
        val secondary = game.addReligion(other)
        majority.addBeliefs(listOf(game.ruleset.beliefs["Church Property"]!!))
        secondary.addBeliefs(listOf(game.ruleset.beliefs["Tithe"]!!,
            game.createBelief(BeliefType.Pantheon, "[+2 Faith] from every [Shrine]")))
        city.religion.addPressure(majority.name, 1800)
        city.religion.addPressure(secondary.name, 550)
        owner.policies.freePolicies = 3
        owner.policies.adopt(game.ruleset.policyBranches["Piety"]!!)
        owner.policies.adopt(game.ruleset.policies["Organized Religion"]!!)
        city.cityStats.update()
        val without = city.cityStats.finalStatList.values.sumOf { it.faith.toDouble() }
        owner.policies.adopt(game.ruleset.policies["Free Religion"]!!)
        city.cityStats.update()
        val with = city.cityStats.finalStatList.values.sumOf { it.faith.toDouble() }
        assertEquals(without + 2, with, 0.01)
    }

    @Test
    fun `Heathen Conversion captures an adjacent barbarian when a missionary enters a tile`() {
        val founder = game.addCiv()
        val barbarianCiv = game.addBarbarianCiv()
        game.addCity(founder, game.getTile(0, 0))
        val religion = game.addReligion(founder)
        religion.addBeliefs(listOf(game.ruleset.beliefs["Church Property"]!!))
        val barbarian = game.addUnit("Brute", barbarianCiv, game.getTile(2, 0))
        game.addUnit("Missionary", founder, game.getTile(1, 0))
        assertEquals(barbarianCiv, barbarian.civ)
        religion.addBeliefs(listOf(game.ruleset.beliefs["Heathen Conversion"]!!))
        game.addUnit("Missionary", founder, game.getTile(2, 1))
        assertEquals(founder, barbarian.civ)
        assertEquals(0f, barbarian.currentMovement)
    }

}
