package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Airlifting between the cities that have an Airport (CvUnit::canAirlift, canAirliftAt, airlift; CvCity::CanAirlift), Brave New World data:
 *  a land unit that has not moved, in or next to a city of ours with an Airport, goes to a free land tile in or next to another such
 *  city of ours that has no enemy unit next to it, and its movement is spent.
 */
@RunWith(GdxTestRunner::class)
class AirliftTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var from: City
    private lateinit var to: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(16, Constants.grassland)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        from = game.addCity(civ, tile(0))
        to = game.addCity(civ, tile(8))
        from.cityConstructions.addBuilding("Airport")
        to.cityConstructions.addBuilding("Airport")
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(y: Int): Tile = game.tileMap[0, y]

    private fun soldier(at: Tile = from.getCenterTile(), name: String = "Warrior"): MapUnit = game.addUnit(name, civ, at)

    /** A tile next to [to], not its center */
    private fun freeTileNextToTarget(): Tile = to.getCenterTile().neighbors.first()

    @Test
    fun `a unit in a city with an Airport airlifts to a free tile next to another one and has no movement left`() {
        val unit = soldier()
        val target = freeTileNextToTarget()
        assertTrue(unit.movement.canAirliftTo(target))
        assertTrue(unit.movement.airliftTo(target))
        assertEquals(target, unit.getTile())
        assertEquals(0f, unit.currentMovement)
        assertEquals(1, target.getUnits().count())
        assertTrue(from.getCenterTile().getUnits().none())
    }

    @Test
    fun `the other city's own tile is a destination if it is free`() {
        val unit = soldier()
        assertTrue(unit.movement.airliftTo(to.getCenterTile()))
        assertEquals(to.getCenterTile(), unit.getTile())
    }

    @Test
    fun `a unit standing next to the city airlifts as well`() {
        val unit = soldier(from.getCenterTile().neighbors.first())
        assertTrue(unit.movement.airliftTo(freeTileNextToTarget()))
    }

    @Test
    fun `a unit airlifts once per turn, and again with its movement back`() {
        val unit = soldier()
        assertTrue(unit.movement.airliftTo(freeTileNextToTarget()))
        // Back to the first city's door: it is next to an Airport city, but it has no movement left
        assertFalse(unit.movement.canAirliftFromHere())
        assertFalse(unit.movement.canAirliftTo(from.getCenterTile().neighbors.first()))
        assertFalse(unit.movement.airliftTo(from.getCenterTile().neighbors.first()))
        unit.currentMovement = unit.getMaxMovement().toFloat()
        assertTrue(unit.movement.airliftTo(from.getCenterTile().neighbors.first()))
    }

    @Test
    fun `a unit that has moved this turn cannot airlift`() {
        val unit = soldier()
        unit.currentMovement = 1f
        assertTrue(unit.currentMovement < unit.getMaxMovement())
        assertFalse(unit.movement.canAirliftFromHere())
        assertFalse(unit.movement.canAirliftTo(freeTileNextToTarget()))
    }

    @Test
    fun `both cities need an Airport`() {
        val unit = soldier()
        to.cityConstructions.removeBuilding("Airport")
        assertFalse(unit.movement.canAirliftTo(freeTileNextToTarget()))
        to.cityConstructions.addBuilding("Airport")
        assertTrue(unit.movement.canAirliftTo(freeTileNextToTarget()))
        from.cityConstructions.removeBuilding("Airport")
        assertFalse(unit.movement.canAirliftFromHere())
        assertFalse(unit.movement.canAirliftTo(freeTileNextToTarget()))
    }

    @Test
    fun `the destination must be in or next to the other city, not the same city and not further away`() {
        val unit = soldier()
        assertFalse("a tile of the city it starts in", unit.movement.canAirliftTo(from.getCenterTile().neighbors.last()))
        val twoAway = to.getCenterTile().getTilesAtDistance(2).first()
        assertFalse(unit.movement.canAirliftTo(twoAway))
        val unrelated = tile(-6)
        assertFalse(unit.movement.canAirliftTo(unrelated))
    }

    @Test
    fun `the destination must be land and free of a military unit`() {
        val unit = soldier()
        val water = freeTileNextToTarget()
        game.setTileTerrain(water.position, Constants.coast)
        assertFalse(unit.movement.canAirliftTo(water))
        val occupied = to.getCenterTile().neighbors.last()
        game.addUnit("Warrior", civ, occupied)
        assertFalse(unit.movement.canAirliftTo(occupied))
        assertTrue(unit.movement.canAirliftTo(to.getCenterTile().neighbors.toList()[2]))
    }

    @Test
    fun `an enemy unit that can defend next to the destination forbids it, a civilian or a unit we are not at war with does not`() {
        val unit = soldier()
        val target = freeTileNextToTarget()
        val beside = target.neighbors.first { it.aerialDistanceTo(to.getCenterTile()) == 2 }
        val away = to.getCenterTile().neighbors.first { it != beside && it !in beside.neighbors && it != target }
        game.addUnit("Worker", enemy, beside)
        assertTrue("a worker cannot defend", unit.movement.canAirliftTo(target))
        beside.getUnits().toList().forEach { it.destroy() }

        game.addUnit("Warrior", game.addCiv(), beside)
        assertTrue("a civilization we are not at war with is no enemy", unit.movement.canAirliftTo(target))
        beside.getUnits().toList().forEach { it.destroy() }

        game.addUnit("Warrior", enemy, beside)
        assertFalse(unit.movement.canAirliftTo(target))
        assertTrue("a tile that the enemy is not next to is fine", unit.movement.canAirliftTo(away))
    }

    @Test
    fun `an enemy next to the city's own tile forbids landing in the city`() {
        val unit = soldier()
        assertTrue(unit.movement.canAirliftTo(to.getCenterTile()))
        game.addUnit("Warrior", enemy, to.getCenterTile().getTilesAtDistance(2).first())
        assertTrue("two tiles away is not next to it", unit.movement.canAirliftTo(to.getCenterTile()))
        game.addUnit("Warrior", enemy, to.getCenterTile().neighbors.first())
        assertFalse(unit.movement.canAirliftTo(to.getCenterTile()))
    }

    @Test
    fun `only land units airlift, not air units, nor the caravans of the trade routes`() {
        val fighter = game.addUnit("Fighter", civ, from.getCenterTile())
        assertFalse(fighter.movement.canAirliftFromHere())
        val caravan = soldier(from.getCenterTile().neighbors.first(), "Caravan")
        assertFalse(caravan.movement.canAirliftFromHere())
        assertTrue(soldier(from.getCenterTile().neighbors.last()).movement.canAirliftFromHere())
    }

    @Test
    fun `a unit at sea next to the city does not airlift`() {
        civ.tech.unitsCanEmbark = true
        val shore = from.getCenterTile().neighbors.first()
        game.setTileTerrain(shore.position, Constants.coast)
        val unit = soldier(shore)
        assertTrue(unit.isEmbarked())
        assertFalse(unit.movement.canAirliftFromHere())
    }

    @Test
    fun `a tile next to the Airport of another civilization is no destination`() {
        val rival = game.addCiv()
        val foreign = game.addCity(rival, tile(-8))
        foreign.cityConstructions.addBuilding("Airport")
        val unit = soldier()
        assertFalse(unit.movement.canAirliftTo(foreign.getCenterTile().neighbors.first()))
    }

    @Test
    fun `the airlift action prepares the unit, and moving it to a tile then goes through the engine`() {
        val unit = soldier()
        val action = UnitActions.getUnitActions(unit, UnitActionType.Airlift).single()
        assertNotNull(action.action)
        assertFalse(action.isCurrentAction)
        action.action!!.invoke()
        assertTrue(unit.isPreparingAirlift())

        val destinations = unit.movement.getReachableTilesInCurrentTurn().toSet()
        assertEquals(unit.movement.getAirliftDestinations().toSet(), destinations)
        assertEquals((listOf(to.getCenterTile()) + to.getCenterTile().neighbors).toSet(), destinations)
        val target = freeTileNextToTarget()
        assertTrue(unit.movement.canReach(target))
        assertFalse(unit.movement.canReach(from.getCenterTile().neighbors.first()))
        assertEquals(target, unit.movement.getTileToMoveToThisTurn(target))
        assertNull(unit.movement.getTileToMoveToThisTurnOrNull(tile(-5)))

        unit.movement.moveToTile(target)
        assertEquals(target, unit.getTile())
        assertEquals(0f, unit.currentMovement)
        assertFalse("the order is used up", unit.isPreparingAirlift())
    }

    @Test
    fun `the airlift action is offered with an Airport, but only enabled if there is another one to go to`() {
        val unit = soldier()
        to.cityConstructions.removeBuilding("Airport")
        val disabled = UnitActions.getUnitActions(unit, UnitActionType.Airlift).single()
        assertNull("nowhere to go", disabled.action)
        from.cityConstructions.removeBuilding("Airport")
        assertTrue(UnitActions.getUnitActions(unit, UnitActionType.Airlift).toList().isEmpty())
    }
}
