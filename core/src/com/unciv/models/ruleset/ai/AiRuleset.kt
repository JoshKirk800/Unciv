package com.unciv.models.ruleset.ai

import com.unciv.models.ruleset.Building
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.tile.ResourceType
import com.unciv.models.ruleset.tile.TileImprovement
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.models.stats.INamed
import com.unciv.models.stats.Stat
import com.unciv.models.stats.Stats
import kotlin.math.max

/**
 *  The AI data of a ruleset (`AI.json`, available as [Ruleset.ai]): the original game's AI tables, generated from its XML.
 *
 *  Mods: a mod's AI.json is merged into the base's like other ruleset objects - defines by name, every list entry by its name
 *  (item flavors by kind + name): a mod entry replaces the base entry of the same name, new ones are appended.
 *  A ruleset without AI.json has empty tables: defines fall back to [AiDefine.default], the grand strategies to [defaultGrandStrategies]
 *  (no flavors, so their priority is situational only), item flavors to the derivation of [flavorsOf], and strategies are simply absent.
 */
class AiRuleset {
    var defines = LinkedHashMap<String, Float>()
    var postDefines = LinkedHashMap<String, String>()
    var grandStrategies = ArrayList<GrandStrategyDef>()
    var economicStrategies = ArrayList<EconomicStrategyDef>()
    var militaryStrategies = ArrayList<MilitaryStrategyDef>()
    var cityStrategies = ArrayList<CityStrategyDef>()
    var citySpecializations = ArrayList<CitySpecializationDef>()
    var minorCivFlavors = ArrayList<NamedFlavors>()
    var itemFlavors = ArrayList<ItemFlavors>()
    /** Technology_PrereqTechs in XML row order, by Unciv tech name - see [TechPrereqsDef] */
    var techPrereqs = ArrayList<TechPrereqsDef>()
    var unitAiTypes = ArrayList<UnitAiTypesDef>()
    var handicaps = ArrayList<HandicapDef>()
    /** Nation names of CvCivilizationInfo::isCoastalCiv (Civilization_Start_Along_Ocean), read by CvCitySiteEvaluator::PlotFoundValue */
    var coastalCivilizations = ArrayList<String>()
    /** CvFeatureInfo::getStartingLocationWeight by terrain feature name (non-zero ones), read by CvCitySiteEvaluator::ComputeStrategicValue */
    var featureStartingLocationWeights = LinkedHashMap<String, Int>()
    var policyBranches = ArrayList<PolicyBranchAiDef>()

    /** The ruleset this belongs to - needed to derive flavors of objects without a table entry */
    @Transient lateinit var ruleset: Ruleset

    @Transient private var itemFlavorIndex: HashMap<String, ItemFlavors>? = null
    @Transient private var minorCivFlavorIndex: HashMap<String, NamedFlavors>? = null
    @Transient private var unitAiTypesIndex: HashMap<String, UnitAiTypesDef>? = null
    @Transient private val flavorCache = HashMap<String, IntArray>()

    companion object {
        const val KIND_BUILDING = "Building"
        const val KIND_UNIT = "Unit"
        const val KIND_TECHNOLOGY = "Technology"
        const val KIND_POLICY = "Policy"
        const val KIND_PROCESS = "Process"
        const val KIND_PROJECT = "Project"
        const val KIND_RESOURCE = "Resource"
        const val KIND_IMPROVEMENT = "Improvement"

        /** Flavor a stat feeds in the derivation of [flavorsOf] */
        private fun flavorOfStat(stat: Stat): Flavor = when (stat) {
            Stat.Production -> Flavor.Production
            Stat.Food -> Flavor.Growth
            Stat.Gold -> Flavor.Gold
            Stat.Science -> Flavor.Science
            Stat.Culture -> Flavor.Culture
            Stat.Happiness -> Flavor.Happiness
            Stat.Faith -> Flavor.Religion
        }
    }

    //region Merging

    fun add(other: AiRuleset) {
        defines.putAll(other.defines)
        postDefines.putAll(other.postDefines)
        mergeByName(grandStrategies, other.grandStrategies)
        mergeByName(economicStrategies, other.economicStrategies)
        mergeByName(militaryStrategies, other.militaryStrategies)
        mergeByName(cityStrategies, other.cityStrategies)
        mergeByName(citySpecializations, other.citySpecializations)
        mergeByName(minorCivFlavors, other.minorCivFlavors)
        mergeByName(unitAiTypes, other.unitAiTypes)
        mergeByName(techPrereqs, other.techPrereqs)
        mergeByName(handicaps, other.handicaps)
        for (name in other.coastalCivilizations) if (name !in coastalCivilizations) coastalCivilizations.add(name)
        featureStartingLocationWeights.putAll(other.featureStartingLocationWeights)
        mergeByName(policyBranches, other.policyBranches)
        for (entry in other.itemFlavors) {
            val index = itemFlavors.indexOfFirst { it.kind == entry.kind && it.name == entry.name }
            if (index >= 0) itemFlavors[index] = entry else itemFlavors.add(entry)
        }
        clearCaches()
    }

    fun clear() {
        defines.clear()
        postDefines.clear()
        grandStrategies.clear()
        economicStrategies.clear()
        militaryStrategies.clear()
        cityStrategies.clear()
        citySpecializations.clear()
        minorCivFlavors.clear()
        itemFlavors.clear()
        techPrereqs.clear()
        unitAiTypes.clear()
        handicaps.clear()
        coastalCivilizations.clear()
        featureStartingLocationWeights.clear()
        policyBranches.clear()
        clearCaches()
    }

    private fun <T : INamed> mergeByName(target: ArrayList<T>, source: List<T>) {
        for (entry in source) {
            val index = target.indexOfFirst { it.name == entry.name }
            if (index >= 0) target[index] = entry else target.add(entry)
        }
    }

    private fun clearCaches() {
        itemFlavorIndex = null
        minorCivFlavorIndex = null
        unitAiTypesIndex = null
        flavorCache.clear()
    }
    //endregion

    //region Lookups

    fun define(define: AiDefine): Int = (defines[define.name] ?: define.default).toInt()
    fun defineFloat(define: AiDefine): Float = defines[define.name] ?: define.default
    fun handicapValue(difficulty: String, column: String): Int? = handicaps.firstOrNull { it.name == difficulty }?.values?.get(column)

    /** The four grand strategies of the original, without flavors, for a ruleset without AI.json */
    @delegate:Transient
    val defaultGrandStrategies: List<GrandStrategyDef> by lazy {
        listOf(
            "AIGRANDSTRATEGY_CONQUEST" to "Domination",
            "AIGRANDSTRATEGY_CULTURE" to "Cultural",
            "AIGRANDSTRATEGY_UNITED_NATIONS" to "Diplomatic",
            "AIGRANDSTRATEGY_SPACESHIP" to "Scientific",
        ).map { (name, victory) -> GrandStrategyDef().apply { this.name = name; victoryType = victory } }
    }

    /** The grand strategies in XML order - the order of the original's loops and of its tie-breaking */
    fun getGrandStrategies(): List<GrandStrategyDef> = grandStrategies.ifEmpty { defaultGrandStrategies }

    /** Personality flavors of a city-state by nation name (MinorCivilization_Flavors), null if it has no entry */
    fun minorCivFlavorsOf(nationName: String): NamedFlavors? {
        val index = minorCivFlavorIndex ?: minorCivFlavors.associateByTo(HashMap()) { it.name }.also { minorCivFlavorIndex = it }
        return index[nationName]
    }

    /** The explicit flavor entry of an item, null if its table has none */
    fun getItemFlavors(kind: String, name: String): ItemFlavors? {
        val index = itemFlavorIndex ?: itemFlavors.associateByTo(HashMap()) { it.kind + "/" + it.name }.also { itemFlavorIndex = it }
        return index["$kind/$name"]
    }

    /** The Unit_AITypes entry of a unit by Unciv unit name, null if it has none */
    fun unitAiTypesOf(unitName: String): UnitAiTypesDef? {
        val index = unitAiTypesIndex ?: unitAiTypes.associateByTo(HashMap()) { it.name }.also { unitAiTypesIndex = it }
        return index[unitName]
    }

    /** The policy AI columns of a policy branch, null if the ruleset has no entry for it */
    fun policyBranchOf(branchName: String): PolicyBranchAiDef? = policyBranches.firstOrNull { it.name == branchName }
    //endregion

    //region Item flavors

    /**
     *  The AI flavors of a ruleset object as a vector by [Flavor.ordinal] - the original's X_Flavors tables (Building_Flavors, Unit_Flavors, ...).
     *  The returned array is cached and shared: do not modify it.
     *
     *  When AI.json has an entry (kind [KIND_BUILDING] or [KIND_PROJECT] for a [Building], [KIND_UNIT] or [KIND_PROJECT] for a [BaseUnit],
     *  [KIND_TECHNOLOGY], [KIND_POLICY] (policies and branches), [KIND_RESOURCE], [KIND_IMPROVEMENT]), that entry is used.
     *  Otherwise (mod objects, rulesets without AI.json) the flavors are derived, on the scale of the original's tables:
     *  - Building: each stat yields its flavor (Food - Growth, Production, Gold, Science, Culture, Happiness, Faith - Religion) at 10 per point;
     *    city strength - CityDefense at 4 per point; Great Person points - GreatPeople at 5 per point; (national) wonder - Wonder 20;
     *    spaceship part - Spaceship 150.
     *  - Unit: settler-type - Expansion 20; worker-type - TileImprovement 20 (water units NavalTileImprovement 20); great person - GreatPeople 10;
     *    spaceship part - Spaceship 150. Military: strength / 4 (at least 1) as Offense and Defense for land melee units, Naval for water units,
     *    Air for air units; land ranged units Ranged = ranged strength / 2 (at least 1) and Offense = Defense = 1; land units with 3+ movement
     *    Mobile 4; nuclear weapons Nuke 16.
     *  - Technology: the sum of the flavors of the buildings, units and improvements it unlocks, divided by 10.
     *  - Resource: its stats as for buildings (10 per point), plus Offense 10 if strategic, Happiness 10 if luxury.
     *  - Improvement: its stats as for buildings.
     *  - Policy: no flavors (the original's Policy_Flavors cannot be derived from Unciv uniques).
     */
    fun flavorsOf(obj: INamed): IntArray {
        val kind = when (obj) {
            is Building -> KIND_BUILDING
            is BaseUnit -> KIND_UNIT
            is Technology -> KIND_TECHNOLOGY
            is Policy -> KIND_POLICY
            is TileResource -> KIND_RESOURCE
            is TileImprovement -> KIND_IMPROVEMENT
            else -> return IntArray(Flavor.count)
        }
        val key = "$kind/${obj.name}"
        flavorCache[key]?.let { return it }
        val explicit = getItemFlavors(kind, obj.name)
            ?: if (obj is Building || obj is BaseUnit) getItemFlavors(KIND_PROJECT, obj.name) else null
        val vector = if (explicit != null) Flavor.vectorOf(explicit.flavors) else deriveFlavors(obj)
        flavorCache[key] = vector
        return vector
    }

    /** Flavors of a process (Process_Flavors) by [PerpetualConstruction] name; without an entry: 5 of the flavor of the stat it produces */
    fun flavorsOfProcess(name: String): IntArray {
        val key = "$KIND_PROCESS/$name"
        flavorCache[key]?.let { return it }
        val explicit = getItemFlavors(KIND_PROCESS, name)
        val vector = if (explicit != null) Flavor.vectorOf(explicit.flavors) else IntArray(Flavor.count).also { vector ->
            val stat = (PerpetualConstruction.perpetualConstructionsMap[name] as? PerpetualConstruction.StatConversion)?.stat
            if (stat != null) vector[flavorOfStat(stat).ordinal] = 5
        }
        flavorCache[key] = vector
        return vector
    }

    private fun deriveFlavors(obj: INamed): IntArray {
        val vector = IntArray(Flavor.count)
        fun add(flavor: Flavor, value: Int) { vector[flavor.ordinal] += value }
        fun addStats(stats: Stats) {
            for ((stat, value) in stats) add(flavorOfStat(stat), (value * 10).toInt())
        }
        when (obj) {
            is Building -> {
                addStats(obj)
                if (obj.cityStrength > 0) add(Flavor.CityDefense, (obj.cityStrength * 4).toInt())
                add(Flavor.GreatPeople, obj.greatPersonPoints.sumValues() * 5)
                if (obj.isWonder || obj.isNationalWonder) add(Flavor.Wonder, 20)
                if (obj.hasUnique(UniqueType.SpaceshipPart, GameContext.IgnoreConditionals)) add(Flavor.Spaceship, 150)
            }
            is BaseUnit -> deriveUnitFlavors(obj, ::add)
            is Technology -> {
                val sum = IntArray(Flavor.count)
                val unlocked = ruleset.buildings.values.asSequence().filter { obj.name in it.requiredTechs() } +
                    ruleset.units.values.asSequence().filter { obj.name in it.requiredTechs() } +
                    ruleset.tileImprovements.values.asSequence().filter { obj.name in it.requiredTechs() }
                for (item in unlocked) {
                    val itemFlavors = flavorsOf(item)
                    for (i in sum.indices) sum[i] += itemFlavors[i]
                }
                for (i in sum.indices) vector[i] = sum[i] / 10
            }
            is TileResource -> {
                addStats(obj)
                if (obj.resourceType == ResourceType.Strategic) add(Flavor.Offense, 10)
                if (obj.resourceType == ResourceType.Luxury) add(Flavor.Happiness, 10)
            }
            is TileImprovement -> addStats(obj)
        }
        return vector
    }

    private fun deriveUnitFlavors(unit: BaseUnit, add: (Flavor, Int) -> Unit) {
        if (unit.hasUnique(UniqueType.SpaceshipPart, GameContext.IgnoreConditionals)) add(Flavor.Spaceship, 150)
        if (unit.isCivilian()) {
            if (unit.isCityFounder()) add(Flavor.Expansion, 20)
            if (unit.hasUnique(UniqueType.BuildImprovements, GameContext.IgnoreConditionals))
                add(if (unit.isWaterUnit) Flavor.NavalTileImprovement else Flavor.TileImprovement, 20)
            if (unit.isGreatPerson) add(Flavor.GreatPeople, 10)
            return
        }
        val power = max(1, max(unit.strength, unit.rangedStrength) / 4)
        when {
            unit.isWaterUnit -> add(Flavor.Naval, power)
            unit.isAirUnit() -> add(Flavor.Air, power)
            unit.isRanged() -> {
                add(Flavor.Ranged, max(1, unit.rangedStrength / 2))
                add(Flavor.Offense, 1)
                add(Flavor.Defense, 1)
            }
            else -> {
                add(Flavor.Offense, power)
                add(Flavor.Defense, power)
            }
        }
        if (unit.isLandUnit && unit.movement >= 3) add(Flavor.Mobile, 4)
        if (unit.hasUnique(UniqueType.NuclearWeapon, GameContext.IgnoreConditionals)) add(Flavor.Nuke, 16)
    }
    //endregion
}
