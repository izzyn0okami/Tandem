package app.lawnchair.gamemode

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class GameLibraryScannerTest {

    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tandem-game-library").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun m3uHidesOnlyTheDiscFilesItReferences() {
        val folder = File(root, "psx").apply { mkdirs() }
        File(folder, "disc1.cue").writeText("FILE \"disc1.bin\" BINARY")
        File(folder, "disc2.cue").writeText("FILE \"disc2.bin\" BINARY")
        File(folder, "unrelated-game.cue").writeText("FILE \"unrelated.bin\" BINARY")
        File(folder, "game.m3u").writeText("disc1.cue\ndisc2.cue\n")

        val games = GameLibraryScanner.scan(root)

        assertEquals(
            setOf("game.m3u", "unrelated-game.cue"),
            games.map { File(it.path).name }.toSet(),
        )
    }

    @Test
    fun playlistCannotHideFilesOutsideItsOwnDirectory() {
        val folder = File(root, "psx").apply { mkdirs() }
        val nested = File(folder, "set").apply { mkdirs() }
        File(folder, "other-game.cue").writeText("FILE \"other.bin\" BINARY")
        File(nested, "game.m3u").writeText("../other-game.cue\n")

        val games = GameLibraryScanner.scan(root)

        assertEquals(
            setOf("game.m3u", "other-game.cue"),
            games.map { File(it.path).name }.toSet(),
        )
    }

    @Test
    fun playlistWithMissingDiscDoesNotHideUnrelatedFiles() {
        val folder = File(root, "psx").apply { mkdirs() }
        File(folder, "other-game.cue").writeText("FILE \"other.bin\" BINARY")
        File(folder, "game.m3u").writeText("missing-disc.cue\n")

        val games = GameLibraryScanner.scan(root)

        assertEquals(
            setOf("game.m3u", "other-game.cue"),
            games.map { File(it.path).name }.toSet(),
        )
    }
}
