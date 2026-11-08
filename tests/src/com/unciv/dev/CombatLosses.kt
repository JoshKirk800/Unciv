package com.unciv.dev

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization

/**
 * What the wars cost each civilization, for [FullGameSim]'s report: the military units that vanished from the game (not captured: military
 * units are never captured) while a military unit of a civilization at war with them stood within 2 tiles of where they were at the end of
 * the turn before - killed, as far as that can be told from outside (a unit that disbands or upgrades with an enemy near counts too) -
 * and the cities that changed owner or disappeared. Barbarians are counted as enemies, not as a row of the table.
 */
class CombatLosses(private val game: GameInfo) {
    private class Seen(val civ: Civilization, val land: Boolean, val tile: com.unciv.logic.map.tile.Tile, val enemyNear: Boolean)
    private class Row {
        var landLost = 0
        var otherLost = 0
        var citiesLost = 0
        var citiesTaken = 0
    }

    private val rows = LinkedHashMap<String, Row>()
    private var units = HashMap<Int, Seen>()
    private var cityOwners = HashMap<String, String>()

    private fun row(civ: Civilization) = rows.getOrPut(civ.civName) { Row() }

    private fun enemyNear(civ: Civilization, unit: com.unciv.logic.map.mapunit.MapUnit) =
        unit.getTile().getTilesInDistance(2).any { tile ->
            tile.militaryUnit?.let { it.civ != civ && civ.isAtWarWith(it.civ) } == true
        }

    fun afterTurn() {
        val present = HashSet<Int>()
        val now = HashMap<Int, Seen>()
        for (civ in game.civilizations) {
            if (civ.isBarbarian || civ.isSpectator) continue
            for (unit in civ.units.getCivUnits()) {
                present.add(unit.id)
                if (unit.isMilitary()) now[unit.id] = Seen(civ, unit.baseUnit.isLandUnit, unit.getTile(), enemyNear(civ, unit))
            }
        }
        for ((id, seen) in units) {
            if (id in present || !seen.enemyNear) continue
            val row = row(seen.civ)
            if (seen.land) row.landLost++ else row.otherLost++
        }
        units = now

        val owners = HashMap<String, String>()
        for (civ in game.civilizations) for (city in civ.cities) owners[city.id] = civ.civName
        for ((id, owner) in cityOwners) {
            val newOwner = owners[id]
            if (newOwner == owner) continue
            row(game.civilizations.first { it.civName == owner }).citiesLost++
            if (newOwner != null) row(game.civilizations.first { it.civName == newOwner }).citiesTaken++
        }
        cityOwners = owners
    }

    fun report(): String {
        val text = StringBuilder("## Combat losses\n")
        text.appendLine("Military units gone with an enemy within 2 tiles the turn before, cities that changed owner or were destroyed (a city-state row is the sum of all)")
        var cityStates = Row()
        val cityStateRows = ArrayList<Row>()
        for ((name, row) in rows) {
            val civ = game.civilizations.firstOrNull { it.civName == name }
            if (civ != null && civ.isCityState) { cityStateRows.add(row); continue }
            text.appendLine(String.format(java.util.Locale.ROOT, "%-16s land units lost=%3d other units lost=%3d cities lost=%2d cities taken=%2d", name, row.landLost, row.otherLost, row.citiesLost, row.citiesTaken))
        }
        cityStates = Row().also { sum ->
            for (row in cityStateRows) { sum.landLost += row.landLost; sum.otherLost += row.otherLost; sum.citiesLost += row.citiesLost; sum.citiesTaken += row.citiesTaken }
        }
        text.appendLine(String.format(java.util.Locale.ROOT, "%-16s land units lost=%3d other units lost=%3d cities lost=%2d cities taken=%2d", "city-states", cityStates.landLost, cityStates.otherLost, cityStates.citiesLost, cityStates.citiesTaken))
        return text.toString()
    }
}
