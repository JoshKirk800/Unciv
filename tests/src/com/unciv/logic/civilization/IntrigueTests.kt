package com.unciv.logic.civilization

import com.unciv.logic.automation.diplomacy.IntrigueAi
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.automation.strategy.AiOperation
import com.unciv.logic.automation.strategy.AiOperationType
import com.unciv.logic.automation.strategy.ArmyType
import com.unciv.logic.city.City
import com.unciv.logic.civilization.managers.IntrigueType
import com.unciv.models.Spy
import com.unciv.models.SpyAction
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.MajorCivApproach
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

/**
 *  CvPlayerEspionage::UncoverIntrigue / AddIntrigueMessage and the sharing of intrigue (CvDiplomacyAI::DoShareIntrigueStatement,
 *  FROM_UI_DIPLO_EVENT_HUMAN_DISCUSSION_SHARE_INTRIGUE, GetTimesIntrigueSharedScore). The player [a] has a spy in the capital of the AI civ [b];
 *  [c] and [d] are other AI civs.
 */
@RunWith(GdxTestRunner::class)
class IntrigueTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization
    private lateinit var d: Civilization
    private lateinit var bCity: City
    private lateinit var cCity: City
    private lateinit var spy: Spy

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(8)
        a = game.addCiv(isPlayer = true)
        b = game.addCiv()
        c = game.addCiv()
        d = game.addCiv()
        game.addCity(a, game.tileMap[0, 0], replacePalace = true)
        bCity = game.addCity(b, game.tileMap[0, 5], replacePalace = true)
        cCity = game.addCity(c, game.tileMap[0, -5], replacePalace = true)
        game.addCity(d, game.tileMap[5, 0], replacePalace = true)
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(d)
        c.diplomacyFunctions.makeCivilizationsMeet(d)
        a.diplomacyFunctions.makeCivilizationsMeet(d)
        for (tile in game.tileMap.values) tile.setExplored(a, true)
        spy = a.espionageManager.addSpy()
        spy.moveTo(bCity)
    }

    /** Sends the spy to its post and lets the network be established, which is when the first intrigue is uncovered */
    private fun establishNetwork() {
        var turns = 0
        while ((spy.action == SpyAction.Moving || spy.action == SpyAction.EstablishNetwork) && turns++ < 50) a.espionageManager.endTurn()
        assertTrue(spy.action != SpyAction.Moving && spy.action != SpyAction.EstablishNetwork)
    }

    private fun sneakAttack(type: AiOperationType = AiOperationType.SNEAK_CITY_ATTACK) {
        val operation = AiOperation()
        operation.type = type
        operation.enemyCivId = c.civID
        operation.targetTile = cCity.location
        b.ai.operations.operations.add(operation)
    }

    private val messages get() = a.espionageManager.intrigueMessages

    @Test
    fun `a spy of rank agent learns the target civ and city of a sneak attack`() {
        spy.levelUpSpy()
        sneakAttack()
        establishNetwork()
        val message = messages.single { it.type == IntrigueType.ArmySneakAttack }
        assertEquals(b.civID, message.source)
        assertEquals(c.civID, message.target)
        assertFalse(message.targetHidden)
        assertEquals(cCity.location, message.cityLocation)
        assertEquals(a.civID, message.discoverer)
        assertEquals(spy.name, message.spyName)
        assertTrue(a.notifications.any { "marching toward" in it.text && c.civName in it.text && cCity.name in it.text })
    }

    @Test
    fun `a recruit learns of a sneak attack but not who is targeted`() {
        sneakAttack()
        establishNetwork()
        val message = messages.single { it.type == IntrigueType.ArmySneakAttack }
        assertNull(message.target)
        assertTrue(message.targetHidden)
        assertNull(message.cityLocation)
        assertTrue(a.notifications.any { "We do not know which civ they are targeting" in it.text })
    }

    @Test
    fun `an agent does not learn of a target civ it has not met`() {
        spy.levelUpSpy()
        a.diplomacy.remove(c.civID)
        sneakAttack(AiOperationType.NAVAL_SNEAK_ATTACK)
        establishNetwork()
        val message = messages.single { it.type == IntrigueType.AmphibiousSneakAttack }
        assertNull(message.target)
        assertFalse(message.targetHidden)
        assertEquals(cCity.location, message.cityLocation)
        assertTrue(a.notifications.any { "a civilization you have not met" in it.text })
    }

    @Test
    fun `a civ that is friendly on the surface but deceptive in truth is plotting against its target`() {
        b.ai.diplomacy.memoryOf(c).approach = MajorCivApproach.Deceptive
        establishNetwork()
        val message = messages.single { it.type == IntrigueType.Deception }
        assertEquals(b.civID, message.source)
        assertEquals(c.civID, message.target)
        assertEquals(bCity.location, message.cityLocation)
        assertTrue(a.notifications.any { "secretly plotting against [${c.civName}]" in it.text })
    }

    @Test
    fun `a deception against a civ it is at war with already is not intrigue and a hostile surface hides nothing`() {
        b.ai.diplomacy.memoryOf(c).approach = MajorCivApproach.Deceptive
        b.diplomacyFunctions.makeCivilizationsMeet(c)
        b.getDiplomacyManager(c)!!.declareWar()
        b.ai.diplomacy.memoryOf(d).approach = MajorCivApproach.Hostile
        establishNetwork()
        assertTrue(messages.none { it.type == IntrigueType.Deception })
    }

    @Test
    fun `an army being built is intrigue about its civ`() {
        b.ai.military.armyTypeBeingBuilt = ArmyType.NavalInvasion
        establishNetwork()
        val message = messages.single { it.type == IntrigueType.BuildingAmphibiousArmy }
        assertEquals(b.civID, message.source)
        assertNull(message.target)
        assertTrue(a.notifications.any { "naval escorts" in it.text })
    }

    @Test
    fun `a wonder in production is intrigue even in the city of a human`() {
        val wonder = game.ruleset.buildings.values.first { it.isWonder }.name
        bCity.cityConstructions.setCurrentConstruction(wonder)
        b.playerType = PlayerType.Human
        establishNetwork()
        val message = messages.single()
        assertEquals(IntrigueType.ConstructingWonder, message.type)
        assertEquals(wonder, message.construction)
        assertTrue(a.notifications.any { "is constructing [$wonder]" in it.text })
    }

    @Test
    fun `the plots of a human civ are not known to its spies`() {
        b.playerType = PlayerType.Human
        b.ai.military.armyTypeBeingBuilt = ArmyType.Land
        b.ai.diplomacy.memoryOf(c).approach = MajorCivApproach.Deceptive
        sneakAttack()
        establishNetwork()
        assertTrue(messages.isEmpty())
    }

    @Test
    fun `the same intrigue is not reported again within 30 turns`() {
        b.ai.military.armyTypeBeingBuilt = ArmyType.Land
        establishNetwork()
        assertEquals(1, messages.size)
        a.espionageManager.endTurn() // nothing to steal: back to surveillance, which uncovers intrigue every turn from the next turn
        assertEquals(SpyAction.Surveillance, spy.action)
        game.gameInfo.turns = 29
        a.espionageManager.endTurn()
        assertEquals(1, messages.size)
        game.gameInfo.turns = 31
        a.espionageManager.endTurn()
        assertEquals(2, messages.size)
    }

    @Test
    fun `a diplomat schmoozing in a capital uncovers intrigue every turn`() {
        b.ai.military.armyTypeBeingBuilt = ArmyType.Land
        a.getDiplomacyManager(b)!!.makePeace()
        spy.moveTo(bCity, asDiplomat = true)
        var turns = 0
        while (spy.action != SpyAction.Schmoozing && turns++ < 50) a.espionageManager.endTurn()
        assertEquals(SpyAction.Schmoozing, spy.action)
        assertTrue(messages.isEmpty()) // introductions are made, not yet schmoozing
        a.espionageManager.endTurn()
        assertEquals(IntrigueType.BuildingArmy, messages.single().type)
    }

    private fun plotAgainst(told: Civilization, plotter: Civilization = c, finder: Civilization = b) =
        finder.espionageManager.addIntrigueMessage(finder, plotter, told, false, null, IntrigueType.Deception, null, notify = false, spy = null)

    @Test
    fun `an AI tells a civ about a plot against it once, and the civ learns it`() {
        assertTrue(plotAgainst(d))
        assertTrue(IntrigueAi.doShareIntrigueStatement(b, d))
        val told = d.espionageManager.intrigueMessages.single()
        assertEquals(b.civID, told.discoverer)
        assertEquals(c.civID, told.source)
        assertEquals(d.civID, told.target)
        assertTrue(b.espionageManager.intrigueMessages.single().shared)
        assertTrue(b.espionageManager.hasSharedIntrigue(d, c, IntrigueType.Deception))
        assertFalse(IntrigueAi.doShareIntrigueStatement(b, d))
    }

    @Test
    fun `an AI tells a human about a plot against it`() {
        plotAgainst(a)
        assertTrue(IntrigueAi.doShareIntrigueStatement(b, a))
        assertTrue(a.notifications.any { "Our spies have uncovered that [${c.civName}] is plotting against you" in it.text })
    }

    @Test
    fun `an AI on guard or hostile towards a civ does not warn it`() {
        plotAgainst(d)
        b.ai.diplomacy.memoryOf(d).approach = MajorCivApproach.Guarded
        assertFalse(IntrigueAi.doShareIntrigueStatement(b, d))
        b.ai.diplomacy.memoryOf(d).approach = MajorCivApproach.Hostile
        assertFalse(IntrigueAi.doShareIntrigueStatement(b, d))
        assertTrue(d.espionageManager.intrigueMessages.isEmpty())
        b.ai.diplomacy.memoryOf(d).approach = MajorCivApproach.Friendly
        assertTrue(IntrigueAi.doShareIntrigueStatement(b, d))
    }

    @Test
    fun `an AI does not repeat a warning another civ already gave and does not tell of a plot between civs at war`() {
        plotAgainst(d)
        // c's own intrigue: a third civ has told d already
        a.espionageManager.addIntrigueMessage(a, c, d, false, null, IntrigueType.Deception, null, notify = false, spy = null)
        assertTrue(a.espionageManager.markRecentIntrigueAsShared(d, c, IntrigueType.Deception) == 1)
        assertFalse(IntrigueAi.doShareIntrigueStatement(b, d))
        assertTrue(b.espionageManager.intrigueMessages.single().shared)
        assertTrue(d.espionageManager.intrigueMessages.isEmpty())

        // an AI that finds out another plot warns d, but d is at war with the plotter already: the statement is made, d learns nothing
        val e = game.addCiv()
        game.addCity(e, game.tileMap[-5, 0], replacePalace = true)
        for (civ in listOf(b, d)) civ.diplomacyFunctions.makeCivilizationsMeet(e)
        d.getDiplomacyManager(e)!!.declareWar()
        plotAgainst(d, plotter = e)
        assertTrue(IntrigueAi.doShareIntrigueStatement(b, d))
        assertTrue(d.espionageManager.intrigueMessages.isEmpty())
        assertTrue(b.espionageManager.intrigueMessages.all { it.shared })
    }

    @Test
    fun `intrigue older than five turns is no longer worth sharing`() {
        plotAgainst(d)
        game.gameInfo.turns = 5
        assertNotNull(b.espionageManager.getRecentIntrigueInfo(d))
        game.gameInfo.turns = 6
        assertNull(b.espionageManager.getRecentIntrigueInfo(d))
        assertFalse(IntrigueAi.doShareIntrigueStatement(b, d))
    }

    @Test
    fun `a human warning an AI is remembered as a better opinion`() {
        a.espionageManager.addIntrigueMessage(a, c, b, false, null, IntrigueType.Deception, null, notify = false, spy = null)
        assertTrue(IntrigueAi.canShare(a, b))
        val rules = game.gameInfo.ruleset.ai
        val before = OpinionAi.opinionWeight(b, a, rules)
        assertEquals(c, IntrigueAi.shareWithAi(a, b))
        assertEquals(1, b.ai.diplomacy.memoryOf(a).numTimesIntrigueShared)
        assertEquals(before - 10, OpinionAi.opinionWeight(b, a, rules))
        assertTrue(a.espionageManager.intrigueMessages.single().shared)
        // nothing left to share, and it is not shared twice
        assertFalse(IntrigueAi.canShare(a, b))
        assertNull(IntrigueAi.shareWithAi(a, b))
        assertEquals(1, b.ai.diplomacy.memoryOf(a).numTimesIntrigueShared)
    }

    @Test
    fun `a human cannot warn an AI it is at war with`() {
        a.espionageManager.addIntrigueMessage(a, c, b, false, null, IntrigueType.Deception, null, notify = false, spy = null)
        a.getDiplomacyManager(b)!!.declareWar()
        assertFalse(IntrigueAi.canShare(a, b))
    }

    @Test
    fun `intrigue messages are kept by a copy of the civ`() {
        b.ai.military.armyTypeBeingBuilt = ArmyType.Land
        establishNetwork()
        val copy = a.espionageManager.clone()
        assertEquals(1, copy.intrigueMessages.size)
        assertEquals(messages.single().type, copy.intrigueMessages.single().type)
        assertFalse(copy.intrigueMessages.single() === messages.single())
    }
}
