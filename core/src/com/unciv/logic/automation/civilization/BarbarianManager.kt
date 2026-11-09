package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.GameInfo
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.logic.map.BFS
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.TileMap
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.utils.randomWeighted
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.pow
import kotlin.random.Random

/** GC.getBARBARIAN_TECH_PERCENT: the share of the living majors that must know a tech for the barbarians to learn it */
private const val BARBARIAN_TECH_PERCENT = 75

class BarbarianManager : IsPartOfGameInfoSerialization {

    val encampments = ArrayList<BarbarianEncampment>()

    @Transient
    lateinit var gameInfo: GameInfo

    @Transient
    lateinit var tileMap: TileMap

    fun clone(): BarbarianManager {
        val toReturn = BarbarianManager()
        toReturn.encampments.addAll(encampments.map { it.clone() })
        return toReturn
    }

    fun setTransients(gameInfo: GameInfo) {
        this.gameInfo = gameInfo
        this.tileMap = gameInfo.tileMap

        // Add any preexisting camps as Encampment objects

        val existingEncampmentLocations = encampments.asSequence().map { it.position }.toHashSet()

        for (tile in tileMap.values) {
            if (tile.isBarbarianEncampment() && !existingEncampmentLocations.contains(tile.position)) {
                encampments.add(BarbarianEncampment(tile.position))
            }
        }

        for (camp in encampments)
            camp.gameInfo = gameInfo
    }

    fun updateEncampments() {
        updateBarbarianTech()

        // Check if camps were destroyed
        for (encampment in encampments.toList()) { // tolist to avoid concurrent modification
            if (!tileMap[encampment.position].isBarbarianEncampment()) {
                encampment.wasDestroyed()
            }
            // Check if the ghosts are ready to depart
            if (encampment.destroyed && encampment.countdown == 0)
                encampments.remove(encampment)
        }

        // Possibly place a new encampment
        placeBarbarianEncampment()

        for (encampment in encampments) encampment.update()
    }

    /** Called when an encampment was attacked, will speed up time to next spawn */
    fun campAttacked(position: HexCoord) {
        encampments.firstOrNull { it.position == position }?.wasAttacked()
    }

    fun placeBarbarianEncampment(forTesting: Boolean = false) {
        val rng = gameInfo.getBarbarianCivilization().state.stateBasedRandom("BarbarianManager.placeBarbarianEncampent", encampments.map { it.position }.hashCode())
        // Before we do the expensive stuff, do a roll to see if we will place a camp at all
        if (!forTesting && gameInfo.turns > 1 && rng.nextBoolean())
            return

        // Barbarians will only spawn in places that no one can see
        val allViewableTiles = gameInfo.civilizations.asSequence().filterNot { it.isBarbarian || it.isSpectator }
            .flatMap { it.viewableTiles }.toHashSet()
        val fogTiles = tileMap.values.filter { it.isLand && it !in allViewableTiles }

        val fogTilesPerCamp = (tileMap.values.size.toFloat().pow(0.4f)).toInt() // Approximately

        // Check if we have more room
        var campsToAdd = (fogTiles.size / fogTilesPerCamp) - encampments.count { !it.destroyed }

        // First turn of the game add 1/3 of all possible camps
        if (gameInfo.turns == 1) {
            campsToAdd /= 3
            campsToAdd = max(campsToAdd, 1) // At least 1 on first turn
        } else if (campsToAdd > 0)
            campsToAdd = 1

        if (campsToAdd <= 0) return

        // Camps can't spawn within 7 tiles of each other or within 4 tiles of major civ capitals
        val tooCloseToCapitals = gameInfo.civilizations.filterNot { it.isBarbarian || it.isSpectator || it.cities.isEmpty() || it.isCityState || it.getCapital() == null }
            .flatMap { it.getCapital()!!.getCenterTile().getTilesInDistance(4) }.toSet()
        val tooCloseToCamps = encampments
            .flatMap { tileMap[it.position].getTilesInDistance(
                    if (it.destroyed) 4 else 7
            ) }.toSet()

        // CvBarbarians::DoCamps: a landmass takes camps in proportion to its size - the target number times its share of the land, plus one
        // (a camp is placed while the landmass has no more than that, so one more than that can end up on it)
        val landmasses = Landmasses(tileMap)
        val campTarget = fogTiles.size / fogTilesPerCamp
        val campsOnLandmass = IntArray(landmasses.sizes.size)
        for (campTile in tileMap.values) if (campTile.isLand && campTile.isBarbarianEncampment()) campsOnLandmass[landmasses.idOf(campTile)]++
        fun hasRoomForCamp(tile: Tile): Boolean {
            val id = landmasses.idOf(tile)
            return campsOnLandmass[id] <= campTarget * landmasses.sizes[id] / landmasses.landTiles + 1
        }

        val viableTiles = fogTiles.filter {
            !it.isImpassible()
                    && hasRoomForCamp(it)
                    && it.getOwner() == null // CvBarbarians::DoCamps: !isOwned
                    && it.resource == null
                    && it.terrainFeatureObjects.none { feature -> feature.hasUnique(UniqueType.RestrictedBuildableImprovements) }
                    && it.neighbors.any { neighbor -> neighbor.isLand }
                    && it !in tooCloseToCapitals
                    && it !in tooCloseToCamps
        }.toMutableList()

        var tile: Tile?
        var addedCamps = 0
        var biasCoast = rng.nextInt(6) == 0

        // Add the camps
        while (addedCamps < campsToAdd) {
            if (viableTiles.isEmpty())
                break

            // If we're biasing for coast, get a coast tile if possible
            if (biasCoast) {
                tile = viableTiles.filter { it.isAdjacentToCoast() }.randomOrNull(rng)
                if (tile == null)
                    tile = viableTiles.random(rng)
            } else
                tile = viableTiles.random(rng)

            createNewCamp(tile, rng)
            notifyCivsOfBarbarianEncampment(tile)
            addedCamps++

            // Still more camps to add?
            if (addedCamps < campsToAdd) {
                // The landmass may be full now, and this camp rules out the tiles around it
                campsOnLandmass[landmasses.idOf(tile)]++
                viableTiles.removeAll { !hasRoomForCamp(it) }
                // Remove some newly non-viable tiles
                viableTiles.removeAll(tile.getTilesInDistance(7).toSet())
                // Reroll bias
                biasCoast = rng.nextInt(6) == 0
            }
        }
    }

    /**
     * [CivilizationInfo.addNotification][Add a notification] to every civilization that have
     * adopted Honor policy and have explored the [tile] where the Barbarian Encampment has spawned.
     */
    private fun notifyCivsOfBarbarianEncampment(tile: Tile) {
        for (civ in gameInfo.civilizations) {
            if (!civ.hasExplored(tile)) continue
            if (!civ.hasUnique(UniqueType.NotifiedOfBarbarianEncampments)) continue
            civ.addNotification("A new barbarian encampment has spawned!", tile.position, NotificationCategory.War, NotificationIcon.War)
            civ.setLastSeenImprovement(tile.position, tile.improvement)
        }
    }

    /** Creates a new camp without any checks - Does not affect notifications */
    fun createNewCamp(tile: Tile, rng: Random = tile.stateThisTile.stateBasedRandom("createNewCamp")) {
        val candidates = this.gameInfo.ruleset.tileImprovements.values
            .filter { it.isBarbarianCampEquivalent(tile.stateThisTile) }
        val improvement = candidates.randomOrNull(rng)?.name ?: Constants.barbarianEncampment
        tile.setImprovement(improvement)
        val newCamp = BarbarianEncampment(tile.position)
        newCamp.gameInfo = gameInfo
        encampments.add(newCamp)
    }

    /**
     * Attempts to spawn a random barbarian [MapUnit] at or near the provided [Tile].
     * @param allegiance Which [Civilization] should the unit belong to? Defaults to the barbarian civilization.
     *                   If non-barbarian, there are fewer restrictions on whether a unit can be spawned or not.
     * @param ignoreNearbyLimit Trade-route plunder spawns a bonus unit regardless of the normal turn and nearby-unit limits.
     * @return The newly spawned [MapUnit], or null if unsuccessful.
     */
    fun spawnBarbarian(
        tile: Tile,
        allegiance: Civilization = gameInfo.getBarbarianCivilization(),
        ignoreNearbyLimit: Boolean = false
    ): MapUnit? {
        if (allegiance.isBarbarian) {
            // Empty camp - spawn a defender
            if (tile.militaryUnit == null)
                // Try spawning a unit on this tile, return null if unsuccessful
                return spawnUnit(tile.position, false)

            // Don't spawn wandering barbs too early
            if (gameInfo.turns < 10 && !ignoreNearbyLimit)
                return null

            if (!ignoreNearbyLimit) {
                var nearbyBarbarians = 0
                tile.forEachTileInDistance(4) { if (it.militaryUnit?.civ?.isBarbarian == true) nearbyBarbarians++ }
                // Too many barbarians around already?
                if (nearbyBarbarians > 2) return null
            }
        }

        val canSpawnNaval = gameInfo.turns > 30
        val validTiles = tile.neighbors.toList().filterNot {
            it.isImpassible()
                || it.isCityCenter()
                || it.getFirstUnit() != null
                || (it.isWater && !canSpawnNaval)
                || (it.terrainHasUnique(UniqueType.FreshWater) && it.isWater) // No Lakes
        }
        if (validTiles.isEmpty())
            return null

        val rng = gameInfo.getBarbarianCivilization().state.stateBasedRandom("BarbarianManager.spawnBarbarian")
        // Attempt to spawn a barbarian on a valid tile
        return spawnUnit(tile.position, validTiles.random(rng).isWater, allegiance) 
    }

    /** Attempts to spawn a barbarian on [position], returns true if successful and false if unsuccessful. */
    private fun spawnUnit(
        position: HexCoord,
        naval: Boolean,
        allegiance: Civilization = gameInfo.getBarbarianCivilization()
    ): MapUnit? {
        updateBarbarianTech()
        val unitToSpawn = chooseBarbarianUnit(naval)
            ?: return null // return null if we didn't find a unit
        val spawnedUnit = gameInfo.tileMap.placeUnitNearTile(position, unitToSpawn, allegiance)
        return spawnedUnit
    }

    /**
     *  CvTeam::DoBarbarianTech, run every turn: the barbarians learn every tech that 75% (BARBARIAN_TECH_PERCENT, rounded down, at least one)
     *  of the living majors know, city-states and the barbarians themselves not counted; a tech once learned is never lost. The barbarians'
     *  era follows from it, and with it the average era of the game ([com.unciv.logic.automation.diplomacy.DiplomacyTurn.gameEraName]).
     */
    fun updateBarbarianTech() {
        val barbarianTech = gameInfo.getBarbarianCivilization().tech
        val majors = gameInfo.civilizations.filter { it.isMajorCiv && !it.isDefeated() }
        val needed = max(1, majors.size * BARBARIAN_TECH_PERCENT / 100)
        for (tech in gameInfo.ruleset.technologies.values) {
            if (barbarianTech.isResearched(tech.name) || majors.count { it.tech.isResearched(tech.name) } < needed) continue
            barbarianTech.techsResearched.add(tech.name)
            barbarianTech.researchedTechnologies.add(tech)
        }
        barbarianTech.updateEra()
    }

    @Readonly
    private fun chooseBarbarianUnit(naval: Boolean): BaseUnit? {
        // if we don't make this into a separate list then the retain() will happen on the Tech keys,
        // which effectively removes those techs from the game and causes all sorts of problems
        val barbarianCiv = gameInfo.getBarbarianCivilization()
        val unitList = gameInfo.ruleset.units.values
            .filter { it.isMilitary &&
                !(it.hasUnique(UniqueType.CannotAttack) ||
                    it.hasUnique(UniqueType.CannotBeBarbarian)) &&
                (if (naval) it.isWaterUnit else it.isLandUnit) &&
                it.isBuildable(barbarianCiv) }

        if (unitList.isEmpty()) return null // No naval tech yet? Mad modders?

        // Civ V weights its list by FAST_ATTACK or ATTACK_SEA AI types, we'll do it a bit differently
        // getForceEvaluation is already conveniently biased towards fast units and against ranged naval
        val weightings = unitList.map { it.getForceEvaluation().toFloat() }
        val rng = GameContext(gameInfo = gameInfo).stateBasedRandom("BarbarianManager.chooseBarbarianUnit")

        return unitList.randomWeighted(weightings, rng)
    }

    /** Return a _mutable_ List of encampment tiles, allowing in-place sort */
    fun getEncampmentTiles() = encampments.mapTo(mutableListOf()) { gameInfo.tileMap[it.position] }
}

/**
 *  The landmasses of [tileMap], CvArea of the land: every group of land tiles that touch, mountains included. [Tile.getContinent] is not
 *  used, it leaves out the impassable tiles, so a range of mountains would cut a landmass in two.
 */
private class Landmasses(tileMap: TileMap) {
    private val ids = HashMap<Tile, Int>()
    val sizes: IntArray
    val landTiles: Int

    init {
        val sizeList = ArrayList<Int>()
        for (start in tileMap.values) {
            if (!start.isLand || start in ids) continue
            val bfs = BFS(start) { it.isLand }
            bfs.stepToEnd()
            val reached = bfs.getReachedTiles()
            for (tile in reached) ids[tile] = sizeList.size
            sizeList.add(reached.size)
        }
        sizes = sizeList.toIntArray()
        landTiles = ids.size
    }

    fun idOf(tile: Tile) = ids.getValue(tile)
}
