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
