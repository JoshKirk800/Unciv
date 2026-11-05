package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.ui.audio.MusicController
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/**
 * A fixed AI-only game, played headless, to compare two versions of the sources: run it on each tree and diff the files
 * (`tools/ab_sim.py` does both and prints the difference). Skipped unless the environment variable PARITY_SIM_OUT is set.
 *
 * Environment: PARITY_SIM_OUT = output file, PARITY_SIM_START = the starting game, PARITY_SIM_TURNS (default 150),
 * PARITY_SIM_CIVS (default 6 AI civilizations), PARITY_SIM_CITYSTATES (default 4), PARITY_SIM_SEED (map seed, default 42).
 *
 * The starting game is a saved file: when PARITY_SIM_START exists it is loaded, otherwise a game is generated and saved there.
 * Generating is not reproducible (the world comes from a random game id), so every run that is to be compared must load the
 * same file. Keep it outside the repository; it stays valid for older and newer sources.
 *
 * The output is a summary every 25 turns and at the end: per civilization the city count, population, technologies, policies,
 * gold, happiness and unit count. It is a behaviour fingerprint,
 * not an assertion: a change that should not touch AI play must leave it identical (check with two runs of the same tree first,
 * some AI decisions are not seeded), and a change that should must move the numbers it claims to.
 */
@RunWith(GdxTestRunner::class)
class ParitySim {
    companion object {
        @BeforeClass @JvmStatic
        fun setup() {
            Assume.assumeTrue("Set PARITY_SIM_OUT to run", System.getenv("PARITY_SIM_OUT") != null)
            UncivGame.Current = UncivGame()
            UncivGame.Current.settings = GameSettings().apply { musicVolume = 0f; soundEffectsVolume = 0f; citySoundsVolume = 0f; voicesVolume = 0f }
            UncivGame.Current.files = UncivFiles(Gdx.files)
            UncivGame.Current.musicController = MusicController() // nextTurn picks a track every tenth turn
            RulesetCache.loadRulesets(noMods = true)
        }
    }

    private fun env(name: String, default: String) = System.getenv(name) ?: default

    private fun startGame(civs: Int, cityStates: Int, seed: Long): GameInfo {
        val ruleset = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!
        val players = ArrayList<Player>()
        for (nation in ruleset.nations.values) {
            if (players.size >= civs) break
            if (nation.isMajorCiv) players.add(Player(nation.name, PlayerType.AI))
        }
        players.add(Player("Spectator", PlayerType.Human)) // Unciv needs one human; a spectator takes no turn
        val parameters = GameParameters().apply {
            baseRuleset = ruleset.name
            difficulty = "Prince"
            speed = "Standard"
            numberOfCityStates = cityStates
            shufflePlayerOrder = false
            this.players = players
        }
        val mapParameters = MapParameters().apply {
            type = MapType.pangaea
            mapSize = MapSize.Small
            this.seed = seed
        }
        return GameStarter.startNewGame(GameSetupInfo(parameters, mapParameters))
    }

    private fun summary(game: GameInfo, turn: Int, out: StringBuilder) {
        out.appendLine("== turn $turn")
        for (civ in game.civilizations.filter { !it.isSpectator && !it.isBarbarian }.sortedBy { it.civName }) {
            val line = String.format(Locale.ROOT,
                "%-16s alive=%-5s cities=%2d pop=%3d techs=%3d policies=%2d gold=%5d happiness=%3d units=%3d culture=%d",
                civ.civName, !civ.isDefeated(), civ.cities.size, civ.cities.sumOf { it.population.population },
                civ.tech.researchedTechnologies.size, civ.policies.getAdoptedPolicies().size, civ.gold,
                civ.stats.happiness, civ.units.getCivUnitsSize(), civ.policies.storedCulture)
            out.appendLine(line)
        }
    }

    @Test
    fun play() {
        val turns = env("PARITY_SIM_TURNS", "150").toInt()
        val startFile = File(System.getenv("PARITY_SIM_START") ?: error("Set PARITY_SIM_START to the starting game file"))
        if (!startFile.isFile) // a game that was just generated is played from the file too, so that this run is comparable with the next
            startFile.writeText(UncivFiles.gameInfoToString(
                startGame(env("PARITY_SIM_CIVS", "6").toInt(), env("PARITY_SIM_CITYSTATES", "4").toInt(), env("PARITY_SIM_SEED", "42").toLong()),
                forceZip = true))
        val game = UncivFiles.gameInfoFromString(startFile.readText())
        UncivGame.Current.gameInfo = game
        val out = StringBuilder()
        for (turn in 1..turns) {
            game.nextTurn()
            if (turn % 25 == 0 || turn == turns) summary(game, turn, out)
        }
        File(System.getenv("PARITY_SIM_OUT")!!).writeText(out.toString())
    }
}
