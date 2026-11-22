package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Instala e atualiza núcleos ARM64 do Buildbot oficial sem substituir uma versão válida
 * até que o novo arquivo tenha sido baixado e validado por completo.
 */
object CoreInstaller {

    private const val BASE_URL = "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a"
    private const val PREFS = "libretro_core_updates"

    fun coresDir(context: Context): File = File(context.filesDir, "libretro/cores").apply { mkdirs() }

    fun coreFile(context: Context, coreId: String): File = File(coresDir(context), "${coreId}_libretro_android.so")

    fun isInstalled(context: Context, coreId: String): Boolean {
        val file = coreFile(context, coreId)
        return file.isFile && isArm64Elf(file)
    }

    /**
     * null = servidor indisponível ou sem metadados suficientes; false = sem atualização;
     * true = o Buildbot publicou um arquivo mais novo ou mudou seu ETag.
     */
    suspend fun updateAvailable(context: Context, coreId: String): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            val target = coreFile(context, coreId)
            if (!target.isFile || !isArm64Elf(target)) return@runCatching false

            val connection = URL("$BASE_URL/${target.name}.zip").openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            try {
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "HTTP ${connection.responseCode} ao consultar ${target.name}"
                }
                val remoteModified = connection.lastModified
                val remoteEtag = connection.getHeaderField("ETag")?.takeIf { it.isNotBlank() }
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val installedModified = prefs.getLong("modified_$coreId", target.lastModified())
                val installedEtag = prefs.getString("etag_$coreId", null)
                when {
                    remoteModified > 0L && remoteModified > installedModified + 1_000L -> true
                    remoteEtag != null && installedEtag != null && remoteEtag != installedEtag -> true
                    remoteModified > 0L || (remoteEtag != null && installedEtag != null) -> false
                    else -> null
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse {
            GameLog.error("núcleos", "não foi possível verificar atualização de $coreId", it)
            null
        }
    }

    /** Devolve o núcleo instalado ou baixa se estiver ausente/inválido. */
    suspend fun ensureInstalled(
        context: Context,
        coreId: String,
        onProgress: (Long) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        if (isInstalled(context, coreId)) {
            Result.success(coreFile(context, coreId))
        } else {
            install(context, coreId, onProgress)
        }
    }

    /** Baixa e instala a versão atual, preservando o núcleo anterior até validar o novo. */
    suspend fun update(
        context: Context,
        coreId: String,
        onProgress: (Long) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        install(context, coreId, onProgress)
    }

    private suspend fun install(
        context: Context,
        coreId: String,
        onProgress: (Long) -> Unit,
    ): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            require(coreId.matches(Regex("[a-zA-Z0-9_]+"))) { "Identificador de núcleo inválido" }
            val target = coreFile(context, coreId)
            val part = File(target.parentFile, "${target.name}.part")
            val backup = File(target.parentFile, "${target.name}.backup")
            val connection = URL("$BASE_URL/${target.name}.zip").openConnection() as HttpURLConnection
            connection.connectTimeout = 30_000
            connection.readTimeout = 90_000
            GameLog.log("núcleos", "baixando ${target.name}")
            try {
                check(connection.responseCode == HttpURLConnection.HTTP_OK) {
                    "HTTP ${connection.responseCode} ao baixar ${target.name}"
                }
                val remoteModified = connection.lastModified
                val remoteEtag = connection.getHeaderField("ETag")?.takeIf { it.isNotBlank() }
                ZipInputStream(connection.inputStream).use { zip ->
                    val entry = zip.nextEntry
                    check(entry != null && entry.name == target.name && !entry.isDirectory) {
                        "ZIP inesperado: esperava ${target.name}, veio ${entry?.name}"
                    }
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var written = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            written += read
                            onProgress(written)
                        }
                        output.flush()
                        output.fd.sync()
                    }
                    zip.closeEntry()
                }
                check(isArm64Elf(part)) {
                    "${target.name} não é uma biblioteca ELF ARM64 válida"
                }

                if (backup.exists()) check(backup.delete()) { "Não foi possível limpar backup antigo" }
                if (target.exists()) {
                    check(target.renameTo(backup)) { "Não foi possível preservar o núcleo anterior" }
                }
                if (!part.renameTo(target)) {
                    if (backup.exists()) backup.renameTo(target)
                    error("Não foi possível instalar o novo núcleo; versão anterior restaurada quando disponível")
                }
                target.setExecutable(true, false)
                check(isArm64Elf(target)) { "Núcleo instalado falhou na validação final" }

                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.edit()
                    .putLong("modified_$coreId", remoteModified.takeIf { it > 0L } ?: System.currentTimeMillis())
                    .apply {
                        if (remoteEtag != null) putString("etag_$coreId", remoteEtag)
                    }
                    .apply()
                backup.delete()
                GameLog.log("núcleos", "$coreId instalado/atualizado (${target.length() / 1024} KB)")
                target
            } finally {
                connection.disconnect()
                part.delete()
            }
        }.onFailure { GameLog.error("núcleos", "falha ao instalar/atualizar $coreId", it) }
    }

    /** Valida ELF64 little-endian para AArch64 (e_machine = EM_AARCH64/183). */
    internal fun isArm64Elf(file: File): Boolean = runCatching {
        file.inputStream().use { input ->
            val header = ByteArray(20)
            var offset = 0
            while (offset < header.size) {
                val read = input.read(header, offset, header.size - offset)
                if (read < 0) return@use false
                offset += read
            }
            header[0] == 0x7F.toByte() &&
                header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() &&
                header[3] == 'F'.code.toByte() &&
                header[4] == 2.toByte() &&
                header[5] == 1.toByte() &&
                (header[18].toInt() and 0xFF) == 183 &&
                header[19] == 0.toByte()
        }
    }.getOrDefault(false)
}
