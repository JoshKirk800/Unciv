package com.unciv.logic.automation.unit

import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.tile.TileResource

/** Resources.Happiness of every luxury in the original's XML; Unciv has no per-resource happiness (CivInfoStatsForNextTurn uses 4 per luxury) */
private const val LUXURY_RESOURCE_HAPPINESS = 4

/**
 *  CvBuilderTaskingAI::GetResourceWeight: how much [civ] wants to hook up [resource] with [improvement] on a tile holding [quantity] of it.
 *
 *  For every flavor: the positive products resource flavor × personality flavor and personality flavor × improvement flavor
 *  (×1 when [improvement] is null) are summed. A luxury then multiplies the sum by BUILDER_TASKING_PLOT_EVAL_MULTIPLIER_LUXURY_RESOURCE
 *  × its happiness (3/4 of that when the civ already has some available); a strategic resource whose tech the civ knows multiplies
 *  it by quantity × 200, ×4 more when none is available.
 *
 *  Unciv mapping: the resource's TechCityTrade is [TileResource.revealedBy] (the same tech for every strategic resource of the XML);
 *  a strategic resource without one counts as tradeable. "Available" is [Civilization.getResourceAmount] (supply minus use, imports included).
 */
fun resourceWeight(civ: Civilization, resource: TileResource, improvement: TileImprovement?, quantity: Int): Int {
    val aiRules = civ.gameInfo.ruleset.ai
    val personality = civ.ai.personalityFlavorVector()
    val resourceFlavors = aiRules.flavorsOf(resource)
    val improvementFlavors = improvement?.let { aiRules.flavorsOf(it) }
    var weight = 0
    for (i in 0 until Flavor.count) {
        val personalityFlavor = personality[i]
        val resourceResult = resourceFlavors[i] * personalityFlavor
        if (resourceResult > 0) weight += resourceResult
        val improvementFlavor = improvementFlavors?.get(i) ?: 1
        val usableByCityWeight = personalityFlavor * improvementFlavor
        if (usableByCityWeight > 0) weight += usableByCityWeight
    }

    when (resource.resourceType) {
        ResourceType.Luxury -> {
            var modifier = aiRules.define(AiDefine.BUILDER_TASKING_PLOT_EVAL_MULTIPLIER_LUXURY_RESOURCE) * LUXURY_RESOURCE_HAPPINESS
            if (civ.getResourceAmount(resource) != 0) modifier = modifier * 3 / 4
            weight *= modifier
        }
        ResourceType.Strategic -> {
            val tech = resource.revealedBy
            if (tech == null || civ.tech.isResearched(tech)) {
                var multiplyingAmount = quantity * 200
                // The original multiplies by 4 both when some is owned but all used and when none is owned
                if (civ.getResourceAmount(resource) == 0) multiplyingAmount *= 4
                weight *= multiplyingAmount
            }
        }
        ResourceType.Bonus -> Unit
    }
    return weight
}
