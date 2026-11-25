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
}
