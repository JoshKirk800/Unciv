package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.unit.PlotDanger
import com.unciv.logic.automation.unit.tactical.TacticalMap
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit

/**
 * Holds data that automation caches while a civ's moves are being automated.
 * Created in [NextTurnAutomation.automateCivMoves] and passed down to wherever it's needed - it is never stored on game state,
 * so it cannot go stale between turns.
 */
class AutomationCache {

    /**
     *  The units whose moves were decided this turn (CvUnit::TurnProcessed): whoever commands a unit adds it here, every later routine skips it.
     */
    val processedUnits = HashSet<MapUnit>()

    /** The plot danger of [civ] for the current turn, see [com.unciv.logic.automation.strategy.CivAi.plotDanger] */
    fun plotDanger(civ: Civilization): PlotDanger = civ.ai.plotDanger

    /** The tactical analysis map of [civ] for the current turn, see [com.unciv.logic.automation.unit.tactical.TacticalState.map] */
    fun tacticalMap(civ: Civilization): TacticalMap = civ.ai.tactical.map
}
