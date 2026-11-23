package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resultado de uma inspeção Device Sync que não aplica nenhuma operação retornada pelo servidor. */
data class DeviceSyncSramInspection(
    val romId: Long,
    val localSramIncluded: Boolean,
    val negotiation: DeviceSyncNegotiation,
)

/**
 * Integração diagnóstica do Device Sync ao save local.
 *
 * A inspeção envia somente o manifesto da SRAM do jogo explicitamente vinculado. Não inclui
 * save states, não registra automaticamente um dispositivo, não transfere arquivos e não
 * executa operações delete, upload, download ou conflict.
 */
object RomMDeviceSyncDiagnostics {

    suspend fun inspectLocalSram(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String? = null,
    ): DeviceSyncSramInspection = withContext(Dispatchers.IO) {
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
            throw IOException("Feche o jogo antes de inspecionar a sincronização da SRAM.")
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
        val localSave = DeviceSyncProtocolJson.localSramSave(
            romId = romId,
            fileName = store.sramFile.name,
            emulator = "tandem_$coreId",
            file = store.sramFile,
            updatedAt = Instant.ofEpochMilli(store.sramFile.lastModified()).toString(),
        )
        val client = RomMDeviceSyncClient(connection.url, connection.token)
        val negotiation = client.inspectNegotiation(
            deviceId = deviceId,
            saves = listOfNotNull(localSave),
            romIds = setOf(romId),
        )
        DeviceSyncSramInspection(
            romId = romId,
            localSramIncluded = localSave != null,
            negotiation = negotiation,
        )
    }
}
