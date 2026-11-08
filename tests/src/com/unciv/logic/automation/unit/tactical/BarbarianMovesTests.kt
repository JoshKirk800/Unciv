package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.civilization.BarbarianAutomation
import com.unciv.logic.automation.civilization.BarbarianEncampment
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The barbarian tactical AI (CvTacticalAI as it runs for the barbarian player) with Brave New World data: the target ranges by difficulty
 *  (HandicapInfos.BarbarianLandTargetRange / BarbarianSeaTargetRange), the move priorities (AI_TACTICAL_BARBARIAN_PRIORITY_*), hunting, sea
 *  raids, camp defense, escorting captured civilians, and a whole turn.
 */
@RunWith(BaseTestRunner::class)
class BarbarianMovesTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var barbarians: Civilization
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(20)
        barbarians = game.addBarbarianCiv()
        barbarians.ai.setTransients(barbarians)
        game.gameInfo.barbarians.gameInfo = game.gameInfo
        civ = game.addCiv()
        game.setDifficulty("Prince")
        for (tile in game.tileMap.values) tile.setExplored(barbarians, true)
        barbarians.viewableTiles = game.tileMap.values.toSet()
        civ.viewableTiles = game.tileMap.values.toSet()
    }

    private fun tile(x: Int, y: Int = 0) = game.getTile(HexCoord(x, y))

    private fun context(vararg units: MapUnit): TacticalContext {
        // putting units on the map recomputes what a civilization sees: the barbarians see everything for these tests
        barbarians.viewableTiles = game.tileMap.values.toSet()
        civ.viewableTiles = game.tileMap.values.toSet()
        barbarians.ai.invalidateTurnCaches()
        val danger = barbarians.ai.plotDanger
        return TacticalContext(barbarians, null, danger, AutomationCache(), units.toList(), TacticalTargets.find(barbarians, null, danger))
    }

    private fun makeWater(xRange: IntRange, yRange: IntRange) {
        for (x in xRange) for (y in yRange) game.tileMap.getOrNull(x, y)?.let { game.setTileTerrain(it.position, "Coast") }
    }

    // region Ranges and priorities

    @Test
    fun `the target ranges are the handicap's land and sea target range of the game's difficulty`() {
        val expected = mapOf("Settler" to (2 to 4), "Chieftain" to (3 to 6), "Warlord" to (4 to 8), "Prince" to (5 to 10), "King" to (5 to 12),
            "Emperor" to (6 to 15), "Immortal" to (7 to 18), "Deity" to (8 to 20))
        for ((difficulty, ranges) in expected) {
            game.setDifficulty(difficulty)
            assertEquals(difficulty, ranges.first, BarbarianMoves.landRange(barbarians))
            assertEquals(difficulty, ranges.second, BarbarianMoves.seaRange(barbarians))
        }
    }

    @Test
    fun `the move list is ordered by base priority plus a die roll, escorting captives first and a negative priority never`() {
        val moves = BarbarianAutomation.establishPriorities(barbarians)
        assertEquals("escorting a captured civilian is 30..35, nothing else reaches it", BarbarianAutomation.Move.ESCORT_CIVILIAN, moves.first())
        assertFalse("the passive move has priority -1", BarbarianAutomation.Move.PASSIVE_MOVE in moves)
        assertEquals(BarbarianAutomation.Move.entries.size - 1, moves.size)
        // 16..21 against 9..14 and 0..5 against 3..8: the rolls never reverse these
        assertTrue(moves.indexOf(BarbarianAutomation.Move.DESTROY_HIGH_PRIORITY_UNIT) < moves.indexOf(BarbarianAutomation.Move.CAPTURE_CITY))
        assertTrue(moves.indexOf(BarbarianAutomation.Move.CAPTURE_CITY) < moves.indexOf(BarbarianAutomation.Move.AGGRESSIVE_MOVE))
        assertTrue(moves.indexOf(BarbarianAutomation.Move.CAMP_DEFENSE) < moves.indexOf(BarbarianAutomation.Move.DESPERATE_ATTACK))
    }
    // endregion

    // region Hunting on land

    @Test
    fun `a barbarian hunts the nearest unit within the land target range and ignores one beyond it`() {
        val hunter = game.addUnit("Warrior", barbarians, tile(0))
        val near = game.addUnit("Archer", civ, tile(4))
        game.addUnit("Archer", civ, tile(-7))
        assertEquals(tile(4), BarbarianMoves.findBestLandMove(context(hunter), hunter))
        near.removeFromTile()
        val explore = BarbarianMoves.findBestLandMove(context(hunter), hunter)
        assertNotNull("no target in range: it explores", explore)
        assertTrue(explore != tile(-7) && explore != tile(4))
    }

    @Test
    fun `how far a barbarian looks depends on the difficulty`() {
        val hunter = game.addUnit("Warrior", barbarians, tile(0))
        game.addUnit("Archer", civ, tile(4))
        game.setDifficulty("Warlord") // 4: a target 4 tiles away is not within range
        assertTrue(BarbarianMoves.findBestLandMove(context(hunter), hunter) != tile(4))
        game.setDifficulty("Prince") // 5
        assertEquals(tile(4), BarbarianMoves.findBestLandMove(context(hunter), hunter))
    }

    @Test
    fun `a moving barbarian ends its turn next to its target`() {
        val hunter = game.addUnit("Warrior", barbarians, tile(0))
        game.addUnit("Archer", civ, tile(4))
        val ctx = context(hunter)
        BarbarianMoves.executeMoves(ctx, aggressive = true)
        assertFalse(hunter.hasMovement())
        assertTrue(hunter in ctx.cache.processedUnits)
        assertTrue("moved towards it", hunter.getTile().aerialDistanceTo(tile(4)) < 4)
    }

    @Test
    fun `the passive move heads for the nearest camp`() {
        tile(9).setImprovementBasic(Constants.barbarianEncampment)
        tile(-3).setImprovementBasic(Constants.barbarianEncampment)
        val unit = game.addUnit("Warrior", barbarians, tile(0))
        assertEquals(tile(-3), BarbarianMoves.findPassiveLandMove(context(unit), unit))
    }
    // endregion

    // region Camps

    private fun camp(at: Tile): Tile {
        at.setImprovementBasic(Constants.barbarianEncampment)
        game.gameInfo.barbarians.encampments.add(BarbarianEncampment(at.position))
        return at
    }

    @Test
    fun `an enemy unit that can reach a barbarian camp next turn is a high priority target, one that cannot is a low one`() {
        camp(tile(0))
        val threat = game.addUnit("Warrior", civ, tile(2))
        val distant = game.addUnit("Warrior", civ, tile(9))
        val ctx = context()
        assertEquals(TacticalTargetType.HighPriorityUnit, ctx.targets.all.first { it.unit == threat }.type)
        assertEquals(TacticalTargetType.LowPriorityUnit, ctx.targets.all.first { it.unit == distant }.type)
    }

    @Test
    fun `an archer that can shoot a camp from where it stands is marked a high priority target, then medium as every ranged unit is`() {
        camp(tile(0))
        val archer = game.addUnit("Archer", civ, tile(2))
        // the original makes every ranged unit a medium priority target, a high one too (IdentifyPriorityTargetsByType)
        assertEquals(TacticalTargetType.MediumPriorityUnit, context().targets.all.first { it.unit == archer }.type)
    }

    @Test
    fun `the strongest unit that can be on an empty camp this turn goes there, one too far away stays`() {
        val camp = tile(3)
        camp.setImprovementBasic(Constants.barbarianEncampment)
        val archer = game.addUnit("Archer", barbarians, tile(2))
        val swordsman = game.addUnit("Swordsman", barbarians, tile(1))
        val far = game.addUnit("Swordsman", barbarians, tile(-4))
        val ctx = context(archer, swordsman, far)
        BarbarianMoves.plotCampDefenseMoves(ctx)
        assertSame(swordsman, camp.militaryUnit)
        assertEquals(tile(2), archer.getTile())
        assertEquals(tile(-4), far.getTile())
    }
    // endregion

    // region Sea raids

    @Test
    fun `a barbarian ship picks the nearest enemy ship within the sea target range`() {
        makeWater(-18..18, -3..3)
        val raider = game.addUnit("Trireme", barbarians, tile(-9))
        val victim = game.addUnit("Trireme", civ, tile(-2))
        assertEquals(victim.getTile(), BarbarianMoves.findBestSeaMove(context(raider), raider))
    }

    @Test
    fun `the sea target range follows the difficulty`() {
        makeWater(-18..18, -3..3)
        val raider = game.addUnit("Trireme", barbarians, tile(-12))
        game.addUnit("Trireme", civ, tile(-3)) // 9 tiles away
        game.setDifficulty("Warlord") // 8
        assertTrue(BarbarianMoves.findBestSeaMove(context(raider), raider) != tile(-3))
        game.setDifficulty("Prince") // 10
        assertEquals(tile(-3), BarbarianMoves.findBestSeaMove(context(raider), raider))
    }

    @Test
    fun `with no ship to pick on a barbarian ship sails to the water beside a camp other than the nearest`() {
        makeWater(-18..18, -3..3)
        tile(-9, 4).setImprovementBasic(Constants.barbarianEncampment)
        tile(9, 4).setImprovementBasic(Constants.barbarianEncampment)
        val raider = game.addUnit("Trireme", barbarians, tile(-8))
        val target = BarbarianMoves.findBestSeaMove(context(raider), raider)
        assertNotNull(target)
        assertTrue("next to the second camp", target!!.aerialDistanceTo(tile(9, 4)) == 1 && target.isWater)
    }

    @Test
    fun `a barbarian ship keeps its destination over the turns, even when the target is gone, until it gets there`() {
        makeWater(-18..18, -3..3)
        val victim = game.addUnit("Trireme", civ, tile(2))
        val raider = game.addUnit("Trireme", barbarians, tile(-6))
        BarbarianMoves.executeMoves(context(raider), aggressive = true)
        assertEquals(victim.getTile().position, barbarians.ai.tactical.unitDestinations[raider.id])
        val afterFirst = raider.getTile()
        assertTrue(afterFirst != tile(-6))
        victim.removeFromTile() // the victim is gone: the ship goes on to where it was seen
        raider.currentMovement = raider.getMaxMovement().toFloat()
        BarbarianMoves.executeMoves(context(raider), aggressive = true)
        assertEquals(tile(2).position, barbarians.ai.tactical.unitDestinations[raider.id])
        assertTrue(raider.getTile().aerialDistanceTo(tile(2)) < afterFirst.aerialDistanceTo(tile(2)))
    }
    // endregion

    // region Captured civilians

    private fun captive(at: Tile): MapUnit {
        val worker = game.addUnit("Worker", barbarians, at)
        worker.originalOwner = civ.civID
        return worker
    }

    @Test
    fun `a captured civilian goes to the nearest camp with its escort, both ending the turn on the same tile`() {
        tile(8).setImprovementBasic(Constants.barbarianEncampment)
        val worker = captive(tile(0))
        val escort = game.addUnit("Warrior", barbarians, tile(0))
        BarbarianMoves.executeCivilianEscortMoves(context(worker, escort))
        assertSame(worker.getTile(), escort.getTile())
        assertTrue(worker.getTile().aerialDistanceTo(tile(8)) < 8)
    }

    @Test
    fun `an escort that is stronger than the camp's defender takes over the camp and the old defender steps aside`() {
        val camp = tile(2)
        camp.setImprovementBasic(Constants.barbarianEncampment)
        val defender = game.addUnit("Warrior", barbarians, camp)
        val worker = captive(tile(0))
        val escort = game.addUnit("Swordsman", barbarians, tile(0))
        BarbarianMoves.executeCivilianEscortMoves(context(worker, escort, defender))
        assertSame(escort, camp.militaryUnit)
        assertSame(worker, camp.civilianUnit)
        assertTrue(defender.getTile() != camp)
    }

    @Test
    fun `a captured civilian without an escort goes alone, and one already on a camp stays`() {
        tile(8).setImprovementBasic(Constants.barbarianEncampment)
        val alone = captive(tile(0))
        BarbarianMoves.executeCivilianEscortMoves(context(alone))
        assertTrue(alone.getTile().aerialDistanceTo(tile(8)) < 8)
        val settled = captive(tile(8))
        BarbarianMoves.executeCivilianEscortMoves(context(settled))
        assertEquals(tile(8), settled.getTile())
    }

    @Test
    fun `a camp that already holds a civilian of the same kind is not the target of another`() {
        tile(3).setImprovementBasic(Constants.barbarianEncampment)
        tile(9).setImprovementBasic(Constants.barbarianEncampment)
        captive(tile(3))
        val second = captive(tile(0))
        BarbarianMoves.executeCivilianEscortMoves(context(second))
        assertTrue("it heads for the far camp", second.getTile().aerialDistanceTo(tile(9)) < 9 && second.getTile() != tile(3))
    }
    // endregion

    // region A whole turn

    @Test
    fun `a barbarian next to a worker of ours captures it`() {
        val raider = game.addUnit("Warrior", barbarians, tile(0))
        val worker = game.addUnit("Worker", civ, tile(1))
        BarbarianAutomation(barbarians).automate()
        assertEquals(barbarians, worker.civ)
        assertEquals(tile(1), raider.getTile())
    }

    @Test
    fun `a barbarian next to a unit of ours that it beats attacks it`() {
        val raider = game.addUnit("Swordsman", barbarians, tile(0))
        val victim = game.addUnit("Warrior", civ, tile(1))
        BarbarianAutomation(barbarians).automate()
        assertTrue("the victim lost hit points or died", victim.isDestroyed || victim.health < 100)
        assertTrue(raider.attacksThisTurn > 0)
    }
    // endregion
}
