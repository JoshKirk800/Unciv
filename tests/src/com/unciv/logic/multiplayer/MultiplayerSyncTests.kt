package com.unciv.logic.multiplayer

import com.badlogic.gdx.Gdx
import com.badlogic.gdx.files.FileHandle
import com.sun.net.httpserver.HttpServer
import com.unciv.UncivGame
import com.unciv.logic.GameInfoPreview
import com.unciv.logic.files.UncivFiles
import com.unciv.logic.multiplayer.storage.MultiplayerServer
import com.unciv.testing.BaseTestRunner
import com.unciv.testing.TestGame
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions
import kotlin.reflect.jvm.isAccessible

/**
 * The multiplayer sync logic against a real HTTP file server on localhost (the same boundary the game uses),
 * which keeps files in memory and records the order of uploads.
 */
@RunWith(BaseTestRunner::class)
class MultiplayerSyncTests {

    /** `PUT /files/{name}` stores, `GET /files/{name}` returns it or 404 - the part of UncivServer the client uses. */
    private class FileServer {
        val files = ConcurrentHashMap<String, ByteArray>()
        val uploadOrder = CopyOnWriteArrayList<String>()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/files/") { exchange ->
                val name = exchange.requestURI.path.removePrefix("/files/")
                when (exchange.requestMethod) {
                    "PUT" -> {
                        files[name] = exchange.requestBody.readBytes()
                        uploadOrder += name
                        exchange.sendResponseHeaders(200, -1)
                    }
                    "GET" -> {
                        val body = files[name]
                        if (body == null) exchange.sendResponseHeaders(404, -1)
                        else {
                            exchange.sendResponseHeaders(200, body.size.toLong())
                            exchange.responseBody.write(body)
                        }
                    }
                    else -> exchange.sendResponseHeaders(405, -1)
                }
                exchange.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        fun stop() = server.stop(0)
    }

    private lateinit var fileServer: FileServer
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        fileServer = FileServer()
        tempDir = Files.createTempDirectory("unciv multiplayer ").toFile()
        val game = TestGame() // sets UncivGame.Current and its settings
        UncivGame.Current.files = UncivFiles(Gdx.files)
        game.makeHexagonalMap(1)
    }

    @After
    fun tearDown() {
        fileServer.stop()
        tempDir.deleteRecursively()
    }

    //region uploadGame

    private fun gameToUpload() = TestGame().also {
        val civ = it.addCiv(isPlayer = true)
        it.gameInfo.currentPlayer = civ.civName
    }.gameInfo

    @Test
    fun `uploading with preview stores the game before its preview`() {
        val gameInfo = gameToUpload()

        runBlocking { MultiplayerServer(fileServer.url).uploadGame(gameInfo, withPreview = true) }

        // The other order lets a player see the new preview, download the game and still get the old state
        Assert.assertEquals(listOf(gameInfo.gameId, "${gameInfo.gameId}_Preview"), fileServer.uploadOrder.toList())
    }

    @Test
    fun `uploading without preview stores only the game`() {
        val gameInfo = gameToUpload()

        runBlocking { MultiplayerServer(fileServer.url).uploadGame(gameInfo, withPreview = false) }

        Assert.assertEquals(listOf(gameInfo.gameId), fileServer.uploadOrder.toList())
    }

    //endregion
    //region MultiplayerGamePreview.update change detection

    private fun preview(turns: Int, player: String) = GameInfoPreview().apply {
        gameId = "00000000-0000-4000-8000-000000000001"
        this.turns = turns
        currentPlayer = player
        gameParameters.multiplayerServerUrl = fileServer.url
    }

    /** Runs the private `update()` of a [MultiplayerGamePreview] holding [local] against a server holding [remote]. */
    private fun updateLocalAgainstServer(local: GameInfoPreview, remote: GameInfoPreview): MultiplayerGamePreview {
        runBlocking { MultiplayerServer(fileServer.url).tryUploadGamePreview(remote) }
        val game = MultiplayerGamePreview(FileHandle(File(tempDir, "game")), local)
        val update = MultiplayerGamePreview::class.declaredFunctions.first { it.name == "update" }.apply { isAccessible = true }
        runBlocking { update.callSuspend(game) }
        return game
    }

    @Test
    fun `an identical remote preview leaves the local one alone`() {
        val local = preview(turns = 5, player = "Babylon")

        val game = updateLocalAgainstServer(local, remote = preview(turns = 5, player = "Babylon"))

        Assert.assertSame(local, game.preview)
    }

    @Test
    fun `a later turn on the server replaces the local preview`() {
        val local = preview(turns = 5, player = "Babylon")

        val game = updateLocalAgainstServer(local, remote = preview(turns = 6, player = "Babylon"))

        Assert.assertNotSame(local, game.preview)
        Assert.assertEquals(6, game.preview!!.turns)
    }

    @Test
    fun `another current player on the server replaces the local preview even in the same turn`() {
        val local = preview(turns = 5, player = "Babylon")

        val game = updateLocalAgainstServer(local, remote = preview(turns = 5, player = "Greece"))

        Assert.assertNotSame(local, game.preview)
        Assert.assertEquals("Greece", game.preview!!.currentPlayer)
    }

    @Test
    fun `a changed preview is also written to the local file`() {
        val local = preview(turns = 5, player = "Babylon")

        val game = updateLocalAgainstServer(local, remote = preview(turns = 6, player = "Greece"))

        val fromFile = UncivGame.Current.files.loadGamePreviewFromFile(game.fileHandle)
        Assert.assertEquals(6, fromFile.turns)
        Assert.assertEquals("Greece", fromFile.currentPlayer)
    }

    //endregion
}
