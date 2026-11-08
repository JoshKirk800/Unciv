package com.unciv.logic

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.automation.strategy.UnitAiType
import com.unciv.logic.automation.strategy.UnitAiTypes
import com.unciv.logic.civilization.PlayerType
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.map.MapParameters
import com.unciv.logic.map.MapSize
import com.unciv.models.metadata.BaseRuleset
import com.unciv.models.metadata.GameParameters
import com.unciv.models.metadata.GameSettings
import com.unciv.models.metadata.GameSetupInfo
import com.unciv.models.metadata.Player
import com.unciv.models.ruleset.RulesetCache
import com.unciv.testing.BaseTestRunner
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CvPlayer::initFreeUnits: a player without an explorer sends its free military unit exploring */
@RunWith(BaseTestRunner::class)
class GameStarterExplorerTests {
    private var settingsBackup = GameSettings()

    @Before
    fun prepare() {
        RulesetCache.loadRulesets(noMods = true)
    }

    @After
    fun cleanup() {
        settingsBackup.save()
    }

    @Test
    fun `the free military unit of a new game explores`() {
        val parameters = GameParameters().apply {
            baseRuleset = BaseRuleset.Civ_V_BNW.fullName
            numberOfCityStates = 0
            difficulty = "Prince"
            players.clear()
            players.add(Player("Rome", PlayerType.Human))
            players.add(Player("Greece"))
        }
        val mapParameters = MapParameters().apply { mapSize = MapSize.Tiny; seed = 42L }
        UncivGame.Current = UncivGame()
        UncivGame.Current.files = UncivFiles(Gdx.files)
        settingsBackup = UncivGame.Current.files.getGeneralSettings()
        UncivGame.Current.settings = GameSettings()
        val game = GameStarter.startNewGame(GameSetupInfo(parameters, mapParameters))

        for (civ in game.civilizations.filter { it.isMajorCiv && !it.isSpectator }) {
            val military = civ.units.getCivUnits().filter { it.isMilitary() && it.baseUnit.isLandUnit }.toList()
            assertEquals("${civ.civName} starts with one military unit", 1, military.size)
            assertTrue("${civ.civName}'s free unit explores", UnitAiTypes.current(military.single()) == UnitAiType.EXPLORE)
        }
    }
}
