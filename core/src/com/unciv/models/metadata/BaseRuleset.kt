package com.unciv.models.metadata

@Suppress("EnumEntryName")  // These merit unusual names
enum class BaseRuleset(val fullName: String) {
    Civ_V_Vanilla("Civ V - Vanilla"),
    Civ_V_GnK("Civ V - Gods & Kings"),
    /** Starts as a copy of Gods & Kings; the data and rules of Brave New World are applied to it step by step (see phaseE/bnw-foundation.md) */
    Civ_V_BNW("Civ V - Brave New World"),
}
