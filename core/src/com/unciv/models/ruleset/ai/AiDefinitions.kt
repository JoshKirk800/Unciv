package com.unciv.models.ruleset.ai

import com.unciv.models.stats.INamed

/*
 *  The definitions of AI.json, one class per table group of the original XML (see `tools/gen_ai_data.py` in the workspace, which
 *  generates them from the XML). Field names follow the XML columns. A "flavor map" is `{ "<Flavor.name>": value }`; its vector
 *  form (by [Flavor.ordinal]) is built once per definition.
 */

/** AIGrandStrategies + AIGrandStrategy_Flavors, _FlavorMods, _Yields (CvAIGrandStrategyXMLEntry) */
class GrandStrategyDef : INamed {
    override var name = ""
    /** The Unciv victory this grand strategy pursues (CONQUEST - Domination, CULTURE - Cultural, UNITED_NATIONS - Diplomatic, SPACESHIP - Scientific) */
    var victoryType = ""
    /** AIGrandStrategy_Flavors: multiplied with the personality flavors for the base priority (GetBaseGrandStrategyPriority) */
    var flavors = LinkedHashMap<String, Int>()
    /** AIGrandStrategy_FlavorMods: added to the personality while active (GetPersonalityAndGrandStrategy) */
    var flavorMods = LinkedHashMap<String, Int>()
    /** AIGrandStrategy_Yields: city specialization boost by yield (Unciv Stat name) */
    var yields = LinkedHashMap<String, Int>()

    @delegate:Transient val flavorVector by lazy { Flavor.vectorOf(flavors) }
    @delegate:Transient val flavorModVector by lazy { Flavor.vectorOf(flavorMods) }
}

/** The XML row fields common to economic, military and city strategies */
open class StrategyDef : INamed {
    override var name = ""
    var noMinorCivs = false
    var checkTriggerTurnCount = 0
    var minimumNumTurnsExecuted = 0
    /** Game turn before which the strategy is not tested (economic and military only; city strategies have no such column) */
    var firstTurnExecuted = 0
    var weightThreshold = 0
    /** Unciv tech name */
    var techPrereq: String? = null
    /** Unciv tech name */
    var techObsolete: String? = null
    var advisor: String? = null
    var advisorCounsel: String? = null
    var advisorCounselImportance = 0
    /** Per flavor: multiplied with the personality flavor and summed into the weight threshold modifier (GetWeightThresholdModifier) */
    var personalityFlavorThresholdMods = LinkedHashMap<String, Int>()

    @delegate:Transient val thresholdModVector by lazy { Flavor.vectorOf(personalityFlavorThresholdMods) }
}

/** A player-level strategy: its flavors go to the player ([playerFlavors]) and to each of its cities ([cityFlavors]) */
open class PlayerStrategyDef : StrategyDef() {
    var playerFlavors = LinkedHashMap<String, Int>()
    var cityFlavors = LinkedHashMap<String, Int>()

    @delegate:Transient val playerFlavorVector by lazy { Flavor.vectorOf(playerFlavors) }
    @delegate:Transient val cityFlavorVector by lazy { Flavor.vectorOf(cityFlavors) }
}

/** AIEconomicStrategies + _Player_Flavors, _City_Flavors, _PersonalityFlavorThresholdMods */
class EconomicStrategyDef : PlayerStrategyDef()

/** AIMilitaryStrategies + _Player_Flavors, _City_Flavors, _PersonalityFlavorThresholdMods */
class MilitaryStrategyDef : PlayerStrategyDef() {
    var onlyMinorCivs = false
    var updateCitySpecializations = false
}

/** AICityStrategies + AICityStrategy_Flavors, _PersonalityFlavorThresholdMods */
class CityStrategyDef : StrategyDef() {
    /** Once adopted never dropped */
    var permanent = false
    var flavors = LinkedHashMap<String, Int>()

    @delegate:Transient val flavorVector by lazy { Flavor.vectorOf(flavors) }
}

/** CitySpecializations + CitySpecialization_Flavors, _TargetYields */
class CitySpecializationDef : INamed {
    override var name = ""
    /** Unciv Stat name */
    var yieldType: String? = null
    /** DLL ProductionSpecializationSubtypes name, e.g. PRODUCTION_SPECIALIZATION_WONDER */
    var subtype: String? = null
    var isDefault = false
    var isWonder = false
    var mustBeCoastal = false
    var isOperationUnitProvider = false
    var flavors = LinkedHashMap<String, Int>()
    /** By Unciv Stat name */
    var targetYields = LinkedHashMap<String, Int>()

    @delegate:Transient val flavorVector by lazy { Flavor.vectorOf(flavors) }
}

/** MinorCivilization_Flavors: personality flavors of a city-state (by Unciv nation name) */
class NamedFlavors : INamed {
    override var name = ""
    var flavors = LinkedHashMap<String, Int>()
}

/** One row group of an `X_Flavors` item table: [kind] names the table (Building, Unit, Technology, Policy, Process, Project, Resource, Improvement) */
class ItemFlavors {
    var kind = ""
    var name = ""
    var flavors = LinkedHashMap<String, Int>()
}

/** Unit_AITypes + Units.DefaultUnitAI, by Unciv unit name */
class UnitAiTypesDef : INamed {
    override var name = ""
    var defaultUnitAi: String? = null
    var unitAiTypes = ArrayList<String>()
}

/** The AI columns of HandicapInfos, by Unciv difficulty name */
class HandicapDef : INamed {
    override var name = ""
    var values = LinkedHashMap<String, Int>()
}
