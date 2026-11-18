package app.lawnchair.gamemode

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest

/**
 * Tema musical do jogo em destaque (recurso experimental, desligado por padrão em Configurações):
 * procura um vídeo no YouTube com o tema/trilha do jogo em destaque e toca só o áudio dele, em
 * repetição, enquanto o botão na tela principal estiver ligado. Não baixa nem salva nada no
 * aparelho — só usa o yt-dlp (empacotado pro Android) pra achar o endereço de áudio direto.
 *
 * Chame [play] sempre fora da thread principal (ela faz chamadas de rede e de processo que podem
 * demorar vários segundos); a tela já cuida disso rodando num `Dispatchers.IO`. [stop] pode ser
 * chamado de qualquer thread, inclusive a principal — nunca espera uma busca em andamento.
 */
object GameThemeMusic {

    private val lock = Any()

    @Volatile
    private var initOutcome: Boolean? = null

    @Volatile
    private var player: MediaPlayer? = null

    @Volatile
    private var currentKey: String? = null

    private fun ensureInitialized(context: Context): Boolean {
        initOutcome?.let { return it }
        synchronized(lock) {
            initOutcome?.let { return it }
            val outcome = try {
                YoutubeDL.init(context.applicationContext)
                true
            } catch (e: YoutubeDLException) {
                GameLog.error("tema musical", "não consegui preparar o yt-dlp", e)
                false
            }
            initOutcome = outcome
            return outcome
        }
    }

    /** Busca e toca o tema de [title]; não faz nada se o mesmo jogo ([key]) já estiver tocando. */
    fun play(context: Context, key: String, title: String, platform: String) {
        if (currentKey == key && player != null) return
        stop()
        currentKey = key
        if (!ensureInitialized(context)) {
            if (currentKey == key) currentKey = null
            return
        }
        val url = try {
            val request = YoutubeDLRequest("ytsearch1:$title $platform theme song soundtrack")
            request.addOption("-f", "bestaudio/best")
            YoutubeDL.getInfo(request).url
        } catch (e: Exception) {
            // YoutubeDLException, InterruptedException ou CanceledException: sem tema pra esse jogo.
            GameLog.error("tema musical", "não achei o tema de $title", e)
            null
        }
        // Enquanto buscava, o usuário pode ter trocado de jogo em destaque ou apertado "parar".
        if (url.isNullOrBlank() || currentKey != key) {
            if (currentKey == key) currentKey = null
            return
        }
        runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                setDataSource(url)
                isLooping = true
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        }.onSuccess { media ->
            synchronized(lock) {
                if (currentKey == key) {
                    player = media
                } else {
                    runCatching { media.release() }
                }
            }
        }.onFailure {
            GameLog.error("tema musical", "não consegui tocar o tema de $title", it)
            if (currentKey == key) currentKey = null
        }
    }

    /** Para e libera o player atual, se houver; seguro de chamar de qualquer thread. */
    fun stop() {
        currentKey = null
        val old = synchronized(lock) {
            val previous = player
            player = null
            previous
        }
        old?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
    }

    fun isPlaying(key: String) = currentKey == key && player != null
}
