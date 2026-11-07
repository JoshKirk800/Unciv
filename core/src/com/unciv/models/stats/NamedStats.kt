package com.unciv.models.stats

import yairm210.purity.annotations.Readonly


open class NamedStats : Stats(), INamed {
    override lateinit var name: String

    @Readonly override fun toString(): String = name

    /** Not the identity hash: the game iterates hash sets and maps of ruleset objects, and the order must not depend on the run
     *  (see `tools/determinism_check.py`). Equality stays identity. */
    @Readonly override fun hashCode(): Int = if (::name.isInitialized) name.hashCode() else 0
    @Readonly override fun equals(other: Any?): Boolean = this === other

    @Readonly fun cloneStats(): Stats = clone()
}
