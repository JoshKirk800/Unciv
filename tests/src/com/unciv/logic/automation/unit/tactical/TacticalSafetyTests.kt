package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.unit.CivilianSafety
import com.unciv.logic.automation.unit.PlotDanger
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Where units go when they are in danger (Brave New World data): the units that flee (CvTacticalAI::PlotMovesToSafety), the plot they pick
 *  (ExecuteMovesToSafestPlot), healing (PlotHealMoves), and the civilians' versions (CvHomelandAI::PlotMovesToSafety/ExecuteMovesToSafestPlot,
 *  MoveCivilianToSafety).
 */
@RunWith(BaseTestRunner::class)
class TacticalSafetyTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(14)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(y: Int) = game.tileMap[0, y]

    private fun danger(): PlotDanger {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        return civ.ai.plotDanger
    }

    private fun candidates(unit: MapUnit): List<Tile> =
        unit.movement.getDistanceToTiles().keys.filter { it != unit.getTile() && unit.movement.canMoveTo(it) }

    // region Who flees

    @Test
    fun `a unit on a tile without danger never flees`() {
        val unit = game.addUnit("Warrior", civ, tile(0))
        unit.health = 10
        assertFalse(TacticalSafety.wantsToFlee(unit, danger()))
    }

    @Test
    fun `a healthy unit flees when the danger is above its strength times 100, which one equal enemy within a move already is`() {
        val unit = game.addUnit("Warrior", civ, tile(0))
        // a weaker enemy: an Archer's 5 x 100 + the immediate threat bit is below 8 x 100
        val archer = game.addUnit("Archer", enemy, tile(2))
        assertEquals(501, danger().get(tile(0)))
        assertFalse(TacticalSafety.wantsToFlee(unit, danger()))
        archer.destroy()
        // an equal one: 8 x 100 + 1 is more than 8 x 100
        game.addUnit("Warrior", enemy, tile(2))
        assertEquals(801, danger().get(tile(0)))
        assertTrue(TacticalSafety.wantsToFlee(unit, danger()))
    }

    @Test
    fun `a hurt unit flees at half strength or less`() {
        val unit = game.addUnit("Warrior", civ, tile(0))
        game.addUnit("Warrior", enemy, tile(2))
        // BNW's wounded multiplier of 33 never halves a unit's strength: with a multiplier of 100 a unit at 40 health is at 4 of 8
        unit.health = 40
        assertFalse(TacticalSafety.wantsToFlee(unit, danger()))
        game.ruleset.ai.defines["WOUNDED_DAMAGE_MULTIPLIER"] = 100f
        assertTrue(TacticalSafety.wantsToFlee(unit, danger()))
        unit.health = 60
        assertFalse(TacticalSafety.wantsToFlee(unit, danger()))
    }

    @Test
    fun `a hurt unit flees from ranged fire that does more damage than it heals but not from melee units`() {
        val unit = game.addUnit("Warrior", civ, tile(0))
        unit.health = 80
        val melee = game.addUnit("Warrior", enemy, tile(2))
        assertFalse(TacticalSafety.isUnderEnemyRangedAttack(unit))
        assertFalse(TacticalSafety.wantsToFlee(unit, danger()))
        melee.destroy()
        game.addUnit("Archer", enemy, tile(2))
        civ.viewableTiles = game.tileMap.values.toSet()
        assertTrue(TacticalSafety.isUnderEnemyRangedAttack(unit))
        assertTrue(TacticalSafety.wantsToFlee(unit, danger()))
    }

    @Test
    fun `a unit that cannot defend flees from any danger`() {
        val worker = game.addUnit("Worker", civ, tile(0))
        assertFalse(TacticalSafety.canDefend(worker))
        game.addUnit("Archer", enemy, tile(2))
        assertTrue(TacticalSafety.wantsToFlee(worker, danger()))
        assertTrue(TacticalSafety.canDefend(game.addUnit("Warrior", civ, tile(6))))
    }

    @Test
    fun `barbarian combat units do not flee unless they are hurt ships`() {
        val barbarian = game.addBarbarianCiv()
        val unit = game.addUnit("Warrior", barbarian, tile(0))
        game.addUnit("Warrior", civ, tile(2))
        game.addUnit("Warrior", civ, tile(-2))
        barbarian.viewableTiles = game.tileMap.values.toSet()
        val danger = PlotDanger(barbarian)
        assertTrue("danger above the unit's strength", danger.get(tile(0)) > 8 * 100)
        assertFalse("healthy: no", TacticalSafety.wantsToFlee(unit, danger))
        unit.health = 30
        assertFalse("hurt but not a ship: no", TacticalSafety.wantsToFlee(unit, danger))
    }

    // endregion
    // region Where combat units go

    @Test
    fun `a city we can reach beats everything else`() {
        val city = game.addCity(civ, tile(3))
        val unit = game.addUnit("Warrior", civ, tile(5))
        game.addUnit("Horseman", enemy, tile(9))
        val danger = danger()
        assertTrue("the unit is in danger", danger.get(tile(5)) > 0)
        assertTrue("and there are safe plots to go to", candidates(unit).any { danger.get(it) == 0 })
        assertEquals(city.getCenterTile(), TacticalSafety.safestPlot(unit, danger))
    }

    @Test
    fun `a tile without danger in our territory beats one outside it, however far`() {
        val city = game.addCity(civ, tile(-9))
        val owned = tile(3).also { it.setOwningCity(city) }
        val unit = game.addUnit("Warrior", civ, tile(5))
        game.addUnit("Horseman", enemy, tile(9))
        val danger = danger()
        val nearerSafe = tile(4)
        assertEquals(0, danger.get(nearerSafe))
        assertEquals(0, danger.get(owned))
        assertTrue(nearerSafe.aerialDistanceTo(unit.getTile()) < owned.aerialDistanceTo(unit.getTile()))
        assertEquals(owned, TacticalSafety.safestPlot(unit, danger))
    }

    @Test
    fun `outside our territory the nearest tile without danger is taken`() {
        val unit = game.addUnit("Warrior", civ, tile(5))
        game.addUnit("Horseman", enemy, tile(9))
        val danger = danger()
        val chosen = TacticalSafety.safestPlot(unit, danger)!!
        assertEquals(0, danger.get(chosen))
        assertEquals("the nearest one", 1, chosen.aerialDistanceTo(unit.getTile()))
    }

    @Test
    fun `with danger everywhere the lowest danger wins`() {
        val unit = game.addUnit("Warrior", civ, tile(0))
        for (tile in tile(0).getTilesAtDistance(2)) game.addUnit("Warrior", enemy, tile)
        val danger = danger()
        val reachable = candidates(unit)
        assertTrue(reachable.isNotEmpty())
        assertTrue("everything dangerous", reachable.all { danger.get(it) > 0 })
        val best = TacticalSafety.safestPlot(unit, danger)!!
        assertEquals(reachable.minOf { danger.get(it) }, danger.get(best))
    }

    @Test
    fun `a tile with another unit of ours is no place to flee to`() {
        val unit = game.addUnit("Warrior", civ, tile(5))
        game.addUnit("Horseman", enemy, tile(9))
        for (tile in tile(5).getTilesInDistance(1)) if (tile != tile(5)) game.addUnit("Warrior", civ, tile)
        val danger = danger()
        val target = TacticalSafety.safestPlot(unit, danger)!!
        assertNull(target.militaryUnit)
    }

    @Test
    fun `moving to safety ends the unit's turn on the chosen tile`() {
        val unit = game.addUnit("Warrior", civ, tile(5))
        unit.health = 20
        game.ruleset.ai.defines["WOUNDED_DAMAGE_MULTIPLIER"] = 100f // so that 20 health is under half strength
        game.addUnit("Horseman", enemy, tile(9))
        val danger = danger()
        val expected = TacticalSafety.safestPlot(unit, danger)!!
        assertTrue(TacticalSafety.moveToSafety(unit, danger))
        assertEquals(expected, unit.getTile())
        assertEquals(0f, unit.currentMovement, 0f)
    }

    // endregion
    // region Healing

    @Test
    fun `a hurt unit outside a city fortifies to heal and its turn is over`() {
        val unit = game.addUnit("Warrior", civ, tile(0))
        unit.health = 60
        assertTrue(TacticalSafety.heal(unit))
        assertTrue(unit.isFortified())
        assertEquals(0f, unit.currentMovement, 0f)
    }

    @Test
    fun `a healthy unit, one in a city, and one under ranged fire do not heal`() {
        val healthy = game.addUnit("Warrior", civ, tile(0))
        assertFalse(TacticalSafety.heal(healthy))
        val city = game.addCity(civ, tile(6))
        val inCity = game.addUnit("Warrior", civ, city.getCenterTile())
        inCity.health = 50
        assertFalse(TacticalSafety.heal(inCity))
        val shot = game.addUnit("Warrior", civ, tile(-6))
        shot.health = 50
        game.addUnit("Archer", enemy, tile(-4))
        civ.viewableTiles = game.tileMap.values.toSet()
        assertFalse(TacticalSafety.heal(shot))
    }

    // endregion
    // region Civilians

    @Test
    fun `a civilian on a plot with danger flees to the best tile, a safe own one first`() {
        val city = game.addCity(civ, tile(-9))
        val ownSafe = tile(3).also { it.setOwningCity(city) }
        val worker = game.addUnit("Worker", civ, tile(5))
        game.addUnit("Horseman", enemy, tile(9))
        danger()
        assertTrue(CivilianSafety.fleeIfThreatened(worker))
        assertEquals(ownSafe, worker.getTile())
        assertEquals(0f, worker.currentMovement, 0f)
    }

    @Test
    fun `a civilian on a plot without danger stays`() {
        val worker = game.addUnit("Worker", civ, tile(0))
        game.addUnit("Horseman", enemy, tile(12))
        danger()
        assertFalse(CivilianSafety.fleeIfThreatened(worker))
        assertEquals(tile(0), worker.getTile())
        assertTrue(worker.currentMovement > 0f)
    }

    @Test
    fun `a combat unit is no civilian and never flees by this rule`() {
        val warrior = game.addUnit("Warrior", civ, tile(5))
        game.addUnit("Horseman", enemy, tile(9))
        danger()
        assertFalse(CivilianSafety.fleeIfThreatened(warrior))
    }

    @Test
    fun `when everything is dangerous a tile with one of our defenders beats our territory and that beats the rest`() {
        val city = game.addCity(civ, tile(-12))
        val worker = game.addUnit("Worker", civ, tile(0))
        for (tile in tile(0).getTilesAtDistance(2)) game.addUnit("Warrior", enemy, tile)
        val covered = tile(0).neighbors.first()
        val own = tile(0).neighbors.drop(2).first().also { it.setOwningCity(city) }
        game.addUnit("Warrior", civ, covered)
        val danger = danger()
        val reachable = (candidates(worker) + tile(0))
        assertTrue("everything is dangerous", reachable.all { danger.get(it) > 0 })
        assertEquals(covered, CivilianSafety.safestPlot(worker, danger))
        covered.militaryUnit!!.destroy()
        assertEquals(own, CivilianSafety.safestPlot(worker, danger))
        own.setOwningCity(null)
        val best = CivilianSafety.safestPlot(worker, danger)!!
        assertEquals(reachable.minOf { danger.get(it) }, danger.get(best))
    }

    @Test
    fun `our city is where a civilian is safest even if it is in danger`() {
        val city = game.addCity(civ, tile(2))
        val worker = game.addUnit("Worker", civ, tile(0))
        game.addUnit("Warrior", enemy, tile(3))
        val danger = danger()
        assertTrue(danger.get(city.getCenterTile()) > 0)
        assertEquals(city.getCenterTile(), CivilianSafety.safestPlot(worker, danger))
    }

    @Test
    fun `moving a civilian to safety takes the tile with the lowest danger`() {
        val worker = game.addUnit("Worker", civ, tile(0))
        game.addUnit("Warrior", enemy, tile(2))
        game.addUnit("Warrior", enemy, tile(-2))
        val danger = danger()
        val options = candidates(worker) + tile(0)
        val here = danger.get(tile(0))
        assertTrue("some tile is safer than this one", options.any { danger.get(it) < here })
        assertTrue(CivilianSafety.moveToSafety(worker))
        assertEquals(options.minOf { danger.get(it) }, danger.get(worker.getTile()))
    }

    @Test
    fun `moving a civilian to safety goes into our city`() {
        val city = game.addCity(civ, tile(2))
        val worker = game.addUnit("Worker", civ, tile(0))
        assertTrue(CivilianSafety.moveToSafety(worker))
        assertEquals(city.getCenterTile(), worker.getTile())
    }

    @Test
    fun `moving a civilian to safety goes to a defender that stays but not to one that can still move`() {
        val worker = game.addUnit("Worker", civ, tile(0))
        val defender = game.addUnit("Warrior", civ, tile(-1))
        danger()
        assertTrue(CivilianSafety.moveToSafety(worker))
        assertEquals("a defender that can still move gives no cover", tile(0), worker.getTile())
        defender.fortify()
        assertTrue(CivilianSafety.moveToSafety(worker))
        assertEquals(tile(-1), worker.getTile())
    }

    @Test
    fun `a civilian does not walk into a tile with danger when it is walking somewhere`() {
        val worker = game.addUnit("Worker", civ, tile(0))
        game.addUnit("Horseman", enemy, tile(6))
        danger()
        // the end of its first turn towards (0, 8) is a tile in the horseman's reach
        assertFalse(CivilianSafety.headTowardsAvoidingDanger(worker, tile(8)))
        assertEquals(tile(0), worker.getTile())
        // away from it nothing is in the way
        assertTrue(CivilianSafety.headTowardsAvoidingDanger(worker, tile(-8)))
        assertNotEquals(tile(0), worker.getTile())
    }
    // endregion
}
