package com.unciv.logic.map

import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.BaseTestRunner
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The start biases of the original come from three XML tables only: Civilization_Start_Along_Ocean,
 *  Civilization_Start_Region_Priority and Civilization_Start_Region_Avoid (AssignStartingPlots.BalanceAndAssign). */
@RunWith(BaseTestRunner::class)
class NationStartBiasTests {

    @Before
    fun loadRulesets() {
        if (RulesetCache.isEmpty())
            RulesetCache.loadRulesets(noMods = true)
    }

    private val coast = listOf("Coast")

    @Test
    fun `Brave New World major civs have exactly the start biases of the XML tables`() {
        val expected = mapOf(
            "England" to coast, "The Ottomans" to coast, "Japan" to coast, "Polynesia" to coast, "Denmark" to coast,
            "Carthage" to coast, "Spain" to coast, "Byzantium" to coast, "Indonesia" to coast, "Korea" to coast,
            "Portugal" to coast, "Venice" to coast,
            "Arabia" to listOf("Desert"), "Morocco" to listOf("Desert"),
            "Aztecs" to listOf("Jungle"), "Brazil" to listOf("Jungle"),
            "India" to listOf("Grassland"), "The Netherlands" to listOf("Grassland"),
            "Iroquois" to listOf("Forest"), "Celts" to listOf("Forest"),
            "Russia" to listOf("Tundra"), "Sweden" to listOf("Tundra"),
            "Mongolia" to listOf("Plains"), "Poland" to listOf("Plains"),
            "Inca" to listOf("Hill"), "Austria" to listOf("Hill"),
            "Egypt" to listOf("Avoid [Jungle]", "Avoid [Forest]"),
            "The Huns" to listOf("Avoid [Forest]", "Avoid [Jungle]"),
            "Siam" to listOf("Avoid [Forest]"),
            "Zulus" to listOf("Avoid [Jungle]"),
            "Songhai" to listOf("Avoid [Tundra]"), "Assyria" to listOf("Avoid [Tundra]"), "Babylon" to listOf("Avoid [Tundra]"),
        )
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val actual = ruleset.nations.values
            .filter { it.isMajorCiv && it.startBias.isNotEmpty() }
            .associate { it.name to it.startBias.sorted() }
        Assert.assertEquals(expected.mapValues { it.value.sorted() }, actual)
    }

    @Test
    fun `Rome Greece Ethiopia and the Maya have no start bias in G&K and Brave New World`() {
        for (base in listOf(BaseRuleset.Civ_V_GnK, BaseRuleset.Civ_V_BNW)) {
            val ruleset = RulesetCache[base.fullName]!!
            for (name in listOf("Rome", "Greece", "Ethiopia", "The Maya"))
                Assert.assertEquals("$name in ${base.fullName}", emptyList<String>(), ruleset.nations[name]!!.startBias.toList())
        }
    }
}
