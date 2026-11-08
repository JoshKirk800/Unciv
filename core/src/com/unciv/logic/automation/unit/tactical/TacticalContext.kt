package com.unciv.logic.automation.unit.tactical

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.automation.civilization.AutomationCache
import com.unciv.logic.automation.unit.PlotDanger
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.tile.Tile
import com.unciv.models.ruleset.ai.AiDefine

/**
 *  What the moves of one tactical turn share (the members of CvTacticalAI that are not state across turns): the analysis [map], the
 *  [danger], this turn's [targets], and the [units] the tactical AI still may command (m_CurrentTurnUnits). A routine that commands a unit
 *  calls [unitProcessed] (CvTacticalAI::UnitProcessed): the unit leaves the list, is in [AutomationCache.processedUnits] for the rest of the
 *  unit AI, and its tile is marked as one a unit finished on.
 */
class TacticalContext(
    val civ: Civilization,
    private val analysisMap: TacticalMap?,
    val danger: PlotDanger,
    val cache: AutomationCache,
    units: Collection<MapUnit>,
    val targets: TacticalTargets,
) {
    /** The analysis map; barbarians have none (CvTacticalAnalysisMap is not built for them), their moves use [isEnemyCity] and [sameArea] */
    val map: TacticalMap get() = analysisMap ?: error("${civ.civName} has no tactical analysis map")
    val rules = civ.gameInfo.ruleset.ai
    val units = ArrayList(units)
    val state get() = civ.ai.tactical
    val turn get() = civ.gameInfo.turns

    /** CvTacticalAI::m_ZoneTargets: the targets the move being run looks at (ExtractTargetsForZone: all of them, or those of one dominance zone) */
    var currentTargets: List<TacticalTarget> = targets.all

    /** CvTacticalAI::TurnOffMove: moves a posture disabled for the rest of the turn (TacticalMoveDef names) */
    val disabledMoves = HashSet<String>()

    /** ClearEnemiesNearArmy: [unit] joins this turn's tactical units (CvTacticalAI::m_CurrentTurnUnits) if it is not one yet */
    fun addUnit(unit: MapUnit) {
        if (unit !in units) units.add(unit)
    }

    /** AI_TACTICAL_RECRUIT_RANGE: m_iRecruitRange, how far units are recruited for one move */
    val recruitRange = rules.define(AiDefine.AI_TACTICAL_RECRUIT_RANGE)

    /** CvTacticalAI::UnitProcessed */
    fun unitProcessed(unit: MapUnit, markTacticalMap: Boolean = true) {
        units.remove(unit)
        cache.processedUnits.add(unit)
        if (markTacticalMap && !unit.isDestroyed) analysisMap?.markFriendlyTurnEnd(unit.getTile())
    }

    /** The tile holds a city of a civilization at war with us (the map cell's enemy city flag) */
    fun isEnemyCity(tile: Tile): Boolean = analysisMap?.isEnemyCity(tile)
        ?: (tile.isCityCenter() && tile.getOwner()?.let { it != civ && civ.isAtWarWith(it) } == true)

    /** The two tiles are on the same landmass or body of water (CvPlot::getArea) */
    fun sameArea(a: Tile, b: Tile): Boolean = analysisMap?.let { it.areaOf(a) == it.areaOf(b) } ?: (a.getContinent() == b.getContinent())

    /** Units of the list that are still ours and alive (units die and are captured while the moves run) */
    fun liveUnits(): List<MapUnit> = units.filter { !it.isDestroyed && it.civ == civ }

    fun log(message: () -> String) = AiDecisionLog.log(civ) { "Tactical: ${message()}" }
}
