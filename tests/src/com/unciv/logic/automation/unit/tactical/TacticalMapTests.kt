package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.sqrt

/**
 *  CvTacticalAnalysisMap with Brave New World data (ancient era: tactical range 5, so a unit's strength is multiplied by
 *  (5 + 4 - distance) x 50 and a city's by 5 x its strength x100 x health / 200): the strengths of a dominance zone, who dominates it,
 *  what the zone is worth, and the zones temporary zones make.
 */
@RunWith(BaseTestRunner::class)
class TacticalMapTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var city: City

    private val range = 5
    private val multiplier = 50

    @Before
    fun setUp() {
        game.makeHexagonalMap(12)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        city = game.addCity(civ, game.tileMap[0, 0], initialPopulation = 1)
    }

    private fun tile(y: Int) = game.tileMap[0, y]
    private fun strength(unit: String) = game.ruleset.units[unit]!!.strength

    private fun map(): TacticalMap {
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        return civ.ai.tactical.map
    }

    private fun cityStrength() = CityCombatant(city).getCityStrength() * 100 * range * city.health / game.ruleset.ai.define(AiDefine.MAX_CITY_HIT_POINTS)

    @Test
    fun `the tactical range grows with the era and the strength multiplier follows`() {
        assertEquals(range, map().tacticalRange)
    }

    @Test
    fun `a zone adds its city and our units by strength times proximity, and the enemy units the same`() {
        game.addUnit("Warrior", civ, tile(1))
        game.addUnit("Warrior", enemy, tile(3))
        val zone = map().zoneOf(city.getCenterTile())!!
        assertEquals(TacticalTerritory.Friendly, zone.territory)
        assertEquals(city, zone.closestCity)
        assertEquals(cityStrength() + strength("Warrior") * (range + 4 - 1) * multiplier, zone.friendlyStrength)
        assertEquals(strength("Warrior") * (range + 4 - 3) * multiplier, zone.enemyStrength)
        assertEquals(1, zone.friendlyUnitCount)
        assertEquals(1, zone.enemyUnitCount)
        assertEquals(3, zone.rangeClosestEnemyUnit)
    }

    @Test
    fun `units beyond the tactical range of the city do not count`() {
        game.addUnit("Warrior", enemy, tile(range + 1))
        val zone = map().zoneOf(city.getCenterTile())!!
        assertEquals(0, zone.enemyStrength)
        assertEquals(0, zone.enemyUnitCount)
        assertEquals(TacticalDominance.NoUnitsVisible, zone.dominance)
    }

    @Test
    fun `an enemy unit we cannot see counts half and is not counted as a unit`() {
        game.addUnit("Warrior", enemy, tile(4))
        civ.viewableTiles = emptySet()
        civ.ai.invalidateTurnCaches()
        val zone = civ.ai.tactical.map.zoneOf(city.getCenterTile())!!
        assertEquals(strength("Warrior") / 2 * (range + 4 - 4) * multiplier, zone.enemyStrength)
        assertEquals(0, zone.enemyUnitCount)
    }

    /** Free tiles around the city, nearest first */
    private fun freeTilesAroundCity() = city.getCenterTile().getTilesInDistance(3)
        .filter { it != city.getCenterTile() && it.militaryUnit == null }
        .sortedBy { it.aerialDistanceTo(city.getCenterTile()) }.toList()

    @Test
    fun `dominance is by the ratio of strengths against 25 percent`() {
        // our city alone: nothing of the enemy in sight
        assertEquals(TacticalDominance.NoUnitsVisible, map().zoneOf(city.getCenterTile())!!.dominance)

        // one enemy Warrior on the border: the city is much stronger; then add enemies one by one
        game.addUnit("Warrior", enemy, tile(5))
        val stages = ArrayList<Triple<Int, Int, TacticalDominance>>()
        for (tile in freeTilesAroundCity().take(12)) {
            val zone = map().zoneOf(city.getCenterTile())!!
            stages.add(Triple(zone.friendlyStrength, zone.enemyStrength, zone.dominance))
            game.addUnit("Warrior", enemy, tile)
        }
        for ((friendly, enemies, dominance) in stages) {
            val ratio = friendly * 100 / enemies
            val expected = when {
                ratio > 125 -> TacticalDominance.Friendly
                ratio < 75 -> TacticalDominance.Enemy
                else -> TacticalDominance.Even
            }
            assertEquals("ratio $ratio of $friendly to $enemies", expected, dominance)
        }
        assertEquals(TacticalDominance.Friendly, stages.first().third)
        assertEquals(TacticalDominance.Enemy, stages.last().third)
        assertTrue(map().isInEnemyDominatedZone(city.getCenterTile()))
    }

    @Test
    fun `the zone of our capital is worth 3 for the city, 2 for the capital, 4 for a visible city, 3 for land and 6 when the enemy dominates`() {
        // no enemy: base (1 + (1 + sqrt 1)) x2 x4 x3
        val base = (1 + (1 + sqrt(1f).toInt())) * 2 * 4 * 3
        assertEquals(base, map().zoneOf(city.getCenterTile())!!.value)

        // the enemy dominates a zone that is ours: x6
        for (tile in freeTilesAroundCity().take(8)) game.addUnit("Swordsman", enemy, tile)
        val zone = map().zoneOf(city.getCenterTile())!!
        assertEquals(TacticalDominance.Enemy, zone.dominance)
        assertEquals(base * 6, zone.value)
    }

    @Test
    fun `zones are sorted by value and an own zone beats unclaimed land`() {
        game.addUnit("Warrior", civ, tile(9))
        val map = map()
        val zones = map.zones
        assertEquals(zones.sortedByDescending { it.value }.map { it.id }, zones.map { it.id })
        assertSame(zones.first(), map.zoneOf(city.getCenterTile()))
    }

    @Test
    fun `unclaimed land is one zone per landmass and counts the units standing in it`() {
        game.addUnit("Warrior", civ, tile(9))
        game.addUnit("Warrior", enemy, tile(10))
        val map = map()
        val zone = map.zoneOf(tile(9))!!
        assertEquals(TacticalTerritory.NoOwner, zone.territory)
        assertNull(zone.closestCity)
        assertSame(zone, map.zoneOf(tile(-9)))
        assertEquals(strength("Warrior") * multiplier, zone.friendlyStrength)
        assertEquals(strength("Warrior") * multiplier, zone.enemyStrength)
        assertEquals(1, zone.friendlyUnitCount)
        assertEquals(1, zone.enemyUnitCount)
    }

    @Test
    fun `a temporary zone is worth 1000 and takes the unclaimed land near its center until it expires`() {
        civ.ai.tactical.addTemporaryZone(tile(9), TacticalTargetType.BarbarianCamp)
        val map = map()
        val zone = map.zoneOf(tile(9))!!
        assertEquals(TacticalTerritory.TempZone, zone.territory)
        assertEquals(1000, zone.value)
        assertSame(zone, map.zoneOf(tile(9 - range)))
        assertFalse(zone === map.zoneOf(tile(9 - range - 1)))
        assertEquals(zone, map.zones.first())

        val lastTurn = game.gameInfo.turns + game.ruleset.ai.define(AiDefine.AI_TACTICAL_MAP_TEMP_ZONE_TURNS)
        game.gameInfo.turns = lastTurn
        assertTrue("still there on its last turn", map().zones.any { it.territory == TacticalTerritory.TempZone })
        game.gameInfo.turns = lastTurn + 1
        assertTrue("gone after it", map().zones.none { it.territory == TacticalTerritory.TempZone })
    }

    @Test
    fun `a temporary zone on a city makes that city's zone worth 20 times its base instead of 4`() {
        val enemyCity = game.addCity(enemy, tile(9), initialPopulation = 1)
        // base (1 + (1 + 1)) x2 for the enemy capital, x land 3, and x2 for an enemy territory the enemy dominates
        val normal = map().zoneOf(enemyCity.getCenterTile())!!
        assertEquals(TacticalDominance.Enemy, normal.dominance)
        assertEquals(3 * 2 * 4 * 3 * 2, normal.value)

        civ.ai.tactical.addTemporaryZone(enemyCity.getCenterTile(), TacticalTargetType.City)
        val map = map()
        assertTrue(civ.ai.tactical.isTemporaryZoneCity(enemyCity))
        assertEquals(3 * 2 * 20 * 3 * 2, map.zoneOf(enemyCity.getCenterTile())!!.value)
        assertTrue("no zone of its own for a city", map.zones.none { it.territory == TacticalTerritory.TempZone })
    }

    @Test
    fun `an enemy zone where only the enemy city counts is dominated by the enemy`() {
        val enemyCity = game.addCity(enemy, tile(9), initialPopulation = 1)
        val map = map()
        val zone = map.zoneOf(enemyCity.getCenterTile())!!
        assertEquals(TacticalTerritory.Enemy, zone.territory)
        assertEquals(TacticalDominance.Enemy, zone.dominance)
        assertTrue(map.isInEnemyDominatedZone(enemyCity.getCenterTile()))
        assertFalse(map.isInEnemyDominatedZone(city.getCenterTile()))
        assertNotNull(map.zoneByCity(enemyCity, false))
    }

    @Test
    fun `an enemy can attack the tiles it can reach in a move and move past those it reaches with movement left`() {
        game.addUnit("Warrior", enemy, tile(6))
        game.addUnit("Warrior", enemy, tile(-1)) // next to our city, which no enemy can ever "attack" by this measure
        val map = map()
        assertTrue(map.isSubjectToAttack(tile(4)))
        assertTrue(map.enemyCanMovePast(tile(5)))
        assertTrue("on the edge of its moves it can attack but not move on", map.isSubjectToAttack(tile(4)) && !map.enemyCanMovePast(tile(4)))
        assertFalse(map.isSubjectToAttack(tile(3)))
        assertFalse("never in our own city", map.isSubjectToAttack(city.getCenterTile()))
    }
}
