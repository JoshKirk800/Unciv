package com.unciv.ui.screens.leaguescreens

import com.badlogic.gdx.scenes.scene2d.ui.Table
import com.unciv.logic.league.ActiveResolution
import com.unciv.logic.league.LeagueResultEntry
import com.unciv.models.Counter
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.UiTestSupport
import com.unciv.ui.screens.basescreen.BaseScreen
import com.unciv.ui.screens.overviewscreen.EmpireOverviewCategories
import com.unciv.ui.screens.overviewscreen.WorldCongressOverview
import com.unciv.view.GameView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Headless smoke tests of the World Congress screens: what they show and what their actions do to the League */
@RunWith(GdxTestRunner::class)
class LeagueScreensTests {
    private lateinit var f: LeagueUiFixture
    private var screen: BaseScreen? = null

    @Before
    fun setUp() {
        f = LeagueUiFixture()
        UiTestSupport.prepare(f.game)
    }

    @After
    fun tearDown() {
        screen?.dispose()
    }

    private fun texts() = UiTestSupport.labelTexts(screen!!.stage)

    //region Proposal picker
    @Test
    fun `picker lists proposable resolutions and the reason for the others`() {
        LeagueProposalPickerScreen(f.a).also { screen = it }
        val texts = texts()
        assertTrue(texts.toString(), "Enact a resolution" in texts)
        assertTrue("Standing Army Tax" in texts)
        assertTrue("Embargo" in texts)
        assertTrue(texts.toString(), texts.any { it.startsWith("Needs") && it.endsWith("Advanced Ballistics to be known by any civilization") })
        assertFalse("World Leader" in texts)
        assertFalse("Choose Host" in texts)
        assertFalse("Repeal a resolution in effect" in texts)
    }

    @Test
    fun `picking a resolution describes it and proposing puts it on the table`() {
        val picker = LeagueProposalPickerScreen(f.a).also { screen = it }
        picker.selectEnact(f.resolution("Standing Army Tax"), null)
        val description = picker.descriptionLabel.text.toString()
        assertTrue(description, "Standing Army Tax" in description)
        assertTrue(description, "+25% unit gold maintenance" in description)

        picker.propose()
        val proposal = f.league.proposals.single()
        assertEquals("Standing Army Tax", proposal.resolution)
        assertFalse(proposal.repeal)
        assertEquals(f.a.civID, proposal.proposer)
        assertFalse(f.league.canPropose(f.a))
    }

    @Test
    fun `picker offers resolutions in effect for repeal`() {
        f.league.activeResolutions.add(ActiveResolution(1, "Scholars In Residence", f.b.civID, null, 1))
        val picker = LeagueProposalPickerScreen(f.a).also { screen = it }
        val texts = texts()
        assertTrue(texts.toString(), "Repeal a resolution in effect" in texts)
        assertTrue(texts.toString(), "Repeal Scholars In Residence" in texts)
        assertTrue("Already in effect" in texts)

        picker.selectRepeal(f.league.activeResolutions.single())
        picker.propose()
        val proposal = f.league.proposals.single()
        assertTrue(proposal.repeal)
        assertEquals(1, proposal.targetId)
    }

    @Test
    fun `proposing nothing changes nothing`() {
        val picker = LeagueProposalPickerScreen(f.a).also { screen = it }
        picker.confirm()
        assertTrue(f.league.proposals.isEmpty())
        assertTrue(f.league.canPropose(f.a))
    }
    //endregion

    //region Vote screen
    private fun startSession(): Pair<Int, Int> {
        f.league.proposeEnact(f.resolution("Standing Army Tax"), null, f.a)
        f.league.proposeEnact(f.resolution("Scholars In Residence"), null, f.otherProposer)
        f.beginSession()
        val ids = f.league.proposals.map { it.id }
        return ids[0] to ids[1]
    }

    @Test
    fun `vote screen shows delegates and a row per proposal choice`() {
        startSession()
        val total = f.league.getDelegatesLeft(f.a)
        LeagueVoteScreen(f.a).also { screen = it }
        val texts = texts()
        assertTrue(texts.toString(), "Delegates left: $total of $total" in texts)
        assertTrue("Standing Army Tax" in texts)
        assertTrue("Scholars In Residence" in texts)
        assertEquals(2, texts.count { it == "Yes" })
        assertEquals(2, texts.count { it == "No" })
        assertTrue(texts.toString(), "Proposed by ${f.a.civName}" in texts)
    }

    @Test
    fun `counters never exceed the delegates left`() {
        val (first, second) = startSession()
        val total = f.league.getDelegatesLeft(f.a)
        val vote = LeagueVoteScreen(f.a).also { screen = it }
        repeat(total + 3) { vote.addDelegate(first, League_YES) }
        vote.addDelegate(second, League_NO)
        assertEquals(total, vote.getDelegates(first, League_YES))
        assertEquals(0, vote.getDelegates(second, League_NO))
        assertEquals(0, vote.getUnassignedDelegates())
        assertTrue(texts().toString(), "Delegates left: 0 of $total" in texts())

        vote.removeDelegate(first, League_YES)
        vote.addDelegate(second, League_NO)
        assertEquals(total - 1, vote.getDelegates(first, League_YES))
        assertEquals(1, vote.getDelegates(second, League_NO))
    }

    @Test
    fun `spending votes only what is set up and keeps the rest`() {
        val (first, _) = startSession()
        val total = f.league.getDelegatesLeft(f.a)
        val vote = LeagueVoteScreen(f.a).also { screen = it }
        vote.addDelegate(first, League_YES)
        vote.spendSetUpDelegates()

        val proposal = f.league.proposals.first { it.id == first }
        assertEquals(1, f.league.getTally(proposal)[League_YES])
        assertEquals(total - 1, f.league.getDelegatesLeft(f.a))
        assertTrue(f.league.canVote(f.a))
    }

    @Test
    fun `giving up the rest abstains what was not set up`() {
        val (first, _) = startSession()
        val total = f.league.getDelegatesLeft(f.a)
        val vote = LeagueVoteScreen(f.a).also { screen = it }
        vote.addDelegate(first, League_NO)
        vote.giveUpRemainingDelegates()

        assertEquals(0, f.league.getDelegatesLeft(f.a))
        assertEquals(1, f.league.getTally(f.league.proposals.first { it.id == first })[League_NO])
        assertEquals(total - 1, f.league.members.getValue(f.a.civID).abstained)
    }
    //endregion

    //region Overview
    private fun overviewTexts(): List<String> {
        val table = Table(BaseScreen.skin)
        WorldCongressOverview.build(table, f.a)
        return UiTestSupport.labelTexts(table)
    }

    @Test
    fun `overview shows countdown host members and our status`() {
        val texts = overviewTexts()
        assertTrue(texts.toString(), "World Congress" in texts)
        assertTrue(texts.toString(), "Turns until the next World Congress session: ${f.league.turnsUntilSession}" in texts)
        assertTrue("Host: ${f.a.civName}" in texts)
        assertTrue("Proposals you may make: 1" in texts)
        for (civ in listOf(f.a, f.b, f.c)) assertTrue(texts.toString(), civ.civName in texts)
        assertTrue("None" in texts)
        assertFalse("Proposals on the table" in texts)
    }

    @Test
    fun `overview shows proposals active resolutions and last results`() {
        f.league.proposeEnact(f.resolution("Standing Army Tax"), null, f.a)
        f.league.activeResolutions.add(ActiveResolution(5, "Scholars In Residence", f.b.civID, null, 3))
        val tally = Counter<String>()
        tally.add("Yes", 4)
        tally.add("No", 2)
        f.league.lastResults.add(LeagueResultEntry("Embargo", f.c.civID, true, false, tally))
        f.league.lastResultsTurn = 7

        val texts = overviewTexts()
        assertTrue(texts.toString(), "Proposals on the table" in texts)
        assertTrue("Standing Army Tax" in texts)
        assertTrue("Enact" in texts)
        assertTrue(texts.toString(), "Resolutions in effect" in texts && "Scholars In Residence" in texts)
        assertTrue(texts.toString(), "Results of the last session (turn 7)" in texts)
        assertTrue(texts.toString(), "Embargo (${f.c.civName}) has been passed" in texts)
        assertTrue(texts.toString(), "Yes: 4, No: 2" in texts)
    }

    @Test
    fun `overview of a running session shows delegates left`() {
        f.league.proposeEnact(f.resolution("Standing Army Tax"), null, f.a)
        f.league.proposeEnact(f.resolution("Scholars In Residence"), null, f.otherProposer)
        f.beginSession()
        val texts = overviewTexts()
        assertTrue(texts.toString(), "A session is in progress" in texts)
        assertTrue("Delegates you have left: ${f.league.getDelegatesLeft(f.a)}" in texts)
    }

    @Test
    fun `world leader countdown only appears under the United Nations`() {
        assertEquals(
            listOf("Turns until the next World Congress session: [${f.league.turnsUntilSession}]"),
            WorldCongressOverview.getSessionStatusLines(f.league)
        )
        f.league.unitedNations = true
        f.league.lastSpecialSession = "Atomic era"
        assertEquals(
            listOf(
                "Turns until the next World Congress session: [${f.league.turnsUntilSession}]",
                "Turns until the World Leader vote: [${f.league.getTurnsUntilWorldLeaderSession()}]",
                "Delegates needed to be elected World Leader: [${f.league.getVotesNeededForWorldLeader()}]"
            ),
            WorldCongressOverview.getSessionStatusLines(f.league)
        )
    }

    @Test
    fun `overview tab is hidden without a World Congress`() {
        val view = GameView(f.gameInfo, f.a).civView
        assertEquals(EmpireOverviewCategories.EmpireOverviewTabState.Normal, EmpireOverviewCategories.WorldCongress.testState(view))
        f.gameInfo.league = null
        assertEquals(EmpireOverviewCategories.EmpireOverviewTabState.Hidden, EmpireOverviewCategories.WorldCongress.testState(view))
    }
    //endregion

    private companion object {
        const val League_YES = com.unciv.logic.league.League.YES
        const val League_NO = com.unciv.logic.league.League.NO
    }
}
