package com.unciv.dev

import com.unciv.logic.GameInfo

/**
 * How the land units at sea fare, for [FullGameSim]'s report. Land units embark themselves (as in Civ V); a warship is to stay near them
 * (CvTacticalAI::PlotEscortEmbarkedMoves). At the end of each turn the embarked land units of every civilization are counted, with whether one
 * of the civilization's military ships stood on a tile next to them or on theirs (escorted); a unit that was embarked then and is gone from the game
 * the next turn is a loss (killed, drowned or disbanded: that cannot be told from outside), with whether it was escorted and whether an enemy
 * military unit of a civilization at war with it was within 2 tiles.
 */
class EmbarkedStats(private val game: GameInfo) {
    private class Seen(val civ: String, val military: Boolean, val escorted: Boolean, val enemyNear: Boolean)
    private class Counts {
        var unitTurns = 0
        var escortedTurns = 0
        var lost = 0
        var lostEscorted = 0
        var lostEnemyNear = 0
    }

    private val military = Counts()
    private val civilian = Counts()
    private var seen = HashMap<Int, Seen>()

    fun afterTurn() {
        val present = HashSet<Int>()
        val now = HashMap<Int, Seen>()
        for (civ in game.civilizations) {
            if (civ.isBarbarian || civ.isSpectator || civ.isCityState) continue
            for (unit in civ.units.getCivUnits()) {
                present.add(unit.id)
                if (!unit.baseUnit.isLandUnit || !unit.isEmbarked()) continue
                val tile = unit.getTile()
                val escorted = tile.getTilesInDistance(1).any { t ->
                    t.militaryUnit?.let { it.civ == civ && it.baseUnit.isWaterUnit } == true
                }
                val enemyNear = tile.getTilesInDistance(2).any { t ->
                    t.militaryUnit?.let { it.civ != civ && civ.isAtWarWith(it.civ) } == true
                }
                now[unit.id] = Seen(civ.civName, unit.isMilitary(), escorted, enemyNear)
                val counts = if (unit.isMilitary()) military else civilian
                counts.unitTurns++
                if (escorted) counts.escortedTurns++
            }
        }
        for ((id, unit) in seen) {
            if (id in present) continue
            val counts = if (unit.military) military else civilian
            counts.lost++
            if (unit.escorted) counts.lostEscorted++
            if (unit.enemyNear) counts.lostEnemyNear++
        }
        seen = now
    }

    fun report(): String {
        val text = StringBuilder("## Land units at sea\n")
        text.appendLine("Embarked land units of the major civilizations at the end of each turn; escorted = an own military ship on their tile or next to it; lost = gone the next turn")
        for ((name, counts) in listOf("military" to military, "civilian" to civilian)) {
            text.appendLine(String.format(java.util.Locale.ROOT,
                "%-9s unit-turns=%5d escorted=%5d lost=%3d (escorted %3d, enemy within 2: %3d)",
                name, counts.unitTurns, counts.escortedTurns, counts.lost, counts.lostEscorted, counts.lostEnemyNear))
        }
        return text.toString()
    }
}
