package com.unciv.logic.map.tile

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.models.ruleset.ArtifactClasses
import yairm210.purity.annotations.Readonly
import yairm210.purity.annotations.ReturnsNewInstance

/**
 * What happened on a tile that an archaeologist can find (CvArchaeologyData in the original): a battle, a cleared barbarian camp,
 * an ancient ruin, a city that was razed, or a writing that waits to be found. It is what an artifact dug up here is made of.
 * Only the most memorable of the things that happened is kept, see [ArtifactClasses.rank].
 */
class ArchaeologyRecord : IsPartOfGameInfoSerialization {
    /** One of the [ArtifactClasses], or [ArtifactClasses.Writing] */
    var kind = ""
        private set
    /** The era the first civilization was in at the time, which is what the finds are dated to */
    var era = ""
        private set
    /** The civilization the find is attributed to */
    var civ1 = ""
        private set
    /** The other one in battles and razed cities, which can be the one that digs */
    var civ2: String? = null
        private set
    /** For a writing: the work that is found here */
    var work: String? = null
        private set

    @Suppress("unused") // Gdx Json will find this constructor and use it
    private constructor()

    constructor(kind: String, era: String, civ1: String, civ2: String? = null, work: String? = null) {
        this.kind = kind
        this.era = era
        this.civ1 = civ1
        this.civ2 = civ2
        this.work = work
    }

    @Readonly @ReturnsNewInstance
    fun clone() = ArchaeologyRecord(kind, era, civ1, civ2, work)

    override fun toString() = "$kind of $era, $civ1" + (civ2?.let { " and $it" } ?: "")
}
