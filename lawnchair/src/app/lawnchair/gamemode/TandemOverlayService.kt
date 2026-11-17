package app.lawnchair.gamemode

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.android.launcher3.R

/**
 * Atalho global do menu do Tandem: o MESMO atalho de controle que abre o menu de pausa dentro do
 * Tandem (Select + Start) também abre, por cima de qualquer outro app (emulador externo, jogo
 * Android), uma janelinha com atalhos rápidos. Não é uma bolha flutuante visível o tempo todo —
 * só aparece quando o atalho é pressionado, e some ao escolher uma opção ou apertar de novo.
 *
 * Precisa de um serviço de acessibilidade (é o jeito do Android de ouvir botões do controle
 * mesmo com outro app em primeiro plano) e o usuário precisa ativá-lo manualmente nos Ajustes,
 * já que essa permissão não pode ser concedida sozinha pelo app.
 */
class TandemOverlayService : AccessibilityService() {

    private var foregroundPackage: String? = null
    private var selectDown = false
    private var startDown = false
    private var overlayView: View? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        serviceInfo = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            notificationTimeout = 0
        }
        GameLog.log("menu global", "serviço de acessibilidade ligado")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            foregroundPackage = event.packageName?.toString()
            // Trocou de app: o atalho fica sem efeito até ser solto e apertado de novo.
            if (overlayView != null) hideOverlay()
        }
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Dentro do próprio Tandem o atalho já é tratado pela Activity; aqui só cobre outros apps.
        if (foregroundPackage == packageName) return super.onKeyEvent(event)
        val isDown = event.action == KeyEvent.ACTION_DOWN
        when (event.keyCode) {
            KeyEvent.KEYCODE_BUTTON_SELECT -> selectDown = isDown || (event.action != KeyEvent.ACTION_UP && selectDown)
            KeyEvent.KEYCODE_BUTTON_START -> startDown = isDown || (event.action != KeyEvent.ACTION_UP && startDown)
            else -> return super.onKeyEvent(event)
        }
        if (isDown && event.repeatCount == 0 && selectDown && startDown) {
            if (overlayView != null) hideOverlay() else showOverlay()
            return true
        }
        return super.onKeyEvent(event)
    }

    private fun showOverlay() {
        if (overlayView != null) return
        val windowManager = getSystemService(WindowManager::class.java) ?: return
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#F00B1017"))
                cornerRadius = 24f
            }
        }
        panel.addView(
            TextView(this).apply {
                text = getString(R.string.game_overlay_title)
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(0, 0, 0, 16)
            },
        )
        panel.addView(
            actionButton(getString(R.string.game_overlay_open_tandem)) {
                hideOverlay()
                startActivity(GameModeActivity.intent(this).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
        )
        panel.addView(
            actionButton(getString(R.string.game_overlay_go_home)) {
                hideOverlay()
                performGlobalAction(GLOBAL_ACTION_HOME)
            },
        )
        panel.addView(actionButton(getString(R.string.game_overlay_close)) { hideOverlay() })

        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        runCatching { windowManager.addView(panel, params) }.onFailure {
            GameLog.error("menu global", "não consegui mostrar a janela por cima", it)
            return
        }
        overlayView = panel
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
        overlayView = null
        runCatching { getSystemService(WindowManager::class.java)?.removeView(view) }
    }

    private fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#1A9FFF"))
            cornerRadius = 14f
        }
        setOnClickListener { onClick() }
        val margin = 8
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(0, margin, 0, margin)
        }
    }
}
