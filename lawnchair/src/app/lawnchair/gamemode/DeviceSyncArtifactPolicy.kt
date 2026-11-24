package app.lawnchair.gamemode

import java.io.IOException

/**
 * Seleciona o único artefato que o protocolo Device Sync atual consegue transportar.
 *
 * Nesta etapa, CORE_SAVE e MEMORY_CARD em formato .srm são enviados como Save RomM
 * no slot remoto legado autosave. Isso preserva o contrato existente, mas não declara
 * que memory-card-0 e autosave sejam slots semanticamente equivalentes. Save states
 * e progresso RetroAchievements nunca entram na negociação.
 */
internal object DeviceSyncArtifactPolicy {
    fun selectForRomM(artifacts: List<SaveArtifact>): SaveArtifact? {
        val candidates = artifacts.filter {
            it.type in setOf(SaveArtifactType.CORE_SAVE, SaveArtifactType.MEMORY_CARD) &&
                it.format.equals("srm", ignoreCase = true)
        }
        if (candidates.size > 1) {
            throw IOException("Mais de um artefato .srm elegível foi encontrado; sincronização interrompida por ambiguidade.")
        }
        return candidates.singleOrNull()
    }
}
