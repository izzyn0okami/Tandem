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
        "nes" to listOf(
            core("fceumm", "FCEUmm", "Rápido e compatível"),
            core("nestopia", "Nestopia", "Mais preciso"),
            core("mesen", "Mesen", "Muito preciso, mais pesado"),
            core("quicknes", "QuickNES", "Muito leve"),
        ),
        "snes" to listOf(
            core("snes9x", "Snes9x", "Rápido e compatível"),
            core("snes9x2010", "Snes9x 2010", "Mais leve"),
            core("bsnes", "bsnes", "Mais preciso, bem mais pesado"),
        ),
        "gb" to gbCores(),
        "gbc" to gbCores(),
        "gba" to listOf(
            core("mgba", "mGBA", "Preciso e rápido"),
            core("gpsp", "gpSP", "Mais leve"),
            core("vbam", "VBA-M", "Clássico"),
            core("vba_next", "VBA Next", "Leve"),
        ),
        "genesis" to listOf(
            core("genesis_plus_gx", "Genesis Plus GX", "Preciso e completo"),
            core("picodrive", "PicoDrive", "Leve, com 32X"),
            core("blastem", "BlastEm", "Muito preciso"),
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
            core("parallel_n64", "ParaLLEl N64", "Alternativa leve"),
        ),
        "psx" to listOf(
            core("pcsx_rearmed", "PCSX ReARMed", "Leve e rápido"),
            core("swanstation", "SwanStation", "Mais preciso, mais pesado"),
        ),
        "pce" to listOf(
            core("mednafen_pce_fast", "Beetle PCE Fast", "Rápido"),
            core("mednafen_pce", "Beetle PCE", "Mais preciso"),
            core("geargrafx", "Geargrafx", "Alternativa moderna"),
        ),
        "atari2600" to listOf(
            core("stella", "Stella", "Padrão"),
            core("stella2014", "Stella 2014", "Mais leve"),
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
        ),
        "nds" to listOf(
            core("melonds", "melonDS", "Preciso, com toque direto"),
            core("desmume", "DeSmuME", "Alternativa"),
            core("desmume2015", "DeSmuME 2015", "Mais leve"),
            core("noods", "NooDS", "Leve"),
        ),
        "coleco" to listOf(
            core("gearcoleco", "Gearcoleco", "Padrão"),
            core("bluemsx", "blueMSX", "Alternativa"),
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

    /** Variáveis aplicadas ao abrir o jogo: as salvas e, no DS, as telas lado a lado como padrão. */
    fun variables(context: Context, coreId: String): Array<Variable> {
        val saved = savedOptions(context, coreId).toMutableMap()
        if (coreId == "melonds") saved.putIfAbsent("melonds_screen_layout", "Left/Right")
        return saved.map { Variable(key = it.key, value = it.value) }.toTypedArray()
    }

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
