package com.unciv.logic.league

import com.unciv.logic.civilization.Civilization

/**
 * The one-time work of a World Congress resolution that comes into force or goes out of force.
 * The ongoing effects are read from the [League] by the engine (trade, happiness, tourism, religion, public opinion)
 * or come from the uniques of the resolution.
 */
object LeagueEffects {
    /** A resolution was enacted: embargoes end the trade routes they forbid (CvTradeClasses.cpp:907-973, LR 4.3.1 and 4.3.2) */
    fun onEnact(league: League, active: ActiveResolution) {
        val gameInfo = league.gameInfo
        val resolution = gameInfo.ruleset.resolutions[active.resolution] ?: return

        if (resolution.embargoesCityStates) {
            // CvGameTrade::ClearAllCityStateTradeRoutes: every route to a city-state ends, the trade unit returns to the origin
            for (civ in gameInfo.civilizations)
                civ.tradeRoutes.cancelRoutesByEmbargo { it.getDestinationCiv()?.isCityState == true }
        }
        if (resolution.embargoesPlayer) {
            val target = active.choice?.let { id -> gameInfo.civilizations.firstOrNull { it.civID == id } }
            if (target != null) {
                // CvGameTrade::ClearAllCivTradeRoutes: routes from or to the target, but not those within the target
                for (civ in gameInfo.civilizations) {
                    civ.tradeRoutes.cancelRoutesByEmbargo { route ->
                        val destination = route.getDestinationCiv()
                        (civ == target || destination == target) && !(civ == target && destination == target)
                    }
                }
            }
        }
        refresh(league, resolution.bansLuxuryHappiness, resolution.ideologyPressure != 0)
    }

    /** A resolution was repealed: nothing is restored, but what depended on it is recalculated */
    fun onRepeal(league: League, active: ActiveResolution) {
        val resolution = league.gameInfo.ruleset.resolutions[active.resolution] ?: return
        refresh(league, resolution.bansLuxuryHappiness, resolution.ideologyPressure != 0)
    }

    private fun refresh(league: League, luxuries: Boolean, ideology: Boolean) {
        for (civ in league.gameInfo.civilizations) {
            if (civ.isBarbarian || civ.isSpectator) continue
            refresh(civ, luxuries, ideology)
        }
    }

    private fun refresh(civ: Civilization, luxuries: Boolean, ideology: Boolean) {
        if (luxuries) civ.cache.updateCivResources()
        if (ideology && civ.isMajorCiv) civ.culture.publicOpinion.update()
        for (city in civ.cities) city.cityStats.update()
    }
}
