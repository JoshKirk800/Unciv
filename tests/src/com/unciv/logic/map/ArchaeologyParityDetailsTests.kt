package com.unciv.logic.map

import com.unciv.json.json
import com.unciv.logic.automation.diplomacy.OpinionAi
import com.unciv.models.ruleset.ai.MinorCivApproach
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.city.City
import com.unciv.logic.civilization.AlertType
import com.unciv.logic.civilization.Civilization
import com.unciv.logic.civilization.diplomacy.Demand
import com.unciv.logic.civilization.diplomacy.DiplomacyFlags
import com.unciv.logic.civilization.diplomacy.DiplomacyTurnManager
import com.unciv.logic.civilization.diplomacy.DiplomacyManager
import com.unciv.logic.civilization.diplomacy.DiplomaticModifiers
import com.unciv.logic.map.tile.ArchaeologyChoice
import com.unciv.logic.map.tile.ArchaeologyFunctions
import com.unciv.logic.map.tile.ArchaeologyRecord
import com.unciv.logic.map.tile.Tile
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.ruleset.ArtifactClasses
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class ArchaeologyParityDetailsTests {
    private lateinit var game: TestGame
    private lateinit var builder: Civilization
    private lateinit var owner: Civilization
    private lateinit var gallery: City

    private fun tile(x: Int, y: Int) = game.tileMap[x, y]
    private fun site(x: Int, y: Int, owningCity: City? = null): Tile = tile(x, y).also {
        if (owningCity != null) {
            game.addTileToCity(owningCity, it)
            it.setOwningCity(owningCity)
        }
        it.tileResource = game.ruleset.tileResources["Antiquity site"]
        it.archaeologyRecord = ArchaeologyRecord(ArtifactClasses.MeleeBattle, "Classical era", builder.civName, owner.civName)
        it.setImprovement("Archaeological Dig")
    }

    @Before
    fun setUp() {
        game = TestGame(baseRuleset = BaseRuleset.Civ_V_BNW)
        game.makeHexagonalMap(10)
        owner = game.addCiv()
        builder = game.addCiv(isPlayer = true)
        gallery = game.addCity(builder, tile(0, 0), replacePalace = true)
        game.addCity(owner, tile(0, 7), replacePalace = true)
        builder.diplomacyFunctions.makeCivilizationsMeet(owner)
        gallery.cityConstructions.addBuilding(game.createBuilding("Has [8] [Art/Artifact] Great Work slots"))
        game.gameInfo.archaeologyTriggered = true
        builder.tech.addTechnology("Archaeology")
    }

    @Test
    fun `a dig includes the feature clearing time before work modifiers and clears it on completion`() {
        val dig = game.ruleset.tileImprovements["Archaeological Dig"]!!
        val flat = tile(0, -3)
        val unit = game.addUnit("Archaeologist", builder, tile(1, -3))
        val baseline = dig.getTurnsToBuild(builder, unit, flat)
        assertEquals(6, baseline)
        for ((feature, additional) in listOf("Forest" to 4, "Jungle" to 7, "Marsh" to 6)) {
            val plot = site(1, -3)
            plot.removeImprovement()
            plot.setTerrainFeatures(listOf(feature))
            assertEquals("$feature build time", baseline + additional, dig.getTurnsToBuild(builder, unit, plot))
            plot.startWorkingOnImprovement(dig, builder, unit)
            assertEquals("$feature queued time", baseline + additional, plot.turnsToImprovement)
            repeat(baseline + additional) { if (plot.improvementInProgress != null) plot.doWorkerTurn(unit) }
            assertFalse("$feature removed on completion", feature in plot.terrainFeatures)
        }
    }

    @Test
    fun `artifacts and culture from a foreign major cost ten points but landmarks do not`() {
        val ownerCity = owner.cities.first()
        val diplomacy = owner.getDiplomacyManager(builder)!!
        val artifact = site(1, 6, ownerCity)
        assertTrue(ArchaeologyFunctions.execute(builder, artifact, ArchaeologyChoice.ArtifactOfFirst, null))
        assertEquals(10, diplomacy.negativeArchaeologyPoints)
        assertTrue(diplomacy.hasFlag(DiplomacyFlags.DugInOurLands))

        val landmark = site(-1, 7, ownerCity)
        assertTrue(ArchaeologyFunctions.execute(builder, landmark, ArchaeologyChoice.Landmark, null))
        assertEquals(10, diplomacy.negativeArchaeologyPoints)

        val neutral = site(0, -3)
        assertTrue(ArchaeologyFunctions.execute(builder, neutral, ArchaeologyChoice.ArtifactOfFirst, null))
        assertEquals(10, diplomacy.negativeArchaeologyPoints)
        val writing = site(2, 6, ownerCity)
        writing.archaeologyRecord = ArchaeologyRecord(ArtifactClasses.Writing, "Classical era", builder.civName, work = "Rosetta Stone")
        assertTrue(ArchaeologyFunctions.execute(builder, writing, ArchaeologyChoice.CultureBoost, null))
        assertEquals(20, diplomacy.negativeArchaeologyPoints)
    }

    @Test
    fun `a foreign landmark improves AI major opinion with diminishing integer credit reset at war`() {
        val ownerCity = owner.cities.first()
        val diplomacy = owner.getDiplomacyManager(builder)!!
        fun landmarkOpinion(): Float {
            OpinionAi.updateOpinion(owner, builder)
            return diplomacy.diplomaticModifiers[DiplomaticModifiers.BuiltLandmarksForUs.name] ?: 0f
        }
        val first = site(1, 6, ownerCity)
        assertTrue(ArchaeologyFunctions.execute(builder, first, ArchaeologyChoice.Landmark, null))
        assertEquals(20f, landmarkOpinion(), 0.001f)
        val second = site(-1, 7, ownerCity)
        assertTrue(ArchaeologyFunctions.execute(builder, second, ArchaeologyChoice.Landmark, null))
        assertEquals(26f, landmarkOpinion(), 0.001f)
        assertEquals(2, diplomacy.clone().landmarksBuiltForUs)
        assertEquals(2, json().fromJson(DiplomacyManager::class.java, json().toJson(diplomacy)).landmarksBuiltForUs)
        builder.getDiplomacyManager(owner)!!.declareWar()
        assertEquals(0, diplomacy.landmarksBuiltForUs)
        assertEquals(0f, landmarkOpinion(), 0.001f)
    }

    @Test
    fun `digging promise is persistent and refusal and breach carry original opinion penalties`() {
        val diplomacy = owner.getDiplomacyManager(builder)!!
        val ours = builder.getDiplomacyManager(owner)!!
        val first = site(1, 6, owner.cities.first())
        assertTrue(ArchaeologyFunctions.execute(builder, first, ArchaeologyChoice.ArtifactOfFirst, null))
        assertEquals(10, diplomacy.negativeArchaeologyPoints)
        // Avoid the desktop music hook on turn zero in headless test games.
        game.gameInfo.turns = 1
        game.gameInfo.currentPlayerCiv = owner
        game.gameInfo.nextTurn()
        assertTrue(builder.popupAlerts.any { it.type == AlertType.DemandToStopDiggingInOurLands })
        assertFalse(diplomacy.hasFlag(DiplomacyFlags.DugInOurLands))
        ours.agreeToDemand(Demand.DoNotDigInOurLands)
        assertEquals(0, diplomacy.negativeArchaeologyPoints)
        assertTrue(diplomacy.hasFlag(DiplomacyFlags.AgreedToNotDigInOurLands))
        repeat(120) { with(DiplomacyTurnManager) { diplomacy.nextTurn() } }
        assertTrue(diplomacy.hasFlag(DiplomacyFlags.AgreedToNotDigInOurLands))
        val second = site(-1, 7, owner.cities.first())
        assertTrue(ArchaeologyFunctions.execute(builder, second, ArchaeologyChoice.ArtifactOfSecond, null))
        assertEquals(10, diplomacy.negativeArchaeologyPoints)
        // The turn manager consumes the violation on the owner's diplomacy turn.
        game.gameInfo.currentPlayerCiv = owner
        game.gameInfo.nextTurn()
        assertFalse(diplomacy.hasFlag(DiplomacyFlags.AgreedToNotDigInOurLands))
        // GetBrokenNoDiggingPromiseScore: 30, permanent
        OpinionAi.updateOpinion(owner, builder)
        assertEquals(-30f, diplomacy.diplomaticModifiers[DiplomaticModifiers.BetrayedPromiseToNotDigInOurLands.name]!!, 0.001f)
        assertTrue(builder.popupAlerts.any { it.type == AlertType.DugInOurLandsDespitePromise })
        assertEquals(10, diplomacy.clone().negativeArchaeologyPoints)
        val restored = json().fromJson(DiplomacyManager::class.java, json().toJson(diplomacy))
        assertEquals(10, restored.negativeArchaeologyPoints)
        assertTrue(restored.hasFlag(DiplomacyFlags.IgnoreThemDiggingInOurLands))

    }

    @Test
    fun `refusing the dig promise costs twenty opinion and remains remembered`() {
        val diplomacy = owner.getDiplomacyManager(builder)!!
        builder.getDiplomacyManager(owner)!!.refuseDemand(Demand.DoNotDigInOurLands)
        OpinionAi.updateOpinion(owner, builder)
        assertEquals(-20f, diplomacy.diplomaticModifiers[DiplomaticModifiers.IgnoredRequestToStopDigging.name] ?: 0f, 0.001f)
        assertFalse(diplomacy.hasModifier(DiplomaticModifiers.BetrayedPromiseToNotDigInOurLands))
        repeat(120) { with(DiplomacyTurnManager) { diplomacy.nextTurn() } }
        assertTrue(diplomacy.hasFlag(DiplomacyFlags.IgnoreThemDiggingInOurLands))
        OpinionAi.updateOpinion(owner, builder)
        assertEquals(-20f, diplomacy.diplomaticModifiers[DiplomaticModifiers.IgnoredRequestToStopDigging.name] ?: 0f, 0.001f)
    }

    @Test
    fun `diplomatic victory AI builds a landmark in peaceful city state territory`() {
        game.gameInfo.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys)
        builder.playerType = PlayerType.AI
        builder.ai.grandStrategy = "AIGRANDSTRATEGY_UNITED_NATIONS"
        val state = game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        val stateCity = game.addCity(state, tile(-6, 2))
        builder.diplomacyFunctions.makeCivilizationsMeet(state)
        val find = site(-5, 2, stateCity)
        assertTrue(builder.ai.isGoingForDiploVictory())
        assertEquals(ArchaeologyChoice.Landmark, ArchaeologyFunctions.chooseForAi(builder, find, 0, 0))
        builder.ai.minorCivApproaches[state.civID] = MinorCivApproach.Conquest
        assertEquals("planned conquest rules out a Landmark", ArchaeologyChoice.ArtifactOfFirst, ArchaeologyFunctions.chooseForAi(builder, find, 0, 0))
        builder.ai.minorCivApproaches.remove(state.civID)
        builder.ai.grandStrategy = "AIGRANDSTRATEGY_SPACESHIP"
        assertEquals(ArchaeologyChoice.ArtifactOfFirst, ArchaeologyFunctions.chooseForAi(builder, find, 0, 0))
        builder.ai.grandStrategy = "AIGRANDSTRATEGY_UNITED_NATIONS"
        val previousInfluence = state.getDiplomacyManager(builder)!!.getInfluence()
        ArchaeologyFunctions.onDigCompleted(builder, find, null)
        assertEquals("Landmark", find.improvement)
        assertEquals(previousInfluence + 50f, state.getDiplomacyManager(builder)!!.getInfluence(), 0.001f)
    }

    @Test
    fun `diplomatic victory AI builds a landmark in city state territory also while at war with it unless it plans to conquer it`() {
        // CvPlayerCulture::GetArchaeologyChoice only looks at the minor civ approach, not at war
        game.gameInfo.gameParameters.victoryTypes = ArrayList(game.ruleset.victories.keys)
        builder.playerType = PlayerType.AI
        builder.ai.grandStrategy = "AIGRANDSTRATEGY_UNITED_NATIONS"
        val state = game.addCiv(cityStateType = game.ruleset.cityStateTypes.keys.first())
        val stateCity = game.addCity(state, tile(-6, 2))
        builder.diplomacyFunctions.makeCivilizationsMeet(state)
        builder.getDiplomacyManager(state)!!.declareWar()
        assertTrue(builder.isAtWarWith(state))
        val find = site(-5, 2, stateCity)
        assertEquals(ArchaeologyChoice.Landmark, ArchaeologyFunctions.chooseForAi(builder, find, 0, 0))
        builder.ai.minorCivApproaches[state.civID] = MinorCivApproach.Conquest
        assertEquals(ArchaeologyChoice.ArtifactOfFirst, ArchaeologyFunctions.chooseForAi(builder, find, 0, 0))
    }
}
