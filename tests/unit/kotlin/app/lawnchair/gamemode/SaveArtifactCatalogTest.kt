package app.lawnchair.gamemode

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SaveArtifactCatalogTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tandem-artifact-catalog").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun inventorySeparatesCoreSaveStateAndAccountScopedRaProgress() {
        val store = SaveStore(root, "swanstation", File("Castlevania.cue"), gameIdentity = "romm-account-game-830")
        store.writeSram(byteArrayOf(1, 2, 3, 4))
        store.writeState(byteArrayOf(5, 6, 7))
        store.writeRaProgress("player-one", byteArrayOf(8, 9))

        val artifacts = store.inventory("player-one")

        assertEquals(
            listOf(
                SaveArtifactType.CORE_SAVE,
                SaveArtifactType.SAVE_STATE,
                SaveArtifactType.RETRO_ACHIEVEMENTS_PROGRESS,
            ),
            artifacts.map { it.type },
        )
        assertEquals(listOf("autosave", "state1", artifacts[2].slot), artifacts.map { it.slot })
        assertEquals("romm-account-game-830", artifacts.first().gameIdentity)
        assertEquals("swanstation", artifacts.first().coreId)
        assertEquals(4L, artifacts.first().sizeBytes)
        assertEquals(64, artifacts.first().sha256.length)
        assertTrue(artifacts.all { it.file.isFile })
    }

    @Test
    fun inventoryDoesNotExposeAnotherAccountsRaProgress() {
        val store = SaveStore(root, "snes9x", File("SuperMarioWorld.sfc"), gameIdentity = "romm-650")
        store.writeRaProgress("player-one", byteArrayOf(1))
        store.writeRaProgress("player-two", byteArrayOf(2))

        val artifacts = store.inventory("player-one")

        assertEquals(1, artifacts.size)
        assertEquals(SaveArtifactType.RETRO_ACHIEVEMENTS_PROGRESS, artifacts.single().type)
        assertFalse(artifacts.single().file.name.contains("player-one"))
    }

    @Test
    fun catalogIgnoresUnknownEmptyAndDuplicateFiles() {
        val save = File(root, "sram.srm").apply { writeBytes(byteArrayOf(1, 2)) }
        val emptyState = File(root, "state1.state").apply { createNewFile() }
        val unknown = File(root, "screenshot.png").apply { writeBytes(byteArrayOf(3)) }

        val artifacts = SaveArtifactCatalog.inspect(
            gameIdentity = "local-game",
            coreId = "melonds",
            files = listOf(save, save, emptyState, unknown),
        )

        assertEquals(1, artifacts.size)
        assertEquals(SaveArtifactType.CORE_SAVE, artifacts.single().type)
    }
}
