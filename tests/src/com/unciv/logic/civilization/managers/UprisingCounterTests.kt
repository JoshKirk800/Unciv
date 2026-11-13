package com.unciv.logic.civilization.managers

import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvPlayer::DoUpdateUprisings, DoResetUprisingCounter: the counter winds down only while the empire is very unhappy (below -19 in this port),
 *  keeps its value otherwise, is seeded with half the length the first time and with the full length after an uprising. Brave New World ruleset. */
@RunWith(BaseTestRunner::class)
class UprisingCounterTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var barbarians: Civilization
    private lateinit var manager: TurnManager

    /** UPRISING_COUNTER_MIN 4 + 0..2, times the game speed if that makes it longer (the BNW speeds' modifier) */
    private val fullLengths get() = (4..6).map { (it * game.gameInfo.speed.modifier.coerceAtLeast(1f)).toInt() }.toSet()
    private val firstLengths get() = fullLengths.map { (it / 2).coerceAtLeast(1) }.toSet()

    @Before
    fun setUp() {
        game.makeHexagonalMap(5)
        barbarians = game.addBarbarianCiv()
        civ = game.addCiv()
        game.addCity(civ, game.getTile(HexCoord.Zero), replacePalace = true, initialPopulation = 3)
        manager = TurnManager(civ)
    }

    private fun veryUnhappy() { civ.stats.happiness = -20 }
    private fun content() { civ.stats.happiness = 0 }

    @Test
    fun `the first time the empire is very unhappy the counter is seeded with half the length`() {
        veryUnhappy()
        assertEquals(0, civ.uprisingCounter)

        manager.updateRevolts()

        assertTrue("${civ.uprisingCounter} in $firstLengths", civ.uprisingCounter in firstLengths)
        assertFalse(civ.hasFlag(CivFlags.RevoltSpawning.name))
    }

    @Test
    fun `the counter pauses while the empire is not very unhappy instead of starting over`() {
        civ.uprisingCounter = 3

        content()
        manager.updateRevolts()
        manager.updateRevolts()
        assertEquals(3, civ.uprisingCounter)

        veryUnhappy()
        manager.updateRevolts()
        assertEquals(2, civ.uprisingCounter)

        content()
        manager.updateRevolts()
        assertEquals(2, civ.uprisingCounter)
    }

    @Test
    fun `an uprising spawns rebels when the counter runs out and the counter starts over at full length`() {
        veryUnhappy()
        civ.uprisingCounter = 2
        manager.updateRevolts()
        assertEquals(1, civ.uprisingCounter)
        assertEquals(0, barbarians.units.getCivUnits().count())

        manager.updateRevolts()

        assertTrue("rebels were spawned", barbarians.units.getCivUnits().count() >= 1)
        assertTrue("${civ.uprisingCounter} in $fullLengths", civ.uprisingCounter in fullLengths)
        // and it is not seeded again at the half length: the next turn winds it down by one
        val after = civ.uprisingCounter
        manager.updateRevolts()
        assertEquals(after - 1, civ.uprisingCounter)
    }
}
