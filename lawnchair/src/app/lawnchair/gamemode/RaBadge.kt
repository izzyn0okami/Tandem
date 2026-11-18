package app.lawnchair.gamemode

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Ícones (badges) das conquistas do RetroAchievements, guardados no cache do app depois do primeiro download. */
internal object RaBadges {

    private val http = OkHttpClient()

    private fun file(context: Context, badge: String, locked: Boolean) = File(File(context.cacheDir, "ra_badges"), if (locked) "${badge}_lock.png" else "$badge.png")

    /**
     * Bloqueante: devolve o ícone da conquista (baixa se ainda não estiver no cache). Com `locked = true`,
     * baixa a versão em cinza (cadeado) que o próprio RetroAchievements usa para conquista ainda não ganha.
     */
    fun load(context: Context, badge: String, locked: Boolean = false): ImageBitmap? {
        if (badge.isBlank() || !badge.all { it.isLetterOrDigit() || it == '_' }) return null
        val target = file(context, badge, locked)
        if (!target.isFile) {
            runCatching {
                target.parentFile?.mkdirs()
                val name = if (locked) "${badge}_lock" else badge
                val request = Request.Builder().url("https://media.retroachievements.org/Badge/$name.png").build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val temp = File(target.parentFile, "$badge.part")
                        temp.outputStream().use { response.body.byteStream().copyTo(it) }
                        temp.renameTo(target)
                    }
                }
            }
        }
        return target.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
    }
}

/**
 * Notificação de conquista no estilo de um HUD de console: badge, informação, pontuação e modo.
 * A animação e o tempo de permanência são geridos pelo próprio componente; a fila fica na Activity.
 */
@Composable
internal fun AchievementNotification(
    achievement: RaAchievement,
    hardcore: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, achievement.badge) {
        value = withContext(Dispatchers.IO) { RaBadges.load(context, achievement.badge) }
    }
    var visible by androidx.compose.runtime.remember(achievement.id) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    androidx.compose.runtime.LaunchedEffect(achievement.id) {
        visible = true
        kotlinx.coroutines.delay(NOTIFICATION_VISIBLE_MS)
        visible = false
        kotlinx.coroutines.delay(NOTIFICATION_EXIT_MS)
        onDismiss()
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(top = 24.dp, start = 16.dp, end = 16.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = slideInVertically(initialOffsetY = { -it / 2 }, animationSpec = tween(260)) + fadeIn(tween(220)),
            exit = slideOutVertically(targetOffsetY = { -it / 3 }, animationSpec = tween(220)) + fadeOut(tween(180)),
        ) {
            val shape = RoundedCornerShape(14.dp)
            Row(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .shadow(18.dp, shape)
                    .clip(shape)
                    .background(GameColors.panel)
                    .border(1.dp, GameColors.accent.copy(alpha = 0.48f), shape)
                    .padding(horizontal = 16.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(GameColors.backgroundTop),
                    contentAlignment = Alignment.Center,
                ) {
                    if (icon != null) {
                        Image(
                            bitmap = icon!!,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Text("★", color = GameColors.accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = stringResource(R.string.game_ra_unlocked_title, achievement.points),
                        color = GameColors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = achievement.title,
                        color = GameColors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (achievement.description.isNotBlank()) {
                        Text(
                            text = achievement.description,
                            color = GameColors.textDim,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = stringResource(
                            if (hardcore) R.string.game_ra_hardcore_label else R.string.game_ra_softcore_label,
                        ),
                        color = if (hardcore) GameColors.success else GameColors.textDim,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private const val NOTIFICATION_VISIBLE_MS = 3_600L
private const val NOTIFICATION_EXIT_MS = 240L
