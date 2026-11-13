package com.unciv.logic.automation.civilization

import com.unciv.logic.league.League
import com.unciv.logic.league.VoteCommitment
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.Speed
import yairm210.purity.annotations.Pure
import yairm210.purity.annotations.Readonly

/**
 *  A TRADE_ITEM_VOTE_COMMITMENT as an Unciv [TradeOffer] of type [TradeOfferType.VoteCommitment]:
 *  name "proposalId/choice/repeal" (iData1, iData2, bFlag1), amount = number of votes (iData3).
 */
class VoteCommitmentItem(val proposalId: Int, val choice: String, val repeal: Boolean) {
    companion object {
        @Pure
        fun name(proposalId: Int, choice: String, repeal: Boolean) = "$proposalId/$choice/$repeal"

        fun offer(commitment: VoteCommitment, speed: Speed) =
            TradeOffer(name(commitment.proposalId, commitment.choice, !commitment.enact), TradeOfferType.VoteCommitment, commitment.votes, speed)

        /** The text of the offer: what is voted on a proposal of the World Congress (the [item] parsed from the offer) */
        @Readonly
        fun text(item: VoteCommitmentItem, league: League?): String {
            val resolution = league?.proposals?.firstOrNull { it.id == item.proposalId && it.repeal == item.repeal }?.resolution
                ?: return "Vote [${item.choice}] on proposal [${item.proposalId}]"
            return if (item.repeal) "Vote [${item.choice}] on repealing [$resolution]" else "Vote [${item.choice}] on [$resolution]"
        }

        @Pure
        fun parse(offer: TradeOffer): VoteCommitmentItem? {
            if (offer.type != TradeOfferType.VoteCommitment) return null
            val first = offer.name.indexOf('/')
            val last = offer.name.lastIndexOf('/')
            if (first <= 0 || last <= first) return null
            val id = offer.name.substring(0, first).toIntOrNull() ?: return null
            return VoteCommitmentItem(id, offer.name.substring(first + 1, last), offer.name.substring(last + 1).toBoolean())
        }
    }
}
