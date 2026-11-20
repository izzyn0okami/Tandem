package app.lawnchair.gamemode

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.yausername.youtubedl_android.YoutubeDL
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
        initOutcome?.let {
            GameLog.log("tema musical", "inicialização yt-dlp já resolvida: ${if (it) "sucesso" else "falha em tentativa anterior"}")
            return it
        }
        synchronized(lock) {
            initOutcome?.let { return it }
            GameLog.log("tema musical", "inicializando yt-dlp")
            val outcome = try {
                YoutubeDL.init(context.applicationContext)
                GameLog.log("tema musical", "yt-dlp inicializado com sucesso")
                true
            } catch (e: Exception) {
                GameLog.error("tema musical", "falha ao inicializar yt-dlp", e)
                false
            }
            initOutcome = outcome
            return outcome
        }
    }

    /** Busca e toca o tema de [title]; não faz nada se o mesmo jogo ([key]) já estiver tocando. */
    fun play(context: Context, key: String, title: String, platform: String) {
        GameLog.log("tema musical", "solicitação recebida; jogo=\"$title\"; plataforma=\"$platform\"; ativado=${GameModeSettings.isThemeMusicEnabled(context)}")
        if (currentKey == key && player != null) {
            GameLog.log("tema musical", "ignorado: este jogo já possui um player ativo")
            return
        }
        stop()
        currentKey = key
        if (!ensureInitialized(context)) {
            GameLog.log("tema musical", "busca cancelada: yt-dlp não inicializou")
            if (currentKey == key) currentKey = null
            return
        }
        val url = try {
            GameLog.log("tema musical", "iniciando pesquisa no YouTube")
            val request = YoutubeDLRequest("ytsearch1:$title $platform theme song soundtrack")
            request.addOption("-f", "bestaudio/best")
            val result = YoutubeDL.getInfo(request)
            GameLog.log("tema musical", "pesquisa concluída; resultado=${if (result.url.isNullOrBlank()) "sem URL" else "URL de áudio recebida"}")
            result.url
        } catch (e: Exception) {
            GameLog.error("tema musical", "falha na pesquisa do tema de $title", e)
            null
        }
        if (url.isNullOrBlank() || currentKey != key) {
            GameLog.log("tema musical", "reprodução não iniciada; urlVazia=${url.isNullOrBlank()}; solicitaçãoAindaAtual=${currentKey == key}")
            if (currentKey == key) currentKey = null
            return
        }

        var media: MediaPlayer? = null
        try {
            media = MediaPlayer()
            media.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            media.setOnPreparedListener { preparedPlayer ->
                runCatching {
                    preparedPlayer.start()
                    GameLog.log("tema musical", "MediaPlayer preparado e reprodução iniciada")
                }.onFailure { error ->
                    GameLog.error("tema musical", "falha ao iniciar áudio preparado", error)
                    synchronized(lock) {
                        if (player === preparedPlayer) player = null
                    }
                    runCatching { preparedPlayer.release() }
                    if (currentKey == key) currentKey = null
                }
            }
            media.setOnErrorListener { mp, what, extra ->
                GameLog.log("tema musical", "erro assíncrono do MediaPlayer; what=$what; extra=$extra")
                synchronized(lock) {
                    if (player === mp) player = null
                }
                runCatching { mp.release() }
                if (currentKey == key) currentKey = null
                true
            }
            media.setDataSource(url)
            media.isLooping = true
            synchronized(lock) {
                if (currentKey == key) {
                    player = media
                } else {
                    media.release()
                    GameLog.log("tema musical", "player descartado: jogo mudou antes da preparação")
                    return
                }
            }
            GameLog.log("tema musical", "fonte de áudio configurada; iniciando preparação assíncrona")
            media.prepareAsync()
        } catch (e: Exception) {
            GameLog.error("tema musical", "falha ao configurar/preparar áudio de $title", e)
            media?.let { failedPlayer ->
                synchronized(lock) { if (player === failedPlayer) player = null }
                runCatching { failedPlayer.release() }
            }
            if (currentKey == key) currentKey = null
        }
    }

    /** Para e libera o player atual, se houver; seguro de chamar de qualquer thread. */
    fun stop() {
        val hadPlayer = player != null
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
        if (hadPlayer) GameLog.log("tema musical", "player interrompido e liberado")
    }

    fun isPlaying(key: String) = currentKey == key && player != null
}
