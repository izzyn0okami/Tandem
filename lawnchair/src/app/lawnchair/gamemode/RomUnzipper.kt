package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.util.zip.ZipFile

/**
 * Muitos núcleos (Nintendo 64, DS, entre outros) não abrem ROM dentro de .zip e, em vez de recusar,
 * podem travar. Antes de abrir, o jogo é extraído para o cache com o nome do .zip (assim os saves
 * continuam com o mesmo nome). Jogos de disco ficam como estão: precisam de vários arquivos juntos.
 */
object RomUnzipper {

    fun prepare(context: Context, romPath: String, platform: GamePlatform?): File {
        val rom = File(romPath)
        if (!rom.extension.equals("zip", ignoreCase = true) || platform?.discBased == true) return rom
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
        if (out.isFile && out.length() == entry.size) return out
        // Mantém só a extração do jogo atual.
        root.deleteRecursively()
        folder.mkdirs()
        val temp = File(folder, "${out.name}.part")
        zip.getInputStream(entry).use { input -> temp.outputStream().use { input.copyTo(it) } }
        if (!temp.renameTo(out)) return null
        GameLog.log("jogo", "extraído de ${rom.name}: ${entry.name} (${out.length() / 1024} KB)")
        out
    }
}
