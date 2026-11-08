package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.automation.civilization.CityConquestAutomation
import com.unciv.logic.automation.civilization.CityConquestAutomation.ConquestChoice
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.diplomacy.WarGoal
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.ai.PlayerStrategyDef
import com.unciv.models.ruleset.tile.ResourceType
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

/** Decision-level checks of the CvEconomicAI, CvMilitaryAI and CvPlayerAI ports against the DLL formulas (BNW XML values, Prince = handicap 3) */
@RunWith(GdxTestRunner::class)
class EconomicMilitaryAiTests {
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(6, Constants.grassland)
    }

    /** A major whose personality is all zero except what the test sets */
    private fun neutralCiv(): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        return civ
    }

    private fun economicDef(name: String): PlayerStrategyDef = game.ruleset.ai.economicStrategies.first { it.name == name }
    private fun militaryDef(name: String): PlayerStrategyDef = game.ruleset.ai.militaryStrategies.first { it.name == name }

    private fun tile(x: Int, y: Int) = game.tileMap[HexCoord(x, y)]

    //region Savings and gold

    @Test
    fun `higher priority savings reserve gold from lower priority purchases`() {
        val civ = neutralCiv()
        civ.addGold(350)
        val savings = civ.ai.purchaseSavings
        savings.startSaveForPurchase(PurchaseType.UnitUpgrade, 300, 400)
        // The tile at priority 275 only sees 350 - 300 = 50
        assertFalse(savings.canWithdrawMoneyForPurchase(civ, PurchaseType.Tile, 100, 275))
        // Raising the tile's priority above the upgrade frees the whole treasury
        assertTrue(savings.canWithdrawMoneyForPurchase(civ, PurchaseType.Tile, 100, 500))
        // A city-state gift (priority 0) comes after both: the tile request reserves 0, the upgrade 300
        assertEquals(50, savings.amountAvailableForPurchase(civ, PurchaseType.MinorCivGift))
        // Reaching 0 before the purchase's turn means nothing is available, even for a free purchase
        civ.addGold(-50)
        assertEquals(0, savings.amountAvailableForPurchase(civ, PurchaseType.MinorCivGift))
        assertFalse(savings.canWithdrawMoneyForPurchase(civ, PurchaseType.MinorCivGift, 0))
    }

    private fun spearmenReadyToUpgrade(civ: Civilization, count: Int): City {
        val city = game.addCity(civ, tile(0, 0))
        civ.tech.addTechnology("Bronze Working")
        civ.tech.addTechnology("Civil Service")
        val tiles = listOf(tile(0, 0)) + tile(0, 0).neighbors.toList()
        for (i in 0 until count) game.addUnit("Spearman", civ, tiles[i])
        return city
    }

    @Test
    fun `upgrades are capped at a third of the military training flavor`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.MilitaryTraining, 6)
        spearmenReadyToUpgrade(civ, 4)
        civ.addGold(10000)
        // max(1, 6 / 3) + max(0, Prince 3 - 5) = 2
        assertEquals(2, UseGoldAutomation.maxUpgradesPerTurn(civ))
        assertEquals(2, UseGoldAutomation.plotUpgradeMoves(civ).upgraded)
        assertEquals(2, civ.units.getCivUnits().count { it.name == "Pikeman" })
        assertEquals(2, civ.units.getCivUnits().count { it.name == "Spearman" })
    }

    @Test
    fun `without gold for an upgrade the AI saves for it at the military training priority`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.MilitaryTraining, 6)
        spearmenReadyToUpgrade(civ, 1)
        assertEquals(0, UseGoldAutomation.plotUpgradeMoves(civ).upgraded)
        val request = civ.ai.purchaseSavings.requests[PurchaseType.UnitUpgrade.name]!!
        // AI_GOLD_PRIORITY_UPGRADE_BASE 150 + AI_GOLD_PRIORITY_UPGRADE_PER_FLAVOR_POINT 25 x current MILITARY_TRAINING 6
        assertEquals(300, request.priority)
        assertTrue(request.amount > 0)
    }

    @Test
    fun `the plot score needed drops as the treasury approaches the halving balance`() {
        val civ = neutralCiv()
        civ.addGold(50)
        // no purchase unless the gold exceeds the cost
        assertNull(UseGoldAutomation.minimumPlotBuyScore(civ, 50))
        civ.addGold(475)
        // discount 50 x (525 - 50) / (1000 - 50) = 25%: 275 - 68
        assertEquals(207, UseGoldAutomation.minimumPlotBuyScore(civ, 50))
        // a cost at or above AI_GOLD_BALANCE_TO_HALVE_PLOT_BUY_MINIMUM: no purchase
        civ.addGold(1000)
        assertNull(UseGoldAutomation.minimumPlotBuyScore(civ, 1000))
    }

    @Test
    fun `plot score values a luxury the civilization lacks twice`() {
        val civ = neutralCiv()
        val city = game.addCity(civ, tile(0, 0))
        val plain = tile(2, 0)
        val withLuxury = tile(-2, 0)
        val luxury = game.createResource().apply { resourceType = ResourceType.Luxury } // no yields of its own
        withLuxury.setTileResource(luxury.name)
        val difference = UseGoldAutomation.individualPlotScore(city, withLuxury) - UseGoldAutomation.individualPlotScore(city, plain)
        // AI_PLOT_VALUE_LUXURY_RESOURCE 40, doubled when the civilization has none of it; same distance, so same cost
        assertEquals(80, difference)
    }
    //endregion

    //region Military sizing

    @Test
    fun `recommended army is a threat and flavor scaled reserve plus strike force`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Offense, 5)
        civ.ai.setPersonalityFlavor(Flavor.Defense, 5)
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 6)
        game.addCity(civ, tile(0, 0))
        game.addCity(civ, tile(4, 0))
        val military = civ.ai.military
        military.updateBaseData(civ)
        // multiplier 0.40 + (threat 0 + 5 + 5) / 100 = 0.5; reserve (3 + 2 cities) x 0.5 = 2, + Prince 3 - 1 = 4
        assertEquals(4, military.mandatoryReserveSize)
        // strike force (7 + boldness 6 + 2) x 0.5 = 7
        assertEquals(11, military.recommendedMilitarySize)
        civ.ai.grandStrategy = GrandStrategyAi.CONQUEST
        military.updateBaseData(civ)
        // conquest doubles the basic attack: (14 + 6 + 2) x 0.5 = 11
        assertEquals(15, military.recommendedMilitarySize)
    }

    @Test
    fun `land units are counted by role and the defense state follows reserve and recommended size`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Offense, 5)
        civ.ai.setPersonalityFlavor(Flavor.Defense, 5)
        civ.ai.setDiplomacyValue(DiplomacyPersonality.Boldness, 6)
        game.addCity(civ, tile(0, 0))
        game.addCity(civ, tile(4, 0))
        val free = game.tileMap.values.filter { !it.isCityCenter() }.iterator()
        game.addUnit("Archer", civ, free.next())
        game.addUnit("Horseman", civ, free.next())
        game.addUnit("Warrior", civ, free.next())
        val military = civ.ai.military
        military.doTurnBeforeStrategies(civ)
        assertEquals(1, military.numRangedLandUnits)
        assertEquals(1, military.numMobileLandUnits)
        assertEquals(1, military.numMeleeLandUnits)
        // 3 land units < reserve 4
        assertEquals(DefenseState.Critical, military.landDefenseState)
        repeat(9) { game.addUnit("Warrior", civ, free.next()) }
        military.doTurnBeforeStrategies(civ)
        // 12: at least 11 recommended, below 11 x 5/4 = 13
        assertEquals(DefenseState.Neutral, military.landDefenseState)
        // explorers do not count
        repeat(2) { UnitAiTypes.set(civ.units.getCivUnits().first { u -> u.name == "Warrior" && UnitAiTypes.current(u) != UnitAiType.EXPLORE }, UnitAiType.EXPLORE) }
        military.doTurnBeforeStrategies(civ)
        assertEquals(DefenseState.Needed, military.landDefenseState)
    }

    @Test
    fun `recommended navy grows with coastal cities, the naval flavor and conquest over time`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Naval, 10)
        for (position in listOf(HexCoord(1, 0), HexCoord(5, 0))) game.setTileTerrain(position, Constants.coast)
        for (t in game.tileMap.values) t.setTerrainTransients()
        game.addCity(civ, tile(0, 0))
        game.addCity(civ, tile(4, 0))
        assertTrue(civ.cities.all { it.isCoastal() })
        // (1 + 2) x (0.75 + 0 / 4 + 10 / 40) = 3
        assertEquals(3, MilitaryAi.recommendedNavySize(civ))
        civ.ai.grandStrategy = GrandStrategyAi.CONQUEST
        game.gameInfo.turns = 100
        // + 10 x min(turn, 200) / 200
        assertEquals(8, MilitaryAi.recommendedNavySize(civ))
    }

    @Test
    fun `unit mix strategies compare the share of a role with the flavor`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Ranged, 6)
        // 1 x 10 / 5 = 2 <= 6 / 2: need ranged
        assertTrue(EconomicMilitaryStrategyTests.needUnits(civ, Flavor.Ranged, 1, 4))
        // 2 x 10 / 5 = 4 > 3
        assertFalse(EconomicMilitaryStrategyTests.needUnits(civ, Flavor.Ranged, 2, 3))
        // enough at a share of 6 tenths, not at 5
        assertTrue(EconomicMilitaryStrategyTests.enoughUnits(civ, Flavor.Ranged, 6, 4))
        assertFalse(EconomicMilitaryStrategyTests.enoughUnits(civ, Flavor.Ranged, 5, 5))
        // the grand strategy's flavor mod counts (GetPersonalityAndGrandStrategy)
        civ.ai.grandStrategy = GrandStrategyAi.CONQUEST
        val conquestRanged = civ.ai.grandStrategyDef()!!.flavorModVector[Flavor.Ranged.ordinal]
        assertEquals(6 + conquestRanged, civ.ai.personalityAndGrandStrategy(Flavor.Ranged))
    }
    //endregion

    //region Strategy tests

    @Test
    fun `war mobilization weighs conquest, war preparations and hostile approaches`() {
        val civ = neutralCiv()
        val preparedAgainst = game.addCiv()
        val guarded = game.addCiv()
        val hostile = game.addCiv()
        civ.ai.diplomacy.memoryOf(preparedAgainst).warGoal = WarGoal.Prepare
        civ.ai.diplomacy.memoryOf(preparedAgainst).approach = MajorCivApproach.Hostile // ignored: preparing counts instead
        civ.ai.diplomacy.memoryOf(guarded).approach = MajorCivApproach.Guarded
        civ.ai.diplomacy.memoryOf(hostile).approach = MajorCivApproach.Afraid
        civ.ai.grandStrategy = GrandStrategyAi.CONQUEST
        assertEquals(25 + 100 + 25 + 50, EconomicMilitaryStrategyTests.warMobilizationWeight(civ))
    }

    @Test
    fun `an army being built for a sneak or city-state attack turns war mobilization on for good`() {
        val civ = neutralCiv()
        game.addCiv()
        val threshold = militaryDef(EconomicMilitaryStrategyTests.WAR_MOBILIZATION).weightThreshold
        assertTrue(EconomicMilitaryStrategyTests.warMobilizationWeight(civ) < threshold)
        civ.ai.military.requestArmy(bySea = false)
        assertTrue(EconomicMilitaryStrategyTests.warMobilizationWeight(civ) >= threshold)
        civ.ai.military.numLandAttacksRequested = 0
        assertTrue("the original never clears the army type", EconomicMilitaryStrategyTests.warMobilizationWeight(civ) >= threshold)
    }

    @Test
    fun `barbarian camps near our cities make eradication worthwhile unless a war is being prepared`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Offense, 5)
        civ.ai.setPersonalityFlavor(Flavor.Defense, 5)
        val other = game.addCiv()
        game.addCity(civ, tile(0, 0))
        val camp = tile(3, 0)
        camp.setImprovementBasic(Constants.barbarianEncampment)
        camp.setExplored(civ, true)
        assertTrue(camp.isBarbarianEncampment())
        val military = civ.ai.military
        military.scanForBarbarians(civ)
        // a camp within 10 tiles of a city counts 5
        assertEquals(5, military.barbarianCampCount)
        val def = militaryDef("MILITARYAISTRATEGY_ERADICATE_BARBARIANS")
        // 5 x 50 = 250 >= 200 + (-2 x 5 + 2 x 5)
        assertTrue(EconomicMilitaryStrategyTests.eradicateBarbarians(civ, def))
        civ.ai.diplomacy.memoryOf(other).warGoal = WarGoal.Prepare
        assertFalse(EconomicMilitaryStrategyTests.eradicateBarbarians(civ, def))
    }

    @Test
    fun `a barbarian camp operation that is running keeps eradication on after the camp threat has dropped below the threshold`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Offense, 5)
        civ.ai.setPersonalityFlavor(Flavor.Defense, 5)
        game.addCity(civ, tile(0, 0))
        val camp = tile(3, 0)
        camp.setImprovementBasic(Constants.barbarianEncampment)
        camp.setExplored(civ, true)
        civ.ai.military.scanForBarbarians(civ)
        val def = militaryDef("MILITARYAISTRATEGY_ERADICATE_BARBARIANS")
        civ.ai.militaryStrategies[def.name] = 0
        MilitaryOperations.updateOperations(civ)
        assertEquals(1, civ.ai.operations.numOperationsOfType(AiOperationType.DESTROY_BARBARIAN_CAMP))

        camp.removeImprovement()
        civ.ai.military.scanForBarbarians(civ)
        assertEquals(0, civ.ai.military.barbarianCampCount)
        assertTrue(EconomicMilitaryStrategyTests.eradicateBarbarians(civ, def))
        civ.ai.operations.killAll(AiOperationType.DESTROY_BARBARIAN_CAMP, OperationAbortReason.WAR_STATE_CHANGE)
        assertFalse(EconomicMilitaryStrategyTests.eradicateBarbarians(civ, def))
    }

    @Test
    fun `need happiness uses half the happiness personality as margin, critical a fixed -3`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Happiness, 8)
        game.addCity(civ, tile(0, 0), initialPopulation = 3)
        val need = economicDef("ECONOMICAISTRATEGY_NEED_HAPPINESS")
        val critical = economicDef("ECONOMICAISTRATEGY_NEED_HAPPINESS_CRITICAL")
        civ.stats.happiness = 4
        assertTrue(EconomicMilitaryStrategyTests.needHappiness(civ, need))   // 4 <= 8 x 1 / 2
        civ.stats.happiness = 5
        assertFalse(EconomicMilitaryStrategyTests.needHappiness(civ, need))
        assertFalse(EconomicMilitaryStrategyTests.needHappinessCritical(civ, critical))
        civ.stats.happiness = -3
        assertTrue(EconomicMilitaryStrategyTests.needHappinessCritical(civ, critical))
        civ.stats.happiness = -2
        assertFalse(EconomicMilitaryStrategyTests.needHappinessCritical(civ, critical))
    }

    @Test
    fun `expand like crazy follows the expansion flavor but not a culture victory on maps where cities cost policies`() {
        val civ = neutralCiv()
        val def = economicDef("ECONOMICAISTRATEGY_EXPAND_LIKE_CRAZY")
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 9)
        assertFalse(EconomicMilitaryStrategyTests.expandLikeCrazy(civ, def))
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 10)
        assertTrue(EconomicMilitaryStrategyTests.expandLikeCrazy(civ, def))
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        // 20 - 10 (the culture grand strategy's EXPANSION mod) would pass the threshold, but cities raise policy costs on this map size
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 20)
        assertTrue(civ.ai.personalityAndGrandStrategy(Flavor.Expansion) >= def.weightThreshold)
        assertFalse(EconomicMilitaryStrategyTests.expandLikeCrazy(civ, def))
    }

    @Test
    fun `early expansion wants cities by the expansion to growth ratio scaled by map size`() {
        val civ = neutralCiv()
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 20)
        civ.ai.setPersonalityFlavor(Flavor.Growth, 1)
        val tiles = game.tileMap.values.size
        // 8 x 20 / 1 + max(0, Prince 3 - 3), x tiles / (80 x 52)
        assertEquals(160 * tiles / 4160, EconomicMilitaryStrategyTests.desiredEarlyCities(civ))
        civ.ai.setPersonalityFlavor(Flavor.Growth, 4)
        assertEquals(40 * tiles / 4160, EconomicMilitaryStrategyTests.desiredEarlyCities(civ))
    }

    @Test
    fun `recon weight and threshold follow the fog formula`() {
        val civ = neutralCiv()
        // 100 x 50 / (1 + 1)^2 / (int)sqrt(120)
        assertEquals(5000 / 4 / 10, EconomicAi.reconWeight(civ, 50, 120, 1))
        civ.ai.setPersonalityFlavor(Flavor.Recon, 3)
        assertEquals(80, EconomicAi.reconThreshold(civ, Flavor.Recon))
        civ.ai.setPersonalityFlavor(Flavor.Recon, 0)
        assertEquals(100, EconomicAi.reconThreshold(civ, Flavor.Recon))
    }

    @Test
    fun `with enough recon explorers go back to attack, keeping one before turn 100`() {
        val civ = neutralCiv()
        game.makeHexagonalMap(2, Constants.grassland)
        game.addCity(civ, tile(0, 0))
        for (t in game.tileMap.values) t.setExplored(civ, true)
        val first = game.addUnit("Warrior", civ, tile(1, 0))
        val second = game.addUnit("Warrior", civ, tile(-1, 0))
        UnitAiTypes.set(first, UnitAiType.EXPLORE)
        UnitAiTypes.set(second, UnitAiType.EXPLORE)
        game.gameInfo.turns = 50
        civ.ai.economic.doReconState(civ)
        assertEquals(ReconState.Enough, civ.ai.economic.reconState)
        assertEquals(1, civ.units.getCivUnits().count { UnitAiTypes.current(it) == UnitAiType.EXPLORE })
        game.gameInfo.turns = 120
        civ.ai.economic.doReconState(civ)
        assertEquals(0, civ.units.getCivUnits().count { UnitAiTypes.current(it) == UnitAiType.EXPLORE })
    }

    @Test
    fun `city-state status is critical with a neighbor at war and normal with a close peaceful one`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val major = neutralCiv()
        game.addCity(cityState, tile(0, 0))
        game.addCity(major, tile(4, 0))
        major.diplomacyFunctions.makeCivilizationsMeet(cityState)
        major.proximity[cityState.civID] = Proximity.Neighbors
        assertEquals(MinorCivStatus.Normal, MinorCivStatusAi.status(cityState))
        for (civ in listOf(major, cityState)) civ.getDiplomacyManager(if (civ == major) cityState else major)!!.diplomaticStatus = DiplomaticStatus.War
        // NEIGHBORS at war: +20
        assertEquals(MinorCivStatus.Critical, MinorCivStatusAi.status(cityState))
        // CLOSE counts only minor-civ aggressors, not the war itself
        major.proximity[cityState.civID] = Proximity.Close
        assertEquals(MinorCivStatus.Normal, MinorCivStatusAi.status(cityState))
    }
    //endregion

    //region Player decisions

    private fun conquestSetup(): Triple<Civilization, Civilization, City> {
        val attacker = neutralCiv()
        val defender = neutralCiv()
        game.addCity(attacker, tile(-4, 0))
        game.addCity(defender, tile(4, 0))
        val target = game.addCity(defender, tile(0, 0))
        attacker.stats.happiness = 5
        return Triple(attacker, defender, target)
    }

    @Test
    fun `conquered cities are puppeted when happy and razed when unhappy`() {
        val (attacker, _, target) = conquestSetup()
        assertEquals(ConquestChoice.Puppet, CityConquestAutomation.chooseConquest(attacker, target))
        attacker.stats.happiness = -1
        assertEquals(ConquestChoice.Raze, CityConquestAutomation.chooseConquest(attacker, target))
    }

    @Test
    fun `retaken own cities are annexed`() {
        val (attacker, _, target) = conquestSetup()
        target.foundingCivObject = attacker
        attacker.stats.happiness = -1 // a city we founded is never razed
        assertEquals(ConquestChoice.Annex, CityConquestAutomation.chooseConquest(attacker, target))
    }

    @Test
    fun `city-state cities are liberated by friendly or protective conquerors and diplomats`() {
        val (attacker, _, target) = conquestSetup()
        val cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(cityState, tile(0, 4))
        target.foundingCivObject = cityState
        assertEquals(ConquestChoice.Puppet, CityConquestAutomation.chooseConquest(attacker, target))
        attacker.ai.minorCivApproaches[cityState.civID] = MinorCivApproach.Protective
        assertEquals(ConquestChoice.Liberate, CityConquestAutomation.chooseConquest(attacker, target))
        attacker.ai.minorCivApproaches[cityState.civID] = MinorCivApproach.Bully
        attacker.ai.grandStrategy = GrandStrategyAi.UNITED_NATIONS
        assertEquals(ConquestChoice.Liberate, CityConquestAutomation.chooseConquest(attacker, target))
    }

    @Test
    fun `annexing takes the best producing puppet in the top half, never under the culture grand strategy`() {
        val civ = neutralCiv()
        civ.stats.happiness = 5
        val capital = game.addCity(civ, tile(0, 0))
        val strong = game.addCity(civ, tile(4, 0))
        val weak = game.addCity(civ, tile(-4, 0))
        val weakest = game.addCity(civ, tile(0, 4))
        for ((city, production) in listOf(capital to 10f, strong to 8f, weak to 3f, weakest to 1f)) {
            city.cityStats.currentCityStats.production = production
        }
        weak.isPuppet = true
        // only the top 50% (capital, strong) count: the puppet is third
        assertNull(CityConquestAutomation.considerAnnex(civ))
        strong.isPuppet = true
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        assertNull(CityConquestAutomation.considerAnnex(civ))
        civ.ai.grandStrategy = null
        assertEquals(strong, CityConquestAutomation.considerAnnex(civ))
        assertFalse(strong.isPuppet)
        assertTrue(weak.isPuppet)
    }
    //endregion

    //region Strategy tests

    @Test
    fun `found city counts only loose settlers, not those an operation already moves`() {
        game.makeHexagonalMap(12, Constants.grassland)
        val civ = neutralCiv()
        game.tileMap.assignContinents(TileMap.AssignContinentsMode.Reassign)
        civ.ai.setPersonalityFlavor(Flavor.Growth, 5)
        civ.ai.setPersonalityFlavor(Flavor.Expansion, 3)
        civ.viewableTiles = game.tileMap.values.toSet()
        for (tile in game.tileMap.values) tile.setExplored(civ, true)
        game.addCity(civ, tile(0, 0))
        assertFalse(EconomicMilitaryStrategyTests.foundCity(civ))
        val settler = game.addUnit("Settler", civ, tile(2, 0))
        assertTrue(EconomicMilitaryStrategyTests.foundCity(civ))
        val operation = AiOperationLogic.addSettlerOperation(civ, AiOperationType.FOUND_CITY, settler, -1)
        assertNotNull(civ.ai.operations.armyOf(settler))
        assertFalse("aborted: ${operation.abortReason}", operation.state == OperationState.ABORTED)
        assertFalse(EconomicMilitaryStrategyTests.foundCity(civ))
    }
    //endregion
}
