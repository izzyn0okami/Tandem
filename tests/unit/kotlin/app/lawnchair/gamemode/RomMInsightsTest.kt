package app.lawnchair.gamemode

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomMInsightsTest {
    @Test
    fun loadsOfficialServerStatsWithVisibilityBreakdownEnabled() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse(
                    body = """{"PLATFORMS":8,"ROMS":1200,"SAVES":31,"STATES":14,"SCREENSHOTS":90,"TOTAL_FILESIZE_BYTES":987654321}""",
                ),
            )
            val client = RomMClient(server.url("/").toString(), "test-token")

            val stats = client.getServerStats()
            val request = server.takeRequest()

            assertEquals(8L, stats.platforms)
            assertEquals(1200L, stats.roms)
            assertEquals(31L, stats.saves)
            assertEquals(14L, stats.states)
            assertEquals(90L, stats.screenshots)
            assertEquals(987654321L, stats.totalFileSizeBytes)
            assertEquals("GET", request.method)
            assertEquals("/api/stats", request.url.encodedPath)
            assertEquals("true", request.url.queryParameter("include_platform_stats"))
            assertEquals("Bearer test-token", request.headers["Authorization"])
        } finally {
            server.close()
        }
    }

    @Test
    fun loadsPersonalizedRecommendationsAndTheirReasons() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse(
                    body = """[{"rom":{"id":42,"name":"Metroid Prime","platform_display_name":"GameCube","fs_name_no_ext":"Metroid Prime"},"score":0.91,"reasons":[{"facet":"franchise","value":"Metroid"},{"facet":"genre","value":"Action"}],"seed_rom_id":7,"seed_rom_name":"Metroid Fusion"}]""",
                ),
            )
            val client = RomMClient(server.url("/").toString(), "test-token")

            val recommendations = client.getRecommendations()
            val request = server.takeRequest()

            assertEquals(1, recommendations.size)
            assertEquals(42L, recommendations.single().romId)
            assertEquals("Metroid Prime", recommendations.single().title)
            assertEquals("GameCube", recommendations.single().platform)
            assertEquals(0.91, recommendations.single().score, 0.001)
            assertEquals(listOf("franchise: Metroid", "genre: Action"), recommendations.single().reasons)
            assertEquals("Metroid Fusion", recommendations.single().seedRomName)
            assertEquals("GET", request.method)
            assertEquals("/api/recommendations", request.url.encodedPath)
            assertEquals("20", request.url.queryParameter("limit"))
            assertEquals("Bearer test-token", request.headers["Authorization"])
        } finally {
            server.close()
        }
    }

    @Test
    fun loadsNotificationsWithoutMutatingReadState() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse(
                    body = """[{"id":9,"kind":"sync_done","level":"success","title":"Sincronização concluída","body":"Save enviado","created_at":"2026-11-25T12:30:00Z","read_at":null,"actor":{"username":"admin"}}]""",
                ),
            )
            val client = RomMClient(server.url("/").toString(), "test-token")

            val notifications = client.getNotifications()
            val request = server.takeRequest()

            assertEquals(1, notifications.size)
            assertEquals(9L, notifications.single().id)
            assertEquals("sync_done", notifications.single().kind)
            assertEquals("Sincronização concluída", notifications.single().title)
            assertEquals("Save enviado", notifications.single().body)
            assertFalse(notifications.single().read)
            assertEquals("admin", notifications.single().actor)
            assertTrue(notifications.single().createdAtMs > 0L)
            assertEquals("GET", request.method)
            assertEquals("/api/notifications", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers["Authorization"])
        } finally {
            server.close()
        }
    }

    @Test
    fun emptyRecommendationAndNotificationFeedsAreValidResults() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(body = "[]"))
            server.enqueue(MockResponse(body = "[]"))
            val client = RomMClient(server.url("/").toString(), "test-token")

            assertTrue(client.getRecommendations().isEmpty())
            assertTrue(client.getNotifications().isEmpty())
            assertEquals("/api/recommendations", server.takeRequest().url.encodedPath)
            assertEquals("/api/notifications", server.takeRequest().url.encodedPath)
        } finally {
            server.close()
        }
    }

    @Test
    fun recommendationLimitIsBoundedToRomMApiContract() {
        val server = MockWebServer()
        server.start()
        try {
            val client = RomMClient(server.url("/").toString(), "test-token")

            assertTrue(runCatching { client.getRecommendations(0) }.isFailure)
            assertTrue(runCatching { client.getRecommendations(51) }.isFailure)
            assertEquals(0, server.requestCount)
        } finally {
            server.close()
        }
    }
    @Test
    fun marksSelectedNotificationsReadWithOnlyRequestedIds() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(body = "{}"))
            val client = RomMClient(server.url("/").toString(), "test-token")

            client.markNotificationsRead(listOf(9L, 12L))
            val request = server.takeRequest()

            assertEquals("POST", request.method)
            assertEquals("/api/notifications/read", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers["Authorization"])
            assertEquals("[9,12]", org.json.JSONObject(requireNotNull(request.body).utf8()).getJSONArray("ids").toString())
        } finally {
            server.close()
        }
    }

    @Test
    fun marksAllNotificationsReadWithoutDeletingThem() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(body = "{}"))
            val client = RomMClient(server.url("/").toString(), "test-token")

            client.markAllNotificationsRead()
            val request = server.takeRequest()
            val payload = org.json.JSONObject(requireNotNull(request.body).utf8())

            assertEquals("POST", request.method)
            assertEquals("/api/notifications/read", request.url.encodedPath)
            assertTrue(payload.get("ids") === org.json.JSONObject.NULL)
        } finally {
            server.close()
        }
    }

    @Test
    fun dismissesOnlyTheRequestedNotification() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(body = "{}"))
            val client = RomMClient(server.url("/").toString(), "test-token")

            client.dismissNotification(27L)
            val request = server.takeRequest()

            assertEquals("DELETE", request.method)
            assertEquals("/api/notifications/27", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers["Authorization"])
        } finally {
            server.close()
        }
    }

    @Test
    fun rejectsInvalidNotificationIdsBeforeSendingRequests() {
        val server = MockWebServer()
        server.start()
        try {
            val client = RomMClient(server.url("/").toString(), "test-token")

            assertTrue(runCatching { client.markNotificationsRead(emptyList()) }.isFailure)
            assertTrue(runCatching { client.markNotificationsRead(listOf(0L)) }.isFailure)
            assertTrue(runCatching { client.dismissNotification(-1L) }.isFailure)
            assertEquals(0, server.requestCount)
        } finally {
            server.close()
        }
    }
    @Test
    fun notificationWriteFailurePreservesHttpStatusForUiRecovery() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(code = 403, body = """{"detail":"forbidden"}"""))
            val client = RomMClient(server.url("/").toString(), "test-token")

            val failure = runCatching { client.markAllNotificationsRead() }.exceptionOrNull()

            assertTrue(failure is RomMHttpException)
            assertEquals(403, (failure as RomMHttpException).statusCode)
            assertEquals("POST", server.takeRequest().method)
        } finally {
            server.close()
        }
    }

    @Test
    fun devicePairingRequestsMeWriteForNotificationActions() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(body = """{"verification_path_complete":"/device","device_code":"device","user_code":"ABCD","expires_in":600,"interval":5}"""))
            val client = RomMClient(server.url("/").toString(), null)

            client.beginDevicePairing("device-id", "Tandem", "1.0")
            val request = server.takeRequest()
            val scopes = org.json.JSONObject(requireNotNull(request.body).utf8()).getJSONArray("requested_scopes")

            assertEquals("POST", request.method)
            assertEquals("/api/auth/device/init", request.url.encodedPath)
            assertTrue((0 until scopes.length()).any { scopes.getString(it) == "me.write" })
        } finally {
            server.close()
        }
    }
    @Test
    fun notificationWriteUnauthorizedResponseIsNotTreatedAsSuccess() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(code = 401, body = """{"detail":"unauthorized"}"""))
            val client = RomMClient(server.url("/").toString(), "expired-token")

            val failure = runCatching { client.markNotificationsRead(listOf(9L)) }.exceptionOrNull()

            assertTrue(failure is RomMHttpException)
            assertEquals(401, (failure as RomMHttpException).statusCode)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("Bearer expired-token", request.headers["Authorization"])
        } finally {
            server.close()
        }
    }

    @Test
    fun notificationWriteNetworkFailurePropagatesWithoutRetry() {
        val server = MockWebServer()
        server.start()
        try {
            val baseUrl = server.url("/").toString()
            server.close()
            val client = RomMClient(baseUrl, "test-token")

            assertTrue(runCatching { client.dismissNotification(27L) }.isFailure)
        } finally {
            runCatching { server.close() }
        }
    }
}
