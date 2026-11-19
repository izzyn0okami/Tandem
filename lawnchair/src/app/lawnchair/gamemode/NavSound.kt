package app.lawnchair.gamemode

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Sons curtos gerados no próprio app: clique de navegação e jingle de conquista. Não dependem dos
 * "sons de toque" do Android (que costumam vir desligados) e seguem o volume de mídia.
 */
object NavSound {

    private const val SAMPLE_RATE = 22_050
    private const val PEAK = 0.35

    private val clickTrack: AudioTrack? by lazy { build(listOf(1_400.0 to 35)) }

    private val unlockTrack: AudioTrack? by lazy { build(listOf(880.0 to 110, 1_175.0 to 110, 1_568.0 to 220)) }

    private val masteryTrack: AudioTrack? by lazy { build(listOf(784.0 to 110, 988.0 to 110, 1_175.0 to 110, 1_568.0 to 320)) }

    /** Cada nota é (frequência em Hz, duração em ms), com decaimento suave. */
    private fun build(notes: List<Pair<Double, Int>>): AudioTrack? = runCatching {
        val pcm = notes.flatMap { (frequency, ms) ->
            val count = SAMPLE_RATE * ms / 1000
            List(count) { i ->
                val fade = 1.0 - i.toDouble() / count
                (sin(2 * PI * frequency * i / SAMPLE_RATE) * fade * fade * PEAK * Short.MAX_VALUE).toInt().toShort()
            }
        }.toShortArray()
        AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .also { it.write(pcm, 0, pcm.size) }
    }.getOrNull()

    private fun play(track: AudioTrack?) {
        val audio = track ?: return
        runCatching {
            audio.stop()
            audio.reloadStaticData()
            audio.play()
        }
    }

    @Synchronized
    fun click() = play(clickTrack)

    /** Jingle de três notas para conquista desbloqueada. */
    @Synchronized
    fun unlock() = play(unlockTrack)

    /** Jingle maior, para o aviso de "Mastered"/"Completed" (todas as conquistas do jogo obtidas). */
    @Synchronized
    fun mastery() = play(masteryTrack)
}
