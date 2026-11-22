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

    internal const val WORK_TAG = "romm-save-sync"

    private fun prefs(context: Context) = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)

    private fun statusKey(romPath: String) = "sync_status:$romPath"

    /** Estado persistente exibido na biblioteca. Só existe para ROM vinculada ao RomM e com saves locais. */
    fun status(context: Context, romPath: String, coreId: String): SaveSyncStatus? {
        if (romId(context, romPath) == null) return null
        val store = SaveStore(context, coreId, File(romPath))
        if (files(store).none { (_, file) -> file.isFile && file.length() > 0L }) return null
        val prefs = prefs(context)
        val resolution = SaveSyncPolicy.resolveStatus(
            prefs.getString(statusKey(romPath), null),
            prefs.getLong("${statusKey(romPath)}:started", 0L),
            System.currentTimeMillis(),
        )
        if (resolution.resetStartedAt) {
            prefs.edit {
                putString(statusKey(romPath), "pending")
                remove("${statusKey(romPath)}:started")
            }
        }
        return resolution.status
    }

    /** Marca pendência apenas quando há arquivo local diferente da última versão confirmada. */
    fun markPending(context: Context, romPath: String, coreId: String) {
        if (romId(context, romPath) == null) return
        val store = SaveStore(context, coreId, File(romPath))
        val prefs = prefs(context)
        val changed = files(store).any { (kind, file) ->
            val exists = file.isFile
            val length = if (exists) file.length() else 0L
            val currentHash = if (length > 0L) hash(file) else null
            val key = "${kind.path}:$romPath"
            val confirmedHash = prefs.getString(
                appliedHashKey(kind, romPath, coreId),
                prefs.getString("$key:hash", null),
            )
            SaveSyncPolicy.shouldUpload(
                exists,
                length,
                currentHash,
                confirmedHash,
            )
        }
        if (changed) prefs.edit { putString(statusKey(romPath), "pending") }
    }

    /** Liga uma ROM baixada ao jogo correspondente no servidor. */
    fun link(context: Context, romPath: String, romId: Long) {
        prefs(context).edit { putLong("rom:$romPath", romId) }
    }

    private fun romId(context: Context, romPath: String): Long? = prefs(context).getLong("rom:$romPath", -1L).takeIf { it >= 0 }

    private fun client(context: Context): RomMClient? {
        val url = RomMSettings.url(context) ?: return null
        return RomMClient(url, RomMSettings.token(context))
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

    private fun appliedHashKey(kind: RemoteFileKind, romPath: String, coreId: String) =
        "${kind.path}:$romPath:$coreId:applied_hash"

    private fun remoteHashKey(kind: RemoteFileKind, romPath: String) =
        "${kind.path}:$romPath:remote_hash"

    private fun newestRemoteFile(kind: RemoteFileKind, remotes: List<RemoteFile>, coreId: String): RemoteFile? =
        SaveSyncPolicy.newestCompatibleRemote(kind, remotes, coreId)

    /** Registra que a ROM está em uma sessão ativa, impedindo upload enquanto ela pode mudar. */
    fun markSessionActive(context: Context, romPath: String) {
        prefs(context).edit { putLong(sessionKey(romPath), System.currentTimeMillis()) }
    }

    fun markSessionInactive(context: Context, romPath: String) {
        prefs(context).edit { remove(sessionKey(romPath)) }
    }

    private fun sessionKey(romPath: String) = "session_active:$romPath"

    private fun isSessionActive(context: Context, romPath: String): Boolean {
        val lastActive = prefs(context).getLong(sessionKey(romPath), 0L)
        return SaveSyncPolicy.isSessionActive(lastActive, System.currentTimeMillis())
    }

    /** Marca a falha do pull como pendente sem confundi-la com sincronização confirmada. */
    fun markSyncPending(context: Context, romPath: String) {
        if (romId(context, romPath) == null) return
        prefs(context).edit {
            putString(statusKey(romPath), "pending")
            remove("${statusKey(romPath)}:started")
        }
    }

    /** Antes de jogar: aguarda o pull terminar e restaura os saves locais se qualquer etapa falhar. */
    suspend fun pull(context: Context, romPath: String, coreId: String) {
        val romId = romId(context, romPath) ?: return
        val client = client(context) ?: throw IOException("RomM está conectado, mas não foi possível criar o cliente")
        val store = SaveStore(context, coreId, File(romPath))
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
                val key = "${kind.path}:$romPath"
                SaveFileSnapshot(
                    file = file,
                    backup = backup,
                    existed = existed,
                    kind = kind,
                    key = key,
                    hash = prefs.getString("$key:hash", null),
                    remote = prefs.getLong("$key:remote", Long.MIN_VALUE),
                    id = prefs.getLong("$key:id", Long.MIN_VALUE),
                    appliedHashKey = appliedHashKey(kind, romPath, coreId),
                    appliedHash = prefs.getString(appliedHashKey(kind, romPath, coreId), null),
                    remoteHash = prefs.getString(remoteHashKey(kind, romPath), null),
                )
            }
            var preserveSnapshots = false
            try {
                for ((kind, file) in files(store)) {
                    val remotes = client.listRemoteFiles(kind, romId)
                    val remote = newestRemoteFile(kind, remotes, coreId) ?: continue
                    GameLog.log(
                        "saves",
                        "save remoto selecionado: tipo=${kind.path}, id=${remote.id}, núcleo=${remote.emulator}, " +
                            "updatedAt=${remote.updatedAtMs}, candidatos compatíveis=${remotes.count { SaveSyncPolicy.isCompatibleRemote(kind, it.emulator, coreId) }}",
                    )
                    pullOne(context, client, store, kind, file, remote, romPath, coreId)
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
                            if (snapshot.hash == null) remove("${snapshot.key}:hash") else putString("${snapshot.key}:hash", snapshot.hash)
                            if (snapshot.remote == Long.MIN_VALUE) remove("${snapshot.key}:remote") else putLong("${snapshot.key}:remote", snapshot.remote)
                            if (snapshot.id == Long.MIN_VALUE) remove("${snapshot.key}:id") else putLong("${snapshot.key}:id", snapshot.id)
                            if (snapshot.appliedHash == null) remove(snapshot.appliedHashKey) else putString(snapshot.appliedHashKey, snapshot.appliedHash)
                            val remoteKey = remoteHashKey(snapshot.kind, romPath)
                            if (snapshot.remoteHash == null) remove(remoteKey) else putString(remoteKey, snapshot.remoteHash)
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
    ) {
        // Recover the previous save if Android stopped the app during an earlier replacement.
        RomMFileReplacement.recoverFile(file)
        val prefs = prefs(context)
        val key = "${kind.path}:$romPath"
        val appliedKey = appliedHashKey(kind, romPath, coreId)
        val remoteKey = remoteHashKey(kind, romPath)
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
        var remoteContentHash = remote.contentHash
        var tempReady = false
        try {
            // Algumas versões do RomM não expõem content_hash. Nesse caso, baixa o SRAM
            // temporariamente e calcula o hash real do conteúdo antes de decidir se mudou.
            if (SaveSyncPolicy.shouldVerifyRemoteContent(kind, remoteContentHash)) {
                client.downloadRemoteFile(kind, remote.id, temp)
                if (temp.length() == 0L) {
                    throw IOException("O servidor RomM retornou um save vazio para ${File(romPath).name}")
                }
                remoteContentHash = hash(temp)
                tempReady = true
            }

            val remoteChanged = SaveSyncPolicy.isRemoteVersionChanged(
                remote.updatedAtMs,
                remote.id,
                lastRemoteMs,
                lastRemoteId,
                remoteContentHash,
                lastRemoteHash,
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
                    if (remoteContentHash == null) remove(remoteKey) else putString(remoteKey, remoteContentHash)
                }
                if (localHash != appliedHash) {
                    GameLog.log(
                        "saves",
                        "save local mudou desde a última sincronização; servidor sem nova versão; mantendo progresso local para envio posterior",
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
                if (remoteContentHash == null) remoteContentHash = hash(temp)
            }
            // Keep the previous local save until the downloaded file is safely promoted.
            RomMFileReplacement.replaceFile(temp, file)
            val downloadedHash = hash(file)
            prefs.edit {
                putString("$key:hash", downloadedHash)
                putString(appliedKey, downloadedHash)
                putLong("$key:remote", remote.updatedAtMs)
                putLong("$key:id", remote.id)
                if (remoteContentHash == null) remove(remoteKey) else putString(remoteKey, remoteContentHash)
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
    fun pushAsync(context: Context, romPath: String, coreId: String) {
        val app = context.applicationContext
        if (romId(app, romPath) == null) return
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<RomMSaveSyncWorker>()
            .setInputData(workDataOf(RomMSaveSyncWorker.KEY_ROM_PATH to romPath, RomMSaveSyncWorker.KEY_CORE_ID to coreId))
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .build()
        prefs(app).edit {
            putString(statusKey(romPath), "pending")
            remove("${statusKey(romPath)}:started")
        }
        WorkManager.getInstance(app).enqueueUniqueWork(
            workName(romPath),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request,
        )
    }

    /** Chamado somente pelo Worker; o mesmo Mutex protege pull e push contra concorrência. */
    internal suspend fun pushFromWorker(context: Context, romPath: String, coreId: String): Boolean {
        val app = context.applicationContext
        lock.withLock {
            if (romId(app, romPath) == null) return true
            // A sessão pode ter sido reaberta enquanto o trabalho aguardava rede.
            // Não envie arquivos enquanto o núcleo puder estar atualizando o save.
            if (isSessionActive(app, romPath)) return false
            prefs(app).edit {
                putString(statusKey(romPath), "syncing")
                putLong("${statusKey(romPath)}:started", System.currentTimeMillis())
            }
            try {
                withContext(Dispatchers.IO) { push(app, romPath, coreId) }
                prefs(app).edit {
                    putString(statusKey(romPath), "synced")
                    remove("${statusKey(romPath)}:started")
                }
                return true
            } catch (error: Exception) {
                markSyncPending(app, romPath)
                throw error
            }
        }
    }

    internal fun cancelPending(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(WORK_TAG)
    }

    private fun workName(romPath: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(romPath.toByteArray(Charsets.UTF_8))
        val key = digest.joinToString("") { "%02x".format(it) }
        return "romm-save-sync:$key"
    }
    private fun push(context: Context, romPath: String, coreId: String) {
        val romId = romId(context, romPath) ?: return
        val client = client(context) ?: error("RomM não está configurado para enviar saves")
        val store = SaveStore(context, coreId, File(romPath))
        val prefs = prefs(context)
        for ((kind, file) in files(store)) {
            if (!file.isFile || file.length() == 0L) continue
            val key = "${kind.path}:$romPath"
            val current = hash(file)
            val confirmedHash = prefs.getString(
                appliedHashKey(kind, romPath, coreId),
                prefs.getString("$key:hash", null),
            )
            if (current == confirmedHash) continue
            val storedId = prefs.getLong("$key:id", -1L).takeIf { it >= 0 }
            // A recuperação do Worker é "at least once": se o servidor aceitou um POST
            // antes de o processo morrer, encontre o head atual de qualquer núcleo Tandem
            // para SRAM; estados rápidos continuam vinculados ao núcleo de origem.
            val existing = storedId ?: newestRemoteFile(kind, client.listRemoteFiles(kind, romId), coreId)?.id
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
                putString(appliedHashKey(kind, romPath, coreId), current)
                putLong("$key:remote", uploaded.updatedAtMs)
                putLong("$key:id", uploaded.id)
                val remoteKey = remoteHashKey(kind, romPath)
                if (uploaded.contentHash == null) remove(remoteKey) else putString(remoteKey, uploaded.contentHash)
            }
        }
    }
}
