package com.unciv.logic.civilization

import com.unciv.logic.city.City
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.nation.Difficulty
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Handicap fidelity to the original:
 *  - unitCostModifier and buildingCostModifier scale a human's gold maintenance (CvTreasury::CalculateUnitCost,
 *    GetBuildingGoldMaintenance), not production (CvPlayer::getProductionNeeded has no human handicap).
 *  - in Brave New World city states use MINOR_CIV_HANDICAP (Prince), barbarians BARBARIAN_HANDICAP (Chieftain). */
@RunWith(BaseTestRunner::class)
class HandicapFidelityTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
    private lateinit var human: Civilization
    private lateinit var city: City

    @Before
    fun setUp() {
        game.makeHexagonalMap(2)
        // Settler level has 0.5 unitCostModifier and 0.5 buildingCostModifier
        game.setDifficulty("Settler")
        human = game.addCiv(isPlayer = true)
        city = game.addCity(human, game.getTile(0, 0))
    }

    @Test
    fun `a human on a low difficulty pays normal production cost for units and buildings`() {
        val warrior = game.ruleset.units["Warrior"]!!
        val monument = game.ruleset.buildings["Monument"]!!

        assertEquals("warrior base cost 40", 40, warrior.getProductionCost(human, city))
        assertEquals("monument base cost 40", 40, monument.getProductionCost(human, city))
    }

    @Test
    fun `a human on Settler pays half the building maintenance of a human on Prince`() {
        city.cityConstructions.addBuilding("Courthouse") // 4 gold maintenance

        city.cityStats.update()
        val settlerMaintenance = kotlin.math.abs(city.cityStats.finalStatList["Maintenance"]?.gold ?: 0f)

        game.setDifficulty("Prince") // Prince has 1.0 buildingCostModifier
        city.cityStats.update()
        val princeMaintenance = kotlin.math.abs(city.cityStats.finalStatList["Maintenance"]?.gold ?: 0f)

        assertEquals("Prince maintenance", 4f, princeMaintenance, 0.001f)
        assertEquals("Settler maintenance is halved", 2f, settlerMaintenance, 0.001f)
    }

    @Test
    fun `city states and barbarians in BNW have their own handicap levels`() {
        val cityState = game.addCiv(cityStateType = "Cultured")
        val barb = game.addBarbarianCiv()

        assertEquals("city state gets Prince", "Prince", cityState.getDifficulty().name)
        assertEquals("barbarian gets Chieftain", "Chieftain", barb.getDifficulty().name)

        val majorAi = game.addCiv()
        assertEquals("major AI gets AI Default", "AI Default", majorAi.getDifficulty().name)
    }
}
