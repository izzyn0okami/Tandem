package app.lawnchair.gamemode

/**
 * Descreve a semântica do arquivo de save produzido por um núcleo e os núcleos
 * cujo formato de arquivo foi explicitamente considerado compatível.
 *
 * O nome físico sram.srm é legado e não determina o conteúdo: em núcleos de PSX
 * ele representa o cartão de memória; em SNES, SRAM. Núcleos desconhecidos só
 * são compatíveis com eles próprios até que exista uma regra declarada e testada.
 */
internal data class CoreSaveAdapter(
    val artifactType: SaveArtifactType,
    val localSlot: String,
    /** Slot remoto estável do protocolo RomM. */
    val remoteSlot: String,
    val compatibleCoreIds: Set<String>,
)

/** Mapeamento explícito por núcleo; compatibilidade nunca é inferida apenas pela extensão. */
internal object CoreSaveAdapterRegistry {
    private val memoryCardCores = setOf(
        "pcsx_rearmed",
        "swanstation",
        "mednafen_psx",
    )
    private val snesSaveCores = setOf(
        "snes9x",
        "snes9x2010",
        "bsnes",
    )

    /** O identificador público do núcleo Libretro; o namespace Tandem nunca é enviado ao RomM. */
    fun romMEmulatorId(coreId: String): String = coreId.removePrefixIgnoreCase("tandem_").lowercase()

    /** Aceita nomes canônicos novos e o namespace legado já gravado no RomM. */
    fun romMEmulatorAliases(coreId: String): Set<String> = resolve(coreId).compatibleCoreIds.flatMap { compatible ->
        val canonical = romMEmulatorId(compatible)
        listOf(canonical, "tandem_$canonical")
    }.toSet()

    fun isCompatibleRomMEmulator(coreId: String, remoteEmulator: String?): Boolean {
        if (remoteEmulator.isNullOrBlank()) return false
        val canonicalRemote = remoteEmulator.removePrefixIgnoreCase("tandem_").lowercase()
        return resolve(coreId).compatibleCoreIds.any { it.equals(canonicalRemote, ignoreCase = true) }
    }

    private fun String.removePrefixIgnoreCase(prefix: String): String = if (startsWith(prefix, ignoreCase = true)) drop(prefix.length) else this

    fun resolve(coreId: String): CoreSaveAdapter {
        val normalized = coreId.lowercase()
        return when (normalized) {
            in memoryCardCores -> CoreSaveAdapter(
                artifactType = SaveArtifactType.MEMORY_CARD,
                localSlot = "memory-card-0",
                remoteSlot = "memory-card-0",
                compatibleCoreIds = memoryCardCores,
            )

            in snesSaveCores -> CoreSaveAdapter(
                artifactType = SaveArtifactType.CORE_SAVE,
                localSlot = "core-save",
                remoteSlot = "autosave",
                compatibleCoreIds = snesSaveCores,
            )

            else -> CoreSaveAdapter(
                artifactType = SaveArtifactType.CORE_SAVE,
                localSlot = "core-save",
                remoteSlot = "autosave",
                compatibleCoreIds = setOf(normalized),
            )
        }
    }
}
