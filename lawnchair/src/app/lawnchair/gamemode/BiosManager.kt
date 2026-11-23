package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

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
    fun isReady(context: Context, platform: GamePlatform): Boolean =
        isReady(platform, hasBios = { has(context, it) }, isKitReady = { kitReady(context, platform) })

    internal fun isReady(
        platform: GamePlatform,
        hasBios: (BiosFile) -> Boolean,
        isKitReady: () -> Boolean,
    ): Boolean = when {
        !platform.biosRequired -> true
        platform.biosKit != null -> isKitReady()
        else -> requiredBiosGroups(platform).let { groups ->
            groups.isNotEmpty() && groups.all { group -> group.any(hasBios) }
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
        if (kit.isEmpty()) return 0
        val root = systemDir(context).apply { mkdirs() }
        val suffix = "${platform.slug}-${System.nanoTime()}"
        val stagingRoot = File(root, ".bios-kit-stage-$suffix")
        val backupRoot = File(root, ".bios-kit-backup-$suffix")
        val sources = kit.associateWith { folder ->
            source.walkTopDown().maxDepth(2).firstOrNull {
                it.isDirectory && it.name.equals(folder, ignoreCase = true)
            } ?: throw IllegalArgumentException("A pasta obrigatória '$folder' não foi encontrada no kit")
        }

        var copied = 0
        val backedUp = mutableSetOf<String>()
        val promoted = mutableSetOf<String>()
        try {
            // Prepara e valida todas as pastas antes de tocar na instalação existente.
            for (folder in kit) {
                val sourceFolder = sources.getValue(folder)
                val stagedFolder = File(stagingRoot, folder)
                val files = sourceFolder.walkTopDown().filter { it.isFile }.toList()
                check(files.isNotEmpty()) { "A pasta '$folder' está vazia" }
                files.forEach { file ->
                    val destination = File(stagedFolder, file.relativeTo(sourceFolder).path)
                    check(destination.canonicalPath.startsWith(stagedFolder.canonicalPath + File.separator)) {
                        "Caminho inválido dentro do kit '$folder'"
                    }
                    destination.parentFile?.mkdirs()
                    file.copyTo(destination, overwrite = false)
                    copied++
                }
                check(stagedFolder.walkTopDown().any(File::isFile)) { "A pasta '$folder' não foi preparada corretamente" }
            }

            // Preserva as pastas antigas antes de promover qualquer pasta nova.
            for (folder in kit) {
                val target = File(root, folder)
                if (target.exists()) {
                    val backup = File(backupRoot, folder)
                    backup.parentFile?.mkdirs()
                    check(target.renameTo(backup)) { "Não foi possível preservar a pasta existente '$folder'" }
                    backedUp += folder
                }
            }

            for (folder in kit) {
                val staged = File(stagingRoot, folder)
                val target = File(root, folder)
                check(staged.renameTo(target)) { "Não foi possível instalar a pasta '$folder'" }
                promoted += folder
            }
        } catch (failure: Exception) {
            // Reverte em ordem inversa; se a restauração falhar, mantém o backup para recuperação manual.
            for (folder in kit.asReversed()) {
                val target = File(root, folder)
                if (folder in promoted && target.exists()) {
                    check(target.deleteRecursively()) { "Falha ao remover a pasta incompleta '$folder' durante a reversão" }
                }
                if (folder in backedUp) {
                    val backup = File(backupRoot, folder)
                    check(backup.renameTo(target)) { "Falha ao restaurar '$folder'; backup preservado em ${backup.absolutePath}" }
                }
            }
            throw IllegalStateException("Não foi possível importar o kit de BIOS; instalação anterior preservada", failure)
        } finally {
            stagingRoot.deleteRecursively()
        }

        if (backupRoot.exists() && !backupRoot.deleteRecursively()) {
            GameLog.error(
                "bios",
                "${platform.slug}: kit instalado, mas os backups antigos não puderam ser removidos (${backupRoot.name})",
                IllegalStateException("Falha ao limpar backups do kit"),
            )
        }
        GameLog.log("bios", "${platform.slug}: kit de sistema importado com segurança ($copied arquivos)")
        return copied
    }

    /** Integridade dos arquivos locais comparada apenas a metadados publicados pelo RomM. */
    enum class FirmwareIntegrity {
        VERIFIED,
        MISMATCH,
        UNKNOWN,
    }

    fun validateLocalFirmware(context: Context, client: RomMClient? = null): Map<String, FirmwareIntegrity> {
        val results = linkedMapOf<String, FirmwareIntegrity>()
        val localBios = platformsWithBios()
            .flatMap { platform -> platform.bios + platform.biosGroups.flatten() }
            .distinctBy { it.name.lowercase() }
        for (bios in localBios) {
            val file = File(systemDir(context), bios.name)
            if (!file.isFile) continue
            results[bios.name] = if (bios.md5Hash == null) {
                FirmwareIntegrity.UNKNOWN
            } else if (runCatching { verifyKnownMd5(file, bios) }.isSuccess) {
                FirmwareIntegrity.VERIFIED
            } else {
                FirmwareIntegrity.MISMATCH
            }
        }

        // Quando não há hash oficial embutido, usa apenas metadados de integridade publicados pelo RomM.
        if (client != null) {
            for (firmware in client.listFirmware()) {
                val bios = canonical(File(firmware.fileName).name) ?: continue
                if (bios.md5Hash != null || bios.name !in results) continue
                val file = File(systemDir(context), bios.name)
                val hasReference = firmware.sizeBytes != null ||
                    firmware.crcHash != null ||
                    firmware.md5Hash != null ||
                    firmware.sha1Hash != null
                if (!hasReference) continue
                results[bios.name] = if (runCatching { client.verifyFirmwareFile(file, firmware) }.isSuccess) {
                    FirmwareIntegrity.VERIFIED
                } else {
                    FirmwareIntegrity.MISMATCH
                }
            }
        }
        return results
    }

    private fun verifyKnownMd5(file: File, bios: BiosFile) {
        val expected = bios.md5Hash ?: return
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        check(actual.equals(expected, ignoreCase = true)) {
            "Integridade da BIOS ${bios.name} inválida: MD5 não corresponde à referência oficial"
        }
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
            verifyKnownMd5(temp, bios)
            val importedLength = temp.length()

            if (backup.exists()) {
                if (!target.exists()) {
                    check(backup.renameTo(target)) { "Não foi possível recuperar o backup anterior da BIOS" }
                } else {
                    check(backup.delete()) { "Não foi possível limpar o backup antigo da BIOS" }
                }
            }
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
                val message = if (hadPrevious) {
                    "Nova BIOS rejeitada; arquivo anterior restaurado"
                } else {
                    "Nova BIOS rejeitada; não havia arquivo anterior para restaurar"
                }
                throw IllegalStateException(message, installFailure)
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
            runCatching { file.inputStream().use { import(context, file.name, it) } }
                .onSuccess { if (it != null) count++ }
                .onFailure { GameLog.error("bios", "falha ao importar ${file.name} da pasta local", it) }
        }
        return count
    }

    /** Baixa do RomM os BIOS que faltam. Bloqueante. Devolve quantos baixou. */
    fun downloadFromServer(context: Context, client: RomMClient): Int {
        var count = 0
        for (firmware in client.listFirmware()) {
            val bios = canonical(File(firmware.fileName).name) ?: continue
            val target = File(systemDir(context), bios.name)
            if (target.isFile) {
                val officialCheck = if (bios.md5Hash != null) {
                    runCatching { verifyKnownMd5(target, bios) }.isSuccess
                } else {
                    val hasReference = firmware.sizeBytes != null ||
                        firmware.crcHash != null ||
                        firmware.md5Hash != null ||
                        firmware.sha1Hash != null
                    !hasReference || runCatching { client.verifyFirmwareFile(target, firmware) }.isSuccess
                }
                if (officialCheck) continue
            }
            target.parentFile?.mkdirs()
            client.downloadFirmware(firmware, target, expectedMd5 = bios.md5Hash)
            count++
        }
        return count
    }
}
