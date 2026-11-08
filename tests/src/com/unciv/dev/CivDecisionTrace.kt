package com.unciv.dev

import com.unciv.logic.GameInfo
import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.automation.unit.SettleAreas
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.ai.AiDefine
import java.io.File

/**
 *  FullGameSim's trace of one civilization's decisions, turn by turn ([FullGameSim] with FULL_SIM_TRACE_CIV=<civ name>, written to
 *  `<report>.trace.txt`): its economy and military each turn, what the AI decided with the alternatives it weighed ([AiDecisionLog]:
 *  production with the top weights and turns, research with the top weights, settler operations), strategy changes, cities founded and lost,
 *  units lost with the enemies that stood near them the turn before, and every 10 turns its cities.
 */
class CivDecisionTrace(private val game: GameInfo, private val civName: String, file: File) {
    private val writer = file.bufferedWriter()
    private val decisions = ArrayList<String>()
    private var lastStrategies = emptySet<String>()
    private var lastGrandStrategy: String? = null
    private val lastCityStrategies = HashMap<String, Set<String>>()
    private var lastCities = emptySet<String>()
    private var lastUnits = HashMap<Int, UnitRecord>()

    private class UnitRecord(val description: String, val position: String, val enemiesNear: String)

    init {
        AiDecisionLog.sink = { civ, message -> if (civ.civName == civName) decisions.add(message) }
        writer.appendLine("# Decision trace of $civName")
    }

    private fun civ(): Civilization? = game.civilizations.firstOrNull { it.civName == civName }

    fun afterTurn(turn: Int) {
        val civ = civ() ?: return
        if (civ.isDefeated()) {
            if (lastCities.isNotEmpty()) writer.appendLine("== turn $turn: defeated")
            lastCities = emptySet()
            decisions.clear()
            return
        }
        val stats = civ.stats.statsForNextTurn
        val units = civ.units.getCivUnits().toList()
        fun count(vararg types: UnitAiType) = units.count { UnitAiTypes.current(it) in types }
        val military = civ.ai.military
        val landMilitary = units.count { it.isMilitary() && it.baseUnit.isLandUnit && UnitAiTypes.current(it) != UnitAiType.EXPLORE }
        writer.appendLine("== turn $turn  cities ${civ.cities.size} pop ${civ.cities.sumOf { it.population.population }}" +
            "  science ${stats.science.toInt()} gold ${civ.gold} (${"%+d".format(stats.gold.toInt())}) culture ${stats.culture.toInt()} " +
            "faith ${stats.faith.toInt()} happiness ${civ.getHappiness()}  techs ${civ.tech.techsResearched.size} -> ${civ.tech.currentTechnologyName()}" +
            "  land military $landMilitary (reserve ${military.mandatoryReserveSize}, recommended ${military.recommendedMilitarySize}, " +
            "${military.landDefenseState}) explorers ${count(UnitAiType.EXPLORE)} settlers ${count(UnitAiType.SETTLE)} " +
            "workers ${count(UnitAiType.WORKER)} units ${units.size}  at war with ${civ.getCivsAtWarWith().filter { !it.isBarbarian }.joinToString { it.civName }.ifEmpty { "-" }}")

        val strategies = (civ.ai.economicStrategies.keys + civ.ai.militaryStrategies.keys).toSet()
        diff("strategies", lastStrategies, strategies)
        lastStrategies = strategies
        if (civ.ai.grandStrategy != lastGrandStrategy) {
            writer.appendLine("  grand strategy ${lastGrandStrategy ?: "-"} -> ${civ.ai.grandStrategy ?: "-"}")
            lastGrandStrategy = civ.ai.grandStrategy
        }

        val cities = civ.cities.map { it.name }.toSet()
        for (name in cities - lastCities) writer.appendLine("  city gained: $name")
        for (name in lastCities - cities) writer.appendLine("  city lost: $name")
        lastCities = cities
        for (city in civ.cities) {
            val cityStrategies = city.ai.cityStrategies.keys.toSet()
            diff("${city.name} strategies", lastCityStrategies[city.name] ?: emptySet(), cityStrategies)
            lastCityStrategies[city.name] = cityStrategies
        }

        val nowUnits = HashMap<Int, UnitRecord>()
        for (unit in units) nowUnits[unit.id] = record(civ, unit)
        for ((id, record) in lastUnits) {
            if (id in nowUnits) continue
            val tile = game.tileMap.getOrNull(record.position.substringBefore(',').toInt(), record.position.substringAfter(',').toInt())
            val nowThere = tile?.getUnits()?.filter { it.civ != civ }?.joinToString { "${it.name} of ${it.civ.civName}" }.orEmpty()
            writer.appendLine("  unit gone: ${record.description} at (${record.position}); enemies within 2 the turn before: " +
                record.enemiesNear.ifEmpty { "none" } + if (nowThere.isNotEmpty()) "; now on its tile: $nowThere" else "")
        }
        lastUnits = nowUnits

        for (decision in decisions) writer.appendLine("  $decision")
        decisions.clear()
        if (turn % 10 == 0) {
            settleAreas(civ)
            for (city in civ.cities) {
                val cityStats = city.cityStats.currentCityStats
                writer.appendLine("  [${city.name}] pop ${city.population.population} food ${"%+d".format(cityStats.food.toInt())} " +
                    "(stored ${city.population.foodStored}) production ${cityStats.production.toInt()} science ${cityStats.science.toInt()} " +
                    "building ${city.cityConstructions.currentConstructionName()} specialization ${city.ai.specialization ?: "-"} " +
                    "worked ${city.workedTiles.size} specialists ${city.population.getNumberOfSpecialists()} " +
                    "improved ${city.getTiles().count { it.improvement != null && !it.isCityCenter() }}/${city.getTiles().count { !it.isCityCenter() }} " +
                    "buildings ${city.cityConstructions.getBuiltBuildings().count()}")
            }
        }
        if (turn % 25 == 0) writer.flush()
    }

    private fun diff(label: String, before: Set<String>, after: Set<String>) {
        val added = after - before
        val removed = before - after
        if (added.isEmpty() && removed.isEmpty()) return
        writer.appendLine("  $label: " + (added.map { "+$it" } + removed.map { "-$it" }).joinToString(" "))
    }

    /** What CvPlayer::GetBestSettleAreas sees: the landmasses' found value totals against the minimum, and how much of them is explored */
    private fun settleAreas(civ: Civilization) {
        val minimum = game.ruleset.ai.define(AiDefine.AI_STRATEGY_MINIMUM_SETTLE_FERTILITY)
        val found = SettleAreas.foundValues(civ)
        val best = SettleAreas.bestSettleAreas(civ, minimum)
        val capitalArea = civ.getCapital()?.getCenterTile()?.getContinent()
        val land = game.tileMap.tileList.filter { it.isLand && it.getContinent() == capitalArea }
        val sites = game.tileMap.tileList.count { found.values[it.zeroBasedIndex] > 5000 }
        writer.appendLine("  settle: good areas ${best.count} (best ${best.first}, second ${best.second}), totals " +
            found.areaTotals.entries.sortedByDescending { it.value }.take(4).joinToString { "area ${it.key}: ${it.value}" } +
            " (minimum $minimum); sites over 5000: $sites; home landmass $capitalArea explored ${land.count { civ.hasExplored(it) }}/${land.size}, " +
            "unowned ${land.count { it.getOwner() == null }}")
    }

    private fun record(civ: Civilization, unit: MapUnit): UnitRecord {
        val tile = unit.getTile()
        val enemies = tile.getTilesInDistance(2).flatMap { it.getUnits() }
            .filter { it.civ != civ && it.isMilitary() && (it.civ.isBarbarian || civ.isAtWarWith(it.civ)) }
            .joinToString { "${it.name} of ${it.civ.civName}" }
        return UnitRecord("${unit.name} (${UnitAiTypes.current(unit).name}, id ${unit.id})", "${tile.position.x},${tile.position.y}", enemies)
    }

    fun close() {
        AiDecisionLog.sink = null
        writer.close()
    }
}
