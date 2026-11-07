package com.unciv.ui.images

import com.badlogic.gdx.Gdx
import com.unciv.UncivGame
import com.unciv.logic.files.UncivFiles
import com.unciv.models.stats.Stat
import com.unciv.testing.GdxTestRunner
import com.unciv.testing.TestGame
import com.unciv.testing.UiTestSupport
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(GdxTestRunner::class)
class IconTextButtonTests {
    @Before
    fun setUp() {
        val game = TestGame()
        UncivGame.Current.files = UncivFiles(Gdx.files)
        UiTestSupport.prepare(game)
    }

    @Test
    fun `a button with its own icon does not repeat the stat icon of a stat name`() {
        val withIcon = IconTextButton("Culture", ImageGetter.getStatIcon("Culture"))
        assertEquals("Culture", withIcon.label.text.toString())
    }

    @Test
    fun `a button without an icon keeps the stat icon of a stat name`() {
        val withoutIcon = IconTextButton("Culture")
        assertEquals(Stat.Culture.character + "Culture", withoutIcon.label.text.toString())
    }
}
