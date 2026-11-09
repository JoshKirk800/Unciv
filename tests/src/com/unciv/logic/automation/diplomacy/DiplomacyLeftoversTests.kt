package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.civilization.DiplomacyAutomation
import com.unciv.logic.automation.civilization.InformationalStatementAi
import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticStatus
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueAlignmentMemory
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.AiDefine
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
 *  The informational statements of CvDiplomacyAI::DoContactPlayer and what they do to the turn's one statement, the promise lengths and the
 *  posture that breaks the expansion and border promises (DoTestPromises), and the requests to a human to join a war and to denounce
 *  someone (DoCoopWarStatement, DoCoopWarTimeStatement, DoRequestFriendDenounceStatement), against the DLL rules with the BNW XML values.
 */
@RunWith(GdxTestRunner::class)
class DiplomacyLeftoversTests {
    private lateinit var game: TestGame
    private lateinit var rules: AiRuleset
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var human: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(14)
        rules = game.ruleset.ai
        a = neutralCiv(HexCoord(0, 0))
        b = neutralCiv(HexCoord(8, 0))
        human = neutralCiv(HexCoord(-8, 0), isPlayer = true)
        meet(a, b)
        meet(a, human)
        meet(b, human)
        for (tile in game.tileMap.values) for (civ in listOf(a, b, human)) tile.setExplored(civ, true)
        game.gameInfo.turns = 50
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

    private fun cityState(at: HexCoord): Civilization {
        val minor = game.addCiv(cityStateType = "Cultured")
        game.addCity(minor, game.getTile(at))
        for (major in listOf(a, b, human)) meet(major, minor)
        return minor
    }

    private fun protect(protector: Civilization, minor: Civilization) {
        minor.getDiplomacyManager(protector)!!.diplomaticStatus = DiplomaticStatus.Protector
    }

    private fun memory(civ: Civilization, of: Civilization) = civ.ai.diplomacy.memoryOf(of)

    private fun alerts(civ: Civilization, type: AlertType) = civ.popupAlerts.filter { it.type == type }

    //region Promise lengths

    @Test
    fun `a human's promises to an AI last the DLL lengths, an AI's promises to a human 50 turns`() {
        val percent = DiplomacyTurn.opinionDurationPercent(a)
        val toA = human.getDiplomacyManager(a)!!
        val heldByA = a.getDiplomacyManager(human)!!
        toA.agreeToDemand(Demand.DoNotSettleNearUs)
        assertEquals(rules.define(AiDefine.EXPANSION_PROMISE_TURNS_EFFECTIVE) * percent / 100, heldByA.getFlag(DiplomacyFlags.AgreedToNotSettleNearUs))
        toA.agreeToDemand(Demand.DoNotBuyLandNearUs)
        assertEquals(rules.define(AiDefine.BORDER_PROMISE_TURNS_EFFECTIVE) * percent / 100, heldByA.getFlag(DiplomacyFlags.AgreedToNotBuyLandNearUs))
        toA.agreeToDemand(Demand.DoNotAttackUs)
        assertEquals(20, heldByA.getFlag(DiplomacyFlags.AgreedToNotAttackUs))

        // The AI answering the human's requests: IC_MEMORY_TURN_EXPIRATION and STOP_SPYING_MEMORY_TURN_EXPIRATION
        val aToHuman = a.getDiplomacyManager(human)!!
        aToHuman.agreeToDemand(Demand.DoNotSettleNearUs)
        aToHuman.agreeToDemand(Demand.DontSpyOnUs)
        val heldByHuman = human.getDiplomacyManager(a)!!
        assertEquals(50, heldByHuman.getFlag(DiplomacyFlags.AgreedToNotSettleNearUs))
        assertEquals(50, heldByHuman.getFlag(DiplomacyFlags.AgreedToNotSendSpies))
    }
    //endregion

    //region Posture breaks the expansion and border promises

    private fun promise(demand: Demand) = human.getDiplomacyManager(a)!!.agreeToDemand(demand)

    @Test
    fun `an expansion promise breaks when their expansion posture rises above its level at the promise`() {
        memory(a, human).expansionPosture = AggressivePosture.Low
        promise(Demand.DoNotSettleNearUs)
        assertEquals(AggressivePosture.Low, memory(a, human).expansionPromisePosture)
        val toHuman = a.getDiplomacyManager(human)!!

        PromiseAi.testPromises(a, listOf(human), rules)
        assertFalse("posture unchanged", toHuman.hasFlag(DiplomacyFlags.SettledCitiesNearUs))
        memory(a, human).expansionPosture = AggressivePosture.Medium
        PromiseAi.testPromises(a, listOf(human), rules)
        assertTrue("posture rose", toHuman.hasFlag(DiplomacyFlags.SettledCitiesNearUs))

        // The violation turns into the broken promise, with the human told
        NextTurnAutomation.automateCivMoves(a)
        val broken = memory(a, human).brokenExpansionPromiseValue
        assertEquals(rules.define(AiDefine.OPINION_WEIGHT_EXPANSION_PROMISE_BROKE_MAX) * DiplomacyTurn.opinionDurationPercent(a), broken)
        assertFalse(toHuman.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs))
        assertEquals(1, alerts(human, AlertType.CitySettledNearOtherCivDespiteOurPromise).size)
    }

    @Test
    fun `founding a city near the AI does not break the promise, it only warns without one`() {
        val toHuman = a.getDiplomacyManager(human)!!
        game.addCity(human, game.getTile(HexCoord(-3, 0)))
        assertTrue("no promise: the rolling event starts", toHuman.hasFlag(DiplomacyFlags.SettledCitiesNearUs))
        toHuman.removeFlag(DiplomacyFlags.SettledCitiesNearUs)

        promise(Demand.DoNotSettleNearUs)
        game.addCity(human, game.getTile(HexCoord(-3, 3)))
        assertFalse("the promise is judged on the posture", toHuman.hasFlag(DiplomacyFlags.SettledCitiesNearUs))
        assertTrue(toHuman.hasFlag(DiplomacyFlags.AgreedToNotSettleNearUs))
    }

    @Test
    fun `a border promise breaks when their plot buying posture rises, and a promise that ran out is announced`() {
        memory(a, human).plotBuyingPosture = AggressivePosture.None
        promise(Demand.DoNotBuyLandNearUs)
        val toHuman = a.getDiplomacyManager(human)!!
        memory(a, human).plotBuyingPosture = AggressivePosture.Low
        PromiseAi.testPromises(a, listOf(human), rules)
        assertTrue(toHuman.hasFlag(DiplomacyFlags.BoughtLandNearUs))
        NextTurnAutomation.automateCivMoves(a)
        assertEquals(rules.define(AiDefine.OPINION_WEIGHT_BORDER_PROMISE_BROKE_MAX) * DiplomacyTurn.opinionDurationPercent(a),
            memory(a, human).brokenBorderPromiseValue)
        assertEquals(1, alerts(human, AlertType.LandBoughtNearUsDespiteOurPromise).size)

        // A promise whose flag ran out: the human is told
        human.notifications.clear()
        memory(a, human).expansionPromisePosture = AggressivePosture.None
        PromiseAi.testPromises(a, listOf(human), rules)
        assertTrue(human.notifications.any { it.text.contains("not to settle near them has expired") })
        assertNull(memory(a, human).expansionPromisePosture)
    }

    @Test
    fun `refusing a border request is remembered as an ignored border promise`() {
        human.getDiplomacyManager(a)!!.refuseDemand(Demand.DoNotBuyLandNearUs)
        assertEquals(rules.define(AiDefine.OPINION_WEIGHT_BORDER_PROMISE_IGNORED_MAX) * DiplomacyTurn.opinionDurationPercent(a),
            memory(a, human).ignoredBorderPromiseValue)
    }
    //endregion

    //region Informational statements

    private fun pick(civ: Civilization, other: Civilization) = InformationalStatementAi.pick(civ, other, rules)?.statement

    @Test
    fun `a statement takes the turn's slot so the friendship statement waits a turn`() {
        // Both accept a Declaration of Friendship once it is not too early
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Afraid)
        b.ai.diplomacy.setApproach(a, MajorCivApproach.Afraid)
        memory(a, b).brokenExpansionPromiseValue = 100
        DiplomacyAutomation.contactMajorCivs(a)
        assertEquals(50, memory(a, b).statementTurns[DiploStatement.ExpansionBrokenPromise])
        assertNull("the slot was used", memory(a, b).statementTurns[DiploStatement.WorkWithUs])
        assertFalse(a.getDiplomacyManager(b)!!.hasFlag(DiplomacyFlags.DeclarationOfFriendship))

        // Said once only: the next turn the friendship statement goes out
        game.gameInfo.turns = 51
        DiplomacyAutomation.contactMajorCivs(a)
        assertEquals(51, memory(a, b).statementTurns[DiploStatement.WorkWithUs])
        assertTrue(a.getDiplomacyManager(b)!!.hasFlag(DiplomacyFlags.DeclarationOfFriendship))
    }

    @Test
    fun `an attack on a protected city-state is said to its protector once, whoever the attacker`() {
        val minor = cityState(HexCoord(0, 6))
        protect(a, minor)
        b.getDiplomacyManager(minor)!!.declareWar()
        assertEquals(minor.civID, memory(a, b).protectedMinorAttacked)
        assertEquals(DiploStatement.AttackedProtectedCityState, pick(a, b))
        // An AI receiver gets no message, a human a notification
        human.notifications.clear()
        human.getDiplomacyManager(minor)!!.declareWar()
        assertEquals(DiploStatement.AttackedProtectedCityState, pick(a, human))
        InformationalStatementAi.contact(a, human)
        assertTrue(human.notifications.any { it.text.contains("is angry that you attacked") && it.text.contains(minor.civName) })

        // The original counts the turns up when its DoTurn ends: one contact pass sees the attack
        InformationalStatementAi.endOfTurn(a)
        game.gameInfo.turns = 51
        assertNull(pick(a, b))
    }

    @Test
    fun `bullying the city-state of a protector is said the same turn, and our own bullying the turn after`() {
        val minor = cityState(HexCoord(0, 6))
        protect(b, minor)
        DiplomacyEvents.onCityStateBullied(a, minor)
        // b protects it and a bullied it: b tells a at once (a is the receiver), a tells b the turn after
        assertEquals(DiploStatement.BulliedProtectedCityState, pick(b, a))
        assertNull(pick(a, b))
        game.gameInfo.turns = 51
        assertEquals(DiploStatement.WeBulliedYourMinor, pick(a, b))
        assertNull(pick(b, a))
    }

    @Test
    fun `a killed spy is said by its killer the same turn and by its owner the turn after`() {
        DiplomacyEvents.onSpyKilled(a, b)
        assertEquals(DiploStatement.KilledYourSpy, pick(a, b))
        assertNull(pick(b, a))
        game.gameInfo.turns = 51
        assertNull(pick(a, b))
        assertEquals(DiploStatement.KilledMySpy, pick(b, a))
        // An identified spy is said the same turn
        DiplomacyEvents.onSpyCaught(a, b)
        assertEquals(DiploStatement.CaughtYourSpy, pick(a, b))
    }

    @Test
    fun `a human is asked for the border promise when we have a strong dispute and see plots bought, and not after a promise`() {
        for (other in listOf(b, human)) {
            memory(a, other).landDispute = DisputeLevel.Strong
            memory(a, other).plotBuyingPosture = AggressivePosture.Low
        }
        assertEquals(DiploStatement.PlotBuyingWarning, pick(a, b))
        // The AI is not asked: only the slot is used
        DiplomacyAutomation.contactMajorCivs(a)
        assertTrue(memory(a, b).statementTurns.containsKey(DiploStatement.PlotBuyingWarning))
        assertEquals(1, alerts(human, AlertType.DemandToStopBuyingLandNearUs).size)
        assertTrue(memory(a, human).statementTurns.containsKey(DiploStatement.PlotBuyingWarning))

        // Not again within BORDER_PROMISE_TURNS_EFFECTIVE
        game.gameInfo.turns = 51
        assertNull(pick(a, human))
        // A promise ends it for good
        game.gameInfo.turns = 500
        memory(a, human).borderPromiseTurn = 10
        assertNull(pick(a, human))
    }

    @Test
    fun `the expansion warning needs a lately founded city, a dispute and no promise, and repeats after the promise length`() {
        memory(a, b).landDispute = DisputeLevel.Fierce
        b.ai.diplomacy.turnsSinceSettledLastCity = 9
        assertEquals(DiploStatement.ExpansionWarning, pick(a, b))
        b.ai.diplomacy.turnsSinceSettledLastCity = 10
        assertNull("EXPANSION_BICKER_TIMEOUT", pick(a, b))
        b.ai.diplomacy.turnsSinceSettledLastCity = 0
        memory(a, b).expansionPromiseTurn = 3
        assertNull("they promised once", pick(a, b))
        memory(a, b).expansionPromiseTurn = -1

        memory(a, b).statementTurns[DiploStatement.ExpansionWarning] = 40
        assertNull(pick(a, b))
        game.gameInfo.turns = 40 + rules.define(AiDefine.EXPANSION_PROMISE_TURNS_EFFECTIVE)* DiplomacyTurn.opinionDurationPercent(a) / 100
        assertEquals(DiploStatement.ExpansionWarning, pick(a, b))
    }

    @Test
    fun `founding a city starts the settle counter that the turn counts up`() {
        assertEquals(0, a.ai.diplomacy.turnsSinceSettledLastCity)
        DiplomacyTurn.doTurn(a)
        assertEquals(1, a.ai.diplomacy.turnsSinceSettledLastCity)
    }

    @Test
    fun `a liked World Congress proposal is said by a chatty AI that likes us, and as a notification to a human`() {
        game.gameInfo.gameParameters.victoryTypes.add("Diplomatic")
        cityState(HexCoord(0, 6))
        a.tech.addTechnology("Printing Press")
        val league = League.tryFound(game.gameInfo)!!
        game.gameInfo.league = league
        a.ai.setDiplomacyValue(DiplomacyPersonality.Chattiness, 10)
        for (other in listOf(b, human)) {
            league.members.getValue(a.civID).alignmentMemory.getOrPut(other.civID) { LeagueAlignmentMemory() }
                .likedProposalTurn = 49
            a.ai.diplomacy.setOpinion(other, MajorCivOpinion.Favorable)
        }
        assertEquals(DiploStatement.WeLikedTheirProposal, pick(a, b))
        // Not to someone we are about to attack
        a.ai.diplomacy.setApproach(b, MajorCivApproach.Hostile)
        assertNull(pick(a, b))

        human.notifications.clear()
        InformationalStatementAi.contact(a, human)
        assertTrue(human.notifications.any { it.text.contains("liked your proposal") })
    }
    //endregion

    //region Coop war and friend-denounce requests to a human

    private fun target(): Civilization {
        val target = neutralCiv(HexCoord(0, 9))
        meet(a, target)
        meet(human, target)
        meet(b, target)
        return target
    }

    private fun wantWarOn(target: Civilization) {
        a.ai.diplomacy.setApproach(target, MajorCivApproach.War)
        a.ai.diplomacy.setOpinion(target, MajorCivOpinion.Unforgivable)
        a.ai.grandStrategy = GrandStrategyAi.CONQUEST
    }

    @Test
    fun `an AI at war with no major asks a human to join its war with an alert, once`() {
        val target = target()
        wantWarOn(target)
        DiplomacyAutomation.contactMajorCivs(a)
        val request = alerts(human, AlertType.CoopWarRequest).single()
        assertEquals(PopupAlert.pairValue(a.civID, target.civID), request.value)
        assertEquals(50, memory(a, human).statementTurns[DiploStatement.CoopWarRequest])
        // Nothing is decided yet
        assertNull(CoopWarAi.state(a, human, target))
        assertFalse(a.isAtWarWith(target))

        // Asked again after 10 turns, but not while the first request waits
        game.gameInfo.turns = 61
        DiplomacyAutomation.contactMajorCivs(a)
        assertEquals(1, alerts(human, AlertType.CoopWarRequest).size)
    }

    @Test
    fun `a human who agrees to join the war declares it with the AI and both are locked in`() {
        val target = target()
        CoopWarAi.onHumanAnswered(a, human, target, CoopWarAi.HumanAnswer.Agree, now = false)
        assertTrue(a.isAtWarWith(target))
        assertTrue(human.isAtWarWith(target))
        assertEquals(CoopWarState.Accepted, CoopWarAi.state(a, human, target))
        assertEquals(0, CoopWarAi.counter(a, human, target))
        val locked = rules.define(AiDefine.COOP_WAR_LOCKED_LENGTH)
        assertEquals(locked, memory(a, target).turnsLockedIntoWar)
        assertEquals(locked, memory(human, target).turnsLockedIntoWar)
    }

    @Test
    fun `a human who refuses is not asked about that war for 30 turns and has no penalty`() {
        val target = target()
        wantWarOn(target)
        CoopWarAi.onHumanAnswered(a, human, target, CoopWarAi.HumanAnswer.Refuse, now = false)
        assertNull(CoopWarAi.state(a, human, target))
        assertEquals(0, CoopWarAi.counter(a, human, target))
        assertFalse(memory(a, human).brokenCoopWarPromise)
        assertFalse(a.isAtWarWith(target))
        assertNull(CoopWarAi.coopWarTarget(a, human, rules))
    }

    @Test
    fun `a human who said soon is asked again after the counter and breaks the promise by refusing then`() {
        val target = target()
        wantWarOn(target)
        CoopWarAi.onHumanAnswered(a, human, target, CoopWarAi.HumanAnswer.Soon, now = false)
        assertEquals(CoopWarState.Soon, CoopWarAi.state(a, human, target))
        repeat(rules.define(AiDefine.COOP_WAR_SOON_COUNTER)) { CoopWarAi.doCoopWarCounters(a, rules) }
        // An AI partner would be at war now; a human is asked
        assertFalse(a.isAtWarWith(target))
        DiplomacyAutomation.contactMajorCivs(a)
        assertEquals(PopupAlert.pairValue(a.civID, target.civID), alerts(human, AlertType.CoopWarNow).single().value)

        CoopWarAi.onHumanAnswered(a, human, target, CoopWarAi.HumanAnswer.Refuse, now = true)
        assertNull(CoopWarAi.state(a, human, target))
        assertTrue(memory(a, human).brokenCoopWarPromise)
    }

    @Test
    fun `a human at war with the target already is taken to have agreed when the counter ends`() {
        val target = target()
        CoopWarAi.onHumanAnswered(a, human, target, CoopWarAi.HumanAnswer.Soon, now = false)
        human.getDiplomacyManager(target)!!.declareWar()
        repeat(rules.define(AiDefine.COOP_WAR_SOON_COUNTER)) { CoopWarAi.doCoopWarCounters(a, rules) }
        DiplomacyAutomation.contactMajorCivs(a)
        assertEquals(CoopWarState.Accepted, CoopWarAi.state(a, human, target))
        assertTrue(a.isAtWarWith(target))
        assertTrue(alerts(human, AlertType.CoopWarNow).isEmpty())
    }

    @Test
    fun `an AI friend asks a human to denounce its enemy, and denounces the human if the refusal is unacceptable`() {
        val target = target()
        a.getDiplomacyManager(human)!!.signDeclarationOfFriendship()
        a.getDiplomacyManager(target)!!.declareWar()
        a.ai.setDiplomacyValue(DiplomacyPersonality.DenounceWillingness, 40)
        a.ai.setDiplomacyValue(DiplomacyPersonality.Neediness, 40)
        a.ai.diplomacy.setApproach(target, MajorCivApproach.Hostile)
        a.ai.diplomacy.setOpinion(target, MajorCivOpinion.Unforgivable)
        assertTrue(OpinionAi.isDoFAccepted(a, human))
        // The original's roll may fail and is logged for 10 turns; with this willingness the request goes out at the latest then
        var sent = 0
        for (turn in 50..120 step 11) {
            game.gameInfo.turns = turn
            DiplomacyAutomation.contactMajorCivs(a)
            if (alerts(human, AlertType.DenounceRequest).isNotEmpty()) { sent = turn; break }
        }
        assertTrue("no request", sent > 0)
        assertEquals(PopupAlert.pairValue(a.civID, target.civID), alerts(human, AlertType.DenounceRequest).single().value)
        assertEquals(sent, memory(a, human).statementTurns[DiploStatement.RequestFriendDenounce])

        DiplomacyAutomation.onHumanAnsweredDenounceRequest(a, human, target, agreed = false)
        assertTrue("a refusal this serious costs the friendship", a.getDiplomacyManager(human)!!.hasFlag(DiplomacyFlags.Denunciation))
        assertFalse(human.getDiplomacyManager(target)!!.hasFlag(DiplomacyFlags.Denunciation))
    }

    @Test
    fun `agreeing to a denounce request denounces the target and a mild refusal costs nothing`() {
        val target = target()
        a.getDiplomacyManager(human)!!.signDeclarationOfFriendship()
        DiplomacyAutomation.onHumanAnsweredDenounceRequest(a, human, target, agreed = false)
        assertFalse(a.getDiplomacyManager(human)!!.hasFlag(DiplomacyFlags.Denunciation))
        assertTrue(a.getDiplomacyManager(human)!!.hasFlag(DiplomacyFlags.DeclarationOfFriendship))

        DiplomacyAutomation.onHumanAnsweredDenounceRequest(a, human, target, agreed = true)
        assertTrue(human.getDiplomacyManager(target)!!.hasFlag(DiplomacyFlags.Denunciation))
    }
    //endregion
}
