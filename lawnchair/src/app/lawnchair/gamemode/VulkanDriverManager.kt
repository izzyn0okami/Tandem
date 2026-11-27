package app.lawnchair.gamemode

import android.content.Context
import android.net.Uri
import com.swordfish.libretrodroid.VulkanDriver
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.zip.ZipInputStream

/**
 * Importa um pacote Turnip/Adreno fornecido pelo usuário para armazenamento privado do app.
 * Só aceita bibliotecas ELF64 AArch64 e mantém o driver anterior se a importação falhar.
 */
object VulkanDriverManager {
    private const val DRIVER_ROOT = "vulkan/drivers/adreno"
    private const val DRIVER_LIBRARY = "libvulkan_freedreno.so"
    private const val MAX_ENTRIES = 512
    private const val MAX_UNCOMPRESSED_BYTES = 384L * 1024L * 1024L

    fun importTurnipDriver(context: Context, uri: Uri): Result<VulkanDriver> = runCatching {
        val parent = File(context.filesDir, "vulkan/drivers").apply { mkdirs() }
        val staging = File(parent, "adreno.importing")
        val destination = File(context.filesDir, DRIVER_ROOT)
        val backup = File(parent, "adreno.backup")
        staging.deleteRecursively()
        check(staging.mkdirs()) { "Não foi possível preparar a pasta temporária do driver." }

        try {
            var entries = 0
            var totalBytes = 0L
            val canonicalRoot = staging.canonicalFile
            val input = context.contentResolver.openInputStream(uri)
                ?: error("Não foi possível abrir o arquivo ZIP selecionado.")
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    entries++
                    require(entries <= MAX_ENTRIES) { "O pacote contém arquivos demais." }
                    val relative = entry.name.replace('\\', '/')
                    require(
                        relative.isNotBlank() &&
                            !relative.startsWith("/") &&
                            !Regex("^[A-Za-z]:").containsMatchIn(relative) &&
                            relative.split('/').none { it == ".." } &&
                            !relative.contains('\u0000')
                    ) { "O pacote contém um caminho de arquivo inválido." }

                    val output = File(staging, relative).canonicalFile
                    require(output.path.startsWith(canonicalRoot.path + File.separator)) {
                        "O pacote tenta gravar fora da pasta de destino."
                    }
                    if (entry.isDirectory) {
                        check(output.mkdirs() || output.isDirectory) { "Não foi possível criar uma pasta do driver." }
                    } else {
                        check(output.parentFile?.mkdirs() != false) { "Não foi possível criar a pasta do driver." }
                        FileOutputStream(output).use { stream ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read < 0) break
                                totalBytes += read
                                require(totalBytes <= MAX_UNCOMPRESSED_BYTES) { "O pacote excede o limite de tamanho permitido." }
                                stream.write(buffer, 0, read)
                            }
                            stream.flush()
                            stream.fd.sync()
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val libraries = staging.walkTopDown().filter { it.isFile && it.name == DRIVER_LIBRARY }.toList()
            require(libraries.size == 1) {
                "O ZIP deve conter exatamente uma biblioteca $DRIVER_LIBRARY."
            }
            val sourceLibrary = libraries.single()
            require(isArm64Elf(sourceLibrary)) {
                "$DRIVER_LIBRARY não é uma biblioteca ELF64 ARM64 válida."
            }
            val relativeLibraryPath = sourceLibrary.relativeTo(staging).path

            if (backup.exists()) check(backup.deleteRecursively()) { "Não foi possível limpar o backup anterior do driver." }
            if (destination.exists()) check(destination.renameTo(backup)) { "Não foi possível preservar o driver anterior." }
            if (!staging.renameTo(destination)) {
                if (backup.exists()) backup.renameTo(destination)
                error("Não foi possível instalar o novo driver; a versão anterior foi preservada.")
            }
            backup.deleteRecursively()

            val installedLibrary = File(destination, relativeLibraryPath)
            check(installedLibrary.isFile && isArm64Elf(installedLibrary)) {
                "A biblioteca do driver não passou na validação após a instalação."
            }
            VulkanDriver(installedLibrary.parentFile!!.absolutePath, installedLibrary.name)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun remove(context: Context) {
        val directory = File(context.filesDir, DRIVER_ROOT)
        check(!directory.exists() || directory.deleteRecursively()) {
            "Não foi possível remover os arquivos do driver externo."
        }
    }

    private fun isArm64Elf(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 20L) return@use false
            val header = ByteArray(20)
            input.readFully(header)
            val isElf = header[0] == 0x7f.toByte() &&
                header[1] == 'E'.code.toByte() &&
                header[2] == 'L'.code.toByte() &&
                header[3] == 'F'.code.toByte()
            val is64Bit = header[4] == 2.toByte()
            val isLittleEndian = header[5] == 1.toByte()
            val machine = (header[18].toInt() and 0xff) or ((header[19].toInt() and 0xff) shl 8)
            isElf && is64Bit && isLittleEndian && machine == 183
        }
    }.getOrDefault(false)
}
