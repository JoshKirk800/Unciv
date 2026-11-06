package com.unciv.logic.battle

import com.unciv.ui.components.UnitMovementMemoryType
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly

/** Heavy Charge (Brave New World, [UniqueType.HeavyCharge]) and the fall-back of a defender (CvUnit::CanFallBackFromMelee, CvUnit::DoFallBackFromMelee). */
object MeleeFallBack {

    /** Whether the unit may not retreat into [tile]: it cannot move there, or would leave land for water, or enter a foreign city. */
    @Readonly
    internal fun cannotRetreatTo(defender: MapUnitCombatant, tile: Tile): Boolean =
        !defender.unit.movement.canMoveTo(tile)
            || defender.isLandUnit() && !tile.isLand
            || tile.isCityCenter() && tile.getOwner() != defender.getCivInfo()

    /** The neighbours of the defender's tile, away from the attacker, in the order the original tries them:
     *  the tile straight behind it, then the two beside that one (the `first` side being the one tried first), then the two next to the attacker. */
    private class Retreat(val behind: Tile?, val behindSides: List<Tile>, val besideAttacker: List<Tile>)

    @Readonly
    private fun getRetreat(defenderTile: Tile, attackerTile: Tile): Retreat {
        val neighbors = defenderTile.neighbors.filter { it != attackerTile }.toList()
        val besideAttacker = neighbors.filter { it in attackerTile.neighbors }
        val behindThree = neighbors.filter { it !in attackerTile.neighbors }
        // the one behind is the one that touches neither of the tiles beside the attacker
        val behind = behindThree.firstOrNull { tile -> besideAttacker.none { it in tile.neighbors } }
        return Retreat(behind, behindThree.filter { it != behind }, besideAttacker)
    }

    /** CvUnit::CanFallBackFromMelee: false only if all three tiles behind the defender exist and are blocked. */
    @Readonly
    fun canFallBack(defender: MapUnitCombatant, attackerTile: Tile): Boolean {
        val retreat = getRetreat(defender.getTile(), attackerTile)
        val candidates = if (retreat.behind == null) retreat.behindSides else retreat.behindSides + retreat.behind
        return candidates.count { cannotRetreatTo(defender, it) } < 3
    }

    /** +50% for a Heavy Charge attack on a unit that cannot fall back (CvUnitCombat::GenerateMeleeCombatInfo) */
    @Readonly
    fun isHeavyChargeBoosted(attacker: ICombatant, defender: ICombatant): Boolean =
        attacker is MapUnitCombatant && defender is MapUnitCombatant && attacker.unit.cache.hasHeavyChargeUnique
            && attacker.isMelee() && !defender.isCivilian()
            && attacker.unit.hasUnique(UniqueType.HeavyCharge, GameContext(attacker, defender, defender.getTile(), CombatAction.Attack))
            && !canFallBack(defender, attacker.getTile())

    /** After a melee attack: a Heavy Charge that did more damage than it took pushes the surviving defender back (CvUnitCombat::ResolveMeleeCombat).
     *  @return whether the defender moved */
    fun tryHeavyChargeFallBack(attacker: ICombatant, defender: ICombatant, damageDealt: Battle.DamageDealt): Boolean {
        if (attacker !is MapUnitCombatant || defender !is MapUnitCombatant || !attacker.unit.cache.hasHeavyChargeUnique) return false
        if (!attacker.isMelee() || attacker.isDefeated() || defender.isDefeated() || defender.isCivilian()) return false
        if (damageDealt.attackerDealt <= damageDealt.defenderDealt) return false
        if (!attacker.unit.hasUnique(UniqueType.HeavyCharge, GameContext(attacker, defender, defender.getTile(), CombatAction.Attack))) return false
        if (defender.unit.isEscorting()) return false // leaving the escorted unit behind defeats the escort

        val fromTile = defender.getTile()
        val retreat = getRetreat(fromTile, attacker.getTile())
        val rng = GameContext(attacker, defender, fromTile, CombatAction.Defend).stateBasedRandom("MeleeFallBack.tryHeavyChargeFallBack")
        // Biases 0, -s, +s, -2s, +2s of the original with a random side s: the tile beside the attacker next to the first side is tried before the other
        val sides = retreat.behindSides.shuffled(rng)
        val nearAttacker = sides.map { side -> retreat.besideAttacker.firstOrNull { it in side.neighbors } }
        val order = listOfNotNull(retreat.behind) + sides + nearAttacker.filterNotNull() +
            retreat.besideAttacker.filter { it !in nearAttacker }
        val toTile = order.firstOrNull { !cannotRetreatTo(defender, it) } ?: return false

        defender.unit.removeFromTile()
        defender.unit.putInTile(toTile)
        defender.unit.mostRecentMoveType = UnitMovementMemoryType.UnitWithdrew
        return true
    }
}
