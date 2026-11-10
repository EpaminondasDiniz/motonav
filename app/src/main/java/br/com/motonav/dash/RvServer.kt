package br.com.motonav.dash

import android.content.Context
import br.com.motonav.Compat
import java.io.BufferedOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Canais de vídeo do painel (formato confirmado no código e no log do Carbit).
 *
 * Porta 10921 (controle), cabeçalho de 8 bytes LE: [short cmd][short tamanho][int token] + corpo
 *   0x10 pedido de config (800x480, H.264...)  -> 0x11 [int encoder=2][short w][short h]
 *   0x40 sinal de vida                           -> 0x41
 *   0x70 iniciar vídeo                           -> 0x71
 * Porta 10920 (dados): o painel manda 0x72 (~30x/s) pedindo o próximo quadro.
 *   resposta: [int tamanho LE][quadro H.264]  ou  [00 00 00 00] se não houver quadro.
 */
class RvServer(private val ctx: Context) {

    companion object {
        const val REQ_CONFIG = 0x10
        const val RLY_CONFIG = 0x11
        const val REQ_HEARTBEAT = 0x40
        const val RLY_HEARTBEAT = 0x41
        const val REQ_DATA_START = 0x70
        const val RLY_DATA_START = 0x71
        const val REQ_DATA_NEXT = 0x72
        const val RLY_UN_SUPPORT = 0x7F02
        const val MAX_BITRATE = 3_000_000
    }

    @Volatile private var width = 800
    @Volatile private var height = 480
    @Volatile private var bitRate = 4_000_000
    @Volatile private var encoder: VideoEncoder? = null
    private var source: FrameSource? = null

    // ------------------------------------------------------------- controle 10921

    fun handleCtrl(s: Socket) {
        val input = s.getInputStream()
        val out = BufferedOutputStream(s.getOutputStream())
        while (true) {
            val (cmd, body) = readCmd(input)
            when (cmd) {
                REQ_CONFIG -> {
                    parseConfig(body)
                    val reply = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(2) // 2 = H.264
                        .putShort(width.toShort())
                        .putShort(height.toShort())
                        .array()
                    writeCmd(out, RLY_CONFIG, reply)
                    DashLog.d("RV -> config aceita: H.264 ${width}x$height")
                }
                REQ_HEARTBEAT -> writeCmd(out, RLY_HEARTBEAT)
                REQ_DATA_START -> {
                    startEncoder()
                    writeCmd(out, RLY_DATA_START)
                    DashLog.d("RV -> início do vídeo confirmado")
                }
                else -> {
                    DashLog.d("RV <- comando não tratado 0x%04X (%d bytes)".format(cmd, body.size))
                    writeCmd(out, RLY_UN_SUPPORT)
                }
            }
        }
    }

    private fun parseConfig(body: ByteArray) {
        val b = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        width = b.short.toInt() and 0xFFFF
        height = b.short.toInt() and 0xFFFF
        val fps = b.int
        val enc = b.int
        val codec = b.int
        b.short; b.short // qualidade mín/máx
        val br = b.int
        // O painel pede 4 Mbps, mas no modo captura (mapa andando) ele chegou a fechar o vídeo
        // recebendo ~500 KB/s. Limitamos a 3 Mbps para aliviar o Wi-Fi Direct e o decodificador.
        if (br > 0) bitRate = minOf(br, MAX_BITRATE)
        DashLog.d("RV <- config: ${width}x$height fps=$fps encoder=$enc codec=$codec bitrate=$br")
        Compat.onScreen(ctx, width, height)
    }

    // ------------------------------------------------------------- dados 10920

    fun handleData(s: Socket) {
        val input = s.getInputStream()
        val out = BufferedOutputStream(s.getOutputStream(), 256 * 1024)
        val enc = startEncoder()
        enc.requestKeyFrame()
        var waitingKey = true
        var sent = 0
        var bytes = 0L
        var lastReport = System.currentTimeMillis()
        val zero = ByteArray(4)
        val lenBuf = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)

        while (true) {
            val (cmd, _) = readCmd(input)
            if (cmd != REQ_DATA_NEXT) {
                DashLog.d("RV-DATA <- comando inesperado 0x%04X".format(cmd))
                continue
            }
            var frame = enc.frames.poll(25, TimeUnit.MILLISECONDS)
            // Depois de conectar, só começa a mandar a partir de um quadro-chave.
            while (frame != null && waitingKey && !frame.key) frame = enc.frames.poll()
            if (frame == null) {
                out.write(zero)
                out.flush()
                continue
            }
            if (frame.key) {
                waitingKey = false
                enc.config?.let { cfg -> // SPS/PPS antes de cada quadro-chave
                    lenBuf.clear(); lenBuf.putInt(cfg.size)
                    out.write(lenBuf.array()); out.write(cfg)
                }
            }
            lenBuf.clear(); lenBuf.putInt(frame.data.size)
            out.write(lenBuf.array())
            out.write(frame.data)
            out.flush()
            sent++; bytes += frame.data.size
            val now = System.currentTimeMillis()
            if (now - lastReport > 5000) {
                DashLog.d("RV -> $sent quadros enviados (${bytes / 1024} KB) nos últimos 5 s")
                sent = 0; bytes = 0; lastReport = now
            }
        }
    }

    // ------------------------------------------------------------- utilidades

    @Synchronized
    private fun startEncoder(): VideoEncoder {
        encoder?.let { e ->
            if (e.width == width && e.height == height) return e
            stop()
        }
        val enc = VideoEncoder(width, height, bitRate)
        enc.start()
        encoder = enc
        DashLog.d("codificador H.264 iniciado ${width}x$height @ ${bitRate / 1000} kbps")
        source = try {
            DashMapSource(ctx).also { it.attach(enc.surface, width, height) }
        } catch (t: Throwable) {
            DashLog.d("mapa do painel falhou (${t.message}); usando tela de teste")
            TestPatternSource().also { it.attach(enc.surface, width, height) }
        }
        return enc
    }

    @Synchronized
    fun stop() {
        runCatching { source?.detach() }
        source = null
        encoder?.stop()
        encoder = null
    }

    private fun readCmd(input: InputStream): Pair<Int, ByteArray> {
        val h = ByteArray(8)
        readFully(input, h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.short.toInt() and 0xFFFF
        val len = b.short.toInt() and 0xFFFF
        val body = ByteArray(len)
        if (len > 0) readFully(input, body)
        return cmd to body
    }

    private fun writeCmd(out: OutputStream, cmd: Int, body: ByteArray = ByteArray(0)) {
        val h = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(cmd.toShort()).putShort(body.size.toShort()).putInt(0).array()
        synchronized(out) {
            out.write(h)
            if (body.isNotEmpty()) out.write(body)
            out.flush()
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n <= 0) throw IOException("conexão fechada pelo painel")
            off += n
        }
    }
}
