package com.cleanproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProxyVpnService : VpnService() {
    companion object {
        const val ACTION_START = "start"
        const val ACTION_STOP = "stop"
        @Volatile var running = false
        @Volatile var listener: ((Boolean, String?) -> Unit)? = null
        private val logs = ArrayList<String>()

        @Synchronized fun log(s: String) {
            logs.add(SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + s)
            if (logs.size > 200) logs.removeAt(0)
        }

        @Synchronized fun logText(ctx: Context): String {
            val sb = StringBuilder()
            logs.forEach { sb.append(it).append('\n') }
            val f = File(ctx.cacheDir, "hev.log")
            if (f.exists()) {
                sb.append("--- tunnel log ---\n")
                sb.append(f.readLines().takeLast(60).joinToString("\n"))
            }
            return sb.toString().ifBlank { "(empty)" }
        }
    }

    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            log("Stop requested")
            stopVpn(null)
            return START_NOT_STICKY
        }
        goForeground(intent?.getStringExtra("name") ?: "Proxy")
        val host = intent?.getStringExtra("host")
        if (host == null) { stopSelf(); return START_NOT_STICKY }
        val port = intent.getIntExtra("port", 0)
        val user = intent.getStringExtra("user")
        val pass = intent.getStringExtra("pass")
        val dns = intent.getIntExtra("dns", 0)
        Thread { startVpn(host, port, user, pass, dns) }.start()
        return START_NOT_STICKY
    }

    @Suppress("DEPRECATION")
    private fun goForeground(name: String) {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val b: Notification.Builder
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel("vpn", "VPN status", NotificationManager.IMPORTANCE_LOW))
                b = Notification.Builder(this, "vpn")
            } else b = Notification.Builder(this)
            val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = b.setContentTitle("Clean Proxy").setContentText("SOCKS5 • $name")
                .setSmallIcon(android.R.drawable.ic_lock_lock).setContentIntent(pi).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED)
            else startForeground(1, n)
        } catch (e: Throwable) {
            log("Foreground notification failed: ${e.message}")
        }
    }

    private fun startVpn(host: String, port: Int, user: String?, pass: String?, dns: Int) {
        try {
            if (running) stopVpn(null, notify = false, keepService = true)
            File(cacheDir, "hev.log").delete()
            log("Resolving $host")
            val addr = InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
                ?: throw Exception("No IPv4 address for proxy host")
            log("Proxy IP ${addr.hostAddress}:$port")
            val ipInt = ByteBuffer.wrap(addr.address).int
            val b = Builder()
                .setSession("Clean Proxy")
                .setMtu(1500)
                .addAddress("198.18.0.1", 32)
                .addAddress("fc00::1", 128)
                .addDnsServer(if (dns == 1) "1.1.1.1" else "198.18.0.2")
            for ((net, len) in routesExcluding(ipInt)) b.addRoute(net, len)
            b.addRoute("::", 0)
            val fd = b.establish() ?: throw Exception("VPN permission not granted")
            tun = fd
            log("TUN established")

            fun q(s: String) = "'" + s.replace("'", "''") + "'"
            val auth = if (!user.isNullOrEmpty())
                "  username: ${q(user)}\n  password: ${q(pass ?: "")}\n" else ""
            val mapdns = if (dns == 1) "" else """
mapdns:
  address: 198.18.0.2
  port: 53
  network: 100.64.0.0
  netmask: 255.192.0.0
  cache-size: 10000
"""
            val cfg = """
tunnel:
  mtu: 1500
  ipv4: 198.18.0.1
  ipv6: 'fc00::1'
socks5:
  port: $port
  address: ${addr.hostAddress}
  udp: 'udp'
$auth$mapdns
misc:
  task-stack-size: 81920
  connect-timeout: 5000
  tcp-read-write-timeout: 60000
  log-file: ${File(cacheDir, "hev.log").absolutePath}
  log-level: info
""".trimStart()
            val f = File(cacheDir, "tproxy.yml")
            f.writeText(cfg)
            log("Starting tunnel engine")
            TProxyService.TProxyStartService(f.absolutePath, fd.fd)
            running = true
            log("Connected")
            listener?.invoke(true, null)
        } catch (t: Throwable) {
            log("ERROR: ${t.javaClass.simpleName}: ${t.message}")
            stopVpn(t.javaClass.simpleName + ": " + (t.message ?: ""))
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

    @Suppress("DEPRECATION")
    private fun stopVpn(error: String?, notify: Boolean = true, keepService: Boolean = false) {
        try { if (running) TProxyService.TProxyStopService() } catch (_: Throwable) { }
        try { tun?.close() } catch (_: Exception) { }
        tun = null
        running = false
        if (notify) listener?.invoke(false, error)
        if (!keepService) {
            try { stopForeground(true) } catch (_: Throwable) { }
            stopSelf()
        }
    }

    override fun onRevoke() { log("VPN revoked by system"); stopVpn(null) }
    override fun onDestroy() { log("Service destroyed"); if (running) stopVpn(null); super.onDestroy() }
}

