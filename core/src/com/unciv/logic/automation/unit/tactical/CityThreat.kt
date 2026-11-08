package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine

/** What the danger map says about our cities: CvMilitaryAI::GetMostThreatenedCity and the city threat tests that read it */
object CityThreat {

    /**
     *  CvMilitaryAI::GetMostThreatenedCity: the city with the [order]-th highest threat value ([com.unciv.logic.automation.unit.PlotDanger.cityThreat])
     *  x population (x AI_MILITARY_CITY_THREAT_WEIGHT_CAPITAL percent for the capital), counting only cities with a positive value;
     *  null if there are fewer than [order] + 1. Ties go to the earlier city.
     */
    fun mostThreatenedCity(civ: Civilization, order: Int = 0): City? {
        val danger = civ.ai.plotDanger
        val capitalWeight = civ.gameInfo.ruleset.ai.define(AiDefine.AI_MILITARY_CITY_THREAT_WEIGHT_CAPITAL)
        val ranked = civ.cities.map { city ->
            var value = danger.cityThreat(city).toLong() * city.population.population
            if (city.isCapital()) value = value * capitalWeight / 100
            city to value
        }.filter { it.second > 0 }
        return ranked.sortedByDescending { it.second }.getOrNull(order)?.first
    }

    /** CityStrategyAIHelpers::IsTestCityStrategy_CapitalUnderThreat: the capital of a civilization at war that is its most threatened city, with a threat value above 200 */
    fun isCapitalUnderThreat(city: City): Boolean {
        if (!city.isCapital()) return false
        val civ = city.civ
        if (civ.isCityState) return false
        val atPeace = civ.gameInfo.civilizations.none { it != civ && !it.isBarbarian && !it.isSpectator && it.isAlive() && civ.isAtWarWith(it) }
        if (atPeace) return false
        return mostThreatenedCity(civ) == city && civ.ai.plotDanger.cityThreat(city) > CAPITAL_THREAT_VALUE
    }

    private const val CAPITAL_THREAT_VALUE = 200
}
