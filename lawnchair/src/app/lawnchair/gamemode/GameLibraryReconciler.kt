package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import java.io.File
import java.security.MessageDigest

/**
 * Reconcilia ROMs locais com o catálogo RomM sem transferir conteúdo.
 *
 * A associação automática só é permitida quando existe uma correspondência SHA-1
 * exata, na mesma plataforma, para um jogo não multidisco e para um único rom.id.
 * Nome e tamanho nunca são usados como prova de identidade.
 */
internal object GameLibraryReconciler {

    internal data class Report(
        val linked: Int = 0,
        val alreadyLinked: Int = 0,
        val ambiguous: Int = 0,
        val withoutVerifiedHash: Int = 0,
        val deferredMultiDisc: Int = 0,
    )

    fun reconcile(context: Context, locals: List<LocalGame>, remote: List<RemoteGame>): Report {
        val connection = GameIdentityResolver.connectionSnapshot(context) ?: return Report()
        var linked = 0
        var alreadyLinked = 0
        var ambiguous = 0
        var withoutVerifiedHash = 0
        var deferredMultiDisc = 0

        for (local in locals) {
            if (local.platform.discBased || local.path.endsWith(".m3u", ignoreCase = true)) {
                markAmbiguous(context, local.path, false)
                deferredMultiDisc++
                continue
            }
            val file = File(local.path)
            if (!file.isFile) continue
            val localHash = runCatching { sha1(file) }.getOrNull()
            if (localHash.isNullOrBlank()) {
                withoutVerifiedHash++
                continue
            }

            val matchingRomIds = matchingRomIds(localHash, local.platform.slug, remote)

            when (matchingRomIds.size) {
                0 -> {
                    markAmbiguous(context, local.path, false)
                    withoutVerifiedHash++
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

    internal fun matchingRomIds(localSha1: String, platformSlug: String, remote: List<RemoteGame>): List<Long> =
        remote.asSequence()
            .filter { it.platform.slug == platformSlug }
            .filter { game ->
                game.associatedFiles.any { associated ->
                    associated.sha1Hash?.trim()?.equals(localSha1, ignoreCase = true) == true
                }
            }
            .map { it.id }
            .distinct()
            .toList()

    fun isAmbiguous(context: Context, path: String): Boolean =
        context.getSharedPreferences("romm_auto_link", Context.MODE_PRIVATE)
            .getBoolean("ambiguous:$path", false)

    private fun markAmbiguous(context: Context, path: String, value: Boolean) {
        context.getSharedPreferences("romm_auto_link", Context.MODE_PRIVATE).edit {
            putBoolean("ambiguous:$path", value)
        }
    }

    private fun sha1(file: File): String {
        val digest = MessageDigest.getInstance("SHA-1")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
