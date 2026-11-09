package com.unciv.logic.automation.strategy

import com.unciv.logic.GameInfo
import com.unciv.logic.automation.civilization.CityStateAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.ai.MinorCivApproach
import kotlin.math.log10
import kotlin.random.Random

/**
 *  The personality part of the original's CvFlavorManager and CvDiplomacyAI: how a player's personality flavors and diplomacy personality
 *  values are set up once per game. The state lives in [CivAi].
 */
object FlavorManager {

    /**
     *  Sets up the personality of every civilization of a new game, in the original's order: CvFlavorManager::Init (with RandomizeWeights)
     *  for every player, CvFlavorManager::AdjustWeightsForMap for the living majors (CvGame::setInitialItems), then
     *  CvDiplomacyAI::DoInitializePersonality for the majors (CvGame::InitPlayers... after setInitialItems).
     */
    fun initializeNewGame(gameInfo: GameInfo) {
        val civs = gameInfo.civilizations.filter { !it.isBarbarian && !it.isSpectator }
        for (civ in civs) initPersonalityFlavors(civ)
        for (civ in civs) if (civ.isMajorCiv && civ.isAlive()) adjustWeightsForMap(civ)
        for (civ in civs) if (civ.isMajorCiv) initDiplomacyPersonality(civ)
        for (civ in civs) civ.ai.personalityInitialized = true
    }

    /** The same set-up for one civilization joining later or loaded from a save without AI state */
    fun initializePersonality(civ: Civilization, adjustForMap: Boolean) {
        initPersonalityFlavors(civ)
        if (adjustForMap) adjustWeightsForMap(civ)
        if (civ.isMajorCiv) initDiplomacyPersonality(civ)
        civ.ai.personalityInitialized = true
    }

    /**
     *  CvFlavorManager::Init: an AI major takes its leader's flavors (Personalities.json `flavors`), an AI city-state those of its
     *  MinorCivilization_Flavors entry; a flavor without a value is DEFAULT_FLAVOR_VALUE. Then RandomizeWeights. A human player gets
     *  DEFAULT_FLAVOR_VALUE for every flavor, not randomized. The wonder AI starts from zero weights (CvWonderProductionAI::Init) and gets the
     *  personality as its first flavor broadcast (when the recipient is registered). Deviation: a city-state's CvMinorCivAI::DoPickPersonality
     *  broadcast is not counted; city-states never choose wonders.
     */
    internal fun initPersonalityFlavors(civ: Civilization) {
        val ai = civ.ai
        val rules = civ.gameInfo.ruleset.ai
        val default = rules.define(AiDefine.DEFAULT_FLAVOR_VALUE)
        val vector = if (civ.isHuman()) IntArray(Flavor.count) { default }
        else {
            val source = if (civ.isCityState) rules.minorCivFlavorsOf(civ.civName)?.flavors
                else civ.nation.personality?.let { civ.gameInfo.ruleset.personalities[it] }?.flavors
            val leaderFlavors = Flavor.vectorOf(source ?: emptyMap(), default)
            randomizeWeights(leaderFlavors, civ.ai.random("CvFlavorManager::RandomizeWeights", 0), rules.define(AiDefine.FLAVOR_RANDOMIZATION_RANGE),
                rules.define(AiDefine.PERSONALITY_FLAVOR_MIN_VALUE), rules.define(AiDefine.PERSONALITY_FLAVOR_MAX_VALUE))
            if (civ.isCityState) CityStateAi.pickInitialFlavors(civ, leaderFlavors, civ.ai.random("CvMinorCivAI::DoPickPersonality", 0))
            leaderFlavors
        }
        ai.personalityFlavors.clear()
        for (flavor in Flavor.entries) ai.personalityFlavors[flavor.name] = vector[flavor.ordinal]
        ai.setTransients(civ)
        WonderProductionAi.reset(civ)
        broadcastBaseFlavors(civ)
    }

    /** CvFlavorManager::RandomizeWeights: every nonzero flavor moves by a random amount in -plusMinus..plusMinus, kept within min..max */
    fun randomizeWeights(flavors: IntArray, random: Random, plusMinus: Int, min: Int, max: Int) {
        for (i in flavors.indices)
            if (flavors[i] != 0) flavors[i] = getAdjustedValue(flavors[i], plusMinus, min, max, random)
    }

    /** CvFlavorManager::GetAdjustedValue / CvDiplomacyAI::GetRandomPersonalityWeight: value + a random amount in -plusMinus..plusMinus, kept within min..max */
    fun getAdjustedValue(value: Int, plusMinus: Int, min: Int, max: Int, random: Random): Int =
        (value + random.nextInt(plusMinus * 2 + 1) - plusMinus).coerceIn(min, max)

    /**
     *  CvFlavorManager::AdjustWeightsForMap: with t = land tiles / living majors, adjust = (int)((log10(t) - FLAVOR_STANDARD_LOG10_TILES_PER_PLAYER)
     *  × FLAVOR_EXPANDGROW_COEFFICIENT) is added to Expansion (capped at the maximum) and subtracted from Growth (floored at the minimum).
     */
    fun adjustWeightsForMap(civ: Civilization) {
        val gameInfo = civ.gameInfo
        val players = gameInfo.getAliveMajorCivs().size
        if (players <= 0) return
        val rules = gameInfo.ruleset.ai
        val landTiles = gameInfo.tileMap.values.count { it.isLand }
        val tilesPerPlayer = landTiles.toDouble() / players
        val adjust = ((log10(tilesPerPlayer) - rules.defineFloat(AiDefine.FLAVOR_STANDARD_LOG10_TILES_PER_PLAYER))
            * rules.define(AiDefine.FLAVOR_EXPANDGROW_COEFFICIENT)).toInt()
        val ai = civ.ai
        val default = rules.define(AiDefine.DEFAULT_FLAVOR_VALUE)
        val expansion = (ai.personalityFlavors[Flavor.Expansion.name] ?: default) + adjust
        val growth = (ai.personalityFlavors[Flavor.Growth.name] ?: default) - adjust
        ai.personalityFlavors[Flavor.Expansion.name] = minOf(expansion, rules.define(AiDefine.PERSONALITY_FLAVOR_MAX_VALUE))
        ai.personalityFlavors[Flavor.Growth.name] = maxOf(growth, rules.define(AiDefine.PERSONALITY_FLAVOR_MIN_VALUE))
        ai.setTransients(civ)
        broadcastBaseFlavors(civ)
    }

    /**
     *  CvFlavorManager::BroadcastBaseFlavors to the wonder AI (CvFlavorRecipient::SetFlavors -> FlavorUpdate): the personality is its latest
     *  flavors, and its weights grow by them once more.
     */
    private fun broadcastBaseFlavors(civ: Civilization) {
        val personality = Flavor.vectorOf(civ.ai.personalityFlavors, civ.gameInfo.ruleset.ai.define(AiDefine.DEFAULT_FLAVOR_VALUE))
        WonderProductionAi.flavorUpdate(civ, personality)
    }

    /**
     *  CvDiplomacyAI::DoInitializePersonality: an AI major takes each personality value and approach bias of its leader through
     *  GetRandomPersonalityWeight (±FLAVOR_RANDOMIZATION_RANGE, kept within the personality flavor range); a human gets DEFAULT_FLAVOR_VALUE.
     *  Values the leader lacks are DEFAULT_FLAVOR_VALUE before randomizing.
     */
    internal fun initDiplomacyPersonality(civ: Civilization) {
        val ai = civ.ai
        val rules = civ.gameInfo.ruleset.ai
        val default = rules.define(AiDefine.DEFAULT_FLAVOR_VALUE)
        val personality = civ.nation.personality?.let { civ.gameInfo.ruleset.personalities[it] }
        val random = ai.random("CvDiplomacyAI::DoInitializePersonality", 0)
        val plusMinus = rules.define(AiDefine.FLAVOR_RANDOMIZATION_RANGE)
        val min = rules.define(AiDefine.PERSONALITY_FLAVOR_MIN_VALUE)
        val max = rules.define(AiDefine.PERSONALITY_FLAVOR_MAX_VALUE)
        fun value(source: Map<String, Int>?, key: String): Int =
            if (civ.isHuman()) default
            else getAdjustedValue(source?.get(key) ?: default, plusMinus, min, max, random)

        ai.diplomacyPersonality.clear()
        for (value in DiplomacyPersonality.entries) ai.diplomacyPersonality[value.name] = value(personality?.diplomacyPersonality, value.name)
        ai.majorCivApproachBiases.clear()
        for (approach in MajorCivApproach.entries) ai.majorCivApproachBiases[approach.name] = value(personality?.majorCivApproachBiases, approach.name)
        ai.minorCivApproachBiases.clear()
        for (approach in MinorCivApproach.entries) ai.minorCivApproachBiases[approach.name] = value(personality?.minorCivApproachBiases, approach.name)
    }
}
