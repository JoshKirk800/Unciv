package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.unit.PlotDanger
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine

/**
 *  CvTacticalAI::Update for one civilization's turn: the units under tactical control ([commandeerUnits]), then the moves of the tactical
 *  priority list (`tacticalMoves` of AI.json, [com.unciv.models.ruleset.ai.TacticalMoveDef.priority] first) that this port has: emergency
 *  purchases (once per dominance zone), withdrawing from zones the enemy dominates, moves to safety and healing. A unit a move commands is
 *  added to [AutomationCache.processedUnits]; the other units, tactical or not, go on to the rest of the unit AI.
 *
 *  Not ported yet (their rows of AI.json's `tacticalMoves` have no code behind them): the attack moves, hedgehog/close-on-target/posture moves
 *  and operation moves; [attackStage] stands in for the attack moves with the unit AI's own attack.
 */
object TacticalAi {

    private const val EMERGENCY_PURCHASES = "TACTICAL_EMERGENCY_PURCHASES"
    private const val POSTURE_WITHDRAW = "TACTICAL_POSTURE_WITHDRAW"
    private const val TO_SAFETY = "TACTICAL_TO_SAFETY"
    private const val HEAL = "TACTICAL_HEAL"
    /** The attack moves of the original (priorities 105 to 150) all run before WITHDRAW: [attackStage] takes the place of the last of them */
    private const val ATTACK_STAGE = "TACTICAL_OFFENSIVE_POSTURE_MOVES"

    /**
     *  CvTacticalAI::CommandeerUnits: the units that are the tactical AI's this turn. Not units that cannot move, are processed or have no
     *  unit AI, or are not combat units or Great Generals. Of those: not the ones in an army tactical may not interrupt
     *  ([com.unciv.logic.automation.strategy.CivAi.canTacticalAiInterrupt]); the ones whose tile has danger, that have a visible enemy unit or
     *  city within AI_TACTICAL_RECRUIT_RANGE, or that left an operation within AI_TACTICAL_MAP_TEMP_ZONE_TURNS turns. (Barbarians have their own AI, air
     *  units and paratroopers are not handled here.)
     */
    fun commandeerUnits(civ: Civilization, units: List<MapUnit>, cache: AutomationCache): List<MapUnit> {
        val rules = civ.gameInfo.ruleset.ai
        val recruitRange = rules.define(AiDefine.AI_TACTICAL_RECRUIT_RANGE)
        val tempZoneTurns = rules.define(AiDefine.AI_TACTICAL_MAP_TEMP_ZONE_TURNS)
        val turn = civ.gameInfo.turns
        val danger = civ.ai.plotDanger
        val enemyTiles by lazy { visibleEnemyTiles(civ) }
        val result = ArrayList<MapUnit>()
        for (unit in units) {
            if (unit.isDestroyed || unit.civ != civ || unit in cache.processedUnits || !unit.hasMovement()) continue
            if (unit.baseUnit.isAirUnit()) continue
            if (!unit.isMilitary() && !unit.isGreatPersonOfType("War")) continue
            if (!civ.ai.canTacticalAiInterrupt(unit)) continue
            val tile = unit.getTile()
            if (danger.get(tile) > 0 || nearAny(tile, enemyTiles, recruitRange) || civ.ai.deployFromOperationTurn(unit) + tempZoneTurns >= turn)
                result.add(unit)
        }
        return result
    }

    /** The tiles of the visible units and cities of players at war with [civ] (CvTacticalAI::NearVisibleEnemy's candidates) */
    fun visibleEnemyTiles(civ: Civilization): List<Tile> {
        val tiles = ArrayList<Tile>()
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isAlive() || !civ.isAtWarWith(other)) continue
            for (unit in other.units.getCivUnits()) if (unit.isVisibleTo(civ)) tiles.add(unit.getTile())
            for (city in other.cities) if (city.getCenterTile() in civ.viewableTiles) tiles.add(city.getCenterTile())
        }
        return tiles
    }

    /** CvTacticalAI::NearVisibleEnemy: a visible enemy unit or city within [range] tiles of the unit */
    fun nearVisibleEnemy(unit: MapUnit, range: Int): Boolean = nearAny(unit.getTile(), visibleEnemyTiles(unit.civ), range)

    private fun nearAny(tile: Tile, others: List<Tile>, range: Int): Boolean = others.any { it.aerialDistanceTo(tile) <= range }

    /**
     *  The tactical turn. [attackStage] is called with each commandeered unit when the attack moves would run, and returns whether the unit's
     *  turn is over (CvTacticalAI's attacks are not ported: this is where the unit AI's own attack plugs in).
     */
    fun automate(civ: Civilization, units: List<MapUnit>, cache: AutomationCache, attackStage: (MapUnit) -> Boolean) = timeThis("AI.Tactical") {
        val state = civ.ai.tactical
        val tacticalUnits = commandeerUnits(civ, units, cache).toMutableList()
        val map = state.map
        PostureSelection.updatePostures(civ, state, map)
        val danger = civ.ai.plotDanger
        val highPriority by lazy { CityAttackers.highPriorityUnits(civ) }

        fun finished(unit: MapUnit) {
            cache.processedUnits.add(unit)
            tacticalUnits.remove(unit)
        }

        class Step(val name: String, val run: () -> Unit)
        val steps = listOf(
            Step(EMERGENCY_PURCHASES) {
                if (civ.isAIOrAutoPlaying()) for (zone in map.zones)
                    if (zone.dominance != TacticalDominance.Friendly && zone.territory == TacticalTerritory.Friendly && PostureSelection.useThisZone(civ, state, zone))
                        EmergencyPurchases.plot(civ, zone, map) { highPriority }
            },
            Step(ATTACK_STAGE) {
                for (unit in tacticalUnits.toList()) if (!unit.isDestroyed && unit.civ == civ && attackStage(unit)) finished(unit)
            },
            Step(POSTURE_WITHDRAW) {
                for (zone in map.zones) {
                    val posture = state.findPosture(zone.owner?.civName, zone.isWater, zone.closestCity?.id)
                    if (posture == TacticalPosture.Withdraw && !zone.isWater && PostureSelection.useThisZone(civ, state, zone))
                        for (unit in withdrawMoves(civ, zone, tacticalUnits, map, danger)) finished(unit)
                }
            },
            Step(TO_SAFETY) {
                for (unit in tacticalUnits.toList()) if (unit.hasMovement() && TacticalSafety.moveToSafety(unit, danger)) finished(unit)
            },
            Step(HEAL) {
                for (unit in tacticalUnits.toList()) if (unit.hasMovement() && TacticalSafety.heal(unit)) finished(unit)
            },
        )
        // CvTacticalAI::EstablishTacticalPriorities: every move with a priority of 0 or more, the highest first, stable in XML order
        val priorities = civ.gameInfo.ruleset.ai.tacticalMoves.associate { it.name to it.priority }
        for (step in steps.filter { (priorities[it.name] ?: -1) >= 0 }.sortedByDescending { priorities[it.name] }) step.run()
    }

    /**
     *  CvTacticalAI::PlotWithdrawMoves / ExecuteWithdrawMoves for one zone: units of the right domain that stand in the zone, or within the
     *  tactical recruit range of its city on a tile that is in danger and not ours, or (in unclaimed land) in danger outside our land,
     *  fall back to the nearest of our cities on their landmass, the nearest to it first, onto an empty tile next to the city. Only land zones:
     *  the original runs a zone move only if the zone has targets, or is a temporary zone or enemy land, and the targets are not ported.
     *  @return the units that moved
     */
    fun withdrawMoves(civ: Civilization, zone: DominanceZone, units: List<MapUnit>, map: TacticalMap, danger: PlotDanger): List<MapUnit> {
        val recruitRange = civ.gameInfo.ruleset.ai.define(AiDefine.AI_TACTICAL_RECRUIT_RANGE)
        val withdrawing = ArrayList<MapUnit>()
        for (unit in units) {
            if (zone.isWater && !unit.baseUnit.isWaterUnit || !zone.isWater && !unit.baseUnit.isLandUnit) continue
            val tile = unit.getTile()
            val city = zone.closestCity
            val inZone = map.zoneOf(tile)?.id == zone.id
            val retreat = when {
                inZone -> true
                city != null -> city.getCenterTile().aerialDistanceTo(tile) <= recruitRange && danger.get(tile) > 0 && tile.getOwner() != civ
                zone.territory == TacticalTerritory.NoOwner -> danger.get(tile) > 0 && tile.getOwner() != civ
                else -> false
            }
            if (retreat) withdrawing.add(unit)
        }
        if (withdrawing.isEmpty()) return emptyList()

        class Withdrawal(val unit: MapUnit, val city: Tile, val turns: Int)
        val plan = ArrayList<Withdrawal>()
        for (unit in withdrawing) {
            val city = nearestCityInArea(civ, unit, map) ?: continue
            val path = unit.movement.getShortestPath(city)
            plan.add(Withdrawal(unit, city, if (path.isEmpty()) Int.MAX_VALUE else path.size))
        }
        val moved = ArrayList<MapUnit>()
        for (withdrawal in plan.sortedBy { it.turns }) {
            val unit = withdrawal.unit
            val target = emptyTileNextTo(unit, withdrawal.city) ?: continue
            AiDecisionLog.log(civ) { "Tactical: ${unit.name} withdraws from zone ${zone.id} (${zone.closestCity?.name}) toward ${target.position}" }
            unit.movement.headTowards(target)
            unit.currentMovement = 0f
            moved.add(unit)
        }
        return moved
    }

    /** CvMap::findCity(x, y, owner, NO_TEAM, bSameArea): the nearest of our cities on the same landmass or sea as the unit */
    private fun nearestCityInArea(civ: Civilization, unit: MapUnit, map: TacticalMap): Tile? {
        val tile = unit.getTile()
        val area = map.areaOf(tile)
        var best: Tile? = null
        var bestDistance = Int.MAX_VALUE
        for (city in civ.cities) {
            val cityTile = city.getCenterTile()
            if (map.areaOf(cityTile) != area) continue
            val distance = cityTile.aerialDistanceTo(tile)
            if (distance < bestDistance) {
                bestDistance = distance
                best = cityTile
            }
        }
        return best
    }

    /** CvTacticalAI::MoveToEmptySpaceNearTarget: the first tile next to [target], on the unit's domain, with no unit and no city but our own, that it can reach */
    private fun emptyTileNextTo(unit: MapUnit, target: Tile): Tile? {
        val land = !unit.baseUnit.isWaterUnit
        for (neighbor in target.neighbors) {
            if (neighbor.isWater == land) continue
            if (neighbor.militaryUnit != null) continue
            if (neighbor.isCityCenter() && neighbor.getOwner() != unit.civ) continue
            if (unit.movement.canReach(neighbor)) return neighbor
        }
        return null
    }
}
