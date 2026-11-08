package com.unciv.logic.map.tile

import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.PopupAlert
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.logic.city.GreatWorkFunctions
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.ArtifactClasses
import com.unciv.models.ruleset.GreatWork
import com.unciv.models.ruleset.GreatWorkSlotType
import yairm210.purity.annotations.Readonly
import kotlin.random.Random

/** What can be done with what an excavation has found (the choices of CvPlayerCulture::DoArchaeologyChoice) */
enum class ArchaeologyChoice(val label: String) {
    /** Build a Landmark: culture and tourism for the tile, and friendship with a city-state whose land it is */
    Landmark("Build a Landmark"),
    /** Keep the artifact, attributed to the first civilization of the record */
    ArtifactOfFirst("Take the artifact"),
    /** Keep the artifact, attributed to the second civilization of the record */
    ArtifactOfSecond("Take the artifact"),
    /** Keep the writing the site holds */
    Writing("Take the writing"),
    /** Turn a writing into culture, as much as a Great Writer's burst gives */
    CultureBoost("Gain culture");
}

/**
 * Archaeology (CvGame::SpawnArchaeologySitesHistorically, CvPlayerCulture::DoArchaeologyChoice and what goes with them).
 * Once the first civilization knows what makes them, sites appear around the world on tiles that have a history of battle, ruins, razed
 * cities or camps. An archaeologist digs one up, and then chooses between an artifact for a Great Work slot, a writing, culture, or a Landmark.
 */
object ArchaeologyFunctions {

    //region Where the sites are
    /** The civilizations that count when sites are made: all major civilizations that ever were */
    @Readonly
    private fun getMajorCivs(gameInfo: GameInfo) = gameInfo.civilizations.filter { it.isMajorCiv }

    /** CvGame::CalculateDigSiteWeight: how well a tile suits a site. 0 means not at all. [jitter] is what the original draws from its random generator */
    @Readonly
    fun getSiteWeight(tile: Tile, chosen: Set<Tile>, jitter: Int): Int {
        if (tile in chosen) return 0
        val record = tile.archaeologyRecord
        val ruleset = tile.tileMap.gameInfo.ruleset
        val eraNumber = record?.let { ruleset.eras[it.era]?.eraNumber } ?: -1
        var weight = (ArtifactClasses.rank(record?.kind ?: "") + 1) * (10 - eraNumber)

        if (tile.resource != null || tile.isWater || tile.terrainFeatures.contains("Ice") || tile.isImpassible() || tile.isNaturalWonder()) return 0
        if (tile.tileImprovement?.isGreatImprovement() == true) return 0

        weight += 10 + jitter
        val owner = tile.getOwner()
        weight = weight * (if (owner == null) 9 else 8) / 8
        weight = weight * (if (owner != null && owner.isMajorCiv) 11 else 12) / 12
        weight = weight * (if (tile.improvement != null || tile.roadStatus != RoadStatus.None) 7 else 8) / 8
        weight = weight * (if (tile.isCityCenter()) 1 else 5) / 5
        weight *= if (tile.baseTerrain == "Desert") 3 else 2
        weight *= if (tile.terrainFeatures.contains("Jungle")) 3 else 2
        weight *= if ((tile.tileMap.continentSizes[tile.getContinent()] ?: 0) <= 4) 3 else 2

        // One more divisor for every site close by: those within 3 tiles, those within 2 and those within 1 each count once more
        var divisor = 1
        for (range in 1..3)
            divisor += tile.getTilesInDistance(range).count { it in chosen }
        return weight / divisor
    }

    /** CvGame::PopulateDigSite: who the history of a tile that has none yet is about */
    private fun populateRecord(tile: Tile, gameInfo: GameInfo, era: String, kind: String, random: Random): ArchaeologyRecord {
        val cities = gameInfo.getCities().toList()
        val sameContinent = cities.filter { it.getCenterTile().getContinent() == tile.getContinent() }
        val city = (sameContinent.ifEmpty { cities }).minByOrNull { it.getCenterTile().aerialDistanceTo(tile) }
        val majors = getMajorCivs(gameInfo)
        val first = (city?.foundingCivObject ?: city?.civ)?.takeIf { it.isMajorCiv } ?: majors.random(random)
        val needsSecond = kind == ArtifactClasses.MeleeBattle || kind == ArtifactClasses.RangedBattle || kind == ArtifactClasses.RazedCity
        val second = if (needsSecond) gameInfo.civilizations
            .filter { !it.isBarbarian && !it.isSpectator && it != first }
            .let { if (it.isEmpty()) null else it.random(random) } else null
        return ArchaeologyRecord(kind, era, first.civName, second?.civName)
    }

    /**
     * Makes the archaeological sites. The number is 5 to 8 for every major civilization, spread out over tiles that suit them, preferring
     * deserts, jungles, small islands and land that no one owns, with the oldest history. Some are hidden until a policy is adopted
     * (`archaeologyPercentHidden`), and some of those hold a writing.
     * @return the tiles that got a site
     */
    fun spawnSites(gameInfo: GameInfo, random: Random): List<Tile> {
        val ruleset = gameInfo.ruleset
        val constants = ruleset.modOptions.constants
        val siteResource = ruleset.tileResources[constants.archaeologySiteResource] ?: return emptyList()
        val hiddenResource = ruleset.tileResources[constants.archaeologyHiddenSiteResource] ?: siteResource

        val numMajors = getMajorCivs(gameInfo).size
        val minSites = constants.archaeologySitesPerCivMin * numMajors
        val maxSites = constants.archaeologySitesPerCivMax * numMajors
        val half = ((maxSites - minSites) / 2).coerceAtLeast(1)
        val ideal = minSites + random.nextInt(half) + random.nextInt(half)

        // Older eras are more likely for a site, the era that the most advanced civilization is in is left out
        val eras = ruleset.eras.values.sortedBy { it.eraNumber }
        val highestEra = getMajorCivs(gameInfo).maxOfOrNull { it.getEraNumber() } ?: 0
        val eraWeights = (0 until highestEra).map { eras[it] to highestEra - it }
        fun drawEra(): String {
            if (eraWeights.isEmpty()) return eras.first().name
            var roll = random.nextInt(eraWeights.sumOf { it.second })
            for ((era, weight) in eraWeights) {
                if (roll < weight) return era.name
                roll -= weight
            }
            return eras.first().name
        }

        val writings = ruleset.greatWorks.values.filter { it.archaeologyOnly }.shuffled(random).toMutableList()
        val desiredWriting = ideal * constants.archaeologyPercentHidden / 100 * constants.archaeologyPercentHiddenWriting / 100
        var numWritings = minOf(writings.size, desiredWriting)

        val tiles = gameInfo.tileMap.values.toList()
        val chosen = LinkedHashSet<Tile>()
        val weights = HashMap<Tile, Int>(tiles.size)
        for (tile in tiles) weights[tile] = getSiteWeight(tile, chosen, random.nextInt(10))

        val kinds = listOf(ArtifactClasses.AncientRuin, ArtifactClasses.AncientRuin, ArtifactClasses.RazedCity, ArtifactClasses.BarbarianCamp,
            ArtifactClasses.BarbarianCamp, ArtifactClasses.MeleeBattle, ArtifactClasses.RangedBattle)

        while (chosen.size < ideal) {
            val best = weights.maxByOrNull { it.value } ?: break
            if (best.value <= 0) break
            val tile = best.key
            val hidden = random.nextInt(100) < constants.archaeologyPercentHidden
            tile.tileResource = if (hidden) hiddenResource else siteResource
            tile.resourceAmount = 1
            chosen.add(tile)

            var record = tile.archaeologyRecord ?: populateRecord(tile, gameInfo, drawEra(), kinds.random(random), random)
            if (hidden && numWritings > 0) {
                record = ArchaeologyRecord(ArtifactClasses.Writing, record.era, record.civ1, record.civ2, writings.removeLast().name)
                numWritings--
            }
            tile.archaeologyRecord = record

            for (near in tile.getTilesInDistance(3)) weights[near] = getSiteWeight(near, chosen, random.nextInt(10))
            weights[tile] = 0
        }
        gameInfo.archaeologyTriggered = true
        return chosen.toList()
    }
    //endregion

    //region Digging
    /** The sites that [civ] can see and has explored (CvEconomicAI::GetVisibleAntiquitySites) */
    @Readonly
    fun getVisibleSites(civ: Civilization): List<Tile> {
        val constants = civ.gameInfo.ruleset.modOptions.constants
        val siteResources = setOf(constants.archaeologySiteResource, constants.archaeologyHiddenSiteResource)
        return civ.gameInfo.tileMap.values.filter {
            it.resource in siteResources && it.isExplored(civ) && civ.canSeeResource(it.tileResource)
        }
    }

    /**
     * The dig on [tile] is complete. The AI chooses what to do with the finds at once. A player is asked, in a popup that is opened by the
     * world screen, and the dig waits with the archaeologist on it until then.
     */
    fun onDigCompleted(civ: Civilization, tile: Tile, unit: MapUnit?, aiControlled: Boolean = civ.isAI()) {
        if (aiControlled) {
            val others = civ.units.getCivUnits().count { it.hasUnique(UniqueType.CanExcavate) && it != unit }
            execute(civ, tile, chooseForAi(civ, tile, others, getVisibleSites(civ).size), unit)
            return
        }
        civ.popupAlerts.add(PopupAlert(AlertType.ArchaeologyChoice, "${tile.position.x},${tile.position.y}"))
        civ.addNotification("The excavation is complete: what shall we do with the finds?", tile.position, NotificationCategory.General, "StatIcons/Culture")
    }

    /**
     * A player who left a dig without choosing (by saving, or by the popup being closed by other means) is asked again at the start of the turn,
     * and the AI chooses for any dig of its archaeologists that is still complete.
     */
    fun resolvePendingDigs(civ: Civilization, aiControlled: Boolean = civ.isAI()) {
        for (unit in civ.units.getCivUnits().filter { it.hasUnique(UniqueType.CanExcavate) }.toList()) {
            val tile = unit.getTile()
            if (!hasCompletedDig(tile)) continue
            val value = "${tile.position.x},${tile.position.y}"
            if (aiControlled || civ.popupAlerts.none { it.type == AlertType.ArchaeologyChoice && it.value == value })
                onDigCompleted(civ, tile, unit, aiControlled)
        }
    }

    /** A dig that is complete and waits for what is to be done with its finds */
    @Readonly
    fun hasCompletedDig(tile: Tile): Boolean =
        tile.improvement == tile.tileMap.gameInfo.ruleset.modOptions.constants.archaeologicalDigImprovement

    /** CultureHelpers::GetArtifact: the work that is found here. Artifacts of the era of the find come first, those that fit any era are the others */
    fun getArtifact(tile: Tile, random: Random = Random(tile.position.hashCode())): GreatWork? {
        val record = tile.archaeologyRecord ?: return null
        val ruleset = tile.tileMap.gameInfo.ruleset
        if (record.kind == ArtifactClasses.Writing) return ruleset.greatWorks[record.work]
        val candidates = ruleset.greatWorks.values.filter { it.artifactClass == record.kind }
        return candidates.filter { it.era == record.era }.ifEmpty { candidates.filter { it.era == null } }.randomOrNull(random)
    }

    /** What [civ] can do with the finds on [tile], which has a completed dig */
    fun getChoices(civ: Civilization, tile: Tile): List<ArchaeologyChoice> {
        val record = tile.archaeologyRecord
        val choices = ArrayList<ArchaeologyChoice>()
        choices.add(ArchaeologyChoice.Landmark)
        if (record == null) return choices
        if (record.kind == ArtifactClasses.Writing) {
            if (GreatWorkFunctions.getNumFreeSlots(civ, GreatWorkSlotType.Literature) > 0) choices.add(ArchaeologyChoice.Writing)
            choices.add(ArchaeologyChoice.CultureBoost)
        } else if (GreatWorkFunctions.getNumFreeSlots(civ, GreatWorkSlotType.ArtArtifact) > 0 && getArtifact(tile) != null) {
            choices.add(ArchaeologyChoice.ArtifactOfFirst)
            if (record.civ2 != null) choices.add(ArchaeologyChoice.ArtifactOfSecond)
        }
        return choices
    }

    /** CvPlayerCulture::GetArchaeologyChoice, what the AI does with a dig */
    fun chooseForAi(civ: Civilization, tile: Tile, numOtherArchaeologists: Int, numOtherSites: Int): ArchaeologyChoice {
        val record = tile.archaeologyRecord ?: return ArchaeologyChoice.Landmark
        val choices = getChoices(civ, tile)
        val isWriting = record.kind == ArtifactClasses.Writing
        val noSlot = if (isWriting) ArchaeologyChoice.Writing !in choices else ArchaeologyChoice.ArtifactOfFirst !in choices
        var choice = when {
            isWriting && ArchaeologyChoice.Writing in choices -> ArchaeologyChoice.Writing
            noSlot -> if (isWriting) ArchaeologyChoice.CultureBoost else ArchaeologyChoice.Landmark
            tile.getOwner() != civ -> {
                // CvPlayerCulture::GetArchaeologyChoice: diplomatic strategy favors a city-state
                // Landmark unless our approach towards it is conquest.
                val owner = tile.getOwner()
                if (owner?.isCityState == true && civ.ai.isGoingForDiploVictory()
                    && !civ.isAtWarWith(owner)) {
                    if (civ.ai.minorCivApproaches[owner.civID] == MinorCivApproach.Conquest)
                        ArchaeologyChoice.ArtifactOfFirst
                    else ArchaeologyChoice.Landmark
                } else ArchaeologyChoice.ArtifactOfFirst
            }
            tile.getWorkingCity() == null -> ArchaeologyChoice.ArtifactOfFirst
            record.era == civ.gameInfo.ruleset.eras.values.minByOrNull { it.eraNumber }?.name -> ArchaeologyChoice.Landmark
            else -> {
                val freeSlots = GreatWorkFunctions.getNumFreeSlots(civ, GreatWorkSlotType.ArtArtifact)
                if (freeSlots <= minOf(numOtherArchaeologists, numOtherSites))
                    if (isWriting) ArchaeologyChoice.CultureBoost else ArchaeologyChoice.Landmark
                else ArchaeologyChoice.ArtifactOfFirst
            }
        }
        // Collect the own artifacts if there are any
        if (choice == ArchaeologyChoice.ArtifactOfFirst && record.civ2 == civ.civName && ArchaeologyChoice.ArtifactOfSecond in choices)
            choice = ArchaeologyChoice.ArtifactOfSecond
        return choice
    }

    /**
     * Does what [choice] says with the dig on [tile] and uses up the archaeologist. Returns whether it could be done.
     * An artifact or a writing needs a free slot, which [getChoices] makes sure of.
     */
    fun execute(civ: Civilization, tile: Tile, choice: ArchaeologyChoice, unit: MapUnit?): Boolean {
        if (choice !in getChoices(civ, tile)) return false
        val constants = civ.gameInfo.ruleset.modOptions.constants
        val record = tile.archaeologyRecord
        val owner = tile.getOwner()
        when (choice) {
            ArchaeologyChoice.Landmark -> {
                tile.setImprovement(constants.landmarkImprovement, civ, unit)
                if (owner?.isCityState == true)
                    owner.getDiplomacyManager(civ)?.addInfluence(constants.landmarkInfluenceWithCityStates.toFloat())
                else if (owner != null && owner != civ && owner.isMajorCiv && owner.isAI()) {
                    val diplomacy = owner.getDiplomacyManager(civ)
                    // CvPlayerCulture: ChangeNumLandmarksBuiltForMe (the opinion is CvDiplomacyAI::GetLandmarksBuiltForMeScore)
                    if (diplomacy != null) diplomacy.landmarksBuiltForUs++
                }
            }
            ArchaeologyChoice.ArtifactOfFirst, ArchaeologyChoice.ArtifactOfSecond, ArchaeologyChoice.Writing -> {
                val work = getArtifact(tile) ?: return false
                val slot = GreatWorkFunctions.getClosestFreeSlot(civ, work.getSlotType(), tile) ?: return false
                val attributedTo = if (choice == ArchaeologyChoice.ArtifactOfSecond) record!!.civ2!! else record!!.civ1
                GreatWorkFunctions.createWork(civ, work, slot, attributedTo, record.era)
                recordForeignExtraction(civ, owner)
                civ.addNotification("We have found the Great Work [${work.name}] and put it in [${slot.city.name}]", slot.city.location,
                    NotificationCategory.General, "StatIcons/Culture")
                tile.removeImprovement()
            }
            ArchaeologyChoice.CultureBoost -> {
                civ.policies.addCulture(civ.policies.getCultureFromGreatWriter())
                tile.removeImprovement()
                recordForeignExtraction(civ, owner)
            }
        }
        unit?.destroy()
        return true
    }
    /** CvPlayerCulture::DoArchaeologyChoice: artifacts, writings and culture boosts all cost 10
     *  negative archaeology points on another major civilization's land; a Landmark does not. */
    private fun recordForeignExtraction(civ: Civilization, owner: Civilization?) {
        if (owner == null || owner == civ || !owner.isMajorCiv) return
        val diplomacy = owner.getDiplomacyManager(civ) ?: return
        diplomacy.negativeArchaeologyPoints += 10
        if (owner.isAI() && diplomacy.negativeArchaeologyPoints >= 10 &&
            !diplomacy.hasFlag(DiplomacyFlags.IgnoreThemDiggingInOurLands))
            diplomacy.setFlag(DiplomacyFlags.DugInOurLands, 1)
    }

    //endregion
}
