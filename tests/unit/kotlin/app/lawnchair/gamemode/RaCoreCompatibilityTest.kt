package app.lawnchair.gamemode

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaCoreCompatibilityTest {

    private fun platform(slug: String, consoleId: Int?) = GamePlatform(
        slug = slug,
        name = slug,
        coreId = "",
        extensions = emptySet(),
        folderAliases = emptySet(),
        sortOrder = 0,
        raConsoleId = consoleId,
    )

    @Test
    fun playStationAllowsOnlyOfficialLibretroCores() {
        val psx = GamePlatforms.bySlug("psx")!!
        assertTrue(psx.raConsoleId == 12)

        assertTrue(RaCoreCompatibility.isSupported(psx, "mednafen_psx"))
        assertTrue(RaCoreCompatibility.isSupported(psx, "mednafen_psx_hw"))
        assertTrue(RaCoreCompatibility.isSupported(psx, "swanstation"))
        assertFalse(RaCoreCompatibility.isSupported(psx, "pcsx_rearmed"))
    }

    @Test
    fun nintendoDsAllowsSupportedCoresAndRejectsNooDS() {
        val nds = GamePlatforms.bySlug("nds")!!
        assertTrue(nds.raConsoleId == 18)

        assertTrue(RaCoreCompatibility.isSupported(nds, "melonds_ds"))
        assertTrue(RaCoreCompatibility.isSupported(nds, "melonds"))
        assertTrue(RaCoreCompatibility.isSupported(nds, "desmume"))
        assertFalse(RaCoreCompatibility.isSupported(nds, "noods"))
    }

    @Test
    fun snesAllowsMesenSAndSnes9x() {
        val snes = GamePlatforms.bySlug("snes")!!
        assertTrue(snes.raConsoleId == 3)
        assertTrue(RaCoreCompatibility.isSupported(snes, "snes9x"))
        assertTrue(RaCoreCompatibility.isSupported(snes, "mesen-s"))
    }

    @Test
    fun jaguarKeepsItsConsoleIdButDoesNotEnableUnavailableAndroidCore() {
        val jaguar = GamePlatforms.bySlug("jaguar")!!
        assertTrue(jaguar.raConsoleId == 17)
        assertFalse(RaCoreCompatibility.isSupported(jaguar, "virtualjaguar"))
    }

    @Test
    fun famicomDiskSystemUsesItsDedicatedConsoleAndMesenCore() {
        val fds = GamePlatforms.bySlug("fds")!!

        assertTrue(fds.raConsoleId == 81)
        assertTrue(RaCoreCompatibility.isSupported(fds, "mesen"))
        assertFalse(RaCoreCompatibility.isSupported(fds, "fceumm"))
    }

    @Test
    fun nintendoDsiUsesItsOwnConsoleAndOnlyMelonDsDs() {
        val dsi = GamePlatforms.bySlug("dsi")!!
        assertTrue(dsi.raConsoleId == 78)

        assertTrue(RaCoreCompatibility.isSupported(dsi, "melonds_ds"))
        assertFalse(RaCoreCompatibility.isSupported(dsi, "melonds"))
        assertFalse(RaCoreCompatibility.isSupported(dsi, "desmume"))
    }

    @Test
    fun pc88AndSuperGrafxUseOfficiallyListedCores() {
        val pc88 = GamePlatforms.bySlug("pc88")!!
        val supergrafx = GamePlatforms.bySlug("supergrafx")!!
        assertTrue(pc88.raConsoleId == 47)
        assertTrue(supergrafx.raConsoleId == 8)

        assertTrue(RaCoreCompatibility.isSupported(pc88, "quasi88"))
        assertTrue(RaCoreCompatibility.isSupported(supergrafx, "mednafen_supergrafx"))
        assertFalse(RaCoreCompatibility.isSupported(supergrafx, "mednafen_pce_fast"))
        assertTrue(RaCoreCompatibility.isSupported(GamePlatforms.bySlug("pce")!!, "mednafen_supergrafx"))
    }

    @Test
    fun additionalOfficialSystemsUseTheirOwnPlatformsAndCores() {
        val pokemonMini = GamePlatforms.bySlug("pokemini")!!
        val amstrad = GamePlatforms.bySlug("amstradcpc")!!
        val supervision = GamePlatforms.bySlug("supervision")!!

        assertTrue(pokemonMini.raConsoleId == 24)
        assertTrue(RaCoreCompatibility.isSupported(pokemonMini, "pokemini"))
        assertTrue(amstrad.raConsoleId == 37)
        assertTrue(RaCoreCompatibility.isSupported(amstrad, "cap32"))
        assertTrue(supervision.raConsoleId == 63)
        assertTrue(RaCoreCompatibility.isSupported(supervision, "potator"))
        assertFalse(RaCoreCompatibility.isSupported(GamePlatforms.bySlug("megaduck")!!, "potator"))
    }

    @Test
    fun ambiguousNdsExtensionKeepsNintendoDsAsSafeFallback() {
        assertTrue(GamePlatforms.resolve(emptyList(), "nds")?.slug == "nds")
        assertTrue(GamePlatforms.resolve(emptyList(), "ids")?.slug == "nds")
        assertTrue(GamePlatforms.resolve(listOf("dsi"), "nds")?.slug == "dsi")
        assertTrue(GamePlatforms.resolve(listOf("dsi"), "ids")?.slug == "dsi")
    }

    @Test
    fun masterSystemRejectsUnlistedSmsPlusCore() {
        val masterSystem = GamePlatforms.bySlug("sms")!!
        assertTrue(masterSystem.raConsoleId == 11)

        assertTrue(RaCoreCompatibility.isSupported(masterSystem, "genesis_plus_gx"))
        assertTrue(RaCoreCompatibility.isSupported(masterSystem, "gearsystem"))
        assertFalse(RaCoreCompatibility.isSupported(masterSystem, "smsplus"))
    }

    @Test
    fun nintendo64AllowsTheOfficialParallelCore() {
        val n64 = GamePlatforms.bySlug("n64")!!
        assertTrue(n64.raConsoleId == 2)
        assertTrue(RaCoreCompatibility.isSupported(n64, "parallel_n64"))
    }

    @Test
    fun knownPlatformDoesNotEnableRaForAnUnlistedCore() {
        val nes = GamePlatforms.bySlug("nes")!!
        assertTrue(nes.raConsoleId == 7)

        assertTrue(RaCoreCompatibility.isSupported(nes, "fceumm"))
        assertTrue(RaCoreCompatibility.isSupported(nes, "mesen"))
        assertFalse(RaCoreCompatibility.isSupported(nes, "nestopia"))
    }

    @Test
    fun everyAllowedRaCoreIsAvailableInItsPlatformCatalog() {
        val raPlatforms = GamePlatforms.all.filter { it.raConsoleId != null }

        assertTrue("No RA-enabled platforms found", raPlatforms.isNotEmpty())
        raPlatforms.forEach { platform ->
            val selectableCoreIds = CoreRegistry.cores(platform).map { it.id }.toSet()
            val allowedCoreIds = RaCoreCompatibility.supportedCoreIds(platform.slug)

            assertTrue("No RA cores configured for ${platform.slug}", allowedCoreIds.isNotEmpty())
            allowedCoreIds.forEach { coreId ->
                assertTrue(
                    "RA allows $coreId for ${platform.slug}, but CoreRegistry does not offer it",
                    coreId in selectableCoreIds,
                )
            }
        }
    }

    @Test
    fun platformWithoutConsoleIdNeverEnablesRa() {
        val psxWithoutId = platform("psx", null)

        assertFalse(RaCoreCompatibility.isSupported(psxWithoutId, "mednafen_psx"))
    }
}
