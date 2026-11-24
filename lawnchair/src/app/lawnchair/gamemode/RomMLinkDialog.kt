package app.lawnchair.gamemode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Associa uma ROM já existente a um registro escolhido explicitamente no RomM.
 * A associação grava somente metadados de identidade; não transfere nem altera ROMs ou saves.
 */
@Composable
internal fun RomMLinkDialog(
    local: LocalGame,
    onDismiss: () -> Unit,
    onLinked: (RemoteGame) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var query by remember(local.path) { mutableStateOf(local.title) }
    var candidates by remember(local.path) { mutableStateOf<List<RemoteGame>>(emptyList()) }
    var selected by remember(local.path) { mutableStateOf<RemoteGame?>(null) }
    var loading by remember(local.path) { mutableStateOf(false) }
    var saving by remember(local.path) { mutableStateOf(false) }
    var error by remember(local.path) { mutableStateOf("") }
    var searched by remember(local.path) { mutableStateOf(false) }
    val localFile = remember(local.path) { File(local.path) }

    fun search() {
        if (loading || query.trim().length < 2) return
        loading = true
        error = ""
        selected = null
        scope.launch {
            try {
                val (found, localSha1) = withContext(Dispatchers.IO) {
                    val url = RomMSettings.url(context).orEmpty()
                    val token = RomMSettings.token(context)
                    val results = RomMClient(url, token).listGames(searchTerm = query.trim())
                    results to localFile.takeIf { it.isFile }?.let(::romMLinkSha1)
                }
                candidates = found.filter { it.platform.slug == local.platform.slug }
                    .sortedWith(
                        compareByDescending<RemoteGame> { game ->
                            localSha1 != null && game.associatedFiles.any {
                                it.sha1Hash.equals(localSha1, ignoreCase = true)
                            }
                        }.thenByDescending { game ->
                            val localNames = listOf(local.title, localFile.nameWithoutExtension)
                                .map(::romMLinkNormalize)
                            val remoteNames = listOf(game.title, game.fileName.substringBeforeLast('.')) + game.alternativeNames
                            remoteNames.any { romMLinkNormalize(it) in localNames }
                        }.thenByDescending { game ->
                            game.sizeBytes == localFile.length() ||
                                game.associatedFiles.any { it.sizeBytes == localFile.length() }
                        }.thenBy { it.title.lowercase() },
                    )
                searched = true
                if (candidates.isEmpty()) error = "Nenhum resultado dessa plataforma foi encontrado. Tente outro termo."
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "Não foi possível pesquisar no RomM."
            } finally {
                loading = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text("Associar ROM existente ao RomM") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ROM local: ${local.title} • ${local.platform.name}")
                Text(
                    "A pesquisa considera somente a mesma plataforma. Confira o jogo e a versão antes de associar. Nenhum arquivo será enviado, baixado, substituído ou apagado.",
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = {
                        query = it
                        searched = false
                        selected = null
                    },
                    label = { Text("Nome para pesquisar no RomM") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = ::search, enabled = !loading && query.trim().length >= 2) {
                        Text(if (loading) "Pesquisando…" else "Pesquisar")
                    }
                }
                if (error.isNotBlank()) Text(error)
                if (searched && candidates.isNotEmpty()) {
                    Text("Selecione o registro remoto correto:")
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                        items(candidates, key = { it.id }) { game ->
                            val isSelected = selected?.id == game.id
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selected = game }
                                    .padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Text(
                                    text = (if (isSelected) "✓ " else "") + game.title,
                                )
                                Text("ID RomM #${game.id} • ${game.fileName}")
                                val sizeMatches = game.sizeBytes == localFile.length() ||
                                    game.associatedFiles.any { it.sizeBytes == localFile.length() }
                                if (sizeMatches) Text("Tamanho do arquivo local corresponde a um arquivo do registro.")
                            }
                        }
                    }
                }
                selected?.let { Text("Selecionado: ${it.title} (ID #${it.id})") }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected != null && !saving,
                onClick = {
                    val game = selected ?: return@TextButton
                    saving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                SaveSync.link(context, local.path, game.id)
                                check(GameIdentityResolver.linkedRomId(context, localFile) == game.id) {
                                    "O vínculo não pôde ser confirmado para a conta RomM atual."
                                }
                            }
                            onLinked(game)
                        } catch (failure: CancellationException) {
                            throw failure
                        } catch (failure: Exception) {
                            error = failure.message ?: "Não foi possível salvar a associação."
                        } finally {
                            saving = false
                        }
                    }
                },
            ) {
                Text(if (saving) "Associando…" else "Associar")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancelar") }
        },
    )
}

private fun romMLinkSha1(file: File): String {
    val digest = MessageDigest.getInstance("SHA-1")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

private fun romMLinkNormalize(value: String): String =
    value.lowercase().replace(Regex("""\.[a-z0-9]{1,5}$"""), "")
        .replace(Regex("""[^\p{L}\p{N}]"""), "")
