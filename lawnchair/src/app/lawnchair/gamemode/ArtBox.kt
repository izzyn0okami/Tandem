package app.lawnchair.gamemode

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.ColorUtils

/**
 * Capa gerada para jogos sem arte: fundo na cor do console, faixa com o nome do console, o ícone
 * do app (jogos Android) ou as iniciais do jogo, e o título embaixo. Tudo desenhado no aparelho.
 */
object ArtBox {

    const val WIDTH = 240
    const val HEIGHT = 320

    private const val BAND_HEIGHT = 46f
    private const val ICON_SIZE = 128
    private const val ANDROID_GREEN = 0xFF3DDC84.toInt()

    private class Theme(val color: Int, val label: String)

    private val themes = mapOf(
        "nes" to Theme(0xFFB3261E.toInt(), "NES"),
        "snes" to Theme(0xFF5B4B8A.toInt(), "SUPER NINTENDO"),
        "gb" to Theme(0xFF5E7A3A.toInt(), "GAME BOY"),
        "gbc" to Theme(0xFF2E7D8C.toInt(), "GAME BOY COLOR"),
        "gba" to Theme(0xFF3F4FA8.toInt(), "GAME BOY ADVANCE"),
        "genesis" to Theme(0xFF1B2A49.toInt(), "MEGA DRIVE"),
        "sms" to Theme(0xFF8E1B1B.toInt(), "MASTER SYSTEM"),
        "gg" to Theme(0xFF2B2B2B.toInt(), "GAME GEAR"),
        "32x" to Theme(0xFF3B3B6B.toInt(), "SEGA 32X"),
        "scd" to Theme(0xFF24406B.toInt(), "MEGA-CD"),
        "n64" to Theme(0xFF2E7D32.toInt(), "NINTENDO 64"),
        "psx" to Theme(0xFF4A4F5C.toInt(), "PLAYSTATION"),
        "pce" to Theme(0xFFD2691E.toInt(), "PC ENGINE"),
        "atari2600" to Theme(0xFF8B4A1F.toInt(), "ATARI 2600"),
        "atari5200" to Theme(0xFF6B3B2B.toInt(), "ATARI 5200"),
        "atari7800" to Theme(0xFF7A3B1D.toInt(), "ATARI 7800"),
        "lynx" to Theme(0xFFA35A00.toInt(), "ATARI LYNX"),
        "jaguar" to Theme(0xFF8B1E1E.toInt(), "ATARI JAGUAR"),
        "ngp" to Theme(0xFF8A2B6B.toInt(), "NEO GEO POCKET"),
        "wswan" to Theme(0xFF1F5F8B.toInt(), "WONDERSWAN"),
        "sg1000" to Theme(0xFF7A1F3D.toInt(), "SG-1000"),
        "saturn" to Theme(0xFF2F3E5C.toInt(), "SATURN"),
        "dreamcast" to Theme(0xFFE65100.toInt(), "DREAMCAST"),
        "nds" to Theme(0xFF5A5F6B.toInt(), "NINTENDO DS"),
        "psp" to Theme(0xFF1F3A5F.toInt(), "PSP"),
        "3do" to Theme(0xFF6B4E16.toInt(), "3DO"),
        "vb" to Theme(0xFFB0171F.toInt(), "VIRTUAL BOY"),
        "coleco" to Theme(0xFF1F4E79.toInt(), "COLECOVISION"),
        "intellivision" to Theme(0xFF8A1C1C.toInt(), "INTELLIVISION"),
        "ps2" to Theme(0xFF1E3A8A.toInt(), "PLAYSTATION 2"),
        "gc" to Theme(0xFF4B3F8C.toInt(), "GAMECUBE"),
        "wii" to Theme(0xFF5F8FA8.toInt(), "WII"),
        "switch" to Theme(0xFFD32F2F.toInt(), "SWITCH"),
        "3ds" to Theme(0xFFB71C1C.toInt(), "NINTENDO 3DS"),
        "wiiu" to Theme(0xFF1976D2.toInt(), "WII U"),
        "pc" to Theme(0xFF1B2838.toInt(), "PC"),
    )

    /** Capa de um jogo de console: cor e nome do console, iniciais do jogo e título. */
    fun forPlatform(title: String, platform: GamePlatform): ImageBitmap {
        val theme = themes[platform.slug]
        val color = theme?.color ?: Color.HSVToColor(floatArrayOf((platform.slug.hashCode() and 0x7FFFFFFF) % 360f, 0.45f, 0.5f))
        return draw(title, color, theme?.label ?: platform.name.uppercase(), null, Color.WHITE)
    }

    /** Capa de um jogo Android: cor tirada do ícone, ícone grande e faixa verde. */
    fun forAndroid(title: String, icon: Bitmap?): ImageBitmap {
        val color = icon?.let { dominantColor(it) } ?: 0xFF1B3A2A.toInt()
        return draw(title, color, "ANDROID", icon, ANDROID_GREEN)
    }

    private fun dominantColor(icon: Bitmap): Int {
        val pixel = Bitmap.createScaledBitmap(icon, 1, 1, true).getPixel(0, 0)
        val hsv = FloatArray(3)
        Color.colorToHSV(pixel or 0xFF000000.toInt(), hsv)
        hsv[1] = hsv[1].coerceIn(0.35f, 0.9f)
        hsv[2] = hsv[2].coerceIn(0.35f, 0.55f)
        return Color.HSVToColor(hsv)
    }

    private fun initials(title: String): String {
        val words = title.split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        return words.take(2).joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    }

    @Suppress("LongMethod")
    private fun draw(title: String, base: Int, label: String, icon: Bitmap?, accent: Int): ImageBitmap {
        val width = WIDTH.toFloat()
        val height = HEIGHT.toFloat()
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height,
            ColorUtils.blendARGB(base, Color.WHITE, 0.22f),
            ColorUtils.blendARGB(base, Color.BLACK, 0.5f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null

        paint.color = Color.argb(28, 255, 255, 255)
        canvas.drawCircle(width * 0.85f, height * 0.28f, width * 0.55f, paint)
        paint.color = Color.argb(24, 0, 0, 0)
        canvas.drawCircle(width * 0.1f, height * 0.78f, width * 0.6f, paint)

        paint.color = Color.argb(150, 0, 0, 0)
        canvas.drawRect(0f, 0f, width, BAND_HEIGHT, paint)
        paint.color = accent
        canvas.drawRect(0f, BAND_HEIGHT, width, BAND_HEIGHT + 4f, paint)

        val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
            color = Color.WHITE
            textSize = 22f
        }
        while (text.measureText(label) > width - 24f && text.textSize > 10f) text.textSize -= 1f
        canvas.drawText(label, width / 2f, BAND_HEIGHT / 2f + text.textSize * 0.35f, text)

        val centerY = 160f
        if (icon != null) {
            val half = ICON_SIZE / 2f
            val target = RectF(width / 2f - half, centerY - half, width / 2f + half, centerY + half)
            paint.color = Color.argb(70, 0, 0, 0)
            canvas.drawRoundRect(RectF(target).apply { offset(0f, 6f) }, 28f, 28f, paint)
            canvas.drawBitmap(icon, null, target, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            text.textSize = 104f
            text.color = Color.argb(235, 255, 255, 255)
            canvas.drawText(initials(title), width / 2f, centerY + 36f, text)
        }

        paint.shader = LinearGradient(0f, height - 120f, 0f, height, Color.TRANSPARENT, Color.argb(215, 0, 0, 0), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, height - 120f, width, height, paint)
        paint.shader = null

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = Color.WHITE
            textSize = 20f
        }
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, (width - 28f).toInt())
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .build()
        canvas.save()
        canvas.translate(14f, height - 12f - layout.height)
        layout.draw(canvas)
        canvas.restore()
        return bitmap.asImageBitmap()
    }
}
