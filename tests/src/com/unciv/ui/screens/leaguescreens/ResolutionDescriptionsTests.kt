package com.unciv.ui.screens.leaguescreens

import com.unciv.logic.league.ActiveResolution
import com.unciv.logic.league.LeagueResultEntry
import com.unciv.models.Counter
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.UiTestSupport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ResolutionDescriptionsTests {
    private lateinit var f: LeagueUiFixture

    @Before
    fun setUp() {
        f = LeagueUiFixture()
    }

    private fun effects(name: String) = ResolutionDescriptions.getEffectLines(f.resolution(name), f.game.ruleset)
    private fun reason(name: String, civ: com.unciv.logic.civilization.Civilization = f.a) =
        ResolutionDescriptions.getRejectionReason(f.league, f.resolution(name), civ)

    //region Descriptions
    @Test
    fun `effects of typed fields are listed without availability conditions`() {
        assertEquals(
            listOf(
                "[2] more delegates for civilizations that mostly follow the chosen religion",
                "[50]% more tourism from the holy city of the chosen religion",
                "[25]% more spread strength for the chosen religion"
            ),
            effects("World Religion")
        )
    }

    @Test
    fun `effects of uniques are listed as written`() {
        assertEquals(listOf("[+25]% unit gold maintenance"), effects("Standing Army Tax"))
    }

    @Test
    fun `world leader lists the bonus for the leaders but not its availability`() {
        val lines = effects("World Leader")
        assertTrue(lines.toString(), lines.any { it.startsWith("If nobody wins") && "[2]" in it })
        assertTrue(lines.toString(), lines.none { it.startsWith("Only available") })
    }

    @Test
    fun `units a resolution forbids are listed`() {
        val lines = effects("Nuclear Non-Proliferation")
        assertEquals(lines.toString(), 1, lines.size)
        assertTrue(lines.single().startsWith("Units that cannot be built: ["))
        assertTrue(lines.single().contains("Atomic Bomb") && lines.single().contains("Nuclear Missile"))
    }

    @Test
    fun `deciders name who votes on what and what the proposer chooses`() {
        assertEquals(
            listOf("The delegates vote Yes or No", "The proposer chooses another civilization"),
            ResolutionDescriptions.getDeciderLines(f.resolution("Embargo"))
        )
        assertEquals(listOf("The delegates vote for a civilization"), ResolutionDescriptions.getDeciderLines(f.resolution("Choose Host")))
        assertEquals(
            listOf("The delegates vote Yes or No", "Needs [Advanced Ballistics] to be known by any civilization"),
            ResolutionDescriptions.getDeciderLines(f.resolution("Nuclear Non-Proliferation"))
        )
    }
    //endregion

    //region Rejection reasons
    @Test
    fun `a resolution that can be proposed has no rejection reason`() {
        assertNull(reason("Standing Army Tax"))
    }

    @Test
    fun `reasons name the cause`() {
        assertEquals("Needs [Advanced Ballistics] to be known by any civilization", reason("Nuclear Non-Proliferation"))
        assertEquals("The World Congress proposes this itself", reason("World Leader"))
        assertEquals("There are no city-states", reason("Embargo City-States"))
    }

    @Test
    fun `a resolution in effect or on the table is rejected as such`() {
        f.league.activeResolutions.add(ActiveResolution(1, "Scholars In Residence", f.a.civID, null, 1))
        assertEquals("Already in effect", reason("Scholars In Residence"))

        f.league.proposeEnact(f.resolution("Standing Army Tax"), null, f.a)
        assertEquals("Already on the table", reason("Standing Army Tax", f.otherProposer))
    }

    @Test
    fun `a civilization without proposal left is told so`() {
        f.league.proposeEnact(f.resolution("Standing Army Tax"), null, f.a)
        assertEquals("You have no proposal left", reason("Scholars In Residence"))
    }

    @Test
    fun `repeal is rejected once proposed`() {
        f.league.activeResolutions.add(ActiveResolution(1, "Scholars In Residence", f.a.civID, null, 1))
        assertNull(ResolutionDescriptions.getRepealRejectionReason(f.league, 1, f.a))
        f.league.proposeRepeal(1, f.a)
        assertEquals("Already on the table", ResolutionDescriptions.getRepealRejectionReason(f.league, 1, f.otherProposer))
    }
    //endregion

    //region Results
    @Test
    fun `result headlines tell host world leader repeal and plain outcomes`() {
        val gameInfo = f.gameInfo
        fun entry(resolution: String, choice: String?, passed: Boolean, repeal: Boolean = false) =
            LeagueResultEntry(resolution, choice, passed, repeal, Counter())

        assertEquals("[${f.a.civName}] is the new host of the World Congress",
            UiTestSupport.plain(ResolutionDescriptions.getResultHeadline(entry("Choose Host", f.a.civID, true), gameInfo)))
        assertEquals("[${f.b.civName}] has been elected World Leader!",
            UiTestSupport.plain(ResolutionDescriptions.getResultHeadline(entry("World Leader", f.b.civID, true), gameInfo)))
        assertEquals("Nobody has been elected World Leader",
            UiTestSupport.plain(ResolutionDescriptions.getResultHeadline(entry("World Leader", null, false), gameInfo)))
        assertEquals("[Standing Army Tax] has been passed",
            UiTestSupport.plain(ResolutionDescriptions.getResultHeadline(entry("Standing Army Tax", null, true), gameInfo)))
        assertEquals("[Standing Army Tax] has failed",
            UiTestSupport.plain(ResolutionDescriptions.getResultHeadline(entry("Standing Army Tax", null, false), gameInfo)))
        assertEquals("[Embargo (${f.b.civName})] has been repealed",
            UiTestSupport.plain(ResolutionDescriptions.getResultHeadline(entry("Embargo", f.b.civID, true, repeal = true), gameInfo)))
        assertEquals("The repeal of [Standing Army Tax] has failed",
            UiTestSupport.plain(ResolutionDescriptions.getResultHeadline(entry("Standing Army Tax", null, false, repeal = true), gameInfo)))
    }

    @Test
    fun `tally lists the most delegates first and names civilizations`() {
        val tally = Counter<String>()
        tally.add(f.a.civID, 2)
        tally.add(f.b.civID, 4)
        assertEquals("${f.b.civName}: 4, ${f.a.civName}: 2",
            UiTestSupport.plain(ResolutionDescriptions.getTallyText(LeagueResultEntry("World Leader", null, false, false, tally), f.gameInfo)))
    }
    //endregion
}
