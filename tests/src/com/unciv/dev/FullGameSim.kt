package com.unciv.dev

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.GameInfo
import com.unciv.logic.GameStarter
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.MapShape
import com.unciv.logic.map.MapType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.GdxTestRunner
import com.unciv.ui.audio.MusicController
import org.junit.Assert
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID

/**
 * An AI-only game played from the first turn to its end: a victory, one civilization left, or the turn limit.
 * Where [ParitySim] compares two source versions over a fixed number of turns, this one checks a single version over a whole
 * game, and reports what happened to the late-game systems (eras, ideologies, public opinion, tourism and influence, the
 * World Congress, religion). Skipped unless the environment variable FULL_SIM_OUT is set; `tools/full_sim.py` runs it.
 * The report also follows, per civilization, the specialists, Great People and Great Works behind its culture ([GreatPeopleTrace]).
 *
 * Environment: FULL_SIM_OUT = report file, FULL_SIM_START = the starting game (generated and saved there when missing),
 * FULL_SIM_MAX_TURNS (default 700), FULL_SIM_WALL_MINUTES (default 150), FULL_SIM_PLAY_ON (any value: keep playing after a victory),
 * FULL_SIM_CIVS (8), FULL_SIM_CITYSTATES (8), FULL_SIM_SEED (7), FULL_SIM_RULESET (default Civ V - Brave New World),
 * FULL_SIM_MAPSIZE (Tiny, Small, Medium, Large or Huge; default Medium), FULL_SIM_MAPTYPE (default Small Continents).
 * FULL_SIM_MAPWIDTH and FULL_SIM_MAPHEIGHT together select a custom rectangular map; FULL_SIM_WORLD_WRAP enables horizontal wrap.
 * FULL_SIM_WATER_THRESHOLD sets the generated map's water threshold (default 0).
 * FULL_SIM_NATIONS = comma-separated exact names of major nations (`The Huns,Babylon`): these play instead of the first FULL_SIM_CIVS
 * majors (an unknown or non-major name stops the run). FULL_SIM_VICTORIES = comma-separated victory type names that replace the default
 * (every selectable victory without a turn limit; an unknown name stops the run). FULL_SIM_START_UNITS = `Nation=Unit,Unit;Nation2=Unit`:
 * after the game is generated these units are placed for the civilization on a free land tile near its first unit (they are in the
 * start file, which is generated once and reused). FULL_SIM_TRACE_CIV = a civilization name, a comma-separated list of names or `all`
 * (every major): [CivDecisionTrace] writes `<report>.trace.txt` for one civilization, `<report>.trace.<Civ>.txt` (spaces as underscores)
 * for several. The report's header names the nations, map, seed, victories and start units of the game.
 *
 * Reproducibility: FULL_SIM_DIGESTS = a file that gets one line per turn, `turn <n> <hash of the whole game state>` (the wall-clock
 * turn start time and the random ids of great works are left out of the hash). Two runs from the same start file must write the
 * same lines, `tools/determinism_check.py` compares them and names the first turn that differs. FULL_SIM_DUMP_TURNS (comma-separated)
 * (or `all`) with FULL_SIM_DUMP_DIR writes the full state JSON, gzipped, after those turns, so that two runs can be diffed.
 *
 * FULL_SIM_PERTURB_HASH = n: ask for n identity hash codes before the game starts (see the comment in `play`).
 *
 * Performance ([SimPerformance]): every turn's wall time, CPU time, allocation, garbage collection and heap go to `<report>.perf.csv`
 * and into the report. FULL_SIM_SPANS (any value) also times every `timeThis` span, into `<report>.spans.csv`; this slows the hottest
 * spans, so compare runs with spans only with runs with spans. `tools/perf_compare.py` compares two runs.
 *
 * The report is rewritten every 25 turns so that a hang or a crash still leaves the story up to that point. The state of the
 * last 25-turn mark is saved to `<report>.checkpoint.save` (and, on a crash, the exception is in the report), so that the turn
 * that failed can be replayed with FULL_SIM_START pointing at the checkpoint.
 *
 * What it checks every turn (a violation is listed, it does not stop the game; an exception does): no negative free tenets,
 * at most one ideology adopted, tenets only with their own ideology, no unfinished numbers in the stats, cities that belong
 * to the civilization that lists them, and a population of at least 1.
 */
@RunWith(GdxTestRunner::class)
class FullGameSim {
    companion object {
        @BeforeClass @JvmStatic
        fun setup() {
            Assume.assumeTrue("Set FULL_SIM_OUT to run", System.getenv("FULL_SIM_OUT") != null)
            UncivGame.Current = UncivGame()
            UncivGame.Current.settings = GameSettings().apply { musicVolume = 0f; soundEffectsVolume = 0f; citySoundsVolume = 0f; voicesVolume = 0f }
            UncivGame.Current.files = UncivFiles(Gdx.files)
            UncivGame.Current.musicController = MusicController()
            RulesetCache.loadRulesets(noMods = true)
        }
    }

    private fun env(name: String, default: String) = System.getenv(name) ?: default

    private fun startGame(): GameInfo {
        val ruleset = RulesetCache[env("FULL_SIM_RULESET", BaseRuleset.Civ_V_BNW.fullName)]!!
        val players = ArrayList<Player>()
        val nations = list("FULL_SIM_NATIONS")
        if (nations.isEmpty()) {
            for (nation in ruleset.nations.values) {
                if (players.size >= env("FULL_SIM_CIVS", "8").toInt()) break
                if (nation.isMajorCiv) players.add(Player(nation.name, PlayerType.AI))
            }
        } else {
            require(nations.distinct().size == nations.size) { "FULL_SIM_NATIONS names a nation twice: $nations" }
            for (name in nations) {
                val nation = ruleset.nations[name] ?: error("FULL_SIM_NATIONS: unknown nation '$name'")
                require(nation.isMajorCiv) { "FULL_SIM_NATIONS: '$name' is not a major civilization" }
                players.add(Player(name, PlayerType.AI))
            }
        }
        players.add(Player("Spectator", PlayerType.Human))
        val startUnits = startUnits(ruleset)
        for (nation in startUnits.keys) require(players.any { it.chosenCiv == nation }) { "FULL_SIM_START_UNITS: '$nation' does not play in this game" }
        val victories = list("FULL_SIM_VICTORIES")
        for (name in victories) require(name in ruleset.victories) { "FULL_SIM_VICTORIES: unknown victory '$name', known: ${ruleset.victories.keys}" }
        val parameters = GameParameters().apply {
            baseRuleset = ruleset.name
            difficulty = "Prince"
            speed = "Standard"
            numberOfCityStates = env("FULL_SIM_CITYSTATES", "8").toInt()
            // as the new game screen does: every victory that does not need a turn limit; without any, the game never ends
            victoryTypes.addAll(victories.ifEmpty { ruleset.selectableVictories().filter { !it.enablesMaxTurns() }.map { it.name } })
            shufflePlayerOrder = false
            this.players = players
        }
        val mapParameters = MapParameters().apply {
            type = env("FULL_SIM_MAPTYPE", MapType.smallContinents)
            val width = env("FULL_SIM_MAPWIDTH", "").toIntOrNull()
            val height = env("FULL_SIM_MAPHEIGHT", "").toIntOrNull()
            if (width != null || height != null) {
                require(width != null && height != null && width > 0 && height > 0) { "FULL_SIM_MAPWIDTH and FULL_SIM_MAPHEIGHT must both be positive" }
                shape = MapShape.rectangular
                mapSize = MapSize(width, height)
            } else {
                mapSize = when (env("FULL_SIM_MAPSIZE", "Medium")) {
                    "Tiny" -> MapSize.Tiny
                    "Small" -> MapSize.Small
                    "Large" -> MapSize.Large
                    "Huge" -> MapSize.Huge
                    else -> MapSize.Medium
                }
            }
            worldWrap = System.getenv("FULL_SIM_WORLD_WRAP") != null
            waterThreshold = env("FULL_SIM_WATER_THRESHOLD", "0").toFloat()
            seed = env("FULL_SIM_SEED", "7").toLong()
        }
        val game = GameStarter.startNewGame(GameSetupInfo(parameters, mapParameters), gameId = UUID.nameUUIDFromBytes("full-sim-${mapParameters.seed}".toByteArray()).toString())
        for ((nation, units) in startUnits) {
            val civ = game.civilizations.first { it.civName == nation }
            val position = civ.units.getCivUnits().firstOrNull()?.getTile()?.position ?: error("FULL_SIM_START_UNITS: $nation has no unit to place its units next to")
            for (unit in units) civ.units.placeUnitNearTile(position, unit) ?: error("FULL_SIM_START_UNITS: no free tile for $unit of $nation")
        }
        return game
    }

    /** The comma-separated names in an environment variable, empty when it is not set */
    private fun list(name: String) = env(name, "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

    /** FULL_SIM_START_UNITS parsed: nation -> unit names, each checked against the ruleset */
    private fun startUnits(ruleset: com.unciv.models.ruleset.Ruleset): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        for (entry in env("FULL_SIM_START_UNITS", "").split(';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val nation = entry.substringBefore('=').trim()
            require('=' in entry) { "FULL_SIM_START_UNITS: '$entry' is not Nation=Unit,Unit" }
            require(nation in ruleset.nations) { "FULL_SIM_START_UNITS: unknown nation '$nation'" }
            val units = entry.substringAfter('=').split(',').map { it.trim() }.filter { it.isNotEmpty() }
            for (unit in units) require(unit in ruleset.units) { "FULL_SIM_START_UNITS: unknown unit '$unit'" }
            result[nation] = (result[nation] ?: emptyList()) + units
        }
        return result
    }

    /** The civilizations FULL_SIM_TRACE_CIV names (`all` = every major), each of which must play in this game */
    private fun traceCivs(game: GameInfo): List<String> {
        val names = list("FULL_SIM_TRACE_CIV")
        if (names == listOf("all")) return players(game).map { it.civName }
        val known = players(game).map { it.civName }
        for (name in names) require(name in known) { "FULL_SIM_TRACE_CIV: '$name' is not a major civilization of this game, known: $known" }
        return names.distinct()
    }

    private fun players(game: GameInfo) =
        game.civilizations.filter { !it.isSpectator && !it.isBarbarian && it.isMajorCiv }.sortedBy { it.civName }

    private fun ideologyOf(civ: Civilization) = civ.policies.getIdeology()?.name

    private val violations = LinkedHashMap<String, Int>()
    private val violationSamples = ArrayList<String>()
    private var performance: SimPerformance? = null
    private var trace: GreatPeopleTrace? = null
    private var combatLosses: CombatLosses? = null
    private var barbarianStats: BarbarianStats? = null
    private var embarkedStats: EmbarkedStats? = null

    private fun violation(turn: Int, text: String) {
        val key = text.substringBefore(':')
        violations[key] = (violations[key] ?: 0) + 1
        if (violationSamples.size < 40 && violations[key]!! <= 3) violationSamples.add("turn $turn: $text")
    }

    // Json leaves out a field that equals its default, so a clock value is sometimes there and sometimes not: remove the key
    private val clockFields = Regex("\"(currentTurnStartTime|totalTurnTimeSeconds)\":\\d+,?")
    private val randomIds = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    /** The state of the whole game as JSON, without what differs between runs by design (wall-clock time, random ids) */
    private fun stateJson(game: GameInfo) =
        randomIds.replace(clockFields.replace(UncivFiles.gameInfoToString(game, forceZip = false), ""), "~")

    private fun digest(text: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    private fun checkInvariants(game: GameInfo, turn: Int) {
        for (civ in game.civilizations.filter { !it.isSpectator && !it.isBarbarian }) {
            if (civ.isDefeated()) continue
            val ruleset = game.ruleset
            if (civ.isMajorCiv) {
                if (civ.policies.freeTenets < 0) violation(turn, "negative free tenets: ${civ.civName} ${civ.policies.freeTenets}")
                val ideologies = ruleset.policyBranches.values.filter { it.isIdeology && civ.policies.isAdopted(it.name) }
                if (ideologies.size > 1) violation(turn, "several ideologies: ${civ.civName} ${ideologies.map { it.name }}")
                val own = ideologies.firstOrNull()
                for (name in civ.policies.getAdoptedPolicies()) {
                    val policy = ruleset.policies[name] ?: continue
                    if (policy.isTenet && policy.branch != own)
                        violation(turn, "tenet without its ideology: ${civ.civName} has $name, ideology ${own?.name}")
                }
            }
            if (civ.gold !in -10_000_000..10_000_000) violation(turn, "gold out of range: ${civ.civName} ${civ.gold}")
            for (city in civ.cities) {
                if (city.civ != civ) violation(turn, "city of another civ: ${city.name} listed by ${civ.civName}")
                if (city.population.population < 1) violation(turn, "empty city: ${city.name} of ${civ.civName}")
            }
        }
    }

    private fun summary(game: GameInfo, turn: Int, wallSeconds: Long, out: StringBuilder) {
        out.appendLine("== turn $turn  (${wallSeconds}s wall)")
        for (civ in players(game)) {
            val line = String.format(Locale.ROOT,
                "%-16s alive=%-5s era=%-18s cities=%2d pop=%3d techs=%3d policies=%2d gold=%5d happiness=%3d units=%3d landMil=%3d garrisoned=%2d/%2d inArmies=%2d ops=%d/%d/%d culture=%d tourism=%d influential=%d works=%d ideology=%s opinion=%s religion=%s",
                civ.civName, !civ.isDefeated(), civ.getEra().name, civ.cities.size, civ.cities.sumOf { it.population.population },
                civ.tech.researchedTechnologies.size, civ.policies.getAdoptedPolicies().size, civ.gold,
                civ.stats.happiness, civ.units.getCivUnitsSize(),
                civ.units.getCivUnits().count { it.isMilitary() && it.baseUnit.isLandUnit },
                civ.cities.count { city -> city.getCenterTile().militaryUnit.let { it != null && it.civ == civ && it.baseUnit.isLandUnit && (it.isFortified() || !it.canFortify(ignoreAlreadyFortified = true)) } }, civ.cities.size,
                civ.units.getCivUnits().count { civ.ai.operations.armyOf(it) != null && it.baseUnit.isLandUnit },
                civ.ai.operations.statsLaunched, civ.ai.operations.statsSucceeded, civ.ai.operations.statsAborted, civ.policies.storedCulture,
                civ.culture.getTourism(), civ.culture.getNumCivsInfluentialOn(), civ.cities.sumOf { it.greatWorks.getNumWorks() },
                ideologyOf(civ) ?: "-", civ.culture.publicOpinion.type.label, civ.religionManager.religion?.getReligionDisplayName() ?: "-")
            out.appendLine(line)
        }
        val league = game.league
        out.appendLine(if (league == null) "league: none"
            else "league: members=${league.members.size} unitedNations=${league.unitedNations} active=${league.activeResolutions.map { it.resolution }} turnsUntilSession=${league.turnsUntilSession}")
    }

    private fun report(out: StringBuilder, events: List<String>, game: GameInfo, ended: String) {
        val text = StringBuilder()
        text.appendLine("# Full game simulation, ${game.gameParameters.baseRuleset}, ${game.civilizations.count { it.isMajorCiv && !it.isSpectator }} majors, ${game.civilizations.count { it.isCityState }} city-states")
        text.appendLine("ended: $ended")
        // the game as the report's reader needs to know it; line 2 stays the `ended:` line
        text.appendLine("nations: ${players(game).joinToString(", ") { it.civName }}")
        val map = game.tileMap.mapParameters
        text.appendLine("map: ${map.mapSize.name}, ${map.type}, ${game.tileMap.tileList.size} tiles, seed ${map.seed}, ${game.civilizations.count { it.isCityState }} city-states")
        if (map.mapSize.name == MapSize.custom)
            text.appendLine("map geometry: ${map.mapSize}, ${map.shape}, ${game.tileMap.tileList.count { it.isLand }} land tiles, wrap=${map.worldWrap}, water threshold=${map.waterThreshold}")
        text.appendLine("victories: ${game.gameParameters.victoryTypes.joinToString(", ")}")
        text.appendLine("start units: ${env("FULL_SIM_START_UNITS", "").ifEmpty { "none" }}")
        text.appendLine()
        text.appendLine("## Events")
        events.forEach { text.appendLine(it) }
        text.appendLine()
        text.appendLine("## Invariant violations")
        if (violations.isEmpty()) text.appendLine("none")
        else {
            violations.forEach { (key, count) -> text.appendLine("$count x $key") }
            violationSamples.forEach { text.appendLine("  e.g. $it") }
        }
        text.appendLine()
        trace?.let { text.append(it.report()).appendLine() }
        combatLosses?.let { text.append(it.report()).appendLine() }
        barbarianStats?.let { text.append(it.report()).appendLine() }
        embarkedStats?.let { text.append(it.report()).appendLine() }
        performance?.let { text.append(it.report()).appendLine() }
        text.appendLine("## Snapshots")
        text.append(out)
        File(System.getenv("FULL_SIM_OUT")!!).writeText(text.toString())
    }

    @Test
    fun play() {
        val maxTurns = env("FULL_SIM_MAX_TURNS", "700").toInt()
        val wallLimitMillis = env("FULL_SIM_WALL_MINUTES", "150").toLong() * 60_000
        val playOn = System.getenv("FULL_SIM_PLAY_ON") != null
        val startFile = File(System.getenv("FULL_SIM_START") ?: error("Set FULL_SIM_START to the starting game file"))
        if (!startFile.isFile) startFile.writeText(UncivFiles.gameInfoToString(startGame(), forceZip = true))
        val game = UncivFiles.gameInfoFromString(startFile.readText())
        UncivGame.Current.gameInfo = game

        // Identity hash codes of objects are handed out in the order they are first asked for, and which codes the objects get differs
        // between runs. A game whose course depends on them (iterating a hash set of objects that do not define hashCode) is not
        // reproducible; with this the checker asks for a number of them first, to see whether the course changes.
        repeat(env("FULL_SIM_PERTURB_HASH", "0").toInt()) { System.identityHashCode(Any()) }
        val trace = GreatPeopleTrace(game).also { this.trace = it }
        val combatLosses = CombatLosses(game).also { this.combatLosses = it }
        val barbarianStats = BarbarianStats(game).also { this.barbarianStats = it }
        val embarkedStats = EmbarkedStats(game).also { this.embarkedStats = it }
        val outPath = System.getenv("FULL_SIM_OUT")!!
        val spans = System.getenv("FULL_SIM_SPANS") != null
        val performance = SimPerformance(game, spans, File("$outPath.perf.csv"), if (spans) File("$outPath.spans.csv") else null)
            .also { this.performance = it }
        val traceNames = traceCivs(game)
        val civTraces = traceNames.map { name ->
            val file = if (traceNames.size == 1) "$outPath.trace.txt" else "$outPath.trace.${name.replace(' ', '_')}.txt"
            CivDecisionTrace(game, name, File(file))
        }
        val out = StringBuilder()
        val events = ArrayList<String>()
        val checkpoint = File(System.getenv("FULL_SIM_OUT")!! + ".checkpoint.save")
        val startedAt = System.currentTimeMillis()
        val digestFile = System.getenv("FULL_SIM_DIGESTS")?.let { File(it).apply { writeText("") } }
        val dumpAll = env("FULL_SIM_DUMP_TURNS", "") == "all"
        val dumpTurns = env("FULL_SIM_DUMP_TURNS", "").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
        val dumpDir = System.getenv("FULL_SIM_DUMP_DIR")?.let { File(it).apply { mkdirs() } }
        if (digestFile != null || dumpDir != null) { // the start itself: two generated worlds from one seed must be the same
            val state = stateJson(game)
            digestFile?.appendText("turn ${game.turns} ${digest(state)}\n")
            if (dumpDir != null) java.util.zip.GZIPOutputStream(File(dumpDir, "state-${game.turns}.json.gz").outputStream()).use { it.write(state.toByteArray()) }
        }
        val lastEra = HashMap<String, String>()
        val lastIdeology = HashMap<String, String?>()
        val lastOpinion = HashMap<String, String>()
        val wasDefeated = HashSet<String>()
        val atWar = HashSet<String>() // "civ1|civ2" of majors at war with each other, in player order
        var lastSession = game.league?.lastResultsTurn ?: -1
        var leagueFounded = game.league != null
        var ended = "turn limit $maxTurns"

        fun event(turn: Int, text: String) = events.add("turn $turn: $text")
        // Military units that left the game (killed, captured, disbanded; an upgrade keeps the id) and cities that changed hands, per snapshot interval
        var knownMilitary = HashMap<String, String>() // "civ#unit id" -> civ
        val militaryGone = HashMap<String, Int>()
        val cityOwners = HashMap<String, String>()

        for (turn in 1..maxTurns) {
            try {
                performance.beforeTurn()
                game.nextTurn()
                performance.afterTurn(turn)
            } catch (ex: Throwable) {
                event(turn, "EXCEPTION in nextTurn: $ex")
                ex.stackTrace.take(25).forEach { events.add("    at $it") }
                ended = "exception on turn $turn"
                break
            }
            checkInvariants(game, turn)
            trace.afterTurn()
            combatLosses.afterTurn()
            barbarianStats.afterTurn()
            embarkedStats.afterTurn()
            for (civTrace in civTraces) civTrace.afterTurn(turn)
            val dump = dumpDir != null && (dumpAll || turn in dumpTurns)
            if (digestFile != null || dump) {
                val state = stateJson(game)
                digestFile?.appendText("turn ${game.turns} ${digest(state)}\n")
                if (dump) java.util.zip.GZIPOutputStream(File(dumpDir, "state-$turn.json.gz").outputStream()).use { it.write(state.toByteArray()) }
            }

            for (civ in players(game)) {
                val era = civ.getEra().name
                if (lastEra.put(civ.civName, era) != era && turn > 1) event(turn, "${civ.civName} enters the $era")
                val ideology = ideologyOf(civ)
                if (lastIdeology.containsKey(civ.civName) && lastIdeology[civ.civName] != ideology)
                    event(turn, "${civ.civName} ideology ${lastIdeology[civ.civName] ?: "none"} -> ${ideology ?: "none"} (tenets ${civ.policies.getAdoptedPolicies().count { game.ruleset.policies[it]?.isTenet == true }})")
                lastIdeology[civ.civName] = ideology
                val opinion = civ.culture.publicOpinion.type.label
                if (lastOpinion.put(civ.civName, opinion) != opinion && (opinion != "Content" || turn > 1))
                    event(turn, "${civ.civName} public opinion $opinion (unhappiness ${civ.culture.publicOpinion.unhappiness})")
                if (civ.isDefeated() && wasDefeated.add(civ.civName)) event(turn, "${civ.civName} is defeated")
            }
            val nowMilitary = HashMap<String, String>()
            for (civ in game.civilizations) {
                if (civ.isSpectator || civ.isBarbarian) continue
                for (unit in civ.units.getCivUnits()) if (unit.isMilitary()) nowMilitary["${civ.civName}#${unit.id}"] = civ.civName
                for (city in civ.cities) {
                    val before = cityOwners.put(city.id, civ.civName)
                    if (before != null && before != civ.civName) event(turn, "city changed hands: ${city.name} from $before to ${civ.civName}")
                }
            }
            for ((key, owner) in knownMilitary) if (key !in nowMilitary) militaryGone.merge(owner, 1, Int::plus)
            knownMilitary = nowMilitary
            val majors = players(game)
            for ((i, civ) in majors.withIndex()) for (other in majors.drop(i + 1)) {
                val pair = "${civ.civName}|${other.civName}"
                val fighting = civ.knows(other) && civ.isAtWarWith(other)
                if (fighting && atWar.add(pair)) event(turn, "war: ${civ.civName} and ${other.civName}")
                else if (!fighting && atWar.remove(pair) && !civ.isDefeated() && !other.isDefeated())
                    event(turn, "peace: ${civ.civName} and ${other.civName}")
            }
            val league = game.league
            if (league != null && !leagueFounded) { leagueFounded = true; event(turn, "World Congress founded") }
            if (league != null && league.lastResultsTurn != lastSession) {
                lastSession = league.lastResultsTurn
                event(turn, "World Congress session held, results ${league.lastResults.map { "${it.resolution}${if (it.passed) "+" else "-"}" }}")
            }

            if (turn % 25 == 0) {
                event(turn, "military units gone since the start: " + militaryGone.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" })
                summary(game, turn, (System.currentTimeMillis() - startedAt) / 1000, out)
                checkpoint.writeText(UncivFiles.gameInfoToString(game, forceZip = true))
                report(out, events, game, "running, turn $turn")
            }
            val victory = game.victoryData
            if (victory != null) {
                event(turn, "VICTORY: ${victory.winningCiv} by ${victory.victoryType} (turn ${victory.victoryTurn})")
                if (!playOn) { ended = "${victory.winningCiv} won by ${victory.victoryType} on turn $turn"; break }
            }
            if (players(game).count { !it.isDefeated() } <= 1) { ended = "one civilization left on turn $turn"; break }
            if (System.currentTimeMillis() - startedAt > wallLimitMillis) { ended = "wall-clock limit on turn $turn"; break }
        }
        val turn = game.turns
        summary(game, turn, (System.currentTimeMillis() - startedAt) / 1000, out)
        report(out, events, game, ended)
        performance.close()
        for (civTrace in civTraces) civTrace.close()
        Assert.assertFalse("The game threw: see the report", ended.startsWith("exception"))
    }
}
