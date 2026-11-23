package app.lawnchair.gamemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun RomMGameDataPanel(
    game: RemoteGame,
    localPlaytimeMs: Long,
    localLastPlayedMs: Long,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val user = game.userData
    var backlogged by remember(game.id, user) { mutableStateOf(user?.backlogged ?: false) }
    var nowPlaying by remember(game.id, user) { mutableStateOf(user?.nowPlaying ?: false) }
    var hidden by remember(game.id, user) { mutableStateOf(user?.hidden ?: false) }
    var rating by remember(game.id, user) { mutableStateOf(user?.rating?.takeIf { it > 0 }?.toString().orEmpty()) }
    var difficulty by remember(game.id, user) { mutableStateOf(user?.difficulty?.takeIf { it > 0 }?.toString().orEmpty()) }
    var completion by remember(game.id, user) { mutableStateOf(user?.completion?.takeIf { it > 0 }?.toString().orEmpty()) }
    var status by remember(game.id, user) { mutableStateOf(user?.status.orEmpty()) }
    var saving by remember(game.id) { mutableStateOf(false) }
    var message by remember(game.id) { mutableStateOf("") }
    var activity by remember(game.id) { mutableStateOf<List<RemoteActivity>?>(null) }
    var activityError by remember(game.id) { mutableStateOf("") }

    LaunchedEffect(game.id, RomMSettings.url(context), RomMSettings.token(context)) {
        activity = null
        activityError = ""
        if (RomMSettings.isConnected(context)) {
            runCatching {
                withContext(Dispatchers.IO) {
                    RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context))
                        .listActivity().filter { it.romId == game.id }
                }
            }.onSuccess { activity = it }
                .onFailure {
                    activityError = it.message ?: "Não foi possível consultar a atividade."
                    activity = emptyList()
                }
        } else {
            activity = emptyList()
        }
    }

    fun save() {
        if (saving || !RomMSettings.isConnected(context)) return
        val parsedRating = rating.toIntOrNull()
        val parsedDifficulty = difficulty.toIntOrNull()
        val parsedCompletion = completion.toIntOrNull()
        if (rating.isNotBlank() && (parsedRating == null || parsedRating !in 0..10)) {
            message = "Avaliação deve estar entre 0 e 10."
            return
        }
        if (difficulty.isNotBlank() && (parsedDifficulty == null || parsedDifficulty !in 0..10)) {
            message = "Dificuldade deve estar entre 0 e 10."
            return
        }
        if (completion.isNotBlank() && (parsedCompletion == null || parsedCompletion !in 0..100)) {
            message = "Conclusão deve estar entre 0 e 100."
            return
        }
        saving = true
        message = "Salvando no RomM…"
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context))
                        .updateUserProperties(
                            game.id,
                            mapOf(
                                "backlogged" to backlogged,
                                "now_playing" to nowPlaying,
                                "hidden" to hidden,
                                "rating" to (parsedRating ?: 0),
                                "difficulty" to (parsedDifficulty ?: 0),
                                "completion" to (parsedCompletion ?: 0),
                                "status" to status.takeIf { it.isNotBlank() },
                            ),
                        )
                }
            }
            saving = false
            message = result.fold(
                onSuccess = { "Dados pessoais atualizados no RomM." },
                onFailure = { it.message ?: "Falha ao salvar no RomM." },
            )
            result.exceptionOrNull()?.let { GameLog.error("romm", "falha ao atualizar dados pessoais de ${game.title}", it) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(7.dp), modifier = Modifier.width(520.dp)) {
        Text("RomM · dados pessoais", color = GameColors.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (user == null) {
            Text("Ainda não há registro pessoal para este jogo. Salvar criará as propriedades da sua conta no RomM.", color = GameColors.textDim, fontSize = 12.sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text("No backlog", color = GameColors.textDim, fontSize = 12.sp)
                Switch(checked = backlogged, onCheckedChange = { backlogged = it })
            }
            Column {
                Text("Jogando agora", color = GameColors.textDim, fontSize = 12.sp)
                Switch(checked = nowPlaying, onCheckedChange = { nowPlaying = it })
            }
            Column {
                Text("Oculto", color = GameColors.textDim, fontSize = 12.sp)
                Switch(checked = hidden, onCheckedChange = { hidden = it })
            }
        }
        Text("Status", color = GameColors.textDim, fontSize = 12.sp)
        val statusOptions = listOf(
            "" to "Não definido",
            "incomplete" to "Em andamento",
            "finished" to "Finalizado",
            "completed_100" to "100%",
            "retired" to "Abandonado",
            "never_playing" to "Nunca jogar",
        )
        statusOptions.chunked(3).forEach { options ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { (value, label) ->
                    GameButton(label, onClick = { status = value }, enabled = !saving)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = rating,
                onValueChange = { rating = it.filter(Char::isDigit).take(2) },
                label = { Text("Avaliação (0–10)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(150.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = difficulty,
                onValueChange = { difficulty = it.filter(Char::isDigit).take(2) },
                label = { Text("Dificuldade (0–10)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(150.dp),
                singleLine = true,
            )
            OutlinedTextField(
                value = completion,
                onValueChange = { completion = it.filter(Char::isDigit).take(3) },
                label = { Text("Conclusão (0–100)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(160.dp),
                singleLine = true,
            )
        }
        GameButton("Salvar dados pessoais", onClick = ::save, primary = true, enabled = !saving)
        if (message.isNotBlank()) Text(message, color = if (message.startsWith("Falha") || message.contains("deve estar") || message.contains("não tem o escopo")) GameColors.danger else GameColors.textDim, fontSize = 11.sp)

        Text("Informações somente de leitura", color = GameColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        user?.let {
            Text("Criado: ${formatRomMDate(it.createdAtMs)} · Atualizado: ${formatRomMDate(it.updatedAtMs)}", color = GameColors.textDim, fontSize = 11.sp)
            Text("Última partida no RomM: ${formatRomMDate(it.lastPlayedAtMs ?: 0L)}", color = GameColors.textDim, fontSize = 11.sp)
            Text("Versão principal: ${if (it.isMainSibling) "Sim" else "Não"}", color = GameColors.textDim, fontSize = 11.sp)
            if (it.pinnedMedia.isNotEmpty()) Text("Mídias fixadas: ${it.pinnedMedia.joinToString(" · ")}", color = GameColors.textDim, fontSize = 11.sp)
        }
        Text("Histórico local do Tandem", color = GameColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Tempo jogado: ${formatLocalPlaytime(localPlaytimeMs)} · Última sessão: ${formatRomMDate(localLastPlayedMs)}", color = GameColors.textDim, fontSize = 11.sp)
        Text("Atividade atual no RomM", color = GameColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        when {
            activity == null -> Text("Consultando sessões ativas…", color = GameColors.textDim, fontSize = 11.sp)
            activityError.isNotBlank() -> Text("Atividade indisponível: $activityError", color = GameColors.danger, fontSize = 11.sp)
            activity!!.isEmpty() -> Text("Nenhuma sessão ativa informada pelo RomM.", color = GameColors.textDim, fontSize = 11.sp)
            else -> activity!!.forEach { session ->
                Text(
                    "${session.username} · ${session.deviceType} · desde ${formatRomMDate(session.startedAtMs)}",
                    color = GameColors.textDim,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

private fun formatLocalPlaytime(ms: Long): String {
    val totalMinutes = ms.coerceAtLeast(0L) / 60_000L
    val hours = totalMinutes / 60L
    val minutes = totalMinutes % 60L
    return if (hours > 0L) "${hours}h ${minutes}min" else "${minutes}min"
}

private fun formatRomMDate(timestampMs: Long): String =
    if (timestampMs <= 0L) "Não informado" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(timestampMs))
