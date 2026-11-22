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
internal data class LegacyMigrationPlan(
    val sources: Map<String, File>,
    val ambiguous: Boolean,
)

internal data class RomMConnectionSnapshot(
    val url: String,
    val token: String,
    val server: String,
    val accountFingerprint: String,
)

internal object GameIdentityResolver {
    private val cueFileReference = Regex("""(?i)^\s*FILE\s+(?:"([^"]+)"|(\S+))""")
    private val cueFileDirective = Regex("""(?i)^\s*FILE\s+(?:"[^"]+"|\S+)""")

    private fun referencedFile(parent: File?, reference: String): File =
        File(parent, reference.replace('\\', '/'))
    fun resolve(context: Context, romFile: File, platformSlug: String?): String =
        resolveWithFingerprint(context, romFile, platformSlug, cachedFingerprint(context, romFile))

    fun cachedIdentity(context: Context, romFile: File, platformSlug: String?): String? {
        val fingerprint = cachedFingerprintIfAvailable(context, romFile) ?: return null
        return resolveWithFingerprint(context, romFile, platformSlug, fingerprint)
    }

    private fun resolveWithFingerprint(
        context: Context,
        romFile: File,
        platformSlug: String?,
        fingerprint: String,
    ): String {
        val prefs = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
        val storedDirectId = prefs.getLong("rom:${romFile.absolutePath}", -1L)
        val directFingerprint = prefs.getString("rom_fingerprint:${romFile.absolutePath}", null)
        val directId = storedDirectId.takeIf { isSameGameFingerprint(directFingerprint, fingerprint) } ?: -1L
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
        val direct = prefs.getLong("rom:${romFile.absolutePath}", -1L)
        if (direct >= 0L) {
            val stored = prefs.getString("rom_fingerprint:${romFile.absolutePath}", null)
            if (stored == null) return true // vínculo antigo: exige reassociação explícita, sem sincronizar às cegas
            val current = cachedFingerprintIfAvailable(context, romFile) ?: return false
            if (isSameGameFingerprint(stored, current)) return true
            // O caminho pode ter sido reutilizado por outro jogo que já tenha vínculo por conteúdo.
        }
        val fingerprint = cachedFingerprintIfAvailable(context, romFile) ?: return false
        return parseContentLink(prefs.getString(contentLinkKey(fingerprint), null)) != null
    }

    internal fun isSameGameFingerprint(stored: String?, current: String): Boolean =
        !stored.isNullOrBlank() && stored == current

    fun linkedRomId(
        context: Context,
        romFile: File,
        connection: RomMConnectionSnapshot? = connectionSnapshot(context),
    ): Long? {
        val prefs = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
        val direct = prefs.getLong("rom:${romFile.absolutePath}", -1L)
        val currentFingerprint = cachedFingerprint(context, romFile)
        val directFingerprint = prefs.getString("rom_fingerprint:${romFile.absolutePath}", null)
        val verifiedDirect = direct.takeIf { isSameGameFingerprint(directFingerprint, currentFingerprint) } ?: -1L
        connection ?: return null
        val server = connection.server
        val account = connection.accountFingerprint
        if (verifiedDirect >= 0L) {
            val storedServer = prefs.getString("rom_server:${romFile.absolutePath}", null)
            val storedAccount = prefs.getString("rom_account:${romFile.absolutePath}", null)
            return verifiedDirect.takeIf { storedServer == server && storedAccount == account }
        }
        val link = parseContentLink(prefs.getString(contentLinkKey(currentFingerprint), null)) ?: return null
        return link.romId.takeIf { link.server == server && link.accountFingerprint == account }
    }

    fun connectionSnapshot(context: Context): RomMConnectionSnapshot? {
        val url = RomMSettings.url(context)?.takeIf { it.isNotBlank() } ?: return null
        val token = RomMSettings.token(context)?.takeIf { it.isNotBlank() } ?: return null
        val server = normalizedServer(url) ?: return null
        return RomMConnectionSnapshot(url, token, server, digest("$server|$token"))
    }

    /** Fingerprint of the credentials that originally linked this game, even while disconnected. */
    fun originAccountFingerprint(
        context: Context,
        romFile: File,
        allowHash: Boolean = false,
    ): String? {
        val prefs = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
        val direct = prefs.getLong("rom:${romFile.absolutePath}", -1L)
        val fingerprint = if (allowHash) {
            cachedFingerprint(context, romFile)
        } else {
            cachedFingerprintIfAvailable(context, romFile) ?: return null
        }
        val directFingerprint = prefs.getString("rom_fingerprint:${romFile.absolutePath}", null)
        if (direct >= 0L && isSameGameFingerprint(directFingerprint, fingerprint)) {
            return prefs.getString("rom_account:${romFile.absolutePath}", null)
        }
        return parseContentLink(prefs.getString(contentLinkKey(fingerprint), null))?.accountFingerprint
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
        val fingerprint = cachedFingerprint(context, romFile)
        val prefs = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
        val key = contentLinkKey(fingerprint)
        val encoded = "${connection.server}|${connection.accountFingerprint}|$romId"
        val existing = prefs.getString(key, null)
        val existingLink = parseContentLink(existing)
        val sameRemoteGame = existingLink?.server == connection.server && existingLink.romId == romId
        prefs.edit {
            putLong("rom:${romFile.absolutePath}", romId)
            putString("rom_fingerprint:${romFile.absolutePath}", fingerprint)
            putString("rom_server:${romFile.absolutePath}", connection.server)
            putString("rom_account:${romFile.absolutePath}", connection.accountFingerprint)
            // Token rotation for the same server/game may update the account fingerprint.
            // Different remote records sharing identical bytes remain ambiguous after a move.
            if (existing == null || existing == encoded || sameRemoteGame) putString(key, encoded)
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
    ): Map<String, File> = planLegacyMigration(
        context,
        savesRoot,
        romFile,
        platformSlug,
        coreIds,
        stableDirectoryName,
    ).sources

    fun planLegacyMigration(
        context: Context,
        savesRoot: File,
        romFile: File,
        platformSlug: String?,
        coreIds: List<String>,
        stableDirectoryName: String,
    ): LegacyMigrationPlan {
        val legacyName = sanitize(romFile.nameWithoutExtension)
        val currentCore = coreIds.firstOrNull()
        val currentStable = currentCore?.let { File(File(savesRoot, it), stableDirectoryName) }
        val currentHasSave = currentStable?.let { stable ->
            listOf("sram.srm", "state1.state").any { name ->
                File(stable, name).let { it.isFile && it.length() > 0L }
            }
        } == true
        if (currentHasSave) return LegacyMigrationPlan(emptyMap(), ambiguous = false)

        val legacyDataExists = coreIds.distinct().any { coreId ->
            File(File(savesRoot, coreId), legacyName)
                .walkTopDown()
                .any { it.isFile && it.length() > 0L }
        }
        if (!legacyDataExists) return LegacyMigrationPlan(emptyMap(), ambiguous = false)

        val libraryRoot = File(GameLibrarySettings.rootPath(context))
        val canonicalRom = runCatching { romFile.canonicalFile }.getOrNull()
            ?: return LegacyMigrationPlan(emptyMap(), ambiguous = true)
        val canonicalRoot = runCatching { libraryRoot.canonicalFile }.getOrNull()
            ?: return LegacyMigrationPlan(emptyMap(), ambiguous = true)
        if (!canonicalRom.toPath().startsWith(canonicalRoot.toPath())) {
            return LegacyMigrationPlan(emptyMap(), ambiguous = true)
        }

        val candidates = runCatching { GameLibraryScanner.scan(libraryRoot) }.getOrNull().orEmpty()
            .filter { sanitize(File(it.path).nameWithoutExtension) == legacyName }
        val uniqueCandidate = candidates.singleOrNull()
        val candidatePath = uniqueCandidate?.let { runCatching { File(it.path).canonicalFile }.getOrNull() }
        if (uniqueCandidate?.platform?.slug != platformSlug || candidatePath != canonicalRom) {
            runCatching {
                GameLog.log(
                    "saves",
                    "migração legada ambígua para $legacyName; diretórios antigos preservados",
                )
            }
            return LegacyMigrationPlan(emptyMap(), ambiguous = true)
        }

        val sources = coreIds.distinct().associateWith { coreId ->
            File(File(savesRoot, coreId), legacyName)
        }.filterValues { it.isDirectory }
        return LegacyMigrationPlan(sources, ambiguous = false)
    }

    @Synchronized
    fun migrateLegacy(source: File, target: File): Boolean {
        if (!source.isDirectory || (target.exists() && !target.isDirectory)) return false
        val parent = target.parentFile ?: return false
        if (!parent.exists() && !parent.mkdirs()) return false

        val recoveryPrefix = ".${target.name}.pre-migration"
        if (!target.exists()) {
            val recovery = parent.listFiles()
                ?.filter { it.name.startsWith(recoveryPrefix) && it.isDirectory }
                ?.maxByOrNull { it.lastModified() }
            if (recovery != null && !recovery.renameTo(target)) return false
        }

        val temp = File(parent, ".${target.name}.migration")
        if (temp.exists() && !temp.deleteRecursively()) return false
        var previous: File? = null
        return runCatching {
            check(temp.mkdirs())
            val expectedHashes = linkedMapOf<String, String>()
            val conflictingFiles = mutableListOf<String>()

            fun copyTree(from: File, preserveExisting: Boolean) {
                from.walkTopDown().filter { it.isFile }.forEach { file ->
                    val relative = file.relativeTo(from).path
                    val destination = File(temp, relative)
                    val sourceHash = fileHash(file)
                    if (destination.exists() && destination.length() == 0L && file.length() > 0L) {
                        check(destination.delete())
                    }
                    if (destination.exists()) {
                        val existingHash = fileHash(destination)
                        if (existingHash != sourceHash) {
                            check(preserveExisting) {
                                "save collision inside stable target: $relative"
                            }
                            conflictingFiles += relative
                        }
                    } else {
                        destination.parentFile?.let { check(it.exists() || it.mkdirs()) }
                        file.copyTo(destination)
                    }
                    check(fileHash(file) == sourceHash)
                    val chosenHash = fileHash(destination)
                    expectedHashes[relative] = chosenHash
                }
            }

            if (target.isDirectory) copyTree(target, preserveExisting = false)
            copyTree(source, preserveExisting = true)

            val copiedFiles = temp.walkTopDown()
                .filter { it.isFile }
                .map { it.relativeTo(temp).path }
                .toSet()
            check(copiedFiles == expectedHashes.keys)
            check(expectedHashes.all { (relative, expected) ->
                fileHash(File(temp, relative)) == expected
            })
            if (conflictingFiles.isNotEmpty()) {
                runCatching {
                    GameLog.log(
                        "saves",
                        "arquivos legados conflitantes preservados na origem: ${conflictingFiles.joinToString()}",
                    )
                }
            }

            if (target.exists()) {
                var suffix = 0
                var backup = File(parent, recoveryPrefix)
                while (backup.exists()) {
                    suffix += 1
                    backup = File(parent, "$recoveryPrefix-$suffix")
                }
                check(target.renameTo(backup))
                previous = backup
            }
            if (!temp.renameTo(target)) {
                previous?.renameTo(target)
                error("could not promote verified legacy save directory")
            }
            previous?.deleteRecursively()
            parent.listFiles()
                ?.filter { it.name.startsWith(recoveryPrefix) && it.isDirectory }
                ?.forEach { it.deleteRecursively() }
            true
        }.getOrElse {
            if (!target.exists()) previous?.renameTo(target)
            runCatching { temp.deleteRecursively() }
            runCatching {
                GameLog.error("saves", "falha ao migrar save legado; origem preservada", it)
            }
            false
        }
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
            java.net.URI(
                scheme,
                null,
                host,
                port,
                uri.path?.trimEnd('/').orEmpty(),
                null,
                null,
            ).toASCIIString().trimEnd('/')
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
        gameIdentity: String? = null,
        migrationSourcesOverride: Map<String, File>? = null,
    ) : this(
        GameFolders.dir(context, FolderKind.Saves),
        coreId,
        romFile,
        siblingCores,
        gameIdentity ?: GameIdentityResolver.resolve(context, romFile, platformSlug),
        migrationSourcesOverride ?: GameIdentityResolver.legacySources(
            context,
            GameFolders.dir(context, FolderKind.Saves),
            romFile,
            platformSlug,
            listOf(coreId) + siblingCores,
            gameIdentity ?: GameIdentityResolver.resolve(context, romFile, platformSlug),
        ),
    )

    private val legacyGameDirName: String = romFile.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._ -]"), "_")
    private val gameDirName: String = gameIdentity?.takeIf { it.matches(Regex("[A-Za-z0-9._-]+")) } ?: legacyGameDirName
    private fun directoryForCore(targetCoreId: String): File {
        val stable = File(File(savesRoot, targetCoreId), gameDirName)
        val hasPrimarySave = listOf("sram.srm", "state1.state").any { name ->
            File(stable, name).let { it.isFile && it.length() > 0L }
        }
        if (hasPrimarySave) return stable
        val legacy = migrationSources[targetCoreId]
        if (legacy != null && legacy.isDirectory) {
            if (!GameIdentityResolver.migrateLegacy(legacy, stable)) return legacy
            return stable
        }
        if (stable.isDirectory) return stable
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