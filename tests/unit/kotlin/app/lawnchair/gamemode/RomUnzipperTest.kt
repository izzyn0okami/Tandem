package app.lawnchair.gamemode

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class RomUnzipperTest {

    private lateinit var root: File
    private lateinit var cache: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tandem-rom-zip").toFile()
        cache = File(root, "cache").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun extractsSingleCompatibleRomAndReusesValidatedCache() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val archive = zip("game.zip", "nested/game.nes" to bytes)

        val first = RomUnzipper.extract(archive, setOf("nes"), cache)
        val second = RomUnzipper.extract(archive, setOf("nes"), cache)

        assertNotNull(first)
        assertEquals(first, second)
        assertArrayEquals(bytes, first!!.readBytes())
        assertTrueFileExists(first)
    }

    @Test
    fun rejectsArchiveWithMultipleCompatibleRoms() {
        val archive = zip(
            "multiple.zip",
            "one.nes" to byteArrayOf(1),
            "two.nes" to byteArrayOf(2),
        )

        expectFailure<IllegalArgumentException> {
            RomUnzipper.extract(archive, setOf("nes"), cache)
        }
    }

    @Test
    fun rejectsArchiveWithoutCompatibleRom() {
        val archive = zip("wrong-platform.zip", "game.sfc" to byteArrayOf(1, 2))

        expectFailure<IllegalArgumentException> {
            RomUnzipper.extract(archive, setOf("nes"), cache)
        }
    }

    @Test
    fun ignoresTraversalEntryAndDoesNotWriteOutsideCache() {
        val archive = zip("traversal.zip", "../escaped.nes" to byteArrayOf(7))

        expectFailure<IllegalArgumentException> {
            RomUnzipper.extract(archive, setOf("nes"), cache)
        }

        assertFalse(File(root, "escaped.nes").exists())
    }

    @Test
    fun rejectsCentralDirectoryCrcMismatch() {
        val archive = zip("bad-crc.zip", "game.nes" to byteArrayOf(10, 20, 30))
        corruptCentralDirectoryCrc(archive)

        expectFailure<Exception> {
            RomUnzipper.extract(archive, setOf("nes"), cache)
        }
    }

    @Test
    fun rejectsCorruptZip() {
        val archive = File(root, "corrupt.zip").apply { writeText("not a ZIP archive") }

        expectFailure<Exception> {
            RomUnzipper.extract(archive, setOf("nes"), cache)
        }
    }

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val archive = File(root, name)
        ZipOutputStream(archive.outputStream()).use { output ->
            entries.forEach { (entryName, bytes) ->
                output.putNextEntry(ZipEntry(entryName))
                output.write(bytes)
                output.closeEntry()
            }
        }
        return archive
    }

    private fun corruptCentralDirectoryCrc(archive: File) {
        val bytes = archive.readBytes()
        val signature = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
        val offset = (0..bytes.size - signature.size).firstOrNull { index ->
            signature.indices.all { bytes[index + it] == signature[it] }
        } ?: error("Central directory entry not found")
        // CRC-32 is the four-byte little-endian field at offset 16 in a central directory header.
        for (index in 0 until 4) bytes[offset + 16 + index] = 0
        archive.writeBytes(bytes)
    }

    private inline fun <reified T : Throwable> expectFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (failure: Throwable) {
            if (failure !is T) throw failure
        }
    }

    private fun assertTrueFileExists(file: File) {
        org.junit.Assert.assertTrue(file.isFile)
    }
}
