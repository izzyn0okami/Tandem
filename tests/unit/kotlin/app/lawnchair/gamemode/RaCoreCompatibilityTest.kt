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
