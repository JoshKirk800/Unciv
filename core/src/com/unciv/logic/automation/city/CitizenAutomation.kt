package com.unciv.logic.automation.city

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.city.City
import com.unciv.logic.city.CityFocus
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.Specialist
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import kotlin.math.min
import kotlin.math.roundToInt

/**
 *  The citizen governor of the original (CvCityCitizens.cpp): the work focus of AI cities and puppets, and the value of tiles and
 *  specialists by which every city places its citizens (the original runs the same placement code for humans and AI).
 *
 *  Unciv state used for the original's: [CityFocus] = CityAIFocusTypes (Manual is NO_CITY_AI_FOCUS_TYPE for valuation; HappinessFocus
 *  has no original counterpart and multiplies nothing), [City.manualSpecialists] = m_bNoAutoAssignSpecialists (an AI city has no forced
 *  specialists, so the flag only stops automatic assignment), [City.avoidGrowth] = m_bForceAvoidGrowth, locked tiles = forced plots,
 *  unemployed citizens = default specialists (1 Production each, see CityStats' "Population" entry).
 */
object CitizenAutomation {
    private const val LOSING_MONEY = "ECONOMICAISTRATEGY_LOSING_MONEY"
    private const val FORCED_PLOT_BONUS = 10000

    /**
     *  CvCityCitizens::DoTurn: AI cities and puppets choose their focus, then every city reallocates its citizens.
     *  Each focus or flag change reallocates at once in the original; here the reallocation is deferred until the food surplus is read
     *  or the turn's final reallocation, which gives the same result because a reallocation depends only on the city's state.
     */
    fun doTurn(city: City): Unit = timeThis("AI.CitizenFocus") {
        val civ = city.civ
        var dirty = city.shouldReassignPopulation
        var reallocated = false

        fun reallocate() {
            if (city.isPuppet) city.reassignAllPopulation() else city.reassignPopulation()
            dirty = false
            reallocated = true
        }
        fun setFocus(focus: CityFocus) {
            if (city.getCityFocus() == focus) return
            city.setCityFocus(focus)
            dirty = true
        }
        fun setNoAutoAssignSpecialists(value: Boolean) {
            if (city.manualSpecialists == value) return
            city.manualSpecialists = value
            dirty = true
        }
        fun setForcedAvoidGrowth(value: Boolean) {
            if (city.avoidGrowth == value) return
            city.avoidGrowth = value
            dirty = true
        }
        fun excessFood(): Float {
            if (dirty) reallocate()
            return city.cityStats.excessFood
        }

        if (city.isPuppet) {
            setFocus(CityFocus.GoldFocus)
            setNoAutoAssignSpecialists(false)
            setForcedAvoidGrowth(false)
            if (excessFood() < 0f) setFocus(CityFocus.NoFocus)
        } else if (!civ.isHuman()) {
            val turn = civ.gameInfo.turns
            val isWonderCity = city.ai.specializationDef()?.isWonder == true
            if (turn % 8 == 0) {
                setFocus(CityFocus.GoldGrowthFocus)
                setNoAutoAssignSpecialists(true)
                setForcedAvoidGrowth(false)
                if (excessFood() < 2f) setFocus(CityFocus.NoFocus)
            }
            if (city.isCapital() && !civ.isCityState && !isWonderCity) {
                setFocus(CityFocus.NoFocus)
                setNoAutoAssignSpecialists(false)
                setForcedAvoidGrowth(false)
                if (excessFood() < 4f) setFocus(CityFocus.FoodFocus)
            } else if (isWonderCity) {
                setFocus(CityFocus.ProductionFocus)
                setNoAutoAssignSpecialists(false)
                setForcedAvoidGrowth(false)
                if (excessFood() < 2f) setFocus(CityFocus.ProductionGrowthFocus)
                if (excessFood() < 2f) setFocus(CityFocus.NoFocus)
            } else if (city.population.population < 5) {
                setFocus(CityFocus.NoFocus)
                setForcedAvoidGrowth(false)
            } else if (civ.ai.isEconomicStrategyActive(LOSING_MONEY)) {
                setFocus(CityFocus.GoldGrowthFocus)
                setNoAutoAssignSpecialists(false)
                setForcedAvoidGrowth(false)
                if (excessFood() < 2f) setFocus(CityFocus.NoFocus)
            } else if (turn % 3 == 0 && civ.ai.grandStrategy == GrandStrategyAi.CULTURE) {
                setFocus(CityFocus.CultureFocus)
                setNoAutoAssignSpecialists(true)
                setForcedAvoidGrowth(false)
                if (excessFood() < 2f) setFocus(CityFocus.NoFocus)
            } else {
                setNoAutoAssignSpecialists(false)
                setForcedAvoidGrowth(false)
                setFocus(specializationFocus(city))
            }
        }

        // The original reallocates every city every turn; a deferred reallocation that already ran with the final state is that one
        if (dirty || !reallocated) reallocate()
    }

    /** The focus for the city specialization's yield, from the last branch of CvCityCitizens::DoTurn */
    private fun specializationFocus(city: City): CityFocus = when (city.ai.specializationDef()?.yieldType) {
        Stat.Food.name -> CityFocus.FoodFocus
        Stat.Production.name -> CityFocus.ProductionGrowthFocus
        Stat.Gold.name -> CityFocus.GoldFocus
        Stat.Science.name -> CityFocus.ScienceFocus
        else -> CityFocus.NoFocus
    }

    /** CvCityCitizens::IsAvoidGrowth: the empire is unhappy, or the city avoids growth */
    fun isAvoidGrowth(city: City): Boolean = city.civ.getHappiness() < 0 || city.avoidGrowth

    /**
     *  The yield values of CvCityCitizens::GetPlotValue and GetSpecialistValue for one city, with the city's surplus food ([excessFood],
     *  kept current by the caller while it places citizens, as the original re-reads the city's yield).
     */
    class Valuation(private val city: City) {
        private val rules = city.civ.gameInfo.ruleset.ai
        private val foodValue = rules.define(AiDefine.AI_CITIZEN_VALUE_FOOD)
        private val productionValue = rules.define(AiDefine.AI_CITIZEN_VALUE_PRODUCTION)
        private val goldValue = rules.define(AiDefine.AI_CITIZEN_VALUE_GOLD)
        private val scienceValue = rules.define(AiDefine.AI_CITIZEN_VALUE_SCIENCE)
        private val cultureValue = rules.define(AiDefine.AI_CITIZEN_VALUE_CULTURE)
        private val faithValue = rules.define(AiDefine.AI_CITIZEN_VALUE_FAITH)

        val focus = city.getCityFocus()
        private val avoidGrowth = isAvoidGrowth(city)
        /** A focus other than none, food and the growth focuses: food only matters until the city stops starving */
        private val isNonFoodFocus = focus != CityFocus.NoFocus && focus != CityFocus.Manual && focus != CityFocus.FoodFocus
            && focus != CityFocus.ProductionGrowthFocus && focus != CityFocus.GoldGrowthFocus
        private val boostSmallCityFood = !isNonFoodFocus && focus != CityFocus.FoodFocus && !avoidGrowth && city.population.population < 5

        /** CvPlayer::isHalfSpecialistFood: specialists eat less */
        val isHalfSpecialistFood = hasSpecialistUnique(UniqueType.FoodConsumptionByPopulation)
        /** CvPlayer::isHalfSpecialistUnhappiness */
        val isHalfSpecialistUnhappiness = hasSpecialistUnique(UniqueType.UnhappinessFromPopulationTypePercentageChange)
        private val specialistHappinessValue =
            if (!isHalfSpecialistUnhappiness) 0 else if (city.civ.getHappiness() < 0) 10 else 5

        /** Surplus food (getYieldRateTimes100(YIELD_FOOD) - foodConsumption() * 100, in whole food) */
        var excessFood = city.cityStats.excessFood

        private fun hasSpecialistUnique(type: UniqueType): Boolean {
            var found = false
            city.forEachMatchingUnique(type, city.state) {
                if (it.params[1].startsWith("Specialists") && it.params[0].toFloat() < 0f && city.matchesFilter(it.params[2])) found = true
            }
            return found
        }

        /** CvCityCitizens::GetPlotValue for a tile yielding [stats] */
        fun plotValue(stats: Stats, useAllowGrowthFlag: Boolean): Int =
            value(stats.food.roundToInt(), stats, useAllowGrowthFlag)

        /** CvCityCitizens::GetSpecialistValue: [stats] are the specialist's yields in this city */
        fun specialistValue(specialist: Specialist, stats: Stats): Int {
            val food = stats.food.roundToInt() + if (isHalfSpecialistFood) 1 else 0
            val greatPersonValue = specialist.greatPersonPoints.sumValues() * 3
            return value(food, stats, true) + greatPersonValue + specialistHappinessValue
        }

        private fun value(food: Int, stats: Stats, useAllowGrowthFlag: Boolean): Int {
            var foodYieldValue = foodValue * food
            var productionYieldValue = productionValue * stats.production.roundToInt()
            var goldYieldValue = goldValue * stats.gold.roundToInt()
            var scienceYieldValue = scienceValue * stats.science.roundToInt()
            var cultureYieldValue = cultureValue * stats.culture.roundToInt()
            var faithYieldValue = faithValue * stats.faith.roundToInt()
            when (focus) {
                CityFocus.FoodFocus -> foodYieldValue *= 3
                CityFocus.ProductionFocus -> productionYieldValue *= 3
                CityFocus.GoldFocus -> goldYieldValue *= 3
                CityFocus.ScienceFocus -> scienceYieldValue *= 3
                CityFocus.CultureFocus -> cultureYieldValue *= 3
                CityFocus.FaithFocus -> faithYieldValue *= 3
                CityFocus.GoldGrowthFocus -> { foodYieldValue *= 2; goldYieldValue *= 2 }
                CityFocus.ProductionGrowthFocus -> { foodYieldValue *= 2; productionYieldValue *= 2 }
                else -> {}
            }

            if (useAllowGrowthFlag && excessFood >= 0f && avoidGrowth) foodYieldValue = 0
            else if (isNonFoodFocus) {
                if (excessFood < 0f) foodYieldValue *= 8 else foodYieldValue /= 2
            } else if (!avoidGrowth) {
                if (excessFood < 2f) foodYieldValue *= 8
                else if (focus != CityFocus.FoodFocus) foodYieldValue /= 2
            }
            if (boostSmallCityFood) foodYieldValue *= 4

            return foodYieldValue + productionYieldValue + goldYieldValue + scienceYieldValue + cultureYieldValue + faithYieldValue
        }

        /** CvCityCitizens::IsBetterThanDefaultSpecialist: the specialist's own yield of the focused stat is at least an unemployed citizen's */
        fun isBetterThanDefaultSpecialist(specialist: Specialist): Boolean {
            val stat = when (focus) {
                CityFocus.FoodFocus, CityFocus.ProductionGrowthFocus, CityFocus.GoldGrowthFocus -> Stat.Food
                CityFocus.ProductionFocus -> Stat.Production
                CityFocus.GoldFocus -> Stat.Gold
                CityFocus.ScienceFocus -> Stat.Science
                CityFocus.CultureFocus -> Stat.Culture
                CityFocus.FaithFocus -> Stat.Faith
                else -> return true
            }
            var specialistYield = specialist[stat]
            if (isHalfSpecialistUnhappiness || isHalfSpecialistFood) specialistYield *= 2
            return specialistYield >= DEFAULT_SPECIALIST_YIELDS[stat]
        }
    }

    /** An unemployed citizen's yields (CityStats' "Population" entry): the original's SPECIALIST_CITIZEN */
    private val DEFAULT_SPECIALIST_YIELDS = Stats(production = 1f)

    /**
     *  The specialist slots of the city's buildings. Unciv counts specialists per type, not per building; they are spread over the
     *  buildings of their type the way the original fills them (the building with the most slots first, as GetAIBestSpecialistBuilding prefers it).
     */
    private class SpecialistSlots(city: City) {
        val specialists: Array<Specialist>
        private val slots: IntArray
        private val typeIndex: IntArray
        private val fillOrder: Array<Int>
        val typeNames: Array<String>
        private val filled: IntArray
        private val remaining: IntArray
        private val ruleset = city.getRuleset()

        init {
            val entrySpecialists = ArrayList<Specialist>()
            val entrySlots = ArrayList<Int>()
            for (building in city.cityConstructions.getBuiltBuildings()) {
                val buildingSlots = building.newSpecialists()
                for (name in buildingSlots.keys) {
                    val amount = buildingSlots[name]
                    val specialist = ruleset.specialists[name] ?: continue
                    if (amount <= 0) continue
                    entrySpecialists += specialist
                    entrySlots += amount
                }
            }
            specialists = entrySpecialists.toTypedArray()
            slots = entrySlots.toIntArray()
            typeNames = specialists.map { it.name }.distinct().toTypedArray()
            typeIndex = IntArray(specialists.size) { typeNames.indexOf(specialists[it].name) }
            fillOrder = specialists.indices.sortedByDescending { slots[it] }.toTypedArray()
            filled = IntArray(specialists.size)
            remaining = IntArray(typeNames.size)
        }

        val size get() = specialists.size

        /** Spreads the current allocation over the buildings */
        fun distribute(allocations: Map<String, Int>) {
            for (t in typeNames.indices) remaining[t] = allocations[typeNames[t]] ?: 0
            for (i in fillOrder) {
                val t = typeIndex[i]
                val inBuilding = min(slots[i], remaining[t])
                filled[i] = inBuilding
                remaining[t] -= inBuilding
            }
        }

        fun hasFreeSlot(i: Int) = filled[i] < slots[i]
        fun isStaffed(i: Int) = filled[i] > 0
        fun slotCount(i: Int) = slots[i]
        fun typeOf(i: Int) = typeIndex[i]
    }

    /**
     *  One placement pass over a city: CvCityCitizens::DoAddBestCitizenFromUnassigned and DoRemoveWorstCitizen, sharing the tile yields,
     *  the specialist slots and the running food surplus.
     */
    class Governor(private val city: City) {
        val valuation = Valuation(city)
        private val slots = SpecialistSlots(city)
        private val specialistStats = Array(slots.typeNames.size) { city.cityStats.getStatsOfSpecialist(slots.typeNames[it]) }
        private val specialistValues = IntArray(slots.typeNames.size)
        /** Workable tiles ring by ring, as the original's city plot indices (ties go to the first) */
        private val candidateTiles: List<Tile> = city.getWorkableTiles().filter { !it.isBlockaded() && !it.isCityCenter() }
            .sortedBy { it.aerialDistanceTo(city.getCenterTile()) }.toList()
        private val tileStats = HashMap<Tile, Stats>(candidateTiles.size)

        private fun statsOf(tile: Tile) = tileStats.getOrPut(tile) { tile.stats.getTileStats(city, city.civ) }

        /** CvCityCitizens::GetAIBestSpecialistBuilding: index of the slot entry to staff, or -1; [bestValue] gets its unmodified value */
        private var bestValue = 0
        private fun bestSpecialistBuilding(): Int {
            if (slots.size == 0) return -1
            slots.distribute(city.population.specialistAllocations)
            for (t in specialistValues.indices) specialistValues[t] = Int.MIN_VALUE
            var best = -1
            var bestModified = -1
            for (i in 0 until slots.size) {
                if (!slots.hasFreeSlot(i)) continue
                val t = slots.typeOf(i)
                if (specialistValues[t] == Int.MIN_VALUE)
                    specialistValues[t] = valuation.specialistValue(slots.specialists[i], specialistStats[t])
                val value = specialistValues[t]
                // 10% more per extra slot, so one building is filled before specialists spread out
                val modified = value + (slots.slotCount(i) - 1) * value * 10 / 100
                if (modified > bestModified) {
                    best = i
                    bestModified = modified
                    bestValue = value
                }
            }
            return best
        }

        /** CvCityCitizens::GetBestCityPlotWithValue(bWantBest = true, bWantWorked = false); [bestValue] gets the value, -1 if none */
        private fun bestUnworkedTile(): Tile? {
            var best: Tile? = null
            var bestTileValue = -1
            for (tile in candidateTiles) {
                if (tile.providesYield()) continue
                var value = valuation.plotValue(statsOf(tile), true)
                if (tile.isLocked()) value += FORCED_PLOT_BONUS
                if (best == null || value > bestTileValue) {
                    best = tile
                    bestTileValue = value
                }
            }
            bestValue = bestTileValue
            return best
        }

        private fun addSpecialist(i: Int) {
            val t = slots.typeOf(i)
            city.population.specialistAllocations.add(slots.typeNames[t], 1)
            valuation.excessFood += specialistStats[t].food + if (valuation.isHalfSpecialistFood) 1f else 0f
        }

        /** CvCityCitizens::DoAddBestCitizenFromUnassigned for one unemployed citizen; false if it stays unemployed */
        fun addBestCitizen(): Boolean {
            val bestBuilding = if (city.manualSpecialists) -1 else bestSpecialistBuilding()
            val specialistValue = bestValue
            val betterThanSlacker = bestBuilding != -1 && valuation.isBetterThanDefaultSpecialist(slots.specialists[bestBuilding])

            val bestTile = bestUnworkedTile()
            val specialistBetterThanTile = bestBuilding != -1 && specialistValue >= bestValue

            if (specialistBetterThanTile && betterThanSlacker) {
                addSpecialist(bestBuilding)
                return true
            }
            if (!specialistBetterThanTile && bestTile != null) {
                city.workTile(bestTile)
                valuation.excessFood += statsOf(bestTile).food
                return true
            }
            // No tile left and no good specialist: AI players (and humans who let specialists be assigned) take any acceptable one
            if (!city.civ.isHuman() || !city.manualSpecialists) {
                val building = bestSpecialistBuilding()
                if (building != -1 && valuation.isBetterThanDefaultSpecialist(slots.specialists[building])) {
                    addSpecialist(building)
                    return true
                }
            }
            return false
        }

        /** CvCityCitizens::DoRemoveWorstCitizen: the worst worked tile (locked ones last), else a specialist; false if nobody works */
        fun removeWorstCitizen(): Boolean {
            var worst: Tile? = null
            var worstValue = 0
            for (tile in city.getWorkedTiles()) {
                var value = valuation.plotValue(statsOf(tile), false)
                if (tile.isLocked()) value += FORCED_PLOT_BONUS
                if (worst == null || value < worstValue) {
                    worst = tile
                    worstValue = value
                }
            }
            if (worst != null) {
                city.stopWorkingTile(worst)
                valuation.excessFood -= statsOf(worst).food
                return true
            }
            // CvCityCitizens::DoRemoveWorstSpecialist: from the first building that has one
            slots.distribute(city.population.specialistAllocations)
            for (i in 0 until slots.size) {
                if (!slots.isStaffed(i)) continue
                val t = slots.typeOf(i)
                city.population.specialistAllocations.add(slots.typeNames[t], -1)
                valuation.excessFood -= specialistStats[t].food + if (valuation.isHalfSpecialistFood) 1f else 0f
                return true
            }
            val allocations = city.population.specialistAllocations
            val anySpecialist = allocations.keys.firstOrNull { allocations[it] > 0 } ?: return false
            allocations.add(anySpecialist, -1)
            return true
        }
    }
}
