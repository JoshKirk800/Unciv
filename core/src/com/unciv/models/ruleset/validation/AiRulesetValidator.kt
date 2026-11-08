package com.unciv.models.ruleset.validation

import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.ai.PlayerStrategyDef
import com.unciv.models.ruleset.ai.StrategyDef
import com.unciv.models.stats.Stat

/** Checks of AI.json ([Ruleset.ai]) and of the AI fields of Personalities.json */
internal class AiRulesetValidator(private val ruleset: Ruleset) {
    private val ai get() = ruleset.ai

    /** Unknown flavor, yield, diplomacy-personality and approach names - errors in any ruleset or mod */
    fun addNameErrors(lines: RulesetErrorList) {
        fun checkFlavors(map: Map<String, Int>, where: String) {
            for (name in map.keys)
                if (Flavor.fromName(name) == null)
                    lines.add("AI.json: $where has unknown flavor '$name'", sourceObject = null)
        }
        fun checkStats(names: Collection<String>, where: String) {
            for (name in names)
                if (Stat.safeValueOf(name) == null)
                    lines.add("AI.json: $where has unknown yield '$name'", sourceObject = null)
        }
        for (def in ai.grandStrategies) {
            checkFlavors(def.flavors, "grand strategy ${def.name}")
            checkFlavors(def.flavorMods, "grand strategy ${def.name}")
            checkStats(def.yields.keys, "grand strategy ${def.name}")
        }
        val strategies: List<StrategyDef> = ai.economicStrategies + ai.militaryStrategies + ai.cityStrategies
        for (def in strategies) {
            checkFlavors(def.personalityFlavorThresholdMods, "strategy ${def.name}")
            if (def is PlayerStrategyDef) {
                checkFlavors(def.playerFlavors, "strategy ${def.name}")
                checkFlavors(def.cityFlavors, "strategy ${def.name}")
            }
        }
        for (def in ai.cityStrategies) checkFlavors(def.flavors, "city strategy ${def.name}")
        for (def in ai.citySpecializations) {
            checkFlavors(def.flavors, "city specialization ${def.name}")
            checkStats(def.targetYields.keys + listOfNotNull(def.yieldType), "city specialization ${def.name}")
        }
        for (entry in ai.minorCivFlavors) checkFlavors(entry.flavors, "minor civ ${entry.name}")
        for (entry in ai.itemFlavors) checkFlavors(entry.flavors, "${entry.kind} ${entry.name}")

        for (personality in ruleset.personalities.values) {
            for (name in personality.flavors.keys)
                if (Flavor.fromName(name) == null)
                    lines.add("Personality ${personality.name} has unknown flavor '$name'", sourceObject = personality)
            for (name in personality.diplomacyPersonality.keys)
                if (DiplomacyPersonality.entries.none { it.name == name })
                    lines.add("Personality ${personality.name} has unknown diplomacy personality value '$name'", sourceObject = personality)
            for (name in personality.majorCivApproachBiases.keys)
                if (MajorCivApproach.entries.none { it.name == name })
                    lines.add("Personality ${personality.name} has unknown major civ approach '$name'", sourceObject = personality)
            for (name in personality.minorCivApproachBiases.keys)
                if (MinorCivApproach.entries.none { it.name == name })
                    lines.add("Personality ${personality.name} has unknown minor civ approach '$name'", sourceObject = personality)
        }
    }

    /**
     *  Names of ruleset objects in AI.json that do not exist - only for base rulesets (an extension can refer to its base's objects).
     *  Warnings: an entry for a missing object is inert (e.g. when a mod removes the object), it cannot break the AI.
     */
    fun addObjectNameErrors(lines: RulesetErrorList) {
        fun warn(text: String) = lines.add("AI.json: $text", RulesetErrorSeverity.Warning, sourceObject = null)

        for (def in ai.grandStrategies)
            if (def.victoryType !in ruleset.victories) warn("grand strategy ${def.name} pursues victory '${def.victoryType}' which does not exist")
        val strategies: List<StrategyDef> = ai.economicStrategies + ai.militaryStrategies + ai.cityStrategies
        for (def in strategies) {
            for (tech in listOfNotNull(def.techPrereq, def.techObsolete))
                if (tech !in ruleset.technologies) warn("strategy ${def.name} refers to tech '$tech' which does not exist")
        }
        for (entry in ai.minorCivFlavors)
            if (ruleset.nations[entry.name]?.isCityState != true) warn("minor civ flavors for '${entry.name}', which is no city-state")
        for (entry in ai.itemFlavors) {
            val exists = when (entry.kind) {
                AiRuleset.KIND_BUILDING -> entry.name in ruleset.buildings
                AiRuleset.KIND_UNIT -> entry.name in ruleset.units
                AiRuleset.KIND_TECHNOLOGY -> entry.name in ruleset.technologies
                AiRuleset.KIND_POLICY -> entry.name in ruleset.policies
                AiRuleset.KIND_PROCESS -> entry.name in PerpetualConstruction.perpetualConstructionsMap || entry.name in ruleset.resolutions
                AiRuleset.KIND_PROJECT -> entry.name in ruleset.buildings || entry.name in ruleset.units
                AiRuleset.KIND_RESOURCE -> entry.name in ruleset.tileResources
                AiRuleset.KIND_IMPROVEMENT -> entry.name in ruleset.tileImprovements
                else -> {
                    warn("item flavors of unknown kind '${entry.kind}'")
                    continue
                }
            }
            if (!exists) warn("${entry.kind} flavors for '${entry.name}', which does not exist")
        }
        for (entry in ai.unitAiTypes)
            if (entry.name !in ruleset.units) warn("unit AI types for '${entry.name}', which does not exist")
        for (entry in ai.handicaps)
            if (entry.name !in ruleset.difficulties) warn("handicap values for '${entry.name}', which is no difficulty")
        for (entry in ai.policyBranches)
            if (entry.name !in ruleset.policyBranches) warn("policy branch values for '${entry.name}', which is no policy branch")
    }
}
