package app.lawnchair.gamemode

import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Minimal iiDB client. API keys are sent only to the documented API host, never to asset URLs. */
internal object IidbSoundbites {
    private const val API_BASE = "https://iidb-api.iisu.network/api/v1/"
    private const val ASSET_HOST = "assets.iisu.network"
    private const val MAX_ASSET_BYTES = 8 * 1024 * 1024
    private const val MAX_DURATION_MS = 10_000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    enum class AccessResult { VALID, INVALID_KEY, DENIED, NETWORK_ERROR }

    suspend fun checkAccess(apiKey: String): AccessResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(API_BASE.toHttpUrl().newBuilder().addPathSegments("me/access").build())
            .header("X-API-Key", apiKey.trim())
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    in 200..299 -> AccessResult.VALID
                    401 -> AccessResult.INVALID_KEY
                    403 -> AccessResult.DENIED
                    else -> AccessResult.NETWORK_ERROR
                }
            }
        } catch (_: Exception) {
            AccessResult.NETWORK_ERROR
        }
    }

    suspend fun findSoundbite(
        context: Context,
        apiKey: String,
        gameKey: String,
        title: String,
        platform: String,
    ): File? = withContext(Dispatchers.IO) {
        try {
            val appContext = context.applicationContext
            val cache = File(appContext.cacheDir, "iidb-soundbites").apply { mkdirs() }
            val prefs = appContext.getSharedPreferences("iidb_game_cache", Context.MODE_PRIVATE)
            val stableKey = sha256("$platform|$title")
            val parentId = prefs.getInt("parent_$stableKey", 0).takeIf { it > 0 }
                ?: resolveParent(apiKey, title)?.also {
                    prefs.edit().putInt("parent_$stableKey", it).apply()
                }
                ?: return@withContext null

            val artworkUrl = API_BASE.toHttpUrl().newBuilder()
                .addPathSegments("assets/parents/$parentId")
                .addQueryParameter("slots", "soundbite")
                .build()
            val artwork = apiRequest(apiKey, artworkUrl.toString()) ?: return@withContext null
            val soundbites = artwork.optJSONObject("artwork")?.optJSONArray("soundbite")
                ?: return@withContext null

            for (index in 0 until soundbites.length()) {
                val asset = soundbites.optJSONObject(index) ?: continue
                val url = asset.optString("url").takeIf { it.isNotBlank() } ?: continue
                val mime = asset.optString("mime_type").lowercase()
                if (!mime.startsWith("audio/")) continue
                val duration = asset.optLong("duration_ms", 0L)
                if (duration > MAX_DURATION_MS) continue
                val size = asset.optLong("file_size_bytes", 0L)
                if (size > MAX_ASSET_BYTES) continue
                val assetId = asset.optLong("id", -1L)
                if (assetId <= 0L) continue
                val revision = asset.optString("revision")
                val destination = File(cache, "$assetId-${sha256(revision).take(12)}.audio")
                if (destination.isFile && destination.length() > 0L) return@withContext destination
                if (download(url, mime, destination)) return@withContext destination
            }
            GameLog.log("iiDB", "soundbite indisponível para $title ($platform)")
            null
        } catch (error: Exception) {
            GameLog.error("iiDB", "falha ao consultar soundbite de $title", error)
            null
        }
    }

    private fun resolveParent(apiKey: String, title: String): Int? {
        val body = JSONObject().put("title", title).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(API_BASE.toHttpUrl().newBuilder().addPathSegments("resolve").build())
            .header("X-API-Key", apiKey.trim())
            .header("Accept", "application/json")
            .post(body)
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val match = JSONObject(response.body?.string().orEmpty()).optJSONObject("match") ?: return@use null
            if (match.optString("status") != "matched") return@use null
            match.optJSONObject("parent")?.optInt("id")?.takeIf { it > 0 }
        }
    }

    private fun apiRequest(apiKey: String, url: String): JSONObject? {
        val request = Request.Builder()
            .url(url)
            .header("X-API-Key", apiKey.trim())
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            JSONObject(response.body?.string().orEmpty())
        }
    }

    private fun download(url: String, mime: String, destination: File): Boolean {
        val parsed = runCatching { url.toHttpUrl() }.getOrNull() ?: return false
        if (parsed.scheme != "https" || parsed.host != ASSET_HOST || parsed.port != 443 || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) {
            return false
        }
        val request = Request.Builder().url(parsed).header("Accept", mime).get().build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use false
            val body = response.body ?: return@use false
            val responseMime = body.contentType()?.type
            if (responseMime != "audio" || body.contentLength() > MAX_ASSET_BYTES) return@use false
            val temporary = File(destination.parentFile, destination.name + ".part")
            try {
                body.byteStream().use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(8 * 1024)
                        var total = 0
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            total += read
                            if (total > MAX_ASSET_BYTES) throw IllegalStateException("soundbite excede o limite de tamanho")
                            output.write(buffer, 0, read)
                        }
                    }
                }
                if (temporary.length() == 0L) return@use false
                if (!temporary.renameTo(destination)) {
                    temporary.copyTo(destination, overwrite = true)
                    temporary.delete()
                }
                true
            } catch (error: Exception) {
                temporary.delete()
                throw error
            }
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
