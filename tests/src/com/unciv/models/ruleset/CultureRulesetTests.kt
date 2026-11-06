package com.unciv.models.ruleset

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The tourism and culture victory data of Brave New World */
@RunWith(GdxTestRunner::class)
class CultureRulesetTests {
    private val bnw = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
    private val gnk = RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!

    @Test
    fun `the culture victory of Brave New World is won by influence, of Gods and Kings by the Utopia Project`() {
        assertEquals(listOf("Be [Influential] over all other civilizations"), bnw.victories["Cultural"]!!.milestones)
        assertFalse("Utopia Project" in bnw.buildings)
        assertTrue("Build [Utopia Project]" in gnk.victories["Cultural"]!!.milestones)
    }

    @Test
    fun `hotels and the National Visitor Center turn landmarks and Great Works into tourism`() {
        val greatWorks = "Tourism from Great Works [in this city]"
        for ((name, percent) in listOf("Hotel" to 50, "Airport" to 50, "National Visitor Center" to 100)) {
            val uniques = bnw.buildings[name]!!.uniques
            assertTrue("$name: $percent% more from Great Works", "[+$percent]% $greatWorks" in uniques)
            assertTrue("$name: $percent% of landmark culture", "[$percent]% of the Culture from wonders, natural wonders and improved tiles becomes Tourism [in this city]" in uniques)
        }
        val center = bnw.buildings["National Visitor Center"]!!
        assertTrue(center.isNationalWonder)
        assertTrue("Only available <if [Hotel] is constructed in all [non-[Puppeted]] cities>" in center.uniques)
    }

    @Test
    fun `the Internet doubles tourism unless the target has the Great Firewall`() {
        assertTrue("[+100]% Tourism [in all cities] unless the target has a [Great Firewall]" in bnw.technologies["The Internet"]!!.uniques)
        assertTrue("Great Firewall" in bnw.buildings)
    }

    @Test
    fun `Cultural Exchange adds fifteen percent to three relations and is where the original has it`() {
        val policy = bnw.policies["Cultural Exchange"]!!
        assertEquals(listOf("Flourishing of the Arts"), policy.requires)
        assertEquals(3, policy.uniques.count { it.startsWith("[+15]% Tourism towards civilizations with [") })
        assertTrue(policy in bnw.policyBranches["Aesthetics"]!!.policies)
        assertEquals("the branch still ends with its finisher", "Aesthetics Complete", bnw.policyBranches["Aesthetics"]!!.policies.last().name)
    }

    @Test
    fun `the Eiffel Tower and the Mughal Fort make tourism once their technology is known`() {
        assertTrue("[+12] Tourism [in this city] <after discovering [Radio]>" in bnw.buildings["Eiffel Tower"]!!.uniques)
        assertTrue("[+2] Tourism [in this city] <after discovering [Flight]>" in bnw.buildings["Mughal Fort"]!!.uniques)
    }
}
