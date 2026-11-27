package app.lawnchair.gamemode

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AdrenoToolsDriverManagerTest {

    @Test
    fun safeArchiveOutputKeepsFilesInsideCanonicalStagingDirectory() {
        val staging = Files.createTempDirectory("adrenotools-driver-staging").toFile()
        try {
            val output = AdrenoToolsDriverManager.safeArchiveOutput(staging, "libvulkan_freedreno.so")
            assertEquals(staging.canonicalFile, output.parentFile)
        } finally {
            staging.deleteRecursively()
        }
    }

    @Test
    fun safeArchiveOutputRejectsTraversalAndPathSeparators() {
        val staging = Files.createTempDirectory("adrenotools-driver-staging").toFile()
        try {
            listOf("../escape.so", "folder/driver.so", "..", ".", "C:driver.so", "folder\\driver.so").forEach { name ->
                try {
                    AdrenoToolsDriverManager.safeArchiveOutput(staging, name)
                    fail("Expected unsafe archive filename to be rejected: $name")
                } catch (_: IllegalArgumentException) {
                    // Entradas devem ser arquivos simples dentro da pasta temporária.
                }
            }
        } finally {
            staging.deleteRecursively()
        }
    }
}
