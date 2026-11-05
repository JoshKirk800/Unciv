package com.unciv.logic.battle

import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Plundering a city (promotions like Coastal Raider, CvUnitCombat::ApplyPostCityCombatEffects): after a melee attack in which neither
 *  side was lost, the attacker gets the percentage of the damage as gold and the city's owner loses it, as far as it has gold. */
@RunWith(BaseTestRunner::class)
class CityPlunderTests {
    private val game = TestGame()
    private lateinit var us: Civilization
    private lateinit var them: Civilization
    private lateinit var city: City
    private lateinit var cityCombatant: CityCombatant

    @Before
    fun setUp() {
        game.makeHexagonalMap(6)
        us = game.addCiv()
        them = game.addCiv()
        us.diplomacyFunctions.makeCivilizationsMeet(them)
        us.getDiplomacyManager(them)!!.declareWar()
        city = game.addCity(them, game.getTile(HexCoord.Zero))
        game.addCity(them, game.getTile(0, 5)) // so that taking this one does not eliminate the civilization
        cityCombatant = CityCombatant(city)
        city.health = cityCombatant.getMaxHealth()
        setGold(us, 0)
        setGold(them, 500)
    }

    private fun setGold(civ: Civilization, amount: Int) = civ.addGold(amount - civ.gold)

    private fun raider(vararg percentages: Int): MapUnit =
        game.addDefaultMeleeUnitWithUniques(us, game.getTile(1, 0),
            *percentages.map { "Earn [$it]% of the damage done to [City] units as [Gold]" }.toTypedArray())

    private fun attack(unit: MapUnit) = Battle.attack(MapUnitCombatant(unit), cityCombatant)

    @Test
    fun `the city's owner loses what the attacker gains`() {
        val dealt = attack(raider(100)).attackerDealt

        assertTrue("the city was hurt: $dealt", dealt > 0)
        assertEquals(dealt, us.gold)
        assertEquals(500 - dealt, them.gold)
    }

    @Test
    fun `the attacker gets all of it even when the city's owner has less gold`() {
        setGold(them, 3)

        val dealt = attack(raider(100)).attackerDealt

        assertEquals(dealt, us.gold)
        assertEquals(0, them.gold)
    }

    @Test
    fun `nothing is plundered from a city that falls`() {
        city.health = 10 // the attack deals more than that, so the city falls

        val dealt = attack(raider(100))

        assertTrue("the city fell with damage dealt: $dealt", dealt.cityFell && dealt.attackerDealt > 0)
        assertEquals(0, us.gold)
        assertEquals(500, them.gold)
    }
}
