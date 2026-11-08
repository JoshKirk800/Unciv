package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.Flavor

/**
 * The strategy tests of the espionage and trade route slice: the espionage-defense flavor (EconomicAIHelpers::IsTestStrategy_TechLeader,
 * CityStrategyAIHelpers::IsTestCityStrategy_ManyTechsStolen / KeyScienceCity) and the trade unit and trade hub flavors
 * (CityStrategyAIHelpers::IsTestCityStrategy_NeedInternationalTradeRoute / NoNeedInternationalTradeRoute / IsInternationalTradeOrigin /
 * IsInternationalTradeDestination). Their XML flavors drive Constabulary, Police Station, Caravan, Cargo Ship, Market and the like.
 */
object EspionageTradeStrategyTests {
    fun registerAll(tests: StrategyTests) {
        tests.register("ECONOMICAISTRATEGY_TECH_LEADER") { civ, _ -> isTechLeader(civ) }
        tests.registerCity("AICITYSTRATEGY_MANY_TECHS_STOLEN") { city, _ -> manyTechsStolen(city) }
        tests.registerCity("AICITYSTRATEGY_KEY_SCIENCE_CITY") { city, _ -> isKeyScienceCity(city) }
        tests.registerCity("AICITYSTRATEGY_NEED_INTERNATIONAL_LAND_TRADE_ROUTE") { city, _ -> needsInternationalTradeRoute(city, isSea = false) }
        tests.registerCity("AICITYSTRATEGY_NO_NEED_INTERNATIONAL_LAND_TRADE_ROUTE") { city, _ -> !needsInternationalTradeRoute(city, isSea = false) }
        tests.registerCity("AICITYSTRATEGY_NEED_INTERNATIONAL_SEA_TRADE_ROUTE") { city, _ -> needsInternationalTradeRoute(city, isSea = true) }
        tests.registerCity("AICITYSTRATEGY_NO_NEED_INTERNATIONAL_SEA_TRADE_ROUTE") { city, _ -> !needsInternationalTradeRoute(city, isSea = true) }
        tests.registerCity("AICITYSTRATEGY_INTERNATIONAL_TRADE_DESTINATION") { city, _ -> internationalRoutesTo(city) >= 2 }
        tests.registerCity("AICITYSTRATEGY_INTERNATIONAL_TRADE_ORIGIN") { city, _ -> internationalRoutesFrom(city) >= 2 }
    }

    /** EconomicAIHelpers::IsTestStrategy_TechLeader: fewer than 5% x the espionage personality flavor of the others are ahead in technologies */
    fun isTechLeader(civ: Civilization): Boolean {
        if (civ.isHuman() || civ.espionageManager.turnEspionageStarted == -1) return false
        val ours = civ.tech.techsResearched.size
        var others = 0
        var ahead = 0
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isMajorCiv || !other.isAlive()) continue
            others++
            if (other.tech.techsResearched.size > ours) ahead++
        }
        if (others == 0) return false
        return ahead / others.toFloat() < 0.05f * civ.ai.personality(Flavor.Espionage)
    }

    /**
     * CityStrategyAIHelpers::IsTestCityStrategy_ManyTechsStolen: robbed at least once. The original divides by the turns since espionage started
     * and only if it did not start on turn 0, so a city is never "robbed" then.
     */
    fun manyTechsStolen(city: City): Boolean {
        val espionage = city.civ.espionageManager
        if (espionage.turnEspionageStarted == 0) return false
        return (espionage.timesCityRobbed[city.id] ?: 0) > 0
    }

    /** CityStrategyAIHelpers::IsTestCityStrategy_KeyScienceCity: fewer than 5% x the espionage personality flavor of our cities have more science */
    fun isKeyScienceCity(city: City): Boolean {
        val civ = city.civ
        if (civ.espionageManager.turnEspionageStarted == -1) return false
        val science = city.cityStats.currentCityStats.science
        // The original counts the city itself among the "other" cities
        val cities = civ.cities.size
        if (cities == 0) return false
        val better = civ.cities.count { it != city && it.cityStats.currentCityStats.science > science }
        return better / cities.toFloat() < 0.05f * civ.ai.personality(Flavor.Espionage)
    }

    /** CityStrategyAIHelpers::IsTestCityStrategy_NeedInternationalTradeRoute: a free slot and a possible route from this city in the domain */
    fun needsInternationalTradeRoute(city: City, isSea: Boolean): Boolean {
        val manager = city.civ.tradeRoutes
        return manager.getNumRoutesRemaining() > 0 && manager.hasPotentialConnection(city, isSea)
    }

    /** CvGameTrade::GetNumTimesDestinationCity(city, bOnlyInternational = true) */
    private fun internationalRoutesTo(city: City): Int =
        city.civ.gameInfo.civilizations.sumOf { civ -> civ.tradeRoutes.routes.count { it.isInternational() && it.destination == city.location } }

    /** CvGameTrade::GetNumTimesOriginCity(city, bOnlyInternational = true) */
    private fun internationalRoutesFrom(city: City): Int =
        city.civ.tradeRoutes.routes.count { it.isInternational() && it.origin == city.location }
}
