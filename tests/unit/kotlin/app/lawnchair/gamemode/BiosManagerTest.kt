package app.lawnchair.gamemode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BiosManagerTest {
    private val biosA = BiosFile("bios-a.bin", "BIOS A")
    private val biosB = BiosFile("bios-b.bin", "BIOS B")
    private val biosC = BiosFile("bios-c.bin", "BIOS C")

    private fun platform(
        bios: List<BiosFile> = emptyList(),
        groups: List<List<BiosFile>> = emptyList(),
        required: Boolean = true,
        kit: List<String>? = null,
    ) = GamePlatform(
        slug = "test",
        name = "Test platform",
        coreId = "test-core",
        extensions = setOf("rom"),
        folderAliases = setOf("test"),
        sortOrder = 0,
        bios = bios,
        biosGroups = groups,
        biosRequired = required,
        biosKit = kit,
    )

    private fun ready(platform: GamePlatform, vararg present: BiosFile, kitReady: Boolean = false): Boolean {
        val available = present.toSet()
        return BiosManager.isReady(
            platform = platform,
            hasBios = { it in available },
            isKitReady = { kitReady },
        )
    }

    @Test
    fun optionalBiosNeverBlocksLaunch() {
        assertTrue(ready(platform(bios = listOf(biosA), required = false)))
    }

    @Test
    fun legacyBiosListTreatsEntriesAsAlternatives() {
        val required = platform(bios = listOf(biosA, biosB))
        assertFalse(ready(required))
        assertTrue(ready(required, biosA))
        assertTrue(ready(required, biosB))
    }

    @Test
    fun explicitGroupsRequireEveryGroupButOnlyOneEntryPerGroup() {
        val required = platform(groups = listOf(listOf(biosA, biosB), listOf(biosC)))
        assertFalse(ready(required, biosA))
        assertFalse(ready(required, biosC))
        assertTrue(ready(required, biosA, biosC))
        assertTrue(ready(required, biosB, biosC))
    }

    @Test
    fun requiredPlatformWithoutAnyBiosDefinitionIsNotReady() {
        assertFalse(ready(platform()))
    }

    @Test
    fun biosKitUsesItsDedicatedReadinessCheck() {
        val required = platform(kit = listOf("Databases", "Machines"))
        assertFalse(ready(required, kitReady = false))
        assertTrue(ready(required, kitReady = true))
    }

    @Test
    fun auditedCoreCatalogKeepsOptionalAndRequiredFirmwareAccurate() {
        val atari5200 = GamePlatforms.all.single { it.slug == "atari5200" }
        val coleco = GamePlatforms.all.single { it.slug == "coleco" }
        val msx = GamePlatforms.all.single { it.slug == "msx" }
        val intellivision = GamePlatforms.all.single { it.slug == "intellivision" }

        assertFalse(atari5200.biosRequired)
        assertEquals("281f20ea4320404ec820fb7ec0693b38", atari5200.bios.single().md5Hash)
        assertTrue(coleco.biosRequired)
        assertEquals("2c66f5911e5b42b8ebe113403548eee7", coleco.bios.single().md5Hash)
        assertEquals(listOf("Databases", "Machines"), msx.biosKit)
        assertEquals(2, intellivision.biosGroups.size)
        assertTrue(intellivision.biosGroups.all { it.size == 1 })
    }

    @Test
    fun missingDeviceCapabilityRemainsUnknown() {
        val device = RemoteDevice(
            id = "device-1",
            name = "Test device",
            client = "tandem",
            platform = "Android",
            deviceType = null,
            clientVersion = "1.0",
            lastSeen = null,
            capabilities = mapOf("remote_install" to false, "save_sync" to true),
            online = false,
        )

        assertFalse(device.supports("remote_install")!!)
        assertTrue(device.supports("save_sync")!!)
        assertNull(device.supports("unknown_capability"))
    }
}
