package app.lawnchair.gamemode

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/** Capacidades anunciadas pelo servidor WebDAV. */
data class WebDavCapabilities(
    val dav: String?,
    val allow: String?,
)

/**
 * Prova de conceito WebDAV inspirada no RetroArch Cloud Sync.
 *
 * OPTIONS, PROPFIND e GET são operações de leitura/descoberta. PUT só cria um destino novo
 * usando If-None-Match: *; se o destino já existir, o servidor deve recusar a escrita.
 * Não há método DELETE, MOVE ou sobrescrita de arquivos nesta PoC.
 */
class RetroArchWebDavClient(
    baseUrl: String,
    username: String? = null,
    password: String? = null,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').plus('/').toHttpUrl()
    private val authorization = when {
        username.isNullOrBlank() && password.isNullOrBlank() -> null
        username.isNullOrBlank() || password == null -> throw IllegalArgumentException("Credenciais WebDAV incompletas.")
        else -> Credentials.basic(username, password)
    }
    private val xmlType = "application/xml; charset=utf-8".toMediaType()
    private val binaryType = "application/octet-stream".toMediaType()

    fun options(): WebDavCapabilities {
        val request = request("").method("OPTIONS", null).build()
        http.newCall(request).execute().use { response ->
            ensureSuccess(response.code, "OPTIONS")
            return WebDavCapabilities(
                dav = response.header("DAV"),
                allow = response.header("Allow"),
            )
        }
    }

    /** Retorna o XML de propriedades; a resposta 207 Multi-Status é esperada em WebDAV. */
    fun propfind(path: String = "", depth: Int = 1): String {
        require(depth in 0..1) { "A profundidade PROPFIND deve ser 0 ou 1 nesta PoC." }
        val body = """<?xml version="1.0" encoding="utf-8" ?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getetag/><d:getlastmodified/><d:getcontentlength/></d:prop></d:propfind>"""
            .toRequestBody(xmlType)
        val request = request(path)
            .header("Depth", depth.toString())
            .method("PROPFIND", body)
            .build()
        http.newCall(request).execute().use { response ->
            ensureSuccess(response.code, "PROPFIND")
            return response.body?.string().orEmpty()
        }
    }

    /** Baixa para staging; não promove o arquivo nem altera o destino final. */
    fun downloadToStaging(
        path: String,
        stagingFile: File,
        expectedSha256: String? = null,
    ): String {
        if (stagingFile.exists()) throw IOException("O arquivo de staging já existe; ele não será sobrescrito.")
        stagingFile.parentFile?.let {
            if (!it.exists() && !it.mkdirs()) throw IOException("Não foi possível preparar o staging WebDAV.")
        }
        try {
            http.newCall(request(path).get().build()).execute().use { response ->
                ensureSuccess(response.code, "GET")
                val body = response.body ?: throw IOException("O servidor WebDAV retornou uma resposta sem conteúdo.")
                stagingFile.outputStream().use { output ->
                    body.byteStream().use { input -> input.copyTo(output) }
                }
            }
            val actualHash = sha256(stagingFile)
            if (expectedSha256 != null && !actualHash.equals(expectedSha256, ignoreCase = true)) {
                throw IOException("O hash do arquivo WebDAV não corresponde ao esperado.")
            }
            return actualHash
        } catch (error: Exception) {
            stagingFile.delete()
            throw error
        }
    }

    /** Cria um arquivo novo. If-None-Match impede sobrescrever um recurso existente. */
    fun uploadNewFile(path: String, source: File) {
        require(source.isFile && source.length() > 0L) { "O arquivo local está ausente ou vazio." }
        val request = request(path)
            .header("If-None-Match", "*")
            .put(source.asRequestBody(binaryType))
            .build()
        http.newCall(request).execute().use { response ->
            ensureSuccess(response.code, "PUT")
        }
    }

    private fun request(relativePath: String): Request.Builder {
        val url = resolve(relativePath)
        return Request.Builder()
            .url(url)
            .header("Accept", "*/*")
            .apply { authorization?.let { header("Authorization", it) } }
    }

    private fun resolve(relativePath: String): HttpUrl {
        val clean = relativePath.replace('\\\\', '/')
        require(!clean.startsWith('/')) { "O caminho WebDAV deve ser relativo à raiz configurada." }
        require(!clean.contains('?') && !clean.contains('#')) { "O caminho WebDAV não pode conter query ou fragmento." }
        require(!Regex("(?i)%2e|%2f|%5c").containsMatchIn(clean)) { "O caminho WebDAV contém codificação não permitida." }
        val normalized = clean.trimEnd('/')
        if (normalized.isNotEmpty()) {
            require(normalized.split('/').none { it == "." || it == ".." || it.isBlank() }) {
                "O caminho WebDAV não pode sair da pasta configurada."
            }
        }
        val url = base.resolve(clean) ?: throw IOException("Caminho WebDAV inválido.")
        require(url.scheme == base.scheme && url.host == base.host && url.port == base.port) {
            "O caminho WebDAV não pode mudar de servidor."
        }
        return url
    }

    private fun ensureSuccess(code: Int, method: String) {
        if (code !in 200..299) {
            throw IOException("WebDAV $method respondeu HTTP $code.")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
