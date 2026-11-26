package app.lawnchair.gamemode

import kotlin.math.hypot

internal object VirtualGamepadMath {
    data class Axis(val x: Float, val y: Float)

    fun normalizeStick(
        dx: Float,
        dy: Float,
        radius: Float,
        deadzone: Float = 0.14f,
    ): Axis {
        if (radius <= 0f) return Axis(0f, 0f)
        val rawX = dx / radius
        val rawY = dy / radius
        val magnitude = hypot(rawX, rawY)
        if (magnitude <= deadzone) return Axis(0f, 0f)

        val clampedMagnitude = magnitude.coerceAtMost(1f)
        val scaledMagnitude = ((clampedMagnitude - deadzone) / (1f - deadzone)).coerceIn(0f, 1f)
        val scale = scaledMagnitude / magnitude
        return Axis(
            x = (rawX * scale).coerceIn(-1f, 1f),
            y = (rawY * scale).coerceIn(-1f, 1f),
        )
    }

    fun dpadAxis(distance: Float, threshold: Float = 0.32f): Float = when {
        distance >= threshold -> 1f
        distance <= -threshold -> -1f
        else -> 0f
    }
}
