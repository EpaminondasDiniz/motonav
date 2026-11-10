package br.com.motonav

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import br.com.motonav.dash.DashLink

/**
 * Bloco "Moto" nas configurações rápidas (puxar a barra de notificações): um toque começa a
 * procurar o painel. Abre o app por um instante (o Android só deixa iniciar o serviço de conexão
 * a partir de algo na tela) e ele volta sozinho para segundo plano.
 */
class ConnectTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        val running = DashLink.get(this).isRunning
        qsTile?.apply {
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            label = "Moto"
            if (Build.VERSION.SDK_INT >= 29) subtitle = if (running) "Conectando" else "Toque para conectar"
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val i = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_CONNECT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(i)
        }
    }
}
