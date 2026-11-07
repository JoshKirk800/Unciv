package com.unciv.logic.map

import com.unciv.Constants
import com.unciv.logic.battle.Battle
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.movement.UnitMovement
import com.unciv.logic.map.tile.Tile
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Embarked units and ships do not compete for a tile slot, but two embarked units (military or civilian) do:
 *  in the original game any two embarked units are one kind and ships another (CvUnit::AreUnitsOfSameType). */
@RunWith(BaseTestRunner::class)
class EmbarkedStackingTests {
    private val game = TestGame()
    private lateinit var civ: Civilization
    private lateinit var other: Civilization
    private lateinit var home: Tile // the tile the ship is on
    private lateinit var shore: Tile // a tile next to it, where embarked units come from

    @Before
    fun setUp() {
        game.makeHexagonalMap(4)
        for (tile in game.tileMap.tileList) {
            tile.baseTerrain = Constants.ocean
            tile.setTransients()
        }
        civ = game.addCiv()
        other = game.addCiv()
        for (tech in game.ruleset.technologies.keys) civ.tech.addTechnology(tech) // lets land units embark on ocean
        for (tech in game.ruleset.technologies.keys) other.tech.addTechnology(tech)
        home = game.getTile(0, 0)
        shore = game.getTile(1, 0)
    }

    private fun landUnit(name: String, owner: Civilization, tile: Tile): MapUnit = game.addUnit(name, owner, tile)

    private fun MapUnit.reasonToMoveTo(tile: Tile) = movement.getCannotMoveToReason(tile)

    @Test
    fun `an embarked military unit can join a warship, and both stay on the tile`() {
        val ship = landUnit("Frigate", civ, home)
        val warrior = landUnit("Warrior", civ, shore)
        assertTrue(warrior.isEmbarked())

        assertNull(warrior.reasonToMoveTo(home))
        warrior.movement.moveToTile(home)

        assertSame(ship, home.militaryUnit)
        assertSame(warrior, home.embarkedUnit)
        assertEquals(setOf(ship, warrior), home.getUnits().toSet())
        assertSame(home, warrior.currentTile)
    }

    @Test
    fun `a warship can join an embarked military unit, which then waits in the embarked slot`() {
        val warrior = landUnit("Warrior", civ, home)
        val ship = landUnit("Frigate", civ, shore)

        assertNull(ship.reasonToMoveTo(home))
        ship.movement.moveToTile(home)

        assertSame(ship, home.militaryUnit)
        assertSame(warrior, home.embarkedUnit)
        assertEquals(setOf(ship, warrior), home.getUnits().toSet())
    }

    @Test
    fun `an embarked soldier and a ship can share water but cannot swap`() {
        val ship = landUnit("Frigate", civ, home)
        val soldier = landUnit("Warrior", civ, shore)

        assertFalse(soldier.movement.canUnitSwapTo(home))
        assertFalse(ship.movement.canUnitSwapTo(shore))
    }

    @Test
    fun `a soldier cannot swap with a ship stationed in its coastal city`() {
        home.baseTerrain = Constants.grassland
        home.setTransients()
        game.addCity(civ, home)
        val ship = landUnit("Frigate", civ, home)
        val soldier = landUnit("Warrior", civ, shore)

        assertFalse(soldier.movement.canUnitSwapTo(home))
        assertFalse(ship.movement.canUnitSwapTo(shore))
    }

    @Test
    fun `two embarked tanks can swap when one shares a tile with a ship`() {
        home.baseTerrain = Constants.coast
        home.setTransients()
        val ship = landUnit("Frigate", civ, home)
        val coastTank = landUnit("Tank", civ, home)
        val oceanTank = landUnit("Tank", civ, shore)

        assertTrue(coastTank.movement.canUnitSwapTo(shore))
        coastTank.movement.swapMoveToTile(shore)

        assertSame(coastTank, shore.militaryUnit)
        assertSame(ship, home.militaryUnit)
        assertSame(oceanTank, home.embarkedUnit)
    }

    @Test
    fun `embarked tanks sharing ships cannot swap with the ships`() {
        home.baseTerrain = Constants.coast
        home.setTransients()
        landUnit("Frigate", civ, home)
        landUnit("Frigate", civ, shore)
        val coastTank = landUnit("Tank", civ, home)
        val oceanTank = landUnit("Tank", civ, shore)

        assertFalse(coastTank.movement.canUnitSwapTo(shore))
        assertFalse(oceanTank.movement.canUnitSwapTo(home))
    }

    @Test
    fun `an embarked civilian can join a naval civilian`() {
        val admiral = landUnit("Great Admiral", civ, home)
        val settler = landUnit("Settler", civ, shore)

        assertNull(settler.reasonToMoveTo(home))
        settler.movement.moveToTile(home)

        assertSame(admiral, home.civilianUnit)
        assertSame(settler, home.embarkedUnit)
    }

    @Test
    fun `only one embarked unit fits next to a ship`() {
        landUnit("Frigate", civ, home)
        landUnit("Warrior", civ, home)
        val second = landUnit("Spearman", civ, shore)

        assertEquals(UnitMovement.CannotMoveToReason.TileIsNotEmpty, second.reasonToMoveTo(home))
    }

    @Test
    fun `two embarked military units cannot share a tile`() {
        landUnit("Warrior", civ, home)
        val second = landUnit("Spearman", civ, shore)

        assertEquals(UnitMovement.CannotMoveToReason.TileIsNotEmpty, second.reasonToMoveTo(home))
    }

    @Test
    fun `an embarked military and an embarked civilian unit cannot share a tile, either way round`() {
        landUnit("Warrior", civ, home)
        val settler = landUnit("Settler", civ, shore)
        assertEquals(UnitMovement.CannotMoveToReason.TileIsNotEmpty, settler.reasonToMoveTo(home))

        val spot = game.getTile(-2, 0)
        landUnit("Worker", civ, spot)
        val spearman = landUnit("Spearman", civ, game.getTile(-1, 0))
        assertEquals(UnitMovement.CannotMoveToReason.TileIsNotEmpty, spearman.reasonToMoveTo(spot))
    }

    @Test
    fun `an escorting pair can embark together`() {
        val coast = game.getTile(2, 0).apply { baseTerrain = Constants.grassland; setTransients() }
        val warrior = landUnit("Warrior", civ, coast)
        val worker = landUnit("Worker", civ, coast)
        warrior.startEscorting()
        assertTrue(warrior.isEscorting())

        val water = game.getTile(1, 0)
        assertNull(warrior.reasonToMoveTo(water))
        warrior.movement.moveToTile(water)

        assertSame(warrior, water.militaryUnit)
        assertSame(worker, water.civilianUnit)
    }

    @Test
    fun `an embarked unit does not join the ship of another civilization`() {
        landUnit("Frigate", other, home)
        val warrior = landUnit("Warrior", civ, shore)

        assertEquals(UnitMovement.CannotMoveToReason.TileIsNotEmpty, warrior.reasonToMoveTo(home))
    }

    @Test
    fun `when the ship leaves, the embarked unit takes its place and is what an attacker meets`() {
        val ship = landUnit("Frigate", civ, home)
        val warrior = landUnit("Warrior", civ, home.neighbors.first { it != shore })
        warrior.movement.moveToTile(home)
        assertSame(warrior, home.embarkedUnit)

        assertSame("the ship defends first", ship, (Battle.getMapCombatantOfTile(home) as com.unciv.logic.battle.MapUnitCombatant).unit)

        ship.destroy()

        assertSame(warrior, home.militaryUnit)
        assertNull(home.embarkedUnit)
        assertSame(warrior, (Battle.getMapCombatantOfTile(home) as com.unciv.logic.battle.MapUnitCombatant).unit)
    }

    @Test
    fun `the embarked unit leaving frees the slot and the ship stays`() {
        val ship = landUnit("Frigate", civ, home)
        val warrior = landUnit("Warrior", civ, shore)
        warrior.movement.moveToTile(home)

        warrior.movement.moveToTile(shore)

        assertSame(ship, home.militaryUnit)
        assertNull(home.embarkedUnit)
        assertSame(warrior, shore.militaryUnit)
        assertFalse(home.getUnits().contains(warrior))
    }

    @Test
    fun `an embarked unit sharing with a ship survives a copy of the tile`() {
        val ship = landUnit("Frigate", civ, home)
        val warrior = landUnit("Warrior", civ, shore)
        warrior.movement.moveToTile(home)

        val copy = home.clone()

        assertEquals(ship.name, copy.militaryUnit!!.name)
        assertEquals(warrior.name, copy.embarkedUnit!!.name)
    }
}
