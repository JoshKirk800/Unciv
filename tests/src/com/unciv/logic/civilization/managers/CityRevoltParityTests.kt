package com.unciv.logic.civilization.managers

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.civilization.CivFlags
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.map.HexCoord
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.unique.UniqueType
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class CityRevoltParityTests {
    private lateinit var game: TestGame
    private lateinit var owner: Civilization
    private lateinit var closeRecipient: Civilization
    private lateinit var distantRecipient: Civilization
    private lateinit var manager: TurnManager
    private val flag = CivFlags.CityRevoltCountdown.name

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(12)
        UncivGame.Current.files = UncivFiles(Gdx.files)
        owner = game.addCiv(isPlayer = true)
        closeRecipient = game.addCiv()
        distantRecipient = game.addCiv()
        game.addCity(owner, game.getTile(HexCoord.Zero), replacePalace = true)
        game.addCity(owner, game.getTile(0, 4), initialPopulation = 12)
        game.addCity(owner, game.getTile(4, 0), initialPopulation = 4)
        game.addCity(closeRecipient, game.getTile(0, 8), replacePalace = true)
        game.addCity(distantRecipient, game.getTile(8, 0), replacePalace = true)
        owner.diplomacyFunctions.makeCivilizationsMeet(closeRecipient)
        owner.diplomacyFunctions.makeCivilizationsMeet(distantRecipient)
        for (civ in listOf(owner, closeRecipient, distantRecipient)) {
            civ.totalCultureForContests = 100
            civ.tech.addTechnology(game.ruleset.technologies.values.first { it.era() == "Modern era" }.name)
        }
        owner.policies.adopt(game.ruleset.policyBranches["Freedom"]!!)
        for (civ in listOf(closeRecipient, distantRecipient))
            civ.policies.adopt(game.ruleset.policyBranches["Autocracy"]!!)
        manager = TurnManager(owner)
        owner.stats.happiness = -20
    }

    private fun applyPressure() {
        // The distant civ dominates more strongly but the nearer capital receives the selected city.
        closeRecipient.culture.addInfluence(owner, 30)
        distantRecipient.culture.addInfluence(owner, 60)
        owner.culture.publicOpinion.update()
        assertTrue(owner.culture.publicOpinion.unhappiness > 0)
        assertEquals("Autocracy", owner.culture.publicOpinion.preferredIdeology)
    }

    @Test
    fun `content citizens and a civ with only its capital do not start a city revolt`() {
        manager.updateCityRevolts()
        assertFalse(owner.hasFlag(flag))
        applyPressure()
        owner.cities.drop(1).toList().forEach { it.destroyCity() }
        manager.updateCityRevolts()
        assertFalse(owner.hasFlag(flag))
    }

    @Test
    fun `a city only transfers after five eligible super unhappy turns and then resets`() {
        applyPressure()
        val candidate = owner.cities[1]
        val defendingUnit = game.addUnit("Warrior", owner, candidate.getCenterTile()).apply { currentMovement = 2f }
        manager.updateCityRevolts()
        assertEquals(5, owner.flagsCountdown[flag])
        assertSame(owner, candidate.civ)
        repeat(4) { manager.updateCityRevolts() }
        assertEquals(1, owner.flagsCountdown[flag])
        assertSame(owner, candidate.civ)
        manager.updateCityRevolts()
        assertFalse(owner.hasFlag(flag))
        assertSame(closeRecipient, candidate.civ)
        assertSame(owner, owner.cities[1].civ)
        assertTrue(owner.notifications.any { it.text.contains(candidate.name) && it.text.contains("joined") })
        assertFalse(defendingUnit.getTile() == candidate.getCenterTile())
        assertEquals(0f, defendingUnit.currentMovement)
        manager.updateCityRevolts()
        assertEquals(5, owner.flagsCountdown[flag])
    }

    @Test
    fun `recovery and content pause countdown instead of counting down`() {
        applyPressure()
        manager.updateCityRevolts()
        owner.stats.happiness = -19
        repeat(3) { manager.updateCityRevolts() }
        assertEquals(5, owner.flagsCountdown[flag])
        owner.stats.happiness = -20
        owner.culture.addInfluence(closeRecipient, 60)
        owner.culture.addInfluence(distantRecipient, 60)
        owner.culture.publicOpinion.update()
        assertEquals(0, owner.culture.publicOpinion.unhappiness)
        manager.updateCityRevolts()
        assertEquals(5, owner.flagsCountdown[flag])
        closeRecipient.culture.addInfluence(owner, 170)
        owner.culture.publicOpinion.update()
        manager.updateCityRevolts()
        assertEquals(4, owner.flagsCountdown[flag])
    }

    @Test
    fun `recipient lost on deadline cancels this revolt and restart needs a recipient`() {
        applyPressure()
        manager.updateCityRevolts()
        repeat(4) { manager.updateCityRevolts() }
        val capitals = listOf(closeRecipient, distantRecipient).map { recipient ->
            val capital = recipient.cities.first()
            val indicator = capital.cityConstructions.getBuiltBuildings()
                .first { it.hasUnique(UniqueType.IndicatesCapital) }
            capital.cityConstructions.removeBuilding(indicator)
            assertNull(recipient.getCapital(false))
            capital to indicator
        }
        manager.updateCityRevolts()
        assertFalse(owner.hasFlag(flag))
        assertEquals(3, owner.cities.size)
        manager.updateCityRevolts()
        assertFalse(owner.hasFlag(flag))
        for ((city, indicator) in capitals) city.cityConstructions.addBuilding(indicator)
        manager.updateCityRevolts()
        assertEquals(5, owner.flagsCountdown[flag])
    }

    @Test
    fun `countdown survives a game clone without affecting barbarian uprising flags`() {
        applyPressure()
        owner.addFlag(CivFlags.RevoltSpawning.name, 3)
        manager.updateCityRevolts()
        val copy = game.gameInfo.clone().getCivilization(owner.civID)
        assertEquals(5, copy.flagsCountdown[flag])
        assertEquals(3, copy.flagsCountdown[CivFlags.RevoltSpawning.name])
        assertEquals(3, owner.flagsCountdown[CivFlags.RevoltSpawning.name])
    }

    @Test
    fun `played start turns count down and transfer the city without a conquest`() {
        applyPressure()
        val candidate = owner.cities[1]
        candidate.population.addPopulation(60)
        candidate.cityStats.update()
        owner.updateStatsForNextTurn()
        assertTrue(owner.getHappiness() <= -20)

        manager.startTurn()
        assertEquals(5, owner.flagsCountdown[flag])
        repeat(5) { manager.startTurn() }
        assertSame(closeRecipient, candidate.civ)
        assertFalse(owner.hasFlag(flag))
        assertFalse(candidate.hasJustBeenConquered)
    }

}
