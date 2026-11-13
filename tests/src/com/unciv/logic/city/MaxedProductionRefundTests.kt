package com.unciv.logic.city

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Production stored in a unit or building that cannot be built any more because its limit is reached (CvCity::doCheckProduction),
 *  and the production above the overflow cap (CvCity::popOrder), is turned into gold at maxedUnitGoldPercent / maxedBuildingGoldPercent. */
@RunWith(BaseTestRunner::class)
class MaxedProductionRefundTests {
    private val game = TestGame()
    private lateinit var civ: Civilization
    private lateinit var cityA: City
    private lateinit var cityB: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(8)
        civ = game.addCiv()
        cityA = game.addCity(civ, game.getTile(HexCoord.Zero))
        cityB = game.addCity(civ, game.getTile(4, 0))
        civ.addGold(-civ.gold)
    }

    /** City A queues [limited] (which uses up its limit of 1), city B has [stored] production in it while building something else. */
    private fun storeInLimited(limited: INonPerpetualConstruction, filler: INonPerpetualConstruction, stored: Int) {
        cityA.cityConstructions.addToQueue(limited)
        cityB.cityConstructions.addToQueue(filler)
        cityB.cityConstructions.inProgressConstructions[limited.name] = stored
        cityB.cityConstructions.validateInProgressConstructions()
    }

    private fun limitedBuilding() = game.createBuilding("Limited to [1] per Civilization").apply { cost = 100 }
    private fun limitedUnit() = game.createBaseUnit(game.createUnitType().name, "Limited to [1] per Civilization").apply { cost = 100 }

    @Test
    fun maxedBuildingProductionBecomesGold() {
        game.ruleset.modOptions.constants.maxedBuildingGoldPercent = 100
        storeInLimited(limitedBuilding(), game.createBuilding().apply { cost = 100 }, 40)
        assertEquals(40, civ.gold)
    }

    @Test
    fun maxedUnitProductionBecomesGoldAtThePercentage() {
        game.ruleset.modOptions.constants.maxedUnitGoldPercent = 50
        storeInLimited(limitedUnit(), game.createBuilding().apply { cost = 100 }, 40)
        assertEquals(20, civ.gold)
    }

    @Test
    fun maxedUnitProductionIsLostWithTheDefault() {
        game.ruleset.modOptions.constants.maxedUnitGoldPercent = 0
        val limited = limitedUnit()
        storeInLimited(limited, game.createBuilding().apply { cost = 100 }, 40)
        assertEquals(0, civ.gold)
        assertFalse(cityB.cityConstructions.inProgressConstructions.containsKey(limited.name))
    }

    @Test
    fun productionAboveTheOverflowCapBecomesGold() {
        game.ruleset.modOptions.constants.maxedBuildingGoldPercent = 100
        val building = game.createBuilding().apply { cost = 100 }
        val constructions = cityA.cityConstructions
        constructions.addToQueue(building)
        // The cap is max(cost, production per turn) = 100 (a one-pop city makes far less than 100): 150 above the cost, 50 are lost
        constructions.inProgressConstructions[building.name] = 250
        constructions.constructIfEnough()
        assertEquals(50, civ.gold)
        assertEquals(100, constructions.productionOverflow)
    }
}
