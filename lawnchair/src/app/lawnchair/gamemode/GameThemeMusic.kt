package app.lawnchair.gamemode

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Plays a short iiDB soundbite for the selected game.
 * The asset is cached locally; no audio is bundled with the APK and playback never loops.
 */
object GameThemeMusic {
    private val lock = Any()

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var currentKey: String? = null

    @Volatile
    private var playbackRequestId = 0L

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var onPlaybackEnded: (() -> Unit)? = null

    private data class AudioFocusState(
        val manager: AudioManager?,
        val request: AudioFocusRequest?,
    )

    suspend fun play(context: Context, key: String, title: String, platform: String, onEnded: () -> Unit = {}): Boolean {
        val requestId: Long
        val previous: MediaPlayer?
        val previousFocus: AudioFocusState
        synchronized(lock) {
            if (currentKey == key && player?.isPlaying == true) return true
            playbackRequestId += 1
            requestId = playbackRequestId
            currentKey = key
            previous = player
            player = null
            onPlaybackEnded = null
            previousFocus = takeAudioFocusLocked()
        }
        releasePrevious(previous, previousFocus, "nova solicitação de soundbite")

        val apiKey = IidbKeyStore.get(context)
        if (apiKey.isNullOrBlank()) {
            GameLog.log("iiDB", "reprodução indisponível: API key não configurada")
            clearRequest(requestId)
            return false
        }

        val file = IidbSoundbites.findSoundbite(context, apiKey, title, platform)
        if (file == null || playbackRequestId != requestId) {
            clearRequest(requestId)
            return false
        }
        return withContext(Dispatchers.IO) { prepareAndPlay(context, key, requestId, file, onEnded) }
    }

    private fun prepareAndPlay(
        context: Context,
        key: String,
        requestId: Long,
        file: File,
        onEnded: () -> Unit,
    ): Boolean {
        if (playbackRequestId != requestId || currentKey != key || !file.isFile) return false
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    stopIfCurrent(requestId, "perda de foco de áudio")
                }
            }
            .setWillPauseWhenDucked(false)
            .build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            GameLog.log("iiDB", "reprodução cancelada: foco de áudio não concedido")
            clearRequest(requestId)
            return false
        }

        var media: MediaPlayer? = null
        return try {
            val created = MediaPlayer()
            media = created
            created.setAudioAttributes(attributes)
            created.setDataSource(file.absolutePath)
            created.isLooping = false
            created.setOnCompletionListener { completed ->
                releasePlayer(completed, requestId, "soundbite concluído")
            }
            created.setOnErrorListener { failed, what, extra ->
                GameLog.log("iiDB", "erro do player local; what=$what; extra=$extra")
                releasePlayer(failed, requestId, "erro de reprodução")
                true
            }
            created.prepare()
            val accepted = synchronized(lock) {
                if (playbackRequestId != requestId || currentKey != key || player != null) {
                    false
                } else {
                    player = created
                    audioManager = manager
                    focusRequest = request
                    onPlaybackEnded = onEnded
                    created.start()
                    true
                }
            }
            if (!accepted) {
                created.release()
                manager.abandonAudioFocusRequest(request)
                return false
            }
            GameLog.log("iiDB", "soundbite iniciado; arquivo em cache; jogo=$key")
            true
        } catch (error: Exception) {
            GameLog.error("iiDB", "falha ao preparar soundbite local", error)
            val focusToAbandon = synchronized(lock) {
                if (media != null && player === media) {
                    player = null
                    onPlaybackEnded = null
                    takeAudioFocusLocked()
                } else {
                    AudioFocusState(null, null)
                }
            }
            media?.let { runCatching { it.release() } }
            if (focusToAbandon.request == null) {
                manager.abandonAudioFocusRequest(request)
            } else {
                abandonAudioFocus(focusToAbandon)
            }
            clearRequest(requestId)
            false
        }
    }

    private fun releasePlayer(target: MediaPlayer, requestId: Long, reason: String) {
        val releaseState = synchronized(lock) {
            if (player === target && playbackRequestId == requestId) {
                player = null
                currentKey = null
                val callback = onPlaybackEnded.also { onPlaybackEnded = null }
                Triple(true, callback, takeAudioFocusLocked())
            } else {
                Triple(false, null, AudioFocusState(null, null))
            }
        }
        runCatching { target.release() }
        if (!releaseState.first) return
        abandonAudioFocus(releaseState.third)
        GameLog.log("iiDB", reason)
        releaseState.second?.let { Handler(Looper.getMainLooper()).post { it() } }
    }

    private fun takeAudioFocusLocked(): AudioFocusState {
        val state = AudioFocusState(audioManager, focusRequest)
        audioManager = null
        focusRequest = null
        return state
    }

    private fun abandonAudioFocus(state: AudioFocusState) {
        if (state.manager != null && state.request != null) {
            runCatching { state.manager.abandonAudioFocusRequest(state.request) }
        }
    }

    private fun releasePrevious(previous: MediaPlayer?, focus: AudioFocusState, reason: String) {
        previous?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        abandonAudioFocus(focus)
        if (previous != null) GameLog.log("iiDB", "soundbite interrompido; motivo=$reason")
    }

    private fun clearRequest(requestId: Long) {
        synchronized(lock) {
            if (playbackRequestId == requestId) currentKey = null
        }
    }

    private fun stopIfCurrent(requestId: Long, reason: String) {
        val old: MediaPlayer?
        val focus: AudioFocusState
        synchronized(lock) {
            if (playbackRequestId != requestId) return
            playbackRequestId += 1
            currentKey = null
            old = player
            player = null
            onPlaybackEnded = null
            focus = takeAudioFocusLocked()
        }
        releasePrevious(old, focus, reason)
    }

    fun stop(reason: String = "solicitação de parada") {
        val old: MediaPlayer?
        val focus: AudioFocusState
        synchronized(lock) {
            playbackRequestId += 1
            currentKey = null
            old = player
            player = null
            onPlaybackEnded = null
            focus = takeAudioFocusLocked()
        }
        releasePrevious(old, focus, reason)
    }

    fun isPlaying(key: String): Boolean = currentKey == key && player?.isPlaying == true
}
