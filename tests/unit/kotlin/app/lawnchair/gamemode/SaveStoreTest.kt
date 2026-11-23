package app.lawnchair.gamemode

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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
    fun queuedSaveSnapshotRemainsImmutableAcrossWorkerRetries() {
        val source = File(root, "sram.srm").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val snapshots = SaveSnapshotStore(File(root, "queue"))

        val first = snapshots.capture("worker-one", mapOf(RemoteFileKind.Save to source))
        source.writeBytes(byteArrayOf(4, 5, 6))

        val retry = snapshots.capture("worker-one", mapOf(RemoteFileKind.Save to source))
        val nextJob = snapshots.capture("worker-two", mapOf(RemoteFileKind.Save to source))

        assertArrayEquals(byteArrayOf(1, 2, 3), retry.getValue(RemoteFileKind.Save).readBytes())
        assertArrayEquals(byteArrayOf(4, 5, 6), nextJob.getValue(RemoteFileKind.Save).readBytes())
        assertEquals(first.getValue(RemoteFileKind.Save), retry.getValue(RemoteFileKind.Save))
    }

    @Test
    fun queuedSaveSnapshotRejectsModifiedSnapshotBytes() {
        val source = File(root, "state.state").apply { writeBytes(byteArrayOf(7, 8, 9)) }
        val snapshots = SaveSnapshotStore(File(root, "queue"))
        val captured = snapshots.capture("worker-integrity", mapOf(RemoteFileKind.State to source))
        captured.getValue(RemoteFileKind.State).writeBytes(byteArrayOf(9, 8, 7))

        assertTrue(runCatching {
            snapshots.capture("worker-integrity", mapOf(RemoteFileKind.State to source))
        }.isFailure)
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
        playlist.writeText("Disc 1.cue\n")
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

    @Test
    fun directRomMLinkRequiresMatchingContentFingerprint() {
        val fingerprint = "sha256-original"
        assertTrue(GameIdentityResolver.isSameGameFingerprint(fingerprint, fingerprint))
        assertFalse(GameIdentityResolver.isSameGameFingerprint("sha256-old", "sha256-new"))
        assertFalse(GameIdentityResolver.isSameGameFingerprint(null, fingerprint))
    }

    @Test
    fun singleRomFingerprintSurvivesRename() {
        val original = File(root, "folder/Original Name.nds")
        original.parentFile.mkdirs()
        original.writeBytes(byteArrayOf(4, 8, 15, 16, 23, 42))
        val before = GameIdentityResolver.fingerprint(original)

        val renamed = File(original.parentFile, "Renamed Name.nds")
        assertTrue(original.renameTo(renamed))

        assertEquals(before, GameIdentityResolver.fingerprint(renamed))
    }

    @Test
    fun cueFingerprintSurvivesCueAndDiscRename() {
        val folder = File(root, "cue-rename").apply { mkdirs() }
        val cue = File(folder, "Original.cue")
        val disc = File(folder, "Original.bin")
        cue.writeText(
            """FILE "Original.bin" BINARY
                TRACK 01 MODE1/2352
            """.trimIndent(),
        )
        disc.writeBytes(byteArrayOf(2, 7, 1, 8, 2, 8))
        val before = GameIdentityResolver.fingerprint(cue)

        val renamedDisc = File(folder, "Renamed.bin")
        assertTrue(disc.renameTo(renamedDisc))
        val renamedCue = File(folder, "Renamed.cue")
        cue.writeText(
            """FILE "Renamed.bin" BINARY
                TRACK 01 MODE1/2352
            """.trimIndent(),
        )
        assertTrue(cue.renameTo(renamedCue))

        assertEquals(before, GameIdentityResolver.fingerprint(renamedCue))
    }

    @Test
    fun legacyMigrationRejectsConflictingBytesAndKeepsBothDirectories() {
        val legacy = File(root, "legacy/Pokemon")
        legacy.mkdirs()
        File(legacy, "sram.srm").writeBytes(byteArrayOf(1, 2, 3))
        val target = File(root, "stable/Pokemon").apply { mkdirs() }
        File(target, "sram.srm").writeBytes(byteArrayOf(9, 9, 9))

        assertFalse(GameIdentityResolver.migrateLegacy(legacy, target))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(legacy, "sram.srm").readBytes())
        assertArrayEquals(byteArrayOf(9, 9, 9), File(target, "sram.srm").readBytes())
    }

    @Test
    fun migrationRecoversWhenStableDirectoryWasCreatedEmpty() {
        val legacy = File(root, "melonds/Pokemon")
        legacy.mkdirs()
        File(legacy, "sram.srm").writeBytes(byteArrayOf(5, 4, 3, 2, 1))
        val stable = File(root, "melonds/local-game-id")
        assertTrue(stable.mkdirs())

        val store = SaveStore(
            root,
            "melonds",
            File("Pokemon.nds"),
            gameIdentity = "local-game-id",
            migrationSources = mapOf("melonds" to legacy),
        )

        assertArrayEquals(byteArrayOf(5, 4, 3, 2, 1), store.readSram())
        assertTrue(legacy.isDirectory)
        assertTrue(File(stable, "sram.srm").isFile)
    }

    @Test
    fun unquotedCueReferenceAlsoSurvivesRename() {
        val folder = File(root, "cue-unquoted").apply { mkdirs() }
        val cue = File(folder, "Original.cue")
        val disc = File(folder, "Original.bin")
        cue.writeText("FILE Original.bin BINARY\\nTRACK 01 MODE1/2352")
        disc.writeBytes(byteArrayOf(6, 2, 6, 4))
        val before = GameIdentityResolver.fingerprint(cue)

        val renamedDisc = File(folder, "Renamed.bin")
        assertTrue(disc.renameTo(renamedDisc))
        val renamedCue = File(folder, "Renamed.cue")
        cue.writeText("FILE Renamed.bin BINARY\\nTRACK 01 MODE1/2352")
        assertTrue(cue.renameTo(renamedCue))

        assertEquals(before, GameIdentityResolver.fingerprint(renamedCue))
    }

    @Test
    fun migrationMergesLegacySaveWithExistingAuxiliaryProgressFiles() {
        val legacy = File(root, "legacy/Pokemon")
        legacy.mkdirs()
        File(legacy, "sram.srm").writeBytes(byteArrayOf(1, 3, 3, 7))
        val target = File(root, "stable/local-game-id").apply { mkdirs() }
        File(target, "ra-progress-player.bin").writeBytes(byteArrayOf(9, 8, 7))

        assertTrue(GameIdentityResolver.migrateLegacy(legacy, target))
        assertArrayEquals(byteArrayOf(1, 3, 3, 7), File(target, "sram.srm").readBytes())
        assertArrayEquals(byteArrayOf(9, 8, 7), File(target, "ra-progress-player.bin").readBytes())
        assertTrue(legacy.isDirectory)
    }

    @Test
    fun verifiedLegacyClaimCopiesBytesAndKeepsOriginalSource() {
        val source = File(root, "legacy/Pokemon")
        source.mkdirs()
        File(source, "sram.srm").writeBytes(byteArrayOf(3, 1, 4, 1, 5))
        File(source, "state1.state").writeBytes(byteArrayOf(9, 2, 6))
        val target = File(root, "account-a/romm-game")

        assertFalse(GameIdentityResolver.isVerifiedLegacyCopy(source, target))
        assertTrue(GameIdentityResolver.migrateLegacy(source, target))
        assertTrue(GameIdentityResolver.isVerifiedLegacyCopy(source, target))
        assertArrayEquals(byteArrayOf(3, 1, 4, 1, 5), File(target, "sram.srm").readBytes())
        assertArrayEquals(byteArrayOf(9, 2, 6), File(target, "state1.state").readBytes())
        assertTrue(source.isDirectory)
        assertArrayEquals(byteArrayOf(3, 1, 4, 1, 5), File(source, "sram.srm").readBytes())
    }

    @Test
    fun legacyClaimCannotBeVerifiedWhenDestinationHasDifferentProgress() {
        val source = File(root, "legacy/Pokemon")
        source.mkdirs()
        File(source, "sram.srm").writeBytes(byteArrayOf(1, 2, 3))
        val target = File(root, "account-a/romm-game")
        target.mkdirs()
        File(target, "sram.srm").writeBytes(byteArrayOf(9, 9, 9))

        assertFalse(GameIdentityResolver.isVerifiedLegacyCopy(source, target))
        assertFalse(GameIdentityResolver.migrateLegacy(source, target))
        assertArrayEquals(byteArrayOf(1, 2, 3), File(source, "sram.srm").readBytes())
        assertArrayEquals(byteArrayOf(9, 9, 9), File(target, "sram.srm").readBytes())
    }

    @Test
    fun rommIdentityMigrationPreservesProgressFromPreviousLocalIdentity() {
        val local = File(root, "melonds/local-previous-identity")
        local.mkdirs()
        File(local, "sram.srm").writeBytes(byteArrayOf(8, 6, 7, 5))
        File(local, "ra-progress-player.bin").writeBytes(byteArrayOf(3, 0, 9))

        val store = SaveStore(
            root,
            "melonds",
            File("Pokemon.nds"),
            gameIdentity = "romm-current-identity",
            migrationSources = mapOf("melonds" to local),
        )

        assertArrayEquals(byteArrayOf(8, 6, 7, 5), store.readSram())
        assertTrue(File(store.sramFile.parentFile, "ra-progress-player.bin").isFile)
        assertTrue(local.isDirectory)
    }

    @Test
    fun conflictInspectionListsOnlyDifferentOverlappingFilesAndKeepsBothTrees() {
        val source = File(root, "legacy/Pokemon").apply { mkdirs() }
        val target = File(root, "melonds/romm-game").apply { mkdirs() }
        File(source, "sram.srm").writeBytes(byteArrayOf(1, 2, 3))
        File(target, "sram.srm").writeBytes(byteArrayOf(9, 8, 7))
        File(source, "same.state").writeBytes(byteArrayOf(4, 5, 6))
        File(target, "same.state").writeBytes(byteArrayOf(4, 5, 6))
        File(source, "legacy-only.bin").writeBytes(byteArrayOf(7, 7))
        File(target, "current-only.bin").writeBytes(byteArrayOf(8, 8, 8))

        val conflicts = GameIdentityResolver.inspectLegacyConflicts(
            root,
            "romm-game",
            mapOf("melonds" to source),
        )

        assertEquals(1, conflicts.size)
        assertEquals("sram.srm", conflicts.single().path)
        assertEquals(3L, conflicts.single().sourceBytes)
        assertEquals(3L, conflicts.single().targetBytes)
        assertArrayEquals(byteArrayOf(1, 2, 3), File(source, "sram.srm").readBytes())
        assertArrayEquals(byteArrayOf(9, 8, 7), File(target, "sram.srm").readBytes())
        assertTrue(File(source, "legacy-only.bin").isFile)
        assertTrue(File(target, "current-only.bin").isFile)
    }

    @Test
    fun conflictInspectionReturnsEmptyWhenOverlappingFilesAreIdentical() {
        val source = File(root, "legacy/Pokemon").apply { mkdirs() }
        val target = File(root, "melonds/romm-game").apply { mkdirs() }
        val bytes = byteArrayOf(2, 7, 1, 8)
        File(source, "sram.srm").writeBytes(bytes)
        File(target, "sram.srm").writeBytes(bytes)

        val conflicts = GameIdentityResolver.inspectLegacyConflicts(
            root,
            "romm-game",
            mapOf("melonds" to source),
        )

        assertTrue(conflicts.isEmpty())
        assertTrue(source.isDirectory)
        assertTrue(target.isDirectory)
    }

    @Test
    fun conflictStagingUsesExplicitChoiceAndMergesNonConflictingFiles() {
        val source = File(root, "legacy/Pokemon").apply { mkdirs() }
        val target = File(root, "stable/Pokemon").apply { mkdirs() }
        File(source, "sram.srm").writeBytes(byteArrayOf(1, 1, 1))
        File(target, "sram.srm").writeBytes(byteArrayOf(2, 2, 2))
        File(source, "legacy-only.bin").writeBytes(byteArrayOf(3, 3))
        File(target, "current-only.bin").writeBytes(byteArrayOf(4, 4))

        val sourceChoice = File(root, "staged/source")
        assertTrue(
            GameIdentityResolver.stageLegacyConflictResolution(
                source,
                target,
                sourceChoice,
                preferSource = true,
            ),
        )
        assertArrayEquals(byteArrayOf(1, 1, 1), File(sourceChoice, "sram.srm").readBytes())
        assertArrayEquals(byteArrayOf(3, 3), File(sourceChoice, "legacy-only.bin").readBytes())
        assertArrayEquals(byteArrayOf(4, 4), File(sourceChoice, "current-only.bin").readBytes())

        val currentChoice = File(root, "staged/current")
        assertTrue(
            GameIdentityResolver.stageLegacyConflictResolution(
                source,
                target,
                currentChoice,
                preferSource = false,
            ),
        )
        assertArrayEquals(byteArrayOf(2, 2, 2), File(currentChoice, "sram.srm").readBytes())
        assertArrayEquals(byteArrayOf(3, 3), File(currentChoice, "legacy-only.bin").readBytes())
        assertArrayEquals(byteArrayOf(4, 4), File(currentChoice, "current-only.bin").readBytes())
        assertArrayEquals(byteArrayOf(1, 1, 1), File(source, "sram.srm").readBytes())
        assertArrayEquals(byteArrayOf(2, 2, 2), File(target, "sram.srm").readBytes())
    }

}
