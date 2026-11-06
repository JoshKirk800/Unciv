package com.unciv.logic.league

import com.unciv.logic.IsPartOfGameInfoSerialization
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.NotificationCategory
import com.unciv.logic.civilization.NotificationIcon
import com.unciv.models.Counter
import com.unciv.models.ruleset.Resolution
import com.unciv.models.ruleset.unique.UniqueTriggerActivation
import com.unciv.models.ruleset.unique.UniqueType
import yairm210.purity.annotations.Readonly
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A League project of the World Congress (`CvLeague::Project`, CvVotingClasses.h), started when a resolution with [Resolution.projectCostPerPlayer] is enacted.
 * There is one per project resolution for the whole game: running until the civilizations have put its cost together, then [complete].
 */
class LeagueProject : IsPartOfGameInfoSerialization {
    /** Name of the resolution that started it, which is also the name of the city construction that contributes to it */
    var name = ""
    var complete = false
    /** Civ id to the production that civilization has put into the project (`vProductionList`) */
    val contributions = Counter<String>()
    /** The notification that the project has come a part of the way was sent (`bProgressWarningSent`) */
    var warned = false

    fun clone(): LeagueProject {
        val toReturn = LeagueProject()
        toReturn.name = name
        toReturn.complete = complete
        toReturn.contributions.add(contributions)
        toReturn.warned = warned
        return toReturn
    }
}

// The rules of the projects, CvVotingClasses.cpp:3583-3808 and 6008-6232. Contributions are whole production points, the original keeps hundredths.

@Readonly
fun League.getProject(name: String): LeagueProject? = projects.firstOrNull { it.name == name }

/** The project has been started and is not yet complete (`IsProjectActive`) */
@Readonly
fun League.isProjectActive(name: String): Boolean = getProject(name)?.complete == false

/**
 * What each civilization has to put into [name] (`GetProjectCostPerPlayer`, CvVotingClasses.cpp:3614-3630): the cost of the resolution
 * times the construction percent of the game speed and of the starting era, each applied to hundredths and truncated. 0 for a resolution that is no project.
 */
@Readonly
fun League.getProjectCostPerPlayer(name: String): Int {
    val resolution = gameInfo.ruleset.resolutions[name] ?: return 0
    val speedPercent = (gameInfo.speed.productionCostModifier * 100).roundToInt()
    var cost = resolution.projectCostPerPlayer.toLong() * 100
    cost = cost * speedPercent / 100
    cost = cost * gameInfo.getStartingEra().constructPercent / 100
    return (cost / 100).toInt()
}

/** The cost of the whole project: the share of a civilization times the major civilizations there ever were (`GetProjectCost`, CvVotingClasses.cpp:3657-3667) */
@Readonly
fun League.getProjectCost(name: String): Int =
    getProjectCostPerPlayer(name) * gameInfo.civilizations.count { it.isMajorCiv }

/** What all civilizations have put into [project] together (`GetProjectProgress`, CvVotingClasses.cpp:3669-3690) */
@Readonly
fun League.getProjectProgress(project: LeagueProject): Int = project.contributions.values.sum()

/** Whether a major member can put production into [name], which is running (`CanMemberContribute`, CvVotingClasses.cpp:3692-3705) */
@Readonly
fun League.canContribute(civ: Civilization, name: String): Boolean =
    civ.isMajorCiv && isMember(civ) && isProjectActive(name)

/**
 * The contribution that reaches [tier] 1 to 3 of [project] (`GetContributionTierThreshold`, CvVotingClasses.cpp:3771-3808).
 * Tiers 1 and 2 are fractions of the share of a civilization (see `leagueProjectTier1Threshold` of the constants);
 * for tier 3 the best contribution of a living member, but not less than tier 2. 0 for another tier.
 */
@Readonly
fun League.getTierThreshold(project: LeagueProject, tier: Int): Int {
    val constants = gameInfo.ruleset.modOptions.constants
    val perPlayer = getProjectCostPerPlayer(project.name)
    return when (tier) {
        1 -> ceil((constants.leagueProjectTier1Threshold * perPlayer).toDouble()).toInt()
        2 -> ceil((constants.leagueProjectTier2Threshold * perPlayer).toDouble()).toInt()
        3 -> max(members.keys.maxOfOrNull { project.contributions[it] } ?: 0, getTierThreshold(project, 2))
        else -> 0
    }
}

/**
 * The reward tier 0 to 3 that [civ] has earned in [project] (`GetMemberContributionTier`, CvVotingClasses.cpp:3751-3768).
 * Only one civilization gets tier 3, if several reach it the first of the members does and the others have tier 2 (CvVotingClasses.cpp:6079-6097).
 */
@Readonly
fun League.getContributionTier(project: LeagueProject, civ: Civilization): Int {
    val contribution = project.contributions[civ.civID]
    val top = getTierThreshold(project, 3)
    return when {
        contribution >= top -> {
            val first = members.keys.firstOrNull { project.contributions[it] >= top }
            if (first == null || first == civ.civID) 3 else 2
        }
        contribution >= getTierThreshold(project, 2) -> 2
        contribution >= getTierThreshold(project, 1) -> 1
        else -> 0
    }
}

/** Adds [amount] production of a city of [civ] to its contribution to [name], if it may contribute (`DoLeagueProjectContribution`, CvVotingClasses.cpp:7010-7022) */
fun League.addContribution(civ: Civilization, name: String, amount: Int) {
    if (amount <= 0 || !canContribute(civ, name)) return
    getProject(name)?.contributions?.add(civ.civID, amount)
}

/** Enacting a project resolution starts the project, the resolution itself stays in no list (`StartProject`, CvVotingClasses.cpp:6008-6017) */
fun League.startProject(resolution: Resolution) {
    if (getProject(resolution.name) != null) return
    projects.add(LeagueProject().also { it.name = resolution.name })
    for (civ in gameInfo.getAliveMajorCivs())
        civ.addNotification("The World Congress has started the [${resolution.name}] project, put production into it to contribute",
            NotificationCategory.Diplomacy, NotificationIcon.Diplomacy)
}

/** Completes the running projects that have their cost together and tells of those that are a part of the way (`CheckProjectsProgress`, CvVotingClasses.cpp:6019-6070) */
fun League.checkProjects() {
    val warningPercent = gameInfo.ruleset.modOptions.constants.leagueProjectProgressWarningPercent
    for (project in projects.filter { !it.complete }) {
        val needed = getProjectCost(project.name)
        if (needed <= 0) continue
        val total = getProjectProgress(project)
        if (total >= needed) {
            completeProject(project)
        } else if (!project.warned) {
            val percent = min(100L, total * 100L / needed).toInt()
            if (percent < warningPercent) continue
            project.warned = true
            for (civ in gameInfo.getAliveMajorCivs())
                civ.addNotification("The [${project.name}] project of the World Congress has reached [$percent]% of its cost",
                    NotificationCategory.Diplomacy, NotificationIcon.Diplomacy)
        }
    }
}

/** Gives every living major member the rewards of its tier, cumulative (`DoProjectRewards`, CvVotingClasses.cpp:6073-6102) */
private fun League.completeProject(project: LeagueProject) {
    val resolution = gameInfo.ruleset.resolutions[project.name]
    val tiers = members.keys.mapNotNull { gameInfo.getCivilizationOrNull(it) }
        .filter { it.isAlive() }
        .associateWith { getContributionTier(project, it) }
    project.complete = true
    for ((civ, tier) in tiers) {
        if (resolution != null) grantProjectRewards(civ, resolution, tier)
        civ.addNotification(
            if (tier > 0) "The [${project.name}] project of the World Congress is complete, you earned reward tier [$tier]!"
            else "The [${project.name}] project of the World Congress is complete, but you contributed too little for a reward",
            NotificationCategory.Diplomacy, NotificationIcon.Diplomacy)
    }
}

/**
 * Triggers the uniques of [resolution] with the conditional [UniqueType.TriggerUponProjectTier] of [tier] and of every lower tier for [civ]
 * (`DoProjectReward`, CvVotingClasses.cpp:6105-6232, where the tiers fall through the same way).
 */
private fun League.grantProjectRewards(civ: Civilization, resolution: Resolution, tier: Int) {
    for (reached in tier downTo 1)
        for (unique in resolution.uniqueObjects) {
            val tierConditional = unique.getModifiers(UniqueType.TriggerUponProjectTier).firstOrNull() ?: continue
            if (tierConditional.params[0].toIntOrNull() != reached) continue
            if (!unique.isTriggerable || !unique.conditionalsApply(civ.state)) continue
            UniqueTriggerActivation.triggerUnique(unique, civ, triggerNotificationText = "due to completing the [${resolution.name}] project")
        }
    if (tier > 0) {
        civ.cache.updateCivResources()
        for (city in civ.cities) city.cityStats.update()
        civ.updateStatsForNextTurn()
    }
}
