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

/** Unit_AITypes + Units.DefaultUnitAI + Units.CivilianAttackPriority, by Unciv unit name */
class UnitAiTypesDef : INamed {
    override var name = ""
    var defaultUnitAi: String? = null
    /** CvUnitEntry::GetCivilianAttackPriority: `CIVILIAN_ATTACK_PRIORITY_HIGH`, `_HIGH_EARLY_GAME_ONLY` or `_LOW`, null for none */
    var civilianAttackPriority: String? = null
    var unitAiTypes = ArrayList<String>()
    /** Units.CombatClass (`UNITCOMBAT_*`), CvUnit::getUnitCombatType; null for units without one (Great People, workers) */
    var combatClass: String? = null
    /** Unit_FreePromotions the unit starts with that [PromotionAiDef] has data for, by promotion type (`PROMOTION_*`) */
    var freePromotions = ArrayList<String>()
}

/** What a terrain or feature does for a promotion: UnitPromotions_Terrains / UnitPromotions_Features columns Attack, Defense and DoubleMove */
class PromotionTerrainAiDef {
    var attack = 0
    var defense = 0
    var doubleMove = false
}

/**
 *  The UnitPromotions columns CvUnit::AI_promotionValue reads, by Unciv promotion [name] ([type] when Unciv has no promotion of it: such rows
 *  only describe what units start with, see [UnitAiTypesDef.freePromotions]). [type] is the `PROMOTION_*` type of the XML, the rows are in the
 *  original's order. Terrains and features are keyed by XML type (`TERRAIN_HILL`, `FEATURE_FOREST`), unit combat classes by `UNITCOMBAT_*`
 *  and domains by `DOMAIN_*`.
 */
class PromotionAiDef : INamed {
    override var name = ""
    var type = ""
    var openAttack = 0
    var openDefense = 0
    var roughAttack = 0
    var roughDefense = 0
    var openRangedAttackMod = 0
    var roughRangedAttackMod = 0
    var visibilityChange = 0
    var cityAttack = 0
    var cityDefense = 0
    var attackFortifiedMod = 0
    var enemyHealChange = 0
    var neutralHealChange = 0
    var friendlyHealChange = 0
    var adjacentTileHealChange = 0
    var rangedDefenseMod = 0
    var attackWoundedMod = 0
    var flankAttackModifier = 0
    var movesChange = 0
    var extraAttacks = 0
    var rangeChange = 0
    var interceptionCombatModifier = 0
    var airSweepCombatModifier = 0
    var numInterceptionChange = 0
    var interceptionDefenseDamageModifier = 0
    var defenseMod = 0
    var instaHeal = false
    var amphib = false
    var river = false
    var rangeAttackIgnoreLOS = false
    var healOutsideFriendly = false
    var alwaysHeal = false
    var blitz = false
    var canMoveAfterAttacking = false
    var noDefensiveBonus = false
    var airSweepCapable = false
    var terrains = LinkedHashMap<String, PromotionTerrainAiDef>()
    var features = LinkedHashMap<String, PromotionTerrainAiDef>()
    var unitCombatModifiers = LinkedHashMap<String, Int>()
    var domainModifiers = LinkedHashMap<String, Int>()
}

/** The AI columns of HandicapInfos, by Unciv difficulty name */
class HandicapDef : INamed {
    override var name = ""
    var values = LinkedHashMap<String, Int>()
}

/** The PolicyBranchTypes columns the policy AI reads, by Unciv branch name: the AIDelayNo* flags of CvPolicyAI::IsBranchEffectiveInGame
 *  and the free tenets of the first and second adopter (PolicyHelpers::GetNumFreePolicies) */
class PolicyBranchAiDef : INamed {
    override var name = ""
    var aiDelayNoReligion = false
    var aiDelayNoCulture = false
    var aiDelayNoScience = false
    var aiDelayNoCityStates = false
    var firstAdopterFreePolicies = 0
    var secondAdopterFreePolicies = 0
}

/** Eras.DiploEmphasisReligion / DiploEmphasisLatePolicies, by Unciv era name (religion and ideology opinion of CvDiplomacyAI) */
class EraDiplomacyDef : INamed {
    override var name = ""
    var diploEmphasisReligion = 0
    var diploEmphasisLatePolicies = 0
}

/** GameSpeeds.RelationshipDuration / OpinionDurationPercent, by Unciv speed name (XML defaults: standard speed) */
class GameSpeedDiplomacyDef : INamed {
    override var name = ""
    var relationshipDuration = 50
    var opinionDurationPercent = 100
}

/** Worlds.EstimatedNumCities, by Unciv map size name (CvDiplomacyAIHelpers::GetWarmongerOffset) */
class WorldSizeDef : INamed {
    override var name = ""
    var estimatedNumCities = 52
}

/** MultiUnitPositionTypes (CvTacticalAI.h): where a unit of a formation stands relative to the others while the army moves */
enum class FormationPosition(val xmlName: String) {
    BOMBARD("MUPOSITION_BOMBARD"), FRONT_LINE("MUPOSITION_FRONT_LINE"), CIVILIAN_SUPPORT("MUPOSITION_CIVILIAN_SUPPORT"),
    NAVAL_ESCORT("MUPOSITION_NAVAL_ESCORT");

    companion object {
        fun ofXmlName(name: String) = entries.firstOrNull { it.xmlName == name }
    }
}

/** One slot of a MultiUnitFormation: the unit AI types that fill it, whether the formation needs it, and its position (MultiUnitPositionType) */
class FormationSlotDef {
    var primaryUnitAi = ""
    var secondaryUnitAi = ""
    var required = false
    /** MUPOSITION_* name; "" in data generated before positions were added */
    var positionType = ""

    val position get() = FormationPosition.ofXmlName(positionType) ?: FormationPosition.FRONT_LINE
}

/** MultiUnitFormation_SlotEntries by formation type (CvMilitaryAI::IsAttackReady) */
class MultiUnitFormationDef : INamed {
    override var name = ""
    /** MultiUnitFormations.RequiresNavalUnitConsistency: with ocean-going ships available only those may fill the slots (naval and city-state invasions) */
    var requiresNavalUnitConsistency = false
    var slots = ArrayList<FormationSlotDef>()
}

/** One TacticalMoves row (CvTacticalMoveXMLEntry): [priority] orders the moves of a turn, [dominanceZoneMove] runs them once per dominance zone */
class TacticalMoveDef : INamed {
    override var name = ""
    var priority = 0
    var operationsCanRecruit = false
    var dominanceZoneMove = false
    var offenseFlavorWeight = 0
    var defenseFlavorWeight = 0
}
