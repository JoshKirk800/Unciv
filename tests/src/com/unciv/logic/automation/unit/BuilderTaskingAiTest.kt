package com.unciv.logic.automation.unit

import com.unciv.logic.automation.unit.BuilderTaskingAi.CityYieldTargets
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapPathing
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.max
import kotlin.math.min

/**
 *  Decision-level checks of the CvBuilderTaskingAI port ([BuilderTaskingAi]) against the DLL formulas with the BNW XML values
 *  (BUILD_ROUTES 100, LUXURY_RESOURCE 750, luxury happiness 4, road maintenance 1, railroad 2).
 */
@RunWith(GdxTestRunner::class)
class BuilderTaskingAiTest {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        for (tile in game.tileMap.values) game.setTileTerrain(tile.position, "Grassland")
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
    }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]
    private fun research(vararg techs: String) { for (tech in techs) civ.tech.techsResearched.add(tech) }
    private fun improvement(name: String) = game.ruleset.tileImprovements[name]!!
    private fun yields(vararg values: Int) = values.copyOf(BuilderTaskingAi.YIELDS.size)
    private fun targets(vararg deltas: Int, focus: Stat? = null) =
        CityYieldTargets(deltas.copyOf(BuilderTaskingAi.YIELDS.size), focus?.let { BuilderTaskingAi.YIELDS.indexOf(it) } ?: -1)

    @Test
    fun `score plot multiplies a yield the city lacks by its deficit and charges any loss`() {
        // Food short by 0.5 (-50): raising food from 2 to 3 scores the projected 3 x 50; production (not short) a nominal 0
        assertEquals(150, BuilderTaskingAi.scoreYields(yields(2, 0), yields(3, 0), targets(-50, 30, focus = Stat.Production)))
        // Losing a food the city lacks costs 1 x 50; production not short scores its projected 2; a short yield blocks the focus bonus
        assertEquals(-48, BuilderTaskingAi.scoreYields(yields(2, 1), yields(1, 2), targets(-50, 30, focus = Stat.Production)))
        // Losing a yield the city has enough of costs the loss x its surplus
        assertEquals(3 - 20, BuilderTaskingAi.scoreYields(yields(2, 1), yields(3, 0), targets(10, 20, focus = Stat.Production)))
    }

    @Test
    fun `score plot adds the focus yield x100 only when no yield is short`() {
        assertEquals(2 + 1 + 100, BuilderTaskingAi.scoreYields(yields(2, 0), yields(2, 1), targets(10, 20, focus = Stat.Production)))
        assertEquals(2 + 1, BuilderTaskingAi.scoreYields(yields(2, 0), yields(2, 1), targets(10, 20, focus = null)))
        assertEquals(2 + 1, BuilderTaskingAi.scoreYields(yields(2, 1), yields(2, 1), targets(10, 20, focus = Stat.Production)))
    }

    @Test
    fun `city yield deltas average the best population + 2 plots against the default specialization`() {
        val city = game.addCity(civ, tile(0, 0))
        tile(1, 0).also { it.addTerrainFeature("Hill") }
        tile(0, 1).also { it.setTileResource("Wheat") }
        val ai = BuilderTaskingAi(civ)
        val targets = ai.cityYieldTargets(city)

        val owned = city.tilesInRange.filter { it.getCity() == city }
        val slots = min(owned.size, city.population.population + 2)
        val specialization = game.ruleset.ai.citySpecializations.first { it.isDefault }
        for ((i, stat) in BuilderTaskingAi.YIELDS.withIndex()) {
            var sum = owned.map { it.stats.getTileStats(city, civ)[stat].toInt() }.sortedDescending().take(slots).sum() * 100
            if (stat == Stat.Food) sum += (city.getCenterTile().stats.getTileStats(city, civ).food.toInt() - 2 +
                city.cityConstructions.getBuiltBuildings().sumOf { it.food.toInt() }) * 100
            assertEquals(stat.name, sum / slots - (specialization.targetYields[stat.name] ?: 0), targets.deltaTimes100[i])
        }
        assertEquals(BuilderTaskingAi.YIELDS.indexOfFirst { it.name == specialization.yieldType }, targets.focusYield)
    }

    @Test
    fun `the capital's plots score x8 and an original capital's x2`() {
        research("Agriculture")
        val city = game.addCity(civ, tile(0, 0))
        val plot = tile(1, 0)
        val farm = Build(improvement("Farm"))
        val asCapital = BuilderTaskingAi(civ).scorePlot(plot, farm)

        city.cityConstructions.removeBuilding("Palace")
        city.isOriginalCapital = false
        val plain = BuilderTaskingAi(civ).scorePlot(plot, farm)
        assertTrue("setup: a farm improves the plot", plain > 0)
        assertEquals(8 * plain, asCapital)
        city.isOriginalCapital = true
        assertEquals(2 * plain, BuilderTaskingAi(civ).scorePlot(plot, farm))
        // Not in one of our cities' reach
        assertEquals(-1, BuilderTaskingAi(civ).scorePlot(tile(6, 0), farm))
    }

    @Test
    fun `build time weight is 10000 over the turns left after this turn plus the extra time`() {
        research("Agriculture", "Bronze Working", "Mining")
        game.addCity(civ, tile(0, 0))
        val plot = tile(2, 0)
        val worker = game.addUnit("Worker", civ, plot)
        val ai = BuilderTaskingAi(civ)
        val farm = improvement("Farm")
        val farmTurns = farm.getTurnsToBuild(civ, worker, plot)
        assertEquals(10000 / (max(1, farmTurns - 1) + 2), ai.buildTimeWeight(worker, plot, Build(farm), false, 2))

        // Work already done counts: 2 turns left
        plot.queueImprovement("Farm", 2)
        assertEquals(10000 / (1 + 3), ai.buildTimeWeight(worker, plot, Build(farm), false, 3))

        // A farm on a forest removes the forest first: both times add up; helping a rush subtracts the removal in build points
        val forest = tile(-2, 0).also { it.addTerrainFeature("Forest") }
        val removeForest = improvement("Remove Forest")
        val total = removeForest.getTurnsToBuild(civ, worker, forest) + farm.getTurnsToBuild(civ, worker, forest)
        assertEquals(10000 / (total - 1 + 1), ai.buildTimeWeight(worker, forest, Build(farm, removeForest), false, 1))
        assertEquals(10000 / (total - 1 - removeForest.turnsToBuild * 100 + 1), ai.buildTimeWeight(worker, forest, Build(farm, removeForest), true, 1))
    }

    @Test
    fun `resource weight sums the flavor products and scales luxuries and known strategics`() {
        civ.ai.setPersonalityFlavor(Flavor.Happiness, 6)
        civ.ai.setPersonalityFlavor(Flavor.Production, 4)
        val rules = game.ruleset.ai
        val mine = improvement("Mine")
        fun flavorSum(resourceName: String): Int {
            val resourceFlavors = rules.flavorsOf(game.ruleset.tileResources[resourceName]!!)
            val mineFlavors = rules.flavorsOf(mine)
            val personality = civ.ai.personalityFlavorVector()
            return Flavor.entries.sumOf { max(0, resourceFlavors[it.ordinal] * personality[it.ordinal]) + max(0, personality[it.ordinal] * mineFlavors[it.ordinal]) }
        }
        val gems = game.ruleset.tileResources["Gems"]!!
        assertEquals(flavorSum("Gems") * 750 * 4, resourceWeight(civ, gems, mine, 1))

        val iron = game.ruleset.tileResources["Iron"]!!
        assertEquals("not revealed yet: no strategic multiplier", flavorSum("Iron"), resourceWeight(civ, iron, mine, 2))
        research("Bronze Working")
        assertEquals("2 iron x 200, x4 with none available", flavorSum("Iron") * 2 * 200 * 4, resourceWeight(civ, iron, mine, 2))
    }

    /** Capital at (0,0) and a city 4 plots east, both of [population], a worker at the capital */
    private fun twoCities(population: Int, distance: Int = 4): Pair<City, City> {
        research("The Wheel")
        val capital = game.addCity(civ, tile(0, 0), initialPopulation = population)
        val other = game.addCity(civ, tile(distance, 0), initialPopulation = population)
        game.addUnit("Worker", civ, capital.getCenterTile())
        return capital to other
    }

    private fun roadPlots(capital: City, other: City): List<Tile> =
        MapPathing.getRoadPath(civ, capital.getCenterTile(), other.getCenterTile())!!.filter { !it.isCityCenter() }

    @Test
    fun `a road is flagged when the connection gold pays its maintenance`() {
        val (capital, other) = twoCities(10)
        val plots = roadPlots(capital, other)
        val gold = other.cityStats.getTradeRouteGold().toInt()
        assertTrue("setup: profitable", gold - plots.size * 1 >= 0)
        val ai = BuilderTaskingAi(civ)
        val expected = gold * 100 / (plots.size * (1 + 1)) * plots.size / plots.size
        assertEquals(plots.toSet(), ai.routePlots.keys)
        for (plot in plots) {
            assertEquals(expected, ai.routePlots[plot]!!.value)
            assertEquals(RoadStatus.Road, ai.routePlots[plot]!!.route)
        }
    }

    @Test
    fun `a road that would not pay for itself is not built`() {
        twoCities(1)
        assertTrue(BuilderTaskingAi(civ).routePlots.isEmpty())
    }

    @Test
    fun `a profitable railroad gets the highest value`() {
        val (capital, other) = twoCities(10)
        research("Railroads")
        val ai = BuilderTaskingAi(civ)
        for (plot in roadPlots(capital, other)) {
            assertEquals(Short.MAX_VALUE.toInt(), ai.routePlots[plot]!!.value)
            assertEquals(RoadStatus.Railroad, ai.routePlots[plot]!!.route)
        }
    }

    @Test
    fun `a route directive weighs build routes over distance plus build time, times the route value`() {
        val (capital, other) = twoCities(10)
        val worker = capital.getCenterTile().civilianUnit!!
        val ai = BuilderTaskingAi(civ)
        val directive = ai.evaluateBuilder(worker) { false }
        assertNotNull(directive)
        directive!!
        assertEquals(BuilderDirective.Type.BuildRoute, directive.type)
        val distance = worker.getTile().aerialDistanceTo(directive.tile)
        assertEquals("the nearest road plot wins", roadPlots(capital, other).minOf { it.aerialDistanceTo(worker.getTile()) }, distance)
        val road = improvement("Road")
        val roadTurns = road.getTurnsToBuild(civ, worker, directive.tile)
        val expected = ((100 / (distance + 1)) * 100 + 10000 / (max(1, roadTurns - 1) + distance)) * ai.routePlots[directive.tile]!!.value
        assertEquals(expected, directive.weight)
    }
}
