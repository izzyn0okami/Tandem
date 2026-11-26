package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
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

    internal data class CoreArtifact(val archiveName: String, val libraryName: String)

    private val specialArtifacts = mapOf(
        // O Buildbot publica Azahar com nome diferente do padrão Android.
        "azahar" to CoreArtifact("azahar_libretro.so.zip", "azahar_libretro.so"),
    )

    internal fun artifact(coreId: String): CoreArtifact {
        require(coreId.matches(Regex("[a-zA-Z0-9_-]+"))) { "Identificador de núcleo inválido" }
        return specialArtifacts[coreId]
            ?: CoreArtifact("${coreId}_libretro_android.so.zip", "${coreId}_libretro_android.so")
    }

    fun coreIdFromFile(file: File): String {
        val name = file.name
        specialArtifacts.forEach { (id, artifact) ->
            if (name == artifact.libraryName) return id
        }
        return name.removeSuffix("_libretro_android.so")
    }

    fun coresDir(context: Context): File = File(context.filesDir, "libretro/cores").apply { mkdirs() }

    fun coreFile(context: Context, coreId: String): File = File(coresDir(context), artifact(coreId).libraryName)

    fun isInstalled(context: Context, coreId: String): Boolean {
        val file = coreFile(context, coreId)
        if (!file.isFile || !isArm64Elf(file)) return false

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val storedDigest = prefs.getString("sha256_$coreId", null)
        val actualDigest = sha256(file) ?: return false
        if (storedDigest != null && !storedDigest.equals(actualDigest, ignoreCase = true)) {
            GameLog.error(
                "núcleos",
                "integridade local de $coreId divergente; núcleo será tratado como inválido",
                IllegalStateException("SHA-256 do núcleo instalado não corresponde ao registro local"),
            )
            return false
        }
        // Migração de núcleos já instalados: registra a impressão digital local na primeira leitura.
        if (storedDigest == null) prefs.edit().putString("sha256_$coreId", actualDigest).apply()
        return true
    }

    /**
     * null = servidor indisponível ou sem metadados suficientes; false = sem atualização;
     * true = o Buildbot publicou um arquivo mais novo ou mudou seu ETag.
     */
    suspend fun updateAvailable(context: Context, coreId: String): Boolean? = withContext(Dispatchers.IO) {
        runCatching {
            val target = coreFile(context, coreId)
            if (!target.isFile || !isArm64Elf(target)) return@runCatching false
            val remote = artifact(coreId)

            val connection = URL("$BASE_URL/${remote.archiveName}").openConnection() as HttpURLConnection
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
                val installedModified = prefs.getLong("modified_$coreId", 0L)
                val installedEtag = prefs.getString("etag_$coreId", null)
                when {
                    remoteModified > 0L && installedModified > 0L ->
                        remoteModified > installedModified + 1_000L

                    remoteEtag != null && installedEtag != null ->
                        remoteEtag != installedEtag

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
            require(coreId.matches(Regex("[a-zA-Z0-9_-]+"))) { "Identificador de núcleo inválido" }
            val target = coreFile(context, coreId)
            val remote = artifact(coreId)
            val part = File(target.parentFile, "${target.name}.part")
            val backup = File(target.parentFile, "${target.name}.backup")
            val connection = URL("$BASE_URL/${remote.archiveName}").openConnection() as HttpURLConnection
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
                    check(entry != null && entry.name == remote.libraryName && !entry.isDirectory) {
                        "ZIP inesperado: esperava ${remote.libraryName}, veio ${entry?.name}"
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
                    check(zip.nextEntry == null) { "ZIP contém entradas adicionais inesperadas" }
                }
                check(isArm64Elf(part)) {
                    "${target.name} não é uma biblioteca ELF ARM64 válida"
                }

                // Ajusta permissões antes de tocar na instalação atual. Uma falha aqui
                // descarta apenas o arquivo temporário e mantém o núcleo anterior intacto.
                check(part.setExecutable(true, false)) { "Não foi possível definir permissão de execução no novo núcleo" }
                check(isArm64Elf(part)) { "Núcleo temporário falhou na validação antes da instalação" }

                if (backup.exists()) check(backup.delete()) { "Não foi possível limpar backup antigo" }
                val hadPrevious = target.exists()
                if (hadPrevious) {
                    check(target.renameTo(backup)) { "Não foi possível preservar o núcleo anterior" }
                }

                try {
                    check(part.renameTo(target)) { "Não foi possível promover o novo núcleo" }
                    check(isArm64Elf(target)) { "Núcleo instalado falhou na validação final" }
                } catch (installFailure: Throwable) {
                    // Qualquer falha após mover o arquivo anterior aciona rollback.
                    if (target.exists() && !target.delete()) {
                        throw IllegalStateException(
                            "Falha na instalação e não foi possível remover o novo arquivo; backup preservado em ${backup.name}",
                            installFailure,
                        )
                    }
                    if (hadPrevious && backup.exists() && !backup.renameTo(target)) {
                        throw IllegalStateException(
                            "Falha na instalação e restauração automática não concluída; backup preservado em ${backup.name}",
                            installFailure,
                        )
                    }
                    throw IllegalStateException(
                        "Novo núcleo rejeitado; versão anterior restaurada",
                        installFailure,
                    )
                }

                val installedDigest = checkNotNull(sha256(target)) { "Não foi possível calcular SHA-256 do núcleo instalado" }
                val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString("sha256_$coreId", installedDigest)
                    .apply {
                        if (remoteModified > 0L) {
                            putLong("modified_$coreId", remoteModified)
                        } else {
                            remove("modified_$coreId")
                        }
                        if (remoteEtag != null) {
                            putString("etag_$coreId", remoteEtag)
                        } else {
                            remove("etag_$coreId")
                        }
                    }
                    .apply()
                // A limpeza do backup é pós-commit: sua falha não invalida a instalação.
                if (backup.exists() && !backup.delete()) {
                    GameLog.error(
                        "núcleos",
                        "núcleo $coreId atualizado, mas não foi possível remover ${backup.name}",
                        IllegalStateException("Falha ao excluir o backup após a atualização"),
                    )
                }
                GameLog.log("núcleos", "$coreId instalado/atualizado (${target.length() / 1024} KB)")
                target
            } finally {
                connection.disconnect()
                part.delete()
            }
        }.onFailure { GameLog.error("núcleos", "falha ao instalar/atualizar $coreId", it) }
    }

    /** SHA-256 local para detectar corrupção posterior do arquivo instalado. */
    internal fun sha256(file: File): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }.getOrNull()

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
