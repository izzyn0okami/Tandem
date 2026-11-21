package app.lawnchair.gamemode

import android.content.Context
import android.os.StatFs
import android.os.SystemClock
import androidx.core.content.edit
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        return if (folder) File(File(platformDir, serverName), fileName) else File(platformDir, fileName)
    }
}

/** Arquivo de firmware (BIOS) guardado no servidor. */
data class RemoteFirmware(val id: Long, val fileName: String)

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
     * Não altera os prazos, cabeçalhos ou a estratégia de transferência.
     */
    private fun <T> withDownloadResponse(url: HttpUrl, description: String, consume: (Response) -> T): T {
        val startedAt = SystemClock.elapsedRealtime()
        val path = url.encodedPath + (url.encodedQuery?.let { "?$it" } ?: "")
        GameLog.log("romm", "download início: $description; caminho=$path")
        try {
            return longHttp.newCall(request(url).build()).execute().use { response ->
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
                    throw IOException("Servidor respondeu ${response.code} ao baixar $description")
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
        if (value <= 0L) total else if (Long.MAX_VALUE - total < value) Long.MAX_VALUE else total + value
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
        withDownloadResponse(url, description) { response ->
            if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
            val expectedBytes = response.body.contentLength()
            val downloadEstimate = expectedBytes.takeIf { it > 0L } ?: game.sizeBytes
            val extractionEstimate = if (game.fileId != null && downloadEstimate > 0L) downloadEstimate else 0L
            requireAvailableSpace(checkNotNull(target.parentFile), estimatedBytes(downloadEstimate, extractionEstimate), game.title)
            var total = 0L            response.body.byteStream().use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onProgress(total)
                    }
                }
            }
        }
        if (game.fileId != null && isZip(temp) && target.extension.lowercase() != "zip") unzipSingle(temp, target)
        target.delete()
        check(temp.renameTo(target)) { "Não consegui gravar ${target.name}" }
    }

    /** Baixa o ZIP completo de uma ROM multi-arquivo, valida e extrai o conteúdo e a M3U. */
    private fun downloadFolder(game: RemoteGame, target: File, onProgress: (Long) -> Unit) {
        val dir = checkNotNull(target.parentFile)
        val parent = checkNotNull(dir.parentFile)
        val temp = File(parent, "${dir.name}.part")
        val archive = File(parent, "${dir.name}.zip.part")
        temp.deleteRecursively()
        archive.delete()
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
            withDownloadResponse(url, description) { response ->
                if (!response.isSuccessful) throw IOException("Servidor respondeu ${response.code}")
                val expectedBytes = response.body.contentLength()
                val archiveEstimate = expectedBytes.takeIf { it > 0L } ?: game.sizeBytes
                val extractionEstimate = game.sizeBytes.takeIf { it > 0L } ?: archiveEstimate
                requireAvailableSpace(parent, estimatedBytes(archiveEstimate, extractionEstimate), game.title)
                var total = 0L
                response.body.byteStream().use { input ->
                    archive.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            total += read
                            onProgress(total)
                        }
                    }
                }
                check(expectedBytes < 0L || total == expectedBytes) {
                    "Download incompleto de ${game.title}: recebidos $total de $expectedBytes bytes"
                }
                GameLog.log(
                    "romm",
                    "pacote ZIP recebido: ${game.title}; bytes=$total; contentLength=$expectedBytes; " +
                        "tipo=${response.header("Content-Type") ?: "-"}",
                )
            }
            check(isZip(archive)) {
                "O RomM não entregou um ZIP válido para ${game.title} " +
                    "(assinatura inicial inválida; tamanho=${archive.length()} bytes)"
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

            // Só substitui uma pasta já existente depois de validar todo o pacote.
            dir.deleteRecursively()
            check(content.renameTo(dir)) { "Não consegui gravar ${dir.name}" }
            temp.deleteRecursively()
            GameLog.log("romm", "download multidisco concluído: ${game.title}; pasta=${dir.name}")
        } catch (error: Exception) {
            temp.deleteRecursively()
            throw error
        } finally {
            archive.delete()
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
                result += RemoteFirmware(item.getLong("id"), item.getString("file_name"))
            }
        }
        return result
    }

    fun downloadFirmware(firmware: RemoteFirmware, target: File) {
        val url = base.resolve("api/firmware/${firmware.id}/content/")!!
            .newBuilder()
            .addPathSegment(firmware.fileName)
            .build()
        downloadToFile(url, target, "firmware=${firmware.id}, arquivo=${firmware.fileName}")
    }

    private fun downloadToFile(url: HttpUrl, target: File, description: String) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        temp.delete()
        try {
            longHttp.newCall(request(url).build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw RomMHttpException(response.code, "Servidor respondeu ${response.code} ao baixar $description")
                }
                val expectedBytes = response.body.contentLength()
                requireAvailableSpace(checkNotNull(target.parentFile), expectedBytes, description)
                var total = 0L
                response.body.byteStream().use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            total += read
                        }
                    }
                }
                check(expectedBytes < 0L || total == expectedBytes) {
                    "Download incompleto de $description: recebidos $total de $expectedBytes bytes"
                }
            }
            target.delete()
            check(temp.renameTo(target)) { "Não consegui gravar ${target.name}" }
        } catch (error: Exception) {
            temp.delete()
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
        zip.delete()
        check(out.renameTo(zip)) { "Não consegui extrair ${target.name}" }
    }

    /** Baixa a capa para o cache e devolve o arquivo (ou nulo se falhar). */
    fun cover(context: Context, game: RemoteGame): File? {
        if (game.coverPath == null && game.coverUrl == null) return null
        val file = RomMCovers.coverFile(context, game.id).apply { parentFile?.mkdirs() }
        if (file.isFile && file.length() > 0) return file
        // Grava num arquivo temporário e só então renomeia: outra tela nunca lê uma capa pela metade.
        val temp = File(file.parentFile, "${file.name}.${Thread.currentThread().id}.part")
        return runCatching {
            if (listOfNotNull(game.coverPath, game.coverUrl).flatMap { coverUrls(it) }.any { downloadCover(it, temp) } && temp.renameTo(file)) file else null
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
