package com.cleanproxy

import android.app.ActivityManager
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
        @Volatile private var logFile: File? = null
        private var handlerInstalled = false

        @Synchronized fun initLogging(ctx: Context) {
            logFile = File(ctx.cacheDir, "app.log")
            if (!handlerInstalled) {
                handlerInstalled = true
                val prev = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { t, e ->
                    log("CRASH in ${t.name}: " + android.util.Log.getStackTraceString(e))
                    prev?.uncaughtException(t, e)
                }
            }
        }

        @Synchronized fun log(s: String) {
            val line = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()) + " " + s
            try {
                logFile?.let { f ->
                    if (f.length() > 100_000) f.writeText("")
                    f.appendText(line + "\n")
                }
            } catch (_: Exception) { }
        }

        @Synchronized fun logText(ctx: Context): String {
            val sb = StringBuilder()
            val a = File(ctx.cacheDir, "app.log")
            if (a.exists()) sb.append(a.readLines().takeLast(120).joinToString("\n")).append('\n')
            val f = File(ctx.cacheDir, "hev.log")
            if (f.exists()) {
                sb.append("--- tunnel log ---\n")
                sb.append(f.readLines().takeLast(60).joinToString("\n"))
            }
            if (Build.VERSION.SDK_INT >= 30) {
                try {
                    val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    sb.append("\n--- last exits ---\n")
                    am.getHistoricalProcessExitReasons(ctx.packageName, 0, 3).forEach {
                        sb.append("reason=${it.reason} status=${it.status} ${it.description}\n")
                    }
                } catch (_: Throwable) { }
            }
            try {
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "600"))
                val rx = Regex("libc|JNI|hev|FATAL|AndroidRuntime|bort|signal|SIG|cleanproxy|UnsatisfiedLink|NoSuchMethod")
                val keep = p.inputStream.bufferedReader().readLines().filter { rx.containsMatchIn(it) }.takeLast(40)
                sb.append("\n--- system log ---\n").append(keep.joinToString("\n"))
            } catch (_: Throwable) { }
            return sb.toString().ifBlank { "(empty)" }
        }
    }

    private var tun: ParcelFileDescriptor? = null

    override fun onCreate() { super.onCreate(); initLogging(this); log("Service created") }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            log("Stop requested")
            stopVpn(null)
            return START_NOT_STICKY
        }
        goForeground(intent?.getStringExtra("name") ?: "Proxy")
        val host = intent?.getStringExtra("host")
        if (host == null) { stopSelf(); return START_NOT_STICKY }
        val port = intent?.getIntExtra("port", 0) ?: 0
        val user = intent?.getStringExtra("user")
        val pass = intent?.getStringExtra("pass")
        val dns = intent?.getIntExtra("dns", 0) ?: 0
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
            if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
            log("Starting tunnel engine (fd=${fd.fd})")
            val started: Any = TProxyService.TProxyStartService(f.absolutePath, fd.fd)
            if (started == false) throw Exception("Tunnel engine refused to start")
            log("Tunnel engine returned")
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
