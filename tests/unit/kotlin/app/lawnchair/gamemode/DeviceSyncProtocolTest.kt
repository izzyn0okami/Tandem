package app.lawnchair.gamemode

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSyncProtocolTest {
    @Test
    fun registrationUsesStableHostnameAndApiSyncModeWithoutAssumingMacAddress() {
        val json = DeviceSyncProtocolJson.registration(
            DeviceSyncRegistration(
                name = "Tandem Android",
                clientVersion = "1.0",
                hostname = "tandem-device-stable-id",
            ),
        )

        assertEquals("tandem-device-stable-id", json.getString("hostname"))
        assertEquals("api", json.getString("sync_mode"))
        assertFalse(json.has("mac_address"))
    }

    @Test
    fun negotiationScopesRequestToExplicitRomsAndDisablesRestoreUnlisted() {
        val json = DeviceSyncProtocolJson.negotiation(
            deviceId = "device-uuid",
            saves = listOf(
                DeviceSyncSave(
                    romId = 42,
                    fileName = "game.srm",
                    slot = "autosave",
                    emulator = "tandem_mgba",
                    contentHashMd5 = "0123456789abcdef0123456789abcdef",
                    updatedAt = "2026-11-23T12:00:00Z",
                    fileSizeBytes = 128,
                ),
            ),
            romIds = setOf(42),
        )

        assertEquals("device-uuid", json.getString("device_id"))
        assertEquals(42, json.getJSONArray("rom_ids").getLong(0))
        assertFalse(json.getBoolean("restore_unlisted"))
        assertEquals(1, json.getJSONArray("saves").length())
        assertEquals("0123456789abcdef0123456789abcdef", json.getJSONArray("saves").getJSONObject(0).getString("content_hash"))
    }

    @Test
    fun negotiationRejectsSavesOutsideExplicitRomScope() {
        val save = DeviceSyncSave(
            romId = 99,
            fileName = "other.srm",
            slot = null,
            emulator = null,
            contentHashMd5 = "0123456789abcdef0123456789abcdef",
            updatedAt = "2026-11-23T12:00:00Z",
            fileSizeBytes = 1,
        )

        assertTrue(
            runCatching {
                DeviceSyncProtocolJson.negotiation("device-uuid", listOf(save), setOf(42))
            }.isFailure,
        )
    }

    @Test
    fun negotiationParsesDeleteAsAnUnappliedOperation() {
        val parsed = DeviceSyncProtocolJson.parseNegotiation(
            JSONObject(
                """{
                    "session_id": 7,
                    "operations": [
                        {"action":"delete","rom_id":42,"slot":"autosave"},
                        {"action":"conflict","rom_id":42,"save_id":12,"slot":"manual"}
                    ]
                }""",
            ),
        )

        assertEquals(7L, parsed.sessionId)
        assertEquals(DeviceSyncAction.Delete, parsed.operations[0].action)
        assertEquals(DeviceSyncAction.Conflict, parsed.operations[1].action)
        assertNull(parsed.operations[0].saveId)
        assertFalse(parsed.operations.any { it.action == DeviceSyncAction.Unknown })
    }

    @Test
    fun registerDeviceSendsAuthenticatedRequestToOfficialEndpoint() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"device_id":"device-123","name":"Tandem"}"""))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token", OkHttpClient())
            val registered = client.registerDevice(
                DeviceSyncRegistration("Tandem Android", clientVersion = "1.0", hostname = "tandem-test"),
            )
            val request = server.takeRequest()

            assertEquals("device-123", registered.deviceId)
            assertEquals("POST", request.method)
            assertEquals("/api/devices", request.path)
            assertEquals("Bearer test-token", request.getHeader("Authorization"))
            assertTrue(request.body.readUtf8().contains("\"sync_mode\":\"api\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun negotiateSendsExplicitRomScopeAndDoesNotRestoreUnlistedGames() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"session_id":8,"operations":[]}"""))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            val result = client.negotiate("device-123", emptyList(), setOf(42L))
            val request = server.takeRequest()
            val payload = JSONObject(request.body.readUtf8())

            assertEquals(8L, result.sessionId)
            assertEquals("/api/sync/negotiate", request.path)
            assertEquals("POST", request.method)
            assertEquals(42L, payload.getJSONArray("rom_ids").getLong(0))
            assertFalse(payload.getBoolean("restore_unlisted"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun completeSendsOperationCountsAndHttpErrorsAreSurfaced() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("{}"))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            client.complete(8L, operationsCompleted = 2, operationsFailed = 1)
            val request = server.takeRequest()
            val payload = JSONObject(request.body.readUtf8())
            assertEquals("/api/sync/sessions/8/complete", request.path)
            assertEquals(2, payload.getInt("operations_completed"))
            assertEquals(1, payload.getInt("operations_failed"))

            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"detail":"forbidden"}"""))
            val error = runCatching { client.registerDevice(DeviceSyncRegistration("Tandem", clientVersion = "1.0", hostname = "tandem")) }.exceptionOrNull()
            assertTrue(error is RomMHttpException)
            assertEquals(403, (error as RomMHttpException).statusCode)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun negotiationRejectsInvalidMd5AndMissingSessionId() {
        assertTrue(
            runCatching {
                DeviceSyncSave(42, "game.srm", "autosave", null, "not-md5", "2026-11-23T12:00:00Z", 12)
            }.isFailure,
        )
        assertTrue(
            runCatching {
                DeviceSyncProtocolJson.parseNegotiation(JSONObject("""{"operations":[]}"""))
            }.isFailure,
        )
    }
}
