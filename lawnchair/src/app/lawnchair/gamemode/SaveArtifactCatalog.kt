package app.lawnchair.gamemode

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.Locale

/**
 * Papel local do artefato. A distinção entre save de núcleo e cartão de memória
 * vem do adaptador do núcleo, nunca apenas da extensão .srm.
 */
internal enum class SaveArtifactType {
    CORE_SAVE,
    MEMORY_CARD,
    SAVE_STATE,
    RETRO_ACHIEVEMENTS_PROGRESS,
}

/** Metadados verificáveis de um arquivo local, sem carregar seu conteúdo na memória. */
internal data class SaveArtifact(
    val gameIdentity: String,
    val coreId: String,
    val type: SaveArtifactType,
    val slot: String,
    val format: String,
    val file: File,
    val sizeBytes: Long,
    val modifiedAtEpochMs: Long,
    val sha256: String,
)

/**
 * Inventário único dos arquivos produzidos/consumidos pelos pilares do jogo.
 *
 * Não presume compatibilidade entre núcleos e não envia progresso RA ao RomM.
 * O SHA-256 é calculado em fluxo para suportar arquivos grandes sem alocar o arquivo inteiro.
 */
internal object SaveArtifactCatalog {
    @Throws(IOException::class)
    fun inspect(gameIdentity: String, coreId: String, files: Collection<File>): List<SaveArtifact> {
        require(gameIdentity.isNotBlank()) { "A identidade do jogo é obrigatória." }
        require(coreId.isNotBlank()) { "A identidade do núcleo é obrigatória." }

        return files.asSequence()
            .distinctBy { it.absoluteFile.toPath().normalize().toString() }
            .filter { it.isFile && it.length() > 0L }
            .mapNotNull { file ->
                val typeAndSlot = classify(file, coreId) ?: return@mapNotNull null
                SaveArtifact(
                    gameIdentity = gameIdentity,
                    coreId = coreId,
                    type = typeAndSlot.first,
                    slot = typeAndSlot.second,
                    format = file.extension.lowercase(Locale.ROOT),
                    file = file,
                    sizeBytes = file.length(),
                    modifiedAtEpochMs = file.lastModified(),
                    sha256 = sha256(file),
                )
            }
            .sortedWith(compareBy<SaveArtifact>({ it.type.ordinal }, { it.slot }))
            .toList()
    }

    private fun classify(file: File, coreId: String): Pair<SaveArtifactType, String>? {
        val name = file.name.lowercase(Locale.ROOT)
        return when {
            name == "sram.srm" -> {
                val adapter = CoreSaveAdapterRegistry.resolve(coreId)
                adapter.artifactType to adapter.localSlot
            }

            name.matches(Regex("state[0-9]+\\.state")) ->
                SaveArtifactType.SAVE_STATE to name.removeSuffix(".state")

            name.matches(Regex("ra-progress-[a-f0-9]+\\.bin")) ->
                SaveArtifactType.RETRO_ACHIEVEMENTS_PROGRESS to name.removePrefix("ra-progress-").removeSuffix(".bin")

            else -> null
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
