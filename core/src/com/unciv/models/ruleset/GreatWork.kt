package com.unciv.models.ruleset

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.models.stats.INamed
import yairm210.purity.annotations.Readonly

/** What kind of Great Work something is. Art and artifacts share the slots of museums and the like */
enum class GreatWorkType(val slotType: GreatWorkSlotType) {
    Art(GreatWorkSlotType.ArtArtifact),
    Artifact(GreatWorkSlotType.ArtArtifact),
    Literature(GreatWorkSlotType.Literature),
    Music(GreatWorkSlotType.Music),
}

/** The kinds of slots a building offers for Great Works (GreatWorkSlots in the original) */
enum class GreatWorkSlotType(val label: String) {
    ArtArtifact("Art/Artifact"),
    Literature("Literature"),
    Music("Music");

    companion object {
        @Readonly
        fun fromLabel(label: String): GreatWorkSlotType? = entries.firstOrNull { it.label == label }
    }
}

/**
 * A Great Work as defined by the ruleset (GreatWorks.json). Great People create them, archaeologists dig up artifacts,
 * and a few buildings come with one. The works that exist in a game are [GreatWorkInstance]s.
 */
class GreatWork : INamed {
    override var name = ""
    var type = GreatWorkType.Art

    /** The Great Person this is the work of: the name a unit gets from its unit name group. Only these units can create it */
    var creator: String? = null

    /** The era an artifact belongs to, if it is specific to one. Artifacts without it are used for the other eras */
    var era: String? = null

    /** What kind of site yields this artifact (see [ArtifactClasses]), for artifacts */
    var artifactClass: String? = null

    /** Writings that only archaeologists can find, no Great Person creates them */
    var archaeologyOnly = false

    var quote = ""

    @Readonly
    fun getSlotType() = type.slotType
}

/** Where artifacts come from. The ruleset names the class of an artifact, these are the classes the game knows */
object ArtifactClasses {
    const val RangedBattle = "Ranged Battle"
    const val MeleeBattle = "Melee Battle"
    const val BarbarianCamp = "Barbarian Camp"
    const val AncientRuin = "Ancient Ruin"
    const val RazedCity = "Razed City"
    val all = listOf(RangedBattle, MeleeBattle, BarbarianCamp, AncientRuin, RazedCity)

    /** What an archaeological site that holds a writing is called. No artifact belongs to it, the work is chosen when the site is made */
    const val Writing = "Writing"

    /** Which find a tile remembers when there are several: the higher one wins (a razed city beats a ruin, a ruin a camp, a camp a battle) */
    @Readonly
    fun rank(kind: String) = when (kind) {
        RangedBattle -> 1
        MeleeBattle -> 2
        BarbarianCamp -> 3
        AncientRuin -> 4
        RazedCity -> 5
        Writing -> 6
        else -> 0
    }
}

/**
 * One Great Work that exists in a game. Works are not unique by name: archaeology can produce the same generic artifact several times.
 * [creatorCiv] and [era] are fixed when it is created and are what theming looks at; the work keeps them when it changes hands.
 */
class GreatWorkInstance : IsPartOfGameInfoSerialization {
    var work = ""
    /** The civilization the work is attributed to, which is not necessarily the one that owns it */
    var creatorCiv = ""
    var era = ""
    var turnCreated = 0

    fun clone() = GreatWorkInstance().also {
        it.work = work
        it.creatorCiv = creatorCiv
        it.era = era
        it.turnCreated = turnCreated
    }

    constructor()
    constructor(work: String, creatorCiv: String, era: String, turnCreated: Int) {
        this.work = work
        this.creatorCiv = creatorCiv
        this.era = era
        this.turnCreated = turnCreated
    }

    override fun toString() = work
}

/**
 * A way for the Great Works in a building to fit together and give a bonus (CvThemingBonusInfo in the original).
 * A building's theming bonuses are tried in order, the first one all of whose requirements hold gives its [bonus].
 * Only a building with all its slots filled can be themed.
 */
class ThemingBonus {
    /** Named for the bonus, as in "World's Most Visited Museum" */
    var name = ""
    var bonus = 0
    /** All works are from the same era */
    var sameEra = false
    /** No two works are from the same era */
    var uniqueEras = false
    var mustBeArt = false
    var mustBeArtifact = false
    /** As many works of art as artifacts */
    var mustBeEqualArtArtifact = false
    /** All works are attributed to the city's owner */
    var requiresOwner = false
    /** No work is attributed to the city's owner */
    var requiresAnyButOwner = false
    /** All works are attributed to the same civilization */
    var requiresSamePlayer = false
    /** No two works are attributed to the same civilization */
    var requiresUniquePlayers = false

    /** The requirements in words, for the Civilopedia. Each is a translatable text */
    fun getRequirementTexts(): List<String> = buildList {
        if (sameEra) add("All from the same era")
        if (uniqueEras) add("All from different eras")
        if (mustBeArt) add("Only art")
        if (mustBeArtifact) add("Only artifacts")
        if (mustBeEqualArtArtifact) add("As much art as artifacts")
        if (requiresOwner) add("All attributed to the civilization of the city")
        if (requiresAnyButOwner) add("None attributed to the civilization of the city")
        if (requiresSamePlayer) add("All attributed to the same civilization")
        if (requiresUniquePlayers) add("All attributed to different civilizations")
    }

    /** Whether [works], which must be all of the works of a full building, satisfy the requirements. [owner] is the civilization the city belongs to */
    @Readonly
    fun isSatisfiedBy(works: List<GreatWorkInstance>, types: List<GreatWorkType>, owner: String): Boolean {
        if (works.isEmpty()) return false
        val artCount = types.count { it == GreatWorkType.Art }
        val artifactCount = types.count { it == GreatWorkType.Artifact }
        if (mustBeArt && artifactCount > 0) return false
        if (mustBeArtifact && artCount > 0) return false
        if (mustBeEqualArtArtifact && artCount != artifactCount) return false

        val eras = works.map { it.era }
        if (sameEra && eras.any { it != eras.first() }) return false
        if (uniqueEras && eras.toSet().size != eras.size) return false

        val civs = works.map { it.creatorCiv }
        if (requiresOwner && civs.any { it != owner }) return false
        if (requiresSamePlayer && civs.any { it != civs.first() }) return false
        if (requiresUniquePlayers && civs.toSet().size != civs.size) return false
        if (requiresAnyButOwner && civs.any { it == owner }) return false
        return true
    }
}
