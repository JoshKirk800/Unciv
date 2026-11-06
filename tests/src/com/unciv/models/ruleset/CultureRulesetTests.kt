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
    fun `sites appear with Archaeology, and are dug by an archaeologist whose finds are chosen`() {
        assertTrue("Archaeological sites appear around the world" in bnw.technologies["Archaeology"]!!.uniques)
        assertEquals("Archaeology", bnw.tileResources["Antiquity site"]!!.revealedBy)
        assertEquals("the policy that completes Exploration", "Exploration Complete", bnw.tileResources["Hidden Antiquity site"]!!.revealedBy)
        assertTrue(bnw.policies.containsKey("Exploration Complete"))

        val digging = bnw.tileImprovements["Archaeological Dig"]!!
        assertEquals(6, digging.turnsToBuild)
        for (unique in listOf("Excavate what was found here", "Remove [Antiquity site] resources from this tile", "Remove [Hidden Antiquity site] resources from this tile", "Can be built outside your borders"))
            assertTrue(unique, unique in digging.uniques)

        val archaeologist = bnw.units["Archaeologist"]!!
        assertEquals(200, archaeologist.cost)
        assertEquals("Archaeology", archaeologist.requiredTech)
        assertTrue("Can build [Archaeological Dig] improvements on tiles" in archaeologist.uniques)
        assertTrue("Only available <in cities with a [University]>" in archaeologist.uniques)
    }

    @Test
    fun `a Landmark is only made by archaeology, the Great Artist of Brave New World cannot, that of Gods and Kings can`() {
        val landmark = bnw.tileImprovements["Landmark"]!!
        assertTrue("Unbuildable" in landmark.uniques)
        assertTrue("[+1 Culture] for every era since the site of this improvement was formed" in landmark.uniques)
        assertEquals(0f, landmark.culture, 0f)
        assertFalse(bnw.units["Great Artist"]!!.uniques.any { "Landmark" in it })
        assertTrue(gnk.units["Great Artist"]!!.uniques.any { "Landmark" in it })
    }

    @Test
    fun `the Eiffel Tower and the Mughal Fort make tourism once their technology is known`() {
        assertTrue("[+12] Tourism [in this city] <after discovering [Radio]>" in bnw.buildings["Eiffel Tower"]!!.uniques)
        assertTrue("[+2] Tourism [in this city] <after discovering [Flight]>" in bnw.buildings["Mughal Fort"]!!.uniques)
    }
}
