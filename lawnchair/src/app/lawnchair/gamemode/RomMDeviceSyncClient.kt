package app.lawnchair.gamemode

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
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
    ): JSONObject {
        require(deviceId.isNotBlank())
        require(romIds.isNotEmpty() && romIds.all { it > 0L })
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
        return JSONObject()
            .put("device_id", deviceId)
            .put("saves", saveArray)
            .put("rom_ids", romArray)
            .put("restore_unlisted", false)
            .put("emulators", JSONObject.NULL)
    }

    fun parseRegistration(json: JSONObject): RegisteredSyncDevice {
        val id = json.optString("device_id")
        require(id.isNotBlank() && id != "null") { "O RomM não retornou device_id." }
        return RegisteredSyncDevice(id, json.optString("name").ifBlank { "Tandem" })
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
                throw RomMHttpException(response.code, "RomM Device Sync respondeu HTTP ${response.code}")
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
    ): DeviceSyncNegotiation {
        val body = DeviceSyncProtocolJson.negotiation(deviceId, saves, romIds)
            .toString()
            .toRequestBody(jsonType)
        val json = executeJson(request("api/sync/negotiate").post(body).build())
        return DeviceSyncProtocolJson.parseNegotiation(json)
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
}
