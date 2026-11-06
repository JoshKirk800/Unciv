package com.unciv.ui.screens.leaguescreens

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.league.League
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.Resolution
import com.unciv.testing.TestGame

/**
 * A Brave New World game with the human [a] and the major civilizations [b] and [c], all having met,
 * and a founded World Congress hosted by [a]. Two of the three may propose: [a] and [otherProposer].
 */
class LeagueUiFixture {
    val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    val a: Civilization
    val b: Civilization
    val c: Civilization
    val league: League
    private var nextCell = 0

    val gameInfo get() = game.gameInfo

    init {
        game.makeHexagonalMap(12)
        gameInfo.gameParameters.victoryTypes.add("Diplomatic")
        a = major(isPlayer = true)
        b = major()
        c = major()
        val majors = listOf(a, b, c)
        for (x in majors) for (y in majors) if (x != y) x.diplomacyFunctions.makeCivilizationsMeet(y)
        a.tech.addTechnology("Printing Press")
        league = League.tryFound(gameInfo)!!
        gameInfo.league = league
    }

    /** The civilization that may propose besides the host */
    val otherProposer: Civilization get() = listOf(b, c).first { league.canPropose(it) }

    private fun major(isPlayer: Boolean = false): Civilization {
        val civ = game.addCiv(isPlayer = isPlayer)
        game.addCity(civ, game.tileMap.tileList[7 * nextCell++], replacePalace = true)
        return civ
    }

    fun resolution(name: String): Resolution = game.ruleset.resolutions[name]!!

    /** Everybody who may propose has proposed: the session starts now, delegates are handed out */
    fun beginSession() {
        league.turnsUntilSession = 0
        league.doTurn()
        check(league.inSession) { "the session should have started" }
    }
}
