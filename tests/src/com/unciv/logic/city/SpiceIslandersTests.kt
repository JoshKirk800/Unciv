package com.unciv.logic.city

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.json.json
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class SpiceIslandersTests {
    private lateinit var game: TestGame
    private lateinit var indonesia: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(11)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        listOf(
            Triple(0, 0, 0), Triple(0, 5, 0), Triple(5, 0, 1),
            Triple(-5, 0, 1), Triple(0, -5, 2), Triple(5, -5, 3),
            Triple(-5, 5, 4)
        ).forEach { (x, y, area) -> game.getTile(x, y).setContinent(area) }
        indonesia = game.addCiv(game.ruleset.nations["Indonesia"]!!)
        game.gameInfo.currentPlayerCiv = indonesia
        game.gameInfo.currentPlayer = indonesia.civID
    }

    private fun city(x: Int, y: Int): City = game.addCity(indonesia, game.getTile(x, y))

    @Test
    fun `different foreign landmasses receive two copies of each exclusive luxury once`() {
        val capital = city(0, 0)
        assertEquals(null, capital.getCenterTile().resource)
        assertEquals(0, indonesia.originalLuxuryArea)
        assertEquals(null, city(0, 5).getCenterTile().resource)

        val first = city(5, 0)
        assertEquals("Nutmeg", first.getCenterTile().resource)
        assertEquals(2, indonesia.getResourceAmount("Nutmeg"))
        assertEquals(null, city(-5, 0).getCenterTile().resource)
        assertEquals("Cloves", city(0, -5).getCenterTile().resource)
        assertEquals("Pepper", city(5, -5).getCenterTile().resource)
        assertEquals(null, city(-5, 5).getCenterTile().resource)
        assertEquals(3, indonesia.uniqueLuxuryAreas.size)
        assertEquals(2, indonesia.getResourceAmount("Cloves"))
        assertEquals(2, indonesia.getResourceAmount("Pepper"))
    }

    @Test
    fun `claimed area history survives clone and save and cities stay unrazable after capture`() {
        city(0, 0)
        val first = city(5, 0)
        assertEquals(setOf(1), indonesia.clone().uniqueLuxuryAreas)
        val restored = json().fromJson(Civilization::class.java, json().toJson(indonesia))
        assertEquals(setOf(1), restored.uniqueLuxuryAreas)
        assertEquals(0, restored.originalLuxuryArea)

        val conqueror = game.addCiv()
        game.addCity(conqueror, game.getTile(8, 2))
        first.moveToCiv(conqueror)
        assertEquals(2, conqueror.getResourceAmount("Nutmeg"))
        assertFalse(first.canBeDestroyed(justCaptured = true))
    }
}
