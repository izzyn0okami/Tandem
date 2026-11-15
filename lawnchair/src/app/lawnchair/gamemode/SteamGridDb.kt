package app.lawnchair.gamemode

import android.content.Context
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Scraper opcional do SteamGridDB (precisa de uma chave de API gratuita do próprio usuário).
 * Serve para jogos que o acervo do Libretro não cobre: Android, PC, Switch e outros.
 */
object SteamGridDb {

    private const val MISS_RETRY_MS = 7L * 24 * 60 * 60 * 1000
    private val tags = Regex("""\s*[(\[][^)\]]*[)\]]""")
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun api(vararg segments: String): HttpUrl.Builder {
        val builder = HttpUrl.Builder().scheme("https").host("www.steamgriddb.com").addPathSegments("api/v2")
        segments.forEach { builder.addPathSegment(it) }
        return builder
    }

    private fun dir(context: Context) = File(File(GameFolders.dir(context, FolderKind.Assets), "scraped"), "steamgriddb")

    private fun fingerprint(key: String): String = MessageDigest.getInstance("SHA-1").digest(key.lowercase().toByteArray()).take(8).joinToString("") { "%02x".format(it) }

    /** Capa já baixada para esse jogo, se houver. [key] identifica o jogo (plataforma + título). */
    fun cachedFile(context: Context, key: String): File? = File(dir(context), "${fingerprint(key)}.img").takeIf { it.isFile && it.length() > 0 }

    private fun request(url: HttpUrl, apiKey: String) = Request.Builder().url(url).header("Authorization", "Bearer $apiKey").build()

    /** Testa a chave. Devolve null se estiver certa, ou a mensagem de erro. */
    fun check(apiKey: String): String? = try {
        http.newCall(request(api("search", "autocomplete", "zelda").build(), apiKey)).execute().use { response ->
            when {
                response.isSuccessful -> null
                response.code == 401 || response.code == 403 -> "Chave recusada pelo SteamGridDB"
                else -> "Erro HTTP ${response.code}"
            }
        }
    } catch (e: IOException) {
        "Sem conexão: ${e.message}"
    }

    /** Bloqueante: procura o jogo pelo título e baixa a primeira capa vertical. */
    fun download(context: Context, key: String, title: String): ScrapeResult {
        val apiKey = GameModeSettings.steamGridKey(context) ?: return ScrapeResult.NotFound
        val folder = dir(context).apply { mkdirs() }
        val print = fingerprint(key)
        val miss = File(folder, "$print.none")
        if (miss.isFile && System.currentTimeMillis() - miss.lastModified() < MISS_RETRY_MS) return ScrapeResult.NotFound
        val search = title.replace(tags, "").trim().ifEmpty { title }
        return try {
            val id = firstId(search, apiKey)
            val url = id?.let { firstGrid(it, apiKey) }
            if (url == null) {
                miss.writeText("")
                GameLog.log("arte", "steamgriddb: sem capa para '$title'")
                ScrapeResult.NotFound
            } else {
                val target = File(folder, "$print.img")
                val temp = File(folder, "$print.${Thread.currentThread().id}.part")
                try {
                    http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code} ao baixar a capa")
                        temp.outputStream().use { response.body.byteStream().copyTo(it) }
                    }
                    if (temp.renameTo(target)) {
                        GameLog.log("arte", "steamgriddb: '$title' ok")
                        ScrapeResult.Found(target)
                    } else {
                        ScrapeResult.NotFound
                    }
                } finally {
                    temp.delete()
                }
            }
        } catch (e: IOException) {
            GameLog.error("arte", "steamgriddb offline ou com erro", e)
            ScrapeResult.Offline
        }
    }

    private fun firstId(term: String, apiKey: String): Int? {
        http.newCall(request(api("search", "autocomplete", term).build(), apiKey)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} na busca")
            val data = JSONObject(response.body.string()).optJSONArray("data") ?: return null
            return if (data.length() > 0) data.getJSONObject(0).optInt("id").takeIf { it > 0 } else null
        }
    }

    private fun firstGrid(id: Int, apiKey: String): HttpUrl? {
        val url = api("grids", "game", id.toString())
            .addQueryParameter("dimensions", "600x900")
            .addQueryParameter("types", "static")
            .addQueryParameter("nsfw", "false")
            .build()
        http.newCall(request(url, apiKey)).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} nas capas")
            val data = JSONObject(response.body.string()).optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            return data.getJSONObject(0).optString("url").takeIf { it.startsWith("https://") }?.toHttpUrlOrNull()
        }
    }
}
