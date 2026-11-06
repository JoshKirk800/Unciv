package com.unciv.ui.screens.leaguescreens

import com.unciv.logic.GameInfo
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.league.League
import com.unciv.logic.league.LeagueResultEntry
import com.unciv.models.ruleset.Resolution
import com.unciv.models.ruleset.ResolutionDecision
import com.unciv.models.ruleset.Ruleset
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.translations.tr

/**
 * The texts that describe resolutions of the World Congress and the outcome of its sessions.
 * All of them are translation keys with bracketed parameters, to be translated by `tr()`/`toLabel()` where they are shown.
 */
object ResolutionDescriptions {

    /** What a choice of a proposer or voter is called: the name of the civilization for a civilization id, else the choice itself (luxury, religion, ideology, Yes, No) */
    fun getChoiceName(choice: String, gameInfo: GameInfo): String =
        gameInfo.getCivilizationOrNull(choice)?.civName ?: choice

    /** Who decides, what the proposer has to choose, which technology is needed: one line each, in this order, empty ones left out */
    fun getDeciderLines(resolution: Resolution): List<String> {
        val lines = ArrayList<String>()
        lines += when (resolution.voterDecision) {
            ResolutionDecision.MajorCiv -> "The delegates vote for a civilization"
            else -> "The delegates vote Yes or No"
        }
        val proposerLine = when (resolution.proposerDecision) {
            ResolutionDecision.OtherMajorCiv -> "The proposer chooses another civilization"
            ResolutionDecision.MajorCiv -> "The proposer chooses a civilization"
            ResolutionDecision.Luxury -> "The proposer chooses a luxury resource"
            ResolutionDecision.Religion -> "The proposer chooses a religion"
            ResolutionDecision.Ideology -> "The proposer chooses an ideology"
            ResolutionDecision.None, ResolutionDecision.YesNo -> null
        }
        if (proposerLine != null) lines += proposerLine
        resolution.requiredTechAnyMember?.let { lines += "Needs [$it] to be known by any civilization" }
        return lines
    }

    /**
     * What the resolution does while in effect: the effects its uniques describe (the roles and availability conditions are no effects),
     * those of its typed fields, and the units it forbids to build.
     */
    fun getEffectLines(resolution: Resolution, ruleset: Ruleset): List<String> {
        val lines = ArrayList<String>()
        if (resolution.isProject)
            lines += "The project costs [${resolution.projectCostPerPlayer}] production for each civilization at Standard game speed"
        val tierRewards = ArrayList<Pair<Int, String>>()
        for (unique in resolution.uniqueObjects) {
            if (unique.isHiddenToUsers() || unique.type == UniqueType.OnlyAvailable || unique.type == UniqueType.Unavailable) continue
            val tierModifier = unique.getModifiers(UniqueType.TriggerUponProjectTier).firstOrNull()
            if (tierModifier == null) lines += unique.getDisplayText()
            else tierRewards += tierModifier.params[0].toInt() to unique.getDisplayText().replace(" <${tierModifier.text}>", "")
        }
        for ((tier, reward) in tierRewards.sortedBy { it.first })
            lines += "${"Tier".tr()} $tier: ${reward.tr()}"
        if (resolution.isHostElection) lines += "The civilization with the most delegates becomes the host"
        if (resolution.leadersVoteBonusOnFail > 0)
            lines += "If nobody wins, the civilizations with the most delegates get [${resolution.leadersVoteBonusOnFail}] more delegates for good"
        if (resolution.embargoesCityStates) lines += "Trade routes to city-states are forbidden"
        if (resolution.embargoesPlayer) lines += "Trade routes with the chosen civilization are forbidden"
        if (resolution.bansLuxuryHappiness) lines += "The chosen luxury gives no happiness and cannot be traded"
        if (resolution.votesForFollowingReligion != 0)
            lines += "[${resolution.votesForFollowingReligion}] more delegates for civilizations that mostly follow the chosen religion"
        if (resolution.votesForFollowingIdeology != 0)
            lines += "[${resolution.votesForFollowingIdeology}] more delegates for civilizations that follow the chosen ideology"
        if (resolution.holyCityTourismPercent != 0)
            lines += "[${resolution.holyCityTourismPercent}]% more tourism from the holy city of the chosen religion"
        if (resolution.religionSpreadStrengthPercent != 0)
            lines += "[${resolution.religionSpreadStrengthPercent}]% more spread strength for the chosen religion"
        if (resolution.ideologyPressure != 0)
            lines += "The chosen ideology presses [${resolution.ideologyPressure}] more on every civilization"
        val forbidden = ruleset.units.values.filter { unit ->
            unit.uniqueObjects.any { unique ->
                unique.type == UniqueType.Unbuildable && unique.getModifiers(UniqueType.ConditionalResolutionInEffect).any { it.params[0] == resolution.name }
            }
        }
        if (forbidden.isNotEmpty()) lines += "Units that cannot be built: [${forbidden.joinToString { it.name }}]"
        return lines
    }

    /** [getDeciderLines] and [getEffectLines], one per line */
    fun getDescription(resolution: Resolution, ruleset: Ruleset): List<String> =
        getDeciderLines(resolution) + getEffectLines(resolution, ruleset)

    /**
     * Why [civ] cannot propose [resolution] now, `null` if it can with at least one choice.
     * Mirrors the checks of [League.canProposeEnact] in the order that is the most useful to the player.
     */
    fun getRejectionReason(league: League, resolution: Resolution, civ: Civilization): String? {
        val choices: List<String?> =
            if (resolution.proposerDecision == ResolutionDecision.None) listOf(null) else league.getChoices(resolution, civ)
        if (choices.any { league.canProposeEnact(resolution, it, civ) }) return null

        val gameInfo = league.gameInfo
        if (!resolution.playerProposable) return "The World Congress proposes this itself"
        if (!league.canPropose(civ)) return "You have no proposal left"
        if (!resolution.isAvailableInGame(gameInfo)) return "This resolution is not available in this game"
        val tech = resolution.requiredTechAnyMember
        if (tech != null && gameInfo.civilizations.none { it.isAlive() && it.tech.isResearched(tech) })
            return "Needs [$tech] to be known by any civilization"
        if (resolution.embargoesCityStates && gameInfo.civilizations.none { it.isCityState }) return "There are no city-states"
        if (choices.isEmpty()) return "There is nothing to choose from"

        fun inEffect(choice: String?) = league.activeResolutions.any { it.resolution == resolution.name && (resolution.exclusive || it.choice == choice) }
        fun proposed(choice: String?) = league.proposals.any { !it.repeal && it.resolution == resolution.name && (resolution.exclusive || it.proposerChoice == choice) }
        return when {
            choices.all { inEffect(it) } -> "Already in effect"
            choices.all { proposed(it) } -> "Already on the table"
            choices.all { inEffect(it) || proposed(it) } -> "Already in effect or on the table"
            else -> "Cannot be proposed now"
        }
    }

    /** Why [civ] cannot propose to repeal the active resolution [activeResolutionId], `null` if it can */
    fun getRepealRejectionReason(league: League, activeResolutionId: Int, civ: Civilization): String? = when {
        league.canProposeRepeal(activeResolutionId, civ) -> null
        !league.canPropose(civ) -> "You have no proposal left"
        league.proposals.any { it.repeal && it.targetId == activeResolutionId } -> "Already on the table"
        else -> "Cannot be proposed now"
    }

    /** "Embargo (France)": the resolution with what the proposer chose, if there is a choice. Translated, as the result is mostly used as a parameter */
    fun getTitle(resolutionName: String, choice: String?, gameInfo: GameInfo): String =
        if (choice == null) resolutionName.tr() else "${resolutionName.tr()} (${getChoiceName(choice, gameInfo).tr()})"

    /** The outcome of one proposal of a finished session as a sentence */
    fun getResultHeadline(entry: LeagueResultEntry, gameInfo: GameInfo): String {
        val resolution = gameInfo.ruleset.resolutions[entry.resolution]
        val choiceName = entry.choice?.let { getChoiceName(it, gameInfo) }
        val title = getTitle(entry.resolution, entry.choice, gameInfo)
        return when {
            entry.repeal -> if (entry.passed) "[$title] has been repealed" else "The repeal of [$title] has failed"
            resolution?.isWorldLeader == true ->
                if (entry.passed && choiceName != null) "[$choiceName] has been elected World Leader!" else "Nobody has been elected World Leader"
            resolution?.isHostElection == true ->
                if (entry.passed && choiceName != null) "[$choiceName] is the new host of the World Congress" else "No host has been elected"
            else -> if (entry.passed) "[$title] has been passed" else "[$title] has failed"
        }
    }

    /** The delegates per choice of a finished vote, most first: "Yes: 5, No: 3" (the choices are translated) */
    fun getTallyText(entry: LeagueResultEntry, gameInfo: GameInfo): String =
        entry.tally.entries.sortedByDescending { it.value }
            .joinToString { "${getChoiceName(it.key, gameInfo).tr()}: ${it.value}" }
}
