package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.json.json
import com.unciv.logic.city.City
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.actions.UnitActionsPillage
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class FeitoriaParityTests {
    private lateinit var game: TestGame
    private lateinit var portugal: Civilization
    private lateinit var cityState: Civilization
    private lateinit var city: City

    private fun tile(x: Int, y: Int) = game.tileMap[x, y]

    private fun own(x: Int, y: Int): Tile = tile(x, y).also {
        if (it.getOwner() != cityState) {
            game.addTileToCity(city, it)
            it.setOwningCity(city)
        }
    }

    private fun luxury(x: Int, y: Int, resource: String): Tile = own(x, y).also {
        it.setTileResource(resource)
        it.setImprovement("Mine", cityState)
    }

    private fun feitoria(x: Int, y: Int) = own(x, y).also {
        it.setImprovement("Feitoria", portugal)
    }

    @Before
    fun setup() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        portugal = game.addCiv(game.ruleset.nations["Portugal"]!!, isPlayer = true)
        cityState = game.addCiv(cityStateType = "Cultured")
        game.setTileTerrain(tile(2, 0).position, "Coast")
        game.setTileTerrain(tile(2, -1).position, "Coast")
        game.addCity(portugal, tile(-6, 0), replacePalace = true)
        city = game.addCity(cityState, tile(0, 0), replacePalace = true)
        portugal.diplomacyFunctions.makeCivilizationsMeet(cityState)
        portugal.getDiplomacyManager(cityState)!!.makePeace()
        cityState.tech.addTechnology("Mining")
        portugal.tech.addTechnology("Navigation")
    }

    @Test
    fun `each Feitoria copies each connected luxury type once without making it tradable`() {
        val salt = luxury(0, 1, "Salt")
        luxury(-1, 1, "Salt")
        val gems = luxury(0, -1, "Gems")
        cityState.cache.updateCivResources()
        assertEquals(2, cityState.getResourceAmount("Salt"))
        assertEquals(1, cityState.getResourceAmount("Gems"))
        assertEquals(0, portugal.getResourceAmount("Salt"))

        val first = feitoria(1, 0)
        assertEquals(1, portugal.getResourceAmount("Salt"))
        assertEquals(1, portugal.getResourceAmount("Gems"))
        assertEquals(0, portugal.getPerTurnResourcesWithOriginsForTrade()
            .get(game.ruleset.tileResources["Salt"]!!, Constants.tradable)?.amount ?: 0)
        val second = feitoria(1, -1)
        assertEquals(2, portugal.getResourceAmount("Salt"))
        assertEquals(2, portugal.getResourceAmount("Gems"))

        salt.setPillaged()
        assertEquals("one remaining Salt deposit still gives each Feitoria one copy", 2, portugal.getResourceAmount("Salt"))
        gems.setPillaged()
        assertEquals(0, portugal.getResourceAmount("Gems"))
        gems.setRepaired()
        assertEquals(2, portugal.getResourceAmount("Gems"))

        second.setPillaged()
        assertEquals(1, portugal.getResourceAmount("Salt"))
        second.setRepaired()
        assertEquals(2, portugal.getResourceAmount("Salt"))
        first.removeImprovement()
        assertEquals(1, portugal.getResourceAmount("Salt"))
        city.expansion.relinquishOwnership(second)
        assertEquals(0, portugal.getResourceAmount("Salt"))
        city.expansion.takeOwnership(second)
        assertEquals(1, portugal.getResourceAmount("Salt"))
    }

    @Test
    fun `foreign build requires Portuguese worker Navigation City State coast and empty land`() {
        val improvement = game.ruleset.tileImprovements["Feitoria"]!!
        val worker = game.addUnit("Worker", portugal, tile(-5, 0))
        val candidate = own(1, 0)
        val context = GameContext(portugal, unit = worker, tile = candidate)
        assertTrue(worker.canBuildImprovement(improvement, candidate))
        assertTrue(candidate.improvementFunctions.canBuildImprovement(improvement, context))

        val inland = own(-2, 0)
        assertFalse(inland.improvementFunctions.canBuildImprovement(improvement, GameContext(portugal, unit = worker, tile = inland)))
        candidate.setTileResource("Salt")
        assertFalse(candidate.improvementFunctions.canBuildImprovement(improvement, context))
        candidate.setTileResource(null)
        val ownedByPortugal = tile(-5, 1)
        assertFalse(ownedByPortugal.improvementFunctions.canBuildImprovement(improvement,
            GameContext(portugal, unit = worker, tile = ownedByPortugal)))
        val other = game.addCiv()
        game.addCity(other, tile(6, 0), replacePalace = true)
        val outsider = game.addUnit("Worker", other, tile(5, 0))
        assertFalse(candidate.improvementFunctions.canBuildImprovement(improvement,
            GameContext(other, unit = outsider, tile = candidate)))
    }

    @Test
    fun `war removes copies and protector cannot be pillaged without war against builder`() {
        luxury(0, 1, "Salt")
        cityState.cache.updateCivResources()
        val fort = feitoria(1, 0)
        val attacker = game.addCiv()
        game.addCity(attacker, tile(6, 0), replacePalace = true)
        attacker.diplomacyFunctions.makeCivilizationsMeet(cityState)
        attacker.diplomacyFunctions.makeCivilizationsMeet(portugal)
        attacker.getDiplomacyManager(cityState)!!.declareWar()
        val warrior = game.addUnit("Warrior", attacker, fort)
        assertFalse(UnitActionsPillage.canPillage(warrior, fort))
        attacker.getDiplomacyManager(portugal)!!.declareWar()
        assertTrue(UnitActionsPillage.canPillage(warrior, fort))

        assertEquals(1, portugal.getResourceAmount("Salt"))
        portugal.getDiplomacyManager(cityState)!!.declareWar()
        assertEquals(0, portugal.getResourceAmount("Salt"))
        portugal.getDiplomacyManager(cityState)!!.makePeace()
        assertEquals(1, portugal.getResourceAmount("Salt"))
        assertEquals(portugal.civID, fort.improvementBuilder)
        assertEquals(portugal.civID, fort.clone().improvementBuilder)
        val restored = json().fromJson(Tile::class.java, json().toJson(fort))
        assertEquals(portugal.civID, restored.improvementBuilder)
        val clonedGame = game.gameInfo.clone()
        assertEquals(portugal.civID, clonedGame.tileMap.tileList.first { it.position == fort.position }.improvementBuilder)
        val savedGame = json().fromJson(GameInfo::class.java, json().toJson(game.gameInfo))
        assertEquals(portugal.civID, savedGame.tileMap.tileList.first { it.position == fort.position }.improvementBuilder)
        assertFalse(fort.improvementIsPillaged)
    }
}
