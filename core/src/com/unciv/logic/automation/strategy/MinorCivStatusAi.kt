package com.unciv.logic.automation.strategy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.models.ruleset.ai.AiDefine

/** CvMinorCivAI MinorCivStatusTypes: how threatened a city-state feels */
enum class MinorCivStatus { Normal, Elevated, Critical }

/** CvMinorCivAI::DoTurnStatus and the CvTeam tests it reads */
object MinorCivStatusAi {

    /** CvTeam::IsMinorCivAggressor: declared war on at least MINOR_CIV_AGGRESSOR_THRESHOLD city-states */
    fun isMinorCivAggressor(civ: Civilization): Boolean =
        civ.numMinorCivsAttacked >= civ.gameInfo.ruleset.ai.define(AiDefine.MINOR_CIV_AGGRESSOR_THRESHOLD)

    /** CvTeam::IsMinorCivWarmonger: declared war on at least MINOR_CIV_WARMONGER_THRESHOLD city-states */
    fun isMinorCivWarmonger(civ: Civilization): Boolean =
        civ.numMinorCivsAttacked >= civ.gameInfo.ruleset.ai.define(AiDefine.MINOR_CIV_WARMONGER_THRESHOLD)

    /**
     *  CvMinorCivAI::DoTurnStatus: per living major by its proximity to the city-state - CLOSE: +10 if it is a minor civ aggressor,
     *  +20 if a warmonger; NEIGHBORS: +20 if an aggressor, +20 if at war with the city-state (DISTANT and FAR count nothing).
     *  CRITICAL from 20, ELEVATED from 10.
     */
    fun status(cityState: Civilization): MinorCivStatus {
        var weight = 0
        for (major in cityState.gameInfo.civilizations) {
            if (!major.isMajorCiv || !major.isAlive()) continue
            when (major.getProximity(cityState)) {
                Proximity.Close -> {
                    if (isMinorCivAggressor(major)) weight += 10
                    if (isMinorCivWarmonger(major)) weight += 20
                }
                Proximity.Neighbors -> {
                    if (isMinorCivAggressor(major)) weight += 20
                    if (cityState.isAtWarWith(major)) weight += 20
                }
                else -> {}
            }
        }
        return when {
            weight >= 20 -> MinorCivStatus.Critical
            weight >= 10 -> MinorCivStatus.Elevated
            else -> MinorCivStatus.Normal
        }
    }
}
