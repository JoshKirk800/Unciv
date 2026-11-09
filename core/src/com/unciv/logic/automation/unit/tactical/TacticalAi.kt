package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.unit.AirMoves
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.UniqueType

/**
 *  CvTacticalAI::Update for one civilization's turn: the units under tactical control ([commandeerUnits]), the targets
 *  ([TacticalTargets]), then the moves of the tactical priority list (`tacticalMoves` of AI.json, [com.unciv.models.ruleset.ai.TacticalMoveDef.priority]
 *  first, in the XML order among equals): emergency purchases (once per dominance zone), the attack moves (capture and damage cities, destroy
 *  and attrition attacks on units, civilians, camps, pillaging (by paradrop too), plundering and parking on trade routes, safe bombards -
 *  [TacticalMoves], [TradeMoves], [TacticalAttacks], [TacticalBombards]),
 *  the moves of the dominance zones' postures (offensive postures, shore bombardment, hedgehog, withdrawing, closing on the target - [Postures],
 *  [Deployment]), the armies of the operations ([OperationalMoves]), moves to safety and healing, garrisons, bastions, guarding improvements,
 *  repositioning. A unit a move commands is added to [AutomationCache.processedUnits]; the other units, tactical or not, go on to the rest of
 *  the unit AI.
 *
 *  Not ported (their rows of AI.json's `tacticalMoves` have no code behind them): naval blockades.
 */
object TacticalAi {

    private const val EMERGENCY_PURCHASES = "TACTICAL_EMERGENCY_PURCHASES"
    private const val MOVE_OPERATIONS = "TACTICAL_MOVE_OPERATIONS"
    private const val POSTURE_WITHDRAW = "TACTICAL_POSTURE_WITHDRAW"
    private const val TO_SAFETY = "TACTICAL_TO_SAFETY"
    private const val HEAL = "TACTICAL_HEAL"

    /**
     *  CvTacticalAI::CommandeerUnits: the units that are the tactical AI's this turn. Not units that cannot move, are processed, have the
     *  unit AI UNKNOWN or EXPLORE (explorers are the homeland AI's, however near the enemy), are not combat units or Great Generals. Of the
     *  rest: not the ones in an army tactical may not interrupt ([com.unciv.logic.automation.strategy.CivAi.canTacticalAiInterrupt]); the
     *  ones whose tile has danger, that have a visible enemy unit or city within AI_TACTICAL_RECRUIT_RANGE, or that left an operation within
     *  AI_TACTICAL_MAP_TEMP_ZONE_TURNS turns; and, danger or not, a unit that [canParadrop]. Air units with more than half their health that
     *  will not rebase ([AirMoves.willAirUnitRebase]) are all tactical. (Barbarians have their own AI, [com.unciv.logic.automation.civilization.BarbarianAutomation].)
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
            val unitAi = UnitAiTypes.current(unit)
            if (unitAi == UnitAiType.UNKNOWN || unitAi == UnitAiType.EXPLORE) continue
            // CommandeerUnits: air units that are combat ready (more than half health) and not about to rebase
            if (unit.baseUnit.isAirUnit()) {
                if (AirMoves.isCombatReady(unit) && !AirMoves.willAirUnitRebase(unit)) result.add(unit)
                continue
            }
            if (!unit.isMilitary() && !unit.isGreatPersonOfType("War")) continue
            if (!civ.ai.canTacticalAiInterrupt(unit)) continue
            val tile = unit.getTile()
            if (danger.get(tile) > 0 || nearAny(tile, enemyTiles, recruitRange) || civ.ai.deployFromOperationTurn(unit) + tempZoneTurns >= turn
                || canParadrop(unit))
                result.add(unit)
        }
        return result
    }

    /** CvUnit::canParadrop(plot, false): the unit has a paradrop range, has not moved this turn, is not embarked and stands in friendly territory */
    fun canParadrop(unit: MapUnit): Boolean =
        unit.hasUnique(UniqueType.MayParadrop) && !unit.hasUnitMovedThisTurn() && !unit.isEmbarked() && unit.getTile().isFriendlyTerritory(unit.civ)

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
     *  The tactical turn (CvTacticalAI::Update, ProcessDominanceZones): find the targets, then run the moves of the priority list one after
     *  the other, each commanding the units it needs. [units] are the civilization's units in the order they would move.
     */
    fun automate(civ: Civilization, units: List<MapUnit>, cache: AutomationCache) = timeThis("AI.Tactical") {
        val state = civ.ai.tactical
        val tacticalUnits = commandeerUnits(civ, units, cache)
        val commandeered = tacticalUnits.size
        val map = state.map
        PostureSelection.updatePostures(civ, state, map)
        val danger = civ.ai.plotDanger
        val ctx = TacticalContext(civ, map, danger, cache, tacticalUnits, TacticalTargets.find(civ, map, danger))
        val highPriority by lazy { ctx.targets.all.filter { it.type == TacticalTargetType.HighPriorityUnit }.mapNotNullTo(HashSet()) { it.unit } }

        class Step(val name: String, val run: () -> Unit) { val span = "AI.Tactical.$name" }
        val steps = listOf(
            Step(EMERGENCY_PURCHASES) {
                if (civ.isAIOrAutoPlaying()) Postures.forEachZone(ctx, { zone, _ -> zone.dominance != TacticalDominance.Friendly && zone.territory == TacticalTerritory.Friendly }) { zone, _ ->
                    EmergencyPurchases.plot(civ, zone, map) { highPriority }
                }
            },
            Step("TACTICAL_CAPTURE_CITY") { TacticalMoves.plotCaptureCityMoves(ctx) },
            Step("TACTICAL_DESTROY_HIGH_UNIT") { TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.HighPriorityUnit, true) },
            Step("TACTICAL_ATTACK_VERY_HIGH_PRIORITY_CIVILIAN") { TacticalMoves.plotCivilianAttackMoves(ctx, TacticalTargetType.VeryHighPriorityCivilian) },
            Step("TACTICAL_PILLAGE_CITADEL") { TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Citadel, true) },
            Step("TACTICAL_DESTROY_MEDIUM_UNIT") { TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.MediumPriorityUnit, true) },
            Step("TACTICAL_DESTROY_LOW_UNIT") { TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.LowPriorityUnit, true) },
            Step(Postures.OFFENSIVE_POSTURE_MOVES) { Postures.offensivePostureMoves(ctx) },
            Step(Postures.SHORE_BOMBARDMENT) { Postures.shoreBombardmentZones(ctx) },
            Step("TACTICAL_ATTACK_HIGH_PRIORITY_CIVILIAN") { TacticalMoves.plotCivilianAttackMoves(ctx, TacticalTargetType.HighPriorityCivilian) },
            Step(POSTURE_WITHDRAW) {
                // CvTacticalAI::PlotWithdrawMoves: land zones
                Postures.forEachZone(ctx, { zone, posture -> posture == TacticalPosture.Withdraw && !zone.isWater }) { zone, _ -> withdrawMoves(ctx, zone) }
            },
            Step(MOVE_OPERATIONS) { OperationalMoves.plotOperationalArmyMoves(ctx) },
            Step("TACTICAL_SAFE_BOMBARDS") { TacticalMoves.plotSafeBombardMoves(ctx) },
            Step(Postures.HEDGEHOG) { Postures.hedgehogZones(ctx) },
            Step(Postures.CLOSE_ON_TARGET) { Postures.closeOnTargetZones(ctx) },
            Step("TACTICAL_PILLAGE") { TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Improvement, true) },
            Step("TACTICAL_ATTACK_MEDIUM_PRIORITY_CIVILIAN") { TacticalMoves.plotCivilianAttackMoves(ctx, TacticalTargetType.MediumPriorityCivilian) },
            Step("TACTICAL_PILLAGE_RESOURCE") { TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.ImprovementResource, true) },
            Step("TACTICAL_ATTACK_LOW_PRIORITY_CIVILIAN") { TacticalMoves.plotCivilianAttackMoves(ctx, TacticalTargetType.LowPriorityCivilian) },
            Step("TACTICAL_ANCIENT_RUINS") { TacticalMoves.plotAncientRuinMoves(ctx, 1) },
            Step("TACTICAL_PILLAGE_CITADEL_NEXT_TURN") { TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Citadel, false) },
            Step("TACTICAL_GARRISON_TO_ALLOW_BOMBARD") { TacticalMoves.plotGarrisonMoves(ctx, 1, true) },
            Step("TACTICAL_ATTRIT_HIGH_UNIT") { TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.HighPriorityUnit, false) },
            Step("TACTICAL_DAMAGE_CITY") { TacticalMoves.plotDamageCityMoves(ctx) },
            Step("TACTICAL_ATTRIT_MEDIUM_UNIT") { TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.MediumPriorityUnit, false) },
            Step("TACTICAL_PILLAGE_RESOURCE_NEXT_TURN") { TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.ImprovementResource, false) },
            Step("TACTICAL_ATTRIT_LOW_UNIT") { TacticalMoves.plotDestroyUnitMoves(ctx, TacticalTargetType.LowPriorityUnit, false) },
            Step(TO_SAFETY) {
                for (unit in ctx.liveUnits()) if (unit.hasMovement() && TacticalSafety.moveToSafety(unit, danger)) ctx.unitProcessed(unit)
            },
            Step("TACTICAL_BARBARIAN_CAMP") { TacticalMoves.plotBarbarianCampMoves(ctx) },
            Step("TACTICAL_PILLAGE_NEXT_TURN") { TacticalMoves.plotPillageMoves(ctx, TacticalTargetType.Improvement, false) },
            Step(HEAL) {
                for (unit in ctx.liveUnits()) if (unit.hasMovement() && TacticalSafety.heal(unit)) ctx.unitProcessed(unit)
            },
            Step("TACTICAL_BASTION_ALREADY_THERE") { TacticalMoves.plotBastionMoves(ctx, 0) },
            Step("TACTICAL_GARRISON_ALREADY_THERE") { TacticalMoves.plotGarrisonMoves(ctx, 0) },
            Step("TACTICAL_BASTION_1_TURN") { TacticalMoves.plotBastionMoves(ctx, 1) },
            Step("TACTICAL_GARRISON_1_TURN") { TacticalMoves.plotGarrisonMoves(ctx, 1) },
            Step("TACTICAL_GUARD_IMPROVEMENT_ALREADY_THERE") { TacticalMoves.plotGuardImprovementMoves(ctx, 0) },
            Step("TACTICAL_GUARD_IMPROVEMENT_1_TURN") { TacticalMoves.plotGuardImprovementMoves(ctx, 1) },
            Step("TACTICAL_REPOSITION") { TacticalMoves.plotRepositionMoves(ctx) },
            Step("TACTICAL_AIR_SWEEP") { AirMoves.plotAirSweepMoves(ctx) },
            Step("TACTICAL_AIR_INTERCEPT") { AirMoves.plotAirInterceptMoves(ctx) },
            Step("TACTICAL_ESCORT_EMBARKED_UNIT") { NavalMoves.plotEscortEmbarkedMoves(ctx) },
            Step("TACTICAL_PLUNDER_TRADE_UNIT") { TradeMoves.plotPlunderTradeUnitMoves(ctx) },
            Step("TACTICAL_PARK_ON_TRADE_ROUTE") { TradeMoves.plotParkOnTradeRouteMoves(ctx) },
        )
        // CvTacticalAI::EstablishTacticalPriorities: every move with a priority of 0 or more, the highest first, stable in XML order
        val stepByName = steps.associateBy { it.name }
        for (move in civ.gameInfo.ruleset.ai.tacticalMoves.filter { it.priority >= 0 }.sortedByDescending { it.priority }) {
            // A posture may have turned a move off for the rest of the turn (CvTacticalAI::TurnOffMove)
            if (move.name in ctx.disabledMoves) continue
            ctx.currentTargets = ctx.targets.all
            stepByName[move.name]?.let { step -> timeThis(step.span) { step.run() } }
        }
        AiDecisionLog.log(civ) {
            "Tactical: $commandeered units commandeered, ${commandeered - ctx.units.size} commanded, ${ctx.targets.all.size} targets; postures " +
                state.postures.filter { it.posture != TacticalPosture.None }.joinToString { "${it.posture}${if (it.isWater) " (sea)" else ""}" }.ifEmpty { "-" }
        }
    }

    /**
     *  CvTacticalAI::PlotWithdrawMoves / ExecuteWithdrawMoves for one zone: units of the right domain that stand in the zone, or within the
     *  tactical recruit range of its city on a tile that is in danger and not ours, or (in unclaimed land) in danger outside our land,
     *  fall back to the nearest of our cities on their landmass, the nearest to it first, onto an empty tile next to the city. Only land zones.
     *  @return the units that moved
     */
    fun withdrawMoves(ctx: TacticalContext, zone: DominanceZone): List<MapUnit> {
        val civ = ctx.civ
        val map = ctx.map
        val danger = ctx.danger
        val recruitRange = civ.gameInfo.ruleset.ai.define(AiDefine.AI_TACTICAL_RECRUIT_RANGE)
        val withdrawing = ArrayList<MapUnit>()
        for (unit in ctx.liveUnits()) {
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
            val target = TacticalMoves.emptyTileNextTo(unit, withdrawal.city) ?: continue
            AiDecisionLog.log(civ) { "Tactical: ${unit.name} withdraws from zone ${zone.id} (${zone.closestCity?.name}) toward ${target.position}" }
            unit.movement.tryHeadTowards(target)
            unit.currentMovement = 0f
            ctx.unitProcessed(unit)
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
}
