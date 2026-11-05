package com.unciv.logic.battle

import com.unciv.Constants
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Against a ranged attack a ranged unit defends with its ranged strength - unless it is a boat, which uses its combat strength
 *  (CvUnit::GetRangeCombatDamage, GetAirCombatDamage). */
@RunWith(BaseTestRunner::class)
class NavalDefenseTests {
    private val game = TestGame()

    @Before
    fun setUp() {
        game.makeHexagonalMap(3)
        for (tile in game.tileMap.tileList) {
            tile.baseTerrain = Constants.ocean
            tile.setTransients()
        }
    }

    @Test
    fun `a ship defends against a ranged attack with its combat strength`() {
        val us = game.addCiv()
        val them = game.addCiv()
        game.getTile(1, 0).apply { baseTerrain = Constants.grassland; setTransients() } // the archer stands on the coast
        val submarine = MapUnitCombatant(game.addUnit("Submarine", us, game.getTile(0, 0)))
        val archer = MapUnitCombatant(game.addDefaultRangedUnitWithUniques(them, game.getTile(1, 0)))

        assertEquals(submarine.unit.baseUnit.strength, submarine.getDefendingStrength(archer))
        assertEquals("not its ranged strength ${submarine.unit.baseUnit.rangedStrength}", 35, submarine.getDefendingStrength(archer))
    }

    @Test
    fun `a land unit defends against a ranged attack with its ranged strength`() {
        for (tile in game.tileMap.tileList) {
            tile.baseTerrain = Constants.grassland
            tile.setTransients()
        }
        val us = game.addCiv()
        val them = game.addCiv()
        val defender = MapUnitCombatant(game.addDefaultRangedUnitWithUniques(us, game.getTile(0, 0)))
        val attacker = MapUnitCombatant(game.addDefaultRangedUnitWithUniques(them, game.getTile(1, 0)))

        assertEquals(7, defender.getDefendingStrength(attacker))
        assertEquals("against a melee attack it is the combat strength", 5, defender.getDefendingStrength(null))
    }
}
