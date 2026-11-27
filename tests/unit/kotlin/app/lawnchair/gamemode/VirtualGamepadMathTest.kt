package app.lawnchair.gamemode

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualGamepadMathTest {
    @Test
    fun stickInsideDeadzoneReturnsNeutralAxes() {
        assertEquals(VirtualGamepadMath.Axis(0f, 0f), VirtualGamepadMath.normalizeStick(3f, 2f, 100f))
    }

    @Test
    fun stickReachesFullScaleAtOuterRadius() {
        val result = VirtualGamepadMath.normalizeStick(100f, 0f, 100f)
        assertEquals(1f, result.x, 0.0001f)
        assertEquals(0f, result.y, 0.0001f)
    }

    @Test
    fun stickClampsDiagonalOutsideRadius() {
        val result = VirtualGamepadMath.normalizeStick(100f, 100f, 100f)
        assertEquals(1f, kotlin.math.hypot(result.x, result.y), 0.0001f)
    }

    @Test
    fun invalidRadiusReturnsNeutralAxes() {
        assertEquals(VirtualGamepadMath.Axis(0f, 0f), VirtualGamepadMath.normalizeStick(10f, 10f, 0f))
    }

    @Test
    fun dpadUsesDigitalThreshold() {
        assertEquals(-1f, VirtualGamepadMath.dpadAxis(-0.8f))
        assertEquals(0f, VirtualGamepadMath.dpadAxis(0.1f))
        assertEquals(1f, VirtualGamepadMath.dpadAxis(0.8f))
    }
}
