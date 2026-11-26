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
}
