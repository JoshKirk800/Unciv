package com.unciv.logic.city

import com.unciv.logic.city.managers.CityConquestFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class NationAbilityParityTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(11)
        // Conquest notifications use the active player even in headless tests.
        val current = game.addCiv()
        game.gameInfo.currentPlayerCiv = current
        game.gameInfo.currentPlayer = current.civID
    }

    private fun assyria() = game.addCiv(game.ruleset.nations["Assyria"]!!)
    private fun city(civ: Civilization, x: Int, y: Int) = game.addCity(civ, game.getTile(x, y))

    @Test
    fun `Assyria learns the cheapest eligible technology on each first conquest and cannot trade it`() {
        val assyria = assyria()
        val owner = game.addCiv()
        val first = city(owner, 0, 0)
        val second = city(owner, 0, 6)
        owner.tech.addTechnology("Pottery")
        owner.tech.addTechnology("Writing")
        owner.tech.addTechnology("Mining")

        CityConquestFunctions(first).puppetCity(assyria)
        assertEquals(1, setOf("Pottery", "Mining").count { assyria.tech.isResearched(it) })
        assertFalse(assyria.tech.isResearched("Writing"))
        val obtained = setOf("Pottery", "Mining").single { assyria.tech.isResearched(it) }
        assertTrue(obtained in assyria.tech.noTradeTechnologies)

        val trade = Trade().apply {
            ourOffers.add(TradeOffer(obtained, TradeOfferType.Technology, speed = game.gameInfo.speed))
        }
        assertFalse(TradeEvaluation().isTradeValid(trade, assyria, owner))
        // A normal researched technology is still eligible to trade.
        assyria.tech.addTechnology("Archery")
        trade.ourOffers.clear()
        trade.ourOffers.add(TradeOffer("Archery", TradeOfferType.Technology, speed = game.gameInfo.speed))
        assertTrue(TradeEvaluation().isTradeValid(trade, assyria, owner))

        CityConquestFunctions(second).puppetCity(assyria)
        assertTrue(assyria.tech.isResearched("Pottery"))
        assertTrue(assyria.tech.isResearched("Mining"))
        assertFalse(assyria.tech.isResearched("Writing"))
    }

    @Test
    fun `Assyria cannot receive a tech twice from the same city even after trade and save clone`() {
        val assyria = assyria()
        val owner = game.addCiv()
        val other = game.addCiv()
        val captured = city(owner, 0, 0)
        city(owner, 0, 6)
        owner.tech.addTechnology("Pottery")
        CityConquestFunctions(captured).puppetCity(assyria)
        assertTrue(assyria.tech.isResearched("Pottery"))
        val savedCity = captured.clone()
        assertTrue(assyria.civID in savedCity.everOwnedBy)
        assertTrue("Pottery" in assyria.tech.clone().noTradeTechnologies)

        captured.moveToCiv(other)
        other.tech.addTechnology("Mining")
        CityConquestFunctions(captured).puppetCity(assyria)
        assertFalse(assyria.tech.isResearched("Mining"))
    }

    @Test
    fun `trading a city to Assyria prevents a later conquest reward`() {
        val assyria = assyria()
        val owner = game.addCiv()
        val captured = city(owner, 0, 0)
        city(owner, 0, 6)
        captured.moveToCiv(assyria)
        captured.moveToCiv(owner)
        owner.tech.addTechnology("Pottery")

        CityConquestFunctions(captured).puppetCity(assyria)
        assertFalse(assyria.tech.isResearched("Pottery"))
    }

    @Test
    fun `first conquest with no researchable technology still uses up the city's reward`() {
        val assyria = assyria()
        val owner = game.addCiv()
        val captured = city(owner, 0, 0)
        city(owner, 0, 6)
        owner.tech.addTechnology("Writing") // Pottery is required, but Assyria has not researched it.
        CityConquestFunctions(captured).puppetCity(assyria)
        assertFalse(assyria.tech.isResearched("Writing"))

        captured.moveToCiv(owner)
        assyria.tech.addTechnology("Pottery")
        CityConquestFunctions(captured).puppetCity(assyria)
        assertFalse(assyria.tech.isResearched("Writing"))
    }

    @Test
    fun `non-Assyrian civilizations do not receive technologies on conquest`() {
        val owner = game.addCiv()
        val conqueror = game.addCiv()
        val captured = city(owner, 0, 0)
        city(owner, 0, 6)
        owner.tech.addTechnology("Pottery")
        CityConquestFunctions(captured).puppetCity(conqueror)
        assertFalse(conqueror.tech.isResearched("Pottery"))
    }

    @Test
    fun `conquest technology is supplied by the unique rather than a nation name`() {
        val owner = game.addCiv()
        val conqueror = game.addCiv(UniqueType.TechnologyFromConqueringCity.text)
        val captured = city(owner, 0, 0)
        city(owner, 0, 6)
        owner.tech.addTechnology("Pottery")
        CityConquestFunctions(captured).puppetCity(conqueror)
        assertTrue(conqueror.tech.isResearched("Pottery"))
    }

    @Test
    fun `Shoshone cities claim eight additional plots on founding without spending culture`() {
        val shoshone = game.addCiv(game.ruleset.nations["Shoshone"]!!)
        val city = city(shoshone, 0, 0)
        assertEquals(15, city.tiles.size) // Center, six adjacent plots, eight additional plots.
        assertEquals(0, city.expansion.cultureStored)
        assertEquals(8, city.expansion.tilesClaimed())
        assertTrue(city.tiles.all { game.getTile(it).getCity() == city })
    }

    @Test
    fun `extra plots are configurable for a mod civilization`() {
        val modded = game.addCiv("[2] additional tiles claimed when founding a city")
        assertEquals(9, city(modded, 0, 0).tiles.size)
    }

    @Test
    fun `extra founding territory stops at map boundaries and never takes another city's plots`() {
        val other = game.addCiv()
        val neighbor = city(other, 0, 3)
        val newCity = city(game.addCiv(game.ruleset.nations["Shoshone"]!!), 0, 0)
        assertTrue(newCity.tiles.size <= 15)
        assertTrue(neighbor.tiles.none { it in newCity.tiles })

        val small = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        small.makeHexagonalMap(1)
        val shoshone = small.addCiv(small.ruleset.nations["Shoshone"]!!)
        val founded = small.addCity(shoshone, small.getTile(HexCoord.Zero))
        assertEquals(7, founded.tiles.size)
    }

    @Test
    fun `BNW nations without the founding unique and G and K nations keep ordinary territory`() {
        val ordinary = city(game.addCiv(), 0, 0)
        assertEquals(7, ordinary.tiles.size)

        val gk = TestGame()
        gk.makeHexagonalMap(5)
        val roman = gk.addCiv(gk.ruleset.nations["Rome"]!!)
        assertEquals(7, gk.addCity(roman, gk.getTile(HexCoord.Zero)).tiles.size)
    }
}
