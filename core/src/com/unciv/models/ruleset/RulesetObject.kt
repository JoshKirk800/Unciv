package com.unciv.models.ruleset

import com.unciv.models.ruleset.unique.IHasUniques
import com.unciv.models.ruleset.unique.Unique
import com.unciv.models.ruleset.unique.UniqueMap
import com.unciv.models.stats.NamedStats
import com.unciv.ui.screens.civilopediascreen.FormattedLine
import com.unciv.ui.screens.civilopediascreen.ICivilopediaText
import yairm210.purity.annotations.Readonly

interface IRulesetObject: IHasUniques, ICivilopediaText {
    var originRuleset: String
}

abstract class RulesetObject: IRulesetObject {
    override var name = ""
    override var originRuleset = ""
    override var uniques = ArrayList<String>() // Can not be a hashset as that would remove doubles
    @delegate:Transient
    override val uniqueObjects: List<Unique> by lazy (::uniqueObjectsProvider)
    @delegate:Transient
    override val uniqueMap: UniqueMap by lazy(::uniqueMapProvider)

    override var civilopediaText = listOf<FormattedLine>()
    override fun toString() = name

    /** Not the identity hash: the game iterates hash sets and maps of ruleset objects, and the order must not depend on the run
     *  (see `tools/determinism_check.py`). Equality stays identity. */
    @Readonly override fun hashCode(): Int = name.hashCode()
    @Readonly override fun equals(other: Any?): Boolean = this === other
}

// Same, but inherits from NamedStats - I couldn't find a way to unify the declarations but this is fine
abstract class RulesetStatsObject: NamedStats(), IRulesetObject {
    override var originRuleset = ""
    override var uniques = ArrayList<String>() // Can not be a hashset as that would remove doubles
    @delegate:Transient
    override val uniqueObjects: List<Unique> by lazy (::uniqueObjectsProvider)
    @delegate:Transient
    override val uniqueMap: UniqueMap by lazy(::uniqueMapProvider)

    override var civilopediaText = listOf<FormattedLine>()
}
