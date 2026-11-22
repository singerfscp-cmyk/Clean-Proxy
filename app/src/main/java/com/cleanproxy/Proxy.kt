package com.cleanproxy

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

data class ProxyConfig(val host: String, val port: Int, val user: String?, val pass: String?)

object ProxyParser {
    // Supports: socks5://user:pass@host:port, user:pass@host:port, host:port, host:port:user:pass
    fun parse(raw: String): ProxyConfig? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = s.replace(Regex("^(socks5h?|socks)://", RegexOption.IGNORE_CASE), "")
        s = s.trimEnd('/')
        var user: String? = null
        var pass: String? = null
        val host: String
        val portStr: String
        if (s.contains('@')) {
            val cred = s.substringBeforeLast('@')
            val hp = s.substringAfterLast('@')
            user = cred.substringBefore(':')
            pass = if (cred.contains(':')) cred.substringAfter(':') else ""
            val parts = hp.split(':')
            if (parts.size != 2) return null
            host = parts[0]; portStr = parts[1]
        } else {
            val parts = s.split(':')
            when (parts.size) {
                2 -> { host = parts[0]; portStr = parts[1] }
                4 -> { host = parts[0]; portStr = parts[1]; user = parts[2]; pass = parts[3] }
                else -> return null
            }
        }
        val port = portStr.toIntOrNull() ?: return null
        if (host.isBlank() || port !in 1..65535) return null
        return ProxyConfig(host, port, user?.ifEmpty { null }, pass)
    }
}

object ProxyChecker {
    data class Result(val ok: Boolean, val ip: String?, val ms: Long, val error: String?)

    private fun fail(msg: String) = Result(false, null, 0, msg)

    fun check(p: ProxyConfig, timeout: Int = 8000): Result {
        val start = System.currentTimeMillis()
        try {
            Socket().use { s ->
                s.soTimeout = timeout
                s.connect(InetSocketAddress(p.host, p.port), timeout)
                val inp = DataInputStream(s.getInputStream())
                val out = s.getOutputStream()
                val hasAuth = !p.user.isNullOrEmpty()
                out.write(if (hasAuth) byteArrayOf(5, 2, 0, 2) else byteArrayOf(5, 1, 0))
                out.flush()
                val r = ByteArray(2)
                inp.readFully(r)
                if (r[0].toInt() != 5) return fail("Not a SOCKS5 server")
                when (r[1].toInt() and 0xFF) {
                    0 -> {}
                    2 -> {
                        if (!hasAuth) return fail("Proxy needs username/password")
                        val u = p.user!!.toByteArray()
                        val pw = (p.pass ?: "").toByteArray()
                        val b = ByteArrayOutputStream()
                        b.write(1); b.write(u.size); b.write(u); b.write(pw.size); b.write(pw)
                        out.write(b.toByteArray()); out.flush()
                        val a = ByteArray(2)
                        inp.readFully(a)
                        if (a[1].toInt() != 0) return fail("Wrong username/password")
                    }
                    else -> return fail("Unsupported auth method")
                }
                val target = "api.ipify.org".toByteArray()
                val req = ByteArrayOutputStream()
                req.write(byteArrayOf(5, 1, 0, 3)); req.write(target.size); req.write(target)
                req.write(0); req.write(80)
                out.write(req.toByteArray()); out.flush()
                val h = ByteArray(4)
                inp.readFully(h)
                if (h[1].toInt() != 0) return fail("Proxy refused connection (code ${h[1]})")
                when (h[3].toInt()) {
                    1 -> inp.readFully(ByteArray(6))
                    4 -> inp.readFully(ByteArray(18))
                    3 -> { val l = inp.readUnsignedByte(); inp.readFully(ByteArray(l + 2)) }
                }
                out.write("GET / HTTP/1.1\r\nHost: api.ipify.org\r\nConnection: close\r\n\r\n".toByteArray())
                out.flush()
                val sb = ByteArrayOutputStream()
                val buf = ByteArray(1024)
                try {
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        sb.write(buf, 0, n)
                        if (sb.size() > 8192) break
                    }
                } catch (_: Exception) { }
                val body = sb.toString().substringAfter("\r\n\r\n", "").trim()
                val ip = Regex("""\d{1,3}(\.\d{1,3}){3}|[0-9a-fA-F]*:[0-9a-fA-F:]+""").find(body)?.value
                return Result(true, ip, System.currentTimeMillis() - start, null)
            }
        } catch (e: Exception) {
            return fail(e.message ?: e.javaClass.simpleName)
        }
    }
}
