package com.unciv.dev

import com.unciv.logic.GameInfo
import com.unciv.logic.automation.civilization.DiplomacyAutomation
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.logic.civilization.Proximity
import java.io.File

/**
 * The per-turn AI values of every major civilization, for [FullGameSim]: the same columns as the Lua observer that was attached to the
 * original Brave New World game (`tools/attach_bnw_observer.py`), so the two games' war timing and approaches can be compared.
 *
 *     AI_CIV  turn  name  cities  militaryMight  militaryUnits
 *     AI_PAIR turn  name  other  atWar  majorCivApproach  landDispute  victoryDispute  warmongerThreat  numWarsFought  doF  opinion  doFWeight  tooEarly
 *
 * Approach is the index of CvDiplomacyAI's MajorCivApproachTypes (War, Hostile, Deceptive, Guarded, Afraid, Friendly, Neutral); the dispute
 * and threat levels are 0..4 as in the original (None, Weak/Minor, Strong/Major, Fierce/Severe, Critical). A pair is listed only when
 * the civilizations have met. Victory disputes are not kept by the port (the original's read feeds only the grand strategy) and are written as -1.
 * doF is 1 while a Declaration of Friendship stands; opinion is the index of MajorCivOpinionTypes (Unforgivable, Enemy, Competitor, Neutral,
 * Favorable, Friend, Ally), which the Lua API of the original does not expose.
 * Appended columns of AI_PAIR: proximity (the original's PlayerProximityTypes: 0 Distant, 1 Far, 2 Close, 3 Neighbors, -1 none) and
 * minCityDistance (tiles between the nearest cities of the two, -1 without cities). AI_CITY rows every 5 turns:
 *     AI_CITY turn  name  column  row  population  isCapital
 * Written once per turn after the turn has been played, so a row for turn N is the state the original's observer sees at the start of turn N+1.
 */
class DecisionLog(private val game: GameInfo, private val file: File) {
    init {
        file.writeText(
            "# AI_CIV\tturn\tname\tcities\tmilitaryMight\tmilitaryUnits\n" +
                "# AI_PAIR\tturn\tname\tother\tatWar\tmajorCivApproach\tlandDispute\tvictoryDispute\twarmongerThreat\tnumWarsFought\tdoF\topinion\tdoFWeight\ttooEarly\tproximity\tminCityDistance\n" +
                "# AI_CITY\tturn\tname\tcolumn\trow\tpopulation\tisCapital\n"
        )
    }

    private fun proximityIndex(proximity: Proximity) = when (proximity) {
        Proximity.Distant -> 0
        Proximity.Far -> 1
        Proximity.Close -> 2
        Proximity.Neighbors -> 3
        else -> -1
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
                    .append(ai.memoryOf(other).numWarsFought).append('\t')
                    .append(if (OpinionAi.isDoFAccepted(civ, other)) 1 else 0).append('\t')
                    .append(ai.opinionOf(other).ordinal).append('\t')
                    .append(DiplomacyAutomation.doFWeight(civ, other)).append('\t')
                    .append(if (DiplomacyAutomation.isTooEarlyForDoF(civ, other)) 1 else 0).append('\t')
                    .append(proximityIndex(civ.getProximity(other))).append('\t')
                    .append(civ.cities.minOfOrNull { ours -> other.cities.minOfOrNull { ours.getCenterTile().aerialDistanceTo(it.getCenterTile()) } ?: -1 } ?: -1)
                    .append('\n')
            }
            if (turn % 5 == 0) for (city in civ.cities) {
                val tile = city.getCenterTile()
                // on a copy of a Civ V map (BnwMapGrid) the plot's x and y, else the column and the row of Unciv's flat hexes
                val (column, row) = if (game.tileMap.mapParameters.civVGridWrap) BnwMapGrid.civVOf(tile.position) else tile.getColumn() to tile.getRow()
                text.append("AI_CITY\t$turn\t${civ.civName}\t$column\t$row\t${city.population.population}\t${if (city.isCapital()) 1 else 0}\n")
            }
        }
        file.appendText(text.toString())
    }
}
