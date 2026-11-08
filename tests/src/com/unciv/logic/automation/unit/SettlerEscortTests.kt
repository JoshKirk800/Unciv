package com.unciv.logic.automation.unit

import com.unciv.logic.automation.city.ConstructionAutomation
import com.unciv.logic.automation.diplomacy.WarAi
import com.unciv.logic.automation.strategy.ArmyType
import com.unciv.logic.automation.strategy.ProductionAi
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.city.City
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.DiplomacyPersonality
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 *  Decision-level checks of the units the original's cities train for operations: the escort of a found-city operation
 *  (CvCity::GetUnitForOperation, CvAIOperationFoundCity) and the units of an attack army (CvMilitaryAI::GetUnitForArmy,
 *  MilitaryAIHelpers::FirstSlotCityCanFill), with the BNW XML values (AI_CITYSTRATEGY_OPERATION_UNIT_BASE_WEIGHT 1500, _FLAVOR_MULTIPLIER 250,
 *  AI_CITYSTRATEGY_ARMY_UNIT_BASE_WEIGHT 750).
 */
@RunWith(GdxTestRunner::class)
class SettlerEscortTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization
    private lateinit var city: City
    private lateinit var settler: MapUnit

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(4)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        for (value in DiplomacyPersonality.entries) civ.ai.setDiplomacyValue(value, 0)
        city = game.addCity(civ, game.tileMap[0, 0])
        settler = game.addUnit("Settler", civ, game.tileMap[0, 0])
        settler.settlerOperation = SettlerOperation().apply { foundCity = true; escorted = true; target = HexCoord(3, 0); area = 0 }
    }

    private fun escortCandidate(): ConstructionAutomation.Buildable? =
        ConstructionAutomation(city.cityConstructions).getBuildables().firstOrNull { it.escortFor != null }

    @Test
    fun `a city offers the escort of a settler waiting on its landmass with the operation unit weight`() {
        civ.ai.setPersonalityFlavor(Flavor.Offense, 4)
        val warrior = game.ruleset.units["Warrior"]!!
        val candidate = escortCandidate()
        assertNotNull(candidate)
        assertEquals("Warrior", candidate!!.construction.name)
        assertEquals(UnitAiType.DEFENSE, candidate.escortAi)
        // 1500 + 250 x (OFFENSE 4 + 0 skipped over) + the unit's own weight
        assertEquals(1500 + 250 * 4 + ProductionAi.unitWeight(city, warrior), candidate.weight)
    }

    @Test
    fun `every offer that is not taken raises the next one by the flavor multiplier`() {
        civ.ai.setPersonalityFlavor(Flavor.Offense, 4)
        val first = escortCandidate()!!.weight
        assertEquals(1, civ.ai.military.numberOfTimesOpsBuildSkippedOver)
        assertEquals(first + 250, escortCandidate()!!.weight)
        assertEquals(2, civ.ai.military.numberOfTimesOpsBuildSkippedOver)
    }

    @Test
    fun `a settler with no escort wanted, with one underway or already escorted asks for none`() {
        settler.settlerOperation!!.escorted = false
        assertNull(escortCandidate())
        settler.settlerOperation!!.escorted = true
        val escort = game.addUnit("Warrior", civ, game.tileMap[0, 0]) // stands with the settler
        assertNull(escortCandidate())
        escort.destroy()
        assertNotNull(escortCandidate())
        settler.settlerOperation!!.escortUnitId = 12345 // an escort is on its way
        assertNull(escortCandidate())
    }

    @Test
    fun `a committed city stops other cities from offering the escort, until it builds something else`() {
        val other = game.addCity(civ, game.tileMap[4, 0])
        val unit = game.ruleset.units["Warrior"]!!
        SettlerAutomation.commitEscort(settler, city, unit, UnitAiType.DEFENSE)
        city.cityConstructions.setCurrentConstruction("Warrior")
        assertNull(SettlerAutomation.settlerAwaitingEscort(other))
        city.cityConstructions.setCurrentConstruction("Monument")
        assertNotNull(SettlerAutomation.settlerAwaitingEscort(other))
    }

    @Test
    fun `the unit a city trained for the operation becomes its escort with the slot's unit AI`() {
        val unit = game.ruleset.units["Warrior"]!!
        SettlerAutomation.commitEscort(settler, city, unit, UnitAiType.DEFENSE)
        val trained = game.addUnit("Warrior", civ, game.tileMap[0, 0])
        assertTrue(SettlerAutomation.onUnitTrained(trained, city))
        assertEquals(UnitAiType.DEFENSE, UnitAiTypes.current(trained))
        assertEquals(trained.id, settler.settlerOperation!!.escortUnitId)
        assertNull(settler.settlerOperation!!.escortCityId)
        // Not the escort of anything: the original's recon assignment still applies
        assertFalse(SettlerAutomation.onUnitTrained(game.addUnit("Warrior", civ, game.tileMap[1, 0]), city))
    }

    @Test
    fun `the escort heads for the settler`() {
        val unit = game.ruleset.units["Warrior"]!!
        SettlerAutomation.commitEscort(settler, city, unit, UnitAiType.DEFENSE)
        val trained = game.addUnit("Warrior", civ, game.tileMap[3, 0])
        SettlerAutomation.onUnitTrained(trained, city)
        assertTrue(SettlerAutomation.tryJoinSettler(trained))
        assertEquals(1, trained.currentTile.aerialDistanceTo(settler.currentTile))
        assertFalse(SettlerAutomation.tryJoinSettler(settler))
    }

    //region Armies

    @Test
    fun `an army that waits for units asks for the first empty required slot, the secondary AI when the city cannot train the first`() {
        val formation = game.ruleset.ai.multiUnitFormations.first { it.name == "MUFORMATION_BASIC_CITY_ATTACK_FORCE" }
        // Required slots in XML order: CITY_BOMBARD/RANGED, ATTACK/DEFENSE, ATTACK/COUNTER
        assertEquals(UnitAiType.CITY_BOMBARD, WarAi.firstSlotCityCanFill(civ, formation, false, false, false))
        assertEquals(UnitAiType.RANGED, WarAi.firstSlotCityCanFill(civ, formation, false, false, true))
        game.addUnit("Archer", civ, game.tileMap[1, 0]) // fills the first slot through its RANGED type
        assertEquals(UnitAiType.ATTACK, WarAi.firstSlotCityCanFill(civ, formation, false, false, false))
        assertEquals(UnitAiType.DEFENSE, WarAi.firstSlotCityCanFill(civ, formation, false, false, true))
        val warrior = game.addUnit("Warrior", civ, game.tileMap[1, 1])
        game.addUnit("Warrior", civ, game.tileMap[0, 1])
        // Required slots filled
        assertNull(WarAi.firstSlotCityCanFill(civ, formation, false, false, false))
        // A warrior sent exploring is not an army unit
        UnitAiTypes.set(warrior, UnitAiType.EXPLORE)
        assertEquals(UnitAiType.ATTACK, WarAi.firstSlotCityCanFill(civ, formation, false, false, false))
    }

    @Test
    fun `units for an army are asked of the cities only once an attack could not start for lack of units`() {
        assertNull(civ.ai.military.armyTypeBeingBuilt)
        assertNull(WarAi.unitForArmy(city))
        civ.ai.military.requestArmy(bySea = false)
        assertEquals(ArmyType.Land, civ.ai.military.armyTypeBeingBuilt)
        // The army type is the one with more requests
        civ.ai.military.requestArmy(bySea = true)
        assertEquals(ArmyType.NavalInvasion, civ.ai.military.armyTypeBeingBuilt)
        civ.ai.military.requestArmy(bySea = false)
        assertEquals(ArmyType.Land, civ.ai.military.armyTypeBeingBuilt)
        // No city bombard unit yet and no archers: nothing to train; with Archery the secondary AI of the slot, RANGED, gives the Archer
        assertNull(WarAi.unitForArmy(city))
        civ.tech.addTechnology("Archery")
        assertEquals("Archer", WarAi.unitForArmy(city)!!.name)
    }

    @Test
    fun `an exploring unit fills no slot of an attack`() {
        val formation = game.ruleset.ai.multiUnitFormations.first { it.name == "MUFORMATION_BASIC_CITY_ATTACK_FORCE" }
        game.addUnit("Archer", civ, game.tileMap[1, 0])
        game.addUnit("Warrior", civ, game.tileMap[1, 1])
        val second = game.addUnit("Warrior", civ, game.tileMap[0, 1])
        assertTrue(WarAi.isAttackReady(civ, formation, bySea = false, game.ruleset.ai))
        UnitAiTypes.set(second, UnitAiType.EXPLORE) // a Warrior may explore (Unit_AITypes) and then is no army unit
        assertFalse(WarAi.isAttackReady(civ, formation, bySea = false, game.ruleset.ai))
    }
    //endregion
}
