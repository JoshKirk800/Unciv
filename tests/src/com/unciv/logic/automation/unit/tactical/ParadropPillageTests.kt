package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsFromUniques
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Paratroopers in the tactical AI (CvTacticalAI::PlotPillageMoves with FindParatroopersWithinStrikingDistance and ExecuteParadropPillage)
 *  and the paradrop rules they use (CvUnit::canParadropAt, CvUnit::paradrop) with Brave New World data. Our city is at x = 0, y = 0, the
 *  enemy's at y = 8; the paratroopers drop up to 5 tiles from friendly land.
 */
@RunWith(BaseTestRunner::class)
class ParadropPillageTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var home: Tile
    private lateinit var farm: Tile

    @Before
    fun setUp() {
        game.makeHexagonalMap(16)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        home = game.addCity(civ, tile(0)).getCenterTile()
        val enemyCity = game.addCity(enemy, tile(8))
        farm = tile(5)
        farm.setOwningCity(enemyCity)
        farm.setImprovement("Farm")
    }

    private fun tile(y: Int) = game.tileMap[0, y]

    private fun context(): TacticalContext {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), civ.units.getCivUnits().toList(), TacticalTargets.find(civ, map, danger))
    }

    private fun paratrooper(at: Tile = home): MapUnit = game.addUnit("Paratrooper", civ, at)

    @Test
    fun `a paratrooper that cannot walk to an enemy improvement drops on it and pillages`() {
        val unit = paratrooper()
        val ctx = context()
        assertEquals(TacticalTargetType.Improvement, ctx.targets.all.first { it.tile == farm }.type)
        assertTrue(unit.movement.getDistanceToTiles()[farm] == null)
        TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Improvement, true)
        assertEquals(farm, unit.getTile())
        assertTrue(farm.improvementIsPillaged)
        assertTrue(unit in ctx.cache.processedUnits)
        assertEquals(0f, unit.currentMovement)
    }

    @Test
    fun `the strongest paratrooper drops, one beyond the drop range or that has moved does not`() {
        val weak = paratrooper().also { it.health = 50 }
        val strong = paratrooper(tile(1))
        val far = paratrooper(tile(-6))
        val ctx = context()
        TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Improvement, true)
        assertEquals(farm, strong.getTile())
        assertEquals(home, weak.getTile())
        assertEquals(tile(-6), far.getTile())

        val moved = paratrooper(tile(2))
        moved.currentMovement = 1f
        assertFalse(TacticalMoves.findParatroopers(context(), farm).any { it.unit == moved })
    }

    @Test
    fun `the tactical turn pillages by paradrop`() {
        val unit = paratrooper()
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        TacticalAi.automate(civ, civ.units.getCivUnits().toList(), AutomationCache())
        assertTrue(farm.improvementIsPillaged)
        assertEquals(farm, unit.getTile())
    }

    @Test
    fun `a drop costs half a movement point and counts as an attack`() {
        val unit = paratrooper()
        civ.viewableTiles = game.tileMap.values.toSet()
        assertTrue(UnitActionsFromUniques.paradrop(unit, tile(4)))
        assertEquals(tile(4), unit.getTile())
        assertEquals(1.5f, unit.currentMovement, 0.001f)
        assertEquals(1, unit.attacksThisTurn)
        assertFalse("once a turn", UnitActionsFromUniques.canParadropAt(unit, tile(3)))
    }

    @Test
    fun `a paratrooper cannot land on a tile it cannot enter`() {
        val unit = paratrooper()
        civ.viewableTiles = game.tileMap.values.toSet()
        val occupied = tile(4)
        game.addUnit("Warrior", enemy, occupied)
        civ.viewableTiles = game.tileMap.values.toSet()
        assertFalse(UnitActionsFromUniques.canParadropAt(unit, occupied))
        assertTrue(UnitActionsFromUniques.canParadropAt(unit, tile(3)))
    }
}
