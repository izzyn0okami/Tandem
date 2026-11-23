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
            server.enqueue(MockResponse(body = """{"device_id":"device-123","name":"Tandem"}"""))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token", OkHttpClient())
            val registered = client.registerDevice(
                DeviceSyncRegistration("Tandem Android", clientVersion = "1.0", hostname = "tandem-test"),
            )
            val request = server.takeRequest()

            assertEquals("device-123", registered.deviceId)
            assertEquals("POST", request.method)
            assertEquals("/api/devices", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers["Authorization"])
            assertTrue(request.body!!.utf8().contains("\"sync_mode\":\"api\""))
        } finally {
            server.close()
        }
    }

    @Test
    fun negotiateSendsExplicitRomScopeAndDoesNotRestoreUnlistedGames() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(body = """{"session_id":8,"operations":[]}"""))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            val result = client.negotiate("device-123", emptyList(), setOf(42L))
            val request = server.takeRequest()
            val payload = JSONObject(request.body!!.utf8())

            assertEquals(8L, result.sessionId)
            assertEquals("/api/sync/negotiate", request.url.encodedPath)
            assertEquals("POST", request.method)
            assertEquals(42L, payload.getJSONArray("rom_ids").getLong(0))
            assertFalse(payload.getBoolean("restore_unlisted"))
        } finally {
            server.close()
        }
    }

    @Test
    fun completeSendsOperationCountsAndHttpErrorsAreSurfaced() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(body = "{}"))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            client.complete(8L, operationsCompleted = 2, operationsFailed = 1)
            val request = server.takeRequest()
            val payload = JSONObject(request.body!!.utf8())
            assertEquals("/api/sync/sessions/8/complete", request.url.encodedPath)
            assertEquals(2, payload.getInt("operations_completed"))
            assertEquals(1, payload.getInt("operations_failed"))

            server.enqueue(MockResponse(code = 403, body = """{"detail":"forbidden"}"""))
            val error = runCatching { client.registerDevice(DeviceSyncRegistration("Tandem", clientVersion = "1.0", hostname = "tandem")) }.exceptionOrNull()
            assertTrue(error is RomMHttpException)
            assertEquals(403, (error as RomMHttpException).statusCode)
        } finally {
            server.close()
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
    @Test
    fun uploadSaveUsesSafeQueryParametersAndMultipartSaveFile() {
        val server = MockWebServer()
        val file = java.io.File.createTempFile("device-sync-", ".srm")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        server.start()
        try {
            server.enqueue(MockResponse(body = """{"id":91}"""))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            client.uploadSave(42L, "device-123", 8L, "autosave", file)
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals("/api/saves", request.url.encodedPath)
            assertEquals("42", request.url.queryParameter("rom_id"))
            assertEquals("device-123", request.url.queryParameter("device_id"))
            assertEquals("8", request.url.queryParameter("session_id"))
            assertEquals("autosave", request.url.queryParameter("slot"))
            assertEquals("false", request.url.queryParameter("overwrite"))
            assertEquals("false", request.url.queryParameter("autocleanup"))
            assertEquals("Bearer test-token", request.headers["Authorization"])
            assertTrue(request.body!!.utf8().contains("name=\"saveFile\""))
            assertTrue(request.body!!.utf8().contains("device-sync-"))
        } finally {
            server.close()
            file.delete()
        }
    }

    @Test
    fun uploadSaveSurfacesConflictWithoutRetryingAsOverwrite() {
        val server = MockWebServer()
        val file = java.io.File.createTempFile("device-sync-conflict-", ".srm")
        file.writeBytes(byteArrayOf(9, 8, 7))
        server.start()
        try {
            server.enqueue(MockResponse(code = 409, body = """{"detail":"conflict"}"""))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            val error = runCatching { client.uploadSave(42L, "device-123", 8L, "autosave", file) }.exceptionOrNull()
            val request = server.takeRequest()

            assertTrue(error is RomMHttpException)
            assertEquals(409, (error as RomMHttpException).statusCode)
            assertEquals("false", request.url.queryParameter("overwrite"))
        } finally {
            server.close()
            file.delete()
        }
    }

    @Test
    fun downloadSaveStagesAndVerifiesHashWithoutConfirmingIt() {
        val server = MockWebServer()
        val staging = java.io.File.createTempFile("device-sync-download-", ".part")
        val payload = "sram-content"
        val hash = java.security.MessageDigest.getInstance("MD5")
            .digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        server.start()
        try {
            server.enqueue(MockResponse(body = payload))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            val actual = client.downloadSaveToStaging(91L, "device-123", 8L, hash, staging)
            val request = server.takeRequest()

            assertEquals(hash, actual)
            assertEquals(payload, staging.readText())
            assertEquals("/api/saves/91/content", request.url.encodedPath)
            assertEquals("false", request.url.queryParameter("optimistic"))
            assertEquals("device-123", request.url.queryParameter("device_id"))
            assertEquals("8", request.url.queryParameter("session_id"))
            assertEquals("GET", request.method)
            assertTrue(server.requestCount == 1)
        } finally {
            server.close()
            staging.delete()
        }
    }

    @Test
    fun downloadSaveRejectsWrongHashAndRemovesStagingFile() {
        val server = MockWebServer()
        val staging = java.io.File.createTempFile("device-sync-invalid-", ".part")
        server.start()
        try {
            server.enqueue(MockResponse(body = "wrong-content"))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            val result = runCatching {
                client.downloadSaveToStaging(
                    91L,
                    "device-123",
                    8L,
                    "00000000000000000000000000000000",
                    staging,
                )
            }

            assertTrue(result.isFailure)
            assertFalse(staging.exists())
        } finally {
            server.close()
            staging.delete()
        }
    }

    @Test
    fun downloadedSaveIsConfirmedOnlyThroughSeparateEndpoint() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(body = "{}"))
            val client = RomMDeviceSyncClient(server.url("/").toString(), "test-token")
            client.confirmDownloaded(91L, "device-123", "0123456789abcdef0123456789abcdef")
            val request = server.takeRequest()
            val body = JSONObject(request.body!!.utf8())

            assertEquals("POST", request.method)
            assertEquals("/api/saves/91/downloaded", request.url.encodedPath)
            assertEquals("device-123", body.getString("device_id"))
            assertEquals("0123456789abcdef0123456789abcdef", body.getString("content_hash"))
        } finally {
            server.close()
        }
    }

}
