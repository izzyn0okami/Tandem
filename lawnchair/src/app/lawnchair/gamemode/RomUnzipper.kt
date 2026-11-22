package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Muitos núcleos (Nintendo 64, DS, entre outros) não abrem ROM dentro de .zip e, em vez de recusar,
 * podem travar. Antes de abrir, o jogo é extraído para o cache com o nome do .zip (assim os saves
 * continuam com o mesmo nome). Jogos de disco ficam como estão: precisam de vários arquivos juntos.
 */
object RomUnzipper {

    fun prepare(context: Context, romPath: String, platform: GamePlatform?): File {
        val rom = File(romPath)
        if (!rom.extension.equals("zip", ignoreCase = true) || platform?.discBased == true || platform?.zipIsNativeRom == true) return rom
        return try {
            extract(context, rom, platform?.extensions.orEmpty())
                ?: throw IllegalArgumentException("O arquivo ${rom.name} não contém uma ROM compatível.")
        } catch (error: IllegalArgumentException) {
            GameLog.error("jogo", "arquivo ZIP inválido ou ambíguo: ${rom.name}", error)
            throw error
        } catch (error: Exception) {
            GameLog.error("jogo", "falha ao extrair ${rom.name}", error)
            throw IllegalArgumentException("Não foi possível extrair a ROM de ${rom.name}.", error)
        }
    }

    private fun extract(context: Context, rom: File, wanted: Set<String>): File? = ZipFile(rom).use { zip ->
        val candidates = zip.entries().asSequence()
            .filter { !it.isDirectory && File(it.name).extension.lowercase() in wanted }
            .toList()
        require(candidates.size == 1) {
            when (candidates.size) {
                0 -> "O arquivo ${rom.name} não contém uma ROM com extensão reconhecida."
                else -> "O arquivo ${rom.name} contém ${candidates.size} ROMs possíveis. Extraia ou selecione o jogo desejado."
            }
        }
        val entry = candidates.single()
        val root = File(context.cacheDir, "unzipped")
        val folder = File(root, rom.absolutePath.hashCode().toString())
        val out = File(folder, "${rom.nameWithoutExtension}.${File(entry.name).extension}")
        val metadata = File(folder, "${out.name}.source")
        val sourceSignature = listOf(
            rom.absolutePath,
            rom.length().toString(),
            rom.lastModified().toString(),
            entry.name,
            entry.size.toString(),
            entry.crc.toString(),
        ).joinToString("\n")

        // O tamanho sozinho não identifica uma ROM: outra versão pode ter exatamente os mesmos bytes.
        // Reutilize o cache apenas quando os metadados da entrada ZIP também forem idênticos.
        if (out.isFile && out.length() == entry.size && metadata.isFile &&
            runCatching { metadata.readText() == sourceSignature }.getOrDefault(false)
        ) {
            return out
        }

        // Mantém só a extração do jogo atual.
        root.deleteRecursively()
        folder.mkdirs()
        val temp = File(folder, "${out.name}.part")
        val crc = CRC32()
        var copied = 0L
        zip.getInputStream(entry).use { input ->
            temp.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    crc.update(buffer, 0, read)
                    copied += read
                }
                output.flush()
            }
        }
        check(entry.size < 0L || copied == entry.size) {
            "ROM extraída incompleta: recebidos $copied de ${entry.size} bytes."
        }
        check(entry.crc < 0L || crc.value == entry.crc) {
            "A verificação CRC da ROM extraída falhou."
        }
        if (!temp.renameTo(out)) {
            temp.delete()
            return null
        }

        // Se não for possível gravar o marcador, a ROM ainda pode ser usada nesta sessão;
        // na próxima abertura ela será extraída novamente, em vez de reutilizar cache sem validação.
        val metadataTemp = File(folder, "${metadata.name}.part")
        runCatching {
            metadataTemp.writeText(sourceSignature)
            check(metadataTemp.renameTo(metadata))
        }.onFailure {
            metadataTemp.delete()
            metadata.delete()
            GameLog.error("jogo", "não foi possível registrar a origem do cache de ${rom.name}", it)
        }

        GameLog.log("jogo", "extraído de ${rom.name}: ${entry.name} (${out.length() / 1024} KB; CRC verificado)")
        out
    }
}
