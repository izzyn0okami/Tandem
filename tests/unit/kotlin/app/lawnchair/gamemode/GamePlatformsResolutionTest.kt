package app.lawnchair.gamemode

import org.junit.Assert.assertEquals
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
    fun newlySupportedAdvancedSystemsResolveToLibretroCores() {
        assertEquals("pcee2", GamePlatforms.resolve(listOf("ps2"), "iso")?.coreId)
        assertEquals("azahar", GamePlatforms.resolve(listOf("3ds"), "3ds")?.coreId)
        assertEquals("vita3k", GamePlatforms.resolve(listOf("vita"), "vpk")?.coreId)
        assertEquals("rpcs3", GamePlatforms.resolve(listOf("ps3"), "sfb")?.coreId)
        assertEquals("xenia_edge", GamePlatforms.resolve(listOf("xbox360"), "xex")?.coreId)
    }

    @Test
    fun newRetroComputerAndArcadeSystemsResolve() {
        assertEquals("atari800", GamePlatforms.resolve(listOf("atari8"), "atr")?.coreId)
        assertEquals("hatari", GamePlatforms.resolve(listOf("atarist"), "st")?.coreId)
        assertEquals("puae", GamePlatforms.resolve(listOf("amiga"), "adf")?.coreId)
        assertEquals("vice_x64", GamePlatforms.resolve(listOf("c64"), "d64")?.coreId)
        assertEquals("fuse", GamePlatforms.resolve(listOf("zxspectrum"), "z80")?.coreId)
        assertEquals("scummvm", GamePlatforms.resolve(listOf("scummvm"), "scummvm")?.coreId)
        assertEquals("fbneo", GamePlatforms.resolve(listOf("arcade"), "zip")?.coreId)
    }

}
