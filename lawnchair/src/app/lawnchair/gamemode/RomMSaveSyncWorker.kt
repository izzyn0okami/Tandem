package app.lawnchair.gamemode

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException

/** Executa uploads de saves em trabalho persistente, fora do ciclo de vida da Activity. */
class RomMSaveSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val romPath = inputData.getString(KEY_ROM_PATH) ?: return Result.failure()
        val coreId = inputData.getString(KEY_CORE_ID) ?: return Result.failure()
        return try {
            val completed = SaveSync.pushFromWorker(applicationContext, romPath, coreId)
            if (SaveSyncPolicy.shouldRetryWorker(completed)) Result.retry() else Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            GameLog.error("saves", "falha no envio persistente de saves de ${java.io.File(romPath).name}", error)
            Result.retry()
        }
    }

    companion object {
        const val KEY_ROM_PATH = "rom_path"
        const val KEY_CORE_ID = "core_id"
    }
}
