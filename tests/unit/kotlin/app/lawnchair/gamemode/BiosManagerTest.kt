package app.lawnchair.gamemode

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
