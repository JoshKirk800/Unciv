package com.unciv.logic.automation.strategy

import com.unciv.Constants
import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.diplomacy.DiplomacyAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.AiRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.EconomicStrategyDef
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.GrandStrategyDef
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MilitaryStrategyDef
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.models.ruleset.ai.StrategyDef
import com.unciv.utils.hashOf
import org.jetbrains.annotations.VisibleForTesting
import yairm210.purity.annotations.Readonly
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

/** How sure a player is of its guess of another player's grand strategy (the original's GuessConfidenceTypes) */
enum class GuessConfidence { Positive, Likely, Unsure }

/** What one player believes another one's grand strategy is (CvGrandStrategyAI::m_eGuessOtherPlayerActiveGrandStrategy and its confidence) */
class GrandStrategyGuess() : IsPartOfGameInfoSerialization {
    /** Grand strategy name, null = "no grand strategy" */
    var grandStrategy: String? = null
    /** null when [grandStrategy] is null */
    var confidence: GuessConfidence? = null

    constructor(grandStrategy: String?, confidence: GuessConfidence?) : this() {
        this.grandStrategy = grandStrategy
        this.confidence = confidence
    }

    fun clone() = GrandStrategyGuess(grandStrategy, confidence)
}

/** What a player remembers of another player's aggression (CvDiplomacyAI m_paiOtherPlayerNumMinorsAttacked/Conquered, ...MajorsAttacked/Conquered) */
class AggressionMemory : IsPartOfGameInfoSerialization {
    var minorsAttacked = 0
    var minorsConquered = 0
    var majorsAttacked = 0
    var majorsConquered = 0

    fun clone() = AggressionMemory().also {
        it.minorsAttacked = minorsAttacked
        it.minorsConquered = minorsConquered
        it.majorsAttacked = majorsAttacked
        it.majorsConquered = majorsConquered
    }
}

/**
 *  The strategic AI state of a civilization (`civ.ai`), serialized with it: the original's CvFlavorManager (personality and active flavors),
 *  the personality part of CvDiplomacyAI, CvGrandStrategyAI and the strategy state of CvEconomicAI / CvMilitaryAI.
 *  The logic lives in [FlavorManager], [GrandStrategyAi] and [runStrategyLoop]; this class holds the state and the API the ports use.
 *
 *  Old saves have no state: the personality is initialized on first use ([ensureInitialized]), the strategies start inactive.
 */
class CivAi : IsPartOfGameInfoSerialization {
    //region Persisted state

    /** CvFlavorManager::m_piPersonalityFlavor by [Flavor] name: leader flavors, randomized once, adjusted for the map */
    var personalityFlavors = LinkedHashMap<String, Int>()
    /** CvDiplomacyAI personality values by [DiplomacyPersonality] name (GetRandomPersonalityWeight applied once) */
    var diplomacyPersonality = LinkedHashMap<String, Int>()
    /** By [MajorCivApproach] name */
    var majorCivApproachBiases = LinkedHashMap<String, Int>()
    /** By [MinorCivApproach] name */
    var minorCivApproachBiases = LinkedHashMap<String, Int>()
    var personalityInitialized = false

    /** The active grand strategy (AIGRANDSTRATEGY_* name), null before the first [GrandStrategyAi.doTurn] */
    var grandStrategy: String? = null
    var turnsSinceGrandStrategySet = 0
    /** Last computed priority per grand strategy name */
    var grandStrategyPriorities = LinkedHashMap<String, Int>()
    /** Guesses of other majors' grand strategies, by civID */
    var grandStrategyGuesses = HashMap<String, GrandStrategyGuess>()

    /** Active economic strategies: name -> game turn adopted */
    var economicStrategies = LinkedHashMap<String, Int>()
    /** Active military strategies: name -> game turn adopted */
    var militaryStrategies = LinkedHashMap<String, Int>()

    /** CvPlayer::m_bCramped, updated each turn by [updateCramped] */
    var isCramped = false
    /** Aggression of other majors this player has seen, by civID */
    var aggressionMemory = HashMap<String, AggressionMemory>()
    /** CvCitySpecializationAI state, see [CitySpecializationAi] */
    var citySpecialization = CitySpecializationState()
    /** CvDiplomacyAI::m_paeMinorCivApproach: our approach to each city-state by civID, missing = [MinorCivApproach.Ignore] */
    var minorCivApproaches = HashMap<String, MinorCivApproach>()
    /** CvDiplomacyAI war state memory (turns at war, war states, target values, military strength comparison), see [WarStateAi] */
    var warStates = WarStateMemory()
    /** CvCitySpecializationAI::m_iWonderCityID: the [com.unciv.logic.city.City.id] of the city chosen to build wonders, null if none */
    var wonderCityId: String? = null
    /** CvEconomicAI's gold reservations (StartSaveForPurchase ...) */
    var purchaseSavings = PurchaseSavings()
    /** CvUnit::AI_setUnitAIType reassignments: unit id -> UNITAI_* name, see [UnitAiTypes] */
    var unitAiOverrides = HashMap<String, String>()
    /** CvEconomicAI state beyond the strategies, see [EconomicAi] */
    var economic = EconomicAi()
    /** CvMilitaryAI state beyond the strategies, see [MilitaryAi] */
    var military = MilitaryAi()
    /** CvDiplomacyAI state towards other players (opinions, approaches, war goals, disputes, memories), see [DiplomacyAi] */
    var diplomacy = DiplomacyAi()
    /** CvMinorCivAI::m_eStatus of a city-state, updated each turn by [MinorCivStatusAi.status] */
    var minorCivStatus = MinorCivStatus.Normal
    //endregion

    //region Transients

    @Transient lateinit var civ: Civilization

    @Transient private var personalityVector: IntArray? = null
    @Transient private var activeFlavorVector: IntArray? = null
    /** Increased whenever the active economic/military strategies change, so city flavor caches know to rebuild */
    @Transient internal var strategyVersion = 0
        private set

    @Transient private var militaryMightTurn = -1
    @Transient private var militaryMightCache = 0
    @Transient private var tourismTurn = -1
    @Transient private var tourismCache = 0
    //endregion

    fun clone(): CivAi {
        val toReturn = CivAi()
        toReturn.personalityFlavors.putAll(personalityFlavors)
        toReturn.diplomacyPersonality.putAll(diplomacyPersonality)
        toReturn.majorCivApproachBiases.putAll(majorCivApproachBiases)
        toReturn.minorCivApproachBiases.putAll(minorCivApproachBiases)
        toReturn.personalityInitialized = personalityInitialized
        toReturn.grandStrategy = grandStrategy
        toReturn.turnsSinceGrandStrategySet = turnsSinceGrandStrategySet
        toReturn.grandStrategyPriorities.putAll(grandStrategyPriorities)
        for ((civId, guess) in grandStrategyGuesses) toReturn.grandStrategyGuesses[civId] = guess.clone()
        toReturn.economicStrategies.putAll(economicStrategies)
        toReturn.militaryStrategies.putAll(militaryStrategies)
        toReturn.isCramped = isCramped
        for ((civId, memory) in aggressionMemory) toReturn.aggressionMemory[civId] = memory.clone()
        toReturn.citySpecialization = citySpecialization.clone()
        toReturn.minorCivApproaches.putAll(minorCivApproaches)
        toReturn.warStates = warStates.clone()
        toReturn.wonderCityId = wonderCityId
        toReturn.purchaseSavings = purchaseSavings.clone()
        toReturn.unitAiOverrides.putAll(unitAiOverrides)
        toReturn.economic = economic.clone()
        toReturn.military = military.clone()
        toReturn.diplomacy = diplomacy.clone()
        toReturn.minorCivStatus = minorCivStatus
        return toReturn
    }

    fun setTransients(civ: Civilization) {
        this.civ = civ
        diplomacy.civ = civ
        personalityVector = null
        activeFlavorVector = null
        strategyVersion++
    }

    internal val rules: AiRuleset get() = civ.gameInfo.ruleset.ai

    //region Personality (CvFlavorManager, CvDiplomacyAI personality)

    /** Initializes the personality if this civ has none yet (old saves, civs added after game start) - see [FlavorManager.initializePersonality] */
    fun ensureInitialized() {
        if (personalityInitialized) return
        FlavorManager.initializePersonality(civ, adjustForMap = civ.isMajorCiv)
    }

    /** CvFlavorManager::GetAllPersonalityFlavors: the personality vector by [Flavor.ordinal] - shared, do not modify */
    fun personalityFlavorVector(): IntArray {
        personalityVector?.let { return it }
        ensureInitialized()
        val vector = Flavor.vectorOf(personalityFlavors, rules.define(AiDefine.DEFAULT_FLAVOR_VALUE))
        personalityVector = vector
        return vector
    }

    /** CvFlavorManager::GetPersonalityIndividualFlavor: the leader's flavor, randomized once per game (±2, 0..20) and adjusted for the map */
    fun personality(flavor: Flavor): Int = personalityFlavorVector()[flavor.ordinal]

    /** Sets a personality flavor directly (scenario setup and tests); the active flavors follow */
    @VisibleForTesting
    fun setPersonalityFlavor(flavor: Flavor, value: Int) {
        ensureInitialized()
        personalityFlavors[flavor.name] = value
        personalityVector = null
        onStrategiesChanged()
    }

    /** A CvDiplomacyAI personality value (GetVictoryCompetitiveness, GetBoldness, ...), randomized once per game by GetRandomPersonalityWeight */
    fun diplomacyValue(value: DiplomacyPersonality): Int {
        ensureInitialized()
        return diplomacyPersonality[value.name] ?: rules.define(AiDefine.DEFAULT_FLAVOR_VALUE)
    }

    /** CvDiplomacyAI::GetPersonalityMajorCivApproachBias */
    fun majorCivApproachBias(approach: MajorCivApproach): Int {
        ensureInitialized()
        return majorCivApproachBiases[approach.name] ?: rules.define(AiDefine.DEFAULT_FLAVOR_VALUE)
    }

    /** CvDiplomacyAI::GetPersonalityMinorCivApproachBias */
    fun minorCivApproachBias(approach: MinorCivApproach): Int {
        ensureInitialized()
        return minorCivApproachBiases[approach.name] ?: rules.define(AiDefine.DEFAULT_FLAVOR_VALUE)
    }

    /** Sets a diplomacy personality value directly (scenario setup and tests) */
    @VisibleForTesting
    fun setDiplomacyValue(value: DiplomacyPersonality, amount: Int) {
        ensureInitialized()
        diplomacyPersonality[value.name] = amount
    }

    /** Sets an approach bias directly (scenario setup and tests) */
    @VisibleForTesting
    fun setMajorCivApproachBias(approach: MajorCivApproach, amount: Int) {
        ensureInitialized()
        majorCivApproachBiases[approach.name] = amount
    }
    //endregion

    //region Active flavors

    /**
     *  CvFlavorManager::GetAllFlavors: the player's current flavors by [Flavor.ordinal] - the personality plus the player flavors of every active
     *  economic and military strategy, each kept within FLAVOR_MIN_VALUE..FLAVOR_MAX_VALUE. Values can be negative (as the original's
     *  active flavors; its recipients read them through GetLatestFlavorValue, which shows negatives as 0). Shared array, do not modify.
     */
    fun flavors(): IntArray {
        activeFlavorVector?.let { return it }
        val vector = personalityFlavorVector().copyOf()
        val min = rules.define(AiDefine.FLAVOR_MIN_VALUE)
        val max = rules.define(AiDefine.FLAVOR_MAX_VALUE)
        for (def in activeEconomicStrategyDefs()) addFlavorDelta(vector, def.playerFlavorVector, min, max)
        for (def in activeMilitaryStrategyDefs()) addFlavorDelta(vector, def.playerFlavorVector, min, max)
        activeFlavorVector = vector
        return vector
    }

    /** CvFlavorManager::GetIndividualFlavor - see [flavors] */
    fun flavor(flavor: Flavor): Int = flavors()[flavor.ordinal]

    internal fun onStrategiesChanged() {
        activeFlavorVector = null
        strategyVersion++
    }

    internal fun activeEconomicStrategyDefs(): Sequence<EconomicStrategyDef> =
        rules.economicStrategies.asSequence().filter { it.name in economicStrategies }

    internal fun activeMilitaryStrategyDefs(): Sequence<MilitaryStrategyDef> =
        rules.militaryStrategies.asSequence().filter { it.name in militaryStrategies }
    //endregion

    //region Grand strategy (CvGrandStrategyAI, CvDiplomacyAI::IsGoingFor*)

    /** The active grand strategy's definition, null if none */
    fun grandStrategyDef(): GrandStrategyDef? = grandStrategy?.let { name -> rules.getGrandStrategies().firstOrNull { it.name == name } }

    /** CvGrandStrategyAI::GetGrandStrategyPriority: last computed priority, -1 before the first computation */
    fun grandStrategyPriority(grandStrategy: String): Int = grandStrategyPriorities[grandStrategy] ?: -1

    /** CvGrandStrategyAI::GetPersonalityAndGrandStrategy: personality flavor plus the active grand strategy's FlavorMod, at least 0 */
    fun personalityAndGrandStrategy(flavor: Flavor): Int {
        val active = grandStrategyDef() ?: return personality(flavor)
        return max(0, active.flavorModVector[flavor.ordinal] + personality(flavor))
    }

    /** CvGrandStrategyAI::GetGuessOtherPlayerActiveGrandStrategy and its confidence; (null, null) if no guess */
    fun guessGrandStrategy(other: Civilization): Pair<String?, GuessConfidence?> {
        val guess = grandStrategyGuesses[other.civID] ?: return Pair(null, null)
        return Pair(guess.grandStrategy, guess.confidence)
    }

    /** CvDiplomacyAI::IsGoingForWorldConquest */
    @Readonly fun isGoingForWorldConquest() = grandStrategy == GrandStrategyAi.CONQUEST
    /** CvDiplomacyAI::IsGoingForDiploVictory */
    @Readonly fun isGoingForDiploVictory() = grandStrategy == GrandStrategyAi.UNITED_NATIONS
    /** CvDiplomacyAI::IsGoingForCultureVictory */
    @Readonly fun isGoingForCultureVictory() = grandStrategy == GrandStrategyAi.CULTURE
    /** CvDiplomacyAI::IsGoingForSpaceshipVictory */
    @Readonly fun isGoingForSpaceshipVictory() = grandStrategy == GrandStrategyAi.SPACESHIP
    //endregion

    //region Economic and military strategies

    /** CvEconomicAI::IsUsingStrategy */
    fun isEconomicStrategyActive(name: String) = name in economicStrategies
    /** CvMilitaryAI::IsUsingStrategy */
    fun isMilitaryStrategyActive(name: String) = name in militaryStrategies
    /** CvEconomicAI::GetTurnStrategyAdopted: -1 if not active */
    fun economicStrategyTurnAdopted(name: String): Int = economicStrategies[name] ?: -1
    /** CvMilitaryAI::GetTurnStrategyAdopted: -1 if not active */
    fun militaryStrategyTurnAdopted(name: String): Int = militaryStrategies[name] ?: -1

    /** EconomicAIHelpers / MilitaryAIHelpers / CityStrategyAIHelpers::GetWeightThresholdModifier: Σ personality flavor × the strategy's
     *  PersonalityFlavorThresholdMods. A test compares against `def.weightThreshold` plus this. */
    fun weightThresholdModifier(def: StrategyDef): Int {
        val personality = personalityFlavorVector()
        val mods = def.thresholdModVector
        var sum = 0
        for (i in 0 until Flavor.count) sum += personality[i] * mods[i]
        return sum
    }
    //endregion

    //region Inputs the original keeps on CvPlayer

    /**
     *  CvPlayer::GetMilitaryMight (calculateMilitaryMight), computed once per turn: per unit the unit type's power, × (1000 + 125 × level) / 1000,
     *  × health / 100, halved for water units; the sum × min(2, 1 + √gold / 100).
     *  The unit type's power (CvUnitEntry::GetPower) is Unciv's port of it, [com.unciv.models.ruleset.unit.BaseUnit.getForceEvaluation];
     *  the unit level is 1 + its promotions gained by experience (CvUnit::getLevel starts at 1).
     */
    fun militaryMight(): Int {
        val turn = civ.gameInfo.turns
        if (militaryMightTurn == turn) return militaryMightCache
        var sum = 0
        for (unit in civ.units.getCivUnits()) {
            var power = unit.baseUnit.getForceEvaluation()
            if (power == 0) continue
            val level = 1 + unit.promotions.numberOfPromotions
            power = power * (1000 + level * 125) / 1000
            power = power * unit.health / 100
            if (unit.baseUnit.isWaterUnit) power /= 2
            sum += power
        }
        val goldMultiplier = min(2f, 1f + sqrt(max(0, civ.gold).toFloat()) / 100f)
        militaryMightCache = (sum * goldMultiplier).toInt()
        militaryMightTurn = turn
        return militaryMightCache
    }

    /** CvPlayerCulture::GetTourism, computed once per turn */
    fun tourism(): Int {
        val turn = civ.gameInfo.turns
        if (tourismTurn == turn) return tourismCache
        tourismCache = civ.culture.getTourism()
        tourismTurn = turn
        return tourismCache
    }

    /**
     *  CvPlayer::DoUpdateCramped: within CRAMPED_RANGE_FROM_CITY of each city (tiles counted once per city), among the tiles we do not own,
     *  the share of unowned passable land (not impassable, not mountain, not water); cramped when that share is at most CRAMPED_USABLE_PLOT_PERCENT.
     *  Without such tiles the previous value stays.
     */
    fun updateCramped() {
        val range = rules.define(AiDefine.CRAMPED_RANGE_FROM_CITY)
        var total = 0
        var usable = 0
        for (city in civ.cities) {
            for (tile in city.getCenterTile().getTilesInDistance(range)) {
                val owner = tile.getOwner()
                if (owner == civ) continue
                total++
                if (owner == null && !tile.isImpassible() && !tile.isWater && tile.baseTerrain != Constants.mountain) usable++
            }
        }
        if (total > 0) isCramped = 100 * usable / total <= rules.define(AiDefine.CRAMPED_USABLE_PLOT_PERCENT)
    }

    /** The aggression of [other] as this player remembers it */
    fun aggressionOf(other: Civilization): AggressionMemory = aggressionMemory.getOrPut(other.civID) { AggressionMemory() }
    //endregion

    /** A random generator deterministic per game, civilization, purpose and turn (never Random.Default, so simulations stay reproducible) */
    internal fun random(purpose: String, turn: Int = civ.gameInfo.turns): Random =
        Random(hashOf(purpose.hashCode(), civ.civID.hashCode(), civ.gameInfo.gameId.hashCode(), turn))

    /**
     *  The strategic AI turn of this civilization, in CvPlayer::doTurn / doTurnPostDiplomacy order:
     *  DoUpdateCramped, CvGrandStrategyAI::DoTurn (majors), the war states of CvDiplomacyAI::DoTurn (majors, [WarStateAi.doTurn]),
     *  then CvEconomicAI::DoTurn (recon state and antiquity sites, the strategy loop, then for AI players plot purchases and worker
     *  disbanding - [EconomicAi]), CvMilitaryAI::DoTurn (base data and defense states, the strategy loop, then for AI players offensive
     *  purchases and obsolete unit disbanding - [MilitaryAi]), then each city's CvCityStrategyAI::DoTurn (in CvCity::doTurn).
     *  Runs for every living civilization except barbarians and spectators, human or AI, as in the original.
     */
    fun doTurn() {
        if (civ.isBarbarian || civ.isSpectator || civ.isDefeated()) return
        ensureInitialized()
        timeThis("AI.Cramped") { updateCramped() }
        if (civ.isMajorCiv) timeThis("AI.GrandStrategy") { GrandStrategyAi.doTurn(civ) }
        if (civ.isMajorCiv) timeThis("AI.WarStates") { WarStateAi.doTurn(civ) }
        if (civ.isCityState) minorCivStatus = MinorCivStatusAi.status(civ)
        timeThis("AI.EconomicStrategies") {
            economic.doTurnBeforeStrategies(civ)
            if (runStrategyLoop(rules.economicStrategies, economicStrategies, civ, checkFirstTurn = true) { StrategyTests.test(civ, it) })
                onStrategiesChanged()
        }
        if (!civ.isHuman()) timeThis("AI.EconomicDoTurn") { economic.doTurnAfterStrategies(civ) }
        timeThis("AI.MilitaryStrategies") {
            military.doTurnBeforeStrategies(civ)
            if (runStrategyLoop(rules.militaryStrategies, militaryStrategies, civ, checkFirstTurn = true) { StrategyTests.test(civ, it) })
                onStrategiesChanged()
        }
        if (!civ.isHuman()) timeThis("AI.MilitaryDoTurn") { military.doTurnAfterStrategies(civ) }
        timeThis("AI.CityStrategies") {
            for (city in civ.cities) city.ai.doTurn()
        }
    }
}
