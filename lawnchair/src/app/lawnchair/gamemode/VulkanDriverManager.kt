package app.lawnchair.gamemode

import android.content.Context
import android.net.Uri
import android.os.Build
import com.swordfish.libretrodroid.VulkanDriver
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Metadata for a rootless AdrenoTools-compatible Vulkan driver package. */
data class InstalledVulkanDriver(
    val id: String,
    val name: String,
    val vendor: String,
    val version: String,
    val author: String,
    val libraryName: String,
    val sha256: String,
    val directory: File,
) {
    fun asLibretroDriver() = VulkanDriver(directory.absolutePath, libraryName)
}

/**
 * Imports AdrenoTools/Turnip driver packages into app-private storage.
 *
 * libadrenotools loads native libraries with dlopen, so drivers must never be loaded
 * from shared storage. This manager does not modify /vendor and does not require root.
 */
object VulkanDriverManager {
    private const val PREFS = "vulkan_driver_settings"
    private const val SELECTED = "selected_driver"
    private const val MAX_ARCHIVE_BYTES = 256L * 1024L * 1024L
    private const val MAX_FILE_BYTES = 128L * 1024L * 1024L
    private const val MAX_ENTRIES = 64

    fun driversDir(context: Context) = File(context.filesDir, "gpu-drivers").apply { mkdirs() }

    fun isLikelyAdreno(): Boolean {
        val identifiers = buildString {
            append(Build.MANUFACTURER).append(' ')
            append(Build.HARDWARE).append(' ')
            append(Build.BOARD).append(' ')
            if (Build.VERSION.SDK_INT >= 31) append(Build.SOC_MODEL)
        }.lowercase()
        return listOf("qualcomm", "qcom", "adreno", "sm8350", "sm8450", "sm8475", "sm8550", "sm8650", "sm8750", "kona", "lahaina", "taro", "kalama", "pineapple").any { identifiers.contains(it) }
    }

    fun installed(context: Context): List<InstalledVulkanDriver> = driversDir(context).listFiles().orEmpty()
        .filter { it.isDirectory && File(it, "meta.json").isFile }
        .mapNotNull { readInstalled(it) }
        .sortedWith(compareBy<InstalledVulkanDriver> { it.vendor }.thenBy { it.name })

    fun selected(context: Context): VulkanDriver? {
        if (!isLikelyAdreno()) return null
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SELECTED, null) ?: return null
        val driver = installed(context).firstOrNull { it.id == id } ?: return null
        return driver.asLibretroDriver()
    }

    fun selectedId(context: Context): String? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SELECTED, null)

    fun select(context: Context, id: String?) {
        require(id == null || installed(context).any { it.id == id }) { "Driver Vulkan não encontrado" }
        require(id == null || isLikelyAdreno()) { "Drivers Turnip/Adreno só podem ser ativados em GPUs Qualcomm Adreno" }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (id == null) remove(SELECTED) else putString(SELECTED, id)
        }.apply()
        GameLog.log("Vulkan", if (id == null) "driver do sistema selecionado" else "driver customizado selecionado: $id")
    }

    suspend fun importPackage(context: Context, uri: Uri): Result<InstalledVulkanDriver> = withContext(Dispatchers.IO) {
        runCatching {
            require(isLikelyAdreno()) { "Este pacote é destinado a GPUs Qualcomm Adreno" }
            val staging = File(driversDir(context), ".import-${System.nanoTime()}").apply {
                check(mkdirs()) { "Não foi possível criar a pasta temporária do driver" }
            }
            try {
                val files = mutableSetOf<String>()
                var wrapper: String? = null
                var totalBytes = 0L
                var entries = 0
                val input = requireNotNull(context.contentResolver.openInputStream(uri)) { "Não foi possível abrir o pacote selecionado" }
                ZipInputStream(input).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        entries++
                        check(entries <= MAX_ENTRIES) { "Pacote contém entradas demais" }
                        if (entry.isDirectory) {
                            zip.closeEntry()
                            continue
                        }
                        val raw = entry.name
                        // Bundles Turnip multi-plataforma podem conter manifest.json, LICENSES e
                        // uma pasta linux/ ao lado de android/. Esses itens não são carregados no Android.
                        if (raw == "manifest.json" || raw == "NOTICE" || raw.startsWith("linux/") || raw.startsWith("LICENSES/")) {
                            zip.closeEntry()
                            continue
                        }
                        check(!raw.startsWith("/") && !raw.startsWith("\\") && !raw.contains('\\')) { "Caminho inválido no pacote" }
                        val segments = raw.split('/').filter { it.isNotEmpty() }
                        check(segments.isNotEmpty() && segments.none { it == "." || it == ".." || ':' in it }) {
                            "O pacote contém um caminho inseguro"
                        }
                        val fileName = when (segments.size) {
                            1 -> {
                                check(wrapper == null) { "Pacote mistura arquivos na raiz e em uma subpasta" }
                                segments[0]
                            }

                            2 -> {
                                val root = segments[0]
                                check(wrapper == null || wrapper == root) { "Pacote contém múltiplas pastas raiz" }
                                check(files.isEmpty() || wrapper == root) { "Pacote mistura arquivos na raiz e em uma subpasta" }
                                wrapper = root
                                segments[1]
                            }

                            else -> error("Pacote de driver deve conter arquivos na raiz, sem subpastas internas")
                        }
                        check(fileName.matches(Regex("[A-Za-z0-9_.+-]+"))) { "Nome de arquivo inválido no pacote" }
                        check(files.add(fileName)) { "Arquivo duplicado no pacote: $fileName" }
                        val target = File(staging, fileName)
                        var written = 0L
                        target.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                written += read
                                totalBytes += read
                                check(written <= MAX_FILE_BYTES && totalBytes <= MAX_ARCHIVE_BYTES) {
                                    "Pacote de driver excede o limite de tamanho"
                                }
                                output.write(buffer, 0, read)
                            }
                        }
                        zip.closeEntry()
                    }
                }

                val metadataFile = File(staging, "meta.json")
                check(metadataFile.isFile) { "Pacote incompatível: meta.json não encontrado" }
                val metadata = JSONObject(metadataFile.readText())
                check(metadata.optInt("schemaVersion", -1) == 1) { "Versão de pacote AdrenoTools não suportada" }
                val name = metadata.optString("name").takeIf { it.isNotBlank() } ?: error("Nome do driver ausente")
                val author = metadata.optString("author").takeIf { it.isNotBlank() } ?: error("Autor do driver ausente")
                val packageVersion = metadata.optString("packageVersion").takeIf { it.isNotBlank() } ?: error("Versão do pacote ausente")
                val vendor = metadata.optString("vendor").takeIf { it in setOf("Mesa", "Qualcomm") }
                    ?: error("Fornecedor do driver deve ser Mesa ou Qualcomm")
                val version = metadata.optString("driverVersion").takeIf { it.isNotBlank() } ?: error("Versão do driver ausente")
                check(packageVersion.length <= 80 && version.length <= 80) { "Metadados de versão inválidos" }
                val minApi = metadata.optInt("minApi", 0)
                check(minApi > 0 && Build.VERSION.SDK_INT >= minApi) { "Este driver exige Android API $minApi" }
                val libraryName = metadata.optString("libraryName").takeIf {
                    it.isNotBlank() && it.endsWith(".so") && File(it).name == it
                } ?: error("Nome da biblioteca principal inválido")
                val mainLibrary = File(staging, libraryName)
                check(mainLibrary.isFile && CoreInstaller.isArm64Elf(mainLibrary)) {
                    "A biblioteca principal não é ELF64/AArch64"
                }
                staging.listFiles().orEmpty().filter { it.isFile && it.extension == "so" }.forEach { library ->
                    check(CoreInstaller.isArm64Elf(library)) { "${library.name} não é uma biblioteca ELF64/AArch64" }
                    check(library.setReadable(true, true)) { "Não foi possível preparar ${library.name}" }
                }
                val digest = sha256(mainLibrary)
                val id = "${safeId(name)}-${safeId(version)}-${digest.take(12)}"
                val installedDir = File(driversDir(context), id)
                val installedMetadata = JSONObject(metadata.toString())
                    .put("installedSha256", digest)
                    .put("installedLibrary", libraryName)
                File(staging, "meta.json").writeText(installedMetadata.toString())
                check(mainLibrary.setReadable(true, true)) { "Não foi possível preparar a biblioteca principal" }

                val backup = File(driversDir(context), ".$id.backup")
                if (backup.exists()) backup.deleteRecursively()
                if (installedDir.exists()) check(installedDir.renameTo(backup)) { "Não foi possível preservar a versão anterior do driver" }
                try {
                    check(staging.renameTo(installedDir)) { "Não foi possível concluir a instalação do driver" }
                } catch (error: Throwable) {
                    if (backup.exists()) backup.renameTo(installedDir)
                    throw error
                }
                backup.deleteRecursively()
                readInstalled(installedDir) ?: error("Driver instalado não passou pela verificação final")
            } catch (error: Throwable) {
                staging.deleteRecursively()
                throw error
            }
        }.onFailure { GameLog.error("Vulkan", "falha ao importar driver externo", it) }
    }

    private fun readInstalled(directory: File): InstalledVulkanDriver? = runCatching {
        val metadata = JSONObject(File(directory, "meta.json").readText())
        val libraryName = metadata.optString("installedLibrary").ifBlank { metadata.optString("libraryName") }
        val library = File(directory, libraryName)
        val digest = sha256(library)
        val expected = metadata.optString("installedSha256")
        require(library.isFile && CoreInstaller.isArm64Elf(library))
        require(expected.isNotBlank() && digest.equals(expected, ignoreCase = true))
        InstalledVulkanDriver(
            id = directory.name,
            name = metadata.getString("name"),
            vendor = metadata.getString("vendor"),
            version = metadata.getString("driverVersion"),
            author = metadata.getString("author"),
            libraryName = libraryName,
            sha256 = digest,
            directory = directory,
        )
    }.getOrNull()

    private fun safeId(value: String) = value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(32).ifBlank { "driver" }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
