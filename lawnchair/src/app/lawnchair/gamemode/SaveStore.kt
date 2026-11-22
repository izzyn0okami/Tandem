package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Resolves a stable game identity without using the ROM filename as the primary key.
 * RomM-linked games use server + RomM game ID; local games use platform + content fingerprint.
 */
internal data class RomMConnectionSnapshot(
    val url: String,
    val token: String,
    val server: String,
    val accountFingerprint: String,
)

internal object GameIdentityResolver {
    fun resolve(context: Context, romFile: File, platformSlug: String?): String {
        val fingerprint = cachedFingerprint(context, romFile)
        val prefs = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
        val directId = prefs.getLong("rom:${romFile.absolutePath}", -1L)
        val storedServer = prefs.getString("rom_server:${romFile.absolutePath}", null)
        val contentLink = parseContentLink(prefs.getString(contentLinkKey(fingerprint), null))
        val server = if (directId >= 0L) storedServer else contentLink?.server
        val linkedId = if (directId >= 0L) directId else contentLink?.romId ?: -1L
        return when {
            linkedId >= 0L && server != null -> "romm-${digest("$server|$linkedId")}"
            directId >= 0L -> "romm-legacy-${digest("${platformSlug.orEmpty()}|$directId")}"
            else -> {
                "local-${digest("${platformSlug.orEmpty()}|$fingerprint")}"
            }
        }
    }

    fun hasRomMLink(context: Context, romFile: File): Boolean {
        val prefs = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
        return prefs.getLong("rom:${romFile.absolutePath}", -1L) >= 0L ||
            parseContentLink(prefs.getString(contentLinkKey(cachedFingerprint(context, romFile)), null)) != null
    }

    fun linkedRomId(
        context: Context,
        romFile: File,
        connection: RomMConnectionSnapshot? = connectionSnapshot(context),
    ): Long? {
        val prefs = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
        val direct = prefs.getLong("rom:${romFile.absolutePath}", -1L)
        connection ?: return null
        val server = connection.server
        val account = connection.accountFingerprint
        if (direct >= 0L) {
            val storedServer = prefs.getString("rom_server:${romFile.absolutePath}", null)
            val storedAccount = prefs.getString("rom_account:${romFile.absolutePath}", null)
            return direct.takeIf { storedServer == server && storedAccount == account }
        }
        val link = parseContentLink(prefs.getString(contentLinkKey(cachedFingerprint(context, romFile)), null)) ?: return null
        return link.romId.takeIf { link.server == server && link.accountFingerprint == account }
    }

    fun connectionSnapshot(context: Context): RomMConnectionSnapshot? {
        val url = RomMSettings.url(context)?.takeIf { it.isNotBlank() } ?: return null
        val token = RomMSettings.token(context)?.takeIf { it.isNotBlank() } ?: return null
        val server = normalizedServer(url) ?: return null
        return RomMConnectionSnapshot(url, token, server, digest("$server|$token"))
    }

    fun connectionFingerprint(context: Context): String? = connectionSnapshot(context)?.accountFingerprint

    /** Fingerprint of the credentials that originally linked this game, even while disconnected. */
    fun originAccountFingerprint(context: Context, romFile: File): String? {
        val prefs = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
        val direct = prefs.getLong("rom:${romFile.absolutePath}", -1L)
        if (direct >= 0L) return prefs.getString("rom_account:${romFile.absolutePath}", null)
        return parseContentLink(prefs.getString(contentLinkKey(cachedFingerprint(context, romFile)), null))?.accountFingerprint
    }

    /** Finds a moved ROM by its stable identity, without trusting the old path. */
    fun findRomPath(context: Context, identity: String, preferredPath: String, platformSlug: String?): String? {
        val preferred = File(preferredPath)
        if (preferred.isFile && resolve(context, preferred, platformSlug) == identity) return preferred.absolutePath
        val root = File(GameLibrarySettings.rootPath(context))
        return runCatching { GameLibraryScanner.scan(root) }.getOrNull().orEmpty()
            .firstOrNull { game -> resolve(context, File(game.path), game.platform.slug) == identity }
            ?.path
    }

    /** Adds a content-based association so a moved or renamed downloaded ROM keeps its RomM link. */
    fun link(context: Context, romFile: File, romId: Long) {
        val connection = connectionSnapshot(context) ?: return
        if (!romFile.isFile) return
        val fingerprint = fingerprint(romFile)
        val prefs = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
        val key = contentLinkKey(fingerprint)
        val encoded = "${connection.server}|${connection.accountFingerprint}|$romId"
        val existing = prefs.getString(key, null)
        prefs.edit {
            putLong("rom:${romFile.absolutePath}", romId)
            putString("rom_server:${romFile.absolutePath}", connection.server)
            putString("rom_account:${romFile.absolutePath}", connection.accountFingerprint)
            // A content fingerprint shared by different server records is ambiguous after a move.
            if (existing == null || existing == encoded) putString(key, encoded)
            else putString(key, "ambiguous")
        }
    }

    /**
     * Legacy directories are migrated only when the configured library contains exactly one
     * recognized game with the same sanitized basename, and it is the ROM being opened.
     * All source directories are retained after a verified copy.
     */
    fun legacySources(
        context: Context,
        savesRoot: File,
        romFile: File,
        platformSlug: String?,
        coreIds: List<String>,
        stableDirectoryName: String,
    ): Map<String, File> {
        val targetExists = coreIds.all { File(File(savesRoot, it), stableDirectoryName).exists() }
        if (targetExists) return emptyMap()

        val libraryRoot = File(GameLibrarySettings.rootPath(context))
        val canonicalRom = runCatching { romFile.canonicalFile }.getOrNull() ?: return emptyMap()
        val canonicalRoot = runCatching { libraryRoot.canonicalFile }.getOrNull() ?: return emptyMap()
        if (!canonicalRom.toPath().startsWith(canonicalRoot.toPath())) return emptyMap()

        val legacyName = sanitize(romFile.nameWithoutExtension)
        val candidates = runCatching { GameLibraryScanner.scan(libraryRoot) }.getOrNull().orEmpty()
            .filter { sanitize(File(it.path).nameWithoutExtension) == legacyName }
        if (candidates.size != 1 || candidates.single().platform.slug != platformSlug) {
            if (candidates.size > 1) {
                runCatching { GameLog.log("saves", "migração legada ambígua para $legacyName; diretórios antigos preservados") }
            }
            return emptyMap()
        }
        if (runCatching { File(candidates.single().path).canonicalFile }.getOrNull() != canonicalRom) return emptyMap()

        return coreIds.distinct().associateWith { coreId ->
            File(File(savesRoot, coreId), legacyName)
        }.filterValues { it.isDirectory }
    }

    fun migrateLegacy(source: File, target: File): Boolean {
        if (!source.isDirectory || target.exists()) return false
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false
        val temp = File(parent, ".${target.name}.migration")
        if (temp.exists() && !temp.deleteRecursively()) return false
        return runCatching {
            check(temp.mkdirs())
            source.walkTopDown().filter { it.isFile }.forEach { file ->
                val relative = file.relativeTo(source)
                val copy = File(temp, relative.path)
                check(copy.parentFile?.exists() == true || copy.parentFile?.mkdirs() == true)
                file.copyTo(copy, overwrite = false)
                check(file.length() == copy.length())
                check(fileHash(file) == fileHash(copy))
            }
            val sourceFiles = source.walkTopDown().filter { it.isFile }.map { it.relativeTo(source).path }.toSet()
            val copiedFiles = temp.walkTopDown().filter { it.isFile }.map { it.relativeTo(temp).path }.toSet()
            check(sourceFiles == copiedFiles)
            check(temp.renameTo(target))
            runCatching { GameLog.log("saves", "save legado migrado e validado para identidade estável; origem preservada") }
            true
        }.getOrElse {
            temp.deleteRecursively()
            runCatching { GameLog.error("saves", "falha ao migrar save legado; origem preservada", it) }
            false
        }
    }

    private fun cachedFingerprint(context: Context, romFile: File): String {
        val canonicalPath = runCatching { romFile.canonicalPath }.getOrDefault(romFile.absolutePath)
        val cacheKey = "fingerprint:${digest(canonicalPath)}"
        val signature = sourceSignature(romFile)
        val prefs = context.getSharedPreferences("game_identity_cache", Context.MODE_PRIVATE)
        if (prefs.getString("$cacheKey:signature", null) == signature) {
            prefs.getString("$cacheKey:value", null)?.let { return it }
        }
        val value = fingerprint(romFile)
        prefs.edit {
            putString("$cacheKey:signature", signature)
            putString("$cacheKey:value", value)
        }
        return value
    }

    /** Cheap invalidation signature; large ROM bytes are hashed only when the source changes. */
    private fun sourceSignature(romFile: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val visited = mutableSetOf<String>()
        fun addFile(file: File, depth: Int) {
            val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (depth > 4 || !file.isFile || !visited.add(canonical)) return
            digest.update(file.extension.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
            digest.update(file.length().toString().toByteArray(Charsets.UTF_8))
            digest.update(file.lastModified().toString().toByteArray(Charsets.UTF_8))
            val extension = file.extension.lowercase(Locale.ROOT)
            if (extension == "m3u" || extension == "cue") digest.update(fileHash(file).toByteArray(Charsets.UTF_8))
            val references = when (extension) {
                "m3u" -> file.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
                "cue" -> file.readLines().mapNotNull { line ->
                    Regex("""(?i)^\\s*FILE\\s+"([^"]+)"""").find(line)?.groupValues?.getOrNull(1)
                }
                else -> emptyList()
            }
            references.forEach { addFile(File(file.parentFile, it), depth + 1) }
        }
        addFile(romFile, 0)
        return digest.digest().toHex()
    }

    fun fingerprint(romFile: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val visited = mutableSetOf<String>()
        fun addFile(file: File, depth: Int) {
            val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
            if (depth > 4 || !file.isFile || !visited.add(canonical)) return
            when (file.extension.lowercase(Locale.ROOT)) {
                "m3u" -> {
                    digest.update("playlist".toByteArray(Charsets.UTF_8))
                    file.readLines().map(String::trim)
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .forEachIndexed { index, reference ->
                            digest.update(index.toString().toByteArray(Charsets.UTF_8))
                            addFile(File(file.parentFile, reference), depth + 1)
                        }
                }
                "cue" -> {
                    digest.update("cue".toByteArray(Charsets.UTF_8))
                    val lines = file.readLines()
                    val references = mutableListOf<String>()
                    val normalizedLines = lines.map { line ->
                        val match = Regex("""(?i)^\s*FILE\s+"([^"]+)"""").find(line)
                        if (match != null) {
                            references += match.groupValues[1]
                            Regex("""(?i)^\s*FILE\s+"[^"]+"""").replace(line, "FILE <DISC>")
                        } else {
                            line.trim()
                        }
                    }
                    digest.update(normalizedLines.joinToString("\n").toByteArray(Charsets.UTF_8))
                    references.forEachIndexed { index, reference ->
                        digest.update(index.toString().toByteArray(Charsets.UTF_8))
                        addFile(File(file.parentFile, reference), depth + 1)
                    }
                }
                else -> digest.update(fileHash(file).toByteArray(Charsets.UTF_8))
            }
        }
        addFile(romFile, 0)
        return digest.digest().toHex()
    }

    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private data class ContentLink(val server: String, val accountFingerprint: String, val romId: Long)

    private fun contentLinkKey(fingerprint: String) = "game_link_content:$fingerprint"

    private fun parseContentLink(value: String?): ContentLink? {
        val parts = value?.split('|') ?: return null
        if (parts.size != 3) return null
        val romId = parts[2].toLongOrNull() ?: return null
        return ContentLink(parts[0], parts[1], romId)
    }

    private fun normalizedServer(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return runCatching {
            val uri = java.net.URI(value.trim().trimEnd('/'))
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return@runCatching null
            val host = uri.host?.lowercase(Locale.ROOT) ?: return@runCatching null
            val port = when {
                uri.port == 80 && scheme == "http" -> -1
                uri.port == 443 && scheme == "https" -> -1
                else -> uri.port
            }
            java.net.URI(scheme, uri.userInfo, host, port, uri.path?.trimEnd('/').orEmpty(), null, null).toASCIIString().trimEnd('/')
        }.getOrNull()
    }

    private fun sanitize(value: String) = value.replace(Regex("[^A-Za-z0-9._ -]"), "_")

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Saves por jogo, na pasta de saves escolhida (padrão: dentro do app) em `<núcleo>/<jogo>/` com `sram.srm`
 * (save de bateria, gravado sozinho) e `state1.state` (estado rápido, gravado pelo menu).
 */
class SaveStore internal constructor(
    private val savesRoot: File,
    private val coreId: String,
    romFile: File,
    /** Outros núcleos da mesma plataforma: o save de bateria deles serve como ponto de partida. */
    private val siblingCores: List<String> = emptyList(),
    gameIdentity: String? = null,
    private val migrationSources: Map<String, File> = emptyMap(),
) {
    constructor(
        context: Context,
        coreId: String,
        romFile: File,
        siblingCores: List<String> = emptyList(),
        platformSlug: String? = null,
    ) : this(
        GameFolders.dir(context, FolderKind.Saves),
        coreId,
        romFile,
        siblingCores,
        GameIdentityResolver.resolve(context, romFile, platformSlug),
        GameIdentityResolver.legacySources(
            context,
            GameFolders.dir(context, FolderKind.Saves),
            romFile,
            platformSlug,
            listOf(coreId) + siblingCores,
            GameIdentityResolver.resolve(context, romFile, platformSlug),
        ),
    )

    private val legacyGameDirName: String = romFile.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._ -]"), "_")
    private val gameDirName: String = gameIdentity?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) } ?: legacyGameDirName
    private fun directoryForCore(targetCoreId: String): File {
        val stable = File(File(savesRoot, targetCoreId), gameDirName)
        if (stable.isDirectory) return stable
        val legacy = migrationSources[targetCoreId]
        if (legacy != null && legacy.isDirectory && !GameIdentityResolver.migrateLegacy(legacy, stable)) {
            return legacy
        }
        stable.mkdirs()
        return stable
    }

    private val dir: File = directoryForCore(coreId)

    init {
        siblingCores.filter { it != coreId }.forEach(::directoryForCore)
    }

    val sramFile = File(dir, "sram.srm")
    val stateFile = File(dir, "state1.state")

    private fun raProgressFile(user: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(user.toByteArray(Charsets.UTF_8))
        val accountKey = digest.take(12).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        return File(dir, "ra-progress-$accountKey.bin")
    }

    /** Progresso interno do rc_client, separado dos estados de emulação (inclusive em Hardcore). */
    fun readRaProgress(user: String): ByteArray? = raProgressFile(user).takeIf { it.isFile && it.length() > 0 }?.readBytes()

    fun writeRaProgress(user: String, bytes: ByteArray) = write(raProgressFile(user), bytes)

    fun deleteRaProgress(user: String) {
        raProgressFile(user).delete()
    }

    /** Cópias de segurança de saves substituídos durante a sincronização. */
    val backupDir = File(dir, "backups")

    /** Save de bateria deste núcleo; se não houver, o mais recente de outro núcleo do mesmo console (estados não servem entre núcleos). */
    fun readSram(): ByteArray? {
        sramFile.takeIf { it.isFile && it.length() > 0 }?.let { return it.readBytes() }
        val other = siblingCores
            .filter { it != coreId }
            .map { File(directoryForCore(it), "sram.srm") }
            .filter { it.isFile && it.length() > 0 }
            .maxByOrNull { it.lastModified() }
        if (other != null) GameLog.log("saves", "usando o save de ${other.parentFile?.parentFile?.name} para $coreId")
        return other?.readBytes()
    }

    fun writeSram(bytes: ByteArray) = write(sramFile, bytes)

    fun hasState(): Boolean = stateFile.isFile && stateFile.length() > 0

    fun readState(): ByteArray? = stateFile.takeIf { hasState() }?.readBytes()

    fun writeState(bytes: ByteArray) = write(stateFile, bytes)

    /** Grava por arquivo temporário e renomeia, para um corte de energia não estragar o save. */
    @Synchronized
    private fun write(target: File, bytes: ByteArray) {
        val temp = File(dir, "${target.name}.tmp")
        temp.writeBytes(bytes)
        // rename(2) substitui o destino atomicamente neste filesystem; não remova o save antigo
        // antes, pois uma interrupção entre delete e rename poderia perder o último progresso.
        check(temp.renameTo(target)) { "Não consegui gravar ${target.name}" }
    }
}