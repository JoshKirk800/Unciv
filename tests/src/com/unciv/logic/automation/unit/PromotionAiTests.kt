package com.unciv.logic.automation.unit

import com.unciv.logic.automation.civilization.NextTurnAutomation
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.logic.map.mapunit.MapUnit
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ai.Flavor
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.view.GameView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

/**
 *  Decision-level checks of CvPlayerAI::AI_doTurnUnitsPost / CvUnit::AI_promote / CvUnit::AI_promotionValue (PromotionAi.kt) against the
 *  DLL formulas with the BNW XML values (Shock 15/15 open, Drill 15/15 rough, Accuracy 15 open ranged, Barrage 15 rough ranged, ...).
 *  The rating adds a roll of 0 to 14 to every positive value, so values are checked as the formula up to 14 more.
 */
@RunWith(GdxTestRunner::class)
class PromotionAiTests {
    private lateinit var game: TestGame
    private lateinit var civ: Civilization

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(5)
        civ = game.addCiv()
        for (flavor in Flavor.entries) civ.ai.setPersonalityFlavor(flavor, 0)
        civ.ai.setPersonalityFlavor(Flavor.Offense, 5)
        civ.ai.setPersonalityFlavor(Flavor.Defense, 3)
        civ.ai.setPersonalityFlavor(Flavor.Ranged, 4)
        civ.ai.setPersonalityFlavor(Flavor.Air, 2)
        game.addCity(civ, game.getTile(HexCoord(0, 0)))
    }

    private fun unit(name: String, at: HexCoord = HexCoord(1, 0)): MapUnit = game.addUnit(name, civ, game.getTile(at))

    private fun value(unit: MapUnit, promotion: String): Int =
        PromotionAi.promotionValue(unit, game.ruleset.unitPromotions[promotion]!!, Random(7))

    private fun assertValue(formula: Int, actual: Int, message: String) =
        assertTrue("$message: $formula to ${formula + 14}, was $actual", actual in formula..formula + 14)

    @Test
    fun `open and rough terrain promotions follow the unit's defensive bonus`() {
        // A swordsman (unit AI attack) fortifies: Shock = open attack and defense 15 + flavor x 2 each, Drill's rough terrain doubled
        val swordsman = unit("Swordsman")
        assertValue(15 + 5 * 2 + 15 + 3 * 2, value(swordsman, "Shock I"), "Shock I")
        assertValue(15 * 2 + 5 * 2 + 15 * 2 + 3 * 2, value(swordsman, "Drill I"), "Drill I")
        // A horseman has no defensive bonus: the open terrain is doubled instead
        val horseman = unit("Horseman", HexCoord(0, 1))
        assertValue(15 * 2 + 5 * 2 + 15 * 2 + 3 * 2, value(horseman, "Shock I"), "Shock I of a horseman")
        assertValue(15 + 5 * 2 + 15 + 3 * 2, value(horseman, "Drill I"), "Drill I of a horseman")
    }

    @Test
    fun `what the unit has makes the next tier worth more by twice its extra`() {
        val swordsman = unit("Swordsman")
        swordsman.promotions.addPromotion("Shock I", isFree = true)
        // 15 x (100 + 15 x 2) / 100 = 19 in both, open attack and open defense
        assertValue(19 + 5 * 2 + 19 + 3 * 2, value(swordsman, "Shock II"), "Shock II")
    }

    @Test
    fun `ranged promotions need a ranged unit and use the ranged flavor`() {
        val archer = unit("Archer")
        assertValue(15 + 4 * 2, value(archer, "Accuracy I"), "Accuracy I")
        assertValue(15 * 2 + 4 * 2, value(archer, "Barrage I"), "Barrage I")
        val swordsman = unit("Swordsman", HexCoord(0, 1))
        assertEquals(0, value(swordsman, "Accuracy I"))
    }

    @Test
    fun `a heal is rated only for a unit that has lost half its health`() {
        val swordsman = unit("Swordsman")
        assertEquals("not damaged: not a promotion it can take", 0, value(swordsman, "Heal Instantly"))
        swordsman.health = 55
        assertEquals("damaged less than half", 0, value(swordsman, "Heal Instantly"))
        swordsman.health = 50
        assertValue(1000, value(swordsman, "Heal Instantly"), "half health")
    }

    @Test
    fun `a unit takes the best promotion and again while it has the experience`() {
        val swordsman = unit("Swordsman")
        swordsman.promotions.XP = swordsman.promotions.xpForNextNPromotions(2)
        PromotionAi.promoteUnits(civ)
        // Drill I (rough terrain doubled) first; then Cover I: 33 ranged defense, doubled for unit AI attack and again for a unit with 2 moves, + 3 x 2
        assertEquals(setOf("Drill I", "Cover I"), swordsman.promotions.promotions)
        // 33 x (100 + 33 x 2) / 100 = 54 for the next tier, doubled twice
        assertValue(54 * 2 * 2 + 3 * 2, value(swordsman, "Cover II"), "Cover II")
        assertEquals(0, swordsman.promotions.XP)
    }

    @Test
    fun `a promotion the original rates nothing of is not taken`() {
        // A submarine's only choice is Wolfpack I: a plain attack bonus the rating does not read
        game.setTileTerrain(HexCoord(2, 0), "Ocean")
        val submarine = unit("Submarine", HexCoord(2, 0))
        submarine.promotions.XP = 1000
        NextTurnAutomation.applyPromotions(GameView(game.gameInfo, civ).getMapUnitView(submarine))
        assertTrue(submarine.promotions.promotions.none { it.startsWith("Wolfpack") })
        assertEquals(1000, submarine.promotions.XP)
    }

    @Test
    fun `a damaged unit heals instead of promoting only when half its health is gone`() {
        val swordsman = unit("Swordsman")
        swordsman.promotions.XP = 10
        swordsman.health = 55
        NextTurnAutomation.applyPromotions(GameView(game.gameInfo, civ).getMapUnitView(swordsman))
        assertEquals("not worth a heal yet: Drill I", true, "Drill I" in swordsman.promotions.promotions)
        assertEquals(55, swordsman.health)

        val hurt = unit("Swordsman", HexCoord(0, 1))
        hurt.promotions.XP = 10
        hurt.health = 40
        NextTurnAutomation.applyPromotions(GameView(game.gameInfo, civ).getMapUnitView(hurt))
        assertEquals(90, hurt.health)
        assertTrue(hurt.promotions.promotions.isEmpty())
    }

    @Test
    fun `interception is rated for a unit that can intercept`() {
        val fighter = unit("Fighter", HexCoord(0, 0))
        // 33 x 100 / 100 + air flavor x 2; the interception chance of the fighter itself is not what the rating adds to
        assertValue(33 + 2 * 2, value(fighter, "Interception I"), "Interception I")
        assertValue(33 + 2 * 2, value(fighter, "Dogfighting I"), "Dogfighting I")
    }
}
