package app.lawnchair.gamemode

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R
import kotlinx.coroutines.launch

/** Escolha das pastas de BIOS, saves e imagens (capas e outros dados de scrapers). */
@Composable
internal fun FoldersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<FolderKind?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        val kind = picking ?: return@rememberLauncherForActivityResult
        picking = null
        val path = uri?.let(StorageAccess::treeUriToPath)
        if (uri != null && path == null) {
            message = context.getString(R.string.game_mode_status_folder_invalid)
            return@rememberLauncherForActivityResult
        }
        if (path == null) return@rememberLauncherForActivityResult
        scope.launch {
            val copied = GameFolders.change(context, kind, path)
            message = context.getString(R.string.game_folders_changed, copied) +
                if (!StorageAccess.isGranted() && !GameLibrarySettings.isInsideApp(context, path)) {
                    " " + context.getString(R.string.game_folders_need_access)
                } else {
                    ""
                }
            tick++
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        GameScreenHeader(stringResource(R.string.game_folders_title), onBack)
        Text(stringResource(R.string.game_folders_note), color = GameColors.textDim, fontSize = 12.sp)
        if (message.isNotEmpty()) Text(message, color = GameColors.textDim, fontSize = 12.sp, maxLines = 3)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
            items(FolderKind.entries, key = { it.name }) { kind ->
                val current = remember(tick) { GameFolders.dir(context, kind).absolutePath }
                val custom = remember(tick) { GameFolders.customPath(context, kind) != null }
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(kind.titleRes()), color = GameColors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(kind.descriptionRes()), color = GameColors.textDim, fontSize = 12.sp)
                    Text(current, color = GameColors.textDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GameButton(
                            stringResource(R.string.game_folders_choose),
                            onClick = {
                                picking = kind
                                picker.launch(null)
                            },
                            compact = true,
                        )
                        if (custom) {
                            GameButton(
                                stringResource(R.string.game_folders_default),
                                onClick = {
                                    scope.launch {
                                        val copied = GameFolders.change(context, kind, null)
                                        message = context.getString(R.string.game_folders_changed, copied)
                                        tick++
                                    }
                                },
                                compact = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun FolderKind.titleRes() = when (this) {
    FolderKind.Bios -> R.string.game_folders_bios
    FolderKind.Saves -> R.string.game_folders_saves
    FolderKind.Assets -> R.string.game_folders_assets
}

private fun FolderKind.descriptionRes() = when (this) {
    FolderKind.Bios -> R.string.game_folders_bios_desc
    FolderKind.Saves -> R.string.game_folders_saves_desc
    FolderKind.Assets -> R.string.game_folders_assets_desc
}
