package com.unciv.logic.battle

import com.unciv.logic.civilization.Civilization
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/** The combat points toward Great Generals: the threshold of the next one (CvPlayer::createGreatGeneral) and the in-borders bonus (CvUnit::changeExperience) */
@RunWith(BaseTestRunner::class)
class GreatGeneralPointsTests {
    private val game = TestGame()

    @Test
    fun thresholdOfCivVGrowsByTwiceTheModifierStep() {
        game.makeHexagonalMap(2)
        game.ruleset.modOptions.constants.greatGeneralThresholdLikeCivV = true
        val civ = game.addCiv()
        civ.greatPeople.greatGeneralPointsCounter["Great General"] = 100_000

        val thresholds = (1..11).map {
            assertNotNull(civ.greatPeople.getNewGreatGeneral())
            civ.greatPeople.pointsForNextGreatGeneralCounter["Great General"]
        }

        // 200 x (100 + 50 x (1 + 2 + ... )) / 100: +100 for each of the first nine, then +200
        assertEquals(listOf(300, 400, 500, 600, 700, 800, 900, 1000, 1100, 1300, 1500), thresholds)
    }

    @Test
    fun thresholdGrowsFlatByDefault() {
        game.makeHexagonalMap(2)
        game.ruleset.modOptions.constants.greatGeneralThresholdLikeCivV = false
        val civ = game.addCiv()
        civ.greatPeople.greatGeneralPointsCounter["Great General"] = 100_000

        assertNotNull(civ.greatPeople.getNewGreatGeneral())
        assertNotNull(civ.greatPeople.getNewGreatGeneral())

        assertEquals(300, civ.greatPeople.pointsForNextGreatGeneralCounter["Great General"])
    }

    private fun pointsFromAttackingInOwnTerritory(inBordersPercent: Int): Int {
        game.makeHexagonalMap(3)
        game.ruleset.modOptions.constants.greatGeneralPercentInBorders = inBordersPercent
        val attackerCiv: Civilization = game.addCiv()
        val defenderCiv = game.addCiv()
        attackerCiv.diplomacyFunctions.makeCivilizationsMeet(defenderCiv)
        attackerCiv.getDiplomacyManager(defenderCiv)!!.declareWar() // at war, the defender may stand in foreign territory
        game.addCity(attackerCiv, game.getTile(0, 0))
        val defenderTile = game.getTile(1, 0)
        assertEquals(attackerCiv, defenderTile.getOwner())
        val attacker = game.addUnit("Warrior", attackerCiv, game.getTile(0, 0))
        val defender = game.addUnit("Warrior", defenderCiv, defenderTile)

        Battle.attack(MapUnitCombatant(attacker), MapUnitCombatant(defender))

        return attackerCiv.greatPeople.greatGeneralPointsCounter["Great General"]
    }

    @Test
    fun fightingInOwnBordersEarnsDoubleGreatGeneralPoints() {
        // a melee attack gives 5 XP, so 5 points, doubled by the 100% of the in-borders bonus
        assertEquals(10, pointsFromAttackingInOwnTerritory(100))
    }

    @Test
    fun fightingInOwnBordersEarnsNothingExtraByDefault() {
        assertEquals(5, pointsFromAttackingInOwnTerritory(0))
    }
}
