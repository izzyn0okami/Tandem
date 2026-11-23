package app.lawnchair.gamemode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaveSyncPolicyTest {
    @Test
    fun rommAccountIdentityIsStableForTheSameUserAndDifferentAcrossAccounts() {
        val firstFingerprint = SaveSyncPolicy.accountFingerprint("https://romm.example", 41L)
        val sameUserAfterTokenRotation = SaveSyncPolicy.accountFingerprint("https://romm.example", 41L)
        val otherUser = SaveSyncPolicy.accountFingerprint("https://romm.example", 42L)

        assertEquals(firstFingerprint, sameUserAfterTokenRotation)
        assertFalse(firstFingerprint == otherUser)
    }

    @Test
    fun deviceSyncRegistrationIdentityIsStablePerServerAndAccount() {
        val accountA = SaveSyncPolicy.deviceSyncStoreKey("https://romm.example", 41L)
        val sameAccount = SaveSyncPolicy.deviceSyncStoreKey("https://romm.example", 41L)
        val otherAccount = SaveSyncPolicy.deviceSyncStoreKey("https://romm.example", 42L)
        val otherServer = SaveSyncPolicy.deviceSyncStoreKey("https://other.example", 41L)

        assertEquals(accountA, sameAccount)
        assertFalse(accountA == otherAccount)
        assertFalse(accountA == otherServer)
    }

    @Test
    fun legacyAccountLinksMigrateOnlyWhenTheOriginalAccountIsProven() {
        assertTrue(SaveSyncPolicy.canMigrateAccountLinks(true, 41L, 41L, "old", "new"))
        assertFalse(SaveSyncPolicy.canMigrateAccountLinks(true, 41L, 42L, "old", "new"))
        assertTrue(SaveSyncPolicy.canMigrateAccountLinks(true, -1L, 41L, "same-token", "same-token"))
        assertFalse(SaveSyncPolicy.canMigrateAccountLinks(true, -1L, 41L, "old-token", "new-token"))
        assertFalse(SaveSyncPolicy.canMigrateAccountLinks(false, -1L, 41L, "same-token", "same-token"))
    }

    @Test
    fun rommGameStorageIdentityIsIsolatedByAccount() {
        val accountA = SaveSyncPolicy.rommGameIdentity("https://romm.example", "account-a", 7L)
        val accountB = SaveSyncPolicy.rommGameIdentity("https://romm.example", "account-b", 7L)

        assertEquals(accountA, SaveSyncPolicy.rommGameIdentity("https://romm.example", "account-a", 7L))
        assertFalse(accountA == accountB)
    }

    @Test
    fun quickStateMetadataIsIsolatedByCoreWhileSramMetadataIsShared() {
        assertEquals(
            "states:local-game-identity:core-a",
            SaveSyncPolicy.metadataKey(RemoteFileKind.State, "local-game-identity", "core-a"),
        )
        assertEquals(
            "states:local-game-identity:core-b",
            SaveSyncPolicy.metadataKey(RemoteFileKind.State, "local-game-identity", "core-b"),
        )
        assertEquals(
            "saves:local-game-identity",
            SaveSyncPolicy.metadataKey(RemoteFileKind.Save, "local-game-identity", "core-a"),
        )
        assertEquals(
            "saves:local-game-identity",
            SaveSyncPolicy.metadataKey(RemoteFileKind.Save, "local-game-identity", "core-b"),
        )
        assertEquals(
            "states:local-game-identity:core-a:remote_hash",
            SaveSyncPolicy.remoteHashKey(RemoteFileKind.State, "local-game-identity", "core-a"),
        )
    }

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
    fun sramAcceptsTheLatestSaveFromAnotherTandemCore() {
        val remotes = listOf(
            RemoteFile(10L, "tandem_pcsx_rearmed", 1_000L),
            RemoteFile(11L, "tandem_swanstation", 900L),
            RemoteFile(12L, "duckstation", 2_000L),
        )

        assertEquals(10L, SaveSyncPolicy.newestCompatibleRemote(RemoteFileKind.Save, remotes, "swanstation")?.id)
    }

    @Test
    fun quickStatesRemainRestrictedToTheirOriginalCore() {
        val remotes = listOf(
            RemoteFile(10L, "tandem_pcsx_rearmed", 2_000L),
            RemoteFile(11L, "tandem_swanstation", 1_000L),
        )

        assertEquals(11L, SaveSyncPolicy.newestCompatibleRemote(RemoteFileKind.State, remotes, "swanstation")?.id)
    }

    @Test
    fun unrelatedEmulatorSavesAreNotImported() {
        val remotes = listOf(RemoteFile(10L, "duckstation", 2_000L))

        assertEquals(null, SaveSyncPolicy.newestCompatibleRemote(RemoteFileKind.Save, remotes, "swanstation"))
    }

    @Test
    fun sramWithoutServerContentHashIsDownloadedForVerification() {
        assertTrue(SaveSyncPolicy.shouldVerifyRemoteContent(RemoteFileKind.Save, null, "old-hash"))
    }

    @Test
    fun sramWithoutStoredRemoteBaselineIsDownloadedEvenWhenServerProvidesHash() {
        assertTrue(SaveSyncPolicy.shouldVerifyRemoteContent(RemoteFileKind.Save, "server-hash", null))
    }

    @Test
    fun sramWithKnownServerHashDoesNotNeedContentDownload() {
        assertFalse(SaveSyncPolicy.shouldVerifyRemoteContent(RemoteFileKind.Save, "server-hash", "server-hash"))
    }

    @Test
    fun quickStateWithoutServerContentHashUsesItsVersionMetadata() {
        assertFalse(SaveSyncPolicy.shouldVerifyRemoteContent(RemoteFileKind.State, null, null))
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
    fun deferredWorkerSyncIsRetriedUntilItCanComplete() {
        assertTrue(SaveSyncPolicy.shouldRetryWorker(syncCompleted = false))
        assertFalse(SaveSyncPolicy.shouldRetryWorker(syncCompleted = true))
    }

    @Test
    fun uploadRequiresExistingNonEmptyChangedFile() {
        assertFalse(SaveSyncPolicy.shouldUpload(false, 10L, "new", "old"))
        assertFalse(SaveSyncPolicy.shouldUpload(true, 0L, "new", "old"))
        assertFalse(SaveSyncPolicy.shouldUpload(true, 10L, "same", "same"))
        assertTrue(SaveSyncPolicy.shouldUpload(true, 10L, "new", "old"))
        assertTrue(SaveSyncPolicy.shouldUpload(true, 10L, "first", null))
    }
    @Test
    fun workerOnlyRunsWithTheOriginatingRomMAccount() {
        assertTrue(SaveSyncPolicy.isSameSyncAccount("account-a", "account-a"))
        assertFalse(SaveSyncPolicy.isSameSyncAccount("account-a", "account-b"))
        assertFalse(SaveSyncPolicy.isSameSyncAccount("account-a", null))
        assertFalse(SaveSyncPolicy.isSameSyncAccount(null, "account-a"))
    }

}
