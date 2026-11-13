package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.AiDecisionLog
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.tech.Technology
import com.unciv.models.ruleset.tile.TileResource
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

/**
 *  The research AI of the original (CvTechAI with the AI parts of CvPlayerTechs, CvPlayerAI::AI_chooseResearch / AI_chooseFreeTech).
 *
 *  Each tech has a weight ([com.unciv.logic.civilization.managers.TechManager.aiTechWeights]): Technology_Flavors × the player's flavors
 *  (current flavors blended with personality + grand strategy by game progress), × 5 for techs unlocking our unique items, × the number of
 *  nearby resources it lets us improve, with half of it passed down to the prerequisites. The weights are rebuilt when the player's
 *  strategies change, as the original rebuilds them on each flavor change. A research choice divides the weights of the researchable techs by
 *  a power of the turns they take and picks weighted-random among the best few.
 */
object TechAutomation {

    /** HandicapInfos.TechNumOptionsConsidered of the original's Prince and higher handicaps, for rulesets without that data */
    private const val DEFAULT_NUM_OPTIONS_CONSIDERED = 2
    /** A reweighted tech weight is at most this: the original's cast of a huge double to int is undefined (a negative weight on x86), and the sum of weights must fit an Int */
    private const val MAX_REWEIGHTED_WEIGHT = Int.MAX_VALUE / 64
    /** Distance from the city within which unowned tiles count for [localeTechPriorities] (literal in CvPlayerTechs::SetLocalePriorities) */
    private const val LOCALE_UNOWNED_TILE_RANGE = 2

    /** Per-ruleset lookups: the techs by index (ruleset order), their XML-ordered prerequisites, flavors, and the tech that lets a resource be improved */
    private class TechData(val ruleset: Ruleset) {
        val techs: Array<Technology> = ruleset.technologies.values.toTypedArray()
        val index = HashMap<String, Int>(techs.size * 2)
        /** CvTechEntry::GetPrereqAndTechs: in the XML order where AI.json has it for the same set of prerequisites */
        val prereqs: Array<IntArray>
        /** Technology_Flavors by tech index, vectors by [Flavor.ordinal] */
        val flavors: Array<IntArray> = Array(techs.size) { ruleset.ai.flavorsOf(techs[it]) }
        /** Resource name -> index of the tech of the first improvement that connects it, -1 if none; filled on first use */
        private val connectingTech = HashMap<String, Int>()

        /** The tech of the first improvement that connects [resource] (the first matching build of SetLocalePriorities), -1 if none */
        fun connectingTechOf(resource: TileResource): Int = connectingTech.getOrPut(resource.name) {
            val improvement = ruleset.tileImprovements.values.firstOrNull { resource.isImprovedBy(it) }
            improvement?.techRequired?.let { index[it] } ?: -1
        }

        init {
            techs.forEachIndexed { i, tech -> index[tech.name] = i }
            val xmlOrder = ruleset.ai.techPrereqs.associateBy { it.name }
            prereqs = Array(techs.size) { i ->
                val tech = techs[i]
                val ordered = xmlOrder[tech.name]?.prereqs
                    ?.takeIf { it.size == tech.prerequisites.size && tech.prerequisites.containsAll(it) }
                    ?: tech.prerequisites
                ordered.mapNotNull { index[it] }.toIntArray()
            }
        }
    }

    @Volatile private var cachedData: TechData? = null

    private fun data(ruleset: Ruleset): TechData {
        val cached = cachedData
        if (cached != null && cached.ruleset === ruleset) return cached
        return TechData(ruleset).also { cachedData = it }
    }

    //region Weights (CvPlayerTechs::FlavorUpdate, CvTechAI::AddFlavorWeights)

    /**
     *  CvPlayerTechs::FlavorUpdate is called on every change of the player's flavors, i.e. whenever an economic or military strategy is
     *  adopted or dropped (CvFlavorManager::ChangeFlavors), and once at game start. Called each turn after the strategy loops
     *  ([com.unciv.logic.automation.strategy.CivAi.doTurn]): rebuilds the weights when the active strategies differ from those they were built with.
     */
    fun updateFlavorWeights(civ: Civilization) {
        val key = activeStrategiesKey(civ)
        if (civ.tech.aiTechWeightsStrategies != key) rebuildWeights(civ, key)
    }

    private fun activeStrategiesKey(civ: Civilization): String {
        val ai = civ.ai
        val rules = civ.gameInfo.ruleset.ai
        val key = StringBuilder()
        for (def in rules.economicStrategies) if (ai.isEconomicStrategyActive(def.name)) key.append(def.name).append(';')
        for (def in rules.militaryStrategies) if (ai.isMilitaryStrategyActive(def.name)) key.append(def.name).append(';')
        return key.toString()
    }

    /** CvPlayerTechs::FlavorUpdate: SetLocalePriorities, then AddFlavorAsStrategies(TECH_WEIGHT_PROPAGATION_PERCENT) into the stored weights */
    fun rebuildWeights(civ: Civilization, strategiesKey: String = activeStrategiesKey(civ)) {
        val data = data(civ.gameInfo.ruleset)
        val weights = computeWeights(civ, data)
        val stored = civ.tech.aiTechWeights
        stored.clear()
        for (i in data.techs.indices) stored[data.techs[i].name] = weights[i]
        civ.tech.aiTechWeightsStrategies = strategiesKey
    }

    /**
     *  CvPlayerTechs::AddFlavorAsStrategies: the current flavors (negative ones read as 0, CvFlavorRecipient::GetLatestFlavorValue) are scaled so
     *  the biggest is 10, then blended with CvGrandStrategyAI::GetPersonalityAndGrandStrategy by p = elapsed turns × 1000 / estimated end turn
     *  kept within 100..900: (current × (1000 − p) + personality × p) / 1000. Each positive blend goes through CvTechAI::AddFlavorWeights.
     */
    private fun computeWeights(civ: Civilization, data: TechData): IntArray {
        val ai = civ.ai
        val rules = civ.gameInfo.ruleset.ai
        val count = data.techs.size
        val weights = IntArray(count)
        val civPriority = civTechPriorities(civ, data)
        val localePriority = localeTechPriorities(civ, data)
        val flavors = ai.flavors()
        var biggest = -1000
        for (value in flavors) biggest = max(biggest, max(0, value))
        val progress = gameProgressFactor(civ)
        val propagationPercent = rules.define(AiDefine.TECH_WEIGHT_PROPAGATION_PERCENT)
        val propagationLevels = rules.define(AiDefine.TECH_WEIGHT_PROPAGATION_LEVELS)
        val numPrereqs = rules.define(AiDefine.NUM_OR_TECH_PREREQS)
        val temp = IntArray(count)
        for (flavor in Flavor.entries) {
            val f = flavor.ordinal
            val current = if (biggest > 0) max(0, flavors[f]) * 10 / biggest else 0
            val value = (current * (1000 - progress) + ai.personalityAndGrandStrategy(flavor) * progress) / 1000
            if (value <= 0) continue
            // CvTechAI::AddFlavorWeights: tech flavor × weight × CivTechPriority × LocaleTechPriority, propagated, then added
            for (t in 0 until count) temp[t] = data.flavors[t][f] * value * civPriority[t] * localePriority[t]
            for (t in 0 until count)
                if (temp[t] > 0) propagateWeights(data, weights, t, temp[t], propagationPercent, 0, propagationLevels, numPrereqs)
            for (t in 0 until count) weights[t] += temp[t]
        }
        return weights
    }

    /** The p of [computeWeights]: CvGame::getElapsedGameTurns × 1000 / getDefaultEstimateEndTurn (the speed's total turns), within 100..900 */
    private fun gameProgressFactor(civ: Civilization): Int =
        (civ.gameInfo.turns * 1000 / civ.gameInfo.speed.numTotalTurns()).coerceIn(100, 900)

    /**
     *  CvTechAI::PropagateWeights: each of the first NUM_OR_TECH_PREREQS prerequisites gets weight × percent / 100 and passes it on while it is
     *  positive. As in the original, the depth count is post-incremented per prerequisite (the first prerequisite recurses at the same level,
     *  the second one level deeper, ...), and a call at TECH_WEIGHT_PROPAGATION_LEVELS or deeper passes nothing.
     */
    @Suppress("LongParameterList")
    private fun propagateWeights(data: TechData, weights: IntArray, tech: Int, weight: Int, percent: Int, level: Int, maxLevels: Int, numPrereqs: Int) {
        if (level >= maxLevels) return
        val propagated = weight * percent / 100
        var nextLevel = level
        val prereqs = data.prereqs[tech]
        for (i in 0 until min(numPrereqs, prereqs.size)) {
            val prereq = prereqs[i]
            weights[prereq] += propagated
            if (propagated > 0) {
                propagateWeights(data, weights, prereq, propagated, percent, nextLevel, maxLevels, numPrereqs)
                nextLevel++
            }
        }
    }

    /**
     *  CvPlayerTechs::Reset (CivTechPriority): for a major civ, × TECH_PRIORITY_UNIQUE_ITEM on the tech of each of its unique buildings, units
     *  and improvements, × TECH_PRIORITY_MAYA_CALENDAR_BONUS on the tech of a Maya calendar trait. 1 elsewhere.
     */
    private fun civTechPriorities(civ: Civilization, data: TechData): IntArray {
        val priorities = IntArray(data.techs.size) { 1 }
        if (!civ.isMajorCiv) return priorities
        val rules = civ.gameInfo.ruleset.ai
        val uniqueItem = rules.define(AiDefine.TECH_PRIORITY_UNIQUE_ITEM)
        fun multiply(techName: String?, factor: Int) {
            val index = data.index[techName ?: return] ?: return
            priorities[index] *= factor
        }
        for (building in civ.cache.uniqueBuildings) multiply(building.requiredTech, uniqueItem)
        for (unit in civ.cache.uniqueUnits) multiply(unit.requiredTech, uniqueItem)
        for (improvement in civ.cache.uniqueImprovements) multiply(improvement.techRequired, uniqueItem)
        val mayaCalendar = rules.define(AiDefine.TECH_PRIORITY_MAYA_CALENDAR_BONUS)
        for (unique in civ.nation.getMatchingUniques(UniqueType.MayanGainGreatPerson, GameContext.IgnoreConditionals))
            multiply(unique.params[1], mayaCalendar)
        return priorities
    }

    /**
     *  CvPlayerTechs::SetLocalePriorities: 1 + one for each land tile with a resource we can see, among the tiles our cities could work that we
     *  own or that are unowned within 2 of the city, whose connecting improvement we cannot build yet - counted on the tech of that improvement.
     *  (The original counts every tile where CvPlayer::canBuild fails; with the tech known the count no longer matters, the tech being researched.)
     */
    private fun localeTechPriorities(civ: Civilization, data: TechData): IntArray {
        val priorities = IntArray(data.techs.size) { 1 }
        val range = civ.gameInfo.ruleset.modOptions.constants.cityWorkRange
        for (city in civ.cities) {
            val center = city.getCenterTile()
            center.forEachTileInDistance(range) { tile ->
                val owner = tile.getOwner()
                if (owner != civ && (owner != null || tile.aerialDistanceTo(center) > LOCALE_UNOWNED_TILE_RANGE)) return@forEachTileInDistance
                if (tile.isWater) return@forEachTileInDistance
                val resource = tile.tileResource ?: return@forEachTileInDistance
                if (!civ.tech.isRevealed(resource)) return@forEachTileInDistance
                val tech = data.connectingTechOf(resource)
                if (tech >= 0 && !civ.tech.isResearched(data.techs[tech].name)) priorities[tech]++
            }
        }
        return priorities
    }

    /** CvTechAI::GetWeight */
    fun weightOf(civ: Civilization, techName: String): Int = civ.tech.aiTechWeights[techName] ?: 0

    /**
     *  CvTechAI::GetTechRatio: [civ]'s position among the living major civilizations ranked by number of known technologies (stable in the
     *  order of the civilizations), 0 for the first and 1 for the last; 0 when [civ] is alone.
     */
    fun techRatio(civ: Civilization): Float {
        val ranked = civ.gameInfo.getAliveMajorCivs().sortedByDescending { it.tech.researchedTechnologies.size }
        if (ranked.size < 2) return 0f
        return ranked.indexOf(civ).coerceAtLeast(0) / (ranked.size - 1).toFloat()
    }
    //endregion

    //region Choice (CvTechAI::ChooseNextTech, CvPlayerAI::AI_chooseResearch / AI_chooseFreeTech)

    /**
     *  CvPlayerAI::AI_chooseFreeTech for each pending free tech (clears the research queue, then takes CvTechAI::ChooseNextTech(bFreeTech)),
     *  then CvPlayerAI::AI_chooseResearch when nothing is being researched. A free tech is chosen here, on the AI's turn, where the original
     *  chooses it the moment it is granted; when no tech has weight it stays pending (the original's is lost).
     *  The original's adoption of a teammate's research does not apply: Unciv has no teams.
     */
    fun chooseTechToResearch(civ: Civilization) {
        if (civ.tech.freeTechs > 0) {
            val random = civ.ai.random("TechAutomation.freeTech")
            while (civ.tech.freeTechs > 0) {
                val tech = chooseNextTech(civ, freeTech = true, random) ?: break
                civ.tech.techsToResearch.clear()
                civ.tech.getFreeTechnology(tech.name)
            }
        }
        if (civ.tech.techsToResearch.isEmpty()) {
            val tech = chooseNextTech(civ, freeTech = false, civ.ai.random("TechAutomation.research")) ?: return
            civ.tech.techsToResearch.add(tech.name)
        }
    }

    /**
     *  CvTechAI::ChooseNextTech: CvWeightedVector::ChooseFromTopChoices among the best HandicapInfos.TechNumOptionsConsidered (of the game's
     *  handicap) of [researchCandidates]. Null when no tech has weight.
     */
    fun chooseNextTech(civ: Civilization, freeTech: Boolean, random: Random): Technology? {
        val candidates = researchCandidates(civ, freeTech)
        if (candidates.sumOf { it.second.toLong() } <= 0L) return null
        val numOptions = civ.gameInfo.ruleset.ai.handicapValue(civ.gameInfo.getDifficulty().name, "TechNumOptionsConsidered")
            ?: DEFAULT_NUM_OPTIONS_CONSIDERED
        val top = min(numOptions, candidates.size)
        var topTotal = 0
        for (i in 0 until top) topTotal += candidates[i].second
        var roll = random.nextInt(topTotal)
        var chosen = candidates[top - 1].first
        for (i in 0 until top) {
            roll -= candidates[i].second
            if (roll < 0) {
                chosen = candidates[i].first
                break
            }
        }
        AiDecisionLog.log(civ) {
            "${if (freeTech) "free tech" else "researches"} ${chosen.name} of " +
                candidates.take(top).joinToString { "${it.first.name} ${it.second}" } +
                " (science ${civ.stats.statsForNextTurn.science.toInt()})"
        }
        return chosen
    }

    /**
     *  CvTechAI::ChooseNextTech's m_ResearchableTechs: the techs we can research with their weights, divided by the research time
     *  ([ReweightByCost][reweightByCost]) unless the tech is free, sorted by weight (highest first; ties keep the ruleset order).
     */
    fun researchCandidates(civ: Civilization, freeTech: Boolean): List<Pair<Technology, Int>> {
        val data = data(civ.gameInfo.ruleset)
        if (civ.tech.aiTechWeightsStrategies == null) updateFlavorWeights(civ)
        val rules = civ.gameInfo.ruleset.ai
        val baseMod = rules.defineFloat(AiDefine.AI_RESEARCH_WEIGHT_BASE_MOD).toDouble()
        val modPerTurn = rules.defineFloat(AiDefine.AI_RESEARCH_WEIGHT_MOD_PER_TURN_LEFT).toDouble()
        // CvTechAI::ReweightByCost (SDK source, April 2014 balance patch): with lots of science overflow the AI wants an expensive tech
        val needExpensiveTechs = civ.tech.getOverflowScience() * 100 > (civ.stats.statsForNextTurn.science * 100).toInt() * 2
        val candidates = ArrayList<Pair<Technology, Int>>()
        for (tech in data.techs) {
            if (!civ.tech.canBeResearched(tech.name)) continue
            var weight = weightOf(civ, tech.name)
            if (!freeTech) weight = reweightByCost(weight, researchTurnsLeft(civ, tech), baseMod, modPerTurn, needExpensiveTechs)
            candidates.add(tech to weight)
        }
        candidates.sortByDescending { it.second }
        return candidates
    }

    /**
     *  CvPlayerTechs::GetResearchTurnsLeft(bOverflow = true): the science still needed (cost − progress − overflow) over the science per turn,
     *  rounded up, at least 1; Int.MAX_VALUE without science.
     */
    private fun researchTurnsLeft(civ: Civilization, tech: Technology): Int {
        val science = civ.stats.statsForNextTurn.science
        if (science <= 0f) return Int.MAX_VALUE
        return max(1, ceil(civ.tech.remainingScienceToTech(tech.name) / science).toInt())
    }

    /** CvTechAI::ReweightByCost: weight / turns^(AI_RESEARCH_WEIGHT_BASE_MOD + AI_RESEARCH_WEIGHT_MOD_PER_TURN_LEFT × turns) */
    private fun reweightByCost(weight: Int, turnsLeft: Int, baseMod: Double, modPerTurn: Double, needExpensiveTechs: Boolean): Int {
        val factor = turnsLeft.toDouble().pow(baseMod + modPerTurn * turnsLeft)
        return (if (needExpensiveTechs) weight * factor else weight / factor).coerceIn(0.0, MAX_REWEIGHTED_WEIGHT.toDouble()).toInt()
    }
    //endregion
}
