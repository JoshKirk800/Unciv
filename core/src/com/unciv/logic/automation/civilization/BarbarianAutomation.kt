package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.unit.tactical.BarbarianMoves
import com.unciv.logic.automation.unit.tactical.TacticalContext
import com.unciv.logic.automation.unit.tactical.TacticalMoves
import com.unciv.logic.automation.unit.tactical.TacticalSafety
import com.unciv.logic.automation.unit.tactical.TacticalTargetType
import com.unciv.logic.automation.unit.tactical.TacticalTargets
import com.unciv.logic.automation.unit.tactical.TradeMoves
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine

/**
 *  The barbarians' turn, CvTacticalAI as it runs for the barbarian player (ProcessDominanceZones' barbarian branch): all their units are
 *  tactical, there is no analysis map and no dominance zone; the targets are found ([TacticalTargets.find], with
 *  IdentifyPriorityBarbarianTargets) and one list of moves is run in the order of AI_TACTICAL_BARBARIAN_PRIORITY_* plus a die roll of up to
 *  AI_TACTICAL_MOVE_PRIORITY_RANDOMNESS per move (EstablishBarbarianPriorities, once a turn; a negative base priority never runs):
 *  capture city 9, damage city 5, destroy high/medium/low priority units 16/15/14, civilian attacks 13, camp defense 13, pillaging 12/14/4,
 *  escorting captured civilians 30, plundering trade units 20, attrition 10/7/6, move to safety 10, aggressive move 3, desperate attack 0
 *  (BNW data). The attacks are [TacticalMoves] (the same routines the other civilizations use), the trade unit plunder is [TradeMoves], the
 *  hunting, sea raids, camp defense and captive escort are [BarbarianMoves]. Replaces the per-unit chain (pillage to heal, upgrade, attack
 *  nearby, pillage, wander).
 *
 *  Not here: the blockade move (commented out in the
 *  original), the barbarian release turn (Unciv's spawner has its own start).
 */
class BarbarianAutomation(val civInfo: Civilization) {

    /** AIBarbarianTacticalMove: the moves of the barbarian list with their base priority and what they do */
    enum class Move(val define: AiDefine, internal val run: (TacticalContext) -> Unit) {
        CAPTURE_CITY(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_CAPTURE_CITY, { TacticalMoves.plotCaptureCityMoves(it) }),
        DAMAGE_CITY(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_DAMAGE_CITY, { TacticalMoves.plotDamageCityMoves(it) }),
        DESTROY_HIGH_PRIORITY_UNIT(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_DESTROY_HIGH_UNIT,
            { TacticalMoves.plotDestroyUnitMoves(it, TacticalTargetType.HighPriorityUnit, true) }),
        DESTROY_MEDIUM_PRIORITY_UNIT(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_DESTROY_MEDIUM_UNIT,
            { TacticalMoves.plotDestroyUnitMoves(it, TacticalTargetType.MediumPriorityUnit, true) }),
        DESTROY_LOW_PRIORITY_UNIT(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_DESTROY_LOW_UNIT,
            { TacticalMoves.plotDestroyUnitMoves(it, TacticalTargetType.LowPriorityUnit, true) }),
        MOVE_TO_SAFETY(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_TO_SAFETY, { moveToSafety(it) }),
        ATTRIT_HIGH_PRIORITY_UNIT(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_ATTRIT_HIGH_UNIT,
            { TacticalMoves.plotDestroyUnitMoves(it, TacticalTargetType.HighPriorityUnit, false) }),
        ATTRIT_MEDIUM_PRIORITY_UNIT(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_ATTRIT_MEDIUM_UNIT,
            { TacticalMoves.plotDestroyUnitMoves(it, TacticalTargetType.MediumPriorityUnit, false) }),
        ATTRIT_LOW_PRIORITY_UNIT(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_ATTRIT_LOW_UNIT,
            { TacticalMoves.plotDestroyUnitMoves(it, TacticalTargetType.LowPriorityUnit, false) }),
        PILLAGE(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_PILLAGE, { TacticalMoves.plotPillageMoves(it, TacticalTargetType.ImprovementResource, true) }),
        PILLAGE_CITADEL(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_PILLAGE_CITADEL, { TacticalMoves.plotPillageMoves(it, TacticalTargetType.Citadel, true) }),
        PILLAGE_NEXT_TURN(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_PILLAGE_NEXT_TURN, {
            TacticalMoves.plotPillageMoves(it, TacticalTargetType.Citadel, false)
            TacticalMoves.plotPillageMoves(it, TacticalTargetType.ImprovementResource, false)
        }),
        /** The original's PlotBlockadeImprovementMoves call is commented out: the move does nothing */
        BLOCKADE_RESOURCE(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_BLOCKADE_RESOURCE, { }),
        CIVILIAN_ATTACK(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_CIVILIAN_ATTACK, {
            for (type in listOf(TacticalTargetType.VeryHighPriorityCivilian, TacticalTargetType.HighPriorityCivilian,
                TacticalTargetType.MediumPriorityCivilian, TacticalTargetType.LowPriorityCivilian))
                TacticalMoves.plotCivilianAttackMoves(it, type)
        }),
        AGGRESSIVE_MOVE(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_AGGRESSIVE_MOVE, { BarbarianMoves.executeMoves(it, true) }),
        PASSIVE_MOVE(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_PASSIVE_MOVE, { BarbarianMoves.executeMoves(it, false) }),
        CAMP_DEFENSE(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_CAMP_DEFENSE, { BarbarianMoves.plotCampDefenseMoves(it) }),
        DESPERATE_ATTACK(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_DESPERATE_ATTACK,
            { TacticalMoves.plotDestroyUnitMoves(it, TacticalTargetType.LowPriorityUnit, false, attackAtPoorOdds = true) }),
        ESCORT_CIVILIAN(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_ESCORT_CIVILIAN, { BarbarianMoves.executeCivilianEscortMoves(it) }),
        PLUNDER_TRADE_UNIT(AiDefine.AI_TACTICAL_BARBARIAN_PRIORITY_PLUNDER_TRADE_UNIT, { TradeMoves.plotPlunderTradeUnitMoves(it) });
    }

    fun automate() = timeThis("AI.Barbarians") {
        val cache = AutomationCache()
        val danger = civInfo.ai.plotDanger
        // CommandeerUnits: all the barbarians that can move
        val units = civInfo.units.getCivUnits().filter { it.hasMovement() }.toList()
        val state = civInfo.ai.tactical
        val alive = civInfo.units.getCivUnits().mapTo(HashSet()) { it.id }
        state.unitDestinations.keys.retainAll(alive)
        if (units.isNotEmpty()) {
            val ctx = TacticalContext(civInfo, null, danger, cache, units, TacticalTargets.find(civInfo, null, danger))
            for (move in establishPriorities(civInfo)) move.run(ctx)
            AiDecisionLog.log(civInfo) { "Barbarians: ${units.size} units, ${units.size - ctx.units.size} commanded, ${ctx.targets.all.size} targets" }
        }
        // fix buildup of alerts - to shrink saves and ease debugging
        civInfo.popupAlerts.clear()
    }

    companion object {
        /**
         *  CvTacticalAI::EstablishBarbarianPriorities: the moves with a base priority of 0 or more, each with a die roll of
         *  AI_TACTICAL_MOVE_PRIORITY_RANDOMNESS added, the highest first (stable in the order of [Move]).
         */
        fun establishPriorities(civ: Civilization): List<Move> {
            val rules = civ.gameInfo.ruleset.ai
            val random = civ.ai.random("BarbarianPriorities")
            val randomness = rules.define(AiDefine.AI_TACTICAL_MOVE_PRIORITY_RANDOMNESS)
            val prioritized = ArrayList<Pair<Move, Int>>()
            for (move in Move.entries) {
                var priority = rules.define(move.define)
                // Make sure base priority is not negative
                if (priority < 0) continue
                if (randomness > 0) priority += random.nextInt(randomness)
                prioritized.add(move to priority)
            }
            return prioritized.sortedByDescending { it.second }.map { it.first }
        }

        /** PlotMovesToSafety(true): combat units that want to flee (a barbarian only a damaged ship) go to the safest tile in reach */
        private fun moveToSafety(ctx: TacticalContext) {
            for (unit in ctx.liveUnits()) {
                if (!unit.isMilitary() || !unit.hasMovement()) continue
                if (TacticalSafety.moveToSafety(unit, ctx.danger)) ctx.unitProcessed(unit)
            }
        }
    }
}
