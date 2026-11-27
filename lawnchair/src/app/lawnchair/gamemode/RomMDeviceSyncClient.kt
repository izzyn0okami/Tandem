package app.lawnchair.gamemode

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** Dados de um arquivo local enviado para a negociação oficial de sincronização do RomM. */
data class DeviceSyncSave(
    val romId: Long,
    val fileName: String,
    val slot: String?,
    val emulator: String?,
    val contentHashMd5: String,
    val updatedAt: String,
    val fileSizeBytes: Long,
) {
    init {
        require(romId > 0L)
        require(fileName.isNotBlank())
        require(contentHashMd5.matches(Regex("[a-fA-F0-9]{32}"))) {
            "O Device Sync Protocol exige hash MD5 hexadecimal."
        }
        require(fileSizeBytes >= 0L)
    }
}

/** Registro explícito do dispositivo Tandem. O hostname deve ser estável e gerado pelo app. */
data class DeviceSyncRegistration(
    val name: String,
    val platform: String = "Android",
    val client: String = "tandem",
    val clientVersion: String,
    val hostname: String,
) {
    init {
        require(name.isNotBlank())
        require(platform.isNotBlank())
        require(client.isNotBlank())
        require(clientVersion.isNotBlank())
        require(hostname.isNotBlank())
    }
}

data class RegisteredSyncDevice(
    val deviceId: String,
    val name: String,
)

enum class DeviceSyncAction {
    Upload,
    Download,
    Conflict,
    Delete,
    NoOp,
    Unknown,
}

data class DeviceSyncOperation(
    val action: DeviceSyncAction,
    val romId: Long,
    val saveId: Long?,
    val fileName: String?,
    val slot: String?,
    val emulator: String?,
    val reason: String?,
    val serverUpdatedAt: String?,
    val serverContentHash: String?,
)

data class DeviceSyncNegotiation(
    val sessionId: Long,
    val operations: List<DeviceSyncOperation>,
)

object DeviceSyncProtocolJson {
    /**
     * Constrói a entrada de negociação a partir de um arquivo de save local explicitamente suportado.
     * A extensão é preservada (.srm, .dsv); para PSX, o conteúdo pode ser cartão de memória
     * transportado como Save comum do RomM. O filtro de emuladores é opcional: null permite ao cliente
     * detectar saves de outros núcleos no mesmo slot e validar a compatibilidade localmente.
     */
    fun localSave(
        romId: Long,
        fileName: String,
        emulator: String,
        file: File,
        updatedAt: String,
        remoteSlot: String = "autosave",
        expectedExtension: String = "srm",
    ): DeviceSyncSave? {
        require(romId > 0L)
        require(expectedExtension.lowercase() in setOf("srm", "dsv"))
        require(fileName.endsWith(".$expectedExtension", ignoreCase = true)) {
            "A extensão do arquivo não corresponde ao formato de save declarado pelo núcleo."
        }
        require(emulator.isNotBlank())
        require(updatedAt.isNotBlank())
        require(remoteSlot.isNotBlank())
        if (!file.isFile || file.length() <= 0L) return null
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val md5 = digest.digest().joinToString("") { "%02x".format(it) }
        return DeviceSyncSave(
            romId = romId,
            fileName = fileName,
            slot = remoteSlot,
            emulator = emulator,
            contentHashMd5 = md5,
            updatedAt = updatedAt,
            fileSizeBytes = file.length(),
        )
    }

    private fun nullable(value: String?): Any = value ?: JSONObject.NULL

    fun registration(registration: DeviceSyncRegistration): JSONObject = JSONObject()
        .put("name", registration.name)
        .put("platform", registration.platform)
        .put("client", registration.client)
        .put("client_version", registration.clientVersion)
        .put("hostname", registration.hostname)
        .put("sync_mode", "api")

    fun negotiation(
        deviceId: String,
        saves: List<DeviceSyncSave>,
        romIds: Set<Long>,
        emulators: Set<String>? = null,
    ): JSONObject {
        require(deviceId.isNotBlank())
        require(romIds.isNotEmpty() && romIds.all { it > 0L })
        require(emulators == null || (emulators.isNotEmpty() && emulators.all { it.isNotBlank() }))
        require(saves.all { it.romId in romIds }) {
            "A negociação não pode incluir saves fora da lista explícita de jogos."
        }
        val saveArray = JSONArray()
        saves.forEach { save ->
            saveArray.put(
                JSONObject()
                    .put("rom_id", save.romId)
                    .put("file_name", save.fileName)
                    .put("slot", nullable(save.slot))
                    .put("emulator", nullable(save.emulator))
                    .put("content_hash", save.contentHashMd5.lowercase())
                    .put("updated_at", save.updatedAt)
                    .put("file_size_bytes", save.fileSizeBytes),
            )
        }
        val romArray = JSONArray()
        romIds.sorted().forEach(romArray::put)
        val payload = JSONObject()
            .put("device_id", deviceId)
            .put("saves", saveArray)
            .put("rom_ids", romArray)
            .put("restore_unlisted", false)
        if (emulators != null) {
            val emulatorArray = JSONArray()
            emulators.sorted().forEach(emulatorArray::put)
            payload.put("emulators", emulatorArray)
        }
        return payload
    }

    fun parseRegistration(json: JSONObject): RegisteredSyncDevice {
        val id = json.optString("device_id")
        require(id.isNotBlank() && id != "null") { "O RomM não retornou device_id." }
        return RegisteredSyncDevice(id, json.optString("name").ifBlank { "Tandem" })
    }

    fun validateDownloadOperation(
        operation: DeviceSyncOperation,
        expectedEmulator: String,
        expectedSlot: String = "autosave",
        expectedExtension: String = "srm",
    ) = validateDownloadOperation(operation, setOf(expectedEmulator), expectedSlot, expectedExtension)

    fun validateDownloadOperation(
        operation: DeviceSyncOperation,
        compatibleEmulators: Set<String>,
        expectedSlot: String = "autosave",
        expectedExtension: String = "srm",
    ) {
        if (operation.action != DeviceSyncAction.Download) {
            throw IOException("A operação recebida não é um download.")
        }
        if (operation.slot != expectedSlot) {
            throw IOException("Download fora do slot $expectedSlot foi preservado sem alteração.")
        }
        if (operation.fileName?.substringAfterLast('.', "")?.equals(expectedExtension, ignoreCase = true) != true) {
            throw IOException("O formato do save remoto não corresponde ao formato nativo esperado ($expectedExtension); download recusado.")
        }
        if (operation.emulator == null || compatibleEmulators.none { it.equals(operation.emulator, ignoreCase = true) }) {
            throw IOException("O save remoto pertence a um núcleo sem compatibilidade declarada; download recusado.")
        }
    }

    fun parseNegotiation(json: JSONObject): DeviceSyncNegotiation {
        val sessionId = json.optLong("session_id", -1L)
        require(sessionId > 0L) { "O RomM não retornou um session_id válido." }
        val operations = json.optJSONArray("operations") ?: JSONArray()
        return DeviceSyncNegotiation(
            sessionId,
            (0 until operations.length()).map { index ->
                val item = operations.getJSONObject(index)
                val action = when (item.optString("action")) {
                    "upload" -> DeviceSyncAction.Upload
                    "download" -> DeviceSyncAction.Download
                    "conflict" -> DeviceSyncAction.Conflict
                    "delete" -> DeviceSyncAction.Delete
                    "no_op" -> DeviceSyncAction.NoOp
                    else -> DeviceSyncAction.Unknown
                }
                DeviceSyncOperation(
                    action = action,
                    romId = item.optLong("rom_id", -1L),
                    saveId = item.optLong("save_id", -1L).takeIf { it > 0L },
                    fileName = item.optString("file_name").ifBlank { null },
                    slot = item.optString("slot").ifBlank { null },
                    emulator = item.optString("emulator").ifBlank { null },
                    reason = item.optString("reason").ifBlank { null },
                    serverUpdatedAt = item.optString("server_updated_at").ifBlank { null },
                    serverContentHash = item.optString("server_content_hash").ifBlank { null },
                )
            },
        )
    }
}

/**
 * Adaptador isolado do Device Sync Protocol RomM 5.4.
 * Não executa operações retornadas pela negociação; em particular, Delete nunca é aplicado aqui.
 * Chamadas são bloqueantes e devem ocorrer em Dispatchers.IO.
 */
class RomMDeviceSyncClient(
    baseUrl: String,
    private val token: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build(),
) {
    private val base: HttpUrl = baseUrl.trimEnd('/').plus("/").toHttpUrl()
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    init {
        require(token.isNotBlank())
    }

    private fun executeJson(request: Request): JSONObject {
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string().orEmpty()
                throw RomMHttpException(
                    response.code,
                    "RomM Device Sync respondeu HTTP ${response.code}",
                    romMErrorDetail(errorBody),
                    response.request.url.encodedPath,
                )
            }
            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return JSONObject()
            return JSONObject(body)
        }
    }

    private fun request(path: String): Request.Builder {
        val url = base.resolve(path) ?: throw IOException("Endereço do endpoint RomM inválido")
        return Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
    }

    fun registerDevice(registration: DeviceSyncRegistration): RegisteredSyncDevice {
        val body = DeviceSyncProtocolJson.registration(registration)
            .toString()
            .toRequestBody(jsonType)
        val json = executeJson(request("api/devices").post(body).build())
        return DeviceSyncProtocolJson.parseRegistration(json)
    }

    fun negotiate(
        deviceId: String,
        saves: List<DeviceSyncSave>,
        romIds: Set<Long>,
        emulators: Set<String>? = null,
    ): DeviceSyncNegotiation {
        val body = DeviceSyncProtocolJson.negotiation(deviceId, saves, romIds, emulators)
            .toString()
            .toRequestBody(jsonType)
        val json = executeJson(request("api/sync/negotiate").post(body).build())
        return DeviceSyncProtocolJson.parseNegotiation(json)
    }

    /**
     * Executa apenas a negociação para diagnóstico e encerra a sessão sem transferir arquivos.
     * A contagem considera somente a ROM e, quando informado, o slot administrado pelo adaptador.
     */
    fun inspectNegotiation(
        deviceId: String,
        saves: List<DeviceSyncSave>,
        romIds: Set<Long>,
        emulators: Set<String>? = null,
        managedSlot: String? = null,
    ): DeviceSyncNegotiation {
        val negotiation = negotiate(deviceId, saves, romIds, emulators)
        val scoped = negotiation.operations.filter {
            it.romId in romIds && (managedSlot == null || it.slot == managedSlot)
        }
        val completed = scoped.count { it.action == DeviceSyncAction.NoOp }
        val failed = scoped.size - completed
        complete(negotiation.sessionId, completed, failed)
        return negotiation
    }

    fun complete(sessionId: Long, operationsCompleted: Int, operationsFailed: Int) {
        require(sessionId > 0L)
        require(operationsCompleted >= 0 && operationsFailed >= 0)
        val body = JSONObject()
            .put("operations_completed", operationsCompleted)
            .put("operations_failed", operationsFailed)
            .toString()
            .toRequestBody(jsonType)
        executeJson(request("api/sync/sessions/$sessionId/complete").post(body).build())
    }

    /**
     * Envia um arquivo de save nativo como Save comum do Device Sync. Nunca sobrescreve e desativa
     * a limpeza automática do servidor para preservar versões anteriores.
     */
    fun uploadSave(
        romId: Long,
        deviceId: String,
        sessionId: Long,
        slot: String?,
        emulator: String,
        fileName: String,
        file: File,
        expectedExtension: String = "srm",
    ): JSONObject {
        require(romId > 0L && sessionId > 0L)
        require(deviceId.isNotBlank() && emulator.isNotBlank())
        require(slot == null || slot.isNotBlank())
        require(expectedExtension.lowercase() in setOf("srm", "dsv"))
        require(fileName.endsWith(".$expectedExtension", ignoreCase = true)) {
            "A extensão do arquivo não corresponde ao formato de save declarado pelo núcleo."
        }
        require(file.isFile && file.length() > 0L) { "O save local está ausente ou vazio." }
        val contentHash = md5(file)
        val urlBuilder = base.resolve("api/saves")!!.newBuilder()
            .addQueryParameter("rom_id", romId.toString())
            .addQueryParameter("emulator", emulator)
            .addQueryParameter("device_id", deviceId)
            .addQueryParameter("session_id", sessionId.toString())
            .addQueryParameter("content_hash", contentHash)
            .addQueryParameter("overwrite", "false")
            .addQueryParameter("autocleanup", "false")
        // A null slot is an archival save: RomM never pairs it with the active slot.
        slot?.let { urlBuilder.addQueryParameter("slot", it) }
        val url = urlBuilder.build()
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "saveFile",
                fileName,
                file.asRequestBody("application/octet-stream".toMediaType()),
            )
            .build()
        return executeJson(request(url.encodedPath).url(url).post(body).build())
    }

    /**
     * Baixa para um arquivo de staging. Não promove o arquivo nem confirma o download:
     * o chamador deve aplicar o save com segurança e só então chamar confirmDownloaded.
     */
    fun downloadSaveToStaging(
        saveId: Long,
        deviceId: String,
        sessionId: Long,
        expectedContentHash: String,
        stagingFile: File,
    ): String {
        require(saveId > 0L && sessionId > 0L && deviceId.isNotBlank())
        require(expectedContentHash.matches(Regex("[a-fA-F0-9]{32}"))) {
            "O hash MD5 esperado do servidor é inválido."
        }
        val url = base.resolve("api/saves/$saveId/content")!!.newBuilder()
            .addQueryParameter("device_id", deviceId)
            .addQueryParameter("session_id", sessionId.toString())
            .addQueryParameter("optimistic", "false")
            .build()
        stagingFile.parentFile?.mkdirs()
        try {
            http.newCall(request(url.encodedPath).url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()
                    throw RomMHttpException(
                        response.code,
                        "RomM Device Sync respondeu HTTP ${response.code}",
                        romMErrorDetail(errorBody),
                        response.request.url.encodedPath,
                    )
                }
                val body = response.body ?: throw IOException("O RomM retornou um save sem conteúdo.")
                stagingFile.outputStream().use { output -> body.byteStream().use { input -> input.copyTo(output) } }
            }
            val actualHash = md5(stagingFile)
            if (!actualHash.equals(expectedContentHash, ignoreCase = true)) {
                throw IOException("O hash MD5 do save baixado não corresponde ao informado pelo RomM.")
            }
            return actualHash
        } catch (error: Exception) {
            stagingFile.delete()
            throw error
        }
    }

    /** Confirma ao RomM somente depois que o chamador aplicou o arquivo baixado com sucesso. */
    fun confirmDownloaded(saveId: Long, deviceId: String, contentHashMd5: String) {
        require(saveId > 0L && deviceId.isNotBlank())
        require(contentHashMd5.matches(Regex("[a-fA-F0-9]{32}")))
        val body = JSONObject()
            .put("device_id", deviceId)
            .put("content_hash", contentHashMd5.lowercase())
            .toString()
            .toRequestBody(jsonType)
        executeJson(request("api/saves/$saveId/downloaded").post(body).build())
    }

    private fun md5(file: File): String {
        val digest = MessageDigest.getInstance("MD5")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
