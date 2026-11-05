package com.unciv.logic.battle

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A city's ranged strike is 75% of its strength without the defense of its buildings (CvCity::getStrengthValue(bForRangeStrike));
 *  what a policy that strengthens defensive buildings adds to that defense stays. */
@RunWith(BaseTestRunner::class)
class CityRangedStrengthTests {
    private val game = TestGame()

    private fun cityWithWalls(civ: Civilization): City {
        val city = game.addCity(civ, game.getTile(HexCoord.Zero))
        city.cityConstructions.addBuilding("Walls") // +5 city strength
        return city
    }

    @Before
    fun setUp() {
        game.makeHexagonalMap(2)
    }

    @Test
    fun `walls raise the defense of a city but not its ranged strike`() {
        val plain = game.addCity(game.addCiv(), game.getTile(HexCoord.Zero))
        val plainDefense = CityCombatant(plain).getDefendingStrength(null)
        val plainStrike = CityCombatant(plain).getAttackingStrength(null)

        plain.cityConstructions.addBuilding("Walls")
        val combatant = CityCombatant(plain)

        assertEquals(plainDefense + 5, combatant.getDefendingStrength(null))
        assertEquals(plainStrike, combatant.getAttackingStrength(null))
    }

    @Test
    fun `a policy that strengthens defensive buildings still adds to the ranged strike`() {
        val modified = CityCombatant(cityWithWalls(game.addCiv("[+100]% City Strength from defensive buildings")))
        val other = game.addCity(game.addCiv(), game.getTile(0, 2))
        other.cityConstructions.addBuilding("Walls")
        val unmodified = CityCombatant(other)
        val buildingsDefense = other.getStrength() // walls and palace, which the policy doubles

        // the defense counts all of what the policy adds, the strike (75% of the strength) the part that is not the buildings' own
        assertEquals(unmodified.getDefendingStrength(null) + buildingsDefense, modified.getDefendingStrength(null).toFloat(), 1f)
        assertEquals(unmodified.getAttackingStrength(null) + buildingsDefense * 0.75f, modified.getAttackingStrength(null).toFloat(), 1f)
    }
}
