package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resultado de uma inspeção Device Sync que não aplica nenhuma operação retornada pelo servidor. */
data class DeviceSyncSaveInspection(
    val romId: Long,
    val localSaveIncluded: Boolean,
    val localSaveUpdatedAt: String?,
    val localSaveMd5: String?,
    val localSaveSizeBytes: Long?,
    val negotiation: DeviceSyncNegotiation,
)

/**
 * Integração diagnóstica do Device Sync ao save local.
 *
 * A inspeção envia somente o manifesto do save do jogo explicitamente vinculado. Não inclui
 * save states, não registra automaticamente um dispositivo, não transfere arquivos e não
 * executa operações delete, upload, download ou conflict.
 */
object RomMDeviceSyncDiagnostics {

    suspend fun inspectLocalSave(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String? = null,
    ): DeviceSyncSaveInspection = withContext(Dispatchers.IO) {
        SaveSync.withExclusiveSyncLock {
            val connection = GameIdentityResolver.connectionSnapshot(context)
                ?: throw IOException("Conecte a mesma conta RomM que está vinculada a este jogo.")
            val romFile = File(romPath)
            if (!GameIdentityResolver.hasRomMLink(context, romFile)) {
                throw IOException("Este jogo não possui um vínculo RomM confirmado.")
            }
            val romId = GameIdentityResolver.linkedRomId(context, romFile, connection)
                ?: throw IOException("O vínculo deste jogo não corresponde à conta RomM atual.")
            val identity = GameIdentityResolver.cachedIdentity(context, romFile, platformSlug)
                ?: GameIdentityResolver.resolve(context, romFile, platformSlug)
            val lastActive = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
                .getLong("session_active:$identity", 0L)
            if (SaveSyncPolicy.isSessionActive(lastActive, System.currentTimeMillis())) {
                throw IOException("Feche o jogo antes de inspecionar a sincronização do save.")
            }
            val deviceId = RomMSettings.deviceSyncDeviceId(context)
                ?: throw IOException("Este dispositivo ainda não está registrado no Device Sync do RomM.")
            val store = SaveStore(
                context,
                coreId,
                romFile,
                platformSlug = platformSlug,
                gameIdentity = identity,
                migrationSourcesOverride = emptyMap(),
            )
            val artifact = DeviceSyncArtifactPolicy.selectForRomM(store.inventory())
            val remoteSlot = artifact?.let(DeviceSyncArtifactPolicy::remoteSlot)
                ?: DeviceSyncArtifactPolicy.remoteSlotForCore(coreId)
            val localFile = artifact?.file ?: store.sramFile
            val emulator = "tandem_$coreId"
            val localSave = DeviceSyncProtocolJson.localSave(
                romId = romId,
                fileName = localFile.name,
                emulator = emulator,
                file = localFile,
                updatedAt = Instant.ofEpochMilli(localFile.lastModified()).toString(),
                remoteSlot = remoteSlot,
            )
            val client = RomMDeviceSyncClient(connection.url, connection.token)
            val negotiation = client.inspectNegotiation(
                deviceId = deviceId,
                saves = listOfNotNull(localSave),
                romIds = setOf(romId),
                emulators = null,
                managedSlot = remoteSlot,
            )
            DeviceSyncSaveInspection(
                romId = romId,
                localSaveIncluded = localSave != null,
                localSaveUpdatedAt = localSave?.updatedAt,
                localSaveMd5 = localSave?.contentHashMd5,
                localSaveSizeBytes = localSave?.fileSizeBytes,
                negotiation = negotiation,
            )
        }
    }
}
