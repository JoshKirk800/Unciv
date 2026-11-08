package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.unit.PlotDanger
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.ai.AiDefine

/**
 *  What the moves of one tactical turn share (the members of CvTacticalAI that are not state across turns): the analysis [map], the
 *  [danger], this turn's [targets], and the [units] the tactical AI still may command (m_CurrentTurnUnits). A routine that commands a unit
 *  calls [unitProcessed] (CvTacticalAI::UnitProcessed): the unit leaves the list, is in [AutomationCache.processedUnits] for the rest of the
 *  unit AI, and its tile is marked as one a unit finished on.
 */
class TacticalContext(
    val civ: Civilization,
    val map: TacticalMap,
    val danger: PlotDanger,
    val cache: AutomationCache,
    units: Collection<MapUnit>,
    val targets: TacticalTargets,
) {
    val rules = civ.gameInfo.ruleset.ai
    val units = ArrayList(units)
    val state get() = civ.ai.tactical
    val turn get() = civ.gameInfo.turns

    /** AI_TACTICAL_RECRUIT_RANGE: m_iRecruitRange, how far units are recruited for one move */
    val recruitRange = rules.define(AiDefine.AI_TACTICAL_RECRUIT_RANGE)

    /** CvTacticalAI::UnitProcessed */
    fun unitProcessed(unit: MapUnit, markTacticalMap: Boolean = true) {
        units.remove(unit)
        cache.processedUnits.add(unit)
        if (markTacticalMap && !unit.isDestroyed) map.markFriendlyTurnEnd(unit.getTile())
    }

    /** Units of the list that are still ours and alive (units die and are captured while the moves run) */
    fun liveUnits(): List<MapUnit> = units.filter { !it.isDestroyed && it.civ == civ }

    fun log(message: () -> String) = AiDecisionLog.log(civ) { "Tactical: ${message()}" }
}
