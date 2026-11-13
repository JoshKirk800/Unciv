package com.unciv.logic.map

import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert
import org.junit.Test
import org.junit.runner.RunWith

/** How several "Gold cost of upgrading" uniques combine in [com.unciv.logic.map.mapunit.UnitUpgradeManager.getCostOfUpgrade]. */
@RunWith(BaseTestRunner::class)
class UpgradeCostModifierTests {
    private val game = TestGame()

    /** Upgrade cost of a unit (raw cost 10 + 2 x 100 = 210) of a civ with the given upgrade cost percentages. */
    private fun upgradeCost(vararg percentages: Int): Int {
        game.makeHexagonalMap(2)
        val to = game.createBaseUnit()
        to.cost = 100
        val from = game.createBaseUnit()
        from.cost = 0
        from.upgradesTo = to.name
        val civ = game.addCiv(*percentages.map { "[$it]% Gold cost of upgrading" }.toTypedArray())
        game.addCity(civ, game.getTile(0, 0))
        val unit = game.addUnit(from.name, civ, game.getTile(0, 0))
        return unit.upgrade.getCostOfUpgrade(to)
    }

    @Test
    fun summedPercentagesAreAddedNotMultiplied() {
        game.ruleset.modOptions.constants.unitUpgradeCostModsAreSummed = true
        // 210 x 0.34 = 71.4, rounded down to a multiple of 5; multiplied it would be 94.3 -> 90
        Assert.assertEquals(70, upgradeCost(-33, -33))
    }

    @Test
    fun summedPercentagesAreClampedAtTheDiscountMaximum() {
        game.ruleset.modOptions.constants.unitUpgradeCostModsAreSummed = true
        // -120% in total is limited to -75%: 210 x 0.25 = 52.5 -> 50; multiplied it would be 33.6 -> 30
        Assert.assertEquals(50, upgradeCost(-60, -60))
    }

    @Test
    fun percentagesAreMultipliedByDefault() {
        game.ruleset.modOptions.constants.unitUpgradeCostModsAreSummed = false
        Assert.assertEquals(90, upgradeCost(-33, -33))
    }
}
