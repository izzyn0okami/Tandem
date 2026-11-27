package app.lawnchair.gamemode

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NintendoDsDeviceSyncTest {
    private val root = Files.createTempDirectory("tandem-nds-device-sync").toFile()

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun desmumeNativeDsvIsCataloguedAndSelectedForRomM() {
        val store = SaveStore(root, "desmume", File("Pokemon.nds"), gameIdentity = "romm-nds-game")
        val nativeSave = assertNotNullAndReturn(store.nativeSaveFile)
        nativeSave.writeBytes(byteArrayOf(1, 2, 3, 4))

        val artifact = DeviceSyncArtifactPolicy.selectForRomM(store.inventory())

        assertNotNull(artifact)
        assertEquals(SaveArtifactType.CORE_SAVE, artifact!!.type)
        assertEquals("dsv", artifact.format)
        assertEquals("autosave", DeviceSyncArtifactPolicy.remoteSlot(artifact))
        assertEquals(nativeSave, artifact.file)
    }

    @Test
    fun melonDsDsSramIsCataloguedForRomMDeviceSync() {
        val store = SaveStore(root, "melonds_ds", File("Pokemon.nds"), gameIdentity = "romm-nds-game")
        store.sramFile.writeBytes(byteArrayOf(4, 3, 2, 1))

        val artifact = DeviceSyncArtifactPolicy.selectForRomM(store.inventory())

        assertNotNull(artifact)
        assertEquals(SaveArtifactType.CORE_SAVE, artifact!!.type)
        assertEquals("srm", artifact.format)
        assertEquals("autosave", DeviceSyncArtifactPolicy.remoteSlot(artifact))
        assertEquals(store.sramFile, artifact.file)
        assertTrue(CoreSaveAdapterRegistry.isCompatibleRomMEmulator("melonds_ds", "melonds_ds"))
        assertFalse(CoreSaveAdapterRegistry.isCompatibleRomMEmulator("melonds_ds", "melonds"))
    }

    @Test
    fun desmumeFormatsAreNotAssumedCompatibleAcrossCores() {
        assertTrue(CoreSaveAdapterRegistry.isCompatibleRomMEmulator("desmume", "desmume"))
        assertTrue(CoreSaveAdapterRegistry.isCompatibleRomMEmulator("desmume2015", "desmume2015"))
        assertFalse(CoreSaveAdapterRegistry.isCompatibleRomMEmulator("desmume", "desmume2015"))
        assertFalse(CoreSaveAdapterRegistry.isCompatibleRomMEmulator("melonds", "desmume"))
    }

    @Test
    fun deviceSyncAcceptsDsvAndRejectsWrongRemoteExtension() {
        val file = File(root, "Pokemon.dsv").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        val save = DeviceSyncProtocolJson.localSave(
            romId = 123L,
            fileName = "Pokemon.dsv",
            emulator = "desmume",
            file = file,
            updatedAt = "2026-11-27T12:00:00Z",
            remoteSlot = "autosave",
            expectedExtension = DeviceSyncArtifactPolicy.fileExtensionForCore("desmume"),
        )
        assertNotNull(save)

        val validDownload = DeviceSyncOperation(
            action = DeviceSyncAction.Download,
            romId = 123L,
            saveId = 9L,
            fileName = "Pokemon.dsv",
            slot = "autosave",
            emulator = "desmume",
            reason = "server save is newer",
            serverUpdatedAt = "2026-11-27T12:00:00Z",
            serverContentHash = "0123456789abcdef0123456789abcdef",
        )
        DeviceSyncProtocolJson.validateDownloadOperation(
            validDownload,
            setOf("desmume"),
            "autosave",
            "dsv",
        )

        val wrongFormat = validDownload.copy(fileName = "Pokemon.srm")
        val rejected = runCatching {
            DeviceSyncProtocolJson.validateDownloadOperation(
                wrongFormat,
                setOf("desmume"),
                "autosave",
                "dsv",
            )
        }
        assertTrue(rejected.isFailure)
    }

    private fun assertNotNullAndReturn(file: File?): File {
        assertNotNull(file)
        return file!!
    }
}
