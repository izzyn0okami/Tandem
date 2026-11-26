package app.lawnchair.gamemode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BiosManagerTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("tandem-bios-test").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

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
        val lynx = GamePlatforms.all.single { it.slug == "lynx" }
        val gba = GamePlatforms.all.single { it.slug == "gba" }
        val gameBoy = GamePlatforms.all.single { it.slug == "gb" }
        val masterSystem = GamePlatforms.all.single { it.slug == "sms" }
        val nes = GamePlatforms.all.single { it.slug == "nes" }

        assertFalse(atari5200.biosRequired)
        assertEquals("281f20ea4320404ec820fb7ec0693b38", atari5200.bios.single().md5Hash)
        assertTrue(coleco.biosRequired)
        assertEquals("2c66f5911e5b42b8ebe113403548eee7", coleco.bios.single().md5Hash)
        assertEquals(listOf("Databases", "Machines"), msx.biosKit)
        assertEquals(2, intellivision.biosGroups.size)
        assertTrue(intellivision.biosGroups.all { it.size == 1 })
        assertTrue(lynx.biosRequired)
        assertEquals("fcd403db69f54290b51035d82f835e7b", lynx.bios.single().md5Hash)
        assertFalse(gba.biosRequired)
        assertEquals("a860e8c0b6d573d191e4ec7db1b1e4f6", gba.bios.single().md5Hash)
        assertEquals("32fbbd84168d3482956eb3c5051637f5", gameBoy.bios.single().md5Hash)
        assertEquals(3, masterSystem.bios.size)
        assertEquals("ca30b50f880eb660a320674ed365ef7a", nes.bios.first().md5Hash)
        assertFalse(nes.biosRequired)
        assertFalse(BiosManager.isReady(lynx, hasBios = { false }, isKitReady = { false }))
        assertTrue(BiosManager.isReady(nes, hasBios = { false }, isKitReady = { false }))
    }

    @Test
    fun melonDsDsiFirmwareIsCatalogedAsOptional() {
        val nds = GamePlatforms.all.single { it.slug == "nds" }
        val dsiFiles = setOf(
            "dsi_bios7.bin",
            "dsi_bios9.bin",
            "dsi_firmware.bin",
            "dsi_nand.bin",
            "dsi_sd_card.bin",
        )

        assertTrue(dsiFiles.all { name -> nds.bios.any { it.name == name } })
        assertTrue(BiosManager.isReady(nds, hasBios = { false }, isKitReady = { false }))
    }

    @Test
    fun officialMd5VerifierAcceptsMatchingBytesAndRejectsCorruption() {
        val file = File(root, "bios.bin").apply { writeText("abc") }
        val known = BiosFile("bios.bin", "Known BIOS", "900150983cd24fb0d6963f7d28e17f72")
        val incorrect = BiosFile("bios.bin", "Known BIOS", "00000000000000000000000000000000")

        BiosManager.verifyKnownMd5(file, known)
        assertTrue(runCatching { BiosManager.verifyKnownMd5(file, incorrect) }.isFailure)
    }

    @Test
    fun kitImportReplacesAllRequiredFoldersAfterStaging() {
        val platform = platform(kit = listOf("Databases", "Machines"))
        val source = File(root, "source")
        File(source, "Databases").apply { mkdirs() }
        File(source, "Databases/new.dat").writeText("new database")
        File(source, "Machines").apply { mkdirs() }
        File(source, "Machines/msx.rom").writeText("machine data")
        File(root, "system/Databases").apply { mkdirs() }
        File(root, "system/Databases/old.dat").writeText("old database")

        val copied = BiosManager.importKitFiles(File(root, "system"), platform, source)

        assertEquals(2, copied)
        assertFalse(File(root, "system/Databases/old.dat").exists())
        assertEquals("new database", File(root, "system/Databases/new.dat").readText())
        assertEquals("machine data", File(root, "system/Machines/msx.rom").readText())
        assertFalse(File(root, "system").listFiles().orEmpty().any { it.name.startsWith(".bios-kit-") })
    }

    @Test
    fun incompleteKitDoesNotTouchExistingInstallation() {
        val platform = platform(kit = listOf("Databases", "Machines"))
        val source = File(root, "incomplete")
        File(source, "Databases").apply { mkdirs() }
        File(source, "Databases/new.dat").writeText("new database")
        val oldFile = File(root, "system/Databases/old.dat").apply {
            parentFile?.mkdirs()
            writeText("keep me")
        }

        assertTrue(runCatching {
            BiosManager.importKitFiles(File(root, "system"), platform, source)
        }.isFailure)
        assertEquals("keep me", oldFile.readText())
        assertFalse(File(root, "system/Machines").exists())
    }

    @Test
    fun vitaFirmwareIsTrackedOnlyThroughItsDedicatedKit() {
        val vita = GamePlatforms.all.single { it.slug == "vita" }
        assertTrue(vita.biosRequired)
        assertEquals(listOf("vita3k"), vita.biosKit)
        assertTrue(vita.bios.isEmpty())
    }

    @Test
    fun advancedCoreKitsRequireTheirActualSystemStructure() {
        val ps2 = GamePlatforms.all.single { it.slug == "ps2" }
        val ps2Kit = File(root, "pcsx2").apply { mkdirs() }
        assertFalse(BiosManager.kitContentsReady(ps2, "pcsx2", ps2Kit))
        File(ps2Kit, "bios/scph10000.bin").apply { parentFile?.mkdirs(); writeText("bios") }
        assertTrue(BiosManager.kitContentsReady(ps2, "pcsx2", ps2Kit))

        val vita = GamePlatforms.all.single { it.slug == "vita" }
        val vitaKit = File(root, "vita3k").apply { mkdirs() }
        File(vitaKit, "PSP2UPDAT.PUP").writeText("firmware")
        File(vitaKit, "data/data.bin").apply { parentFile?.mkdirs(); writeText("data") }
        File(vitaKit, "shaders-builtin/shader.bin").apply { parentFile?.mkdirs(); writeText("shader") }
        assertFalse(BiosManager.kitContentsReady(vita, "vita3k", vitaKit))
        File(vitaKit, "preinst_fresh.pup").writeText("preinstalled firmware")
        assertTrue(BiosManager.kitContentsReady(vita, "vita3k", vitaKit))

        val dolphin = GamePlatforms.all.single { it.slug == "gc" }
        val dolphinKit = File(root, "dolphin-emu").apply { mkdirs() }
        File(dolphinKit, "Sys/GC/font.bin").apply { parentFile?.mkdirs(); writeText("sys") }
        assertTrue(BiosManager.kitContentsReady(dolphin, "dolphin-emu", dolphinKit))
    }

    @Test
    fun deviceCapabilityParserKeepsOnlyExplicitBooleanValues() {
        val parsed = parseDeclaredDeviceCapabilities(
            mapOf("remote_install" to true, "save_sync" to false, "label" to "unknown"),
        )

        assertEquals(mapOf("remote_install" to true, "save_sync" to false), parsed)
    }

    @Test
    fun missingDeviceCapabilityRemainsUnknown() {
        val device = RemoteDevice(
            id = "device-1",
            name = "Test device",
            client = "tandem",
            platform = "Android",
            clientVersion = "1.0",
            syncMode = "API",
            syncEnabled = true,
            lastSeen = null,
            capabilities = mapOf("remote_install" to false, "save_sync" to true),
            online = false,
        )

        assertFalse(device.supports("remote_install")!!)
        assertTrue(device.supports("save_sync")!!)
        assertNull(device.supports("unknown_capability"))
        assertNull(device.copy(online = null).online)
    }
}
