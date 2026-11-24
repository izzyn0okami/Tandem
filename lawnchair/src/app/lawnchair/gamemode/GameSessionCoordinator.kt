package app.lawnchair.gamemode

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Dono do ciclo de sincronização da sessão.
 *
 * SRAM/cartões usam o protocolo Device Sync; estados rápidos usam os endpoints /api/states.
 * Os dois transportes são executados em sequência, antes da partida e no trabalho persistente
 * posterior à sessão. RetroAchievements continua ligado à mesma sessão Libretro e mantém seu
 * progresso local separado dos arquivos de emulação.
 */
internal object GameSessionCoordinator {

    private val deviceRegistrationMutex = Mutex()

    fun markSessionActive(
        context: Context,
        romPath: String,
        platformSlug: String? = null,
        gameIdentity: String? = null,
    ) = SaveSync.markSessionActive(context, romPath, platformSlug, gameIdentity)

    fun markSessionInactive(
        context: Context,
        romPath: String,
        platformSlug: String? = null,
        gameIdentity: String? = null,
    ) = SaveSync.markSessionInactive(context, romPath, platformSlug, gameIdentity)

    fun markPending(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String? = null,
        gameIdentity: String? = null,
    ) {
        SaveSync.markPending(context, romPath, coreId, platformSlug, gameIdentity)
        val app = context.applicationContext
        val romFile = File(romPath)
        if (!GameIdentityResolver.hasRomMLink(app, romFile)) return
        val identity = gameIdentity ?: GameIdentityResolver.resolve(app, romFile, platformSlug)
        val accountFingerprint = GameIdentityResolver.originAccountFingerprint(app, romFile) ?: return
        app.getSharedPreferences("romm_sync", Context.MODE_PRIVATE).edit {
            putString(statusKey(identity, accountFingerprint), "pending")
        }
    }

    fun enqueuePostSessionSync(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String? = null,
        gameIdentity: String? = null,
    ) = SaveSync.pushAsync(context, romPath, coreId, platformSlug, gameIdentity)

    /**
     * Executa o pull de SRAM/cartão via Device Sync e, depois, o pull de estado rápido.
     * Nenhum arquivo remoto é aplicado se a negociação retornar conflito ou operação desconhecida.
     */
    suspend fun prepareSession(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String?,
        gameIdentity: String,
        migrationSources: Map<String, File>,
    ) {
        val app = context.applicationContext
        if (!RomMSettings.isConnected(app)) return
        val romFile = File(romPath)
        if (!GameIdentityResolver.hasRomMLink(app, romFile)) {
            SaveSync.pull(app, romPath, coreId, platformSlug, gameIdentity, migrationSources)
            return
        }
        val connection = GameIdentityResolver.connectionSnapshot(app)
            ?: throw IOException("Não foi possível confirmar a conta RomM para preparar a sessão.")
        ensureDeviceRegistered(app, connection.url, connection.token)
        val result = RomMDeviceSyncRunner.synchronizeLocalSave(app, romPath, coreId, platformSlug)
        if (GameSessionSyncPolicy.blocksSessionStart(result)) {
            throw IOException(
                "A sincronização pré-jogo foi interrompida para preservar os dados. " +
                    "Falhas: ${result.failed}; conflitos: ${result.conflictsPreserved}; " +
                    "exclusões recusadas: ${result.deletesIgnored}; operações desconhecidas: ${result.unknownIgnored}.",
            )
        }
        SaveSync.pull(app, romPath, coreId, platformSlug, gameIdentity, migrationSources)
    }

    /** Executado pelo único worker persistente após a sessão: Device Sync e, em seguida, estados. */
    internal suspend fun pushFromWorker(
        context: Context,
        romPath: String,
        coreId: String,
        platformSlug: String?,
        gameIdentity: String,
        expectedAccountFingerprint: String,
        snapshotJobId: String,
    ): Boolean {
        val app = context.applicationContext
        val connection = GameIdentityResolver.connectionSnapshot(app) ?: return false
        if (!SaveSyncPolicy.isSameSyncAccount(expectedAccountFingerprint, connection.accountFingerprint)) return false
        val activeRomPath = GameIdentityResolver.findRomPath(app, gameIdentity, romPath, platformSlug) ?: return false
        val activeRom = File(activeRomPath)
        if (!GameIdentityResolver.hasRomMLink(app, activeRom)) return true

        var saveRetry = false
        var saveResult: DeviceSyncExecutionResult? = null
        try {
            ensureDeviceRegistered(app, connection.url, connection.token)
            saveResult = RomMDeviceSyncRunner.synchronizeLocalSave(app, activeRomPath, coreId, platformSlug)
            saveRetry = GameSessionSyncPolicy.shouldRetryBackground(saveResult)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            saveRetry = true
            GameLog.error("Device Sync", "falha no transporte de SRAM/cartão; a fila tentará novamente", error)
        }
        // O transporte /api/states é independente: uma falha no Device Sync não impede sua tentativa.
        val statesCompleted = SaveSync.pushFromWorker(
            app,
            activeRomPath,
            coreId,
            platformSlug,
            gameIdentity,
            expectedAccountFingerprint,
            snapshotJobId,
        )
        val unresolved = saveRetry || saveResult?.let { GameSessionSyncPolicy.blocksSessionStart(it) } == true
        if (unresolved) {
            app.getSharedPreferences("romm_sync", Context.MODE_PRIVATE).edit {
                putString(statusKey(gameIdentity, expectedAccountFingerprint), "pending")
            }
        }
        return !saveRetry && statesCompleted
    }

    private suspend fun ensureDeviceRegistered(
        context: Context,
        serverUrl: String,
        token: String,
    ) {
        deviceRegistrationMutex.withLock {
            if (RomMSettings.deviceSyncDeviceId(context) != null) return@withLock
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            val stableSource = androidId?.takeIf { it.isNotBlank() } ?: Build.FINGERPRINT
            val suffix = MessageDigest.getInstance("SHA-256")
                .digest(stableSource.toByteArray(Charsets.UTF_8))
                .take(10)
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Tandem Android" }
            val registration = DeviceSyncRegistration(
                name = deviceName,
                platform = "Android",
                client = "tandem",
                clientVersion = "1.0",
                hostname = "tandem-$suffix",
            )
            val registered = withContext(Dispatchers.IO) {
                RomMDeviceSyncClient(serverUrl, token).registerDevice(registration)
            }
            RomMSettings.saveDeviceSyncDeviceId(context, registered.deviceId)
            GameLog.log("Device Sync", "dispositivo registrado automaticamente para a conta RomM atual")
        }
    }

    private fun statusKey(gameIdentity: String, accountFingerprint: String) = "sync_status:$gameIdentity:$accountFingerprint"
}

internal object GameSessionSyncPolicy {

    fun blocksSessionStart(result: DeviceSyncExecutionResult): Boolean = result.failed > 0 ||
        result.conflictsPreserved > 0 ||
        result.deletesIgnored > 0 ||
        result.unknownIgnored > 0

    fun isConflictHttpStatus(statusCode: Int): Boolean = statusCode == 409

    /** Conflitos são estacionados para resolução; somente falhas operacionais pedem retry automático. */
    fun shouldRetryBackground(result: DeviceSyncExecutionResult): Boolean = result.failed > 0
}
