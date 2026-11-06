package com.unciv.logic.automation.civilization

import com.unciv.Constants
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.RelationshipLevel
import com.unciv.models.ruleset.Policy
import com.unciv.models.ruleset.PolicyBranch
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.utils.randomWeighted
import kotlin.random.Random

/**
 * What the AI chooses of the policies that belong to ideologies (CvPolicyAI::DoChooseIdeology and ChooseNextPolicy for tenets).
 * Unciv has no grand strategy AI and no "approach" to other civilizations, so the victory types the civilization prefers and the relations
 * it has take their places, see [chooseIdeology].
 */
object PolicyAutomation {
    // The numbers of AI/GlobalAIDefines.xml IDEOLOGY_SCORE_*
    private const val SCORE_GRAND_STRATEGY = 300
    private const val SCORE_PER_FREE_TENET = 36
    private const val SCORE_HOSTILE = 8
    private const val SCORE_FRIENDLY = 12
    private const val SCORE_HAPPINESS = 3

    // The ideologies of Brave New World by name, which is what the grand strategies are about. Other ideologies are chosen at random
    private const val FREEDOM = "Freedom"
    private const val ORDER = "Order"
    private const val AUTOCRACY = "Autocracy"

    private const val CONQUEST = "Domination"
    private const val DIPLOMACY = "Diplomatic"
    private const val SCIENCE = "Scientific"
    private const val CULTURE = "Cultural"

    /** The ideology that [civ] chooses, if one can be chosen now and it has not got one. */
    fun chooseIdeology(civ: Civilization, random: Random): PolicyBranch? {
        val policies = civ.policies
        if (!policies.canChooseIdeology()) return null
        val options = civ.gameInfo.ruleset.policyBranches.values.filter { it.isIdeology && policies.isAdoptable(it) }
        if (options.isEmpty()) return null

        val scores = options.associateWith { 0f }.toMutableMap()
        val known = options.map { it.name }.toSet()
        if (known.containsAll(listOf(FREEDOM, ORDER, AUTOCRACY))) scoreBrave(civ, scores, random)
        else for (branch in options) scores[branch] = random.nextFloat()

        val best = scores.maxOf { it.value }
        return options.first { scores[it] == best } // in the order of the ruleset, which for Brave New World is the order of the original
    }

    /** What the civilization wants to win by, in the order of conquest, diplomacy, science, culture */
    private fun getWants(civ: Civilization): List<Float> {
        val preferred = civ.getPreferredVictoryTypes()
        fun want(type: String) = if (type in preferred || Constants.neutralVictoryType in preferred) 100f else 0f
        return listOf(want(CONQUEST), want(DIPLOMACY), want(SCIENCE), want(CULTURE))
    }

    /** A clear preference for one way to win (a lead of more than a quarter, IDEOLOGY_PERCENT_CLEAR_VICTORY_PREF) rules out the ideology that does not suit it:
     *  Freedom for conquest, Order for diplomacy, Autocracy for science (CvPolicyAI::DoChooseIdeology, DoConsiderIdeologySwitch) */
    private fun getExcludedIdeologies(civ: Civilization): Set<String> {
        val wants = getWants(civ)
        val (conquest, diplomacy, science) = wants
        val culture = wants[3]
        val top = maxOf(conquest, diplomacy, science, culture)
        fun clearlyLeads(own: Float, others: List<Float>) = top > 0f && own == top && own > others.max() * 125 / 100
        val excluded = HashSet<String>()
        if (clearlyLeads(conquest, listOf(diplomacy, science, culture))) excluded += FREEDOM
        if (clearlyLeads(diplomacy, listOf(conquest, science, culture))) excluded += ORDER
        if (clearlyLeads(science, listOf(conquest, diplomacy, culture))) excluded += AUTOCRACY
        return excluded
    }

    // The thresholds of CvPolicyAI::DoConsiderIdeologySwitch
    private const val SWITCH_HAPPINESS = -20
    private const val SWITCH_OPINION_UNHAPPINESS = 10
    private const val SWITCH_IMPROVEMENT = 10

    /** CvPolicyAI::DoConsiderIdeologySwitch: changes the ideology to the one the people prefer if the empire is very unhappy, the public opinion is the cause of much of that,
     *  and the change would improve it enough (the happiness the buildings give under the tenets of the two counts too). */
    fun considerIdeologySwitch(civ: Civilization): Boolean {
        val own = civ.getIdeology() ?: return false
        if (civ.hasFlag(CivFlags.Anarchy.name)) return false
        val opinion = civ.culture.publicOpinion
        if (civ.stats.happiness > SWITCH_HAPPINESS || opinion.unhappiness < SWITCH_OPINION_UNHAPPINESS) return false
        val preferred = civ.gameInfo.ruleset.policyBranches[opinion.preferredIdeology ?: return false] ?: return false
        if (preferred.name in getExcludedIdeologies(civ)) return false
        val improvement = opinion.unhappiness + getBuildingHappinessPerTenet(civ, preferred) - getBuildingHappinessPerTenet(civ, own)
        if (improvement < SWITCH_IMPROVEMENT) return false
        civ.policies.switchIdeology(preferred)
        return true
    }

    private fun scoreBrave(civ: Civilization, scores: MutableMap<PolicyBranch, Float>, random: Random) {
        fun branch(name: String) = scores.keys.first { it.name == name }

        val wants = getWants(civ)
        val (conquest, diplomacy, science) = wants
        val culture = wants[3]
        val excluded = getExcludedIdeologies(civ)

        val grand = conquest + diplomacy + science + culture
        if (grand > 0f) {
            scores[branch(FREEDOM)] = (diplomacy + science + culture) * SCORE_GRAND_STRATEGY / grand
            scores[branch(AUTOCRACY)] = (diplomacy + conquest + culture) * SCORE_GRAND_STRATEGY / grand
            scores[branch(ORDER)] = (science + conquest + culture) * SCORE_GRAND_STRATEGY / grand
        }

        // Being the first or the second to choose brings free tenets
        val adopters = civ.gameInfo.civilizations.filter { it.isMajorCiv && !it.isDefeated() && it != civ }
        for (b in scores.keys) {
            val before = adopters.count { it.policies.isAdopted(b.name) }
            val freeTenets = when (before) { 0 -> 2; 1 -> 1; else -> 0 }
            scores[b] = scores[b]!! + freeTenets * SCORE_PER_FREE_TENET
        }

        // Hostile civilizations make their ideologies ones to avoid, friends make them ones to follow
        for (other in adopters) {
            val theirs = other.getIdeology()?.takeIf { it in scores.keys } ?: continue
            val diplomacyManager = civ.getDiplomacyManager(other) ?: continue
            when {
                civ.isAtWarWith(other) || diplomacyManager.hasFlag(DiplomacyFlags.Denunciation) ->
                    for (b in scores.keys) if (b != theirs) scores[b] = scores[b]!! + SCORE_HOSTILE
                diplomacyManager.isRelationshipLevelGE(RelationshipLevel.Friend) -> scores[theirs] = scores[theirs]!! + SCORE_FRIENDLY
            }
        }

        for (b in scores.keys) {
            scores[b] = scores[b]!! + getBuildingHappinessPerTenet(civ, b) * SCORE_HAPPINESS + random.nextInt(10)
            if (b.name in excluded) scores[b] = 0f
        }
        // A tie goes to Freedom, then Autocracy, then Order: put the others behind
        val order = listOf(FREEDOM, AUTOCRACY, ORDER)
        for (b in scores.keys) scores[b] = scores[b]!! * 1000 - order.indexOf(b.name)
    }

    /** What the buildings the civilization has are worth in happiness under the tenets of [branch], for each tenet that is about buildings (CvPolicyAI::GetBranchBuildingHappiness) */
    private fun getBuildingHappinessPerTenet(civ: Civilization, branch: PolicyBranch): Float {
        var total = 0f
        var tenetsAboutBuildings = 0
        for (tenet in branch.policies.filter { it.isTenet }) {
            var counts = false
            for (unique in tenet.getMatchingUniques(UniqueType.StatsFromObject)) {
                if (unique.stats.happiness <= 0f) continue
                val buildingName = unique.params[1]
                if (buildingName !in civ.gameInfo.ruleset.buildings) continue
                counts = true
                total += unique.stats.happiness * civ.cities.count { it.cityConstructions.isBuilt(buildingName) }
            }
            if (counts) tenetsAboutBuildings++
        }
        return if (tenetsAboutBuildings == 0) 0f else total / tenetsAboutBuildings
    }

    /**
     * The tenet to adopt: one of the third level if there is one (they are the ones that decide the course of the game, and the original takes
     * one at once if it suits its grand strategy), else one of those that can be adopted, by the weights the ruleset gives them.
     */
    fun chooseTenet(civ: Civilization, random: Random): Policy? {
        val policies = civ.policies
        val ideology = policies.getIdeology() ?: return null
        val adoptable = ideology.policies.filter { it.isTenet && policies.canAdopt(it) }
        if (adoptable.isEmpty()) return null
        val topLevel = adoptable.maxOf { it.tenetLevel }
        return adoptable.filter { it.tenetLevel == topLevel }.randomWeighted(random) { it.getWeightForAiDecision(civ.state) }
    }
}
