package app.lawnchair.gamemode

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreInstallerTest {

    @Test
    fun sha256MatchesKnownDigest() {
        val file = Files.createTempFile("core-digest", ".bin").toFile()
        try {
            file.writeText("abc")
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                CoreInstaller.sha256(file),
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun arm64ElfValidationRequires64BitLittleEndianAarch64Header() {
        val file = Files.createTempFile("core-elf", ".so").toFile()
        try {
            val header = ByteArray(20)
            header[0] = 0x7F
            header[1] = 'E'.code.toByte()
            header[2] = 'L'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = 2
            header[5] = 1
            header[18] = 183.toByte()
            file.writeBytes(header)
            assertTrue(CoreInstaller.isArm64Elf(file))

            header[18] = 62 // x86-64, not AArch64
            file.writeBytes(header)
            assertFalse(CoreInstaller.isArm64Elf(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun truncatedElfHeaderIsRejected() {
        val file = Files.createTempFile("core-elf-short", ".so").toFile()
        try {
            file.writeBytes(byteArrayOf(0x7F, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            assertFalse(CoreInstaller.isArm64Elf(file))
        } finally {
            file.delete()
        }
    }
    @Test
    fun azaharUsesItsPublishedBuildbotArtifactName() {
        assertEquals(
            CoreInstaller.CoreArtifact("azahar_libretro.so.zip", "azahar_libretro.so"),
            CoreInstaller.artifact("azahar"),
        )
    }

    @Test
    fun standardCoresKeepAndroidArtifactNaming() {
        assertEquals(
            CoreInstaller.CoreArtifact("vita3k_libretro_android.so.zip", "vita3k_libretro_android.so"),
            CoreInstaller.artifact("vita3k"),
        )
    }

    @Test
    fun coreIdIsResolvedFromTheActualLibraryFilename() {
        assertEquals("azahar", CoreInstaller.coreIdFromFile(File("azahar_libretro.so")))
        assertEquals("vita3k", CoreInstaller.coreIdFromFile(File("vita3k_libretro_android.so")))
    }

}
