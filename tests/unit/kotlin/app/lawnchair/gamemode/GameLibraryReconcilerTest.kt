package app.lawnchair.gamemode

import org.junit.Assert.assertEquals
import org.junit.Test

class GameLibraryReconcilerTest {

    private val snes = checkNotNull(GamePlatforms.bySlug("snes"))
    private val gba = checkNotNull(GamePlatforms.bySlug("gba"))

    private fun remoteGame(
        id: Long,
        platform: GamePlatform = snes,
        hash: String? = "verified-sha1",
    ) = RemoteGame(
        id = id,
        title = "Super Mario World",
        platform = platform,
        fileName = "Super Mario World.sfc",
        sizeBytes = 1024L,
        coverPath = null,
        associatedFiles = listOf(
            RemoteAssociatedFile(
                id = id + 1000L,
                fileName = "Super Mario World.sfc",
                sizeBytes = 1024L,
                category = "game",
                isTopLevel = true,
                sha1Hash = hash,
            ),
        ),
    )

    @Test
    fun exactHashOnSamePlatformFindsRemoteRomId() {
        assertEquals(
            listOf(10L),
            GameLibraryReconciler.matchingRomIds("verified-sha1", "snes", listOf(remoteGame(10L))),
        )
    }

    @Test
    fun matchingTitleAndSizeWithoutHashDoesNotAssociate() {
        assertEquals(
            emptyList<Long>(),
            GameLibraryReconciler.matchingRomIds("verified-sha1", "snes", listOf(remoteGame(10L, hash = null))),
        )
    }

    @Test
    fun exactHashOnAnotherPlatformDoesNotAssociate() {
        assertEquals(
            emptyList<Long>(),
            GameLibraryReconciler.matchingRomIds("verified-sha1", "snes", listOf(remoteGame(10L, platform = gba))),
        )
    }

    @Test
    fun identicalHashInDifferentRemoteRecordsRemainsAmbiguous() {
        assertEquals(
            listOf(10L, 11L),
            GameLibraryReconciler.matchingRomIds(
                "verified-sha1",
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
            GameLibraryReconciler.matchingRomIds("verified-sha1", "snes", listOf(game)),
        )
    }
}
