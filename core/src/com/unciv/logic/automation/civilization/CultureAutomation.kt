package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.Proximity
import com.unciv.logic.civilization.managers.InfluenceLevel

/**
 * The culture side of open borders (CvDiplomacyAI::IsWantsOpenBordersWithPlayer, CvDealAI::GetOpenBordersValue): an AI on the culture grand strategy
 * wants open borders, for their tourism modifier, with the civilizations it still needs influence on.
 */
object CultureAutomation {

    /** INFLUENCE_LEVEL_INFLUENTIAL as a number, which CvDealAI::GetOpenBordersValue compares with raw influence */
    private const val INFLUENCE_LEVEL_INFLUENTIAL_ENUM = 4

    /**
     * CvPlayerCulture::GetCivLowestInfluence(bCheckOpenBorders = false): the other living major civilization, not at war with us, on which our influence
     * is the lowest share of its lifetime culture, below the Influential level; the first in civilization order on a tie. Null if there is none.
     */
    fun getCivLowestInfluence(civ: Civilization): Civilization? {
        var lowestPercent = InfluenceLevel.Influential.getThresholdPercent(civ)
        var lowest: Civilization? = null
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !other.isMajorCiv || !other.isAlive() || civ.isAtWarWith(other)) continue
            val percent = civ.culture.getInfluencePercent(other)
            if (percent < lowestPercent) {
                lowestPercent = percent
                lowest = other
            }
        }
        return lowest
    }

    /** Culture grand strategy with some tourism: the condition of both culture branches below */
    private fun isPursuingCultureWithTourism(civ: Civilization): Boolean =
        civ.ai.grandStrategy == GrandStrategyAi.CULTURE && civ.ai.tourism() > 0

    /** Influential on at least as many civilizations as are left to influence (GetNumCivsToBeInfluentialOn <= GetNumCivsInfluentialOn) */
    private fun isInfluentialOnHalf(civ: Civilization): Boolean =
        civ.culture.getOtherMajorCivs().size <= civ.culture.getNumCivsInfluentialOn()

    private fun isBelowInfluential(civ: Civilization, other: Civilization): Boolean =
        (civ.culture.getInfluenceLevel(other) ?: InfluenceLevel.Unknown) < InfluenceLevel.Influential

    /**
     * The culture branch of CvDiplomacyAI::IsWantsOpenBordersWithPlayer: on the culture grand strategy with tourism, always the civilization with our
     * lowest influence, and, once we are influential on half of them, every civilization we are not yet influential on.
     * The rest of the DLL function (neighbours, approach, military strength) belongs to the diplomacy AI.
     */
    fun wantsOpenBordersWith(civ: Civilization, other: Civilization): Boolean {
        if (!isPursuingCultureWithTourism(civ)) return false
        if (getCivLowestInfluence(civ) == other) return true
        return isInfluentialOnHalf(civ) && isBelowInfluential(civ, other)
    }

    /**
     * The culture factors of CvDealAI::GetOpenBordersValue, in percent of the value without them, for the deal AI to apply as `value * percent / 100`.
     * - [receiving] (the other civilization gives us open borders): on the culture grand strategy with tourism, 1000 for the civilization with our lowest
     *   influence, else 500 for one we are not influential on once we are on half of them, else 300 for a neighbour if we are cramped; else 100.
     * - Giving: 500 if we guess the other is on the culture grand strategy, has tourism and its influence on us is below INFLUENCE_LEVEL_INFLUENTIAL.
     *   As in the DLL the raw influence is compared with the number of that level (4), so this only holds before it has any real influence on us.
     */
    fun openBordersValuePercent(civ: Civilization, other: Civilization, receiving: Boolean): Int {
        if (receiving) {
            if (!isPursuingCultureWithTourism(civ)) return 100
            return when {
                getCivLowestInfluence(civ) == other -> 1000
                isInfluentialOnHalf(civ) -> if (isBelowInfluential(civ, other)) 500 else 100
                civ.getProximity(other) == Proximity.Neighbors && civ.ai.isCramped -> 300
                else -> 100
            }
        }
        if (civ.ai.guessGrandStrategy(other).first == GrandStrategyAi.CULTURE
            && other.ai.tourism() > 0 && other.culture.getInfluence(civ) < INFLUENCE_LEVEL_INFLUENTIAL_ENUM) return 500
        return 100
    }
}
