package com.unciv.logic.civilization

import com.unciv.logic.battle.BattleDamage
import com.unciv.logic.battle.MapUnitCombatant
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.models.stats.Stat
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.min

/** Brave New World: an unhappy empire fights, produces and earns 2% less per point of unhappiness, at most 40% less
 *  (CvUnit::GetUnhappinessCombatPenalty, CvCity::getHappinessModifier). Growth stops at -10 or less, not below it. */
@RunWith(BaseTestRunner::class)
class UnhappinessEffectsTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game.makeHexagonalMap(3)
        civ = game.addCiv()
        game.addCity(civ, game.getTile(0, 0))
    }

    private fun setHappiness(happiness: Int) { civ.stats.happiness = happiness }

    private val attacker by lazy { game.addUnit("Warrior", civ, game.getTile(1, 0)) }
    private val defender by lazy { game.addUnit("Warrior", game.addCiv(), game.getTile(2, 0)) }

    private fun attackStrength(): Float {
        val unit = attacker
        val enemy = defender
        return BattleDamage.getAttackingStrength(MapUnitCombatant(unit), MapUnitCombatant(enemy), enemy.currentTile)
    }

    private fun percentBonus(stat: Stat): Float {
        val city = civ.cities.first()
        city.cityStats.update(updateCivStats = false) // keep the happiness the test set
        return city.cityStats.statPercentBonusTree.totalStats[stat]
    }

    @Test
    fun `combat strength drops 2 percent per unhappy point and stops at 40 percent`() {
        setHappiness(5)
        val happy = attackStrength()
        for (happiness in listOf(-1, -5, -12, -20, -35)) {
            setHappiness(happiness)
            assertEquals("at $happiness", happy * (1f - min(20, -happiness) * 0.02f), attackStrength(), 0.01f)
        }
    }

    @Test
    fun `production and gold drop 2 percent per unhappy point and stop at 40 percent`() {
        for (happiness in listOf(-1, -12, -30)) {
            setHappiness(happiness)
            val expected = -2f * min(20, -happiness)
            assertEquals("production at $happiness", expected, percentBonus(Stat.Production), 0.001f)
            assertEquals("gold at $happiness", expected, percentBonus(Stat.Gold), 0.001f)
        }
    }

    @Test
    fun `a nonnegative happiness has no penalty`() {
        for (happiness in listOf(0, 4)) {
            setHappiness(happiness)
            assertEquals(0f, percentBonus(Stat.Production), 0.001f)
            assertEquals(0f, percentBonus(Stat.Gold), 0.001f)
        }
    }

    /** CvCity::foodDifferenceTimes100: from -10 on the growth modifier gets -100 added, so other growth bonuses still count */
    @Test
    fun `from minus 10 on growth bonuses still apply to what is left of the surplus`() {
        val growers = game.addCiv("[+10 Food] [in all cities]", "[+50]% growth [in all cities]")
        val city = game.addCity(growers, game.getTile(-2, 0))
        fun surplus(happiness: Int): Float {
            growers.stats.happiness = happiness
            city.cityStats.update(updateCivStats = false)
            return city.cityStats.currentCityStats.food
        }
        val happy = surplus(5)
        val unhappy = surplus(-9)
        val veryUnhappy = surplus(-10)
        assertTrue("surplus at 5: $happy", happy > 0f)
        assertEquals("at -9 growth is slowed by 75% (+50%)", happy / 1.5f * 0.75f, unhappy, 0.01f)
        assertEquals("at -10 only the +50% bonus remains of the surplus", happy / 1.5f * 0.5f, veryUnhappy, 0.01f)
    }

    @Test
    fun `cities are refreshed on every happiness change when uniques scale with it`() {
        assertTrue(civ.gameInfo.ruleset.hasUniquesScalingWithHappiness)
        val gk = com.unciv.models.ruleset.RulesetCache[BaseRuleset.Civ_V_GnK.fullName]!!
        assertFalse(gk.hasUniquesScalingWithHappiness)
    }
}
