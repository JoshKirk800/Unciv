package com.unciv.logic.automation.strategy

import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit

/** The original's UnitAITypes (CvEnums.h), in its order; [xmlName] is the `UNITAI_*` name used in AI.json's `unitAiTypes` */
enum class UnitAiType {
    UNKNOWN, SETTLE, WORKER, ATTACK, CITY_BOMBARD, FAST_ATTACK, DEFENSE, COUNTER, RANGED, CITY_SPECIAL, EXPLORE, ARTIST, SCIENTIST,
    GENERAL, MERCHANT, ENGINEER, ICBM, WORKER_SEA, ATTACK_SEA, RESERVE_SEA, ESCORT_SEA, EXPLORE_SEA, ASSAULT_SEA, SETTLER_SEA,
    CARRIER_SEA, MISSILE_CARRIER_SEA, PIRATE_SEA, ATTACK_AIR, DEFENSE_AIR, CARRIER_AIR, MISSILE_AIR, PARADROP, SPACESHIP_PART, TREASURE,
    PROPHET, MISSIONARY, INQUISITOR, ADMIRAL, TRADE_UNIT, ARCHAEOLOGIST, WRITER, MUSICIAN;

    val xmlName = "UNITAI_$name"

    companion object {
        private val byXmlName = entries.associateBy { it.xmlName }
        fun ofXmlName(xmlName: String?): UnitAiType? = byXmlName[xmlName]
    }
}

/**
 *  The unit AI types of units: CvUnitEntry::GetDefaultUnitAIType / GetUnitAIType (the Unit_AITypes table, from AI.json) and
 *  CvUnit::AI_getUnitAIType / AI_setUnitAIType. A unit's current type is its unit type's default unless the AI reassigned it
 *  (CvEconomicAI::DoReconState and CvCity::CreateUnit move units between the explore and attack roles); reassignments are kept in
 *  [CivAi.unitAiOverrides] by unit id.
 *  Units without an AI.json entry (mods) get a type derived from what they are; they can take only that type.
 */
object UnitAiTypes {

    /** CvUnitEntry::GetDefaultUnitAIType */
    fun defaultOf(unit: BaseUnit, rules: AiRuleset): UnitAiType =
        rules.unitAiTypesOf(unit.name)?.let { UnitAiType.ofXmlName(it.defaultUnitAi) } ?: derivedDefault(unit)

    /** CvUnitEntry::GetUnitAIType(eType): whether units of this type may take the unit AI [type] */
    fun canHave(unit: BaseUnit, type: UnitAiType, rules: AiRuleset): Boolean {
        val entry = rules.unitAiTypesOf(unit.name) ?: return derivedDefault(unit) == type
        return type.xmlName in entry.unitAiTypes
    }

    /** CvUnit::AI_getUnitAIType */
    fun current(unit: MapUnit): UnitAiType {
        val overrides = unit.civ.ai.unitAiOverrides
        if (overrides.isNotEmpty()) overrides[unit.id.toString()]?.let { UnitAiType.ofXmlName(it) }?.let { return it }
        return defaultOf(unit.baseUnit, unit.civ.gameInfo.ruleset.ai)
    }

    /** CvUnit::AI_setUnitAIType */
    fun set(unit: MapUnit, type: UnitAiType) {
        val overrides = unit.civ.ai.unitAiOverrides
        if (type == defaultOf(unit.baseUnit, unit.civ.gameInfo.ruleset.ai)) overrides.remove(unit.id.toString())
        else overrides[unit.id.toString()] = type.xmlName
    }

    /** Drops the reassignments of units the civilization no longer has */
    fun pruneOverrides(civ: Civilization) {
        val overrides = civ.ai.unitAiOverrides
        if (overrides.isEmpty()) return
        val ids = civ.units.getCivUnits().mapTo(HashSet()) { it.id.toString() }
        overrides.keys.retainAll(ids)
    }

    /** CvPlayer::GetNumUnitsWithUnitAI: units with the unit AI [type], optionally with the units cities are training (by their default type) */
    fun numUnitsWithUnitAi(civ: Civilization, type: UnitAiType, includeBeingTrained: Boolean, includeWater: Boolean = false): Int {
        var count = 0
        for (unit in civ.units.getCivUnits()) {
            if (unit.baseUnit.isWaterUnit && !includeWater) continue
            if (current(unit) == type) count++
        }
        if (includeBeingTrained) {
            val rules = civ.gameInfo.ruleset.ai
            for (city in civ.cities) {
                val inProduction = city.cityConstructions.getCurrentConstruction() as? BaseUnit ?: continue
                if (inProduction.isWaterUnit && !includeWater) continue
                if (defaultOf(inProduction, rules) == type) count++
            }
        }
        return count
    }

    /** The unit AI of a unit type without a Unit_AITypes entry, from what the unit is */
    private fun derivedDefault(unit: BaseUnit): UnitAiType = when {
        unit.isCityFounder() -> UnitAiType.SETTLE
        unit.hasUnique(UniqueType.BuildImprovements, GameContext.IgnoreConditionals) ->
            if (unit.isWaterUnit) UnitAiType.WORKER_SEA else UnitAiType.WORKER
        unit.hasUnique(UniqueType.NuclearWeapon, GameContext.IgnoreConditionals) -> UnitAiType.ICBM
        unit.isGreatPerson -> UnitAiType.UNKNOWN
        unit.isCivilian() -> UnitAiType.UNKNOWN
        unit.isAirUnit() -> UnitAiType.ATTACK_AIR
        unit.isWaterUnit -> if (unit.isRanged()) UnitAiType.ASSAULT_SEA else UnitAiType.ATTACK_SEA
        unit.isRanged() -> UnitAiType.RANGED
        else -> UnitAiType.ATTACK
    }
}
