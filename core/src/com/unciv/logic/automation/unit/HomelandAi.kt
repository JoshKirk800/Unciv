package com.unciv.logic.automation.unit

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.strategy.AiOperationLogic
import com.unciv.logic.automation.strategy.ExplorationAi
import com.unciv.logic.automation.strategy.ExplorationPlots
import com.unciv.logic.automation.strategy.ReconState
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.unit.tactical.TacticalSafety
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionsExoticGoods
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.view.MapUnitView
import kotlin.math.max

/** CvHomelandAI's AIHomelandMove subset that moves military units, in the original's enum order (the tie-break of the priority sort) */
enum class HomelandMove(val priority: AiDefine) {
    Explore(AiDefine.AI_HOMELAND_MOVE_PRIORITY_EXPLORE),
    ExploreSea(AiDefine.AI_HOMELAND_MOVE_PRIORITY_EXPLORE_SEA),
    Garrison(AiDefine.AI_HOMELAND_MOVE_PRIORITY_GARRISON),
    Heal(AiDefine.AI_HOMELAND_MOVE_PRIORITY_HEAL),
    ToSafety(AiDefine.AI_HOMELAND_MOVE_PRIORITY_TO_SAFETY),
    MobileReserve(AiDefine.AI_HOMELAND_MOVE_PRIORITY_MOBILE_RESERVE),
    Sentry(AiDefine.AI_HOMELAND_MOVE_PRIORITY_SENTRY),
    Patrol(AiDefine.AI_HOMELAND_MOVE_PRIORITY_PATROL),
    Upgrade(AiDefine.AI_HOMELAND_MOVE_PRIORITY_UPGRADE),
    AncientRuins(AiDefine.AI_HOMELAND_MOVE_PRIORITY_ANCIENT_RUINS),
    GarrisonCityState(AiDefine.AI_HOMELAND_MOVE_PRIORITY_GARRISON_CITY_STATE),
}

/** CvHomelandMove: a move and its priority this turn */
class HomelandMovePriority(val move: HomelandMove, val priority: Int)

enum class HomelandTargetType { City, SentryPoint, HomeRoad, AncientRuin }

/** CvHomelandTarget: [weight] is the city threat of a city to garrison and the weight of a sentry point (CvHomelandTarget::GetAuxIntData) */
class HomelandTarget(val type: HomelandTargetType, val tile: Tile, val weight: Int = 0, val city: City? = null)

/** What CvHomelandAI::FindHomelandTargets finds: cities to garrison (threat first), sentry points (weight first), road tiles, goody huts and enemy civilians */
class HomelandTargets(
    val cities: List<HomelandTarget>,
    val sentryPoints: List<HomelandTarget>,
    val roads: List<HomelandTarget>,
    val ruins: List<HomelandTarget>,
)

/**
 *  CvHomelandAI, the military moves: the units the tactical AI and the operations did not take ([AutomationCache.processedUnits]) go
 *  through the priority list of [priorities] (EstablishHomelandPriorities), each move taking the units that suit it to the targets of
 *  [findTargets] (FindHomelandTargets): [HomelandMove.Explore]/[HomelandMove.ExploreSea] (PlotExplorerMoves, ExecuteExplorerMoves with
 *  [ExplorationAi]), [HomelandMove.Garrison] and the city-state variant (PlotGarrisonMoves), [HomelandMove.Heal] and [HomelandMove.ToSafety]
 *  (the moves of [TacticalSafety], which the tactical AI shares), [HomelandMove.MobileReserve], [HomelandMove.Sentry],
 *  [HomelandMove.AncientRuins], [HomelandMove.Upgrade] (the movement part of PlotUpgradeMoves; the gold part is
 *  [UseGoldAutomation.plotUpgradeMoves]) and [HomelandMove.Patrol]; units no move took are skipped (ReviewUnassignedUnits).
 *  The civilian moves (settlers, workers, Great People, trade, religion, archaeologists, spaceship parts) and aircraft are not here:
 *  those units have their own automation, which runs outside the military pass.
 *
 *  Differences from the original, and why:
 *  - A unit that stands on the target counts as able to enter it. The original's CvUnit::canMoveInto fails for the unit's own plot,
 *    which would make GetClosestUnitByTurnsToTarget's "distance 0" test and ExecuteMoveToTarget's fortify-on-arrival unreachable: the
 *    incumbent garrison would never be chosen and a city holding one could not be garrisoned by anybody else (the stacking limit).
 *  - The list of units for a move is built on the first target that is tried, not on target 0 (the original skips building it when
 *    target 0 is a city the tactical AI already garrisoned, and then filters lists left over from the previous move).
 *  - A multi-turn move is the unit's `moveTo` order (MISSION_MOVE_TO keeps going), carried out when its next turn starts, so a unit on its
 *    way is not asked again; one that arrives fortifies at once.
 *  - A tile under the tactical AI's control is a unit of ours on it that is in [AutomationCache.processedUnits] when targets are found
 *    (CvUnit::isUnderTacticalControl). Pathfinder turns come from the unit's path ([AiOperationLogic.turnsToReach]).
 *  - Naval resources (targets of the work boats) and the antiquity sites (the archaeologists') are not homeland targets here, but their
 *    plots stay out of the later target kinds of the else-if chain, as in the original.
 */
object HomelandAi {

    // The CvHomelandAI.cpp constants
    private const val SENTRY_CITY_SEARCH_RADIUS = 5
    private const val MAX_FAILED_PATHS = 2
    private const val EARLY_OUT_FAILED_PATH_DISTANCE = 12
    private const val PATROL_RANDOM_RANGE = 10000
    private const val PATROL_OWN_TERRITORY_BONUS = 10000
    private const val SENTRY_WEIGHT_PER_OUTSIDE_PLOT = 100
    private const val SENTRY_CITY_THREAT_DIVISOR = 50

    /**
     *  CvHomelandAI::EstablishHomelandPriorities for the moves this port runs: each move's base priority (AI_HOMELAND_MOVE_PRIORITY_*; the
     *  garrison just above the sentry under a policy that encourages garrisons) plus a flavor: DEFENSE for the garrison, heal, to safety,
     *  mobile reserve and sentry moves, RECON for exploring and for the goody huts, MILITARY_TRAINING for upgrades; DEFENSE and RECON
     *  scaled by AI_TACTICAL_FLAVOR_DAMPENING_FOR_MOVE_PRIORITIZATION and truncated. Highest first, equal ones in the original's enum order.
     */
    fun priorities(civ: Civilization): List<HomelandMovePriority> {
        val rules = civ.gameInfo.ruleset.ai
        val dampening = rules.defineFloat(AiDefine.AI_TACTICAL_FLAVOR_DAMPENING_FOR_MOVE_PRIORITIZATION).toDouble()
        val flavorDefense = (civ.ai.flavor(Flavor.Defense) * dampening).toInt()
        val flavorExplore = (civ.ai.flavor(Flavor.Recon) * dampening).toInt()
        val flavorMilitaryTraining = civ.ai.flavor(Flavor.MilitaryTraining)
        val encouragesGarrisons by lazy { hasPolicyEncouragingGarrisons(civ) }
        val result = ArrayList<HomelandMovePriority>()
        for (move in HomelandMove.entries) {
            var priority = if (move == HomelandMove.Garrison && encouragesGarrisons) rules.define(AiDefine.AI_HOMELAND_MOVE_PRIORITY_SENTRY) + 1
            else rules.define(move.priority)
            if (priority < 0) continue
            priority += when (move) {
                HomelandMove.Garrison, HomelandMove.Heal, HomelandMove.ToSafety, HomelandMove.MobileReserve, HomelandMove.Sentry,
                HomelandMove.GarrisonCityState -> flavorDefense
                HomelandMove.Explore, HomelandMove.ExploreSea, HomelandMove.AncientRuins -> flavorExplore
                HomelandMove.Upgrade -> flavorMilitaryTraining
                HomelandMove.Patrol -> 0
            }
            result.add(HomelandMovePriority(move, priority))
        }
        return result.sortedByDescending { it.priority } // stable, as std::stable_sort
    }

    /**
     *  CvPlayerPolicies::HasPolicyEncouragingGarrisons: an adopted policy that does something for garrisoned cities (Unciv writes the
     *  Brave New World ones, Oligarchy and Military Caste, as effects `with a garrison`)
     */
    fun hasPolicyEncouragingGarrisons(civ: Civilization): Boolean {
        val policies = civ.gameInfo.ruleset.policies
        return civ.policies.getAdoptedPolicies().any { name ->
            policies[name]?.uniqueObjects?.any { it.hasModifier(UniqueType.ConditionalWhenGarrisoned) } == true
        }
    }

    /**
     *  The missions go on at the start of the turn: a unit with a `moveTo` order (the multi-turn moves of the homeland AI) carries it on
     *  before any AI decides anything, and gives it up when an enemy is near (what [MapUnit.doAction] does for it).
     */
    fun continueMissions(units: Collection<MapUnit>) {
        for (unit in units) if (!unit.isDestroyed && unit.isMoving() && unit.hasMovement()) unit.doAction()
    }

    /**
     *  CvHomelandAI::RecruitUnits for military units: the units of [civ] that can still move, are not processed, have a unit AI and are
     *  military land or sea units (air units, civilians, Great People and the military units that work as civilians in peace have their
     *  own automation).
     */
    fun recruitUnits(civ: Civilization, units: Collection<MapUnit>, cache: AutomationCache): List<MapUnit> {
        val atWar = civ.isAtWar()
        return units.filter { unit ->
            !unit.isDestroyed && unit.civ == civ && unit !in cache.processedUnits && unit.hasMovement() && unit.isMilitary()
                && !unit.baseUnit.isAirUnit() && !unit.isEscorting() && UnitAiTypes.current(unit) != UnitAiType.UNKNOWN
                && !(!atWar && hasCivilianAbilities(unit))
        }
    }

    /** The military units with civilian abilities behave as civilians in peace (UnitAutomation's rule) */
    private fun hasCivilianAbilities(unit: MapUnit) = unit.hasUnique(UniqueType.BuildImprovements) || unit.hasUnique(UniqueType.FoundCity) ||
        unit.hasUnique(UniqueType.FoundPuppetCity) || unit.hasUnique(UniqueType.ReligiousUnit) || unit.hasUnique(UniqueType.CreateWaterImprovements)

    /**
     *  CvHomelandAI::Update for the military units among [units]: [recruitUnits], then the move priority list.
     *  [canUpgrade] lets the upgrade move run (not for forced automation, which changes no state).
     */
    fun doTurn(civ: Civilization, units: Collection<MapUnit>, cache: AutomationCache, canUpgrade: Boolean = true) = timeThis("AI.Homeland") {
        val recruited = recruitUnits(civ, units, cache)
        if (recruited.isEmpty()) return@timeThis
        HomelandTurn(civ, cache, canUpgrade).run(recruited)
    }

    /** The homeland moves for a single unit of an automated human unit (the other units of the civilization are not considered) */
    fun automateUnit(unitView: MapUnitView, cache: AutomationCache) {
        val unit = unitView.getUnit()
        doTurn(unit.civ, listOf(unit), cache, canUpgrade = false)
    }

    /**
     *  What a unit with the Explore order does ([UnitAutomation.automatedExplore]): one pass of CvHomelandAI::ExecuteExplorerMoves for it.
     *  @return whether it went somewhere (false: nothing left to explore)
     */
    fun exploreUnit(unit: MapUnit, cache: AutomationCache): Boolean {
        val turn = HomelandTurn(unit.civ, cache, canUpgrade = false)
        turn.currentUnits.add(unit)
        turn.currentSet.add(unit)
        val before = unit.getTile()
        val ordered = turn.executeExplorerMoves(listOf(unit), disbandWhenStuck = false)
        return ordered || unit.getTile() != before
    }

    /**
     *  CvHomelandAI::FindHomelandTargets over the tiles [civ] sees, each tile the first of the original's else-if chain that fits: a city
     *  of ours with no unit under tactical control (target weight: its threat value); a city of another owner or water without an
     *  improvement (nothing here); a goody hut (not for city-states) or antiquity site or a visible enemy civilian / embarked unit (ruin target);
     *  a land tile with no unit, or only our defenders, in or next to our land and unowned: a sentry point if land around it is not ours
     *  ([sentryWeight]); a road in our territory (a mobile reserve muster point; the else-if chain only gets there for tiles with
     *  units that are not our defenders). Sentry points are sorted by weight and thinned so that no two are adjacent (a stronger one
     *  first), the roads thinned, the cities sorted by threat.
     */
    fun findTargets(civ: Civilization, cache: AutomationCache): HomelandTargets = timeThis("AI.Homeland.targets") {
        val danger = civ.ai.plotDanger
        val map = civ.ai.tactical.map
        val ruleset = civ.gameInfo.ruleset
        val constants = ruleset.modOptions.constants
        val cities = ArrayList<HomelandTarget>()
        val sentries = ArrayList<HomelandTarget>()
        val roads = ArrayList<HomelandTarget>()
        val ruins = ArrayList<HomelandTarget>()
        val visible = civ.viewableTiles
        for (tile in civ.gameInfo.tileMap.tileList) {
            if (tile !in visible) continue
            if (tile.isCityCenter()) {
                val city = tile.getCity() ?: continue
                if (city.civ == civ) {
                    val defender = tile.militaryUnit
                    if (defender == null || defender.civ != civ || defender !in cache.processedUnits)
                        cities.add(HomelandTarget(HomelandTargetType.City, tile, danger.cityThreat(city), city))
                }
            } else if (tile.isWater && tile.improvement == null) {
                // A naval resource: the work boats' target
            } else if (!civ.isCityState && !civ.isBarbarian && ruleset.tileImprovements[tile.improvement]?.isAncientRuinsEquivalent() == true) {
                ruins.add(HomelandTarget(HomelandTargetType.AncientRuin, tile))
            } else if (isAntiquitySite(civ, tile, constants.archaeologySiteResource, constants.archaeologyHiddenSiteResource)) {
                // The archaeologists' target
            } else if (isVisibleOtherUnit(civ, tile)) {
                val first = tile.militaryUnit ?: tile.civilianUnit ?: tile.embarkedUnit
                if (first != null && !first.isDestroyed && civ.isAtWarWith(first.civ) && !TacticalSafety.canDefend(first))
                    ruins.add(HomelandTarget(HomelandTargetType.AncientRuin, tile))
            } else if (!tile.isWater && (!hasUnit(tile) || numDefenders(civ, tile) > 0)) {
                val owner = tile.getOwner()
                if (owner == civ || (owner == null && isAdjacentToOurLand(civ, tile))) {
                    val outsidePlots = outsideLandPlots(civ, tile)
                    if (outsidePlots > 0) sentries.add(HomelandTarget(HomelandTargetType.SentryPoint, tile, sentryWeight(civ, tile, outsidePlots)))
                }
            } else if (tile.getOwner() == civ && tile.getUnpillagedRoad() != RoadStatus.None) {
                roads.add(HomelandTarget(HomelandTargetType.HomeRoad, tile))
            }
        }
        // CvHomelandAI::EliminateAdjacentSentryPoints (sorts first) / EliminateAdjacentHomelandRoads
        HomelandTargets(
            cities.sortedByDescending { it.weight },
            eliminateAdjacent(sentries.sortedByDescending { it.weight }),
            eliminateAdjacent(roads),
            ruins,
        )
    }

    /** The weight of a sentry point (the body of FindHomelandTargets): [outsidePlots] x 100 + the plot's defense modifier + its danger, plus the threat of the nearest city of ours within 5 tiles x its population / 50 (x the capital's threat weight), x2 on hills, /2 on the coast */
    fun sentryWeight(civ: Civilization, tile: Tile, outsidePlots: Int): Int {
        var weight = outsidePlots * SENTRY_WEIGHT_PER_OUTSIDE_PLOT + civ.ai.tactical.map.defenseModifier(tile) + civ.ai.plotDanger.get(tile)
        val city = closestFriendlyCity(civ, tile, SENTRY_CITY_SEARCH_RADIUS)
        if (city != null) {
            weight += civ.ai.plotDanger.cityThreat(city) * city.population.population / SENTRY_CITY_THREAT_DIVISOR
            if (city.isCapital()) weight = weight * civ.gameInfo.ruleset.ai.define(AiDefine.AI_MILITARY_CITY_THREAT_WEIGHT_CAPITAL) / 100
        }
        if (tile.isHill()) weight *= 2
        if (tile.isAdjacentToCoast()) weight /= 2
        return weight
    }

    /** CvPlayer::GetClosestFriendlyCity: the nearest city of ours within [radius] (the first of equals) */
    fun closestFriendlyCity(civ: Civilization, tile: Tile, radius: Int): City? {
        var best: City? = null
        var bestDistance = Int.MAX_VALUE
        for (city in civ.cities) {
            val distance = city.getCenterTile().aerialDistanceTo(tile)
            if (distance < bestDistance && distance <= radius) { best = city; bestDistance = distance }
        }
        return best
    }

    private fun eliminateAdjacent(sorted: List<HomelandTarget>): List<HomelandTarget> {
        val kept = ArrayList<HomelandTarget>()
        for (target in sorted)
            if (kept.none { it.tile.aerialDistanceTo(target.tile) == 1 }) kept.add(target)
        return kept
    }

    private fun hasUnit(tile: Tile) = tile.militaryUnit != null || tile.civilianUnit != null || tile.embarkedUnit != null || tile.airUnits.isNotEmpty()

    /** CvPlot::getNumDefenders: our units on the tile that can defend */
    private fun numDefenders(civ: Civilization, tile: Tile): Int =
        tile.getUnits().count { it.civ == civ && TacticalSafety.canDefend(it) }

    /** CvPlot::isVisibleOtherUnit: a unit of another player that we see */
    private fun isVisibleOtherUnit(civ: Civilization, tile: Tile): Boolean =
        tile.getUnits().any { it.civ != civ && it.isVisibleTo(civ) }

    private fun isAntiquitySite(civ: Civilization, tile: Tile, site: String, hiddenSite: String): Boolean {
        val resource = tile.resource ?: return false
        if (resource != site && resource != hiddenSite) return false
        if (!civ.canSeeResource(tile.tileResource)) return false
        val owner = tile.getOwner()
        // CvDiplomacyAI::IsPlayerMadeNoDiggingPromise
        return !(owner != null && owner != civ && owner.getDiplomacyManager(civ)?.hasFlag(DiplomacyFlags.AgreedToNotDigInOurLands) == true)
    }

    /** CvPlot::isAdjacentTeam(team, landOnly) */
    private fun isAdjacentToOurLand(civ: Civilization, tile: Tile): Boolean {
        for (neighbor in tile.neighbors) if (!neighbor.isWater && neighbor.getOwner() == civ) return true
        return false
    }

    /** CvPlot::GetNumAdjacentDifferentTeam(team, ignoreWater = true): neighbors on land that are not ours (unowned ones count) */
    private fun outsideLandPlots(civ: Civilization, tile: Tile): Int {
        var count = 0
        for (neighbor in tile.neighbors) if (!neighbor.isWater && neighbor.getOwner() != civ) count++
        return count
    }

    //region Unit properties the homeland moves look at

    /** CvUnit::getDefenseModifier / getAttackModifier / getExtraCombatPercent: a strength bonus from the unit's promotions that holds always, when attacking or when defending */
    fun hasPromotionCombatBonus(unit: MapUnit): Boolean = unit.promotions.getPromotions().any { promotion ->
        promotion.uniqueObjects.any { unique ->
            unique.type == UniqueType.Strength && unique.modifiers.all { it.type == UniqueType.ConditionalAttacking || it.type == UniqueType.ConditionalDefending }
        }
    }

    /** CvUnit::getExtraVisibilityRange: the sight the unit's promotions add */
    fun extraSight(unit: MapUnit): Int = unit.promotions.getPromotions().sumOf { promotion ->
        promotion.uniqueObjects.filter { it.type == UniqueType.Sight && it.modifiers.isEmpty() }.sumOf { it.params[0].toInt() }
    }

    /** CvUnit::noDefensiveBonus */
    fun noDefensiveBonus(unit: MapUnit) = unit.hasUnique(UniqueType.NoDefensiveTerrainBonus, checkCivInfoUniques = true)

    private fun canAttack(unit: MapUnit) = unit.isMilitary()

    //endregion

    /** The state of one homeland turn: CvHomelandAI's per-turn lists, kept by the instance */
    internal class HomelandTurn(val civ: Civilization, val cache: AutomationCache, val canUpgrade: Boolean) {
        private val rules = civ.gameInfo.ruleset.ai
        private val gameTurn = civ.gameInfo.turns
        private val defensiveMoveTurns = rules.define(AiDefine.AI_HOMELAND_MAX_DEFENSIVE_MOVE_TURNS)
        private val estimateTurnsDistance = rules.define(AiDefine.AI_HOMELAND_ESTIMATE_TURNS_DISTANCE)

        /** m_CurrentTurnUnits */
        val currentUnits = ArrayList<MapUnit>()
        val currentSet = HashSet<MapUnit>()
        private val targets by lazy { findTargets(civ, cache) }
        private val assigned = HashMap<HomelandMove, Int>()

        /** CvHomelandUnit: a unit and, once computed, the turns it needs to the current target (raw distance first) */
        private class MoveUnit(val unit: MapUnit, var movesToTarget: Int)

        private var moveUnits = ArrayList<MoveUnit>()
        private var highPriorityUnits = ArrayList<MoveUnit>()
        private var listsBuiltFor: HomelandMove? = null
        private var bestUnit: MapUnit? = null
        private var bestUnitTurns = Int.MAX_VALUE
        private var bestHighPriorityUnit: MapUnit? = null
        private var bestHighPriorityUnitTurns = Int.MAX_VALUE
        private val patrolRandom by lazy { civ.ai.random("HomelandAi.patrol") }

        private fun processed(unit: MapUnit, move: HomelandMove? = null) {
            cache.processedUnits.add(unit)
            currentSet.remove(unit)
            currentUnits.remove(unit)
            if (move != null) assigned.merge(move, 1, Int::plus)
        }

        /** CvHomelandAI::AssignHomelandMoves */
        fun run(recruited: List<MapUnit>) {
            currentUnits.addAll(recruited)
            currentSet.addAll(recruited)
            val total = recruited.size
            for (entry in priorities(civ)) {
                if (currentUnits.isEmpty()) break
                when (entry.move) {
                    HomelandMove.Explore -> plotExplorerMoves(sea = false)
                    HomelandMove.ExploreSea -> plotExplorerMoves(sea = true)
                    HomelandMove.Garrison -> plotGarrisonMoves(cityStateOnly = false)
                    HomelandMove.GarrisonCityState -> plotGarrisonMoves(cityStateOnly = true)
                    HomelandMove.Heal -> plotHealMoves()
                    HomelandMove.ToSafety -> plotMovesToSafety()
                    HomelandMove.MobileReserve -> plotTargetMoves(HomelandMove.MobileReserve, targets.roads, Int.MAX_VALUE)
                    HomelandMove.Sentry -> plotTargetMoves(HomelandMove.Sentry, targets.sentryPoints, Int.MAX_VALUE)
                    HomelandMove.AncientRuins -> plotTargetMoves(HomelandMove.AncientRuins, targets.ruins, defensiveMoveTurns)
                    HomelandMove.Patrol -> plotPatrolMoves()
                    HomelandMove.Upgrade -> plotUpgradeMoves()
                }
            }
            reviewUnassignedUnits()
            AiDecisionLog.log(civ) {
                "Homeland: $total units: " + assigned.entries.sortedBy { it.key.ordinal }.joinToString { "${it.key} ${it.value}" }.ifEmpty { "none moved" }
            }
        }

        //region Explorers

        /** CvHomelandAI::PlotExplorerMoves / PlotExplorerSeaMoves: the explorers, twice so that one that could reach the end of its sight can move again */
        private fun plotExplorerMoves(sea: Boolean) {
            val wanted = if (sea) UnitAiType.EXPLORE_SEA else UnitAiType.EXPLORE
            val explorers = currentUnits.filter {
                it.baseUnit.isWaterUnit == sea && (UnitAiTypes.current(it) == wanted || (it.isExploring()))
            }
            if (explorers.isEmpty()) return
            executeExplorerMoves(explorers, disbandWhenStuck = true)
            executeExplorerMoves(explorers.filter { it in currentSet }, disbandWhenStuck = true)
        }

        /**
         *  CvHomelandAI::ExecuteExplorerMoves. Per explorer: sell exotic goods when far from home; go to the goody hut or barbarian camp
         *  the economic AI assigned ([ExplorationPlots]); else to the best plot within its movement that it reaches this turn
         *  (ScoreExplorePlot of the plot with the unit's sight, +50 hills, +200 off shallow water for units that may embark anywhere; ships: exotic
         *  goods bonus, +200 next to land); else, as the best of the exploration plots by rating x 1000 / estimated turns, the plot where
         *  its path to one ends this turn. With no target, a land explorer that reaches none of our cities is disbanded.
         *  @return whether a unit was ordered somewhere
         */
        fun executeExplorerMoves(explorers: List<MapUnit>, disbandWhenStuck: Boolean): Boolean {
            var ordered = false
            val landExplorers = civ.units.getCivUnits().filter { UnitAiTypes.current(it) == UnitAiType.EXPLORE && it.civ == civ && !it.baseUnit.isWaterUnit && !it.isDestroyed }.toList()
            val exploration = ExplorationPlots(civ, landExplorers)
            for (unit in explorers) {
                if (unit.isDestroyed || unit in cache.processedUnits || !unit.hasMovement()) continue
                if (unit.hasUnique(UniqueType.SellExoticGoods)) UnitActionsExoticGoods.tryAutoSell(unit)

                if (goToGoody(unit, exploration)) { ordered = true; continue }

                val best = bestNearbyExplorePlot(unit) ?: bestExplorationPlotStep(unit, exploration)
                if (best != null) {
                    unit.movement.tryHeadTowards(best)
                    ordered = true
                    if (!unit.hasMovement()) processed(unit, if (unit.baseUnit.isWaterUnit) HomelandMove.ExploreSea else HomelandMove.Explore)
                } else if (disbandWhenStuck && UnitAiTypes.current(unit) == UnitAiType.EXPLORE && civ.cities.none { unit.movement.getShortestPath(it.getCenterTile()).isNotEmpty() }) {
                    // "If this is a land explorer and there is no path to a friendly city, then disband him"
                    processed(unit)
                    unit.disband()
                }
            }
            return ordered
        }

        /** The goody target of the unit: heads for it along its path and ends its turn there. @return whether it went */
        private fun goToGoody(unit: MapUnit, exploration: ExplorationPlots): Boolean {
            val goody = exploration.targetGoodyPlot(unit) ?: return false
            val ruleset = civ.gameInfo.ruleset
            val stillWorth = ruleset.tileImprovements[goody.improvement]?.isAncientRuinsEquivalent() == true ||
                (goody.isBarbarianEncampment() && goody.militaryUnit == null)
            if (!stillWorth || unit.movement.getShortestPath(goody).isEmpty()) return false
            unit.movement.tryHeadTowards(goody)
            processed(unit, if (unit.baseUnit.isWaterUnit) HomelandMove.ExploreSea else HomelandMove.Explore)
            return true
        }

        /** CvHomelandAI::IsValidExplorerEndTurnPlot */
        fun isValidExplorerEndTurnPlot(unit: MapUnit, tile: Tile): Boolean {
            if (unit.getTile() == tile || !civ.hasExplored(tile)) return false
            if (tile.getContinent() != unit.getTile().getContinent()) {
                val canEmbark = !unit.cache.cannotEmbark && civ.tech.unitsCanEmbark
                if (!canEmbark && !(unit.baseUnit.isWaterUnit && tile.isWater)) return false
            }
            // Don't let the auto-explore end its turn in a city
            if (tile.isCityCenter() && tile.getCity()?.civ != civ) return false
            return unit.movement.canMoveTo(tile)
        }

        private class Scored(val tile: Tile, val score: Int)

        private fun bestNearbyExplorePlot(unit: MapUnit): Tile? {
            val ship = unit.baseUnit.isWaterUnit
            val range = max(1, unit.currentMovement.toInt())
            val sight = unit.getVisibilityRange()
            val embarkAnywhere = !ship && civ.tech.embarkedUnitsCanEnterOcean
            val scored = ArrayList<Scored>()
            unit.getTile().forEachTileInDistance(range) { tile ->
                if (!isValidExplorerEndTurnPlot(unit, tile)) return@forEachTileInDistance
                var score = ExplorationAi.scoreExplorePlot(civ, tile, sight, ship)
                if (score <= 0) return@forEachTileInDistance
                if (!ship) {
                    if (tile.isHill()) score += 50
                    if (embarkAnywhere && !(tile.isWater && !tile.isOcean)) score += 200
                } else {
                    if (unit.hasUnique(UniqueType.SellExoticGoods) && UnitActionsExoticGoods.canSell(unit, tile)) {
                        val factor = UnitActionsExoticGoods.distanceFactor(unit, tile)
                        if (factor >= 0.75f) score += 150 else if (factor >= 0.5f) score += 75
                    }
                    if (tile.neighbors.any { !it.isWater }) score += 200
                }
                scored.add(Scored(tile, score))
            }
            for (candidate in scored.sortedByDescending { it.score }) { // highest first, equal ones in discovery order
                val path = unit.movement.getShortestPath(candidate.tile)
                if (path.isNotEmpty() && path.size <= 1) return candidate.tile
            }
            return null
        }

        /** The exploration plots by rating x 1000 / estimated turns: where the path of the best one that has a path ends this turn */
        private fun bestExplorationPlotStep(unit: MapUnit, exploration: ExplorationPlots): Tile? {
            val range = unit.currentMovement.toInt()
            if (range <= 0 || exploration.plots.isEmpty()) return null
            val here = unit.getTile()
            val scored = ArrayList<Scored>(exploration.plots.size)
            for ((index, plot) in exploration.plots.withIndex()) {
                val estimateTurns = here.aerialDistanceTo(plot) / range
                val rating = exploration.ratings[index]
                scored.add(Scored(plot, if (estimateTurns == 0) 1000 * rating else 1000 * rating / estimateTurns))
            }
            for (candidate in scored.sortedByDescending { it.score }) {
                if (!isValidExplorerEndTurnPlot(unit, candidate.tile)) continue
                val path = unit.movement.getShortestPath(candidate.tile)
                if (path.isEmpty()) continue
                val endTurn = path.first()
                if (endTurn == here) continue
                if (isValidExplorerEndTurnPlot(unit, endTurn)) return endTurn
            }
            return null
        }

        //endregion

        //region Safety and healing (shared with the tactical AI)

        private fun plotHealMoves() {
            for (unit in currentUnits.toList()) if (TacticalSafety.heal(unit)) processed(unit, HomelandMove.Heal)
        }

        private fun plotMovesToSafety() {
            val danger = civ.ai.plotDanger
            for (unit in currentUnits.toList()) if (unit.hasMovement() && TacticalSafety.moveToSafety(unit, danger)) processed(unit, HomelandMove.ToSafety)
        }

        //endregion

        //region Target moves: garrison, mobile reserve, sentry, ancient ruins

        /** CvHomelandAI::PlotGarrisonMoves: for each city without a tactical garrison, in threat order, the best unit goes there */
        private fun plotGarrisonMoves(cityStateOnly: Boolean) {
            if (cityStateOnly != civ.isCityState) return
            val move = if (cityStateOnly) HomelandMove.GarrisonCityState else HomelandMove.Garrison
            val tactical = civ.ai.tactical
            for (target in targets.cities) {
                val city = target.city ?: continue
                if (tactical.lastTurnGarrisonAssigned(city) >= gameTurn) continue
                findUnitsForThisMove(move)
                if (highPriorityUnits.size + moveUnits.size == 0) break
                if (getBestUnitToReachTarget(target.tile, defensiveMoveTurns)) {
                    executeMoveToTarget(target.tile, move)
                    tactical.setLastTurnGarrisonAssigned(city, gameTurn)
                }
            }
        }

        /** PlotMobileReserveMoves / PlotSentryMoves / PlotAncientRuinMoves: one unit per target, in the order of the target list */
        private fun plotTargetMoves(move: HomelandMove, targetList: List<HomelandTarget>, maxTurns: Int) {
            for (target in targetList) {
                findUnitsForThisMove(move)
                if (highPriorityUnits.size + moveUnits.size == 0) break
                if (getBestUnitToReachTarget(target.tile, maxTurns)) executeMoveToTarget(target.tile, move)
            }
        }

        /**
         *  CvHomelandAI::FindUnitsForThisMove: the units that suit [move], normal and high priority. The first call for a move builds the lists
         *  (not units that are not combat units, nor explorers unless recon is ENOUGH); later calls keep the units still unassigned.
         *  Garrison: ranged units that are not city bombarders (high priority), other units that can attack and have no promotion
         *  strength bonuses. Sentry: units that are not ranged and take the terrain bonus, those with extra sight first (a unit that takes no
         *  terrain bonus only with extra sight, first). Mobile reserve: ranged and fast attack units first, then any that can attack.
         *  Ancient ruins: fast attack units first, then any that can attack.
         */
        private fun findUnitsForThisMove(move: HomelandMove) {
            if (listsBuiltFor == move) {
                moveUnits.removeAll { it.unit !in currentSet }
                highPriorityUnits.removeAll { it.unit !in currentSet }
                return
            }
            listsBuiltFor = move
            moveUnits = ArrayList()
            highPriorityUnits = ArrayList()
            val reconEnough = civ.ai.economic.reconState == ReconState.Enough
            for (unit in currentUnits) {
                if (!unit.isMilitary()) continue
                if (UnitAiTypes.current(unit) == UnitAiType.EXPLORE && !reconEnough) continue
                var suitable = false
                var highPriority = false
                when (move) {
                    HomelandMove.Garrison, HomelandMove.GarrisonCityState -> {
                        if (unit.baseUnit.isRanged() && !UnitAiTypes.canHave(unit.baseUnit, UnitAiType.CITY_BOMBARD, rules)) {
                            suitable = true; highPriority = true
                        } else if (canAttack(unit) && !hasPromotionCombatBonus(unit)) suitable = true
                    }
                    HomelandMove.Sentry -> {
                        if (!unit.baseUnit.isRanged() && !noDefensiveBonus(unit)) {
                            suitable = true
                            if (extraSight(unit) > 0) highPriority = true
                        } else if (noDefensiveBonus(unit) && extraSight(unit) > 0) { suitable = true; highPriority = true }
                    }
                    HomelandMove.MobileReserve -> {
                        if (unit.baseUnit.isRanged() || UnitAiTypes.canHave(unit.baseUnit, UnitAiType.FAST_ATTACK, rules)) { suitable = true; highPriority = true }
                        else if (canAttack(unit)) suitable = true
                    }
                    HomelandMove.AncientRuins -> {
                        if (UnitAiTypes.canHave(unit.baseUnit, UnitAiType.FAST_ATTACK, rules)) { suitable = true; highPriority = true }
                        else if (canAttack(unit)) suitable = true
                    }
                    else -> Unit
                }
                if (!suitable) continue
                (if (highPriority) highPriorityUnits else moveUnits).add(MoveUnit(unit, 0))
            }
        }

        /** The unit can enter the target (CvUnit::canMoveInto with MOVEFLAG_DESTINATION): the right domain, nobody of the same kind on it, or it stands there already (see the class comment) */
        private fun canEnter(unit: MapUnit, target: Tile): Boolean {
            if (unit.baseUnit.isWaterUnit && !target.isWater || !unit.baseUnit.isWaterUnit && target.isWater) return false
            return unit.getTile() == target || unit.movement.canMoveTo(target)
        }

        /** CvHomelandAI::GetBestUnitToReachTarget: raw distances first, then the best unit of each list by the turns of its path */
        private fun getBestUnitToReachTarget(target: Tile, maxTurns: Int): Boolean {
            for (list in listOf(moveUnits, highPriorityUnits)) {
                for (entry in list) {
                    entry.movesToTarget =
                        if (!canEnter(entry.unit, target)) Int.MAX_VALUE else entry.unit.getTile().aerialDistanceTo(target)
                }
                list.sortBy { it.movesToTarget }
            }
            val normal = closestUnitByTurnsToTarget(moveUnits, target, maxTurns)
            bestUnit = normal?.first; bestUnitTurns = normal?.second ?: Int.MAX_VALUE
            val high = closestUnitByTurnsToTarget(highPriorityUnits, target, maxTurns)
            bestHighPriorityUnit = high?.first; bestHighPriorityUnitTurns = high?.second ?: Int.MAX_VALUE
            return bestHighPriorityUnit != null || bestUnit != null
        }

        /**
         *  CvHomelandAI::GetClosestUnitByTurnsToTarget: the units are sorted by raw distance. The first one whose path takes no longer than that
         *  distance, and at most [maxTurns], is taken at once (a unit on the target as well); otherwise the one with the fewest turns, the search
         *  ending at a raw distance of AI_HOMELAND_ESTIMATE_TURNS_DISTANCE, after two units without a path or at one of 12.
         *  [maxTurns] is only that early-out: the original takes a unit that needs longer if no other can reach.
         */
        private fun closestUnitByTurnsToTarget(list: List<MoveUnit>, target: Tile, maxTurns: Int): Pair<MapUnit, Int>? {
            var best: MapUnit? = null
            var minTurns = Int.MAX_VALUE
            var failedPaths = 0
            for (entry in list) {
                val distance = entry.movesToTarget
                if (distance == Int.MAX_VALUE) continue
                val moves = AiOperationLogic.turnsToReach(entry.unit, target)
                entry.movesToTarget = moves
                if (moves != Int.MAX_VALUE) {
                    if (distance == 0 || (moves <= distance && moves <= maxTurns && moves < minTurns)) {
                        best = entry.unit; minTurns = moves
                        break
                    }
                    if (moves < minTurns) { best = entry.unit; minTurns = moves }
                    if (distance >= estimateTurnsDistance) break
                } else {
                    failedPaths++
                    if (failedPaths >= MAX_FAILED_PATHS) break
                    if (distance >= EARLY_OUT_FAILED_PATH_DISTANCE) break
                }
            }
            return best?.let { it to minTurns }
        }

        /** CvHomelandAI::ExecuteMoveToTarget: the best unit, a normal one if it is closer than the best high priority one; it fortifies on arrival */
        private fun executeMoveToTarget(target: Tile, move: HomelandMove) {
            val high = bestHighPriorityUnit
            val normal = bestUnit
            val unit = if (high != null) (if (normal != null && bestUnitTurns < bestHighPriorityUnitTurns) normal else high) else normal ?: return
            AiDecisionLog.log(civ) { "Homeland $move: ${unit.name} ${unit.getTile().position} -> ${target.position}" }
            sendTo(unit, target)
            processed(unit, move)
        }

        /** MISSION_MOVE_TO for [unit]: as far as it gets this turn, the rest by a `moveTo` order carried out when its next turn starts; fortifies if it stands on the target */
        private fun sendTo(unit: MapUnit, target: Tile, fortifyOnArrival: Boolean = true) {
            if (unit.getTile() != target) unit.movement.tryHeadTowards(target)
            if (unit.isDestroyed) return
            if (unit.getTile() == target) {
                if (fortifyOnArrival) unit.fortifyIfCan()
            } else unit.action = "moveTo ${target.position.x},${target.position.y}"
        }

        //endregion

        //region Patrol, upgrades, leftovers

        /** CvHomelandAI::PlotPatrolMoves: every unit left steps to a random neighboring tile ([findPatrolTarget]) */
        private fun plotPatrolMoves() {
            for (unit in currentUnits.toList()) {
                val target = findPatrolTarget(unit) ?: continue
                unit.movement.moveToTile(target)
                processed(unit, HomelandMove.Patrol)
            }
        }

        /** CvHomelandAI::FindPatrolTarget: a neighboring tile the unit can enter, with no visible enemy, that it reaches; random, our own territory first */
        fun findPatrolTarget(unit: MapUnit): Tile? {
            var best: Tile? = null
            var bestValue = 0
            for (adjacent in unit.getTile().neighbors) {
                if (!unit.movement.canMoveTo(adjacent)) continue
                if (adjacent.getUnits().any { it.civ != civ && civ.isAtWarWith(it.civ) && it.isVisibleTo(civ) }) continue
                if (!unit.movement.canReachInCurrentTurn(adjacent)) continue
                var value = 1 + patrolRandom.nextInt(PATROL_RANDOM_RANGE)
                if (adjacent.getOwner() == civ) value += PATROL_OWN_TERRITORY_BONUS
                if (value > bestValue) { bestValue = value; best = adjacent }
            }
            return best
        }

        /**
         *  CvHomelandAI::PlotUpgradeMoves: the gold side upgrades and reserves the gold ([UseGoldAutomation.plotUpgradeMoves], not for units
         *  in an army); if nobody upgraded and the unit next in line stands outside our territory it goes next to our nearest city
         *  ([moveToEmptySpaceNearTarget]) and ends its turn.
         */
        private fun plotUpgradeMoves() {
            if (!canUpgrade) return
            val result = UseGoldAutomation.plotUpgradeMoves(civ, currentUnits.toList().asSequence()) { civ.ai.operations.armyOf(it) != null }
            // Upgrading replaces the unit (which then has no movement)
            if (currentUnits.any { it.isDestroyed }) {
                currentUnits.removeAll { it.isDestroyed }
                currentSet.removeAll { it.isDestroyed }
            }
            val waiting = result.waiting ?: return
            if (waiting.getTile().getOwner() == civ) return
            val cities = targets.cities
            val nearest = cities.minByOrNull { it.tile.aerialDistanceTo(waiting.getTile()) } ?: return
            if (moveToEmptySpaceNearTarget(waiting, nearest.tile)) processed(waiting, HomelandMove.Upgrade)
        }

        /** CvHomelandAI::MoveToEmptySpaceNearTarget (land): the first neighbor of [target] on land with no unit that the unit finds a path to */
        fun moveToEmptySpaceNearTarget(unit: MapUnit, target: Tile): Boolean {
            for (tile in target.neighbors) {
                if (tile.isWater || tile.militaryUnit != null || !unit.movement.canMoveTo(tile)) continue
                if (unit.movement.getShortestPath(tile).isEmpty()) continue
                sendTo(unit, tile, fortifyOnArrival = false)
                return true
            }
            return false
        }

        /** CvHomelandAI::ReviewUnassignedUnits: what no move took stays where it is; the turn is over for it */
        private fun reviewUnassignedUnits() {
            for (unit in currentUnits.toList()) processed(unit)
        }

        //endregion
    }
}
