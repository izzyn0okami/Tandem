package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * Resolves a stable game identity without using the ROM filename as the primary key.
 * RomM-linked games use server + RomM game ID; local games use platform + content fingerprint.
 */
internal data class LegacyMigrationPlan(
    val sources: Map<String, File>,
    val ambiguous: Boolean,
    val confirmationSources: Map<String, File> = emptyMap(),
    val conflictSources: Map<String, File> = emptyMap(),
)

internal data class SaveConflictFileInfo(
    val coreId: String,
    val path: String,
    val sourceBytes: Long,
    val targetBytes: Long,
    val sourceModified: Long,
    val targetModified: Long,
    val identical: Boolean,
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

    private fun referencedFile(parent: File?, reference: String): File = File(parent, reference.replace('\\', '/'))

    fun resolve(context: Context, romFile: File, platformSlug: String?): String = resolveWithFingerprint(context, romFile, platformSlug, cachedFingerprint(context, romFile))

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
        val account = if (directId >= 0L) {
            prefs.getString("rom_account:${romFile.absolutePath}", null)
        } else {
            contentLink?.accountFingerprint
        }
        val linkedId = if (directId >= 0L) directId else contentLink?.romId ?: -1L
        return when {
            linkedId >= 0L && server != null && !account.isNullOrBlank() ->
                SaveSyncPolicy.rommGameIdentity(server, account, linkedId)

            linkedId >= 0L && server != null -> "romm-legacy-${digest("$server|$linkedId")}"

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

    fun hasAmbiguousContentLink(context: Context, romFile: File): Boolean {
        val fingerprint = cachedFingerprintIfAvailable(context, romFile) ?: return false
        return context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
            .getString(contentLinkKey(fingerprint), null) == "ambiguous"
    }

    internal fun isSameGameFingerprint(stored: String?, current: String): Boolean = !stored.isNullOrBlank() && stored == current

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

    fun linkedRomPaths(
        context: Context,
        romId: Long,
        connection: RomMConnectionSnapshot,
        currentPath: String,
    ): List<String> {
        val prefs = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
        val previousPaths = prefs.all.keys.asSequence()
            .filter { it.startsWith("rom:") }
            .map { it.removePrefix("rom:") }
            .filter { oldPath ->
                prefs.getLong("rom:$oldPath", -1L) == romId &&
                    prefs.getString("rom_server:$oldPath", null) == connection.server &&
                    prefs.getString("rom_account:$oldPath", null) == connection.accountFingerprint
            }
            .toList()
        return (listOf(currentPath) + previousPaths).distinct()
    }

    fun connectionSnapshot(context: Context): RomMConnectionSnapshot? {
        val url = RomMSettings.url(context)?.takeIf { it.isNotBlank() } ?: return null
        val token = RomMSettings.token(context)?.takeIf { it.isNotBlank() } ?: return null
        val server = normalizedServer(url) ?: return null
        val preferences = context.getSharedPreferences("romm", Context.MODE_PRIVATE)
        val storedServer = preferences.getString("account_server", null)
        val accountId = preferences.getLong("user_id", -1L)
        // Never fall back to a token-derived identity: an unknown account must not share saves.
        if (storedServer != server || accountId <= 0L) return null
        return RomMConnectionSnapshot(url, token, server, accountFingerprint(server, accountId))
    }

    internal fun accountFingerprint(server: String, userId: Long): String = SaveSyncPolicy.accountFingerprint(server, userId)

    private fun isCurrentAccountGameIdentity(
        context: Context,
        romFile: File,
        gameIdentity: String,
    ): Boolean {
        val connection = connectionSnapshot(context) ?: return false
        val romId = linkedRomId(context, romFile, connection) ?: return false
        val expectedIdentity = SaveSyncPolicy.rommGameIdentity(
            connection.server,
            connection.accountFingerprint,
            romId,
        )
        return expectedIdentity == gameIdentity
    }

    internal fun migrateAccountFingerprint(
        context: Context,
        server: String,
        previousFingerprint: String,
        currentFingerprint: String,
    ) {
        val preferences = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
        val values = preferences.all
        preferences.edit {
            values.forEach { (key, value) ->
                when {
                    key.startsWith("rom_account:") &&
                        value == previousFingerprint &&
                        preferences.getString(key.removePrefix("rom_account:").let { "rom_server:$it" }, null) == server ->
                        putString(key, currentFingerprint)

                    key.startsWith("game_link_content:") && value is String -> {
                        val parts = value.split('|')
                        if (parts.size == 3 && parts[0] == server && parts[1] == previousFingerprint) {
                            putString(key, "$server|$currentFingerprint|${parts[2]}")
                        }
                    }
                }
            }
        }
    }

    /** Fingerprint of the credentials that originally linked this game, even while disconnected. */
    fun originAccountFingerprint(context: Context, romFile: File): String? {
        val prefs = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
        val direct = prefs.getLong("rom:${romFile.absolutePath}", -1L)
        val fingerprint = cachedFingerprintIfAvailable(context, romFile) ?: return null
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
            if (existing == null || existing == encoded || sameRemoteGame) {
                putString(key, encoded)
            } else {
                putString(key, "ambiguous")
            }
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
        val legacyDataExists = coreIds.distinct().any { coreId ->
            File(File(savesRoot, coreId), legacyName)
                .walkTopDown()
                .any { it.isFile && it.length() > 0L }
        }
        // A user may have played with the local identity while an old RomM link was unverified.
        // When the same content is linked again, move that known local progress to the RomM identity.
        if (stableDirectoryName.startsWith("romm-")) {
            val preferences = context.getSharedPreferences("romm_sync", Context.MODE_PRIVATE)
            val currentFingerprint = cachedFingerprint(context, romFile)
            val directId = preferences.getLong("rom:${romFile.absolutePath}", -1L)
            val directHash = preferences.getString("rom_fingerprint:${romFile.absolutePath}", null)
            val verifiedDirectId = directId.takeIf { isSameGameFingerprint(directHash, currentFingerprint) } ?: -1L
            val oldServer = preferences.getString("rom_server:${romFile.absolutePath}", null)
            val oldAccount = preferences.getString("rom_account:${romFile.absolutePath}", null)
            val contentLink = parseContentLink(preferences.getString(contentLinkKey(currentFingerprint), null))
            val oldRomId = if (verifiedDirectId >= 0L) verifiedDirectId else contentLink?.romId ?: -1L
            val oldRomServer = if (verifiedDirectId >= 0L) oldServer else contentLink?.server
            val oldRomAccount = if (verifiedDirectId >= 0L) oldAccount else contentLink?.accountFingerprint
            if (oldRomId > 0L && !oldRomServer.isNullOrBlank() && !oldRomAccount.isNullOrBlank()) {
                val preAccountIdentity = "romm-${digest("$oldRomServer|$oldRomId")}"
                if (preAccountIdentity != stableDirectoryName) {
                    val preAccountSources = coreIds.distinct().associateWith { coreId ->
                        File(File(savesRoot, coreId), preAccountIdentity)
                    }.filterValues { directory ->
                        directory.walkTopDown().any { it.isFile && it.length() > 0L }
                    }
                    if (preAccountSources.isNotEmpty()) {
                        if (!isCurrentAccountGameIdentity(context, romFile, stableDirectoryName)) {
                            return LegacyMigrationPlan(emptyMap(), ambiguous = true)
                        }
                        if (legacyDataExists) {
                            return LegacyMigrationPlan(emptyMap(), ambiguous = true)
                        }
                        val hasConflict = preAccountSources.any { (coreId, source) ->
                            hasConflictingFiles(source, File(File(savesRoot, coreId), stableDirectoryName))
                        }
                        if (hasConflict) return LegacyMigrationPlan(emptyMap(), ambiguous = true, conflictSources = preAccountSources.filter { (coreId, source) ->
                            val target = File(File(savesRoot, coreId), stableDirectoryName)
                            source.isDirectory && target.isDirectory && hasConflictingFiles(source, target)
                        })
                        val alreadyClaimed = preAccountSources.all { (coreId, source) ->
                            isVerifiedLegacyCopy(source, File(File(savesRoot, coreId), stableDirectoryName))
                        }
                        runCatching {
                            GameLog.log(
                                "saves",
                                if (alreadyClaimed) {
                                    "save RomM legado já copiado e validado para a conta atual; origem preservada"
                                } else {
                                    "save RomM legado aguarda confirmação explícita para associação à conta atual"
                                },
                            )
                        }
                        return if (alreadyClaimed) {
                            LegacyMigrationPlan(preAccountSources, ambiguous = false)
                        } else {
                            LegacyMigrationPlan(emptyMap(), ambiguous = false, confirmationSources = preAccountSources)
                        }
                    }
                }
            }
            val localIdentity = "local-${digest("${platformSlug.orEmpty()}|${cachedFingerprint(context, romFile)}")}"
            if (localIdentity != stableDirectoryName) {
                val localSources = coreIds.distinct().associateWith { coreId ->
                    File(File(savesRoot, coreId), localIdentity)
                }.filterValues { directory ->
                    directory.walkTopDown().any { it.isFile && it.length() > 0L }
                }
                if (localSources.isNotEmpty()) {
                    if (legacyDataExists) return LegacyMigrationPlan(emptyMap(), ambiguous = true)
                    val hasConflict = localSources.any { (coreId, source) ->
                        hasConflictingFiles(source, File(File(savesRoot, coreId), stableDirectoryName))
                    }
                    if (hasConflict) return LegacyMigrationPlan(emptyMap(), ambiguous = true, conflictSources = localSources.filter { (coreId, source) ->
                        val target = File(File(savesRoot, coreId), stableDirectoryName)
                        source.isDirectory && target.isDirectory && hasConflictingFiles(source, target)
                    })
                    return LegacyMigrationPlan(localSources, ambiguous = false)
                }
            }
        }

        if (stableDirectoryName.startsWith("romm-legacy-") && legacyDataExists) {
            return LegacyMigrationPlan(emptyMap(), ambiguous = true)
        }
        if (stableDirectoryName.startsWith("romm-") &&
            !stableDirectoryName.startsWith("romm-legacy-") &&
            legacyDataExists
        ) {
            if (!isCurrentAccountGameIdentity(context, romFile, stableDirectoryName)) {
                return LegacyMigrationPlan(emptyMap(), ambiguous = true)
            }
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
                return LegacyMigrationPlan(emptyMap(), ambiguous = true)
            }
            val sources = coreIds.distinct().associateWith { coreId ->
                File(File(savesRoot, coreId), legacyName)
            }.filterValues { directory ->
                directory.isDirectory && directory.walkTopDown().any { it.isFile && it.length() > 0L }
            }
            if (sources.isEmpty()) return LegacyMigrationPlan(emptyMap(), ambiguous = true)
            val hasConflict = sources.any { (coreId, source) ->
                hasConflictingFiles(source, File(File(savesRoot, coreId), stableDirectoryName))
            }
            if (hasConflict) return LegacyMigrationPlan(emptyMap(), ambiguous = true, conflictSources = sources.filter { (coreId, source) ->
                    val target = File(File(savesRoot, coreId), stableDirectoryName)
                    source.isDirectory && target.isDirectory && hasConflictingFiles(source, target)
                })
            val alreadyClaimed = sources.all { (coreId, source) ->
                isVerifiedLegacyCopy(source, File(File(savesRoot, coreId), stableDirectoryName))
            }
            runCatching {
                GameLog.log(
                    "saves",
                    if (alreadyClaimed) {
                        "save legado por nome já copiado e validado para a conta atual; origem preservada"
                    } else {
                        "save legado por nome aguarda confirmação explícita para associação à conta atual"
                    },
                )
            }
            return if (alreadyClaimed) {
                LegacyMigrationPlan(sources, ambiguous = false)
            } else {
                LegacyMigrationPlan(emptyMap(), ambiguous = false, confirmationSources = sources)
            }
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
        val hasConflict = sources.any { (coreId, source) ->
            hasConflictingFiles(source, File(File(savesRoot, coreId), stableDirectoryName))
        }
        if (hasConflict) {
            runCatching { GameLog.log("saves", "migração legada com arquivos conflitantes; ambos os diretórios foram preservados") }
            return LegacyMigrationPlan(
                emptyMap(),
                ambiguous = true,
                conflictSources = sources.filter { (coreId, source) ->
                    val target = File(File(savesRoot, coreId), stableDirectoryName)
                    source.isDirectory && target.isDirectory && hasConflictingFiles(source, target)
                },
            )
        }
        return LegacyMigrationPlan(sources, ambiguous = false)
    }

    internal fun inspectLegacyConflicts(
        savesRoot: File,
        stableDirectoryName: String,
        sources: Map<String, File>,
    ): List<SaveConflictFileInfo> = sources.flatMap { (coreId, source) ->
        val target = File(File(savesRoot, coreId), stableDirectoryName)
        if (!source.isDirectory || !target.isDirectory) return@flatMap emptyList()
        val sourceFiles = source.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(source).path }
        val targetFiles = target.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(target).path }
        (sourceFiles.keys intersect targetFiles.keys).sorted().mapNotNull { relative ->
            val left = sourceFiles.getValue(relative)
            val right = targetFiles.getValue(relative)
            val same = left.length() == right.length() && fileHash(left) == fileHash(right)
            if (same) null else SaveConflictFileInfo(
                coreId = coreId,
                path = relative,
                sourceBytes = left.length(),
                targetBytes = right.length(),
                sourceModified = left.lastModified(),
                targetModified = right.lastModified(),
                identical = false,
            )
        }
    }

    /**
     * Resolves all conflicting core directories as one user-approved operation.
     * Both original trees are copied and hash-verified into a recovery area before any target changes.
     */
    @Synchronized
    fun resolveLegacyConflicts(
        context: Context,
        romFile: File,
        platformSlug: String?,
        gameIdentity: String,
        sources: Map<String, File>,
        preferSource: Boolean,
    ): Boolean {
        if (sources.isEmpty() || !romFile.isFile) return false
        if (resolve(context, romFile, platformSlug) != gameIdentity) return false
        if (gameIdentity.startsWith("romm-") &&
            !gameIdentity.startsWith("romm-legacy-") &&
            !isCurrentAccountGameIdentity(context, romFile, gameIdentity)
        ) return false

        val savesRoot = GameFolders.dir(context, FolderKind.Saves)
        val rootCanonical = runCatching { savesRoot.canonicalFile }.getOrNull() ?: return false
        data class Prepared(
            val coreId: String,
            val source: File,
            val target: File,
            val temp: File,
            val rollback: File,
            val backup: File,
        )
        val stamp = System.currentTimeMillis()
        val prepared = mutableListOf<Prepared>()
        val promoted = mutableListOf<Prepared>()

        fun directoryHash(directory: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            directory.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(directory).path }.forEach { file ->
                digest.update(file.relativeTo(directory).path.toByteArray(Charsets.UTF_8))
                digest.update(fileHash(file).toByteArray(Charsets.US_ASCII))
            }
            return digest.digest().toHex()
        }

        fun copyTree(from: File, to: File): Boolean {
            if (!from.isDirectory || (!to.exists() && !to.mkdirs())) return false
            return runCatching {
                from.walkTopDown().filter { it.isFile }.forEach { file ->
                    val destination = File(to, file.relativeTo(from).path)
                    check(destination.parentFile?.let { it.exists() || it.mkdirs() } == true)
                    file.copyTo(destination, overwrite = true)
                    check(fileHash(file) == fileHash(destination))
                }
                directoryHash(from) == directoryHash(to)
            }.getOrDefault(false)
        }

        try {
            for ((coreId, source) in sources) {
                val target = File(File(savesRoot, coreId), gameIdentity)
                val sourceCanonical = source.canonicalFile
                val targetParent = target.parentFile ?: return false
                if (!sourceCanonical.toPath().startsWith(rootCanonical.toPath()) ||
                    !targetParent.canonicalFile.toPath().startsWith(rootCanonical.toPath()) ||
                    !target.canonicalFile.toPath().startsWith(rootCanonical.toPath()) ||
                    !source.isDirectory || !target.isDirectory || !hasConflictingFiles(source, target)
                ) return false

                val backup = File(savesRoot, ".conflict-backups/$gameIdentity/$coreId/$stamp")
                if (!copyTree(source, File(backup, "legado")) ||
                    !copyTree(target, File(backup, "atual"))
                ) return false

                val temp = File(targetParent, ".${target.name}.conflict-$stamp")
                val rollback = File(targetParent, ".${target.name}.rollback-$stamp")
                if (temp.exists() && !temp.deleteRecursively()) return false
                if (!temp.mkdirs()) return false

                val sourceFiles = source.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(source).path }
                val targetFiles = target.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(target).path }
                for (relative in (sourceFiles.keys + targetFiles.keys).toSortedSet()) {
                    val left = sourceFiles[relative]
                    val right = targetFiles[relative]
                    val chosen = when {
                        left == null -> right!!
                        right == null -> left
                        left.length() == right.length() && fileHash(left) == fileHash(right) -> if (preferSource) left else right
                        preferSource -> left
                        else -> right
                    }
                    val destination = File(temp, relative)
                    check(destination.parentFile?.let { it.exists() || it.mkdirs() } == true)
                    chosen.copyTo(destination)
                    check(fileHash(chosen) == fileHash(destination))
                }
                val stagedFiles = temp.walkTopDown().filter { it.isFile }.toList()
                val expectedFiles = sourceFiles.keys + targetFiles.keys
                check(stagedFiles.map { it.relativeTo(temp).path }.toSet() == expectedFiles)
                prepared += Prepared(coreId, source, target, temp, rollback, backup)
            }

            for (item in prepared) {
                if (gameIdentity.startsWith("romm-") &&
                    !gameIdentity.startsWith("romm-legacy-") &&
                    !isCurrentAccountGameIdentity(context, romFile, gameIdentity)
                ) throw IOException("A conta RomM mudou durante a resolução do conflito")
                if (!item.target.renameTo(item.rollback)) throw IOException("Não foi possível proteger o destino atual")
                if (!item.temp.renameTo(item.target)) {
                    item.rollback.renameTo(item.target)
                    throw IOException("Não foi possível aplicar o conjunto escolhido")
                }
                promoted += item
            }

            if (resolve(context, romFile, platformSlug) != gameIdentity) {
                throw IOException("A identidade do jogo mudou durante a resolução")
            }
            if (gameIdentity.startsWith("romm-") &&
                !gameIdentity.startsWith("romm-legacy-") &&
                !isCurrentAccountGameIdentity(context, romFile, gameIdentity)
            ) throw IOException("A conta RomM mudou antes da conclusão")

            for (item in prepared) {
                if (gameIdentity.startsWith("romm-") &&
                    !gameIdentity.startsWith("romm-legacy-") &&
                    !isCurrentAccountGameIdentity(context, romFile, gameIdentity)
                ) throw IOException("A conta RomM mudou antes do arquivamento final")
                if (item.source.exists() && !item.source.deleteRecursively()) {
                    throw IOException("Não foi possível concluir o arquivamento do diretório legado")
                }
            }
            prepared.forEach { it.rollback.deleteRecursively() }
            GameLog.log(
                "saves",
                "conflito resolvido por escolha explícita: jogo=$gameIdentity, preferência=${if (preferSource) "legado" else "atual"}; originais preservados em .conflict-backups",
            )
            true
        } catch (error: Exception) {
            promoted.asReversed().forEach { item ->
                runCatching {
                    if (item.target.exists()) item.target.deleteRecursively()
                    if (item.rollback.exists()) item.rollback.renameTo(item.target)
                }
            }
            prepared.forEach { item -> runCatching { item.temp.deleteRecursively() } }
            GameLog.error("saves", "falha ao resolver conflito; conjuntos originais preservados", error)
            false
        }
    }

    private fun hasConflictingFiles(source: File, target: File): Boolean {
        if (!target.exists()) return false
        if (!target.isDirectory) return true
        return source.walkTopDown().filter { it.isFile }.any { sourceFile ->
            val existing = File(target, sourceFile.relativeTo(source).path)
            existing.exists() &&
                (!existing.isFile || (existing.length() > 0L && fileHash(existing) != fileHash(sourceFile)))
        }
    }

    /** True only when every legacy byte is already present and hash-verified in the stable target. */
    internal fun isVerifiedLegacyCopy(source: File, target: File): Boolean {
        if (!source.isDirectory || !target.isDirectory) return false
        val files = source.walkTopDown().filter { it.isFile }.toList()
        return files.isNotEmpty() && files.all { sourceFile ->
            val destination = File(target, sourceFile.relativeTo(source).path)
            destination.isFile &&
                destination.length() == sourceFile.length() &&
                fileHash(destination) == fileHash(sourceFile)
        }
    }

    /** Copy an explicitly approved legacy set into the current account namespace, keeping its source. */
    fun claimLegacySaves(
        context: Context,
        romFile: File,
        gameIdentity: String,
        sources: Map<String, File>,
    ): Boolean {
        if (sources.isEmpty() || !isCurrentAccountGameIdentity(context, romFile, gameIdentity)) {
            return false
        }
        val savesRoot = GameFolders.dir(context, FolderKind.Saves)
        return sources.all { (coreId, source) ->
            val target = File(File(savesRoot, coreId), gameIdentity)
            migrateLegacy(source, target) && isVerifiedLegacyCopy(source, target)
        }
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
                        check(fileHash(destination) == sourceHash)
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
            check(
                expectedHashes.all { (relative, expected) ->
                    fileHash(File(temp, relative)) == expected
                },
            )
            if (conflictingFiles.isNotEmpty()) {
                throw IOException(
                    "Arquivos de save conflitantes preservados sem substituição: ${conflictingFiles.joinToString()}",
                )
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
                            addFile(referencedFile(file.parentFile, reference), depth + 1)
                        }
                }

                "cue" -> {
                    digest.update("cue".toByteArray(Charsets.UTF_8))
                    val references = mutableListOf<String>()
                    val normalizedLines = file.readLines().map { line ->
                        val match = cueFileReference.find(line)
                        if (match != null) {
                            references += match.groupValues[1].ifBlank { match.groupValues[2] }
                            cueFileDirective.replace(line, "FILE <DISC>")
                        } else {
                            line.trim()
                        }
                    }
                    digest.update(normalizedLines.joinToString("\n").toByteArray(Charsets.UTF_8))
                    references.forEachIndexed { index, reference ->
                        digest.update(index.toString().toByteArray(Charsets.UTF_8))
                        addFile(referencedFile(file.parentFile, reference), depth + 1)
                    }
                }

                else -> digest.update(fileHash(file).toByteArray(Charsets.UTF_8))
            }
        }

        addFile(romFile, 0)
        return digest.digest().toHex()
    }

    private fun sourceSignature(
        romFile: File,
        depth: Int = 0,
        visited: MutableSet<String> = mutableSetOf(),
    ): String {
        val canonical = runCatching { romFile.canonicalPath }.getOrDefault(romFile.absolutePath)
        if (depth > 4 || !romFile.isFile) return "missing:$canonical"
        if (!visited.add(canonical)) return "visited:$canonical"
        val base = "$canonical:${romFile.length()}:${romFile.lastModified()}"
        return when (romFile.extension.lowercase(Locale.ROOT)) {
            "m3u" -> {
                val references = romFile.readLines().map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                "$base:${fileHash(romFile)}:" + references.joinToString("|") {
                    sourceSignature(referencedFile(romFile.parentFile, it), depth + 1, visited)
                }
            }

            "cue" -> {
                val references = romFile.readLines().mapNotNull { line ->
                    cueFileReference.find(line)?.let { match ->
                        match.groupValues[1].ifBlank { match.groupValues[2] }
                    }
                }
                "$base:${fileHash(romFile)}:" + references.joinToString("|") {
                    sourceSignature(referencedFile(romFile.parentFile, it), depth + 1, visited)
                }
            }

            else -> base
        }
    }

    private fun fingerprintCacheKey(romFile: File): String {
        val canonicalPath = runCatching { romFile.canonicalPath }.getOrDefault(romFile.absolutePath)
        return "fingerprint:${digest(canonicalPath)}"
    }

    private fun cachedFingerprintIfAvailable(context: Context, romFile: File): String? {
        val cacheKey = fingerprintCacheKey(romFile)
        val signature = sourceSignature(romFile)
        val preferences = context.getSharedPreferences("game_identity_cache", Context.MODE_PRIVATE)
        if (preferences.getString("$cacheKey:signature", null) != signature) return null
        return preferences.getString("$cacheKey:value", null)
    }

    private fun cachedFingerprint(context: Context, romFile: File): String {
        cachedFingerprintIfAvailable(context, romFile)?.let { return it }
        val cacheKey = fingerprintCacheKey(romFile)
        val value = fingerprint(romFile)
        val signature = sourceSignature(romFile)
        context.getSharedPreferences("game_identity_cache", Context.MODE_PRIVATE).edit {
            putString("$cacheKey:signature", signature)
            putString("$cacheKey:value", value)
        }
        return value
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

    internal fun normalizedServer(value: String?): String? {
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

    internal fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).toHex()

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
        val legacy = migrationSources[targetCoreId]
        if (legacy != null && legacy.isDirectory) {
            if (!GameIdentityResolver.migrateLegacy(legacy, stable)) return legacy
            return stable
        }
        if (hasPrimarySave) return stable
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
