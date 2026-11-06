package com.unciv.testing

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.graphics.GL20
import com.badlogic.gdx.scenes.scene2d.Actor
import com.badlogic.gdx.scenes.scene2d.Group
import com.badlogic.gdx.scenes.scene2d.Stage
import com.badlogic.gdx.scenes.scene2d.ui.Label
import com.unciv.UncivGame
import com.unciv.dev.FontDesktop
import com.unciv.ui.components.fonts.Fonts
import com.unciv.ui.images.ImageGetter
import com.unciv.ui.screens.basescreen.BaseScreen
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import java.nio.IntBuffer

/**
 * Lets tests running on the [GdxTestRunner] construct real screens and widgets headlessly: [prepare] in `@Before`, then build the screen
 * and look at its actor tree with [actors] and [labelTexts].
 */
object UiTestSupport {
    private var shaderGl: GL20? = null
    private var imagesLoaded = false

    /**
     * Makes the GL mock of the runner able to create the shared sprite batch, loads the images once and gives screens a fresh batch.
     * Needs the [game] to be the one of this test, as `UncivGame.Current` is the one [TestGame] created.
     */
    fun prepare(game: TestGame) {
        Fonts.fontImplementation = FontDesktop()
        UncivGame.Current.settings.alternativeRendering = true
        // The runner installs a new GL mock per test class, so what was installed once is not necessarily still there
        if (Gdx.gl !== shaderGl) {
            installShaderCapableGl()
            shaderGl = Gdx.gl
        }
        if (!imagesLoaded) {
            ImageGetter.setNewRuleset(game.ruleset)
            imagesLoaded = true
        } else ImageGetter.ruleset = game.ruleset
        BaseScreen.recreateBatch()
    }

    /** SpriteBatch compiles a shader while the screen's stage is created. The runner's GL mock reports failure. */
    private fun installShaderCapableGl() {
        val gl = Mockito.mock(GL20::class.java)
        Mockito.`when`(gl.glCreateShader(Mockito.anyInt())).thenReturn(1)
        Mockito.`when`(gl.glCreateProgram()).thenReturn(1)
        Mockito.`when`(gl.glGenBuffer()).thenReturn(1)
        Mockito.`when`(gl.glGenTexture()).thenReturn(1)
        Mockito.`when`(gl.glGetShaderInfoLog(Mockito.anyInt())).thenReturn("")
        Mockito.`when`(gl.glGetProgramInfoLog(Mockito.anyInt())).thenReturn("")
        val reportStatus = Answer<Any> { invocation ->
            val pname = invocation.getArgument<Int>(1)
            val params = invocation.getArgument<IntBuffer>(2)
            val value = when (pname) {
                GL20.GL_COMPILE_STATUS, GL20.GL_LINK_STATUS -> GL20.GL_TRUE
                else -> 0
            }
            params.put(0, value)
            null
        }
        Mockito.doAnswer(reportStatus).`when`(gl).glGetShaderiv(Mockito.anyInt(), Mockito.anyInt(), Mockito.any())
        Mockito.doAnswer(reportStatus).`when`(gl).glGetProgramiv(Mockito.anyInt(), Mockito.anyInt(), Mockito.any())
        Gdx.gl = gl
        Gdx.gl20 = gl
    }

    /** Every actor of the [stage], breadth first */
    fun actors(stage: Stage): List<Actor> = actors(stage.actors.toList())

    /** [root] and everything below it, breadth first */
    fun actors(root: Actor): List<Actor> = actors(listOf(root))

    private fun actors(roots: List<Actor>): List<Actor> {
        val result = ArrayList<Actor>()
        val pending = ArrayDeque(roots)
        while (pending.isNotEmpty()) {
            val actor = pending.removeFirst()
            result.add(actor)
            if (actor is Group)
                for (child in actor.children) pending.add(child)
        }
        return result
    }

    /** The texts of all labels of the [stage], as shown (translated) */
    fun labelTexts(stage: Stage): List<String> = actors(stage).filterIsInstance<Label>().map { plain(it.text.toString()) }

    /** The texts of all labels at or below [root], as shown (translated) */
    fun labelTexts(root: Actor): List<String> = actors(root).filterIsInstance<Label>().map { plain(it.text.toString()) }

    /** Without the icon glyphs that translation puts in front of names of nations, techs and so on (private use characters) */
    fun plain(text: String): String = text.filter { it.code !in 0xE000..0xF8FF }
}
