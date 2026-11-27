package app.lawnchair.gamemode

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.swordfish.libretrodroid.GLRetroView
import kotlin.math.hypot

/**
 * Touch-only controller overlay. It tracks the complete multitouch sequence and forwards
 * pointers that start outside controls to GLRetroView (including DS touchscreen input).
 */
internal class VirtualGamepadView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val retroView: GLRetroView? = null,
) : View(context, attrs),
    InputManager.InputDeviceListener {

    private enum class Control {
        DPAD,
        LEFT_STICK,
        RIGHT_STICK,
        A,
        B,
        X,
        Y,
        START,
        SELECT,
    }

    private data class Region(val x: Float, val y: Float, val radius: Float)

    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val pointerControls = mutableMapOf<Int, Control>()
    private val passthroughPointerIds = mutableSetOf<Int>()
    private val pressedKeys = mutableSetOf<Int>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val density = resources.displayMetrics.density
    private var dpadX = 0f
    private var dpadY = 0f
    private var leftX = 0f
    private var leftY = 0f
    private var rightX = 0f
    private var rightY = 0f
    private var registered = false
    private var menuOpen = false

    init {
        isClickable = true
        isFocusable = false
        updateControllerVisibility()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!registered) {
            inputManager.registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
            registered = true
        }
        updateControllerVisibility()
    }

    override fun onDetachedFromWindow() {
        releaseAll()
        if (registered) {
            inputManager.unregisterInputDeviceListener(this)
            registered = false
        }
        super.onDetachedFromWindow()
    }

    override fun onInputDeviceAdded(deviceId: Int) = updateControllerVisibility()
    override fun onInputDeviceRemoved(deviceId: Int) = updateControllerVisibility()
    override fun onInputDeviceChanged(deviceId: Int) = updateControllerVisibility()

    internal fun setMenuOpen(open: Boolean) {
        menuOpen = open
        updateControllerVisibility()
    }

    private fun updateControllerVisibility() {
        val physicalPadConnected = InputDevice.getDeviceIds().any { id ->
            val device = InputDevice.getDevice(id)
            device != null && (
                device.supportsSource(InputDevice.SOURCE_GAMEPAD) ||
                    device.supportsSource(InputDevice.SOURCE_JOYSTICK)
                )
        }
        val hideForPhysicalPad = GameModeSettings.isVirtualGamepadAutoHideEnabled(context) && physicalPadConnected
        val shouldShow = GameModeSettings.isVirtualGamepadEnabled(context) && !hideForPhysicalPad && !menuOpen
        val newVisibility = if (shouldShow) VISIBLE else GONE
        if (visibility != newVisibility) {
            if (newVisibility != VISIBLE) releaseAll()
            visibility = newVisibility
        }
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this && visibility != VISIBLE) releaseAll()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val alpha = (GameModeSettings.virtualGamepadOpacity(context) * 255f).toInt().coerceIn(40, 255)

        val dpad = region(Control.DPAD)
        drawDpad(canvas, dpad, alpha)
        drawStick(canvas, region(Control.LEFT_STICK), leftX, leftY, alpha)
        drawStick(canvas, region(Control.RIGHT_STICK), rightX, rightY, alpha)

        listOf(
            Control.Y to "Y",
            Control.B to "B",
            Control.A to "A",
            Control.X to "X",
        ).forEach { (control, label) ->
            val button = region(control)
            paint.color = when (control) {
                Control.A -> Color.rgb(190, 70, 70)
                Control.B -> Color.rgb(190, 150, 55)
                Control.X -> Color.rgb(65, 115, 185)
                else -> Color.rgb(70, 150, 95)
            }
            paint.alpha = alpha
            canvas.drawCircle(button.x, button.y, button.radius, paint)
            paint.color = Color.WHITE
            paint.alpha = alpha
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = dp(15f)
            paint.isFakeBoldText = true
            canvas.drawText(label, button.x, button.y + dp(5f), paint)
        }

        drawSystemButton(canvas, region(Control.SELECT), "SEL", alpha)
        drawSystemButton(canvas, region(Control.START), "START", alpha)
    }

    private fun drawDpad(canvas: Canvas, region: Region, alpha: Int) {
        val arm = region.radius * 0.78f
        val thickness = region.radius * 0.72f
        paint.color = Color.BLACK
        paint.alpha = (alpha * 0.72f).toInt()
        rect.set(region.x - arm, region.y - thickness / 2, region.x + arm, region.y + thickness / 2)
        canvas.drawRoundRect(rect, thickness / 3, thickness / 3, paint)
        rect.set(region.x - thickness / 2, region.y - arm, region.x + thickness / 2, region.y + arm)
        canvas.drawRoundRect(rect, thickness / 3, thickness / 3, paint)
        paint.color = Color.WHITE
        paint.alpha = alpha
        canvas.drawCircle(region.x, region.y, region.radius * 0.16f, paint)
        paint.alpha = 255
    }

    private fun drawStick(canvas: Canvas, region: Region, x: Float, y: Float, alpha: Int) {
        paint.color = Color.BLACK
        paint.alpha = (alpha * 0.58f).toInt()
        canvas.drawCircle(region.x, region.y, region.radius, paint)
        paint.color = Color.LTGRAY
        paint.alpha = alpha
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(2f)
        canvas.drawCircle(region.x, region.y, region.radius - dp(2f), paint)
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        paint.alpha = alpha
        canvas.drawCircle(
            region.x + x * region.radius * 0.72f,
            region.y + y * region.radius * 0.72f,
            region.radius * 0.34f,
            paint,
        )
        paint.alpha = 255
    }

    private fun drawSystemButton(canvas: Canvas, region: Region, label: String, alpha: Int) {
        paint.color = Color.DKGRAY
        paint.alpha = (alpha * 0.85f).toInt()
        rect.set(region.x - region.radius * 1.35f, region.y - region.radius * 0.58f, region.x + region.radius * 1.35f, region.y + region.radius * 0.58f)
        canvas.drawRoundRect(rect, region.radius, region.radius, paint)
        paint.color = Color.WHITE
        paint.alpha = alpha
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = dp(9f)
        paint.isFakeBoldText = true
        canvas.drawText(label, region.x, region.y + dp(3f), paint)
        paint.alpha = 255
    }

    private fun region(control: Control): Region {
        val portrait = height > width
        val scale = GameModeSettings.virtualGamepadScale(context)
        val offsetX = width * GameModeSettings.virtualGamepadOffsetX(context)
        val offsetY = height * GameModeSettings.virtualGamepadOffsetY(context)
        val y = height * if (portrait) 0.82f else 0.78f
        val stickRadius = dp(if (portrait) 31f else 36f) * scale
        val dpadRadius = dp(if (portrait) 31f else 36f) * scale
        val buttonRadius = dp(if (portrait) 13f else 15f) * scale
        return when (control) {
            Control.DPAD -> Region(width * 0.105f + offsetX, y + offsetY, dpadRadius)
            Control.LEFT_STICK -> Region(width * 0.325f + offsetX, y + offsetY, stickRadius)
            Control.RIGHT_STICK -> Region(width * 0.675f + offsetX, y + offsetY, stickRadius)
            Control.A -> Region(width * 0.90f + offsetX, y + buttonRadius * 1.55f + offsetY, buttonRadius)
            Control.B -> Region(width * 0.90f + buttonRadius * 1.55f + offsetX, y + offsetY, buttonRadius)
            Control.X -> Region(width * 0.90f - buttonRadius * 1.55f + offsetX, y + offsetY, buttonRadius)
            Control.Y -> Region(width * 0.90f + offsetX, y - buttonRadius * 1.55f + offsetY, buttonRadius)
            Control.SELECT -> Region(width * 0.455f + offsetX, height * 0.95f + offsetY, dp(17f) * scale)
            Control.START -> Region(width * 0.545f + offsetX, height * 0.95f + offsetY, dp(17f) * scale)
        }
    }

    private fun hitControl(x: Float, y: Float): Control? {
        val controls = listOf(
            Control.DPAD,
            Control.LEFT_STICK,
            Control.RIGHT_STICK,
            Control.Y,
            Control.B,
            Control.A,
            Control.X,
            Control.SELECT,
            Control.START,
        )
        return controls.firstOrNull { control ->
            val area = region(control)
            val dx = x - area.x
            val dy = y - area.y
            hypot(dx, dy) <= area.radius * if (control == Control.DPAD) 1.18f else 1.25f
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                val control = hitControl(event.getX(index), event.getY(index))
                val pointerId = event.getPointerId(index)
                if (control == null) {
                    // Own the first pointer even outside controls so later pointers can
                    // activate virtual buttons while the original touch is forwarded to DS.
                    passthroughPointerIds += pointerId
                    forwardPassthroughTouch(event)
                    return true
                }
                if (pointerControls.values.contains(control)) return true
                pointerControls[pointerId] = control
                updateControl(control, event.getX(index), event.getY(index), pressed = true)
                forwardPassthroughTouch(event)
            }

            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val control = pointerControls[event.getPointerId(index)] ?: continue
                    updateControl(control, event.getX(index), event.getY(index), pressed = true)
                }
                forwardPassthroughTouch(event)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                val pointerId = event.getPointerId(index)
                forwardPassthroughTouch(event)
                passthroughPointerIds.remove(pointerId)
                pointerControls.remove(pointerId)?.let { releaseControl(it) }
            }

            MotionEvent.ACTION_CANCEL -> {
                forwardPassthroughTouch(event)
                passthroughPointerIds.clear()
                releaseAll()
            }
        }
        invalidate()
        return pointerControls.isNotEmpty() || passthroughPointerIds.isNotEmpty() ||
            event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_POINTER_UP ||
            event.actionMasked == MotionEvent.ACTION_CANCEL
    }

    /**
     * A View overlay receives the entire multitouch stream once a virtual control owns the first
     * pointer. Forward only pointers that began outside controls so DS touchscreen input remains usable.
     */
    private fun forwardPassthroughTouch(event: MotionEvent) {
        if (passthroughPointerIds.isEmpty()) return
        val pointerIdBits = passthroughPointerIds.fold(0) { bits, pointerId -> bits or (1 shl pointerId) }
        val forwarded = event.split(pointerIdBits)
        try {
            retroView?.dispatchTouchEvent(forwarded)
        } finally {
            forwarded.recycle()
        }
    }

    private fun updateControl(control: Control, x: Float, y: Float, pressed: Boolean) {
        val area = region(control)
        when (control) {
            Control.DPAD -> {
                val axis = VirtualGamepadMath.normalizeStick(x - area.x, y - area.y, area.radius, 0.22f)
                dpadX = VirtualGamepadMath.dpadAxis(axis.x)
                dpadY = VirtualGamepadMath.dpadAxis(axis.y)
                retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, dpadX, dpadY)
            }

            Control.LEFT_STICK, Control.RIGHT_STICK -> {
                val axis = VirtualGamepadMath.normalizeStick(x - area.x, y - area.y, area.radius)
                if (control == Control.LEFT_STICK) {
                    leftX = axis.x
                    leftY = axis.y
                    retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, leftX, leftY)
                } else {
                    rightX = axis.x
                    rightY = axis.y
                    retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, rightX, rightY)
                }
            }

            else -> if (pressed) pressKey(keyCode(control))
        }
    }

    private fun keyCode(control: Control): Int = when (control) {
        Control.A -> KeyEvent.KEYCODE_BUTTON_A
        Control.B -> KeyEvent.KEYCODE_BUTTON_B
        Control.X -> KeyEvent.KEYCODE_BUTTON_X
        Control.Y -> KeyEvent.KEYCODE_BUTTON_Y
        Control.START -> KeyEvent.KEYCODE_BUTTON_START
        Control.SELECT -> KeyEvent.KEYCODE_BUTTON_SELECT
        else -> error("Controle sem código de botão: $control")
    }

    private fun pressKey(keyCode: Int) {
        if (pressedKeys.add(keyCode)) retroView?.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode)
    }

    private fun releaseKey(keyCode: Int) {
        if (pressedKeys.remove(keyCode)) retroView?.sendKeyEvent(KeyEvent.ACTION_UP, keyCode)
    }

    private fun releaseControl(control: Control) {
        when (control) {
            Control.DPAD -> {
                dpadX = 0f
                dpadY = 0f
                retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_DPAD, 0f, 0f)
            }

            Control.LEFT_STICK -> {
                leftX = 0f
                leftY = 0f
                retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_LEFT, 0f, 0f)
            }

            Control.RIGHT_STICK -> {
                rightX = 0f
                rightY = 0f
                retroView?.sendMotionEvent(GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, 0f, 0f)
            }

            else -> releaseKey(keyCode(control))
        }
    }

    internal fun releaseAll() {
        pointerControls.values.toSet().forEach(::releaseControl)
        pointerControls.clear()
        passthroughPointerIds.clear()
        pressedKeys.toList().forEach(::releaseKey)
        dpadX = 0f
        dpadY = 0f
        leftX = 0f
        leftY = 0f
        rightX = 0f
        rightY = 0f
        invalidate()
    }

    private fun dp(value: Float): Float = value * density
}
