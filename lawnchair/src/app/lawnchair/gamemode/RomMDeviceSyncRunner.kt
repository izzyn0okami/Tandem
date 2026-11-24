package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resultado de uma sincronização SRAM explicitamente iniciada pelo usuário. */
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
 * Só opera a SRAM do jogo explicitamente vinculado e fechado. Upload/download são executados
 * somente após negociação iniciada por ação explícita do usuário. Conflitos ficam preservados
 * para resolução explícita; operações delete e desconhecidas nunca são aplicadas.
 * Save states e memory cards não são enumerados nem enviados.
 */
object RomMDeviceSyncRunner {

    suspend fun synchronizeLocalSram(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String? = null,
    ): DeviceSyncExecutionResult = withContext(Dispatchers.IO) {
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
            throw IOException("Feche o jogo antes de sincronizar a SRAM.")
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
        val snapshotRoot = File(app.filesDir, "device-sync-snapshots")
        if (!snapshotRoot.exists() && !snapshotRoot.mkdirs()) {
            throw IOException("Não foi possível preparar a captura temporária da SRAM.")
        }
        val snapshot = File(snapshotRoot, "${UUID.randomUUID()}.srm")
        try {
            val originalSram = store.sramFile
            val localSave = if (originalSram.isFile && originalSram.length() > 0L) {
                originalSram.copyTo(snapshot, overwrite = false)
                val before = md5(originalSram)
                val captured = md5(snapshot)
                if (before != captured) throw IOException("A SRAM mudou durante a captura; tente novamente.")
                DeviceSyncProtocolJson.localSramSave(
                    romId = romId,
                    fileName = originalSram.name,
                    emulator = "tandem_$coreId",
                    file = snapshot,
                    updatedAt = Instant.ofEpochMilli(originalSram.lastModified()).toString(),
                ) ?: throw IOException("Não foi possível preparar a SRAM local.")
            } else {
                null
            }
            val initialLocalHash = localSave?.contentHashMd5
            val negotiation = client.negotiate(
                deviceId = deviceId,
                saves = listOfNotNull(localSave),
                romIds = setOf(romId),
            )

            var uploaded = 0
            var downloaded = 0
            var noOp = 0
            var conflicts = 0
            var deletesIgnored = 0
            var unknownIgnored = 0
            var failed = 0

            negotiation.operations.forEach { operation ->
                if (operation.romId != romId) {
                    failed++
                    GameLog.log("Device Sync", "operação ignorada: ROM fora do escopo negociado")
                    return@forEach
                }
                try {
                    when (operation.action) {
                        DeviceSyncAction.NoOp -> noOp++
                        DeviceSyncAction.Upload -> {
                            if (localSave == null || operation.slot != "autosave") {
                                throw IOException("Upload sem SRAM local compatível ou fora do slot autosave.")
                            }
                            if (!originalSram.matchesMd5(initialLocalHash)) {
                                throw IOException("A SRAM local mudou após a negociação; upload cancelado.")
                            }
                            client.uploadSave(
                                romId = romId,
                                deviceId = deviceId,
                                sessionId = negotiation.sessionId,
                                slot = "autosave",
                                file = snapshot,
                            )
                            uploaded++
                        }
                        DeviceSyncAction.Download -> {
                            if (operation.slot != "autosave") {
                                throw IOException("Download fora do slot autosave foi preservado sem alteração.")
                            }
                            val saveId = operation.saveId
                                ?: throw IOException("O RomM não informou o ID do save para download.")
                            val expectedHash = operation.serverContentHash
                                ?: throw IOException("O RomM não informou o hash do save para download.")
                            if (!originalSram.matchesMd5(initialLocalHash)) {
                                throw IOException("A SRAM local mudou após a negociação; download cancelado.")
                            }
                            val target = store.sramFile
                            target.parentFile?.let {
                                if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível preparar a pasta local de saves.")
                            }
                            val staging = File(target.parentFile, ".${target.name}.${negotiation.sessionId}.${saveId}.part")
                            try {
                                val downloadedHash = client.downloadSaveToStaging(
                                    saveId = saveId,
                                    deviceId = deviceId,
                                    sessionId = negotiation.sessionId,
                                    expectedContentHash = expectedHash,
                                    stagingFile = staging,
                                )
                                if (!originalSram.matchesMd5(initialLocalHash)) {
                                    throw IOException("A SRAM local mudou durante o download; arquivo remoto não aplicado.")
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
                                        throw IOException("Não foi possível verificar o backup da SRAM local.")
                                    }
                                    GameLog.log("Device Sync", "SRAM local preservada em backup antes do download: ${backup.name}")
                                }
                                RomMFileReplacement.replaceFile(staging, target)
                                client.confirmDownloaded(saveId, deviceId, downloadedHash)
                                downloaded++
                            } finally {
                                staging.delete()
                            }
                        }
                        DeviceSyncAction.Conflict -> {
                            conflicts++
                            GameLog.log("Device Sync", "conflito preservado para resolução explícita; nenhum lado foi sobrescrito")
                        }
                        DeviceSyncAction.Delete -> {
                            deletesIgnored++
                            GameLog.log("Device Sync", "operação delete recusada; SRAM local preservada")
                        }
                        DeviceSyncAction.Unknown -> {
                            unknownIgnored++
                            GameLog.log("Device Sync", "operação desconhecida recusada; SRAM local preservada")
                        }
                    }
                } catch (error: Exception) {
                    failed++
                    GameLog.error("Device Sync", "operação ${operation.action} falhou para ROM #$romId", error)
                }
            }

            val completed = noOp + uploaded + downloaded
            val failedOperations = failed + conflicts + deletesIgnored + unknownIgnored
            client.complete(negotiation.sessionId, completed, failedOperations)
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
