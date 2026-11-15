package app.lawnchair.gamemode

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Paleta do modo jogos (estilo SteamOS): fundo azul-escuro, destaque azul. */
object GameColors {
    val background = Color(0xFF0B1017)
    val backgroundTop = Color(0xFF16212E)
    val surface = Color(0xFF16212E)
    val surfaceHigh = Color(0xFF223244)
    val accent = Color(0xFF1A9FFF)
    val onAccent = Color(0xFF04121F)
    val text = Color(0xFFE8EEF5)
    val textDim = Color(0xFF9AA8B8)
    val danger = Color(0xFFFF9B9B)
    val success = Color(0xFF7FD1A0)
    val panel = Color(0xEB0B1017)
    val scrim = Color(0x99000000)
    val bar = Color(0x59000000)
}

private const val FOCUS_SOUND_DELAY_MS = 400L

/** Elemento que recebe foco do controle: destaca com borda azul e responde ao botão A. */
@Composable
fun FocusSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(10.dp),
    background: Color = GameColors.surface,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val created = remember { System.currentTimeMillis() }
    val scale by animateFloatAsState(if (focused) 1.04f else 1f, tween(120), label = "focusScale")
    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onFocusChanged {
                // Sem som no foco inicial da tela (primeiros instantes); depois, um clique leve ao navegar.
                if (it.isFocused && !focused && System.currentTimeMillis() - created > FOCUS_SOUND_DELAY_MS && GameModeSettings.isSoundEnabled(context)) {
                    NavSound.click()
                }
                focused = it.isFocused
            }
            .clip(shape)
            .background(background)
            .then(if (focused) Modifier.border(3.dp, GameColors.accent, shape) else Modifier)
            .clickable(enabled = enabled, onClick = onClick),
        content = content,
    )
}

@Composable
fun GameButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val textColor = when {
        !enabled -> GameColors.textDim.copy(alpha = 0.5f)
        primary -> GameColors.onAccent
        danger -> GameColors.danger
        else -> GameColors.text
    }
    FocusSurface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        background = if (primary && enabled) GameColors.accent else GameColors.surface,
        modifier = if (compact) modifier else modifier.fillMaxWidth(),
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = if (compact) 14.sp else 16.sp,
            maxLines = 1,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = if (compact) 14.dp else 18.dp, vertical = if (compact) 8.dp else 10.dp),
        )
    }
}

/** Capa de jogo: a do RomM quando existe (aparece assim que baixa) ou uma capa gerada com o console e o título. */
@Composable
fun CoverTile(game: LocalGame, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val art by produceState<ImageBitmap?>(null, game.path, RomMCache.lastRefreshMs) {
        value = withContext(Dispatchers.IO) { GameArt.quick(context, game) }
        withContext(Dispatchers.IO) { GameArt.fetchCover(context, game) }?.let { value = it }
    }
    Box(modifier = modifier.background(GameColors.surfaceHigh)) {
        art?.let { Image(bitmap = it, contentDescription = game.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** Dois botões do controle (ex.: L1/R1) passam para o item anterior/seguinte; [onStep] recebe -1 ou +1. */
fun Modifier.onStepKeys(previous: Key, next: Key, onStep: (Int) -> Unit): Modifier = onPreviewKeyEvent { event ->
    val step = when (event.key) {
        next -> 1
        previous -> -1
        else -> 0
    }
    if (step != 0 && event.type == KeyEventType.KeyUp) onStep(step)
    step != 0
}

/** Um botão do controle (ex.: X ou Y) dispara [action] ao ser solto. */
fun Modifier.onShortcutKey(key: Key, action: () -> Unit): Modifier = onPreviewKeyEvent { event ->
    val hit = event.key == key
    if (hit && event.type == KeyEventType.KeyUp) action()
    hit
}

/** Item que fica [step] posições depois de [current] na lista, dando a volta no fim. */
fun <T> cycle(order: List<T>, current: T, step: Int): T = order[(order.indexOf(current) + step + order.size) % order.size]
