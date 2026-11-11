package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.battle.Battle
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsPillage
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.UniqueType

/** One unit that could take a tile (CvTacticalUnit as FindClosestUnit fills it): after [turns] turns, from [distance] tiles away */
class Candidate(val unit: MapUnit, val turns: Int, val distance: Int)

/** One unit assigned to one tile (CvBlockingUnit) */
class Block(val unit: MapUnit, val tile: Tile, val distance: Int)

/**
 *  Bombarding from where the enemy cannot get at the ranged unit, and the attacks that combine units on one target: CvTacticalAI::
 *  ExecuteSafeBombards, ExecuteProtectedBombards, ExecuteOneProtectedBombard, CanCoverFromEnemy, ExecuteFlankAttack,
 *  ExecutePriorityAttacksOnUnitTarget, FindClosestUnit, ChooseRemainingAssignments (the matching of units to tiles).
 *
 *  A safe bombard shoots from a tile within range of the target that no enemy unit can attack (the analysis map's SubjectToAttack, see
 *  [TacticalMap.isSubjectToAttack]), moving a ranged unit there if need be (up to 2 turns away). A protected bombard shoots from a tile
 *  enemies can reach after melee units of ours have taken the tiles around it they could come through. A flank attack puts melee units
 *  on the free tiles around a unit, which adds the flank bonus to every attack on it, and then attacks.
 *
 *  Deviations: the original asks the unit whether it can strike the target from where it stands now (CvUnit::canEverRangeStrikeAt at its
 *  current tile) when it looks for a unit to move to a bombard tile, which would only let units already in range move there; here the
 *  range and the line of sight are those of the tile it moves to. Of the targets of embarked units, which the original never finds any
 *  damage to inflict on (it asks for a defender that an embarked unit is not), none is shot at.
 */
object TacticalBombards {

    /**
     *  CvTacticalAI::FindClosestUnit: the units of the tactical AI that can be on [tile] within [turns] turns (see [TacticalAttacks.turnsToReach]),
     *  with the requirements of the caller, the one that gets there soonest first.
     *  @param mustHaveHalfHp not damaged by more than half; @param mustBeRanged a unit that shoots, with a range of at least [rangeRequired]
     *  from [tile] that suits the tile's domain; @param needsIgnoreLos a unit that shoots over obstacles; @param mustBeMelee a unit that does not shoot;
     *  @param ignoreUnits do not mind the tile being held by another unit
     */
    fun findClosestUnits(
        ctx: TacticalContext, tile: Tile, turns: Int, mustHaveHalfHp: Boolean = false, mustBeRanged: Boolean = false, rangeRequired: Int = 2,
        needsIgnoreLos: Boolean = false, mustBeMelee: Boolean = false, ignoreUnits: Boolean = false, exclude: Collection<MapUnit> = emptyList(),
    ): List<Candidate> {
        val result = ArrayList<Candidate>()
        for (unit in ctx.liveUnits()) {
            if (unit in exclude) continue
            val base = unit.baseUnit
            // Units with no combat strength of their own are not used: civilians, and aircraft (the original gives them ranged strength only)
            if (base.isAirUnit() || base.strength == 0 && base.rangedStrength == 0) continue
            if (mustHaveHalfHp && unit.health < 50) continue
            if (mustBeRanged) {
                if (tile.isWater && base.isLandUnit || !tile.isWater && !tile.isCityCenter() && base.isWaterUnit) continue
                if (!base.isRanged() || unit.getRange() < rangeRequired || TacticalAttacks.isOutOfAttacks(unit)) continue
            }
            if (needsIgnoreLos && !unit.hasUnique(UniqueType.IndirectFire, checkCivInfoUniques = true)) continue
            if (mustBeMelee && base.isRanged()) continue
            val distance = unit.getTile().aerialDistanceTo(tile)
            if (turns == 0 && distance > ctx.recruitRange / 2 || turns == 1 && distance > ctx.recruitRange) continue
            val needed = TacticalAttacks.turnsToReach(unit, tile)
            if (needed > turns) continue
            if (!ignoreUnits && needed < 2 && unit.getTile() != tile && !unit.movement.canMoveTo(tile)) continue
            result.add(Candidate(unit, needed, distance))
        }
        return result.sortedBy { it.turns }
    }

    // region Safe bombards

    /**
     *  CvTacticalAI::PlotSafeBombardMoves for one target: [TacticalMap.setTargetBombardCells] for it, then [executeSafeBombards].
     *  Only worth doing when a ranged unit of ours is near enough to be used.
     */
    fun safeBombardTarget(ctx: TacticalContext, target: TacticalTarget): Boolean {
        if (!target.isAlive(ctx.civ)) return false
        val tile = target.tile
        if (ctx.units.none { it.baseUnit.isRanged() && it.getTile().aerialDistanceTo(tile) <= ctx.recruitRange }
            && ctx.civ.cities.none { TacticalAttacks.canRangeStrikeAt(it, tile) }) return false
        ctx.map.clearDynamicFlags()
        ctx.map.setTargetBombardCells(tile, ctx.map.bestFriendlyRange, ctx.map.ignoreLineOfSight)
        return executeSafeBombards(ctx, target)
    }

    /**
     *  CvTacticalAI::ExecuteSafeBombards: bombard the unit on [target]'s tile from tiles it cannot attack (the target's bombard cells are set):
     *  our cities that can shoot it, ranged units already on such a tile, and ranged units that move to such a tile within 2 turns (a unit
     *  already in the tile's reach first), up to the damage required. @return whether an attack was made
     */
    fun executeSafeBombards(ctx: TacticalContext, target: TacticalTarget): Boolean {
        val tile = target.tile
        val series = AttackSeries(target)
        var required = 0
        val defender = TacticalTargets.visibleEnemyDefender(ctx.civ, tile)
        if (defender != null) {
            required = defender.health
            // If this is a unit target we might also be able to hit it with a city
            val cities = TacticalAttacks.findCities(ctx, tile)
            TacticalAttacks.computeTotalExpectedBombardDamage(Battle.getMapCombatantOfTile(tile)!!, cities)
            for (striker in cities) {
                if (required <= 0) break
                series.addCity(striker.city)
                required -= striker.expectedTargetDamage
            }
        } else if (tile.isCityCenter()) {
            required = tile.getCity()!!.health
            // Can't eliminate a city with ranged fire, so don't target one if that low on health
            if (required <= 1) return false
        }
        if (required <= 0) return false

        // Ranged units that are already on a tile that can bombard and cannot be attacked: bombs away
        val map = ctx.map
        for (unit in ctx.liveUnits()) {
            if (!unit.baseUnit.isRanged() || TacticalAttacks.isOutOfAttacks(unit)) continue
            val unitTile = unit.getTile()
            if (map.isWithinRangeOfTarget(unitTile) && !map.isSubjectToAttack(unitTile) && TacticalAttacks.attackOptions(unit, tile, true).isNotEmpty()) {
                ctx.log { "safe bombard (no move) with ${unit.name}, target ${tile.position}, at ${unitTile.position}" }
                series.addUnit(unit, true, unitTile)
            }
        }

        // For each tile that we can bombard from and the enemy cannot attack, move a ranged unit there
        val range = map.bestFriendlyRange
        for (turnsToReach in 0..MAX_TURNS_TO_BOMBARD_TILE) {
            for (cell in tile.getTilesInDistance(range)) {
                if (cell == tile || !map.isRevealed(cell) || !map.canUseForOperationGathering(cell)) continue
                if (!map.isWithinRangeOfTarget(cell) || map.isSubjectToAttack(cell)) continue
                val distance = cell.aerialDistanceTo(tile)
                val hasLos = ctx.hasLineOfSight(cell, tile)
                val candidate = findClosestUnits(ctx, cell, turnsToReach, mustBeRanged = true, rangeRequired = distance, needsIgnoreLos = !hasLos,
                    ignoreUnits = true, exclude = series.attacks.mapNotNull { it.unit }).firstOrNull() ?: continue
                val unit = candidate.unit
                // Check for presence of unmovable friendly units
                val blocker = cell.militaryUnit
                if (blocker != null && blocker != unit && !TacticalMoves.executeMoveOfBlockingUnit(ctx, blocker)) continue
                if (cell != unit.getTile()) unit.movement.tryHeadTowards(cell)
                ctx.log { "moving closer for safe bombard with ${unit.name}, target ${tile.position}, bombard from ${cell.position}, now at ${unit.getTile().position}" }
                if (unit.getTile() != cell) {
                    ctx.unitProcessed(unit)
                    continue
                }
                // The unit is placed: it shoots now, if it can
                if (unit.hasMovement() && !TacticalAttacks.isOutOfAttacks(unit) && TacticalAttacks.attackOptions(unit, tile, true).isNotEmpty())
                    series.addUnit(unit, true, cell)
                else ctx.unitProcessed(unit)
            }
        }
        return TacticalAttacks.launch(ctx, series)
    }

    private const val MAX_TURNS_TO_BOMBARD_TILE = 2

    // endregion
    // region Protected bombards

    /** CvTacticalAI::ExecuteProtectedBombards: protected bombards, one after the other, while the target lives and they can be made */
    fun executeProtectedBombards(ctx: TacticalContext, target: TacticalTarget): Boolean {
        var attackMade = true
        var atLeastOne = false
        while (attackMade && target.isAlive(ctx.civ)) {
            attackMade = executeOneProtectedBombard(ctx, target)
            if (attackMade) atLeastOne = true
        }
        return atLeastOne
    }

    /**
     *  CvTacticalAI::ExecuteOneProtectedBombard: choose the tile to shoot [target] from (its bombard cells are set) that has no enemy unit next
     *  to it, a ranged unit that can be there this turn or next, and enough melee units to take the tiles around it that the enemy could
     *  attack it through ([canCoverFromEnemy]); most desirable is 300 - 100 for each turn the unit needs - 40 for each covering unit.
     *  Then the covering units take their tiles and the ranged unit moves there and shoots. @return whether it shot
     */
    fun executeOneProtectedBombard(ctx: TacticalContext, target: TacticalTarget): Boolean {
        val tile = target.tile
        if (tile.isCityCenter() && tile.getCity()!!.health <= 1) return false // can't eliminate a city with ranged fire
        val map = ctx.map
        val range = map.bestFriendlyRange

        var best: Tile? = null
        var bestPriority = Int.MIN_VALUE
        for (cell in tile.getTilesInDistance(range)) {
            if (cell == tile || !map.isRevealed(cell) || !map.canUseForOperationGathering(cell) || !map.isWithinRangeOfTarget(cell)) continue
            if (cell.neighbors.any { map.enemyMilitaryUnit(it) != null }) continue
            val distance = cell.aerialDistanceTo(tile)
            val needsIgnoreLos = !ctx.hasLineOfSight(cell, tile)
            val shooter = findClosestUnits(ctx, cell, 0, mustBeRanged = true, rangeRequired = distance, needsIgnoreLos = needsIgnoreLos).firstOrNull()
                ?: findClosestUnits(ctx, cell, 1, mustBeRanged = true, rangeRequired = distance, needsIgnoreLos = needsIgnoreLos).firstOrNull()
                ?: continue
            val cover = canCoverFromEnemy(ctx, cell, shooter.unit) ?: continue
            // Set up math so having to allocate 3 extra units to defend is worse than waiting a turn to attack
            val priority = 300 - shooter.turns * 100 - 40 * cover.numRequired
            if (priority > bestPriority) { // std::stable_sort, the first of equals wins
                bestPriority = priority
                best = cell
            }
        }
        val cell = best ?: return false

        val distance = cell.aerialDistanceTo(tile)
        val needsIgnoreLos = !ctx.hasLineOfSight(cell, tile)
        val shooter = (findClosestUnits(ctx, cell, 0, mustBeRanged = true, rangeRequired = distance, needsIgnoreLos = needsIgnoreLos).firstOrNull()
            ?: findClosestUnits(ctx, cell, 1, mustBeRanged = true, rangeRequired = distance, needsIgnoreLos = needsIgnoreLos).firstOrNull())?.unit
            ?: return false
        val cover = canCoverFromEnemy(ctx, cell, shooter) ?: return false

        // Make each blocking move
        for (block in cover.blocks) {
            val unit = block.unit
            if (unit.getTile() != block.tile) {
                ctx.log { "moving to cover a protected bombard with ${unit.name}, to ${block.tile.position}" }
                unit.movement.tryHeadTowards(block.tile)
            } else if (UnitActionsPillage.canPillage(unit, unit.getTile()) && unit.health < 100) {
                ctx.log { "pillaging during a protected bombard with ${unit.name}" }
                UnitActionsPillage.getPillageAction(unit, unit.getTile())?.action?.invoke()
            } else if (unit.canFortify()) unit.fortify()
            unit.currentMovement = 0f
            ctx.unitProcessed(unit)
        }

        // Then move the attacking unit
        if (cell != shooter.getTile()) {
            ctx.log { "moving closer for protected bombard with ${shooter.name}, to ${cell.position}" }
            shooter.movement.tryHeadTowards(cell)
        }
        ctx.unitProcessed(shooter)
        if (shooter.hasMovement() && !TacticalAttacks.isOutOfAttacks(shooter) && TacticalAttacks.attackOptions(shooter, tile, true).isNotEmpty()) {
            ctx.log { "making a protected bombard with ${shooter.name}, target ${tile.position}, at ${shooter.getTile().position}" }
            val series = AttackSeries(target)
            series.addUnit(shooter, true, shooter.getTile())
            return TacticalAttacks.launch(ctx, series)
        }
        return false
    }

    /** The units that cover a tile, and how many it takes (CanCoverFromEnemy's out parameter and chosen blocks) */
    class Cover(val numRequired: Int, val blocks: List<Block>)

    /**
     *  CvTacticalAI::CanCoverFromEnemy: can melee units of ours take every land tile around [tile] that an enemy could move through (and that no
     *  unit of ours has settled on), so the ranged unit [attacker] that goes to [tile] is safe? Tiles at sea are always covered, as
     *  enemies cannot make melee attacks there. @return the units and what they cover, null if it cannot be done
     */
    fun canCoverFromEnemy(ctx: TacticalContext, tile: Tile, attacker: MapUnit): Cover? {
        if (tile.isWater) return Cover(0, emptyList())
        val map = ctx.map
        // Find all the hexes we need to cover
        val toCover = tile.neighbors.filter { !it.isWater && map.enemyCanMovePast(it) && !map.isFriendlyTurnEndTile(it) }.toList()
        if (toCover.isEmpty()) return Cover(0, emptyList())
        if (toCover.size > ctx.units.size) return null

        // Have some unit that can cover each hex this turn?
        val choices = LinkedHashMap<Tile, List<Candidate>>()
        for (hex in toCover) {
            val units = findClosestUnits(ctx, hex, 1, mustBeMelee = true).filter { it.unit != attacker }
            if (units.isEmpty()) return null
            choices[hex] = units
        }
        // Now select exact covering units, making sure we didn't over commit a unit to covering more than one hex
        val blocks = assign(choices, canLeaveOpenings = false) ?: return null
        return Cover(toCover.size, blocks)
    }

    // endregion
    // region Flank attacks

    /**
     *  CvTacticalAI::ExecuteFlankAttack: if at least 2 of the free tiles around the unit on [target]'s tile give a flank bonus (its flank bonus
     *  cells are set) and melee units of ours can take them (one this turn with at least half health and 3 tiles in all, or 2 units this
     *  turn), move them in and attack with everything that can. @return false: like the original, which never reports an attack
     */
    fun executeFlankAttack(ctx: TacticalContext, target: TacticalTarget): Boolean {
        val tile = target.tile
        val map = ctx.map
        val flankTiles = tile.neighbors.filter { map.helpsProvideFlankBonus(it) }.toList()
        // If more than 1, find how many we can fill with units
        if (flankTiles.size <= 1) return false
        var fillable = 0
        var attackers = 0
        val fillableTiles = ArrayList<Tile>()
        for (hex in flankTiles) {
            if (findClosestUnits(ctx, hex, 0, mustHaveHalfHp = true, mustBeMelee = true).isNotEmpty()) {
                fillable++
                attackers++
                fillableTiles.add(hex)
            } else if (findClosestUnits(ctx, hex, 1, mustBeMelee = true).isNotEmpty()) { // What about next turn?
                fillable++
                fillableTiles.add(hex)
            }
        }
        // As long as we either get three hexes filled with one attacker, or two we can attack from, then this multi-hex attack is worth considering
        if (!((attackers >= 1 && fillable >= 3) || attackers >= 2)) return false

        // Compute best way to fill the hexes
        val choices = LinkedHashMap<Tile, List<Candidate>>()
        for (hex in fillableTiles) {
            val units = findClosestUnits(ctx, hex, 1, mustBeMelee = true)
            if (units.isEmpty()) {
                fillable--
                if (fillable < 2) return false
            } else choices[hex] = units
        }
        val blocks = assign(choices, canLeaveOpenings = fillable != 2, minimum = 2) ?: return false

        // Make moves up into hexes
        for (block in blocks) {
            val unit = block.unit
            if (unit.getTile() == block.tile) ctx.log { "already in a flanking position with ${unit.name}, ${block.tile.position}" }
            else {
                ctx.log { "moving into a flanking position with ${unit.name}, ${block.tile.position}" }
                unit.movement.tryHeadTowards(block.tile)
                if (!unit.hasMovement()) ctx.unitProcessed(unit)
            }
        }
        // Make attacks
        val defender = TacticalTargets.visibleEnemyDefender(ctx.civ, tile) ?: return false
        target.aux = defender.health
        val strikers = TacticalAttacks.findUnits(ctx, tile, 1, 0)
        if (strikers.isNotEmpty()) {
            TacticalAttacks.computeTotalExpectedDamage(target, strikers)
            TacticalAttacks.executeAttack(ctx, target, strikers, emptyList(), inflictWhatWeTake = false, mustSurviveAttack = true)
        }
        return false
    }

    // endregion

    /**
     *  CvTacticalAI::ExecutePriorityAttacksOnUnitTarget: what can be done to a unit target with bombards and flanks: safe bombards, protected
     *  bombards, then a flank attack, as long as it lives.
     */
    fun executePriorityAttacksOnUnitTarget(ctx: TacticalContext, target: TacticalTarget) {
        val tile = target.tile
        // Try to find a bombard first
        ctx.map.clearDynamicFlags()
        ctx.map.setTargetBombardCells(tile, ctx.map.bestFriendlyRange, ctx.map.ignoreLineOfSight)
        if (target.isAlive(ctx.civ)) executeSafeBombards(ctx, target)
        if (target.isAlive(ctx.civ)) executeProtectedBombards(ctx, target)
        // Then try for a flank attack
        if (target.isAlive(ctx.civ)) {
            ctx.map.clearDynamicFlags()
            ctx.map.setTargetFlankBonusCells(tile)
            executeFlankAttack(ctx, target)
        }
    }

    /**
     *  CvTacticalAI::ChooseRemainingAssignments / ScoreAssignments: assign each tile of [choices] one of its candidate units, no unit twice, the
     *  way that scores best: 10000 - 1000 x the distance of the unit + its power, for each assignment. Without [canLeaveOpenings] every
     *  tile must get a unit; with it tiles may stay open as long as [minimum] get one.
     *  @return the assignments, null if there is no acceptable one
     */
    fun assign(choices: Map<Tile, List<Candidate>>, canLeaveOpenings: Boolean, minimum: Int = choices.size): List<Block>? {
        val tiles = choices.keys.toList()
        // With n tiles, the n best candidates of a tile are all that can matter
        val options = tiles.map { hex -> choices.getValue(hex).sortedByDescending { score(it) }.take(tiles.size) }
        var bestScore = -1
        var best: List<Block>? = null
        val used = HashSet<MapUnit>()
        val current = ArrayList<Block>()

        fun search(index: Int, score: Int) {
            if (index == tiles.size) {
                if (current.size >= minimum && score > bestScore && (canLeaveOpenings || current.size == tiles.size)) {
                    bestScore = score
                    best = ArrayList(current)
                }
                return
            }
            for (candidate in options[index]) {
                if (!used.add(candidate.unit)) continue
                current.add(Block(candidate.unit, tiles[index], candidate.distance))
                search(index + 1, score + score(candidate))
                current.removeAt(current.size - 1)
                used.remove(candidate.unit)
            }
            if (canLeaveOpenings) search(index + 1, score)
        }
        search(0, 0)
        return best
    }

    private fun score(candidate: Candidate) = 10000 - candidate.distance * 1000 + candidate.unit.getAiPower()
}
