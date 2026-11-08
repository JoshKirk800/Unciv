package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvTacticalAI::SelectPosture, UpdatePostures and UseThisDominanceZone on zones with chosen strengths and counts */
@RunWith(BaseTestRunner::class)
class PostureSelectionTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization
    private lateinit var enemy: Civilization
    private lateinit var enemyCity: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(12)
        civ = game.addCiv()
        enemy = game.addCiv()
        civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        civ.getDiplomacyManager(enemy)!!.declareWar()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        game.addCity(civ, game.tileMap[0, -9])
        enemyCity = game.addCity(enemy, game.tileMap[0, 9])
    }

    private val state get() = civ.ai.tactical

    private fun zone(
        territory: TacticalTerritory,
        dominance: TacticalDominance,
        isWater: Boolean = false,
        city: City? = null,
        friendlyUnits: Int = 2,
        friendlyRanged: Int = 0,
        enemyUnits: Int = 2,
        friendlyRangedStrength: Int = 100,
        enemyRangedStrength: Int = 100,
    ) = DominanceZone(0, territory, null, 0, isWater, city).also {
        it.dominance = dominance
        it.friendlyUnitCount = friendlyUnits
        it.friendlyRangedUnitCount = friendlyRanged
        it.enemyUnitCount = enemyUnits
        it.friendlyRangedStrength = friendlyRangedStrength
        it.enemyRangedStrength = enemyRangedStrength
        it.friendlyStrength = 1000
        it.enemyStrength = 1000
    }

    private fun select(zone: DominanceZone, last: TacticalPosture = TacticalPosture.None) =
        PostureSelection.selectPosture(civ, state, zone, last)

    // region Enemy land

    @Test
    fun `in enemy land withdraw when the enemy dominates or we have only ranged units left`() {
        assertEquals(TacticalPosture.Withdraw, select(zone(TacticalTerritory.Enemy, TacticalDominance.Enemy, city = enemyCity)))
        assertEquals(TacticalPosture.Withdraw,
            select(zone(TacticalTerritory.Enemy, TacticalDominance.Friendly, city = enemyCity, friendlyUnits = 3, friendlyRanged = 3)))
    }

    @Test
    fun `a zone an operation just reached is assaulted, with the units that are there, not withdrawn from`() {
        state.addTemporaryZone(enemyCity.getCenterTile(), TacticalTargetType.City)
        assertEquals(TacticalPosture.SurgicalCityStrike, select(zone(TacticalTerritory.Enemy, TacticalDominance.Enemy, city = enemyCity)))
        assertEquals(TacticalPosture.Steamroll, select(zone(TacticalTerritory.Enemy, TacticalDominance.Friendly, city = enemyCity)))
        assertEquals(TacticalPosture.Steamroll, select(zone(TacticalTerritory.Enemy, TacticalDominance.Even, city = enemyCity)))
    }

    @Test
    fun `we steamroll enemy land we dominate unless the enemy has the ranged edge, and keep doing it on a weaker edge`() {
        val ranged = zone(TacticalTerritory.Enemy, TacticalDominance.Friendly, city = enemyCity, friendlyRangedStrength = 0, enemyRangedStrength = 100)
        assertEquals("the enemy dominates ranged and we are not at double strength: not steamroll",
            TacticalPosture.SurgicalCityStrike, select(ranged))
        assertEquals("but once begun it goes on", TacticalPosture.Steamroll, select(ranged, last = TacticalPosture.Steamroll))
        ranged.friendlyStrength = 2001
        ranged.enemyStrength = 1000
        assertEquals("at more than double the strength it starts anyway", TacticalPosture.Steamroll, select(ranged))
        assertEquals(TacticalPosture.Steamroll, select(zone(TacticalTerritory.Enemy, TacticalDominance.Friendly, city = enemyCity)))
    }

    @Test
    fun `we sit and bombard enemy land where our ranged units dominate and the unit count is not against us, and keep it on a weaker edge`() {
        val even = zone(TacticalTerritory.Enemy, TacticalDominance.Even, city = enemyCity, friendlyRanged = 1, friendlyRangedStrength = 1000, enemyRangedStrength = 100)
        assertEquals(TacticalPosture.SitAndBombard, select(even))
        val weaker = zone(TacticalTerritory.Enemy, TacticalDominance.Even, city = enemyCity, friendlyRanged = 1, friendlyRangedStrength = 100, enemyRangedStrength = 100)
        assertEquals(TacticalPosture.SurgicalCityStrike, select(weaker))
        assertEquals(TacticalPosture.SitAndBombard, select(weaker, last = TacticalPosture.SitAndBombard))
        val outnumbered = zone(TacticalTerritory.Enemy, TacticalDominance.Even, city = enemyCity, friendlyUnits = 2, friendlyRanged = 1,
            enemyUnits = 4, friendlyRangedStrength = 1000, enemyRangedStrength = 100)
        assertEquals("outnumbered two to one: no sitting back", TacticalPosture.SurgicalCityStrike, select(outnumbered))
    }

    @Test
    fun `in enemy land we exploit flanks when we have more units and there are several enemies`() {
        val flanks = zone(TacticalTerritory.Enemy, TacticalDominance.Even, city = enemyCity, friendlyUnits = 6, friendlyRanged = 1, enemyUnits = 2,
            friendlyRangedStrength = 100, enemyRangedStrength = 100)
        assertEquals(TacticalPosture.ExploitFlanks, select(flanks))
        flanks.enemyUnitCount = 1
        assertEquals("one enemy is not enough", TacticalPosture.SurgicalCityStrike, select(flanks))
    }

    // endregion
    // region Other land

    @Test
    fun `in unclaimed land we attrit from range when our ranged units dominate, else exploit flanks`() {
        assertEquals(TacticalPosture.AttritFromRange, select(zone(TacticalTerritory.NoOwner, TacticalDominance.Even, enemyRangedStrength = 0)))
        val behind = zone(TacticalTerritory.NoOwner, TacticalDominance.Even, friendlyRangedStrength = 100, enemyRangedStrength = 100)
        assertEquals(TacticalPosture.ExploitFlanks, select(behind))
        assertEquals(TacticalPosture.AttritFromRange, select(behind, last = TacticalPosture.AttritFromRange))
        assertEquals(TacticalPosture.AttritFromRange, select(zone(TacticalTerritory.Neutral, TacticalDominance.Even, enemyRangedStrength = 0)))
    }

    @Test
    fun `in our land we attrit with two or more ranged units, exploit flanks with more units, counterattack when stronger, else hold`() {
        assertEquals(TacticalPosture.AttritFromRange,
            select(zone(TacticalTerritory.Friendly, TacticalDominance.Enemy, friendlyRanged = 2, enemyRangedStrength = 0)))
        assertEquals("one ranged unit is not enough", TacticalPosture.Hedgehog,
            select(zone(TacticalTerritory.Friendly, TacticalDominance.Enemy, friendlyRanged = 1, enemyRangedStrength = 0, friendlyUnits = 2, enemyUnits = 2)))
        assertEquals(TacticalPosture.ExploitFlanks,
            select(zone(TacticalTerritory.Friendly, TacticalDominance.Enemy, friendlyUnits = 6, enemyUnits = 2, friendlyRangedStrength = 0, enemyRangedStrength = 100)))
        assertEquals(TacticalPosture.Counterattack,
            select(zone(TacticalTerritory.Friendly, TacticalDominance.Friendly, friendlyRangedStrength = 100, enemyRangedStrength = 100)))
        assertEquals("even, but the enemy has the ranged edge", TacticalPosture.Counterattack,
            select(zone(TacticalTerritory.Friendly, TacticalDominance.Even, friendlyRangedStrength = 0, enemyRangedStrength = 100)))
        val level = zone(TacticalTerritory.Friendly, TacticalDominance.Even, friendlyRangedStrength = 100, enemyRangedStrength = 100)
        assertEquals(TacticalPosture.Hedgehog, select(level))
        assertEquals("a counterattack goes on until the enemy dominates", TacticalPosture.Counterattack,
            select(level, last = TacticalPosture.Counterattack))
        level.dominance = TacticalDominance.Enemy
        assertEquals(TacticalPosture.Hedgehog, select(level, last = TacticalPosture.Counterattack))
    }

    @Test
    fun `a temporary zone is bombarded from the sea on water and flanked on land`() {
        assertEquals(TacticalPosture.ShoreBombardment, select(zone(TacticalTerritory.TempZone, TacticalDominance.Even, isWater = true)))
        assertEquals(TacticalPosture.ExploitFlanks, select(zone(TacticalTerritory.TempZone, TacticalDominance.Even)))
    }

    // endregion
    // region Which zones count and what is remembered

    @Test
    fun `a zone counts when it is temporary, our capital's, or where we or they have units near`() {
        val capital = civ.getCapital()!!
        assertTrue(PostureSelection.useThisZone(civ, state, zone(TacticalTerritory.Friendly, TacticalDominance.NoUnitsVisible, city = capital, friendlyUnits = 0, enemyUnits = 0)))
        val other = game.addCity(civ, game.tileMap[0, 0])
        assertFalse("a quiet city of ours", PostureSelection.useThisZone(civ, state, zone(TacticalTerritory.Friendly, TacticalDominance.NoUnitsVisible, city = other, friendlyUnits = 0, enemyUnits = 0)))
        assertTrue("with the enemy in sight", PostureSelection.useThisZone(civ, state, zone(TacticalTerritory.Friendly, TacticalDominance.Even, city = other, friendlyUnits = 0, enemyUnits = 1)))
        assertTrue("our units near their city", PostureSelection.useThisZone(civ, state, zone(TacticalTerritory.Enemy, TacticalDominance.Enemy, city = enemyCity, friendlyUnits = 1, enemyUnits = 0)))
        assertFalse("no units of ours near their city", PostureSelection.useThisZone(civ, state, zone(TacticalTerritory.Enemy, TacticalDominance.Enemy, city = enemyCity, friendlyUnits = 0, enemyUnits = 0)))
        assertTrue("both have units", PostureSelection.useThisZone(civ, state, zone(TacticalTerritory.NoOwner, TacticalDominance.Even)))
        state.addTemporaryZone(enemyCity.getCenterTile(), TacticalTargetType.City)
        assertTrue("an operation's target", PostureSelection.useThisZone(civ, state, zone(TacticalTerritory.Enemy, TacticalDominance.Enemy, city = enemyCity, friendlyUnits = 0, enemyUnits = 0)))
    }

    @Test
    fun `postures are chosen for the zones worth using and remembered by owner, water and city`() {
        val capital = civ.getCapital()!!
        civ.viewableTiles = game.tileMap.values.toSet()
        civ.ai.invalidateTurnCaches()
        // five Swordsmen around our capital, which is the only zone in use: the enemy dominates it
        for (tile in capital.getCenterTile().getTilesAtDistance(2).take(5)) game.addUnit("Swordsman", enemy, tile)
        civ.ai.invalidateTurnCaches()
        assertEquals(TacticalDominance.Enemy, state.map.zoneOf(capital.getCenterTile())!!.dominance)
        PostureSelection.updatePostures(civ, state, state.map)
        assertEquals(1, state.postures.size)
        assertEquals(TacticalPosture.Hedgehog, state.findPosture(civ.civName, false, capital.id))
        assertEquals(TacticalPosture.None, state.findPosture(civ.civName, true, capital.id))
        assertEquals(TacticalPosture.None, state.findPosture(null, false, null))

        // what a zone chose last turn is what it remembers
        state.postures[0].posture = TacticalPosture.Counterattack
        assertEquals(TacticalPosture.Counterattack, state.findPosture(civ.civName, false, capital.id))
    }

    // endregion
}
