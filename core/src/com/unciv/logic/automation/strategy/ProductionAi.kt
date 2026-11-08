package com.unciv.logic.automation.strategy

import com.unciv.logic.city.City
import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.INonPerpetualConstruction
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit

/**
 *  The item weights of a city's production AIs: CvBuildingProductionAI, CvUnitProductionAI, CvProjectProductionAI and CvProcessProductionAI.
 *  Each is a flavor recipient of the city (CvCityStrategyAI::FlavorUpdate): an item's weight is Σ over flavors of the item's XML flavor
 *  (Building_Flavors, Unit_Flavors, Project_Flavors, Process_Flavors - [AiRuleset.flavorsOf]) × the city's flavor, negative city flavors
 *  read as 0 (CvFlavorRecipient::GetLatestFlavorValue). The city flavors are cached by [CityAi.flavors].
 */
object ProductionAi {

    /** Σ item flavor × max(0, city flavor) */
    private fun weight(city: City, itemFlavors: IntArray): Int {
        val cityFlavors = city.ai.flavors()
        var sum = 0
        for (i in 0 until Flavor.count) {
            val cityFlavor = cityFlavors[i]
            if (cityFlavor > 0) sum += itemFlavors[i] * cityFlavor
        }
        return sum
    }

    /** CvBuildingProductionAI::GetWeight */
    fun buildingWeight(city: City, building: Building): Int = weight(city, city.civ.gameInfo.ruleset.ai.flavorsOf(building))

    /** CvUnitProductionAI::GetWeight */
    fun unitWeight(city: City, unit: BaseUnit): Int = weight(city, city.civ.gameInfo.ruleset.ai.flavorsOf(unit))

    /** CvProjectProductionAI::GetWeight: Unciv's projects are buildings (Apollo Program, Manhattan Project), their flavors Project_Flavors */
    fun projectWeight(city: City, project: Building): Int = weight(city, city.civ.gameInfo.ruleset.ai.flavorsOf(project))

    /** CvProcessProductionAI::GetWeight, by [com.unciv.models.ruleset.PerpetualConstruction] name */
    fun processWeight(city: City, processName: String): Int = weight(city, city.civ.gameInfo.ruleset.ai.flavorsOfProcess(processName))

    /**
     *  Whether a building is one of the original's projects (ProjectInfo, CvCity::canCreate) rather than a building: it has a Project_Flavors
     *  entry, or - without one (mods, rulesets without AI.json) - it enables spaceship parts or nuclear weapons as the Apollo Program and the
     *  Manhattan Project do.
     */
    fun isProject(rules: AiRuleset, building: Building): Boolean {
        if (rules.getItemFlavors(AiRuleset.KIND_PROJECT, building.name) != null) return true
        if (rules.getItemFlavors(AiRuleset.KIND_BUILDING, building.name) != null) return false
        return building.hasUnique(UniqueType.EnablesConstructionOfSpaceshipParts, GameContext.IgnoreConditionals)
            || building.hasUnique(UniqueType.EnablesNuclearWeapons, GameContext.IgnoreConditionals)
    }

    /** CvCity::getProductionTurnsLeft: at least 1 */
    fun turnsLeft(city: City, construction: INonPerpetualConstruction): Int =
        city.cityConstructions.turnsToConstruction(construction.name).coerceAtLeast(1)

    /**
     *  CvUnitProductionAI::RecommendUnit: among the units the city can train that may take the unit AI [type] (CvUnitEntry::GetUnitAIType),
     *  the one with the highest [unitWeight] reweighted by its turns left (CityStrategyAIHelpers::ReweightByTurnsLeft); null if none.
     */
    fun recommendUnit(city: City, type: UnitAiType): BaseUnit? {
        val civ = city.civ
        val rules = civ.gameInfo.ruleset.ai
        var best: BaseUnit? = null
        var bestWeight = Int.MIN_VALUE
        for (unit in civ.gameInfo.ruleset.units.values) {
            if (!UnitAiTypes.canHave(unit, type, rules)) continue
            if (!unit.isBuildable(city.cityConstructions)) continue
            val weight = WonderProductionAi.reweightByTurnsLeft(civ, unitWeight(city, unit), turnsLeft(city, unit))
            if (weight > bestWeight) {
                bestWeight = weight
                best = unit
            }
        }
        return best
    }
}
