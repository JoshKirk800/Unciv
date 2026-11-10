package com.unciv.dev

import com.unciv.logic.GameInfo
import java.io.File

/**
 * The per-turn AI values of every major civilization, for [FullGameSim]: the same columns as the Lua observer that was attached to the
 * original Brave New World game (`tools/attach_bnw_observer.py`), so the two games' war timing and approaches can be compared.
 *
 *     AI_CIV  turn  name  cities  militaryMight  militaryUnits
 *     AI_PAIR turn  name  other  atWar  majorCivApproach  landDispute  victoryDispute  warmongerThreat  numWarsFought
 *
 * Approach is the index of CvDiplomacyAI's MajorCivApproachTypes (War, Hostile, Deceptive, Guarded, Afraid, Friendly, Neutral); the dispute
 * and threat levels are 0..4 as in the original (None, Weak/Minor, Strong/Major, Fierce/Severe, Critical). A pair is listed only when
 * the civilizations have met. Victory disputes are not kept by the port (the original's read feeds only the grand strategy) and are written as -1.
 * Written once per turn after the turn has been played, so a row for turn N is the state the original's observer sees at the start of turn N+1.
 */
class DecisionLog(private val game: GameInfo, private val file: File) {
    init {
        file.writeText(
            "# AI_CIV\tturn\tname\tcities\tmilitaryMight\tmilitaryUnits\n" +
                "# AI_PAIR\tturn\tname\tother\tatWar\tmajorCivApproach\tlandDispute\tvictoryDispute\twarmongerThreat\tnumWarsFought\n"
        )
    }

    fun afterTurn(turn: Int) {
        val majors = game.civilizations.filter { it.isMajorCiv && !it.isSpectator && !it.isBarbarian && !it.isDefeated() }
        val text = StringBuilder()
        for (civ in majors) {
            text.append("AI_CIV\t$turn\t${civ.civName}\t${civ.cities.size}\t${civ.ai.militaryMight()}\t")
                .append(civ.units.getCivUnits().count { it.isMilitary() }).append('\n')
            for (other in majors) {
                if (other == civ || !civ.knows(other)) continue
                val ai = civ.ai.diplomacy
                text.append("AI_PAIR\t$turn\t${civ.civName}\t${other.civName}\t${civ.isAtWarWith(other)}\t")
                    .append(ai.approachTowards(other).ordinal).append('\t')
                    .append(ai.landDisputeLevel(other).ordinal).append("\t-1\t")
                    .append(ai.warmongerThreat(other).ordinal).append('\t')
                    .append(ai.memoryOf(other).numWarsFought).append('\n')
            }
        }
        file.appendText(text.toString())
    }
}
