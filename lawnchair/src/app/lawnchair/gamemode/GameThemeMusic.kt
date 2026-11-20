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

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var onPlaybackEnded: (() -> Unit)? = null

    suspend fun play(context: Context, key: String, title: String, platform: String, onEnded: () -> Unit = {}): Boolean {
        if (currentKey == key && player?.isPlaying == true) return true
        stop("nova solicitação de soundbite")
        currentKey = key

        val apiKey = IidbKeyStore.get(context)
        if (apiKey.isNullOrBlank()) {
            GameLog.log("iiDB", "reprodução indisponível: API key não configurada")
            if (currentKey == key) currentKey = null
            return false
        }

        val file = IidbSoundbites.findSoundbite(context, apiKey, title, platform)
        if (file == null || currentKey != key) {
            if (currentKey == key) currentKey = null
            return false
        }
        return withContext(Dispatchers.IO) { prepareAndPlay(context, key, file) }
    }

    private fun prepareAndPlay(context: Context, key: String, file: File): Boolean {
        if (currentKey != key || !file.isFile) return false
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop("perda de foco de áudio")
            }
            .setWillPauseWhenDucked(false)
            .build()
        if (manager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            GameLog.log("iiDB", "reprodução cancelada: foco de áudio não concedido")
            if (currentKey == key) currentKey = null
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
                releasePlayer(completed, "soundbite concluído")
            }
            created.setOnErrorListener { failed, what, extra ->
                GameLog.log("iiDB", "erro do player local; what=$what; extra=$extra")
                releasePlayer(failed, "erro de reprodução")
                true
            }
            created.prepare()
            if (currentKey != key) {
                created.release()
                manager.abandonAudioFocusRequest(request)
                return false
            }
            synchronized(lock) {
                player = created
                audioManager = manager
                focusRequest = request
                onPlaybackEnded = onEnded
            }
            created.start()
            GameLog.log("iiDB", "soundbite iniciado; arquivo em cache; jogo=$key")
            true
        } catch (error: Exception) {
            GameLog.error("iiDB", "falha ao preparar soundbite local", error)
            media?.let { failed ->
                synchronized(lock) {
                    if (player === failed) {
                        player = null
                        onPlaybackEnded = null
                        audioManager = null
                        focusRequest = null
                    }
                }
                runCatching { failed.release() }
            }
            manager.abandonAudioFocusRequest(request)
            if (currentKey == key) currentKey = null
            false
        }
    }

    private fun releasePlayer(target: MediaPlayer, reason: String) {
        val releaseState = synchronized(lock) {
            if (player === target) {
                player = null
                true to onPlaybackEnded.also { onPlaybackEnded = null }
            } else {
                false to null
            }
        }
        runCatching { target.release() }
        if (!releaseState.first) return
        abandonFocus()
        currentKey = null
        GameLog.log("iiDB", reason)
        releaseState.second?.let { Handler(Looper.getMainLooper()).post { it() } }
    }

    private fun abandonFocus() {
        val manager: AudioManager?
        val request: AudioFocusRequest?
        synchronized(lock) {
            manager = audioManager
            request = focusRequest
            audioManager = null
            focusRequest = null
        }
        if (manager != null && request != null) {
            runCatching { manager.abandonAudioFocusRequest(request) }
        }
    }

    fun stop(reason: String = "solicitação de parada") {
        currentKey = null
        val old = synchronized(lock) {
            val previous = player
            player = null
            onPlaybackEnded = null
            previous
        }
        old?.let {
            runCatching { if (it.isPlaying) it.stop() }
            runCatching { it.release() }
        }
        abandonFocus()
        if (old != null) GameLog.log("iiDB", "soundbite interrompido; motivo=$reason")
    }

    fun isPlaying(key: String): Boolean = currentKey == key && player?.isPlaying == true
}
