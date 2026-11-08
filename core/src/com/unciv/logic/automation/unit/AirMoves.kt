package com.unciv.logic.automation.unit

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.unit.tactical.TacticalContext
import com.unciv.logic.automation.unit.tactical.TacticalDominance
import com.unciv.logic.battle.AirInterception
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.UnitActionType
import com.unciv.models.ruleset.unique.UniqueType

/**
 *  The air moves of the original: CvMilitaryAI::WillAirUnitRebase, GetNumEnemyAirUnitsInRange and GetBestAirSweepTarget; the tactical moves
 *  PlotAirSweepMoves / ExecuteAirSweepMoves and PlotAirInterceptMoves; CvHomelandAI::PlotAircraftMoves / ExecuteAircraftMoves (rebasing
 *  to the base with the highest plot danger, with the carriers' free slots). Aircraft strikes are not here: combat-ready aircraft are strikers of
 *  [com.unciv.logic.automation.unit.tactical.TacticalAttacks] like any shooter, and what the tactical AI leaves of an aircraft is only [plotAircraftMove].
 *
 *  Deviations, with the reason: Unciv has no air patrol action - interception is automatic for a fighter that still has its movement -
 *  so the intercept move only keeps the fighter where it is, unmoved, for the turn; a carrier's free slot is [MapUnit.canTransport] (the
 *  CarryAirUnits uniques of the ruleset, which carry what the original's SpecialUnit_CarrierUnitAI says), rebasing needs the target within
 *  [MapUnit.getAirUnitMovementRange] and [com.unciv.logic.map.mapunit.movement.UnitMovement.canMoveTo]; CvUnit::canAirSweepAt is the sweep range;
 *  the original counts every enemy air unit within 10 tiles of a base, seen or not, as here.
 */
object AirMoves {

    /** CvMilitaryAI::GetNumEnemyAirUnitsInRange counts enemy air units within this many tiles */
    private const val ENEMY_AIR_RANGE = 10

    /** The share of hit points a base may lose before the aircraft in it want to leave (MAX_HIT_POINTS / 5) */
    private const val BASE_DAMAGE_DIVISOR = 5

    /** Half the hit points: an aircraft with this many or fewer is not combat ready (CvUnit::getDamage() * 2 < MAX_HIT_POINTS) */
    private const val HALF_HEALTH = 50

    /** An aircraft with more than half its hit points: the tactical AI commandeers it, uses it for strikes and sweeps, and the homeland AI rebases it */
    fun isCombatReady(unit: MapUnit) = unit.health > HALF_HEALTH

    /** CvUnit::IsAirSweepCapable */
    fun isAirSweepCapable(unit: MapUnit) = unit.hasUnique(UniqueType.CanAirsweep)

    /** CvUnit::canAirDefend: the unit intercepts */
    fun canAirDefend(unit: MapUnit) = unit.interceptChance() > 0

    /** CvUnit::canAirSweep: a sweeper that may still attack */
    fun canAirSweep(unit: MapUnit) = unit.baseUnit.isAirUnit() && isAirSweepCapable(unit) && unit.canAttack()

    /** CvUnit::canAirSweepAt: [tile] is in the fighter's range, and not the one it is on */
    fun canAirSweepAt(unit: MapUnit, tile: Tile) = tile != unit.getTile() && unit.getTile().aerialDistanceTo(tile) <= unit.getRange()

    /** The enemy's air units that [civ] counts: at war with it, alive */
    private fun enemyAirUnits(civ: Civilization): Sequence<MapUnit> =
        civ.gameInfo.civilizations.asSequence().filter { it != civ && it.isAlive() && civ.isAtWarWith(it) }
            .flatMap { it.units.getCivUnits() }.filter { it.baseUnit.isAirUnit() }

    /**
     *  CvMilitaryAI::GetNumEnemyAirUnitsInRange: the air units of players at war with [civ] within 10 tiles of [center]: fighters (units that
     *  sweep or intercept) if [countFighters], the rest if [countBombers].
     */
    fun numEnemyAirUnitsInRange(civ: Civilization, center: Tile, countFighters: Boolean, countBombers: Boolean): Int {
        var result = 0
        for (unit in enemyAirUnits(civ)) {
            if (center.aerialDistanceTo(unit.getTile()) > ENEMY_AIR_RANGE) continue
            if (isAirSweepCapable(unit) || canAirDefend(unit)) { if (countFighters) result++ } else if (countBombers) result++
        }
        return result
    }

    /**
     *  CvMilitaryAI::GetBestAirSweepTarget: the visible city of a player at war with us within the fighter's range that it can sweep and that
     *  holds the most fighters (units that sweep or intercept); null if none holds any.
     */
    fun bestAirSweepTarget(civ: Civilization, fighter: MapUnit): Tile? {
        var best: Tile? = null
        var bestCount = 0
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || !civ.isAtWarWith(other)) continue
            for (city in other.cities) {
                val tile = city.getCenterTile()
                if (!tile.isVisible(civ) || fighter.getTile().aerialDistanceTo(tile) > fighter.getRange() || !canAirSweepAt(fighter, tile)) continue
                val fighters = tile.airUnits.count { isAirSweepCapable(it) || canAirDefend(it) }
                if (fighters > bestCount) { bestCount = fighters; best = tile }
            }
        }
        return best
    }

    /** Whether a base (city or carrier) has lost more than a fifth of its hit points */
    private fun cityInDanger(city: com.unciv.logic.city.City) = city.getMaxHealth() - city.health > city.getMaxHealth() / BASE_DAMAGE_DIVISOR
    private fun carrierDamaged(carrier: MapUnit) = 100 - carrier.health > 100 / BASE_DAMAGE_DIVISOR

    /** The carrier [unit] is on, if it is on one */
    private fun carrierOf(unit: MapUnit): MapUnit? = if (unit.isTransported) unit.getTile().getUnits().firstOrNull { it != unit && it.isTransportTypeOf(unit) } else null

    /** CvUnit::canRebaseAt: [tile] is within the unit's rebase range and it may land there (a city, or a carrier with a free slot) */
    private fun canRebaseAt(unit: MapUnit, tile: Tile) =
        unit.getTile().aerialDistanceTo(tile) <= unit.getAirUnitMovementRange() && unit.movement.canMoveTo(tile)

    /**
     *  CvMilitaryAI::WillAirUnitRebase: an aircraft wants to leave when its base is in danger (a city that lost over a fifth of its hit points,
     *  or a damaged carrier) or, for a fighter, when there are no enemy air units nearby and no city to sweep; and there is somewhere
     *  to go (a healthy carrier with a free slot, in an operation or not, or a healthy city, in range).
     */
    fun willAirUnitRebase(unit: MapUnit): Boolean {
        val civ = unit.civ
        val tile = unit.getTile()
        var needsToMove = false
        val city = tile.getCity()
        if (tile.isCityCenter() && city != null) { if (cityInDanger(city)) needsToMove = true }
        else carrierOf(unit)?.let { if (carrierDamaged(it)) needsToMove = true }
        // Is this a fighter that doesn't have any useful missions nearby
        if (canAirDefend(unit) || canAirSweep(unit)) {
            if (numEnemyAirUnitsInRange(civ, tile, countFighters = true, countBombers = true) == 0 && bestAirSweepTarget(civ, unit) == null) needsToMove = true
        }
        if (!needsToMove) return false
        return civ.units.getCivUnits().any { carrier -> usableCarrier(unit, carrier) } ||
            civ.cities.any { !cityInDanger(it) && (it.getCenterTile() == tile || canRebaseAt(unit, it.getCenterTile())) }
    }

    /** A healthy carrier with a free slot that [unit] can rebase to */
    private fun usableCarrier(unit: MapUnit, carrier: MapUnit): Boolean {
        if (carrier == unit || carrierDamaged(carrier) || !carrier.baseUnit.isWaterUnit) return false
        return carrier.canTransport(unit) && canRebaseAt(unit, carrier.getTile())
    }

    /**
     *  CvHomelandAI::ExecuteAircraftMoves for one aircraft: it rebases to the base it can reach with the highest plot danger (>= 0, the last of
     *  equals): a healthy carrier with a free slot (+5000 for a carrier in an operation) or a healthy city that is not being razed; staying put if
     *  its own base is the most dangerous. The unit's turn ends. @return the tile it went to (its own if it stays)
     */
    fun rebaseTarget(unit: MapUnit): Tile {
        val civ = unit.civ
        val danger = civ.ai.plotDanger
        val here = unit.getTile()
        var best: Tile? = null
        var mostDangerous = 0
        // first look for open carrier slots
        for (carrier in civ.units.getCivUnits()) {
            if (!usableCarrier(unit, carrier)) continue
            var value = danger.get(carrier.getTile())
            if (civ.ai.operations.armyOf(carrier) != null) value += CARRIER_IN_OPERATION_BONUS
            if (value >= mostDangerous) { mostDangerous = value; best = carrier.getTile() }
        }
        for (city in civ.cities) {
            val target = city.getCenterTile()
            if (cityInDanger(city) || city.isBeingRazed) continue
            if (target != here && !canRebaseAt(unit, target)) continue
            val value = danger.get(target)
            if (value >= mostDangerous) { mostDangerous = value; best = target }
        }
        return best ?: here
    }

    private const val CARRIER_IN_OPERATION_BONUS = 5000

    /**
     *  CvHomelandAI::PlotAircraftMoves: an aircraft with more than half its health that will rebase does so ([rebaseTarget]) and is done for the
     *  turn. @return whether the aircraft was commanded
     */
    fun plotAircraftMove(unit: MapUnit): Boolean {
        if (!isCombatReady(unit) || !unit.hasMovement() || !willAirUnitRebase(unit)) return false
        val target = rebaseTarget(unit)
        if (target != unit.getTile()) unit.movement.moveToTile(target)
        AiDecisionLog.log(unit.civ) { "Air: ${unit.name} rebases to ${target.position}" }
        unit.currentMovement = 0f
        return true
    }

    //region Tactical moves

    /** The dominance of the zone of the city the unit is in: null outside a city or in one with no zone (PlotAirSweepMoves / PlotAirInterceptMoves "pZone") */
    private fun cityZoneDominance(ctx: TacticalContext, unit: MapUnit): TacticalDominance? {
        val city = unit.getTile().getCity()?.takeIf { unit.getTile().isCityCenter() } ?: return null
        return ctx.map.zoneByCity(city, false)?.dominance
    }

    /**
     *  CvTacticalAI::PlotAirSweepMoves / ExecuteAirSweepMoves: a fighter with more than half its health that can sweep, will not rebase and has a
     *  [bestAirSweepTarget], on a carrier or in a city where we dominate (or with no zone), sweeps that city.
     */
    fun plotAirSweepMoves(ctx: TacticalContext) {
        for (unit in ctx.liveUnits()) {
            if (!unit.baseUnit.isAirUnit() || !isCombatReady(unit)) continue
            if (!canAirSweep(unit) || willAirUnitRebase(unit)) continue
            val target = bestAirSweepTarget(ctx.civ, unit) ?: continue
            val inCity = unit.getTile().isCityCenter()
            val dominance = cityZoneDominance(ctx, unit)
            if (inCity && dominance != null && dominance != TacticalDominance.Friendly) continue
            ctx.log { "air sweep by ${unit.name} at ${unit.getTile().position} on ${target.position}" }
            unit.action = UnitActionType.AirSweep.value
            AirInterception.airSweep(MapUnitCombatant(unit), target)
            if (!unit.isDestroyed && unit.isPreparingAirSweep()) unit.action = null
            ctx.unitProcessed(unit, markTacticalMap = false)
        }
    }

    /**
     *  CvTacticalAI::PlotAirInterceptMoves: a fighter that will not rebase, on a carrier or in a city where we do not dominate, with enemy
     *  bombers in range, stays unmoved so that it intercepts (the original sets AIRPATROL).
     */
    fun plotAirInterceptMoves(ctx: TacticalContext) {
        for (unit in ctx.liveUnits()) {
            if (!unit.baseUnit.isAirUnit() || !canAirDefend(unit) || willAirUnitRebase(unit)) continue
            val dominance = cityZoneDominance(ctx, unit)
            if (unit.getTile().isCityCenter() && dominance != null && dominance == TacticalDominance.Friendly) continue
            if (numEnemyAirUnitsInRange(ctx.civ, unit.getTile(), countFighters = false, countBombers = true) == 0) continue
            ctx.log { "${unit.name} at ${unit.getTile().position} ready to intercept enemy air units" }
            ctx.unitProcessed(unit, markTacticalMap = false)
        }
    }
    //endregion
}
