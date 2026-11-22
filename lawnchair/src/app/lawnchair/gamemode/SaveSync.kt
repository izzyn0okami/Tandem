package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sincroniza saves de bateria e o estado rápido com o RomM, só para jogos baixados do servidor.
 *
 * Regra: antes de jogar, baixa o do servidor se ele mudou e o local não; ao sair, envia o local
 * se mudou. Se os dois mudaram, vence o mais recente e o outro vai para uma pasta de backup.
 * Falhas de rede nunca atrapalham o jogo.
 */
enum class SaveSyncStatus { PENDING, SYNCING, SYNCED }

class SaveRollbackException(message: String, cause: Throwable) : IOException(message, cause)

private data class SaveFileSnapshot(
    val file: File,
    val backup: File?,
    val existed: Boolean,
    val kind: RemoteFileKind,
    val key: String,
    val hash: String?,
    val remote: Long,
    val id: Long,
    val appliedHashKey: String,
    val appliedHash: String?,
    val remoteHash: String?,
)

object SaveSync {

    private val lock = Mutex()

    private const val INVALID_LINK_MESSAGE =
        "O vínculo salvo deste jogo não pôde ser confirmado com a conta RomM atual. " +
            "Reimporte o jogo do servidor ou associe-o novamente antes de sincronizar."

    internal const val WORK_TAG = "romm-save-sync"

    private fun prefs(context: Context) = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)

    private fun statusKey(gameIdentity: String, accountFingerprint: String) = "sync_status:$gameIdentity:$accountFingerprint"

    /** Estado persistente exibido na biblioteca. Só existe para ROM vinculada ao RomM e com saves locais. */
    fun status(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String? = null,
        gameIdentityOverride: String? = null,
    ): SaveSyncStatus? {
        if (!GameIdentityResolver.hasRomMLink(context, File(romPath))) return null
        val gameIdentity = gameIdentityOverride
            ?: GameIdentityResolver.cachedIdentity(context, File(romPath), platformSlug)
            ?: return null
        val accountFingerprint = GameIdentityResolver.originAccountFingerprint(context, File(romPath)) ?: return null
        val currentAccount = GameIdentityResolver.connectionSnapshot(context)?.accountFingerprint
        if (currentAccount != null && currentAccount != accountFingerprint) return SaveSyncStatus.PENDING
        val statusKey = statusKey(gameIdentity, accountFingerprint)
        val store = SaveStore(
            context,
            coreId,
            File(romPath),
            platformSlug = platformSlug,
            gameIdentity = gameIdentity,
            migrationSourcesOverride = emptyMap(),
        )
        if (files(store).none { (_, file) -> file.isFile && file.length() > 0L }) return null
        val prefs = prefs(context)
        val resolution = SaveSyncPolicy.resolveStatus(
            prefs.getString(statusKey, null),
            prefs.getLong("$statusKey:started", 0L),
            System.currentTimeMillis(),
        )
        if (resolution.resetStartedAt) {
            prefs.edit {
                putString(statusKey, "pending")
                remove("$statusKey:started")
            }
        }
        return resolution.status
    }

    /** Marca pendência apenas quando há arquivo local diferente da última versão confirmada. */
    fun markPending(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String? = null,
        gameIdentityOverride: String? = null,
    ) {
        if (!GameIdentityResolver.hasRomMLink(context, File(romPath))) return
        val gameIdentity = gameIdentityOverride ?: GameIdentityResolver.resolve(context, File(romPath), platformSlug)
        val accountFingerprint = GameIdentityResolver.originAccountFingerprint(context, File(romPath)) ?: return
        val statusKey = statusKey(gameIdentity, accountFingerprint)
        val store = SaveStore(
            context,
            coreId,
            File(romPath),
            platformSlug = platformSlug,
            gameIdentity = gameIdentity,
            migrationSourcesOverride = emptyMap(),
        )
        val prefs = prefs(context)
        val changed = files(store).any { (kind, file) ->
            val exists = file.isFile
            val length = if (exists) file.length() else 0L
            val currentHash = if (length > 0L) hash(file) else null
            val key = SaveSyncPolicy.metadataKey(kind, gameIdentity, coreId)
            val confirmedHash = prefs.getString(
                appliedHashKey(kind, gameIdentity, coreId),
                prefs.getString("$key:hash", null),
            )
            SaveSyncPolicy.shouldUpload(
                exists,
                length,
                currentHash,
                confirmedHash,
            )
        }
        if (changed) prefs.edit { putString(statusKey, "pending") }
    }

    /** Liga uma ROM baixada ao jogo correspondente no servidor. */
    fun link(context: Context, romPath: String, romId: Long) {
        prefs(context).edit { putLong("rom:$romPath", romId) }
        GameIdentityResolver.link(context, File(romPath), romId)
    }

    private fun emulatorTag(coreId: String) = "tandem_$coreId"

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun files(store: SaveStore) = listOf(
        RemoteFileKind.Save to store.sramFile,
        RemoteFileKind.State to store.stateFile,
    )

    private fun appliedHashKey(
        kind: RemoteFileKind,
        gameIdentity: String,
        coreId: String,
    ) = "${kind.path}:$gameIdentity:$coreId:applied_hash"

    private fun newestRemoteFile(
        kind: RemoteFileKind,
        remotes: List<RemoteFile>,
        coreId: String,
    ): RemoteFile? = SaveSyncPolicy.newestCompatibleRemote(kind, remotes, coreId)

    /** Move path-keyed metadata only when its old remote ID is proven to belong to this RomM game. */
    private fun migrateLegacyMetadata(
        context: Context,
        romPath: String,
        gameIdentity: String,
        coreId: String,
        kind: RemoteFileKind,
        remotes: List<RemoteFile>,
        romId: Long,
        connection: RomMConnectionSnapshot,
    ) {
        val preferences = prefs(context)
        val newKey = SaveSyncPolicy.metadataKey(kind, gameIdentity, coreId)
        if (preferences.contains("$newKey:id")) return

        val verifiedPaths = GameIdentityResolver.linkedRomPaths(
            context,
            romId,
            connection,
            romPath,
        ).filter { oldPath ->
            val oldKey = SaveSyncPolicy.metadataKey(kind, oldPath, coreId)
            val oldId = preferences.getLong("$oldKey:id", -1L)
            oldId >= 0L && remotes.any {
                it.id == oldId && SaveSyncPolicy.isCompatibleRemote(kind, it.emulator, coreId)
            }
        }
        val oldPath = when {
            romPath in verifiedPaths -> romPath

            verifiedPaths.size == 1 -> verifiedPaths.single()

            else -> {
                if (verifiedPaths.size > 1) {
                    GameLog.log(
                        "saves",
                        "metadados legados ambíguos; migração adiada: tipo=${kind.path}, jogo=$gameIdentity",
                    )
                }
                return
            }
        }
        if (oldPath == gameIdentity) return

        val oldKey = SaveSyncPolicy.metadataKey(kind, oldPath, coreId)
        val oldId = preferences.getLong("$oldKey:id", -1L)
        val oldAppliedKey = appliedHashKey(kind, oldPath, coreId)
        val newAppliedKey = appliedHashKey(kind, gameIdentity, coreId)
        val oldRemoteHashKey = SaveSyncPolicy.remoteHashKey(kind, oldPath, coreId)
        val newRemoteHashKey = SaveSyncPolicy.remoteHashKey(kind, gameIdentity, coreId)
        val values = preferences.all
        preferences.edit {
            (values["$oldKey:hash"] as? String)?.let {
                if (!preferences.contains("$newKey:hash")) putString("$newKey:hash", it)
            }
            (values["$oldKey:remote"] as? Long)?.let {
                if (!preferences.contains("$newKey:remote")) putLong("$newKey:remote", it)
            }
            if (!preferences.contains("$newKey:id")) putLong("$newKey:id", oldId)
            (values[oldAppliedKey] as? String)?.let {
                if (!preferences.contains(newAppliedKey)) putString(newAppliedKey, it)
            }
            (values[oldRemoteHashKey] as? String)?.let {
                if (!preferences.contains(newRemoteHashKey)) putString(newRemoteHashKey, it)
            }
        }
        GameLog.log(
            "saves",
            "metadados legados migrados para identidade estável: tipo=${kind.path}, jogo=$gameIdentity",
        )
    }

    /** Registra que a ROM está em uma sessão ativa, impedindo upload enquanto ela pode mudar. */
    fun markSessionActive(
        context: Context,
        romPath: String,
        platformSlug: String? = null,
        gameIdentityOverride: String? = null,
    ) {
        val identity = gameIdentityOverride ?: GameIdentityResolver.resolve(context, File(romPath), platformSlug)
        prefs(context).edit { putLong(sessionKey(identity), System.currentTimeMillis()) }
    }

    fun markSessionInactive(
        context: Context,
        romPath: String,
        platformSlug: String? = null,
        gameIdentityOverride: String? = null,
    ) {
        val identity = gameIdentityOverride ?: GameIdentityResolver.resolve(context, File(romPath), platformSlug)
        prefs(context).edit { remove(sessionKey(identity)) }
    }

    private fun sessionKey(gameIdentity: String) = "session_active:$gameIdentity"

    private fun isSessionActive(context: Context, gameIdentity: String): Boolean {
        val lastActive = prefs(context).getLong(sessionKey(gameIdentity), 0L)
        return SaveSyncPolicy.isSessionActive(lastActive, System.currentTimeMillis())
    }

    /** Marca a falha do pull como pendente sem confundi-la com sincronização confirmada. */
    fun markSyncPending(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String? = null,
        gameIdentityOverride: String? = null,
    ) {
        if (!GameIdentityResolver.hasRomMLink(context, File(romPath))) return
        val gameIdentity = gameIdentityOverride ?: GameIdentityResolver.resolve(context, File(romPath), platformSlug)
        val accountFingerprint = GameIdentityResolver.originAccountFingerprint(context, File(romPath)) ?: return
        val statusKey = statusKey(gameIdentity, accountFingerprint)
        prefs(context).edit {
            putString(statusKey, "pending")
            remove("$statusKey:started")
        }
    }

    /** Antes de jogar: aguarda o pull terminar e restaura os saves locais se qualquer etapa falhar. */
    suspend fun pull(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String? = null,
        gameIdentityOverride: String? = null,
        migrationSources: Map<String, File>? = null,
    ) {
        val connection = GameIdentityResolver.connectionSnapshot(context)
        if (connection == null) {
            if (
                GameIdentityResolver.hasRomMLink(context, File(romPath)) ||
                GameIdentityResolver.hasAmbiguousContentLink(context, File(romPath))
            ) {
                throw IOException(INVALID_LINK_MESSAGE)
            }
            return
        }
        val linkedRomId = GameIdentityResolver.linkedRomId(context, File(romPath), connection)
        if (linkedRomId == null) {
            if (
                GameIdentityResolver.hasRomMLink(context, File(romPath)) ||
                GameIdentityResolver.hasAmbiguousContentLink(context, File(romPath))
            ) {
                throw IOException(INVALID_LINK_MESSAGE)
            }
            return
        }
        val gameIdentity = gameIdentityOverride ?: GameIdentityResolver.resolve(context, File(romPath), platformSlug)
        val client = RomMClient(connection.url, connection.token)
        val store = SaveStore(
            context,
            coreId,
            File(romPath),
            platformSlug = platformSlug,
            gameIdentity = gameIdentity,
            migrationSourcesOverride = migrationSources,
        )
        // Compartilha o mesmo bloqueio do push: nunca permita pull e push simultâneos.
        lock.withLock {
            val prefs = prefs(context)
            val snapshots = files(store).map { (kind, file) ->
                val existed = file.isFile
                val backup = if (existed) {
                    File(file.parentFile, "${file.name}.pre-pull-${System.nanoTime()}").also {
                        file.copyTo(it, overwrite = true)
                    }
                } else {
                    null
                }
                val key = SaveSyncPolicy.metadataKey(kind, gameIdentity, coreId)
                SaveFileSnapshot(
                    file = file,
                    backup = backup,
                    existed = existed,
                    kind = kind,
                    key = key,
                    hash = prefs.getString("$key:hash", null),
                    remote = prefs.getLong("$key:remote", Long.MIN_VALUE),
                    id = prefs.getLong("$key:id", Long.MIN_VALUE),
                    appliedHashKey = appliedHashKey(kind, gameIdentity, coreId),
                    appliedHash = prefs.getString(appliedHashKey(kind, gameIdentity, coreId), null),
                    remoteHash = prefs.getString(SaveSyncPolicy.remoteHashKey(kind, gameIdentity, coreId), null),
                )
            }
            var preserveSnapshots = false
            try {
                for ((kind, file) in files(store)) {
                    val remotes = client.listRemoteFiles(kind, linkedRomId)
                    migrateLegacyMetadata(context, romPath, gameIdentity, coreId, kind, remotes, linkedRomId, connection)
                    val remote = newestRemoteFile(kind, remotes, coreId) ?: continue
                    val compatibleCount = remotes.count {
                        SaveSyncPolicy.isCompatibleRemote(kind, it.emulator, coreId)
                    }
                    GameLog.log(
                        "saves",
                        "save remoto selecionado: tipo=${kind.path}, id=${remote.id}, núcleo=${remote.emulator}, " +
                            "updatedAt=${remote.updatedAtMs}, candidatos compatíveis=$compatibleCount",
                    )
                    pullOne(context, client, store, kind, file, remote, romPath, coreId, gameIdentity)
                }
            } catch (error: Exception) {
                var restoreFailure: Throwable? = null
                snapshots.forEach { snapshot ->
                    runCatching {
                        if (snapshot.backup != null && snapshot.backup.isFile) {
                            RomMFileReplacement.replaceFile(snapshot.backup, snapshot.file)
                        } else if (!snapshot.existed) {
                            snapshot.file.delete()
                        }
                        prefs.edit {
                            if (snapshot.hash == null) {
                                remove("${snapshot.key}:hash")
                            } else {
                                putString("${snapshot.key}:hash", snapshot.hash)
                            }
                            if (snapshot.remote == Long.MIN_VALUE) {
                                remove("${snapshot.key}:remote")
                            } else {
                                putLong("${snapshot.key}:remote", snapshot.remote)
                            }
                            if (snapshot.id == Long.MIN_VALUE) {
                                remove("${snapshot.key}:id")
                            } else {
                                putLong("${snapshot.key}:id", snapshot.id)
                            }
                            if (snapshot.appliedHash == null) {
                                remove(snapshot.appliedHashKey)
                            } else {
                                putString(snapshot.appliedHashKey, snapshot.appliedHash)
                            }
                            val remoteKey = SaveSyncPolicy.remoteHashKey(snapshot.kind, gameIdentity, coreId)
                            if (snapshot.remoteHash == null) {
                                remove(remoteKey)
                            } else {
                                putString(remoteKey, snapshot.remoteHash)
                            }
                        }
                    }.onFailure {
                        restoreFailure = it
                        GameLog.error("saves", "falha ao restaurar save local após erro no pull", it)
                    }
                }
                if (restoreFailure != null) {
                    // Keep any remaining snapshot files as recovery copies if rollback was incomplete.
                    preserveSnapshots = true
                    throw SaveRollbackException(
                        "A sincronização falhou e não foi possível confirmar a restauração do save local. Não inicie com o save local.",
                        restoreFailure!!,
                    )
                }
                throw error
            } finally {
                if (!preserveSnapshots) snapshots.forEach { it.backup?.delete() }
            }
        }
    }

    private fun pullOne(
        context: Context,
        client: RomMClient,
        store: SaveStore,
        kind: RemoteFileKind,
        file: File,
        remote: RemoteFile,
        romPath: String,
        coreId: String,
        gameIdentity: String,
    ) {
        // Recover the previous save if Android stopped the app during an earlier replacement.
        RomMFileReplacement.recoverFile(file)
        val prefs = prefs(context)
        val key = SaveSyncPolicy.metadataKey(kind, gameIdentity, coreId)
        val appliedKey = appliedHashKey(kind, gameIdentity, coreId)
        val remoteKey = SaveSyncPolicy.remoteHashKey(kind, gameIdentity, coreId)
        val lastRemoteMs = prefs.getLong("$key:remote", 0L)
        val lastRemoteId = prefs.getLong("$key:id", -1L)
        val lastRemoteHash = prefs.getString(remoteKey, null)
        val local = file.takeIf { it.isFile && it.length() > 0 }
        val localHash = local?.let(::hash)
        var appliedHash = prefs.getString(appliedKey, null)
        val legacyHash = prefs.getString("$key:hash", null)

        // Migra o marcador antigo apenas quando o arquivo deste núcleo prova que contém aqueles bytes.
        if (appliedHash == null && localHash != null && localHash == legacyHash) {
            appliedHash = localHash
            prefs.edit { putString(appliedKey, localHash) }
        }

        val temp = File(file.parentFile, "${file.name}.remote")
        val serverContentHash = remote.contentHash
        var downloadedContentHash: String? = null
        var tempReady = false
        try {
            // Sem um hash remoto anterior não há como provar que content_hash corresponde
            // à versão que o aparelho já conhecia. Fazemos uma verificação inicial do conteúdo.
            // Se o servidor não fornece content_hash, a SRAM é verificada em cada inicialização.
            if (SaveSyncPolicy.shouldVerifyRemoteContent(kind, serverContentHash, lastRemoteHash)) {
                client.downloadRemoteFile(kind, remote.id, temp)
                if (temp.length() == 0L) {
                    throw IOException("O servidor RomM retornou um save vazio para ${File(romPath).name}")
                }
                downloadedContentHash = hash(temp)
                tempReady = true
            }
            val comparisonRemoteHash = if (!serverContentHash.isNullOrBlank() && !lastRemoteHash.isNullOrBlank()) {
                serverContentHash
            } else {
                downloadedContentHash ?: serverContentHash
            }
            val comparisonLastRemoteHash = if (!serverContentHash.isNullOrBlank() && !lastRemoteHash.isNullOrBlank()) {
                lastRemoteHash
            } else if (downloadedContentHash != null) {
                lastRemoteHash ?: legacyHash
            } else {
                lastRemoteHash
            }
            val persistedRemoteHash = serverContentHash ?: downloadedContentHash

            val remoteChanged = SaveSyncPolicy.isRemoteVersionChanged(
                remote.updatedAtMs,
                remote.id,
                lastRemoteMs,
                lastRemoteId,
                comparisonRemoteHash,
                comparisonLastRemoteHash,
            )
            val shouldApplyRemote = SaveSyncPolicy.shouldApplyRemote(
                remoteChanged = remoteChanged,
                localExists = local != null,
                appliedHash = appliedHash,
            )

            if (!shouldApplyRemote) {
                // Atualiza a identidade do registro mesmo quando o conteúdo é idêntico.
                // Isso evita que o próximo upload tente atualizar um ID antigo já substituído no RomM.
                prefs.edit {
                    putLong("$key:remote", remote.updatedAtMs)
                    putLong("$key:id", remote.id)
                    persistedRemoteHash?.let { putString(remoteKey, it) } ?: remove(remoteKey)
                }
                if (localHash != appliedHash) {
                    GameLog.log(
                        "saves",
                        "save local mudou desde a última sincronização; servidor sem nova versão; " +
                            "mantendo progresso local para envio posterior",
                    )
                } else {
                    GameLog.log("saves", "save local já corresponde à versão remota conhecida; nenhum download necessário")
                }
                return
            }

            if (SaveSyncPolicy.shouldBackupLocal(local != null, localHash, appliedHash)) {
                // O arquivo de outro núcleo pode existir, mas ainda não ter recebido a versão remota atual.
                // Preserve-o antes de colocar o head remoto no diretório do núcleo que será iniciado.
                backup(store, file)
                GameLog.log(
                    "saves",
                    "save local divergente preservado em backup antes de aplicar a versão remota",
                )
            }

            if (!tempReady) {
                client.downloadRemoteFile(kind, remote.id, temp)
                if (temp.length() == 0L) {
                    throw IOException("O servidor RomM retornou um save vazio para ${File(romPath).name}")
                }
                tempReady = true
                if (downloadedContentHash == null && serverContentHash == null) {
                    downloadedContentHash = hash(temp)
                }
            }
            // Keep the previous local save until the downloaded file is safely promoted.
            RomMFileReplacement.replaceFile(temp, file)
            val downloadedHash = hash(file)
            prefs.edit {
                putString("$key:hash", downloadedHash)
                putString(appliedKey, downloadedHash)
                putLong("$key:remote", remote.updatedAtMs)
                putLong("$key:id", remote.id)
                persistedRemoteHash?.let { putString(remoteKey, it) } ?: remove(remoteKey)
            }
            GameLog.log(
                "saves",
                "save remoto aplicado: tipo=${kind.path}, id=${remote.id}, núcleo destino=$coreId, bytes=${file.length()}",
            )
        } finally {
            temp.delete()
        }
    }

    private fun backup(store: SaveStore, file: File) {
        store.backupDir.mkdirs()
        file.copyTo(File(store.backupDir, "${System.currentTimeMillis()}-${file.name}"), overwrite = true)
    }

    /** Ao sair do jogo: agenda um envio persistente, retomável após encerramento do processo. */
    fun pushAsync(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String? = null,
        gameIdentityOverride: String? = null,
    ) {
        val app = context.applicationContext
        val gameFile = File(romPath)
        if (!gameFile.isFile) return
        val gameIdentity = gameIdentityOverride ?: GameIdentityResolver.resolve(app, gameFile, platformSlug)
        val accountFingerprint = GameIdentityResolver.originAccountFingerprint(app, gameFile) ?: return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<RomMSaveSyncWorker>()
            .setInputData(
                workDataOf(
                    RomMSaveSyncWorker.KEY_ROM_PATH to romPath,
                    RomMSaveSyncWorker.KEY_CORE_ID to coreId,
                    RomMSaveSyncWorker.KEY_PLATFORM to platformSlug,
                    RomMSaveSyncWorker.KEY_GAME_IDENTITY to gameIdentity,
                    RomMSaveSyncWorker.KEY_ACCOUNT_FINGERPRINT to accountFingerprint,
                ),
            )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        val statusKey = statusKey(gameIdentity, accountFingerprint)
        prefs(app).edit {
            putString(statusKey, "pending")
            remove("$statusKey:started")
        }
        WorkManager.getInstance(app).enqueueUniqueWork(
            workName(gameIdentity, coreId, accountFingerprint),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    /** Chamado somente pelo Worker; o mesmo Mutex protege pull e push contra concorrência. */
    internal suspend fun pushFromWorker(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String?,
        gameIdentity: String,
        expectedAccountFingerprint: String?,
        snapshotJobId: String,
    ): Boolean {
        val app = context.applicationContext
        if (expectedAccountFingerprint.isNullOrBlank()) return false
        val connection = GameIdentityResolver.connectionSnapshot(app) ?: return false
        if (!SaveSyncPolicy.isSameSyncAccount(expectedAccountFingerprint, connection.accountFingerprint)) return false
        val activeRomPath = GameIdentityResolver.findRomPath(app, gameIdentity, romPath, platformSlug) ?: return false
        val activeRom = File(activeRomPath)
        val siblingCores = platformSlug
            ?.let(GamePlatforms::bySlug)
            ?.let { CoreRegistry.cores(it) }
            .orEmpty()
            .map { it.id }
        val migrationPlan = GameIdentityResolver.planLegacyMigration(
            app,
            GameFolders.dir(app, FolderKind.Saves),
            activeRom,
            platformSlug,
            listOf(coreId) + siblingCores,
            gameIdentity,
        )
        // A pending ownership decision must block background uploads as well as game launch.
        // Only an explicit user confirmation may copy these legacy bytes into the account namespace.
        if (migrationPlan.ambiguous || migrationPlan.confirmationSources.isNotEmpty()) return false
        val linkedRomId = GameIdentityResolver.linkedRomId(app, activeRom, connection) ?: return false
        val syncClient = RomMClient(connection.url, connection.token)
        lock.withLock {
            // A sessão pode ter sido reaberta, inclusive depois de mover a ROM.
            // Não envie arquivos enquanto o núcleo puder estar atualizando o save.
            if (isSessionActive(app, gameIdentity)) return false
            val statusKey = statusKey(gameIdentity, expectedAccountFingerprint)
            prefs(app).edit {
                putString(statusKey, "syncing")
                putLong("$statusKey:started", System.currentTimeMillis())
            }
            try {
                val snapshotFiles = withContext(Dispatchers.IO) {
                    val store = SaveStore(
                        app,
                        coreId,
                        activeRom,
                        siblingCores,
                        platformSlug,
                        gameIdentity,
                        migrationPlan.sources,
                    )
                    SaveSnapshotStore(File(app.filesDir, "romm-save-queue")).capture(
                        snapshotJobId,
                        files(store).associate { (kind, file) -> kind to file },
                    )
                }
                withContext(Dispatchers.IO) {
                    push(
                        app,
                        syncClient,
                        activeRomPath,
                        coreId,
                        gameIdentity,
                        linkedRomId,
                        connection,
                        snapshotFiles,
                    )
                }
                prefs(app).edit {
                    putString(statusKey, "synced")
                    remove("$statusKey:started")
                }
                SaveSnapshotStore(File(app.filesDir, "romm-save-queue")).delete(snapshotJobId)
                return true
            } catch (error: Exception) {
                prefs(app).edit {
                    putString(statusKey, "pending")
                    remove("$statusKey:started")
                }
                throw error
            }
        }
    }

    private fun workName(gameIdentity: String, coreId: String, accountFingerprint: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$gameIdentity|$coreId|$accountFingerprint".toByteArray(Charsets.UTF_8))
        val key = digest.joinToString("") { "%02x".format(it) }
        return "romm-save-sync:$key"
    }
    private fun push(
        context: Context,
        client: RomMClient,
        romPath: String,
        coreId: String,
        gameIdentity: String,
        romId: Long,
        connection: RomMConnectionSnapshot,
        snapshotFiles: Map<RemoteFileKind, File>,
    ) {
        val prefs = prefs(context)
        for ((kind, file) in snapshotFiles) {
            if (!file.isFile || file.length() == 0L) continue
            val key = SaveSyncPolicy.metadataKey(kind, gameIdentity, coreId)
            val current = hash(file)
            val confirmedHash = prefs.getString(
                appliedHashKey(kind, gameIdentity, coreId),
                prefs.getString("$key:hash", null),
            )
            if (current == confirmedHash) continue
            val storedId = prefs.getLong("$key:id", -1L).takeIf { it >= 0 }
            val remotes = if (storedId == null) client.listRemoteFiles(kind, romId) else emptyList()
            if (storedId == null) {
                migrateLegacyMetadata(
                    context,
                    romPath,
                    gameIdentity,
                    coreId,
                    kind,
                    remotes,
                    romId,
                    connection,
                )
            }
            // A recuperação do Worker é "at least once": se o servidor aceitou um POST
            // antes de o processo morrer, encontre o head atual de qualquer núcleo Tandem
            // para SRAM; estados rápidos continuam vinculados ao núcleo de origem.
            val existing = prefs.getLong("$key:id", -1L).takeIf { it >= 0 }
                ?: newestRemoteFile(kind, remotes, coreId)?.id
            val extension = if (kind == RemoteFileKind.Save) "srm" else "state"
            val uploadName = "${File(romPath).nameWithoutExtension}.$extension"
            val uploaded = try {
                client.uploadRemoteFile(kind, romId, emulatorTag(coreId), existing, file, uploadName)
            } catch (error: RomMHttpException) {
                // Só cria um registro novo quando o RomM confirma que o antigo não existe.
                // Falhas de rede, autenticação ou servidor não podem gerar saves duplicados.
                if (existing == null || error.statusCode != 404) throw error
                client.uploadRemoteFile(kind, romId, emulatorTag(coreId), null, file, uploadName)
            }
            prefs.edit {
                putString("$key:hash", current)
                putString(appliedHashKey(kind, gameIdentity, coreId), current)
                putLong("$key:remote", uploaded.updatedAtMs)
                putLong("$key:id", uploaded.id)
                val remoteKey = SaveSyncPolicy.remoteHashKey(kind, gameIdentity, coreId)
                if (uploaded.contentHash == null) remove(remoteKey) else putString(remoteKey, uploaded.contentHash)
            }
        }
    }
}
