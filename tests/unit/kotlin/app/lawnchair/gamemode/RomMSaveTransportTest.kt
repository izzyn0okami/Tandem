package app.lawnchair.gamemode

import java.io.File
import java.nio.file.Files
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomMSaveTransportTest {
    @Test
    fun listsQuickStatesFromRomMWithAccountAuthenticationAndMetadata() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse(
                    body = """[{"id":17,"emulator":"tandem_snes9x","slot":null,"updated_at":"2026-11-24T12:00:00Z","content_hash":"sha256-value"}]""",
                ),
            )
            val client = RomMClient(server.url("/").toString(), "test-token")

            val files = client.listRemoteFiles(RemoteFileKind.State, 42L)
            val request = server.takeRequest()

            assertEquals(1, files.size)
            assertEquals(17L, files.single().id)
            assertEquals("tandem_snes9x", files.single().emulator)
            assertEquals("sha256-value", files.single().contentHash)
            assertEquals("GET", request.method)
            assertEquals("/api/states", request.url.encodedPath)
            assertEquals("42", request.url.queryParameter("rom_id"))
            assertEquals("Bearer test-token", request.headers["Authorization"])
        } finally {
            server.close()
        }
    }

    @Test
    fun uploadsQuickStateAsMultipartStateFileToTheStatesEndpoint() {
        val server = MockWebServer()
        val directory = Files.createTempDirectory("romm-state-upload").toFile()
        val file = File(directory, "slot1.state").apply { writeText("quick-state-bytes") }
        server.start()
        try {
            server.enqueue(
                MockResponse(
                    body = """{"id":29,"emulator":"tandem_snes9x","updated_at":"2026-11-24T12:00:00Z","content_hash":"remote-hash"}""",
                ),
            )
            val client = RomMClient(server.url("/").toString(), "test-token")

            val uploaded = client.uploadRemoteFile(
                RemoteFileKind.State,
                romId = 42L,
                emulator = "tandem_snes9x",
                existingId = null,
                file = file,
                uploadName = "SuperMarioWorld.state",
            )
            val request = server.takeRequest()
            val body = request.body!!.utf8()

            assertEquals(29L, uploaded.id)
            assertEquals("POST", request.method)
            assertEquals("/api/states", request.url.encodedPath)
            assertEquals("42", request.url.queryParameter("rom_id"))
            assertEquals("tandem_snes9x", request.url.queryParameter("emulator"))
            assertEquals("Bearer test-token", request.headers["Authorization"])
            assertTrue(body.contains("name=\"stateFile\""))
            assertTrue(body.contains("filename=\"SuperMarioWorld.state\""))
            assertTrue(body.contains("quick-state-bytes"))
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun downloadsQuickStateToStagingAndPromotesOnlyAfterSuccessfulResponse() {
        val server = MockWebServer()
        val directory = Files.createTempDirectory("romm-state-download").toFile()
        val target = File(directory, "state1.state")
        server.start()
        try {
            server.enqueue(MockResponse(body = "downloaded-state"))
            val client = RomMClient(server.url("/").toString(), "test-token")

            client.downloadRemoteFile(RemoteFileKind.State, 17L, target)
            val request = server.takeRequest()

            assertEquals("downloaded-state", target.readText())
            assertEquals("GET", request.method)
            assertEquals("/api/states/17/content", request.url.encodedPath)
            assertEquals("Bearer test-token", request.headers["Authorization"])
            assertFalse(File(directory, "state1.state.part").exists())
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun listEndpointPreservesStructuredHttpErrorAndRedactsSecrets() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse(
                    code = 500,
                    body = """{"detail":"database unavailable token=secret-value authorization=Bearer private-value"}""",
                ),
            )
            val client = RomMClient(server.url("/").toString(), "test-token")

            val error = runCatching { client.listRemoteFiles(RemoteFileKind.State, 42L) }.exceptionOrNull()

            assertTrue(error is RomMHttpException)
            assertEquals(500, (error as RomMHttpException).statusCode)
            assertTrue(error.detail!!.contains("database unavailable"))
            assertFalse(error.detail!!.contains("secret-value"))
            assertFalse(error.detail!!.contains("private-value"))
        } finally {
            server.close()
        }
    }

    @Test
    fun failedDownloadKeepsExistingLocalStateAndSurfacesHttpDetail() {
        val server = MockWebServer()
        val directory = Files.createTempDirectory("romm-state-failure").toFile()
        val target = File(directory, "state1.state").apply { writeText("local-state-must-survive") }
        server.start()
        try {
            server.enqueue(MockResponse(code = 503, body = """{"detail":"storage temporarily unavailable"}"""))
            val client = RomMClient(server.url("/").toString(), "test-token")

            val error = runCatching {
                client.downloadRemoteFile(RemoteFileKind.State, 17L, target)
            }.exceptionOrNull()

            assertTrue(error is RomMHttpException)
            assertEquals(503, (error as RomMHttpException).statusCode)
            assertEquals("storage temporarily unavailable", error.detail)
            assertEquals("local-state-must-survive", target.readText())
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun errorDetailRejectsNonJsonBodiesAndBoundsStructuredText() {
        assertEquals(null, romMErrorDetail("internal server error"))
        assertEquals(300, romMErrorDetail("""{"detail":"${"x".repeat(400)}"}""")!!.length)
    }
}
