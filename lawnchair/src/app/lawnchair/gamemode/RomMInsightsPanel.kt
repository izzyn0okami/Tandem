package app.lawnchair.gamemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Painel RomM inicial do Estágio F. Todas as consultas desta etapa são somente leitura. */
@Composable
internal fun RomMInsightsPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var section by remember { mutableStateOf("stats") }
    var stats by remember { mutableStateOf<RemoteRomMStats?>(null) }
    var recommendations by remember { mutableStateOf<List<RemoteRecommendation>?>(null) }
    var notifications by remember { mutableStateOf<List<RemoteNotification>?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    fun load(target: String) {
        section = target
        loading = true
        error = ""
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val client = RomMClient(RomMSettings.url(context).orEmpty(), RomMSettings.token(context))
                    when (target) {
                        "stats" -> client.getServerStats()
                        "recommendations" -> client.getRecommendations(20)
                        else -> client.getNotifications()
                    }
                }
            }
            result.onSuccess { value ->
                when (target) {
                    "stats" -> stats = value as RemoteRomMStats
                    "recommendations" -> recommendations = value as List<RemoteRecommendation>
                    else -> notifications = value as List<RemoteNotification>
                }
            }.onFailure { failure ->
                error = when (failure) {
                    is RomMHttpException -> when (failure.statusCode) {
                        401 -> "A sessão RomM expirou. Reconecte a conta."
                        403 -> "A autorização atual não permite esta consulta. Reconecte o Tandem com as permissões de leitura necessárias."
                        else -> failure.message ?: "Falha ao consultar o RomM."
                    }
                    else -> failure.message ?: "Não foi possível consultar o RomM."
                }
            }
            loading = false
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text("Painel RomM", color = GameColors.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GameButton("Estatísticas", onClick = { load("stats") }, primary = section == "stats")
            GameButton("Recomendações", onClick = { load("recommendations") }, primary = section == "recommendations")
            GameButton("Notificações", onClick = { load("notifications") }, primary = section == "notifications")
        }
        if (loading) Text("Consultando o RomM…", color = GameColors.textDim, fontSize = 12.sp)
        if (error.isNotBlank()) Text(error, color = GameColors.danger, fontSize = 12.sp)
        when (section) {
            "stats" -> {
                val value = stats
                if (value == null) {
                    Text("Estatísticas oficiais do servidor, sem recalcular os dados no Tandem.", color = GameColors.textDim, fontSize = 12.sp)
                    GameButton("Carregar estatísticas", onClick = { load("stats") }, enabled = !loading)
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        StatValue("Jogos", value.roms)
                        StatValue("Plataformas", value.platforms)
                        StatValue("Saves", value.saves)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                        StatValue("States", value.states)
                        StatValue("Capturas", value.screenshots)
                        StatValue("Armazenamento", value.totalFileSizeBytes, bytes = true)
                    }
                    GameButton("Atualizar estatísticas", onClick = { load("stats") }, enabled = !loading)
                }
            }
            "recommendations" -> {
                val values = recommendations
                if (values == null) {
                    Text("Sugestões personalizadas calculadas pelo RomM com base na biblioteca e no histórico de jogo.", color = GameColors.textDim, fontSize = 12.sp)
                    GameButton("Carregar recomendações", onClick = { load("recommendations") }, enabled = !loading)
                } else if (values.isEmpty()) {
                    Text("O RomM não retornou recomendações. O índice pode ainda não ter sido criado ou não haver jogos suficientes.", color = GameColors.textDim, fontSize = 12.sp)
                    GameButton("Atualizar recomendações", onClick = { load("recommendations") }, enabled = !loading)
                } else {
                    values.take(10).forEachIndexed { index, item ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                            Text("${index + 1}. ${item.title}", color = GameColors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (item.platform.isNotBlank()) Text(item.platform, color = GameColors.textDim, fontSize = 11.sp)
                            item.seedRomName?.let { Text("Porque jogou: $it", color = GameColors.textDim, fontSize = 11.sp) }
                            if (item.reasons.isNotEmpty()) Text(item.reasons.joinToString(" · "), color = GameColors.textDim, fontSize = 11.sp)
                        }
                    }
                    GameButton("Atualizar recomendações", onClick = { load("recommendations") }, enabled = !loading)
                }
            }
            else -> {
                val values = notifications
                if (values == null) {
                    Text("Caixa de entrada da conta RomM. Nesta etapa, as notificações são apenas consultadas.", color = GameColors.textDim, fontSize = 12.sp)
                    GameButton("Carregar notificações", onClick = { load("notifications") }, enabled = !loading)
                } else if (values.isEmpty()) {
                    Text("A caixa de entrada do RomM está vazia.", color = GameColors.textDim, fontSize = 12.sp)
                    GameButton("Atualizar notificações", onClick = { load("notifications") }, enabled = !loading)
                } else {
                    val unread = values.count { !it.read }
                    Text("${values.size} notificações · $unread não lidas", color = GameColors.textDim, fontSize = 11.sp)
                    values.take(10).forEach { item ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(vertical = 3.dp)) {
                            Text(item.title.ifBlank { item.kind.replace('_', ' ') }, color = GameColors.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            if (item.body.isNotBlank()) Text(item.body, color = GameColors.textDim, fontSize = 11.sp)
                            Text("${if (item.read) "Lida" else "Não lida"} · ${item.level} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(item.createdAtMs))}${item.actor?.let { " · $it" } ?: ""}", color = GameColors.textDim, fontSize = 10.sp)
                        }
                    }
                    GameButton("Atualizar notificações", onClick = { load("notifications") }, enabled = !loading)
                }
            }
        }
    }
}

@Composable
private fun StatValue(label: String, value: Long, bytes: Boolean = false) {
    Column {
        Text(label, color = GameColors.textDim, fontSize = 10.sp)
        Text(if (bytes) formatRomMBytes(value) else value.toString(), color = GameColors.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

private fun formatRomMBytes(value: Long): String {
    if (value <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var amount = value.toDouble()
    var index = 0
    while (amount >= 1024.0 && index < units.lastIndex) {
        amount /= 1024.0
        index++
    }
    return String.format(java.util.Locale.ROOT, "%.1f %s", amount, units[index])
}
