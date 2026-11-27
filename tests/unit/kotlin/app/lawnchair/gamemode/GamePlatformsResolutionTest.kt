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
        assertEquals(GamePlatforms.bySlug("vita"), GamePlatforms.resolve(listOf("psvita"), "vpk"))
        assertEquals(GamePlatforms.bySlug("ps3"), GamePlatforms.resolve(listOf("ps3"), "pkg"))
    }

    @Test
    fun coresDeclareVulkanAndSaveCapabilities() {
        val vita = CoreRegistry.info(GamePlatforms.bySlug("vita")!!, "vita3k")
        val ps3 = CoreRegistry.info(GamePlatforms.bySlug("ps3")!!, "rpcs3")
        val ps2 = CoreRegistry.info(GamePlatforms.bySlug("ps2")!!, "armsx2")
        assertTrue(vita.preferVulkan)
        assertTrue(ps3.preferVulkan)
        assertFalse(vita.supportsStates)
        assertFalse(ps3.supportsRomMSaveSync)
        assertTrue(ps2.supportsStates)
        assertFalse(ps2.supportsRomMSaveSync)
    }
}
