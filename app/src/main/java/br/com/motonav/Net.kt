package br.com.motonav

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import br.com.motonav.dash.DashLog

/**
 * Sabe se o celular tem internet DE VERDADE (rede "validada" pelo Android). O Wi-Fi Direct do
 * painel não conta: ele não tem internet. Um Wi-Fi de casa fraco, sem saída, também deixa de
 * contar quando o Android percebe.
 */
object Net {
    @Volatile var online = true
        private set
    private var started = false

    @Synchronized
    fun start(ctx: Context) {
        if (started) return
        started = true
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        online = cm.getNetworkCapabilities(cm.activeNetwork)?.let { hasInternet(it) } ?: false
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = set(hasInternet(caps))
            override fun onLost(network: Network) = set(false)
        })
    }

    private fun hasInternet(c: NetworkCapabilities) =
        c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    private fun set(v: Boolean) {
        if (v == online) return
        online = v
        DashLog.d(if (v) "Internet voltou" else "SEM INTERNET: usando mapa e rotas guardados no celular")
    }
}
