package com.unciv.logic.map.mapgenerator.mapregions

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.TileMap
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.equalsPlaceholderText
import com.unciv.models.translations.getPlaceholderParameters
import kotlin.random.Random

/**
 *  Which civ gets which region: AssignStartingPlots:BalanceAndAssign, for rulesets that set `startAssignmentLikeCivV`.
 *
 *  Every civ is in one group, the first that fits: a start by the sea (a Coast start bias; civs that are placed first go first), then
 *  (not used by the rulesets) a start by a river, then a preferred type of region (the positive biases), then types of region to avoid.
 *  The groups take their regions one after the other from what is left, always at random among the fitting ones:
 *  the coast civs a region by the sea, else by a lake, else, failing both, by a river or near one; a civ with one preferred type
 *  first (in the order of the types), then those with several by their number, a civ without a fitting region takes the region that has the most
 *  of its terrain; a civ that avoids types takes a region that is none of them (the longest lists first);
 *  the rest is dealt out at random.
 */
object CivVStartAssignment {

    private val regionTypeOrder = listOf("Tundra", "Jungle", "Forest", "Desert", "Hill", "Plains", "Grassland", "Hybrid")

    private fun typeNumber(type: String) = regionTypeOrder.indexOf(type) + 1

    fun assign(
        regions: List<Region>, civs: List<Civilization>, ruleset: Ruleset, tileMap: TileMap, noStartBias: Boolean, rng: Random,
        assignCiv: (Civilization, Region) -> Unit
    ) {
        if (noStartBias) {
            for ((civ, region) in civs.shuffled(rng).zip(regions)) assignCiv(civ, region)
            return
        }
        val available = regions.shuffled(rng).toMutableList()
        val regionTaken = HashSet<Region>()
        val civPlaced = HashSet<Civilization>()
        fun place(civ: Civilization, region: Region) {
            assignCiv(civ, region)
            civPlaced.add(civ)
            regionTaken.add(region)
            available.remove(region)
        }

        fun startTile(region: Region) = tileMap[region.startPosition!!]
        fun byTheSea(region: Region) = startTile(region).isAdjacentToCoast()
        fun byALake(region: Region) = startTile(region).neighbors.any { it.baseTerrain == "Lakes" }
        fun onARiver(region: Region) = startTile(region).isAdjacentToRiver()
        fun nearARiver(region: Region) = startTile(region).neighbors.any { it.isAdjacentToRiver() }

        val biases = civs.associateWith { it.nation.getStartBias(ruleset, it.getGameContextForStartBias()) }
        fun avoids(civ: Civilization) = biases[civ]!!.filter { it.equalsPlaceholderText("Avoid []") }.map { it.getPlaceholderParameters()[0] }
        fun prefers(civ: Civilization) = biases[civ]!!.filter { it != "Coast" && !it.equalsPlaceholderText("Avoid []") }
        fun placedFirst(civ: Civilization) = civ.nation.hasUnique(UniqueType.StartsFirstAlongOcean)

        val coastCivs = civs.filter { "Coast" in biases[it]!! }
        val others = civs - coastCivs.toSet()
        val priorityCivs = others.filter { prefers(it).isNotEmpty() }
        val avoidCivs = (others - priorityCivs.toSet()).filter { avoids(it).isNotEmpty() }
        val remainingCoastCivs = coastCivs.toMutableList()

        // 1. The coast
        if (coastCivs.isNotEmpty()) {
            val seaRegions = regions.filter { it !in regionTaken && byTheSea(it) }
            val lakeRegions = if (seaRegions.size < coastCivs.size) regions.filter { it !in regionTaken && byALake(it) && !byTheSea(it) } else emptyList()
            val unassignable = maxOf(0, coastCivs.size - (seaRegions.size + lakeRegions.size))
            if (coastCivs.size - unassignable > 0) {
                val order = coastCivs.filter { placedFirst(it) }.shuffled(rng) + coastCivs.filterNot { placedFirst(it) }.shuffled(rng)
                val shuffledSea = seaRegions.shuffled(rng)
                val shuffledLake = lakeRegions.shuffled(rng)
                for ((index, civ) in order.withIndex()) {
                    if (index >= coastCivs.size - unassignable) break
                    val region = if (index < seaRegions.size) shuffledSea[index] else shuffledLake[index - seaRegions.size]
                    place(civ, region)
                    remainingCoastCivs.remove(civ)
                }
            }
        }

        // 2. A river, for the coast civs that found neither the sea nor a lake
        if (remainingCoastCivs.isNotEmpty()) {
            val riverRegions = regions.filter { it !in regionTaken && onARiver(it) }
            val nearRiverRegions = regions.filter { it !in regionTaken && nearARiver(it) && !onARiver(it) }
            if (riverRegions.size + nearRiverRegions.size > 0) {
                val shuffledRiver = riverRegions.shuffled(rng)
                val shuffledNear = nearRiverRegions.shuffled(rng)
                for ((index, civ) in remainingCoastCivs.shuffled(rng).withIndex()) {
                    if (index >= riverRegions.size + nearRiverRegions.size) break
                    place(civ, if (index < riverRegions.size) shuffledRiver[index] else shuffledNear[index - riverRegions.size])
                }
            }
        }

        // 3. The types of region a civ wants
        if (priorityCivs.isNotEmpty()) {
            val single = priorityCivs.filter { prefers(it).size == 1 }.sortedBy { typeNumber(prefers(it).first()) }
            val multi = priorityCivs.filter { prefers(it).size > 1 }.sortedBy { prefers(it).size }
            val needFallback = ArrayList<Civilization>()
            for (civ in single) {
                val candidates = available.filter { it.type == prefers(civ).first() }
                if (candidates.isEmpty()) needFallback.add(civ) else place(civ, candidates.random(rng))
            }
            for (civ in multi) {
                val wanted = prefers(civ)
                val candidates = available.filter { it.type in wanted }
                if (candidates.isNotEmpty()) place(civ, candidates.random(rng))
            }
            for (civ in needFallback) {
                val region = fallbackFor(prefers(civ).first(), available) ?: continue
                place(civ, region)
            }
        }

        // 4. The types a civ avoids, the longest list first
        for (civ in avoidCivs.sortedBy { avoids(it).size }.reversed()) {
            val avoided = avoids(civ)
            val candidates = available.filter { it.type !in avoided }
            if (candidates.isNotEmpty()) place(civ, candidates.random(rng))
        }

        // 5. The rest at random
        val restCivs = civs.filter { it !in civPlaced }.shuffled(rng)
        val restRegions = regions.filter { it !in regionTaken }
        for ((civ, region) in restCivs.zip(restRegions)) place(civ, region)
    }

    /** FindFallbackForUnmatchedRegionPriority: the region with the most of the terrain of the type */
    private fun fallbackFor(type: String, available: List<Region>): Region? {
        fun count(region: Region, vararg terrains: String) = terrains.sumOf { region.terrainCounts[it] ?: 0 }
        fun best(score: (Region) -> Int): Region? = available.filter { score(it) > 0 }.maxByOrNull(score)
        return when (type) {
            "Tundra" -> best { count(it, "Tundra", "Snow") } ?: available.filter { count(it, "Jungle") == 0 && count(it, "Forest") > 0 }.maxByOrNull { count(it, "Forest") }
            "Jungle" -> best { count(it, "Jungle") }
            "Forest" -> best { count(it, "Forest") }
            "Desert" -> best { count(it, "Desert", "Flood plains", "Oasis") }
            "Hill" -> best { count(it, "Hill", "Mountain") }
            "Plains" -> best { count(it, "Plains") }
            "Grassland" -> best { count(it, "Grassland", "Marsh") }
            "Hybrid" -> best { count(it, "Grassland", "Plains") }
            else -> null
        }
    }
}
