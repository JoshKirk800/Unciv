package com.unciv.logic.automation

import com.unciv.logic.civilization.Civilization

/**
 *  An optional listener for the AI's decisions with the alternatives it weighed, for development tools (FullGameSim's civilization
 *  trace). Null in games: [log] then builds no message.
 */
object AiDecisionLog {
    @JvmField var sink: ((Civilization, String) -> Unit)? = null

    inline fun log(civ: Civilization, message: () -> String) {
        val sink = sink ?: return
        sink(civ, message())
    }
}
