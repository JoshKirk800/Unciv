package com.unciv.logic.battle

import com.unciv.Constants
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvUnit::CanWithdrawFromMelee: the chance of a unit that withdraws before melee combat, lowered by the attacker's speed and by blocked retreat tiles */
@RunWith(BaseTestRunner::class)
class WithdrawChanceTests {
    private val game = TestGame()
    private lateinit var attackerTile: Tile
    private lateinit var defenderTile: Tile

    @Before
    fun setUp() {
        game.makeHexagonalMap(4)
        game.ruleset.modOptions.constants.withdrawLikeCivV = true
        attackerTile = game.getTile(0, 0)
        defenderTile = attackerTile.neighbors.first()
    }

    /** The three neighbours of the defender's tile away from the attacker, and the one straight behind it */
    private fun tilesBehind() = defenderTile.neighbors.filter { it != attackerTile && it !in attackerTile.neighbors }.toList()

    /** [attackerName] attacks a unit that withdraws with [chance] percent; returns the defender, which has withdrawn if it left its tile alive */
    private fun attack(attackerName: String, chance: Int): MapUnit {
        val attackerCiv = game.addCiv()
        val defenderCiv = game.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar()
        val attacker = game.addUnit(attackerName, attackerCiv, attackerTile)
        val defender = game.addUnit("Warrior", defenderCiv, defenderTile)
        defender.promotions.addPromotion(game.createUnitPromotion("Withdraws before melee combat <with [$chance]% chance>").name, true)
        Battle.attack(MapUnitCombatant(attacker), MapUnitCombatant(defender))
        return defender
    }

    private fun MapUnit.hasWithdrawn() = !isDestroyed && currentTile != defenderTile

    @Test
    fun withdrawsStraightBackWhenTheChanceIsCertain() {
        val defender = attack("Warrior", 100)

        assertTrue(defender.hasWithdrawn())
        assertEquals(100, defender.health)
        val behind = tilesBehind().first { tile -> tile.neighbors.none { it in attackerTile.neighbors && it in defenderTile.neighbors } }
        assertEquals(behind, defender.currentTile)
    }

    @Test
    fun aFastAttackerTakesTheChanceAway() {
        // a Horseman has 4 moves: -20 for each above 2 leaves 40 - 40 = 0
        val defender = attack("Horseman", 40)

        assertFalse(defender.hasWithdrawn())
    }

    @Test
    fun noWithdrawalWhenAllThreeTilesBehindAreBlocked() {
        for (tile in tilesBehind()) game.setTileTerrain(tile.position, Constants.ocean)

        val defender = attack("Warrior", 100)

        assertFalse(defender.hasWithdrawn())
    }
}
