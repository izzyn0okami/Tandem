package app.lawnchair.gamemode

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.sin

/**
 * Clique curto de navegação gerado no próprio app. Não depende dos "sons de toque" do Android
 * (que costumam vir desligados) e segue o volume de mídia.
 */
object NavSound {

    private const val SAMPLE_RATE = 22_050
    private const val DURATION_MS = 35
    private const val FREQUENCY = 1_400.0
    private const val PEAK = 0.35

    private val track: AudioTrack? by lazy {
        runCatching {
            val count = SAMPLE_RATE * DURATION_MS / 1000
            val pcm = ShortArray(count) { i ->
                val fade = 1.0 - i.toDouble() / count
                (sin(2 * PI * FREQUENCY * i / SAMPLE_RATE) * fade * fade * PEAK * Short.MAX_VALUE).toInt().toShort()
            }
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
    }

    @Synchronized
    fun click() {
        val audio = track ?: return
        runCatching {
            audio.stop()
            audio.reloadStaticData()
            audio.play()
        }
    }
}
