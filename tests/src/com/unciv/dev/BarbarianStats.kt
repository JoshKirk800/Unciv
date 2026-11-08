package com.unciv.dev

import com.unciv.logic.GameInfo
import com.unciv.logic.automation.strategy.AiOperationType

/**
 * What the barbarians do and what the naval and nuke operations come to, for [FullGameSim]'s report: civilians the barbarians took
 * (a unit that joins them with an original owner), barbarian units that went back to a civilization (rescued), cities they took, the
 * most barbarian ships and land units alive at once, the turns a barbarian ship ended next to land a civilization owns (a coastal raid
 * in the making) and, summed over civilizations, the operations of the naval and nuke types created and succeeded.
 */
class BarbarianStats(private val game: GameInfo) {
    private var barbarianUnits = HashSet<Int>()
    private val capturedCivilians = LinkedHashMap<String, Int>()
    private var rescued = 0
    private var citiesTaken = 0
    private var cityOwners = HashMap<String, String>()
    private var maxShips = 0
    private var maxLand = 0
    private var raidTurns = 0

    fun afterTurn() {
        val barbarians = game.civilizations.firstOrNull { it.isBarbarian } ?: return
        val now = HashSet<Int>()
        var ships = 0
        var land = 0
        for (unit in barbarians.units.getCivUnits()) {
            now.add(unit.id)
            if (unit.baseUnit.isWaterUnit) {
                ships++
                val tile = unit.getTile()
                if (tile.neighbors.any { it.isLand && it.getOwner()?.let { owner -> !owner.isBarbarian } == true }) raidTurns++
            } else if (unit.isMilitary()) land++
            if (unit.id !in barbarianUnits && unit.isCivilian() && unit.originalOwner != null) {
                val owner = game.civilizations.firstOrNull { it.civID == unit.originalOwner }?.civName ?: "?"
                capturedCivilians[owner] = (capturedCivilians[owner] ?: 0) + 1
            }
        }
        for (id in barbarianUnits) if (id !in now) {
            // gone: killed, or taken back (it then lives on in a civilization with the barbarians as its former owner)
            val back = game.civilizations.any { !it.isBarbarian && it.units.getUnitById(id) != null }
            if (back) rescued++
        }
        barbarianUnits = now
        maxShips = maxOf(maxShips, ships)
        maxLand = maxOf(maxLand, land)
        val owners = HashMap<String, String>()
        for (civ in game.civilizations) for (city in civ.cities) owners[city.id] = civ.civName
        for ((id, owner) in cityOwners) if (owners[id] != owner && owners[id] == barbarians.civName) citiesTaken++
        cityOwners = owners
    }

    fun report(): String {
        val text = StringBuilder("## Barbarians, naval and nuke operations\n")
        text.appendLine("civilians taken by barbarians (by original owner): " + (capturedCivilians.entries.joinToString { "${it.key} ${it.value}" }.ifEmpty { "none" }) +
            " (total ${capturedCivilians.values.sum()}), taken back: $rescued, cities taken by barbarians: $citiesTaken")
        text.appendLine("most barbarian land units at once: $maxLand, ships: $maxShips, barbarian ship turns next to civilization land: $raidTurns")
        val totals = LinkedHashMap<AiOperationType, IntArray>()
        for (civ in game.civilizations) for ((type, counts) in civ.ai.operations.statsByType) {
            val sum = totals.getOrPut(type) { IntArray(2) }
            sum[0] += counts[0]; sum[1] += counts[1]
        }
        for (type in AiOperationType.entries) {
            val counts = totals[type] ?: continue
            text.appendLine(String.format(java.util.Locale.ROOT, "%-24s created=%3d succeeded=%3d", type.name, counts[0], counts[1]))
        }
        return text.toString()
    }
}
