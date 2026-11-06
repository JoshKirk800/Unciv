package com.unciv.models.ruleset

import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType

class PolicyBranch : Policy() {
    var policies: ArrayList<Policy> = arrayListOf()
    var priorities: HashMap<String, Int> = HashMap()
    var era: String = ""

    /** An ideology (see [UniqueType.IdeologyBranch]) is chosen at no cost, one at a time, and has tenets */
    val isIdeology: Boolean by lazy { hasUnique(UniqueType.IdeologyBranch, GameContext.IgnoreConditionals) }
}
