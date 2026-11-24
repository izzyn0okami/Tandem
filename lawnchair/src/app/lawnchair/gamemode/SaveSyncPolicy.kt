package app.lawnchair.gamemode

/** Decisões puras da sincronização, separadas de Android/SharedPreferences para testes JVM. */
internal object SaveSyncPolicy {
    const val SESSION_TIMEOUT_MS = 120_000L

    /** A identidade da conta depende do usuário RomM, nunca do token que pode ser rotacionado. */
    fun accountFingerprint(server: String, userId: Long): String = sha256("$server|user:$userId")

    /** Stable key for a Device Sync ID, isolated by normalized server and RomM user. */
    fun deviceSyncStoreKey(server: String, userId: Long): String {
        require(server.isNotBlank() && userId > 0L)
        return "device:${accountFingerprint(server, userId)}"
    }

    /** Migra vínculos antigos somente quando a mesma conta é comprovada. */
    fun canMigrateAccountLinks(
        sameServer: Boolean,
        previousUserId: Long,
        currentUserId: Long,
        previousToken: String?,
        currentToken: String,
    ): Boolean = sameServer && when {
        previousUserId > 0L -> previousUserId == currentUserId
        else -> !previousToken.isNullOrBlank() && previousToken == currentToken
    }

    /** Namespace físico/metadados de um jogo RomM: instância + conta + ID remoto. */
    fun rommGameIdentity(server: String, accountFingerprint: String, romId: Long): String = "romm-${sha256("$server|$accountFingerprint|$romId")}"

    private fun sha256(value: String): String = java.security.MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    internal data class StatusResolution(
        val status: SaveSyncStatus,
        val resetStartedAt: Boolean = false,
    )

    /** SRAM mantém metadados compartilhados entre núcleos; estados rápidos isolam tudo por coreId. */
    fun metadataKey(kind: RemoteFileKind, gameIdentity: String, coreId: String): String = when (kind) {
        RemoteFileKind.Save -> "${kind.path}:$gameIdentity"
        RemoteFileKind.State -> "${kind.path}:$gameIdentity:$coreId"
    }

    fun remoteHashKey(
        kind: RemoteFileKind,
        gameIdentity: String,
        coreId: String,
    ): String = "${metadataKey(kind, gameIdentity, coreId)}:remote_hash"

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
    fun isCompatibleRemote(kind: RemoteFileKind, emulator: String?, coreId: String): Boolean = when (kind) {
        RemoteFileKind.Save -> emulator?.startsWith("tandem_") == true
        RemoteFileKind.State -> emulator == "tandem_$coreId"
    }

    const val AUTOSAVE_SLOT = "autosave"

    fun newestCompatibleRemote(
        kind: RemoteFileKind,
        remotes: List<RemoteFile>,
        coreId: String,
        slot: String? = null,
    ): RemoteFile? = remotes
        .filter { isCompatibleRemote(kind, it.emulator, coreId) }
        .filter { kind != RemoteFileKind.Save || it.slot == slot }
        .maxWithOrNull(compareBy<RemoteFile> { it.updatedAtMs }.thenBy { it.id })

    /** SRAM sem hash remoto anterior precisa ser baixado para estabelecer uma comparação confiável. */
    fun shouldVerifyRemoteContent(
        kind: RemoteFileKind,
        remoteContentHash: String?,
        lastRemoteContentHash: String?,
    ): Boolean = kind == RemoteFileKind.Save &&
        (remoteContentHash.isNullOrBlank() || lastRemoteContentHash.isNullOrBlank())

    /** Um núcleo ainda sem baseline recebe o head remoto; ausência local também pede restauração. */
    fun shouldApplyRemote(
        remoteChanged: Boolean,
        localExists: Boolean,
        appliedHash: String?,
    ): Boolean = remoteChanged || !localExists || appliedHash == null

    /** Só preserva local quando ele não é a versão que já havia sido aplicada neste núcleo. */
    fun shouldBackupLocal(
        localExists: Boolean,
        localHash: String?,
        appliedHash: String?,
    ): Boolean = localExists && (appliedHash == null || localHash != appliedHash)

    /** A tarefa só pode usar as credenciais da mesma conta/servidor que a criou. */
    fun isSameSyncAccount(expectedFingerprint: String?, currentFingerprint: String?): Boolean = !expectedFingerprint.isNullOrBlank() && expectedFingerprint == currentFingerprint

    /** Um trabalho adiado porque a sessão ainda está ativa deve permanecer na fila do WorkManager. */
    fun shouldRetryWorker(syncCompleted: Boolean): Boolean = !syncCompleted

    fun shouldUpload(
        fileExists: Boolean,
        fileLength: Long,
        currentHash: String?,
        confirmedHash: String?,
    ): Boolean = fileExists && fileLength > 0L && currentHash != confirmedHash
}
