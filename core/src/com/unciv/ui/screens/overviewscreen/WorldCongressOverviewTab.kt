package com.unciv.ui.screens.overviewscreen

import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.Constants
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.league.*
import com.unciv.ui.components.extensions.addSeparator
import com.unciv.ui.components.extensions.toLabel
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.leaguescreens.ResolutionDescriptions
import com.unciv.view.CivView

/**
 * The World Congress at a glance: when it meets, who is in it with how many delegates, what is in effect and on the table,
 * how the last session ended and how the projects stand. Hidden in games without a World Congress, see [EmpireOverviewCategories.WorldCongress].
 */
class WorldCongressOverviewTab(
    viewingPlayer: CivView,
    overviewScreen: EmpireOverviewScreen
) : EmpireOverviewTab(viewingPlayer, overviewScreen) {

    init {
        defaults().pad(5f)
        WorldCongressOverview.build(this, viewingPlayer.getCiv())
    }
}

/** Fills the World Congress overview, kept apart from the tab so that it needs nothing but a civilization */
object WorldCongressOverview {
    private const val COLUMNS = 4

    /** The lines about the next session, shown here and in the global politics overview */
    fun getSessionStatusLines(league: League): List<String> {
        val lines = ArrayList<String>()
        lines +=
            if (!league.inSession) "Turns until the next World Congress session: [${league.turnsUntilSession}]"
            else if (league.currentSpecialSession != null) "A special session is in progress"
            else "A session is in progress"
        if (league.unitedNations) {
            val untilWorldLeader = league.getTurnsUntilWorldLeaderSession()
            if (untilWorldLeader < NO_WORLD_LEADER_SESSION) {
                lines += "Turns until the World Leader vote: [$untilWorldLeader]"
                lines += "Delegates needed to be elected World Leader: [${league.getVotesNeededForWorldLeader()}]"
            }
        }
        return lines
    }

    /** `League.getTurnsUntilWorldLeaderSession` says 999 for none */
    private const val NO_WORLD_LEADER_SESSION = 999

    fun build(table: Table, civ: Civilization) {
        val league = civ.gameInfo.league ?: return
        table.clear()
        table.add((if (league.unitedNations) "United Nations" else "World Congress").toLabel(fontSize = Constants.headingFontSize))
            .colspan(COLUMNS).padBottom(10f).row()
        for (line in getSessionStatusLines(league))
            table.add(line.toLabel()).colspan(COLUMNS).row()
        val host = civ.gameInfo.getCivilizationOrNull(league.host)
        if (host != null) table.add("Host: [${host.civName}]".toLabel()).colspan(COLUMNS).row()
        addOwnStatus(table, civ, league)
        addMembers(table, civ, league)
        addActiveResolutions(table, civ, league)
        addProposals(table, civ, league)
        addLastResults(table, civ, league)
        addProjects(table, civ, league)
    }

    private fun addHeading(table: Table, text: String) {
        table.add(text.toLabel(fontSize = Constants.headingFontSize)).colspan(COLUMNS).padTop(20f).padBottom(5f).row()
    }

    private fun addHeaders(table: Table, vararg headers: String) {
        for (header in headers) table.add(header.toLabel()).pad(3f)
        table.row()
        table.addSeparator()
    }

    private fun addCiv(table: Table, civ: Civilization) {
        val cell = Table()
        cell.add(ImageGetter.getNationPortrait(civ.nation, 25f)).padRight(5f)
        cell.add(civ.civName.toLabel(hideIcons = true))
        table.add(cell).left()
    }

    /** What the viewing civilization may still do: propose, spend delegates */
    private fun addOwnStatus(table: Table, civ: Civilization, league: League) {
        val member = league.members[civ.civID] ?: return
        if (member.proposalsLeft > 0)
            table.add("Proposals you may make: [${member.proposalsLeft}]".toLabel(Color.GOLD)).colspan(COLUMNS).row()
        if (league.inSession)
            table.add("Delegates you have left: [${member.delegatesLeft}]".toLabel(if (member.delegatesLeft > 0) Color.GOLD else Color.WHITE)).colspan(COLUMNS).row()
        else
            table.add("Delegates you will have in the next session: [${league.getDelegates(civ)}]".toLabel()).colspan(COLUMNS).row()
    }

    private fun addMembers(table: Table, civ: Civilization, league: League) {
        addHeading(table, "Members")
        addHeaders(table, "Civilization", "Delegates", "Proposals left", "Role")
        for ((id, member) in league.members) {
            val other = civ.gameInfo.getCivilizationOrNull(id) ?: continue
            addCiv(table, other)
            val delegates = if (league.inSession) "${member.delegatesLeft} / ${member.delegatesAtStart}" else league.getDelegates(other).toString()
            table.add(delegates.toLabel())
            table.add(member.proposalsLeft.toString().toLabel())
            table.add((if (id == league.host) "Host" else "").toLabel()).row()
        }
    }

    private fun addActiveResolutions(table: Table, civ: Civilization, league: League) {
        addHeading(table, "Resolutions in effect")
        if (league.activeResolutions.isEmpty()) {
            table.add("None".toLabel()).colspan(COLUMNS).row()
            return
        }
        addHeaders(table, "Resolution", "Choice", "Proposed by", "Turn enacted")
        for (active in league.activeResolutions) {
            table.add(active.resolution.toLabel()).left()
            table.add((active.choice?.let { ResolutionDescriptions.getChoiceName(it, civ.gameInfo) } ?: "").toLabel())
            table.add((active.proposer?.let { civ.gameInfo.getCivilizationOrNull(it)?.civName } ?: "").toLabel())
            table.add(active.turnEnacted.toString().toLabel()).row()
        }
    }

    private fun addProposals(table: Table, civ: Civilization, league: League) {
        if (league.proposals.isEmpty()) return
        addHeading(table, "Proposals on the table")
        addHeaders(table, "Resolution", "Choice", "Proposed by", "Status")
        for (proposal in league.proposals) {
            table.add(proposal.resolution.toLabel()).left()
            table.add((proposal.proposerChoice?.let { ResolutionDescriptions.getChoiceName(it, civ.gameInfo) } ?: "").toLabel())
            table.add((proposal.proposer?.let { civ.gameInfo.getCivilizationOrNull(it)?.civName } ?: "World Congress").toLabel())
            val status = when {
                proposal.onHold -> "On hold"
                proposal.repeal -> "Repeal"
                else -> "Enact"
            }
            table.add(status.toLabel()).row()
        }
    }

    private fun addLastResults(table: Table, civ: Civilization, league: League) {
        if (league.lastResults.isEmpty()) return
        addHeading(table, "Results of the last session (turn [${league.lastResultsTurn}])")
        for (entry in league.lastResults) {
            table.add(ResolutionDescriptions.getResultHeadline(entry, civ.gameInfo).toLabel(if (entry.passed) Color.GREEN else Color.RED))
                .colspan(COLUMNS).row()
            val tally = ResolutionDescriptions.getTallyText(entry, civ.gameInfo)
            if (tally.isNotEmpty()) table.add(tally.toLabel(Color.LIGHT_GRAY)).colspan(COLUMNS).row()
        }
    }

    private fun addProjects(table: Table, civ: Civilization, league: League) {
        if (league.projects.isEmpty()) return
        addHeading(table, "Projects")
        addHeaders(table, "Project", "Progress", "Your contribution", "Reward tier")
        for (project in league.projects) {
            table.add(project.name.toLabel()).left()
            val progress = "${league.getProjectProgress(project)} / ${league.getProjectCost(project.name)}"
            table.add((if (project.complete) "Completed" else progress).toLabel(if (project.complete) Color.GREEN else Color.WHITE))
            table.add(project.contributions[civ.civID].toString().toLabel())
            table.add(league.getContributionTier(project, civ).toString().toLabel()).row()
        }
    }
}
