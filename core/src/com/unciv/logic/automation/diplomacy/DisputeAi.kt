package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor

/**
 *  The disputes and postures of CvDiplomacyAI: land (DoUpdateLandDisputeLevels with the expansion and plot buying postures),
 *  wonders (DoUpdateWonderDisputeLevels), city-states (DoUpdateMinorCivDisputeLevels) and military posture (DoUpdateMilitaryAggressivePostures).
 *  The victory dispute (DoUpdateVictoryDisputeLevels) is not ported: in the original nothing but an end-of-friendship text reads it.
 */
object DisputeAi {

    /** CvDiplomacyAI::DoUpdateLandDisputeLevels: (expansion posture + plot buying posture + proximity) × our expansion flavor */
    fun updateLandDisputes(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        // GetPersonalityAndGrandStrategy(FLAVOR_EXPANSION)
        val expansionFlavor = civ.ai.personalityAndGrandStrategy(Flavor.Expansion)
        for (other in others) {
            val memory = civ.ai.diplomacy.memoryOf(other)
            memory.lastTurnLandDispute = memory.landDispute
            var weight = when (memory.expansionPosture) {
                AggressivePosture.None -> rules.define(AiDefine.LAND_DISPUTE_EXP_AGGRESSIVE_POSTURE_NONE)
                AggressivePosture.Low -> rules.define(AiDefine.LAND_DISPUTE_EXP_AGGRESSIVE_POSTURE_LOW)
                AggressivePosture.Medium -> rules.define(AiDefine.LAND_DISPUTE_EXP_AGGRESSIVE_POSTURE_MEDIUM)
                AggressivePosture.High -> rules.define(AiDefine.LAND_DISPUTE_EXP_AGGRESSIVE_POSTURE_HIGH)
                AggressivePosture.Incredible -> rules.define(AiDefine.LAND_DISPUTE_EXP_AGGRESSIVE_POSTURE_INCREDIBLE)
            }
            weight += when (memory.plotBuyingPosture) {
                AggressivePosture.None -> rules.define(AiDefine.LAND_DISPUTE_PLOT_BUY_AGGRESSIVE_POSTURE_NONE)
                AggressivePosture.Low -> rules.define(AiDefine.LAND_DISPUTE_PLOT_BUY_AGGRESSIVE_POSTURE_LOW)
                AggressivePosture.Medium -> rules.define(AiDefine.LAND_DISPUTE_PLOT_BUY_AGGRESSIVE_POSTURE_MEDIUM)
                AggressivePosture.High -> rules.define(AiDefine.LAND_DISPUTE_PLOT_BUY_AGGRESSIVE_POSTURE_HIGH)
                AggressivePosture.Incredible -> rules.define(AiDefine.LAND_DISPUTE_PLOT_BUY_AGGRESSIVE_POSTURE_INCREDIBLE)
            }
            weight += when (DiplomacyTurn.proximity(civ, other)) {
                DiplomacyTurn.PROXIMITY_DISTANT -> rules.define(AiDefine.LAND_DISPUTE_DISTANT)
                DiplomacyTurn.PROXIMITY_FAR -> rules.define(AiDefine.LAND_DISPUTE_FAR)
                DiplomacyTurn.PROXIMITY_CLOSE -> rules.define(AiDefine.LAND_DISPUTE_CLOSE)
                DiplomacyTurn.PROXIMITY_NEIGHBORS -> rules.define(AiDefine.LAND_DISPUTE_NEIGHBORS)
                else -> 0
            }
            if (civ.ai.isCramped) weight += rules.define(AiDefine.LAND_DISPUTE_CRAMPED_MULTIPLIER)
            weight *= expansionFlavor
            memory.landDispute = when {
                weight >= rules.define(AiDefine.LAND_DISPUTE_FIERCE_THRESHOLD) -> DisputeLevel.Fierce
                weight >= rules.define(AiDefine.LAND_DISPUTE_STRONG_THRESHOLD) -> DisputeLevel.Strong
                weight >= rules.define(AiDefine.LAND_DISPUTE_WEAK_THRESHOLD) -> DisputeLevel.Weak
                else -> DisputeLevel.None
            }
        }
    }

    /** CvDiplomacyAI::GetTotalLandDisputeLevel */
    fun totalLandDisputeLevel(civ: Civilization, others: List<Civilization>, rules: AiRuleset): Int {
        var total = 0
        for (other in others) total += when (civ.ai.diplomacy.memoryOf(other).landDispute) {
            DisputeLevel.Fierce -> rules.define(AiDefine.AI_DIPLO_LAND_DISPUTE_WEIGHT_FIERCE)
            DisputeLevel.Strong -> rules.define(AiDefine.AI_DIPLO_LAND_DISPUTE_WEIGHT_STRONG)
            DisputeLevel.Weak -> rules.define(AiDefine.AI_DIPLO_LAND_DISPUTE_WEIGHT_WEAK)
            DisputeLevel.None -> 0
        }
        return total
    }

    /** CvDiplomacyAI::DoUpdateWonderDisputeLevels: wonders they beat us to × WonderCompetitiveness */
    fun updateWonderDisputes(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        val competitiveness = civ.ai.diplomacyValue(DiplomacyPersonality.WonderCompetitiveness)
        for (other in others) {
            val memory = civ.ai.diplomacy.memoryOf(other)
            val weight = memory.numWondersBeatenTo * competitiveness
            memory.wonderDispute = when {
                weight >= rules.define(AiDefine.WONDER_DISPUTE_FIERCE_THRESHOLD) -> DisputeLevel.Fierce
                weight >= rules.define(AiDefine.WONDER_DISPUTE_STRONG_THRESHOLD) -> DisputeLevel.Strong
                weight >= rules.define(AiDefine.WONDER_DISPUTE_WEAK_THRESHOLD) -> DisputeLevel.Weak
                else -> DisputeLevel.None
            }
        }
    }

    /**
     *  CvDiplomacyAI::DoUpdateMinorCivDisputeLevels: for each city-state we protect that they are allied with, MinorCivCompetitiveness² × 10.
     *  The original's "friends" branch tests alliance a second time and so never counts; neither does this.
     */
    fun updateMinorCivDisputes(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        val competitiveness = civ.ai.diplomacyValue(DiplomacyPersonality.MinorCivCompetitiveness)
        val personalityMod = competitiveness * competitiveness
        val protectedByUs = civ.gameInfo.getAliveCityStates().filter { civ in it.cityStateFunctions.getProtectorCivs() }
        for (other in others) {
            if (!other.isMajorCiv) continue
            var weight = 0
            for (minor in protectedByUs) if (minor.allyCiv == other) weight += personalityMod * rules.define(AiDefine.MINOR_CIV_DISPUTE_ALLIES_WEIGHT)
            civ.ai.diplomacy.memoryOf(other).minorCivDispute = when {
                weight >= rules.define(AiDefine.MINOR_CIV_DISPUTE_FIERCE_THRESHOLD) -> DisputeLevel.Fierce
                weight >= rules.define(AiDefine.MINOR_CIV_DISPUTE_STRONG_THRESHOLD) -> DisputeLevel.Strong
                weight >= rules.define(AiDefine.MINOR_CIV_DISPUTE_WEAK_THRESHOLD) -> DisputeLevel.Weak
                else -> DisputeLevel.None
            }
        }
    }

    /** CvDiplomacyAI::DoUpdateOnePlayerMilitaryAggressivePosture: their visible combat units on our home front (10 each, 5 in their own land) */
    fun updateMilitaryPostures(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        val range = rules.define(AiDefine.AI_DIPLO_PLOT_RANGE_FROM_CITY_HOME_FRONT)
        for (other in others) {
            val memory = civ.ai.diplomacy.memoryOf(other)
            memory.lastTurnMilitaryPosture = memory.militaryPosture
            val theirEnemies = other.getCivsAtWarWith().filter { it != civ && it.isAlive() }
            var value = 0
            for (unit in other.units.getCivUnits()) {
                if (!unit.isMilitary()) continue
                val tile = unit.getTile()
                if (!tile.isVisible(civ) || !WarStateAi.isHomeFrontFor(tile, civ, range)) continue
                // Near someone they are at war with: not aimed at us
                if (theirEnemies.any { WarStateAi.isHomeFrontFor(tile, it, range) }) continue
                value += if (tile.getOwner() == other) 5 else 10
            }
            memory.militaryPosture = when {
                value >= rules.define(AiDefine.MILITARY_AGGRESSIVE_POSTURE_THRESHOLD_INCREDIBLE) -> AggressivePosture.Incredible
                value >= rules.define(AiDefine.MILITARY_AGGRESSIVE_POSTURE_THRESHOLD_HIGH) -> AggressivePosture.High
                value >= rules.define(AiDefine.MILITARY_AGGRESSIVE_POSTURE_THRESHOLD_MEDIUM) -> AggressivePosture.Medium
                value >= rules.define(AiDefine.MILITARY_AGGRESSIVE_POSTURE_THRESHOLD_LOW) -> AggressivePosture.Low
                else -> AggressivePosture.None
            }
        }
    }

    /**
     *  CvDiplomacyAI::DoUpdateOnePlayerExpansionAggressivePosture: their most aggressive self-founded city (not the capital) by its
     *  distance to our capital; several cities tied at Medium or High bump the level by one.
     */
    fun updateExpansionPostures(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        val ourCapital = civ.getCapital(firstCityIfNoCapital = false)?.getCenterTile() ?: return
        for (other in others) {
            // Irrelevant if we can't declare war on them
            if (!civ.isAtWarWith(other) && civ.getDiplomacyManager(other)?.canDeclareWar() != true) continue
            val theirCapital = other.getCapital(firstCityIfNoCapital = false)?.getCenterTile() ?: continue
            val capitalsDistance = theirCapital.aerialDistanceTo(ourCapital)
            var most = AggressivePosture.None
            var numMost = 0
            for (city in other.cities) {
                if (city.isCapital() || city.foundingCivObject != other) continue
                val tile = city.getCenterTile()
                val toUs = ourCapital.aerialDistanceTo(tile)
                val toTheirCapital = theirCapital.aerialDistanceTo(tile)
                var posture = when {
                    toUs <= rules.define(AiDefine.EXPANSION_CAPITAL_DISTANCE_AGGRESSIVE_POSTURE_HIGH) -> AggressivePosture.High
                    toUs <= rules.define(AiDefine.EXPANSION_CAPITAL_DISTANCE_AGGRESSIVE_POSTURE_MEDIUM) -> AggressivePosture.Medium
                    toUs <= rules.define(AiDefine.EXPANSION_CAPITAL_DISTANCE_AGGRESSIVE_POSTURE_LOW) -> AggressivePosture.Low
                    else -> AggressivePosture.None
                }
                // Closer to our capital than to theirs: at least medium
                if (posture == AggressivePosture.Low && toUs < toTheirCapital) posture = AggressivePosture.Medium
                // Farther from their capital than our capitals are apart: incredible
                if (posture >= AggressivePosture.Medium && capitalsDistance < toTheirCapital) posture = AggressivePosture.Incredible
                if (posture == most) numMost++
                else if (posture > most) {
                    most = posture
                    numMost = 0
                }
                if (most == AggressivePosture.Incredible) break
            }
            if (numMost > 1 && most > AggressivePosture.Low && most < AggressivePosture.Incredible) most = AggressivePosture.entries[most.ordinal + 1]
            civ.ai.diplomacy.memoryOf(other).expansionPosture = most
        }
    }

    /** CvDiplomacyAI::DoUpdatePlotBuyingAggressivePosture: tiles they bought within 3 tiles of our cities */
    fun updatePlotBuyingPostures(civ: Civilization, others: List<Civilization>, rules: AiRuleset) {
        for (other in others) {
            val memory = civ.ai.diplomacy.memoryOf(other)
            val score = memory.numPlotsBoughtNearUs
            memory.plotBuyingPosture = when {
                score >= rules.define(AiDefine.PLOT_BUYING_POSTURE_INCREDIBLE_THRESHOLD) -> AggressivePosture.Incredible
                score >= rules.define(AiDefine.PLOT_BUYING_POSTURE_HIGH_THRESHOLD) -> AggressivePosture.High
                score >= rules.define(AiDefine.PLOT_BUYING_POSTURE_MEDIUM_THRESHOLD) -> AggressivePosture.Medium
                score >= rules.define(AiDefine.PLOT_BUYING_POSTURE_LOW_THRESHOLD) -> AggressivePosture.Low
                else -> AggressivePosture.None
            }
        }
    }
}
