package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.city.City

/**
 *  What the units around a dominance zone do in the posture [PostureSelection] chose for it: CvTacticalAI::ProcessDominanceZones (the loop over
 *  the zones that the zone moves of the priority list run), PlotSitAndBombardMoves, PlotAttritFromRangeMoves, PlotExploitFlanksMoves,
 *  PlotSteamrollMoves, PlotSurgicalCityStrikeMoves, PlotHedgehogMoves, PlotCounterattackMoves, PlotShoreBombardmentMoves and PlotCloseOnTarget.
 *  The attacks are those of [TacticalMoves], [TacticalBombards] and [TacticalAttacks] (protected and safe bombards, flank attacks, destroy
 *  and capture moves); the placing of units is [Deployment].
 *
 *  Deviations: [exploitFlanksMoves] leaves out targets that are gone by the time it comes to them (the original sets up flank cells and
 *  looks for units around one that no longer exists).
 */
object Postures {

    const val OFFENSIVE_POSTURE_MOVES = "TACTICAL_OFFENSIVE_POSTURE_MOVES"
    const val SHORE_BOMBARDMENT = "TACTICAL_POSTURE_SHORE_BOMBARDMENT"
    const val HEDGEHOG = "TACTICAL_POSTURE_HEDGEHOG"
    const val CLOSE_ON_TARGET = "TACTICAL_CLOSE_ON_TARGET"
    const val DAMAGE_CITY = "TACTICAL_DAMAGE_CITY"

    private val offensivePostures = setOf(
        TacticalPosture.SitAndBombard, TacticalPosture.AttritFromRange, TacticalPosture.ExploitFlanks,
        TacticalPosture.Steamroll, TacticalPosture.SurgicalCityStrike, TacticalPosture.Counterattack
    )

    /**
     *  The zone loop of CvTacticalAI::ProcessDominanceZones for one zone move: [move] is run for each zone in the order of their value whose
     *  posture [matches], that is worth using ([PostureSelection.useThisZone]), and that has targets (or is a temporary zone or land around an enemy
     *  city, which are always processed, there may be an operation aiming at them), with the zone's targets as the current ones.
     */
    fun forEachZone(ctx: TacticalContext, matches: (DominanceZone, TacticalPosture) -> Boolean, move: (DominanceZone, TacticalPosture) -> Unit) {
        val state = ctx.state
        for (zone in ctx.map.zones) {
            val posture = state.findPosture(zone.owner?.civName, zone.isWater, zone.closestCity?.id)
            if (!matches(zone, posture)) continue
            if (!PostureSelection.useThisZone(ctx.civ, state, zone)) continue
            val targets = ctx.targets.forZone(zone)
            // Must have some moves to continue or it must be land around an enemy city (which we always want to process because we might
            // have an operation targeting it)
            if (targets.isEmpty() && zone.territory != TacticalTerritory.TempZone && (zone.territory != TacticalTerritory.Enemy || zone.isWater)) continue
            ctx.currentTargets = targets
            try {
                move(zone, posture)
            } finally {
                ctx.currentTargets = ctx.targets.all
            }
        }
    }

    /** TACTICAL_OFFENSIVE_POSTURE_MOVES: every zone whose posture is an offensive one runs that posture's move, the zones in the order of their value */
    fun offensivePostureMoves(ctx: TacticalContext) =
        forEachZone(ctx, { _, posture -> posture in offensivePostures }) { zone, posture ->
            when (posture) {
                TacticalPosture.SitAndBombard -> sitAndBombardMoves(ctx, zone)
                TacticalPosture.AttritFromRange -> attritFromRangeMoves(ctx)
                TacticalPosture.ExploitFlanks -> exploitFlanksMoves(ctx)
                TacticalPosture.Steamroll -> steamrollMoves(ctx, zone)
                TacticalPosture.SurgicalCityStrike -> surgicalCityStrikeMoves(ctx, zone)
                TacticalPosture.Counterattack -> counterattackMoves(ctx)
                else -> Unit
            }
        }

    /** TACTICAL_POSTURE_SHORE_BOMBARDMENT */
    fun shoreBombardmentZones(ctx: TacticalContext) =
        forEachZone(ctx, { _, posture -> posture == TacticalPosture.ShoreBombardment }) { _, _ -> shoreBombardmentMoves(ctx) }

    /** TACTICAL_POSTURE_HEDGEHOG */
    fun hedgehogZones(ctx: TacticalContext) =
        forEachZone(ctx, { _, posture -> posture == TacticalPosture.Hedgehog }) { zone, _ -> hedgehogMoves(ctx, zone) }

    /** TACTICAL_CLOSE_ON_TARGET: okay for all zones */
    fun closeOnTargetZones(ctx: TacticalContext) = forEachZone(ctx, { _, _ -> true }) { zone, _ -> plotCloseOnTarget(ctx, zone, true) }

    private fun cityTarget(zone: DominanceZone, city: City) =
        TacticalTarget(TacticalTargetType.City, city.getCenterTile(), zone.id, city.civ, city = city)

    private fun unitTargets(ctx: TacticalContext, includeLow: Boolean = true) = ctx.currentTargets.filter {
        it.type == TacticalTargetType.HighPriorityUnit || it.type == TacticalTargetType.MediumPriorityUnit ||
            includeLow && it.type == TacticalTargetType.LowPriorityUnit
    }

    /** The bombards (safe, then protected) at the target; [TacticalBombards.executeSafeBombards] wants the bombard cells of the target set */
    private fun bombard(ctx: TacticalContext, target: TacticalTarget): Boolean {
        ctx.map.clearDynamicFlags()
        ctx.map.setTargetBombardCells(target.tile, ctx.map.bestFriendlyRange, ctx.map.ignoreLineOfSight)
        val underway = TacticalBombards.executeSafeBombards(ctx, target)
        val protectedBombard = target.isAlive(ctx.civ) && TacticalBombards.executeProtectedBombards(ctx, target)
        return underway || protectedBombard
    }

    /**
     *  CvTacticalAI::PlotSitAndBombardMoves, win an attrition campaign with bombardments: the zone's city is shot at from safe tiles, taken if the
     *  units that reach it can, shot at from protected tiles, taken if they can then; the units near it are bombarded; then the army closes in.
     *  No city damage moves this turn (they would mix badly with the posture).
     */
    fun sitAndBombardMoves(ctx: TacticalContext, zone: DominanceZone) {
        val city = zone.closestCity ?: return
        // Turn off moves we don't want to mix with this posture
        ctx.disabledMoves.add(DAMAGE_CITY)
        val target = cityTarget(zone, city)

        // Attack primary target (city)
        ctx.map.clearDynamicFlags()
        ctx.map.setTargetBombardCells(target.tile, ctx.map.bestFriendlyRange, ctx.map.ignoreLineOfSight)
        TacticalBombards.executeSafeBombards(ctx, target)
        if (target.isAlive(ctx.civ)) {
            TacticalMoves.plotCaptureCityMoves(ctx)
            if (target.isAlive(ctx.civ)) {
                TacticalBombards.executeProtectedBombards(ctx, target)
                if (target.isAlive(ctx.civ)) TacticalMoves.plotCaptureCityMoves(ctx)
            }
        }

        // Attack ancillary target (nearby units)
        for (unitTarget in unitTargets(ctx)) if (unitTarget.isAlive(ctx.civ)) bombard(ctx, unitTarget)

        plotCloseOnTarget(ctx, zone, true)
    }

    /** CvTacticalAI::PlotAttritFromRangeMoves, win an attrition campaign with bombardments: bombard the unit targets */
    fun attritFromRangeMoves(ctx: TacticalContext) {
        for (target in unitTargets(ctx)) if (target.isAlive(ctx.civ)) bombard(ctx, target)
    }

    /** CvTacticalAI::PlotExploitFlanksMoves, defeat enemy units by using our advantage in numbers: flank attacks on the unit targets */
    fun exploitFlanksMoves(ctx: TacticalContext) {
        for (target in unitTargets(ctx)) {
            if (!target.isAlive(ctx.civ)) continue
            ctx.map.clearDynamicFlags()
            ctx.map.setTargetFlankBonusCells(target.tile)
            TacticalBombards.executeFlankAttack(ctx, target)
        }
    }

    /**
     *  CvTacticalAI::PlotSteamrollMoves, we have more overall strength than the enemy, defeat his army first: bombards and flanks on his units, the
     *  attacks that kill, the city if it is time, the attacks that wear down, the city damaged if it was not attacked; then close in.
     */
    fun steamrollMoves(ctx: TacticalContext, zone: DominanceZone) {
        // Loop through unit targets finding advantageous attacks for this turn
        for (target in unitTargets(ctx)) if (target.isAlive(ctx.civ)) TacticalBombards.executePriorityAttacksOnUnitTarget(ctx, target)

        // See if there are any other anti-unit attacks we can make
        destroyUnits(ctx, mustBeAbleToKill = true)

        // See if it is time to go after the city
        val attackMade = TacticalMoves.plotCaptureCityMoves(ctx)

        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.HighPriorityUnit, false)
        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.MediumPriorityUnit, false)
        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.LowPriorityUnit, false, attackAtPoorOdds = true)

        if (!attackMade) TacticalMoves.plotDamageCityMoves(ctx)

        plotCloseOnTarget(ctx, zone, false)
    }

    /**
     *  CvTacticalAI::PlotSurgicalCityStrikeMoves, we should be strong enough to take out the city before the enemy can whittle us down with
     *  ranged attacks: capture it if possible, deal with the units next to it, damage it, take any other really good attacks, close in.
     */
    fun surgicalCityStrikeMoves(ctx: TacticalContext, zone: DominanceZone) {
        val city = zone.closestCity ?: return
        val target = cityTarget(zone, city)
        TacticalMoves.plotCaptureCityMoves(ctx)

        // Any unit targets adjacent to city?
        for (unitTarget in unitTargets(ctx))
            if (unitTarget.tile.aerialDistanceTo(target.tile) == 1 && unitTarget.isAlive(ctx.civ))
                TacticalBombards.executePriorityAttacksOnUnitTarget(ctx, unitTarget)

        if (target.isAlive(ctx.civ)) TacticalMoves.plotDamageCityMoves(ctx)

        // Take any other really good attacks we've set up
        destroyUnits(ctx, mustBeAbleToKill = true)

        plotCloseOnTarget(ctx, zone, false)
    }

    /** CvTacticalAI::PlotHedgehogMoves, build a defensive shell around this city: the best attacks on the units that threaten it, then the shell */
    fun hedgehogMoves(ctx: TacticalContext, zone: DominanceZone) {
        // Attack priority unit targets
        for (target in unitTargets(ctx, includeLow = false)) if (target.isAlive(ctx.civ)) TacticalBombards.executePriorityAttacksOnUnitTarget(ctx, target)
        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.HighPriorityUnit, true)
        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.MediumPriorityUnit, true)

        // But after best attacks are exhausted, go right to playing defense
        val city = zone.closestCity ?: return
        Deployment.executeHedgehogDefense(ctx, city.getCenterTile(), zone)
    }

    /** CvTacticalAI::PlotCounterattackMoves, try to push back the invader: the high and medium priority units, then the low ones, then the attacks that cannot kill */
    fun counterattackMoves(ctx: TacticalContext) {
        // Attack priority unit targets
        for (target in unitTargets(ctx, includeLow = false)) if (target.isAlive(ctx.civ)) TacticalBombards.executePriorityAttacksOnUnitTarget(ctx, target)
        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.HighPriorityUnit, true)
        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.MediumPriorityUnit, true)

        // Now low priority targets
        for (target in ctx.currentTargets.filter { it.type == TacticalTargetType.LowPriorityUnit })
            if (target.isAlive(ctx.civ)) TacticalBombards.executePriorityAttacksOnUnitTarget(ctx, target)
        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.LowPriorityUnit, true)

        // Now targets we can't destroy
        destroyUnits(ctx, mustBeAbleToKill = false)
    }

    /** CvTacticalAI::PlotShoreBombardmentMoves, bombard any enemy targets we can get to on shore: ships shoot the unit targets, then the cities */
    fun shoreBombardmentMoves(ctx: TacticalContext) {
        for (target in unitTargets(ctx)) {
            if (!target.isAlive(ctx.civ)) continue
            ctx.map.clearDynamicFlags()
            ctx.map.setTargetBombardCells(target.tile, ctx.map.bestFriendlyRange, ctx.map.ignoreLineOfSight)
            TacticalBombards.executeSafeBombards(ctx, target)
        }
        for (target in ctx.currentTargets.filter { it.type == TacticalTargetType.City }) {
            if (!target.isAlive(ctx.civ)) continue
            ctx.map.clearDynamicFlags()
            ctx.map.setTargetBombardCells(target.tile, ctx.map.bestFriendlyRange, ctx.map.ignoreLineOfSight)
            TacticalBombards.executeSafeBombards(ctx, target)
        }
    }

    /** The destroy (or wear down) moves on the high, medium and low priority unit targets */
    private fun destroyUnits(ctx: TacticalContext, mustBeAbleToKill: Boolean) {
        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.HighPriorityUnit, mustBeAbleToKill)
        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.MediumPriorityUnit, mustBeAbleToKill)
        TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.LowPriorityUnit, mustBeAbleToKill)
    }

    /**
     *  CvTacticalAI::PlotCloseOnTarget, close units in on the primary target of the dominance zone: the camp a temporary zone is about, or the
     *  city of an enemy zone if we cannot see it or are not outmatched by more than half. Not in a zone the enemy dominates if [checkDominance].
     */
    fun plotCloseOnTarget(ctx: TacticalContext, zone: DominanceZone, checkDominance: Boolean) {
        if (checkDominance && zone.dominance == TacticalDominance.Enemy) return
        // Flank attacks done; if in an enemy zone, close in on target
        if (zone.territory == TacticalTerritory.TempZone) {
            Deployment.executeCloseOnTarget(ctx, zone.tempZoneCenter ?: return, zone)
        } else if (zone.territory == TacticalTerritory.Enemy) {
            val city = zone.closestCity ?: return
            val canSeeCity = city.getCenterTile() in ctx.civ.viewableTiles
            // If we can't see the city, be careful advancing on it. We want to be sure we're not heavily outnumbered
            if (!canSeeCity || zone.friendlyStrength > zone.enemyStrength / 2) Deployment.executeCloseOnTarget(ctx, city.getCenterTile(), zone)
        }
    }
}
