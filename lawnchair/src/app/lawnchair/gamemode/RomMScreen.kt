package app.lawnchair.gamemode

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.android.launcher3.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

/** Do Android 17 em diante, apps novos precisam da permissão de rede local para falar com aparelhos da casa. */
internal fun localNetworkGranted(context: Context): Boolean = Build.VERSION.SDK_INT < 37 ||
    ContextCompat.checkSelfPermission(context, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

/** Tela do servidor RomM (em Configurações): conectar por aprovação no navegador e gerenciar a conexão. */
@Composable
internal fun ServerScreen(remote: RemoteLibrary) {
    val context = LocalContext.current
    var allowed by remember { mutableStateOf(localNetworkGranted(context)) }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = it }
    LaunchedEffect(Unit) {
        if (!allowed) runCatching { request.launch(LOCAL_NETWORK_PERMISSION) }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        if (!allowed) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.game_local_network_info), color = GameColors.danger, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GameButton(
                        label = stringResource(R.string.game_local_network_allow),
                        onClick = { runCatching { request.launch(LOCAL_NETWORK_PERMISSION) } },
                        primary = true,
                        modifier = Modifier.weight(1f),
                    )
                    GameButton(
                        label = stringResource(R.string.game_local_network_settings),
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f)) {
            if (!remote.connected) ConnectForm(onConnect = { remote.onConnected() }) else ServerManage(remote)
        }
    }
}

@Composable
private fun ConnectForm(onConnect: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var manual by remember { mutableStateOf(false) }
    var pairing by remember { mutableStateOf<DevicePairing?>(null) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    val pending = pairing
    if (pending != null) {
        PairingWait(
            pairing = pending,
            onApprove = { token, accountId ->
                RomMSettings.save(context, pending.baseUrl, token, accountId)
                onConnect()
            },
            onFail = { reason ->
                pairing = null
                message = reason
            },
            onCancel = { pairing = null },
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.game_romm_title), color = GameColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(
            stringResource(if (manual) R.string.game_romm_howto else R.string.game_romm_howto_device),
            color = GameColors.textDim,
            fontSize = 14.sp,
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text(stringResource(R.string.game_romm_address)) },
            singleLine = true,
            modifier = Modifier.width(520.dp),
        )
        if (manual) {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                label = { Text(stringResource(R.string.game_romm_code)) },
                singleLine = true,
                modifier = Modifier.width(520.dp),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GameButton(
                label = stringResource(R.string.game_romm_connect),
                enabled = !busy && address.isNotBlank() && (!manual || code.isNotBlank()),
                primary = true,
                modifier = Modifier.width(220.dp),
                onClick = {
                    scope.launch {
                        busy = true
                        message = context.getString(R.string.game_romm_connecting)
                        if (manual) {
                            val result = withContext(Dispatchers.IO) { connect(address, code) }
                            result.onSuccess { (url, token, accountId) ->
                                RomMSettings.save(context, url, token, accountId)
                                onConnect()
                            }.onFailure { message = romMUserFacingError(it, "conectar a conta RomM") }
                        } else {
                            val result = withContext(Dispatchers.IO) { beginPairing(context, address) }
                            result.onSuccess {
                                pairing = it
                                message = ""
                            }.onFailure { message = it.message ?: it.toString() }
                        }
                        busy = false
                    }
                },
            )
            GameButton(
                label = stringResource(if (manual) R.string.game_romm_use_device else R.string.game_romm_use_code),
                modifier = Modifier.width(300.dp),
                onClick = { manual = !manual },
            )
        }
        if (message.isNotEmpty()) Text(message, color = GameColors.textDim, fontSize = 14.sp)
    }
}

/** Mostra o código e espera a aprovação no navegador; avisa quando aprovado, negado ou expirado. */
@Composable
private fun PairingWait(pairing: DevicePairing, onApprove: (String, Long) -> Unit, onFail: (String) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val latestApprove by rememberUpdatedState(onApprove)
    val latestFail by rememberUpdatedState(onFail)
    LaunchedEffect(pairing.deviceCode) {
        val client = RomMClient(pairing.baseUrl, null)
        var intervalMs = pairing.intervalSeconds.coerceAtLeast(1) * 1000L
        val deadline = System.currentTimeMillis() + pairing.expiresInSeconds * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(intervalMs)
            when (val result = withContext(Dispatchers.IO) { client.pollDevicePairing(pairing.deviceCode) }) {
                is DevicePoll.Approved -> {
                    val accountId = runCatching {
                        withContext(Dispatchers.IO) {
                            RomMClient(pairing.baseUrl, result.token).currentUserId()
                        }
                    }.getOrElse {
                        latestFail(romMUserFacingError(it, "identificar a conta RomM"))
                        return@LaunchedEffect
                    }
                    return@LaunchedEffect latestApprove(result.token, accountId)
                }

                DevicePoll.Denied -> return@LaunchedEffect latestFail(context.getString(R.string.game_romm_pair_denied))

                DevicePoll.Expired -> return@LaunchedEffect latestFail(context.getString(R.string.game_romm_pair_expired))

                is DevicePoll.Failed -> return@LaunchedEffect latestFail(result.message)

                DevicePoll.SlowDown -> intervalMs = (intervalMs + 5_000L).coerceAtMost(30_000L)

                DevicePoll.Pending -> Unit
            }
        }
        latestFail(context.getString(R.string.game_romm_pair_expired))
    }
    val openFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { openFocus.requestFocus() } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.game_romm_title), color = GameColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.game_romm_pair_code, pairing.userCode), color = GameColors.accent, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.game_romm_pair_steps), color = GameColors.textDim, fontSize = 14.sp)
        Text(pairing.verificationUrl, color = GameColors.textDim, fontSize = 12.sp)
        GameButton(
            label = stringResource(R.string.game_romm_open_browser),
            primary = true,
            modifier = Modifier.width(320.dp).focusRequester(openFocus),
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pairing.verificationUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        )
        Text(stringResource(R.string.game_romm_pair_wait), color = GameColors.textDim, fontSize = 14.sp)
        GameButton(label = stringResource(R.string.game_romm_pair_cancel), onClick = onCancel, modifier = Modifier.width(220.dp))
    }
}

/** Endereços a testar: com protocolo digitado só ele; sem protocolo, http primeiro para IP/rede local, https primeiro para nomes. */
private fun candidateUrls(address: String): List<String> {
    val typed = address.trim().trimEnd('/')
    if ("://" in typed) return listOf(typed)
    val host = typed.substringBefore('/').substringBefore(':')
    val local = host.matches(Regex("""\d{1,3}(\.\d{1,3}){3}""")) || host == "localhost" || host.endsWith(".local") || '.' !in host
    return if (local) listOf("http://$typed", "https://$typed") else listOf("https://$typed", "http://$typed")
}

/** Primeiro endereço que responde; se nenhum responder, o erro lista o motivo de cada tentativa. */
private fun reachable(address: String): RomMClient {
    val problems = mutableListOf<String>()
    for (candidate in candidateUrls(address)) {
        val client = runCatching { RomMClient(candidate, null) }.getOrElse {
            problems += "$candidate: endereço inválido"
            continue
        }
        val problem = client.checkServer() ?: return client
        problems += "$candidate: $problem"
    }
    throw IllegalStateException("Não consegui falar com o servidor. ${problems.joinToString("; ")}")
}

/** Acha o servidor e pede o código de aprovação. */
private fun beginPairing(context: Context, address: String): Result<DevicePairing> = runCatching {
    val client = reachable(address)

    @Suppress("HardwareIds")
    val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
    val clientId = "tandem-${androidId ?: java.util.UUID.randomUUID()}"
    val name = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    client.beginDevicePairing(clientId, name, "1.0")
}

/** Acha o servidor e troca o código de pareamento por um token; devolve (endereço, token). */
private fun connect(address: String, code: String): Result<Triple<String, String, Long>> = runCatching {
    val client = reachable(address)
    val token = client.exchangePairingCode(code)
    val accountId = RomMClient(client.baseAddress, token).currentUserId()
    Triple(client.baseAddress, token, accountId)
}

/** Conexão com o servidor já feita: mostra o endereço, o estado da lista e os botões de atualizar, log e desconectar. */
@Composable
private fun ServerManage(remote: RemoteLibrary) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLog = rememberLogExporter()
    val count = remote.games?.size
    var registeredDeviceId by remember { mutableStateOf(RomMSettings.deviceSyncDeviceId(context)) }
    var registeringDevice by remember { mutableStateOf(false) }
    var deviceSyncMessage by remember { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.game_romm_title), color = GameColors.text, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.game_romm_connected, remote.url), color = GameColors.textDim, fontSize = 14.sp)
        if (count != null) Text(stringResource(R.string.game_romm_games_count, count), color = GameColors.text, fontSize = 14.sp)
        RomMInsightsPanel()
        if (remote.refreshing) Text(stringResource(R.string.game_romm_updating), color = GameColors.textDim, fontSize = 12.sp)
        if (remote.error.isNotEmpty()) Text(remote.error, color = GameColors.danger, fontSize = 12.sp, maxLines = 3)
        if (count == 0 && remote.error.isEmpty()) {
            Text(stringResource(R.string.game_romm_empty), color = GameColors.textDim, fontSize = 14.sp)
            if (remote.report.isNotEmpty()) Text(remote.report, color = GameColors.textDim, fontSize = 12.sp)
        }
        GameButton(stringResource(R.string.game_romm_refresh), onClick = { remote.requestRefresh() }, modifier = Modifier.width(260.dp))
        if (registeredDeviceId == null) {
            Text(
                "Device Sync ainda não está registrado neste dispositivo para esta conta RomM.",
                color = GameColors.textDim,
                fontSize = 13.sp,
            )
            GameButton(
                label = if (registeringDevice) "Registrando dispositivo..." else "Registrar dispositivo no Device Sync",
                enabled = !registeringDevice,
                primary = true,
                modifier = Modifier.width(340.dp),
                onClick = {
                    scope.launch {
                        registeringDevice = true
                        deviceSyncMessage = ""
                        val url = RomMSettings.url(context)
                        val token = RomMSettings.token(context)
                        if (url.isNullOrBlank() || token.isNullOrBlank()) {
                            deviceSyncMessage = "A conexão RomM não está disponível. Reconecte a conta e tente novamente."
                        } else {
                            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                            val stableSuffix = androidId?.takeIf { it.isNotBlank() }
                                ?: Integer.toHexString(Build.FINGERPRINT.hashCode())
                            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().ifBlank { "Tandem Android" }
                            val registration = DeviceSyncRegistration(
                                name = deviceName,
                                platform = "Android",
                                client = "tandem",
                                clientVersion = "1.0",
                                hostname = "tandem-$stableSuffix",
                            )
                            val result = withContext(Dispatchers.IO) {
                                runCatching { RomMDeviceSyncClient(url, token).registerDevice(registration) }
                            }
                            result.onSuccess { device ->
                                runCatching { RomMSettings.saveDeviceSyncDeviceId(context, device.deviceId) }
                                    .onSuccess {
                                        registeredDeviceId = device.deviceId
                                        deviceSyncMessage = "Dispositivo registrado com sucesso no RomM."
                                    }
                                    .onFailure { error ->
                                        deviceSyncMessage = error.message ?: "O RomM registrou o dispositivo, mas não foi possível salvar o identificador local."
                                    }
                            }.onFailure { error ->
                                deviceSyncMessage = when (error) {
                                    is RomMHttpException -> romMUserFacingError(error, "registrar o dispositivo no Device Sync")

                                    else -> error.message ?: "Não foi possível registrar o dispositivo no RomM."
                                }
                            }
                        }
                        registeringDevice = false
                    }
                },
            )
        } else {
            Text(
                "Device Sync registrado nesta conta. Identificador: $registeredDeviceId",
                color = GameColors.textDim,
                fontSize = 13.sp,
            )
        }
        if (deviceSyncMessage.isNotEmpty()) {
            Text(deviceSyncMessage, color = if (registeredDeviceId != null) GameColors.textDim else GameColors.danger, fontSize = 13.sp)
        }
        GameButton(stringResource(R.string.game_log_export), onClick = exportLog, modifier = Modifier.width(260.dp))
        GameButton(stringResource(R.string.game_romm_disconnect), onClick = { remote.disconnect() }, danger = true, modifier = Modifier.width(260.dp))
    }
}
