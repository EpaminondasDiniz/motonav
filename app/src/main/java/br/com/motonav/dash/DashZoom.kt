package br.com.motonav.dash

/**
 * Zoom do mapa do painel pela velocidade: perto na cidade, mais longe na estrada (mostra as curvas
 * à frente com antecedência). Os botões do guidão somam um ajuste (+/- 0,5 por toque).
 */
object DashZoom {
    private val table = arrayOf(
        0.0 to 17.0, 20.0 to 17.0, 40.0 to 16.4, 60.0 to 15.9, 80.0 to 15.5, 100.0 to 15.1, 130.0 to 14.8
    )
    @Volatile private var speedKmh = 0.0
    @Volatile var offset = 0.0
        private set

    /** Velocidade suavizada (o zoom não fica pulando a cada leitura). */
    fun onSpeed(kmh: Double) {
        speedKmh += (kmh - speedKmh) * 0.25
    }

    /**
     * Inclinação da câmera: na cidade (até 40 km/h) bem baixa, 60°, "dentro da rua", com os
     * prédios em 3D; na estrada (80 km/h ou mais) volta a 45°, para ver mais longe.
     */
    fun tilt(): Double {
        val s = speedKmh
        val f = ((s - 40.0) / 40.0).coerceIn(0.0, 1.0)
        return 60.0 - 15.0 * f
    }

    fun nudge(d: Double) { offset = (offset + d).coerceIn(-2.5, 2.5) }
    fun reset() { offset = 0.0 }

    fun current(): Double {
        val s = speedKmh
        var z = table.last().second
        for (i in 0 until table.size - 1) {
            val (s0, z0) = table[i]
            val (s1, z1) = table[i + 1]
            if (s <= s1) { z = z0 + (z1 - z0) * ((s - s0) / (s1 - s0)).coerceIn(0.0, 1.0); break }
        }
        return (z + offset).coerceIn(13.5, 18.5)
    }
}
