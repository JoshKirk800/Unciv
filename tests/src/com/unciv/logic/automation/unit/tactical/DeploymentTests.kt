package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.FormationPosition
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The placing of units around a target (CvTacticalAI::ScoreCloseOnPlots, ScoreHedgehogPlots, AssignDeployingUnits, PerformChosenMoves,
 *  ExecuteCloseOnTarget, ExecuteHedgehogDefense, MoveGreatGeneral, ScoreGreatGeneralPlot) with the formulas of the original and BNW data.
 *  The map is desert, so no hex has a defense bonus unless a test gives it one.
 */
@RunWith(BaseTestRunner::class)
class DeploymentTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(16)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(x: Int, y: Int): Tile = game.getTile(HexCoord(x, y))

    private fun context(vararg units: MapUnit): TacticalContext {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        val map = civ.ai.tactical.map
        val danger = civ.ai.plotDanger
        return TacticalContext(civ, map, danger, AutomationCache(), units.toList().ifEmpty { civ.units.getCivUnits().toList() }, TacticalTargets.find(civ, map, danger))
    }

    private fun zoneOf(ctx: TacticalContext, city: City) = ctx.map.zoneByCity(city, false)!!

    private fun units(vararg names: String, around: Tile): List<MapUnit> =
        names.mapIndexed { index, name -> game.addUnit(name, civ, around.getTilesInDistance(3).filter { it.militaryUnit == null && !it.isCityCenter() }.drop(index * 7).first()) }

    private fun plotAt(plots: List<DeployPlot>, tile: Tile) = plots.first { it.tile == tile }

    // region ScoreCloseOnPlots

    @Test
    fun `close-on-target hexes score 600 less 100 per hex of distance, 30 less where the enemy can attack them and 30 less where it can pass`() {
        val city = game.addCity(enemy, tile(0, 0))
        // A warrior (2 moves) 3 hexes from the city: it can attack hexes within 2 of it, and move past those within 1
        game.addUnit("Warrior", enemy, tile(0, 3))
        val unit = game.addUnit("Warrior", civ, tile(-1, -1))
        val ctx = context()
        val plots = Deployment.scoreCloseOnPlots(ctx, city.getCenterTile(), false, listOf(OperationUnit(unit, FormationPosition.FRONT_LINE)))

        assertEquals("far from the enemy: 600 - 100 per hex", 500, plotAt(plots, tile(0, -1)).score)
        assertEquals(400, plotAt(plots, tile(0, -2)).score)
        assertEquals("2 hexes from the warrior: it can attack there", 600 - 100 - 30, plotAt(plots, tile(0, 1)).score)
        assertEquals("1 hex from the warrior: it can attack and pass", 600 - 200 - 30 - 30, plotAt(plots, tile(0, 2)).score)
    }

    @Test
    fun `close-on-target hexes are within 3 of the target, free of enemies, and have one of our units within the deploy range`() {
        val city = game.addCity(enemy, tile(0, 0))
        game.addUnit("Warrior", enemy, tile(0, 1)) // an enemy unit holds its hex
        val unit = game.addUnit("Warrior", civ, tile(0, -4))
        val plots = Deployment.scoreCloseOnPlots(context(), city.getCenterTile(), false, listOf(OperationUnit(unit, FormationPosition.FRONT_LINE)))
        val tiles = plots.map { it.tile }
        assertFalse("the city itself", city.getCenterTile() in tiles)
        assertFalse("the enemy's hex", tile(0, 1) in tiles)
        assertTrue(tiles.all { it.aerialDistanceTo(city.getCenterTile()) <= 3 })
        // The deploy range is 5: hexes more than 5 from our only unit are no use
        assertTrue(tiles.all { it.aerialDistanceTo(unit.getTile()) <= 5 })
        assertTrue(tile(0, -3) in tiles)
        assertFalse("6 from the unit", tile(0, 2) in tiles)
    }

    @Test
    fun `close-on-target hexes in our own city add 100, others the terrain's defense, best first`() {
        val enemyCity = game.addCity(enemy, tile(0, 0))
        val ours = game.addCity(civ, tile(0, -3))
        game.setTileTerrain(HexCoord(0, -1), "Hill") // +25% defense
        val unit = game.addUnit("Warrior", civ, tile(0, -6))
        val ctx = context()
        val plots = Deployment.scoreCloseOnPlots(ctx, enemyCity.getCenterTile(), false, listOf(OperationUnit(unit, FormationPosition.FRONT_LINE)))
        assertEquals("distance 3 in our city: 300 + 100", 400, plotAt(plots, ours.getCenterTile()).score)
        assertEquals("a hill next to the city: 500 + its defense", 500 + ctx.map.defenseModifier(tile(0, -1)), plotAt(plots, tile(0, -1)).score)
        assertTrue(ctx.map.defenseModifier(tile(0, -1)) > 0)
        assertEquals(plots.sortedByDescending { it.score }.map { it.score }, plots.map { it.score })
    }

    @Test
    fun `hexes within range of the target but not next to it are for ranged units, and water is left out when land only`() {
        val enemyCity = game.addCity(enemy, tile(0, 0))
        game.setTileTerrain(HexCoord(0, -1), "Ocean")
        val unit = game.addUnit("Archer", civ, tile(0, -5))
        val ctx = context()
        ctx.map.setTargetBombardCells(enemyCity.getCenterTile(), 2, true)
        val operationUnits = listOf(OperationUnit(unit, FormationPosition.BOMBARD))
        val plots = Deployment.scoreCloseOnPlots(ctx, enemyCity.getCenterTile(), false, operationUnits)
        assertTrue("2 away and in range", plotAt(plots, tile(0, -2)).forRanged)
        assertFalse("next to the city", plotAt(plots, tile(-1, -1)).forRanged)
        assertFalse("3 away: out of range 2", plotAt(plots, tile(0, -3)).forRanged)
        assertTrue("water is a hex when not land only", tile(0, -1) in plots.map { it.tile })
        assertFalse(tile(0, -1) in Deployment.scoreCloseOnPlots(ctx, enemyCity.getCenterTile(), true, operationUnits).map { it.tile })
    }

    // endregion
    // region ScoreHedgehogPlots

    @Test
    fun `hedgehog hexes score 600 less 150 per hex, plus 100 where the enemy can attack, 50 where it can pass, 100 in our city, else 4 times the defense`() {
        val city = game.addCity(civ, tile(0, 0))
        game.addUnit("Warrior", enemy, tile(0, 4)) // attacks hexes within 2 of it, passes those within 1
        game.setTileTerrain(HexCoord(0, -2), "Hill")
        val ctx = context()
        val plots = Deployment.scoreHedgehogPlots(ctx, city.getCenterTile())

        assertEquals("our city: 600 + 100", 700, plotAt(plots, city.getCenterTile()).score)
        assertTrue(plotAt(plots, city.getCenterTile()).forRanged)
        assertEquals("a hill 2 away, no enemy near: 300 + 4 x defense", 300 + 4 * ctx.map.defenseModifier(tile(0, -2)), plotAt(plots, tile(0, -2)).score)
        assertTrue(ctx.map.defenseModifier(tile(0, -2)) > 0)
        val attackable = plotAt(plots, tile(0, 2)) // 2 from the warrior: it can attack there, not pass
        assertEquals(600 - 2 * 150 + 100 + 0, attackable.score)
        assertFalse("hexes an enemy can attack are not for ranged units", attackable.forRanged)
        val passable = plotAt(plots, tile(0, 3)) // 1 from the warrior: attack and pass
        assertEquals(600 - 3 * 150 + 100 + 50, passable.score)
    }

    @Test
    fun `hedgehog hexes leave out water and enemy hexes`() {
        val city = game.addCity(civ, tile(0, 0))
        game.setTileTerrain(HexCoord(0, 1), "Ocean")
        game.addUnit("Warrior", enemy, tile(0, -1))
        val tiles = Deployment.scoreHedgehogPlots(context(), city.getCenterTile()).map { it.tile }
        assertFalse(tile(0, 1) in tiles)
        assertFalse(tile(0, -1) in tiles)
        assertTrue(city.getCenterTile() in tiles)
    }

    // endregion
    // region AssignDeployingUnits

    private fun blocks(tile: Tile, vararg units: Pair<MapUnit, Int>) =
        units.map { PotentialBlock(it.first, tile, it.second, units.size) }

    @Test
    fun `a hex only one unit can take is that unit's, and the others go on to the best hex`() {
        val (a, b, c) = units("Warrior", "Warrior", "Warrior", around = tile(0, 0))
        val best = tile(0, 5)
        val second = tile(0, 6)
        val third = tile(0, 7)
        // The best hex can be taken by a and b, the second by a only, the third by b and c
        val potential = blocks(best, a to 1, b to 2) + blocks(second, a to 3) + blocks(third, b to 1, c to 2)
        val chosen = Deployment.assignDeployingUnits(potential, 3)
        assertEquals("a must go to the second hex, then b to the best, then c is left for the third",
            mapOf(second to a, best to b, third to c), chosen.associate { it.tile to it.unit })
    }

    @Test
    fun `with a choice the best hex gets its nearest unit`() {
        val (a, b) = units("Warrior", "Warrior", around = tile(0, 0))
        val best = tile(0, 5)
        val other = tile(0, 6)
        val potential = blocks(best, b to 1, a to 4) + blocks(other, b to 2, a to 3)
        val chosen = Deployment.assignDeployingUnits(potential, 2)
        assertEquals(mapOf(best to b, other to a), chosen.associate { it.tile to it.unit })
    }

    @Test
    fun `two hexes that only the same unit can take do not get it twice`() {
        val (a) = units("Warrior", around = tile(0, 0))
        val chosen = Deployment.assignDeployingUnits(blocks(tile(0, 5), a to 1) + blocks(tile(0, 6), a to 2), 2)
        assertTrue("not going to work: nothing is assigned twice", chosen.size <= 1 && chosen.map { it.unit }.toSet().size == chosen.size)
    }

    // endregion
    // region ExecuteCloseOnTarget and ExecuteHedgehogDefense

    @Test
    fun `units close in on the target one to a hex, ranged units on the hexes they can bombard from`() {
        val enemyCity = game.addCity(enemy, tile(0, 0))
        val archer = game.addUnit("Archer", civ, tile(0, -5))
        val swordsmen = listOf(game.addUnit("Swordsman", civ, tile(-1, -4)), game.addUnit("Swordsman", civ, tile(-2, -5)))
        val all = listOf(archer) + swordsmen
        val before = all.map { it.getTile().aerialDistanceTo(enemyCity.getCenterTile()) }
        state.addTemporaryZone(enemyCity.getCenterTile(), TacticalTargetType.City)
        val ctx = context()
        ctx.map.setTargetBombardCells(enemyCity.getCenterTile(), 2, true)
        Deployment.executeCloseOnTarget(ctx, enemyCity.getCenterTile(), zoneOf(ctx, enemyCity))

        assertEquals("one to a hex", 3, all.map { it.getTile() }.toSet().size)
        assertTrue("they came closer", all.indices.all { all[it].getTile().aerialDistanceTo(enemyCity.getCenterTile()) < before[it] })
        assertTrue("and are done for the turn", all.all { it in ctx.cache.processedUnits && !it.hasMovement() })
    }

    @Test
    fun `a unit inside the ring takes a hex of it and is done for the turn`() {
        val enemyCity = game.addCity(enemy, tile(0, 0))
        val warrior = game.addUnit("Warrior", civ, tile(0, -2))
        val ctx = context()
        Deployment.executeCloseOnTarget(ctx, enemyCity.getCenterTile(), zoneOf(ctx, enemyCity))
        assertTrue(warrior.getTile().aerialDistanceTo(enemyCity.getCenterTile()) <= 3)
        assertTrue(warrior in ctx.cache.processedUnits)
    }

    @Test
    fun `units far from the target are left alone`() {
        val enemyCity = game.addCity(enemy, tile(0, 0))
        val far = game.addUnit("Warrior", civ, tile(0, -14))
        val ctx = context()
        Deployment.executeCloseOnTarget(ctx, enemyCity.getCenterTile(), zoneOf(ctx, enemyCity))
        assertEquals(tile(0, -14), far.getTile())
        assertFalse(far in ctx.cache.processedUnits)
    }

    @Test
    fun `a hedgehog puts ranged units out of the enemy's reach and melee units on the best hexes of the ring`() {
        val city = game.addCity(civ, tile(0, 0))
        game.addUnit("Warrior", enemy, tile(0, 5)) // can attack within 2 of it
        val archer = game.addUnit("Archer", civ, tile(0, -4))
        val swordsman = game.addUnit("Swordsman", civ, tile(1, -4))
        val ctx = context()
        val zone = ctx.map.zoneByCity(city, false)!!
        val plots = Deployment.scoreHedgehogPlots(ctx, city.getCenterTile())
        Deployment.executeHedgehogDefense(ctx, city.getCenterTile(), zone)
        assertTrue("the archer stands where no enemy attacks", plotAt(plots, archer.getTile()).forRanged)
        assertNotEquals(archer.getTile(), swordsman.getTile())
        assertTrue(listOf(archer, swordsman).all { it.getTile().aerialDistanceTo(city.getCenterTile()) <= 3 })
        assertTrue(listOf(archer, swordsman).all { it in ctx.cache.processedUnits })
    }

    private val state get() = civ.ai.tactical

    // endregion
    // region Great generals

    private fun generalScore(general: MapUnit, tile: Tile, army: com.unciv.logic.automation.strategy.AiArmy? = null) =
        Deployment.scoreGreatGeneralPlot(context(), general, tile, army)

    @Test
    fun `a general's plot is worth nothing in an enemy city or beside an enemy unit, and ten when empty`() {
        val general = game.addUnit("Great General", civ, tile(0, 0))
        game.addCity(enemy, tile(0, 4))
        game.addUnit("Warrior", enemy, tile(0, 2))
        assertEquals(0, generalScore(general, tile(0, 4)))
        assertEquals(0, generalScore(general, tile(0, 2)))
        assertEquals(10, generalScore(general, tile(3, -3)))
    }

    @Test
    fun `a general's plot with one of our units is 250 times its strength, a twenty-fifth of it while the unit still has moves`() {
        val general = game.addUnit("Great General", civ, tile(0, 0))
        val swordsman = game.addUnit("Swordsman", civ, tile(0, 2))
        val strength = swordsman.baseUnit.strength
        assertEquals(strength * 250 / 25, generalScore(general, tile(0, 2)))
        swordsman.currentMovement = 0f
        assertEquals("a unit that is done is a place to stand", strength * 250, generalScore(general, tile(0, 2)))
        swordsman.health = 50
        assertTrue("a wounded unit counts for less", generalScore(general, tile(0, 2)) < strength * 250)
    }

    @Test
    fun `a general's plot in one of our cities is the city's strength, and danger divides it`() {
        val city = game.addCity(civ, tile(0, 0))
        val general = game.addUnit("Great General", civ, tile(0, 2))
        val strength = com.unciv.logic.battle.CityCombatant(city).getCityStrength() * 100
        assertEquals(strength, generalScore(general, city.getCenterTile()))
        game.addUnit("Horseman", enemy, tile(0, -2)) // can reach the city
        val ctx = context()
        val danger = ctx.danger.get(city.getCenterTile())
        assertTrue(danger > 0)
        val divisor = when {
            danger > strength * 2 -> 5
            danger > strength -> 3
            danger > strength / 2 -> 2
            else -> 1
        }
        assertEquals(strength / divisor, Deployment.scoreGreatGeneralPlot(ctx, general, city.getCenterTile(), null))
    }

    @Test
    fun `a general moves to the best plot it can reach, next to the army's units rather than alone`() {
        val warrior = game.addUnit("Warrior", civ, tile(0, 2))
        warrior.currentMovement = 0f
        val general = game.addUnit("Great General", civ, tile(0, 0))
        val ctx = context()
        Deployment.moveGreatGenerals(ctx, listOf(general), null)
        assertEquals("with the unit that is done", warrior.getTile(), general.getTile())
        assertTrue(general in ctx.cache.processedUnits)
    }

    // endregion
}
