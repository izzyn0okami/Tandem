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
        remoteContentHash: String? = null,
        lastRemoteContentHash: String? = null,
    ): Boolean {
        if (!remoteContentHash.isNullOrBlank() && !lastRemoteContentHash.isNullOrBlank()) {
            return remoteContentHash != lastRemoteContentHash
        }
        return remoteUpdatedAtMs != lastRemoteUpdatedAtMs || remoteId != lastRemoteId
    }

    /** SRAM é compartilhável entre núcleos Tandem; estados rápidos exigem o mesmo núcleo. */
    fun isCompatibleRemote(kind: RemoteFileKind, emulator: String?, coreId: String): Boolean =
        when (kind) {
            RemoteFileKind.Save -> emulator?.startsWith("tandem_") == true
            RemoteFileKind.State -> emulator == "tandem_$coreId"
        }

    fun newestCompatibleRemote(kind: RemoteFileKind, remotes: List<RemoteFile>, coreId: String): RemoteFile? =
        remotes.filter { isCompatibleRemote(kind, it.emulator, coreId) }
            .maxWithOrNull(compareBy<RemoteFile> { it.updatedAtMs }.thenBy { it.id })

    /** SRAM sem hash fornecido pelo servidor precisa ser baixado temporariamente para verificação. */
    fun shouldVerifyRemoteContent(kind: RemoteFileKind, remoteContentHash: String?): Boolean =
        kind == RemoteFileKind.Save && remoteContentHash.isNullOrBlank()

    /** Um núcleo ainda sem baseline recebe o head remoto; ausência local também pede restauração. */
    fun shouldApplyRemote(remoteChanged: Boolean, localExists: Boolean, appliedHash: String?): Boolean =
        remoteChanged || !localExists || appliedHash == null

    /** Só preserva local quando ele não é a versão que já havia sido aplicada neste núcleo. */
    fun shouldBackupLocal(localExists: Boolean, localHash: String?, appliedHash: String?): Boolean =
        localExists && (appliedHash == null || localHash != appliedHash)

    fun shouldUpload(
        fileExists: Boolean,
        fileLength: Long,
        currentHash: String?,
        confirmedHash: String?,
    ): Boolean = fileExists && fileLength > 0L && currentHash != confirmedHash
}
