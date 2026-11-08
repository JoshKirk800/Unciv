package com.unciv.dev

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.Counter
import com.unciv.models.ruleset.GreatWorkSlotType
import com.unciv.models.ruleset.unique.UniqueType

/**
 * Follows, for every major civilization of a [FullGameSim] game, the chain that ends in filled Great Work slots: specialist slots and
 * the specialists working them, Great Person points, the Great People born and what became of each, and the slots and works at the end.
 * Read-only: it only looks at the game after each turn, so the course of the game (and its digests) is the same with or without it.
 *
 * What a Great Person was used for is seen from the turn it disappeared: a work whose creator is the unit's name is exact; an improvement
 * it can build that appeared on the civilization's tiles, or a golden age that started that turn, is inferred; anything else (bulb,
 * trade mission, hurry, concert tour, treatise, religion, death or capture) is "other". A unit born and used within one turn is never
 * seen alive: when it made a Great Work, the work shows it (counted apart); any other use of such a unit is not seen.
 */
internal class GreatPeopleTrace(private val game: GameInfo) {
    private class CivTrace {
        val specialistTurns = Counter<String>()
        val specialistSlotTurns = Counter<String>()
        val greatPersonPoints = Counter<String>()
        val born = Counter<String>()
        /** "<unit name>: <outcome>" */
        val outcomes = Counter<String>()
        /** Turns a unit that creates Great Works spent alive, by whether the civilization had a free slot for its work */
        val turnsWithSlot = Counter<String>()
        val turnsWithoutSlot = Counter<String>()
        /** "<type> by Great People" or "<type> other" (archaeology), by the civilization that got the work */
        val worksGained = Counter<String>()
        /** Works made by Great People whose unit was never seen alive, by work type */
        val worksBySameTurnUnits = Counter<String>()
        var previousUnits = HashMap<Int, MapUnit>()
        var previousImprovements = Counter<String>()
        var previousGoldenAgeTurns = 0
    }

    private val traces = LinkedHashMap<String, CivTrace>()
    private val seenWorks = HashSet<String>()
    private val greatImprovements: Set<String> = game.ruleset.units.values.filter { it.isGreatPerson }
        .flatMap { unit -> unit.getMatchingUniques(UniqueType.ConstructImprovementInstantly).map { it.params[0] } }.toSet()

    init {
        for (civ in majors()) traces[civ.civName] = CivTrace().also { remember(civ, it) }
        allWorks().forEach { seenWorks.add(it.id) }
    }

    private fun majors() = game.civilizations.filter { it.isMajorCiv && !it.isSpectator }

    private fun allWorks() = game.civilizations.asSequence().flatMap { it.cities }
        .flatMap { it.greatWorks.getWorks() + it.greatWorks.homeless }

    private fun improvementsOf(civ: Civilization): Counter<String> {
        val counter = Counter<String>()
        for (city in civ.cities) for (tile in city.getTiles())
            if (tile.improvement in greatImprovements) counter.add(tile.improvement!!, 1)
        return counter
    }

    private fun remember(civ: Civilization, trace: CivTrace) {
        trace.previousUnits = civ.units.getCivGreatPeople().associateByTo(HashMap()) { it.id }
        trace.previousImprovements = improvementsOf(civ)
        trace.previousGoldenAgeTurns = civ.goldenAges.turnsLeftForCurrentGoldenAge
    }

    fun afterTurn() {
        val ruleset = game.ruleset
        val newWorksByOwner = HashMap<String, MutableList<Pair<String, String>>>() // owner -> (great person name, work type) made this turn
        for (civ in game.civilizations) for (city in civ.cities) for (work in city.greatWorks.getWorks() + city.greatWorks.homeless) {
            if (!seenWorks.add(work.id)) continue
            val definition = ruleset.greatWorks[work.work] ?: continue
            val creator = definition.creator
            traces[civ.civName]?.worksGained?.add("${definition.type.name} ${if (creator != null) "by Great People" else "other"}", 1)
            if (creator != null) newWorksByOwner.getOrPut(civ.civName) { ArrayList() }.add(creator to definition.type.name)
        }

        for (civ in majors()) {
            val trace = traces[civ.civName] ?: continue
            for (city in civ.cities) {
                trace.specialistTurns.add(city.population.getNewSpecialists())
                trace.specialistSlotTurns.add(city.population.getMaxSpecialists())
            }
            trace.greatPersonPoints.add(civ.greatPeople.getGreatPersonPointsForNextTurn())

            val units = civ.units.getCivGreatPeople().toList()
            for (unit in units) {
                if (unit.id !in trace.previousUnits) trace.born.add(unit.name, 1)
                if (!unit.hasUnique(UniqueType.OneTimeCreateGreatWork)) continue
                val slotType = GreatWorkFunctions.getWorkOfUnit(unit)?.getSlotType()
                val hasSlot = slotType != null && civ.cities.any { it.greatWorks.getNumFreeSlots(slotType) > 0 }
                (if (hasSlot) trace.turnsWithSlot else trace.turnsWithoutSlot).add(unit.name, 1)
            }

            val gone = trace.previousUnits.values.filter { old -> units.none { it.id == old.id } }
            val worksMade = newWorksByOwner[civ.civName] ?: mutableListOf()
            val improvementsAdded = improvementsOf(civ).apply { remove(trace.previousImprovements) }
            var goldenAgeStarted = civ.goldenAges.turnsLeftForCurrentGoldenAge > trace.previousGoldenAgeTurns
            for (unit in gone) {
                val improvement = unit.getMatchingUniques(UniqueType.ConstructImprovementInstantly).map { it.params[0] }
                    .firstOrNull { (improvementsAdded[it]) > 0 }
                val outcome = when {
                    unit.instanceName != null && worksMade.removeIf { it.first == unit.instanceName } -> "Great Work"
                    improvement != null -> { improvementsAdded.add(improvement, -1); improvement }
                    goldenAgeStarted && (unit.hasUnique(UniqueType.OneTimeEnterGoldenAge) || unit.hasUnique(UniqueType.OneTimeEnterGoldenAgeTurns)) -> {
                        goldenAgeStarted = false; "golden age"
                    }
                    else -> "other"
                }
                trace.outcomes.add("${unit.name}: $outcome", 1)
            }
            for ((_, type) in worksMade) trace.worksBySameTurnUnits.add(type, 1)
            remember(civ, trace)
        }
    }

    fun report(): String {
        val text = StringBuilder()
        text.appendLine("## Great People and Great Works")
        text.appendLine("Specialists are specialist-turns used/available; turns alive are those of units that create Great Works, with/without a free slot of their kind.")
        for (civ in majors()) {
            val trace = traces[civ.civName] ?: continue
            text.appendLine(civ.civName)
            text.appendLine("  specialists: " + trace.specialistSlotTurns.keys.sorted()
                .joinToString { "$it ${trace.specialistTurns[it]}/${trace.specialistSlotTurns[it]}" })
            text.appendLine("  points: " + trace.greatPersonPoints.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" })
            text.appendLine("  Great People gained per group (sets the next threshold): " + civ.greatPeople.greatPeopleCreated.entries
                .sortedBy { it.key }.joinToString { "${it.key.ifEmpty { "(none)" }} ${it.value}" })
            val alive = civ.units.getCivGreatPeople().groupingBy { it.name }.eachCount()
            for (name in (trace.born.keys + alive.keys).sorted()) {
                val outcomes = trace.outcomes.filterKeys { it.startsWith("$name: ") }.entries
                    .joinToString { "${it.key.removePrefix("$name: ")} ${it.value}" }
                val slotTurns = if (name in trace.turnsWithSlot || name in trace.turnsWithoutSlot)
                    "; turns alive with/without slot ${trace.turnsWithSlot[name]}/${trace.turnsWithoutSlot[name]}" else ""
                text.appendLine("  $name: born ${trace.born[name]}, alive ${alive[name] ?: 0}" +
                    (if (outcomes.isEmpty()) "" else "; used: $outcomes") + slotTurns)
            }
            if (trace.worksBySameTurnUnits.isNotEmpty())
                text.appendLine("  Great Works by Great People used on the turn they were born: " +
                    trace.worksBySameTurnUnits.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" })
            text.appendLine("  works gained: " + trace.worksGained.entries.sortedBy { it.key }.joinToString { "${it.key} ${it.value}" })
            text.appendLine("  slots filled now: " + GreatWorkSlotType.entries.joinToString { type ->
                "${type.label} ${civ.cities.sumOf { it.greatWorks.getNumWorks(type) }}/${civ.cities.sumOf { city -> city.greatWorks.getSlots().count { it.getType() == type } }}"
            })
        }
        return text.toString()
    }
}
