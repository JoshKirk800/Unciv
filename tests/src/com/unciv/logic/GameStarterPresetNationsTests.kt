package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.FileConversions
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.logic.map.TileMap
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.BaseTestRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A premade map's starting locations for city-states decide which city-states play: those with a location come first */
@RunWith(BaseTestRunner::class)
class GameStarterPresetNationsTests {
    private var settingsBackup = GameSettings()
    private val mapFile = File.createTempFile("preset-nations", ".map")

    @Before
    fun prepare() {
        RulesetCache.loadRulesets(noMods = true)
    }

    @After
    fun cleanup() {
        settingsBackup.save()
        mapFile.delete()
    }

    @Test
    fun `city-states with a starting location on the map are the ones chosen`() {
        val ruleset = RulesetCache[BaseRuleset.Civ_V_BNW.fullName]!!
        val chosen = listOf("Zanzibar", "Ragusa", "Quebec City")
        val map = TileMap(12, 12, ruleset)
        map.mapParameters = MapParameters().apply { mapSize = MapSize(12, 12); baseRuleset = ruleset.name }
        for ((index, nation) in (listOf("Rome", "Greece") + chosen).withIndex())
            map.startingLocations.add(TileMap.StartingLocation(map.tileList[20 + index * 27].position, nation))
        FileConversions.writeJson(Gdx.files.absolute(mapFile.absolutePath), map, zip = false)

        val parameters = GameParameters().apply {
            baseRuleset = ruleset.name
            numberOfCityStates = chosen.size
            difficulty = "Prince"
            players.clear()
            players.add(Player("Rome", PlayerType.Human))
            players.add(Player("Greece"))
        }
        val mapParameters = MapParameters().apply { name = "preset"; seed = 42L }
        UncivGame.Current = UncivGame()
        UncivGame.Current.files = UncivFiles(Gdx.files)
        settingsBackup = UncivGame.Current.files.getGeneralSettings()
        UncivGame.Current.settings = GameSettings()
        val game = GameStarter.startNewGame(GameSetupInfo(parameters, mapParameters).apply { this.mapFile = Gdx.files.absolute(this@GameStarterPresetNationsTests.mapFile.absolutePath) })

        assertEquals(chosen.sorted(), game.civilizations.filter { it.isCityState }.map { it.civName }.sorted())
    }
}
