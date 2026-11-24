package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Reconcilia ROMs locais com o catálogo RomM sem transferir conteúdo.
 *
 * A associação automática exige hashes SHA-1 exatos, mesma plataforma e um único rom.id.
 * Para jogos em disco, todos os discos da playlist e os arquivos referenciados pelos CUEs
 * precisam estar presentes e corresponder ao conjunto publicado pelo RomM.
 */
internal object GameLibraryReconciler {

    internal data class Report(
        val linked: Int = 0,
        val alreadyLinked: Int = 0,
        val ambiguous: Int = 0,
        val withoutVerifiedHash: Int = 0,
        val deferredMultiDisc: Int = 0,
    )

    internal data class FileFingerprint(val sizeBytes: Long, val sha1Hash: String) {
        fun key(): String = "$sizeBytes:${sha1Hash.lowercase(Locale.ROOT)}"
    }

    internal data class DiscSet(
        val primary: List<FileFingerprint>,
        val companions: List<FileFingerprint>,
    )

    fun reconcile(context: Context, locals: List<LocalGame>, remote: List<RemoteGame>): Report {
        val connection = GameIdentityResolver.connectionSnapshot(context) ?: return Report()
        var linked = 0
        var alreadyLinked = 0
        var ambiguous = 0
        var withoutVerifiedHash = 0
        var deferredMultiDisc = 0

        for (local in locals) {
            val file = File(local.path)
            if (!file.isFile) continue

            val matchingRomIds = if (local.platform.discBased) {
                val discSet = runCatching { fingerprintDiscSet(file, local.platform) }.getOrNull()
                if (discSet == null) {
                    markAmbiguous(context, local.path, false)
                    withoutVerifiedHash++
                    deferredMultiDisc++
                    continue
                }
                matchingDiscSetRomIds(discSet.primary, discSet.companions, local.platform, remote)
            } else {
                // O tamanho só reduz candidatos; a prova exige tamanho e hash no MESMO arquivo remoto.
                val possibleGames = candidateGames(file.length(), local.platform.slug, remote)
                if (possibleGames.isEmpty()) {
                    markAmbiguous(context, local.path, false)
                    withoutVerifiedHash++
                    continue
                }
                val localFingerprint = runCatching { fingerprint(file) }.getOrNull()
                if (localFingerprint == null) {
                    markAmbiguous(context, local.path, false)
                    withoutVerifiedHash++
                    continue
                }
                matchingRomIds(localFingerprint, local.platform.slug, possibleGames)
            }

            when (matchingRomIds.size) {
                0 -> {
                    markAmbiguous(context, local.path, false)
                    withoutVerifiedHash++
                    if (local.platform.discBased) deferredMultiDisc++
                }

                1 -> {
                    val romId = matchingRomIds.single()
                    val existingId = GameIdentityResolver.linkedRomId(context, file, connection)
                    when {
                        existingId == romId -> {
                            markAmbiguous(context, local.path, false)
                            alreadyLinked++
                        }

                        existingId != null -> {
                            markAmbiguous(context, local.path, true)
                            ambiguous++
                        }

                        else -> {
                            SaveSync.link(context, file.absolutePath, romId)
                            markAmbiguous(context, local.path, false)
                            linked++
                        }
                    }
                }

                else -> {
                    markAmbiguous(context, local.path, true)
                    ambiguous++
                }
            }
        }

        val report = Report(linked, alreadyLinked, ambiguous, withoutVerifiedHash, deferredMultiDisc)
        if (linked > 0 || ambiguous > 0 || deferredMultiDisc > 0) {
            GameLog.log(
                "romm",
                "reconciliação local/RomM: vínculos novos=${report.linked}, já vinculados=${report.alreadyLinked}, " +
                    "ambíguos=${report.ambiguous}, sem hash correspondente=${report.withoutVerifiedHash}, " +
                    "multidisco aguardando conjunto completo=${report.deferredMultiDisc}",
            )
        }
        return report
    }

    internal fun supportsAutomaticDiscDescriptor(extension: String): Boolean = extension.lowercase(Locale.ROOT) !in setOf("gdi", "ccd")

    internal fun candidateGames(localSize: Long, platformSlug: String, remote: List<RemoteGame>): List<RemoteGame> = remote.filter { game ->
        game.platform.slug == platformSlug &&
            game.associatedFiles.any { associated -> associated.sizeBytes == localSize }
    }

    internal fun matchingRomIds(local: FileFingerprint, platformSlug: String, remote: List<RemoteGame>): List<Long> = remote.asSequence()
        .filter { it.platform.slug == platformSlug }
        .filter { game ->
            game.associatedFiles.any { associated ->
                associated.sizeBytes == local.sizeBytes &&
                    associated.sha1Hash?.trim()?.equals(local.sha1Hash, ignoreCase = true) == true
            }
        }
        .map { it.id }
        .distinct()
        .toList()

    internal fun matchingDiscSetRomIds(
        localPrimary: List<FileFingerprint>,
        localCompanions: List<FileFingerprint>,
        platform: GamePlatform,
        remote: List<RemoteGame>,
    ): List<Long> {
        if (localPrimary.isEmpty()) return emptyList()
        val primaryExtensions = platform.extensions - "m3u"
        fun isPlayable(file: RemoteAssociatedFile) = isRomMPlayableFileCategory(file.category)
        fun extension(fileName: String) = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        fun fingerprint(file: RemoteAssociatedFile): FileFingerprint? {
            val hash = file.sha1Hash?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            if (file.sizeBytes <= 0L) return null
            return FileFingerprint(file.sizeBytes, hash)
        }
        val localPrimaryKeys = localPrimary.map { it.key() }.sorted()
        val localCompanionKeys = localCompanions.map { it.key() }.sorted()

        return remote.asSequence()
            .filter { it.platform.slug == platform.slug }
            .filter { game ->
                val remotePrimaryFiles = game.associatedFiles
                    .filter(::isPlayable)
                    .filter { extension(it.fileName) in primaryExtensions }
                val remotePrimaryFingerprints = remotePrimaryFiles.mapNotNull(::fingerprint)
                // Hash ausente em qualquer disco impede afirmar que o conjunto remoto está completo.
                if (remotePrimaryFingerprints.size != remotePrimaryFiles.size) return@filter false
                val remotePrimary = remotePrimaryFingerprints.map { it.key() }.sorted()
                if (remotePrimary != localPrimaryKeys) return@filter false

                val remoteCompanionFiles = game.associatedFiles
                    .filter(::isPlayable)
                    .filter { extension(it.fileName) in platform.discCompanionExtensions }
                val remoteCompanionFingerprints = remoteCompanionFiles.mapNotNull(::fingerprint)
                if (localCompanions.isNotEmpty() &&
                    remoteCompanionFingerprints.size != remoteCompanionFiles.size
                ) {
                    return@filter false
                }
                val remoteCompanions = remoteCompanionFingerprints
                    .map { it.key() }
                    .toMutableList()
                // Cada arquivo referenciado por CUE precisa estar representado no mesmo registro remoto.
                // O conjunto de companions deve ser exato; arquivos extras também podem indicar
                // que o registro remoto representa outra revisão/estrutura do disco.
                remoteCompanions.sorted() == localCompanionKeys
            }
            .map { it.id }
            .distinct()
            .toList()
    }

    internal fun fingerprintDiscSet(entry: File, platform: GamePlatform): DiscSet? {
        val playlistEntries = if (entry.extension.equals("m3u", ignoreCase = true)) {
            entry.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { File(entry.parentFile, it.replace('\\', '/')) }
        } else {
            listOf(entry)
        }
        if (playlistEntries.isEmpty()) return null

        val primary = mutableListOf<FileFingerprint>()
        val companions = mutableListOf<FileFingerprint>()
        val cueReference = Regex("""(?i)^\s*FILE\s+(?:"([^"]+)"|(\S+))""")
        val allowedPrimary = platform.extensions - "m3u"

        for (disc in playlistEntries) {
            val extension = disc.extension.lowercase(Locale.ROOT)
            if (!disc.isFile || extension !in allowedPrimary) return null
            // GDI e CCD possuem referências próprias a imagens/faixas; até existir parser
            // específico, não afirmamos que o conjunto local está completo.
            if (!supportsAutomaticDiscDescriptor(extension)) return null
            primary += fingerprint(disc)

            if (extension == "cue") {
                val references = disc.readLines().mapNotNull { line ->
                    cueReference.find(line)?.let { match -> match.groupValues[1].ifBlank { match.groupValues[2] } }
                }.distinct()
                if (references.isEmpty()) return null
                for (reference in references) {
                    val companion = File(disc.parentFile, reference.replace('\\', '/'))
                    if (!companion.isFile ||
                        companion.extension.lowercase(Locale.ROOT) !in platform.discCompanionExtensions
                    ) {
                        return null
                    }
                    companions += fingerprint(companion)
                }
            }
        }
        return DiscSet(primary, companions)
    }

    private fun fingerprint(file: File): FileFingerprint {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return FileFingerprint(file.length(), digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
    }

    fun isAmbiguous(context: Context, path: String): Boolean = context.getSharedPreferences("romm_auto_link", Context.MODE_PRIVATE)
        .getBoolean("ambiguous:$path", false)

    private fun markAmbiguous(context: Context, path: String, value: Boolean) {
        context.getSharedPreferences("romm_auto_link", Context.MODE_PRIVATE).edit {
            putBoolean("ambiguous:$path", value)
        }
    }
}
