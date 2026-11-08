package com.unciv.logic.automation.unit

import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapType
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.min

/**
 *  Decision-level checks of the CvCitySiteEvaluator / CvPlayer::GetBestSettleAreas / GetBestSettlePlot port (SettleAreas.kt) against the
 *  DLL formulas with the BNW XML values (CITY_RING 6/3/2/1/1, SETTLER_FOOD 15, SETTLER_PRODUCTION 3, BUILD_ON_COAST 40, ...).
 */
@RunWith(GdxTestRunner::class)
class SettleAreasTest {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    }

    /** A hexagonal map of [radius], all [terrain], continents assigned */
    private fun map(radius: Int, terrain: String = "Grassland") {
        game.makeHexagonalMap(radius)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, terrain)
    }

    private fun assignContinents() = game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)

    /** A civ whose personality is all zero except [flavors], boldness 5 (the neutral band), that has seen the whole map */
    private fun civ(vararg flavors: Pair<Flavor, Int>, isPlayer: Boolean = false, nation: String? = null): Civilization {
        val civ = if (nation != null) game.addCiv(game.ruleset.nations[nation]!!, isPlayer) else game.addCiv(isPlayer = isPlayer)
        setPersonality(civ, *flavors)
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 5)
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        civ.viewableTiles = game.tileMap.values.toSet()
        return civ
    }

    private fun setPersonality(civ: Civilization, vararg flavors: Pair<Flavor, Int>) {
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for ((flavor, value) in flavors) civ.ai.setPersonalityFlavor(flavor, value)
    }

    private fun value(civ: Civilization, tile: Tile) = CitySiteEvaluator(civ).plotFoundValue(tile)

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]

    @Test
    fun `flavor multipliers double the single-flavor yields and add the next specialization`() {
        map(1)
        val civ = civ(Flavor.Growth to 5, Flavor.Expansion to 3, Flavor.Gold to 2, Flavor.TileImprovement to 4,
            Flavor.Production to 6, Flavor.Wonder to 1, Flavor.Science to 3, Flavor.Happiness to 4, Flavor.Religion to 7)
        // food = GROWTH + EXPANSION, gold = GOLD + TILE_IMPROVEMENT, production = PRODUCTION + WONDER, science/happiness/faith doubled
        assertArrayEquals(intArrayOf(8, 7, 6, 6, 14, 8, 10, 10), CitySiteEvaluator.computeFlavorMultipliers(civ))

        val specialization = game.ruleset.ai.citySpecializations.first { (it.flavors["Science"] ?: 0) > 0 }
        civ.ai.citySpecialization.nextSpecializationDesired = specialization.name
        val withSpecialization = CitySiteEvaluator.computeFlavorMultipliers(civ)
        assertEquals(6 + 2 * specialization.flavors["Science"]!!, withSpecialization[CitySiteEvaluator.SCIENCE])
    }

    @Test
    fun `found value weights each ring's yields and charges empty plots`() {
        map(8)
        assignContinents()
        val civ = civ(Flavor.Growth to 5, Flavor.Expansion to 3)
        val food = 8 * 15 * 2  // food flavor x SETTLER_FOOD_MULTIPLIER x 2 food of grassland
        // rings 1..3 at 6/3/2 per plot; rings 0, 4, 5 yield nothing counted: -ring x SETTLER_FOOD_MULTIPLIER x 2 each
        val raw = (6 * 6 + 12 * 3 + 18 * 2) * food - 30 * (1 + 24 + 30)
        // no own city within 7: x2/3
        assertEquals(raw * 2 / 3, value(civ, tile(0, 0)))

        // A ring-1 forest: 1 food, 1 production (PRODUCTION + WONDER flavor x SETTLER_PRODUCTION_MULTIPLIER 3)
        setPersonality(civ, Flavor.Growth to 5, Flavor.Expansion to 3, Flavor.Production to 4, Flavor.Wonder to 2)
        game.setTileFeatures(HexCoord(1, 0), "Forest")
        val withForest = raw - 6 * food + 6 * (1 * 8 * 15 + 1 * 6 * 3)
        assertEquals(withForest * 2 / 3, value(civ, tile(0, 0)))

        // A ring-1 jungle: its starting location weight -10 x 10 is strategic value, on top of its 2 food
        game.setTileFeatures(HexCoord(1, 0), "Jungle")
        val withJungle = raw - 6 * food + 6 * (2 * 8 * 15) + (-10 * 10)
        assertEquals(withJungle * 2 / 3, value(civ, tile(0, 0)))
    }

    @Test
    fun `an AI prefers its flavor's city spacing`() {
        map(9)
        assignContinents()
        val civ = civ()
        game.addCity(civ, tile(-5, 0))
        val site = tile(0, 0)  // 5 from the city
        // growth 2, expansion 8: sweet spot 5 - 1 - 1 = 3, at least 4: 5 is farther, but within 7 - unchanged. Food flavor 10.
        setPersonality(civ, Flavor.Growth to 2, Flavor.Expansion to 8)
        val base = value(civ, site)
        assertTrue(base > 0)
        // growth 5, expansion 5: sweet spot 5 - x1.75
        setPersonality(civ, Flavor.Growth to 5, Flavor.Expansion to 5)
        assertEquals(base * 175 / 100, value(civ, site))
        // growth 8, expansion 2: sweet spot 5 + 1 + 1 = 7, at most 6: 5 is closer - halved
        setPersonality(civ, Flavor.Growth to 8, Flavor.Expansion to 2)
        assertEquals(base / 2, value(civ, site))
    }

    @Test
    fun `boldness decides how close to foreign cities to settle`() {
        map(9)
        assignContinents()
        val civ = civ(Flavor.Growth to 5)
        val neighbor = civ()
        game.addCity(neighbor, tile(-4, 0))
        val site = tile(0, 0)  // 4 from a foreign major city
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 9)  // bold, but no own city within 7: unchanged
        val bold = value(civ, site)
        assertTrue(bold > 0)
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 5)
        assertEquals(bold * 2 / 3, value(civ, site))
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 2)
        assertEquals(bold / 4, value(civ, site))

        val siteAt5 = tile(1, 0)  // 5 from the foreign city
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 9)
        val boldAt5 = value(civ, siteAt5)
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 5)
        assertEquals("only closer than 5 costs a third", boldAt5, value(civ, siteAt5))
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 2)
        assertEquals(boldAt5 / 2, value(civ, siteAt5))
    }

    @Test
    fun `coast adds 40 percent, again with naval flavor over 7, and coastal civilizations double it`() {
        map(8)
        game.setTileTerrain(HexCoord(1, 0), "Coast")
        assignContinents()
        val site = tile(0, 0)
        // Humans get no spacing adjustment, so the value is the coast-adjusted value itself
        val human = civ(Flavor.Growth to 5, Flavor.Naval to 7, isPlayer = true)
        val coastOnce = value(human, site)
        setPersonality(human, Flavor.Growth to 5, Flavor.Naval to 8)
        assertEquals(coastOnce + coastOnce * 40 / 100, value(human, site))

        val england = civ(Flavor.Growth to 5, Flavor.Naval to 7, isPlayer = true, nation = "England")
        assertEquals((coastOnce + 25) * 2, value(england, site))
    }

    /** Two landmasses: the capital's (radius 5 around (-7,-7)) and an island (radius 6 around (7,7)); the civ sees the whole map */
    private fun twoLandmasses(): Civilization {
        map(14, "Ocean")
        for (tile in game.tileMap.values) {
            if (tile.aerialDistanceTo(tile(-7, -7)) <= 5 || tile.aerialDistanceTo(tile(7, 7)) <= 6)
                game.setTileTerrain(tile.position, "Grassland")
        }
        assignContinents()
        val civ = civ(Flavor.Growth to 5, Flavor.Expansion to 5)
        game.addCity(civ, tile(-7, -7))
        civ.viewableTiles = game.tileMap.values.toSet()  // founding the city recomputed the civ's vision
        return civ
    }

    private fun minimumFertility() = game.ruleset.ai.define(AiDefine.AI_STRATEGY_MINIMUM_SETTLE_FERTILITY)

    @Test
    fun `settle areas count the capital's landmass a third unless the map expands offshore`() {
        val civ = twoLandmasses()
        val home = tile(-7, -7).getContinent()
        val island = tile(7, 7).getContinent()
        val totals = SettleAreas.foundValues(civ).areaTotals
        val homeTotal = totals[home]!!
        val islandTotal = totals[island]!!
        assertTrue("setup: home is larger, but not three times larger", homeTotal > islandTotal && homeTotal / 3 < islandTotal)

        // The capital's area is never safe for a quick colony: a third of its value
        assertEquals(BestSettleAreas(2, island, home), SettleAreas.bestSettleAreas(civ, minimumFertility()))

        game.tileMap.mapParameters.type = MapType.smallContinents  // AI map hint 4: no safety check
        assertEquals(BestSettleAreas(2, home, island), SettleAreas.bestSettleAreas(civ, minimumFertility()))
    }

    @Test
    fun `a visible enemy on a landmass makes it unsafe`() {
        val civ = twoLandmasses()
        val home = tile(-7, -7).getContinent()
        val island = tile(7, 7).getContinent()
        assertTrue(SettleAreas.isAreaSafeForQuickColony(civ, island))
        assertFalse("the capital's area is never safe", SettleAreas.isAreaSafeForQuickColony(civ, home))
        val barbarians = game.addBarbarianCiv()
        game.addUnit("Warrior", barbarians, tile(7, 7))
        assertFalse(SettleAreas.isAreaSafeForQuickColony(civ, island))
        // Both a third now: the larger home first
        assertEquals(BestSettleAreas(2, home, island), SettleAreas.bestSettleAreas(civ, minimumFertility()))
    }

    @Test
    fun `best settle plot discounts distance and other landmasses`() {
        val civ = twoLandmasses()
        val settler = game.addUnit("Settler", civ, tile(-7, -3))
        val scores = HashMap<Tile, Int>()
        val best = SettleAreas.bestSettlePlot(settler, escorted = false, area = -1, targetedSites = emptyList(), scores = scores)
        assertNotNull(best)
        // Unescorted on turn 0: evaluation distance (24 + 0) x 2/3 = 16 (the map is smaller than 80x52)
        val evalDistance = 24 * 2 / 3
        var sawIsland = false
        for ((tile, score) in scores) {
            val foundValue = SettleAreas.foundValue(civ, tile)
            assertTrue(foundValue > 5000)
            var expected = foundValue * (100 - min(99, 67 * tile.aerialDistanceTo(settler.getTile()) / evalDistance)) / 100
            if (tile.getContinent() != settler.getTile().getContinent()) {
                expected = expected * 2 / 3
                sawIsland = true
            }
            assertEquals(expected, score)
        }
        assertTrue(sawIsland)
        assertEquals(scores.maxBy { it.value }.key, best)

        // A site another settler targets blocks plots within 2 of it
        val other = SettleAreas.bestSettlePlot(settler, escorted = false, area = -1, targetedSites = listOf(best!!))
        assertTrue(other!!.aerialDistanceTo(best) > 2)
        // Restricted to an area
        val onIsland = SettleAreas.bestSettlePlot(settler, escorted = false, area = tile(7, 7).getContinent(), targetedSites = emptyList())
        assertEquals(tile(7, 7).getContinent(), onIsland!!.getContinent())
    }

    @Test
    fun `best settle plot skips plots next to another player's territory`() {
        val civ = twoLandmasses()
        val neighbor = civ()
        game.addCity(neighbor, tile(7, 7))
        val settler = game.addUnit("Settler", civ, tile(-7, -3))
        val scores = HashMap<Tile, Int>()
        SettleAreas.bestSettlePlot(settler, escorted = false, area = -1, targetedSites = emptyList(), scores = scores)
        assertTrue(scores.keys.none { tile -> tile.neighbors.any { it.getOwner() == neighbor } })
    }

    @Test
    fun `a settler whose new site is out of reach ends its operation instead of moving`() {
        val civ = twoLandmasses()
        val island = tile(7, 7).getContinent()
        val settler = game.addUnit("Settler", civ, tile(-7, -3))
        // A quick colony of the island (no embarking yet) whose site was lost: the best site left is on the island too
        settler.settlerOperation = SettlerOperation().apply { foundCity = false; area = island; target = HexCoord(0, 0) }
        SettlerAutomation.automateSettler(GameView(game.gameInfo, civ).getMapUnitView(settler), AutomationCache())
        assertNull("AI_ABORT_LOST_PATH", settler.settlerOperation)
        assertEquals(tile(-7, -3), settler.getTile())
    }
}
