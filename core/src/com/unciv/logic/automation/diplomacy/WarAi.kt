package com.unciv.logic.automation.diplomacy

import com.unciv.logic.automation.strategy.MilitaryOperations
import com.unciv.logic.automation.strategy.OperationAbortReason
import com.unciv.logic.automation.strategy.WarStateAi
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DeclareWarReason
import com.unciv.logic.civilization.diplomacy.WarType
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach

/**
 *  War-making of CvDiplomacyAI: MakeWar (targets by war projection and proximity²), DoMakeWarOnPlayer (one war with majors at a time,
 *  WarGoal PREPARE, the sneak attack / city-state attack operation of [MilitaryOperations], declaring war when that operation is 85% of
 *  the way to its target or has mustered at it), DeclareWar, DoMakePeaceWithMinors.
 */
object WarAi {

    /** The share of the way to its target an attack operation must have covered before war is declared (CvDiplomacyAI::DoMakeWarOnPlayer) */
    const val PERCENT_TO_DECLARE_WAR = 85

    /** CvDiplomacyAI::MakeWar */
    fun makeWar(civ: Civilization, rules: AiRuleset) {
        val ai = civ.ai.diplomacy
        val targets = civ.gameInfo.civilizations.filter { (it.isMajorCiv || it.isCityState) && WarStateAi.isPlayerValid(civ, it) }
            .map { target ->
                var weight = (ai.warProjection(target)?.ordinal ?: -1) + 1
                // Square the distance enum to make it crucial
                val proximity = 1 + DiplomacyTurn.proximity(civ, target)
                weight *= proximity
                weight *= proximity
                if (target.isMajorCiv) {
                    if (ai.opinionOf(target) == MajorCivOpinion.Unforgivable) weight *= 2
                    weight *= 10  // majors before city-states
                }
                target to weight
            }.sortedByDescending { it.second }
        for ((target, _) in targets) doMakeWarOnPlayer(civ, target, rules)
        CoopWarAi.doCoopWarCounters(civ, rules)
    }

    /** MilitaryAIHelpers::IsTestStrategy_AtWar: at war with at least one major */
    fun isAtWarWithAMajor(civ: Civilization): Boolean = civ.getCivsAtWarWith().any { it.isMajorCiv }

    /**
     *  CvDiplomacyAI::DoMakeWarOnPlayer. Without an attack operation against [target] (and not mustering from one that finished) the civilization
     *  that wants war sets the war goal PREPARE and requests the attack ([MilitaryOperations.requestSneakAttack], for city-states
     *  [MilitaryOperations.requestCityStateAttack]) - every turn until the operation exists. With one, war is declared when it is at least
     *  [PERCENT_TO_DECLARE_WAR]% of the way or has mustered; if we changed our mind the operation is killed.
     */
    fun doMakeWarOnPlayer(civ: Civilization, target: Civilization, rules: AiRuleset) {
        if (!WarStateAi.isPlayerValid(civ, target)) return
        val ai = civ.ai.diplomacy
        val memory = ai.memoryOf(target)
        if (memory.warGoal == WarGoal.Demand) return
        if (civ.isAtWarWith(target)) return
        val atWarWithAMajor = isAtWarWithAMajor(civ)
        val operation: com.unciv.logic.automation.strategy.AiOperation?
        val wantToAttack = if (target.isCityState) {
            operation = MilitaryOperations.cityStateAttackOperation(civ, target)
            !atWarWithAMajor && civ.ai.minorCivApproaches[target.civID] == MinorCivApproach.Conquest
        } else {
            operation = MilitaryOperations.sneakAttackOperation(civ, target)
            val approach = ai.approachTowards(target)
            // Let's not get into another war right now
            (approach == MajorCivApproach.War || (approach == MajorCivApproach.Deceptive && civ.ai.isGoingForWorldConquest())) && !atWarWithAMajor
        }
        val canDeclare = civ.getDiplomacyManager(target)?.canDeclareWar() == true
        if (operation == null && !memory.musteringForAttack) {
            // Not yet readying an attack
            if (canDeclare && wantToAttack) {
                memory.warGoal = WarGoal.Prepare
                if (target.isCityState) MilitaryOperations.requestCityStateAttack(civ, target)
                else MilitaryOperations.requestSneakAttack(civ, target)
            }
            return
        }
        // We already have an attack on the way
        if (!wantToAttack) {
            // We were planning an attack, but changed our minds
            if (operation != null) {
                civ.ai.operations.kill(operation, OperationAbortReason.DIPLO_OPINION_CHANGE)
                memory.warGoal = null
                memory.musteringForAttack = false
            }
            return
        }
        if (!canDeclare) return
        // If we're at least 85% of the way to our objective, let loose the dogs of war!
        if (memory.musteringForAttack || (operation != null && AiOperationLogic.percentFromMusterPointToTarget(civ, operation) >= PERCENT_TO_DECLARE_WAR)) {
            memory.musteringForAttack = false
            declareWar(civ, target)
        }
    }

    /** CvDiplomacyAI::DeclareWar */
    fun declareWar(civ: Civilization, target: Civilization) {
        val diplomacy = civ.getDiplomacyManager(target) ?: return
        if (civ.isAtWarWith(target)) return
        diplomacy.declareWar(DeclareWarReason(WarType.DirectWar))
    }

    /** CvDiplomacyAI::DoMakePeaceWithMinors: a war goal of PEACE long enough, not locked into the war */
    fun makePeaceWithMinors(civ: Civilization) {
        val ai = civ.ai.diplomacy
        for (minor in civ.getCivsAtWarWith().filter { it.isCityState && WarStateAi.isPlayerValid(civ, it) }) {
            if (!ai.isWantsPeaceWith(minor) || ai.memoryOf(minor).turnsLockedIntoWar != 0) continue
            // CvMinorCivAI::IsPeaceBlocked: a city-state allied to someone at war with us stays at war
            val ally = minor.allyCiv
            if (ally != null && ally.isAtWarWith(civ)) continue
            civ.getDiplomacyManager(minor)!!.makePeace()
            DiplomacyEvents.onPeace(civ, minor)
        }
    }
}
