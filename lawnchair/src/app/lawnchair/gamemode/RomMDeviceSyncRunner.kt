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
        snapshotOverride: File? = null,
        useSnapshotOnly: Boolean = false,
        siblingCores: List<String> = emptyList(),
        migrationSources: Map<String, File> = emptyMap(),
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
                siblingCores = siblingCores,
                platformSlug = platformSlug,
                gameIdentity = identity,
                migrationSourcesOverride = migrationSources,
            )
            val client = RomMDeviceSyncClient(connection.url, connection.token)
            val emulator = DeviceSyncArtifactPolicy.canonicalEmulator(coreId)
            val snapshotRoot = File(app.filesDir, "device-sync-snapshots")
            if (!snapshotRoot.exists() && !snapshotRoot.mkdirs()) {
                throw IOException("Não foi possível preparar a captura temporária do save.")
            }
            val snapshot = File(snapshotRoot, "${UUID.randomUUID()}.${DeviceSyncArtifactPolicy.fileExtensionForCore(coreId)}")
            try {
                val artifacts = store.inventory()
                val localArtifact = DeviceSyncArtifactPolicy.selectForRomM(artifacts)
                val remoteSlot = localArtifact?.let(DeviceSyncArtifactPolicy::remoteSlot)
                    ?: DeviceSyncArtifactPolicy.remoteSlotForCore(coreId)
                val originalSave = localArtifact?.file ?: store.deviceSyncSaveFile
                val initialTargetHash = originalSave.takeIf { it.isFile && it.length() > 0L }?.let(::md5)
                val sourceSnapshot = if (useSnapshotOnly) {
                    snapshotOverride?.takeIf { it.isFile && it.length() > 0L }
                } else {
                    snapshotOverride?.takeIf { it.isFile && it.length() > 0L }
                        ?: localArtifact?.file?.takeIf { it.isFile && it.length() > 0L }
                }
                val localSave = sourceSnapshot?.let { source ->
                    source.copyTo(snapshot, overwrite = false)
                    val before = md5(source)
                    val captured = md5(snapshot)
                    if (before != captured) throw IOException("O artefato mudou durante a captura; tente novamente.")
                    DeviceSyncProtocolJson.localSave(
                        romId = romId,
                        fileName = romSaveFileName(romFile, localArtifact?.format ?: CoreSaveAdapterRegistry.resolve(coreId).fileExtension),
                        emulator = emulator,
                        file = snapshot,
                        updatedAt = Instant.ofEpochMilli(localArtifact?.modifiedAtEpochMs ?: source.lastModified()).toString(),
                        remoteSlot = remoteSlot,
                        expectedExtension = DeviceSyncArtifactPolicy.fileExtensionForCore(coreId),
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
                                if (localSave == null && initialTargetHash != null) {
                                    conflicts++
                                    GameLog.log(
                                        "Device Sync",
                                        "upload adiado: um save foi criado após a captura vazia da fila; arquivo ativo preservado",
                                    )
                                    return@forEach
                                }
                                if (localSave == null || operation.slot != remoteSlot) {
                                    throw IOException("Upload sem save local compatível ou fora do slot $remoteSlot.")
                                }
                                if (DeviceSyncArtifactPolicy.hasSameContent(initialLocalHash, operation.serverContentHash)) {
                                    val saveId = operation.saveId
                                        ?: throw IOException("O RomM não informou o ID do save para confirmar o baseline.")
                                    val expectedHash = operation.serverContentHash
                                        ?: throw IOException("O RomM não informou o hash do save para confirmar o baseline.")
                                    val baselineStage = File(snapshot.parentFile, "baseline-$saveId-${UUID.randomUUID()}.srm")
                                    try {
                                        val confirmedHash = client.downloadSaveToStaging(
                                            saveId = saveId,
                                            deviceId = deviceId,
                                            sessionId = negotiation.sessionId,
                                            expectedContentHash = expectedHash,
                                            stagingFile = baselineStage,
                                        )
                                        client.confirmDownloaded(saveId, deviceId, confirmedHash)
                                        noOp++
                                        GameLog.log(
                                            "Device Sync",
                                            "upload suprimido: SRAM local e remoto têm o mesmo conteúdo; baseline confirmado sem substituir a versão ativa",
                                        )
                                    } finally {
                                        baselineStage.delete()
                                    }
                                } else if (DeviceSyncArtifactPolicy.isUnbasedUpload(operation)) {
                                    val reconciled = reconcileDivergentSave(
                                        client, operation, deviceId, negotiation.sessionId, romId,
                                        remoteSlot, emulator, romFile, store, originalSave,
                                        localSave, initialLocalHash, initialTargetHash, snapshot,
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
                                    if (!originalSave.matchesMd5(initialTargetHash)) {
                                        throw IOException("O save local mudou após a negociação; upload cancelado.")
                                    }
                                    val uploadedSave = client.uploadSave(
                                        romId = romId,
                                        deviceId = deviceId,
                                        sessionId = negotiation.sessionId,
                                        slot = remoteSlot,
                                        emulator = emulator,
                                        fileName = romSaveFileName(romFile, localArtifact?.format ?: CoreSaveAdapterRegistry.resolve(coreId).fileExtension),
                                        file = snapshot,
                                        expectedExtension = DeviceSyncArtifactPolicy.fileExtensionForCore(coreId),
                                    )
                                    uploaded++
                                    GameLog.log(
                                        "Device Sync",
                                        "save enviada para ROM #$romId; save_id=${uploadedSave.optLong("id", -1L)}, bytes=${snapshot.length()}",
                                    )
                                }
                            }

                            DeviceSyncAction.Download -> {
                                if (localSave == null && initialTargetHash != null) {
                                    conflicts++
                                    GameLog.log(
                                        "Device Sync",
                                        "download adiado: um save foi criado após a captura vazia da fila; arquivo ativo preservado",
                                    )
                                    return@forEach
                                }
                                DeviceSyncProtocolJson.validateDownloadOperation(
                                    operation,
                                    DeviceSyncArtifactPolicy.compatibleEmulators(coreId),
                                    remoteSlot,
                                    DeviceSyncArtifactPolicy.fileExtensionForCore(coreId),
                                )
                                val saveId = operation.saveId
                                    ?: throw IOException("O RomM não informou o ID do save para download.")
                                val expectedHash = operation.serverContentHash
                                    ?: throw IOException("O RomM não informou o hash do save para download.")
                                if (localSave != null && initialLocalHash != null &&
                                    !initialTargetHash.equals(initialLocalHash, ignoreCase = true)
                                ) {
                                    conflicts++
                                    GameLog.log(
                                        "Device Sync",
                                        "download adiado: o save ativo mudou depois da captura da fila; " +
                                            "snapshot e versão atual foram preservados para resolução explícita",
                                    )
                                    return@forEach
                                }
                                if (localSave != null && initialLocalHash != null &&
                                    !initialLocalHash.equals(expectedHash, ignoreCase = true)
                                ) {
                                    if (!originalSave.matchesMd5(initialTargetHash)) {
                                        throw IOException("O save local mudou após a negociação; arquivamento cancelado.")
                                    }
                                    val localTimestamp = Instant.ofEpochMilli(originalSave.lastModified())
                                    client.uploadSave(
                                        romId = romId,
                                        deviceId = deviceId,
                                        sessionId = negotiation.sessionId,
                                        slot = null,
                                        emulator = emulator,
                                        fileName = archiveSaveFileName(romFile, localTimestamp, initialLocalHash ?: md5(snapshot), localArtifact?.format ?: CoreSaveAdapterRegistry.resolve(coreId).fileExtension),
                                        file = snapshot,
                                    )
                                    uploaded++
                                    extraTransfers++
                                    GameLog.log("Device Sync", "versão local anterior preservada no RomM como arquivo de arquivo (slot nulo)")
                                }
                                if (!originalSave.matchesMd5(initialTargetHash)) {
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
                                    if (!originalSave.matchesMd5(initialTargetHash)) {
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
                                    localSave, initialLocalHash, initialTargetHash, snapshot,
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
     * User-confirmed conflict choice: publish the current local bytes as a new active RomM version.
     * The current remote head is first downloaded and confirmed as this device's baseline; upload
     * then creates a new version without overwriting or deleting the server history.
     */
    suspend fun resolveConflictKeepLocal(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String?,
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
                ?: throw IOException("Não há SRAM/cartão local elegível para publicar.")
            val target = artifact.file
            val snapshotRoot = File(app.filesDir, "device-sync-snapshots")
            if (!snapshotRoot.exists() && !snapshotRoot.mkdirs()) {
                throw IOException("Não foi possível preparar a resolução do conflito.")
            }
            val localSnapshot = File(snapshotRoot, "resolve-local-" + UUID.randomUUID() + ".srm")
            val remoteStage = File(snapshotRoot, "resolve-remote-" + UUID.randomUUID() + ".srm")
            var sessionId: Long? = null
            var completed = false
            try {
                val beforeHash = md5(target)
                target.copyTo(localSnapshot, overwrite = false)
                if (md5(localSnapshot) != beforeHash) {
                    throw IOException("A SRAM local mudou durante a captura da resolução.")
                }
                val remoteSlot = DeviceSyncArtifactPolicy.remoteSlot(artifact)
                val emulator = DeviceSyncArtifactPolicy.canonicalEmulator(coreId)
                val localSave = DeviceSyncProtocolJson.localSave(
                    romId = romId,
                    fileName = romSaveFileName(romFile, artifact?.format ?: CoreSaveAdapterRegistry.resolve(coreId).fileExtension),
                    emulator = emulator,
                    file = localSnapshot,
                    updatedAt = Instant.ofEpochMilli(artifact.modifiedAtEpochMs).toString(),
                    remoteSlot = remoteSlot,
                ) ?: throw IOException("A SRAM local está vazia.")
                val client = RomMDeviceSyncClient(connection.url, connection.token)
                val negotiation = client.negotiate(
                    deviceId = deviceId,
                    saves = listOf(localSave),
                    romIds = setOf(romId),
                    emulators = null,
                )
                sessionId = negotiation.sessionId
                val remoteHead = negotiation.operations.firstOrNull {
                    it.romId == romId && it.slot == remoteSlot &&
                        it.saveId != null && !it.serverContentHash.isNullOrBlank() &&
                        it.action in setOf(DeviceSyncAction.Conflict, DeviceSyncAction.Download, DeviceSyncAction.Upload)
                }
                if (remoteHead != null) {
                    val remoteHash = remoteHead.serverContentHash
                        ?: throw IOException("O RomM não informou o hash da versão remota.")
                    val remoteSaveId = remoteHead.saveId
                        ?: throw IOException("O RomM não informou o ID da versão remota.")
                    val confirmedHash = client.downloadSaveToStaging(
                        saveId = remoteSaveId,
                        deviceId = deviceId,
                        sessionId = negotiation.sessionId,
                        expectedContentHash = remoteHash,
                        stagingFile = remoteStage,
                    )
                    client.confirmDownloaded(remoteSaveId, deviceId, confirmedHash)
                }
                if (!target.isFile || md5(target) != beforeHash) {
                    throw IOException("A SRAM local mudou durante a resolução; nenhum upload foi feito.")
                }
                val uploaded = client.uploadSave(
                    romId = romId,
                    deviceId = deviceId,
                    sessionId = negotiation.sessionId,
                    slot = remoteSlot,
                    emulator = emulator,
                    fileName = romSaveFileName(romFile, artifact?.format ?: CoreSaveAdapterRegistry.resolve(coreId).fileExtension),
                    file = localSnapshot,
                )
                client.complete(negotiation.sessionId, operationsCompleted = 1, operationsFailed = 0)
                completed = true
                clearPendingConflict(app, identity, connection.accountFingerprint)
                GameLog.log(
                    "Device Sync",
                    "usuário escolheu manter a versão local; nova versão ativa publicada no RomM, " +
                        "save_id=${uploaded.optLong("id", -1L)}; histórico preservado",
                )
                "Versão local escolhida e publicada como nova versão ativa no RomM."
            } finally {
                if (sessionId != null && !completed) {
                    runCatching { RomMDeviceSyncClient(connection.url, connection.token).complete(sessionId!!, 0, 1) }
                }
                localSnapshot.delete()
                remoteStage.delete()
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
                        fileName = romSaveFileName(romFile, artifact?.format ?: CoreSaveAdapterRegistry.resolve(coreId).fileExtension),
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
                // For an older selection, establish the current server version as this device's
                // baseline before publishing the explicitly selected historical payload. When the
                // latest version itself is selected, confirmation is deferred until after local apply.
                if (selected.id != latest.id) {
                    client.confirmDownloaded(latest.id, deviceId, latestDownloadedHash)
                }

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
                        fileName = romSaveFileName(romFile, artifact?.format ?: CoreSaveAdapterRegistry.resolve(coreId).fileExtension),
                        file = selectedStage,
                    )
                }

                if (beforeHash != null) {
                    if (!store.backupDir.exists() && !store.backupDir.mkdirs()) {
                        throw IOException("Não foi possível preparar a pasta de backup da SRAM; restauração cancelada.")
                    }
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
                clearPendingConflict(app, identity, connection.accountFingerprint)
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
     * Divergências sem ancestral comum confirmado nunca são arbitradas por horário.
     * Ambas as versões permanecem intactas no dispositivo e no RomM para escolha explícita.
     */
    private suspend fun reconcileDivergentSave(
        @Suppress("UNUSED_PARAMETER") client: RomMDeviceSyncClient,
        operation: DeviceSyncOperation,
        @Suppress("UNUSED_PARAMETER") deviceId: String,
        @Suppress("UNUSED_PARAMETER") sessionId: Long,
        @Suppress("UNUSED_PARAMETER") romId: Long,
        @Suppress("UNUSED_PARAMETER") remoteSlot: String,
        @Suppress("UNUSED_PARAMETER") emulator: String,
        @Suppress("UNUSED_PARAMETER") romFile: File,
        @Suppress("UNUSED_PARAMETER") store: SaveStore,
        originalSave: File,
        @Suppress("UNUSED_PARAMETER") localSave: DeviceSyncSave?,
        @Suppress("UNUSED_PARAMETER") initialLocalHash: String?,
        initialTargetHash: String?,
        @Suppress("UNUSED_PARAMETER") snapshot: File,
    ): ReconciliationCounts? {
        if (!originalSave.matchesMd5(initialTargetHash)) {
            throw IOException("O save local mudou durante a análise do conflito; nenhuma versão foi aplicada.")
        }
        GameLog.log(
            "Device Sync",
            "conflito real/sem baseline confirmado estacionado: save_id=${operation.saveId ?: "—"}, " +
                "hash remoto=${operation.serverContentHash?.take(8) ?: "ausente"}; " +
                "nenhuma versão foi escolhida automaticamente",
        )
        return null
    }

    private fun clearPendingConflict(context: Context, identity: String, accountFingerprint: String) {
        val preferences = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
        val key = GameSessionCoordinator.pendingConflictKey(identity, accountFingerprint)
        val pendingJobIds = preferences.getStringSet(key, emptySet()).orEmpty()
        val queue = SaveSnapshotStore(File(context.filesDir, "device-sync-queue"))
        pendingJobIds.forEach { pendingJobId ->
            val snapshots = queue.load(pendingJobId)
            snapshots[RemoteFileKind.Save]?.takeIf { it.isFile && it.length() > 0L }?.let { queuedSave ->
                val archive = File(context.filesDir, "device-sync-conflict-archive")
                if (!archive.exists() && !archive.mkdirs()) {
                    throw IOException("Não foi possível preservar a captura pendente do conflito.")
                }
                val preserved = File(
                    archive,
                    "pending-conflict-$pendingJobId-${System.currentTimeMillis()}-${queuedSave.name}",
                )
                queuedSave.copyTo(preserved, overwrite = false)
                if (sha256(queuedSave) != sha256(preserved)) {
                    preserved.delete()
                    throw IOException("A captura pendente do conflito não passou na verificação de integridade.")
                }
            }
            queue.delete(pendingJobId)
        }
        preferences.edit().remove(key).apply()
    }

    private fun romSaveFileName(romFile: File, extension: String): String = "${romFile.nameWithoutExtension}.$extension"

    private fun archiveSaveFileName(romFile: File, timestamp: Instant, contentHash: String, extension: String): String {
        val tag = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(java.time.ZoneId.systemDefault())
            .format(timestamp)
        // The hash suffix prevents a null-slot archive from colliding with a slotted
        // RomM snapshot that happens to have the same second-level timestamp.
        return "${romFile.nameWithoutExtension} [$tag] - Tandem-${contentHash.take(8)}.$extension"
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
