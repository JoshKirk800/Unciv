package com.unciv.logic.civilization.diplomacy

import com.unciv.json.json
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.IncomingUnitGift
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.logic.map.mapunit.actions.UnitActions
import com.unciv.models.UnitActionType
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A unit gifted to a city-state from afar (CvPlayer::DoDistanceGift, AddIncomingUnit, DoIncomingUnits, CvUnit::CanDistanceGift):
 *  it leaves at once, a new unit appears next to the capital MINOR_UNIT_GIFT_TRAVEL_TURNS (3) turns of the city-state later,
 *  and the influence comes then. Brave New World ruleset (`unitGiftTravelTurns`). */
@RunWith(BaseTestRunner::class)
class UnitGiftFromAfarTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var us: Civilization
    private lateinit var cityState: Civilization
    private lateinit var warrior: MapUnit

    @Before
    fun setUp() {
        game.makeHexagonalMap(8)
        us = game.addCiv(isPlayer = true)
        cityState = game.addCiv(cityStateType = "Cultured")
        game.addCity(us, game.getTile(-6, 0), replacePalace = true)
        game.addCity(cityState, game.getTile(5, 0), replacePalace = true)
        us.diplomacyFunctions.makeCivilizationsMeet(cityState)
        warrior = game.addUnit("Warrior", us, game.getTile(0, 0))
    }

    private fun influence() = cityState.getDiplomacyManager(us)!!.getInfluence()
    private fun csUnits() = cityState.units.getCivUnits().toList()

    @Test
    fun `a military unit can be sent to a known city-state from outside its borders`() {
        assertTrue(cityState.cityStateFunctions.canReceiveUnitGiftFromAfar(warrior))
        val actions = UnitActions.getUnitActions(warrior, UnitActionType.GiftUnitToCityState).toList()
        assertEquals(listOf("Gift unit to [${cityState.civName}]"), actions.map { it.title })
    }

    @Test
    fun `settlers, workers, scouts and units at war are not accepted, nor a second unit while one is on its way`() {
        assertFalse(cityState.cityStateFunctions.canReceiveUnitGiftFromAfar(game.addUnit("Settler", us, game.getTile(1, 0))))
        assertFalse(cityState.cityStateFunctions.canReceiveUnitGiftFromAfar(game.addUnit("Worker", us, game.getTile(2, 0))))
        assertFalse(cityState.cityStateFunctions.canReceiveUnitGiftFromAfar(game.addUnit("Scout", us, game.getTile(3, 0))))

        val other = game.addUnit("Warrior", us, game.getTile(4, 0))
        cityState.cityStateFunctions.receiveUnitGiftFromAfar(warrior)
        assertFalse(cityState.cityStateFunctions.canReceiveUnitGiftFromAfar(other))
    }

    @Test
    fun `no gift from afar to a city-state at war`() {
        us.getDiplomacyManager(cityState)!!.declareWar()
        assertFalse(cityState.cityStateFunctions.canReceiveUnitGiftFromAfar(warrior))
    }

    @Test
    fun `no gift from afar in a ruleset without a travel time`() {
        val gnk = TestGame()
        gnk.makeHexagonalMap(8)
        val giver = gnk.addCiv(isPlayer = true)
        val cs = gnk.addCiv(cityStateType = "Cultured")
        gnk.addCity(cs, gnk.getTile(5, 0), replacePalace = true)
        giver.diplomacyFunctions.makeCivilizationsMeet(cs)
        val unit = gnk.addUnit("Warrior", giver, gnk.getTile(0, 0))
        gnk.ruleset.modOptions.constants.unitGiftTravelTurns = 0

        assertFalse(cs.cityStateFunctions.canReceiveUnitGiftFromAfar(unit))
        assertEquals(0, UnitActions.getUnitActions(unit, UnitActionType.GiftUnitToCityState).count())
    }

    @Test
    fun `the unit leaves at once and arrives with its influence after three turns of the city-state`() {
        val influenceBefore = influence()
        UnitActions.getUnitActions(warrior, UnitActionType.GiftUnitToCityState).single().action!!.invoke()

        assertTrue("the unit is gone", warrior.isDestroyed && us.units.getCivUnits().none { it == warrior })
        assertNull(game.getTile(0, 0).militaryUnit)
        assertEquals(3, cityState.incomingUnitGifts[us.civName]!!.turnsLeft)
        assertEquals("Warrior", cityState.incomingUnitGifts[us.civName]!!.unitName)
        assertEquals(influenceBefore, influence(), 0.001f)

        cityState.cityStateFunctions.deliverIncomingUnitGifts()
        cityState.cityStateFunctions.deliverIncomingUnitGifts()
        assertEquals(0, csUnits().size)
        assertEquals(influenceBefore, influence(), 0.001f)

        cityState.cityStateFunctions.deliverIncomingUnitGifts()
        val arrived = csUnits().single()
        assertEquals("Warrior", arrived.name)
        assertTrue(arrived.currentTile.aerialDistanceTo(cityState.getCapital()!!.getCenterTile()) <= 1)
        assertEquals(influenceBefore + 5f, influence(), 0.001f)
        assertTrue(cityState.incomingUnitGifts.isEmpty())
    }

    @Test
    fun `nothing arrives at a city-state without a capital`() {
        val homeless = game.addCiv(cityStateType = "Cultured")
        us.diplomacyFunctions.makeCivilizationsMeet(homeless)
        homeless.cityStateFunctions.receiveUnitGiftFromAfar(warrior)
        val influenceBefore = homeless.getDiplomacyManager(us)!!.getInfluence()

        repeat(3) { homeless.cityStateFunctions.deliverIncomingUnitGifts() }

        assertEquals(0, homeless.units.getCivUnits().count())
        assertEquals(influenceBefore, homeless.getDiplomacyManager(us)!!.getInfluence(), 0.001f)
        assertTrue(homeless.incomingUnitGifts.isEmpty())
    }

    @Test
    fun `units on their way survive saving, loading and cloning the civilization`() {
        cityState.cityStateFunctions.receiveUnitGiftFromAfar(warrior)
        cityState.incomingUnitGifts[us.civName]!!.turnsLeft = 2

        val clone = cityState.clone()
        assertEquals(2, clone.incomingUnitGifts[us.civName]!!.turnsLeft)
        clone.incomingUnitGifts[us.civName]!!.turnsLeft = 1
        assertEquals("the clone has its own copy", 2, cityState.incomingUnitGifts[us.civName]!!.turnsLeft)

        val text = json().toJson(cityState.incomingUnitGifts)
        @Suppress("UNCHECKED_CAST")
        val loaded = json().fromJson(HashMap::class.java, IncomingUnitGift::class.java, text) as HashMap<String, IncomingUnitGift>
        val gift = loaded[us.civName]
        assertNotNull(gift)
        assertEquals("Warrior", gift!!.unitName)
        assertEquals(2, gift.turnsLeft)
    }
}
