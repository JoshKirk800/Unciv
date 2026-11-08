package com.unciv.logic.automation.city

import com.unciv.UncivGame
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.strategy.ProductionAi
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.strategy.WonderProductionAi
import com.unciv.logic.automation.unit.SettleAreas
import com.unciv.logic.city.City
import com.unciv.logic.city.CityConstructions
import com.unciv.logic.civilization.CityAction
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.IConstruction
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.Stat
import com.unciv.ui.screens.cityscreen.CityScreen
import java.util.WeakHashMap
import kotlin.math.ceil

/**
 *  The production choice of a city: CvCityAI::AI_chooseProduction and CvCityStrategyAI::ChooseProduction (CvCityStrategyAI.cpp).
 *
 *  Every buildable item gets its flavor weight ([ProductionAi]: Σ item flavor × city flavor), adjusted per kind as the original does;
 *  each weight is divided by the item's time penalty ([WonderProductionAi.reweightByTurnsLeft], ReweightByCost); then a weighted random pick
 *  among the best HandicapInfos.CityProductionNumOptionsConsidered (ChooseFromTopChoices). The wonder city first rolls whether to start the
 *  next wonder desired.
 *
 *  Not ported: units for military operations and armies (CvCity::GetUnitForOperation, CvMilitaryAI::GetUnitForArmy) - the operations of the
 *  military AI are out of scope, so no such requests exist; the "rush" flag of long builds, which BNW's DoHurry no longer spends gold on.
 *  Unciv extras kept: items a human disabled in the city, the personality's "Will not build" uniques and the ruleset's AiChoiceWeight
 *  uniques (a multiplier on the weight, none in the base rulesets).
 */
class ConstructionAutomation(val cityConstructions: CityConstructions) {

    private val city = cityConstructions.city
    private val civInfo = city.civ

    /** One candidate of ChooseProduction (CvCityBuildable with its weight) */
    class Buildable(val construction: IConstruction, var weight: Int, val turns: Int)

    private val constructionsToAvoid = civInfo.getPersonality().getMatchingUniques(UniqueType.WillNotBuild, city.state).map { it.params[0] }.toList()

    private fun isAllowed(construction: INonPerpetualConstruction): Boolean {
        if (civInfo.isHuman() && construction.name in city.disabledConstructions) return false
        for (toAvoid in constructionsToAvoid) {
            if (construction is Building && construction.matchesFilter(toAvoid, city.state)) return false
            if (construction is BaseUnit && construction.matchesFilter(toAvoid, city.state)) return false
        }
        return true
    }

    /**
     *  Chooses the next production when the city builds nothing or a process (CvCity::doProduction), or always with [interruptWonders]
     *  (CvCitySpecializationAI::DoTurn after a new war or a lost city).
     */
    fun chooseNextConstruction(interruptWonders: Boolean = false): Unit = timeThis("AI.ChooseProduction") {
        if (!interruptWonders && cityConstructions.getCurrentConstruction() !is PerpetualConstruction) return

        val chosen = chooseWonderForWonderCity(interruptWonders) ?: run {
            val candidates = getBuildables()
            pickFromTopChoices(candidates)?.construction
        } ?: return@timeThis
        setConstruction(chosen)
    }

    /**
     *  The wonder part of CvCityAI::AI_chooseProduction, for the wonder city only: it keeps a wonder underway unless [interruptWonders];
     *  if the next wonder desired cannot be built here any more the specializations are re-evaluated; otherwise it starts that wonder when a
     *  roll of 0..10 is at most max(WONDER, GREAT_PEOPLE) of personality and grand strategy.
     *  @return the wonder to build, null to choose normally
     */
    private fun chooseWonderForWonderCity(interruptWonders: Boolean): Building? {
        if (WonderProductionAi.wonderBuildCity(civInfo) != city) return null
        val current = cityConstructions.getCurrentConstruction()
        if (!interruptWonders && current is Building && WonderProductionAi.isWonder(current)) return current
        val state = civInfo.ai.citySpecialization
        val nextWonder = state.nextWonderDesired?.let { civInfo.gameInfo.ruleset.buildings[it] }
        if (nextWonder == null || !nextWonder.isBuildable(cityConstructions)) {
            state.specializationsDirty = true
            return null
        }
        val flavor = maxOf(civInfo.ai.personalityAndGrandStrategy(Flavor.Wonder), civInfo.ai.personalityAndGrandStrategy(Flavor.GreatPeople))
        return if (civInfo.ai.random("WonderRoll" + city.id).nextInt(11) <= flavor) nextWonder else null
    }

    private fun turnsLeft(construction: INonPerpetualConstruction, productionPerTurn: Int): Int =
        ceil(cityConstructions.getRemainingWork(construction.name).toDouble() / productionPerTurn).toInt().coerceAtLeast(1)

    private fun withAiChoiceWeight(construction: INonPerpetualConstruction, weight: Int): Int =
        if (weight <= 0) weight else (weight * construction.getWeightForAiDecision(GameContext(civInfo, city))).toInt()

    /**
     *  The candidates of CvCityStrategyAI::ChooseProduction with their weights before ReweightByCost:
     *  - buildings (not projects): [ProductionAi.buildingWeight]; a diplomatic-voting building only when going for the diplomatic victory with
     *    the gold to buy the missing votes; in puppets 0 for wonders, land military training and resource-consuming buildings, doubled without
     *    maintenance; only weights above 0;
     *  - not in puppets: units ([ProductionAi.unitWeight]; settlers 0 under ECONOMICAISTRATEGY_ENOUGH_EXPANSION or without a good settle area;
     *    ships other than sea workers 0 when the owner has more than 1 unit per 5 tiles of the city's largest adjacent water body), only
     *    weights above 0; projects ([ProductionAi.projectWeight], whatever their weight);
     *  - processes, not in puppets unless the owner may not annex (Venice), not for city-states, when the city's modified base production
     *    ×100 is at least 8 (the original compares the hundredths with 8), 1 turn each.
     *  Turns left use the city's current production for every item (the original's per-item production difference also counts the item's
     *  own production modifiers).
     */
    fun getBuildables(): List<Buildable> {
        val rules = civInfo.gameInfo.ruleset.ai
        val ruleset = civInfo.gameInfo.ruleset
        val production = city.cityStats.currentCityStats.production.toInt().coerceAtLeast(1)
        val candidates = ArrayList<Buildable>()

        for (building in ruleset.buildings.values) {
            if (ProductionAi.isProject(rules, building)) {
                if (city.isPuppet || !isAllowed(building) || !building.isBuildable(cityConstructions)) continue
                candidates.add(Buildable(building, withAiChoiceWeight(building, ProductionAi.projectWeight(city, building)), turnsLeft(building, production)))
                continue
            }
            var weight = ProductionAi.buildingWeight(city, building)
            // Only weights above 0 are candidates, and the adjustments below never raise a weight from 0: skip the costly buildable test
            if (weight <= 0 || !isAllowed(building) || !building.isBuildable(cityConstructions)) continue
            if (WonderProductionAi.isDiplomaticVoting(building) && !WonderProductionAi.isWorthBuildingUnitedNations(civInfo)) weight = 0
            if (city.isPuppet) {
                if (building.isAnyWonder()) weight = 0
                if (givesLandFreeExperience(building)) weight = 0
                if (building.maintenance == 0) weight *= 2
                if (building.getResourceRequirementsPerTurn(city.state).isNotEmpty()) weight = 0
            }
            weight = withAiChoiceWeight(building, weight)
            if (weight > 0) candidates.add(Buildable(building, weight, turnsLeft(building, production)))
        }

        if (!city.isPuppet) {
            val enoughSettlers = civInfo.ai.isEconomicStrategyActive("ECONOMICAISTRATEGY_ENOUGH_EXPANSION")
            var goodSettleAreas = -1
            for (unit in ruleset.units.values) {
                var weight = ProductionAi.unitWeight(city, unit)
                if (weight <= 0 || !isAllowed(unit) || !unit.isBuildable(cityConstructions)) continue
                val unitAi = UnitAiTypes.defaultOf(unit, rules)
                if (unitAi == UnitAiType.SETTLE) {
                    if (enoughSettlers) weight = 0
                    else {
                        if (goodSettleAreas < 0)
                            goodSettleAreas = SettleAreas.bestSettleAreas(civInfo, rules.define(AiDefine.AI_STRATEGY_MINIMUM_SETTLE_FERTILITY)).count
                        if (goodSettleAreas == 0) weight = 0
                    }
                }
                if (unit.isWaterUnit && unitAi != UnitAiType.WORKER_SEA && tooManyUnitsOnWaterBody()) weight = 0
                weight = withAiChoiceWeight(unit, weight)
                if (weight > 0) candidates.add(Buildable(unit, weight, turnsLeft(unit, production)))
            }
        }

        if ((!city.isPuppet || civInfo.hasUnique(UniqueType.MayNotAnnexCities)) && !civInfo.isCityState && modifiedBaseProductionTimes100() >= 8) {
            for (process in PerpetualConstruction.perpetualConstructionsMap.values) {
                if (process == PerpetualConstruction.Idle || !process.isBuildable(cityConstructions)) continue
                if (process is PerpetualConstruction.StatConversion && process.stat == Stat.Science && civInfo.tech.allTechsAreResearched()) continue
                candidates.add(Buildable(process, ProductionAi.processWeight(city, process.name), 1))
            }
        }
        return candidates
    }

    /** CvCity::getBaseYieldRate(PRODUCTION) × 100 (with the yield per population) × the base production modifier / 100 */
    private fun modifiedBaseProductionTimes100(): Int {
        val base = city.cityStats.baseStatTree.totalStats.production
        val modifier = 100 + city.cityStats.statPercentBonusTree.totalStats.production
        return (base * 100 * modifier / 100).toInt()
    }

    /** CvBuildingEntry::GetDomainFreeExperience(DOMAIN_LAND) > 0 */
    private fun givesLandFreeExperience(building: Building): Boolean {
        val units = civInfo.gameInfo.ruleset.units.values
        return building.getMatchingUniques(UniqueType.UnitStartingExperience, GameContext.IgnoreConditionals)
            .any { unique -> unique.params[1].toInt() > 0 && units.any { it.isLandUnit && it.isMilitary && it.matchesFilter(unique.params[0]) } }
    }

    private var waterBodyCheck: Boolean? = null

    /**
     *  The ship sanity check of ChooseProduction: on the largest water body next to the city (CvCity::waterArea), the owner's units × 5 exceed
     *  its tiles; true also without adjacent water. Computed once per choice.
     */
    private fun tooManyUnitsOnWaterBody(): Boolean {
        waterBodyCheck?.let { return it }
        val bodies = WaterBodies.of(civInfo)
        var biggest = -1
        for (neighbor in city.getCenterTile().neighbors) {
            if (!neighbor.isWater) continue
            val body = bodies.bodyOf(neighbor)
            if (biggest < 0 || bodies.size(body) > bodies.size(biggest)) biggest = body
        }
        val result = if (biggest < 0) true else {
            val mine = civInfo.units.getCivUnits().count { it.getTile().isWater && bodies.bodyOf(it.getTile()) == biggest }
            mine * 5 > bodies.size(biggest)
        }
        waterBodyCheck = result
        return result
    }

    /**
     *  ReweightByCost, SortItems and ChooseFromTopChoices: each weight divided by its time penalty, the best
     *  CityProductionNumOptionsConsidered by weight, one of them by a weighted roll; null if all weights are 0.
     */
    fun pickFromTopChoices(candidates: List<Buildable>): Buildable? {
        if (candidates.isEmpty()) return null
        for (candidate in candidates) candidate.weight = WonderProductionAi.reweightByTurnsLeft(civInfo, candidate.weight, candidate.turns)
        val sorted = candidates.sortedByDescending { it.weight }
        if (sorted.sumOf { it.weight } <= 0) return null
        return WonderProductionAi.chooseFromTopChoices(civInfo, sorted.map { it to it.weight },
            WonderProductionAi.cityProductionNumOptions(civInfo), "ChooseProduction" + city.id)
    }

    private fun setConstruction(chosen: IConstruction) {
        // Do not notify while in resistance (you can't do anything about it) - still notify for puppets ("annex already!")
        // Also do not notify while city screen open - might be a buying spree, not helpful
        // Also do not notify when the decision hasn't changed - duh!
        val noNotification = city.isInResistance()
            || civInfo.isAI() // Optimization: addNotification filters anyway, but saves a string builder and a CityAction instantiation
            || cityConstructions.currentConstructionName() == chosen.name
            || UncivGame.Current.screen is CityScreen
        cityConstructions.setCurrentConstruction(chosen.name)
        if (noNotification) return

        civInfo.addNotification(
            "[${city.name}] has started working on [${chosen.name}]",
            CityAction.withLocation(city),
            NotificationCategory.Production,
            NotificationIcon.Construction
        )
    }

    /** The connected water bodies of the map (the original's water CvAreas), computed once per map and turn */
    private class WaterBodies(val turn: Int, tileCount: Int) {
        val bodyIds = IntArray(tileCount) { -1 }
        val sizes = ArrayList<Int>()

        fun bodyOf(tile: Tile): Int = bodyIds[tile.zeroBasedIndex]
        fun size(body: Int): Int = sizes[body]

        companion object {
            private val cache = WeakHashMap<TileMap, WaterBodies>()

            fun of(civ: Civilization): WaterBodies {
                val turn = civ.gameInfo.turns
                synchronized(cache) { cache[civ.gameInfo.tileMap]?.let { if (it.turn == turn) return it } }
                val tiles = civ.gameInfo.tileMap.tileList
                val bodies = WaterBodies(turn, tiles.size)
                val stack = ArrayList<Tile>()
                for (start in tiles) {
                    if (!start.isWater || bodies.bodyIds[start.zeroBasedIndex] >= 0) continue
                    val id = bodies.sizes.size
                    var size = 0
                    bodies.bodyIds[start.zeroBasedIndex] = id
                    stack.add(start)
                    while (stack.isNotEmpty()) {
                        val tile = stack.removeAt(stack.lastIndex)
                        size++
                        for (neighbor in tile.neighbors) {
                            if (!neighbor.isWater || bodies.bodyIds[neighbor.zeroBasedIndex] >= 0) continue
                            bodies.bodyIds[neighbor.zeroBasedIndex] = id
                            stack.add(neighbor)
                        }
                    }
                    bodies.sizes.add(size)
                }
                synchronized(cache) { cache[civ.gameInfo.tileMap] = bodies }
                return bodies
            }
        }
    }
}
