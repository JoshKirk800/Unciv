package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.automation.diplomacy.WarGoal
import com.unciv.logic.automation.strategy.PurchaseType
import com.unciv.logic.automation.strategy.EconomicAiRatios
import com.unciv.logic.automation.strategy.StateAllWars
import com.unciv.logic.automation.strategy.TargetValue
import com.unciv.logic.automation.strategy.WarState
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.diplomacy.CityStatePersonality
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.ruleset.QuestName
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 *  What a major civilization's AI does about city-states (CvDiplomacyAI.cpp): the approach to each city-state
 *  (DoUpdateMinorCivApproaches / GetBestApproachTowardsMinorCiv), and DoContactMinorCivs: protection pledges and their withdrawal
 *  (DoUpdateMinorCivProtection), Austria's buyout, gold gifts (with the econ AI's MINOR_CIV_GIFT savings), and tribute of a worker or of
 *  gold. Also CvMinorCivAI::DoPickPersonality's flavor change for a city-state ([pickInitialFlavors]).
 *
 *  Proximity follows the original's PlayerProximityTypes ranks ([DiplomacyTurn.proximity]): DISTANT 0 .. NEIGHBORS 3.
 */
object CityStateAi {

    private fun Civilization.define(define: AiDefine) = gameInfo.ruleset.ai.define(define)

    //region Approaches

    /**
     *  CvDiplomacyAI::DoUpdateMinorCivApproaches: last turn's approaches are cleared, then the valid city-states are given a new one,
     *  nearest first (ties in player order); each choice sees those already made this turn.
     */
    fun updateMinorCivApproaches(civ: Civilization) = timeThis("AI.MinorCivApproaches") {
        val ai = civ.ai
        val minors = DiplomacyTurn.validPlayers(civ).filter { it.isCityState }
        val previous = HashMap(ai.minorCivApproaches)
        val assigned = HashMap<String, MinorCivApproach>()
        for (minor in minors.sortedByDescending { DiplomacyTurn.proximity(civ, it) }) {
            val old = previous[minor.civID] ?: MinorCivApproach.Ignore
            val weights = approachWeights(civ, minor, old, assigned, ai.random("DiplomacyAI.MinorCivApproach" + minor.civID))
            assigned[minor.civID] = bestOf(weights)
        }
        ai.minorCivApproaches.clear()
        ai.minorCivApproaches.putAll(assigned)
    }

    /** CvWeightedVector::SortItems then GetElement(0): the highest weight, the first approach on a tie */
    fun bestOf(weights: IntArray): MinorCivApproach {
        var best = 0
        for (i in 1 until weights.size) if (weights[i] > weights[best]) best = i
        return MinorCivApproach.entries[best]
    }

    private fun MinorCivApproach.i() = ordinal

    /** CvMinorCivAI::IsFriends: influence at the friends threshold or more */
    fun isFriends(minor: Civilization, major: Civilization) = MinorCivHelpers.influence(minor, major) >= MinorCivHelpers.FRIENDS_THRESHOLD

    /** CvMinorCivAI::IsAllies */
    fun isAllies(minor: Civilization, major: Civilization) = minor.allyCiv == major

    /** CvMinorCivAI::IsProtectedByMajor */
    fun isProtectedBy(minor: Civilization, major: Civilization) = major in minor.cityStateFunctions.getProtectorCivs()

    /** CvMinorCivAI::CanMajorProtect: not at war, influence not negative, not in the lock-out after a pledge ended (Unciv's 20 turns after a withdrawal) */
    fun canMajorProtect(minor: Civilization, major: Civilization): Boolean {
        if (minor.isAtWarWith(major)) return false
        val diplomacy = minor.getDiplomacyManager(major) ?: return false
        if (diplomacy.getInfluence() < 0) return false
        return !diplomacy.hasFlag(DiplomacyFlags.RecentlyWithdrewProtection)
    }

    private fun hasQuest(minor: Civilization, major: Civilization, quest: QuestName) =
        minor.questManager.getAssignedQuestsFor(major).any { it.questName == quest.value }

    /** CvPlayerTraits::GetCityStateFriendshipModifier > 0 (Greece): slower influence decay or faster recovery from the civilization's trait */
    private fun hasFriendshipTrait(civ: Civilization) = civ.nation.uniqueObjects.any {
        it.type == UniqueType.CityStateInfluenceRecoversTwiceNormalRate
            || it.type == UniqueType.CityStateInfluenceDegradation && it.params[0].toInt() < 0
    }

    /** CvPlayerTraits::GetCityStateBonusModifier > 0 (Siam): more yields from city-states from the civilization's trait */
    private fun hasBonusTrait(civ: Civilization) = civ.nation.uniqueObjects.any {
        it.type == UniqueType.BonusStatsFromCityStates && it.params[0].toInt() > 0
    }

    /** CvPlayerTraits::GetCityStateCombatModifier > 0 (Mongolia): more strength against city-states from the civilization's trait */
    private fun hasCombatTrait(civ: Civilization) = civ.nation.uniqueObjects.any { unique ->
        unique.type == UniqueType.Strength && unique.params[0].toInt() > 0
            && unique.modifiers.any { modifier -> modifier.params.any { it == "City-States" } }
    }

    /** Gold per turn our trade routes to [minor] bring us (CvPlayerTrade::GetAllTradeValueFromPlayerTimes100(YIELD_GOLD) / 100) */
    private fun tradeGoldFrom(civ: Civilization, minor: Civilization): Int {
        var gold = 0f
        for (route in civ.tradeRoutes.routes) if (route.getDestinationCiv() == minor) gold += route.originYields.gold
        return gold.toInt()
    }

    /**
     *  CvDiplomacyAI::GetBestApproachTowardsMinorCiv: the weights of Ignore, Friendly, Protective, Conquest, Bully toward [minor]
     *  (MinorCivApproach order), with [old] last turn's approach and [assigned] the approaches already given this turn. [random] adds the
     *  original's random term (APPROACH_RANDOM_PERCENT of each positive weight); null leaves it out.
     */
    fun approachWeights(civ: Civilization, minor: Civilization, old: MinorCivApproach, assigned: Map<String, MinorCivApproach>, random: Random?): IntArray {
        val ai = civ.ai
        val w = IntArray(MinorCivApproach.entries.size)
        val ignore = MinorCivApproach.Ignore.i()
        val friendly = MinorCivApproach.Friendly.i()
        val protective = MinorCivApproach.Protective.i()
        val conquest = MinorCivApproach.Conquest.i()
        val bully = MinorCivApproach.Bully.i()

        w[ignore] += civ.define(AiDefine.MINOR_APPROACH_IGNORE_DEFAULT)
        w[old.i()] += civ.define(AiDefine.MINOR_APPROACH_BIAS_FOR_CURRENT)
        if (old == MinorCivApproach.Conquest) {
            w[ignore] += civ.define(AiDefine.MINOR_APPROACH_IGNORE_CURRENTLY_WAR)
            val warState = WarStateAi.warState(civ, minor)
            if (warState != null && warState > WarState.Stalemate) w[conquest] += civ.define(AiDefine.APPROACH_WAR_CURRENTLY_WAR)
        } else if (old == MinorCivApproach.Protective) {
            w[conquest] += civ.define(AiDefine.MINOR_APPROACH_WAR_CURRENTLY_PROTECTIVE)
            w[protective] += civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_CURRENTLY_PROTECTIVE)
            w[bully] += -10
        }
        if (civ.isAtWarWith(minor)) {
            w[protective] += civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_CURRENTLY_WAR)
            w[friendly] += civ.define(AiDefine.MINOR_APPROACH_FRIENDLY_CURRENTLY_WAR)
        }

        val numWeLack = MinorCivHelpers.numResourcesMajorLacks(minor, civ)
        if (numWeLack > 0) {
            w[friendly] += numWeLack * civ.define(AiDefine.MINOR_APPROACH_FRIENDLY_RESOURCES)
            w[protective] += numWeLack * civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_RESOURCES)
            w[conquest] += numWeLack
        }
        if (isFriends(minor, civ)) {
            w[conquest] += civ.define(AiDefine.MINOR_APPROACH_WAR_FRIENDS)
            w[friendly] += civ.define(AiDefine.MINOR_APPROACH_FRIENDLY_FRIENDS)
            w[protective] += civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_FRIENDS)
            w[bully] -= 10
        }
        if (isProtectedBy(minor, civ)) {
            w[protective] += 2
            w[bully] += -1
            w[conquest] += -2
        }
        for (approach in MinorCivApproach.entries) w[approach.i()] += ai.minorCivApproachBias(approach)

        val proximity = DiplomacyTurn.proximity(civ, minor)
        var isGoodWarTarget = false
        var checkIfGoodWarTarget = false
        when {
            ai.isGoingForWorldConquest() -> {
                w[conquest] += civ.define(AiDefine.MINOR_APPROACH_WAR_CONQUEST_GRAND_STRATEGY)
                w[protective] += civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_CONQUEST_GRAND_STRATEGY)
                w[friendly] += civ.define(AiDefine.MINOR_APPROACH_FRIENDLY_CONQUEST_GRAND_STRATEGY)
                w[bully] += 8
                if (proximity == DiplomacyTurn.PROXIMITY_NEIGHBORS) isGoodWarTarget = true
            }
            ai.isGoingForDiploVictory() -> {
                w[conquest] += civ.define(AiDefine.MINOR_APPROACH_WAR_DIPLO_GRAND_STRATEGY)
                w[ignore] += civ.define(AiDefine.MINOR_APPROACH_IGNORE_DIPLO_GRAND_STRATEGY)
                w[bully] += -15
                if (proximity >= DiplomacyTurn.PROXIMITY_CLOSE) w[protective] += civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_DIPLO_GRAND_STRATEGY_NEIGHBORS)
            }
            ai.isGoingForCultureVictory() -> {
                w[conquest] += civ.define(AiDefine.MINOR_APPROACH_WAR_CULTURE_GRAND_STRATEGY)
                w[ignore] += civ.define(AiDefine.MINOR_APPROACH_IGNORE_CULTURE_GRAND_STRATEGY)
                w[bully] += -10
                if (minor.cityStateType.name == CULTURED) w[protective] += civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_CULTURE_GRAND_STRATEGY_CST)
                else checkIfGoodWarTarget = true
            }
            else -> checkIfGoodWarTarget = true
        }
        if (checkIfGoodWarTarget) isGoodWarTarget = isOnLandmassOfMajorWeWantToAttack(civ, minor)
        if (isGoodWarTarget) {
            w[conquest] += civ.define(AiDefine.MINOR_APPROACH_WAR_CONQUEST_GRAND_STRATEGY_NEIGHBORS)
            w[bully] += 8
        }

        var bonusTraitMod = 0
        if (hasFriendshipTrait(civ)) bonusTraitMod += 100
        if (hasBonusTrait(civ)) bonusTraitMod += 100
        val combatTrait = hasCombatTrait(civ)
        if (combatTrait) bonusTraitMod -= 100
        if (bonusTraitMod != 0) {
            w[conquest] -= bonusTraitMod
            w[bully] -= bonusTraitMod / 5
            w[friendly] += bonusTraitMod / 5
            w[protective] += bonusTraitMod
        }
        if (bonusTraitMod >= 0 && civ.cities.size <= 1) w[conquest] -= 200

        val others = DiplomacyTurn.validPlayers(civ)
        val stateAllWars = WarStateAi.stateAllWars(civ)
        for (other in others) {
            if (other == minor || !civ.isAtWarWith(other)) continue
            w[conquest] += when (stateAllWars) {
                StateAllWars.Neutral -> civ.define(AiDefine.APPROACH_WAR_AT_WAR_WITH_PLAYER_WARS_NEUTRAL)
                StateAllWars.Winning -> civ.define(AiDefine.APPROACH_WAR_AT_WAR_WITH_PLAYER_WARS_WINNING)
                StateAllWars.Losing -> civ.define(AiDefine.APPROACH_WAR_AT_WAR_WITH_PLAYER_WARS_LOSING)
            }
        }
        for (other in others) {
            if (other == minor) continue
            if (ai.diplomacy.warGoal(other) == WarGoal.Prepare
                || other.isMajorCiv && ai.diplomacy.approachTowards(other) == MajorCivApproach.War)
                w[conquest] += civ.define(AiDefine.MINOR_APPROACH_WAR_PLANNING_WAR_WITH_ANOTHER_PLAYER)
            if (other.isCityState) when (assigned[other.civID]) {
                MinorCivApproach.Conquest -> w[conquest] += civ.define(AiDefine.MINOR_APPROACH_WAR_PLANNING_WAR_WITH_ANOTHER_PLAYER)
                MinorCivApproach.Protective -> w[protective] += civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_WITH_ANOTHER_PLAYER)
                MinorCivApproach.Bully -> w[bully] += -2
                else -> {}
            }
        }

        when (proximity) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> {
                w[ignore] += civ.define(AiDefine.MINOR_APPROACH_IGNORE_PROXIMITY_NEIGHBORS)
                w[friendly] += civ.define(AiDefine.MINOR_APPROACH_FRIENDLY_PROXIMITY_NEIGHBORS)
                w[protective] += civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_PROXIMITY_NEIGHBORS)
                w[conquest] += civ.define(AiDefine.MINOR_APPROACH_CONQUEST_PROXIMITY_NEIGHBORS)
                w[bully] += 1
            }
            DiplomacyTurn.PROXIMITY_CLOSE -> {
                w[ignore] += civ.define(AiDefine.MINOR_APPROACH_IGNORE_PROXIMITY_CLOSE)
                w[protective] += civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_PROXIMITY_CLOSE)
                w[conquest] += civ.define(AiDefine.MINOR_APPROACH_CONQUEST_PROXIMITY_CLOSE)
                w[bully] += 1
            }
            DiplomacyTurn.PROXIMITY_FAR -> {
                w[friendly] += civ.define(AiDefine.MINOR_APPROACH_FRIENDLY_PROXIMITY_FAR)
                w[conquest] += civ.define(AiDefine.MINOR_APPROACH_CONQUEST_PROXIMITY_FAR)
            }
            DiplomacyTurn.PROXIMITY_DISTANT -> {
                w[friendly] += civ.define(AiDefine.MINOR_APPROACH_FRIENDLY_PROXIMITY_DISTANT)
                w[conquest] += civ.define(AiDefine.MINOR_APPROACH_CONQUEST_PROXIMITY_DISTANT)
                w[bully] += -2
            }
        }

        when (minor.cityStatePersonality) {
            CityStatePersonality.Friendly -> {
                w[friendly] += civ.define(AiDefine.MINOR_APPROACH_FRIENDLY_PERSONALITY_FRIENDLY)
                w[protective] += civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_PERSONALITY_PROTECTIVE)
            }
            CityStatePersonality.Neutral -> {
                w[friendly] += civ.define(AiDefine.MINOR_APPROACH_FRIENDLY_PERSONALITY_NEUTRAL)
                w[protective] += civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_PERSONALITY_NEUTRAL)
            }
            CityStatePersonality.Hostile -> {
                w[friendly] += civ.define(AiDefine.MINOR_APPROACH_FRIENDLY_PERSONALITY_HOSTILE)
                w[protective] += civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_PERSONALITY_HOSTILE)
                w[conquest] += civ.define(AiDefine.MINOR_APPROACH_CONQUEST_PERSONALITY_HOSTILE)
                w[bully] += 1
            }
            CityStatePersonality.Irrational -> {
                w[friendly] += civ.define(AiDefine.MINOR_APPROACH_FRIENDLY_PERSONALITY_IRRATIONAL)
                w[protective] += civ.define(AiDefine.MINOR_APPROACH_PROTECTIVE_PERSONALITY_IRRATIONAL)
            }
        }
        if (minor.cityStateType.name == MILITARISTIC) w[bully] += -2
        if (minor.civID in ai.deals.bulliedMinors) w[bully] += 2
        if (hasQuest(minor, civ, QuestName.PledgeToProtect)) w[protective] += 3

        val tradeValue = tradeGoldFrom(civ, minor)
        if (tradeValue > 0) {
            w[protective] += 3
            w[bully] -= 3
            w[friendly] += 5
            w[conquest] -= 5
            val gpt = DealAi.goldRate(civ)
            val deltaGpt = gpt - tradeValue
            if (gpt >= 0 && deltaGpt < 0) w[conquest] += deltaGpt
        }

        var targetValue = ai.diplomacy.targetValue(minor)
        if (combatTrait && targetValue != null)
            targetValue = TargetValue.entries[min(targetValue.ordinal + 1, TargetValue.Soft.ordinal)]
        val targetPercent = when (targetValue) {
            TargetValue.Impossible -> civ.define(AiDefine.MINOR_APPROACH_WAR_TARGET_IMPOSSIBLE)
            TargetValue.Bad -> civ.define(AiDefine.MINOR_APPROACH_WAR_TARGET_BAD)
            TargetValue.Average -> civ.define(AiDefine.MINOR_APPROACH_WAR_TARGET_AVERAGE)
            TargetValue.Favorable -> civ.define(AiDefine.MINOR_APPROACH_WAR_TARGET_FAVORABLE)
            TargetValue.Soft -> civ.define(AiDefine.MINOR_APPROACH_WAR_TARGET_SOFT)
            null -> 100
        }
        w[conquest] = w[conquest] * targetPercent / 100

        val peaceTurn = ai.diplomacy.turnMadePeaceWith(minor)
        if (peaceTurn > -1 && civ.gameInfo.turns - peaceTurn < civ.define(AiDefine.TURNS_SINCE_PEACE_WEIGHT_DAMPENER))
            w[conquest] = w[conquest] * civ.define(AiDefine.APPROACH_WAR_HAS_MADE_PEACE_BEFORE_PERCENT) / 100

        val randomPercent = civ.define(AiDefine.APPROACH_RANDOM_PERCENT)
        for (i in w.indices) {
            w[i] *= 100
            val randAmount = w[i] * randomPercent
            if (randAmount > 0 && random != null) {
                val range = randAmount / 100
                if (range > 0) w[i] += random.nextInt(range)
            }
        }

        if (!canMajorProtect(minor, civ) && w[protective] > 0) w[protective] = 0
        if (!civ.isAtWarWith(minor) && civ.getDiplomacyManager(minor)?.canDeclareWar() != true && w[conquest] > 0) w[conquest] = 0
        if (isAllies(minor, civ)) {
            if (w[conquest] > 0) w[conquest] = 0
            if (w[bully] > 0) w[bully] = 0
        }
        return w
    }

    /**
     *  The landmass check of GetBestApproachTowardsMinorCiv: a major we are at peace with and plan war against has its capital on the
     *  landmass of ours and of [minor]'s, and both of us are neighbours of [minor]
     */
    private fun isOnLandmassOfMajorWeWantToAttack(civ: Civilization, minor: Civilization): Boolean {
        val ourCapital = civ.getCapital() ?: return false
        val minorCapital = minor.getCapital() ?: return false
        for (other in DiplomacyTurn.validPlayers(civ)) {
            if (!other.isMajorCiv || other == civ || civ.isAtWarWith(other)) continue
            if (civ.ai.diplomacy.approachTowards(other) != MajorCivApproach.War) continue
            val theirCapital = other.getCapital() ?: continue
            val continent = theirCapital.getCenterTile().getContinent()
            if (continent != minorCapital.getCenterTile().getContinent() || ourCapital.getCenterTile().getContinent() != continent) continue
            if (DiplomacyTurn.proximity(other, minor) >= DiplomacyTurn.PROXIMITY_NEIGHBORS
                && DiplomacyTurn.proximity(civ, minor) >= DiplomacyTurn.PROXIMITY_NEIGHBORS) return true
        }
        return false
    }
    //endregion

    //region Contacts

    /** CvDiplomacyAI::DoUpdateMinorCivProtection: an AI pledges to protect a Protective city-state when it can, and withdraws from the others */
    fun updateMinorCivProtection(civ: Civilization, minor: Civilization, approach: MinorCivApproach) {
        if (civ.isHuman()) return
        val functions = minor.cityStateFunctions
        if (approach == MinorCivApproach.Protective) {
            if (functions.otherCivCanPledgeProtection(civ)) functions.addProtectorCiv(civ)
        } else if (isProtectedBy(minor, civ) && functions.otherCivCanWithdrawProtection(civ)) {
            functions.removeProtectorCiv(civ)
        }
    }

    /** A gold gift DoContactMinorCivs wants to give (MinorGoldGiftInfo) */
    class GoldGift(val minor: Civilization) {
        var quickBoost = false
        var majorRival: Civilization? = null
    }

    /** The turn's wishes of DoContactMinorCivs; the random rolls use [Random] from [CivAi.random] */
    class ContactWishes(val buyout: Boolean, val goldGift: Boolean, val bullyUnit: Boolean, val bullyGold: Boolean)

    /** The first part of DoContactMinorCivs: whether we want to buy out, gift, take a worker or gold from a city-state this turn */
    fun contactWishes(civ: Civilization): ContactWishes {
        val ai = civ.ai
        val diplomacy = ai.personalityAndGrandStrategy(Flavor.Diplomacy)
        val gold = ai.personalityAndGrandStrategy(Flavor.Gold)
        val tileImprovement = ai.personalityAndGrandStrategy(Flavor.TileImprovement)
        val expansion = ai.personalityAndGrandStrategy(Flavor.Expansion)
        val gpt = DealAi.goldRate(civ)
        val savings = ai.purchaseSavings

        var buyout = false
        if (civ.hasUnique(UniqueType.CityStateCanBeBoughtForGold)) {
            buyout = ai.isEconomicStrategyActive(FOUND_CITY) || ai.isEconomicStrategyActive(EXPAND_LIKE_CRAZY)
                || ai.isEconomicStrategyActive(EXPAND_TO_OTHER_CONTINENTS) || WarStateAi.stateAllWars(civ) == StateAllWars.Losing
                || ai.isGoingForWorldConquest() || gpt > 100
            if (!buyout) buyout = ai.random("DiplomacyAI.Buyout").nextInt(100) < expansion * 5
        }

        var goldGift = diplomacy >= civ.define(AiDefine.MC_ALWAYS_GIFT_DIPLO_THRESHOLD) || ai.isGoingForDiploVictory()
            || ai.isGoingForCultureVictory() || savings.isSavingForThisPurchase(PurchaseType.MinorCivGift)
            || hasActiveGoldQuest(civ) || gpt > 100
        if (!goldGift) goldGift = ai.random("DiplomacyAI.GoldGift").nextInt(100) < diplomacy * civ.define(AiDefine.MC_SOMETIMES_GIFT_RAND_MULTIPLIER)

        var bullyUnit = EconomicAiRatios.workersToCitiesRatio(civ) < 0.25 && EconomicAiRatios.improvedToImprovablePlotsRatio(civ) < 0.50
        if (!bullyUnit) bullyUnit = ai.random("DiplomacyAI.BullyUnit").nextInt(100) < tileImprovement * 3

        var bullyGold = gold >= 6 || ai.isGoingForWorldConquest() || savings.isSavingForThisPurchase(PurchaseType.Unit)
            || savings.isSavingForThisPurchase(PurchaseType.Building) || ai.isEconomicStrategyActive(LOSING_MONEY) || gpt < 0
        if (!bullyGold) bullyGold = ai.random("DiplomacyAI.BullyGold").nextInt(100) < gold * 4
        return ContactWishes(buyout, goldGift, bullyUnit, bullyGold)
    }

    /** CvDiplomacyAI::IsHasActiveGoldQuest: a city-state asks us for gold */
    fun hasActiveGoldQuest(civ: Civilization) =
        civ.gameInfo.getAliveCityStates().any { civ.knows(it) && hasQuest(it, civ, QuestName.GiveGold) }

    /**
     *  CvDiplomacyAI::DoContactMinorCivs: for every valid city-state we are not at war with, the protection update, then the desire to
     *  buy it out (Austria), to give it gold, to take a worker, to take gold; then the buyout (one a turn, else saving), the gifts
     *  (several a turn, never below half of the turn's starting treasury, else saving), one worker, and every gold tribute.
     *  The route wish (SetWantToRouteConnectToMinor) is set for a neighbouring city-state with an active route quest for us that we are
     *  Friendly or Protective towards, cleared otherwise, and kept for a city-state we are at war with; [BuilderTaskingAi] reads it.
     *  Unciv has no intrusion state (SetMajorIntruding).
     */
    fun contactMinorCivs(civ: Civilization) = timeThis("AI.ContactMinorCivs") {
        val wishes = contactWishes(civ)
        val buyouts = ArrayList<Pair<Civilization, Int>>()
        val gifts = ArrayList<Pair<GoldGift, Int>>()
        val bullyUnits = ArrayList<Pair<Civilization, Int>>()
        val bullyGolds = ArrayList<Pair<Civilization, Int>>()
        val wantsRoute = civ.ai.wantsRouteToMinors
        // The original skips the wish update of a city-state at war, so that wish is kept as it was
        wantsRoute.retainAll { id -> civ.gameInfo.getCivilization(id).let { it.isAlive() && civ.isAtWarWith(it) } }
        for (minor in DiplomacyTurn.validPlayers(civ).filter { it.isCityState }) {
            if (civ.isAtWarWith(minor)) continue
            val approach = civ.ai.minorCivApproaches[minor.civID] ?: MinorCivApproach.Ignore
            updateMinorCivProtection(civ, minor, approach)
            if ((approach == MinorCivApproach.Protective || approach == MinorCivApproach.Friendly)
                && hasQuest(minor, civ, QuestName.Route) && DiplomacyTurn.proximity(civ, minor) == DiplomacyTurn.PROXIMITY_NEIGHBORS)
                wantsRoute.add(minor.civID)
            var buyingOut = false
            if (wishes.buyout) {
                val value = buyoutValue(civ, minor)
                if (value != null && value > 100) {
                    buyouts.add(minor to value)
                    buyingOut = true
                }
            }
            var giving = false
            if (wishes.goldGift && !buyingOut) {
                val gift = GoldGift(minor)
                val value = goldGiftValue(civ, minor, approach, gift)
                if (value != null && value > civ.define(AiDefine.MC_GIFT_WEIGHT_THRESHOLD)) {
                    gifts.add(gift to value)
                    giving = true
                }
            }
            var bullyingUnit = false
            if (wishes.bullyUnit && !buyingOut && !giving) {
                val value = bullyUnitValue(civ, minor, approach)
                if (value != null && value > 100) {
                    bullyUnits.add(minor to value)
                    bullyingUnit = true
                }
            }
            if (wishes.bullyGold && !buyingOut && !giving && !bullyingUnit) {
                val value = bullyGoldValue(civ, minor, approach)
                if (value != null && value > 100) bullyGolds.add(minor to value)
            }
        }

        val goldReserve = civ.gold
        val savings = civ.ai.purchaseSavings
        buyouts.sortByDescending { it.second }
        for ((minor, _) in buyouts) {
            val cost = minor.cityStateFunctions.getDiplomaticMarriageCost()
            if (civ.gold >= cost) {
                if (minor.cityStateFunctions.canBeMarriedBy(civ)) {
                    minor.cityStateFunctions.diplomaticMarriage(civ)
                    break
                }
            } else if (!savings.isSavingForThisPurchase(PurchaseType.MinorCivGift))
                savings.startSaveForPurchase(PurchaseType.MinorCivGift, cost, civ.define(AiDefine.AI_GOLD_PRIORITY_BUYOUT_CITY_STATE))
        }

        val large = civ.define(AiDefine.MINOR_GOLD_GIFT_LARGE)
        val medium = civ.define(AiDefine.MINOR_GOLD_GIFT_MEDIUM)
        val small = civ.define(AiDefine.MINOR_GOLD_GIFT_SMALL)
        gifts.sortByDescending { it.second }
        for ((gift, _) in gifts) {
            val goldLeft = civ.gold
            val amount = giftAmount(goldLeft, gift.quickBoost, small, medium, large)
            if (amount > 0 && goldLeft >= goldReserve / 2) {
                gift.minor.cityStateFunctions.receiveGoldGift(civ, amount)
                if (savings.isSavingForThisPurchase(PurchaseType.MinorCivGift)) savings.cancelSaveForPurchase(PurchaseType.MinorCivGift)
            } else if (!savings.isSavingForThisPurchase(PurchaseType.MinorCivGift)) {
                val toSave = if (gift.quickBoost) small else medium
                val priority = civ.define(AiDefine.AI_GOLD_PRIORITY_DIPLOMACY_BASE) +
                    civ.define(AiDefine.AI_GOLD_PRIORITY_DIPLOMACY_PER_FLAVOR_POINT) * civ.ai.personalityAndGrandStrategy(Flavor.Diplomacy)
                savings.startSaveForPurchase(PurchaseType.MinorCivGift, toSave, priority)
            }
        }

        bullyUnits.sortByDescending { it.second }
        for ((minor, _) in bullyUnits) {
            if (!canMajorBullyUnit(minor, civ)) continue
            minor.cityStateFunctions.tributeWorker(civ)
            civ.ai.deals.bulliedMinors.add(minor.civID)
            break
        }
        bullyGolds.sortByDescending { it.second }
        for ((minor, _) in bullyGolds) {
            if (!canMajorBullyGold(minor, civ)) continue
            minor.cityStateFunctions.tributeGold(civ)
            civ.ai.deals.bulliedMinors.add(minor.civID)
        }
    }

    /** The gift size of DoContactMinorCivs: a small one for a quick boost, else large, else medium, as far as the treasury allows; 0 if none */
    fun giftAmount(goldLeft: Int, quickBoost: Boolean, small: Int, medium: Int, large: Int): Int = when {
        goldLeft >= small && quickBoost -> small
        goldLeft >= large -> large
        goldLeft >= medium -> medium
        else -> 0
    }

    /**
     *  The gift desirability of DoContactMinorCivs for [minor], null when we are not Friendly or Protective toward it. Fills
     *  [gift]'s quick boost and rival. Counts only above MC_GIFT_WEIGHT_THRESHOLD.
     */
    fun goldGiftValue(civ: Civilization, minor: Civilization, approach: MinorCivApproach, gift: GoldGift): Int? {
        if (approach != MinorCivApproach.Protective && approach != MinorCivApproach.Friendly) return null
        val ai = civ.ai
        var value = civ.define(AiDefine.MC_GIFT_WEIGHT_THRESHOLD)
        value += min(max(0, DealAi.goldRate(civ) - 50), 100)
        val trait = minor.cityStateType.name
        if (ai.isGoingForDiploVictory()) value += civ.define(AiDefine.MC_GIFT_WEIGHT_DIPLO_VICTORY)
        if (civ.gameInfo.league?.unitedNations == true) value += civ.define(AiDefine.MC_GIFT_WEIGHT_DIPLO_VICTORY)
        else if (ai.isGoingForCultureVictory()) {
            if (trait == CULTURED) value += civ.define(AiDefine.MC_GIFT_WEIGHT_CULTURE_VICTORY)
        } else if (ai.isGoingForWorldConquest()) {
            if (trait == MILITARISTIC) value += civ.define(AiDefine.MC_GIFT_WEIGHT_CONQUEST_VICTORY)
        }
        if (hasQuest(minor, civ, QuestName.GiveGold)) value += 150
        if (hasQuest(minor, civ, QuestName.Invest)) value += 100
        if (hasFriendshipTrait(civ) || hasBonusTrait(civ)) value += civ.define(AiDefine.MC_GIFT_WEIGHT_DIPLO_VICTORY)
        if (trait == MARITIME && civ.getHappiness() >= 0)
            value += civ.define(AiDefine.MC_GIFT_WEIGHT_MARITIME_GROWTH) * ai.personalityAndGrandStrategy(Flavor.Growth) * max(1, civ.cities.size / 3)
        if (trait == MILITARISTIC && !ai.isGoingForWorldConquest()) value += civ.define(AiDefine.MC_GIFT_WEIGHT_MILITARISTIC)
        val lacking = MinorCivHelpers.numResourcesMajorLacks(minor, civ)
        if (lacking > 0) value += lacking * civ.define(AiDefine.MC_GIFT_WEIGHT_RESOURCE_WE_NEED)
        if (approach == MinorCivApproach.Protective) value += civ.define(AiDefine.MC_GIFT_WEIGHT_PROTECTIVE)
        if (minor.cityStatePersonality == CityStatePersonality.Hostile) value += civ.define(AiDefine.MC_GIFT_WEIGHT_HOSTILE)
        when (DiplomacyTurn.proximity(civ, minor)) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> value += civ.define(AiDefine.MC_GIFT_WEIGHT_NEIGHBORS)
            DiplomacyTurn.PROXIMITY_CLOSE -> value += civ.define(AiDefine.MC_GIFT_WEIGHT_CLOSE)
            DiplomacyTurn.PROXIMITY_FAR -> value += civ.define(AiDefine.MC_GIFT_WEIGHT_FAR)
        }

        val functions = minor.cityStateFunctions
        val mediumFriendship = functions.influenceGainedByGift(civ, civ.define(AiDefine.MINOR_GOLD_GIFT_MEDIUM))
        val smallFriendship = functions.influenceGainedByGift(civ, civ.define(AiDefine.MINOR_GOLD_GIFT_SMALL))
        val friendship = MinorCivHelpers.influence(minor, civ)
        for (other in civ.gameInfo.getAliveMajorCivs()) {
            val otherFriendship = MinorCivHelpers.influence(minor, other)
            if (otherFriendship <= 0) continue
            // As in the original: only rivals we are already ahead of are looked at
            if (friendship <= otherFriendship) continue
            val mediumAllies = friendship + mediumFriendship >= MinorCivHelpers.ALLIES_THRESHOLD
            val smallAllies = friendship + smallFriendship >= MinorCivHelpers.ALLIES_THRESHOLD
            if (smallAllies && otherFriendship - friendship < smallFriendship) {
                value += civ.define(AiDefine.MC_SMALL_GIFT_WEIGHT_PASS_OTHER_PLAYER)
                gift.quickBoost = true
                gift.majorRival = other
            } else if (mediumAllies && otherFriendship - friendship < mediumFriendship) {
                value += civ.define(AiDefine.MC_GIFT_WEIGHT_PASS_OTHER_PLAYER)
                gift.majorRival = other
            } else value = 0
        }

        if (isAllies(minor, civ)) {
            if (MinorCivHelpers.isCloseToNotBeingAllies(minor, civ)) {
                value += civ.define(AiDefine.MC_GIFT_WEIGHT_ALMOST_NOT_ALLIES)
                gift.quickBoost = true
            } else value = 0
        } else if (isFriends(minor, civ)) {
            if (MinorCivHelpers.isCloseToNotBeingFriends(minor, civ)) {
                value += civ.define(AiDefine.MC_GIFT_WEIGHT_ALMOST_NOT_FRIENDS)
                gift.quickBoost = true
            } else if (!ai.isGoingForDiploVictory() || civ.gameInfo.league?.unitedNations != true) value = 0
        }
        if (MinorCivHelpers.isRecentlyBulliedByMajor(minor, civ)) value -= 100
        return value
    }

    /** CvMinorCivAI::CanMajorBullyUnit: Unciv's tribute rule (willingness to give a worker not negative) and a capital to give it from */
    fun canMajorBullyUnit(minor: Civilization, major: Civilization): Boolean =
        !minor.isAtWarWith(major) && minor.getCapital() != null && minor.cityStateFunctions.getTributeWillingness(major, demandingWorker = true) >= 0

    /** CvMinorCivAI::CanMajorBullyGold: Unciv's tribute rule (willingness not negative) */
    fun canMajorBullyGold(minor: Civilization, major: Civilization): Boolean =
        !minor.isAtWarWith(major) && minor.cityStateFunctions.getTributeWillingness(major) >= 0

    /** The worker tribute desirability of DoContactMinorCivs; null unless Bully approach and possible. Counts above 100 */
    fun bullyUnitValue(civ: Civilization, minor: Civilization, approach: MinorCivApproach): Int? {
        if (approach != MinorCivApproach.Bully || !canMajorBullyUnit(minor, civ)) return null
        var value = 100
        value += when (DiplomacyTurn.proximity(civ, minor)) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> 50
            DiplomacyTurn.PROXIMITY_CLOSE -> 30
            DiplomacyTurn.PROXIMITY_FAR -> -30
            DiplomacyTurn.PROXIMITY_DISTANT -> -50
            else -> 0
        }
        value += if (minor.cityStateFunctions.getProtectorCivs().isNotEmpty()) -20 else 20
        if (isAllies(minor, civ) || isFriends(minor, civ)) value = 0
        return value
    }

    /** The gold tribute desirability of DoContactMinorCivs; null unless Bully approach and possible. Counts above 100 */
    fun bullyGoldValue(civ: Civilization, minor: Civilization, approach: MinorCivApproach): Int? {
        if (approach != MinorCivApproach.Bully || !canMajorBullyGold(minor, civ)) return null
        var value = 100
        value += when (DiplomacyTurn.proximity(civ, minor)) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> 40
            DiplomacyTurn.PROXIMITY_CLOSE -> 20
            else -> 0
        }
        if (minor.civID in civ.ai.deals.bulliedMinors) value += 20
        if (!MinorCivHelpers.isRecentlyBulliedByMajor(minor, civ) && MinorCivHelpers.isRecentlyBulliedByAnyMajor(minor)) value += -10
        value += if (minor.cityStateFunctions.getProtectorCivs().isNotEmpty()) -10 else 10
        if (isAllies(minor, civ) || isFriends(minor, civ)) value = 0
        return value
    }

    /** The buyout desirability of DoContactMinorCivs (Austria's diplomatic marriage); null if not possible now. Counts above 100 */
    fun buyoutValue(civ: Civilization, minor: Civilization): Int? {
        val minorCapital = minor.getCapital() ?: return null
        if (!civ.hasUnique(UniqueType.CityStateCanBeBoughtForGold) || !minor.cityStateFunctions.canBeMarriedBy(civ)) return null
        val ai = civ.ai
        var value = 100
        val continent = minorCapital.getCenterTile().getContinent()
        val presenceInArea = civ.cities.any { it.getCenterTile().getContinent() == continent }
        // As in the original, every visible rival capital counts, wherever it is
        val majorCapitalsVisible = civ.gameInfo.getAliveMajorCivs().count { it != civ && it.getCapital()?.getCenterTile()?.isVisible(civ) == true }
        var minorMilitaryUnits = 0
        var minorUnits = 0
        for (unit in minor.units.getCivUnits()) {
            if (unit.isMilitary()) minorMilitaryUnits++
            minorUnits++
        }
        val losingAllWars = WarStateAi.stateAllWars(civ) == StateAllWars.Losing
        if (!presenceInArea) {
            value += when {
                ai.isGoingForWorldConquest() && majorCapitalsVisible > 0 -> 100
                ai.isEconomicStrategyActive(EXPAND_TO_OTHER_CONTINENTS) -> 60
                else -> -50
            }
        } else when (DiplomacyTurn.proximity(civ, minor)) {
            DiplomacyTurn.PROXIMITY_NEIGHBORS -> {
                value += 100
                if (losingAllWars) value += if (minorMilitaryUnits > 0) minorMilitaryUnits * 10 else -50
            }
            DiplomacyTurn.PROXIMITY_CLOSE -> value += 10
            DiplomacyTurn.PROXIMITY_FAR -> value += -50
            DiplomacyTurn.PROXIMITY_DISTANT -> value += -100
        }
        if (civ.stats.getUnitSupply() >= civ.units.getCivUnitsSize() + minorUnits) value += minorMilitaryUnits * 5
        val needHappiness = ai.isEconomicStrategyActive(NEED_HAPPINESS)
        val needHappinessCritical = ai.isEconomicStrategyActive(NEED_HAPPINESS_CRITICAL)
        if (needHappiness) value += -50
        if (needHappinessCritical) value += -150
        if (minor.cityStateType.name == CULTURED && ai.isGoingForCultureVictory()) value += -70
        else if (minor.cityStateType.name == MERCANTILE) {
            if (needHappiness) value += -100
            if (needHappinessCritical) value += -150
        }
        return value
    }
    //endregion

    /**
     *  CvMinorCivAI::DoPickPersonality's flavor change for a city-state AI: Friendly lowers City Defense, Defense and Offense by 2,
     *  Hostile raises them by 2, each through CvFlavorManager::GetAdjustedValue within 0..10. As in the original, the "plus-minus" is
     *  the change itself, so a Hostile city-state gets a random ±2 and a Friendly one a random number from a range of 2 × (-2) + 1
     *  read as an unsigned 16-bit count (65533), which puts these flavors at 10 nearly always. Unciv keeps the personality of the
     *  nation instead of a random one.
     */
    fun pickInitialFlavors(civ: Civilization, flavors: IntArray, random: Random) {
        val change = when (civ.cityStatePersonality) {
            CityStatePersonality.Friendly -> -2
            CityStatePersonality.Hostile -> 2
            else -> return
        }
        for (flavor in arrayOf(Flavor.CityDefense, Flavor.Defense, Flavor.Offense)) {
            val range = (change * 2 + 1) and 0xFFFF
            flavors[flavor.ordinal] = (flavors[flavor.ordinal] + random.nextInt(range) - change).coerceIn(0, 10)
        }
    }

    private const val CULTURED = "Cultured"
    private const val MILITARISTIC = "Militaristic"
    private const val MARITIME = "Maritime"
    private const val MERCANTILE = "Mercantile"
    private const val FOUND_CITY = "ECONOMICAISTRATEGY_FOUND_CITY"
    private const val EXPAND_LIKE_CRAZY = "ECONOMICAISTRATEGY_EXPAND_LIKE_CRAZY"
    private const val EXPAND_TO_OTHER_CONTINENTS = "ECONOMICAISTRATEGY_EXPAND_TO_OTHER_CONTINENTS"
    private const val NEED_HAPPINESS = "ECONOMICAISTRATEGY_NEED_HAPPINESS"
    private const val NEED_HAPPINESS_CRITICAL = "ECONOMICAISTRATEGY_NEED_HAPPINESS_CRITICAL"
    private const val LOSING_MONEY = "ECONOMICAISTRATEGY_LOSING_MONEY"
}
