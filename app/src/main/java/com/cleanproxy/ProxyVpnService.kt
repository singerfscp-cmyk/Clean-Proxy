package com.cleanproxy

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer

class ProxyVpnService : VpnService() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        @Volatile var running = false
        @Volatile var listener: ((Boolean, String?) -> Unit)? = null
    }

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopVpn(null)
            return START_NOT_STICKY
        }
        val host = intent?.getStringExtra("host") ?: return START_NOT_STICKY
        val port = intent.getIntExtra("port", 0)
        val user = intent.getStringExtra("user")
        val pass = intent.getStringExtra("pass")
        Thread { startVpn(host, port, user, pass) }.start()
        return START_NOT_STICKY
    }

    private fun startVpn(host: String, port: Int, user: String?, pass: String?) {
        try {
            if (running) stopVpn(null, notify = false)
            val addr = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
                ?: throw Exception("No IPv4 address for proxy host")
            val ipInt = ByteBuffer.wrap(addr.address).int
            val b = Builder()
                .setSession("Clean Proxy")
                .setMtu(1500)
                .addAddress("198.18.0.1", 32)
                .addAddress("fc00::1", 128)
                .addDnsServer("198.18.0.2")
            // Route everything except the proxy server itself (prevents a loop)
            for ((net, len) in routesExcluding(ipInt)) b.addRoute(net, len)
            b.addRoute("::", 0)
            val fd = b.establish() ?: throw Exception("VPN permission not granted")
            tun = fd

            fun q(s: String) = "'" + s.replace("'", "''") + "'"
            val auth = if (!user.isNullOrEmpty())
                "  username: ${q(user)}\n  password: ${q(pass ?: "")}\n" else ""
            val cfg = """
tunnel:
  mtu: 1500
  ipv4: 198.18.0.1
  ipv6: 'fc00::1'
socks5:
  port: $port
  address: ${addr.hostAddress}
  udp: 'udp'
$auth
mapdns:
  address: 198.18.0.2
  port: 53
  network: 100.64.0.0
  netmask: 255.192.0.0
  cache-size: 10000
misc:
  task-stack-size: 81920
  connect-timeout: 5000
  tcp-read-write-timeout: 60000
  log-file: stderr
  log-level: warn
""".trimStart()
            val f = File(cacheDir, "tproxy.yml")
            f.writeText(cfg)
            TProxyService.TProxyStartService(f.absolutePath, fd.fd)
            running = true
            listener?.invoke(true, null)
        } catch (t: Throwable) {
            stopVpn(t.message ?: t.javaClass.simpleName)
        }
    }

    private fun routesExcluding(ip: Int): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        for (len in 1..32) {
            val shift = 32 - len
            val net = (ip xor (1 shl shift)) and (-1 shl shift)
            val bytes = ByteBuffer.allocate(4).putInt(net).array()
            out.add(InetAddress.getByAddress(bytes).hostAddress!! to len)
        }
        return out
    }

    private fun stopVpn(error: String?, notify: Boolean = true) {
        try { if (running) TProxyService.TProxyStopService() } catch (_: Throwable) { }
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        running = false
        if (notify) listener?.invoke(false, error)
        stopSelf()
    }

    override fun onRevoke() { stopVpn(null) }
    override fun onDestroy() { if (running) stopVpn(null); super.onDestroy() }
}
