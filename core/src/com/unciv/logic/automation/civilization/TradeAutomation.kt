package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.strategy.PurchaseType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeRequest

/**
 *  The trades an AI answers and the offers it makes (the deal part of CvDiplomacyAI::DoContactPlayer), all valued by [DealAi].
 *  Between AIs an offer is balanced before it is made and then carried out at once, as the original does; a human gets a trade request.
 */
object TradeAutomation {

    /**
     *  The deals offered to an AI: from a human, CvDealAI::DoHumanOfferDealToThisAI (accepted when acceptable or a gift; otherwise a
     *  counteroffer balanced by CvDealAI::DoEqualizeDealWithHuman keeping what the human asked for, if there is one); from another AI
     *  (Great Work swaps), the AI rule of [DealAi.isAcceptableBetweenAIs]; a demand of an AI ([TradeRequest.isDemand]) is always given in to,
     *  as CvDiplomacyAI::DoSendStatementToPlayer does for an AI receiver.
     */
    fun respondToTradeRequests(civInfo: Civilization, tradeAndChangeState: Boolean) {
        for (tradeRequest in civInfo.tradeRequests.toList()) {
            val otherCiv = civInfo.gameInfo.getCivilization(tradeRequest.requestingCiv)
            // Treat 'no trade' state as if all trades are invalid - thus AIs will not update its "turns to offer"
            if (!tradeAndChangeState || !TradeEvaluation().isTradeValid(tradeRequest.trade, civInfo, otherCiv))
                continue

            val tradeLogic = TradeLogic(civInfo, otherCiv)
            tradeLogic.currentTrade.set(tradeRequest.trade)
            /** We need to remove this here, so that if the trade is accepted, the updateDetailedCivResources()
             * in tradeLogic.acceptTrade() will not consider *both* the trade *and the trade offer as decreasing the
             * amount of available resources, since that will lead to "Our proposed trade is no longer valid" if we try to offer
             * the same resource to ANOTHER civ in this turn. Complicated!
             */
            civInfo.tradeRequests.remove(tradeRequest)
            val accepted = if (otherCiv.isHuman()) {
                val response = DealAi.respondToHumanDeal(civInfo, otherCiv, tradeLogic.currentTrade)
                if (response == DealAi.DealResponse.Accepted) {
                    // CvDealAI::DoAcceptedDeal: valued before the items move
                    val value = DealAi.getDealValue(civInfo, otherCiv, tradeLogic.currentTrade, false)
                    DealAi.onHumanDealAccepted(civInfo, otherCiv, tradeLogic.currentTrade, value)
                    true
                } else false
            } else tradeRequest.isDemand || DealAi.isAcceptableBetweenAIs(civInfo, otherCiv, tradeLogic.currentTrade)
            if (accepted) {
                tradeLogic.acceptTrade()
                otherCiv.addNotification("[${civInfo.civName}] has accepted your trade request", NotificationCategory.Trade, NotificationIcon.Trade, civInfo.civName)
                continue
            }
            val counteroffer = if (otherCiv.isHuman() && tradeRequest.trade.ourOffers.none { it.type == TradeOfferType.GreatWork })
                getCounteroffer(civInfo, otherCiv, tradeRequest.trade) else null
            if (counteroffer != null) {
                otherCiv.addNotification("[${civInfo.civName}] has made a counteroffer to your trade request", NotificationCategory.Trade, NotificationIcon.Trade, civInfo.civName)
                otherCiv.tradeRequests.add(counteroffer)
            } else {
                otherCiv.addNotification("[${civInfo.civName}] has denied your trade request", NotificationCategory.Trade, civInfo.civName, NotificationIcon.Trade)
                tradeRequest.decline(civInfo)
            }
        }
        civInfo.tradeRequests.clear()
    }

    /**
     *  The original's "What would make this deal work?": CvDealAI::DoEqualizeDealWithHuman on [trade] (seen from [civInfo]) without
     *  touching what the human asked of us. Null when no balanced deal different from the proposal comes out.
     */
    private fun getCounteroffer(civInfo: Civilization, human: Civilization, trade: Trade): TradeRequest? {
        val counter = trade.clone()
        if (!DealAi.doEqualizeDealWithHuman(civInfo, human, counter, dontChangeMyExistingItems = true, dontChangeTheirExistingItems = false))
            return null
        if (counter.equalTrade(trade) || counter.theirOffers.isEmpty() && counter.ourOffers.isEmpty()) return null
        if (!TradeEvaluation().isTradeValid(counter, civInfo, human)) return null
        return TradeRequest(civInfo.civID, counter.reverse())
    }

    /**
     *  The offers of CvDiplomacyAI::DoContactPlayer, in its order, first one made per player and turn: DoLuxuryTrade, DoEmbassyExchange,
     *  DoEmbassyOffer, DoOpenBordersExchange, DoOpenBordersOffer, DoResearchAgreementOffer, DoRenewExpiredDeal. Players at peace with us
     *  that we have met before this turn and have not contacted yet this turn, AIs first. BNW's AI offers no defensive pact, war
     *  declaration or third-party peace deal.
     */
    fun doOffers(civ: Civilization) = timeThis("AI.DealOffers") {
        val players = DiplomacyTurn.validPlayers(civ).filter { it.isMajorCiv && it != civ }.sortedBy { it.isHuman() }
        for (other in players) {
            if (civ.isAtWarWith(other) || !canContact(civ, other) || civ.ai.diplomacy.hasMadeStatementThisTurn(other)) continue
            val trade = Trade()
            val statement = chooseOffer(civ, other, trade) ?: continue
            sendOffer(civ, other, statement, trade)
        }
    }

    /** DoContactPlayer waits until the turn after meeting */
    private fun canContact(civ: Civilization, other: Civilization): Boolean {
        val met = civ.ai.diplomacy.memoryOf(other).turnMet
        return met < 0 || civ.gameInfo.turns - met > 0
    }

    private fun turnsSince(civ: Civilization, other: Civilization, statement: DealStatement) =
        civ.ai.deals.turnsSince(other.civID, statement, civ.gameInfo.turns)

    private fun log(civ: Civilization, other: Civilization, statement: DealStatement) =
        civ.ai.deals.log(other.civID, statement, civ.gameInfo.turns)

    /** The offer statements of DoContactPlayer in order: the first one that wants to be sent fills [trade] */
    fun chooseOffer(civ: Civilization, other: Civilization, trade: Trade): DealStatement? {
        val turnsBetween = 20
        val logic = TradeLogic(civ, other)
        val dealDuration = civ.gameInfo.speed.dealDuration
        val emptyTrade = Trade()

        // DoLuxuryTrade
        if (DealAi.isMakeOfferForLuxuryResource(civ, other, trade)) {
            if (turnsSince(civ, other, DealStatement.LuxuryTrade) >= turnsBetween) return DealStatement.LuxuryTrade
        } else clear(trade)

        // DoEmbassyExchange
        val embassy = TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = civ.gameInfo.speed)
        if (DealAi.isPossibleToTradeItem(emptyTrade, civ, other, embassy, true, logic.ourAvailableOffers)
            && DealAi.isPossibleToTradeItem(emptyTrade, other, civ, embassy, false, logic.theirAvailableOffers)
            && DealAi.isEmbassyExchangeAcceptable(civ, other)
            && turnsSince(civ, other, DealStatement.EmbassyExchange) >= turnsBetween
            && turnsSince(civ, other, DealStatement.EmbassyOffer) >= 10) {
            var send = other.isHuman() || DealAi.isEmbassyExchangeAcceptable(other, civ)
            if (50 < civ.ai.random("DiplomacyAI.EmbassyExchange" + other.civID).nextInt(100)) send = false
            if (send) {
                trade.ourOffers.add(embassy.copy())
                trade.theirOffers.add(embassy.copy())
                return DealStatement.EmbassyExchange
            }
            log(civ, other, DealStatement.EmbassyExchange)
        }

        // DoEmbassyOffer
        if (DealAi.makeOfferForEmbassy(civ, other, trade)) {
            if (turnsSince(civ, other, DealStatement.EmbassyOffer) >= turnsBetween
                && turnsSince(civ, other, DealStatement.EmbassyExchange) >= 10) return DealStatement.EmbassyOffer
        } else clear(trade)

        // DoOpenBordersExchange
        val openBorders = TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, dealDuration)
        if (DealAi.isPossibleToTradeItem(emptyTrade, civ, other, openBorders, true, logic.ourAvailableOffers)
            && DealAi.isPossibleToTradeItem(emptyTrade, other, civ, openBorders, false, logic.theirAvailableOffers)
            && DealAi.isOpenBordersExchangeAcceptable(civ, other)
            && turnsSince(civ, other, DealStatement.OpenBordersExchange) >= turnsBetween) {
            var send = other.isHuman() || DealAi.isOpenBordersExchangeAcceptable(other, civ)
            if (50 < civ.ai.random("DiplomacyAI.OpenBordersExchange" + other.civID).nextInt(100)) send = false
            if (send) {
                trade.ourOffers.add(openBorders.copy())
                trade.theirOffers.add(openBorders.copy())
                return DealStatement.OpenBordersExchange
            }
            log(civ, other, DealStatement.OpenBordersExchange)
        }

        // DoOpenBordersOffer
        if (DealAi.isMakeOfferForOpenBorders(civ, other, trade)) {
            if (turnsSince(civ, other, DealStatement.OpenBordersOffer) >= turnsBetween) return DealStatement.OpenBordersOffer
        } else clear(trade)

        // DoResearchAgreementOffer
        if (isCanMakeResearchAgreementRightNow(civ, other)) {
            if (DealAi.isMakeOfferForResearchAgreement(civ, other, trade)) {
                if (turnsSince(civ, other, DealStatement.ResearchAgreementOffer) >= turnsBetween) return DealStatement.ResearchAgreementOffer
            } else clear(trade)
        }

        // DoRenewExpiredDeal
        if (DealAi.doRenewExpiredDeal(civ, other, trade)) return DealStatement.RenewDeal
        clear(trade)
        return null
    }

    private fun clear(trade: Trade) {
        trade.ourOffers.clear()
        trade.theirOffers.clear()
    }

    /**
     *  CvDiplomacyAI::IsCanMakeResearchAgreementRightNow: we want one (DoUpdatePlanningExchanges), have none, may sign one, and both
     *  sides can pay for it
     */
    fun isCanMakeResearchAgreementRightNow(civ: Civilization, other: Civilization): Boolean {
        if (civ.tech.allTechsAreResearched() || other.tech.allTechsAreResearched()) return false
        if (!civ.ai.diplomacy.wantsResearchAgreementWith(other)) return false
        // Unciv's rule (the technology, embassies, a Declaration of Friendship, no agreement yet, both able to pay)
        return civ.diplomacyFunctions.canSignResearchAgreementWith(other)
    }

    /**
     *  CvDiplomacyAI::DoSendStatementToPlayer for the offers: an AI gets the deal carried out (it was checked for both sides before),
     *  a human gets a trade request. The statement is logged for its 20-turn interval and counts as this turn's contact.
     */
    private fun sendOffer(civ: Civilization, other: Civilization, statement: DealStatement, trade: Trade) {
        log(civ, other, statement)
        civ.ai.diplomacy.markStatementMade(other)
        if (!TradeEvaluation().isTradeValid(trade, civ, other)) return
        if (other.isHuman()) {
            if (other.tradeRequests.none { it.requestingCiv == civ.civID })
                other.tradeRequests.add(TradeRequest(civ.civID, trade.reverse()))
            return
        }
        val logic = TradeLogic(civ, other)
        logic.currentTrade.set(trade)
        logic.acceptTrade()
        if (statement == DealStatement.ResearchAgreementOffer)
            civ.ai.purchaseSavings.cancelSaveForPurchase(PurchaseType.MajorCivTrade)
    }
}
