package app.lawnchair.gamemode

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameSessionCoordinatorTest {

    @Test
    fun conflictsBlockPreSessionButDoNotCreateEndlessBackgroundRetries() {
        val result = result(conflicts = 1)

        assertTrue(GameSessionSyncPolicy.blocksSessionStart(result))
        assertFalse(GameSessionSyncPolicy.shouldRetryBackground(result))
    }

    @Test
    fun operationFailuresBlockPreSessionAndRetryInBackground() {
        val result = result(failed = 1)

        assertTrue(GameSessionSyncPolicy.blocksSessionStart(result))
        assertTrue(GameSessionSyncPolicy.shouldRetryBackground(result))
    }

    @Test
    fun ignoredDeleteAndUnknownOperationsBlockPreSessionWithoutRetryingForever() {
        val delete = result(deletesIgnored = 1)
        val unknown = result(unknownIgnored = 1)

        assertTrue(GameSessionSyncPolicy.blocksSessionStart(delete))
        assertTrue(GameSessionSyncPolicy.blocksSessionStart(unknown))
        assertFalse(GameSessionSyncPolicy.shouldRetryBackground(delete))
        assertFalse(GameSessionSyncPolicy.shouldRetryBackground(unknown))
    }

    @Test
    fun successfulNoOpDoesNotBlockOrRetry() {
        val result = result(noOp = 1)

        assertFalse(GameSessionSyncPolicy.blocksSessionStart(result))
        assertFalse(GameSessionSyncPolicy.shouldRetryBackground(result))
    }

    @Test
    fun http409IsClassifiedAsConflictInsteadOfRetryableFailure() {
        assertTrue(GameSessionSyncPolicy.isConflictHttpStatus(409))
        assertFalse(GameSessionSyncPolicy.isConflictHttpStatus(500))
    }

    @Test
    fun oldQueuedSnapshotsCannotSendSramThroughTheStateEndpoint() {
        val selected = SaveSync.stateTransportFiles(
            mapOf(
                RemoteFileKind.Save to File("sram.srm"),
                RemoteFileKind.State to File("state1.state"),
            ),
        )

        assertEquals(setOf(RemoteFileKind.State), selected.keys)
    }

    private fun result(
        noOp: Int = 0,
        conflicts: Int = 0,
        deletesIgnored: Int = 0,
        unknownIgnored: Int = 0,
        failed: Int = 0,
    ) = DeviceSyncExecutionResult(
        romId = 1L,
        uploaded = 0,
        downloaded = 0,
        noOp = noOp,
        conflictsPreserved = conflicts,
        deletesIgnored = deletesIgnored,
        unknownIgnored = unknownIgnored,
        failed = failed,
    )
}
