package com.unciv.logic.battle

import com.unciv.logic.city.City
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvCity::canRangeStrikeAt: "I don't want cities attacking each other directly" */
@RunWith(BaseTestRunner::class)
class CityBombardTargetTests {
    private val game = TestGame()
    private lateinit var city: City
    private lateinit var enemyCityTile: Tile
    private lateinit var enemyUnitTile: Tile

    @Before
    fun setUp() {
        game.makeHexagonalMap(4)
        val civ = game.addCiv()
        val enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        city = game.addCity(civ, game.getTile(0, 0))
        val center = city.getCenterTile()
        enemyCityTile = center.getTilesAtDistance(2).first()
        game.addCity(enemy, enemyCityTile)
        enemyUnitTile = center.neighbors.first { it !in enemyCityTile.neighbors }
        game.addUnit("Warrior", enemy, enemyUnitTile)
        civ.cache.updateViewableTiles()
    }

    @Test
    fun aCityCannotBombardAnEnemyCity() {
        val targets = TargetHelper.getBombardableTiles(city).toList()

        assertTrue("setup: the unit outside the city can be bombarded", enemyUnitTile in targets)
        assertFalse(enemyCityTile in targets)
    }

    @Test
    fun aCityCanBombardAnEnemyCityWhereTheRulesetAllowsIt() {
        game.ruleset.modOptions.constants.citiesCanBombardCities = true

        assertTrue(enemyCityTile in TargetHelper.getBombardableTiles(city).toList())
    }
}
