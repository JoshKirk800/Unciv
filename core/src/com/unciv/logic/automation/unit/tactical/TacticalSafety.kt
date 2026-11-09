package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.unit.PlotDanger
import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import yairm210.purity.annotations.Readonly

/**
 *  How units get out of harm's way: CvTacticalAI::PlotMovesToSafety / ExecuteMovesToSafestPlot (combat units), PlotHealMoves / ExecuteHeals,
 *  and the checks they share (CvUnit::IsCanDefend, IsUnderEnemyRangedAttack). Civilians' counterpart is [com.unciv.logic.automation.unit.CivilianSafety].
 *
 *  Deviation: [isUnderEnemyRangedAttack] only counts what we can see (the original reads every enemy unit) and takes no line of sight into account.
 */
object TacticalSafety {

    /** CvUnit::IsCanDefend: a combat unit that is not embarked (a land unit on water cannot defend itself) */
    @Readonly
    fun canDefend(unit: MapUnit): Boolean = unit.baseUnit.strength > 0 && unit.isMilitary() && !unit.isEmbarked()

    /**
     *  CvUnit::IsUnderEnemyRangedAttack: a city of a civilization at war with us that can bombard the tile, or enemy units that can shoot it
     *  and together do more damage than the unit heals in a turn.
     */
    fun isUnderEnemyRangedAttack(unit: MapUnit): Boolean {
        val civ = unit.civ
        val tile = unit.getTile()
        var totalDamage = 0
        val target = MapUnitCombatant(unit)
        val healing = unit.healingAmountForTile(tile)
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || !civ.isAtWarWith(other)) continue
            for (city in other.cities)
                if (city.getCenterTile().aerialDistanceTo(tile) <= city.getBombardRange() && city.getCenterTile().isExplored(civ)) return true
            for (enemy in other.units.getCivUnits()) {
                if (!enemy.isMilitary() || enemy.baseUnit.isAirUnit() || enemy.baseUnit.rangedStrength <= 0) continue
                if (enemy.getTile().aerialDistanceTo(tile) > enemy.getRange() || !enemy.isVisibleTo(civ)) continue
                totalDamage += BattleDamage.calculateDamageToDefender(MapUnitCombatant(enemy), target, tile, EXPECTED_RANDOMNESS)
                if (totalDamage > healing) return true
            }
        }
        return false
    }

    /**
     *  CvTacticalAI::PlotMovesToSafety for one combat unit: does it flee? Only from a tile with danger. A hurt unit flees if it is under
     *  enemy ranged attack or at half strength or less (a hurt barbarian only if it is a ship); a healthy one (never a barbarian) if the
     *  danger of its tile is above its strength x100. Embarked units (which cannot defend) always flee.
     */
    fun wantsToFlee(unit: MapUnit, danger: PlotDanger): Boolean {
        val tile = unit.getTile()
        val level = danger.get(tile)
        if (level <= 0) return false
        val civ = unit.civ
        if (!canDefend(unit)) return true
        val strength = PlotDanger.strengthConsideringDamage(unit, civ.gameInfo.ruleset.ai)
        if (unit.health < MAX_HEALTH) {
            if (civ.isBarbarian) return unit.baseUnit.isWaterUnit
            return isUnderEnemyRangedAttack(unit) || strength * 2 <= unit.baseUnit.strength
        }
        return !civ.isBarbarian && level > strength * 100
    }

    /**
     *  CvTacticalAI::ExecuteMovesToSafestPlot's choice for a combat unit: among the tiles it can reach this turn (not the one it stands on) that
     *  hold no foreign unit and no unit of ours of its type, the best of the first non-empty class - our cities (lowest danger first),
     *  tiles without danger (our territory first), tiles where a defender covers a unit that cannot defend itself (lowest danger), any other
     *  (lowest danger). Ties go to the nearest tile. (The original also has a variant that ranks our territory above covered tiles
     *  but compiles it out with PATH_PLAN_LAST.)
     */
    fun safestPlot(unit: MapUnit, danger: PlotDanger): Tile? {
        val civ = unit.civ
        val canDefend = canDefend(unit)
        var bestClass = Int.MAX_VALUE
        var bestWeight = Int.MAX_VALUE
        var bestCost = Float.MAX_VALUE
        var best: Tile? = null
        val reachable = unit.movement.getDistanceToTiles()
        for ((tile, move) in reachable) {
            val cost = move.totalMovement
            if (tile == unit.getTile() || !unit.movement.canMoveTo(tile)) continue
            if (unit.baseUnit.isWaterUnit && !tile.isWater) continue
            val level = danger.get(tile)
            val inCity = tile.isCityCenter() && tile.getOwner() == civ
            val tileClass: Int
            val weight: Int
            when {
                inCity -> { tileClass = 0; weight = level }
                level <= 0 -> { tileClass = 1; weight = if (tile.getOwner() == civ) 0 else 1 }
                !canDefend && tile.militaryUnit?.civ == civ -> { tileClass = 2; weight = level }
                else -> { tileClass = 3; weight = level }
            }
            if (tileClass < bestClass || (tileClass == bestClass && (weight < bestWeight || (weight == bestWeight && cost < bestCost)))) {
                bestClass = tileClass
                bestWeight = weight
                bestCost = cost
                best = tile
            }
        }
        return best
    }

    /** CvTacticalAI::PlotMovesToSafety(true) with ExecuteMovesToSafestPlot: moves the unit if it wants to flee and ends its turn; true if it did */
    fun moveToSafety(unit: MapUnit, danger: PlotDanger): Boolean {
        if (!wantsToFlee(unit, danger)) return false
        val target = safestPlot(unit, danger) ?: return false
        AiDecisionLog.log(unit.civ) { "Tactical: ${unit.name} flees from ${unit.getTile().position} (danger ${danger.get(unit.getTile())}) to ${target.position} (danger ${danger.get(target)})" }
        unit.movement.moveToTile(target)
        unit.currentMovement = 0f
        return true
    }

    /**
     *  CvTacticalAI::PlotHealMoves / ExecuteHeals: a unit below full health that is neither embarked nor in a city, not a ship outside our
     *  territory and not under enemy ranged attack, fortifies (or just waits) to heal and ends its turn. True if it did.
     */
    fun heal(unit: MapUnit): Boolean {
        if (unit.health >= MAX_HEALTH || unit.isEmbarked() || unit.getTile().isCityCenter()) return false
        if (unit.baseUnit.isWaterUnit && !unit.getTile().isFriendlyTerritory(unit.civ)) return false
        if (isUnderEnemyRangedAttack(unit)) return false
        AiDecisionLog.log(unit.civ) { "Tactical: ${unit.name} heals at ${unit.getTile().position} (${unit.health})" }
        if (unit.canFortify()) unit.fortify()
        unit.currentMovement = 0f
        return true
    }

    /** CvUnit::GetMaxHitPoints */
    private const val MAX_HEALTH = 100

    /** The mean of the damage roll, for expected damage (the original's getCombatDamage without randomness) */
    private const val EXPECTED_RANDOMNESS = 0.5f
}

