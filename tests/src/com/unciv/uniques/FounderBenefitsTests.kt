package com.unciv.uniques

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Founder beliefs only work for the founder of a religion while it owns the religion's holy city
 *  (CvGameReligions::IsEligibleForFounderBenefits). */
@RunWith(BaseTestRunner::class)
class FounderBenefitsTests {
    private val game = TestGame()
    private lateinit var us: Civilization
    private lateinit var them: Civilization
    private lateinit var holyCity: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(6)
        us = game.addCiv()
        them = game.addCiv()
        holyCity = game.addCity(us, game.getTile(0, 0))
        game.addCity(us, game.getTile(0, 4)) // so that losing the holy city does not eliminate us
        val religion = game.addReligion(us)
        religion.addBeliefs(listOf(game.ruleset.beliefs["Church Property"]!!)) // [+2 Gold] for each global city following this religion
        holyCity.religion.religionThisIsTheHolyCityOf = religion.name
        holyCity.religion.addPressure(religion.name, 1000)
    }

    private fun hasFounderBelief() = us.getMatchingUniques(UniqueType.StatsFromGlobalCitiesFollowingReligion).any()

    private fun religionGold() = us.stats.getStatMapForNextTurn()["Religion"]?.gold ?: 0f

    @Test
    fun `the founder owning the holy city gets the founder belief`() {
        assertTrue(hasFounderBelief())
        assertEquals(2f, religionGold(), 0.001f)
    }

    @Test
    fun `the founder loses the founder belief with the holy city and regains it`() {
        holyCity.moveToCiv(them)
        assertFalse(hasFounderBelief())
        assertEquals(0f, religionGold(), 0.001f)

        holyCity.moveToCiv(us)
        assertTrue(hasFounderBelief())
        assertEquals(2f, religionGold(), 0.001f)
    }

    @Test
    fun `a holy city blocked by an inquisitor still counts`() {
        holyCity.religion.isBlockedHolyCity = true
        assertTrue(hasFounderBelief())
    }

    @Test
    fun `a religion without a holy city gives the founder no founder belief`() {
        holyCity.religion.religionThisIsTheHolyCityOf = null
        assertFalse(hasFounderBelief())
    }
}
