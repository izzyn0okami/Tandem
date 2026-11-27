package app.lawnchair.gamemode

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.CRC32
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
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
    fun corruptedCachedRomIsRecreatedInsteadOfReused() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val archive = zip("cached.zip", "game.nes" to bytes)
        val first = RomUnzipper.extract(archive, setOf("nes"), cache)!!
        first.writeBytes(byteArrayOf(9, 9, 9, 9, 9))

        val repaired = RomUnzipper.extract(archive, setOf("nes"), cache)

        assertNotNull(repaired)
        assertArrayEquals(bytes, repaired!!.readBytes())
    }


    @Test
    fun restoresBackupLeftByInterruptedPromotionBeforeReplacingCache() {
        val archive = zip("interrupted.zip", "game.nes" to byteArrayOf(1, 2, 3))
        val cached = RomUnzipper.extract(archive, setOf("nes"), cache)!!
        val backup = File(cached.parentFile, "${cached.name}.backup")
        assertEquals(true, cached.renameTo(backup))

        zip("interrupted.zip", "game.nes" to byteArrayOf(4, 5, 6))

        val recovered = RomUnzipper.extract(archive, setOf("nes"), cache)

        assertNotNull(recovered)
        assertArrayEquals(byteArrayOf(4, 5, 6), recovered!!.readBytes())
        assertEquals(false, backup.exists())
        assertEquals(false, File(cached.parentFile, "${cached.name}.part").exists())
    }

    @Test
    fun rejectsDuplicateEntriesWithTheSameRomName() {
        val archive = zipWithDuplicateEntry(
            "duplicate.zip",
            "game.nes",
            byteArrayOf(1, 2, 3),
            byteArrayOf(4, 5, 6),
        )

        expectFailure<IllegalArgumentException> {
            RomUnzipper.extract(archive, setOf("nes"), cache)
        }
    }

    @Test
    fun discBasedZipIsPassedThroughWithoutExtraction() {
        val archive = File(root, "disc.zip").apply { writeText("not an extractable ZIP") }
        val platform = GamePlatform(
            slug = "psx-test",
            name = "PlayStation test",
            coreId = "test",
            extensions = setOf("cue", "chd", "zip"),
            folderAliases = setOf("psx-test"),
            sortOrder = 1,
            discBased = true,
        )

        assertSame(archive, RomUnzipper.prepare(archive, platform, cache))
        assertEquals(emptyList<File>(), cache.walkTopDown().filter { it.isFile }.toList())
    }

    @Test
    fun nativeZipRomIsPassedThroughWithoutExtraction() {
        val archive = File(root, "arcade.zip").apply { writeText("native ZIP ROM") }
        val platform = GamePlatform(
            slug = "neogeo-test",
            name = "Neo Geo test",
            coreId = "test",
            extensions = setOf("zip"),
            folderAliases = setOf("neogeo-test"),
            sortOrder = 1,
            zipIsNativeRom = true,
        )

        assertSame(archive, RomUnzipper.prepare(archive, platform, cache))
        assertEquals(emptyList<File>(), cache.walkTopDown().filter { it.isFile }.toList())
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
    fun rejectsAbsoluteWindowsAndDotSegmentEntryPaths() {
        val unsafeNames = listOf(
            "/absolute/game.nes",
            "\\absolute\\game.nes",
            "C:\\roms\\game.nes",
            "folder/../game.nes",
            "folder\\..\\game.nes",
            "folder/./game.nes",
            "folder//game.nes",
        )

        unsafeNames.forEachIndexed { index, entryName ->
            val archive = zip("unsafe-$index.zip", entryName to byteArrayOf(1, 2, 3))
            expectFailure<IllegalArgumentException> {
                RomUnzipper.extract(archive, setOf("nes"), cache)
            }
        }

        assertEquals(emptyList<File>(), cache.walkTopDown().filter { it.isFile }.toList())
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
    fun rejectsRomLargerThanSafeExtractionLimitBeforeWritingCache() {
        val archive = zip("oversized.zip", "game.nes" to byteArrayOf(1, 2, 3))
        setCentralDirectoryUncompressedSize(archive, 8L * 1024 * 1024 * 1024 + 1)

        expectFailure<IllegalArgumentException> {
            RomUnzipper.prepare(
                archive,
                GamePlatform(
                    slug = "nes-test",
                    name = "NES test",
                    coreId = "test",
                    extensions = setOf("nes"),
                    folderAliases = setOf("nes-test"),
                    sortOrder = 1,
                ),
                cache,
            )
        }

        assertEquals(emptyList<File>(), cache.walkTopDown().filter { it.isFile }.toList())
    }

    @Test
    fun rejectsDeclaredSizeMismatchAndCleansTemporaryFile() {
        val archive = zip("bad-size.zip", "game.nes" to byteArrayOf(1, 2, 3))
        corruptCentralDirectoryUncompressedSize(archive)

        expectFailure<Exception> {
            RomUnzipper.extract(archive, setOf("nes"), cache)
        }

        val temporaryFiles = cache.walkTopDown().filter { it.name.endsWith(".part") }.toList()
        assertEquals(emptyList<File>(), temporaryFiles)
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


    /** Produz ZIP válido com dois registros centrais de mesmo nome (ZipOutputStream proíbe duplicatas). */
    private fun zipWithDuplicateEntry(
        archiveName: String,
        entryName: String,
        first: ByteArray,
        second: ByteArray,
    ): File {
        val name = entryName.toByteArray(Charsets.UTF_8)
        val localRecords = ByteArrayOutputStream()
        val centralRecords = ByteArrayOutputStream()
        val contents = listOf(first, second)

        contents.forEach { data ->
            val crc = CRC32().apply { update(data) }.value
            val localOffset = localRecords.size()
            writeInt(localRecords, 0x04034b50L)
            writeShort(localRecords, 20)
            writeShort(localRecords, 0)
            writeShort(localRecords, 0)
            writeShort(localRecords, 0)
            writeShort(localRecords, 0)
            writeInt(localRecords, crc)
            writeInt(localRecords, data.size.toLong())
            writeInt(localRecords, data.size.toLong())
            writeShort(localRecords, name.size)
            writeShort(localRecords, 0)
            localRecords.write(name)
            localRecords.write(data)

            writeInt(centralRecords, 0x02014b50L)
            writeShort(centralRecords, 20)
            writeShort(centralRecords, 20)
            writeShort(centralRecords, 0)
            writeShort(centralRecords, 0)
            writeShort(centralRecords, 0)
            writeShort(centralRecords, 0)
            writeInt(centralRecords, crc)
            writeInt(centralRecords, data.size.toLong())
            writeInt(centralRecords, data.size.toLong())
            writeShort(centralRecords, name.size)
            writeShort(centralRecords, 0)
            writeShort(centralRecords, 0)
            writeShort(centralRecords, 0)
            writeShort(centralRecords, 0)
            writeInt(centralRecords, 0)
            writeInt(centralRecords, localOffset.toLong())
            centralRecords.write(name)
        }

        val centralOffset = localRecords.size()
        val archiveBytes = ByteArrayOutputStream()
        localRecords.writeTo(archiveBytes)
        centralRecords.writeTo(archiveBytes)
        writeInt(archiveBytes, 0x06054b50L)
        writeShort(archiveBytes, 0)
        writeShort(archiveBytes, 0)
        writeShort(archiveBytes, contents.size)
        writeShort(archiveBytes, contents.size)
        writeInt(archiveBytes, centralRecords.size().toLong())
        writeInt(archiveBytes, centralOffset.toLong())
        writeShort(archiveBytes, 0)

        return File(root, archiveName).apply { writeBytes(archiveBytes.toByteArray()) }
    }

    private fun writeShort(output: ByteArrayOutputStream, value: Int) {
        output.write(value and 0xff)
        output.write((value ushr 8) and 0xff)
    }

    private fun writeInt(output: ByteArrayOutputStream, value: Long) {
        repeat(4) { shift -> output.write(((value ushr (shift * 8)) and 0xff).toInt()) }
    }

    private fun setCentralDirectoryUncompressedSize(archive: File, size: Long) {
        val bytes = archive.readBytes()
        val signature = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
        val offset = (0..bytes.size - signature.size).firstOrNull { index ->
            signature.indices.all { bytes[index + it] == signature[it] }
        } ?: error("Central directory entry not found")
        repeat(4) { index ->
            bytes[offset + 24 + index] = (size ushr (index * 8)).toByte()
        }
        archive.writeBytes(bytes)
    }

    private fun corruptCentralDirectoryUncompressedSize(archive: File) {
        val bytes = archive.readBytes()
        val signature = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
        val offset = (0..bytes.size - signature.size).firstOrNull { index ->
            signature.indices.all { bytes[index + it] == signature[it] }
        } ?: error("Central directory entry not found")
        // Uncompressed size is the four-byte little-endian field at offset 24.
        bytes[offset + 24] = (bytes[offset + 24].toInt() + 1).toByte()
        archive.writeBytes(bytes)
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
