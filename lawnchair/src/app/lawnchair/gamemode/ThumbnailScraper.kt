package app.lawnchair.gamemode

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Resultado de uma tentativa de baixar a capa de um jogo. */
sealed interface ScrapeResult {
    data class Found(val file: File) : ScrapeResult
    data object NotFound : ScrapeResult
    data object Offline : ScrapeResult
}

/**
 * Scraper local: baixa a capa do jogo do acervo público de miniaturas do Libretro
 * (thumbnails.libretro.com), sem conta nem chave. As capas têm o nome oficial No-Intro do jogo,
 * então o app procura pelo nome do arquivo e, se não achar, pelo título. O índice de cada console e as
 * capas ficam guardados na pasta de imagens.
 */
object ThumbnailScraper {

    private const val HOST = "thumbnails.libretro.com"
    private const val INDEX_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    private const val MISS_RETRY_MS = 7L * 24 * 60 * 60 * 1000

    /** Pasta de cada plataforma no acervo do Libretro (nomes conferidos na lista do site). */
    private val folders = mapOf(
        "nes" to "Nintendo - Nintendo Entertainment System",
        "snes" to "Nintendo - Super Nintendo Entertainment System",
        "gb" to "Nintendo - Game Boy",
        "gbc" to "Nintendo - Game Boy Color",
        "gba" to "Nintendo - Game Boy Advance",
        "genesis" to "Sega - Mega Drive - Genesis",
        "sms" to "Sega - Master System - Mark III",
        "gg" to "Sega - Game Gear",
        "32x" to "Sega - 32X",
        "scd" to "Sega - Mega-CD - Sega CD",
        "n64" to "Nintendo - Nintendo 64",
        "psx" to "Sony - PlayStation",
        "pce" to "NEC - PC Engine - TurboGrafx 16",
        "atari2600" to "Atari - 2600",
        "atari5200" to "Atari - 5200",
        "atari7800" to "Atari - 7800",
        "lynx" to "Atari - Lynx",
        "jaguar" to "Atari - Jaguar",
        "ngp" to "SNK - Neo Geo Pocket",
        "wswan" to "Bandai - WonderSwan",
        "sg1000" to "Sega - SG-1000",
        "saturn" to "Sega - Saturn",
        "dreamcast" to "Sega - Dreamcast",
        "nds" to "Nintendo - Nintendo DS",
        "psp" to "Sony - PlayStation Portable",
        "3do" to "The 3DO Company - 3DO",
        "vb" to "Nintendo - Virtual Boy",
        "coleco" to "Coleco - ColecoVision",
        "intellivision" to "Mattel - Intellivision",
        "ps2" to "Sony - PlayStation 2",
        "gc" to "Nintendo - GameCube",
        "wii" to "Nintendo - Wii",
        "wiiu" to "Nintendo - Wii U",
        "3ds" to "Nintendo - Nintendo 3DS",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val tags = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val linkPattern = Regex("""href="([^"?/][^"]*?)\.png"""")
    private val unwanted = Regex("""\((Beta|Proto|Demo|Sample|Unl|Aftermarket|Pirate)""", RegexOption.IGNORE_CASE)

    /** Índice por plataforma: título normalizado -> nomes de arquivo do acervo. */
    private val indexes = ConcurrentHashMap<String, Map<String, List<String>>>()
    private val locks = ConcurrentHashMap<String, Any>()

    fun supports(platform: GamePlatform): Boolean = platform.slug in folders

    private fun normalize(text: String) = text.replace(tags, "").lowercase().filter { it.isLetterOrDigit() }

    private fun fingerprint(game: LocalGame): String {
        val digest = MessageDigest.getInstance("SHA-1").digest(File(game.path).nameWithoutExtension.lowercase().toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun scrapeDir(context: Context, slug: String) = File(File(GameFolders.dir(context, FolderKind.Assets), "scraped"), slug)

    /** Capa já baixada no aparelho, se houver. */
    fun cachedFile(context: Context, game: LocalGame): File? = File(scrapeDir(context, game.platform.slug), "${fingerprint(game)}.img").takeIf { it.isFile && it.length() > 0 }

    /** Bloqueante: procura e baixa a capa. Registra no log o que achou. */
    fun download(context: Context, game: LocalGame): ScrapeResult {
        val slug = game.platform.slug
        val folder = folders[slug] ?: return ScrapeResult.NotFound
        val dir = scrapeDir(context, slug).apply { mkdirs() }
        val print = fingerprint(game)
        val miss = File(dir, "$print.none")
        if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < MISS_RETRY_MS) return ScrapeResult.NotFound
        return try {
            val index = index(context, slug, folder, dir)
            val name = pick(index, game)
            if (name == null) {
                miss.writeText("")
                GameLog.log("arte", "scraper: sem capa para '${game.title}' em $folder")
                ScrapeResult.NotFound
            } else {
                fetch(folder, name, File(dir, "$print.img"))?.let {
                    GameLog.log("arte", "scraper: '${game.title}' -> $name")
                    ScrapeResult.Found(it)
                } ?: run {
                    miss.writeText("")
                    ScrapeResult.NotFound
                }
            }
        } catch (e: IOException) {
            GameLog.error("arte", "scraper offline ou com erro", e)
            ScrapeResult.Offline
        }
    }

    private fun pick(index: Map<String, List<String>>, game: LocalGame): String? {
        val base = File(game.path).nameWithoutExtension
        val candidates = index[normalize(base)] ?: index[normalize(game.title)] ?: return null
        candidates.firstOrNull { it.equals(base, ignoreCase = true) }?.let { return it }
        return candidates.minWithOrNull(compareBy({ unwanted.containsMatchIn(it) }, { regionRank(it) }, { it.length }))
    }

    private fun regionRank(name: String) = when {
        name.contains("USA") -> 0
        name.contains("World") -> 1
        name.contains("Europe") -> 2
        else -> 3
    }

    private fun index(context: Context, slug: String, folder: String, dir: File): Map<String, List<String>> {
        indexes[slug]?.let { return it }
        val lock = locks.getOrPut(slug) { Any() }
        synchronized(lock) {
            indexes[slug]?.let { return it }
            val file = File(dir, "index.txt")
            val names = if (file.isFile && System.currentTimeMillis() - file.lastModified() < INDEX_MAX_AGE_MS) {
                file.readLines()
            } else {
                fetchIndex(folder).also { file.writeText(it.joinToString("\n")) }
            }
            return names.groupBy { normalize(it) }.also { indexes[slug] = it }
        }
    }

    private fun boxartUrl(folder: String): HttpUrl.Builder = HttpUrl.Builder().scheme("https").host(HOST).addPathSegment(folder).addPathSegment("Named_Boxarts")

    private fun fetchIndex(folder: String): List<String> {
        val url = boxartUrl(folder).addPathSegment("").build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} ao listar $folder")
            val html = response.body.string()
            return linkPattern.findAll(html).map { Uri.decode(it.groupValues[1]) }.toList()
        }
    }

    private fun fetch(folder: String, name: String, target: File): File? {
        val url = boxartUrl(folder).addPathSegment("$name.png").build()
        val temp = File(target.parentFile, "${target.name}.${Thread.currentThread().id}.part")
        return try {
            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                temp.outputStream().use { response.body.byteStream().copyTo(it) }
            }
            if (temp.renameTo(target)) target else null
        } finally {
            temp.delete()
        }
    }
}
