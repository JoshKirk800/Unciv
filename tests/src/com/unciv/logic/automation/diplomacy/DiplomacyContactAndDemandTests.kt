package com.unciv.logic.automation.diplomacy

import com.unciv.Constants
import com.unciv.logic.automation.civilization.DiplomacyAutomation
import com.unciv.logic.automation.civilization.TradeAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  The memories CvDiplomacyAI clamps (ChangeCommonFoeValue / ChangeRecentTradeValue / ChangeRecentAssistValue), the first-contact assessment
 *  (DoFirstContactInitRelationship), the military posture count (DoUpdateOnePlayerMilitaryAggressivePosture) and the demand flow
 *  (DoMakeDemand, DEMAND_HUMAN_REFUSAL), against the DLL formulas with the BNW XML values.
 */
@RunWith(GdxTestRunner::class)
class DiplomacyContactAndDemandTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        rules = game.ruleset.ai
        a = neutralCiv(HexCoord(0, 0))
        b = neutralCiv(HexCoord(8, 0))
        c = neutralCiv(HexCoord(-8, 0))
        meet(a, b)
        meet(a, c)
        meet(b, c)
    }

    private fun neutralCiv(at: HexCoord, isPlayer: Boolean = false): Civilization {
        val civ = game.addCiv(isPlayer = isPlayer)
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 0)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun meet(x: Civilization, y: Civilization) = x.diplomacyFunctions.makeCivilizationsMeet(y)

    //region Clamped memories

    @Test
    fun `the common foe value stops at 50 opinion weights of 50`() {
        // c fights b, so c likes whoever hurts b: a conquest worth 5000 is more than the opinion can use
        c.getDiplomacyManager(b)!!.declareWar()
        DiplomacyEvents.changeWarValueLost(b, a, 5000)
        assertEquals(2500, c.ai.diplomacy.memoryOf(a).commonFoeValue)
        assertEquals(-50, OpinionAi.commonFoeScore(c.ai.diplomacy.memoryOf(a).commonFoeValue, rules))
        // Stays at the cap; a negative change comes off it
        DiplomacyEvents.changeWarValueLost(b, a, 100)
        assertEquals(2500, c.ai.diplomacy.memoryOf(a).commonFoeValue)
        c.ai.diplomacy.changeCommonFoeValue(a, -3000)
        assertEquals(0, c.ai.diplomacy.memoryOf(a).commonFoeValue)
    }

    @Test
    fun `the recent trade value stops at 30 weights of 10 and never goes below 0`() {
        a.ai.diplomacy.changeRecentTradeValue(b, 3000)
        assertEquals(300, a.ai.diplomacy.memoryOf(b).recentTradeValue)
        a.ai.diplomacy.changeRecentTradeValue(b, -100)
        assertEquals(200, a.ai.diplomacy.memoryOf(b).recentTradeValue)
        a.ai.diplomacy.changeRecentTradeValue(b, -1000)
        assertEquals(0, a.ai.diplomacy.memoryOf(b).recentTradeValue)
    }

    @Test
    fun `the recent assist value stays within plus or minus 30 weights of 10`() {
        a.ai.diplomacy.changeRecentAssistValue(b, 3000)
        assertEquals(300, a.ai.diplomacy.memoryOf(b).recentAssistValue)
        a.ai.diplomacy.changeRecentAssistValue(b, -9000)
        assertEquals(-300, a.ai.diplomacy.memoryOf(b).recentAssistValue)
        a.ai.diplomacy.changeRecentAssistValue(b, 50)
        assertEquals(-250, a.ai.diplomacy.memoryOf(b).recentAssistValue)
    }
    //endregion

    //region First contact

    @Test
    fun `meeting a civilization assesses its military posture before the first approach`() {
        val x = neutralCiv(HexCoord(0, -8))
        val y = neutralCiv(HexCoord(0, 8))
        // Four of y's soldiers 2 to 3 tiles from x's city: 4 x 10 = MILITARY_AGGRESSIVE_POSTURE_THRESHOLD_MEDIUM 30 and more
        val near = listOf(HexCoord(0, -6), HexCoord(1, -6), HexCoord(-1, -6), HexCoord(0, -5)).map { game.getTile(it) }
        for (tile in near) game.addUnit("Warrior", y, tile)
        x.viewableTiles = x.viewableTiles + near

        meet(x, y)

        assertEquals(AggressivePosture.Medium, x.ai.diplomacy.memoryOf(y).militaryPosture)
    }
    //endregion

    //region Military posture

    private fun posture(civ: Civilization, other: Civilization): AggressivePosture {
        DisputeAi.updateMilitaryPosture(civ, other, rules)
        return civ.ai.diplomacy.memoryOf(other).militaryPosture
    }

    @Test
    fun `their units near someone they are at war with still count for the military posture`() {
        // These tiles are 3 to 4 from a's city and 2 to 4 from d's: on both home fronts. b fights d, but the original's test for that does nothing
        val d = neutralCiv(HexCoord(0, 6))
        meet(a, d)
        meet(b, d)
        b.getDiplomacyManager(d)!!.declareWar()
        val tiles = listOf(HexCoord(0, 3), HexCoord(1, 3), HexCoord(-1, 3), HexCoord(0, 4)).map { game.getTile(it) }
        for (tile in tiles) game.addUnit("Warrior", b, tile)
        a.viewableTiles = a.viewableTiles + tiles
        assertEquals(AggressivePosture.Medium, posture(a, b))
    }

    @Test
    fun `aircraft are no combat units for the military posture`() {
        // An air base of b 4 tiles from a's city, in b's own land: 5 a unit if they counted, 6 fighters are 30
        val airbase = game.addCity(b, game.getTile(HexCoord(4, 1))).getCenterTile()
        val tiles = listOf(HexCoord(0, 3), HexCoord(1, 3), HexCoord(-1, 3), HexCoord(0, 4)).map { game.getTile(it) }
        a.viewableTiles = a.viewableTiles + airbase + tiles
        for (i in 0 until 6) game.addUnit("Fighter", b, airbase)
        assertEquals(AggressivePosture.None, posture(a, b))
        for (tile in tiles) game.addUnit("Swordsman", b, tile)
        assertEquals(AggressivePosture.Medium, posture(a, b))
    }
    //endregion

    //region Demands

    private fun readyDemand(demander: Civilization, target: Civilization) {
        game.gameInfo.turns = 10
        demander.ai.diplomacy.demandTarget = target.civID
        demander.ai.diplomacy.demandReady = true
    }

    private fun humanCiv(): Civilization {
        val human = neutralCiv(HexCoord(0, 8), isPlayer = true)
        meet(a, human)
        meet(b, human)
        meet(c, human)
        human.addGold(1000 - human.gold)
        // Lump gold is tradable only between friends (CvDeal::IsPossibleToTradeItem)
        a.getDiplomacyManager(human)!!.signDeclarationOfFriendship()
        return human
    }

    @Test
    fun `a demand on a human is a trade request for up to 200 plus 150 per era in gold`() {
        val human = humanCiv()
        readyDemand(a, human)
        DiplomacyAutomation.contactMajorCivs(a)

        val request = human.tradeRequests.single { it.requestingCiv == a.civID }
        assertTrue(request.isDemand)
        val gold = request.trade.ourOffers.single()
        assertEquals(TradeOfferType.Gold, gold.type)
        assertEquals(200 + 150 * human.getEraNumber(), gold.amount)
        assertTrue(request.trade.theirOffers.isEmpty())
        assertEquals(10, a.ai.diplomacy.memoryOf(human).statementTurns[DiploStatement.Demand])

        // Giving in moves the gold to the demander
        val ours = a.gold
        val logic = TradeLogic(human, a)
        logic.currentTrade.set(request.trade)
        logic.acceptTrade()
        assertEquals(800, human.gold)
        assertEquals(ours + 200, a.gold)
    }

    @Test
    fun `the demand process lingers after the demand and ends at the next update`() {
        val human = humanCiv()
        readyDemand(a, human)
        DiplomacyAutomation.contactMajorCivs(a)
        // CvDiplomacyAI::DoMakeDemand stands nothing down; the Demand statement makes the target unattractive for 40 turns
        assertEquals(10, a.ai.diplomacy.memoryOf(human).statementTurns[DiploStatement.Demand])
        assertEquals(human.civID, a.ai.diplomacy.demandTarget)
        assertTrue(a.ai.diplomacy.demandReady)
        assertFalse(DiplomacyAutomation.isPlayerDemandAttractive(a, human))

        // DoUpdateDemands -> DoCancelHaltDemandProcess
        DiplomacyAutomation.doTurn(a)
        assertNull(a.ai.diplomacy.demandTarget)
        assertFalse(a.ai.diplomacy.demandReady)
    }

    @Test
    fun `a human who refuses the demand gets war about half the time`() {
        val human = humanCiv()
        val trade = Trade()
        trade.ourOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, 200, game.gameInfo.speed))
        var wars = 0
        val trials = 60
        for (turn in 1..trials) {
            game.gameInfo.turns = turn
            TradeRequest(a.civID, trade, isDemand = true).decline(human)
            if (a.isAtWarWith(human)) {
                wars++
                a.getDiplomacyManager(human)!!.makePeace()
            }
        }
        assertTrue("war after $wars of $trials refusals", wars in 15..45)

        // An ordinary refused offer never starts a war
        for (turn in 1..trials) {
            game.gameInfo.turns = turn
            TradeRequest(a.civID, trade).decline(human)
            assertFalse(a.isAtWarWith(human))
        }
    }

    @Test
    fun `an AI gives in to a demand at once and remembers nothing of it`() {
        b.addGold(1000 - b.gold)
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        val ours = a.gold
        readyDemand(a, b)
        DiplomacyAutomation.contactMajorCivs(a)
        assertEquals(800, b.gold)
        assertEquals(ours + 200, a.gold)
        // CvDiplomacyAI::DoDemandMade belongs to the demands made of an AI by a human, not to the AI demanding
        assertEquals(-1, b.ai.diplomacy.memoryOf(a).demandCounter)
        assertEquals(b.civID, a.ai.diplomacy.demandTarget)
    }

    @Test
    fun `no demand is made on a civilization that is no friend, as no gold can be traded with it`() {
        val human = humanCiv()
        a.getDiplomacyManager(human)!!.denounce()
        b.addGold(1000 - b.gold)
        val ours = a.gold
        for (target in listOf(human, b)) {
            readyDemand(a, target)
            DiplomacyAutomation.contactMajorCivs(a)
        }
        assertTrue(human.tradeRequests.none { it.isDemand })
        assertEquals(1000, b.gold)
        assertEquals(ours, a.gold)
        assertNull(a.ai.diplomacy.memoryOf(b).statementTurns[DiploStatement.Demand])
    }

    @Test
    fun `an automated civilization gives in to a demand`() {
        b.addGold(1000 - b.gold)
        a.getDiplomacyManager(b)!!.signDeclarationOfFriendship()
        val trade = Trade()
        trade.ourOffers.add(TradeOffer(Constants.flatGold, TradeOfferType.Gold, 200, game.gameInfo.speed))
        b.tradeRequests.add(TradeRequest(a.civID, trade, isDemand = true))
        val ours = a.gold
        TradeAutomation.respondToTradeRequests(b, tradeAndChangeState = true)
        assertEquals(800, b.gold)
        assertEquals(ours + 200, a.gold)
    }
    //endregion

    //region A human asks to work together

    @Test
    fun `a request to work together too early restarts both counters and is refused`() {
        game.gameInfo.turns = 5 // met on turn 0, DOF_TURN_BUFFER 20
        a.ai.diplomacy.memoryOf(b).doFCounter = -1
        assertFalse(DiplomacyAutomation.answerDoFRequest(a, b))
        assertEquals(0, a.ai.diplomacy.memoryOf(b).doFCounter)
        assertEquals(0, b.ai.diplomacy.memoryOf(a).doFCounter)
    }

    @Test
    fun `asking again within the buffer is refused and leaves the counters alone`() {
        game.gameInfo.turns = 30
        a.ai.diplomacy.memoryOf(b).doFCounter = 7
        b.ai.diplomacy.memoryOf(a).doFCounter = 9
        assertFalse(DiplomacyAutomation.answerDoFRequest(a, b))
        assertEquals(7, a.ai.diplomacy.memoryOf(b).doFCounter)
        assertEquals(9, b.ai.diplomacy.memoryOf(a).doFCounter)
    }

    @Test
    fun `a new answer restarts both counters whatever it is`() {
        game.gameInfo.turns = 30
        a.ai.diplomacy.memoryOf(b).doFCounter = 25
        b.ai.diplomacy.memoryOf(a).doFCounter = 25
        DiplomacyAutomation.answerDoFRequest(a, b)
        assertEquals(0, a.ai.diplomacy.memoryOf(b).doFCounter)
        assertEquals(0, b.ai.diplomacy.memoryOf(a).doFCounter)
    }
    //endregion
}
