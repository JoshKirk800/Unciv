package com.unciv.logic.civilization

import com.unciv.logic.map.tile.RoadStatus
import com.unciv.models.metadata.BaseRuleset
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** What the original's game handicap does to the AIs (CvPlayer::getProductionNeeded, CvUnit::init / workRate / upgradePrice,
 *  CvTreasury::GetImprovementGoldMaintenance), Brave New World on Deity:
 *  AITrainPercent 50, AIConstructPercent 50, AIPerEraModifier -5, AIFreeXP 30, AIFreeXPPercent 100, AIWorkRateModifier 100, AIUnitUpgradePercent 30. */
@RunWith(BaseTestRunner::class)
class HandicapAiFieldsTests {
    private val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)

    @Before
    fun setUp() {
        game.makeHexagonalMap(3)
        game.setDifficulty("Deity")
    }

    private fun civWithCity(isPlayer: Boolean = false, cityStateType: String? = null, x: Int = 0): Civilization {
        val civ = game.addCiv(isPlayer = isPlayer, cityStateType = cityStateType)
        game.addCity(civ, game.getTile(x, 0))
        return civ
    }

    @Test
    fun `city states pay the AI percentage on top of their own 150 percent, barbarians and humans pay none`() {
        val warrior = game.ruleset.units["Warrior"]!!
        val human = civWithCity(isPlayer = true, x = -2)
        val ai = civWithCity(x = 2)
        val cityState = civWithCity(cityStateType = "Cultured", x = 0)
        val barbarian = game.addBarbarianCiv()

        // Ancient era for all, so the per era factor is 1. Warrior costs 40
        assertEquals(40, warrior.getProductionCost(human, null))
        assertEquals(20, warrior.getProductionCost(ai, null))
        assertEquals(30, warrior.getProductionCost(cityState, null))
        assertEquals(40, warrior.getProductionCost(barbarian, null))
    }

    @Test
    fun `an AI pays less per era on Deity`() {
        val ai = civWithCity(x = 2)
        val monument = game.ruleset.buildings["Monument"]!!
        val ancientCost = monument.getProductionCost(ai, null)
        assertEquals(20, ancientCost)

        ai.tech.addTechnology("Philosophy") // classical, with its prerequisites not needed for the era
        val era = ai.getEraNumber()
        assertTrue("the civ is in a later era now", era >= 1)

        assertEquals((20 * (1 - 0.05f * era)).toInt(), monument.getProductionCost(ai, null))
    }

    @Test
    fun `units of an AI start with the free experience, civilians and humans do not`() {
        val human = civWithCity(isPlayer = true, x = -2)
        val ai = civWithCity(x = 2)
        val tile = game.getTile(1, 0)

        assertEquals(30, ai.units.placeUnitNearTile(tile.position, "Warrior")!!.promotions.XP)
        assertEquals(0, ai.units.placeUnitNearTile(tile.position, "Worker")!!.promotions.XP)
        assertEquals(0, human.units.placeUnitNearTile(tile.position, "Warrior")!!.promotions.XP)
    }

    @Test
    fun `an AI worker builds twice as fast on Deity`() {
        val human = civWithCity(isPlayer = true, x = -2)
        val ai = civWithCity(x = 2)
        val farm = game.ruleset.tileImprovements["Farm"]!!
        val humanWorker = game.addUnit("Worker", human, game.getTile(-1, 0))
        val aiWorker = game.addUnit("Worker", ai, game.getTile(1, 0))

        val humanTurns = farm.getTurnsToBuild(human, humanWorker)
        val aiTurns = farm.getTurnsToBuild(ai, aiWorker)
        assertTrue("a farm takes several turns", humanTurns >= 4)
        assertEquals(Math.round(humanTurns / 2f), aiTurns)
    }

    @Test
    fun `an AI pays 30 percent of the upgrade price on Deity`() {
        val human = civWithCity(isPlayer = true, x = -2)
        val ai = civWithCity(x = 2)
        val upgradeTo = game.ruleset.units["Swordsman"]!!
        val humanCost = game.addUnit("Warrior", human, game.getTile(-1, 0)).upgrade.getCostOfUpgrade(upgradeTo)
        val aiCost = game.addUnit("Warrior", ai, game.getTile(1, 0)).upgrade.getCostOfUpgrade(upgradeTo)

        assertTrue("the human price is a real price", humanCost >= 30)
        assertTrue("AI $aiCost vs human $humanCost", aiCost < humanCost * 0.4f)
    }

    @Test
    fun `road maintenance follows the own handicap of the player`() {
        val civ = game.addCiv(isPlayer = true)
        val city = game.addCity(civ, game.getTile(0, 0))
        val tile = game.getTile(1, 0)
        game.addTileToCity(city, tile)
        tile.setOwningCity(city)
        tile.roadStatus = RoadStatus.Road
        fun roadUpkeep() = abs(civ.stats.getStatMapForNextTurn()["Transportation upkeep"]?.gold ?: 0f)

        game.setDifficulty("Prince") // RouteCostPercent 100
        assertEquals(1f, roadUpkeep(), 0.001f)
        game.setDifficulty("Settler") // RouteCostPercent 34
        assertEquals(0.34f, roadUpkeep(), 0.001f)
    }
}
