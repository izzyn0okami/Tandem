package app.lawnchair.gamemode

import java.io.File
import java.security.MessageDigest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.createTempDirectory

class RetroArchWebDavClientTest {

    @Test
    fun optionsAndPropfindDiscoverWebDavWithoutWriting() {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(204).addHeader("DAV", "1, 2").addHeader("Allow", "OPTIONS, PROPFIND, GET, PUT").build())
            server.enqueue(MockResponse.Builder().code(207).body("<multistatus/>").build())
            val client = RetroArchWebDavClient(server.url("/webdav/").toString(), http = OkHttpClient())

            val capabilities = client.options()
            val xml = client.propfind()

            assertEquals("1, 2", capabilities.dav)
            assertTrue(capabilities.allow!!.contains("PROPFIND"))
            assertEquals("<multistatus/>", xml)
            assertEquals("OPTIONS", server.takeRequest().method)
            assertEquals("PROPFIND", server.takeRequest().method)
            assertEquals(2, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun downloadWritesVerifiedBytesOnlyToStaging() {
        val server = MockWebServer()
        val directory = createTempDirectory("webdav-staging-").toFile()
        val staging = File(directory, "save.srm.part")
        val payload = "webdav-save-payload"
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray())
            .joinToString("") { "%02x".format(it) }
        server.start()
        try {
            server.enqueue(MockResponse.Builder().body(payload).build())
            val client = RetroArchWebDavClient(server.url("/webdav/").toString(), http = OkHttpClient())

            val actual = client.downloadToStaging("saves/game.srm", staging, expected)

            assertEquals(expected, actual)
            assertEquals(payload, staging.readText())
            assertEquals("GET", server.takeRequest().method)
            assertTrue(staging.name.endsWith(".part"))
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }

    @Test
    fun uploadCreatesOnlyNewRemoteFilesAndUsesNoOverwritePrecondition() {
        val server = MockWebServer()
        val source = File.createTempFile("webdav-upload-", ".srm")
        source.writeText("local-save")
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(201).build())
            val client = RetroArchWebDavClient(server.url("/webdav/").toString(), http = OkHttpClient())

            client.uploadNewFile("saves/new-game.srm", source)

            val request = server.takeRequest()
            assertEquals("PUT", request.method)
            assertEquals("/webdav/saves/new-game.srm", request.url.encodedPath)
            assertEquals("*", request.headers["If-None-Match"])
        } finally {
            server.close()
            source.delete()
        }
    }

    @Test
    fun uploadDoesNotOverwriteAnExistingRemoteFile() {
        val server = MockWebServer()
        val source = File.createTempFile("webdav-existing-", ".srm")
        source.writeText("local-save")
        server.start()
        try {
            server.enqueue(MockResponse.Builder().code(412).build())
            val client = RetroArchWebDavClient(server.url("/webdav/").toString(), http = OkHttpClient())

            val result = runCatching { client.uploadNewFile("saves/existing.srm", source) }

            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("412"))
            assertEquals("PUT", server.takeRequest().method)
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
            source.delete()
        }
    }

    @Test
    fun rejectsTraversalAndNeverCreatesStagingOnFailedDownload() {
        val server = MockWebServer()
        val directory = createTempDirectory("webdav-invalid-").toFile()
        val staging = File(directory, "invalid.part")
        server.start()
        try {
            val client = RetroArchWebDavClient(server.url("/webdav/").toString(), http = OkHttpClient())
            assertTrue(runCatching { client.propfind("../outside") }.isFailure)
            assertTrue(runCatching { client.propfind("%2e%2e/outside") }.isFailure)
            server.enqueue(MockResponse.Builder().code(404).build())
            assertTrue(runCatching { client.downloadToStaging("missing.srm", staging) }.isFailure)
            assertFalse(staging.exists())
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
            directory.deleteRecursively()
        }
    }
}
