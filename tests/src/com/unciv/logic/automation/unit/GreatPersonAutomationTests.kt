package com.unciv.logic.automation.unit

import com.unciv.logic.automation.diplomacy.DisputeLevel
import com.unciv.logic.automation.diplomacy.WarFace
import com.unciv.logic.automation.diplomacy.WarGoal
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.automation.strategy.StateAllWars
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WonderProductionAi
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.managers.PublicOpinion
import com.unciv.logic.civilization.managers.ReligionState
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.GreatWorkSlotType
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.unit.BaseUnit
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

/** Decision-level checks of the Great People directives (CvPlayerAI::GetDirective*) and free Great Person choices against the DLL, BNW data */
@RunWith(GdxTestRunner::class)
class GreatPersonAutomationTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City
    private var endTurn = 0

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(7)
        game.gameInfo.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys)
        civ = neutralCiv()
        city = game.addCity(civ, tile(0, 0), replacePalace = true) // the BNW Palace has an art slot
        endTurn = GreatPersonAutomation.estimateEndTurn(game.gameInfo)
        game.gameInfo.turns = 1
    }

    private fun tile(x: Int, y: Int): Tile = game.tileMap[x, y]

    private fun neutralCiv(vararg uniques: String): Civilization {
        val newCiv = game.addCiv(*uniques)
        for (flavor in Flavor.entries) newCiv.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) newCiv.ai.setDiplomacyValue(value, 0)
        for (tile in game.tileMap.values) tile.setExplored(newCiv, true)
        return newCiv
    }

    private fun greatPerson(name: String, at: Tile = city.getCenterTile()): MapUnit = game.addUnit(name, civ, at)

    /** A Great Person with a work of [slot]'s type (its name is the creator of such a work) */
    private fun creator(unitName: String, slot: GreatWorkSlotType): MapUnit {
        val unit = greatPerson(unitName)
        unit.instanceName = game.ruleset.greatWorks.values.first { it.getSlotType() == slot && it.creator != null }.creator
        return unit
    }

    private fun addSlot(slot: GreatWorkSlotType) =
        city.cityConstructions.addBuilding(game.createBuilding("Has [1] [${slot.label}] Great Work slots"))

    private fun setPublicOpinionUnhappiness(value: Int) =
        PublicOpinion::class.java.getDeclaredField("unhappiness").apply { isAccessible = true }.setInt(civ.culture.publicOpinion, value)

    private fun setReligionState(state: ReligionState) =
        civ.religionManager.javaClass.getDeclaredField("religionState").apply { isAccessible = true }.set(civ.religionManager, state)

    private fun atWarStrategy() { civ.ai.militaryStrategies["MILITARYAISTRATEGY_AT_WAR"] = 0 }

    private fun base(name: String): BaseUnit = game.ruleset.units[name]!!

    //region Scientist

    @Test
    fun `scientist builds an academy only for spaceship within the first quarter of the game`() {
        civ.ai.grandStrategy = GrandStrategyAi.SPACESHIP
        game.gameInfo.turns = endTurn / 4
        assertEquals(GreatPeopleDirective.ConstructImprovement, GreatPersonAutomation.directiveScientist(civ))
        game.gameInfo.turns = endTurn / 4 + 1
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveScientist(civ))
        game.gameInfo.turns = 1
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveScientist(civ))
    }

    @Test
    fun `scientist bulbs when at war and not winning all wars, even as an early space racer`() {
        civ.ai.grandStrategy = GrandStrategyAi.SPACESHIP
        atWarStrategy()
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveScientist(civ))
        civ.ai.warStates.stateAllWars = StateAllWars.Neutral
        assertFalse(GreatPersonAutomation.isSafe(civ))
        civ.ai.warStates.stateAllWars = StateAllWars.Winning
        assertEquals(GreatPeopleDirective.ConstructImprovement, GreatPersonAutomation.directiveScientist(civ))
    }

    @Test
    fun `a bulbing scientist is spent on the current research`() {
        civ.tech.techsToResearch.add("Pottery")
        val scientist = greatPerson("Great Scientist")
        assertTrue(GreatPersonAutomation.automate(scientist) { false })
        assertFalse("consumed", scientist in civ.units.getCivUnits())
        assertTrue("no academy built", city.getTiles().none { it.improvement == "Academy" })
    }
    //endregion

    //region Engineer

    @Test
    fun `engineer hurries a wonder whenever the flavor-weighted wonder AI wants one`() {
        civ.tech.addTechnology("Calendar") // Stonehenge: Religion 75, Wonder 25, GreatPeople 10
        val engineer = greatPerson("Great Engineer")
        assertEquals("no flavor for any wonder: no wonder wanted", GreatPeopleDirective.None, GreatPersonAutomation.directiveEngineer(engineer))
        civ.ai.setPersonalityFlavor(Flavor.Religion, 4)
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveEngineer(engineer))
        assertEquals(4 * 75, WonderProductionAi.weight(civ, game.ruleset.buildings["Stonehenge"]!!))
    }

    @Test
    fun `engineer without a wonder builds a manufactory for conquest in the first three quarters, else after ten turns`() {
        val engineer = greatPerson("Great Engineer")
        civ.ai.grandStrategy = GrandStrategyAi.CONQUEST
        game.gameInfo.turns = endTurn * 3 / 4
        engineer.turnCreated = game.gameInfo.turns
        assertEquals(GreatPeopleDirective.ConstructImprovement, GreatPersonAutomation.directiveEngineer(engineer))
        game.gameInfo.turns = endTurn * 3 / 4 + 1
        engineer.turnCreated = game.gameInfo.turns - 9
        assertEquals("waits", GreatPeopleDirective.None, GreatPersonAutomation.directiveEngineer(engineer))
        engineer.turnCreated = game.gameInfo.turns - 10
        assertEquals(GreatPeopleDirective.ConstructImprovement, GreatPersonAutomation.directiveEngineer(engineer))
    }

    @Test
    fun `engineer goes for the wonder of highest raw weight, not reweighted by cost`() {
        civ.tech.addTechnology("Calendar")
        civ.tech.addTechnology("Masonry")
        civ.tech.addTechnology("Bronze Working")
        civ.ai.setPersonalityFlavor(Flavor.Religion, 1) // Stonehenge 75
        civ.ai.setPersonalityFlavor(Flavor.Wonder, 4)
        val choice = WonderProductionAi.chooseWonderForGreatEngineer(civ)!!
        val best = game.ruleset.buildings.values.filter { WonderProductionAi.isWonder(it) && it.isBuildable(city.cityConstructions) }
            .maxBy { WonderProductionAi.weight(civ, it) }
        assertEquals(best.name, choice.wonder.name)
        assertEquals(city, choice.city)
    }
    //endregion

    //region Merchant

    private fun cityState(): Civilization {
        val cityState = game.addCiv(cityStateType = "Mercantile")
        game.addCity(cityState, tile(4, 0))
        civ.diplomacyFunctions.makeCivilizationsMeet(cityState)
        return cityState
    }

    @Test
    fun `merchant builds a customs house for diplomacy in the first half, unless Venice`() {
        val merchant = greatPerson("Great Merchant")
        cityState()
        civ.ai.grandStrategy = GrandStrategyAi.UNITED_NATIONS
        game.gameInfo.turns = endTurn / 2
        assertEquals(GreatPeopleDirective.ConstructImprovement, GreatPersonAutomation.directiveMerchant(merchant))
        game.gameInfo.turns = endTurn / 2 + 1
        merchant.turnCreated = game.gameInfo.turns
        assertEquals("a trade mission when safe", GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveMerchant(merchant))

        val venice = neutralCiv("May not annex cities")
        game.addCity(venice, tile(-4, 0))
        venice.ai.grandStrategy = GrandStrategyAi.UNITED_NATIONS
        val venetian = game.addUnit("Great Merchant", venice, venice.cities.first().getCenterTile())
        venice.ai.militaryStrategies["MILITARYAISTRATEGY_AT_WAR"] = 0 // no trade mission
        game.gameInfo.turns = 1
        venetian.turnCreated = 1
        assertEquals("Venice never builds the customs house early", GreatPeopleDirective.None, GreatPersonAutomation.directiveMerchant(venetian))
        venetian.turnCreated = -20
        assertEquals("nor after the wait", GreatPeopleDirective.None, GreatPersonAutomation.directiveMerchant(venetian))
    }

    @Test
    fun `merchant goes on a trade mission when safe and a city-state is reachable`() {
        val merchant = greatPerson("Great Merchant")
        val cityState = cityState()
        val target = GreatPersonAutomation.findBestMerchantTargetPlot(merchant)
        assertNotNull(target)
        assertEquals(cityState, target!!.getOwner())
        assertEquals(1, target.aerialDistanceTo(cityState.cities.first().getCenterTile()))
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveMerchant(merchant))

        atWarStrategy()
        assertEquals("not safe: no mission, waits", GreatPeopleDirective.None, GreatPersonAutomation.directiveMerchant(merchant))
        merchant.turnCreated = game.gameInfo.turns - 10
        assertEquals(GreatPeopleDirective.ConstructImprovement, GreatPersonAutomation.directiveMerchant(merchant))
    }

    @Test
    fun `merchant skips city-states we approach as conquest or are at war with`() {
        val merchant = greatPerson("Great Merchant")
        val cityState = cityState()
        civ.ai.minorCivApproaches[cityState.civID] = MinorCivApproach.Conquest
        assertNull(GreatPersonAutomation.findBestMerchantTargetPlot(merchant))
        civ.ai.minorCivApproaches[cityState.civID] = MinorCivApproach.Friendly
        assertNotNull(GreatPersonAutomation.findBestMerchantTargetPlot(merchant))
        civ.getDiplomacyManager(cityState)!!.declareWar()
        assertNull(GreatPersonAutomation.findBestMerchantTargetPlot(merchant))
    }

    @Test
    fun `merchant skips city-states we plan a war against`() {
        val merchant = greatPerson("Great Merchant")
        val cityState = cityState()
        civ.ai.diplomacy.memoryOf(cityState).warGoal = WarGoal.Prepare
        assertNull(GreatPersonAutomation.findBestMerchantTargetPlot(merchant))
        civ.ai.diplomacy.memoryOf(cityState).warGoal = null
        assertNotNull(GreatPersonAutomation.findBestMerchantTargetPlot(merchant))
    }
    //endregion

    //region General

    @Test
    fun `a citadel goes next to a major only when we show it hostility, or neutrality over a strong land dispute`() {
        val other = neutralCiv()
        game.addCity(other, tile(3, 0))
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        val general = greatPerson("Great General")
        val memory = civ.ai.diplomacy.memoryOf(other)
        fun target() = GreatPersonAutomation.findBestArtistTargetPlot(general)

        memory.approach = MajorCivApproach.Neutral
        assertNull("neutral, no dispute", target())
        memory.landDispute = DisputeLevel.Strong
        assertEquals(other, target()!!.neighbors.firstNotNullOf { it.getOwner()?.takeIf { owner -> owner != civ } })
        memory.approach = MajorCivApproach.Friendly
        assertNull("friendly despite the dispute", target())
        memory.landDispute = DisputeLevel.None
        memory.approach = MajorCivApproach.War
        memory.warFace = WarFace.Hostile
        assertNotNull("the war face shown is hostile", target())
        memory.warFace = WarFace.Friendly
        assertNull("the war face shown is friendly", target())
    }
    //endregion

    //region Writer

    @Test
    fun `writer makes a great work if there is a slot, else a political treatise`() {
        val writer = creator("Great Writer", GreatWorkSlotType.Literature)
        assertEquals(GreatPeopleDirective.CultureBlast, GreatPersonAutomation.directiveWriter(writer))
        addSlot(GreatWorkSlotType.Literature)
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveWriter(writer))
    }

    @Test
    fun `writer defends the ideology with a treatise above 10 public-opinion unhappiness unless going for culture`() {
        val writer = creator("Great Writer", GreatWorkSlotType.Literature)
        addSlot(GreatWorkSlotType.Literature)
        setPublicOpinionUnhappiness(10)
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveWriter(writer))
        setPublicOpinionUnhappiness(11)
        assertEquals(GreatPeopleDirective.CultureBlast, GreatPersonAutomation.directiveWriter(writer))
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveWriter(writer))
    }

    @Test
    fun `writer snags an advanced tenet with a treatise unless going for culture`() {
        val writer = creator("Great Writer", GreatWorkSlotType.Literature)
        addSlot(GreatWorkSlotType.Literature)
        civ.tech.addTechnology("Industrialization")
        val ideology = game.ruleset.policyBranches.values.first { it.isIdeology }
        civ.policies.adopt(ideology)
        civ.policies.freeTenets = 3
        for (tenet in ideology.policies.filter { it.tenetLevel == 1 }.take(2)) civ.policies.adopt(tenet)
        assertTrue(GreatPersonAutomation.canGetAdvancedTenet(civ))
        assertEquals(GreatPeopleDirective.CultureBlast, GreatPersonAutomation.directiveWriter(writer))
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveWriter(writer))
        civ.ai.grandStrategy = null
        civ.policies.freeTenets = 0
        assertFalse("nothing to pay with", GreatPersonAutomation.canGetAdvancedTenet(civ))
    }
    //endregion

    //region Artist

    @Test
    fun `artist makes a great work if there is a slot, else a golden age unless in one`() {
        val artist = creator("Great Artist", GreatWorkSlotType.ArtArtifact)
        assertEquals(GreatPeopleDirective.GoldenAge, GreatPersonAutomation.directiveArtist(artist))
        civ.goldenAges.enterGoldenAge()
        assertEquals(GreatPeopleDirective.None, GreatPersonAutomation.directiveArtist(artist))
        addSlot(GreatWorkSlotType.ArtArtifact)
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveArtist(artist))
    }

    @Test
    fun `artist starts a golden age under ideological pressure or when preparing for war, unless going for culture`() {
        val artist = creator("Great Artist", GreatWorkSlotType.ArtArtifact)
        addSlot(GreatWorkSlotType.ArtArtifact)
        setPublicOpinionUnhappiness(11)
        assertEquals(GreatPeopleDirective.GoldenAge, GreatPersonAutomation.directiveArtist(artist))
        setPublicOpinionUnhappiness(0)
        civ.ai.militaryStrategies["MILITARYAISTRATEGY_WAR_MOBILIZATION"] = 0
        assertEquals(GreatPeopleDirective.GoldenAge, GreatPersonAutomation.directiveArtist(artist))
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveArtist(artist))
    }

    @Test
    fun `artist starts a golden age for the spaceship homestretch`() {
        val artist = creator("Great Artist", GreatWorkSlotType.ArtArtifact)
        addSlot(GreatWorkSlotType.ArtArtifact)
        civ.ai.grandStrategy = GrandStrategyAi.SPACESHIP
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveArtist(artist))
        city.cityConstructions.addBuilding("Apollo Program")
        assertEquals(GreatPeopleDirective.GoldenAge, GreatPersonAutomation.directiveArtist(artist))
        civ.ai.grandStrategy = GrandStrategyAi.CONQUEST
        assertEquals("homestretch counts only when going for spaceship", GreatPeopleDirective.UsePower,
            GreatPersonAutomation.directiveArtist(artist))
    }

    @Test
    fun `a civ with a golden-age movement bonus starts one when at war with more than one major`() {
        civ = neutralCiv("[+1] Movement <for [All] units> <during a Golden Age>")
        city = game.addCity(civ, tile(-4, 0))
        val artist = creator("Great Artist", GreatWorkSlotType.ArtArtifact)
        addSlot(GreatWorkSlotType.ArtArtifact)
        val enemies = listOf(neutralCiv(), neutralCiv())
        for ((i, enemy) in enemies.withIndex()) {
            game.addCity(enemy, tile(4, if (i == 0) -2 else 2))
            civ.diplomacyFunctions.makeCivilizationsMeet(enemy)
        }
        civ.ai.warStates.warStates[enemies[0].civID] = WarState.Calm
        assertEquals("one war is not enough", GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveArtist(artist))
        civ.ai.warStates.warStates[enemies[1].civID] = WarState.Calm
        assertEquals(GreatPeopleDirective.GoldenAge, GreatPersonAutomation.directiveArtist(artist))
        civ.goldenAges.enterGoldenAge()
        assertEquals("not during a golden age", GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveArtist(artist))
    }

    @Test
    fun `a civ with golden-age tourism starts one once influential on someone`() {
        civ = neutralCiv("[+100]% Tourism [in all cities] <during a Golden Age>")
        city = game.addCity(civ, tile(-4, 0))
        val artist = creator("Great Artist", GreatWorkSlotType.ArtArtifact)
        addSlot(GreatWorkSlotType.ArtArtifact)
        val other = neutralCiv()
        game.addCity(other, tile(4, 0))
        civ.diplomacyFunctions.makeCivilizationsMeet(other)
        other.totalCultureForContests = 100
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveArtist(artist))
        civ.culture.addInfluence(other, 1000)
        assertEquals(GreatPeopleDirective.GoldenAge, GreatPersonAutomation.directiveArtist(artist))
    }
    //endregion

    //region Musician

    /** Three other majors with open borders to us; we are influential on [influentialCount] of them */
    private fun majorsForTour(influentialCount: Int): List<Civilization> {
        val others = listOf(neutralCiv(), neutralCiv(), neutralCiv())
        for ((i, other) in others.withIndex()) {
            game.addCity(other, listOf(tile(5, 0), tile(-5, 0), tile(0, 5))[i])
            civ.diplomacyFunctions.makeCivilizationsMeet(other)
            civ.getDiplomacyManager(other)!!.hasOpenBorders = true
            other.totalCultureForContests = 100
        }
        for (other in others.takeLast(influentialCount)) civ.culture.addInfluence(other, 1000)
        return others
    }

    @Test
    fun `musician tours the open-borders civ we influence least`() {
        val others = majorsForTour(influentialCount = 0)
        civ.culture.addInfluence(others[0], 30)
        civ.culture.addInfluence(others[2], 20)
        assertEquals(others[1], GreatPersonAutomation.civLowestInfluence(civ))
        val musician = creator("Great Musician", GreatWorkSlotType.Music)
        assertEquals(others[1], GreatPersonAutomation.findBestMusicianTargetPlot(musician)!!.getOwner())
        civ.getDiplomacyManager(others[1])!!.hasOpenBorders = false
        assertEquals("closed borders are skipped", others[2], GreatPersonAutomation.civLowestInfluence(civ))
    }

    @Test
    fun `musician makes a great work if there is a slot, else tours, else waits`() {
        val musician = creator("Great Musician", GreatWorkSlotType.Music)
        assertEquals("no slot, no tour target", GreatPeopleDirective.None, GreatPersonAutomation.directiveMusician(musician))
        majorsForTour(influentialCount = 0)
        assertEquals(GreatPeopleDirective.TourismBlast, GreatPersonAutomation.directiveMusician(musician))
        addSlot(GreatWorkSlotType.Music)
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveMusician(musician))
    }

    @Test
    fun `musician tours first when going for culture and influential on more than half the civs needed`() {
        val musician = creator("Great Musician", GreatWorkSlotType.Music)
        addSlot(GreatWorkSlotType.Music)
        majorsForTour(influentialCount = 1) // needed: 3, half: 1, influential on 1: not more
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveMusician(musician))
    }

    @Test
    fun `musician near a culture win tours instead of making a work`() {
        val musician = creator("Great Musician", GreatWorkSlotType.Music)
        addSlot(GreatWorkSlotType.Music)
        majorsForTour(influentialCount = 2)
        assertEquals("not going for culture", GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveMusician(musician))
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        assertEquals(GreatPeopleDirective.TourismBlast, GreatPersonAutomation.directiveMusician(musician))
    }

    @Test
    fun `a concert tour whose target cannot be reached any more retargets instead of moving`() {
        val target = tile(5, 0)
        for (neighbor in target.neighbors) game.setTileTerrain(neighbor.position, "Ocean") // no embarking without Optics
        val musician = greatPerson("Great Musician")
        musician.greatPersonTarget = target.position
        assertTrue(GreatPersonAutomation.automate(musician) { false })
        assertNull("no reachable tour target: the operation ends", musician.greatPersonTarget)
        assertEquals(city.getCenterTile(), musician.currentTile)
    }
    //endregion

    //region General and admiral

    @Test
    fun `general plants a citadel only with more than two generals and in our territory`() {
        val tiles = city.getTiles().filter { !it.isCityCenter() }.toList()
        val generals = (0..1).map { greatPerson("Great General", tiles[it]) }
        assertEquals(GreatPeopleDirective.None, GreatPersonAutomation.directiveGeneral(generals[0]))
        val third = greatPerson("Great General", tiles[2])
        assertEquals(GreatPeopleDirective.UsePower, GreatPersonAutomation.directiveGeneral(third))
        third.removeFromTile()
        val outside = tile(0, -6)
        third.putInTile(outside)
        assertEquals(GreatPeopleDirective.None, GreatPersonAutomation.directiveGeneral(third))
    }

    @Test
    fun `admiral has no directive`() {
        assertEquals(GreatPeopleDirective.None, GreatPersonAutomation.directive(greatPerson("Great Admiral")))
    }
    //endregion

    //region Free Great People

    private val allGreatPeople get() = civ.greatPeople.getGreatPeople().toList()

    @Test
    fun `free great person follows the grand strategy and is kept without one`() {
        val expected = mapOf(
            GrandStrategyAi.CONQUEST to "Great General", GrandStrategyAi.CULTURE to "Great Artist",
            GrandStrategyAi.UNITED_NATIONS to "Great Merchant", GrandStrategyAi.SPACESHIP to "Great Scientist",
        )
        for ((strategy, name) in expected) {
            civ.ai.grandStrategy = strategy
            assertEquals(name, GreatPersonAutomation.freeGreatPersonChoice(civ, allGreatPeople)?.name)
        }
        civ.ai.grandStrategy = null
        assertNull(GreatPersonAutomation.freeGreatPersonChoice(civ, allGreatPeople))
        civ.greatPeople.freeGreatPeople = 1
        GreatPersonAutomation.chooseFreeGreatPeople(civ)
        assertEquals("the pick is kept", 1, civ.greatPeople.freeGreatPeople)
    }

    @Test
    fun `a wonder-competitive leader takes an engineer in the first half of the game`() {
        civ.ai.grandStrategy = GrandStrategyAi.SPACESHIP
        civ.ai.setDiplomacyValue(DiplomacyPersonality.WonderCompetitiveness, 8)
        game.gameInfo.turns = endTurn / 2
        assertEquals("Great Engineer", GreatPersonAutomation.freeGreatPersonChoice(civ, allGreatPeople)?.name)
        game.gameInfo.turns = endTurn / 2 + 1
        assertEquals("Great Scientist", GreatPersonAutomation.freeGreatPersonChoice(civ, allGreatPeople)?.name)
        game.gameInfo.turns = 1
        civ.ai.setDiplomacyValue(DiplomacyPersonality.WonderCompetitiveness, 7)
        assertEquals("Great Scientist", GreatPersonAutomation.freeGreatPersonChoice(civ, allGreatPeople)?.name)
    }

    @Test
    fun `free great people are spent until the picks run out`() {
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        civ.greatPeople.freeGreatPeople = 2
        GreatPersonAutomation.chooseFreeGreatPeople(civ)
        assertEquals(0, civ.greatPeople.freeGreatPeople)
        assertEquals(2, civ.units.getCivGreatPeople().count { it.name == "Great Artist" })
    }

    @Test
    fun `maya boost prefers a prophet while the religion is still to found or enhance`() {
        civ.ai.grandStrategy = GrandStrategyAi.SPACESHIP
        assertEquals("religions can still be founded", "Great Prophet", GreatPersonAutomation.mayaBoostChoice(civ, allGreatPeople)?.name)
        setReligionState(ReligionState.Religion)
        assertEquals("Great Prophet", GreatPersonAutomation.mayaBoostChoice(civ, allGreatPeople)?.name)
        setReligionState(ReligionState.EnhancedReligion)
        assertEquals("Great Scientist", GreatPersonAutomation.mayaBoostChoice(civ, allGreatPeople)?.name)
    }

    @Test
    fun `maya boost takes the grand strategy's person, then the engineer, then the fixed order of those not taken yet`() {
        setReligionState(ReligionState.EnhancedReligion)
        civ.ai.setDiplomacyValue(DiplomacyPersonality.WonderCompetitiveness, 8)
        civ.ai.grandStrategy = GrandStrategyAi.CULTURE
        assertEquals("wonder-competitive first", "Great Engineer", GreatPersonAutomation.mayaBoostChoice(civ, allGreatPeople)?.name)
        val noEngineer = allGreatPeople.filter { it.name != "Great Engineer" }
        assertEquals("Great Artist", GreatPersonAutomation.mayaBoostChoice(civ, noEngineer)?.name)
        val noArtist = noEngineer.filter { it.name != "Great Artist" }
        assertEquals("then the fixed order: prophet first", "Great Prophet", GreatPersonAutomation.mayaBoostChoice(civ, noArtist)?.name)
        val rest = noArtist.filter { it.name != "Great Prophet" }
        assertEquals("Great Writer", GreatPersonAutomation.mayaBoostChoice(civ, rest)?.name)
        assertEquals("Great Merchant", GreatPersonAutomation.mayaBoostChoice(civ, rest.filter { it.name != "Great Writer" })?.name)
    }

    @Test
    fun `the great person roles come from the AI data`() {
        assertEquals(GreatPersonRole.Writer, GreatPersonAutomation.roleOf(base("Great Writer"), game.ruleset))
        assertEquals(GreatPersonRole.Merchant, GreatPersonAutomation.roleOf(base("Merchant of Venice"), game.ruleset))
        assertEquals(GreatPersonRole.General, GreatPersonAutomation.roleOf(base("Khan"), game.ruleset))
        assertEquals(GreatPersonRole.Admiral, GreatPersonAutomation.roleOf(base("Great Admiral"), game.ruleset))
        assertNull(GreatPersonAutomation.roleOf(base("Warrior"), game.ruleset))
    }
    //endregion

}
