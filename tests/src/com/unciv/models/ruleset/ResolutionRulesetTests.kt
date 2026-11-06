package com.unciv.models.ruleset

import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The World Congress data of Brave New World (Resolutions.json, the era sessions, the removed United Nations wonder) hangs together */
@RunWith(GdxTestRunner::class)
class ResolutionRulesetTests {
    private val bnw = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
    private val gnk = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!
    private val vanilla = RulesetCache[BaseRuleset.Civ_V_Vanilla.fullName]!!

    @Test
    fun `Brave New World has the eighteen resolutions including the projects`() {
        assertEquals(
            listOf("World Leader", "Choose Host", "Embargo City-States", "Embargo", "Ban Luxury", "Standing Army Tax", "Scholars In Residence",
                "Cultural Heritage Sites", "Natural Heritage Sites", "Nuclear Non-Proliferation", "World Religion", "World Ideology",
                "Arts Funding", "Sciences Funding", "Historical Landmarks", "World's Fair", "International Games", "International Space Station"),
            bnw.resolutions.keys.toList()
        )
        assertEquals(listOf("World's Fair", "International Games", "International Space Station"), bnw.resolutions.values.filter { it.isProject }.map { it.name })
        assertEquals(listOf(350, 720, 850), bnw.resolutions.values.filter { it.isProject }.map { it.projectCostPerPlayer })
    }

    @Test
    fun `decisions and typed fields are those of the original`() {
        val r = bnw.resolutions
        for (name in listOf("World Leader", "Choose Host")) {
            assertEquals(ResolutionDecision.MajorCiv, r[name]!!.voterDecision)
            assertFalse(r[name]!!.playerProposable)
        }
        assertTrue(r["World Leader"]!!.isWorldLeader && !r["World Leader"]!!.isHostElection)
        assertTrue(r["Choose Host"]!!.isHostElection && !r["Choose Host"]!!.isWorldLeader)
        assertEquals(2, r["World Leader"]!!.leadersVoteBonusOnFail)
        assertEquals(r.values.map { it.name }.toSet() - setOf("World Leader", "Choose Host"),
            r.values.filter { it.voterDecision == ResolutionDecision.YesNo }.map { it.name }.toSet())
        assertTrue(r.values.filter { it.name != "World Leader" && it.name != "Choose Host" }.all { it.playerProposable })

        assertEquals(ResolutionDecision.OtherMajorCiv, r["Embargo"]!!.proposerDecision)
        assertTrue(r["Embargo"]!!.embargoesPlayer)
        assertTrue(r["Embargo City-States"]!!.embargoesCityStates)
        assertEquals(ResolutionDecision.Luxury, r["Ban Luxury"]!!.proposerDecision)
        assertTrue(r["Ban Luxury"]!!.bansLuxuryHappiness)

        val religion = r["World Religion"]!!
        assertEquals(ResolutionDecision.Religion, religion.proposerDecision)
        assertTrue(religion.exclusive)
        assertEquals(listOf(2, 50, 25), listOf(religion.votesForFollowingReligion, religion.holyCityTourismPercent, religion.religionSpreadStrengthPercent))
        val ideology = r["World Ideology"]!!
        assertEquals(ResolutionDecision.Ideology, ideology.proposerDecision)
        assertTrue(ideology.exclusive)
        assertEquals(listOf(2, 2), listOf(ideology.votesForFollowingIdeology, ideology.ideologyPressure))

        assertEquals("Advanced Ballistics", r["Nuclear Non-Proliferation"]!!.requiredTechAnyMember)
        assertEquals("Archaeology", r["Historical Landmarks"]!!.requiredTechAnyMember)
        assertEquals("Radio", r["World Ideology"]!!.requiredTechAnyMember)
        assertTrue(bnw.technologies.keys.containsAll(r.values.mapNotNull { it.requiredTechAnyMember }))
    }

    @Test
    fun `Arts and Sciences Funding are mirror images`() {
        fun rates(name: String) = bnw.resolutions[name]!!.getMatchingUniques(UniqueType.GreatPersonEarnedFaster).associate { it.params[0] to it.params[1] }
        val arts = rates("Arts Funding")
        val sciences = rates("Sciences Funding")
        assertEquals(setOf("Great Artist", "Great Writer", "Great Musician", "Great Scientist", "Great Engineer", "Great Merchant"), arts.keys)
        assertEquals(arts.keys, sciences.keys)
        for (person in arts.keys) assertEquals(-arts[person]!!.toInt(), sciences[person]!!.toInt())
        assertEquals(33, arts["Great Artist"]!!.toInt())
        assertEquals(-33, arts["Great Scientist"]!!.toInt())
    }

    @Test
    fun `the four sessions are on the eras Renaissance to Atomic`() {
        fun session(era: String) = bnw.eras[era]!!.getWorldCongressSession()
        assertNull(session("Medieval era"))
        assertEquals(listOf(30, 1, 1, 0), session("Renaissance era")!!.let { listOf(it.turnsBetween, it.civDelegates, it.hostDelegates, it.cityStateDelegates) })
        assertEquals(listOf(25, 2, 1, 1), session("Industrial era")!!.let { listOf(it.turnsBetween, it.civDelegates, it.hostDelegates, it.cityStateDelegates) })
        assertEquals(listOf(20, 3, 2, 1), session("Modern era")!!.let { listOf(it.turnsBetween, it.civDelegates, it.hostDelegates, it.cityStateDelegates) })
        assertEquals(listOf(10, 4, 2, 2), session("Atomic era")!!.let { listOf(it.turnsBetween, it.civDelegates, it.hostDelegates, it.cityStateDelegates) })
        assertNull(session("Information era"))
        assertEquals(listOf("Atomic era"), bnw.eras.values.filter { it.isUnitedNations }.map { it.name })
        assertEquals(4, bnw.eras.values.count { it.getWorldCongressSession() != null })
    }

    @Test
    fun `the Printing Press and the Forbidden Palace give the Congress its start and delegates`() {
        assertTrue(bnw.technologies["Printing Press"]!!.hasUnique(UniqueType.AllowsWorldCongress))
        assertTrue(bnw.buildings["Forbidden Palace"]!!.hasUnique(UniqueType.WorldCongressDelegates))
    }

    @Test
    fun `nuclear weapons cannot be built under Nuclear Non-Proliferation`() {
        for (unit in listOf("Atomic Bomb", "Nuclear Missile")) {
            val uniques = bnw.units[unit]!!.uniqueObjects
            val unbuildable = uniques.single { it.type == UniqueType.Unbuildable }
            assertEquals(listOf("when the [Nuclear Non-Proliferation] resolution is in effect"), unbuildable.modifiers.map { it.text })
            assertTrue(uniques.any { it.type == UniqueType.OnlyAvailable })
        }
    }

    @Test
    fun `Brave New World has no United Nations wonder and its Diplomatic Victory asks only for the vote`() {
        assertNull(bnw.buildings["United Nations"])
        assertNotNull(gnk.buildings["United Nations"])
        assertEquals(listOf("Win diplomatic vote"), bnw.victories["Diplomatic"]!!.milestones)
    }

    @Test
    fun `Gods and Kings and Vanilla have no World Congress`() {
        for (ruleset in listOf(gnk, vanilla)) {
            assertTrue(ruleset.resolutions.isEmpty())
            assertTrue(ruleset.eras.values.none { it.getWorldCongressSession() != null || it.isUnitedNations })
        }
    }

    @Test
    fun `Brave New World validates without errors`() {
        val errors = bnw.getErrorList()
        assertFalse(errors.getErrorText(unfiltered = true), errors.isError())
    }
}

@RunWith(BaseTestRunner::class)
class ResolutionValidationTests {
    @Test
    fun `a resolution whose choice does not fit its effect is a ruleset error`() {
        val testGame = TestGame()
        val resolution = Resolution().apply {
            name = "Broken Embargo"
            embargoesPlayer = true  // needs proposerDecision OtherMajorCiv
        }
        testGame.ruleset.resolutions[resolution.name] = resolution
        val errors = testGame.ruleset.getErrorList()
        assertTrue(errors.any { "Broken Embargo" in it.text && "OtherMajorCiv" in it.text })
    }

    @Test
    fun `a reward on a resolution that is no project, or for a tier that does not exist, is a ruleset error`() {
        val testGame = TestGame()
        testGame.ruleset.resolutions["Misplaced Reward"] = Resolution().apply {
            name = "Misplaced Reward"
            uniques = arrayListOf("Gain [5] Golden Age points <upon reaching contribution tier [1] of a World Congress project>")
        }
        testGame.ruleset.resolutions["World's Fair"] = Resolution().apply {
            name = "World's Fair"
            projectCostPerPlayer = 10
            uniques = arrayListOf("Gain [5] Golden Age points <upon reaching contribution tier [4] of a World Congress project>")
        }
        val errors = testGame.ruleset.getErrorList()
        assertTrue(errors.any { "Misplaced Reward" in it.text && "no project" in it.text })
        assertTrue(errors.any { "World's Fair" in it.text && "not 1, 2 or 3" in it.text })
    }

    @Test
    fun `a project that the cities cannot contribute to is a ruleset error`() {
        val testGame = TestGame()
        testGame.ruleset.resolutions["Moon Base"] = Resolution().apply {
            name = "Moon Base"
            projectCostPerPlayer = 100
        }
        val errors = testGame.ruleset.getErrorList()
        assertTrue(errors.any { "Moon Base" in it.text && "can only contribute" in it.text })
    }
}
