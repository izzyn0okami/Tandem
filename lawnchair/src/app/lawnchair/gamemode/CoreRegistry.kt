package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit
import com.swordfish.libretrodroid.Variable
import org.json.JSONObject

/** Um núcleo Libretro que sabe jogar uma plataforma. */
data class CoreInfo(val id: String, val label: String, val note: String)

/** Uma opção do núcleo (nome, valores permitidos e valor atual), lida do próprio núcleo em execução. */
data class CoreOption(val key: String, val label: String, val values: List<String>, val current: String) {
    fun next(): String = values[(values.indexOf(current) + 1) % values.size]
}

/**
 * Núcleos alternativos por plataforma (o primeiro de cada lista é o padrão) e as opções que o
 * usuário mudou em cada núcleo. As opções são lidas do núcleo em execução, então valem para
 * qualquer núcleo, sem lista fixa.
 */
object CoreRegistry {

    private const val FILE = "game_mode_cores"

    private fun core(id: String, label: String, note: String) = CoreInfo(id, label, note)

    private val catalog: Map<String, List<CoreInfo>> = mapOf(
        "gc" to listOf(
            core("dolphin", "Dolphin", "GameCube; Vulkan ou OpenGL ES 3.0; exige Sys"),
        ),
        "wii" to listOf(
            core("dolphin", "Dolphin", "Wii; Vulkan ou OpenGL ES 3.0; exige Sys"),
        ),
        "wiiu" to listOf(
            core("cemu", "Cemu", "Wii U; Vulkan obrigatório no Android; keys.txt opcional para imagens descriptografadas"),
        ),
        "amstradcpc" to listOf(
            core("cap32", "Caprice32", "Amstrad CPC"),
        ),
        "ps2" to listOf(
            core("pcee2", "PCEE2", "PCSX2 ARM64 experimental; Vulkan/OpenGL; saves, states e memory monitoring"),
            core("armsx2", "ARMSX2", "PCSX2 ARM64; Vulkan por padrão, OpenGL ES 3.2 ou software"),
            core("pcsx2", "PCSX2 Libretro", "Alternativa experimental; validar recursos e estabilidade"),
        ),
        "3ds" to listOf(
            core("azahar", "Azahar", "Core 3DS atual; requer ROMs descriptografadas"),
            core("panda3ds", "Panda3DS", "Alternativa experimental; validar compatibilidade"),
        ),
        "vita" to listOf(
            core("vita3k", "Vita3K", "Experimental; Vulkan 1.1, firmware e dados próprios; sem save states/RA"),
        ),
        "ps3" to listOf(
            core("rpcs3", "RPCS3", "Experimental e muito exigente; Vulkan no Android; firmware PS3"),
        ),
        "xbox360" to listOf(
            core("xenia_edge", "Xenia Edge", "Experimental; Vulkan 1.1; saves no armazenamento próprio"),
        ),
        "atari8" to listOf(
            core("atari800", "Atari800", "Atari 8-bit / 5200"),
        ),
        "atarist" to listOf(
            core("hatari", "Hatari", "Atari ST/STE/TT/Falcon"),
            core("hatari2014", "Hatari 2014", "Alternativa mais antiga"),
            core("hatarib", "HatariB", "Alternativa"),
        ),
        "amiga" to listOf(
            core("puae", "PUAE", "Commodore Amiga"),
            core("puae2021", "PUAE 2021", "Versão anterior"),
            core("amiberry", "Amiberry", "Alternativa"),
        ),
        "c64" to listOf(
            core("vice_x64", "VICE x64", "Commodore 64"),
            core("vice_x64sc", "VICE x64sc", "Emulação mais precisa"),
        ),
        "zxspectrum" to listOf(
            core("fuse", "Fuse", "ZX Spectrum"),
        ),
        "dos" to listOf(
            core("dosbox_pure", "DOSBox Pure", "DOSBox para jogos e imagens ZIP"),
            core("dosbox_core", "DOSBox Core", "Alternativa para software DOS"),
            core("dosbox", "DOSBox", "Compatibilidade clássica"),
            core("dosbox_svn", "DOSBox SVN", "Versão de desenvolvimento"),
            core("rust_dos", "Rust DOS", "Ambiente DOS alternativo"),
        ),
        "scummvm" to listOf(
            core("scummvm", "ScummVM", "Aventuras gráficas clássicas"),
        ),
        "apple2" to listOf(
            core("applewin", "AppleWin", "Apple II"),
        ),
        "x68000" to listOf(
            core("px68k", "PX68k", "Sharp X68000"),
        ),
        "pc98" to listOf(
            core("np2kai", "Neko Project II Kai", "NEC PC-98"),
        ),
        "pcfx" to listOf(
            core("mednafen_pcfx", "Beetle PC-FX", "NEC PC-FX"),
        ),
        "neogeocd" to listOf(
            core("neocd", "NeoCD", "Neo Geo CD"),
        ),
        "pokemini" to listOf(
            core("pokemini", "PokeMini", "Pokémon Mini"),
        ),
        "gamewatch" to listOf(
            core("gw", "Game & Watch", "Nintendo Game & Watch"),
        ),
        "megaduck" to listOf(
            core("potator", "Potator", "Mega Duck / Watara Supervision"),
        ),
        "arduboy" to listOf(
            core("ardens", "Ardens", "Arduboy"),
        ),
        "arcade" to listOf(
            core("fbneo", "FinalBurn Neo", "Arcade e Neo Geo"),
            core("mamearcade", "MAME Arcade", "Compatibilidade ampla; maior consumo"),
            core("mame2003", "MAME 2003", "Conjuntos de ROMs clássicos"),
            core("mame2000", "MAME 2000", "Compatibilidade retro"),
            core("mame2003_plus", "MAME 2003 Plus", "Conjuntos de ROMs clássicos"),
            core("mame2010", "MAME 2010", "Compatibilidade intermediária"),
            core("mame2015", "MAME 2015", "Compatibilidade ampla"),
            core("mame2016", "MAME 2016", "Compatibilidade ampla"),
            core("hbmame", "HBMAME", "Variantes e hacks de arcade"),
            core("supermodel", "Supermodel", "Sega Model 3 arcade"),
            core("fbalpha2012", "FB Alpha 2012", "Arcade legado"),
            core("fbalpha2012_cps1", "FB Alpha 2012 CPS-1", "Capcom CPS-1"),
            core("fbalpha2012_cps2", "FB Alpha 2012 CPS-2", "Capcom CPS-2"),
            core("fbalpha2012_cps3", "FB Alpha 2012 CPS-3", "Capcom CPS-3"),
            core("hypseushw", "Hypseus", "Arcade LaserDisc"),
        ),
        "supergrafx" to listOf(
            core("mednafen_supergrafx", "Beetle SuperGrafx", "NEC SuperGrafx"),
        ),
        "nes" to listOf(
            core("fceumm", "FCEUmm", "Rápido e compatível"),
            core("nestopia", "Nestopia", "Mais preciso"),
            core("mesen", "Mesen", "Muito preciso, mais pesado"),
            core("quicknes", "QuickNES", "Muito leve"),
            core("rustynes", "RustyNES", "Alternativa moderna"),
        ),
        "snes" to listOf(
            core("snes9x", "Snes9x", "Rápido e compatível"),
            core("snes9x2010", "Snes9x 2010", "Mais leve"),
            core("bsnes", "bsnes", "Mais preciso, bem mais pesado"),
            core("bsnes-jg", "bsnes-jg", "Alternativa de alta precisão"),
            core("snes9x2002", "Snes9x 2002", "Muito leve"),
            core("snes9x2005", "Snes9x 2005", "Leve"),
            core("snes9x2005_plus", "Snes9x 2005 Plus", "Leve, revisão Plus"),
            core("mednafen_supafaust", "Beetle Supafaust", "Alta precisão"),
        ),
        "gb" to gbCores(),
        "gbc" to gbCores(),
        "gba" to listOf(
            core("mgba", "mGBA", "Preciso e rápido"),
            core("gpsp", "gpSP", "Mais leve"),
            core("vbam", "VBA-M", "Clássico"),
            core("vba_next", "VBA Next", "Leve"),
            core("mednafen_gba", "Beetle GBA", "Alternativa precisa"),
            core("meteor", "Meteor", "Alternativa experimental"),
        ),
        "genesis" to listOf(
            core("genesis_plus_gx", "Genesis Plus GX", "Preciso e completo"),
            core("picodrive", "PicoDrive", "Leve, com 32X"),
            core("blastem", "BlastEm", "Muito preciso"),
            core("genesis_plus_gx_wide", "Genesis Plus GX Wide", "Versão com suporte widescreen"),
        ),
        "sms" to masterSystemCores(),
        "gg" to masterSystemCores(),
        "scd" to listOf(
            core("genesis_plus_gx", "Genesis Plus GX", "Preciso e completo"),
            core("picodrive", "PicoDrive", "Leve"),
        ),
        "n64" to listOf(
            core("mupen64plus_next_gles3", "Mupen64Plus-Next (GLES3)", "Melhor qualidade"),
            core("mupen64plus_next_gles2", "Mupen64Plus-Next (GLES2)", "Para aparelhos mais antigos"),
            core("parallel_n64", "ParaLLEl N64", "Alternativa com opções gráficas avançadas"),
        ),
        "psx" to listOf(
            core("pcsx_rearmed", "PCSX ReARMed", "Leve e rápido"),
            core("swanstation", "SwanStation", "Mais preciso, mais pesado"),
            core("mednafen_psx", "Beetle PSX", "Alta precisão, renderização por software; exige mais desempenho"),
            core("mednafen_psx_hw", "Beetle PSX HW", "Renderização por hardware; exige GPU compatível"),
        ),
        "pce" to listOf(
            core("mednafen_pce_fast", "Beetle PCE Fast", "Rápido"),
            core("mednafen_pce", "Beetle PCE", "Mais preciso"),
            core("mednafen_supergrafx", "Beetle SuperGrafx", "NEC SuperGrafx"),
            core("geargrafx", "Geargrafx", "Alternativa moderna"),
        ),
        "atari2600" to listOf(
            core("stella", "Stella", "Padrão"),
            core("stella2014", "Stella 2014", "Mais leve"),
            core("stella2023", "Stella 2023", "Versão mais recente"),
        ),
        "atari5200" to listOf(
            core("a5200", "a5200", "Padrão"),
            core("atari800", "Atari800", "Alternativa"),
        ),
        "lynx" to listOf(
            core("handy", "Handy", "Leve"),
            core("mednafen_lynx", "Beetle Lynx", "Alternativa"),
            core("gearlynx", "Gearlynx", "Moderno"),
        ),
        "ngp" to listOf(
            core("mednafen_ngp", "Beetle NeoPop", "Padrão"),
            core("race", "RACE", "Leve"),
        ),
        "saturn" to listOf(
            core("mednafen_saturn", "Beetle Saturn", "Preciso, pesado"),
            core("yabasanshiro", "YabaSanshiro", "Mais leve"),
            core("yabause", "Yabause", "Alternativa"),
            core("ymir", "Ymir", "Alternativa moderna"),
        ),
        "nds" to listOf(
            core("melonds", "melonDS", "Preciso, com toque direto"),
            core("desmume", "DeSmuME", "Alternativa"),
            core("desmume2015", "DeSmuME 2015", "Mais leve"),
            core("noods", "NooDS", "Leve"),
            core("melondsds", "melonDS DS", "Alternativa com foco em compatibilidade"),
        ),
        "coleco" to listOf(
            core("gearcoleco", "Gearcoleco", "Padrão"),
            core("bluemsx", "blueMSX", "Alternativa"),
        ),
        "dreamcast" to listOf(
            core("flycast", "Flycast", "Padrão"),
            core("parallel_dc", "Parallel DC", "Alternativa experimental"),
        ),
        "neogeo" to listOf(
            core("fbneo", "FinalBurn Neo", "Arcade e Neo Geo"),
            core("geolith", "Geolith", "Alternativa Neo Geo"),
        ),
        "msx" to listOf(
            core("bluemsx", "blueMSX", "Padrão"),
            core("openmsx", "openMSX", "Alternativa de alta precisão"),
        ),
        "cdi" to listOf(
            core("same_cdi", "SAME CD-i", "Philips CD-i"),
        ),
        "playdia" to listOf(
            core("playdiaemu", "Playdia Emulator", "Bandai Playdia"),
        ),
        "vectrex" to listOf(
            core("vecx", "VecX", "GCE Vectrex"),
        ),
        "odyssey2" to listOf(
            core("o2em", "O2EM", "Magnavox Odyssey² / Videopac"),
        ),
        "pc88" to listOf(
            core("quasi88", "QUASI88", "NEC PC-8801"),
        ),
        "sharpx1" to listOf(
            core("x1", "Sharp X1", "Sharp X1 computer"),
        ),
        "virtualxt" to listOf(
            core("virtualxt", "VirtualXT", "IBM PC/XT emulator"),
        ),
        "tic80" to listOf(
            core("tic80", "TIC-80", "Fantasy computer"),
        ),
        "wasm4" to listOf(
            core("wasm4", "WASM-4", "Fantasy console"),
        ),
        "uzebox" to listOf(
            core("uzem", "Uzem", "Uzebox"),
        ),
        "commanderx16" to listOf(
            core("x16emu", "Commander X16", "Commander X16"),
        ),
        "wqx" to listOf(
            core("wqxemu", "WQX Emulator", "WQX handheld"),
        ),
        "tamagotchi" to listOf(
            core("tamalibretro", "TamaLibRetro", "Tamagotchi"),
        ),
        "javame" to listOf(
            core("squirreljme", "SquirrelJME", "Java ME runtime"),
        ),
        "intellivision" to listOf(
            core("freeintv", "FreeIntv", "Padrão"),
        ),
        "easyrpg" to listOf(
            core("easyrpg", "EasyRPG Player", "RPG Maker 2000/2003"),
        ),
    )

    private fun gbCores() = listOf(
        core("mgba", "mGBA", "Preciso e rápido"),
        core("gambatte", "Gambatte", "Muito preciso"),
        core("sameboy", "SameBoy", "Muito preciso"),
        core("gearboy", "Gearboy", "Leve"),
    )

    private fun masterSystemCores() = listOf(
        core("genesis_plus_gx", "Genesis Plus GX", "Preciso e completo"),
        core("smsplus", "SMS Plus GX", "Leve"),
        core("gearsystem", "Gearsystem", "Moderno"),
    )

    /** Núcleos da plataforma, com o padrão da plataforma sempre em primeiro. */
    fun cores(platform: GamePlatform): List<CoreInfo> {
        val list = catalog[platform.slug].orEmpty()
        if (list.firstOrNull()?.id == platform.coreId) return list
        val default = list.firstOrNull { it.id == platform.coreId } ?: core(platform.coreId, platform.coreId, "Padrão")
        return listOf(default) + list.filter { it.id != platform.coreId }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Núcleo escolhido para a plataforma (o padrão, se o usuário nunca mudou). */
    fun chosen(context: Context, platform: GamePlatform): String {
        val id = prefs(context).getString("core_${platform.slug}", null)
        return id?.takeIf { wanted -> cores(platform).any { it.id == wanted } } ?: platform.coreId
    }

    fun setChosen(context: Context, platform: GamePlatform, coreId: String) {
        prefs(context).edit { putString("core_${platform.slug}", coreId) }
        GameLog.log("núcleos", "${platform.slug}: núcleo escolhido = $coreId")
    }

    /** Opções salvas pelo usuário para o núcleo (chave -> valor). */
    fun savedOptions(context: Context, coreId: String): Map<String, String> {
        val text = prefs(context).getString("opts_$coreId", null) ?: return emptyMap()
        return runCatching {
            val json = JSONObject(text)
            json.keys().asSequence().associateWith { json.getString(it) }
        }.getOrDefault(emptyMap())
    }

    fun saveOption(context: Context, coreId: String, key: String, value: String) {
        val json = JSONObject(savedOptions(context, coreId))
        json.put(key, value)
        prefs(context).edit { putString("opts_$coreId", json.toString()) }
    }

    fun resetOptions(context: Context, coreId: String) {
        prefs(context).edit { remove("opts_$coreId") }
    }

    /** Variáveis aplicadas ao abrir o jogo: as que o usuário salvou para o núcleo. */
    fun variables(context: Context, coreId: String): Array<Variable> = savedOptions(context, coreId).map { Variable(key = it.key, value = it.value) }.toTypedArray()

    /** Converte as variáveis do núcleo ("Nome; a|b|c") em opções que dá para alternar. */
    fun parse(variables: Array<Variable>): List<CoreOption> = variables.mapNotNull { variable ->
        val key = variable.key ?: return@mapNotNull null
        val description = variable.description ?: return@mapNotNull null
        val separator = description.indexOf(';')
        if (separator < 0) return@mapNotNull null
        val values = description.substring(separator + 1).split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (values.size < 2) return@mapNotNull null
        CoreOption(key, description.substring(0, separator).trim(), values, variable.value ?: values.first())
    }.sortedBy { it.label.lowercase() }
}
