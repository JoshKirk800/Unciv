package com.unciv.logic.battle

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * CvCity::getStrengthValue(bForRangeStrike): the city strike modifier of the majority religion's beliefs also takes the CityRangeStrikeModifier
 * of the pantheon belief of the secondary religion (CvCityReligions::GetSecondaryReligionPantheonBelief, Free Religion).
 * Goddess of Protection (BELIEF_GODDESS_STRATEGY) gives 30. Needs the BNW ruleset.
 */
@RunWith(BaseTestRunner::class)
class CityStrikePantheonTests {
    private val freeReligion = "Cities with a majority religion also get the Pantheon belief bonus of the second most popular religion"
    private lateinit var game: TestGame

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(5)
    }

    private fun cityWithReligions(civ: Civilization, secondaryBeliefs: List<String>): City {
        val other = game.addCiv()
        val majority = game.addReligion(civ)
        majority.addBeliefs(listOf(game.ruleset.beliefs["Tithe"]!!))
        val secondary = game.addReligion(other)
        secondary.addBeliefs(secondaryBeliefs.map { game.ruleset.beliefs[it]!! })
        val city = game.addCity(civ, game.getTile(HexCoord.Zero), initialPopulation = 5)
        city.religion.addPressure(majority.name, 1000)
        city.religion.addPressure(secondary.name, 400)
        return city
    }

    private fun strikeModifiers(city: City): Int {
        val enemy = game.addCiv()
        val unit = game.addUnit("Warrior", enemy, game.getTile(4, 0))
        return BattleDamage.getAttackModifiers(CityCombatant(city), MapUnitCombatant(unit), unit.getTile()).values.sum()
    }

    @Test
    fun `the pantheon belief of the secondary religion adds to the city strike`() {
        val city = cityWithReligions(game.addCiv(freeReligion), listOf("Goddess of Protection", "Church Property"))
        assertEquals(30, strikeModifiers(city))
    }

    @Test
    fun `without Free Religion the secondary pantheon belief does not count`() {
        val city = cityWithReligions(game.addCiv(), listOf("Goddess of Protection", "Church Property"))
        assertEquals(0, strikeModifiers(city))
    }

    @Test
    fun `a secondary belief that is no pantheon belief does not count`() {
        val city = cityWithReligions(game.addCiv(freeReligion), listOf("Church Property"))
        assertEquals(0, strikeModifiers(city))
    }
}
