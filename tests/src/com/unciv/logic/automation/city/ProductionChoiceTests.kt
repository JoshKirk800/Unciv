package com.unciv.logic.automation.city

import com.unciv.logic.automation.strategy.ProductionAi
import com.unciv.logic.automation.strategy.WonderProductionAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Decision-level checks of the CvCityStrategyAI::ChooseProduction / Cv*ProductionAI / CvCityAI::AI_chooseProduction port (BNW XML values) */
@RunWith(GdxTestRunner::class)
class ProductionChoiceTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 0)
        city = game.addCity(civ, game.tileMap[0, 0])
    }

    @Test
    fun `building weight is the XML flavors times the city flavors, negative city flavors as 0`() {
        civ.ai.setPersonalityFlavor(Flavor.Culture, 7)
        civ.ai.setPersonalityFlavor(Flavor.GreatPeople, 3)
        // Building_Flavors: Writers' Guild CULTURE 90; Amphitheater CULTURE 20, GREAT_PEOPLE 2
        assertEquals(90 * 7, ProductionAi.buildingWeight(city, game.ruleset.buildings["Writers' Guild"]!!))
        assertEquals(20 * 7 + 2 * 3, ProductionAi.buildingWeight(city, game.ruleset.buildings["Amphitheater"]!!))
        // The settler pump has CULTURE -4: city culture 3 - 4 = -1 counts 0, not -20
        civ.ai.setPersonalityFlavor(Flavor.Culture, 3)
        city.ai.changeSpecialization("CITYSPECIALIZATION_SETTLER_PUMP")
        assertEquals(2 * 3, ProductionAi.buildingWeight(city, game.ruleset.buildings["Amphitheater"]!!))
    }

    @Test
    fun `units, projects and processes use their own flavor tables`() {
        civ.ai.setPersonalityFlavor(Flavor.TileImprovement, 4)
        civ.ai.setPersonalityFlavor(Flavor.Spaceship, 2)
        civ.ai.setPersonalityFlavor(Flavor.Science, 3)
        civ.ai.setPersonalityFlavor(Flavor.Gold, 6)
        // Unit_Flavors: Worker TILE_IMPROVEMENT 30
        assertEquals(30 * 4, ProductionAi.unitWeight(city, game.ruleset.units["Worker"]!!))
        // Project_Flavors: Apollo Program SPACESHIP 250, SCIENCE 25, WONDER 10
        val apollo = game.ruleset.buildings["Apollo Program"]!!
        assertTrue(ProductionAi.isProject(game.ruleset.ai, apollo))
        assertEquals(250 * 2 + 25 * 3, ProductionAi.projectWeight(city, apollo))
        // Process_Flavors: Wealth GOLD 5, DIPLOMACY 5; Research SCIENCE 4, SPACESHIP 5, NUKE 1
        assertEquals(5 * 6, ProductionAi.processWeight(city, PerpetualConstruction.Gold.name))
        assertEquals(4 * 3 + 5 * 2, ProductionAi.processWeight(city, PerpetualConstruction.Science.name))
    }

    @Test
    fun `time reweighting divides by turns to the power 0_15 plus 0_015 per turn`() {
        // AI_PRODUCTION_WEIGHT_BASE_MOD 0.15, AI_PRODUCTION_WEIGHT_MOD_PER_TURN_LEFT 0.015 (XML; the DLL comment's 0.004 is stale)
        assertEquals(1000, WonderProductionAi.reweightByTurnsLeft(civ, 1000, 1))
        assertEquals(501, WonderProductionAi.reweightByTurnsLeft(civ, 1000, 10)) // 1000 / 10^0.30
        assertEquals(62, WonderProductionAi.reweightByTurnsLeft(civ, 1000, 40)) // 1000 / 40^0.75
    }

    @Test
    fun `the pick is a weighted roll among the top two on Prince`() {
        val automation = ConstructionAutomation(city.cityConstructions)
        val counts = HashMap<String, Int>()
        for (turn in 1..300) {
            game.gameInfo.turns = turn
            val candidates = listOf(
                ConstructionAutomation.Buildable(PerpetualConstruction.Culture, 100, 1),
                ConstructionAutomation.Buildable(PerpetualConstruction.Gold, 300, 1),
                ConstructionAutomation.Buildable(PerpetualConstruction.Science, 200, 1),
            )
            val chosen = automation.pickFromTopChoices(candidates)!!.construction.name
            counts[chosen] = (counts[chosen] ?: 0) + 1
        }
        assertEquals(null, counts[PerpetualConstruction.Culture.name])
        val gold = counts[PerpetualConstruction.Gold.name] ?: 0
        val science = counts[PerpetualConstruction.Science.name] ?: 0
        // 300 : 200 - both happen, the heavier more often
        assertTrue("gold $gold science $science", science > 60 && gold > science)
    }

    @Test
    fun `the time penalty can reorder the top choices`() {
        val automation = ConstructionAutomation(city.cityConstructions)
        // 1000 over 40 turns is 62, 100 over 1 turn stays 100: only the quick item and the 80 are in the top two
        val candidates = listOf(
            ConstructionAutomation.Buildable(PerpetualConstruction.Culture, 1000, 40),
            ConstructionAutomation.Buildable(PerpetualConstruction.Gold, 100, 1),
            ConstructionAutomation.Buildable(PerpetualConstruction.Science, 80, 1),
        )
        for (turn in 1..50) {
            game.gameInfo.turns = turn
            val chosen = automation.pickFromTopChoices(candidates.map { ConstructionAutomation.Buildable(it.construction, it.weight, it.turns) })!!
            assertTrue(chosen.construction != PerpetualConstruction.Culture)
        }
    }

    private fun makeWonderCity() = game.createWonder().apply { cost = 100 }.also { wonder ->
        civ.ai.wonderCityId = city.id
        civ.ai.citySpecialization.nextWonderDesired = wonder.name
    }

    @Test
    fun `the wonder city starts the next wonder when a 0-10 roll is at most the wonder flavor`() {
        val wonder = makeWonderCity()
        // max(WONDER, GREAT_PEOPLE): 10 passes every roll 0..10
        civ.ai.setPersonalityFlavor(Flavor.GreatPeople, 10)
        for (turn in 1..40) {
            game.gameInfo.turns = turn
            city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Idle.name)
            ConstructionAutomation(city.cityConstructions).chooseNextConstruction()
            assertEquals(wonder.name, city.cityConstructions.currentConstructionName())
        }
        // flavor 0: only a roll of 0 starts it (1 in 11); nothing else has weight, so otherwise the city stays idle
        civ.ai.setPersonalityFlavor(Flavor.GreatPeople, 0)
        var started = 0
        for (turn in 1..220) {
            game.gameInfo.turns = turn
            city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Idle.name)
            ConstructionAutomation(city.cityConstructions).chooseNextConstruction()
            if (city.cityConstructions.currentConstructionName() == wonder.name) started++
        }
        assertTrue("started $started of 220", started in 5..45)
    }

    @Test
    fun `a wonder the wonder city cannot build any more makes the specializations dirty`() {
        makeWonderCity()
        civ.ai.citySpecialization.nextWonderDesired = "Pyramids" // needs Masonry
        civ.ai.citySpecialization.specializationsDirty = false
        city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Idle.name)
        ConstructionAutomation(city.cityConstructions).chooseNextConstruction()
        assertTrue(civ.ai.citySpecialization.specializationsDirty)
    }

    @Test
    fun `turns left are those of the production the city makes building that very item, rounded as the game adds it`() {
        civ.ai.setPersonalityFlavor(Flavor.Culture, 5)
        // CvCity::getProductionTurnsLeft: the base production with the modifiers of that item - here +20% for everything and
        // +100% more for wonders - and the production left over that, not the city's current production for every item
        val workshop = game.createBuilding("[+20]% [Production]", "[+100]% Production when constructing [All] wonders [in this city]")
            .apply { production = 4f }
        city.cityConstructions.addBuilding(workshop.name)
        city.cityStats.update()
        val building = game.createBuilding().apply { cost = 60; culture = 1f }
        val wonder = game.createWonder().apply { cost = 60; culture = 1f }

        val base = city.cityStats.baseStatTree.totalStats.production
        assertTrue("base production $base", base >= 4f && base * 1.2f % 1f > 0.5f) // truncating 4.8 to 4 would be wrong
        fun turns(cost: Int, modifier: Float) = Math.ceil(cost / Math.round(base * modifier).toDouble()).toInt()

        val candidates = ConstructionAutomation(city.cityConstructions).getBuildables().associateBy { it.construction.name }
        assertEquals(turns(60, 1.2f), candidates[building.name]!!.turns)
        assertEquals(turns(60, 2.2f), candidates[wonder.name]!!.turns)
    }

    @Test
    fun `puppets avoid wonders and double buildings without maintenance`() {
        civ.ai.setPersonalityFlavor(Flavor.Culture, 5)
        val other = game.addCiv()
        val puppet = game.addCity(other, game.tileMap[2, 0])
        puppet.puppetCity(civ)
        val cheap = game.createBuilding().apply { cost = 30; culture = 1f; maintenance = 0 }
        val upkeep = game.createBuilding().apply { cost = 30; culture = 1f; maintenance = 1 }
        val wonder = game.createWonder().apply { cost = 30; culture = 1f }
        val weights = ConstructionAutomation(puppet.cityConstructions).getBuildables().associate { it.construction.name to it.weight }
        // derived flavors: CULTURE 10 per culture point
        assertEquals(2 * 10 * 5, weights[cheap.name])
        assertEquals(10 * 5, weights[upkeep.name])
        assertEquals(null, weights[wonder.name])
    }
}
