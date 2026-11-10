package br.com.motonav.dash

import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.view.Surface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingDeque
import kotlin.concurrent.thread

/** Quem desenha a imagem que vai para o painel (o mapa, ou a tela de teste). */
interface FrameSource {
    fun attach(surface: Surface, width: Int, height: Int)
    fun detach()
}

/** Codificador H.264: tudo que for desenhado em [surface] vira quadros em [frames]. */
class VideoEncoder(val width: Int, val height: Int, private val bitRate: Int) {

    class Frame(val data: ByteArray, val key: Boolean)

    val frames = LinkedBlockingDeque<Frame>(90)
    @Volatile var config: ByteArray? = null
    @Volatile private var running = false
    private var codec: MediaCodec? = null
    lateinit var surface: Surface
        private set

    fun start() {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000L) // repete o quadro se nada mudar
        }
        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        // Bitrate constante: no modo padrão (VBR) o codificador do Samsung passou de 7 Mbps com o
        // mapa em movimento (limite era 3 Mbps), congestionando o Wi-Fi Direct e engasgando o painel.
        val cbr = runCatching {
            c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).encoderCapabilities
                ?.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR) == true
        }.getOrDefault(false)
        if (cbr) fmt.setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        DashLog.d("codificador ${c.name}: bitrate ${if (cbr) "constante (CBR)" else "variável (CBR não suportado)"}")
        c.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = c.createInputSurface()
        c.start()
        codec = c
        running = true
        thread(name = "rv-drain") { drainLoop() }
    }

    fun requestKeyFrame() {
        runCatching {
            codec?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        }
    }

    fun stop() {
        running = false
        Thread.sleep(50)
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        runCatching { surface.release() }
        codec = null
        frames.clear()
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        while (running) {
            val c = codec ?: break
            val idx = try { c.dequeueOutputBuffer(info, 10_000) } catch (e: Exception) { break }
            if (idx < 0) continue
            val buf = c.getOutputBuffer(idx)
            if (buf != null && info.size > 0) {
                val data = ByteArray(info.size)
                buf.position(info.offset)
                buf.limit(info.offset + info.size)
                buf.get(data)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    config = data
                } else {
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    if (!frames.offerLast(Frame(data, key))) {
                        frames.clear()      // painel parou de consumir: recomeça num quadro-chave
                        requestKeyFrame()
                    }
                }
            }
            runCatching { c.releaseOutputBuffer(idx, false) }
        }
    }
}

/** Tela de teste (etapa 3b). Fica como reserva caso o mapa não consiga abrir. */
class TestPatternSource : FrameSource {
    @Volatile private var running = false

    override fun attach(surface: Surface, width: Int, height: Int) {
        running = true
        thread(name = "rv-test-pattern") {
            val bg = Paint().apply { color = Color.rgb(20, 24, 32) }
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE; textSize = height * 0.14f; isFakeBoldText = true
                textAlign = Paint.Align.CENTER
            }
            val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(128, 255, 128); textSize = height * 0.08f
                textAlign = Paint.Align.CENTER
            }
            val bar = Paint().apply { color = Color.rgb(41, 121, 255) }
            val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
            var n = 0
            while (running) {
                try {
                    val canvas = surface.lockHardwareCanvas()
                    try {
                        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
                        val x = (n * 8 % (width + 200)) - 200f
                        canvas.drawRect(x, height * 0.80f, x + 200f, height * 0.90f, bar)
                        canvas.drawText("MotoNav", width / 2f, height * 0.38f, title)
                        canvas.drawText("teste de vídeo  " + clock.format(Date()), width / 2f, height * 0.58f, sub)
                    } finally {
                        surface.unlockCanvasAndPost(canvas)
                    }
                } catch (e: Exception) {
                    break
                }
                n++
                Thread.sleep(40)
            }
        }
    }

    override fun detach() {
        running = false
    }
}
