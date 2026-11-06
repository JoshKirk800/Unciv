package com.unciv.models.ruleset

import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.GdxTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The Great Work data of Brave New World hangs together: every Great Person can create exactly one work, and slots are where the original has them */
@RunWith(GdxTestRunner::class)
class GreatWorkRulesetTests {
    private val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!

    private val groupOfType = mapOf(GreatWorkType.Art to "Artist", GreatWorkType.Literature to "Writer", GreatWorkType.Music to "Musician")

    @Test
    fun `the Great People of each kind are exactly the creators of its works`() {
        for ((type, group) in groupOfType) {
            val names = ruleset.unitNameGroups[group]!!.unitNames
            val creators = ruleset.greatWorks.values.filter { it.type == type }.mapNotNull { it.creator }
            assertEquals("every $group has a work", names.sorted(), creators.sorted())
            assertEquals("no $group is named twice", names.size, names.toSet().size)
        }
    }

    @Test
    fun `works that no Great Person creates are the artifacts, the writings of archaeology and the gift of the Parthenon`() {
        val withoutCreator = ruleset.greatWorks.values.filter { it.creator == null }
        val unexplained = withoutCreator.filter { it.type != GreatWorkType.Artifact && !it.archaeologyOnly }
        assertEquals(listOf("Parthenon"), unexplained.map { work ->
            ruleset.buildings.values.single { building ->
                building.uniqueObjects.any { it.text == "Gain the [${work.name}] Great Work" }
            }.name
        })
        assertTrue(withoutCreator.filter { it.archaeologyOnly }.all { it.type == GreatWorkType.Literature })
        assertEquals(15, withoutCreator.count { it.archaeologyOnly })
        assertEquals(30, withoutCreator.count { it.type == GreatWorkType.Artifact })
    }

    @Test
    fun `great units can use the Great Work ability and have a name group of their kind`() {
        for ((unit, group) in mapOf("Great Artist" to "Artist", "Great Writer" to "Writer", "Great Musician" to "Musician")) {
            val uniques = ruleset.units[unit]!!.uniques
            assertTrue("$unit creates works", "Create a Great Work <by consuming this unit>" in uniques)
            assertTrue("$unit is named from $group", "[This Unit] gets a name from the [$group] group" in uniques)
        }
    }

    @Test
    fun `buildings have the slots of the original`() {
        fun slots(name: String) = ruleset.buildings[name]!!.greatWorkSlotTypes
        val art = GreatWorkSlotType.ArtArtifact
        assertEquals(List(4) { art }, slots("The Louvre"))
        assertEquals(List(2) { art }, slots("Museum"))
        assertEquals(List(3) { art }, slots("Hermitage"))
        assertEquals(List(3) { GreatWorkSlotType.Music }, slots("Broadway"))
        assertEquals(List(2) { GreatWorkSlotType.Literature }, slots("Oxford University"))
        assertEquals(listOf(art), slots("Palace"))
    }

    @Test
    fun `the Louvre wants as much art as artifacts, from different eras and civilizations`() {
        val louvre = ruleset.buildings["The Louvre"]!!.themingBonuses.single()
        assertEquals(4, louvre.bonus)
        assertTrue(louvre.mustBeEqualArtArtifact && louvre.uniqueEras && louvre.requiresUniquePlayers)
    }
}
