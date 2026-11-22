package app.lawnchair.gamemode

/** Decisões puras da sincronização, separadas de Android/SharedPreferences para testes JVM. */
internal object SaveSyncPolicy {
    const val SESSION_TIMEOUT_MS = 120_000L

    internal data class StatusResolution(
        val status: SaveSyncStatus,
        val resetStartedAt: Boolean = false,
    )

    fun resolveStatus(
        storedStatus: String?,
        startedAt: Long,
        now: Long,
    ): StatusResolution = when (storedStatus) {
        "synced" -> StatusResolution(SaveSyncStatus.SYNCED)

        "syncing" -> {
            if (startedAt > 0L && now - startedAt > SESSION_TIMEOUT_MS) {
                StatusResolution(SaveSyncStatus.PENDING, resetStartedAt = true)
            } else {
                StatusResolution(SaveSyncStatus.SYNCING)
            }
        }

        else -> StatusResolution(SaveSyncStatus.PENDING)
    }

    fun isSessionActive(lastActiveAt: Long, now: Long): Boolean = lastActiveAt > 0L && now - lastActiveAt < SESSION_TIMEOUT_MS

    fun isRemoteVersionChanged(
        remoteUpdatedAtMs: Long,
        remoteId: Long,
        lastRemoteUpdatedAtMs: Long,
        lastRemoteId: Long,
    ): Boolean = remoteUpdatedAtMs != lastRemoteUpdatedAtMs || remoteId != lastRemoteId

    fun shouldUpload(
        fileExists: Boolean,
        fileLength: Long,
        currentHash: String?,
        confirmedHash: String?,
    ): Boolean = fileExists && fileLength > 0L && currentHash != confirmedHash
}
