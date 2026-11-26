package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipFile

/**
 * Muitos núcleos (Nintendo 64, DS, entre outros) não abrem ROM dentro de .zip e, em vez de recusar,
 * podem travar. Antes de abrir, o jogo é extraído para o cache com o nome do .zip (assim os saves
 * continuam com o mesmo nome). Jogos de disco ficam como estão: precisam de vários arquivos juntos.
 */
object RomUnzipper {

    fun prepare(context: Context, romPath: String, platform: GamePlatform?): File = prepare(File(romPath), platform, context.cacheDir)

    /** Separado do Context Android para validar a política de ZIPs em testes unitários. */
    internal fun prepare(rom: File, platform: GamePlatform?, cacheDir: File): File {
        if (!rom.extension.equals("zip", ignoreCase = true) || platform?.discBased == true || platform?.zipIsNativeRom == true) {
            return rom
        }
        return try {
            extract(rom, platform?.extensions.orEmpty(), cacheDir)
                ?: throw IllegalArgumentException("O arquivo ${rom.name} não contém uma ROM compatível.")
        } catch (error: IllegalArgumentException) {
            GameLog.error("jogo", "arquivo ZIP inválido ou ambíguo: ${rom.name}", error)
            throw error
        } catch (error: Exception) {
            GameLog.error("jogo", "falha ao extrair ${rom.name}", error)
            throw IllegalArgumentException("Não foi possível extrair a ROM de ${rom.name}.", error)
        }
    }

    internal fun extract(rom: File, wanted: Set<String>, cacheDir: File): File? = ZipFile(rom).use { zip ->
        val candidates = zip.entries().asSequence()
            .filter { !it.isDirectory && isSafeEntryName(it.name) && File(it.name).extension.lowercase() in wanted }
            .toList()
        require(candidates.size == 1) {
            when (candidates.size) {
                0 -> "O arquivo ${rom.name} não contém uma ROM compatível. Verifique a plataforma e as extensões cadastradas."
                else -> "O arquivo ${rom.name} contém ${candidates.size} ROMs possíveis. Extraia ou selecione o jogo desejado."
            }
        }
        val entry = candidates.single()
        require(entry.size >= 0L) { "A entrada ${entry.name} não informa um tamanho válido." }
        require(entry.crc >= 0L) { "A entrada ${entry.name} não informa um CRC válido." }

        val root = File(cacheDir, "unzipped")
        // SHA-256 evita colisões do hashCode de String e mantém caches de sessões diferentes isolados.
        val folderId = MessageDigest.getInstance("SHA-256")
            .digest(rom.absolutePath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val folder = File(root, folderId)
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

        // Não apaga o diretório inteiro: outra sessão pode estar usando o cache de outro jogo.
        check(folder.isDirectory || folder.mkdirs()) { "Não foi possível criar o cache da ROM." }
        val temp = File(folder, "${out.name}.part")
        val backup = File(folder, "${out.name}.backup")

        // Recupera uma promoção interrompida antes de iniciar outra extração. Se o processo
        // morreu entre out -> backup e .part -> out, o backup ainda é a única cópia anterior.
        if (!out.exists() && backup.isFile) {
            check(backup.renameTo(out)) {
                "Não foi possível restaurar a ROM em cache após uma interrupção."
            }
        } else if (out.exists() && backup.exists()) {
            // out já foi promovido; o backup é sobra de uma transação concluída parcialmente.
            check(backup.delete()) { "Não foi possível limpar o backup antigo da ROM em cache." }
        }
        temp.delete()

        // O tamanho sozinho não identifica uma ROM: outra versão pode ter exatamente os mesmos bytes.
        // Reutilize o cache apenas quando os metadados da entrada ZIP também forem idênticos.
        if (out.isFile && out.length() == entry.size && metadata.isFile &&
            runCatching {
                metadata.readText() == sourceSignature && crcMatches(out, entry.crc)
            }.getOrDefault(false)
        ) {
            return out
        }
        val crc = CRC32()
        var copied = 0L
        try {
            zip.getInputStream(entry).use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        crc.update(buffer, 0, read)
                        copied += read
                        check(copied <= entry.size) {
                            "A ROM extraída excedeu o tamanho declarado pela entrada ZIP."
                        }
                    }
                    output.flush()
                }
            }
        } catch (failure: Exception) {
            temp.delete()
            throw failure
        }
        try {
            check(copied == entry.size) {
                "ROM extraída incompleta: recebidos $copied de ${entry.size} bytes."
            }
            check(crc.value == entry.crc) {
                "A verificação CRC da ROM extraída falhou."
            }
        } catch (failure: Exception) {
            temp.delete()
            throw failure
        }

        // Substituição atômica com rollback: nunca descarta um cache válido antes da nova extração.
        val hadPrevious = out.exists()
        if (hadPrevious) check(out.renameTo(backup)) { "Não foi possível preservar a ROM em cache anterior." }
        try {
            check(temp.renameTo(out)) { "Não foi possível concluir a extração da ROM." }
        } catch (failure: Exception) {
            if (hadPrevious && backup.exists() && !backup.renameTo(out)) {
                throw IllegalStateException("Falha ao restaurar a ROM em cache anterior; backup preservado.", failure)
            }
            throw failure
        }

        val metadataTemp = File(folder, "${metadata.name}.part")
        try {
            metadataTemp.writeText(sourceSignature)
            check(metadataTemp.renameTo(metadata)) { "Não foi possível registrar a origem da ROM em cache." }
        } catch (failure: Exception) {
            metadataTemp.delete()
            // A ROM já foi integralmente validada e pode ser usada nesta sessão. Sem marcador,
            // ela será extraída novamente na próxima abertura.
            metadata.delete()
            GameLog.error("jogo", "não foi possível registrar a origem do cache de ${rom.name}", failure)
        } finally {
            backup.delete()
            temp.delete()
        }

        GameLog.log("jogo", "extraído de ${rom.name}: ${entry.name} (${out.length() / 1024} KB; CRC verificado)")
        out
    }

    private fun crcMatches(file: File, expected: Long): Boolean {
        val crc = CRC32()
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value == expected
    }

    /** Bloqueia nomes absolutos, traversal e segmentos vazios/dot antes de aceitar a entrada. */
    private fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank() || '\u0000' in name || name.startsWith('/') || name.startsWith('\\')) return false
        if (Regex("^[A-Za-z]:").containsMatchIn(name)) return false
        val normalized = name.replace('\\', '/')
        return normalized.split('/').none { it.isBlank() || it == "." || it == ".." }
    }
}
