package app.lawnchair.gamemode

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
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
 * Overlay de conquista do Tandem: substitui o aviso simples por um cartão animado, alinhado ao
 * canto superior direito e usando a paleta do modo jogos. A camada permanece não interativa.
 */
@Composable
internal fun AchievementOverlay(achievement: RaAchievement, hardcore: Boolean, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, achievement.badge) {
        value = withContext(Dispatchers.IO) { RaBadges.load(context, achievement.badge) }
    }
    var visible by remember(achievement.id) { mutableStateOf(false) }

    LaunchedEffect(achievement.id) {
        visible = true
        delay(4_200)
        visible = false
        delay(220)
        onDismiss()
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(top = 22.dp, end = 22.dp),
        contentAlignment = Alignment.TopEnd,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 3 }),
        ) {
            Row(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .shadow(18.dp, RoundedCornerShape(14.dp))
                    .background(GameColors.panel, RoundedCornerShape(14.dp))
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(66.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(GameColors.accent),
                )
                if (icon != null) {
                    Image(
                        bitmap = icon!!,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(58.dp).clip(RoundedCornerShape(9.dp)),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(58.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(GameColors.surfaceHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("★", color = GameColors.accent, fontSize = 28.sp)
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                        ).uppercase(),
                        color = if (hardcore) GameColors.success else GameColors.textDim,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
