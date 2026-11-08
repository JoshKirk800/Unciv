package com.unciv.logic.civilization.diplomacy

import com.unciv.Constants
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.civilization.DiplomacyAction
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.trade.Trade
import com.unciv.logic.trade.TradeOffer
import com.unciv.logic.trade.TradeOfferType
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.ui.components.extensions.toPercent
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min

object DiplomacyTurnManager {

    fun DiplomacyManager.nextTurn():Unit = timeThis("DiplomacyManager.nextTurn") {
        nextTurnTrades()
        removeUntenableTrades()
        updateHasOpenBorders()
        nextTurnCityStateUnitGifts()
        nextTurnFlags()
        if (civInfo.isCityState && otherCiv.isMajorCiv)
            nextTurnCityStateInfluence()
    }

    private fun DiplomacyManager.removeUntenableTrades() {
        for (trade in trades.toList()) {

            // Every cancelled trade can change this - if 1 resource is missing,
            // don't cancel all trades of that resource, only cancel one (the first one, as it happens, since they're added chronologically)
            val negativeCivResources = civInfo.getCivResourceSupply()
                .filter { it.amount < 0 && !it.resource.isStockpiled }.map { it.resource.name }

            for (offer in trade.ourOffers) {
                if (offer.type in listOf(TradeOfferType.Luxury_Resource, TradeOfferType.Strategic_Resource)
                    && (offer.name in negativeCivResources || !civInfo.gameInfo.ruleset.tileResources.containsKey(offer.name))
                ) {

                    trades.remove(trade)
                    val otherCivTrades = otherCiv.getDiplomacyManager(civInfo)!!.trades
                    otherCivTrades.removeAll { it.equalTrade(trade.reverse()) }

                    // Can't cut short peace treaties!
                    if (trade.theirOffers.any { it.name == Constants.peaceTreaty }) {
                        remakePeaceTreaty(trade.theirOffers.first { it.name == Constants.peaceTreaty }.duration)
                    }

                    civInfo.addNotification("One of our trades with [${otherCiv.civName}] has been cut short",
                        DiplomacyAction(otherCiv, true),
                        NotificationCategory.Trade, NotificationIcon.Trade, otherCiv.civName)
                    otherCiv.addNotification("One of our trades with [${civInfo.civName}] has been cut short",
                        DiplomacyAction(civInfo, true),
                        NotificationCategory.Trade, NotificationIcon.Trade, civInfo.civName)
                    // If you cut a trade short, we're not going to trust you with per-turn trades for a while
                    otherCivDiplomacy().setFlag(DiplomacyFlags.ResourceTradesCutShort, civInfo.gameInfo.speed.dealDuration * 2)
                    civInfo.cache.updateCivResources()
                }
            }
        }
    }

    private fun DiplomacyManager.remakePeaceTreaty(durationLeft: Int) {
        val treaty = Trade()
        treaty.ourOffers.add(
            TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, duration = durationLeft)
        )
        treaty.theirOffers.add(
            TradeOffer(Constants.peaceTreaty, TradeOfferType.Treaty, duration = durationLeft)
        )
        trades.add(treaty)
        otherCiv.getDiplomacyManager(civInfo)!!.trades.add(treaty.reverse())
    }

    /** The extra influence per turn the policies of [otherCiv] give with this City-State, before it is applied (CvMinorCivAI::GetFriendshipChangePerTurnTimes100, CvMinorCivAI.cpp:5314-5341).
     *  Run from the City-State's DiplomacyManager. */
    @Readonly
    private fun DiplomacyManager.getCityStateInfluencePerTurnBonus(): Float {
        var bonus = 0

        // POLICYMOD_PROTECTED_MINOR_INFLUENCE: IsPlayerConnectedToPlayer, i.e. a trade route in either direction
        if (otherCiv.hasUnique(UniqueType.CityStateInfluenceWithTradeRoute)
            && (otherCiv.tradeRoutes.routes.any { it.getDestinationCiv() == civInfo }
                || civInfo.tradeRoutes.routes.any { it.getDestinationCiv() == otherCiv }))
            otherCiv.forEachMatchingUnique(UniqueType.CityStateInfluenceWithTradeRoute) { bonus += it.params[0].toInt() }

        // POLICYMOD_AFRAID_INFLUENCE: CanMajorBullyGold is "bully metric >= 0". The closest equivalent here is a tribute willingness of
        // at least 0 for gold (the same threshold that enables the Demand Tribute button), not the "> 0" that shows the Afraid relationship
        if (otherCiv.hasUnique(UniqueType.CityStateInfluenceWhenAfraid) && civInfo.cityStateFunctions.getTributeWillingness(otherCiv) >= 0)
            otherCiv.forEachMatchingUnique(UniqueType.CityStateInfluenceWhenAfraid) { bonus += it.params[0].toInt() }

        // "Mod everything by game speed": GetGoldGiftMod
        return bonus * civInfo.gameInfo.speed.goldGiftModifier
    }

    private fun DiplomacyManager.nextTurnCityStateInfluence() {
        val initialRelationshipLevel = relationshipIgnoreAfraid()  // Enough since only >= Friend is notified

        val restingPoint = getCityStateInfluenceRestingPoint()
        // We don't use `getInfluence()` here, as then during war with the ally of this CS,
        // our influence would be set to -59, overwriting the old value, which we want to keep
        // as it should be restored once the war ends (though we keep influence degradation from time during the war)
        val perTurnBonus = getCityStateInfluencePerTurnBonus()
        if (perTurnBonus != 0f) {
            // CvMinorCivAI::DoFriendship (CvMinorCivAI.cpp:5192-5203): the bonus is added on top of the decay or recovery and,
            // unlike the recovery, is not stopped at the resting point; only a decay never takes us below it
            val baseChange = when {
                influence > restingPoint -> -getCityStateInfluenceDegrade()
                influence < restingPoint -> getCityStateInfluenceRecovery()
                else -> 0f
            }
            val newInfluence = influence + baseChange + perTurnBonus
            setInfluence(if (influence >= restingPoint) max(restingPoint, newInfluence) else newInfluence)
        } else if (influence > restingPoint) {
            val decrement = getCityStateInfluenceDegrade()
            setInfluence(max(restingPoint, influence - decrement))
        } else if (influence < restingPoint) {
            val increment = getCityStateInfluenceRecovery()
            setInfluence(min(restingPoint, influence + increment))
        }

        if (!civInfo.isDefeated()) { // don't display city state relationship notifications when the city state is currently defeated
            val notificationActions = civInfo.cityStateFunctions.getNotificationActions()
            if (getTurnsToRelationshipChange() == 1) {
                val text = "Your relationship with [${civInfo.civName}] is about to degrade"
                otherCiv.addNotification(text, notificationActions, NotificationCategory.Diplomacy, civInfo.civName, NotificationIcon.Diplomacy)
            }

            if (initialRelationshipLevel >= RelationshipLevel.Friend && initialRelationshipLevel != relationshipIgnoreAfraid()) {
                val text = "Your relationship with [${civInfo.civName}] degraded"
                otherCiv.addNotification(text, notificationActions, NotificationCategory.Diplomacy, civInfo.civName, NotificationIcon.Diplomacy)
            }

            // Potentially notify about afraid status
            if (getInfluence() < 30  // We usually don't want to bully our friends
                && !hasFlag(DiplomacyFlags.NotifiedAfraid)
                && civInfo.cityStateFunctions.getTributeWillingness(otherCiv) > 0
                && otherCiv.isMajorCiv
            ) {
                setFlag(DiplomacyFlags.NotifiedAfraid, 20)  // Wait 20 turns until next reminder
                val text = "[${civInfo.civName}] is afraid of your military power!"
                otherCiv.addNotification(text, notificationActions, NotificationCategory.Diplomacy, civInfo.civName, NotificationIcon.Diplomacy)
            }
        }
    }

    @Readonly
    private fun DiplomacyManager.getCityStateInfluenceRecovery(): Float {
        if (getInfluence() >= getCityStateInfluenceRestingPoint())
            return 0f

        val increment = 1f  // sic: personality does not matter here

        var modifierPercent = 0f

        if (otherCiv.hasUnique(UniqueType.CityStateInfluenceRecoversTwiceNormalRate))
            modifierPercent += 100f

        val religion = if (civInfo.cities.isEmpty() || civInfo.getCapital() == null) null
        else civInfo.getCapital()!!.religion.getMajorityReligionName()
        if (religion != null && religion == otherCiv.religionManager.religion?.name)
            modifierPercent += 50f  // 50% quicker recovery when sharing a religion

        return max(0f, increment) * max(0f, modifierPercent).toPercent()
    }

    private fun DiplomacyManager.nextTurnFlags() {
        loop@ for (flag in flagsCountdown.keys.toList()) {
            // Civ V remembers made/ignored no-dig promises indefinitely, unlike timed generic demands.
            if (flag == DiplomacyFlags.AgreedToNotDigInOurLands.name ||
                flag == DiplomacyFlags.IgnoreThemDiggingInOurLands.name) continue
            // We want negative flags to keep on going negative to keep track of time
            flagsCountdown[flag] = flagsCountdown[flag]!! - 1

            // If we have uniques that make city states grant military units faster when at war with a common enemy, add higher numbers to this flag
            if (flag == DiplomacyFlags.ProvideMilitaryUnit.name && civInfo.isMajorCiv && otherCiv.isCityState &&
                civInfo.gameInfo.civilizations.any { civInfo.isAtWarWith(it) && otherCiv.isAtWarWith(it) }) {
                for (unique in civInfo.getMatchingUniques(UniqueType.CityStateMoreGiftedUnits)) {
                    flagsCountdown[DiplomacyFlags.ProvideMilitaryUnit.name] =
                        flagsCountdown[DiplomacyFlags.ProvideMilitaryUnit.name]!! - unique.params[0].toInt() + 1
                    if (flagsCountdown[DiplomacyFlags.ProvideMilitaryUnit.name]!! <= 0) {
                        flagsCountdown[DiplomacyFlags.ProvideMilitaryUnit.name] = 0
                        break
                    }
                }
            }

            // At the end of every turn
            if (flag == DiplomacyFlags.ResearchAgreement.name)
                totalOfScienceDuringRA += civInfo.stats.statsForNextTurn.science.toInt()

            // Only when flag is expired
            if (flagsCountdown[flag] == 0) {
                when (flag) {
                    DiplomacyFlags.ResearchAgreement.name -> {
                        if (!otherCivDiplomacy().hasFlag(DiplomacyFlags.ResearchAgreement))
                            scienceFromResearchAgreement()
                    }
                    DiplomacyFlags.DefensivePact.name -> {
                        diplomaticStatus = DiplomaticStatus.Peace
                    }
                    // This is confusingly named - in fact, the civ that has the flag set is the MAJOR civ
                    DiplomacyFlags.ProvideMilitaryUnit.name -> {
                        // Do not unset the flag - they may return soon, and we'll continue from that point on
                        if (civInfo.cities.isEmpty() || otherCiv.cities.isEmpty())
                            continue@loop
                        else
                            otherCiv.cityStateFunctions.giveMilitaryUnitToPatron(civInfo)
                    }
                    
                    DiplomacyFlags.RecentlyAttacked.name -> {
                        civInfo.cityStateFunctions.askForUnitGifts(otherCiv)
                    }
                }

                flagsCountdown.remove(flag)
            } else if (flag == DiplomacyFlags.WaryOf.name && flagsCountdown[flag]!! < -10) {
                // City-states stay wary of an aggressor for 10 turns
                flagsCountdown.remove(flag)
            }
        }
    }


    private fun DiplomacyManager.scienceFromResearchAgreement() {
        // https://forums.civfanatics.com/resources/research-agreements-bnw.25568/
        val scienceFromResearchAgreement = min(totalOfScienceDuringRA, otherCivDiplomacy().totalOfScienceDuringRA)
        civInfo.tech.scienceFromResearchAgreements += scienceFromResearchAgreement
        otherCiv.tech.scienceFromResearchAgreements += scienceFromResearchAgreement
        totalOfScienceDuringRA = 0
        otherCivDiplomacy().totalOfScienceDuringRA = 0
    }

    private fun DiplomacyManager.nextTurnTrades() {
        for (trade in trades.toList()) {
            for (offer in trade.ourOffers.union(trade.theirOffers).filter { it.duration > 0 }) {
                offer.duration--
            }

            if (trade.ourOffers.all { it.duration <= 0 } && trade.theirOffers.all { it.duration <= 0 }) {
                trades.remove(trade)
                com.unciv.logic.automation.civilization.DealAi.onDealEnded(civInfo, otherCiv, trade)
                for (offer in trade.ourOffers.union(trade.theirOffers).filter { it.duration == 0 }) { // this was a timed trade
                    val direction = if (offer in trade.theirOffers) "from" else "to"
                    civInfo.addNotification("[${offer.name}] $direction [${otherCiv.civName}] has ended",
                        DiplomacyAction(otherCiv, true),
                        NotificationCategory.Trade, otherCiv.civName, NotificationIcon.Trade)

                    civInfo.updateStatsForNextTurn() // if they were bringing us gold per turn
                    if (trade.theirOffers.union(trade.ourOffers) // if resources were involved
                            .any { it.type == TradeOfferType.Luxury_Resource || it.type == TradeOfferType.Strategic_Resource })
                        civInfo.cache.updateCivResources()
                }
            }

            for (offer in trade.theirOffers.filter { it.duration <= 3 })
            {
                if (offer.duration == 3)
                    civInfo.addNotification("[${offer.name}] from [${otherCiv.civName}] will end in [3] turns",
                        DiplomacyAction(otherCiv, true),
                        NotificationCategory.Trade, otherCiv.civName, NotificationIcon.Trade)
                else if (offer.duration == 1)
                    civInfo.addNotification("[${offer.name}] from [${otherCiv.civName}] will end next turn",
                        DiplomacyAction(otherCiv, true),
                        NotificationCategory.Trade, otherCiv.civName, NotificationIcon.Trade)
            }
        }
    }

    /** The military units city-states give their friends (the opinion of majors is recomputed each turn by the diplomacy AI) */
    private fun DiplomacyManager.nextTurnCityStateUnitGifts() {
        val rng = state.stateBasedRandom("DiplomacyManager.nextTurnDiplomaticModifiers")
        if (!otherCiv.isCityState) return

        if (isRelationshipLevelLT(RelationshipLevel.Friend)) {
            if (hasFlag(DiplomacyFlags.ProvideMilitaryUnit))
                removeFlag(DiplomacyFlags.ProvideMilitaryUnit)
            return
        }

        val variance = listOf(-1, 0, 1).random(rng)

        val provideMilitaryUnitUniques = CityStateFunctions
            .getCityStateBonuses(otherCiv.cityStateType, relationshipIgnoreAfraid(), UniqueType.CityStateMilitaryUnits)
            .filter { it.conditionalsApply(civInfo.state) }.toList()
        if (provideMilitaryUnitUniques.isEmpty()) removeFlag(DiplomacyFlags.ProvideMilitaryUnit)

        for (unique in provideMilitaryUnitUniques) {
            // Reset the countdown if it has ended, or if we have longer to go than the current maximum (can happen when going from friend to ally)
            if (!hasFlag(DiplomacyFlags.ProvideMilitaryUnit) || getFlag(DiplomacyFlags.ProvideMilitaryUnit) > unique.params[0].toInt()) {
                setFlag(DiplomacyFlags.ProvideMilitaryUnit, unique.params[0].toInt() + variance)
            }
        }
    }
}
