package com.unciv.logic.automation.city

import com.unciv.logic.city.GreatWorkSlot
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.GreatWorkInstance
import com.unciv.models.ruleset.PerpetualConstruction
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class CulturalConstructionTests {
    @Test
    fun `cultural AI builds a tourism amplifier where its Great Works produce tourism`() {
        val game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(6)
        game.gameInfo.gameParameters.victoryTypes.add("Cultural")
        val civ = game.addCiv()
        civ.nation.preferredVictoryType = "Cultural"
        val city = game.addCity(civ, game.tileMap[0, 0])
        val gallery = game.createBuilding("Has [12] [Art/Artifact] Great Work slots")
        city.cityConstructions.addBuilding(gallery)
        repeat(10) { index ->
            val work = game.createGreatWork()
            city.greatWorks.place(GreatWorkInstance(work.name, civ.civName, "Ancient era", 1), GreatWorkSlot(city, gallery, index))
        }
        val tourism = game.createBuilding("[+50]% Tourism from Great Works [in this city]")
        tourism.cost = 50
        val science = game.createBuilding()
        science.cost = 50
        science.science = 2f

        city.cityConstructions.setCurrentConstruction(PerpetualConstruction.Idle.name)
        ConstructionAutomation(city.cityConstructions).chooseNextConstruction()

        assertEquals(tourism.name, city.cityConstructions.currentConstructionName())
    }
}
