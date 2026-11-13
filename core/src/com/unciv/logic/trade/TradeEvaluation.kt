package com.unciv.logic.trade

import com.unciv.Constants
import com.unciv.logic.automation.civilization.DealAi
import com.unciv.logic.automation.civilization.LeagueAutomation
import com.unciv.logic.automation.civilization.VoteCommitmentItem
import com.unciv.logic.automation.diplomacy.WarGoal
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import yairm210.purity.annotations.Readonly

/** Whether a trade can be made (CvDeal::IsPossibleToTradeItem) and whether an AI accepts it (through [DealAi]) */
class TradeEvaluation {

    @Readonly
    fun isTradeValid(trade: Trade, offerer: Civilization, tradePartner: Civilization): Boolean {

        // Edge case time! Guess what happens if you offer a peace agreement to the AI for all their cities except for the capital,
        // and then capture their capital THAT SAME TURN? It can agree, leading to the civilization getting instantly destroyed!
        // If a civ has never owned an original capital, which means it has not settled the first city yet, 
        // it shouldn't be forbidden to trade with other civs owing to cities.size == 0.
        if ((offerer.hasEverOwnedOriginalCapital && trade.ourOffers.count { it.type == TradeOfferType.City } == offerer.cities.size)
            || (tradePartner.hasEverOwnedOriginalCapital && trade.theirOffers.count { it.type == TradeOfferType.City } == tradePartner.cities.size)) {
            return false
        }

        // CvDeal::IsPossibleToTradeItem: a deal cannot hold a second vote commitment of the same civilization
        if (trade.ourOffers.count { it.type == TradeOfferType.VoteCommitment } > 1
            || trade.theirOffers.count { it.type == TradeOfferType.VoteCommitment } > 1) return false
        
        val ourWorks = trade.ourOffers.filter { it.type == TradeOfferType.GreatWork }
        val theirWorks = trade.theirOffers.filter { it.type == TradeOfferType.GreatWork }
        if (ourWorks.isNotEmpty() || theirWorks.isNotEmpty()) {
            if (!offerer.isMajorCiv || !tradePartner.isMajorCiv || offerer.isAtWarWith(tradePartner)
                || ourWorks.size != 1 || theirWorks.size != 1
                || trade.ourOffers.size != 1 || trade.theirOffers.size != 1
                || ourWorks[0].amount != 1 || theirWorks[0].amount != 1) return false
            val ourSlot = GreatWorkFunctions.findTradeSlot(offerer, ourWorks[0].name) ?: return false
            val theirSlot = GreatWorkFunctions.findTradeSlot(tradePartner, theirWorks[0].name) ?: return false
            if (!GreatWorkFunctions.canExchange(ourSlot, theirSlot)) return false
        }
        // No way to tell who offers what in isOfferValid()
        val embassyOffer = TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = offerer.gameInfo.speed)
        val theirDiploManager = tradePartner.getDiplomacyManager(offerer)!!
        val ourDiploManager = offerer.getDiplomacyManager(tradePartner)!!

        if (trade.ourOffers.contains(embassyOffer)
            && (offerer.getCapital() == null
            || theirDiploManager.hasModifier(DiplomaticModifiers.EstablishedEmbassy)
            || theirDiploManager.hasModifier(DiplomaticModifiers.SharedEmbassies)))
            return false

        if (trade.theirOffers.contains(embassyOffer)
            && (tradePartner.getCapital() == null
            || ourDiploManager.hasModifier(DiplomaticModifiers.EstablishedEmbassy)
            || ourDiploManager.hasModifier(DiplomaticModifiers.SharedEmbassies)))
            return false

        // One side can hand the other the gold for a research agreement in the same trade
        val goldFromThem = trade.theirOffers.filter { it.type == TradeOfferType.Gold }.sumOf { it.amount }
        val goldFromUs = trade.ourOffers.filter { it.type == TradeOfferType.Gold }.sumOf { it.amount }
        for (offer in trade.ourOffers)
            if (!isOfferValid(offer, offerer, tradePartner, trade.isPeaceTreaty(), goldFromThem - goldFromUs)) {
                return false
            }            

        for (offer in trade.theirOffers)
            if (!isOfferValid(offer, tradePartner, offerer, trade.isPeaceTreaty(), goldFromUs - goldFromThem)) {
                return false
            }
        return true
    }

    @Readonly @Suppress("purity") // the vote commitment check reads the league AI
    private fun isOfferValid(tradeOffer: TradeOffer, offerer: Civilization, tradePartner: Civilization, isPeaceDeal: Boolean, netGoldReceived: Int): Boolean {

        @Readonly
        fun hasResource(tradeOffer: TradeOffer): Boolean {
            val resourcesByName = offerer.getCivResourcesByName()
            return resourcesByName.containsKey(tradeOffer.name) && resourcesByName[tradeOffer.name]!! >= tradeOffer.amount
        }

        return when (tradeOffer.type) {
            TradeOfferType.Embassy -> true // Already checked
            // CvDeal::IsPossibleToTradeItem: lump gold only between civs with a mutual Declaration of Friendship, except in peace deals
            TradeOfferType.Gold -> (isPeaceDeal || hasMutualDeclarationOfFriendship(offerer, tradePartner)) && tradeOffer.amount <= offerer.gold
            TradeOfferType.Gold_Per_Turn -> tradeOffer.amount <= offerer.stats.statsForNextTurn.gold
            TradeOfferType.Treaty -> {
                // Current automation should prevent these from being offered anyway, 
                //   these are a safeguard against future automation changes 
                when (tradeOffer.name) {
                    Constants.peaceTreaty -> offerer.isAtWarWith(tradePartner)
                    Constants.researchAgreement -> !offerer.getDiplomacyManager(tradePartner)!!.hasFlag(DiplomacyFlags.ResearchAgreement) && offerer.gold + netGoldReceived >= tradeOffer.amount
                    Constants.defensivePact -> !offerer.getDiplomacyManager(tradePartner)!!.hasFlag(DiplomacyFlags.DefensivePact)
                    else -> true // potentional future treaties
                }
            }
            TradeOfferType.Agreement -> true
            TradeOfferType.Luxury_Resource -> hasResource(tradeOffer) && offerer.gameInfo.league?.isLuxuryBanned(tradeOffer.name) != true // World Congress Ban Luxury (LR 4.3.3)
            TradeOfferType.Strategic_Resource -> hasResource(tradeOffer)
            TradeOfferType.Stockpiled_Resource -> hasResource(tradeOffer)
            TradeOfferType.Technology -> offerer.tech.isResearched(tradeOffer.name) &&
                tradeOffer.name !in offerer.tech.noTradeTechnologies
            TradeOfferType.Introduction -> !tradePartner.knows(tradeOffer.name) // You can't introduce them to someone they already know!
            TradeOfferType.WarDeclaration -> offerer.getDiplomacyManager(tradeOffer.name)!!.canDeclareWar()
            TradeOfferType.PeaceProposal -> offerer.isAtWarWith(offerer.gameInfo.getCivilization(tradeOffer.name))
            TradeOfferType.City -> offerer.cities.any { it.id == tradeOffer.name }
            TradeOfferType.GreatWork -> tradeOffer.amount == 1 &&
                GreatWorkFunctions.findTradeSlot(offerer, tradeOffer.name) != null
            TradeOfferType.VoteCommitment -> VoteCommitmentItem.parse(tradeOffer)?.let {
                LeagueAutomation.isVoteCommitmentPossible(offerer, tradePartner, it.proposalId, it.choice, tradeOffer.amount, it.repeal)
            } == true
        }
    }

    /**
     *  Whether [evaluator] (an AI) accepts [trade] from [tradePartner] (`ourOffers` = what [evaluator] gives): a human's deal by
     *  CvDealAI::DoHumanOfferDealToThisAI, another AI's by the rule CvDealAI::DoEqualizeDealWithAI applies to AI deals.
     */
    fun isTradeAcceptable(trade: Trade, evaluator: Civilization, tradePartner: Civilization): Boolean {
        if (!isTradeValid(trade, evaluator, tradePartner)) return false
        return if (tradePartner.isHuman()) DealAi.respondToHumanDeal(evaluator, tradePartner, trade) == DealAi.DealResponse.Accepted
            else DealAi.isAcceptableBetweenAIs(evaluator, tradePartner, trade)
    }

    /**
     *  CvDeal::IsPossibleToTradeItem for TRADE_ITEM_THIRD_PARTY_PEACE: whether [civInfo] can offer [tradePartner] to make peace with
     *  [thirdCiv]. A city-state whose ally is at war with [civInfo] blocks it, unless that ally is [tradePartner]. A major must be an AI
     *  that wants peace with [civInfo], and [civInfo] must be an AI whose war goal against it is Damage or Peace.
     */
    @Readonly @Suppress("purity") // reads AI state
    fun isPeaceProposalEnabled(thirdCiv: Civilization, civInfo: Civilization, tradePartner: Civilization): Boolean {
        if (thirdCiv.isCityState) {
            val allyCiv = thirdCiv.allyCiv
            if (allyCiv != null && civInfo.isAtWarWith(allyCiv) && allyCiv != tradePartner) return false
            return true
        }
        if (thirdCiv.isHuman() || civInfo.isHuman()) return false
        val warGoal = civInfo.ai.diplomacy.warGoal(thirdCiv) ?: return false
        if (warGoal < WarGoal.Damage) return false
        return thirdCiv.ai.diplomacy.isWantsPeaceWith(civInfo)
    }

    companion object {
        /** CvDiplomacyAI::IsDoFAccepted both ways */
        @Readonly
        fun hasMutualDeclarationOfFriendship(civ: Civilization, other: Civilization): Boolean =
            civ.getDiplomacyManager(other)?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true
                && other.getDiplomacyManager(civ)?.hasFlag(DiplomacyFlags.DeclarationOfFriendship) == true
    }
}
