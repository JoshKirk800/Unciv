package com.unciv.logic.automation.civilization

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.trade.Trade

/**
 *  The deal and city-state AI memory of a civilization (`civ.ai.deals`), serialized with it; old saves start empty.
 *  - [offerStatementTurns]: CvDiplomacyAI's diplo log for the offer statements of the deal AI (DIPLO_STATEMENT_LUXURY_TRADE,
 *    _EMBASSY_EXCHANGE, _EMBASSY_OFFER, _OPEN_BORDERS_EXCHANGE, _OPEN_BORDERS_OFFER, _RESEARCH_AGREEMENT_OFFER, _RENEW_DEAL):
 *    partner civID -> [DealStatement] name -> turn last sent (or logged without sending).
 *  - [expiredDeals]: the renewable deals that ended with each partner (CvGameDeals historic deals not yet checked for renewal), oldest first.
 *  - [bulliedMinors]: civIDs of the city-states we have ever demanded tribute from (CvMinorCivAI::IsEverBulliedByMajor for us).
 */
class DealsAiState : IsPartOfGameInfoSerialization {
    var offerStatementTurns = HashMap<String, HashMap<String, Int>>()
    var expiredDeals = HashMap<String, ArrayList<Trade>>()
    var bulliedMinors = HashSet<String>()

    fun clone(): DealsAiState {
        val copy = DealsAiState()
        for ((civ, turns) in offerStatementTurns) copy.offerStatementTurns[civ] = HashMap(turns)
        for ((civ, deals) in expiredDeals) copy.expiredDeals[civ] = deals.mapTo(ArrayList()) { it.clone() }
        copy.bulliedMinors.addAll(bulliedMinors)
        return copy
    }

    /** CvDiplomacyAI::GetNumTurnsSinceStatementSent: [NEVER_SENT] if never */
    fun turnsSince(partnerId: String, statement: DealStatement, currentTurn: Int): Int {
        val turn = offerStatementTurns[partnerId]?.get(statement.name) ?: return NEVER_SENT
        return currentTurn - turn
    }

    /** CvDiplomacyAI::DoAddNewStatementToDiploLog */
    fun log(partnerId: String, statement: DealStatement, currentTurn: Int) {
        offerStatementTurns.getOrPut(partnerId) { HashMap() }[statement.name] = currentTurn
    }

    companion object {
        /** MAX_TURNS_SAFE_ESTIMATE of the original's scratch pad */
        const val NEVER_SENT = 9999
    }
}

/** The DiploStatementTypes of the deal AI's offers */
enum class DealStatement { LuxuryTrade, EmbassyExchange, EmbassyOffer, OpenBordersExchange, OpenBordersOffer, ResearchAgreementOffer, RenewDeal }
