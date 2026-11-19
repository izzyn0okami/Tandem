package app.lawnchair.gamemode

import android.content.Context
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.graphics.drawable.toBitmap
import java.io.File

/**
 * Arte dos jogos: a capa que o RomM já tem (o RomM guarda as capas dos scrapers dele; o Tandem só
 * reaproveita a lista e as imagens) e, quando não há, uma capa gerada no aparelho.
 */
object GameArt {

    private const val CACHE_BYTES = 24 * 1024 * 1024
    private const val ANDROID_ICON_SIZE = 192
    private const val OFFLINE_PAUSE_MS = 60_000L

    private val memory = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    /** Métricas do cache já limitado em bytes. */
    fun cacheEntryCount(): Int = memory.snapshot().size

    fun cacheBytes(): Int = memory.size()

    /** Depois de uma falha de rede, não tenta baixar capas por um tempo (evita esperas por capa). */
    @Volatile
    private var offlineUntilMs = 0L

    /** Rápido (sem rede): capa do servidor se já estiver no aparelho, senão a gerada. */
    fun quick(context: Context, game: LocalGame): ImageBitmap {
        memory.get("cover:${game.path}")?.let { return it }
        serverCover(context, game, allowNetwork = false)?.let { return it }
        scrapedCover(context, game, allowNetwork = false)?.let { return it }
        gridCover(context, "${game.platform.slug}|${game.title}", game.title, "cover:${game.path}", allowNetwork = false)?.let { return it }
        return memory.get("gen:${game.path}") ?: ArtBox.forPlatform(game.title, game.platform).also { memory.put("gen:${game.path}", it) }
    }

    /** Pode usar a rede: baixa a capa do servidor se o jogo existir lá. Nulo = sem capa do servidor. */
    fun fetchCover(context: Context, game: LocalGame): ImageBitmap? {
        if (System.currentTimeMillis() < offlineUntilMs) return null
        return serverCover(context, game, allowNetwork = true)
            ?: scrapedCover(context, game, allowNetwork = true)
            ?: gridCover(context, "${game.platform.slug}|${game.title}", game.title, "cover:${game.path}", allowNetwork = true)
    }

    /** Capa do SteamGridDB (só com chave do usuário); serve para o que o Libretro não cobre. */
    private fun gridCover(context: Context, key: String, title: String, memoryKey: String, allowNetwork: Boolean): ImageBitmap? {
        if (GameModeSettings.steamGridKey(context) == null || !GameModeSettings.isScraperEnabled(context)) return null
        var file = SteamGridDb.cachedFile(context, key)
        if (file == null && allowNetwork) {
            when (val result = SteamGridDb.download(context, key, title)) {
                is ScrapeResult.Found -> file = result.file
                ScrapeResult.Offline -> offlineUntilMs = System.currentTimeMillis() + OFFLINE_PAUSE_MS
                ScrapeResult.NotFound -> Unit
            }
        }
        val image = file?.let { RomMCovers.decode(it) } ?: return null
        memory.put(memoryKey, image)
        return image
    }

    /** Capa do SteamGridDB para um jogo Android, se houver chave; nulo = usa o ícone sobre cor. */
    fun androidCover(context: Context, game: AndroidGame): ImageBitmap? {
        if (System.currentTimeMillis() < offlineUntilMs) return null
        return gridCover(context, "android|${game.title}", game.title, "androidcover:${game.packageName}", allowNetwork = true)
    }

    /**
     * Arte grande (hero/banner) para o fundo da tela de detalhes, só do SteamGridDB (precisa de chave).
     * Nulo = a tela de detalhes usa a capa normal ampliada e desfocada no lugar.
     */
    fun heroArt(context: Context, key: String, title: String): ImageBitmap? {
        if (GameModeSettings.steamGridKey(context) == null || !GameModeSettings.isScraperEnabled(context)) return null
        if (System.currentTimeMillis() < offlineUntilMs) return null
        val memoryKey = "hero:$key"
        memory.get(memoryKey)?.let { return it }
        var file = SteamGridDb.cachedHeroFile(context, key)
        if (file == null) {
            when (val result = SteamGridDb.downloadHero(context, key, title)) {
                is ScrapeResult.Found -> file = result.file
                ScrapeResult.Offline -> offlineUntilMs = System.currentTimeMillis() + OFFLINE_PAUSE_MS
                ScrapeResult.NotFound -> Unit
            }
        }
        val image = file?.let { RomMCovers.decode(it) } ?: return null
        memory.put(memoryKey, image)
        return image
    }

    /** Capa do acervo do Libretro (scraper local), se o jogo for de um console suportado. */
    private fun scrapedCover(context: Context, game: LocalGame, allowNetwork: Boolean): ImageBitmap? {
        if (!GameModeSettings.isScraperEnabled(context) || !ThumbnailScraper.supports(game.platform)) return null
        var file = ThumbnailScraper.cachedFile(context, game)
        if (file == null && allowNetwork) {
            when (val result = ThumbnailScraper.download(context, game)) {
                is ScrapeResult.Found -> file = result.file
                ScrapeResult.Offline -> offlineUntilMs = System.currentTimeMillis() + OFFLINE_PAUSE_MS
                ScrapeResult.NotFound -> Unit
            }
        }
        val image = file?.let { RomMCovers.decode(it) } ?: return null
        memory.put("cover:${game.path}", image)
        return image
    }

    /** Capa gerada para um jogo do servidor que não tem imagem. */
    fun generated(title: String, platform: GamePlatform): ImageBitmap {
        val key = "gen:${platform.slug}:$title"
        return memory.get(key) ?: ArtBox.forPlatform(title, platform).also { memory.put(key, it) }
    }

    fun androidArt(context: Context, game: AndroidGame): ImageBitmap {
        val key = "android:${game.packageName}"
        memory.get(key)?.let { return it }
        val icon = runCatching { context.packageManager.getApplicationIcon(game.packageName).toBitmap(ANDROID_ICON_SIZE, ANDROID_ICON_SIZE) }.getOrNull()
        return ArtBox.forAndroid(game.title, icon).also { memory.put(key, it) }
    }

    private fun serverCover(context: Context, game: LocalGame, allowNetwork: Boolean): ImageBitmap? {
        val url = RomMSettings.url(context) ?: return null
        val remote = RomMIndex.find(context, url, game) ?: return null
        if (remote.coverPath == null && remote.coverUrl == null) return null
        val client = runCatching { RomMClient(url, RomMSettings.token(context)) }.getOrNull() ?: return null
        val image = RomMCovers.load(context, client, remote, allowNetwork)
        if (image == null && allowNetwork && !RomMCovers.coverFile(context, remote.id).isFile) {
            offlineUntilMs = System.currentTimeMillis() + OFFLINE_PAUSE_MS
            GameLog.log("arte", "sem capa para ${game.title}; pausando downloads de capa por 1 minuto")
        }
        return image?.also { memory.put("cover:${game.path}", it) }
    }
}

/** Liga um jogo local ao mesmo jogo na lista do RomM: pelo nome do arquivo e, se não achar, pelo título. */
private object RomMIndex {

    private var stamp = -1L
    private var byFile = emptyMap<String, RemoteGame>()
    private var byTitle = emptyMap<String, RemoteGame>()
    private val tags = Regex("""\s*[(\[][^)\]]*[)\]]""")

    private fun normalize(text: String) = text.replace(tags, "").lowercase().filter { it.isLetterOrDigit() }

    @Synchronized
    fun find(context: Context, url: String, game: LocalGame): RemoteGame? {
        val current = RomMCache.stamp(context)
        if (current != stamp) {
            val list = RomMCache.load(context, url).orEmpty()
            byFile = list.associateBy { "${it.platform.slug}|${it.fileName.lowercase()}" }
            byTitle = list.associateBy { "${it.platform.slug}|${normalize(it.title)}" }
            stamp = current
        }
        val slug = game.platform.slug
        return byFile["$slug|${File(game.path).name.lowercase()}"] ?: byTitle["$slug|${normalize(game.title)}"]
    }
}
