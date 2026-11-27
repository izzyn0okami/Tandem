package app.lawnchair.gamemode

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreInstallerTest {

    @Test
    fun androidCoreFileNamesSupportAzaharBuildbotNaming() {
        assertEquals("azahar_libretro.so", CoreInstaller.coreFileName("azahar"))
        assertEquals("vita3k_libretro_android.so", CoreInstaller.coreFileName("vita3k"))
        assertEquals("azahar", CoreInstaller.coreIdFromFileName("azahar_libretro.so"))
        assertEquals("vita3k", CoreInstaller.coreIdFromFileName("vita3k_libretro_android.so"))
    }

    @Test
    fun melonDsDsUsesUpstreamAndroidReleaseNameAndPinnedArchiveDigest() {
        assertEquals("melondsds_libretro_android.so", CoreInstaller.coreFileName("melonds_ds"))
        assertEquals("melonds_ds", CoreInstaller.coreIdFromFileName("melondsds_libretro_android.so"))
        assertTrue(CoreInstaller.downloadUrl("melonds_ds").contains("JesseTG/melonds-ds/releases/download/v1.4.0/"))
        assertEquals(
            "555568396dd5cf230030e4dd0ff0b0c6ca7118231fe562bc50680fefa7ad8594",
            CoreInstaller.expectedArchiveSha256("melonds_ds"),
        )
        assertEquals(null, CoreInstaller.expectedArchiveSha256("melonds"))
    }

    @Test
    fun updateDetectionFindsChangedEtagEvenWhenLastModifiedIsUnchanged() {
        assertEquals(
            true,
            CoreInstaller.updateAvailableFromMetadata(10_000L, 10_000L, "\"new\"", "\"old\""),
        )
    }

    @Test
    fun updateDetectionUsesLastModifiedAndEtagTogether() {
        assertEquals(false, CoreInstaller.updateAvailableFromMetadata(10_000L, 10_000L, "\"same\"", "\"same\""))
        assertEquals(true, CoreInstaller.updateAvailableFromMetadata(12_000L, 10_000L, "\"same\"", "\"same\""))
    }

    @Test
    fun updateDetectionReturnsUnknownWithoutComparableMetadata() {
        assertEquals(null, CoreInstaller.updateAvailableFromMetadata(10_000L, 0L, "\"remote\"", null))
        assertEquals(null, CoreInstaller.updateAvailableFromMetadata(0L, 0L, null, null))
    }

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

            header[18] = 62
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
    fun restoresValidBackupWhenTargetIsMissing() {
        val directory = Files.createTempDirectory("core-recovery-missing").toFile()
        val target = File(directory, "core.so")
        val backup = File(directory, "core.so.backup")
        try {
            writeArm64Elf(backup)
            CoreInstaller.recoverBackup(target, backup)
            assertTrue(target.isFile)
            assertTrue(CoreInstaller.isArm64Elf(target))
            assertFalse(backup.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun restoresValidBackupOverInvalidTarget() {
        val directory = Files.createTempDirectory("core-recovery-invalid").toFile()
        val target = File(directory, "core.so")
        val backup = File(directory, "core.so.backup")
        try {
            target.writeText("corrupt target")
            writeArm64Elf(backup)
            CoreInstaller.recoverBackup(target, backup)
            assertTrue(CoreInstaller.isArm64Elf(target))
            assertFalse(backup.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun restoresConfirmedBackupWhenTargetContainsUncommittedValidCore() {
        val directory = Files.createTempDirectory("core-recovery-confirmed-backup").toFile()
        val target = File(directory, "core.so")
        val backup = File(directory, "core.so.backup")
        try {
            writeArm64Elf(target, 2)
            writeArm64Elf(backup, 1)
            val confirmedDigest = CoreInstaller.sha256(backup)
            CoreInstaller.recoverBackup(target, backup, confirmedDigest)
            assertEquals(confirmedDigest, CoreInstaller.sha256(target))
            assertFalse(backup.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun removesBackupWhenTargetMatchesConfirmedDigest() {
        val directory = Files.createTempDirectory("core-recovery-confirmed-target").toFile()
        val target = File(directory, "core.so")
        val backup = File(directory, "core.so.backup")
        try {
            writeArm64Elf(target, 1)
            writeArm64Elf(backup, 2)
            val confirmedDigest = CoreInstaller.sha256(target)
            CoreInstaller.recoverBackup(target, backup, confirmedDigest)
            assertEquals(confirmedDigest, CoreInstaller.sha256(target))
            assertFalse(backup.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun preservesBothValidFilesWhenConfirmedDigestIsUnavailable() {
        val directory = Files.createTempDirectory("core-recovery-unknown-digest").toFile()
        val target = File(directory, "core.so")
        val backup = File(directory, "core.so.backup")
        try {
            writeArm64Elf(target, 1)
            writeArm64Elf(backup, 2)
            val targetDigest = CoreInstaller.sha256(target)
            val backupDigest = CoreInstaller.sha256(backup)
            CoreInstaller.recoverBackup(target, backup, null)
            assertEquals(targetDigest, CoreInstaller.sha256(target))
            assertEquals(backupDigest, CoreInstaller.sha256(backup))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun restoresBackupMatchingConfirmedDigestWhenTargetIsMissing() {
        val directory = Files.createTempDirectory("core-recovery-missing-digest").toFile()
        val target = File(directory, "core.so")
        val backup = File(directory, "core.so.backup")
        try {
            writeArm64Elf(backup, 1)
            val confirmedDigest = CoreInstaller.sha256(backup)
            CoreInstaller.recoverBackup(target, backup, confirmedDigest)
            assertEquals(confirmedDigest, CoreInstaller.sha256(target))
            assertFalse(backup.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun removesInvalidBackupWhenTargetMatchesConfirmedDigest() {
        val directory = Files.createTempDirectory("core-recovery-invalid-backup").toFile()
        val target = File(directory, "core.so")
        val backup = File(directory, "core.so.backup")
        try {
            writeArm64Elf(target, 1)
            backup.writeText("corrupt backup")
            val targetDigest = CoreInstaller.sha256(target)

            CoreInstaller.recoverBackup(target, backup, targetDigest)

            assertEquals(targetDigest, CoreInstaller.sha256(target))
            assertFalse(backup.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun preservesInvalidTargetAndInvalidBackupForDiagnosis() {
        val directory = Files.createTempDirectory("core-recovery-both-invalid").toFile()
        val target = File(directory, "core.so")
        val backup = File(directory, "core.so.backup")
        try {
            target.writeText("corrupt target")
            backup.writeText("corrupt backup")

            CoreInstaller.recoverBackup(target, backup, null)

            assertEquals("corrupt target", target.readText())
            assertEquals("corrupt backup", backup.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun preservesInvalidBackupWhenTargetIsMissing() {
        val directory = Files.createTempDirectory("core-recovery-missing-invalid-backup").toFile()
        val target = File(directory, "core.so")
        val backup = File(directory, "core.so.backup")
        try {
            backup.writeText("corrupt backup")

            CoreInstaller.recoverBackup(target, backup, null)

            assertFalse(target.exists())
            assertEquals("corrupt backup", backup.readText())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun preservesBothValidFilesWhenNeitherMatchesConfirmedDigest() {
        val directory = Files.createTempDirectory("core-recovery-mismatched-digest").toFile()
        val target = File(directory, "core.so")
        val backup = File(directory, "core.so.backup")
        try {
            writeArm64Elf(target, 1)
            writeArm64Elf(backup, 2)
            val targetDigest = CoreInstaller.sha256(target)
            val backupDigest = CoreInstaller.sha256(backup)
            val unrelatedDigest = "0".repeat(64)

            CoreInstaller.recoverBackup(target, backup, unrelatedDigest)

            assertEquals(targetDigest, CoreInstaller.sha256(target))
            assertEquals(backupDigest, CoreInstaller.sha256(backup))
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun restoresOnlyValidBackupWhenItsDigestDoesNotMatch() {
        val directory = Files.createTempDirectory("core-recovery-valid-backup-mismatch").toFile()
        val target = File(directory, "core.so")
        val backup = File(directory, "core.so.backup")
        try {
            target.writeText("corrupt target")
            writeArm64Elf(backup, 3)
            val backupDigest = CoreInstaller.sha256(backup)
            val unrelatedDigest = "0".repeat(64)

            CoreInstaller.recoverBackup(target, backup, unrelatedDigest)

            assertEquals(backupDigest, CoreInstaller.sha256(target))
            assertFalse(backup.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun arm64ElfValidationRejectsWrongClassEndiannessAndMachine() {
        val file = Files.createTempFile("core-elf-invalid-abi", ".so").toFile()
        try {
            val valid = ByteArray(20)
            valid[0] = 0x7F
            valid[1] = 'E'.code.toByte()
            valid[2] = 'L'.code.toByte()
            valid[3] = 'F'.code.toByte()
            valid[4] = 2
            valid[5] = 1
            valid[18] = 183.toByte()

            val invalidHeaders = listOf(
                valid.copyOf().apply { this[4] = 1 },
                valid.copyOf().apply { this[5] = 2 },
                valid.copyOf().apply { this[18] = 62 },
                valid.copyOf().apply { this[0] = 0 },
            )

            invalidHeaders.forEach { header ->
                file.writeBytes(header)
                assertFalse(CoreInstaller.isArm64Elf(file))
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun emptyFileIsNotAValidArm64Elf() {
        val file = Files.createTempFile("core-elf-empty", ".so").toFile()
        try {
            assertFalse(CoreInstaller.isArm64Elf(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun extractsMelonDsCoreAndIgnoresLibretroInfoMetadata() {
        val directory = Files.createTempDirectory("melonds-zip-nested").toFile()
        val target = File(directory, "melondsds_libretro_android.so")
        val payload = ByteArray(32) { it.toByte() }
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("melondsds_libretro-android-Release/"))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("melondsds_libretro-android-Release/cores/melondsds_libretro_android.info"))
                zip.write("display_name = melonDS DS".toByteArray())
                zip.closeEntry()
                zip.putNextEntry(ZipEntry("melondsds_libretro-android-Release/cores/melondsds_libretro_android.so"))
                zip.write(payload)
                zip.closeEntry()
            }
        }.toByteArray()

        try {
            CoreInstaller.extractCoreFromArchive(
                ByteArrayInputStream(archive),
                target,
                "melondsds_libretro_android.so",
            )

            assertArrayEquals(payload, target.readBytes())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnexpectedAdditionalSharedLibraryInCoreArchive() {
        val directory = Files.createTempDirectory("melonds-zip-extra-core").toFile()
        val target = File(directory, "melondsds_libretro_android.so")
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("melondsds_libretro-android-Release/cores/other_libretro.so"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }.toByteArray()

        try {
            try {
                CoreInstaller.extractCoreFromArchive(
                    ByteArrayInputStream(archive),
                    target,
                    "melondsds_libretro_android.so",
                )
                fail("Biblioteca adicional deveria ser rejeitada")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("biblioteca adicional"))
            }
            assertFalse(target.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun rejectsUnsafeZipEntryPathsDuringCoreExtraction() {
        val directory = Files.createTempDirectory("melonds-zip-traversal").toFile()
        val target = File(directory, "melondsds_libretro_android.so")
        val archive = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("../melondsds_libretro_android.so"))
                zip.write(byteArrayOf(1, 2, 3))
                zip.closeEntry()
            }
        }.toByteArray()

        try {
            try {
                CoreInstaller.extractCoreFromArchive(
                    ByteArrayInputStream(archive),
                    target,
                    "melondsds_libretro_android.so",
                )
                fail("ZIP com traversal deveria ser rejeitado")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message.orEmpty().contains("caminho inseguro"))
            }
            assertFalse(target.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    private fun writeArm64Elf(file: File, marker: Int = 0) {
        val header = ByteArray(20)
        header[0] = 0x7F
        header[1] = 'E'.code.toByte()
        header[2] = 'L'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = 2
        header[5] = 1
        header[18] = 183.toByte()
        file.writeBytes(header + byteArrayOf(marker.toByte()))
    }
}
