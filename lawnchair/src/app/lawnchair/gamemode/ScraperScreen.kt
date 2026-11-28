package app.lawnchair.gamemode

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Tela para colar e testar a chave de API do SteamGridDB. */
@Composable
internal fun ScraperScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var typed by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(GameModeSettings.steamGridKey(context) != null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        GameScreenHeader(stringResource(R.string.game_sgdb_title), onBack)
        Text(stringResource(R.string.game_sgdb_note), color = GameColors.textDim, fontSize = 13.sp)
        if (saved) Text(stringResource(R.string.game_sgdb_saved), color = GameColors.text, fontSize = 14.sp)
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text(stringResource(R.string.game_sgdb_key)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.width(420.dp),
        )
        GameButton(
            label = stringResource(R.string.game_sgdb_save),
            enabled = !busy && typed.isNotBlank(),
            primary = true,
            modifier = Modifier.width(220.dp),
            onClick = {
                scope.launch {
                    busy = true
                    val key = typed.trim()
                    val error = withContext(Dispatchers.IO) { SteamGridDb.check(key) }
                    if (error == null) {
                        GameModeSettings.setSteamGridKey(context, key)
                        saved = true
                        typed = ""
                        message = context.getString(R.string.game_sgdb_ok)
                    } else {
                        message = error
                    }
                    busy = false
                }
            },
        )
        if (saved) {
            GameButton(
                label = stringResource(R.string.game_sgdb_remove),
                danger = true,
                modifier = Modifier.width(220.dp),
                onClick = {
                    GameModeSettings.setSteamGridKey(context, null)
                    saved = false
                    message = ""
                },
            )
        }
        if (message.isNotEmpty()) Text(message, color = GameColors.textDim, fontSize = 14.sp)
    }
}
