package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.automation.diplomacy.MajorCivOpinion
import com.unciv.logic.automation.diplomacy.PeaceTreatyType
import com.unciv.logic.automation.diplomacy.WarProjection
import com.unciv.logic.automation.strategy.StrengthLevel
import com.unciv.logic.city.City
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeEvaluation
import com.unciv.logic.trade.TradeLogic
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.logic.trade.TradeOffersList
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 *  The original's CvDealAI (CvDealAI.cpp): what a player thinks a deal is worth, how it balances a deal, and the deals it puts
 *  together (peace treaties, luxury, embassy, open borders and research agreement offers).
 *
 *  A deal is an Unciv [Trade] seen from [us]: `ourOffers` are the items we give, `theirOffers` the items we receive.
 *  Values are the original's "value points" (gold); there is no gold inflation term.
 *  Item kinds Unciv has and the original does not (Great Works, technologies, introductions, stockpiled resources) keep Unciv's
 *  own valuation, averaged like the others when an even value is asked.
 */
object DealAi {

    /** CvDealAI::GetDealPercentLeewayWithAI */
    const val LEEWAY_WITH_AI_PERCENT = 25
    /** CvDealAI::GetDealPercentLeewayWithHuman */
    const val LEEWAY_WITH_HUMAN_PERCENT = 10

    /** CvResourceInfo::getHappiness of every BNW luxury (Resources.Happiness = 4), as Unciv's happiness calculation uses it */
    private const val LUXURY_HAPPINESS = 4
    /** Value a deal item is given when the original refuses it outright */
    const val NEVER = 100000

    /** The three numbers CvDealAI::GetDealValue returns */
    class DealValue {
        var total = 0
        var imOffering = 0
        var theyreOffering = 0
    }

    private fun Civilization.define(define: AiDefine) = gameInfo.ruleset.ai.define(define)

    //region Valuation

    /**
     *  CvDealAI::GetDealValue: the sum of the item values, items we give counted negative. With [useEvenValue] each item is valued at
     *  the average of both players' valuations (deals between AIs).
     */
    fun getDealValue(us: Civilization, them: Civilization, trade: Trade, useEvenValue: Boolean, result: DealValue = DealValue()): DealValue {
        var total = 0L
        var imOffering = 0L
        var theyreOffering = 0L
        for (offer in trade.ourOffers) {
            val value = -getTradeItemValue(us, them, offer, true, useEvenValue, trade).toLong()
            total += value
            if (value < 0) imOffering -= value else theyreOffering += value
        }
        for (offer in trade.theirOffers) {
            val value = getTradeItemValue(us, them, offer, false, useEvenValue, trade).toLong()
            total += value
            if (value < 0) imOffering -= value else theyreOffering += value
        }
        result.total = clamp(total)
        result.imOffering = clamp(imOffering)
        result.theyreOffering = clamp(theyreOffering)
        return result
    }

    private fun clamp(value: Long) = value.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()

    /** CvDealAI::GetTradeItemValue: what one item is worth to [us]; [fromMe] = we give it. [trade] is the deal seen from [us] */
    fun getTradeItemValue(us: Civilization, them: Civilization, offer: TradeOffer, fromMe: Boolean, useEvenValue: Boolean, trade: Trade): Int =
        when (offer.type) {
            TradeOfferType.Gold -> getGoldForValueExchange(us, them, offer.amount, false, fromMe, useEvenValue, false)
            TradeOfferType.Gold_Per_Turn -> getGptForValueExchange(us, them, offer.amount, false, offer.duration, fromMe, useEvenValue, false)
            TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource -> {
                val resource = us.gameInfo.ruleset.tileResources[offer.name]
                if (resource == null) 0 else getResourceValue(us, them, resource, offer.amount, offer.duration, fromMe)
            }
            TradeOfferType.City -> {
                val city = cityById(if (fromMe) us else them, offer.name)
                if (city == null) 0 else getCityValue(us, them, city, fromMe, useEvenValue)
            }
            TradeOfferType.Embassy -> getEmbassyValue(us, them, fromMe, useEvenValue)
            TradeOfferType.Agreement -> if (offer.name == Constants.openBorders) getOpenBordersValue(us, them, fromMe, useEvenValue) else 0
            TradeOfferType.Treaty -> when (offer.name) {
                Constants.defensivePact -> getDefensivePactValue(us, them, fromMe, useEvenValue)
                Constants.researchAgreement -> getResearchAgreementValue(us, them, fromMe, useEvenValue)
                else -> 0 // CvDealAI::GetPeaceTreatyValue: peace itself is worth nothing
            }
            TradeOfferType.PeaceProposal -> getThirdPartyPeaceValue(us, them, us.gameInfo.getCivilization(offer.name), fromMe)
            TradeOfferType.WarDeclaration -> getThirdPartyWarValue(us, them, us.gameInfo.getCivilization(offer.name), fromMe)
            TradeOfferType.VoteCommitment -> getVoteCommitmentValue(us, them, offer, fromMe, useEvenValue)
            TradeOfferType.GreatWork, TradeOfferType.Technology, TradeOfferType.Introduction, TradeOfferType.Stockpiled_Resource ->
                getUncivItemValue(us, them, offer, fromMe, useEvenValue, trade)
        }

    private fun cityById(owner: Civilization, id: String): City? = owner.cities.firstOrNull { it.id == id }

    private fun approachGoldModifier(approach: MajorCivApproach) = when (approach) {
        MajorCivApproach.Hostile -> 150
        MajorCivApproach.Guarded -> 110
        else -> 100
    }

    private fun opinionGoldModifier(opinion: MajorCivOpinion) = when (opinion) {
        MajorCivOpinion.Competitor -> 115
        MajorCivOpinion.Enemy -> 140
        MajorCivOpinion.Unforgivable -> 200
        else -> 100
    }

    private fun applyModifier(value: Long, modifier: Int, inverse: Boolean): Long =
        if (inverse) value * 100 / modifier else value * modifier / 100

    /**
     *  CvDealAI::GetGoldForForValueExchange: the value of [goldOrValue] gold, or with [numGoldFromValue] the gold worth [goldOrValue].
     *  Gold we give is marked up by our approach (Hostile 150%, Guarded 110%) and opinion (Competitor 115%, Enemy 140%, Unforgivable 200%).
     */
    fun getGoldForValueExchange(us: Civilization, them: Civilization, goldOrValue: Int, numGoldFromValue: Boolean, fromMe: Boolean,
                                useEvenValue: Boolean, roundUp: Boolean): Int {
        val percent = us.define(AiDefine.EACH_GOLD_VALUE_PERCENT)
        val multiplier = if (numGoldFromValue) 100 else percent
        val divisor = if (numGoldFromValue) (if (percent == 0) 1 else percent) else 100
        var value = goldOrValue.toLong() * multiplier
        if (fromMe) {
            value = applyModifier(value, approachGoldModifier(us.ai.diplomacy.approachTowards(them, true)), numGoldFromValue)
            value = applyModifier(value, opinionGoldModifier(us.ai.diplomacy.opinionOf(them)), numGoldFromValue)
        }
        if (roundUp) value += 99
        value /= divisor
        if (useEvenValue) {
            value += getGoldForValueExchange(them, us, goldOrValue, numGoldFromValue, !fromMe, false, roundUp)
            value /= 2
        }
        return clamp(value)
    }

    /**
     *  CvDealAI::GetGPTforForValueExchange: GPT × turns × EACH_GOLD_PER_TURN_VALUE_PERCENT, or the reverse with [numGptFromValue].
     *  GPT we give is marked up 130% and by the same approach and opinion modifiers as gold.
     */
    fun getGptForValueExchange(us: Civilization, them: Civilization, gptOrValue: Int, numGptFromValue: Boolean, numTurns: Int, fromMe: Boolean,
                               useEvenValue: Boolean, roundUp: Boolean): Int {
        val percent = us.define(AiDefine.EACH_GOLD_PER_TURN_VALUE_PERCENT)
        val turns = max(1, numTurns)
        val preMultiplier = if (numGptFromValue) 1 else turns
        val multiplier = if (numGptFromValue) 100 else percent
        val divisor = if (numGptFromValue) (if (percent == 0) 1 else percent) else 100
        val postDivider = if (numGptFromValue) turns else 1
        var value = gptOrValue.toLong() * preMultiplier * multiplier
        if (fromMe) {
            value = applyModifier(value, 130, numGptFromValue)
            value = applyModifier(value, approachGoldModifier(us.ai.diplomacy.approachTowards(them, true)), numGptFromValue)
            value = applyModifier(value, opinionGoldModifier(us.ai.diplomacy.opinionOf(them)), numGptFromValue)
        }
        if (roundUp) value += 99
        value /= divisor
        value /= postDivider
        if (useEvenValue) {
            value += getGptForValueExchange(them, us, gptOrValue, numGptFromValue, numTurns, !fromMe, false, roundUp)
            value /= 2
        }
        return clamp(value)
    }

    /** CvPlayer::getNumResourceAvailable: the civ's net amount of [resource]; without [includeImport] what it has from its own lands */
    fun numResourceAvailable(civ: Civilization, resource: TileResource, includeImport: Boolean): Int {
        if (includeImport) return civ.getResourceAmount(resource)
        var amount = 0
        for (supply in civ.detailedCivResources)
            if (supply.resource == resource && !supply.isCityStateOrTradeOrigin()) amount += supply.amount
        return amount
    }

    /**
     *  CvDealAI::GetResourceValue. A luxury is worth quantity × happiness × turns × 2 (0 if the World Congress bans it); giving away our
     *  last copy ×3 (×1.5 with a luxury happiness retention trait). A strategic is worth quantity × turns × 1.5; a strategic we receive
     *  counts at most max(5, cities) units, and none if we already have more than our city count. Items we give are then scaled by
     *  (opinion modifier + approach modifier) / 200. There is no even value.
     */
    fun getResourceValue(us: Civilization, them: Civilization, resource: TileResource, quantity: Int, numTurns: Int, fromMe: Boolean): Int {
        var value = 0L
        if (resource.resourceType == ResourceType.Luxury) {
            if (us.gameInfo.league?.isLuxuryBanned(resource.name) != true) {
                value = quantity.toLong() * LUXURY_HAPPINESS * numTurns * 2
                if (fromMe && numResourceAvailable(us, resource, true) == 1) {
                    value *= 3
                    if (us.hasUnique(UniqueType.RetainHappinessFromLuxury)) value /= 2
                }
            }
        } else if (resource.resourceType == ResourceType.Strategic) {
            var amount = quantity
            if (!fromMe) {
                amount = if (numResourceAvailable(us, resource, true) > us.cities.size) 0
                    else min(max(5, us.cities.size), amount)
            }
            value = amount.toLong() * numTurns * 150 / 100
        }
        if (fromMe) {
            var modifier = when (us.ai.diplomacy.opinionOf(them)) {
                MajorCivOpinion.Competitor -> 175
                MajorCivOpinion.Enemy -> 400
                MajorCivOpinion.Unforgivable -> 1000
                else -> 100
            }
            modifier = when (us.ai.diplomacy.approachTowards(them, true)) {
                MajorCivApproach.Hostile -> modifier + 300
                MajorCivApproach.Guarded -> modifier + 150
                MajorCivApproach.Afraid, MajorCivApproach.Friendly -> 200
                else -> modifier + 100
            }
            value = value * modifier / 200
        }
        return clamp(value)
    }

    /** CvPlayer::GetBuyPlotCost: PLOT_BASE_COST + PLOT_ADDITIONAL_COST_PER_PLOT × plots bought (the tiles our cities claimed) */
    private fun buyPlotCost(civ: Civilization): Int {
        var plotsBought = 0
        for (city in civ.cities) plotsBought += city.expansion.tilesClaimed()
        return civ.define(AiDefine.PLOT_BASE_COST) + civ.define(AiDefine.PLOT_ADDITIONAL_COST_PER_PLOT) * plotsBought
    }

    /**
     *  CvDealAI::GetCityValue: 440 + 200 × population, plus the city's worked-area plots: beyond the first ring a plot's buy cost,
     *  an improved plot 25% of it, a revealed luxury quantity × happiness × 120 × 2 (×3 more if it is our last copy and we give it),
     *  a strategic quantity × 60 × 1.5. A city we give: ×2 with a world wonder, × war projection (100-400%), ×2 when not at war.
     *  A city a human offers outside war is divided by its distance to our nearest city beyond 4. An original major capital ×2.
     */
    fun getCityValue(us: Civilization, them: Civilization, city: City, fromMe: Boolean, useEvenValue: Boolean): Int {
        var value = 440L + city.population.population * 200L
        val goldPerPlot = buyPlotCost(us)
        var plotsValue = 0L
        var improvedValue = 0L
        var resourceValue = 0L
        val center = city.getCenterTile()
        for (tile in city.getTiles()) {
            val distance = tile.aerialDistanceTo(center)
            if (distance > 3) continue
            if (distance > 1) plotsValue += goldPerPlot
            if (tile.improvement != null) improvedValue += goldPerPlot * 25L
            val resource = tile.tileResource.takeIf { tile.resource != null && us.tech.isRevealed(it) } ?: continue
            when (resource.resourceType) {
                ResourceType.Luxury -> {
                    resourceValue += tile.resourceAmount.toLong() * LUXURY_HAPPINESS * 120 * 2
                    if (fromMe && numResourceAvailable(us, resource, true) == 1)
                        resourceValue += tile.resourceAmount.toLong() * LUXURY_HAPPINESS * 120 * 4
                }
                ResourceType.Strategic -> resourceValue += tile.resourceAmount.toLong() * 60 * 150 / 100
                else -> {}
            }
        }
        improvedValue /= 100
        value += plotsValue + improvedValue + resourceValue

        if (fromMe) {
            if (city.cityConstructions.getBuiltBuildings().any { it.isWonder }) value *= 2
            value *= when (us.ai.diplomacy.warProjection(them) ?: WarProjection.Unknown) {
                WarProjection.Destruction -> 100
                WarProjection.Defeat -> 180
                WarProjection.Stalemate -> 220
                WarProjection.Unknown -> 250
                WarProjection.Good, WarProjection.VeryGood -> 400
            }
            value /= 100
            if (!us.isAtWarWith(them)) value *= 2
        } else if (!us.isAtWarWith(them) && them.isHuman()) {
            var bestDistance = 99
            for (ourCity in us.cities) bestDistance = min(bestDistance, ourCity.getCenterTile().aerialDistanceTo(center))
            if (bestDistance <= 4) bestDistance = 5
            value /= bestDistance - 4
            if (value < 100) value = 100
        }
        if (city.isOriginalCapital && city.foundingCivObject?.isMajorCiv == true) value *= 2

        if (useEvenValue) {
            value += getCityValue(them, us, city, !fromMe, false)
            value /= 2
        }
        return clamp(value)
    }

    /**
     *  CvDealAI::GetEmbassyValue: 35 scaled by this speed's deal duration; one we give × approach (Hostile 250%, Guarded 130%, Afraid 80%).
     *  The original's even value averages with the other player's CvDealAI::GetTradeAgreementValue, not its embassy value; ported as is.
     */
    fun getEmbassyValue(us: Civilization, them: Civilization, fromMe: Boolean, useEvenValue: Boolean): Int {
        val standardDuration = us.gameInfo.ruleset.speeds["Standard"]?.dealDuration ?: us.gameInfo.speed.dealDuration
        var value = 35 * us.gameInfo.speed.dealDuration / standardDuration
        if (fromMe) {
            value *= when (us.ai.diplomacy.approachTowards(them, true)) {
                MajorCivApproach.Hostile -> 250
                MajorCivApproach.Guarded -> 130
                MajorCivApproach.Afraid -> 80
                else -> 100
            }
            value /= 100
        }
        if (useEvenValue) {
            value += getTradeAgreementValue(them, us, !fromMe)
            value /= 2
        }
        return value
    }

    /** CvDealAI::GetTradeAgreementValue (no trade agreement item in BNW; read only by the embassy's even value) */
    private fun getTradeAgreementValue(us: Civilization, them: Civilization, fromMe: Boolean): Int {
        if (!fromMe) return 100
        return 100 * when (us.ai.diplomacy.approachTowards(them, true)) {
            MajorCivApproach.Hostile -> 250
            MajorCivApproach.Guarded -> 130
            MajorCivApproach.Afraid -> 80
            MajorCivApproach.Neutral -> 110
            else -> 100
        } / 100
    }

    /**
     *  CvDealAI::GetOpenBordersValue. A Friendly approach makes it 50 both ways. Giving: by approach (Hostile 1000, Guarded 100, Afraid 20,
     *  Neutral 75) × opinion (Ally 0, Friend 35%, Favorable 70%, Competitor 150%, Enemy 400%, Unforgivable 10000), ×10% / ×25% if they fight
     *  two / one of our enemies, then the culture factor. Receiving: by proximity (5/10/15/30), halved if they have one city, then the
     *  culture factor (CultureAutomation.openBordersValuePercent).
     */
    fun getOpenBordersValue(us: Civilization, them: Civilization, fromMe: Boolean, useEvenValue: Boolean): Int {
        val approach = us.ai.diplomacy.approachTowards(them, true)
        if (approach == MajorCivApproach.Friendly) return 50
        var value: Int
        if (fromMe) {
            value = when (approach) {
                MajorCivApproach.Hostile -> 1000
                MajorCivApproach.Guarded -> 100
                MajorCivApproach.Afraid -> 20
                MajorCivApproach.Neutral -> 75
                else -> 100
            }
            value = when (us.ai.diplomacy.opinionOf(them)) {
                MajorCivOpinion.Ally -> 0
                MajorCivOpinion.Friend -> value * 35 / 100
                MajorCivOpinion.Favorable -> value * 70 / 100
                MajorCivOpinion.Neutral -> value
                MajorCivOpinion.Competitor -> value * 150 / 100
                MajorCivOpinion.Enemy -> value * 400 / 100
                MajorCivOpinion.Unforgivable -> 10000
            }
            val enemiesAtWarWith = us.ai.diplomacy.numOurEnemiesAtWarWith(them)
            if (enemiesAtWarWith >= 2) value = value * 10 / 100
            else if (enemiesAtWarWith == 1) value = value * 25 / 100
        } else {
            value = when (us.getProximity(them)) {
                Proximity.Distant -> 5
                Proximity.Far -> 10
                Proximity.Close -> 15
                Proximity.Neighbors -> 30
                Proximity.None -> 0
            }
            if (them.cities.size == 1) value = value * 50 / 100
        }
        value = value * CultureAutomation.openBordersValuePercent(us, them, receiving = !fromMe) / 100
        if (useEvenValue) {
            value += getOpenBordersValue(them, us, !fromMe, false)
            value /= 2
        }
        return value
    }

    /** CvDealAI::GetDefensivePactValue: 100 received; giving one 100 if our opinion is Favorable or better, else [NEVER] */
    fun getDefensivePactValue(us: Civilization, them: Civilization, fromMe: Boolean, useEvenValue: Boolean): Int {
        var value = if (!fromMe) 100
            else if (us.ai.diplomacy.opinionOf(them) >= MajorCivOpinion.Favorable) 100 else NEVER
        if (useEvenValue) {
            value += getDefensivePactValue(them, us, !fromMe, false)
            value /= 2
        }
        return value
    }

    /** CvDealAI::GetResearchAgreementValue: 100; giving one +100 per era they are ahead, × approach (Hostile ×10, Afraid ×0.8) */
    fun getResearchAgreementValue(us: Civilization, them: Civilization, fromMe: Boolean, useEvenValue: Boolean): Int {
        var value = 100
        if (fromMe) {
            value += 100 * max(0, them.getEraNumber() - us.getEraNumber())
            value *= when (us.ai.diplomacy.approachTowards(them, true)) {
                MajorCivApproach.Hostile -> 1000
                MajorCivApproach.Afraid -> 80
                else -> 100
            }
            value /= 100
        }
        if (useEvenValue) {
            value += getResearchAgreementValue(them, us, !fromMe, false)
            value /= 2
        }
        return value
    }

    /**
     *  CvDealAI::GetThirdPartyPeaceValue: we make peace with [third]: 200-600 by war projection, +50 per era, × our hatred of a major
     *  [third] (Unforgivable ×3, Enemy ×2), × our liking of [them] (Ally 30%, Friend 50%, Favorable 75%). Peace with a city-state allied to
     *  either side, inside a peace deal between us, is worth 0. Peace [them] make is worth -10000.
     */
    fun getThirdPartyPeaceValue(us: Civilization, them: Civilization, third: Civilization, fromMe: Boolean): Int {
        if (third.isCityState && us.isAtWarWith(them)) {
            val ally = third.allyCiv
            if (ally == them || ally == us) return 0
        }
        if (!fromMe) return -10000
        var value = when (us.ai.diplomacy.warProjection(third) ?: WarProjection.Unknown) {
            WarProjection.VeryGood -> 600
            WarProjection.Good -> 400
            WarProjection.Unknown -> 250
            else -> 200
        }
        value += us.getEraNumber() * 50
        if (!third.isCityState) {
            when (us.ai.diplomacy.opinionOf(third)) {
                MajorCivOpinion.Unforgivable -> value = value * 300 / 100
                MajorCivOpinion.Enemy -> value = value * 200 / 100
                else -> {}
            }
        }
        when (us.ai.diplomacy.opinionOf(them)) {
            MajorCivOpinion.Ally -> value = value * 30 / 100
            MajorCivOpinion.Friend -> value = value * 50 / 100
            MajorCivOpinion.Favorable -> value = value * 75 / 100
            else -> {}
        }
        return value
    }

    /**
     *  CvDealAI::GetThirdPartyWarValue: we declare war on [third]: refused ([NEVER]) when our War approach bias is under 4; else
     *  400 / 600 / 1000 / 50000 by war projection, +50 per era, ±10% per point of War bias away from DEFAULT_FLAVOR_VALUE; never against
     *  a city-state we are Friendly or Protective toward; × hatred of a major [third] (25-75%), × liking of [them] (50-125%).
     *  War [them] declare is worth 200 / 100 if we find [third] Unforgivable / Enemy, else -100000.
     */
    fun getThirdPartyWarValue(us: Civilization, them: Civilization, third: Civilization, fromMe: Boolean): Int {
        val warBiasWeight = us.ai.majorCivApproachBias(MajorCivApproach.War)
        if (fromMe && warBiasWeight < 4) return NEVER
        if (!fromMe) {
            if (third.isCityState) return -NEVER
            return when (us.ai.diplomacy.opinionOf(third)) {
                MajorCivOpinion.Unforgivable -> 200
                MajorCivOpinion.Enemy -> 100
                else -> -NEVER
            }
        }
        val projection = us.ai.diplomacy.warProjection(third) ?: WarProjection.Unknown
        var value = when {
            projection >= WarProjection.Good -> 400
            projection == WarProjection.Unknown -> 600
            projection == WarProjection.Stalemate -> 1000
            else -> 50000
        }
        value += us.getEraNumber() * 50
        val warBias = us.define(AiDefine.DEFAULT_FLAVOR_VALUE) - warBiasWeight
        value += warBias * 10 * value / 100
        if (third.isCityState) {
            val approach = us.ai.minorCivApproaches[third.civID]
            if (approach == MinorCivApproach.Friendly || approach == MinorCivApproach.Protective) value = NEVER
        } else {
            when (us.ai.diplomacy.opinionOf(third)) {
                MajorCivOpinion.Unforgivable -> value = value * 25 / 100
                MajorCivOpinion.Enemy -> value = value * 50 / 100
                MajorCivOpinion.Competitor -> value = value * 75 / 100
                else -> {}
            }
        }
        when (us.ai.diplomacy.opinionOf(them)) {
            MajorCivOpinion.Ally -> value = value * 50 / 100
            MajorCivOpinion.Friend -> value = value * 75 / 100
            MajorCivOpinion.Favorable -> value = value * 85 / 100
            MajorCivOpinion.Competitor -> value = value * 125 / 100
            else -> {}
        }
        return value
    }

    /**
     *  CvDealAI::GetVoteCommitmentValue, per vote. Votes we give: by how much we like the proposal (50-300, [NEVER] when we strongly
     *  dislike it), by our alignment with [them] (-50..+50, [NEVER] for enemies or war), [NEVER] if we are Hostile or planning war on them.
     *  Votes we receive: by how much we like it (50-200, -100000 if not at least weakly), less 20/40 when they keep many votes in hand.
     */
    fun getVoteCommitmentValue(us: Civilization, them: Civilization, offer: TradeOffer, fromMe: Boolean, useEvenValue: Boolean): Int {
        val commitment = VoteCommitmentItem.parse(offer) ?: return 0
        val votes = offer.amount
        var value = 0
        val desire = LeagueAutomation.evaluateVoteForTrade(us, commitment.proposalId, commitment.choice, votes, commitment.repeal)
        if (fromMe) {
            value += when (desire) {
                LeagueDesire.NEVER, LeagueDesire.STRONG_DISLIKE -> NEVER
                LeagueDesire.DISLIKE -> 300
                LeagueDesire.WEAK_DISLIKE -> 200
                LeagueDesire.NEUTRAL, LeagueDesire.WEAK_LIKE, LeagueDesire.LIKE -> 150
                LeagueDesire.STRONG_LIKE, LeagueDesire.ALWAYS -> 50
            }
            val league = us.gameInfo.league
            if (league != null) {
                value += when (LeagueEvaluator(us, league).evaluateAlignment(them)) {
                    LeagueAlignment.LIBERATOR, LeagueAlignment.LEADER -> -50
                    LeagueAlignment.ALLY -> -35
                    LeagueAlignment.CONFIDANT, LeagueAlignment.FRIEND -> -25
                    LeagueAlignment.RIVAL -> 25
                    LeagueAlignment.HATRED -> 50
                    LeagueAlignment.ENEMY, LeagueAlignment.WAR -> NEVER
                    else -> 0
                }
            }
            val approach = us.ai.diplomacy.approachTowards(them, false)
            if (approach == MajorCivApproach.Hostile || approach == MajorCivApproach.War) value += NEVER
        } else {
            value += when (desire) {
                LeagueDesire.WEAK_LIKE -> 50
                LeagueDesire.LIKE -> 100
                LeagueDesire.STRONG_LIKE -> 150
                LeagueDesire.ALWAYS -> 200
                else -> -NEVER
            }
            val league = us.gameInfo.league
            if (league != null) {
                val startingVotes = league.getDelegates(them)
                val ratio = if (startingVotes > 0) votes.toFloat() / startingVotes else 1f
                if (ratio <= 0.25f) value -= 40
                else if (ratio <= 0.5f) value -= 20
            }
        }
        var total = max(value, 0).toLong() * votes
        if (useEvenValue) {
            total += getVoteCommitmentValue(them, us, offer, !fromMe, false)
            total /= 2
        }
        return clamp(total)
    }

    /** Unciv items without a CvDealAI counterpart: Unciv's own prices (no gold inflation) */
    private fun getUncivItemValue(us: Civilization, them: Civilization, offer: TradeOffer, fromMe: Boolean, useEvenValue: Boolean, trade: Trade): Int {
        val ruleset = us.gameInfo.ruleset
        var value: Int = when (offer.type) {
            TradeOfferType.GreatWork -> {
                val ours = (if (fromMe) trade.ourOffers else trade.theirOffers).firstOrNull { it.type == TradeOfferType.GreatWork }
                val theirs = (if (fromMe) trade.theirOffers else trade.ourOffers).firstOrNull { it.type == TradeOfferType.GreatWork }
                val given = (if (fromMe) ours else theirs)?.let { GreatWorkFunctions.findTradeSlot(us, it.name) }
                val received = (if (fromMe) theirs else ours)?.let { GreatWorkFunctions.findTradeSlot(them, it.name) }
                if (given == null || received == null) (if (fromMe) NEVER else 0)
                else if (fromMe) 200 + max(0, -GreatWorkFunctions.themingGain(given, received)) * 250
                else 200 + max(0, GreatWorkFunctions.themingGain(given, received)) * 250
            }
            TradeOfferType.Technology -> ruleset.technologies[offer.name]?.let { sqrt(it.cost.toDouble()).toInt() * 20 } ?: 0
            TradeOfferType.Introduction ->
                ruleset.modOptions.getMatchingUniques(UniqueType.TradeCivIntroductions).firstOrNull()?.params?.get(0)?.toInt() ?: 0
            else -> {
                val resource = ruleset.tileResources[offer.name]
                if (resource == null) 0
                else if (fromMe) resource.getMatchingUniques(UniqueType.AiWillSellAt, GameContext(us)).minOfOrNull { it.params[0].toInt() } ?: NEVER
                else resource.getMatchingUniques(UniqueType.AiWillBuyAt, GameContext(us)).minOfOrNull { it.params[0].toInt() } ?: 0
            }
        }
        if (useEvenValue) {
            value += getUncivItemValue(them, us, offer, !fromMe, false, trade.reverse())
            value /= 2
        }
        return value
    }
    //endregion

    //region Acceptance and balancing

    /** CvDealAI::IsDealWithHumanAcceptable, with [cachedPeaceValue] standing for CvDealAI::GetCachedValueOfPeaceWithHuman */
    class HumanDealCheck {
        var acceptable = false
        var cantMatchOffer = false
        val value = DealValue()
        var amountOverWeWillRequest = 0
        var amountUnderWeWillOffer = 0
    }

    /**
     *  CvDealAI::IsDealWithHumanAcceptable: acceptable when no gold is involved and both sides are within 25 value (one GPT) of each other;
     *  for a peace deal when the value reaches the cached value of the peace we want; otherwise when the value is in
     *  0..10% of the deal's total size.
     */
    fun isDealWithHumanAcceptable(us: Civilization, human: Civilization, trade: Trade, check: HumanDealCheck = HumanDealCheck()): HumanDealCheck {
        check.cantMatchOffer = false
        check.acceptable = false
        val value = getDealValue(us, human, trade, false, check.value)
        if (trade.ourOffers.none { it.type == TradeOfferType.Gold } && trade.theirOffers.none { it.type == TradeOfferType.Gold }) {
            if (abs(value.theyreOffering - value.imOffering) < 25) {
                check.acceptable = true
                return check
            }
        }
        val dealSum = value.imOffering + value.theyreOffering
        check.amountOverWeWillRequest = dealSum * LEEWAY_WITH_HUMAN_PERCENT / 100
        check.amountUnderWeWillOffer = 0
        if (trade.isPeaceTreaty()) {
            if (value.total >= cachedValueOfPeaceWithHuman(us, human)) check.acceptable = true
        } else if (value.total <= check.amountOverWeWillRequest && value.total >= check.amountUnderWeWillOffer) {
            check.acceptable = true
        } else if (value.total > check.amountOverWeWillRequest) {
            check.cantMatchOffer = true
        }
        return check
    }

    /**
     *  CvDealAI::GetCachedValueOfPeaceWithHuman, as CvDealAI::DoTradeScreenOpened sets it: the value of the peace treaty we want with the
     *  human - minus what we would give if we surrender, plus what they would give if they surrender, 0 for white peace.
     */
    fun cachedValueOfPeaceWithHuman(us: Civilization, human: Civilization): Int {
        if (!us.isAtWarWith(human)) return 0
        val offer = us.ai.diplomacy.treatyWillingToOffer(human)
        val accept = us.ai.diplomacy.treatyWillingToAccept(human)
        if (offer < PeaceTreatyType.WhitePeace || accept < PeaceTreatyType.WhitePeace) return 0
        val trade = Trade()
        return when {
            offer > PeaceTreatyType.WhitePeace -> {
                addItemsToDealForPeaceTreaty(trade, us, human, offer, meSurrendering = true)
                -getDealValue(us, human, trade, false).imOffering
            }
            accept > PeaceTreatyType.WhitePeace -> {
                addItemsToDealForPeaceTreaty(trade, us, human, accept, meSurrendering = false)
                getDealValue(us, human, trade, false).theyreOffering
            }
            else -> 0
        }
    }

    /** The outcome of CvDealAI::DoHumanOfferDealToThisAI */
    enum class DealResponse { Accepted, Unacceptable, Insulting }

    /**
     *  CvDealAI::DoHumanOfferDealToThisAI: accept an acceptable deal or a gift (they give more than we do, outside peace deals);
     *  else "unacceptable" if the value is above -75 and we are not asked for five times more than we get, else "insulting".
     */
    fun respondToHumanDeal(us: Civilization, human: Civilization, trade: Trade): DealResponse {
        val check = isDealWithHumanAcceptable(us, human, trade)
        val value = check.value
        val acceptable = check.acceptable || (!trade.isPeaceTreaty() && value.theyreOffering > value.imOffering)
        return when {
            acceptable -> DealResponse.Accepted
            value.total > -75 && value.imOffering < value.theyreOffering * 5 -> DealResponse.Unacceptable
            else -> DealResponse.Insulting
        }
    }

    /**
     *  CvDealAI::DoAcceptedDeal for a deal a human offered us: a generous deal (value ≥ 100, or they give five times what we give)
     *  adds its value to our recent trade value with them, an acceptable one half of it; peace deals count against the cached peace value.
     */
    fun onHumanDealAccepted(us: Civilization, human: Civilization, trade: Trade, value: DealValue) {
        val dealValueToMe = value.total - (if (trade.isPeaceTreaty()) cachedValueOfPeaceWithHuman(us, human) else 0)
        if (dealValueToMe >= 100 || value.theyreOffering > value.imOffering * 5)
            us.ai.diplomacy.changeRecentTradeValue(human, dealValueToMe)
        else us.ai.diplomacy.changeRecentTradeValue(human, dealValueToMe / 2)
    }

    /**
     *  CvDealAI::DoEqualizeDealWithAI: with even values, a deal within ±25% of its total size is fine as it is. Otherwise items are added
     *  and removed (vote commitments, resources, GPT, gold, then trimming GPT and gold; open borders are never added between AIs), and the
     *  result is acceptable only if each side, by its own valuation, receives at least 75% of what it gives.
     */
    fun doEqualizeDealWithAI(us: Civilization, them: Civilization, trade: Trade): Boolean {
        val even = getDealValue(us, them, trade, true)
        val dealDuration = us.gameInfo.speed.dealDuration
        val dealSum = even.imOffering + even.theyreOffering
        val amountOver = dealSum * LEEWAY_WITH_AI_PERCENT / 100
        val amountUnder = dealSum * -LEEWAY_WITH_AI_PERCENT / 100
        if (even.total in amountUnder..amountOver) return true

        val eq = Equalizer(us, them, trade, true, even)
        eq.addVoteCommitmentToThem(false, amountOver)
        eq.addVoteCommitmentToUs(false, amountUnder)
        eq.addResourceToThem(false, amountOver, dealDuration)
        eq.addResourceToUs(false, amountUnder, dealDuration)
        // DoAddOpenBordersToThem/ToUs are called with bDontChange...ExistingItems = true here, so they never add anything
        eq.addGptToThem(false, dealDuration)
        eq.addGptToUs(false, dealDuration)
        eq.addGoldToThem(false)
        eq.addGoldToUs(false)
        eq.removeGptFromThem(dealDuration)
        eq.removeGptFromUs(dealDuration)
        eq.removeGoldFromUs()
        eq.removeGoldFromThem()

        if (trade.ourOffers.isEmpty() && trade.theirOffers.isEmpty()) return false
        return isAcceptableToBothAIs(us, them, trade)
    }

    /** The end of CvDealAI::DoEqualizeDealWithAI: each side gets at least (100 - leeway)% of what it gives, by its own valuation */
    fun isAcceptableToBothAIs(us: Civilization, them: Civilization, trade: Trade): Boolean {
        val mine = getDealValue(us, them, trade, false)
        val lowEndOfWhatIWillAccept = mine.imOffering - mine.imOffering * LEEWAY_WITH_AI_PERCENT / 100
        if (mine.theyreOffering < lowEndOfWhatIWillAccept) return false
        val theirs = getDealValue(them, us, trade.reverse(), false)
        val lowEndOfWhatTheyWillAccept = theirs.imOffering - theirs.imOffering * LEEWAY_WITH_AI_PERCENT / 100
        return theirs.theyreOffering >= lowEndOfWhatTheyWillAccept
    }

    /** Whether a deal another AI proposes is one we would have made: the even-value band of CvDealAI::DoEqualizeDealWithAI, or the 75% rule */
    fun isAcceptableBetweenAIs(us: Civilization, them: Civilization, trade: Trade): Boolean {
        val even = getDealValue(us, them, trade, true)
        val dealSum = even.imOffering + even.theyreOffering
        if (even.total in dealSum * -LEEWAY_WITH_AI_PERCENT / 100..dealSum * LEEWAY_WITH_AI_PERCENT / 100) return true
        return isAcceptableToBothAIs(us, them, trade)
    }

    /**
     *  CvDealAI::DoEqualizeDealWithHuman: a peace deal is replaced by the treaty we want (IsOfferPeace); otherwise, unless already
     *  acceptable, items are added in the original's order (votes, embassies, resources, open borders, GPT, gold, cities when we
     *  surrender) and trimmed. Returns whether the resulting [trade] is acceptable to us.
     */
    fun doEqualizeDealWithHuman(us: Civilization, human: Civilization, trade: Trade,
                                dontChangeMyExistingItems: Boolean, dontChangeTheirExistingItems: Boolean): Boolean {
        if (trade.isPeaceTreaty()) {
            trade.ourOffers.clear()
            trade.theirOffers.clear()
            return isOfferPeace(us, human, trade)
        }
        val check = isDealWithHumanAcceptable(us, human, trade)
        if (check.value.total < 0 && dontChangeTheirExistingItems) return false
        if (check.acceptable) return true
        val dealDuration = us.gameInfo.speed.dealDuration
        val over = check.amountOverWeWillRequest
        val under = check.amountUnderWeWillOffer
        val eq = Equalizer(us, human, trade, false, check.value)
        eq.addVoteCommitmentToThem(dontChangeTheirExistingItems, over)
        eq.addVoteCommitmentToUs(dontChangeMyExistingItems, under)
        eq.addEmbassyToThem(dontChangeTheirExistingItems, over)
        eq.addEmbassyToUs(dontChangeMyExistingItems, under)
        eq.addResourceToThem(dontChangeTheirExistingItems, over, dealDuration)
        eq.addResourceToUs(dontChangeMyExistingItems, under, dealDuration)
        eq.addOpenBordersToThem(dontChangeTheirExistingItems, over, dealDuration)
        eq.addOpenBordersToUs(dontChangeMyExistingItems, under, dealDuration)
        eq.addGptToThem(dontChangeTheirExistingItems, dealDuration)
        eq.addGptToUs(dontChangeMyExistingItems, dealDuration)
        eq.addGoldToThem(dontChangeTheirExistingItems)
        eq.addGoldToUs(dontChangeMyExistingItems)
        if (!dontChangeTheirExistingItems) eq.removeGptFromThem(dealDuration)
        if (!dontChangeMyExistingItems) eq.removeGptFromUs(dealDuration)
        eq.removeGoldFromUs()
        eq.removeGoldFromThem()
        // DoAddCitiesToUs acts only when we are the surrendering player of a peace deal, which this is not
        if (trade.ourOffers.isEmpty() && trade.theirOffers.isEmpty()) return false
        return isDealWithHumanAcceptable(us, human, trade).acceptable
    }

    /**
     *  The CvDealAI::DoAdd... / DoRemove... helpers on one deal. Each adds or trims an item only when it moves the deal toward
     *  [DealValue.total] = 0 without crossing the limit, and recomputes the total afterwards (where the original does).
     */
    private class Equalizer(val us: Civilization, val them: Civilization, val trade: Trade, val useEvenValue: Boolean, val value: DealValue) {
        var total = value.total
        private val ourAvailable: TradeOffersList
        private val theirAvailable: TradeOffersList

        init {
            val logic = TradeLogic(us, them)
            ourAvailable = logic.ourAvailableOffers
            theirAvailable = logic.theirAvailableOffers
        }

        private fun recompute() {
            getDealValue(us, them, trade, useEvenValue, value)
            total = value.total
        }

        private fun item(fromUs: Boolean, offer: TradeOffer) =
            getTradeItemValue(us, them, offer, fromUs, useEvenValue, trade)

        private fun isPossible(fromUs: Boolean, offer: TradeOffer) =
            isPossibleToTradeItem(trade, if (fromUs) us else them, if (fromUs) them else us, offer, fromUs, if (fromUs) ourAvailable else theirAvailable)

        fun addVoteCommitmentToThem(dontChange: Boolean, amountOver: Int) {
            if (dontChange || total >= 0) return
            if (trade.ourOffers.any { it.type == TradeOfferType.VoteCommitment } || trade.theirOffers.any { it.type == TradeOfferType.VoteCommitment }) return
            if (!LeagueAutomation.canCommitVote(them, us)) return
            for (commitment in LeagueAutomation.desiredVoteCommitments(us, them)) {
                val offer = VoteCommitmentItem.offer(commitment, us.gameInfo.speed)
                if (!isPossible(false, offer)) continue
                if (item(false, offer) + total <= amountOver) {
                    trade.theirOffers.add(offer)
                    recompute()
                }
            }
        }

        fun addVoteCommitmentToUs(dontChange: Boolean, amountUnder: Int) {
            if (dontChange || total <= 0) return
            if (trade.ourOffers.any { it.type == TradeOfferType.VoteCommitment } || trade.theirOffers.any { it.type == TradeOfferType.VoteCommitment }) return
            if (!LeagueAutomation.canCommitVote(us, them)) return
            for (commitment in LeagueAutomation.desiredVoteCommitments(them, us)) {
                val offer = VoteCommitmentItem.offer(commitment, us.gameInfo.speed)
                if (!isPossible(true, offer)) continue
                if (-item(true, offer) + total >= amountUnder) {
                    trade.ourOffers.add(offer)
                    recompute()
                }
            }
        }

        /** CvDeal::ChangeResourceTrade then AddResourceTrade: an existing item is changed in place without re-evaluating, as in the original */
        private fun addOrChangeResource(list: TradeOffersList, offer: TradeOffer) {
            val existing = list.firstOrNull { it.name == offer.name && it.type == offer.type }
            if (existing != null) {
                existing.amount = offer.amount
                existing.duration = offer.duration
                return
            }
            list.add(offer)
            recompute()
        }

        private fun resourceOfferType(resource: TileResource) =
            if (resource.resourceType == ResourceType.Luxury) TradeOfferType.Luxury_Resource else TradeOfferType.Strategic_Resource

        fun addResourceToThem(dontChange: Boolean, amountOver: Int, duration: Int) {
            if (dontChange || total >= 0) return
            val resources = us.gameInfo.ruleset.tileResources.values
            val league = us.gameInfo.league
            for (resource in resources) {
                if (resource.resourceType != ResourceType.Luxury) continue
                if (numResourceAvailable(them, resource, false) <= 0) continue
                if (league?.isLuxuryBanned(resource.name) == true) continue
                val offer = TradeOffer(resource.name, TradeOfferType.Luxury_Resource, 1, duration)
                if (!isPossible(false, offer)) continue
                if (item(false, offer) + total <= amountOver) addOrChangeResource(trade.theirOffers, offer)
            }
            for (resource in resources) {
                if (resource.resourceType == ResourceType.Luxury) continue
                val available = numResourceAvailable(them, resource, false)
                if (available <= 0) continue
                val offer = TradeOffer(resource.name, resourceOfferType(resource), min(5, available), duration)
                if (!isPossible(false, offer)) continue
                if (item(false, offer) + total <= amountOver) addOrChangeResource(trade.theirOffers, offer)
            }
        }

        fun addResourceToUs(dontChange: Boolean, amountUnder: Int, duration: Int) {
            if (dontChange || total <= 0) return
            val league = us.gameInfo.league
            for (resource in us.gameInfo.ruleset.tileResources.values) {
                var quantity = numResourceAvailable(us, resource, false)
                if (quantity == 0) continue
                if (resource.resourceType == ResourceType.Luxury) {
                    quantity = 1
                    if (league?.isLuxuryBanned(resource.name) == true) continue
                } else quantity = min(5, quantity)
                val offer = TradeOffer(resource.name, resourceOfferType(resource), quantity, duration)
                if (!isPossible(true, offer)) continue
                if (-item(true, offer) + total >= amountUnder) addOrChangeResource(trade.ourOffers, offer)
            }
        }

        fun addEmbassyToThem(dontChange: Boolean, amountOver: Int) {
            if (dontChange || total >= 0 || trade.theirOffers.any { it.type == TradeOfferType.Embassy }) return
            val offer = TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = us.gameInfo.speed)
            if (!isPossible(false, offer)) return
            if (item(false, offer) + total <= amountOver) {
                trade.theirOffers.add(offer)
                recompute()
            }
        }

        fun addEmbassyToUs(dontChange: Boolean, amountUnder: Int) {
            if (dontChange || total <= 0 || trade.ourOffers.any { it.type == TradeOfferType.Embassy }) return
            val offer = TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = us.gameInfo.speed)
            if (!isPossible(true, offer)) return
            if (-item(true, offer) + total >= amountUnder) {
                trade.ourOffers.add(offer)
                recompute()
            }
        }

        fun addOpenBordersToThem(dontChange: Boolean, amountOver: Int, duration: Int) {
            if (dontChange || total >= 0 || trade.theirOffers.any { it.name == Constants.openBorders }) return
            val offer = TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, duration)
            if (!isPossible(false, offer)) return
            if (item(false, offer) + total <= amountOver) {
                trade.theirOffers.add(offer)
                recompute()
            }
        }

        fun addOpenBordersToUs(dontChange: Boolean, amountUnder: Int, duration: Int) {
            if (dontChange || total <= 0 || trade.ourOffers.any { it.name == Constants.openBorders }) return
            val offer = TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, duration)
            if (!isPossible(true, offer)) return
            if (-item(true, offer) + total >= amountUnder) {
                trade.ourOffers.add(offer)
                recompute()
            }
        }

        private fun amountOf(list: TradeOffersList, type: TradeOfferType) = list.firstOrNull { it.type == type }?.amount ?: 0

        /** CvDeal::ChangeGoldTrade/AddGoldTrade (and the GPT versions): set the item's amount, adding it if missing */
        private fun setAmount(list: TradeOffersList, type: TradeOfferType, amount: Int, duration: Int) {
            val existing = list.firstOrNull { it.type == type }
            if (existing != null) {
                existing.amount = amount
                return
            }
            val name = if (type == TradeOfferType.Gold) Constants.flatGold else Constants.goldPerTurn
            list.add(TradeOffer(name, type, amount, duration))
        }

        private fun removeItem(list: TradeOffersList, type: TradeOfferType) {
            list.firstOrNull { it.type == type }?.let { list.remove(it) }
        }

        fun addGoldToThem(dontChange: Boolean) {
            if (dontChange || total >= 0 || amountOf(trade.ourOffers, TradeOfferType.Gold) != 0) return
            var gold = getGoldForValueExchange(us, them, -total, true, false, useEvenValue, false)
            val already = amountOf(trade.theirOffers, TradeOfferType.Gold)
            gold = min(gold + already, goldAvailable(trade, them, false))
            if (gold != already) setAmount(trade.theirOffers, TradeOfferType.Gold, gold, -1)
            recompute()
        }

        fun addGoldToUs(dontChange: Boolean) {
            if (dontChange || total <= 0 || amountOf(trade.theirOffers, TradeOfferType.Gold) != 0) return
            var gold = getGoldForValueExchange(us, them, total, true, true, useEvenValue, false)
            val already = amountOf(trade.ourOffers, TradeOfferType.Gold)
            gold = min(gold + already, goldAvailable(trade, us, true))
            if (gold != already) setAmount(trade.ourOffers, TradeOfferType.Gold, gold, -1)
            recompute()
        }

        fun addGptToThem(dontChange: Boolean, duration: Int) {
            if (dontChange || total >= 0 || goldRate(them) <= 0 || amountOf(trade.ourOffers, TradeOfferType.Gold_Per_Turn) != 0) return
            var gpt = getGptForValueExchange(us, them, -total, true, duration, false, useEvenValue, false)
            val already = amountOf(trade.theirOffers, TradeOfferType.Gold_Per_Turn)
            gpt = min(gpt + already, goldRate(them))
            if (gpt != already) setAmount(trade.theirOffers, TradeOfferType.Gold_Per_Turn, gpt, duration)
            recompute()
        }

        fun addGptToUs(dontChange: Boolean, duration: Int) {
            if (dontChange || total <= 0 || goldRate(us) <= 0 || amountOf(trade.theirOffers, TradeOfferType.Gold_Per_Turn) != 0) return
            var gpt = getGptForValueExchange(us, them, total, true, duration, true, useEvenValue, false)
            val already = amountOf(trade.ourOffers, TradeOfferType.Gold_Per_Turn)
            gpt = min(gpt + already, goldRate(us))
            if (gpt != already) setAmount(trade.ourOffers, TradeOfferType.Gold_Per_Turn, gpt, duration)
            recompute()
        }

        /** CvDealAI::DoRemoveGPTFromThem: trims their GPT by the GPT worth 150% of our surplus */
        fun removeGptFromThem(duration: Int) {
            if (total <= 0) return
            val toRemove = getGptForValueExchange(us, them, total * 150 / 100, true, duration, false, useEvenValue, true)
            val inDeal = amountOf(trade.theirOffers, TradeOfferType.Gold_Per_Turn)
            if (inDeal <= 0) return
            val left = inDeal - min(toRemove, inDeal)
            if (left == 0) removeItem(trade.theirOffers, TradeOfferType.Gold_Per_Turn)
            else setAmount(trade.theirOffers, TradeOfferType.Gold_Per_Turn, left, duration)
            recompute()
        }

        /** CvDealAI::DoRemoveGPTFromUs */
        fun removeGptFromUs(duration: Int) {
            if (total >= 0) return
            val toRemove = getGptForValueExchange(us, them, -total * 150 / 100, true, duration, true, useEvenValue, true)
            val inDeal = amountOf(trade.ourOffers, TradeOfferType.Gold_Per_Turn)
            if (inDeal <= 0) return
            val left = inDeal - min(toRemove, inDeal)
            if (left == 0) removeItem(trade.ourOffers, TradeOfferType.Gold_Per_Turn)
            else setAmount(trade.ourOffers, TradeOfferType.Gold_Per_Turn, left, duration)
            recompute()
        }

        /** CvDealAI::DoRemoveGoldFromThem */
        fun removeGoldFromThem() {
            if (total <= 0) return
            val inDeal = amountOf(trade.theirOffers, TradeOfferType.Gold)
            if (inDeal <= 0) return
            val left = inDeal - min(inDeal, getGoldForValueExchange(us, them, total, true, false, useEvenValue, true))
            if (left == 0) removeItem(trade.theirOffers, TradeOfferType.Gold)
            else setAmount(trade.theirOffers, TradeOfferType.Gold, left, -1)
            recompute()
        }

        /** CvDealAI::DoRemoveGoldFromUs */
        fun removeGoldFromUs() {
            if (total >= 0) return
            val inDeal = amountOf(trade.ourOffers, TradeOfferType.Gold)
            if (inDeal <= 0) return
            val left = inDeal - min(inDeal, getGoldForValueExchange(us, them, -total, true, true, useEvenValue, true))
            if (left == 0) removeItem(trade.ourOffers, TradeOfferType.Gold)
            else setAmount(trade.ourOffers, TradeOfferType.Gold, left, -1)
            recompute()
        }
    }

    /** CvPlayer::calculateGoldRate */
    fun goldRate(civ: Civilization) = civ.stats.statsForNextTurn.gold.toInt()

    /** CvDeal::GetGoldAvailable(ePlayer, TRADE_ITEM_GOLD): the civ's gold minus research agreement costs it pays in [trade] */
    fun goldAvailable(trade: Trade, civ: Civilization, civGivesOurOffers: Boolean): Int {
        val list = if (civGivesOurOffers) trade.ourOffers else trade.theirOffers
        var gold = civ.gold
        for (offer in list) if (offer.type == TradeOfferType.Treaty && offer.name == Constants.researchAgreement) gold -= offer.amount
        return gold
    }

    /**
     *  CvDeal::IsPossibleToTradeItem for an item [from] would add to [trade] (given toward [to]); [fromGivesOurOffers] = [from] is the
     *  player whose items are `trade.ourOffers`. The item must be in [from]'s available offers with enough amount, and: lump gold needs a
     *  mutual Declaration of Friendship outside peace deals and no more than the gold available; GPT no more than the gold rate;
     *  a luxury the receiver does not have yet and is not giving in this deal.
     */
    fun isPossibleToTradeItem(trade: Trade, from: Civilization, to: Civilization, offer: TradeOffer, fromGivesOurOffers: Boolean,
                              available: TradeOffersList): Boolean {
        val receiverGives = if (fromGivesOurOffers) trade.theirOffers else trade.ourOffers
        when (offer.type) {
            TradeOfferType.Gold -> {
                if (!trade.isPeaceTreaty() && !TradeEvaluation.hasMutualDeclarationOfFriendship(from, to)) return false
                return offer.amount <= goldAvailable(trade, from, fromGivesOurOffers)
            }
            TradeOfferType.Gold_Per_Turn -> return offer.amount <= goldRate(from)
            TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource -> {
                val resource = from.gameInfo.ruleset.tileResources[offer.name] ?: return false
                if (offer.amount > numResourceAvailable(from, resource, false)) return false
                if (resource.resourceType == ResourceType.Luxury && numResourceAvailable(to, resource, true) > 0) return false
                if (receiverGives.any { it.name == offer.name && it.type == offer.type }) return false
                return available.any { it.name == offer.name && it.type == offer.type }
            }
            TradeOfferType.VoteCommitment -> return !from.isAtWarWith(to) && LeagueAutomation.canCommitVote(from, to)
            else -> return available.any { it.name == offer.name && it.type == offer.type && it.amount >= offer.amount }
        }
    }
    //endregion

    //region Peace

    /**
     *  CvDealAI::IsOfferPeace: fills [trade] (seen from [us]) with a peace treaty both sides accept. Between AIs: what we are willing to
     *  offer must reach what they want to accept and vice versa; when both would give something up, both levels drop until one is white
     *  peace; a gap is split in the middle; the side giving more surrenders that treaty's items. With a human: we surrender if we are
     *  willing to give anything, else we ask them to surrender what we want; white peace adds only the allied city-states' peace.
     */
    fun isOfferPeace(us: Civilization, them: Civilization, trade: Trade): Boolean {
        if (!us.isAtWarWith(them)) return false
        var imWillingToOffer = us.ai.diplomacy.treatyWillingToOffer(them)
        val imWillingToAccept = us.ai.diplomacy.treatyWillingToAccept(them)
        if (imWillingToOffer < PeaceTreatyType.WhitePeace || imWillingToAccept < PeaceTreatyType.WhitePeace) return false

        if (!them.isHuman()) {
            val theyreWillingToAccept = them.ai.diplomacy.treatyWillingToAccept(us)
            var theyreWillingToOffer = them.ai.diplomacy.treatyWillingToOffer(us)
            if (imWillingToOffer < theyreWillingToAccept) return false
            if (theyreWillingToOffer < imWillingToAccept) return false
            val white = PeaceTreatyType.WhitePeace.ordinal
            if (imWillingToOffer.ordinal > white && theyreWillingToOffer.ordinal > white) {
                // The original subtracts the enum values themselves, NO_PEACE_TREATY_TYPE being -1 and WHITE_PEACE 0
                val reduce = min(imWillingToOffer.ordinal, theyreWillingToOffer.ordinal) - white
                imWillingToOffer = PeaceTreatyType.entries[imWillingToOffer.ordinal - reduce]
                theyreWillingToOffer = PeaceTreatyType.entries[theyreWillingToOffer.ordinal - reduce]
            }
            if (imWillingToOffer > theyreWillingToAccept)
                imWillingToOffer = PeaceTreatyType.entries[(imWillingToOffer.ordinal - white + theyreWillingToAccept.ordinal - white) / 2 + white]
            if (theyreWillingToOffer > imWillingToAccept)
                theyreWillingToOffer = PeaceTreatyType.entries[(theyreWillingToOffer.ordinal - white + imWillingToAccept.ordinal - white) / 2 + white]
            if (imWillingToOffer > theyreWillingToOffer)
                addItemsToDealForPeaceTreaty(trade, us, them, imWillingToOffer, meSurrendering = true)
            else if (imWillingToOffer < theyreWillingToOffer)
                addItemsToDealForPeaceTreaty(trade, us, them, theyreWillingToOffer, meSurrendering = false)
        } else {
            when {
                imWillingToOffer > PeaceTreatyType.WhitePeace -> addItemsToDealForPeaceTreaty(trade, us, them, imWillingToOffer, meSurrendering = true)
                imWillingToAccept > PeaceTreatyType.WhitePeace -> addItemsToDealForPeaceTreaty(trade, us, them, imWillingToAccept, meSurrendering = false)
                else -> addPlayersAlliesToTreaty(trade, us, them)
            }
        }
        val duration = us.gameInfo.speed.peaceDealDuration
        trade.ourOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, 1, duration))
        trade.theirOffers.add(TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, 1, duration))
        return true
    }

    /**
     *  CvDealAI::DoAddItemsToDealForPeaceTreaty: the loser's package by treaty. Armistice: 50% of gold and GPT; Settlement: 100%;
     *  Backdown: + open borders and strategic resources; Submission: + luxuries; Surrender: one city; Cession: 25% of city value and 50% gold;
     *  Capitulation: 33% and all gold; Unconditional surrender: every city but the capital and all gold. GPT is the lesser of the loser's
     *  rate and the winner's rate / ARMISTICE_GPT_DIVISOR; cities go closest to the winner's capital first (the winner's former cities count
     *  a third of their distance). The allied city-states' peace is always added.
     */
    fun addItemsToDealForPeaceTreaty(trade: Trade, us: Civilization, them: Civilization, treaty: PeaceTreatyType, meSurrendering: Boolean) {
        var percentGold = 0
        var percentGpt = 0
        var giveOpenBorders = false
        var giveOnlyOneCity = false
        var percentCities = 0
        var giveStrategic = false
        var giveLuxury = false
        when (treaty) {
            PeaceTreatyType.Armistice -> { percentGold = 50; percentGpt = 50 }
            PeaceTreatyType.Settlement -> { percentGold = 100; percentGpt = 100 }
            PeaceTreatyType.Backdown -> { percentGold = 100; percentGpt = 100; giveOpenBorders = true; giveStrategic = true }
            PeaceTreatyType.Submission -> { percentGold = 100; percentGpt = 100; giveOpenBorders = true; giveStrategic = true; giveLuxury = true }
            PeaceTreatyType.Surrender -> giveOnlyOneCity = true
            PeaceTreatyType.Cession -> { percentCities = 25; percentGold = 50 }
            PeaceTreatyType.Capitulation -> { percentCities = 33; percentGold = 100 }
            PeaceTreatyType.UnconditionalSurrender -> { percentCities = 100; percentGold = 100 }
            else -> {}
        }
        val duration = us.gameInfo.speed.dealDuration
        val loser = if (meSurrendering) us else them
        val winner = if (meSurrendering) them else us
        val loserItems = if (meSurrendering) trade.ourOffers else trade.theirOffers
        // Peace items first, so that the gold checks see a peace deal (the original sets the treaty type before adding items)
        val peace = TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, 1, us.gameInfo.speed.peaceDealDuration)
        val hadPeace = trade.isPeaceTreaty()
        if (!hadPeace) trade.ourOffers.add(peace)

        addPlayersAlliesToTreaty(trade, us, them)
        val available = TradeLogic(loser, winner).ourAvailableOffers

        if (percentGold > 0) {
            var gold = goldAvailable(trade, loser, meSurrendering)
            if (gold > 0) {
                gold = gold * percentGold / 100
                val offer = TradeOffer(Constants.flatGold, TradeOfferType.Gold, gold, -1)
                if (gold > 0 && isPossibleToTradeItem(trade, loser, winner, offer, meSurrendering, available)) loserItems.add(offer)
            }
        }
        if (percentGpt > 0) {
            var gpt = min(goldRate(loser), goldRate(winner) / loser.define(AiDefine.ARMISTICE_GPT_DIVISOR))
            if (gpt > 0) {
                gpt = gpt * percentGpt / 100
                val offer = TradeOffer(Constants.goldPerTurn, TradeOfferType.Gold_Per_Turn, gpt, duration)
                if (gpt > 0 && isPossibleToTradeItem(trade, loser, winner, offer, meSurrendering, available)) loserItems.add(offer)
            }
        }
        if (giveOpenBorders) {
            val offer = TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, duration)
            if (isPossibleToTradeItem(trade, loser, winner, offer, meSurrendering, available)) loserItems.add(offer)
        }
        for (resource in us.gameInfo.ruleset.tileResources.values) {
            if (resource.resourceType == ResourceType.Bonus) continue
            var quantity = numResourceAvailable(loser, resource, false)
            if (quantity == 0) continue
            if (resource.resourceType == ResourceType.Luxury && !giveLuxury) continue
            if (resource.resourceType == ResourceType.Strategic && !giveStrategic) continue
            if (resource.resourceType == ResourceType.Luxury) quantity = 1
            val type = if (resource.resourceType == ResourceType.Luxury) TradeOfferType.Luxury_Resource else TradeOfferType.Strategic_Resource
            val offer = TradeOffer(resource.name, type, quantity, duration)
            if (isPossibleToTradeItem(trade, loser, winner, offer, meSurrendering, available)) loserItems.add(offer)
        }

        if (percentCities == 100) {
            for (city in loser.cities) {
                if (city.isCapital()) continue
                val offer = TradeOffer(city.id, TradeOfferType.City, 1, -1)
                if (isPossibleToTradeItem(trade, loser, winner, offer, meSurrendering, available)) loserItems.add(offer)
            }
        } else if (percentCities > 0 || giveOnlyOneCity && loser.cities.size > 1) {
            var totalCityValue = 0
            val winnerCapital = winner.getCapital()
            val candidates = ArrayList<Pair<City, Int>>()
            for (city in loser.cities) {
                totalCityValue += getCityValue(us, them, city, meSurrendering, true)
                var distance = if (winnerCapital != null) winnerCapital.getCenterTile().aerialDistanceTo(city.getCenterTile()) else 0
                if (city.foundingCivObject == winner) distance /= 3
                if (!city.isCapital()) candidates.add(city to distance)
            }
            // CvWeightedVector::SortItems sorts by descending weight, and the loop runs from the back: closest first
            candidates.sortBy { it.second }
            if (giveOnlyOneCity) {
                val closest = candidates.firstOrNull()
                if (closest != null) loserItems.add(TradeOffer(closest.first.id, TradeOfferType.City, 1, -1))
            } else {
                var valueToSurrender = totalCityValue * percentCities / 100
                for ((city, _) in candidates) {
                    val cityValue = getCityValue(us, them, city, meSurrendering, true)
                    if (cityValue < valueToSurrender) {
                        val offer = TradeOffer(city.id, TradeOfferType.City, 1, -1)
                        if (isPossibleToTradeItem(trade, loser, winner, offer, meSurrendering, available)) {
                            loserItems.add(offer)
                            valueToSurrender -= cityValue
                        }
                    }
                }
            }
        }
        if (!hadPeace) trade.ourOffers.remove(peace)
    }

    /**
     *  CvDealAI::DoAddPlayersAlliesToTreaty: peace with each city-state allied to [them] that is at war with us, and with each
     *  city-state allied to us that is at war with them (no permanent wars in Unciv).
     */
    fun addPlayersAlliesToTreaty(trade: Trade, us: Civilization, them: Civilization) {
        val duration = us.gameInfo.speed.peaceDealDuration
        for (minor in us.gameInfo.getAliveCityStates()) {
            val ally = minor.allyCiv
            if (ally == them) {
                if (!us.isAtWarWith(minor)) continue
                if (trade.ourOffers.none { it.type == TradeOfferType.PeaceProposal && it.name == minor.civID })
                    trade.ourOffers.add(TradeOffer(minor.civID, TradeOfferType.PeaceProposal, 1, duration))
            } else if (ally == us) {
                if (!them.isAtWarWith(minor)) continue
                if (trade.theirOffers.none { it.type == TradeOfferType.PeaceProposal && it.name == minor.civID })
                    trade.theirOffers.add(TradeOffer(minor.civID, TradeOfferType.PeaceProposal, 1, duration))
            }
        }
    }
    //endregion

    //region Offers

    /**
     *  CvDealAI::IsMakeOfferForLuxuryResource: unless Hostile or planning war, ask for the first luxury (ruleset order) they have a spare
     *  copy of and we are allowed to get happiness from, then balance the deal (DoEqualizeDealWithAI between AIs).
     */
    fun isMakeOfferForLuxuryResource(us: Civilization, them: Civilization, trade: Trade): Boolean {
        val approach = us.ai.diplomacy.approachTowards(them, false)
        if (approach == MajorCivApproach.Hostile || approach == MajorCivApproach.War) return false
        val league = us.gameInfo.league
        val luxury = us.gameInfo.ruleset.tileResources.values.firstOrNull {
            it.resourceType == ResourceType.Luxury && league?.isLuxuryBanned(it.name) != true && numResourceAvailable(them, it, false) > 1
        } ?: return false
        val offer = TradeOffer(luxury.name, TradeOfferType.Luxury_Resource, 1, us.gameInfo.speed.dealDuration)
        if (!isPossibleToTradeItem(trade, them, us, offer, false, TradeLogic(us, them).theirAvailableOffers)) return false
        trade.theirOffers.add(offer)
        return if (!them.isHuman()) doEqualizeDealWithAI(us, them, trade)
            else doEqualizeDealWithHuman(us, them, trade, dontChangeMyExistingItems = false, dontChangeTheirExistingItems = true)
    }

    /** CvDiplomacyAI::WantsEmbassyAtPlayer: unless Hostile or planning war */
    fun wantsEmbassyAt(us: Civilization, them: Civilization): Boolean {
        val approach = us.ai.diplomacy.approachTowards(them, true)
        return approach != MajorCivApproach.Hostile && approach != MajorCivApproach.War
    }

    /** CvDealAI::MakeOfferForEmbassy: unless Hostile, Guarded or planning war, ask for an embassy in their capital and balance the deal */
    fun makeOfferForEmbassy(us: Civilization, them: Civilization, trade: Trade): Boolean {
        val approach = us.ai.diplomacy.approachTowards(them, false)
        if (approach == MajorCivApproach.Hostile || approach == MajorCivApproach.War || approach == MajorCivApproach.Guarded) return false
        val offer = TradeOffer(Constants.acceptEmbassy, TradeOfferType.Embassy, speed = us.gameInfo.speed)
        if (!isPossibleToTradeItem(trade, them, us, offer, false, TradeLogic(us, them).theirAvailableOffers)) return false
        if (!wantsEmbassyAt(us, them)) return false
        trade.theirOffers.add(offer)
        return if (!them.isHuman()) doEqualizeDealWithAI(us, them, trade)
            else doEqualizeDealWithHuman(us, them, trade, dontChangeMyExistingItems = false, dontChangeTheirExistingItems = true)
    }

    /**
     *  CvDiplomacyAI::IsWantsOpenBordersWithPlayer: the culture branch (CultureAutomation.wantsOpenBordersWith), else only with neighbours
     *  we are not Hostile, Guarded or Afraid toward and who are not stronger than us.
     */
    fun wantsOpenBordersWith(us: Civilization, them: Civilization): Boolean {
        if (CultureAutomation.wantsOpenBordersWith(us, them)) return true
        if (us.getProximity(them) != Proximity.Neighbors) return false
        val approach = us.ai.diplomacy.approachTowards(them, true)
        if (approach == MajorCivApproach.Hostile || approach == MajorCivApproach.Guarded || approach == MajorCivApproach.Afraid) return false
        val strength = us.ai.diplomacy.militaryStrength(them)
        return strength == null || strength < StrengthLevel.Strong
    }

    /** CvDealAI::IsMakeOfferForOpenBorders: unless Hostile or planning war, ask for open borders we want and balance the deal */
    fun isMakeOfferForOpenBorders(us: Civilization, them: Civilization, trade: Trade): Boolean {
        val approach = us.ai.diplomacy.approachTowards(them, false)
        if (approach == MajorCivApproach.Hostile || approach == MajorCivApproach.War) return false
        val offer = TradeOffer(Constants.openBorders, TradeOfferType.Agreement, 1, us.gameInfo.speed.dealDuration)
        if (!isPossibleToTradeItem(trade, them, us, offer, false, TradeLogic(us, them).theirAvailableOffers)) return false
        if (!wantsOpenBordersWith(us, them)) return false
        trade.theirOffers.add(offer)
        return if (!them.isHuman()) doEqualizeDealWithAI(us, them, trade)
            else doEqualizeDealWithHuman(us, them, trade, dontChangeMyExistingItems = false, dontChangeTheirExistingItems = true)
    }

    /** CvDealAI::IsMakeOfferForResearchAgreement: a research agreement on both sides, balanced (we may not change our side with a human) */
    fun isMakeOfferForResearchAgreement(us: Civilization, them: Civilization, trade: Trade): Boolean {
        if (!us.diplomacyFunctions.canSignResearchAgreementsWith(them)) return false
        val cost = us.diplomacyFunctions.getResearchAgreementCost(them)
        val duration = us.gameInfo.speed.dealDuration
        trade.ourOffers.add(TradeOffer(Constants.researchAgreement, TradeOfferType.Treaty, cost, duration))
        trade.theirOffers.add(TradeOffer(Constants.researchAgreement, TradeOfferType.Treaty, cost, duration))
        return if (!them.isHuman()) doEqualizeDealWithAI(us, them, trade)
            else doEqualizeDealWithHuman(us, them, trade, dontChangeMyExistingItems = true, dontChangeTheirExistingItems = false)
    }

    /** CvDiplomacyAI::IsEmbassyExchangeAcceptable: not when planning war, Hostile or Guarded */
    fun isEmbassyExchangeAcceptable(us: Civilization, them: Civilization): Boolean {
        val approach = us.ai.diplomacy.approachTowards(them, true)
        return approach != MajorCivApproach.War && approach != MajorCivApproach.Hostile && approach != MajorCivApproach.Guarded
    }

    /** CvDiplomacyAI::IsOpenBordersExchangeAcceptable: only when Friendly or Afraid */
    fun isOpenBordersExchangeAcceptable(us: Civilization, them: Civilization): Boolean {
        val approach = us.ai.diplomacy.approachTowards(them, true)
        return approach == MajorCivApproach.Friendly || approach == MajorCivApproach.Afraid
    }

    /** Whether [us] already has the resource-trade-cut-short distrust flag against [them] (Unciv rule kept for deals with humans) */
    @Suppress("unused")
    private fun distrusts(us: Civilization, them: Civilization) =
        us.getDiplomacyManager(them)?.hasFlag(DiplomacyFlags.ResourceTradesCutShort) == true
    //endregion
}
