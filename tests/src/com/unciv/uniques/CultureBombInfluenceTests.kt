package com.unciv.uniques

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvUnit::PerformCultureBomb: a city-state that loses tiles to a culture bomb loses influence once (CULTURE_BOMB_MINOR_FRIENDSHIP_CHANGE), not for each tile */
@RunWith(BaseTestRunner::class)
class CultureBombInfluenceTests {
    private val game = TestGame()
    private lateinit var civ: Civilization
    private lateinit var cityState: Civilization
    private var tilesTaken = 0

    @Before
    fun setUp() {
        game.makeHexagonalMap(6)
        civ = game.addCiv(isPlayer = true)
        cityState = game.addCiv(cityStateType = "Cultured")
        val bombTile = game.getTile(HexCoord.Zero)
        game.addCity(civ, bombTile)
        // a city-state city 3 tiles away owns tiles at 2 to 4 tiles from the bomb: several of them are in its radius of 3
        game.addCity(cityState, bombTile.getTilesAtDistance(3).first())
        tilesTaken = bombTile.getTilesInDistance(3).count { it.getOwner() == cityState && !it.isCityCenter() }
        assertTrue("setup: more than one tile of the city-state in range, was $tilesTaken", tilesTaken > 1)
    }

    private fun cultureBomb() {
        val unique = Unique("Gain control over [All] tiles in a [3]-tile radius")
        assertTrue(UniqueTriggerActivation.triggerUnique(unique, civ, tile = game.getTile(HexCoord.Zero)))
    }

    @Test
    fun aCityStateLosesInfluenceOncePerCultureBomb() {
        game.ruleset.modOptions.constants.cityStateInfluenceOncePerTilesTaken = true
        game.ruleset.modOptions.constants.cityStateInfluenceChangeForTilesTaken = -50f

        cultureBomb()

        assertEquals(-50f, cityState.getDiplomacyManager(civ)!!.getInfluence(), 0.001f)
    }

    @Test
    fun otherRulesetsLoseInfluenceForEachTileTaken() {
        game.ruleset.modOptions.constants.cityStateInfluenceOncePerTilesTaken = false
        game.ruleset.modOptions.constants.cityStateInfluenceChangeForTilesTaken = -15f

        cultureBomb()

        assertEquals(-15f * tilesTaken, cityState.getDiplomacyManager(civ)!!.getInfluence(), 0.001f)
    }
}
