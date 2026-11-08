package com.unciv.logic.automation.strategy

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.civilization.UseGoldAutomation
import com.unciv.logic.automation.diplomacy.AiThreat
import com.unciv.logic.automation.unit.CitySiteEvaluator
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import kotlin.math.max
import kotlin.math.min

/** CvMilitaryAI DefenseState, best to worst (the original compares them by this order) */
enum class DefenseState { Enough, Neutral, Needed, Critical }

/** CvMilitaryAI ArmyType: what kind of army the cities are asked to supply units for */
enum class ArmyType { Land, NavalInvasion }

/**
 *  The strategic part of CvMilitaryAI beyond the strategy loop (`civ.ai.military`): threats, barbarian scan, unit counts by role,
 *  recommended army and navy sizes, defense states, offensive purchases and disbanding of obsolete units.
 *
 *  For the production port, the military AI's requests are its strategies' flavors (through `civ.ai.flavors()` / `city.ai.flavors()`)
 *  and this API: [landDefenseState], [navalDefenseState], [recommendedMilitarySize], [mandatoryReserveSize], [recommendedNavySize],
 *  [percentOfRecommendedMilitarySize], the unit counts ([numLandUnits], [numRangedLandUnits], [numMobileLandUnits], [numMeleeLandUnits],
 *  [numAntiAirUnits], [numNavalUnits], [numAirUnits]), [highestThreat], [totalThreatWeight] and [barbarianThreatTotal].
 *
 *  Not ported, with the reason: UpdateWars (its only effect is a log line), UpdateOperations and MakeEmergencyPurchases (they work on
 *  AI operations: CvAIOperation is out of scope - Unciv has no operations, so GetArmyBeingBuilt is always NO_ARMY_TYPE and an army is
 *  never "in an army").
 */
class MilitaryAi : IsPartOfGameInfoSerialization {
    /** CvMilitaryAI::m_paeLastTurnMilitaryThreat: by civID, [AiThreat] name (missing = None) */
    var lastTurnMilitaryThreats = HashMap<String, String>()
    /** CvMilitaryAI::m_iTotalThreatWeight: starts at 1, "don't ever assume there is no threat at all" */
    var totalThreatWeight = 1
    /** CvMilitaryAI::m_iNumberOfTimesOpsBuildSkippedOver: how often a city was offered a unit for an operation and chose another item; it raises that unit's weight */
    var numberOfTimesOpsBuildSkippedOver = 0
    /** CvMilitaryAI::m_eArmyTypeBeingBuilt: set when an attack was requested that the units at hand cannot form, never reset in the original */
    var armyTypeBeingBuilt: ArmyType? = null
    /** CvMilitaryAI::m_iNumLandAttacksRequested / m_iNumNavalAttacksRequested: the attacks that could not start for lack of units */
    var numLandAttacksRequested = 0
    var numNavalAttacksRequested = 0

    //region Per-turn data (CvMilitaryAI::UpdateBaseData, ScanForBarbarians, UpdateDefenseState), recomputed each turn
    @Transient private var dataTurn = -1
    @Transient var numLandUnits = 0; private set
    @Transient var numRangedLandUnits = 0; private set
    @Transient var numMobileLandUnits = 0; private set
    @Transient var numMeleeLandUnits = 0; private set
    @Transient var numAntiAirUnits = 0; private set
    @Transient var numNavalUnits = 0; private set
    @Transient var numAirUnits = 0; private set
    @Transient var mandatoryReserveSize = 0; private set
    @Transient var recommendedMilitarySize = 0; private set
    @Transient var landDefenseState = DefenseState.Neutral; private set
    @Transient var navalDefenseState = DefenseState.Neutral; private set
    @Transient var barbarianCampCount = 0; private set
    @Transient var visibleBarbarianCount = 0; private set
    //endregion

    fun clone(): MilitaryAi {
        val toReturn = MilitaryAi()
        toReturn.lastTurnMilitaryThreats.putAll(lastTurnMilitaryThreats)
        toReturn.totalThreatWeight = totalThreatWeight
        toReturn.numberOfTimesOpsBuildSkippedOver = numberOfTimesOpsBuildSkippedOver
        toReturn.armyTypeBeingBuilt = armyTypeBeingBuilt
        toReturn.numLandAttacksRequested = numLandAttacksRequested
        toReturn.numNavalAttacksRequested = numNavalAttacksRequested
        return toReturn
    }

    /** The unit-short branch of RequestSneakAttack / RequestCityStateAttack: one more attack by land or by sea waits for units, the larger count names the army */
    fun requestArmy(bySea: Boolean) {
        if (bySea) numNavalAttacksRequested++ else numLandAttacksRequested++
        armyTypeBeingBuilt = if (numLandAttacksRequested > numNavalAttacksRequested) ArmyType.Land else ArmyType.NavalInvasion
    }

    /** What CvMilitaryAI::DoTurn does before its strategy loop: ScanForBarbarians, UpdateThreats, UpdateBaseData, UpdateDefenseState */
    fun doTurnBeforeStrategies(civ: Civilization) {
        scanForBarbarians(civ)
        updateThreats(civ)
        updateBaseData(civ)
        updateDefenseState(civ)
        dataTurn = civ.gameInfo.turns
    }

    /** What CvMilitaryAI::DoTurn does after its strategy loop for AI players: MakeOffensivePurchases, DisbandObsoleteUnits */
    fun doTurnAfterStrategies(civ: Civilization) {
        makeOffensivePurchases(civ)
        disbandObsoleteUnits(civ)
    }

    /** Computes the per-turn data if it was never computed (a game just loaded, or another player's AI asking before our turn) */
    fun ensureData(civ: Civilization) {
        if (dataTurn < 0) doTurnBeforeStrategies(civ)
    }

    //region Threats

    /** CvMilitaryAI::GetThreatWeight */
    fun threatWeight(civ: Civilization, threat: AiThreat): Int {
        val rules = civ.gameInfo.ruleset.ai
        return when (threat) {
            AiThreat.None -> 0
            AiThreat.Minor -> rules.define(AiDefine.AI_MILITARY_THREAT_WEIGHT_MINOR)
            AiThreat.Major -> rules.define(AiDefine.AI_MILITARY_THREAT_WEIGHT_MAJOR)
            AiThreat.Severe -> rules.define(AiDefine.AI_MILITARY_THREAT_WEIGHT_SEVERE)
            AiThreat.Critical -> rules.define(AiDefine.AI_MILITARY_THREAT_WEIGHT_CRITICAL)
        }
    }

    /** CvMilitaryAI::UpdateThreats (ThreatIncrease/ThreatDecrease): the total weight follows each valid major's change of threat level */
    fun updateThreats(civ: Civilization) {
        for (other in civ.gameInfo.civilizations) {
            if (!other.isMajorCiv || !civ.ai.diplomacy.isPlayerValid(other)) continue
            val threat = civ.ai.diplomacy.militaryThreat(other)
            val last = lastTurnMilitaryThreats[other.civID]?.let { AiThreat.valueOf(it) } ?: AiThreat.None
            if (threat == last) continue
            totalThreatWeight += threatWeight(civ, threat) - threatWeight(civ, last)
            lastTurnMilitaryThreats[other.civID] = threat.name
        }
    }

    /** CvMilitaryAI::GetHighestThreat: the highest military threat level of a valid major, as its level number (NONE = 0 ... CRITICAL = 4) */
    fun highestThreat(civ: Civilization): Int {
        var highest = AiThreat.None
        for (other in civ.gameInfo.civilizations) {
            if (!other.isMajorCiv || !civ.ai.diplomacy.isPlayerValid(other)) continue
            val threat = civ.ai.diplomacy.militaryThreat(other)
            if (threat > highest) highest = threat
        }
        return highest.ordinal
    }

    /**
     *  CvMilitaryAI::ScanForBarbarians: revealed barbarian camps (a camp in our territory counts 10, one within 9 tiles of a city 5)
     *  and visible tiles with barbarian units.
     */
    fun scanForBarbarians(civ: Civilization) {
        var camps = 0
        var visible = 0
        for (tile in civ.gameInfo.tileMap.values) {
            if (civ.hasExplored(tile) && tile.isBarbarianEncampment()) {
                camps++
                if (tile.getOwner() == civ) camps += 9
                else if (civ.cities.any { it.getCenterTile().aerialDistanceTo(tile) < 10 }) camps += 4
            }
            if (tile in civ.viewableTiles && tile.getUnits().any { it.civ.isBarbarian }) visible++
        }
        barbarianCampCount = camps
        visibleBarbarianCount = visible
    }

    /** CvMilitaryAI::GetBarbarianThreatTotal: a major threat per camp, a minor one per AI_MILITARY_BARBARIANS_FOR_MINOR_THREAT barbarians */
    fun barbarianThreatTotal(civ: Civilization): Int {
        val rules = civ.gameInfo.ruleset.ai
        return rules.define(AiDefine.AI_MILITARY_THREAT_WEIGHT_MAJOR) * barbarianCampCount +
            visibleBarbarianCount / rules.define(AiDefine.AI_MILITARY_BARBARIANS_FOR_MINOR_THREAT)
    }
    //endregion

    //region Army size (CvMilitaryAI::UpdateBaseData, UpdateDefenseState)

    /** Whether a unit counts in UpdateBaseData: it can attack and is not exploring */
    private fun countsAsMilitary(unit: MapUnit): Boolean {
        if (!unit.isMilitary()) return false
        val type = UnitAiTypes.current(unit)
        return type != UnitAiType.EXPLORE && type != UnitAiType.EXPLORE_SEA
    }

    /**
     *  CvMilitaryAI::UpdateBaseData. Counts the military units that are not exploring: land ones by role (ranged; else anti-air;
     *  else mobile with more than 2 moves; else melee), naval and air ones. Multiplier = 0.40 + (highest threat + OFFENSE + DEFENSE) / 100
     *  (personality and grand strategy). Mandatory reserve = (DEFEND_MY_LANDS_BASE_UNITS + cities x UNITS_PER_CITY + settlers) x
     *  multiplier + max(0, handicap - 1), at least 1. Strike force (majors) = (7, doubled under conquest, + boldness + max(0, handicap - 1))
     *  x multiplier, at least 1; city-states halve the reserve and have none. Both x 2/3 under NAVAL_MAP.
     */
    fun updateBaseData(civ: Civilization) {
        var land = 0; var ranged = 0; var mobile = 0; var melee = 0; var antiAir = 0; var naval = 0; var air = 0
        for (unit in civ.units.getCivUnits()) {
            if (!countsAsMilitary(unit)) continue
            val baseUnit = unit.baseUnit
            when {
                baseUnit.isLandUnit -> {
                    land++
                    when {
                        baseUnit.isRanged() -> ranged++
                        unit.canIntercept() -> antiAir++
                        baseUnit.movement > 2 -> mobile++
                        else -> melee++
                    }
                }
                baseUnit.isWaterUnit -> naval++
                baseUnit.isAirUnit() -> air++
            }
        }
        numLandUnits = land; numRangedLandUnits = ranged; numMobileLandUnits = mobile; numMeleeLandUnits = melee
        numAntiAirUnits = antiAir; numNavalUnits = naval; numAirUnits = air

        val rules = civ.gameInfo.ruleset.ai
        val navalMap = civ.ai.isEconomicStrategyActive(NAVAL_MAP)
        val flavorOffense = civ.ai.personalityAndGrandStrategy(Flavor.Offense)
        val flavorDefense = civ.ai.personalityAndGrandStrategy(Flavor.Defense)
        val multiplier = 0.40f + (highestThreat(civ) + flavorOffense + flavorDefense).toFloat() / 100f

        var unitsWanted = rules.define(AiDefine.AI_STRATEGY_DEFEND_MY_LANDS_BASE_UNITS)
        unitsWanted += (civ.cities.size * rules.defineFloat(AiDefine.AI_STRATEGY_DEFEND_MY_LANDS_UNITS_PER_CITY)).toInt()
        unitsWanted += UnitAiTypes.numUnitsWithUnitAi(civ, UnitAiType.SETTLE, includeBeingTrained = true)
        var reserve = (unitsWanted * multiplier).toInt()
        val difficulty = max(0, EconomicAi.gameHandicapIndex(civ.gameInfo) - 1)
        reserve += difficulty
        reserve = max(1, reserve)

        if (civ.isCityState) {
            reserve = max(1, reserve / 2)
            unitsWanted = 0
        } else {
            unitsWanted = 7 // size of a basic attack
            if (civ.ai.isGoingForWorldConquest()) unitsWanted *= 2
            unitsWanted += civ.ai.diplomacyValue(DiplomacyPersonality.Boldness)
            unitsWanted += difficulty
            unitsWanted = max(1, (unitsWanted * multiplier).toInt())
        }
        if (navalMap) {
            unitsWanted = unitsWanted * 2 / 3
            reserve = reserve * 2 / 3
        }
        mandatoryReserveSize = reserve
        recommendedMilitarySize = reserve + unitsWanted
    }

    /** CvMilitaryAI::UpdateDefenseState: land and naval unit counts against the reserve, the recommended size and 125% of it */
    fun updateDefenseState(civ: Civilization) {
        landDefenseState = when {
            numLandUnits < mandatoryReserveSize -> DefenseState.Critical
            numLandUnits < recommendedMilitarySize -> DefenseState.Needed
            numLandUnits < recommendedMilitarySize * 5 / 4 -> DefenseState.Neutral
            else -> DefenseState.Enough
        }
        val navySize = recommendedNavySize(civ)
        navalDefenseState = when {
            numNavalUnits <= navySize / 2 -> DefenseState.Critical
            numNavalUnits <= navySize -> DefenseState.Needed
            numNavalUnits <= navySize * 5 / 4 -> DefenseState.Neutral
            else -> DefenseState.Enough
        }
    }

    /** CvMilitaryAI::GetPercentOfRecommendedMilitarySize */
    fun percentOfRecommendedMilitarySize(): Int =
        if (recommendedMilitarySize <= 0) 100 else numLandUnits * 100 / recommendedMilitarySize

    /** CvMilitaryAI::HasAirforce */
    fun hasAirforce(): Boolean = numAirUnits > 0
    //endregion

    //region Purchases and disbanding

    /**
     *  CvMilitaryAI::MakeOffensivePurchases: majors not at war (or winning all wars), with personality OFFENSE >= 7 and a land army below
     *  the recommended size or a navy that is not ENOUGH, buy one unit in the capital - for the navy if its state is worse than the land
     *  one (in the first coastal city if the capital is not; none if no city is): assault or attack ship; otherwise fast attack, attack or
     *  defense when there are no more melee than ranged units, else siege or ranged.
     */
    fun makeOffensivePurchases(civ: Civilization) {
        if (civ.isCityState) return
        if (civ.ai.isMilitaryStrategyActive(AT_WAR) && WarStateAi.stateAllWars(civ) != StateAllWars.Winning) return
        if (civ.ai.personality(Flavor.Offense) < 7) return
        if (percentOfRecommendedMilitarySize() >= 100 && navalDefenseState <= DefenseState.Enough) return
        var city = civ.getCapital(firstCityIfNoCapital = false) ?: return
        var needNaval = navalDefenseState > landDefenseState
        if (needNaval && !city.isCoastal()) {
            val coastal = civ.cities.firstOrNull { it.isCoastal() }
            needNaval = coastal != null
            if (coastal != null) city = coastal
        }
        val order = when {
            needNaval -> listOf(UnitAiType.ASSAULT_SEA, UnitAiType.ATTACK_SEA)
            numMeleeLandUnits <= numRangedLandUnits -> listOf(UnitAiType.FAST_ATTACK, UnitAiType.ATTACK, UnitAiType.DEFENSE)
            else -> listOf(UnitAiType.CITY_BOMBARD, UnitAiType.RANGED)
        }
        for (type in order) if (UseGoldAutomation.buyEmergencyUnit(civ, type, city) != null) return
    }

    /**
     *  CvMilitaryAI::DisbandObsoleteUnits: majors at peace. When losing money (LOSING_MONEY, or more than 5 gold per unit) or, when not
     *  going for conquest, with a land (naval) defense state of NEUTRAL or ENOUGH, finds the weakest obsolete land (naval) unit
     *  ([findBestUnitToScrap]); scraps the land one if the land state is not worse than the naval one or it is not stronger, else the naval one.
     */
    fun disbandObsoleteUnits(civ: Civilization) {
        if (WarStateAi.numberCivsAtWarWith(civ) > 0) return
        if (civ.isCityState) return
        val averageGoldPerUnit = civ.stats.getUnitMaintenance() / max(1, civ.units.getCivUnitsSize())
        val inDeficit = civ.ai.isEconomicStrategyActive(EconomicAi.LOSING_MONEY) || averageGoldPerUnit > 5
        val conquest = civ.ai.isGoingForWorldConquest()

        var land: Pair<MapUnit, Int>? = null
        var naval: Pair<MapUnit, Int>? = null
        if (inDeficit || (landDefenseState <= DefenseState.Neutral && !conquest)) land = findBestUnitToScrap(civ, isLand = true)
        if (inDeficit || (navalDefenseState <= DefenseState.Neutral && !conquest)) naval = findBestUnitToScrap(civ, isLand = false)
        val landScore = land?.second ?: Int.MAX_VALUE
        val navalScore = naval?.second ?: Int.MAX_VALUE
        if (land != null && (landDefenseState <= navalDefenseState || landScore <= navalScore)) land.first.disband()
        else naval?.first?.disband()
    }

    /**
     *  CvMilitaryAI::FindBestUnitToScrap (not deficit-forced): among military units of the domain, skipping all if that domain's defense
     *  state is CRITICAL, land units we can still build, units without a researched obsolete tech, units with at least half the power of
     *  the strongest unit we can build in the domain, and units whose upgrade needs a resource we have while that domain's state is worse
     *  than NEUTRAL; the one with the lowest power (CvUnit::GetPower: level and health). Null if none.
     */
    fun findBestUnitToScrap(civ: Civilization, isLand: Boolean): Pair<MapUnit, Int>? {
        val state = if (isLand) landDefenseState else navalDefenseState
        if (state == DefenseState.Critical) return null
        val strongestBuildable = powerOfStrongestBuildableUnit(civ, isLand)
        var best: MapUnit? = null
        var bestScore = Int.MAX_VALUE
        for (unit in civ.units.getCivUnits()) {
            if (!unit.isMilitary()) continue
            val baseUnit = unit.baseUnit
            if (isLand && !baseUnit.isLandUnit || !isLand && !baseUnit.isWaterUnit) continue
            if (isLand && baseUnit.isBuildable(civ)) continue
            val obsoleteTech = baseUnit.obsoleteTech ?: continue
            if (!civ.tech.isResearched(obsoleteTech)) continue
            if (baseUnit.getForceEvaluation() * 2 >= strongestBuildable) continue
            if (state > DefenseState.Neutral) {
                val upgradeTo = baseUnit.upgradesTo?.let { civ.getEquivalentUnit(it) }
                if (upgradeTo != null && upgradeTo.getResourceRequirementsPerTurn(civ.state).keys.any { civ.getResourceAmount(it) > 0 })
                    continue // we'll wait and try to upgrade this one
            }
            val score = unitPower(unit)
            if (score < bestScore) {
                bestScore = score
                best = unit
            }
        }
        return best?.let { it to bestScore }
    }
    //endregion

    companion object {
        const val AT_WAR = "MILITARYAISTRATEGY_AT_WAR"
        const val NAVAL_MAP = "ECONOMICAISTRATEGY_NAVAL_MAP"
        const val EXPAND_TO_OTHER_CONTINENTS = "ECONOMICAISTRATEGY_EXPAND_TO_OTHER_CONTINENTS"

        /**
         *  MilitaryAIHelpers::ComputeRecommendedNavySize: (1 + coastal cities) x (0.75 + highest threat / 4 + NAVAL / 40) x
         *  AI_STRATEGY_NAVAL_UNITS_PER_CITY, at least 1; x 3/2 under NAVAL_MAP or EXPAND_TO_OTHER_CONTINENTS; x 3/2 for coastal civilizations
         *  (start bias along the coast); + 10 x min(turn, 200) / 200 under conquest.
         */
        fun recommendedNavySize(civ: Civilization): Int {
            val rules = civ.gameInfo.ruleset.ai
            val flavorNaval = civ.ai.personalityAndGrandStrategy(Flavor.Naval)
            var wanted = 1 + civ.cities.count { it.isCoastal() }
            val multiplier = 0.75 + civ.ai.military.highestThreat(civ) / 4.0 + flavorNaval / 40.0
            wanted = (wanted * multiplier * rules.defineFloat(AiDefine.AI_STRATEGY_NAVAL_UNITS_PER_CITY)).toInt()
            wanted = max(1, wanted)
            if (civ.ai.isEconomicStrategyActive(NAVAL_MAP) || civ.ai.isEconomicStrategyActive(EXPAND_TO_OTHER_CONTINENTS))
                wanted = wanted * 3 / 2
            if (CitySiteEvaluator.isCoastalCiv(civ)) wanted = wanted * 3 / 2
            if (civ.ai.isGoingForWorldConquest()) wanted += 10 * min(civ.gameInfo.turns, 200) / 200
            return wanted
        }

        /** CvMilitaryAI::GetPowerOfStrongestBuildableUnit: the highest unit type power among units of the domain we can train */
        fun powerOfStrongestBuildableUnit(civ: Civilization, isLand: Boolean): Int {
            var best = 0
            for (baseUnit in civ.gameInfo.ruleset.units.values) {
                if (isLand && !baseUnit.isLandUnit || !isLand && !baseUnit.isWaterUnit) continue
                val power = baseUnit.getForceEvaluation()
                if (power > best && baseUnit.isBuildable(civ)) best = power
            }
            return best
        }

        /** CvUnit::GetPower: the unit type's power x (1000 + 125 x level) / 1000, x health / 100 (level = 1 + promotions) */
        fun unitPower(unit: MapUnit): Int {
            var power = unit.baseUnit.getForceEvaluation()
            power = power * (1000 + (1 + unit.promotions.numberOfPromotions) * 125) / 1000
            return power * unit.health / 100
        }
    }
}
