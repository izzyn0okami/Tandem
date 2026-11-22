package app.lawnchair.gamemode

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import androidx.core.content.edit
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

class RomMHttpException(val statusCode: Int, message: String) : IOException(message)

/** ROM disponível no servidor RomM. */
data class RemoteGame(
    val id: Long,
    val title: String,
    val platform: GamePlatform,
    val fileName: String,
    val sizeBytes: Long,
    val coverPath: String?,
    /** Endereço externo da capa (do scraper), usado se o servidor não tiver o arquivo. */
    val coverUrl: String? = null,
    /** Nome do jogo no servidor (pasta ou arquivo), usado na URL de download. */
    val serverName: String = fileName,
    /** Arquivo dentro da pasta do jogo, quando o jogo é uma pasta com um único arquivo jogável. */
    val fileId: Long? = null,
    /** IDs dos arquivos que o RomM associou a esta ROM; usados para limitar o ZIP ao próprio jogo. */
    val fileIds: List<Long> = emptyList(),
    /** Jogo de vários arquivos (ex.: cue + bin): baixa a pasta inteira e abre [fileName]. */
    val folder: Boolean = false,
    /** Descrição/sinopse do jogo, quando o RomM tiver (vem do provedor de metadados dele). */
    val summary: String? = null,
) {
    /** Destino dentro da pasta de jogos: uma subpasta por plataforma (e uma pasta por jogo, se for de vários arquivos). */
    fun destination(root: File): File {
        val platformDir = File(root, platform.slug)
        val target = if (folder) File(File(platformDir, serverName), fileName) else File(platformDir, fileName)
        if (folder) {
            RomMFileReplacement.recoverDirectory(checkNotNull(target.parentFile))
        } else {
            RomMFileReplacement.recoverFile(target)
        }
        return target
    }
}

/** Arquivo de firmware (BIOS) guardado no servidor. */
data class RemoteFirmware(
    val id: Long,
    val fileName: String,
    val sizeBytes: Long? = null,
    val crcHash: String? = null,
    val md5Hash: String? = null,
    val sha1Hash: String? = null,
)

enum class RemoteFileKind(val path: String, val part: String) {
    Save("saves", "saveFile"),
    State("states", "stateFile"),
}

data class RemoteFile(val id: Long, val emulator: String?, val updatedAtMs: Long)

/** Pedido de aprovação do aparelho: o usuário confirma o código no RomM, pelo navegador. */
data class DevicePairing(
    val baseUrl: String,
    val deviceCode: String,
    val userCode: String,
    val verificationUrl: String,
    val expiresInSeconds: Int,
    val intervalSeconds: Int,
)

sealed class DevicePoll {
    data object Pending : DevicePoll()
    data object SlowDown : DevicePoll()
    data object Denied : DevicePoll()
    data object Expired : DevicePoll()
    data class Approved(val token: String) : DevicePoll()
    data class Failed(val message: String) : DevicePoll()
}

/** Endereço do servidor e token, guardados só neste aparelho. */
object RomMSettings {

    private const val FILE = "romm"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun url(context: Context): String? = prefs(context).getString("url", null)

    fun token(context: Context): String? = prefs(context).getString("token", null)

    fun isConnected(context: Context): Boolean = url(context) != null && token(context) != null

    fun save(context: Context, url: String, token: String) {
        prefs(context).edit {
            putString("url", url)
            putString("token", token)
        }
    }

    fun clear(context: Context) {
        SaveSync.cancelPending(context)
        prefs(context).edit { clear() }
        RomMCache.clear(context)
    }
}

/**
 * Cliente mínimo da API do RomM: conecta por código de pareamento (ou token), lista jogos e baixa
 * arquivos. Chamadas são bloqueantes: rodar em Dispatchers.IO.
 */
class RomMClient(private val baseUrl: String, private val token: String?) {

    val baseAddress: String get() = baseUrl.trimEnd('/')

    // Mesmos prazos do Argosy: servidor remoto ou atrás de túnel pode demorar a responder.
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        // Diagnóstico passivo: registra apenas HTTP não-2xx e falhas de rede/timeout.
        .eventListenerFactory {
            object : EventListener() {
                override fun responseHeadersEnd(call: Call, response: Response) {
                    if (!response.isSuccessful) {
                        GameLog.log(
                            "romm-http",
                            "HTTP ${response.code}: ${call.request().method} ${call.request().url.encodedPath}",
                        )
                    }
                }

                override fun callFailed(call: Call, ioe: IOException) {
                    GameLog.error(
                        "romm-http",
                        "falha de rede: ${call.request().method} ${call.request().url.encodedPath}",
                        ioe,
                    )
                }
            }
        }
        .build()

    /** Listar a biblioteca inteira e baixar jogos grandes podem ficar parados por bastante tempo. */
    private val longHttp = http.newBuilder()
        .readTimeout(15, TimeUnit.MINUTES)
        .writeTimeout(15, TimeUnit.MINUTES)
        .build()
    private val base: HttpUrl = baseUrl.trimEnd('/').plus("/").toHttpUrl()

    private fun request(url: HttpUrl): Request.Builder {
        val builder = Request.Builder().url(url)
        // O token só vai para o próprio servidor, nunca para endereços de fora.
        if (
            token != null &&
            url.scheme == base.scheme &&
            url.host == base.host &&
            url.port == base.port
        ) {
            builder.header("Authorization", "Bearer $token")
        }
        return builder
    }

    fun heartbeat(): Boolean = checkServer() == null

    /** Testa se o servidor responde; devolve nulo se está tudo bem ou o motivo da falha em palavras. */
    fun checkServer(): String? = try {
        http.newCall(request(base.resolve("api/heartbeat")!!).build()).execute().use { response ->
            if (!response.isSuccessful) {
                "o servidor respondeu ${response.code}"
            } else {
                val body = response.body.string()
                val heartbeat = runCatching { JSONObject(body) }.getOrNull()
                val versionFields = heartbeat?.keys()?.asSequence()
                    ?.filter { it.contains("version", ignoreCase = true) }
                    ?.map { key -> "$key=${heartbeat.optString(key)}" }
                    ?.toList()
                    .orEmpty()
                GameLog.log(
                    "romm",
                    "heartbeat: HTTP ${response.code}; " +
                        (versionFields.takeIf { it.isNotEmpty() }?.joinToString() ?: "versão não identificada"),
                )
                null
            }
        }
    } catch (e: IOException) {
        e.message?.let { "${e.javaClass.simpleName}: $it" } ?: e.javaClass.simpleName
    }

    /** Troca o código de pareamento gerado no RomM por um token de cliente. */
    fun exchangePairingCode(code: String): String {
        val body = JSONObject().put("code", code.trim()).toString().toRequestBody("application/json".toMediaType())
        val request = request(base.resolve("api/client-tokens/exchange")!!).post(body).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    when (response.code) {
                        404 -> "Código inválido ou expirado"
                        429 -> "Muitas tentativas, tente de novo daqui a pouco"
                        else -> "Falha na troca do código (${response.code})"
                    },
                )
            }
            return JSONObject(response.body.string()).getString("raw_token")
        }
    }

    /** Pede ao servidor um código de aprovação (fluxo de dispositivo, como TV): o login é feito no navegador. */
    fun beginDevicePairing(clientId: String, deviceName: String, version: String): DevicePairing {
        val body = JSONObject()
            .put("client_device_identifier", clientId)
            .put("name", deviceName)
            .put("client", "tandem")
            .put("platform", "Android")
            .put("client_version", version)
            .put("requested_scopes", org.json.JSONArray(DEVICE_SCOPES))
            .toString()
            .toRequestBody("application/json".toMediaType())
        val request = request(base.resolve("api/auth/device/init")!!).post(body).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException(
                    if (response.code == 404) "Esta versão do RomM não permite aprovar pelo aparelho; use o código de pareamento" else "Falha ao iniciar o pareamento (${response.code})",
                )
            }
            val json = JSONObject(response.body.string())
            val path = json.getString("verification_path_complete")
            val link = if (path.startsWith("http")) path else base.resolve(path.trimStart('/'))?.toString() ?: path
            return DevicePairing(
                baseUrl = baseUrl.trimEnd('/'),
                deviceCode = json.getString("device_code"),
                userCode = json.getString("user_code"),
                verificationUrl = link,
                expiresInSeconds = json.optInt("expires_in", 600),
                intervalSeconds = json.optInt("interval", 5),
            )
        }
    }

    /** Pergunta se o usuário já aprovou. Erros de rede passageiros contam como "ainda pendente". */
    fun pollDevicePairing(deviceCode: String): DevicePoll = try {
        val body = JSONObject().put("device_code", deviceCode).toString().toRequestBody("application/json".toMediaType())
        http.newCall(request(base.resolve("api/auth/device/token")!!).post(body).build()).execute().use { response ->
            val text = response.body.string()
            if (response.isSuccessful) {
                DevicePoll.Approved(JSONObject(text).getString("access_token"))
            } else {
                when (runCatching { JSONObject(text).optString("detail") }.getOrDefault("")) {
                    "authorization_pending" -> DevicePoll.Pending
                    "slow_down" -> DevicePoll.SlowDown
                    "access_denied" -> DevicePoll.Denied
                    "expired_token" -> DevicePoll.Expired
                    else -> DevicePoll.Failed("Pareamento falhou (${response.code})")
                }
            }
        }
    } catch (_: IOException) {
        DevicePoll.Pending
    }

    // Resumo do que o servidor tem e do que foi ignorado, para explicar uma lista vazia.
    @Volatile
    var listReport: String = ""
        private set

    private var serverTotal = 0
    private val skippedPlatforms = sortedMapOf<String, Int>()
    private var skippedMulti = 0
    private var skippedExtension = 0

    /** Lista ROMs compatíveis, respeitando o agrupamento e os arquivos associados pelo RomM. */
    fun listGames(): List<RemoteGame> {
        serverTotal = 0
        skippedPlatforms.clear()
        skippedMulti = 0
        skippedExtension = 0
        val games = mutableListOf<RemoteGame>()
        var offset = 0
        while (true) {
            val url = base.resolve("api/roms")!!.newBuilder()
                .addQueryParameter("order_by", "name")
                .addQueryParameter("order_dir", "asc")
                .addQueryParameter("limit", PAGE_SIZE.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("with_char_index", "false")
                .addQueryParameter("with_filter_values", "false")
                .addQueryParameter("with_files", "true")
                .build()
            val json = longHttp.newCall(request(url).build()).execute().use { response ->
                GameLog.log("romm", "GET api/roms offset=$offset -> ${response.code}")
                if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
                val text = response.body.string()
                if (offset == 0) GameLog.log("romm", "início da resposta: ${text.take(600)}")
                JSONObject(text)
            }
            val items = json.optJSONArray("items")
            if (items == null) {
                GameLog.log("romm", "resposta sem 'items'; chaves: ${json.keys().asSequence().toList()}")
                break
            }
            if (items.length() == 0) break
            for (i in 0 until items.length()) {
                parseGame(items.getJSONObject(i))?.let(games::add)
            }
            offset += items.length()
            serverTotal = json.optInt("total", offset)
            if (offset >= serverTotal) break
        }
        listReport = buildString {
            append("O servidor tem $serverTotal jogo(s); $offset lido(s); ${games.size} compatível(is).")
            if (skippedMulti > 0) append(" Ignorados por terem vários arquivos: $skippedMulti.")
            if (skippedExtension > 0) append(" Ignorados por extensão não suportada: $skippedExtension.")
            if (skippedPlatforms.isNotEmpty()) {
                append(" Plataformas ainda não suportadas: ")
                append(skippedPlatforms.entries.joinToString { "${it.key} (${it.value})" })
                append(".")
            }
        }
        return games
    }

    private fun parseGame(item: JSONObject): RemoteGame? {
        val slug = item.optString("platform_slug")
        val platform = GamePlatforms.fromRomM(slug)
        if (platform == null) {
            if (skippedPlatforms[slug.ifEmpty { "?" }] == null) GameLog.log("romm", "plataforma ignorada: '$slug'")
            skippedPlatforms[slug.ifEmpty { "?" }] = (skippedPlatforms[slug.ifEmpty { "?" }] ?: 0) + 1
            return null
        }
        // A disponibilidade física é decidida pelo RomM. Não tente baixar registros
        // que o próprio servidor informa não possuírem arquivo no disco.
        if (item.has("has_file_on_disk") && !item.optBoolean("has_file_on_disk")) return null
        val serverName = item.optString("fs_name").ifEmpty { return null }
        val allowed = platform.extensions + if (platform.discBased) emptySet() else GamePlatforms.genericExtensions
        // Arquivos só são tratados como um conjunto quando pertencem à mesma ROM no RomM.
        // Extensões auxiliares continuam válidas como ROMs independentes se o servidor
        // as cadastrou assim; não inferimos relação com outros registros pelo nome.
        val discFiles = allowed + platform.discCompanionExtensions
        val serverExtension = File(serverName).extension.lowercase()
        val flat = serverExtension in discFiles &&
            !item.optBoolean("has_multiple_files", false) &&
            !item.optBoolean("has_nested_single_file", false)
        if (platform.discBased && File(serverName).extension.lowercase() in setOf("cue", "gdi")) {
            val files = item.optJSONArray("files")
            val fileSummary = (0 until (files?.length() ?: 0)).take(12).map { index ->
                val file = files!!.getJSONObject(index)
                "${file.optLong("id", -1L)}:${file.optString("file_name")}"
            }
            GameLog.log(
                "romm",
                "metadados de disco: rom=${item.optLong("id", -1L)}; fs_name=$serverName; " +
                    "has_multiple_files=${item.optBoolean("has_multiple_files", false)}; " +
                    "has_nested_single_file=${item.optBoolean("has_nested_single_file", false)}; " +
                    "files=${files?.length() ?: 0}; amostra=$fileSummary",
            )
        }
        var fileName = serverName
        var fileId: Long? = null
        var fileIds: List<Long> = emptyList()
        var folder = false
        if (!flat) {
            val files = item.optJSONArray("files")
            val playable = (0 until (files?.length() ?: 0)).map { files!!.getJSONObject(it) }.filter {
                val name = it.optString("file_name")
                val category = it.optString("category").lowercase()
                val isGameFile = category.isBlank() || category == "null" ||
                    category !in setOf("manual", "walkthrough", "soundtrack", "screenshot")
                isGameFile && !name.startsWith(".") && File(name).extension.lowercase() in discFiles
            }
            if (playable.isEmpty()) {
                skippedExtension++
                return null
            }
            val names = playable.map { it.getString("file_name") }
            val ids = playable.mapNotNull { file -> file.optLong("id", -1L).takeIf { it > 0L } }
            // O RomM pode agrupar multidiscos CHD sem CUE/M3U. A associação dentro da mesma
            // ROM é a fonte de verdade: mais de uma imagem de disco significa um único conjunto.
            if (platform.discBased && playable.size > 1) {
                folder = true
                fileIds = ids
                fileName = names.firstOrNull { it.endsWith(".m3u", ignoreCase = true) }
                    ?: "${File(serverName).nameWithoutExtension}.m3u"
            } else if (platform.multiFileFolder && playable.size > 1) {
                // Switch e semelhantes: manter jogo base + atualização/DLC na mesma pasta,
                // mas abrir somente o arquivo principal.
                folder = true
                fileIds = ids
                val addonWords = listOf("update", "upd", "patch", "dlc", "atualiz", "addon", "add-on", "[v")
                val base = names.filterNot { name -> addonWords.any { name.contains(it, ignoreCase = true) } }
                    .ifEmpty { names }
                    .minByOrNull { File(it).nameWithoutExtension.length }
                fileName = base ?: names.first()
            } else if (playable.size > 1) {
                skippedMulti++
                return null
            } else {
                fileName = playable.single().getString("file_name")
                fileId = playable.single().getLong("id")
            }
        }
        return RemoteGame(
            id = item.getLong("id"),
            title = item.optString("name").ifEmpty { File(fileName).nameWithoutExtension },
            platform = platform,
            fileName = fileName,
            sizeBytes = item.optLong("fs_size_bytes", 0L),
            coverPath = (
                item.optString("path_cover_small")
                    .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
                    ?: item.optString("path_cover_large")
                        .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) }
                ).also { logCoverSample(item) },
            coverUrl = item.optString("url_cover")
                .takeUnless { it.isBlank() || it.equals("null", ignoreCase = true) },
            serverName = serverName,
            fileId = fileId,
            fileIds = fileIds,
            folder = folder,
            summary = item.optString("summary").ifEmpty { null },
        )
    }

    private var coverSamples = 0

    /** Guarda no log os campos de capa dos primeiros jogos, para descobrir de onde o servidor serve as imagens. */
    private fun logCoverSample(item: JSONObject) {
        if (coverSamples >= 2) return
        coverSamples++
        GameLog.log(
            "romm",
            "capa exemplo: small=${item.optString("path_cover_small")}, large=${item.optString("path_cover_large")}, " +
                "url=${item.optString("url_cover")}",
        )
    }

    /**
     * Instrumentação de download: registra rota, resposta e duração sem expor o token.
     */
    private fun <T> withDownloadResponse(
        url: HttpUrl,
        description: String,
        headers: Map<String, String> = emptyMap(),
        consume: (Response) -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtime()
        val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        GameLog.log("romm", "download início: $description; caminho=$path")
        try {
            val requestBuilder = request(url)
            headers.forEach { (name, value) -> requestBuilder.header(name, value) }
            return longHttp.newCall(requestBuilder.build()).execute().use { response ->
                GameLog.log(
                    "romm",
                    "download HTTP: $description; status=${response.code}; " +
                        "tipo=${response.header("Content-Type") ?: "-"}; " +
                        "bytes=${response.body.contentLength()}; caminho=${response.request.url.encodedPath}",
                )
                if (!response.isSuccessful) {
                    val errorBody = runCatching { response.peekBody(2048).string() }.getOrDefault("")
                    val safeError = errorBody
                        .replace(Regex("""(?i)(token|authorization|password)(\\s*[:=]\\s*)[^",\\s}]+"""), "\\$1\\$2[redacted]")
                        .take(240)
                    GameLog.log(
                        "romm",
                        "detalhe HTTP: status=${response.code}; corpo=${safeError.ifBlank { "<vazio>" }}",
                    )
                    throw RomMHttpException(response.code, "Servidor respondeu ${response.code} ao baixar $description")
                }
                consume(response)
            }
        } catch (e: Exception) {
            GameLog.error(
                "romm",
                "download falhou: $description; caminho=$path; " +
                    "duração=${SystemClock.elapsedRealtime() - startedAt}ms",
                e,
            )
            throw e
        } finally {
            GameLog.log("romm", "download fim: $description; duração=${SystemClock.elapsedRealtime() - startedAt}ms")
        }
    }

    /** Evita iniciar transferências que não cabem no armazenamento disponível. */
    private fun requireAvailableSpace(directory: File, requiredBytes: Long, description: String) {
        if (requiredBytes <= 0L) return
        val reserveBytes = maxOf(MINIMUM_FREE_SPACE_BYTES, requiredBytes / 20L)
        val neededBytes = if (Long.MAX_VALUE - requiredBytes < reserveBytes) Long.MAX_VALUE else requiredBytes + reserveBytes
        val availableBytes = StatFs(directory.absolutePath).availableBytes
        GameLog.log("romm", "espaço para download: $description; disponível=$availableBytes; necessário=$neededBytes")
        if (availableBytes < neededBytes) {
            throw IOException(
                "Espaço insuficiente para baixar $description: " +
                    "necessários aproximadamente ${neededBytes / (1024 * 1024)} MB, " +
                    "disponíveis ${availableBytes / (1024 * 1024)} MB.",
            )
        }
    }

    private fun estimatedBytes(vararg values: Long): Long = values.fold(0L) { total, value ->
        if (value <= 0L) {
            total
        } else if (Long.MAX_VALUE - total < value) {
            Long.MAX_VALUE
        } else {
            total + value
        }
    }

    /**
     * Continua um arquivo parcial usando HTTP Range. Se o servidor ignorar Range, reinicia
     * escrevendo a resposta completa; nunca concatena uma resposta 200 a um arquivo parcial.
     */
    private fun downloadResumable(
        url: HttpUrl,
        temp: File,
        description: String,
        estimateRequiredSpace: (Long) -> Long,
        onProgress: (Long) -> Unit,
    ): Long {
        val validatorFile = File(temp.parentFile, "${temp.name}.range")
        var offset = temp.length().takeIf { temp.isFile } ?: 0L
        if (offset > 0L && !validatorFile.isFile) {
            GameLog.log("romm", "sem validador HTTP salvo; descartando parcial para evitar juntar versões diferentes: $description")
            temp.delete()
            offset = 0L
        }
        var restartedAfter416 = false

        while (true) {
            val headers = if (offset > 0L) {
                buildMap {
                    put("Range", "bytes=$offset-")
                    validatorFile.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }?.let {
                        put("If-Range", it)
                    }
                }
            } else {
                emptyMap()
            }

            try {
                return withDownloadResponse(url, description, headers) { response ->
                    val append = offset > 0L && response.code == 206
                    val contentRange = response.header("Content-Range")
                    val rangeMatch = contentRange?.let {
                        Regex("""bytes (\d+)-(\d+)/(\d+|\*)""").matchEntire(it)
                    }
                    if (response.code == 206) {
                        val rangeStart = rangeMatch?.groupValues?.get(1)?.toLongOrNull()
                        if (rangeStart != (if (offset > 0L) offset else 0L)) {
                            temp.delete()
                            validatorFile.delete()
                            throw IOException("Resposta parcial inválida do RomM: Content-Range não corresponde ao trecho solicitado")
                        }
                    } else if (response.code != 200) {
                        throw RomMHttpException(response.code, "Resposta inesperada ao continuar download: HTTP ${response.code}")
                    }

                    if (offset > 0L && !append) {
                        GameLog.log("romm", "servidor não retomou o download; reiniciando desde o início: $description")
                        validatorFile.delete()
                    }

                    val validator = response.header("ETag")
                        ?.takeUnless { it.startsWith("W/") }
                        ?: response.header("Last-Modified")
                    if (validator != null) {
                        validatorFile.writeText(validator)
                    }

                    val writeOffset = if (append) offset else 0L
                    val rangeTotal = rangeMatch?.groupValues?.get(3)?.takeUnless { it == "*" }?.toLongOrNull()
                    val responseLength = response.body.contentLength()
                    val expectedTotal = rangeTotal ?: responseLength.takeIf { it >= 0L }?.let { it + writeOffset } ?: -1L
                    val requiredSpace = estimateRequiredSpace(expectedTotal.takeIf { it > 0L } ?: 0L)
                    requireAvailableSpace(
                        checkNotNull(temp.parentFile),
                        (requiredSpace - writeOffset).coerceAtLeast(0L),
                        description,
                    )

                    var downloaded = writeOffset
                    response.body.byteStream().use { input ->
                        FileOutputStream(temp, append).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                onProgress(downloaded)
                            }
                        }
                    }

                    val received = temp.length()
                    check(expectedTotal <= 0L || received == expectedTotal) {
                        "Download incompleto de $description: recebidos $received de $expectedTotal bytes"
                    }
                    GameLog.log(
                        "romm",
                        "download parcial concluído: $description; retomado=$append; bytes=$received; esperado=$expectedTotal",
                    )
                    received
                }
            } catch (error: RomMHttpException) {
                if (error.statusCode == 416 && offset > 0L && !restartedAfter416) {
                    GameLog.log("romm", "trecho parcial expirado no servidor; reiniciando download: $description")
                    temp.delete()
                    validatorFile.delete()
                    offset = 0L
                    restartedAfter416 = true
                    continue
                }
                if (error.statusCode in setOf(401, 403, 404)) {
                    temp.delete()
                    validatorFile.delete()
                }
                throw error
            }
        }
    }

    /** Baixa para um arquivo temporário e só renomeia no fim; [onProgress] recebe bytes baixados. */
    fun download(game: RemoteGame, target: File, onProgress: (Long) -> Unit) {
        if (game.folder) {
            downloadFolder(game, target, onProgress)
            return
        }
        // A rota /files/content também recebe o ID da ROM (Rom.id), não o ID do arquivo.
        // fileId apenas indica que selecionamos um arquivo específico dentro da ROM.
        val url = if (game.fileId != null) {
            base.resolve("api/roms/${game.id}/files/content/")!!.newBuilder()
                .addPathSegment(game.fileName)
                .build()
        } else {
            base.resolve("api/roms/${game.id}/content/")!!.newBuilder()
                .addPathSegment(game.serverName)
                .build()
        }
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        val description = "rom=${game.id}, título=${game.title}, arquivo=${game.serverName}, fileId=${game.fileId ?: "-"}"
        downloadResumable(
            url,
            temp,
            description,
            estimateRequiredSpace = { total ->
                val estimate = total.takeIf { it > 0L } ?: game.sizeBytes
                estimatedBytes(estimate, if (game.fileId != null) estimate else 0L)
            },
            onProgress = onProgress,
        )
        if (game.fileId != null && isZip(temp) && target.extension.lowercase() != "zip") {
            try {
                unzipSingle(temp, target)
            } catch (error: Exception) {
                temp.delete()
                File(temp.parentFile, "${temp.name}.range").delete()
                throw error
            }
        }
        RomMFileReplacement.replaceFile(temp, target)
        File(temp.parentFile, "${temp.name}.range").delete()
    }

    /** Baixa o ZIP completo de uma ROM multi-arquivo, valida e extrai o conteúdo e a M3U. */
    private fun downloadFolder(game: RemoteGame, target: File, onProgress: (Long) -> Unit) {
        val dir = checkNotNull(target.parentFile)
        val parent = checkNotNull(dir.parentFile)
        val temp = File(parent, "${dir.name}.part")
        val archive = File(parent, "${dir.name}.zip.part")
        temp.deleteRecursively()
        temp.mkdirs()

        val urlBuilder = base.resolve("api/roms/${game.id}/content/")!!
            .newBuilder()
            .addPathSegment(game.serverName)
        if (game.fileIds.isNotEmpty()) {
            urlBuilder.addQueryParameter("file_ids", game.fileIds.joinToString(","))
        }
        val url = urlBuilder.build()
        val description = "rom=${game.id}, título=${game.title}, pasta=${game.serverName}, " +
            "fileIds=${game.fileIds.size}"
        try {
            val archiveBytes = downloadResumable(
                url,
                archive,
                description,
                estimateRequiredSpace = { total ->
                    val archiveEstimate = total.takeIf { it > 0L } ?: game.sizeBytes
                    val extractionEstimate = game.sizeBytes.takeIf { it > 0L } ?: archiveEstimate
                    estimatedBytes(archiveEstimate, extractionEstimate)
                },
                onProgress = onProgress,
            )
            GameLog.log("romm", "pacote ZIP recebido: ${game.title}; bytes=$archiveBytes")
            if (!isZip(archive)) {
                archive.delete()
                File(archive.parentFile, "${archive.name}.range").delete()
                throw IOException(
                    "O RomM não entregou um ZIP válido para ${game.title} " +
                        "(assinatura inicial inválida; tamanho=${archive.length()} bytes)",
                )
            }

            var archiveEntries = 0
            var extractedFiles = 0
            var extractedBytes = 0L
            val entrySamples = mutableListOf<String>()
            java.util.zip.ZipFile(archive, Charsets.UTF_8).use { zip ->
                val canonicalTemp = temp.canonicalFile
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    archiveEntries++
                    if (entrySamples.size < 40) {
                        entrySamples += "${entry.name} (${entry.size} bytes)"
                    }
                    val out = File(temp, entry.name)
                    val canonicalOut = out.canonicalFile
                    // Protege contra caminhos absolutos e ../ dentro do pacote.
                    check(canonicalOut.path.startsWith(canonicalTemp.path + File.separator)) {
                        "Pacote inválido: caminho fora da pasta de destino"
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { input ->
                            out.outputStream().use { output ->
                                val buffer = ByteArray(64 * 1024)
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    extractedBytes += read
                                }
                            }
                        }
                        extractedFiles++
                    }
                }
            }
            GameLog.log(
                "romm",
                "ZIP extraído: ${game.title}; entradas=$archiveEntries; arquivos=$extractedFiles; " +
                    "bytesExtraídos=$extractedBytes; amostra=${entrySamples.joinToString(" | ")}",
            )
            check(extractedFiles > 0) { "O ZIP de ${game.title} não contém arquivos extraíveis" }

            // O pacote pode trazer tudo dentro de uma pasta única: sobe o conteúdo um nível.
            val onlyChild = temp.listFiles()?.singleOrNull()
            val content = if (onlyChild != null && onlyChild.isDirectory) onlyChild else temp
            val contentRoot = content.canonicalFile
            val expectedPlaylist = File(content, game.fileName)
            check(expectedPlaylist.canonicalPath.startsWith(contentRoot.path + File.separator)) {
                "Caminho de arquivo inválido no pacote de ${game.title}"
            }

            if (game.fileName.endsWith(".m3u", ignoreCase = true)) {
                val supportedDiscs = setOf("cue", "gdi", "bin", "img", "sub", "ccd", "wav", "raw", "chd", "iso", "cdi", "cso", "pbp")
                val existingPlaylists = content.walkTopDown()
                    .filter { it.isFile && it.extension.equals("m3u", ignoreCase = true) }
                    .toList()
                val sourcePlaylist = existingPlaylists.firstOrNull {
                    it.name.equals(expectedPlaylist.name, ignoreCase = true)
                } ?: existingPlaylists.firstOrNull()

                fun resolvePlaylist(playlist: File): Pair<List<File>, Boolean> {
                    val lines = playlist.readLines()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                    val resolved = lines.mapNotNull { line ->
                        runCatching { File(playlist.parentFile, line).canonicalFile }.getOrNull()
                            ?.takeIf {
                                it.isFile &&
                                    it.path.startsWith(contentRoot.path + File.separator) &&
                                    it.extension.lowercase() in supportedDiscs
                            }
                    }
                    return resolved to (lines.isNotEmpty() && resolved.size == lines.size)
                }

                val sourceResolution = sourcePlaylist?.let(::resolvePlaylist)
                val expectedResolution = if (expectedPlaylist.isFile) resolvePlaylist(expectedPlaylist) else null
                val validExistingPlaylist = expectedResolution?.second == true
                if (!validExistingPlaylist) {
                    val discoveredDiscs = content.walkTopDown()
                        .filter { it.isFile && it.extension.lowercase() in supportedDiscs }
                        .toList()
                    val referencedDiscs = sourceResolution?.takeIf { it.second }?.first.orEmpty()
                    val discs = referencedDiscs.ifEmpty { discoveredDiscs }
                    val primaryDiscs = discs.filter {
                        it.extension.lowercase() in setOf("cue", "gdi", "ccd", "chd", "iso", "cdi", "cso", "pbp")
                    }
                    val selectedDiscs = (primaryDiscs.ifEmpty { discs })
                        .distinctBy { it.canonicalPath }
                        .sortedBy { naturalSortKey(it.relativeTo(content).invariantSeparatorsPath) }
                    check(selectedDiscs.isNotEmpty()) {
                        "Nenhum disco compatível encontrado em ${game.title} " +
                            "(entradas ZIP=$archiveEntries; arquivos extraídos=$extractedFiles; " +
                            "amostra=${entrySamples.joinToString(" | ")})"
                    }
                    expectedPlaylist.parentFile?.mkdirs()
                    val entries = selectedDiscs
                        .map { it.relativeTo(expectedPlaylist.parentFile ?: content).invariantSeparatorsPath }
                    expectedPlaylist.writeText(entries.joinToString("\n") + "\n")
                    GameLog.log(
                        "romm",
                        "lista M3U criada/reparada: ${game.title}; discos=${entries.size}; " +
                            "caminhos=${entries.joinToString()}",
                    )
                } else {
                    GameLog.log("romm", "M3U do RomM validada: ${game.title}; discos=${expectedResolution?.first?.size ?: 0}")
                }
            } else {
                check(expectedPlaylist.isFile) {
                    "O ZIP do RomM não contém o arquivo principal ${game.fileName} " +
                        "(entradas ZIP=$archiveEntries; arquivos extraídos=$extractedFiles)"
                }
            }

            // Mantém a pasta anterior recuperável até a nova pasta estar no destino.
            RomMFileReplacement.replaceDirectory(content, dir)
            temp.deleteRecursively()
            archive.delete()
            File(archive.parentFile, "${archive.name}.range").delete()
            GameLog.log("romm", "download multidisco concluído: ${game.title}; pasta=${dir.name}")
        } catch (error: Exception) {
            temp.deleteRecursively()
            // Falha de rede mantém o ZIP parcial para a próxima tentativa; falha após
            // completar o arquivo descarta um pacote inválido para não retomá-lo em loop.
            if (isZip(archive) && archive.length() > 0L) {
                val zipComplete = runCatching {
                    java.util.zip.ZipFile(archive, Charsets.UTF_8).use { it.entries().hasMoreElements() }
                }.getOrDefault(false)
                if (zipComplete) {
                    archive.delete()
                    File(archive.parentFile, "${archive.name}.range").delete()
                }
            }
            throw error
        } finally {
            temp.deleteRecursively()
        }
    }

    private fun naturalSortKey(value: String): String = Regex("(\\d+)").replace(value.lowercase()) { match ->
        match.value.toLongOrNull()?.toString()?.padStart(12, '0') ?: match.value
    }

    /** Arquivos de firmware (BIOS) que o servidor guarda, de todas as plataformas. */
    fun listFirmware(): List<RemoteFirmware> {
        val text = http.newCall(request(base.resolve("api/platforms")!!).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
            response.body.string()
        }
        val platforms = org.json.JSONArray(text)
        val result = mutableListOf<RemoteFirmware>()
        for (i in 0 until platforms.length()) {
            val firmware = platforms.getJSONObject(i).optJSONArray("firmware") ?: continue
            for (j in 0 until firmware.length()) {
                val item = firmware.getJSONObject(j)
                if (item.optBoolean("missing_from_fs", false)) continue
                result += RemoteFirmware(
                    id = item.getLong("id"),
                    fileName = item.getString("file_name"),
                    sizeBytes = item.optLong("file_size_bytes", 0L).takeIf { it > 0L },
                    crcHash = item.optString("crc_hash").takeUnless { it.isBlank() || it.equals("null", true) },
                    md5Hash = item.optString("md5_hash").takeUnless { it.isBlank() || it.equals("null", true) },
                    sha1Hash = item.optString("sha1_hash").takeUnless { it.isBlank() || it.equals("null", true) },
                )
            }
        }
        return result
    }

    fun downloadFirmware(firmware: RemoteFirmware, target: File) {
        val url = base.resolve("api/firmware/${firmware.id}/content/")!!
            .newBuilder()
            .addPathSegment(firmware.fileName)
            .build()
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        val description = "firmware=${firmware.id}, arquivo=${firmware.fileName}"
        downloadResumable(
            url,
            temp,
            description,
            estimateRequiredSpace = { total -> total.takeIf { it > 0L } ?: firmware.sizeBytes ?: 0L },
            onProgress = {},
        )
        try {
            verifyFirmwareIntegrity(temp, firmware)
        } catch (error: Exception) {
            temp.delete()
            File(temp.parentFile, "${temp.name}.range").delete()
            throw error
        }
        RomMFileReplacement.replaceFile(temp, target)
        File(temp.parentFile, "${temp.name}.range").delete()
    }

    /** Compara o arquivo recebido com tamanho e hashes publicados pelo RomM, quando disponíveis. */
    private fun verifyFirmwareIntegrity(file: File, firmware: RemoteFirmware) {
        firmware.sizeBytes?.let { expected ->
            check(file.length() == expected) {
                "Firmware ${firmware.fileName} incompleto: recebidos ${file.length()} de $expected bytes"
            }
        }
        val expectedHashes = listOfNotNull(
            firmware.crcHash?.let { "CRC32" to it },
            firmware.md5Hash?.let { "MD5" to it },
            firmware.sha1Hash?.let { "SHA-1" to it },
        )
        if (expectedHashes.isEmpty()) return

        val crc = if (firmware.crcHash != null) CRC32() else null
        val md5 = if (firmware.md5Hash != null) MessageDigest.getInstance("MD5") else null
        val sha1 = if (firmware.sha1Hash != null) MessageDigest.getInstance("SHA-1") else null
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                crc?.update(buffer, 0, read)
                md5?.update(buffer, 0, read)
                sha1?.update(buffer, 0, read)
            }
        }
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val actualHashes = buildMap {
            crc?.let { put("CRC32", it.value.toString(16).padStart(8, '0')) }
            md5?.let { put("MD5", hex(it.digest())) }
            sha1?.let { put("SHA-1", hex(it.digest())) }
        }
        expectedHashes.forEach { (algorithm, expected) ->
            val actual = actualHashes.getValue(algorithm)
            check(actual.equals(expected.trim(), ignoreCase = true)) {
                "Integridade do firmware ${firmware.fileName} inválida: hash $algorithm não corresponde"
            }
        }
        GameLog.log("romm", "integridade do firmware confirmada: ${firmware.fileName}; verificações=${expectedHashes.map { it.first }}")
    }

    private fun downloadToFile(url: HttpUrl, target: File, description: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        try {
            downloadResumable(
                url,
                temp,
                description,
                estimateRequiredSpace = { it },
                onProgress = {},
            )
            RomMFileReplacement.replaceFile(temp, target)
            File(temp.parentFile, "${temp.name}.range").delete()
        } catch (error: Exception) {
            throw error
        }
    }

    private fun isZip(file: File): Boolean = file.inputStream().use {
        val head = ByteArray(4)
        it.read(head) == 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte()
    }

    /** O servidor às vezes empacota a pasta num zip: tira de dentro o arquivo do jogo. */
    private fun unzipSingle(zip: File, target: File) {
        val out = File(zip.parentFile, "${zip.name}.out")
        java.util.zip.ZipInputStream(zip.inputStream()).use { input ->
            var entry = input.nextEntry
            while (entry != null && (entry.isDirectory || File(entry.name).name != target.name)) entry = input.nextEntry
            checkNotNull(entry) { "O pacote do servidor não contém ${target.name}" }
            out.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                }
            }
        }
        RomMFileReplacement.replaceFile(out, zip)
    }

    /** Baixa a capa para o cache e devolve o arquivo (ou nulo se falhar). */
    fun cover(context: Context, game: RemoteGame): File? {
        if (game.coverPath == null && game.coverUrl == null) return null
        val file = RomMCovers.coverFile(context, game.id).apply { parentFile?.mkdirs() }
        if (file.isFile && file.length() > 0) return file
        // Grava num arquivo temporário e só então renomeia: outra tela nunca lê uma capa pela metade.
        val temp = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.part")
        return runCatching {
            val downloaded = listOfNotNull(game.coverPath, game.coverUrl)
                .flatMap { coverUrls(it) }
                .any { downloadCover(it, temp) }
            if (!downloaded) {
                null
            } else {
                RomMFileReplacement.replaceFile(temp, file)
                file
            }
        }.also { temp.delete() }.getOrNull()
    }

    /** O RomM serve as imagens em /assets/romm/resources/<caminho>; o caminho da lista vem sem esse prefixo. */
    private fun coverUrls(path: String): List<HttpUrl> {
        if (path.startsWith("http")) return listOfNotNull(path.toHttpUrlOrNull())
        val clean = path.trimStart('/')
        val candidates = if (clean.startsWith("assets/")) listOf(clean) else listOf("assets/romm/resources/$clean", clean)
        return candidates.mapNotNull { base.resolve(it) }
    }

    private fun downloadCover(url: HttpUrl, temp: File): Boolean {
        return runCatching {
            http.newCall(request(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    if (coverFailuresLogged.getAndIncrement() < MAX_COVER_FAILURE_LOGS) {
                        GameLog.log("romm", "capa: HTTP ${response.code} em ${url.host}${url.encodedPath}")
                    }
                    return@use false
                }
                temp.outputStream().use { response.body.byteStream().copyTo(it) }
                true
            }
        }.getOrDefault(false)
    }

    /** Saves (bateria) e estados guardados no servidor para um jogo. */
    fun listRemoteFiles(kind: RemoteFileKind, romId: Long): List<RemoteFile> {
        val url = base.resolve("api/${kind.path}")!!.newBuilder().addQueryParameter("rom_id", romId.toString()).build()
        val text = http.newCall(request(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
            response.body.string()
        }
        val array = org.json.JSONArray(text)
        return (0 until array.length()).map { parseRemoteFile(array.getJSONObject(it)) }
    }

    private fun parseRemoteFile(json: JSONObject) = RemoteFile(
        id = json.getLong("id"),
        emulator = json.optString("emulator").ifEmpty { null },
        updatedAtMs = parseTime(json.optString("updated_at")),
    )

    private fun parseTime(text: String): Long = runCatching { java.time.OffsetDateTime.parse(text).toInstant().toEpochMilli() }
        .recoverCatching { java.time.Instant.parse(text).toEpochMilli() }
        .recoverCatching { java.time.LocalDateTime.parse(text).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
        .getOrDefault(0L)

    fun downloadRemoteFile(kind: RemoteFileKind, id: Long, target: File) {
        val url = base.resolve("api/${kind.path}/$id/content")!!
        downloadToFile(url, target, "${kind.path}=$id")
    }

    /** Envia o arquivo: atualiza o existente ([existingId]) ou cria um novo; devolve o registro no servidor. */
    fun uploadRemoteFile(kind: RemoteFileKind, romId: Long, emulator: String, existingId: Long?, file: File, uploadName: String): RemoteFile {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(kind.part, uploadName, file.asRequestBody("application/octet-stream".toMediaType()))
            .build()
        val builder = if (existingId != null) {
            request(base.resolve("api/${kind.path}/$existingId")!!).put(body)
        } else {
            val url = base.resolve("api/${kind.path}")!!.newBuilder()
                .addQueryParameter("rom_id", romId.toString())
                .addQueryParameter("emulator", emulator)
                .build()
            request(url).post(body)
        }
        longHttp.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw RomMHttpException(response.code, "Servidor respondeu ${response.code}")
            }
            return parseRemoteFile(JSONObject(response.body.string()))
        }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MINIMUM_FREE_SPACE_BYTES = 32L * 1024L * 1024L
        const val MAX_COVER_FAILURE_LOGS = 8
        val coverFailuresLogged = AtomicInteger()
        val DEVICE_SCOPES = listOf(
            "me.read",
            "platforms.read",
            "roms.read",
            "roms.user.read",
            "roms.user.write",
            "assets.read",
            "assets.write",
            "firmware.read",
            "devices.read",
            "devices.write",
        )
    }
}

/**
 * Substitui arquivos por rename no mesmo diretório (atômico no filesystem Android).
 * Para diretórios, usa backup + rollback porque um diretório não vazio não pode ser
 * sobrescrito por rename; a cópia anterior só é removida depois que a nova está no lugar.
 */
internal object RomMFileReplacement {

    @Synchronized
    fun replaceFile(staged: File, target: File) {
        require(staged.isFile) { "Arquivo temporário ausente: ${staged.name}" }
        requireSameParent(staged, target)
        recoverBackups(target, directory = false, cleanupIfTargetExists = true)
        if (staged.renameTo(target)) return

        if (!target.exists()) {
            throw IOException("Não consegui gravar ${target.name}")
        }
        val backup = backupFor(target)
        if (!target.renameTo(backup)) {
            throw IOException("Não consegui preservar o arquivo anterior ${target.name}")
        }
        try {
            if (!staged.renameTo(target)) {
                throw IOException("Não consegui gravar ${target.name}")
            }
        } catch (error: Exception) {
            if (!backup.renameTo(target)) {
                error.addSuppressed(IOException("A versão anterior foi preservada em ${backup.name}, mas não pôde ser restaurada automaticamente"))
            }
            throw IOException("Falha ao substituir ${target.name}; a versão anterior foi preservada", error)
        }
        if (!backup.delete()) {
            GameLog.log("romm", "substituição concluída; backup antigo não pôde ser removido: ${backup.name}")
        }
    }

    @Synchronized
    fun replaceDirectory(staged: File, target: File) {
        require(staged.isDirectory) { "Pasta temporária ausente: ${staged.name}" }
        requireSameParent(staged, target)
        recoverBackups(target, directory = true, cleanupIfTargetExists = true)
        if (!target.exists()) {
            if (!staged.renameTo(target)) throw IOException("Não consegui gravar ${target.name}")
            return
        }
        if (!target.isDirectory) {
            throw IOException("O destino ${target.name} existe, mas não é uma pasta")
        }

        val backup = backupFor(target)
        if (!target.renameTo(backup)) {
            throw IOException("Não consegui preservar a pasta anterior ${target.name}")
        }
        try {
            if (!staged.renameTo(target)) {
                throw IOException("Não consegui gravar ${target.name}")
            }
        } catch (error: Exception) {
            if (!backup.renameTo(target)) {
                error.addSuppressed(IOException("A pasta anterior foi preservada em ${backup.name}, mas não pôde ser restaurada automaticamente"))
            }
            throw IOException("Falha ao substituir ${target.name}; a pasta anterior foi preservada", error)
        }
        if (!backup.deleteRecursively()) {
            GameLog.log("romm", "substituição concluída; backup antigo não pôde ser removido: ${backup.name}")
        }
    }

    @Synchronized
    fun recoverFile(target: File) {
        recoverBackups(target, directory = false, cleanupIfTargetExists = false)
    }

    @Synchronized
    fun recoverDirectory(target: File) {
        recoverBackups(target, directory = true, cleanupIfTargetExists = false)
    }

    /**
     * Recupera uma cópia anterior deixada por encerramento inesperado entre os dois renames
     * de uma troca de diretório, ou limpa backups após uma troca que já chegou ao destino.
     */
    private fun recoverBackups(target: File, directory: Boolean, cleanupIfTargetExists: Boolean) {
        val parent = checkNotNull(target.parentFile)
        val prefix = ".${target.name}.backup-"
        val backups = parent.listFiles { file ->
            file.name.startsWith(prefix) && (if (directory) file.isDirectory else file.isFile)
        }.orEmpty()
        if (backups.isEmpty()) return

        if (!target.exists()) {
            val backup = backups.maxByOrNull { it.name } ?: return
            if (!backup.renameTo(target)) {
                throw IOException("Não consegui recuperar a versão anterior de ${target.name} em ${backup.name}")
            }
            GameLog.log("romm", "versão anterior recuperada após interrupção: ${target.name}")
            backups.filter { it != backup }.forEach(::deleteBackup)
        } else if (cleanupIfTargetExists) {
            backups.forEach(::deleteBackup)
        }
    }

    private fun deleteBackup(backup: File) {
        val deleted = if (backup.isDirectory) backup.deleteRecursively() else backup.delete()
        if (!deleted) GameLog.log("romm", "backup antigo não pôde ser removido: ${backup.name}")
    }

    private fun requireSameParent(staged: File, target: File) {
        val stagedParent = staged.canonicalFile.parentFile
        val targetParent = target.canonicalFile.parentFile
        require(stagedParent != null && stagedParent == targetParent) {
            "O arquivo temporário e o destino precisam estar no mesmo diretório"
        }
    }

    private fun backupFor(target: File): File = File(
        checkNotNull(target.parentFile),
        ".${target.name}.backup-${SystemClock.elapsedRealtimeNanos()}",
    )
}
