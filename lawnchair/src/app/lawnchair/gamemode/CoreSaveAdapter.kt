package app.lawnchair.gamemode

/**
 * Descreve a semântica do arquivo de save produzido por um núcleo.
 *
 * O nome físico sram.srm é legado e não determina o conteúdo: em núcleos de PSX
 * ele representa o cartão de memória, enquanto em vários outros sistemas é RAM
 * de bateria. O adaptador é a fonte de verdade para essa distinção.
 */
internal data class CoreSaveAdapter(
    val artifactType: SaveArtifactType,
    val localSlot: String,
    /** Slot remoto estável do protocolo RomM. */
    val remoteSlot: String,
)

/** Mapeamento explícito por núcleo; núcleos não reconhecidos permanecem genéricos. */
internal object CoreSaveAdapterRegistry {
    private val memoryCardCores = setOf(
        "pcsx_rearmed",
        "swanstation",
        "mednafen_psx",
    )

    fun resolve(coreId: String): CoreSaveAdapter = if (coreId.lowercase() in memoryCardCores) {
        CoreSaveAdapter(
            artifactType = SaveArtifactType.MEMORY_CARD,
            localSlot = "memory-card-0",
            remoteSlot = "memory-card-0",
        )
    } else {
        CoreSaveAdapter(
            artifactType = SaveArtifactType.CORE_SAVE,
            localSlot = "core-save",
            remoteSlot = "autosave",
        )
    }
}
