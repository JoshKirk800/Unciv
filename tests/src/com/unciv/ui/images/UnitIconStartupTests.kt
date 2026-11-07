package com.unciv.ui.images

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.models.ruleset.RulesetCache
import com.unciv.models.ruleset.unit.BaseUnit
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class UnitIconStartupTests {
    @Test
    fun `main menu uses icons from an unbound vanilla ruleset`() {
        val game = TestGame()
        UncivGame.Current.files = UncivFiles(Gdx.files)
        UiTestSupport.prepare(game)

        // The menu uses a cloned ruleset before any game has called BaseUnit.setRuleset.
        val menuRuleset = RulesetCache.getVanillaRuleset()
        ImageGetter.setNewRuleset(menuRuleset)
        assertEquals("UnitIcons/Warrior", ImageGetter.getUnitIconPath(menuRuleset.units["Warrior"]!!))

        val replacement = BaseUnit().apply {
            name = "Test Archer replacement"
            replaces = "Archer"
            unitType = "Archery"
        }
        assertEquals("UnitIcons/Archer", ImageGetter.getUnitIconPath(replacement))

        val noOwnIcon = BaseUnit().apply {
            name = "Test unit without an icon"
            unitType = "Sword"
        }
        assertEquals("UnitTypeIcons/DomainLand", ImageGetter.getUnitIconPath(noOwnIcon))
    }
}
