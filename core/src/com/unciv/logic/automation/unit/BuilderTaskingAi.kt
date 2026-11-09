package com.unciv.logic.automation.unit

import com.unciv.Constants
import com.unciv.UncivGame
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.diplomacy.DiplomacyTurn
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.MapPathing
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActionModifiers
import com.unciv.logic.map.tile.RoadStatus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.QuestName
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.CitySpecializationDef
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 *  One thing a builder can do on a tile (the original's CvBuildInfo). The original's builds remove the plot feature as part of
 *  the improvement (a Farm on a Forest); Unciv removes it with a separate "Remove <feature>" improvement queued before, kept in
 *  [removeFirst]. A route build has the road or railroad as [improvement]; a repair has [Constants.repair].
 */
class Build(val improvement: TileImprovement, val removeFirst: TileImprovement? = null) {
    val isRepair get() = improvement.name == Constants.repair
}

/** BuilderDirective: what a builder should do where; [weight] is the original's directive weight */
class BuilderDirective(val type: Type, val build: Build, val tile: Tile, val moveTurnsAway: Int, val weight: Int) {
    enum class Type { BuildImprovementOnResource, BuildImprovement, Repair, BuildRoute, Chop }
}

/**
 *  CvBuilderTaskingAI: which tile a builder (worker, or a Great Person that creates an improvement) of [civ] should improve, and how.
 *  One instance per civilization and turn (held by [WorkerAutomation]); [update] runs again when the number of cities changes mid-turn,
 *  as in the original.
 *
 *  Unciv mapping and deviations:
 *  - Plot yields are Unciv tile stats (Food, Production, Gold, Science, Culture, Faith), truncated to integers; projected yields come from
 *    a copy of the tile with the build applied.
 *  - GetBuildCostWeight is always ×100: builds cost no gold in the original's XML.
 *  - DoesBuildHelpRush reads the city's current construction: an AI city's non-perpetual construction counts as rushed when it needs more
 *    than AI_ATTEMPT_RUSH_OVER_X_TURNS_TO_BUILD turns (×MINOR_CIV_PRODUCTION_PERCENT for city-states), which is when the original's
 *    CvCityStrategyAI::ChooseProduction sets the order's rush flag. Chop production is Unciv's (closest city within 5 tiles).
 *  - A road is free for the route value when someone else built it (Unciv has no per-plot trade route flag).
 *  - A non-human major connects its capital to a city-state's capital only while it wants to (CvDiplomacyAI::IsWantToRouteConnectToMinor,
 *    kept by [com.unciv.logic.automation.civilization.CityStateAi.contactMinorCivs]) and the city-state has a route quest for it; a city-state
 *    connects to non-human majors it gave a route quest, never to a human's capital.
 *  - Builders keep their plot across turns as the original's MISSIONAI_BUILD does ([MapUnit.builderTarget]): another builder skips it until
 *    that one is sent elsewhere. A human's builder only counts while it is automated.
 */
class BuilderTaskingAi(val civ: Civilization) {
    private val ruleset = civ.gameInfo.ruleset
    private val rules = ruleset.ai

    /** CvPlayerTraits::IsFaithFromUnimprovedForest (the Celts): a nation unique conditional on neighboring unimproved forests */
    private val faithFromUnimprovedForest = civ.nation.uniqueObjects.any { unique ->
        unique.modifiers.any { it.type == UniqueType.ConditionalNeighborTiles && "unimproved" in it.params[2] && FOREST in it.params[2] }
    }
    /** CvPlayerTraits::IsEmbarkedAllWater (Polynesia): embarkation from the nation itself */
    private val embarksFromStart = civ.nation.uniqueObjects.any { it.type == UniqueType.LandUnitEmbarkation }

    /** CvBuilderTaskingAI::m_iNumCities */
    private var numCities = -1
    /** The builder AI scratch pad of CvPlot: route value and route per plot flagged this turn (UpdateRoutePlots) */
    val routePlots = HashMap<Tile, RoutePlot>()
    /** m_aiNonTerritoryPlots: flagged plots outside our territory */
    private val nonTerritoryPlots = ArrayList<Tile>()
    /** CvCityStrategyAI::UpdateBestYields of every city, this turn */
    private val cityYields = HashMap<City, CityYieldTargets>()
    /** Current yields per plot this turn (UpdateCurrentPlotYields) */
    private val currentYieldCache = HashMap<Tile, IntArray>()
    /** Projected yields per plot and build this turn (UpdateProjectedPlotYields) */
    private val projectedYieldCache = HashMap<Pair<Tile, String>, IntArray>()
    /** Copies of plots with their top feature removed, to test and project builds that need the feature gone */
    private val withoutFeatureCache = HashMap<Tile, Tile>()

    /** Route value (CvPlot::GetBuilderAIScratchPadValue) and route (GetBuilderAIScratchPadRoute) of a flagged plot */
    class RoutePlot(var value: Int, var route: RoadStatus)

    /** CvCityStrategyAI's yield deltas (best yield average - specialization target, ×100) and focus yield (index in [YIELDS], -1 = none) */
    class CityYieldTargets(val deltaTimes100: IntArray, val focusYield: Int)

    init {
        update()
    }

    /** CvBuilderTaskingAI::Update: the route plots, and the best yields of every city */
    fun update() {
        numCities = civ.cities.size
        cityYields.clear()
        updateRoutePlots()
    }

    /** CvPlayer::getBestRoute, except for a human who turned off automated road building */
    fun bestRoute(): RoadStatus =
        if (civ.isHuman() && !UncivGame.Current.settings.autoBuildingRoads
            && UncivGame.Current.worldScreen?.autoPlay?.isAutoPlayingAndFullAutoPlayAI() == false) RoadStatus.None
        else civ.tech.getBestRoadAvailable()

    //region Routes

    /**
     *  CvBuilderTaskingAI::UpdateRoutePlots: for every route build the player has the tech for, every pair of connectable cities with a
     *  capital in it (CITY_CONNECTIONS_CONNECT_TO_CAPITAL) is connected, the pair's capital being ours if it has one, else the later of the
     *  two - see [connectCitiesToCapital]. Needs two connectable cities, a best route and a builder.
     *  The cities are ours and [connectableCapitals], in the original's order of player ids (majors before city-states).
     */
    private fun updateRoutePlots() {
        routePlots.clear()
        nonTerritoryPlots.clear()
        if (civ.getCapital() == null) return
        val others = connectableCapitals()
        if (civ.cities.size + others.size < 2) return
        if (bestRoute() == RoadStatus.None) return
        if (civ.units.getCivUnits().none { it.cache.hasUniqueToBuildImprovements }) return

        val connectable = if (civ.isCityState) others + civ.cities else civ.cities + others
        for (route in arrayOf(RoadStatus.Road, RoadStatus.Railroad)) {
            val improvement = route.improvement(ruleset) ?: continue
            if (improvement.techRequired != null && !civ.tech.isResearched(improvement.techRequired!!)) continue
            for (i in connectable.indices) for (j in i + 1 until connectable.size) {
                val first = connectable[i]
                val second = connectable[j]
                if (!first.isCapital() && !second.isCapital()) continue
                if (first.isCapital() && first.civ == civ) connectCitiesToCapital(first, second, route)
                else connectCitiesToCapital(second, first, route)
            }
        }
    }

    /**
     *  The capitals of the other civilizations we connect to (CvCityConnections::ShouldConnectToOtherPlayer): never for a human major; a
     *  city-state's builders go to the non-human majors it gave a route quest; a non-human major's go to the city-states it wants to connect
     *  to that gave it one.
     */
    private fun connectableCapitals(): List<City> {
        if (civ.isHuman()) return emptyList()
        return civ.getKnownCivs().filter {
            when {
                civ.isCityState -> it.isMajorCiv && !it.isHuman() && hasRouteQuest(civ, it)
                civ.isMajorCiv -> it.isCityState && it.isAlive() && it.civID in civ.ai.wantsRouteToMinors && hasRouteQuest(it, civ)
                else -> false
            }
        }.mapNotNull { it.getCapital() }.toList()
    }

    private fun hasRouteQuest(minor: Civilization, major: Civilization) =
        minor.questManager.getAssignedQuestsFor(major).any { it.questName == QuestName.Route.value }

    /**
     *  CvBuilderTaskingAI::ConnectCitiesToCapital: flags the plots of the road path from [capital] to [target] that lack [route] with a
     *  value - the railroad 32767 when the connection pays for its maintenance, else the target's production × INDUSTRIAL_ROUTE_PRODUCTION_MOD
     *  when the treasury can afford it; a city-state route MINOR_CIV_ROUTE_QUEST_WEIGHT / plots needed; a road (connection gold × 100 /
     *  (length × (maintenance + 1))) × length / plots needed, only if it pays for itself. A plot keeps its highest value.
     */
    private fun connectCitiesToCapital(capital: City, target: City, route: RoadStatus) {
        val majorMinorConnection = target.civ != capital.civ
        val industrialRoute = route == RoadStatus.Railroad
        if (target.civ == civ && target.cityStats.isConnectedToCapital(route)) return
        val goldForRoute = if (majorMinorConnection) 0 else target.cityStats.getTradeRouteGold().toInt()
        val maintenancePerTile = route.upkeep

        val path = MapPathing.getRoadPath(civ, capital.getCenterTile(), target.getCenterTile()) ?: return
        var roadLength = 0
        var plotsNeeded = 0
        for (tile in path) {
            if (tile.isCityCenter()) continue  // a city's plot has its road: ours cost nothing, a foreign one is someone else's
            if (tile.roadStatus == route && !tile.roadIsPillaged) {
                if (tile.getRoadOwner() != civ) continue  // someone else built it: free
                roadLength++
            } else {
                roadLength++
                plotsNeeded++
            }
        }
        if (roadLength <= 0 || plotsNeeded <= 0) return

        val profit = goldForRoute - roadLength * maintenancePerTile
        val value: Int = when {
            industrialRoute -> when {
                profit >= 0 -> Short.MAX_VALUE.toInt()
                civ.stats.statsForNextTurn.gold.toInt() + profit >= 0 ->
                    min(target.cityStats.currentCityStats.production.toInt() * rules.define(AiDefine.INDUSTRIAL_ROUTE_PRODUCTION_MOD), Short.MAX_VALUE.toInt())
                else -> return
            }
            majorMinorConnection -> min(rules.define(AiDefine.MINOR_CIV_ROUTE_QUEST_WEIGHT) / plotsNeeded, Short.MAX_VALUE.toInt())
            else -> {
                if (profit < 0) return
                val perPlot = goldForRoute * 100 / (roadLength * (maintenancePerTile + 1))
                min(perPlot * roadLength / plotsNeeded, Short.MAX_VALUE.toInt())
            }
        }

        for (tile in path) {
            if (tile.isCityCenter() || tile.roadStatus == route && !tile.roadIsPillaged) continue
            val known = routePlots[tile]
            if (known != null) {
                if (value > known.value) {
                    known.value = value
                    known.route = route
                }
                continue
            }
            routePlots[tile] = RoutePlot(value, route)
            // Minors do not build outside their borders for a major/minor connection
            if (!(civ.isCityState && majorMinorConnection) && tile.getOwner() != civ) nonTerritoryPlots.add(tile)
        }
    }

    //endregion
    //region Directives

    /**
     *  CvBuilderTaskingAI::EvaluateBuilder for one directive: the best-weighted directive of [unit] over our plots and the flagged route
     *  plots outside our territory, the first one in weight order that [unit] can reach. Null if there is none.
     *  The "already building" bail-out of the original is the caller's ([WorkerAutomation.automateWorkerAction]).
     */
    fun evaluateBuilder(unit: MapUnit): BuilderDirective? = timeThis("AI.EvaluateBuilder") {
        if (civ.cities.size != numCities) update()
        val builds = BuilderBuilds(unit)
        val claimed = otherBuilderTargets(unit)
        val directives = ArrayList<BuilderDirective>()
        for (city in civ.cities) for (tile in city.getTiles()) {
            if (!shouldBuilderConsiderPlot(unit, tile, claimed)) continue
            val turnsAway = findTurnsAway(unit, tile)
            if (turnsAway < 0) continue
            addRouteDirectives(unit, builds, tile, turnsAway, directives)
            addImprovingResourcesDirectives(unit, builds, tile, turnsAway, directives)
            addImprovingPlotsDirectives(unit, builds, tile, turnsAway, directives)
            addChopDirectives(unit, builds, tile, turnsAway, directives)
            addScrubFalloutDirectives(unit, builds, tile, turnsAway, directives)
        }
        for (tile in nonTerritoryPlots) {
            if (!shouldBuilderConsiderPlot(unit, tile, claimed)) continue
            val turnsAway = findTurnsAway(unit, tile)
            if (turnsAway < 0) continue
            addRouteDirectives(unit, builds, tile, turnsAway, directives)
        }
        // StableSortItems: highest weight first, ties in insertion order
        directives.sortByDescending { it.weight }
        directives.firstOrNull { it.tile == unit.getTile() || unit.movement.canReach(it.tile) }
    }

    /** Sends [unit] to [tile] (MISSIONAI_BUILD): no other builder considers it until [unit] gets another directive */
    fun claim(unit: MapUnit, tile: Tile) {
        unit.builderTarget = tile.position
    }

    /** The plots other builders of ours were sent to (CvPlayerAI::AI_plotTargetMissionAIs with MISSIONAI_BUILD) */
    private fun otherBuilderTargets(unit: MapUnit): Set<HexCoord> {
        val targets = HashSet<HexCoord>()
        for (other in civ.units.getCivUnits()) {
            if (other == unit) continue
            val target = other.builderTarget ?: continue
            if (civ.isHuman() && !other.isAutomated()) continue
            targets.add(target)
        }
        return targets
    }

    /**
     *  CvBuilderTaskingAI::ShouldBuilderConsiderPlot: not impassable, not owned by another major, in the builder's domain, on its landmass
     *  unless it can embark, not the target of another builder ([claimed]), without plot danger, and with no other civilian of ours on it.
     */
    private fun shouldBuilderConsiderPlot(unit: MapUnit, tile: Tile, claimed: Set<HexCoord>): Boolean {
        if (tile.isImpassible() || tile.getBaseTerrain().impassable) return false
        val owner = tile.getOwner()
        if (owner != null && owner != civ && !owner.isCityState) return false
        if (tile.isCityCenter() || tile.isMarkedForCreatesOneImprovement()) return false
        val landUnit = unit.baseUnit.isLandUnit
        if (landUnit == tile.isWater) return false
        if (landUnit && tile.getContinent() != unit.getTile().getContinent() && !civ.tech.unitsCanEmbark) return false
        val atPlot = tile == unit.getTile()
        if (!atPlot && tile.position in claimed) return false
        val otherCivilian = tile.civilianUnit
        if (!atPlot && otherCivilian != null) return false  // another builder works here, or the plot is full (PLOT_UNIT_LIMIT)
        if (civ.ai.plotDanger.get(tile) > 0) return false
        return true
    }

    /** CvBuilderTaskingAI::FindTurnsAway: the plot distance, -1 for another landmass a land unit cannot reach */
    private fun findTurnsAway(unit: MapUnit, tile: Tile): Int {
        if (unit.baseUnit.isLandUnit && tile.getContinent() != unit.getTile().getContinent() && !civ.tech.unitsCanEmbark) return -1
        return unit.getTile().aerialDistanceTo(tile)
    }

    /** "Do we have a special improvement here?" (CvPlot::HasSpecialImprovement): a Great Person's or one automation must not replace */
    private fun hasSpecialImprovement(tile: Tile): Boolean {
        val improvement = tile.tileImprovement ?: return false
        return improvement.hasUnique(UniqueType.GreatImprovement) || improvement.hasUnique(UniqueType.AutomatedUnitsWillNotReplace)
    }

    /** CvPlot::getResourceType(team): the resource if [civ] can see it */
    private fun visibleResource(tile: Tile): TileResource? = tile.tileResource?.takeIf { civ.canSeeResource(it) }

    /**
     *  CvBuilderTaskingAI::AddImprovingResourcesDirectives: improvements that hook up a luxury or strategic resource, in ruleset order,
     *  stopping at the first the builder cannot build or that is already there: (BUILD_RESOURCE_IMPROVEMENTS × 100 + time weight
     *  [adding the build points of the improvement to replace] + [resourceWeight]) × the plot score when positive, plus the chop
     *  production when the build helps a rush.
     */
    private fun addImprovingResourcesDirectives(unit: MapUnit, builds: BuilderBuilds, tile: Tile, turnsAway: Int, out: MutableList<BuilderDirective>) {
        val existing = tile.tileImprovement
        if (existing != null && hasSpecialImprovement(tile) && !tile.improvementIsPillaged) return
        val resource = visibleResource(tile) ?: return
        if (resource.resourceType == ResourceType.Bonus) return

        for (improvement in ruleset.tileImprovements.values) {
            if (!resource.isImprovedBy(improvement.name)) continue
            var build: Build
            var type = BuilderDirective.Type.BuildImprovementOnResource
            var weight = rules.define(AiDefine.BUILDER_TASKING_BASELINE_BUILD_RESOURCE_IMPROVEMENTS)
            if (improvement == existing) {
                if (!tile.improvementIsPillaged) break  // already hooked up
                build = builds.repair(tile) ?: break
                type = BuilderDirective.Type.Repair
                weight = rules.define(AiDefine.BUILDER_TASKING_BASELINE_REPAIR)
            } else {
                if (existing != null && hasSpecialImprovement(tile)) continue
                build = builds.build(tile, improvement) ?: break
            }

            weight = buildCostWeight(weight)
            // CvPlot::getBuildTime of the first build of the improvement already there, in build points (100 per worker turn): also when
            // it is the pillaged one to repair; a created improvement (no build time) counts nothing
            val investedTime = if (existing != null && existing.turnsToBuild > 0) existing.getTurnsToBuild(civ, unit, tile) * 100 else 0
            val helpsRush = doesBuildHelpRush(tile, build)
            weight += buildTimeWeight(unit, tile, build, helpsRush, investedTime + turnsAway)
            weight = correctWeight(weight)
            weight += resourceWeight(civ, resource, improvement, tile.resourceAmount)
            weight = correctWeight(weight)
            val score = scorePlot(tile, build)
            if (score > 0) weight = correctWeight(weight * score)
            if (helpsRush) weight += chopProduction(tile, build)
            if (weight <= 0) continue
            out.add(BuilderDirective(type, build, tile, turnsAway, weight))
        }
    }

    /**
     *  CvBuilderTaskingAI::AddImprovingPlotsDirectives: every improvement for a plot within a city's reach that has no luxury or strategic
     *  resource (a bonus resource only with its own improvement): (BUILD_IMPROVEMENTS × 100 + time weight) × plot score, skipping builds
     *  that do not raise the score. A culture improvement starts from ADDS_CULTURE × its culture; repairs from REPAIR.
     *  Trait rules: the Celts keep forests next to their cities, the Dutch their marshes and Brazil its jungles (for a civilization with a
     *  unique improvement valid on them), and a civilization whose forests and jungles count as roads weighs their removal a quarter.
     */
    private fun addImprovingPlotsDirectives(unit: MapUnit, builds: BuilderBuilds, tile: Tile, turnsAway: Int, out: MutableList<BuilderDirective>) {
        val existing = tile.tileImprovement
        if (existing != null && hasSpecialImprovement(tile) && !tile.improvementIsPillaged) return
        if (!isWithinCityRadius(tile)) return
        val resource = visibleResource(tile)
        if (resource != null && resource.resourceType != ResourceType.Bonus) return
        val topFeature = tile.terrainFeatures.lastOrNull()
        if (faithFromUnimprovedForest && existing == null && topFeature == FOREST
            && tile.neighbors.any { it.isCityCenter() && it.getOwner() == civ }) return
        workingCity(tile) ?: return
        val human = civ.isHuman()
        val settings = UncivGame.Current.settings

        for (improvement in builds.candidates(tile)) {
            if (improvement.isRoad()) continue
            if (resource != null && !resource.isImprovedBy(improvement.name)) continue
            val build: Build
            if (improvement == existing) {
                if (!tile.improvementIsPillaged) continue
                build = builds.repair(tile) ?: continue
            } else {
                // PLAYEROPTION_SAFE_AUTOMATION: a human's workers keep existing improvements unless allowed to replace them
                if (existing != null && (hasSpecialImprovement(tile) || (human && !settings.automatedWorkersReplaceImprovements))) continue
                build = builds.build(tile, improvement) ?: continue
            }

            val removedFeature = if (build.removeFirst != null) topFeature else null
            if (removedFeature == MARSH && keepsFeature(MARSH)) continue
            if (removedFeature == JUNGLE && keepsFeature(JUNGLE) && resource == null) continue
            // PLAYEROPTION_LEAVE_FORESTS
            if (removedFeature != null && human && settings.stopAutomatedWorkersRemoveVegetation
                && ruleset.terrains[removedFeature]?.hasUnique(UniqueType.Vegetation) == true) continue

            var score = scorePlot(tile, build)
            if (score <= 0) continue

            var type = BuilderDirective.Type.BuildImprovement
            var weight = rules.define(AiDefine.BUILDER_TASKING_BASELINE_BUILD_IMPROVEMENTS)
            if (build.isRepair) {
                type = BuilderDirective.Type.Repair
                weight = rules.define(AiDefine.BUILDER_TASKING_BASELINE_REPAIR)
            } else if (improvement.culture > 0) {
                weight = rules.define(AiDefine.BUILDER_TASKING_BASELINE_ADDS_CULTURE) * improvement.culture.toInt()
                val adjacentCulture = adjacentSameTypeCulture(tile, improvement)
                if (adjacentCulture >= 0) score *= 1 + adjacentCulture
            }
            weight = buildCostWeight(weight)
            weight += buildTimeWeight(unit, tile, build, doesBuildHelpRush(tile, build), turnsAway)
            weight *= score
            if ((removedFeature == FOREST || removedFeature == JUNGLE) && civ.hasUnique(UniqueType.ForestsAndJunglesAreRoads)) weight /= 4
            weight = correctWeight(weight)
            out.add(BuilderDirective(type, build, tile, turnsAway, weight))
        }
    }

    /**
     *  CvImprovementEntry::GetCultureAdjacentSameType + CvPlot::ComputeCultureFromAdjacentImprovement: the culture a Moai-like improvement
     *  ([UniqueType.ImprovementStatsForAdjacencies] naming itself) gets from neighbors with the same improvement; -1 if it has no such bonus
     */
    private fun adjacentSameTypeCulture(tile: Tile, improvement: TileImprovement): Int {
        val perNeighbor = improvement.getMatchingUniques(UniqueType.ImprovementStatsForAdjacencies, GameContext.IgnoreConditionals)
            .filter { it.params[1] == improvement.name }
            .sumOf { it.stats.culture.toInt() }
        if (perNeighbor <= 0) return -1
        return perNeighbor * tile.neighbors.count { it.improvement == improvement.name }
    }

    /** m_bKeepMarshes / m_bKeepJungle: [civ] has a unique improvement that the feature makes valid */
    private fun keepsFeature(feature: String): Boolean = ruleset.tileImprovements.values.any {
        it.uniqueTo != null && civ.matchesFilter(it.uniqueTo!!) && feature in it.terrainsCanBeBuiltOn
    }

    /**
     *  CvBuilderTaskingAI::AddRouteDirectives: a flagged plot without the best route gets (BUILD_ROUTES / (turns away + 1) × 100 + time
     *  weight) × its route value, the flagged route's build (repair when the road is pillaged).
     */
    private fun addRouteDirectives(unit: MapUnit, builds: BuilderBuilds, tile: Tile, turnsAway: Int, out: MutableList<BuilderDirective>) {
        val best = bestRoute()
        if (best == RoadStatus.None) return
        if (tile.roadStatus == best && !tile.roadIsPillaged) return
        val plot = routePlots[tile] ?: return
        var type = BuilderDirective.Type.BuildRoute
        var weight = rules.define(AiDefine.BUILDER_TASKING_BASELINE_BUILD_ROUTES)
        val build: Build
        if (tile.roadIsPillaged) {
            build = builds.repair(tile) ?: return
            type = BuilderDirective.Type.Repair
            weight = rules.define(AiDefine.BUILDER_TASKING_BASELINE_REPAIR)
        } else {
            val road = plot.route.improvement(ruleset) ?: return
            if (!unit.canBuildImprovement(road, tile)) return
            build = Build(road)
        }
        weight /= turnsAway + 1
        weight = buildCostWeight(weight)
        weight += buildTimeWeight(unit, tile, build, false, turnsAway)
        weight *= plot.value
        weight = correctWeight(weight)
        out.add(BuilderDirective(type, build, tile, turnsAway, weight))
    }

    /**
     *  CvBuilderTaskingAI::AddChopDirectives: removing an unworked, unimproved, resource-free plot's feature that yields production, only
     *  when that helps a rush: (REPAIR / (turns away + 1) × 100 + time weight) × chop production + the flavor-weighted yield change
     *  (Growth×food×2, Production×production×2, Gold, Science, Culture), skipped when that change is negative.
     */
    private fun addChopDirectives(unit: MapUnit, builds: BuilderBuilds, tile: Tile, turnsAway: Int, out: MutableList<BuilderDirective>) {
        if (!isWithinCityRadius(tile)) return
        if (tile.improvement != null) return
        if (civ.isHuman() && UncivGame.Current.settings.stopAutomatedWorkersRemoveVegetation) return
        if (visibleResource(tile) != null) return
        val city = workingCity(tile) ?: return
        if (city.isWorked(tile)) return
        val feature = tile.terrainFeatures.lastOrNull() ?: return
        if (faithFromUnimprovedForest && feature == FOREST
            && tile.neighbors.any { it.isCityCenter() && it.getOwner() == civ }) return
        val terrain = ruleset.terrains[feature] ?: return
        if (!terrain.hasUnique(UniqueType.ProductionBonusWhenRemoved)) return
        val removal = builds.removal(tile) ?: return
        val build = Build(removal)
        val production = chopProduction(tile, build)
        if (!doesBuildHelpRush(tile, build)) return

        var weight = rules.define(AiDefine.BUILDER_TASKING_BASELINE_REPAIR)
        weight /= turnsAway + 1
        weight = buildCostWeight(weight)
        weight += buildTimeWeight(unit, tile, build, false, turnsAway)
        weight *= production

        val before = natureYields(tile)
        val after = natureYields(withoutFeature(tile))
        var yieldDifferenceWeight = 0
        for (i in YIELDS.indices) {
            val delta = after[i] - before[i]
            if (delta == 0) continue
            yieldDifferenceWeight += when (YIELDS[i]) {
                Stat.Food -> delta * civ.ai.personality(Flavor.Growth) * rules.define(AiDefine.BUILDER_TASKING_PLOT_EVAL_MULTIPLIER_FOOD)
                Stat.Production -> delta * civ.ai.personality(Flavor.Production) * rules.define(AiDefine.BUILDER_TASKING_PLOT_EVAL_MULTIPLIER_PRODUCTION)
                Stat.Gold -> delta * civ.ai.personality(Flavor.Gold) * rules.define(AiDefine.BUILDER_TASKING_PLOT_EVAL_MULTIPLIER_GOLD)
                Stat.Science -> delta * civ.ai.personality(Flavor.Science) * rules.define(AiDefine.BUILDER_TASKING_PLOT_EVAL_MULTIPLIER_SCIENCE)
                Stat.Culture -> delta * civ.ai.personality(Flavor.Culture) * rules.define(AiDefine.BUILDER_TASKING_PLOT_EVAL_MULTIPLIER_CULTURE)
                else -> 0
            }
        }
        if (yieldDifferenceWeight < 0) return
        weight += yieldDifferenceWeight
        if ((feature == FOREST || feature == JUNGLE) && civ.hasUnique(UniqueType.ForestsAndJunglesAreRoads)) weight /= 4
        if (feature == FOREST && faithFromUnimprovedForest) weight /= 4
        // "this doesn't actually help them, but adds some historical flavor" (Polynesia)
        if ((feature == FOREST || feature == JUNGLE) && embarksFromStart) weight *= 2
        weight = correctWeight(weight)
        if (weight > 0) out.add(BuilderDirective(BuilderDirective.Type.Chop, build, tile, turnsAway, weight))
    }

    /** CvBuilderTaskingAI::AddScrubFalloutDirectives: SCRUB_FALLOUT / (turns away + 1) × 100 + time weight, for fallout in a city's reach */
    private fun addScrubFalloutDirectives(unit: MapUnit, builds: BuilderBuilds, tile: Tile, turnsAway: Int, out: MutableList<BuilderDirective>) {
        val feature = tile.terrainFeatures.lastOrNull() ?: return
        if (ruleset.terrains[feature]?.hasUnique(UniqueType.NullifyYields) != true) return
        workingCity(tile) ?: return
        val removal = builds.removal(tile) ?: return
        val build = Build(removal)
        var weight = rules.define(AiDefine.BUILDER_TASKING_BASELINE_SCRUB_FALLOUT)
        weight /= turnsAway + 1
        weight = buildCostWeight(weight)
        weight += buildTimeWeight(unit, tile, build, false, turnsAway)
        out.add(BuilderDirective(BuilderDirective.Type.Chop, build, tile, turnsAway, weight))
    }

    //endregion
    //region Weights

    /** CvBuilderTaskingAI::GetBuildCostWeight: builds have no gold cost in the original's XML, so always ×100 */
    private fun buildCostWeight(weight: Int) = weight * 100

    /**
     *  CvBuilderTaskingAI::GetBuildTimeWeight: 10000 / (turns left after this turn's work, at least 1, + [additionalTime]). When
     *  [ignoreFeatureTime], the feature removal time is subtracted in build points (turns × 100), as the original does.
     *  An improvement (route) build on a plot whose improvement (route) is pillaged is timed as the repair, whatever it replaces.
     */
    fun buildTimeWeight(unit: MapUnit, tile: Tile, build: Build, ignoreFeatureTime: Boolean, additionalTime: Int): Int {
        val timed = timedAsRepair(tile, build)
        var buildTime = turnsLeft(unit, tile, timed) - 1
        if (buildTime <= 0) buildTime = 1
        if (ignoreFeatureTime && timed.removeFirst != null) buildTime -= timed.removeFirst.turnsToBuild * 100
        buildTime += additionalTime
        if (buildTime == 0) buildTime = 1
        return 10000 / buildTime
    }

    private fun timedAsRepair(tile: Tile, build: Build): Build {
        if (build.isRepair || build.improvement.name.startsWith(Constants.remove)) return build
        val pillaged = if (build.improvement.isRoad()) tile.roadStatus != RoadStatus.None && tile.roadIsPillaged
            else tile.improvement != null && tile.improvementIsPillaged
        if (!pillaged) return build
        return ruleset.tileImprovements[Constants.repair]?.let { Build(it) } ?: build
    }

    /** Turns [unit] needs to finish [build] on [tile], counting work already done there; 0 for an instant (Great Person) improvement */
    private fun turnsLeft(unit: MapUnit, tile: Tile, build: Build): Int {
        if (build.isRepair) {
            if (tile.improvementInProgress == Constants.repair) return tile.turnsToImprovement
            val pillaged = tile.getImprovementToRepair() ?: return 0
            return min(build.improvement.getTurnsToBuild(civ, unit, tile), pillaged.getTurnsToBuild(civ, unit, tile))
        }
        var turns = turnsOf(unit, tile, build.improvement)
        if (build.removeFirst != null) turns += turnsOf(unit, tile, build.removeFirst)
        return turns
    }

    private fun turnsOf(unit: MapUnit, tile: Tile, improvement: TileImprovement): Int = when {
        tile.improvementInProgress == improvement.name -> tile.turnsToImprovement
        improvement.turnsToBuild == -1 -> 0
        else -> improvement.getTurnsToBuild(civ, unit, tile)
    }

    /** CorrectWeight: an overflowed (below -1000) weight becomes the maximum */
    private fun correctWeight(weight: Int) = if (weight < -1000) Int.MAX_VALUE else weight

    /**
     *  CvBuilderTaskingAI::ScorePlot: for every yield, a city short of it (negative delta) scores the projected yield × |delta| when the
     *  build raises it; a city not short of it scores the projected yield (when not lowered); a lowered yield costs the loss × |delta|.
     *  With no yield short, a build raising the focus yield adds it ×100. The capital's plots count ×8, an original capital's ×2.
     *  -1 when the plot has no working city of ours.
     */
    fun scorePlot(tile: Tile, build: Build): Int {
        val city = tile.getCity()?.takeIf { it.civ == civ && tile in it.tilesInRange } ?: return -1
        val multiplier = if (city.isCapital()) 8 else if (city.isOriginalCapital) 2 else 1
        return scoreYields(currentYields(tile, city), projectedYields(tile, build, city), cityYieldTargets(city)) * multiplier
    }

    /**
     *  CvCityStrategyAI::UpdateBestYields: per yield, the average of the best (population + 2) plot yields of the plots the city owns in
     *  its reach (×100), food plus the city plot's food above 2 and the flat food of its buildings; minus the specialization's target yield.
     *  The specialization is the city's, else for a human the one matching the city focus (food: settler pump, gold: commerce), else the
     *  default one; its yield is the focus yield.
     */
    fun cityYieldTargets(city: City): CityYieldTargets = cityYields.getOrPut(city) {
        val tiles = city.tilesInRange.filter { it.getCity() == city }
        val slots = min(tiles.size, city.population.population + 2)
        val delta = IntArray(YIELDS.size)
        val specialization = specializationFor(city)
        if (slots > 0) {
            val yieldsByTile = tiles.map { currentYields(it, city) }
            for (i in YIELDS.indices) {
                var sum = yieldsByTile.map { it[i] }.sortedDescending().take(slots).sum() * 100
                if (YIELDS[i] == Stat.Food) {
                    val cityFood = currentYields(city.getCenterTile(), city)[i] - MIN_CITY_FOOD +
                        city.cityConstructions.getBuiltBuildings().sumOf { it.food.toInt() }
                    sum += cityFood * 100
                }
                delta[i] = sum / slots - (specialization?.targetYields?.get(YIELDS[i].name) ?: 0)
            }
        }
        val focus = specialization?.yieldType?.let { name -> YIELDS.indexOfFirst { it.name == name } } ?: -1
        CityYieldTargets(delta, focus)
    }

    private fun specializationFor(city: City): CitySpecializationDef? {
        city.ai.specializationDef()?.let { return it }
        val specializations = rules.citySpecializations
        if (civ.isHuman()) {
            val lookup = when (city.getCityFocus()) {
                CityFocus.FoodFocus -> "CITYSPECIALIZATION_SETTLER_PUMP"
                CityFocus.GoldFocus -> "CITYSPECIALIZATION_COMMERCE"
                else -> null
            }
            if (lookup != null) specializations.firstOrNull { it.name == lookup }?.let { return it }
        }
        return specializations.firstOrNull { it.isDefault }
    }

    /** CvPlot::getYield, cached for the turn */
    private fun currentYields(tile: Tile, city: City?): IntArray = currentYieldCache.getOrPut(tile) {
        yieldsOf(tile.stats.getTileStats(city, civ))
    }

    /** CvPlot::getYieldWithBuild (each at least 0), cached for the turn */
    private fun projectedYields(tile: Tile, build: Build, city: City): IntArray = projectedYieldCache.getOrPut(tile to build.improvement.name) {
        val alternative = if (build.removeFirst != null) withoutFeature(tile).clone(addUnits = false) else tile.clone(addUnits = false)
        alternative.setTerrainTransients()
        if (build.isRepair) {
            alternative.improvementIsPillaged = false
            alternative.roadIsPillaged = false
        } else if (!build.improvement.name.startsWith(Constants.remove)) {
            alternative.setImprovement(build.improvement.name)
        }
        yieldsOf(alternative.stats.getTileStats(city, civ)).also { for (i in it.indices) it[i] = max(it[i], 0) }
    }

    /** CvPlot::calculateNatureYield: the plot's yields without improvement and route (tile stats are never below 0, as there) */
    private fun natureYields(tile: Tile): IntArray {
        val bare = tile.clone(addUnits = false)
        bare.setTerrainTransients()
        bare.improvement = null
        bare.roadStatus = RoadStatus.None
        return yieldsOf(bare.stats.getTileStats(null, civ))
    }

    /** A copy of [tile] without its top terrain feature */
    fun withoutFeature(tile: Tile): Tile = withoutFeatureCache.getOrPut(tile) {
        val copy = tile.clone(addUnits = false)
        copy.setTerrainTransients()
        copy.terrainFeatures.lastOrNull()?.let { copy.removeTerrainFeature(it) }
        copy
    }

    private fun yieldsOf(stats: Stats) = IntArray(YIELDS.size) { stats[YIELDS[it]].toInt() }

    /** CvPlot::isWithinTeamCityRadius: within the work range of one of our cities */
    private fun isWithinCityRadius(tile: Tile) = civ.cities.any { tile in it.tilesInRange }

    /** CvBuilderTaskingAI::GetWorkingCity: the city owning the plot in its reach, else one of ours that could work it */
    private fun workingCity(tile: Tile): City? {
        if (tile.getOwner() != civ) return null
        val owning = tile.getCity()
        if (owning != null && tile in owning.tilesInRange) return owning
        return civ.cities.firstOrNull { tile in it.tilesInRange }
    }

    /** CvPlot::getFeatureProduction for Unciv's chop (TileImprovementFunctions): the production the removal gives the closest city */
    private fun chopProduction(tile: Tile, build: Build): Int {
        val removal = build.removeFirst ?: build.improvement.takeIf { it.name.startsWith(Constants.remove) } ?: return 0
        val feature = removal.name.removePrefix(Constants.remove)
        val terrain = ruleset.terrains[feature] ?: return 0
        val city = closestCityForChop(tile) ?: return 0
        val distance = city.getCenterTile().aerialDistanceTo(tile)
        var production = 0f
        for (unique in terrain.getMatchingUniques(UniqueType.ProductionBonusWhenRemoved, GameContext(civ, tile = tile))) {
            var stats = unique.stats
            if (unique.isModifiedByGameSpeed()) stats = stats * civ.gameInfo.speed.modifier
            if (unique.isModifiedByGameProgress()) stats = stats * unique.getGameProgressModifier(civ)
            production += stats.production
        }
        if (distance != 1) production *= (6 - distance) / 4f
        if (tile.getCity()?.civ != civ) production *= 2 / 3f
        return production.toInt()
    }

    private fun closestCityForChop(tile: Tile): City? {
        val city = civ.cities.minByOrNull { it.getCenterTile().aerialDistanceTo(tile) } ?: return null
        return city.takeIf { it.getCenterTile().aerialDistanceTo(tile) <= 5 }
    }

    /**
     *  CvBuilderTaskingAI::DoesBuildHelpRush: the build removes a feature that gives production to a city whose current order is rushed
     *  (an AI city's non-perpetual construction needing more than AI_ATTEMPT_RUSH_OVER_X_TURNS_TO_BUILD turns; see the class comment)
     */
    fun doesBuildHelpRush(tile: Tile, build: Build): Boolean {
        if (chopProduction(tile, build) <= 0) return false
        val city = closestCityForChop(tile) ?: return false
        if (city.civ.isHuman()) return false
        val construction = city.cityConstructions.currentConstructionName()
        if (construction.isEmpty() || construction in PerpetualConstruction.perpetualConstructionsMap) return false
        var rushOver = rules.define(AiDefine.AI_ATTEMPT_RUSH_OVER_X_TURNS_TO_BUILD)
        if (civ.isCityState) rushOver = rushOver * rules.define(AiDefine.MINOR_CIV_PRODUCTION_PERCENT) / 100
        return city.cityConstructions.turnsToConstruction(construction) > rushOver
    }

    //endregion

    /** The builds [unit] can do: a worker's improvements, or the improvements a Great Person creates instantly */
    inner class BuilderBuilds(private val unit: MapUnit) {
        private val instant: List<TileImprovement>? =
            UnitActionModifiers.getUsableUnitActionUniques(unit, UniqueType.ConstructImprovementInstantly).toList()
                .takeIf { it.isNotEmpty() }
                ?.let { uniques ->
                    val context = GameContext(civ, unit = unit)
                    val civResources = civ.getCivResourcesByName()
                    ruleset.tileImprovements.values.filter { improvement ->
                        uniques.any { improvement.matchesFilter(it.params[0], context) }
                            && improvement.getMatchingUniques(UniqueType.ConsumesResources).none { (civResources[it.params[1]] ?: 0) < it.params[0].toInt() }
                    }
                }

        /** Improvements worth testing on [tile], in ruleset order */
        fun candidates(tile: Tile): Collection<TileImprovement> = instant ?: ruleset.tileImprovements.values.filter {
            !it.name.startsWith(Constants.remove) && it.name != Constants.repair && it.name != Constants.cancelImprovementOrder
        }

        private fun canBuildOn(improvement: TileImprovement, tile: Tile): Boolean {
            if (instant != null) return improvement in instant && tile.improvementFunctions.canBuildImprovement(improvement, GameContext(civ, unit = unit, tile = tile))
            return unit.canBuildImprovement(improvement, tile) &&
                tile.improvementFunctions.canBuildImprovement(improvement, GameContext(civ, unit = unit, tile = tile))
        }

        /** The build of [improvement] on [tile], removing the top feature first when the improvement needs it gone; null if impossible */
        fun build(tile: Tile, improvement: TileImprovement): Build? {
            if (canBuildOn(improvement, tile)) return Build(improvement)
            if (instant != null) return null
            val removal = removal(tile) ?: return null
            val cleared = withoutFeature(tile)
            return if (canBuildOn(improvement, cleared)) Build(improvement, removal) else null
        }

        /** The "Remove <top feature>" improvement if [unit] can build it on [tile] */
        fun removal(tile: Tile): TileImprovement? {
            if (instant != null) return null
            val feature = tile.terrainFeatures.lastOrNull() ?: return null
            val removal = ruleset.tileImprovements[Constants.remove + feature] ?: return null
            return removal.takeIf { canBuildOn(it, tile) }
        }

        /** The repair build (CvBuilderTaskingAI::m_eRepairBuild) if [unit] can repair [tile] */
        fun repair(tile: Tile): Build? {
            if (instant != null || !unit.cache.hasUniqueToBuildImprovements || tile.isCityCenter()) return null
            if (!tile.isPillaged()) return null
            val repair = ruleset.tileImprovements[Constants.repair] ?: return null
            return Build(repair)
        }
    }

    companion object {
        /** [scorePlot] before the capital multiplier: the plot's [current] and [projected] yields (in [YIELDS] order) for a city's [targets] */
        fun scoreYields(current: IntArray, projected: IntArray, targets: CityYieldTargets): Int {
            var score = 0
            var anyNegativeMultiplier = false
            for (i in YIELDS.indices) {
                val multiplier = targets.deltaTimes100[i]
                val absMultiplier = abs(multiplier)
                val yieldDelta = projected[i] - current[i]
                if (multiplier < 0) {
                    anyNegativeMultiplier = true
                    if (yieldDelta > 0) score += projected[i] * absMultiplier
                    else if (yieldDelta < 0) score += yieldDelta * absMultiplier
                } else {
                    if (yieldDelta >= 0) score += projected[i]
                    else score += yieldDelta * absMultiplier
                }
            }
            val focus = targets.focusYield
            if (!anyNegativeMultiplier && focus != -1 && projected[focus] - current[focus] > 0) score += projected[focus] * 100
            return score
        }

        /** The original's yields, in YieldTypes order */
        val YIELDS = arrayOf(Stat.Food, Stat.Production, Stat.Gold, Stat.Science, Stat.Culture, Stat.Faith)
        /** Yields.MinCity of food */
        private const val MIN_CITY_FOOD = 2
        private const val FOREST = "Forest"
        private const val JUNGLE = "Jungle"
        private const val MARSH = "Marsh"
    }
}
