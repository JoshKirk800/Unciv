package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.battle.Battle
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsPillage
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.UniqueType

/**
 *  The target moves of CvTacticalAI, run in the order of the tactical priority list ([TacticalAi]): each looks at the targets of one type and
 *  commands the units that fit them. Ported: PlotCaptureCityMoves, PlotDamageCityMoves, PlotDestroyUnitMoves (the "destroy" moves of
 *  high, medium and low priority units and the "attrition" ones), PlotCivilianAttackMoves, PlotBarbarianCampMoves, PlotPillageMoves,
 *  PlotAncientRuinMoves, PlotSafeBombardMoves, PlotGarrisonMoves, PlotBastionMoves, PlotGuardImprovementMoves, PlotRepositionMoves and
 *  the execution helpers they share (ExecuteMoveToTarget, ExecuteMoveToPlot, ExecuteMoveOfBlockingUnit, MoveToEmptySpaceNearTarget,
 *  FindUnitsForThisMove, FindNearbyTarget).
 *
 *  Deviations: [plotRepositionMoves] measures the path to the 5 nearest targets only; units that may paradrop do not pillage by paradrop (the AI has no paratrooper logic, [MayParadrop] is not used);
 *  a unit's promotions that raise its attack, defense or strength are the unconditional and "when attacking" / "when defending"
 *  [UniqueType.Strength] uniques of its promotions (the original's attack, defense and extra combat modifiers); a unit "is a fast mover" when
 *  its type may have the unit AI FAST_ATTACK.
 */
object TacticalMoves {

    //region Attacking cities and units

    /**
     *  CvTacticalAI::PlotCaptureCityMoves: against each enemy city, the units that can reach it count together; if what they do is at least half
     *  of the city's hit points (a risk: they need not survive), all attack, melee last, one of them taking the city.
     *  @return whether an attack was set up
     */
    fun plotCaptureCityMoves(ctx: TacticalContext): Boolean {
        var attackMade = false
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.City)) {
            if (!target.isAlive(ctx.civ)) continue
            val strikers = TacticalAttacks.findUnits(ctx, target.tile, 1, includeBlocked = true)
            if (strikers.isEmpty()) continue
            val required = target.city!!.health
            target.aux = required
            // Do we have enough firepower to destroy it?
            if (TacticalAttacks.computeTotalExpectedDamage(target, strikers) >= required / 2) { // risky
                ctx.log { "attacking city of ${target.city.name} to capture, ${target.tile.position}" }
                TacticalAttacks.executeAttack(ctx, target, strikers, emptyList(), inflictWhatWeTake = false, mustSurviveAttack = false)
                attackMade = true
                // Did it work? If so, don't need a temporary dominance zone if had one here
                if (target.tile.getOwner() == ctx.civ) ctx.state.deleteTemporaryZone(target.tile)
            }
        }
        return attackMade
    }

    /**
     *  CvTacticalAI::PlotDamageCityMoves: against each enemy city, attack with the units that can reach it if together they do more than an
     *  eighth of its hit points ("don't want to hammer away to try and take down a city for more than 8 turns"), units that survive only.
     */
    fun plotDamageCityMoves(ctx: TacticalContext) {
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.City)) {
            if (!target.isAlive(ctx.civ)) continue
            val strikers = TacticalAttacks.findUnits(ctx, target.tile, 1, includeBlocked = true)
            if (strikers.isEmpty()) continue
            val required = target.city!!.health
            target.aux = required
            if (TacticalAttacks.computeTotalExpectedDamage(target, strikers) > required / 8) {
                ctx.log { "attacking city of ${target.city.name} to damage, ${target.tile.position}" }
                TacticalAttacks.executeAttack(ctx, target, strikers, emptyList(), inflictWhatWeTake = false, mustSurviveAttack = true)
            }
        }
    }

    /**
     *  CvTacticalAI::PlotDestroyUnitMoves for the targets of [type]: the units and cities that can strike the unit together. With [mustBeAbleToKill]
     *  they attack if what they do (cities included) is more than its hit points, units of any priority but high ones must survive. Without,
     *  they attack for attrition: all of them if [attackAtPoorOdds]; else if together they take it to 40% of its strength each only has to
     *  do as much as it takes, below that each has to, and they must survive.
     */
    fun plotDestroyUnitMoves(ctx: TacticalContext, type: TacticalTargetType, mustBeAbleToKill: Boolean, attackAtPoorOdds: Boolean = false) {
        for (target in TacticalTargets.ofType(ctx.currentTargets, type)) {
            val defender = TacticalTargets.visibleEnemyDefender(ctx.civ, target.tile) ?: continue
            val strikers = TacticalAttacks.findUnits(ctx, target.tile, 1)
            val cities = TacticalAttacks.findCities(ctx, target.tile)
            if (strikers.isEmpty() && cities.isEmpty()) continue
            val combatant = Battle.getMapCombatantOfTile(target.tile) ?: continue
            val expected = TacticalAttacks.computeTotalExpectedDamage(target, strikers) + TacticalAttacks.computeTotalExpectedBombardDamage(combatant, cities)
            val required = defender.health
            target.aux = required
            if (!mustBeAbleToKill) {
                // Put in any attacks where we'll inflict at least equal damage
                ctx.log { "looking at damaging $type ${defender.name}, ${target.tile.position}, expecting $expected of $required" }
                if (attackAtPoorOdds) TacticalAttacks.executeAttack(ctx, target, strikers, cities, inflictWhatWeTake = false, mustSurviveAttack = false)
                // If we can at least knock the defender to 40% strength with our combined efforts, go ahead even if each individual attack isn't favorable
                else TacticalAttacks.executeAttack(ctx, target, strikers, cities, inflictWhatWeTake = expected < required * 40 / 100, mustSurviveAttack = true)
            } else if (expected > required) { // Do we have enough firepower to destroy it?
                ctx.log { "looking at killing $type ${defender.name}, ${target.tile.position}, expecting $expected of $required" }
                TacticalAttacks.executeAttack(ctx, target, strikers, cities, inflictWhatWeTake = false, mustSurviveAttack = type != TacticalTargetType.HighPriorityUnit)
            }
        }
    }

    /** CvTacticalAI::PlotSafeBombardMoves: safe bombards on the high, then medium, then low priority units */
    fun plotSafeBombardMoves(ctx: TacticalContext) {
        for (type in listOf(TacticalTargetType.HighPriorityUnit, TacticalTargetType.MediumPriorityUnit, TacticalTargetType.LowPriorityUnit)) {
            // The original stops at the first target that is gone
            for (target in TacticalTargets.ofType(ctx.currentTargets, type)) {
                if (!target.isAlive(ctx.civ)) break
                TacticalBombards.safeBombardTarget(ctx, target)
            }
        }
    }

    //endregion
    //region Civilians, camps, improvements, ruins

    /**
     *  CvTacticalAI::PlotCivilianAttackMoves for the targets of [type]: the strongest unit that can move onto the undefended unit captures it
     *  (ExecuteCivilianCapture: it moves there and is done for the turn).
     */
    fun plotCivilianAttackMoves(ctx: TacticalContext, type: TacticalTargetType) {
        for (target in TacticalTargets.ofType(ctx.currentTargets, type)) {
            val victim = target.unit ?: continue
            if (victim.isDestroyed || victim.civ == ctx.civ || victim.getTile() != target.tile) continue
            val striker = TacticalAttacks.findUnits(ctx, target.tile, 1, targetUndefended = true).firstOrNull() ?: continue
            ctx.log { "attacking $type ${victim.name}, ${target.tile.position}" }
            moveAndFinish(ctx, striker.unit, target.tile)
        }
    }

    /** CvTacticalAI::PlotBarbarianCampMoves: a unit that can move onto an undefended barbarian camp does, and clears it */
    fun plotBarbarianCampMoves(ctx: TacticalContext) {
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.BarbarianCamp)) {
            if (!target.tile.isBarbarianEncampment() || target.tile.militaryUnit != null) continue
            val striker = TacticalAttacks.findUnits(ctx, target.tile, 1, targetUndefended = true).firstOrNull() ?: continue
            ctx.log { "removing barbarian camp, ${target.tile.position}" }
            moveAndFinish(ctx, striker.unit, target.tile)
            ctx.state.deleteTemporaryZone(target.tile)
        }
    }

    /**
     *  CvTacticalAI::PlotPillageMoves for the targets of [type]: in the first pass the best unit (damaged ones, which heal by it, count triple)
     *  that can get to the tile with movement left goes there and pillages (ExecutePillage); in the second pass (for the next turn) the
     *  best unit within 2 turns moves towards it.
     */
    fun plotPillageMoves(ctx: TacticalContext, type: TacticalTargetType, firstPass: Boolean) {
        val heal = ctx.rules.define(AiDefine.PILLAGE_HEAL_AMOUNT)
        for (target in TacticalTargets.ofType(ctx.currentTargets, type)) {
            val tile = target.tile
            if (!tile.canPillageTile()) continue
            if (firstPass) {
                val striker = TacticalAttacks.findUnits(ctx, tile, 0, heal, mustMoveThrough = true, willPillage = true).firstOrNull() ?: continue
                ctx.log { "pillaging $type, ${tile.position}" }
                val unit = striker.unit
                unit.movement.moveToTile(tile)
                if (unit.getTile() == tile) UnitActionsPillage.getPillageAction(unit, tile)?.action?.invoke()
                unit.currentMovement = 0f
                ctx.unitProcessed(unit)
            } else {
                // No one can reach it this turn, what about next turn?
                val strikers = TacticalAttacks.findUnits(ctx, tile, 2, heal, willPillage = true)
                if (strikers.isEmpty()) continue
                ctx.log { "moving towards $type for pillage, ${tile.position}" }
                moveToTarget(ctx, tile, emptyList(), strikers.map { MoveCandidate(it.unit, TacticalAttacks.turnsToReach(it.unit, tile)) })
            }
        }
    }

    /** CvTacticalAI::PlotAncientRuinMoves: a unit that can attack (a fast one first) and is [turnsAway] turns from a ruin goes to pop it */
    fun plotAncientRuinMoves(ctx: TacticalContext, turnsAway: Int) {
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.AncientRuins)) {
            val (high, regular) = findUnitsForThisMove(ctx, MoveKind.Ruins, target.tile, turnsAway)
            if (high.isEmpty() && regular.isEmpty()) continue
            ctx.log { "moving to goody hut, ${target.tile.position}, turns away $turnsAway" }
            moveToTarget(ctx, target.tile, high, regular)
        }
    }

    //endregion
    //region Defense

    /**
     *  CvTacticalAI::PlotGarrisonMoves: for each of our cities (the most threatened first) that has no garrison assigned this turn, a unit
     *  [turnsAway] turns away goes in or, if it is there, fortifies; ranged units are preferred. [mustAllowRangedAttack]: only ranged units.
     */
    fun plotGarrisonMoves(ctx: TacticalContext, turnsAway: Int, mustAllowRangedAttack: Boolean = false) {
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.CityToDefend)) {
            val city = target.city!!
            if (ctx.state.lastTurnGarrisonAssigned(city) >= ctx.turn) continue
            // Grab units that make sense for this move type
            val (high, regular) = findUnitsForThisMove(ctx, MoveKind.Garrison, target.tile, turnsAway, mustAllowRangedAttack)
            if (high.isEmpty() && regular.isEmpty()) continue
            ctx.log { "garrison ${city.name}, priority ${target.aux}, turns away $turnsAway${if (mustAllowRangedAttack) ", allows bombard" else ""}" }
            moveToTarget(ctx, target.tile, high, regular)
            ctx.state.setLastTurnGarrisonAssigned(city, ctx.turn)
        }
    }

    /** CvTacticalAI::PlotBastionMoves: a unit that does not shoot goes to a defensible tile next to a threatened city, to hold it */
    fun plotBastionMoves(ctx: TacticalContext, turnsAway: Int) {
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.DefensiveBastion)) {
            val (high, regular) = findUnitsForThisMove(ctx, MoveKind.Defender, target.tile, turnsAway)
            if (high.isEmpty() && regular.isEmpty()) continue
            ctx.log { "bastion ${target.tile.position}, priority ${target.aux}, turns away $turnsAway" }
            moveToTarget(ctx, target.tile, high, regular)
        }
    }

    /** CvTacticalAI::PlotGuardImprovementMoves: a unit that does not shoot goes to stand on one of our improvements (it looks for units as for a bastion) */
    fun plotGuardImprovementMoves(ctx: TacticalContext, turnsAway: Int) {
        for (target in TacticalTargets.ofType(ctx.currentTargets, TacticalTargetType.ImprovementToDefend)) {
            val (high, regular) = findUnitsForThisMove(ctx, MoveKind.Defender, target.tile, turnsAway)
            if (high.isEmpty() && regular.isEmpty()) continue
            ctx.log { "guard improvement ${target.tile.position}, turns away $turnsAway" }
            moveToTarget(ctx, target.tile, high, regular)
        }
    }

    /**
     *  CvTacticalAI::PlotRepositionMoves / ExecuteRepositionMoves: every land unit still free, but great generals, goes to an empty tile next
     *  to the nearest target (unit, city or improvement of the enemy) within AI_TACTICAL_REPOSITION_RANGE tiles on its landmass.
     */
    fun plotRepositionMoves(ctx: TacticalContext) {
        val range = ctx.rules.define(AiDefine.AI_TACTICAL_REPOSITION_RANGE)
        val wanted = setOf(
            TacticalTargetType.HighPriorityUnit, TacticalTargetType.MediumPriorityUnit, TacticalTargetType.LowPriorityUnit,
            TacticalTargetType.City, TacticalTargetType.Improvement
        )
        val targets = ctx.currentTargets.filter { it.type in wanted }
        for (unit in ctx.liveUnits()) {
            // Never use this (default) move for Great Admirals or Generals
            if (unit.baseUnit.isGreatPerson || !unit.baseUnit.isLandUnit || !unit.hasMovement()) continue
            val destination = findNearbyTarget(ctx, unit, range, targets) ?: continue
            if (moveToEmptySpaceNearTarget(ctx, unit, destination)) {
                unit.currentMovement = 0f
                ctx.unitProcessed(unit)
                ctx.log { "${unit.name} moved to an empty space near target ${destination.position}, now at ${unit.getTile().position}" }
            }
        }
    }

    /** CvTacticalAI::FindNearbyTarget: the target within [range] of the unit on its landmass that it reaches soonest (the tile it stands on first of all) */
    private fun findNearbyTarget(ctx: TacticalContext, unit: MapUnit, range: Int, targets: List<TacticalTarget>): Tile? {
        var best: Tile? = null
        var bestValue = Int.MAX_VALUE
        val unitTile = unit.getTile()
        val area = ctx.map.areaOf(unitTile)
        val near = ArrayList<Pair<TacticalTarget, Int>>()
        for (target in targets) {
            val distance = unitTile.aerialDistanceTo(target.tile)
            if (distance == 0) return target.tile
            if (distance < range && area == ctx.map.areaOf(target.tile)) near.add(target to distance)
        }
        // Only the nearest few are measured by path: it would be a path search for every target in range
        for ((target, _) in near.sortedBy { it.second }.take(MAX_REPOSITION_CANDIDATES)) {
            val turns = TacticalAttacks.turnsToReach(unit, target.tile)
            if (turns < bestValue) {
                best = target.tile
                bestValue = turns
            }
        }
        return best
    }

    private const val MAX_REPOSITION_CANDIDATES = 5

    //endregion
    //region Finding and moving units for a move

    /** What a move wants of its units (the move types FindUnitsForThisMove tells apart) */
    enum class MoveKind { Garrison, Defender, Ruins }

    /** A unit that can do a move, [turns] turns from it (CvTacticalUnit with its moves to target) */
    class MoveCandidate(val unit: MapUnit, val turns: Int)

    /**
     *  CvTacticalAI::FindUnitsForThisMove: the combat units of the tactical AI of the target's domain that suit the move of [kind] and can be
     *  at [tile] within [turnsAway] turns (0: already there, or there this turn with movement left), as (high priority, regular) lists in
     *  the order of the unit list. Garrisons: ranged units are high priority, others only if no promotion raises their attack, defense or
     *  strength (their bonus is lost in a city), [rangedOnly] allows only ranged ones. Defenders (bastions, improvements): no ranged units
     *  and none without a defensive terrain bonus, those with a defensive promotion are high priority. Ruins: units that attack, fast
     *  ones first.
     */
    fun findUnitsForThisMove(ctx: TacticalContext, kind: MoveKind, tile: Tile, turnsAway: Int, rangedOnly: Boolean = false): Pair<List<MoveCandidate>, List<MoveCandidate>> {
        val high = ArrayList<MoveCandidate>()
        val regular = ArrayList<MoveCandidate>()
        for (unit in ctx.liveUnits()) {
            val base = unit.baseUnit
            if (base.isAirUnit() || !unit.isMilitary()) continue
            // Make sure domain matches
            if (base.isWaterUnit && !tile.isWater || base.isLandUnit && tile.isWater) continue
            var suitable = false
            var highPriority = false
            when (kind) {
                MoveKind.Garrison ->
                    // Want to put ranged units in cities to give them a ranged attack
                    if (base.isRanged()) { suitable = true; highPriority = true }
                    else if (rangedOnly) continue
                    else if (!hasStrengthPromotion(unit, attacking = true, defending = true)) suitable = true
                MoveKind.Defender ->
                    // No ranged units or units without defensive bonuses as plot defenders
                    if (!base.isRanged() && !unit.hasUnique(UniqueType.NoDefensiveTerrainBonus, checkCivInfoUniques = true)) {
                        suitable = true
                        // Units with defensive promotions are especially valuable
                        if (hasStrengthPromotion(unit, attacking = false, defending = true)) highPriority = true
                    }
                MoveKind.Ruins ->
                    if (UnitAiTypes.canHave(base, UnitAiType.FAST_ATTACK, ctx.rules)) { suitable = true; highPriority = true }
                    else if (TacticalAttacks.canAttack(unit)) suitable = true
            }
            if (!suitable || unit.getMaxMovement() <= 0) continue
            // Is it even possible for the unit to reach in the number of requested turns
            val leastTurns = (unit.getTile().aerialDistanceTo(tile) + unit.getMaxMovement() - 1) / unit.getMaxMovement()
            if (leastTurns > turnsAway) continue
            val turns = TacticalAttacks.turnsToReach(unit, tile)
            if (turns == Int.MAX_VALUE || !(turns <= turnsAway || (turnsAway == 0 && unit.getTile() == tile))) continue
            if (turns < 2 && unit.getTile() != tile && !unit.movement.canMoveTo(tile)) continue
            (if (highPriority) high else regular).add(MoveCandidate(unit, turns))
        }
        return high to regular
    }

    /**
     *  Whether a promotion of the unit adds strength: an unconditional [UniqueType.Strength] unique, or one that applies when attacking
     *  ([attacking]) or when defending ([defending]) (CvUnit::getAttackModifier / getDefenseModifier / getExtraCombatPercent)
     */
    private fun hasStrengthPromotion(unit: MapUnit, attacking: Boolean, defending: Boolean): Boolean =
        unit.promotions.getPromotions().any { promotion ->
            promotion.uniqueObjects.any { unique ->
                unique.type == UniqueType.Strength && unique.params[0].toInt() > 0 && unique.modifiers.all {
                    attacking && it.type == UniqueType.ConditionalAttacking || defending && it.type == UniqueType.ConditionalDefending
                }
            }
        }

    /**
     *  CvTacticalAI::ExecuteMoveToTarget: one unit goes to [tile] and is done for the turn (the one there fortifies): the first of the high
     *  priority units, unless the first regular one is nearer, else the first regular one.
     */
    fun moveToTarget(ctx: TacticalContext, tile: Tile, high: List<MoveCandidate>, regular: List<MoveCandidate>) {
        fun go(candidate: MoveCandidate): Boolean {
            val unit = candidate.unit
            if (unit.getTile() == tile && unit.canFortify()) unit.fortify()
            else if (candidate.turns < Int.MAX_VALUE) {
                unit.movement.headTowards(tile)
                unit.currentMovement = 0f
            } else return false
            ctx.unitProcessed(unit)
            return true
        }
        // Start with high priority list
        for (candidate in high) {
            // Don't move high priority unit if regular priority unit is closer
            if (regular.isNotEmpty() && regular.first().turns < candidate.turns) break
            if (go(candidate)) return
        }
        // Then regular priority
        for (candidate in regular) if (go(candidate)) return
    }

    /** CvTacticalAI::ExecuteMoveToPlot: [unit] goes to [tile] (or fortifies, or stays, if it is there) and is done for the turn */
    fun moveToPlot(ctx: TacticalContext, unit: MapUnit, tile: Tile) {
        if (unit.getTile() == tile) {
            if (unit.canFortify()) unit.fortify() else unit.currentMovement = 0f
        } else {
            unit.movement.moveToTile(tile)
            unit.currentMovement = 0f
        }
        ctx.unitProcessed(unit, unit.isMilitary())
    }

    /** ExecuteBarbarianCampMove, ExecuteCivilianCapture: the unit moves onto the tile (capturing what is on it) and is done for the turn */
    private fun moveAndFinish(ctx: TacticalContext, unit: MapUnit, tile: Tile) {
        unit.movement.moveToTile(tile)
        unit.currentMovement = 0f
        ctx.unitProcessed(unit)
    }

    /**
     *  CvTacticalAI::ExecuteMoveOfBlockingUnit: a unit of ours that stands where another unit wants to be moves to a neighbouring tile it can
     *  reach (not onto water from land, not onto an enemy unit or city) and is done for the turn. @return whether it moved
     */
    fun executeMoveOfBlockingUnit(ctx: TacticalContext, blocker: MapUnit): Boolean {
        if (blocker.civ != ctx.civ || !blocker.hasMovement()) return false
        val from = blocker.getTile()
        for (tile in from.neighbors) {
            // Don't embark for one of these moves
            if (!from.isWater && tile.isWater && blocker.baseUnit.isLandUnit) continue
            // Has to be somewhere we can move and be empty of other units/enemy cities
            if (TacticalTargets.visibleEnemyDefender(ctx.civ, tile) == null && !ctx.map.isEnemyCity(tile) && blocker.movement.canReach(tile)
                && blocker.movement.canMoveTo(tile)) {
                moveToPlot(ctx, blocker, tile)
                ctx.log { "moving blocking ${blocker.name} out of way, leaving ${from.position}, now at ${blocker.getTile().position}" }
                return true
            }
        }
        return false
    }

    /**
     *  CvTacticalAI::MoveToEmptySpaceNearTarget: the unit heads for the first tile next to [target] of its domain that holds no unit, and no city
     *  but a friendly one, that it can reach. @return whether it went
     */
    fun moveToEmptySpaceNearTarget(ctx: TacticalContext, unit: MapUnit, target: Tile): Boolean {
        val destination = emptyTileNextTo(unit, target) ?: return false
        unit.movement.headTowards(destination)
        return true
    }

    /** The first tile next to [target], on the unit's domain, with no unit and no city but our own, that it can reach */
    fun emptyTileNextTo(unit: MapUnit, target: Tile): Tile? {
        val land = !unit.baseUnit.isWaterUnit
        for (neighbor in target.neighbors) {
            if (neighbor.isWater == land) continue
            if (neighbor.militaryUnit != null) continue
            if (neighbor.isCityCenter() && neighbor.getOwner() != unit.civ) continue
            if (unit.movement.canReach(neighbor)) return neighbor
        }
        return null
    }

    //endregion
}
