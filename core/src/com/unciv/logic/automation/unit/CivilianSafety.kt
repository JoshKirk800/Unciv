package com.unciv.logic.automation.unit

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.unit.tactical.TacticalSafety
import com.unciv.logic.battle.CityCombatant
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile

/**
 *  How civilians get out of harm's way, by plot danger ([PlotDanger]): CvHomelandAI::PlotMovesToSafety / ExecuteMovesToSafestPlot for
 *  units that cannot defend themselves, and CvHomelandAI::MoveCivilianToSafety, the fallback of workers and Great People.
 *  Combat units' counterpart is [TacticalSafety].
 */
object CivilianSafety {

    /** CvHomelandAI::ExecuteMovesToSafestPlot's class of a tile: higher is better (city, safe own, safe, covered, own, any) */
    private const val CITY = 5
    private const val SAFE_OWN = 4
    private const val SAFE = 3
    private const val COVERED = 2
    private const val OWN = 1
    private const val ANY = 0

    /** GC.getMAX_CITY_DEFENSE_DAMAGE: a city's damage at which its defense is gone (100 in the original's defines) */
    private const val MAX_CITY_DEFENSE_DAMAGE = 100

    /**
     *  CvHomelandAI::PlotMovesToSafety for a unit that cannot defend itself: when the danger of its tile is above 0 it moves to the best
     *  tile it can reach this turn ([safestPlot]) and its turn ends. False if it is not in danger or has nowhere better to go.
     */
    fun fleeIfThreatened(unit: MapUnit): Boolean {
        if (TacticalSafety.canDefend(unit)) return false
        val danger = unit.civ.ai.plotDanger
        val tile = unit.getTile()
        if (danger.get(tile) <= 0) return false
        val target = safestPlot(unit, danger)
        AiDecisionLog.log(unit.civ) { "Civilian: ${unit.name} in danger ${danger.get(tile)} at ${tile.position} goes to ${target?.position} (danger ${target?.let { danger.get(it) }})" }
        if (target != null && target != tile) unit.movement.moveToTile(target)
        unit.currentMovement = 0f
        return true
    }

    /**
     *  CvHomelandAI::ExecuteMovesToSafestPlot: among the tiles the unit can reach this turn (and the one it stands on) the best by class -
     *  our city, a tile without danger in our territory, a tile without danger, a tile where one of our defenders covers it, a tile in our
     *  territory, any - and within a class the lowest danger; the nearest on ties.
     */
    fun safestPlot(unit: MapUnit, danger: PlotDanger): Tile? {
        val civ = unit.civ
        val current = unit.getTile()
        var best: Tile? = null
        var bestClass = -1
        var bestDanger = Int.MAX_VALUE
        var bestCost = Float.MAX_VALUE
        val candidates = unit.movement.getDistanceToTiles()
        fun consider(tile: Tile, cost: Float) {
            val level = danger.get(tile)
            val own = tile.getOwner() == civ
            val tileClass = when {
                tile.isCityCenter() && own -> CITY
                level <= 0 -> if (own) SAFE_OWN else SAFE
                tile.militaryUnit?.civ == civ -> COVERED
                own -> OWN
                else -> ANY
            }
            if (tileClass > bestClass || (tileClass == bestClass && (level < bestDanger || (level == bestDanger && cost < bestCost)))) {
                best = tile
                bestClass = tileClass
                bestDanger = level
                bestCost = cost
            }
        }
        consider(current, 0f)
        for ((tile, cost) in candidates) if (tile != current && unit.movement.canMoveTo(tile)) consider(tile, cost.totalMovement)
        return best
    }

    /**
     *  CvHomelandAI::MoveCivilianToSafety: the best tile [unit] can reach this turn that holds no visible enemy unit: +1 in our territory;
     *  our city counts its strength x (MAX_CITY_DEFENSE_DAMAGE - damage), else (unless [ignoreUnits]) our defender that is fortified or has no
     *  movement left counts its defense strength x health (x100 as the original's strengths); minus the danger of the tile. Staying put
     *  counts. False if no tile qualifies. (The original's defense strength has the unit's modifiers; here it is the unit type's.)
     */
    fun moveToSafety(unit: MapUnit, ignoreUnits: Boolean = false): Boolean {
        val civ = unit.civ
        val danger = civ.ai.plotDanger
        val current = unit.getTile()
        var bestTile: Tile? = null
        var bestValue = Int.MIN_VALUE
        fun consider(tile: Tile) {
            if (SettleAreas.isVisibleEnemyUnit(civ, tile)) return
            var value = 0
            if (tile.getOwner() == civ) value += 1
            val city = tile.getCity()
            if (tile.isCityCenter() && city != null && city.civ == civ) {
                value += CityCombatant(city).getCityStrength() * 100 * (MAX_CITY_DEFENSE_DAMAGE - (city.getMaxHealth() - city.health))
            } else if (!ignoreUnits) {
                val defender = tile.militaryUnit
                if (defender != null && defender.civ == civ && defender != unit && TacticalSafety.canDefend(defender)
                    && (defender.isFortified() || !defender.hasMovement()))
                    value += defender.baseUnit.strength * 100 * defender.health
            }
            value -= danger.get(tile)
            if (value > bestValue) {
                bestValue = value
                bestTile = tile
            }
        }
        consider(current)
        for (tile in unit.movement.getDistanceToTiles().keys) if (tile != current && unit.movement.canMoveTo(tile)) consider(tile)
        val target = bestTile ?: return false
        if (target != current) unit.movement.moveToTile(target)
        return true
    }
}
