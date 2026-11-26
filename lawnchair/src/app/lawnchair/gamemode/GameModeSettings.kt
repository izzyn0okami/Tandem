package app.lawnchair.gamemode

import android.content.Context
import androidx.core.content.edit

/**
 * Preferências mínimas do modo jogos (M1). Ficam em SharedPreferences simples
 * para não depender do DataStore do Lawnchair enquanto o modo ainda é um esqueleto.
 */
object GameModeSettings {

    private const val FILE = "game_mode"
    private const val KEY_AUTO_ENTER = "auto_enter_on_controller"
    private const val KEY_SCRAPER = "scraper_thumbnails"
    private const val KEY_SOUND = "navigation_sound"
    private const val KEY_RA_RICH_PRESENCE = "ra_rich_presence_visible"
    private const val KEY_SOUNDBITES = "theme_music_enabled"
    private const val KEY_SOUNDBITES_AUTO_DETAILS = "soundbites_auto_details"

    private const val KEY_VIRTUAL_GAMEPAD_ENABLED = "virtual_gamepad_enabled"
    private const val KEY_VIRTUAL_GAMEPAD_AUTO_HIDE = "virtual_gamepad_auto_hide"
    private const val KEY_VIRTUAL_GAMEPAD_OPACITY = "virtual_gamepad_opacity"
    private const val KEY_VIRTUAL_GAMEPAD_SCALE = "virtual_gamepad_scale"
    private const val KEY_VIRTUAL_GAMEPAD_OFFSET_X = "virtual_gamepad_offset_x"
    private const val KEY_VIRTUAL_GAMEPAD_OFFSET_Y = "virtual_gamepad_offset_y"

    fun isVirtualGamepadEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_VIRTUAL_GAMEPAD_ENABLED, true)

    fun setVirtualGamepadEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(KEY_VIRTUAL_GAMEPAD_ENABLED, enabled) }
    }

    fun isVirtualGamepadAutoHideEnabled(context: Context): Boolean =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_VIRTUAL_GAMEPAD_AUTO_HIDE, true)

    fun setVirtualGamepadAutoHideEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putBoolean(KEY_VIRTUAL_GAMEPAD_AUTO_HIDE, enabled) }
    }

    fun virtualGamepadOpacity(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat(KEY_VIRTUAL_GAMEPAD_OPACITY, 0.76f).coerceIn(0.25f, 1f)

    fun setVirtualGamepadOpacity(context: Context, value: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putFloat(KEY_VIRTUAL_GAMEPAD_OPACITY, value.coerceIn(0.25f, 1f)) }
    }

    fun virtualGamepadScale(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat(KEY_VIRTUAL_GAMEPAD_SCALE, 1f).coerceIn(0.7f, 1.3f)

    fun setVirtualGamepadScale(context: Context, value: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putFloat(KEY_VIRTUAL_GAMEPAD_SCALE, value.coerceIn(0.7f, 1.3f)) }
    }

    fun virtualGamepadOffsetX(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat(KEY_VIRTUAL_GAMEPAD_OFFSET_X, 0f).coerceIn(-0.08f, 0.08f)

    fun setVirtualGamepadOffsetX(context: Context, value: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putFloat(KEY_VIRTUAL_GAMEPAD_OFFSET_X, value.coerceIn(-0.08f, 0.08f)) }
    }

    fun virtualGamepadOffsetY(context: Context): Float =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getFloat(KEY_VIRTUAL_GAMEPAD_OFFSET_Y, 0f).coerceIn(-0.08f, 0.08f)

    fun setVirtualGamepadOffsetY(context: Context, value: Float) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit { putFloat(KEY_VIRTUAL_GAMEPAD_OFFSET_Y, value.coerceIn(-0.08f, 0.08f)) }
    }

    /** Exibição do Rich Presence do RetroAchievements durante a sessão; ativada por padrão. */
    fun isRaRichPresenceVisible(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_RA_RICH_PRESENCE, true)

    fun setRaRichPresenceVisible(context: Context, visible: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_RA_RICH_PRESENCE, visible)
        }
    }

    /** Som de clique ao mover o foco com o controle (usa o som do sistema; some se "sons de toque" estiver desligado). */
    fun isSoundEnabled(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_SOUND, true)

    fun setSoundEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SOUND, enabled)
        }
    }
    private const val KEY_STEAMGRID = "steamgriddb_key"

    /** Chave de API do SteamGridDB (opcional); sem ela o scraper do SteamGridDB fica desligado. */
    fun steamGridKey(context: Context): String? = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_STEAMGRID, null)?.takeIf { it.isNotBlank() }

    fun setSteamGridKey(context: Context, key: String?) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            if (key.isNullOrBlank()) remove(KEY_STEAMGRID) else putString(KEY_STEAMGRID, key.trim())
        }
    }

    /** Baixar capas da internet (acervo do Libretro) para jogos sem capa do RomM. */
    fun isScraperEnabled(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_SCRAPER, true)

    fun setScraperEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SCRAPER, enabled)
        }
    }

    fun isAutoEnterEnabled(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_AUTO_ENTER, true)

    fun setAutoEnterEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_AUTO_ENTER, enabled)
        }
    }

    /** Soundbites curtos da iiDB para o jogo em destaque; desligado por padrão. */
    fun isSoundbitesEnabled(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_SOUNDBITES, false)

    fun setSoundbitesEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SOUNDBITES, enabled)
        }
        if (!enabled) GameThemeMusic.stop("recurso desativado")
    }

    /** Inicia o soundbite ao abrir os detalhes de um jogo; desligado por padrão. */
    fun isSoundbitesAutoDetailsEnabled(context: Context): Boolean = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(KEY_SOUNDBITES_AUTO_DETAILS, false)

    fun setSoundbitesAutoDetailsEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit {
            putBoolean(KEY_SOUNDBITES_AUTO_DETAILS, enabled)
        }
    }
}
