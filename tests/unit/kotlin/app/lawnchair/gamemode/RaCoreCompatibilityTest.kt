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
        val psx = platform("psx", 12)

        assertTrue(RaCoreCompatibility.isSupported(psx, "mednafen_psx"))
        assertTrue(RaCoreCompatibility.isSupported(psx, "mednafen_psx_hw"))
        assertTrue(RaCoreCompatibility.isSupported(psx, "swanstation"))
        assertFalse(RaCoreCompatibility.isSupported(psx, "pcsx_rearmed"))
    }

    @Test
    fun nintendoDsAllowsSupportedCoresAndRejectsNooDS() {
        val nds = platform("nds", 18)

        assertTrue(RaCoreCompatibility.isSupported(nds, "melonds_ds"))
        assertTrue(RaCoreCompatibility.isSupported(nds, "melonds"))
        assertTrue(RaCoreCompatibility.isSupported(nds, "desmume"))
        assertFalse(RaCoreCompatibility.isSupported(nds, "noods"))
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
        val dsi = platform("dsi", 78)

        assertTrue(RaCoreCompatibility.isSupported(dsi, "melonds_ds"))
        assertFalse(RaCoreCompatibility.isSupported(dsi, "melonds"))
        assertFalse(RaCoreCompatibility.isSupported(dsi, "desmume"))
    }

    @Test
    fun pc88AndSuperGrafxUseOfficiallyListedCores() {
        val pc88 = platform("pc88", 47)
        val supergrafx = platform("supergrafx", 8)

        assertTrue(RaCoreCompatibility.isSupported(pc88, "quasi88"))
        assertTrue(RaCoreCompatibility.isSupported(supergrafx, "mednafen_supergrafx"))
        assertFalse(RaCoreCompatibility.isSupported(supergrafx, "mednafen_pce_fast"))
    }

    @Test
    fun ambiguousNdsExtensionKeepsNintendoDsAsSafeFallback() {
        assertTrue(GamePlatforms.resolve(emptyList(), "nds")?.slug == "nds")
        assertTrue(GamePlatforms.resolve(listOf("dsi"), "nds")?.slug == "dsi")
    }

    @Test
    fun knownPlatformDoesNotEnableRaForAnUnlistedCore() {
        val nes = platform("nes", 7)

        assertTrue(RaCoreCompatibility.isSupported(nes, "fceumm"))
        assertTrue(RaCoreCompatibility.isSupported(nes, "mesen"))
        assertFalse(RaCoreCompatibility.isSupported(nes, "nestopia"))
    }

    @Test
    fun platformWithoutConsoleIdNeverEnablesRa() {
        val psxWithoutId = platform("psx", null)

        assertFalse(RaCoreCompatibility.isSupported(psxWithoutId, "mednafen_psx"))
    }
}
