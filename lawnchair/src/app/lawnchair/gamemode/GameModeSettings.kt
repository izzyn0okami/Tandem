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
