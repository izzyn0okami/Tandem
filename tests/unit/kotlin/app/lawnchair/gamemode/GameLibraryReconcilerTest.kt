package app.lawnchair.gamemode

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class GameLibraryReconcilerTest {

    private val snes = checkNotNull(GamePlatforms.bySlug("snes"))
    private val gba = checkNotNull(GamePlatforms.bySlug("gba"))
    private val psx = checkNotNull(GamePlatforms.bySlug("psx"))
    private val dreamcast = checkNotNull(GamePlatforms.bySlug("dreamcast"))
    private val saturn = checkNotNull(GamePlatforms.bySlug("saturn"))
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tandem-rom-reconcile").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun associated(
        id: Long,
        name: String,
        size: Long,
        hash: String?,
        category: String? = "game",
    ) = RemoteAssociatedFile(
        id = id,
        fileName = name,
        sizeBytes = size,
        category = category,
        isTopLevel = true,
        sha1Hash = hash,
    )

    private fun remoteGame(
        id: Long,
        platform: GamePlatform = snes,
        hash: String? = "verified-sha1",
        fileName: String = "Super Mario World.sfc",
        size: Long = 1024L,
    ) = RemoteGame(
        id = id,
        title = "Super Mario World",
        platform = platform,
        fileName = fileName,
        sizeBytes = size,
        coverPath = null,
        associatedFiles = listOf(associated(id + 1000L, fileName, size, hash)),
    )

    private fun local(size: Long, hash: String) = GameLibraryReconciler.FileFingerprint(size, hash)

    @Test
    fun exactHashAndSizeOnSamePlatformFindsRemoteRomId() {
        assertEquals(
            listOf(10L),
            GameLibraryReconciler.matchingRomIds(local(1024L, "verified-sha1"), "snes", listOf(remoteGame(10L))),
        )
    }

    @Test
    fun sameHashWithDifferentSizeDoesNotAssociate() {
        assertEquals(
            emptyList<Long>(),
            GameLibraryReconciler.matchingRomIds(
                local(2048L, "verified-sha1"),
                "snes",
                listOf(remoteGame(10L)),
            ),
        )
    }

    @Test
    fun matchingTitleAndSizeWithoutHashDoesNotAssociate() {
        assertEquals(
            emptyList<Long>(),
            GameLibraryReconciler.matchingRomIds(
                local(1024L, "verified-sha1"),
                "snes",
                listOf(remoteGame(10L, hash = null)),
            ),
        )
    }

    @Test
    fun exactHashOnAnotherPlatformDoesNotAssociate() {
        assertEquals(
            emptyList<Long>(),
            GameLibraryReconciler.matchingRomIds(
                local(1024L, "verified-sha1"),
                "snes",
                listOf(remoteGame(10L, platform = gba)),
            ),
        )
    }

    @Test
    fun identicalHashInDifferentRemoteRecordsRemainsAmbiguous() {
        assertEquals(
            listOf(10L, 11L),
            GameLibraryReconciler.matchingRomIds(
                local(1024L, "verified-sha1"),
                "snes",
                listOf(remoteGame(10L), remoteGame(11L)),
            ),
        )
    }

    @Test
    fun duplicateAssociatedFilesInOneRecordDoNotDuplicateRomId() {
        val game = remoteGame(10L).copy(
            associatedFiles = remoteGame(10L).associatedFiles + remoteGame(10L).associatedFiles,
        )
        assertEquals(
            listOf(10L),
            GameLibraryReconciler.matchingRomIds(local(1024L, "verified-sha1"), "snes", listOf(game)),
        )
    }

    @Test
    fun candidatePrefilterDoesNotRequireMatchingFileExtension() {
        val remote = remoteGame(10L, fileName = "Super Mario World.bin")
        assertEquals(
            listOf(remote),
            GameLibraryReconciler.candidateGames(1024L, "snes", listOf(remote)),
        )
    }

    @Test
    fun candidatePrefilterStillRequiresSamePlatformAndSize() {
        assertEquals(
            emptyList<RemoteGame>(),
            GameLibraryReconciler.candidateGames(
                2048L,
                "snes",
                listOf(remoteGame(10L, platform = gba)),
            ),
        )
    }

    @Test
    fun cueFingerprintIncludesEveryReferencedPhysicalCompanionOnlyOnce() {
        val bin = File(root, "disc.bin").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val cue = File(root, "disc.cue").apply {
            writeText(
                """
                FILE "disc.bin" BINARY
                  TRACK 01 MODE1/2352
                    INDEX 01 00:00:00
                  TRACK 02 MODE1/2352
                    INDEX 01 10:00:00
                """.trimIndent(),
            )
        }

        val set = GameLibraryReconciler.fingerprintDiscSet(cue, psx)

        assertNotNull(set)
        assertEquals(1, set!!.primary.size)
        assertEquals(1, set.companions.size)
        assertEquals(bin.length(), set.companions.single().sizeBytes)
    }

    @Test
    fun m3uFingerprintRequiresEveryDiscCueAndCompanion() {
        File(root, "disc1.bin").writeBytes(byteArrayOf(1, 2))
        File(root, "disc2.bin").writeBytes(byteArrayOf(3, 4))
        File(root, "disc1.cue").writeText("FILE \"disc1.bin\" BINARY\n TRACK 01 MODE1/2352\n INDEX 01 00:00:00")
        File(root, "disc2.cue").writeText("FILE \"disc2.bin\" BINARY\n TRACK 01 MODE1/2352\n INDEX 01 00:00:00")
        val playlist = File(root, "game.m3u").apply { writeText("disc1.cue\ndisc2.cue\n") }

        val set = GameLibraryReconciler.fingerprintDiscSet(playlist, psx)

        assertNotNull(set)
        assertEquals(2, set!!.primary.size)
        assertEquals(2, set.companions.size)
    }

    @Test
    fun missingCueCompanionDefersFingerprinting() {
        val cue = File(root, "disc.cue").apply {
            writeText("FILE \"missing.bin\" BINARY\n TRACK 01 MODE1/2352\n INDEX 01 00:00:00")
        }

        assertNull(GameLibraryReconciler.fingerprintDiscSet(cue, psx))
    }

    @Test
    fun gdiAndCcdAreNotFingerprintedWithoutTheirOwnParsers() {
        val gdi = File(root, "disc.gdi").apply { writeText("1\n1 0 4 2352 track01.bin 0") }
        val ccd = File(root, "disc.ccd").apply { writeText("[CloneCD]\nVersion=3") }

        assertNull(GameLibraryReconciler.fingerprintDiscSet(gdi, dreamcast))
        assertNull(GameLibraryReconciler.fingerprintDiscSet(ccd, saturn))
    }

    @Test
    fun unsupportedGdiAndCcdDescriptorsAreDeferred() {
        assertEquals(false, GameLibraryReconciler.supportsAutomaticDiscDescriptor("gdi"))
        assertEquals(false, GameLibraryReconciler.supportsAutomaticDiscDescriptor("ccd"))
        assertEquals(true, GameLibraryReconciler.supportsAutomaticDiscDescriptor("cue"))
        assertEquals(true, GameLibraryReconciler.supportsAutomaticDiscDescriptor("chd"))
    }

    @Test
    fun completeMultiDiscSetMatchesOnlyWhenEveryDiscMatches() {
        val localPrimary = listOf(local(100L, "disc-one"), local(200L, "disc-two"))
        val game = remoteGame(30L, platform = psx).copy(
            associatedFiles = listOf(
                associated(31L, "Disc 1.cue", 100L, "disc-one"),
                associated(32L, "Disc 2.cue", 200L, "disc-two"),
            ),
        )
        assertEquals(
            listOf(30L),
            GameLibraryReconciler.matchingDiscSetRomIds(localPrimary, emptyList(), psx, listOf(game)),
        )
    }

    @Test
    fun incompleteMultiDiscSetIsNotAssociated() {
        val localPrimary = listOf(local(100L, "disc-one"), local(200L, "disc-two"))
        val game = remoteGame(30L, platform = psx).copy(
            associatedFiles = listOf(associated(31L, "Disc 1.cue", 100L, "disc-one")),
        )
        assertEquals(
            emptyList<Long>(),
            GameLibraryReconciler.matchingDiscSetRomIds(localPrimary, emptyList(), psx, listOf(game)),
        )
    }

    @Test
    fun missingHashOnAnyRemoteDiscPreventsAssociation() {
        val primary = listOf(local(100L, "disc-one"), local(200L, "disc-two"))
        val game = remoteGame(30L, platform = psx).copy(
            associatedFiles = listOf(
                associated(31L, "Disc 1.cue", 100L, "disc-one"),
                associated(32L, "Disc 2.cue", 200L, null),
            ),
        )
        assertEquals(
            emptyList<Long>(),
            GameLibraryReconciler.matchingDiscSetRomIds(primary, emptyList(), psx, listOf(game)),
        )
    }

    @Test
    fun cueCompanionsMustAlsoMatch() {
        val localPrimary = listOf(local(100L, "disc-one"))
        val localCompanions = listOf(local(500L, "track-bin"))
        val game = remoteGame(30L, platform = psx).copy(
            associatedFiles = listOf(
                associated(31L, "Disc 1.cue", 100L, "disc-one"),
                associated(32L, "Disc 1.bin", 500L, "different-track"),
            ),
        )
        assertEquals(
            emptyList<Long>(),
            GameLibraryReconciler.matchingDiscSetRomIds(localPrimary, localCompanions, psx, listOf(game)),
        )
    }

    @Test
    fun extraRemoteCueCompanionPreventsAssociation() {
        val localPrimary = listOf(local(100L, "disc-one"))
        val localCompanions = listOf(local(500L, "track-bin"))
        val game = remoteGame(30L, platform = psx).copy(
            associatedFiles = listOf(
                associated(31L, "Disc 1.cue", 100L, "disc-one"),
                associated(32L, "Disc 1.bin", 500L, "track-bin"),
                associated(33L, "Extra.bin", 600L, "extra-track"),
            ),
        )
        assertEquals(
            emptyList<Long>(),
            GameLibraryReconciler.matchingDiscSetRomIds(localPrimary, localCompanions, psx, listOf(game)),
        )
    }

    @Test
    fun identicalDiscSetInDifferentRemoteRecordsRemainsAmbiguous() {
        val primary = listOf(local(100L, "disc-one"))
        val first = remoteGame(30L, platform = psx).copy(
            associatedFiles = listOf(associated(31L, "Disc 1.cue", 100L, "disc-one")),
        )
        val second = first.copy(id = 40L)
        assertEquals(
            listOf(30L, 40L),
            GameLibraryReconciler.matchingDiscSetRomIds(primary, emptyList(), psx, listOf(first, second)),
        )
    }
}
