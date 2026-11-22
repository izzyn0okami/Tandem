package app.lawnchair.gamemode

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SaveStoreTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tandem-save-store-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun writesAndReadsSramAndStateThroughTemporaryReplacement() {
        val store = SaveStore(root, "melonds", File("Pokemon.nds"))

        assertNull(store.readSram())
        assertNull(store.readState())
        assertFalse(store.hasState())

        val firstSram = byteArrayOf(1, 2, 3)
        val latestSram = byteArrayOf(4, 5, 6, 7)
        val state = byteArrayOf(9, 8, 7)

        store.writeSram(firstSram)
        store.writeSram(latestSram)
        store.writeState(state)

        assertArrayEquals(latestSram, store.readSram())
        assertArrayEquals(state, store.readState())
        assertTrue(store.hasState())
        assertFalse(File(store.sramFile.parentFile, "sram.srm.tmp").exists())
        assertFalse(File(store.stateFile.parentFile, "state1.state.tmp").exists())
    }

    @Test
    fun readsNewestSiblingCoreSramWhenCurrentCoreHasNoSave() {
        val older = File(root, "older/Pokemon/sram.srm")
        val newer = File(root, "newer/Pokemon/sram.srm")
        older.parentFile.mkdirs()
        newer.parentFile.mkdirs()
        older.writeBytes(byteArrayOf(1))
        newer.writeBytes(byteArrayOf(2))
        older.setLastModified(1_000L)
        newer.setLastModified(2_000L)

        val store = SaveStore(root, "current", File("Pokemon.nds"), listOf("older", "newer"))
        assertArrayEquals(byteArrayOf(2), store.readSram())
    }

    @Test
    fun currentCoreSramTakesPrecedenceOverSiblingSaves() {
        val sibling = File(root, "older/Pokemon/sram.srm")
        sibling.parentFile.mkdirs()
        sibling.writeBytes(byteArrayOf(1, 1))

        val store = SaveStore(root, "current", File("Pokemon.nds"), listOf("older"))
        store.writeSram(byteArrayOf(2, 2))

        assertArrayEquals(byteArrayOf(2, 2), store.readSram())
    }

    @Test
    fun retroAchievementsProgressIsIsolatedByAccountAndCanBeDeleted() {
        val store = SaveStore(root, "melonds", File("Pokemon.nds"))
        val firstUser = byteArrayOf(1, 3, 5)
        val secondUser = byteArrayOf(2, 4, 6)

        store.writeRaProgress("player-one", firstUser)
        store.writeRaProgress("player-two", secondUser)

        assertArrayEquals(firstUser, store.readRaProgress("player-one"))
        assertArrayEquals(secondUser, store.readRaProgress("player-two"))

        store.deleteRaProgress("player-one")

        assertNull(store.readRaProgress("player-one"))
        assertArrayEquals(secondUser, store.readRaProgress("player-two"))
    }

    @Test
    fun emptyRetroAchievementsProgressIsTreatedAsAbsent() {
        val store = SaveStore(root, "melonds", File("Pokemon.nds"))
        store.writeRaProgress("player", byteArrayOf())

        assertNull(store.readRaProgress("player"))
    }

    @Test
    fun stableGameIdentitiesPreventSameBasenameCollisions() {
        val first = SaveStore(root, "melonds", File("folder-one/Pokemon.nds"), gameIdentity = "local-game-one")
        val second = SaveStore(root, "melonds", File("folder-two/Pokemon.nds"), gameIdentity = "local-game-two")

        first.writeSram(byteArrayOf(1, 2, 3))

        assertArrayEquals(byteArrayOf(1, 2, 3), first.readSram())
        assertNull(second.readSram())
        assertNotEquals(first.sramFile.parentFile, second.sramFile.parentFile)
    }

    @Test
    fun stableIdentityAllowsOnlyExplicitSiblingCoreSramFallback() {
        val source = SaveStore(root, "core-a", File("Pokemon.nds"), gameIdentity = "romm-game-123")
        source.writeSram(byteArrayOf(7, 8, 9))

        val sibling = SaveStore(
            root,
            "core-b",
            File("Renamed-Pokemon.nds"),
            listOf("core-a"),
            gameIdentity = "romm-game-123",
        )

        assertArrayEquals(byteArrayOf(7, 8, 9), sibling.readSram())
        assertNull(sibling.readState())
    }

    @Test
    fun verifiedLegacyMigrationCopiesBytesAndKeepsOriginalDirectory() {
        val legacy = File(root, "melonds/Pokemon")
        legacy.mkdirs()
        File(legacy, "sram.srm").writeBytes(byteArrayOf(3, 1, 4, 1, 5))
        File(legacy, "state1.state").writeBytes(byteArrayOf(9, 2, 6))

        val target = File(root, "melonds/local-stable-identity")

        assertTrue(GameIdentityResolver.migrateLegacy(legacy, target))
        assertArrayEquals(byteArrayOf(3, 1, 4, 1, 5), File(target, "sram.srm").readBytes())
        assertArrayEquals(byteArrayOf(9, 2, 6), File(target, "state1.state").readBytes())
        assertTrue(legacy.isDirectory)
        assertArrayEquals(byteArrayOf(3, 1, 4, 1, 5), File(legacy, "sram.srm").readBytes())
    }

    @Test
    fun gameFingerprintIncludesReferencedDiscContent() {
        val folder = File(root, "multidisc").apply { mkdirs() }
        val playlist = File(folder, "Game.m3u")
        val cue = File(folder, "Disc 1.cue")
        val disc = File(folder, "Disc 1.bin")
        playlist.writeText("Disc 1.cue\\n")
        cue.writeText(
            """FILE "Disc 1.bin" BINARY
                TRACK 01 MODE1/2352
            """.trimIndent(),
        )
        disc.writeBytes(byteArrayOf(1, 2, 3))

        val before = GameIdentityResolver.fingerprint(playlist)
        disc.writeBytes(byteArrayOf(1, 2, 4))
        val after = GameIdentityResolver.fingerprint(playlist)

        assertNotEquals(before, after)
    }

}
