package br.com.motonav.dash

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Enquadramento dos pacotes do painel (confirmado no código e no log do Carbit):
 * cabeçalho de 16 bytes little-endian = [cmd][tamanhoTotal][cmd xor tamanhoTotal][0] + payload.
 * A resposta de um comando X é sempre X+1.
 */
object Pxc {
    // portas
    const val PORT_WAKEUP = 10930      // painel escuta; o celular conecta
    const val PORT_PXC = 10922         // celular escuta; painel conecta (C2P e P2C)
    const val PORT_RV_CTRL = 10921     // celular escuta; controle do vídeo
    const val PORT_RV_DATA = 10920     // celular escuta; vídeo H.264

    // tipos de canal (1º pacote de cada conexão na 10922)
    const val CONN_C2P = 0x00010000
    const val CONN_P2C = 0x00020000
    const val CONN_RV = 0x00030000

    // comandos conhecidos
    const val CMD_WAKEUP = 0x70000010
    const val CMD_CLIENT_INFO = 0x00010010
    const val CMD_CHECK_SN = 0x000103E0
    const val CMD_KEEPALIVE = 0x70000000
    const val CMD_P2C_DISPLAY = 0x00020020
    const val CMD_UNSUPPORTED = -2147483632 // 0x80000010

    class Packet(val cmd: Int, val payload: ByteArray) {
        fun text(): String = String(payload, Charsets.UTF_8)
    }

    class MagicException(val header: ByteArray) : IOException("cabeçalho fora do formato PXC")

    @Throws(IOException::class)
    fun read(input: InputStream): Packet {
        val header = ByteArray(16)
        readFully(input, header)
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = bb.getInt(0)
        val len = bb.getInt(4)
        val magic = bb.getInt(8)
        if ((cmd xor len) != magic || len < 16 || len > 16 * 1024 * 1024) throw MagicException(header)
        val payload = ByteArray(len - 16)
        if (payload.isNotEmpty()) readFully(input, payload)
        return Packet(cmd, payload)
    }

    @Throws(IOException::class)
    fun write(output: OutputStream, cmd: Int, payload: ByteArray = ByteArray(0)) {
        val len = 16 + payload.size
        val buf = ByteBuffer.allocate(len).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd).putInt(len).putInt(cmd xor len).putInt(0).put(payload)
        synchronized(output) {
            output.write(buf.array())
            output.flush()
        }
    }

    /** Comandos para os quais o Carbit NÃO manda resposta (b1/v0.f()). */
    fun expectsNoReply(cmd: Int): Boolean =
        cmd == 262160 || cmd == 196656 || cmd == 196640 || cmd == 196672 || cmd == 66224 ||
            (cmd and 0xFF000000.toInt()) == 0x60000000

    fun hex(cmd: Int) = String.format("0x%08X", cmd)

    /** Mostra o payload como texto se for legível, senão os primeiros bytes em hex. */
    fun preview(payload: ByteArray, max: Int = 400): String {
        if (payload.isEmpty()) return "(vazio)"
        val s = String(payload, Charsets.UTF_8)
        val printable = s.count { it.isLetterOrDigit() || it in " {}[]\":,._-/\\+=\n\r\t" }
        return if (printable >= s.length * 0.9) s.take(max).replace("\n", " ")
        else "[${payload.size} bytes] " + payload.take(32).joinToString(" ") { String.format("%02X", it) }
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
