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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.text.font.FontFamily
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

    /**
     * Bloqueante: baixa (e guarda no cache) uma imagem a partir de uma URL completa — usado para o
     * ícone do jogo no aviso de "Mastered"/"Completed", cuja URL já vem pronta do motor nativo
     * (a RetroAchievements decide o endereço certo; aqui só baixamos e guardamos).
     */
    fun loadUrl(context: Context, url: String): ImageBitmap? {
        if (url.isBlank()) return null
        val name = url.substringAfterLast('/').substringBefore('?')
        if (name.isBlank() || !name.all { it.isLetterOrDigit() || it == '_' || it == '.' || it == '-' }) return null
        val target = File(File(context.cacheDir, "ra_badges"), name)
        if (!target.isFile) {
            runCatching {
                target.parentFile?.mkdirs()
                val request = Request.Builder().url(url).build()
                http.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val temp = File(target.parentFile, "$name.part")
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
    val currentOnDismiss by rememberUpdatedState(onDismiss)

    LaunchedEffect(achievement.id) {
        visible = true
        delay(4_200)
        visible = false
        delay(220)
        currentOnDismiss()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        Box(
            modifier = Modifier.fillMaxSize().padding(top = 18.dp, end = 18.dp, start = 18.dp),
            contentAlignment = Alignment.TopEnd,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 3 }),
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = minOf(440.dp, availableWidth * 0.52f))
                        .shadow(18.dp, RoundedCornerShape(14.dp))
                        .background(GameColors.panel, RoundedCornerShape(14.dp))
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .height(64.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(GameColors.accent),
                    )
                    if (icon != null) {
                        Image(
                            bitmap = icon!!,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(9.dp)),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
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
}

// --- Indicadores e avisos extras, no padrão de overlay da própria RetroAchievements -------------
// (ver https://github.com/RetroAchievements/rcheevos/wiki/rc_client-integration), com as cores e
// o acabamento do Tandem.

/** Ícones de "desafio ativo": a própria RetroAchievements define como "uma versão pequena do ícone desbloqueado". */
@Composable
internal fun ChallengeIndicatorsRow(indicators: List<RaChallengeIndicator>) {
    if (indicators.isEmpty()) return
    val context = LocalContext.current
    Box(modifier = Modifier.fillMaxSize().padding(start = 18.dp, bottom = 18.dp), contentAlignment = Alignment.BottomStart) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            indicators.forEach { indicator ->
                key(indicator.id) {
                    val icon by produceState<ImageBitmap?>(null, indicator.badge) {
                        value = withContext(Dispatchers.IO) { RaBadges.load(context, indicator.badge) }
                    }
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .shadow(8.dp, RoundedCornerShape(8.dp))
                            .clip(RoundedCornerShape(8.dp))
                            .background(GameColors.surfaceHigh),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (icon != null) {
                            Image(bitmap = icon!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        } else {
                            Text("★", color = GameColors.accent, fontSize = 16.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Indicador de progresso: "um popup pequeno com o ícone trancado e o texto de progresso" (ex.: 7/10), só um por vez. */
@Composable
internal fun ProgressIndicatorBadge(indicator: RaProgressIndicator) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, indicator.badge) {
        value = withContext(Dispatchers.IO) { RaBadges.load(context, indicator.badge, locked = true) }
    }
    Box(modifier = Modifier.fillMaxSize().padding(end = 18.dp, bottom = 18.dp), contentAlignment = Alignment.BottomEnd) {
        Row(
            modifier = Modifier
                .shadow(10.dp, RoundedCornerShape(10.dp))
                .background(GameColors.panel, RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(36.dp).clip(RoundedCornerShape(7.dp)).background(GameColors.surfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(bitmap = icon!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("?", color = GameColors.textDim, fontSize = 14.sp)
                }
            }
            Column {
                Text(
                    text = indicator.title,
                    color = GameColors.text,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 160.dp),
                )
                Text(text = indicator.progress, color = GameColors.accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Placares (leaderboards) sendo acompanhados: a RetroAchievements pede fonte monoespaçada, para o texto não "tremer". */
@Composable
internal fun LeaderboardTrackersColumn(trackers: List<RaLeaderboardTracker>) {
    if (trackers.isEmpty()) return
    Box(modifier = Modifier.fillMaxSize().padding(top = 22.dp, start = 22.dp), contentAlignment = Alignment.TopStart) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            trackers.forEach { tracker ->
                key(tracker.id) {
                    Text(
                        text = tracker.display,
                        color = GameColors.text,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .shadow(6.dp, RoundedCornerShape(6.dp))
                            .background(GameColors.panel, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
            }
        }
    }
}

/** Aviso rápido de placar (iniciado, não validado ou enviado): a RetroAchievements usa só uma mensagem simples. */
@Composable
internal fun GameMessageOverlay(message: String, onDismiss: () -> Unit) {
    var visible by remember(message) { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(message) {
        visible = true
        delay(3_000)
        visible = false
        delay(220)
        currentOnDismiss()
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        Box(
            modifier = Modifier.fillMaxSize().padding(top = 18.dp, start = 18.dp, end = 18.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
                Column(
                    modifier = Modifier
                        .widthIn(max = minOf(420.dp, availableWidth * 0.52f))
                        .shadow(10.dp, RoundedCornerShape(10.dp))
                        .background(GameColors.panel, RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    Text(
                        text = "RETROACHIEVEMENTS",
                        color = GameColors.accent,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = message,
                        color = GameColors.text,
                        fontSize = 13.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * Aviso de "Mastered" (zerou no hardcore) ou "Completed" (zerou no normal) — os dois nomes oficiais
 * que a própria RetroAchievements usa (aparecem assim no perfil do site também). Ícone do jogo,
 * título e o nome do jogador + tempo jogado, com a paleta do Tandem.
 */
@Composable
internal fun MasteryOverlay(mastery: RaMastery, playtimeLabel: String, userName: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, mastery.badgeUrl) {
        value = withContext(Dispatchers.IO) { RaBadges.loadUrl(context, mastery.badgeUrl) }
    }
    var visible by remember(mastery) { mutableStateOf(false) }
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(mastery) {
        visible = true
        delay(6_500)
        visible = false
        delay(260)
        currentOnDismiss()
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableWidth = maxWidth
        Box(
            modifier = Modifier.fillMaxSize().padding(top = 18.dp, start = 18.dp, end = 18.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = fadeIn() + slideInVertically(initialOffsetY = { -it / 2 }),
                exit = fadeOut() + slideOutVertically(targetOffsetY = { -it / 3 }),
            ) {
                Row(
                    modifier = Modifier
                        .widthIn(max = minOf(460.dp, availableWidth * 0.54f))
                        .shadow(20.dp, RoundedCornerShape(16.dp))
                        .background(GameColors.panel, RoundedCornerShape(16.dp))
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (icon != null) {
                        Image(
                            bitmap = icon!!,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)),
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(64.dp).clip(RoundedCornerShape(10.dp)).background(GameColors.surfaceHigh),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("🏆", fontSize = 26.sp)
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(
                            text = stringResource(
                                when {
                                    mastery.subset && mastery.hardcore -> R.string.game_ra_subset_mastered
                                    mastery.subset -> R.string.game_ra_subset_completed
                                    mastery.hardcore -> R.string.game_ra_mastered
                                    else -> R.string.game_ra_completed
                                },
                            ).uppercase(),
                            color = if (mastery.hardcore) GameColors.success else GameColors.accent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = mastery.title,
                            color = GameColors.text,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(R.string.game_ra_mastery_subtitle, userName, playtimeLabel),
                            color = GameColors.textDim,
                            fontSize = 12.sp,
                        )
                    }
                }
            }
        }
    }
}
