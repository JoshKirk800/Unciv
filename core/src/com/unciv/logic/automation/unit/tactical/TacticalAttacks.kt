package com.unciv.logic.automation.unit.tactical

import com.unciv.Constants
import com.unciv.logic.automation.unit.AirMoves
import com.unciv.logic.battle.AttackableTile
import com.unciv.logic.battle.Battle
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.battle.ICombatant
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.battle.TargetHelper
import com.unciv.logic.city.City
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsPillage
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.UniqueType

/**
 *  One unit that can strike a target (CvTacticalUnit): [attackStrength] and [healthPercent] rank it ([attackPriority], highest first),
 *  the expected damages are filled in by [TacticalAttacks.computeTotalExpectedDamage].
 */
class Striker(
    val unit: MapUnit,
    val attackStrength: Int,
    val healthPercent: Int,
    /** The tile it would attack from: the one it stands on if it shoots or is adjacent, else the cheapest it can move to */
    val attackFrom: Tile? = null,
) {
    var expectedTargetDamage = 0
    var expectedSelfDamage = 0
    val attackPriority get() = attackStrength * healthPercent
    override fun toString() = "${unit.name} ($expectedTargetDamage/$expectedSelfDamage)"
}

/** One city that can shoot a target (CvTacticalCity) */
class CityStriker(val city: City) {
    var expectedTargetDamage = 0
}

/** One attack of a series (CvQueuedAttack): a city's or a unit's, with the tile a melee unit was assigned to attack from */
class QueuedAttack(val unit: MapUnit?, val city: City?, val ranged: Boolean, val attackFrom: Tile?)

/**
 *  A series of attacks on one target (CvTacticalAI::QueueAttack / LaunchAttack / CombatResolved): cities and ranged units first, melee
 *  units after. [TacticalAttacks.launch] makes the attacks one after the other; when the target is gone the rest of the series is
 *  released, and those units are still the tactical AI's.
 */
class AttackSeries(val target: TacticalTarget) {
    val attacks = ArrayList<QueuedAttack>()

    fun addCity(city: City) { attacks.add(QueuedAttack(null, city, true, null)) }
    fun addUnit(unit: MapUnit, ranged: Boolean, attackFrom: Tile?) { attacks.add(QueuedAttack(unit, null, ranged, attackFrom)) }
    fun contains(unit: MapUnit) = attacks.any { it.unit == unit }
    fun isEmpty() = attacks.isEmpty()
}

/**
 *  How the tactical AI attacks one target together (CvTacticalAI): which of our units and cities can strike it, what they would do to it
 *  and take from it (expected damage, [BattleDamage] at the mean of the randomness), and the attack with its overkill budget.
 *
 *  Sources: CvTacticalAI::FindUnitsWithinStrikingDistance, FindCitiesWithinStrikingDistance, ComputeTotalExpectedDamage,
 *  ComputeTotalExpectedBombardDamage, ExecuteAttack, QueueAttack / LaunchAttack / CombatResolved.
 *
 *  Deviations:
 *  - "can reach the target in N turns" is Unciv's reach: a unit that can attack (melee: move beside the target with movement left; ranged:
 *    shoot from where it stands) or, for targets without an enemy on them, move onto the tile this turn (0 turns: with movement left)
 *    or along its path (more turns). The original's ignore-units path finder has no counterpart: a melee unit that can reach the tile next
 *    to the target only if someone moves out of the way counts as "blocked" (half strength) when the tiles are held by units of ours;
 *  - the fire support of the defender's ranged units is not part of the expected damage (Unciv has no such rule);
 *  - the series is made one attack at a time as the fights resolve (they do at once in Unciv, so there is no queue to wait on): a
 *    unit's melee move up to its attack tile happens when its turn in the series comes, not when the series is planned, so units whose
 *    attack is no longer needed do not move;
 *  - an attacker whose attack turns out to be impossible when its turn comes (the target is hidden, it is blocked) simply stays unused;
 *  - an aircraft strikes through [Battle.moveAndAttack] like any shooter (the original sends it on a move-to mission): interception is Unciv's
 *    [com.unciv.logic.battle.AirInterception], and the damage its strike costs it is not part of the expected damage, as in the original.
 */
object TacticalAttacks {

    private const val EPSILON = Constants.minimumMovementEpsilon

    //region Reach

    /** CvUnit::GetMaxAttackStrength with no target: what a unit that shoots fights with, else its attack in the open */
    fun maxAttackStrength(unit: MapUnit): Int =
        if (unit.baseUnit.isRanged()) unit.baseUnit.strength else MapUnitCombatant(unit).getAttackingStrength(null)

    /**
     *  CvUnit::IsCanAttack: units that fight, aircraft by their ranged strike. Nuclear weapons have no attack strength in the original (the nuke
     *  operation sets them off), so they never are strikers.
     */
    fun canAttack(unit: MapUnit) = unit.isMilitary() && !unit.isNuclearWeapon() && !unit.hasUnique(UniqueType.CannotAttack)

    /** CvUnit::isOutOfAttacks */
    fun isOutOfAttacks(unit: MapUnit) = unit.attacksThisTurn >= unit.maxAttacksPerTurn()

    /** CvUnit::IsCityAttackOnly: siege units that only attack cities (the unique "Can only attack [City] units") */
    fun isCityAttackOnly(unit: MapUnit) = unit.getMatchingUniques(UniqueType.CanOnlyAttackUnits).any { it.params[0] == "City" }

    /** The ways [unit] can attack what is on [tile] now: from where it stands if [stayOnTile] (it shoots), else after moving as far as it may */
    fun attackOptions(unit: MapUnit, tile: Tile, stayOnTile: Boolean): List<AttackableTile> {
        if (!unit.hasMovement()) return emptyList()
        // A unit that must set up to shoot needs a movement point for it and one to shoot with
        if (stayOnTile && unit.hasUnique(UniqueType.MustSetUp) && !unit.isSetUpForSiege() && unit.currentMovement - 1f <= EPSILON) return emptyList()
        return TargetHelper.getAttackableEnemies(unit, unit.movement.getDistanceToTiles(), listOf(tile), stayOnTile)
    }

    /** The best way to attack from [options]: from [preferred] if it is one, else the one that leaves the most movement */
    fun bestOption(options: List<AttackableTile>, preferred: Tile?): AttackableTile? =
        options.firstOrNull { it.tileToAttackFrom == preferred } ?: options.maxByOrNull { it.movementLeftAfterMovingToAttackTile }

    /**
     *  CanReachInXTurns / TurnsToReachTarget for a tile we want to move onto: 0 turns means this turn with movement left, 1 means this turn
     *  (the unit stops there), more is the number of turns along its path. [Int.MAX_VALUE] if it cannot get there.
     */
    fun turnsToReach(unit: MapUnit, tile: Tile): Int {
        if (tile == unit.getTile()) return 0
        val reachable = unit.movement.getDistanceToTiles()[tile]
        if (reachable != null) return if (reachable.totalMovement < unit.currentMovement - EPSILON) 0 else 1
        val path = unit.movement.getShortestPath(tile)
        return if (path.isEmpty()) Int.MAX_VALUE else maxOf(path.size, 2)
    }

    /** Whether [unit] can move onto [tile] and get there within [turns] turns (see [turnsToReach]); it must be able to enter the tile this turn if it can reach it this turn */
    private fun canMoveOnto(unit: MapUnit, tile: Tile, turns: Int): Boolean {
        val needed = turnsToReach(unit, tile)
        if (needed > turns) return false
        return needed >= 2 || tile == unit.getTile() || unit.movement.canMoveTo(tile)
    }

    /** Units that can reach the tiles next to [tile] but find them held by units of ours: they could strike if those moved on (the original's "blocked units") */
    private fun isBlocked(unit: MapUnit, tile: Tile): Boolean {
        val reachable = unit.movement.getDistanceToTiles()
        return tile.neighbors.any {
            val move = reachable[it]
            move != null && unit.currentMovement - move.totalMovement > EPSILON && it != unit.getTile() && !unit.movement.canMoveTo(it)
        }
    }

    //endregion
    //region Finding strikers

    /**
     *  CvTacticalAI::FindUnitsWithinStrikingDistance: the units of the tactical AI that can strike [tile] within [turns] turns, strongest
     *  and healthiest first (ranged ones before all others).
     *  @param preferredDamage when pillaging: units with more damage than this count three times as strong (they want to heal)
     *  @param noRanged leave out units that shoot; @param navalOnly only ships; @param mustMoveThrough they must be able to get there with
     *  movement to spare; @param includeBlocked also units that could strike if units of ours made room, at half strength;
     *  @param willPillage the unit pillages the tile (shooters do not); @param targetUndefended the tile has nobody to fight, units just move
     *  onto it (half strength)
     *
     *  An aircraft strikes only as a shooter: more than half its health ([AirMoves.isCombatReady]), the target within its range (line of sight is
     *  no matter), a defender it is expected to damage. The original's other ways to a target (move onto it, move beside it) path-find, which it
     *  never does for aircraft, so aircraft are left out when [noRanged], [willPillage] or [targetUndefended] ask for those.
     */
    fun findUnits(
        ctx: TacticalContext, tile: Tile, turns: Int, preferredDamage: Int = 0, noRanged: Boolean = false, navalOnly: Boolean = false,
        mustMoveThrough: Boolean = false, includeBlocked: Boolean = false, willPillage: Boolean = false, targetUndefended: Boolean = false,
    ): List<Striker> {
        val isCityTarget = tile.isCityCenter()
        val hasEnemy = Battle.getMapCombatantOfTile(tile)?.let { it.getCivInfo() != ctx.civ && ctx.civ.isAtWarWith(it.getCivInfo()) } == true
        val result = ArrayList<Striker>()
        for (unit in ctx.liveUnits()) {
            if (navalOnly && !unit.baseUnit.isWaterUnit) continue
            if (!canAttack(unit) || isOutOfAttacks(unit) || !unit.hasMovement()) continue
            val air = unit.baseUnit.isAirUnit()
            if (air && (noRanged || willPillage || targetUndefended)) continue
            if (!isCityTarget && isCityAttackOnly(unit)) continue
            if (willPillage && !UnitActionsPillage.canPillage(unit, tile)) continue
            val ranged = unit.baseUnit.isRanged()
            // Cannot strike from farther than it moves in the turns and shoots
            val distance = unit.getTile().aerialDistanceTo(tile)
            if (distance > unit.getMaxMovement() * maxOf(turns, 1) + (if (ranged) unit.getRange() else 1)) continue

            var strength = maxAttackStrength(unit)
            val health = unit.health
            if (preferredDamage > 0 && 100 - health > preferredDamage) strength *= 3

            if (targetUndefended) {
                if (canMoveOnto(unit, tile, turns)) result.add(Striker(unit, strength / 2, health))
            } else if (!noRanged && !willPillage && ranged) {
                // Want ranged units to attack first, so inflate this; they take no damage from bombarding, so they show as fully healthy
                if (hasEnemy && (!air || AirMoves.isCombatReady(unit)) && isExpectedToDamageWithRangedAttack(unit, tile) && attackOptions(unit, tile, true).isNotEmpty())
                    result.add(Striker(unit, 100 * unit.baseUnit.rangedStrength, 100, unit.getTile()))
            } else if (hasEnemy && !willPillage) {
                val option = attackOptions(unit, tile, false).let { bestOption(it, null) }
                if (option != null) result.add(Striker(unit, strength, health, option.tileToAttackFrom))
                else if (includeBlocked && !ranged && isBlocked(unit, tile)) result.add(Striker(unit, strength / 2, health))
            } else if (mustMoveThrough) {
                if (canMoveOnto(unit, tile, 0)) result.add(Striker(unit, strength, health))
            } else if (canMoveOnto(unit, tile, turns)) result.add(Striker(unit, strength, health))
            else if (includeBlocked && !ranged && isBlocked(unit, tile)) result.add(Striker(unit, strength / 2, health))
        }
        // std::stable_sort: in the order we'd like them to attack
        return result.sortedByDescending { it.attackPriority }
    }

    /** CvCity::canRangeStrikeAt: the city can shoot what is on [tile] */
    fun canRangeStrikeAt(city: City, tile: Tile): Boolean =
        city.canBombard() && city.getCenterTile().aerialDistanceTo(tile) <= city.getBombardRange()
            && tile.isVisible(city.civ) && TargetHelper.containsAttackableEnemy(tile, CityCombatant(city))

    /** CvTacticalAI::FindCitiesWithinStrikingDistance: our cities that can shoot [tile] this turn */
    fun findCities(ctx: TacticalContext, tile: Tile): List<CityStriker> =
        ctx.civ.cities.filter { canRangeStrikeAt(it, tile) }.map { CityStriker(it) }

    /**
     *  CvTacticalAI::IsExpectedToDamageWithRangedAttack: [unit] shooting from where it stands is expected (the mean of the randomness) to
     *  do damage to the city on [tile] or else to the defender there, the military unit that can defend. A tile with only a civilian or an
     *  embarked unit has no defender, so no damage is expected and no ranged unit is used on it.
     */
    fun isExpectedToDamageWithRangedAttack(unit: MapUnit, tile: Tile): Boolean {
        val defender: ICombatant = if (tile.isCityCenter()) CityCombatant(tile.getCity()!!)
            else MapUnitCombatant(TacticalTargets.visibleEnemyDefender(unit.civ, tile) ?: return false)
        return BattleDamage.calculateDamageToDefender(MapUnitCombatant(unit), defender, unit.getTile(), TacticalTargets.EXPECTED_RANDOMNESS) > 0
    }

    //endregion
    //region Expected damage

    /**
     *  CvTacticalAI::ComputeTotalExpectedDamage: what [strikers] do to the unit or city on the target's tile and take from it, in whole hit
     *  points at the mean of the randomness; sets each striker's expected damages.
     *  @return the damage the strikers do together
     */
    fun computeTotalExpectedDamage(target: TacticalTarget, strikers: List<Striker>): Int {
        val defender = Battle.getMapCombatantOfTile(target.tile) ?: return 0
        var total = 0
        for (striker in strikers) {
            val attacker = MapUnitCombatant(striker.unit)
            val from = striker.attackFrom ?: striker.unit.getTile()
            striker.expectedTargetDamage = BattleDamage.calculateDamageToDefender(attacker, defender, from, TacticalTargets.EXPECTED_RANDOMNESS)
            // Shooting costs nothing (CvTacticalAI sets the self damage of ranged attacks to 0), aircraft included: what Unciv's air strikes cost them is not weighed
            striker.expectedSelfDamage = if (striker.unit.baseUnit.isRanged()) 0 else BattleDamage.calculateDamageToAttacker(attacker, defender, from, TacticalTargets.EXPECTED_RANDOMNESS)
            total += striker.expectedTargetDamage
        }
        return total
    }

    /** CvTacticalAI::ComputeTotalExpectedBombardDamage: what [cities] do to [defender] by shooting at it */
    fun computeTotalExpectedBombardDamage(defender: ICombatant, cities: List<CityStriker>): Int {
        var total = 0
        for (striker in cities) {
            striker.expectedTargetDamage = BattleDamage.calculateDamageToDefender(
                CityCombatant(striker.city), defender, striker.city.getCenterTile(), TacticalTargets.EXPECTED_RANDOMNESS)
            total += striker.expectedTargetDamage
        }
        return total
    }

    //endregion
    //region ExecuteAttack

    /**
     *  CvTacticalAI::ExecuteAttack: attack the unit or city on [target]'s tile until the damage still needed, [TacticalTarget.aux] plus the
     *  overkill percent (AI_TACTICAL_OVERKILL_PERCENT), has been used up in expectation: our cities that can shoot it first, then the units that
     *  shoot and are in range, then the melee units (beside the target, or moving to a free tile beside it, else next to it for the next
     *  turn). A unit whose expected damage to the target is less than what it takes joins only if [inflictWhatWeTake] is off, one that
     *  would die only if [mustSurviveAttack] is off. Against a city at least one melee attack is made.
     *  [strikers] and [cities] come from [findUnits] / [findCities] and [computeTotalExpectedDamage].
     */
    fun executeAttack(
        ctx: TacticalContext, target: TacticalTarget, strikers: List<Striker>, cities: List<CityStriker>,
        inflictWhatWeTake: Boolean = false, mustSurviveAttack: Boolean = true,
    ) {
        launch(ctx, planAttack(ctx, target, strikers, cities, inflictWhatWeTake, mustSurviveAttack))
    }

    /** The planning half of [executeAttack]: who attacks [target], in what order (cities, shooters, melee units), without making any attack */
    fun planAttack(
        ctx: TacticalContext, target: TacticalTarget, strikers: List<Striker>, cities: List<CityStriker>,
        inflictWhatWeTake: Boolean = false, mustSurviveAttack: Boolean = true,
    ): AttackSeries {
        val tile = target.tile
        val series = AttackSeries(target)
        var damageRemaining = target.aux * (100 + ctx.rules.define(AiDefine.AI_TACTICAL_OVERKILL_PERCENT)) / 100

        // Start by applying damage from city bombards
        for (striker in cities) {
            if (damageRemaining <= 0) break
            series.addCity(striker.city)
            damageRemaining -= striker.expectedTargetDamage
        }

        fun usable(striker: Striker): Boolean {
            val unit = striker.unit
            if (inflictWhatWeTake && striker.expectedTargetDamage < striker.expectedSelfDamage) {
                ctx.log { "not attacking with ${unit.name}: it cannot inflict what it takes" }
                return false
            }
            if (!unit.hasMovement()) return false
            if (mustSurviveAttack && striker.expectedSelfDamage >= unit.health) {
                ctx.log { "not attacking with ${unit.name} at ${unit.getTile().position}: it would be destroyed" }
                return false
            }
            return true
        }

        // First loop is ranged units only
        for (striker in strikers) {
            if (damageRemaining <= 0) break
            val unit = striker.unit
            if (!unit.baseUnit.isRanged() || !usable(striker) || series.contains(unit)) continue
            if (unit.getTile().aerialDistanceTo(tile) > unit.getRange() || attackOptions(unit, tile, true).isEmpty()) continue
            series.addUnit(unit, true, unit.getTile())
            damageRemaining -= striker.expectedTargetDamage
        }

        // If target is city, want to get in one melee attack, so set damage remaining to 1
        if (target.type == TacticalTargetType.City && damageRemaining < 1) damageRemaining = 1

        // The free tiles around the target to attack from
        val freeTiles = tile.neighbors.filter { n ->
            !ctx.isEnemyCity(n) && n.militaryUnit?.let { it.civ == target.targetCiv } != true
        }.toMutableList()

        // Second loop are only melee units
        for (striker in strikers) {
            if (damageRemaining <= 0) break
            val unit = striker.unit
            if (unit.baseUnit.isRanged() || !usable(striker) || series.contains(unit)) continue
            var attackFrom: Tile? = null
            if (unit.getTile().aerialDistanceTo(tile) <= 1) attackFrom = unit.getTile()
            else {
                // Find spaces adjacent to target we can move into with movement left
                val reachable = unit.movement.getDistanceToTiles()
                attackFrom = freeTiles.firstOrNull {
                    val move = reachable[it]
                    move != null && unit.currentMovement - move.totalMovement > EPSILON && unit.movement.canMoveTo(it)
                }
                if (attackFrom != null) freeTiles.remove(attackFrom)
                else {
                    ctx.log { "not enough free tiles next to $tile for ${unit.name} to attack" }
                    // Move up if we can, to attack next turn
                    if (TacticalMoves.moveToEmptySpaceNearTarget(ctx, unit, tile)) ctx.unitProcessed(unit)
                    continue
                }
            }
            series.addUnit(unit, false, attackFrom)
            damageRemaining -= striker.expectedTargetDamage
        }
        return series
    }

    /**
     *  CvTacticalAI::LaunchAttack and CombatResolved: make the attacks of [series] one after the other. When the target is gone (killed, or
     *  the city taken) the follow-up attacks are dropped, their units are free for other moves. A unit that has been in a fight is
     *  done for the turn, unless it still can attack or move after attacking.
     *  @return whether an attack was made
     */
    fun launch(ctx: TacticalContext, series: AttackSeries): Boolean {
        val target = series.target
        var attacked = false
        for (queued in series.attacks) {
            if (!target.isAlive(ctx.civ)) break
            val defender = Battle.getMapCombatantOfTile(target.tile) ?: break
            val city = queued.city
            if (city != null) {
                if (!city.canBombard()) continue
                ctx.log { "${city.name} bombards ${target.tile.position}" }
                Battle.attackOrNuke(CityCombatant(city), AttackableTile(city.getCenterTile(), target.tile, 0f, defender))
                attacked = true
                continue
            }
            val unit = queued.unit!!
            if (unit.isDestroyed || unit.civ != ctx.civ || !unit.canAttack()) continue
            val option = bestOption(attackOptions(unit, target.tile, queued.ranged), queued.attackFrom) ?: continue
            ctx.log { "${unit.name} at ${unit.getTile().position} attacks ${target.tile.position} from ${option.tileToAttackFrom.position}" }
            val attacksBefore = unit.attacksThisTurn
            Battle.moveAndAttack(MapUnitCombatant(unit), option)
            if (unit.isDestroyed || unit.attacksThisTurn > attacksBefore) attacked = true
            // CombatResolved: an attacker that can still attack or move after attacking is released to be processed again
            val released = !unit.isDestroyed && unit.hasMovement() && (unit.canAttack() || unit.hasUnique(UniqueType.CanMoveAfterAttacking))
            if (!released) ctx.unitProcessed(unit, queued.ranged)
        }
        return attacked
    }

    //endregion
}
