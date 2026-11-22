package app.lawnchair.gamemode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSyncPolicyTest {
    @Test
    fun confirmedSyncRemainsSynced() {
        val result = SaveSyncPolicy.resolveStatus("synced", 0L, 500_000L)

        assertEquals(SaveSyncStatus.SYNCED, result.status)
        assertFalse(result.resetStartedAt)
    }

    @Test
    fun missingOrUnknownStatusDefaultsToPending() {
        assertEquals(SaveSyncStatus.PENDING, SaveSyncPolicy.resolveStatus(null, 0L, 10L).status)
        assertEquals(SaveSyncStatus.PENDING, SaveSyncPolicy.resolveStatus("unexpected", 0L, 10L).status)
    }

    @Test
    fun activeSyncRemainsSyncingBeforeTimeout() {
        val result = SaveSyncPolicy.resolveStatus("syncing", 10_000L, 10_000L + SaveSyncPolicy.SESSION_TIMEOUT_MS)

        assertEquals(SaveSyncStatus.SYNCING, result.status)
        assertFalse(result.resetStartedAt)
    }

    @Test
    fun staleSyncReturnsToPendingAfterTimeout() {
        val result = SaveSyncPolicy.resolveStatus("syncing", 10_000L, 10_001L + SaveSyncPolicy.SESSION_TIMEOUT_MS)

        assertEquals(SaveSyncStatus.PENDING, result.status)
        assertTrue(result.resetStartedAt)
    }

    @Test
    fun sessionIsActiveOnlyWithinHeartbeatWindow() {
        assertTrue(SaveSyncPolicy.isSessionActive(10_000L, 10_001L))
        assertFalse(SaveSyncPolicy.isSessionActive(10_000L, 10_000L + SaveSyncPolicy.SESSION_TIMEOUT_MS))
        assertFalse(SaveSyncPolicy.isSessionActive(0L, 10_001L))
    }

    @Test
    fun remoteVersionChangesWhenRecordIdChangesEvenIfTimestampMatches() {
        assertTrue(SaveSyncPolicy.isRemoteVersionChanged(1_000L, 24L, 1_000L, 23L))
    }

    @Test
    fun remoteVersionChangesWhenTimestampChangesForSameRecord() {
        assertTrue(SaveSyncPolicy.isRemoteVersionChanged(1_001L, 23L, 1_000L, 23L))
    }

    @Test
    fun remoteVersionIsUnchangedOnlyWhenTimestampAndRecordIdMatch() {
        assertFalse(SaveSyncPolicy.isRemoteVersionChanged(1_000L, 23L, 1_000L, 23L))
    }

    @Test
    fun remoteContentHashDetectsReplacementEvenWhenIdAndTimestampMatch() {
        assertTrue(SaveSyncPolicy.isRemoteVersionChanged(1_000L, 23L, 1_000L, 23L, "hash-b", "hash-a"))
    }

    @Test
    fun matchingRemoteContentHashIsStableAcrossTimestampChanges() {
        assertFalse(SaveSyncPolicy.isRemoteVersionChanged(1_001L, 23L, 1_000L, 23L, "same-hash", "same-hash"))
    }

    @Test
    fun aCoreWithoutAnAppliedBaselineReceivesTheRemoteSave() {
        assertTrue(SaveSyncPolicy.shouldApplyRemote(false, true, null))
    }

    @Test
    fun aMissingLocalSaveIsRestoredEvenWhenTheRemoteVersionIsKnown() {
        assertTrue(SaveSyncPolicy.shouldApplyRemote(false, false, "known-hash"))
    }

    @Test
    fun aChangedLocalSaveIsKeptWhenTheRemoteHeadDidNotChange() {
        assertFalse(SaveSyncPolicy.shouldApplyRemote(false, true, "previously-applied-hash"))
    }

    @Test
    fun aChangedRemoteHeadIsApplied() {
        assertTrue(SaveSyncPolicy.shouldApplyRemote(true, true, "previously-applied-hash"))
    }

    @Test
    fun localFileIsBackedUpWhenItHasNoBaselineOrDiffersFromAppliedVersion() {
        assertTrue(SaveSyncPolicy.shouldBackupLocal(true, "local", null))
        assertTrue(SaveSyncPolicy.shouldBackupLocal(true, "local-new", "local-old"))
        assertFalse(SaveSyncPolicy.shouldBackupLocal(true, "same", "same"))
        assertFalse(SaveSyncPolicy.shouldBackupLocal(false, null, null))
    }

    @Test
    fun uploadRequiresExistingNonEmptyChangedFile() {
        assertFalse(SaveSyncPolicy.shouldUpload(false, 10L, "new", "old"))
        assertFalse(SaveSyncPolicy.shouldUpload(true, 0L, "new", "old"))
        assertFalse(SaveSyncPolicy.shouldUpload(true, 10L, "same", "same"))
        assertTrue(SaveSyncPolicy.shouldUpload(true, 10L, "new", "old"))
        assertTrue(SaveSyncPolicy.shouldUpload(true, 10L, "first", null))
    }
}
