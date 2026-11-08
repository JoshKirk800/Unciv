package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.PeaceTreatyType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.HexCoord
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Decision-level checks of the CvDealAI port against the DLL formulas with the BNW XML values */
@RunWith(GdxTestRunner::class)
class DealAiTests {
    private lateinit var game: TestGame
    private lateinit var a: Civilization
    private lateinit var b: Civilization
    private lateinit var c: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        a = neutralCiv(HexCoord(0, 0))
        b = neutralCiv(HexCoord(8, 0))
        c = neutralCiv(HexCoord(-8, 0))
        a.diplomacyFunctions.makeCivilizationsMeet(b)
        a.diplomacyFunctions.makeCivilizationsMeet(c)
        b.diplomacyFunctions.makeCivilizationsMeet(c)
    }

    private fun neutralCiv(at: HexCoord): Civilization {
        val civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        for (approach in MajorCivApproach.entries) civ.ai.setMajorCivApproachBias(approach, 5)
        game.addCity(civ, game.getTile(at))
        return civ
    }

    private fun feelings(civ: Civilization, other: Civilization, approach: MajorCivApproach, opinion: MajorCivOpinion) {
        civ.ai.diplomacy.setApproach(other, approach)
        civ.ai.diplomacy.setOpinion(other, opinion)
    }

    private fun gold(amount: Int) = TradeOffer(Constants.flatGold, TradeOfferType.Gold, amount, -1)
    private fun gpt(amount: Int, turns: Int = 30) = TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, amount, turns)

    private fun signDoF(x: Civilization, y: Civilization) {
        x.getDiplomacyManager(y)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 50)
        y.getDiplomacyManager(x)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 50)
    }

    //region Item values

    @Test
    fun `gold we give is marked up by approach then opinion`() {
        feelings(a, b, MajorCivApproach.Hostile, MajorCivOpinion.Enemy)
        // 100 × EACH_GOLD_VALUE_PERCENT 100 / 100, × 150% (Hostile), × 140% (Enemy)
        assertEquals(210, DealAi.getGoldForValueExchange(a, b, 100, false, true, false, false))
        assertEquals(100, DealAi.getGoldForValueExchange(a, b, 100, false, false, false, false))
        // The reverse: the gold worth 210 value when we give it
        assertEquals(100, DealAi.getGoldForValueExchange(a, b, 210, true, true, false, false))
        feelings(a, b, MajorCivApproach.Guarded, MajorCivOpinion.Unforgivable)
        assertEquals(220, DealAi.getGoldForValueExchange(a, b, 100, false, true, false, false))
    }

    @Test
    fun `gold per turn is worth 85 percent per turn and ours carries the 130 percent markup`() {
        // 5 × 30 × 85 / 100
        assertEquals(127, DealAi.getGptForValueExchange(a, b, 5, false, 30, false, false, false))
        // 5 × 30 × 85 × 130% / 100
        assertEquals(165, DealAi.getGptForValueExchange(a, b, 5, false, 30, true, false, false))
        feelings(a, b, MajorCivApproach.Hostile, MajorCivOpinion.Competitor)
        // 12750 × 130% = 16575, × 150% = 24862, × 115% = 28591, / 100
        assertEquals(285, DealAi.getGptForValueExchange(a, b, 5, false, 30, true, false, false))
    }

    @Test
    fun `even value averages both players' valuations`() {
        feelings(a, b, MajorCivApproach.Hostile, MajorCivOpinion.Neutral)
        // a gives: 150 by its valuation, b receives: 100 by its valuation
        assertEquals(125, DealAi.getGoldForValueExchange(a, b, 100, false, true, true, false))
        val trade = Trade()
        trade.ourOffers.add(gold(100))
        assertEquals(-125, DealAi.getDealValue(a, b, trade, true).total)
        assertEquals(-150, DealAi.getDealValue(a, b, trade, false).total)
    }

    @Test
    fun `a luxury is worth happiness times turns times two and ours is scaled by opinion and approach`() {
        val silk = game.ruleset.tileResources["Silk"]!!
        // 1 × 4 × 30 × 2
        assertEquals(240, DealAi.getResourceValue(a, b, silk, 1, 30, false))
        // Giving (not our last copy): (100 Neutral opinion + 300 Hostile) / 200
        feelings(a, b, MajorCivApproach.Hostile, MajorCivOpinion.Neutral)
        assertEquals(480, DealAi.getResourceValue(a, b, silk, 1, 30, true))
        // Friendly or Afraid replaces the sum with 200: no change
        feelings(a, b, MajorCivApproach.Friendly, MajorCivOpinion.Unforgivable)
        assertEquals(240, DealAi.getResourceValue(a, b, silk, 1, 30, true))
    }

    @Test
    fun `a strategic we receive counts at most max(5, cities) units`() {
        val iron = game.ruleset.tileResources["Iron"]!!
        // min(max(5, 1 city), 8) × 30 × 1.5
        assertEquals(225, DealAi.getResourceValue(a, b, iron, 8, 30, false))
        // Giving counts every unit: 8 × 30 × 1.5 × (100 + 100) / 200
        assertEquals(360, DealAi.getResourceValue(a, b, iron, 8, 30, true))
    }

    @Test
    fun `an embassy is worth 35 and ours costs more when hostile`() {
        val standard = game.ruleset.speeds["Standard"]!!.dealDuration
        val base = 35 * game.gameInfo.speed.dealDuration / standard
        assertEquals(base, DealAi.getEmbassyValue(a, b, false, false))
        feelings(a, b, MajorCivApproach.Hostile, MajorCivOpinion.Neutral)
        assertEquals(base * 250 / 100, DealAi.getEmbassyValue(a, b, true, false))
    }

    @Test
    fun `open borders we grant are priced by approach then opinion, Friendly is 50`() {
        feelings(a, b, MajorCivApproach.Hostile, MajorCivOpinion.Neutral)
        assertEquals(1000, DealAi.getOpenBordersValue(a, b, true, false))
        feelings(a, b, MajorCivApproach.Guarded, MajorCivOpinion.Enemy)
        assertEquals(400, DealAi.getOpenBordersValue(a, b, true, false))
        feelings(a, b, MajorCivApproach.Neutral, MajorCivOpinion.Favorable)
        // 75 × 70%
        assertEquals(52, DealAi.getOpenBordersValue(a, b, true, false))
        feelings(a, b, MajorCivApproach.Friendly, MajorCivOpinion.Unforgivable)
        assertEquals(50, DealAi.getOpenBordersValue(a, b, true, false))
        assertEquals(50, DealAi.getOpenBordersValue(a, b, false, false))
    }

    @Test
    fun `open borders we receive from a one-city civ are worth half the proximity value`() {
        val byProximity = when (a.getProximity(b)) {
            com.unciv.logic.civilization.Proximity.Distant -> 5
            com.unciv.logic.civilization.Proximity.Far -> 10
            com.unciv.logic.civilization.Proximity.Close -> 15
            com.unciv.logic.civilization.Proximity.Neighbors -> 30
            com.unciv.logic.civilization.Proximity.None -> 0
        }
        assertEquals(byProximity * 50 / 100, DealAi.getOpenBordersValue(a, b, false, false))
    }

    @Test
    fun `a research agreement we grant costs 100 more per era they are ahead`() {
        assertEquals(100, DealAi.getResearchAgreementValue(a, b, true, false))
        b.tech.addTechnology("Education") // Medieval: 2 eras ahead
        assertEquals(300, DealAi.getResearchAgreementValue(a, b, true, false))
        assertEquals(100, DealAi.getResearchAgreementValue(a, b, false, false))
        feelings(a, b, MajorCivApproach.Hostile, MajorCivOpinion.Neutral)
        assertEquals(3000, DealAi.getResearchAgreementValue(a, b, true, false))
        feelings(a, b, MajorCivApproach.Afraid, MajorCivOpinion.Neutral)
        assertEquals(240, DealAi.getResearchAgreementValue(a, b, true, false))
    }

    @Test
    fun `a defensive pact is granted only from Favorable opinion`() {
        feelings(a, b, MajorCivApproach.Neutral, MajorCivOpinion.Favorable)
        assertEquals(100, DealAi.getDefensivePactValue(a, b, true, false))
        feelings(a, b, MajorCivApproach.Neutral, MajorCivOpinion.Neutral)
        assertEquals(DealAi.NEVER, DealAi.getDefensivePactValue(a, b, true, false))
        assertEquals(100, DealAi.getDefensivePactValue(a, b, false, false))
    }

    @Test
    fun `third-party war is refused below war bias 4 and priced by projection, bias, hatred and liking`() {
        a.ai.setMajorCivApproachBias(MajorCivApproach.War, 3)
        assertEquals(DealAi.NEVER, DealAi.getThirdPartyWarValue(a, b, c, true))
        a.ai.setMajorCivApproachBias(MajorCivApproach.War, 7)
        // Unknown projection 600, era 0; bias 5 - 7 = -2: 600 - 20% = 480
        assertEquals(480, DealAi.getThirdPartyWarValue(a, b, c, true))
        feelings(a, c, MajorCivApproach.Neutral, MajorCivOpinion.Competitor)
        feelings(a, b, MajorCivApproach.Neutral, MajorCivOpinion.Friend)
        // × 75% (we dislike the target) × 75% (we like the asker)
        assertEquals(270, DealAi.getThirdPartyWarValue(a, b, c, true))
        // A war b declares on c: only worth something if we hate c
        assertEquals(-DealAi.NEVER, DealAi.getThirdPartyWarValue(a, b, c, false))
        feelings(a, c, MajorCivApproach.Neutral, MajorCivOpinion.Enemy)
        assertEquals(100, DealAi.getThirdPartyWarValue(a, b, c, false))
    }

    @Test
    fun `third-party peace is priced by projection, era, hatred of the enemy and liking of the asker`() {
        assertEquals(-10000, DealAi.getThirdPartyPeaceValue(a, b, c, false))
        // Unknown projection 250, era 0
        assertEquals(250, DealAi.getThirdPartyPeaceValue(a, b, c, true))
        feelings(a, c, MajorCivApproach.Neutral, MajorCivOpinion.Enemy)
        assertEquals(500, DealAi.getThirdPartyPeaceValue(a, b, c, true))
        feelings(a, b, MajorCivApproach.Neutral, MajorCivOpinion.Favorable)
        assertEquals(375, DealAi.getThirdPartyPeaceValue(a, b, c, true))
    }

    @Test
    fun `a city we give is doubled at peace and multiplied by the war projection`() {
        val city = a.cities.first()
        val received = DealAi.getCityValue(b, a, city, false, false)
        val given = DealAi.getCityValue(a, b, city, true, false)
        // Unknown projection 250%, ×2 when not at war; the original capital doubling applies to both
        val base = received / 2
        assertEquals(base * 250 / 100 * 2 * 2, given)
    }
    //endregion

    //region Gold gate and acceptance

    @Test
    fun `lump gold needs a mutual Declaration of Friendship outside peace deals`() {
        a.addGold(500)
        val trade = Trade()
        trade.ourOffers.add(gold(100))
        val available = TradeLogic(a, b).ourAvailableOffers
        assertFalse(DealAi.isPossibleToTradeItem(Trade(), a, b, gold(100), true, available))
        assertFalse(TradeEvaluation().isTradeValid(trade, a, b))
        a.getDiplomacyManager(b)!!.setFlag(DiplomacyFlags.DeclarationOfFriendship, 50)
        assertFalse(DealAi.isPossibleToTradeItem(Trade(), a, b, gold(100), true, available))
        signDoF(a, b)
        assertTrue(DealAi.isPossibleToTradeItem(Trade(), a, b, gold(100), true, available))
        assertTrue(TradeEvaluation().isTradeValid(trade, a, b))
        assertFalse(DealAi.isPossibleToTradeItem(Trade(), a, b, gold(600), true, available))
    }

    @Test
    fun `lump gold is allowed in a peace deal without friendship`() {
        a.getDiplomacyManager(b)!!.declareWar()
        a.addGold(500)
        val trade = Trade()
        trade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, 1, 10))
        trade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, 1, 10))
        assertTrue(DealAi.isPossibleToTradeItem(trade, a, b, gold(100), true, TradeLogic(a, b).ourAvailableOffers))
    }

    @Test
    fun `between AIs each side must receive at least 75 percent of what it gives`() {
        signDoF(a, b)
        a.addGold(500)
        b.addGold(500)
        // a gives 100 gold (100) and receives 3 GPT: 3 × 30 × 85% = 76 >= 75; b gives 3 GPT worth 99 (130% markup) and gets 100
        val trade = Trade()
        trade.ourOffers.add(gold(100))
        trade.theirOffers.add(gpt(3))
        assertTrue(DealAi.isAcceptableToBothAIs(a, b, trade))
        // 2 GPT: a receives 51 < 75
        trade.theirOffers.clear()
        trade.theirOffers.add(gpt(2))
        assertFalse(DealAi.isAcceptableToBothAIs(a, b, trade))
    }

    @Test
    fun `an AI deal within 25 percent of its even-value size is accepted as it is`() {
        signDoF(a, b)
        val trade = Trade()
        // even values: 100 gold given is worth 100 to both; 3 GPT is (76 + 99) / 2 = 87: total -13 within ±(100 + 87) × 25%
        trade.ourOffers.add(gold(100))
        trade.theirOffers.add(gpt(3))
        val even = DealAi.getDealValue(a, b, trade, true)
        assertEquals(87, even.theyreOffering)
        assertEquals(100, even.imOffering)
        assertTrue(DealAi.isAcceptableBetweenAIs(a, b, trade))
    }

    @Test
    fun `a human deal without gold is acceptable within 25 value`() {
        val human = game.addCiv(isPlayer = true)
        game.addCity(human, game.getTile(HexCoord(0, 8)))
        a.diplomacyFunctions.makeCivilizationsMeet(human)
        val trade = Trade()
        // They give an embassy (35) for open borders we give (Neutral: 75): 40 apart
        trade.theirOffers.add(TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = game.gameInfo.speed))
        trade.ourOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, 30))
        assertFalse(DealAi.isDealWithHumanAcceptable(a, human, trade).acceptable)
        // Favorable: 75 × 70% = 52, 17 apart
        a.ai.diplomacy.setOpinion(human, MajorCivOpinion.Favorable)
        assertTrue(DealAi.isDealWithHumanAcceptable(a, human, trade).acceptable)
    }
    //endregion

    //region Peace

    @Test
    fun `an armistice gives half the loser's gold, a settlement all of it`() {
        a.getDiplomacyManager(b)!!.declareWar()
        a.addGold(300 - a.gold)
        val trade = Trade()
        DealAi.addItemsToDealForPeaceTreaty(trade, a, b, PeaceTreatyType.Armistice, meSurrendering = true)
        assertEquals(150, trade.ourOffers.first { it.type == TradeOfferType.Gold }.amount)
        val settlement = Trade()
        DealAi.addItemsToDealForPeaceTreaty(settlement, a, b, PeaceTreatyType.Settlement, meSurrendering = true)
        assertEquals(300, settlement.ourOffers.first { it.type == TradeOfferType.Gold }.amount)
        assertTrue(settlement.theirOffers.none { it.type == TradeOfferType.Gold })
    }

    @Test
    fun `AIs make peace only when what each offers covers what the other accepts`() {
        a.getDiplomacyManager(b)!!.declareWar()
        val ours = a.ai.diplomacy.memoryOf(b)
        val theirs = b.ai.diplomacy.memoryOf(a)
        ours.treatyWillingToOffer = PeaceTreatyType.WhitePeace
        ours.treatyWillingToAccept = PeaceTreatyType.WhitePeace
        theirs.treatyWillingToOffer = PeaceTreatyType.WhitePeace
        theirs.treatyWillingToAccept = PeaceTreatyType.Armistice
        assertFalse(DealAi.isOfferPeace(a, b, Trade()))
        ours.treatyWillingToOffer = PeaceTreatyType.Armistice
        val trade = Trade()
        assertTrue(DealAi.isOfferPeace(a, b, trade))
        assertTrue(trade.isPeaceTreaty())
    }
    //endregion

    //region Renewal

    @Test
    fun `an ended deal with a renewable item is kept for renewal, one with an embassy is not`() {
        val renewable = Trade()
        renewable.ourOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, 0))
        renewable.theirOffers.add(gold(50))
        DealAi.onDealEnded(a, b, renewable)
        assertEquals(1, a.ai.deals.expiredDeals[b.civID]!!.size)
        val embassy = Trade()
        embassy.ourOffers.add(TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, 0))
        embassy.theirOffers.add(TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = game.gameInfo.speed))
        DealAi.onDealEnded(a, b, embassy)
        assertEquals(1, a.ai.deals.expiredDeals[b.civID]!!.size)
        val goldOnly = Trade()
        goldOnly.ourOffers.add(gold(50))
        assertFalse(DealAi.isPotentiallyRenewable(goldOnly))
    }
    //endregion
}
