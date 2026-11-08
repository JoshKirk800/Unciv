package com.unciv.logic.automation.civilization

import com.unciv.logic.automation.Timers.Companion.timeThis
import com.unciv.logic.automation.strategy.GrandStrategyAi
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.Policy.PolicyBranchType
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.ai.AiDefine
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.models.ruleset.ai.MajorCivApproach
import com.unciv.models.ruleset.unique.GameContext
import com.unciv.models.ruleset.unique.UniqueType
import kotlin.random.Random

/**
 *  The policy AI of the original (CvPolicyAI and the AI parts of CvPlayerPolicies): policy weights from Policy_Flavors × the leader's
 *  personality and grand strategy flavors, the choice of the next policy or branch from one weighted pool, the ideology choice and
 *  the ideology switch. Runs for AI players from [NextTurnAutomation] in the order of CvPlayer::doTurn.
 */
object PolicyAutomation {
    private const val LOSING_MONEY = "ECONOMICAISTRATEGY_LOSING_MONEY"
    private const val POLICY_NUM_OPTIONS = "PolicyNumOptionsConsidered"
    /** HandicapInfos.PolicyNumOptionsConsidered from Prince up, for a ruleset without the handicap table */
    private const val DEFAULT_POLICY_NUM_OPTIONS = 2

    /** The flavor of the active grand strategy that makes a level-3 tenet an immediate pick (CvPolicyAI::ChooseNextPolicy) */
    private val grandStrategyTenetFlavor = mapOf(
        GrandStrategyAi.CONQUEST to Flavor.Offense,
        GrandStrategyAi.SPACESHIP to Flavor.Spaceship,
        GrandStrategyAi.UNITED_NATIONS to Flavor.Diplomacy,
        GrandStrategyAi.CULTURE to Flavor.Culture,
    )

    //region Ruleset tables

    /** The policies of a ruleset in its order (the original's policy IDs), their flavors and their prerequisites as positions */
    private class PolicyTables(val ruleset: Ruleset) {
        val policies: List<Policy> = ruleset.policies.values.toList()
        private val positionOf: Map<String, Int> = policies.withIndex().associate { it.value.name to it.index }
        val flavors: Array<IntArray> = Array(policies.size) { ruleset.ai.flavorsOf(policies[it]) }

        /** CvPolicyEntry::GetPrereqAndPolicies: Unciv lists the opening of the branch as a requirement, the original does not */
        val prereqs: Array<IntArray> = Array(policies.size) { i ->
            val policy = policies[i]
            policy.requires.orEmpty().filter { it != policy.branch.name }.mapNotNull { positionOf[it] }.toIntArray()
        }

        /** The position of [policy], null for a branch member a mod removed from the ruleset's policies */
        fun positionOf(policy: Policy): Int? = positionOf[policy.name]

        fun weightOf(weights: IntArray, policy: Policy): Int = positionOf(policy)?.let { weights[it] } ?: 0
    }

    @Volatile private var tables: PolicyTables? = null

    private fun tablesOf(ruleset: Ruleset): PolicyTables {
        val cached = tables
        if (cached != null && cached.ruleset === ruleset) return cached
        return PolicyTables(ruleset).also { tables = it }
    }
    //endregion

    //region Policy weights (CvPlayerPolicies::FlavorUpdate, AddFlavorAsStrategies; CvPolicyAI::AddFlavorWeights, WeightPrereqs, PropagateWeights)

    /**
     *  CvPlayerPolicies::FlavorUpdate → AddFlavorAsStrategies: rebuilds the weight of every policy. For each flavor, the leader's
     *  personality plus the active grand strategy's flavor mod (CvGrandStrategyAI::GetPersonalityAndGrandStrategy), with +5 Gold while
     *  ECONOMICAISTRATEGY_LOSING_MONEY is active, +5 Happiness while unhappy and +3 Defense and City Defense while at war; each positive
     *  value adds Policy_Flavors × value to each policy and POLICY_WEIGHT_PROPAGATION_PERCENT of that to its prerequisites, recursively.
     *  The original runs this whenever the player's flavors change (a strategy is adopted or dropped), so the situational boosts are those
     *  of that moment: [policyWeights] calls it when [com.unciv.logic.automation.strategy.CivAi] reports changed strategies.
     *  As in the original, nothing is weighted if the ruleset has no ECONOMICAISTRATEGY_LOSING_MONEY.
     */
    fun flavorUpdate(civ: Civilization): IntArray = timeThis("AI.PolicyFlavorUpdate") {
        val rules = civ.gameInfo.ruleset.ai
        val tables = tablesOf(civ.gameInfo.ruleset)
        val count = tables.policies.size
        val weights = IntArray(count)
        if (rules.economicStrategies.any { it.name == LOSING_MONEY }) {
            val propagationPercent = rules.define(AiDefine.POLICY_WEIGHT_PROPAGATION_PERCENT)
            val propagationLevels = rules.define(AiDefine.POLICY_WEIGHT_PROPAGATION_LEVELS)
            val atWar = civ.isAtWar()
            val inDeficit = civ.ai.isEconomicStrategyActive(LOSING_MONEY)
            val unhappy = civ.stats.happiness < 0
            val temp = IntArray(count)
            for (flavor in Flavor.entries) {
                var value = civ.ai.personalityAndGrandStrategy(flavor)
                value += when (flavor) {
                    Flavor.Gold -> if (inDeficit) 5 else 0
                    Flavor.Happiness -> if (unhappy) 5 else 0
                    Flavor.Defense, Flavor.CityDefense -> if (atWar) 3 else 0
                    else -> 0
                }
                if (value <= 0) continue
                for (i in 0 until count) temp[i] = tables.flavors[i][flavor.ordinal] * value
                if (propagationPercent > 0)
                    for (i in 0 until count)
                        if (temp[i] > 0) propagate(tables, weights, i, temp[i], propagationPercent, propagationLevels, 0)
                for (i in 0 until count) weights[i] += temp[i]
            }
        }
        civ.policies.aiPolicyWeights = weights
        civ.policies.aiPolicyWeightsVersion = civ.ai.strategyVersion
        weights
    }

    /** CvPolicyAI::PropagateWeights, with the original's `iPropagationLevel++` (post-increment: the level passed down only grows for later prerequisites) */
    private fun propagate(tables: PolicyTables, weights: IntArray, policy: Int, weight: Int, percent: Int, levels: Int, startLevel: Int) {
        var level = startLevel
        if (level >= levels) return
        val propagated = weight * percent / 100
        for (prereq in tables.prereqs[policy]) {
            weights[prereq] += propagated
            if (propagated > 0) propagate(tables, weights, prereq, propagated, percent, levels, level++)
        }
    }

    /** The policy weights of [civ] (CvPolicyAI::m_PolicyAIWeights), rebuilt by [flavorUpdate] when its strategies changed */
    private fun policyWeights(civ: Civilization): IntArray {
        val cached = civ.policies.aiPolicyWeights
        if (cached != null && civ.policies.aiPolicyWeightsVersion == civ.ai.strategyVersion) return cached
        return flavorUpdate(civ)
    }

    /** The weight of [policy] in [civ]'s policy weights (CvPolicyAI::m_PolicyAIWeights.GetWeight) */
    fun policyWeight(civ: Civilization, policy: Policy): Int = tablesOf(civ.gameInfo.ruleset).weightOf(policyWeights(civ), policy)
    //endregion

    //region Choosing policies (CvPlayerPolicies::DoPolicyAI, CvPolicyAI::ChooseNextPolicy)

    /**
     *  CvPlayerPolicies::DoPolicyAI: the ideology switch check, then policies and branches are adopted while culture pays for the next one or
     *  free policies or free tenets remain.
     */
    fun doPolicyAi(civ: Civilization) = timeThis("AI.PolicyAI") {
        considerIdeologySwitch(civ)
        val policies = civ.policies
        if (policies.getCultureNeededForNextPolicy() <= 0) return@timeThis
        val random = civ.ai.random("PolicyAutomation.chooseNextPolicy")
        while (policies.storedCulture >= policies.getCultureNeededForNextPolicy() || policies.freePolicies > 0 || policies.freeTenets > 0) {
            val next = chooseNextPolicy(civ, random) ?: break
            policies.adopt(next)
        }
    }

    /** The pool of CvPolicyAI::ChooseNextPolicy: the candidates with their weights, and the level-3 tenet taken at once if there is one */
    class PolicyChoices(val candidates: List<Policy>, val weights: List<Int>, val immediate: Policy?)

    /**
     *  CvPolicyAI::ChooseNextPolicy: the policy or branch to adopt next, null if none can be adopted: the [weighChoices] tenet taken at once, else
     *  a weighted pick among the top HandicapInfos.PolicyNumOptionsConsidered of the game's difficulty ([pickFromTop]).
     */
    fun chooseNextPolicy(civ: Civilization, random: Random): Policy? {
        val choices = weighChoices(civ)
        if (choices.immediate != null) return choices.immediate
        if (choices.candidates.isEmpty()) return null
        val numOptions = civ.gameInfo.ruleset.ai.handicapValue(civ.gameInfo.getDifficulty().name, POLICY_NUM_OPTIONS) ?: DEFAULT_POLICY_NUM_OPTIONS
        return choices.candidates[pickFromTop(choices.weights, numOptions, random)]
    }

    /**
     *  The pool of CvPolicyAI::ChooseNextPolicy:
     *  - Every adoptable policy (only tenets while free tenets remain) weighs its weight, plus the weight of the branch's finisher if it completes the branch.
     *  - Every branch that can be opened (none while free tenets remain; not ideologies, which are chosen by [chooseIdeology]) weighs, if it is effective
     *    in this game ([isBranchEffectiveInGame]), [weighBranch] × (100 − POLICY_WEIGHT_PERCENT_DROP_NEW_BRANCH)%, a third of that under the culture
     *    grand strategy; 0 otherwise.
     *  - An adoptable level-3 tenet with a flavor of the active grand strategy (Conquest: Offense, Spaceship: Spaceship, United Nations: Diplomacy,
     *    Culture: Culture) is the one taken at once, the first in the ruleset's order.
     *  Mutually exclusive branches are excluded by the rules themselves (Unciv's "Only available" uniques), as the original skips them.
     */
    fun weighChoices(civ: Civilization): PolicyChoices {
        val ruleset = civ.gameInfo.ruleset
        val tables = tablesOf(ruleset)
        val weights = policyWeights(civ)
        val manager = civ.policies
        val mustChooseTenet = manager.freeTenets > 0
        val tenetFlavor = civ.ai.grandStrategy?.let { grandStrategyTenetFlavor[it] }

        val candidates = ArrayList<Policy>()
        val candidateWeights = ArrayList<Int>()
        var immediate: Policy? = null
        for ((i, policy) in tables.policies.withIndex()) {
            if (policy is PolicyBranch) continue // opening a branch is weighed below; the original's opener policies have no branch, so cannot be adopted directly
            if (mustChooseTenet && !policy.isTenet) continue
            if (!manager.canAdopt(policy)) continue
            var weight = weights[i]
            if (willFinishBranchIfAdopted(civ, policy)) {
                val finisher = policy.branch.policies.lastOrNull { it.policyBranchType == PolicyBranchType.BranchComplete }
                if (finisher != null) weight += tables.weightOf(weights, finisher)
            }
            candidates += policy
            candidateWeights += weight
            if (immediate == null && tenetFlavor != null && policy.tenetLevel == 3 && tables.flavors[i][tenetFlavor.ordinal] > 0) immediate = policy
        }

        if (!mustChooseTenet) {
            val dropPercent = ruleset.ai.define(AiDefine.POLICY_WEIGHT_PERCENT_DROP_NEW_BRANCH)
            val cultureStrategy = civ.ai.grandStrategy == GrandStrategyAi.CULTURE
            for (branch in ruleset.policyBranches.values) {
                if (branch.isIdeology || !manager.canAdopt(branch)) continue
                var weight = 0
                if (isBranchEffectiveInGame(civ, branch)) {
                    weight = weighBranch(civ, branch, weights, tables) * (100 - dropPercent) / 100
                    if (cultureStrategy) weight /= 3
                }
                candidates += branch
                candidateWeights += weight
            }
        }
        return PolicyChoices(candidates, candidateWeights, immediate)
    }

    /**
     *  CvWeightedVector::SortItems then ChooseFromTopChoices: the index of a weighted pick among the [numOptions] highest [weights] (ties in list order),
     *  or, if no weight is positive, ChooseAtRandom: a uniform pick
     */
    fun pickFromTop(weights: List<Int>, numOptions: Int, random: Random): Int {
        val order = weights.indices.sortedByDescending { weights[it] }
        if (weights.sum() <= 0) return order[random.nextInt(order.size)]
        val top = order.take(numOptions.coerceAtLeast(1))
        var roll = random.nextInt(top.sumOf { weights[it] }.coerceAtLeast(1))
        for (index in top) {
            roll -= weights[index]
            if (roll < 0) return index
        }
        return top.last()
    }

    /** CvPlayerPolicies::WillFinishBranchIfAdopted: every other member of the branch (not its opener or finisher) is adopted */
    private fun willFinishBranchIfAdopted(civ: Civilization, policy: Policy): Boolean =
        policy.branch.policies.all { it.name == policy.name || it.policyBranchType == PolicyBranchType.BranchComplete || civ.policies.isAdopted(it.name) }

    /** CvPolicyAI::WeighBranch: the weights of the branch's policies without prerequisites that are not adopted yet, plus its opener's */
    private fun weighBranch(civ: Civilization, branch: PolicyBranch, weights: IntArray, tables: PolicyTables): Int {
        var weight = 0
        for (policy in branch.policies) {
            if (policy.policyBranchType == PolicyBranchType.BranchComplete || civ.policies.isAdopted(policy.name)) continue
            val position = tables.positionOf(policy) ?: continue
            if (tables.prereqs[position].isEmpty()) weight += weights[position]
        }
        return weight + tables.weightOf(weights, branch)
    }

    /**
     *  CvPolicyAI::IsBranchEffectiveInGame: a branch flagged AIDelayNoReligion is worthless without religion, AIDelayNoCityStates in a game
     *  that never had city-states (the flags come from AI.json `policyBranches`). Unciv has no game options without policies or science,
     *  so AIDelayNoCulture and AIDelayNoScience never apply.
     */
    fun isBranchEffectiveInGame(civ: Civilization, branch: PolicyBranch): Boolean {
        val def = civ.gameInfo.ruleset.ai.policyBranchOf(branch.name) ?: return true
        if (def.aiDelayNoReligion && !civ.gameInfo.isReligionEnabled()) return false
        if (def.aiDelayNoCityStates && civ.gameInfo.civilizations.none { it.isCityState }) return false
        return true
    }
    //endregion

    //region Ideology (CvPolicyAI::DoChooseIdeology, DoConsiderIdeologySwitch)

    /** The three ideologies of the original by the post-defines POLICY_BRANCH_FREEDOM, _AUTOCRACY, _ORDER, null if the ruleset lacks one */
    class Ideologies(val freedom: PolicyBranch, val autocracy: PolicyBranch, val order: PolicyBranch)

    fun ideologiesOf(ruleset: Ruleset): Ideologies? {
        fun branch(postDefine: String) = ruleset.ai.postDefines[postDefine]?.let { ruleset.policyBranches[it] }
        return Ideologies(
            branch("POLICY_BRANCH_FREEDOM") ?: return null,
            branch("POLICY_BRANCH_AUTOCRACY") ?: return null,
            branch("POLICY_BRANCH_ORDER") ?: return null
        )
    }

    /** CvGrandStrategyAI::GetConquestPriority, GetUnitedNationsPriority, GetSpaceshipPriority, GetCulturePriority */
    private class VictoryPriorities(civ: Civilization) {
        val conquest = civ.ai.grandStrategyPriority(GrandStrategyAi.CONQUEST)
        val diplomacy = civ.ai.grandStrategyPriority(GrandStrategyAi.UNITED_NATIONS)
        val science = civ.ai.grandStrategyPriority(GrandStrategyAi.SPACESHIP)
        val culture = civ.ai.grandStrategyPriority(GrandStrategyAi.CULTURE)
    }

    /**
     *  The ideology a clear victory preference rules out (IDEOLOGY_PERCENT_CLEAR_VICTORY_PREF more than each other priority, checked in the
     *  order conquest, diplomacy, science): Freedom for conquest, Order for diplomacy, Autocracy for science. Null if none.
     */
    private fun excludedIdeology(conquest: Int, diplomacy: Int, science: Int, culture: Int, clearPercent: Int, ideologies: Ideologies): PolicyBranch? {
        fun beats(own: Int, other: Int) = own > other * (100 + clearPercent) / 100
        return when {
            beats(conquest, diplomacy) && beats(conquest, science) && beats(conquest, culture) -> ideologies.freedom
            beats(diplomacy, conquest) && beats(diplomacy, science) && beats(diplomacy, culture) -> ideologies.order
            beats(science, conquest) && beats(science, diplomacy) && beats(science, culture) -> ideologies.autocracy
            else -> null
        }
    }

    /**
     *  The ideology [civ] chooses (CvPolicyAI::DoChooseIdeology), null if it cannot choose one now or has one. Each of Freedom, Autocracy and Order scores:
     *  - IDEOLOGY_SCORE_GRAND_STRATS split by the grand strategy priorities (at least 0) it suits: Freedom diplomacy + science + culture,
     *    Autocracy diplomacy + conquest + culture, Order science + conquest + culture, each over the sum of the three;
     *  - IDEOLOGY_SCORE_PER_FREE_TENET per free tenet it would give ([numFreeTenets]);
     *  - per valid major with that ideology, by the approach we show it: Hostile IDEOLOGY_SCORE_HOSTILE and Guarded IDEOLOGY_SCORE_GUARDED to
     *    the two other ideologies, Afraid IDEOLOGY_SCORE_AFRAID and Friendly IDEOLOGY_SCORE_FRIENDLY to theirs;
     *  - the happiness its tenets would give our buildings, per tenet that gives building happiness, × IDEOLOGY_SCORE_HAPPINESS;
     *  - (100 − the public opinion unhappiness it would bring now, at least 0) × IDEOLOGY_SCORE_HAPPINESS;
     *  - a random 0-9; then 0 for the one ruled out by a clear victory preference ([excludedIdeology]).
     *  The highest wins, ties in the order Freedom, Autocracy, Order. A ruleset whose ideologies are not these three (no post-defines) gets a uniform pick.
     */
    fun chooseIdeology(civ: Civilization): PolicyBranch? = timeThis("AI.ChooseIdeology") {
        val manager = civ.policies
        if (!manager.canChooseIdeology()) return@timeThis null
        val ruleset = civ.gameInfo.ruleset
        val random = civ.ai.random("PolicyAutomation.chooseIdeology")
        val ideologies = ideologiesOf(ruleset)
        if (ideologies == null) {
            val options = ruleset.policyBranches.values.filter { it.isIdeology && manager.isAdoptable(it) }
            return@timeThis if (options.isEmpty()) null else options[random.nextInt(options.size)]
        }
        val scores = scoreIdeologies(civ, ideologies, random)
        val (freedom, autocracy, order) = scores
        val chosen = when {
            freedom >= autocracy && freedom >= order -> ideologies.freedom
            autocracy >= freedom && autocracy >= order -> ideologies.autocracy
            else -> ideologies.order
        }
        chosen.takeIf { manager.isAdoptable(it) }
    }

    /** The scores of [chooseIdeology] in the order Freedom, Autocracy, Order */
    fun scoreIdeologies(civ: Civilization, ideologies: Ideologies, random: Random): IntArray {
        val rules = civ.gameInfo.ruleset.ai
        val priorities = VictoryPriorities(civ)
        val conquest = maxOf(0, priorities.conquest)
        val diplomacy = maxOf(0, priorities.diplomacy)
        val science = maxOf(0, priorities.science)
        val culture = maxOf(0, priorities.culture)
        val branches = arrayOf(ideologies.freedom, ideologies.autocracy, ideologies.order)
        val scores = IntArray(3)

        val totals = intArrayOf(diplomacy + science + culture, diplomacy + conquest + culture, science + conquest + culture)
        val grandTotal = totals.sum()
        if (grandTotal > 0) {
            val toDivide = rules.define(AiDefine.IDEOLOGY_SCORE_GRAND_STRATS)
            for (i in 0..2) scores[i] = totals[i] * toDivide / grandTotal
        }

        val perFreeTenet = rules.define(AiDefine.IDEOLOGY_SCORE_PER_FREE_TENET)
        for (i in 0..2) scores[i] += numFreeTenets(civ, branches[i]) * perFreeTenet

        val hostile = rules.define(AiDefine.IDEOLOGY_SCORE_HOSTILE)
        val guarded = rules.define(AiDefine.IDEOLOGY_SCORE_GUARDED)
        val afraid = rules.define(AiDefine.IDEOLOGY_SCORE_AFRAID)
        val friendly = rules.define(AiDefine.IDEOLOGY_SCORE_FRIENDLY)
        for (other in civ.gameInfo.civilizations) {
            if (other == civ || !civ.ai.diplomacy.isPlayerValid(other)) continue
            val theirs = branches.indexOf(other.getIdeology())
            if (theirs < 0) continue
            when (civ.ai.diplomacy.approachTowards(other, hideTrueFeelings = true)) {
                MajorCivApproach.Hostile -> for (i in 0..2) if (i != theirs) scores[i] += hostile
                MajorCivApproach.Guarded -> for (i in 0..2) if (i != theirs) scores[i] += guarded
                MajorCivApproach.Afraid -> scores[theirs] += afraid
                MajorCivApproach.Friendly -> scores[theirs] += friendly
                else -> {} // War, Deceptive, Neutral: no change
            }
        }

        val happinessScore = rules.define(AiDefine.IDEOLOGY_SCORE_HAPPINESS)
        for (i in 0..2) {
            val happinessPolicies = numHappinessPolicies(civ, branches[i])
            if (happinessPolicies > 0) scores[i] += branchBuildingHappiness(civ, branches[i]) * happinessScore / happinessPolicies
        }
        for (i in 0..2)
            scores[i] += maxOf(0, 100 - civ.culture.publicOpinion.hypotheticalUnhappiness(branches[i])) * happinessScore
        for (i in 0..2) scores[i] += random.nextInt(10)

        val excluded = excludedIdeology(conquest, diplomacy, science, culture, rules.define(AiDefine.IDEOLOGY_PERCENT_CLEAR_VICTORY_PREF), ideologies)
        for (i in 0..2) if (branches[i] == excluded) scores[i] = 0
        return scores
    }

    /**
     *  PolicyHelpers::GetNumFreePolicies: the free tenets of the first or second civilization to adopt [branch] (AI.json `policyBranches`),
     *  if the branch's era is not before the game's starting era; the adopters are the living major civilizations that have it.
     */
    fun numFreeTenets(civ: Civilization, branch: PolicyBranch): Int {
        val def = civ.gameInfo.ruleset.ai.policyBranchOf(branch.name) ?: return 0
        val gameInfo = civ.gameInfo
        val branchEra = gameInfo.ruleset.eras[branch.era]?.eraNumber ?: return 0
        if (branchEra < gameInfo.getStartingEra().eraNumber) return 0
        val previousAdopters = gameInfo.civilizations.count { it.isMajorCiv && it.isAlive() && it.policies.isAdopted(branch.name) }
        return when (previousAdopters) {
            0 -> def.firstAdopterFreePolicies
            1 -> def.secondAdopterFreePolicies
            else -> 0
        }
    }

    /** The happiness a building-happiness unique of a policy gives to the building named [buildingName] (CvPolicyEntry::GetBuildingClassHappiness) */
    private inline fun forEachBuildingHappiness(policy: Policy, ruleset: Ruleset, action: (buildingName: String, happiness: Int) -> Unit) {
        for (unique in policy.getMatchingUniques(UniqueType.StatsFromObject, GameContext.IgnoreConditionals)) {
            val happiness = unique.stats.happiness.toInt()
            if (happiness == 0) continue
            val buildingName = unique.params[1]
            if (buildingName in ruleset.buildings) action(buildingName, happiness)
        }
    }

    /** CvPolicyAI::GetBranchBuildingHappiness: what the policies of [branch] would add in happiness to the buildings (this civilization's version) in our cities */
    fun branchBuildingHappiness(civ: Civilization, branch: PolicyBranch): Int {
        val ruleset = civ.gameInfo.ruleset
        var total = 0
        for (policy in branch.policies) forEachBuildingHappiness(policy, ruleset) { buildingName, happiness ->
            val building = civ.getEquivalentBuilding(buildingName).name
            total += happiness * civ.cities.count { it.cityConstructions.isBuilt(building) }
        }
        return total
    }

    /** CvPolicyAI::GetNumHappinessPolicies: the policies of [branch] that give happiness to a building, not counting buildings only for conquered cities */
    fun numHappinessPolicies(civ: Civilization, branch: PolicyBranch): Int {
        val ruleset = civ.gameInfo.ruleset
        return branch.policies.count { policy ->
            var counts = false
            forEachBuildingHappiness(policy, ruleset) { buildingName, _ ->
                if (!civ.getEquivalentBuilding(buildingName).hasUnique(UniqueType.RemovesAnnexUnhappiness, GameContext.IgnoreConditionals)) counts = true
            }
            counts
        }
    }

    /**
     *  CvPolicyAI::DoConsiderIdeologySwitch: with excess happiness at most SUPER_UNHAPPY_THRESHOLD and public opinion unhappiness of 10 or more,
     *  switches to the ideology the people prefer if the opinion unhappiness plus the building happiness of that ideology's tenets minus that of
     *  the current one ([branchBuildingHappiness], totals) is 10 or more, unless a clear victory preference rules that ideology out
     *  (the raw grand strategy priorities). Returns whether it switched.
     */
    fun considerIdeologySwitch(civ: Civilization): Boolean {
        val current = civ.getIdeology() ?: return false
        val opinion = civ.culture.publicOpinion
        val rules = civ.gameInfo.ruleset.ai
        if (civ.stats.happiness > rules.define(AiDefine.SUPER_UNHAPPY_THRESHOLD) || opinion.unhappiness < 10) return false
        val preferred = civ.gameInfo.ruleset.policyBranches[opinion.preferredIdeology ?: return false] ?: return false
        val improvement = opinion.unhappiness + branchBuildingHappiness(civ, preferred) - branchBuildingHappiness(civ, current)
        if (improvement < 10) return false
        val ideologies = ideologiesOf(civ.gameInfo.ruleset)
        if (ideologies != null) {
            val p = VictoryPriorities(civ)
            if (preferred == excludedIdeology(p.conquest, p.diplomacy, p.science, p.culture, rules.define(AiDefine.IDEOLOGY_PERCENT_CLEAR_VICTORY_PREF), ideologies))
                return false
        }
        civ.policies.switchIdeology(preferred)
        return true
    }
    //endregion
}
