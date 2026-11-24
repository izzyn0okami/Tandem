package app.lawnchair.gamemode

import java.io.IOException

/**
 * Seleciona o único artefato que o protocolo Device Sync atual consegue transportar.
 *
 * CORE_SAVE usa o slot remoto autosave; MEMORY_CARD PSX usa memory-card-0.
 * A escolha é explícita e independente do nome físico sram.srm. Save states e progresso
 * RetroAchievements nunca entram na negociação.
 */
internal object DeviceSyncArtifactPolicy {
    fun remoteSlot(artifact: SaveArtifact): String = when (artifact.type) {
        SaveArtifactType.CORE_SAVE -> "autosave"
        SaveArtifactType.MEMORY_CARD -> "memory-card-0"
        else -> throw IOException("Este tipo de artefato não possui slot RomM nesta etapa.")
    }

    fun remoteSlotForCore(coreId: String): String = CoreSaveAdapterRegistry.resolve(coreId).remoteSlot

    /**
     * RomM pode devolver o identificador original do núcleo (ex.: snes9x) ou
     * o identificador namespaced do cliente (ex.: tandem_snes9x). Aceitamos
     * ambas as formas somente para os núcleos declarados no mesmo grupo.
     */
    fun compatibleEmulators(coreId: String): Set<String> = CoreSaveAdapterRegistry.resolve(coreId).compatibleCoreIds.flatMap { compatibleCore ->
        listOf(compatibleCore, "tandem_$compatibleCore")
    }.toSet()

    fun isCompatibleRemote(coreId: String, remoteEmulator: String?): Boolean {
        if (remoteEmulator.isNullOrBlank()) return false
        val normalizedRemote = remoteEmulator.removePrefix("tandem_")
        return CoreSaveAdapterRegistry.resolve(coreId).compatibleCoreIds.any {
            it.equals(normalizedRemote, ignoreCase = true)
        }
    }

    /** RomM 5.4.1 reports this reason when a newer client save has no device baseline yet. */
    fun isUnbasedUpload(operation: DeviceSyncOperation): Boolean = operation.action == DeviceSyncAction.Upload &&
        operation.saveId != null &&
        operation.reason?.contains("no sync history", ignoreCase = true) == true

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
