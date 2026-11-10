package br.com.motonav.dash

import java.io.ByteArrayOutputStream

/**
 * Quadros "EC-BTP" que os painéis novos (Zontes 2025+, ID CARBIT… sem "mac=" no QR) trocam pelo
 * Bluetooth LE (serviço 0000b360-…, característica b362) antes da conexão de vídeo.
 *
 *     0x24 | CMD | LEN | payload | XOR | 0x0A
 *     LEN = payload + 4;  XOR = 0x24 ^ CMD ^ LEN ^ bytes do payload
 *
 * Formato e ordem dos comandos aprendidos com o projeto aberto ZC-Panel (que funciona na 350R
 * 2026) e os que ele cita (MOTO-HUB, open-cfmoto). Código escrito do zero para este app.
 */
object EcBtp {
    const val START = 0x24
    const val END = 0x0A
    const val MAX_PAYLOAD = 251

    const val SYNC_TIME = 0x01          // painel pede a hora (resposta: epoch ms + fuso, 8 bytes LE)
    const val CLIENT_INFO = 0x30        // celular se apresenta
    const val BUILD_NET = 0x50          // "como montamos a rede?"  status 2 = hotspot do celular
    const val BUILD_NET_FINISH = 0x51
    const val AP_INFO = 0x52            // celular manda nome/senha do hotspot
    const val CAR_NET_INFO = 0x53       // painel conta o IP que recebeu
    const val QUERY_TIME = 0x55         // painel pede a hora em texto
    const val HANDSHAKE_RESPONSE = 0x58

    class Frame(val cmd: Int, val payload: ByteArray) {
        val text: String get() = String(payload, Charsets.UTF_8)
        fun field(name: String): String? {
            val m = Regex("\"" + Regex.escape(name) + "\"\\s*:\\s*(\"([^\"]*)\"|-?[0-9]+)").find(text) ?: return null
            return m.groupValues[2].ifEmpty { m.groupValues[1] }
        }
        override fun toString(): String = String.format("0x%02X", cmd) + " " + Pxc.preview(payload, 200)
    }

    fun build(cmd: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= MAX_PAYLOAD) { "payload grande demais" }
        val out = ByteArray(payload.size + 5)
        out[0] = START.toByte()
        out[1] = cmd.toByte()
        out[2] = (payload.size + 4).toByte()
        payload.copyInto(out, 3)
        var x = 0
        for (i in 0 until payload.size + 3) x = x xor (out[i].toInt() and 0xFF)
        out[out.size - 2] = x.toByte()
        out[out.size - 1] = END.toByte()
        return out
    }

    fun clientInfo(phoneId: String): ByteArray {
        val id = phoneId.filter { it.isLetterOrDigit() }.take(32)
        return build(CLIENT_INFO, """{"phoneType":0,"phoneID":"$id"}""".toByteArray())
    }

    /** null se não couber num quadro (SSID/senha longos demais). */
    fun apInfo(ssid: String, pwd: String, auth: String, phoneIp: String): ByteArray? {
        val json = org.json.JSONObject()
            .put("ssid", ssid).put("pwd", pwd).put("auth", auth).put("mac", "").put("ip", phoneIp)
            .toString().toByteArray()
        return if (json.size > MAX_PAYLOAD) null else build(AP_INFO, json)
    }

    /** Resposta ao 0x01: epoch em ms já somado ao fuso (sem horário de verão), 8 bytes LE. */
    fun syncTime(now: Long = System.currentTimeMillis()): ByteArray {
        val v = now + java.util.TimeZone.getDefault().rawOffset
        return build(SYNC_TIME, ByteArray(8) { i -> ((v shr (8 * i)) and 0xFF).toByte() })
    }

    /** Resposta ao 0x55: "dd.MM.yyyy HH:mm:ss:zzz". */
    fun queryTime(now: Long = System.currentTimeMillis()): ByteArray {
        val f = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss:zzz", java.util.Locale.US)
        return build(QUERY_TIME, f.format(java.util.Date(now)).toByteArray().take(MAX_PAYLOAD).toByteArray())
    }

    fun ips(f: Frame): List<String> =
        Regex("\"ip\"\\s*:\\s*\"([0-9]{1,3}(\\.[0-9]{1,3}){3})\"").findAll(f.text).map { it.groupValues[1] }.toList()

    /** Junta pedaços das notificações BLE (um quadro pode vir picotado, ou vários juntos). */
    class Assembler {
        private val buf = ByteArrayOutputStream()

        @Synchronized
        fun feed(chunk: ByteArray): List<Frame> {
            buf.write(chunk)
            var data = buf.toByteArray()
            val frames = mutableListOf<Frame>()
            var i = 0
            while (true) {
                while (i < data.size && (data[i].toInt() and 0xFF) != START) i++
                if (data.size - i < 3) break
                val total = (data[i + 2].toInt() and 0xFF) + 1
                if (total < 5) { i++; continue }
                if (data.size - i < total) break
                val f = parse(data, i, total)
                if (f != null) { frames += f; i += total } else i++
            }
            data = data.copyOfRange(i, data.size)
            buf.reset()
            if (data.size <= 1024) buf.write(data)
            return frames
        }

        private fun parse(d: ByteArray, off: Int, total: Int): Frame? {
            if ((d[off + total - 1].toInt() and 0xFF) != END) return null
            var x = 0
            for (k in off until off + total - 2) x = x xor (d[k].toInt() and 0xFF)
            if (x.toByte() != d[off + total - 2]) return null
            return Frame(d[off + 1].toInt() and 0xFF, d.copyOfRange(off + 3, off + total - 2))
        }
    }
}
