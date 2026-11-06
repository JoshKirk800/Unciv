package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.managers.quests.QuestManager
import com.unciv.models.TradeRouteKind
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.QuestName
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(BaseTestRunner::class)
class TradeRouteQuestTests {
    private lateinit var game: TestGame
    private lateinit var major: Civilization
    private lateinit var cityState: Civilization
    private lateinit var majorCity: City
    private lateinit var cityStateCity: City

    private val manager get() = cityState.questManager
    private fun activeQuests() = manager.getAssignedQuestsFor(major).toList()

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        major = game.addCiv()
        cityState = game.addCiv(cityStateType = "Maritime")
        major.diplomacyFunctions.makeCivilizationsMeet(cityState)
        major.getDiplomacyManager(cityState)!!.makePeace()
        majorCity = game.addCity(major, game.getTile(0, 0))
        cityStateCity = game.addCity(cityState, game.getTile(0, 5))
        for (tile in game.tileMap.values) {
            tile.setExplored(major, true)
            tile.setExplored(cityState, true)
        }
        // Exercise real assignment, completion and reward without random choices of other quests.
        game.ruleset.quests.keys.retainAll(setOf(QuestName.TradeRoute.value))
        game.gameInfo.turns = QuestManager.INDIVIDUAL_QUEST_FIRST_POSSIBLE_TURN
    }

    private fun waitForQuest() {
        repeat(QuestManager.INDIVIDUAL_QUEST_FIRST_POSSIBLE_TURN_RAND + 2) {
            manager.endTurn()
            if (activeQuests().isNotEmpty()) return
            game.gameInfo.turns++
        }
        assertTrue("reachable city-state should assign the quest", activeQuests().isNotEmpty())
    }

    private fun startRoute(origin: City, destination: City) {
        val trader = game.addUnit("Caravan", origin.civ, origin.getCenterTile())
        assertNotNull("a legal international trade route must be established",
            origin.civ.tradeRoutes.establish(trader, destination, TradeRouteKind.International))
    }

    @Test
    fun `route from player to city-state pays forty influence exactly once`() {
        waitForQuest()
        assertEquals(QuestName.TradeRoute, activeQuests().single().questNameInstance)
        assertEquals(40f, activeQuests().single().getInfluence())
        manager.handleIndividualQuests()
        assertEquals(1, activeQuests().size)
        startRoute(majorCity, cityStateCity)
        manager.handleIndividualQuests()
        assertTrue(activeQuests().isEmpty())
        assertEquals(40f, cityState.getDiplomacyManager(major)!!.getInfluence())
        manager.handleIndividualQuests()
        assertEquals(40f, cityState.getDiplomacyManager(major)!!.getInfluence())
    }

    @Test
    fun `incoming route from city-state also fulfills assigned quest`() {
        waitForQuest()
        startRoute(cityStateCity, majorCity)
        manager.handleIndividualQuests()
        assertTrue(activeQuests().isEmpty())
        assertEquals(40f, cityState.getDiplomacyManager(major)!!.getInfluence())
    }

    @Test
    fun `city-state does not offer quest for an already connected player`() {
        startRoute(cityStateCity, majorCity)
        repeat(QuestManager.INDIVIDUAL_QUEST_FIRST_POSSIBLE_TURN_RAND + 2) {
            manager.endTurn()
            game.gameInfo.turns++
        }
        assertFalse(manager.haveQuestsFor(major))
        assertEquals(0f, cityState.getDiplomacyManager(major)!!.getInfluence())
    }

    @Test
    fun `outgoing route also prevents offering a new quest`() {
        startRoute(majorCity, cityStateCity)
        repeat(QuestManager.INDIVIDUAL_QUEST_FIRST_POSSIBLE_TURN_RAND + 2) {
            manager.endTurn()
            game.gameInfo.turns++
        }
        assertFalse(manager.haveQuestsFor(major))
    }

    @Test
    fun `city-state does not offer a trade quest to its recent bully`() {
        cityState.getDiplomacyManager(major)!!.setFlag(DiplomacyFlags.Bullied, 20)
        repeat(QuestManager.INDIVIDUAL_QUEST_FIRST_POSSIBLE_TURN_RAND + 2) {
            manager.endTurn()
            game.gameInfo.turns++
        }
        assertFalse(manager.haveQuestsFor(major))
    }

    @Test
    fun `city-state does not offer quest without a possible land or sea route`() {
        val farState = game.addCiv(cityStateType = "Maritime")
        major.diplomacyFunctions.makeCivilizationsMeet(farState)
        major.getDiplomacyManager(farState)!!.makePeace()
        game.addCity(farState, game.getTile(0, -11))
        for (tile in game.tileMap.values) tile.setExplored(farState, true)
        repeat(QuestManager.INDIVIDUAL_QUEST_FIRST_POSSIBLE_TURN_RAND + 2) {
            farState.questManager.endTurn()
            game.gameInfo.turns++
        }
        assertFalse(farState.questManager.haveQuestsFor(major))
    }
}
