package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * BIOS dos consoles: ficam na pasta de sistema dos núcleos, com o nome exato que o núcleo espera.
 * Vêm de três lugares: a pasta "bios" dentro da pasta de jogos, o firmware guardado no RomM ou um
 * arquivo escolhido à mão.
 */
object BiosManager {

    fun systemDir(context: Context): File = GameFolders.dir(context, FolderKind.Bios)

    /** Plataformas que usam algum BIOS. */
    fun platformsWithBios(): List<GamePlatform> = GamePlatforms.all.filter { it.bios.isNotEmpty() || it.biosGroups.isNotEmpty() }

    /** O nome pode trazer uma subpasta (ex.: "dc/dc_boot.bin"); o arquivo escolhido só precisa ter o mesmo nome final. */
    private fun BiosFile.fileOnly(): String = File(name).name.lowercase()

    fun has(context: Context, file: BiosFile): Boolean = File(systemDir(context), file.name).isFile

    /**
     * Cada grupo representa alternativas entre si; todos os grupos precisam ter pelo menos
     * um arquivo presente. Sem grupos explícitos, a lista antiga [GamePlatform.bios] continua
     * sendo tratada como um único grupo de alternativas.
     */
    private fun requiredBiosGroups(platform: GamePlatform): List<List<BiosFile>> = platform.biosGroups.ifEmpty { listOf(platform.bios) }

    /** O jogo pode rodar? BIOS opcionais dispensam arquivos; requisitos explícitos usam AND/OR. */
    fun isReady(context: Context, platform: GamePlatform): Boolean = when {
        !platform.biosRequired -> true

        platform.biosKit != null -> kitReady(context, platform)

        else -> requiredBiosGroups(platform).let { groups ->
            groups.isNotEmpty() && groups.all { group -> group.any { has(context, it) } }
        }
    }

    /** Plataformas que precisam de uma pasta de sistema inteira (ex.: blueMSX), em vez de um arquivo só. */
    fun platformsWithKit(): List<GamePlatform> = GamePlatforms.all.filter { it.biosKit != null }

    /** Cada subpasta pedida existe e tem pelo menos um arquivo dentro. */
    fun kitReady(context: Context, platform: GamePlatform): Boolean {
        val kit = platform.biosKit ?: return true
        return kit.all { folder -> File(systemDir(context), folder).let { it.isDirectory && it.walkTopDown().any(File::isFile) } }
    }

    /** Quantas das subpastas pedidas já têm algum arquivo. */
    fun kitStatus(context: Context, platform: GamePlatform): Pair<Int, Int> {
        val kit = platform.biosKit ?: return 0 to 0
        val ok = kit.count { folder -> File(systemDir(context), folder).let { it.isDirectory && it.walkTopDown().any(File::isFile) } }
        return ok to kit.size
    }

    /**
     * Copia para a pasta de sistema as subpastas pedidas (ex.: "Databases", "Machines") que
     * existirem dentro de [source], em qualquer nível até 2 pastas de profundidade. Devolve quantos
     * arquivos foram copiados.
     */
    fun importKit(context: Context, platform: GamePlatform, source: File): Int {
        val kit = platform.biosKit ?: return 0
        var copied = 0
        for (folder in kit) {
            val found = source.walkTopDown().maxDepth(2).firstOrNull { it.isDirectory && it.name.equals(folder, ignoreCase = true) } ?: continue
            val target = File(systemDir(context), folder)
            found.walkTopDown().filter { it.isFile }.forEach { file ->
                val dest = File(target, file.relativeTo(found).path)
                dest.parentFile?.mkdirs()
                runCatching { file.copyTo(dest, overwrite = true) }.onSuccess { copied++ }
            }
        }
        GameLog.log("bios", "${platform.slug}: pasta de sistema importada ($copied arquivos)")
        return copied
    }

    private fun canonical(name: String): BiosFile? {
        val lower = name.lowercase()
        return platformsWithBios()
            .flatMap { platform -> platform.bios + platform.biosGroups.flatten() }
            .distinctBy { it.name.lowercase() }
            .firstOrNull { it.fileOnly() == lower }
    }

    /** Copia o arquivo para a pasta de sistema se o nome for de um BIOS conhecido; devolve o BIOS ou nulo. */
    fun import(context: Context, displayName: String, input: InputStream): BiosFile? {
        val bios = canonical(File(displayName).name) ?: return null
        val target = File(systemDir(context), bios.name)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        val backup = File(target.parentFile, "${target.name}.backup")

        try {
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
            check(temp.isFile && temp.length() > 0L) { "Arquivo de BIOS vazio ou inválido" }
            val importedLength = temp.length()

            if (backup.exists()) check(backup.delete()) { "Não foi possível limpar o backup antigo da BIOS" }
            val hadPrevious = target.exists()
            if (hadPrevious) check(target.renameTo(backup)) { "Não foi possível preservar a BIOS anterior" }

            try {
                check(temp.renameTo(target)) { "Não foi possível instalar a nova BIOS" }
                check(target.isFile && target.length() == importedLength) { "A BIOS instalada não corresponde ao arquivo importado" }
            } catch (installFailure: Throwable) {
                if (target.exists() && !target.delete()) {
                    throw IllegalStateException(
                        "Falha na importação; não foi possível remover o arquivo incompleto. Backup preservado em ${backup.name}",
                        installFailure,
                    )
                }
                if (hadPrevious && backup.exists() && !backup.renameTo(target)) {
                    throw IllegalStateException(
                        "Falha na importação e restauração automática não concluída; backup preservado em ${backup.name}",
                        installFailure,
                    )
                }
                throw IllegalStateException("Nova BIOS rejeitada; arquivo anterior restaurado", installFailure)
            }

            if (backup.exists() && !backup.delete()) {
                GameLog.error(
                    "bios",
                    "BIOS ${bios.name} importada, mas não foi possível remover o backup ${backup.name}",
                    IllegalStateException("Falha ao excluir o backup após a importação"),
                )
            }
            return bios
        } finally {
            temp.delete()
        }
    }

    /** Procura BIOS na pasta "bios" da pasta de jogos e copia os que faltam. Devolve quantos copiou. */
    fun importFromFolder(context: Context, gamesRoot: File): Int {
        val dir = File(gamesRoot, "bios")
        if (!dir.isDirectory) return 0
        var count = 0
        dir.walkTopDown().maxDepth(3).filter { it.isFile }.forEach { file ->
            val bios = canonical(file.name) ?: return@forEach
            if (has(context, bios)) return@forEach
            file.inputStream().use { import(context, file.name, it) }?.let { count++ }
        }
        return count
    }

    /** Baixa do RomM os BIOS que faltam. Bloqueante. Devolve quantos baixou. */
    fun downloadFromServer(context: Context, client: RomMClient): Int {
        var count = 0
        for (firmware in client.listFirmware()) {
            val bios = canonical(firmware.fileName) ?: continue
            if (has(context, bios)) continue
            val target = File(systemDir(context), bios.name)
            target.parentFile?.mkdirs()
            client.downloadFirmware(firmware, target)
            count++
        }
        return count
    }
}
