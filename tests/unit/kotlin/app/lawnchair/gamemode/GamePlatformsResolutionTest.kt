package app.lawnchair.gamemode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamePlatformsResolutionTest {

    @Test
    fun everyCurrentlyDeclaredPlatformHasAtLeastOneExtension() {
        assertTrue(GamePlatforms.all.all { it.extensions.isNotEmpty() })
    }

    @Test
    fun recognizedFolderCanDisambiguateGenericZipExtension() {
        assertEquals(GamePlatforms.bySlug("snes"), GamePlatforms.resolve(listOf("snes"), "zip"))
        assertEquals(GamePlatforms.bySlug("neogeo"), GamePlatforms.resolve(listOf("neogeo"), "zip"))
    }

    @Test
    fun genericBinaryExtensionDoesNotOverrideDiscPlatformRules() {
        assertNull(GamePlatforms.resolve(listOf("psx"), "bin"))
    }

    @Test
    fun unknownExtensionIsNotGuessedFromFolderOrCatalog() {
        assertNull(GamePlatforms.resolve(listOf("psx"), "unknown"))
        assertNull(GamePlatforms.resolve(emptyList(), "unknown"))
    }

    @Test
    fun ambiguousExtensionWithoutFolderDoesNotGuessPlatform() {
        assertNull(GamePlatforms.resolve(emptyList(), "iso"))
        assertNull(GamePlatforms.resolve(emptyList(), "cue"))
    }

    @Test
    fun uniqueExtensionCanResolveWithoutFolder() {
        assertEquals(GamePlatforms.bySlug("snes"), GamePlatforms.resolve(emptyList(), "sfc"))
        assertEquals(GamePlatforms.bySlug("gba"), GamePlatforms.resolve(emptyList(), "gba"))
    }

    @Test
    fun newHighEndPlatformsUseTheirArm64LibretroCores() {
        assertEquals("armsx2", GamePlatforms.bySlug("ps2")?.coreId)
        assertEquals("dolphin", GamePlatforms.bySlug("gc")?.coreId)
        assertEquals("dolphin", GamePlatforms.bySlug("wii")?.coreId)
        assertEquals("azahar", GamePlatforms.bySlug("3ds")?.coreId)
        assertEquals("cemu", GamePlatforms.bySlug("wiiu")?.coreId)
        assertEquals("rpcs3", GamePlatforms.bySlug("ps3")?.coreId)
        assertEquals("vita3k", GamePlatforms.bySlug("vita")?.coreId)
        assertEquals("xenia_edge", GamePlatforms.bySlug("xbox360")?.coreId)
        assertEquals("supermodel", GamePlatforms.bySlug("model3")?.coreId)
        assertEquals(GamePlatforms.bySlug("vita"), GamePlatforms.resolve(listOf("psvita"), "vpk"))
        assertEquals(GamePlatforms.bySlug("ps3"), GamePlatforms.resolve(listOf("ps3"), "pkg"))
        assertEquals(GamePlatforms.bySlug("xbox360"), GamePlatforms.resolve(listOf("xbox360"), "xex"))
        assertEquals(GamePlatforms.bySlug("model3"), GamePlatforms.resolve(listOf("model3"), "zip"))
    }

    @Test
    fun stageJAddsRetroComputerAndArcadePlatformsWithoutEnablingUnauditedCapabilities() {
        val amiga = GamePlatforms.bySlug("amiga")!!
        val dos = GamePlatforms.bySlug("dos")!!
        val arcade = GamePlatforms.bySlug("arcade")!!
        val pocketStation = GamePlatforms.bySlug("pocketstation")!!

        assertEquals("puae", amiga.coreId)
        assertEquals("dosbox_pure", dos.coreId)
        assertEquals("fbneo", arcade.coreId)
        assertEquals("pokketstation", pocketStation.coreId)

        listOf(
            CoreRegistry.info(amiga, amiga.coreId),
            CoreRegistry.info(dos, dos.coreId),
            CoreRegistry.info(arcade, arcade.coreId),
            CoreRegistry.info(pocketStation, pocketStation.coreId),
        ).forEach { core ->
            assertFalse("${core.id} must not advertise unaudited save states", core.supportsStates)
            assertFalse("${core.id} must not advertise unaudited RomM save sync", core.supportsRomMSaveSync)
        }
    }

    @Test
    fun everyPreviouslyImplicitPlatformDeclaresCoreCapabilitiesExplicitly() {
        val wonderSwan = CoreRegistry.info(GamePlatforms.bySlug("wswan")!!, "mednafen_wswan")
        val dreamcast = CoreRegistry.info(GamePlatforms.bySlug("dreamcast")!!, "flycast")
        val psp = CoreRegistry.info(GamePlatforms.bySlug("psp")!!, "ppsspp")
        val virtualBoy = CoreRegistry.info(GamePlatforms.bySlug("vb")!!, "mednafen_vb")

        assertTrue(wonderSwan.supportsStates)
        assertTrue(wonderSwan.supportsRomMSaveSync)
        assertTrue(dreamcast.supportsStates)
        assertFalse(dreamcast.supportsRomMSaveSync)
        assertTrue(psp.supportsStates)
        assertFalse(psp.supportsRomMSaveSync)
        assertTrue(virtualBoy.supportsStates)
        assertTrue(virtualBoy.supportsRomMSaveSync)
    }

    @Test
    fun coresDeclareVulkanAndSaveCapabilities() {
        val vita = CoreRegistry.info(GamePlatforms.bySlug("vita")!!, "vita3k")
        val ps3 = CoreRegistry.info(GamePlatforms.bySlug("ps3")!!, "rpcs3")
        val ps2 = CoreRegistry.info(GamePlatforms.bySlug("ps2")!!, "armsx2")
        assertTrue(vita.preferVulkan == true)
        assertTrue(ps3.preferVulkan == true)
        assertFalse(vita.supportsStates)
        assertFalse(ps3.supportsRomMSaveSync)
        assertTrue(ps2.supportsStates)
        assertFalse(ps2.supportsRomMSaveSync)
        val supermodel = CoreRegistry.info(GamePlatforms.bySlug("model3")!!, "supermodel")
        val xenia = CoreRegistry.info(GamePlatforms.bySlug("xbox360")!!, "xenia_edge")
        val pcsx2 = CoreRegistry.info(GamePlatforms.bySlug("ps2")!!, "pcsx2")
        val pcee2 = CoreRegistry.info(GamePlatforms.bySlug("ps2")!!, "pcee2")
        val dolphin = CoreRegistry.info(GamePlatforms.bySlug("gc")!!, "dolphin")
        assertTrue(supermodel.preferVulkan == false)
        assertTrue(xenia.preferVulkan == true)
        assertFalse(xenia.supportsStates)
        assertTrue(pcsx2.preferVulkan == true)
        assertTrue(pcee2.preferVulkan == true)
        assertTrue(dolphin.preferVulkan == true)
    }
    @Test
    fun pcEngineCoresDeclareSaveTransportByDocumentedFormat() {
        val platform = GamePlatforms.bySlug("pce")!!
        val fast = CoreRegistry.info(platform, "mednafen_pce_fast")
        val accurate = CoreRegistry.info(platform, "mednafen_pce")
        val geargrafx = CoreRegistry.info(platform, "geargrafx")

        assertTrue(fast.supportsStates)
        assertTrue(fast.supportsRomMSaveSync)
        assertTrue(accurate.supportsStates)
        assertFalse(accurate.supportsRomMSaveSync)
        assertTrue(geargrafx.supportsStates)
        assertTrue(geargrafx.supportsRomMSaveSync)
    }

    @Test
    fun n64CoresDeclareSaveTransportOnlyWhenDocumented() {
        val platform = GamePlatforms.bySlug("n64")!!
        val mupenGles2 = CoreRegistry.info(platform, "mupen64plus_next_gles2")
        val mupenGles3 = CoreRegistry.info(platform, "mupen64plus_next_gles3")
        val parallel = CoreRegistry.info(platform, "parallel_n64")

        assertTrue(mupenGles2.supportsStates)
        assertTrue(mupenGles2.supportsRomMSaveSync)
        assertTrue(mupenGles3.supportsStates)
        assertTrue(mupenGles3.supportsRomMSaveSync)
        assertTrue(parallel.supportsStates)
        assertFalse(parallel.supportsRomMSaveSync)
    }

    @Test
    fun atariCoresDoNotAdvertiseUnsupportedSaveRam() {
        val atari2600 = GamePlatforms.bySlug("atari2600")!!
        val stella = CoreRegistry.info(atari2600, "stella")
        val stella2014 = CoreRegistry.info(atari2600, "stella2014")
        assertTrue(stella.supportsStates)
        assertFalse(stella.supportsRomMSaveSync)
        assertTrue(stella2014.supportsStates)
        assertFalse(stella2014.supportsRomMSaveSync)

        val atari5200 = GamePlatforms.bySlug("atari5200")!!
        val atari800 = CoreRegistry.info(atari5200, "atari800")
        val a5200 = CoreRegistry.info(atari5200, "a5200")
        assertTrue(atari800.supportsStates)
        assertFalse(atari800.supportsRomMSaveSync)
        assertFalse(a5200.supportsStates)
        assertFalse(a5200.supportsRomMSaveSync)
    }

    @Test
    fun undocumentedBlastEmCapabilitiesRemainDisabled() {
        val platform = GamePlatforms.bySlug("genesis")!!
        val blastEm = CoreRegistry.info(platform, "blastem")

        assertFalse(blastEm.supportsStates)
        assertFalse(blastEm.supportsRomMSaveSync)
    }

    @Test
    fun playstationCoresUseLibretroMemoryCardFormatForPrimarySlot() {
        val platform = GamePlatforms.bySlug("psx")!!
        listOf("pcsx_rearmed", "swanstation", "mednafen_psx").forEach { coreId ->
            val info = CoreRegistry.info(platform, coreId)
            assertTrue("$coreId should support save states", info.supportsStates)
            assertTrue("$coreId primary memory card uses Libretro SRAM", info.supportsRomMSaveSync)
        }
    }

    @Test
    fun nintendoDsDefaultsToMaintainedMelonDsDsCore() {
        val platform = GamePlatforms.bySlug("nds")!!
        assertEquals("melonds_ds", platform.coreId)
        assertTrue("ids" in platform.extensions)
        val modern = CoreRegistry.info(platform, "melonds_ds")
        assertTrue(modern.supportsStates)
        assertTrue(modern.supportsRomMSaveSync)
        val legacy = CoreRegistry.info(platform, "melonds")
        assertFalse(legacy.supportsRomMSaveSync)
    }

    @Test
    fun nintendoDsCoresDeclareNativeSaveCompatibilityPerCoreFormat() {
        val platform = GamePlatforms.bySlug("nds")!!
        val melonDs = CoreRegistry.info(platform, "melonds")
        val desmume = CoreRegistry.info(platform, "desmume")
        val desmume2015 = CoreRegistry.info(platform, "desmume2015")
        val noods = CoreRegistry.info(platform, "noods")

        assertTrue(melonDs.supportsStates)
        assertFalse(melonDs.supportsRomMSaveSync)
        assertTrue(desmume.supportsStates)
        assertTrue(desmume.supportsRomMSaveSync)
        assertTrue(desmume2015.supportsStates)
        assertTrue(desmume2015.supportsRomMSaveSync)
        assertTrue(noods.supportsStates)
        assertFalse(noods.supportsRomMSaveSync)
    }

    @Test
    fun unknownCoreCapabilitiesDefaultToDisabledWithoutChangingCatalogEntries() {
        val platform = GamePlatforms.bySlug("nes")!!
        val unknownAlternative = CoreRegistry.info(platform, "unlisted_core")
        val unknownPlatform = CoreRegistry.cores(
            GamePlatform("unknown", "Unknown", "unknown_core", setOf("rom"), setOf("unknown"), 999),
        ).first()

        assertEquals("unlisted_core", unknownAlternative.id)
        assertFalse(unknownAlternative.supportsStates)
        assertFalse(unknownAlternative.supportsRomMSaveSync)
        assertEquals("unknown_core", unknownPlatform.id)
        assertFalse(unknownPlatform.supportsStates)
        assertFalse(unknownPlatform.supportsRomMSaveSync)

        val knownCore = CoreRegistry.info(platform, "fceumm")
        assertTrue(knownCore.supportsStates)
        assertTrue(knownCore.supportsRomMSaveSync)
    }

    @Test
    fun dolphinSaveFilesAreNotMisclassifiedAsRomMSramSync() {
        val gameCube = CoreRegistry.info(GamePlatforms.bySlug("gc")!!, "dolphin")
        val wii = CoreRegistry.info(GamePlatforms.bySlug("wii")!!, "dolphin")

        assertTrue(gameCube.supportsStates)
        assertFalse(gameCube.supportsRomMSaveSync)
        assertTrue(wii.supportsStates)
        assertFalse(wii.supportsRomMSaveSync)
    }

    @Test
    fun saturnCoresDeclareSaveTransportCompatibilityExplicitly() {
        val platform = GamePlatforms.bySlug("saturn")!!
        val beetle = CoreRegistry.info(platform, "mednafen_saturn")
        val yabasanshiro = CoreRegistry.info(platform, "yabasanshiro")
        val yabause = CoreRegistry.info(platform, "yabause")

        assertTrue(beetle.supportsStates)
        assertFalse(beetle.supportsRomMSaveSync)
        assertTrue(yabasanshiro.supportsStates)
        assertFalse(yabasanshiro.supportsRomMSaveSync)
        assertTrue(yabause.supportsStates)
        assertTrue(yabause.supportsRomMSaveSync)
    }

    @Test
    fun segaCdGenesisPlusGxBackupRamIsNotAdvertisedAsSramSync() {
        val platform = GamePlatforms.bySlug("scd")!!
        val genesisPlus = CoreRegistry.info(platform, "genesis_plus_gx")
        val picoDrive = CoreRegistry.info(platform, "picodrive")

        assertTrue(genesisPlus.supportsStates)
        assertFalse(genesisPlus.supportsRomMSaveSync)
        assertTrue(picoDrive.supportsStates)
        assertFalse(picoDrive.supportsRomMSaveSync)
    }


    @Test
    fun stageJAlternativeCoresRemainConservativeUntilIndividuallyAudited() {
        val alternatives = listOf(
            "ps2" to "pcee2",
            "snes" to "bsnes-jg",
            "gba" to "mednafen_gba",
            "gba" to "meteor",
            "genesis" to "genesis_plus_gx_wide",
            "atari2600" to "stella2023",
            "psx" to "mednafen_psx_hw",
            "dreamcast" to "parallel_dc",
            "pce" to "mednafen_supergrafx",
        )

        alternatives.forEach { (slug, coreId) ->
            val platform = GamePlatforms.bySlug(slug)!!
            val info = CoreRegistry.info(platform, coreId)
            assertEquals(coreId, info.id)
            assertFalse("$coreId must not advertise unaudited save states", info.supportsStates)
            assertFalse("$coreId must not advertise unaudited RomM sync", info.supportsRomMSaveSync)
        }
    }

    @Test
    fun stageJDoesNotReintroduceLegacyMelonDsDsAlias() {
        val platform = GamePlatforms.bySlug("nds")!!
        assertEquals("melonds_ds", platform.coreId)
        assertFalse(CoreRegistry.cores(platform).any { it.id == "melondsds" })
    }
}
