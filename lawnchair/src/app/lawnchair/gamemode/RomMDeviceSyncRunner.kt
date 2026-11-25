package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resultado de uma execução Device Sync para o ciclo de sessão ou ação manual. */
data class DeviceSyncExecutionResult(
    val romId: Long,
    val uploaded: Int,
    val downloaded: Int,
    val noOp: Int,
    val conflictsPreserved: Int,
    val deletesIgnored: Int,
    val unknownIgnored: Int,
    val failed: Int,
)

/**
 * Executor conservador do Device Sync RomM.
 *
 * Só opera a save do jogo explicitamente vinculado e fora de uma sessão ativa. O
 * GameSessionCoordinator usa este executor antes e depois da partida; a tela também pode
 * acioná-lo manualmente. Conflitos ficam preservados; operações delete e desconhecidas nunca são aplicadas.
 * O inventário tipado escolhe somente save de núcleo ou cartão PSX em .srm; estados rápidos e progresso RA ficam locais.
 */
object RomMDeviceSyncRunner {

    suspend fun synchronizeLocalSave(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String? = null,
    ): DeviceSyncExecutionResult = withContext(Dispatchers.IO) {
        SaveSync.withExclusiveSyncLock {
            val app = context.applicationContext
            val connection = GameIdentityResolver.connectionSnapshot(app)
                ?: throw IOException("Conecte a mesma conta RomM vinculada a este jogo.")
            val romFile = File(romPath)
            if (!romFile.isFile || !GameIdentityResolver.hasRomMLink(app, romFile)) {
                throw IOException("Este jogo não possui um vínculo RomM confirmado.")
            }
            val romId = GameIdentityResolver.linkedRomId(app, romFile, connection)
                ?: throw IOException("O vínculo deste jogo não corresponde à conta RomM atual.")
            val identity = GameIdentityResolver.cachedIdentity(app, romFile, platformSlug)
                ?: GameIdentityResolver.resolve(app, romFile, platformSlug)
            val sessionKey = "session_active:$identity"
            val activeAt = app.getSharedPreferences("romm_sync", Context.MODE_PRIVATE).getLong(sessionKey, 0L)
            if (SaveSyncPolicy.isSessionActive(activeAt, System.currentTimeMillis())) {
                throw IOException("Feche o jogo antes de sincronizar o save.")
            }
            val deviceId = RomMSettings.deviceSyncDeviceId(app)
                ?: throw IOException("Este dispositivo ainda não está registrado no Device Sync do RomM.")

            val store = SaveStore(
                app,
                coreId,
                romFile,
                platformSlug = platformSlug,
                gameIdentity = identity,
                migrationSourcesOverride = emptyMap(),
            )
            val client = RomMDeviceSyncClient(connection.url, connection.token)
            val emulator = DeviceSyncArtifactPolicy.canonicalEmulator(coreId)
            val snapshotRoot = File(app.filesDir, "device-sync-snapshots")
            if (!snapshotRoot.exists() && !snapshotRoot.mkdirs()) {
                throw IOException("Não foi possível preparar a captura temporária do save.")
            }
            val snapshot = File(snapshotRoot, "${UUID.randomUUID()}.srm")
            try {
                val artifacts = store.inventory()
                val localArtifact = DeviceSyncArtifactPolicy.selectForRomM(artifacts)
                val remoteSlot = localArtifact?.let(DeviceSyncArtifactPolicy::remoteSlot)
                    ?: DeviceSyncArtifactPolicy.remoteSlotForCore(coreId)
                val originalSave = localArtifact?.file ?: store.sramFile
                val localSave = localArtifact?.let { artifact ->
                    artifact.file.copyTo(snapshot, overwrite = false)
                    val before = md5(artifact.file)
                    val captured = md5(snapshot)
                    if (before != captured) throw IOException("O artefato mudou durante a captura; tente novamente.")
                    DeviceSyncProtocolJson.localSave(
                        romId = romId,
                        fileName = romSaveFileName(romFile),
                        emulator = emulator,
                        file = snapshot,
                        updatedAt = Instant.ofEpochMilli(artifact.modifiedAtEpochMs).toString(),
                        remoteSlot = remoteSlot,
                    ) ?: throw IOException("Não foi possível preparar o artefato local.")
                }
                GameLog.log(
                    "Device Sync",
                    "artefatos elegíveis: " + artifacts.joinToString { artifact ->
                        "${artifact.type}/${artifact.slot} ${artifact.format} ${artifact.sizeBytes}B sha256=${artifact.sha256.take(8)}"
                    }.ifBlank { "nenhum" } + "; selecionado=${localArtifact?.type ?: "nenhum"}",
                )
                val initialLocalHash = localSave?.contentHashMd5
                GameLog.log(
                    "Device Sync",
                    "manifesto local: ROM #$romId, slot=$remoteSlot, arquivo=${localSave?.fileName ?: "ausente"}, " +
                        "emulador=${localSave?.emulator ?: "—"}, bytes=${localSave?.fileSizeBytes ?: 0L}, " +
                        "atualizado=${localSave?.updatedAt ?: "—"}, MD5=${localSave?.contentHashMd5?.take(8) ?: "—"}",
                )
                val negotiation = client.negotiate(
                    deviceId = deviceId,
                    saves = listOfNotNull(localSave),
                    romIds = setOf(romId),
                    emulators = null,
                )

                var uploaded = 0
                var downloaded = 0
                var noOp = 0
                var conflicts = 0
                var deletesIgnored = 0
                var unknownIgnored = 0
                var failed = 0
                var extraTransfers = 0

                val scopedOperations = negotiation.operations.filter { it.romId == romId && it.slot == remoteSlot }
                val ignoredOutOfScope = negotiation.operations.size - scopedOperations.size
                if (ignoredOutOfScope > 0) {
                    GameLog.log(
                        "Device Sync",
                        "$ignoredOutOfScope operação(ões) de outros slots/ROMs ignoradas pelo adaptador $remoteSlot.",
                    )
                }
                scopedOperations.forEach { operation ->
                    GameLog.log(
                        "Device Sync",
                        "operação recebida: ação=${operation.action}, ROM #${operation.romId}, slot=${operation.slot ?: "—"}, " +
                            "save_id=${operation.saveId ?: "—"}, arquivo=${operation.fileName ?: "—"}, " +
                            "emulador=${operation.emulator ?: "—"}, atualizado=${operation.serverUpdatedAt ?: "—"}, " +
                            "MD5=${operation.serverContentHash?.take(8) ?: "—"}, motivo=${operation.reason ?: "—"}",
                    )
                    if (
                        operation.saveId != null &&
                        operation.action in setOf(DeviceSyncAction.Upload, DeviceSyncAction.Download, DeviceSyncAction.Conflict) &&
                        !DeviceSyncArtifactPolicy.isCompatibleRemote(coreId, operation.emulator)
                    ) {
                        conflicts++
                        GameLog.log(
                            "Device Sync",
                            "save remoto de emulador sem compatibilidade declarada preservado; remoto=${operation.emulator ?: "—"}, local=$emulator",
                        )
                        return@forEach
                    }
                    try {
                        when (operation.action) {
                            DeviceSyncAction.NoOp -> noOp++

                            DeviceSyncAction.Upload -> {
                                if (localSave == null || operation.slot != remoteSlot) {
                                    throw IOException("Upload sem save local compatível ou fora do slot $remoteSlot.")
                                }
                                if (DeviceSyncArtifactPolicy.isUnbasedUpload(operation)) {
                                    val reconciled = reconcileDivergentSave(
                                        client, operation, deviceId, negotiation.sessionId, romId,
                                        remoteSlot, emulator, romFile, store, originalSave,
                                        localSave, initialLocalHash, snapshot,
                                    )
                                    if (reconciled == null) {
                                        conflicts++
                                        GameLog.log("Device Sync", "reconciliação adiada: timestamps ou hash remoto ausentes; ambos os lados preservados")
                                    } else {
                                        uploaded += reconciled.uploaded
                                        downloaded += reconciled.downloaded
                                        if (reconciled.downloaded > 0) extraTransfers++
                                    }
                                } else {
                                    if (!originalSave.matchesMd5(initialLocalHash)) {
                                        throw IOException("O save local mudou após a negociação; upload cancelado.")
                                    }
                                    val uploadedSave = client.uploadSave(
                                        romId = romId,
                                        deviceId = deviceId,
                                        sessionId = negotiation.sessionId,
                                        slot = remoteSlot,
                                        emulator = emulator,
                                        fileName = romSaveFileName(romFile),
                                        file = snapshot,
                                    )
                                    uploaded++
                                    GameLog.log(
                                        "Device Sync",
                                        "save enviada para ROM #$romId; save_id=${uploadedSave.optLong("id", -1L)}, bytes=${snapshot.length()}",
                                    )
                                }
                            }

                            DeviceSyncAction.Download -> {
                                DeviceSyncProtocolJson.validateDownloadOperation(
                                    operation,
                                    DeviceSyncArtifactPolicy.compatibleEmulators(coreId),
                                    remoteSlot,
                                )
                                val saveId = operation.saveId
                                    ?: throw IOException("O RomM não informou o ID do save para download.")
                                val expectedHash = operation.serverContentHash
                                    ?: throw IOException("O RomM não informou o hash do save para download.")
                                if (localSave != null && initialLocalHash != null &&
                                    !initialLocalHash.equals(expectedHash, ignoreCase = true)
                                ) {
                                    if (!originalSave.matchesMd5(initialLocalHash)) {
                                        throw IOException("O save local mudou após a negociação; arquivamento cancelado.")
                                    }
                                    val localTimestamp = Instant.ofEpochMilli(originalSave.lastModified())
                                    client.uploadSave(
                                        romId = romId,
                                        deviceId = deviceId,
                                        sessionId = negotiation.sessionId,
                                        slot = null,
                                        emulator = emulator,
                                        fileName = archiveSaveFileName(romFile, localTimestamp, initialLocalHash ?: md5(snapshot)),
                                        file = snapshot,
                                    )
                                    uploaded++
                                    extraTransfers++
                                    GameLog.log("Device Sync", "versão local anterior preservada no RomM como arquivo de arquivo (slot nulo)")
                                }
                                if (!originalSave.matchesMd5(initialLocalHash)) {
                                    throw IOException("O save local mudou após a negociação; download cancelado.")
                                }
                                val target = originalSave
                                target.parentFile?.let {
                                    if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível preparar a pasta local de saves.")
                                }
                                val staging = File(target.parentFile, ".${target.name}.${negotiation.sessionId}.$saveId.part")
                                try {
                                    val downloadedHash = client.downloadSaveToStaging(
                                        saveId = saveId,
                                        deviceId = deviceId,
                                        sessionId = negotiation.sessionId,
                                        expectedContentHash = expectedHash,
                                        stagingFile = staging,
                                    )
                                    if (!originalSave.matchesMd5(initialLocalHash)) {
                                        throw IOException("O save local mudou durante o download; arquivo remoto não aplicado.")
                                    }
                                    if (target.isFile && target.length() > 0L) {
                                        store.backupDir.mkdirs()
                                        val backup = File(
                                            store.backupDir,
                                            "device-sync-${System.currentTimeMillis()}-${target.name}",
                                        )
                                        target.copyTo(backup, overwrite = false)
                                        if (sha256(target) != sha256(backup)) {
                                            backup.delete()
                                            throw IOException("Não foi possível verificar o backup do save local.")
                                        }
                                        GameLog.log("Device Sync", "save local preservado em backup antes do download: ${backup.name}")
                                    }
                                    RomMFileReplacement.replaceFile(staging, target)
                                    client.confirmDownloaded(saveId, deviceId, downloadedHash)
                                    downloaded++
                                    GameLog.log(
                                        "Device Sync",
                                        "save remoto aplicado e confirmado; ROM #$romId, save_id=$saveId, bytes=${target.length()}",
                                    )
                                } finally {
                                    staging.delete()
                                }
                            }

                            DeviceSyncAction.Conflict -> {
                                val reconciled = reconcileDivergentSave(
                                    client, operation, deviceId, negotiation.sessionId, romId,
                                    remoteSlot, emulator, romFile, store, originalSave,
                                    localSave, initialLocalHash, snapshot,
                                )
                                if (reconciled == null) {
                                    conflicts++
                                    GameLog.log("Device Sync", "conflito preservado: não foi possível determinar a versão mais recente com segurança")
                                } else {
                                    uploaded += reconciled.uploaded
                                    downloaded += reconciled.downloaded
                                    if (reconciled.downloaded > 0) extraTransfers++
                                }
                            }

                            DeviceSyncAction.Delete -> {
                                deletesIgnored++
                                GameLog.log("Device Sync", "operação delete recusada; save local preservado")
                            }

                            DeviceSyncAction.Unknown -> {
                                unknownIgnored++
                                GameLog.log("Device Sync", "operação desconhecida recusada; save local preservado")
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: RomMHttpException) {
                        if (GameSessionSyncPolicy.isConflictHttpStatus(error.statusCode)) {
                            conflicts++
                            val detail = error.detail?.let { ": $it" }.orEmpty()
                            GameLog.log("Device Sync", "HTTP 409 tratado como conflito; dados preservados$detail")
                        } else {
                            failed++
                            GameLog.error(
                                "Device Sync",
                                "operação ${operation.action} falhou (HTTP ${error.statusCode}) para ROM #$romId",
                                error,
                            )
                        }
                    } catch (error: Exception) {
                        failed++
                        GameLog.error("Device Sync", "operação ${operation.action} falhou para ROM #$romId", error)
                    }
                }

                val completed = (noOp + uploaded + downloaded - extraTransfers).coerceAtLeast(0)
                val failedOperations = failed + conflicts + deletesIgnored + unknownIgnored
                client.complete(negotiation.sessionId, completed, failedOperations)
                GameLog.log(
                    "Device Sync",
                    "sessão ${negotiation.sessionId} concluída; ok=$completed, falhas=$failedOperations",
                )
                DeviceSyncExecutionResult(
                    romId = romId,
                    uploaded = uploaded,
                    downloaded = downloaded,
                    noOp = noOp,
                    conflictsPreserved = conflicts,
                    deletesIgnored = deletesIgnored,
                    unknownIgnored = unknownIgnored,
                    failed = failed,
                )
            } finally {
                snapshot.delete()
            }
        }
    }

    /**
     * Restores a specific historical SRAM version. When the selected version is older than the
     * current server version, publish its bytes as a new active-slot snapshot first. This makes
     * the manual choice durable across devices without rewriting or deleting server history.
     */
    suspend fun restoreRemoteSaveVersion(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String?,
        saveId: Long,
    ): String = withContext(Dispatchers.IO) {
        SaveSync.withExclusiveSyncLock {
            val app = context.applicationContext
            val connection = GameIdentityResolver.connectionSnapshot(app)
                ?: throw IOException("Conecte a conta RomM vinculada a este jogo.")
            val romFile = File(romPath)
            if (!romFile.isFile || !GameIdentityResolver.hasRomMLink(app, romFile)) {
                throw IOException("Este jogo não possui um vínculo RomM confirmado.")
            }
            val romId = GameIdentityResolver.linkedRomId(app, romFile, connection)
                ?: throw IOException("O vínculo RomM não corresponde à conta atual.")
            val deviceId = RomMSettings.deviceSyncDeviceId(app)
                ?: throw IOException("Este dispositivo ainda não está registrado no Device Sync.")
            val identity = GameIdentityResolver.cachedIdentity(app, romFile, platformSlug)
                ?: GameIdentityResolver.resolve(app, romFile, platformSlug)
            val store = SaveStore(
                app,
                coreId,
                romFile,
                platformSlug = platformSlug,
                gameIdentity = identity,
                migrationSourcesOverride = emptyMap(),
            )
            val artifact = DeviceSyncArtifactPolicy.selectForRomM(store.inventory())
            val target = artifact?.file ?: store.sramFile
            val remoteSlot = artifact?.let(DeviceSyncArtifactPolicy::remoteSlot)
                ?: DeviceSyncArtifactPolicy.remoteSlotForCore(coreId)
            val emulator = DeviceSyncArtifactPolicy.canonicalEmulator(coreId)
            val beforeHash = if (target.isFile && target.length() > 0L) md5(target) else null
            val snapshotRoot = File(app.filesDir, "device-sync-snapshots")
            if (!snapshotRoot.exists() && !snapshotRoot.mkdirs()) {
                throw IOException("Não foi possível preparar a área temporária de restauração.")
            }
            val snapshot = File(snapshotRoot, "history-current-${UUID.randomUUID()}.srm")
            val latestStage = File(snapshotRoot, "history-latest-${UUID.randomUUID()}.srm")
            val selectedStage = File(snapshotRoot, "history-selected-${UUID.randomUUID()}.srm")
            var sessionId: Long? = null
            var sessionCompleted = false
            try {
                if (beforeHash != null) {
                    target.copyTo(snapshot, overwrite = false)
                    if (md5(snapshot) != beforeHash) throw IOException("A captura local não passou na verificação de integridade.")
                }
                val localSave = if (beforeHash != null) {
                    DeviceSyncProtocolJson.localSave(
                        romId = romId,
                        fileName = romSaveFileName(romFile),
                        emulator = emulator,
                        file = snapshot,
                        updatedAt = Instant.ofEpochMilli(target.lastModified()).toString(),
                        remoteSlot = remoteSlot,
                    )
                } else {
                    null
                }
                val client = RomMDeviceSyncClient(connection.url, connection.token)
                val negotiation = client.negotiate(
                    deviceId = deviceId,
                    saves = listOfNotNull(localSave),
                    romIds = setOf(romId),
                    emulators = null,
                )
                sessionId = negotiation.sessionId

                val versions = RomMClient(connection.url, connection.token)
                    .listRemoteFiles(RemoteFileKind.Save, romId, remoteSlot)
                    .filter { it.slot == remoteSlot && it.id > 0L }
                    .sortedByDescending { it.updatedAtMs }
                val selected = versions.firstOrNull { it.id == saveId }
                    ?: throw IOException("A versão escolhida não está mais disponível nesse slot.")
                val latest = versions.firstOrNull()
                    ?: throw IOException("O RomM não retornou uma versão ativa para este slot.")
                if (!DeviceSyncArtifactPolicy.isCompatibleRemote(coreId, selected.emulator)) {
                    throw IOException("A versão escolhida pertence a um núcleo sem compatibilidade declarada.")
                }
                if (!DeviceSyncArtifactPolicy.isCompatibleRemote(coreId, latest.emulator)) {
                    throw IOException("A versão ativa do RomM pertence a um núcleo incompatível; restauração cancelada.")
                }
                val latestHash = latest.contentHash?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }
                    ?: throw IOException("O RomM não informou o hash da versão ativa; não é seguro estabelecer o histórico.")
                val selectedHash = selected.contentHash?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}")) }
                    ?: throw IOException("O RomM não informou o hash da versão escolhida; restauração cancelada.")

                val latestDownloadedHash = client.downloadSaveToStaging(
                    saveId = latest.id,
                    deviceId = deviceId,
                    sessionId = negotiation.sessionId,
                    expectedContentHash = latestHash,
                    stagingFile = latestStage,
                )
                client.confirmDownloaded(latest.id, deviceId, latestDownloadedHash)

                val selectedDownloadedHash = if (selected.id == latest.id) {
                    latestStage.copyTo(selectedStage, overwrite = false)
                    latestDownloadedHash
                } else {
                    client.downloadSaveToStaging(
                        saveId = selected.id,
                        deviceId = deviceId,
                        sessionId = negotiation.sessionId,
                        expectedContentHash = selectedHash,
                        stagingFile = selectedStage,
                    )
                }
                if (!selectedDownloadedHash.equals(selectedHash, ignoreCase = true)) {
                    throw IOException("O hash da versão escolhida mudou durante o download.")
                }
                if (beforeHash != null && (!target.isFile || md5(target) != beforeHash)) {
                    throw IOException("A SRAM local mudou durante a restauração; arquivo preservado.")
                }
                if (beforeHash == null && target.exists()) {
                    throw IOException("Uma SRAM local foi criada durante a restauração; arquivo preservado.")
                }

                val restoringOlder = selected.id != latest.id
                if (restoringOlder) {
                    client.uploadSave(
                        romId = romId,
                        deviceId = deviceId,
                        sessionId = negotiation.sessionId,
                        slot = remoteSlot,
                        emulator = emulator,
                        fileName = romSaveFileName(romFile),
                        file = selectedStage,
                    )
                }

                if (beforeHash != null) {
                    store.backupDir.mkdirs()
                    val backup = File(store.backupDir, "manual-history-${System.currentTimeMillis()}-${target.name}")
                    target.copyTo(backup, overwrite = false)
                    if (sha256(target) != sha256(backup)) {
                        backup.delete()
                        throw IOException("O backup da SRAM atual não passou na verificação; restauração cancelada.")
                    }
                }
                target.parentFile?.let { if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível preparar a pasta de SRAM.") }
                RomMFileReplacement.replaceFile(selectedStage, target)
                if (md5(target) != selectedHash) {
                    throw IOException("A SRAM restaurada não passou na verificação final.")
                }
                if (!restoringOlder) {
                    client.confirmDownloaded(selected.id, deviceId, selectedDownloadedHash)
                }
                client.complete(negotiation.sessionId, operationsCompleted = 1, operationsFailed = 0)
                sessionCompleted = true
                val message = if (restoringOlder) {
                    "Versão histórica restaurada e publicada como nova versão ativa no RomM."
                } else {
                    "Versão mais recente restaurada no dispositivo."
                }
                GameLog.log(
                    "Device Sync",
                    "restauração manual concluída: ROM #$romId, slot=$remoteSlot, save_id=$saveId, " +
                        "nova versão ativa=$restoringOlder; backup local preservado=${beforeHash != null}",
                )
                message
            } finally {
                if (sessionId != null && !sessionCompleted) {
                    runCatching { RomMDeviceSyncClient(connection.url, connection.token).complete(sessionId!!, 0, 1) }
                }
                snapshot.delete()
                latestStage.delete()
                selectedStage.delete()
            }
        }
    }

    private data class ReconciliationCounts(val uploaded: Int, val downloaded: Int)

    /**
     * Resolves a divergent first-sync/conflict by timestamps. RomM remains the tie-breaker:
     * equal or unreadable timestamps are not enough to claim that the local file is newer.
     * The losing bytes are retained as an unpaired archival save; the active slot receives
     * the newest version. A remote download is confirmed before uploading local bytes, which
     * establishes this device's baseline without overwriting the server's current save.
     */
    private suspend fun reconcileDivergentSave(
        client: RomMDeviceSyncClient,
        operation: DeviceSyncOperation,
        deviceId: String,
        sessionId: Long,
        romId: Long,
        remoteSlot: String,
        emulator: String,
        romFile: File,
        store: SaveStore,
        originalSave: File,
        localSave: DeviceSyncSave?,
        initialLocalHash: String?,
        snapshot: File,
    ): ReconciliationCounts? {
        val saveId = operation.saveId ?: return null
        val expectedHash = operation.serverContentHash ?: return null
        val localTimestamp = localSave?.updatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        val serverTimestamp = operation.serverUpdatedAt?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return null
        if (!originalSave.matchesMd5(initialLocalHash)) {
            throw IOException("O save local mudou durante a reconciliação; nenhuma versão foi aplicada.")
        }

        val staging = File(snapshot.parentFile, "remote-$sessionId-$saveId.srm")
        try {
            val downloadedHash = client.downloadSaveToStaging(
                saveId = saveId,
                deviceId = deviceId,
                sessionId = sessionId,
                expectedContentHash = expectedHash,
                stagingFile = staging,
            )
            if (!originalSave.matchesMd5(initialLocalHash)) {
                throw IOException("O save local mudou durante o download de reconciliação.")
            }

            if (localTimestamp.isAfter(serverTimestamp)) {
                client.confirmDownloaded(saveId, deviceId, downloadedHash)
                val uploadedSave = client.uploadSave(
                    romId = romId,
                    deviceId = deviceId,
                    sessionId = sessionId,
                    slot = remoteSlot,
                    emulator = emulator,
                    fileName = romSaveFileName(romFile),
                    file = snapshot,
                )
                GameLog.log(
                    "Device Sync",
                    "reconciliação: versão local mais recente; baseline remoto save_id=$saveId confirmado e versão local publicada como save_id=${uploadedSave.optLong("id", -1L)}",
                )
                return ReconciliationCounts(uploaded = 1, downloaded = 0)
            }

            // RomM wins ties. Keep the losing local bytes as a separate, non-paired save.
            client.uploadSave(
                romId = romId,
                deviceId = deviceId,
                sessionId = sessionId,
                slot = null,
                emulator = emulator,
                fileName = archiveSaveFileName(romFile, localTimestamp, initialLocalHash ?: md5(snapshot)),
                file = snapshot,
            )
            if (!originalSave.matchesMd5(initialLocalHash)) {
                throw IOException("O save local mudou antes da aplicação da versão RomM.")
            }
            store.backupDir.mkdirs()
            val backup = File(store.backupDir, "device-sync-conflict-${System.currentTimeMillis()}-${originalSave.name}")
            originalSave.copyTo(backup, overwrite = false)
            if (sha256(originalSave) != sha256(backup)) {
                backup.delete()
                throw IOException("Não foi possível verificar o backup local do conflito.")
            }
            RomMFileReplacement.replaceFile(staging, originalSave)
            client.confirmDownloaded(saveId, deviceId, downloadedHash)
            GameLog.log(
                "Device Sync",
                "reconciliação: versão RomM mais recente (ou empate); versão local arquivada e save_id=$saveId aplicado",
            )
            return ReconciliationCounts(uploaded = 1, downloaded = 1)
        } finally {
            staging.delete()
        }
    }

    private fun romSaveFileName(romFile: File): String = "${romFile.nameWithoutExtension}.srm"

    private fun archiveSaveFileName(romFile: File, timestamp: Instant, contentHash: String): String {
        val tag = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(timestamp)
        // The hash suffix prevents a null-slot archive from colliding with a slotted
        // RomM snapshot that happens to have the same second-level timestamp.
        return "${romFile.nameWithoutExtension} [$tag] - Tandem-${contentHash.take(8)}.srm"
    }

    private fun File.matchesMd5(expected: String?): Boolean {
        if (expected == null) return !isFile || length() <= 0L
        return isFile && length() > 0L && md5(this).equals(expected, ignoreCase = true)
    }

    private fun md5(file: File): String = digest(file, "MD5")

    private fun sha256(file: File): String = digest(file, "SHA-256")

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
