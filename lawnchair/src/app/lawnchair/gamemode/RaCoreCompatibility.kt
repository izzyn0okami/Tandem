package app.lawnchair.gamemode

/**
 * Allowlist conservadora de núcleos reconhecidos oficialmente pelo RetroAchievements.
 *
 * Compatibilidade de memória Libretro, por si só, não habilita RA. Cada plataforma precisa ter
 * Console ID e o coreId selecionado precisa constar nesta matriz.
 */
object RaCoreCompatibility {

    private val supportedCores: Map<String, Set<String>> = mapOf(
        "nes" to setOf("fceumm", "mesen", "quicknes"),
        "fds" to setOf("mesen"),
        "snes" to setOf("snes9x"),
        "gb" to setOf("mgba", "gambatte", "gearboy", "vbam"),
        "gbc" to setOf("mgba", "gambatte", "gearboy", "vbam"),
        "gba" to setOf("mgba", "vbam", "vba_next", "mednafen_gba"),
        "genesis" to setOf("genesis_plus_gx", "picodrive"),
        "sms" to setOf("genesis_plus_gx", "gearsystem"),
        "gg" to setOf("genesis_plus_gx", "gearsystem"),
        "32x" to setOf("picodrive"),
        "scd" to setOf("genesis_plus_gx", "picodrive"),
        "n64" to setOf("mupen64plus_next_gles3", "mupen64plus_next_gles2", "parallel_n64"),
        "psx" to setOf("mednafen_psx", "mednafen_psx_hw", "swanstation"),
        "pce" to setOf("mednafen_pce_fast", "mednafen_supergrafx"),
        "atari2600" to setOf("stella", "stella2014"),
        "ngp" to setOf("mednafen_ngp"),
        "wswan" to setOf("mednafen_wswan"),
        "sg1000" to setOf("genesis_plus_gx", "bluemsx"),
        "saturn" to setOf("mednafen_saturn"),
        "dreamcast" to setOf("flycast"),
        "nds" to setOf("melonds_ds", "melonds", "desmume"),
        "dsi" to setOf("melonds_ds"),
        "pc88" to setOf("quasi88"),
        "pokemini" to setOf("pokemini"),
        "amstradcpc" to setOf("cap32"),
        "supervision" to setOf("potator"),
        "psp" to setOf("ppsspp"),
        "3do" to setOf("opera"),
        "vb" to setOf("mednafen_vb"),
        "atari7800" to setOf("prosystem"),
        "lynx" to setOf("handy", "mednafen_lynx"),
        "jaguar" to setOf("virtualjaguar"),
        "coleco" to setOf("bluemsx"),
        "msx" to setOf("bluemsx"),
        "intellivision" to setOf("freeintv"),
        "neogeo" to setOf("fbneo"),
        "arcade" to setOf("fbneo"),
        "neogeocd" to setOf("neocd"),
        "supergrafx" to setOf("mednafen_supergrafx"),
        "pcfx" to setOf("mednafen_pcfx"),
        "vectrex" to setOf("vecx"),
        "odyssey2" to setOf("o2em"),
        "wasm4" to setOf("wasm4"),
        "arduboy" to setOf("ardens"),
        "uzebox" to setOf("uzem"),
    )

    fun isSupported(platform: GamePlatform, coreId: String): Boolean = platform.raConsoleId != null && coreId in supportedCores[platform.slug].orEmpty()

    fun supportedCoreIds(platformSlug: String): Set<String> = supportedCores[platformSlug].orEmpty()
}
