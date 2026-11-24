package app.lawnchair.gamemode

import org.junit.Assert.assertEquals
import org.junit.Test

class GameLibraryReconcilerTest {

    private val snes = checkNotNull(GamePlatforms.bySlug("snes"))
    private val gba = checkNotNull(GamePlatforms.bySlug("gba"))
    private val psx = checkNotNull(GamePlatforms.bySlug("psx"))

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
