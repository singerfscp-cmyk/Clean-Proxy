package com.cleanproxy

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var icon: TextView
    private lateinit var status: TextView
    private lateinit var connectBtn: Button
    private val h = Handler(Looper.getMainLooper())
    private var seq = 0
    private var pending: Runnable? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pill(color: String) = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = dp(14).toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1115"))
            setPadding(dp(20), dp(56), dp(20), dp(20))
        }
        root.addView(TextView(this).apply {
            text = "Clean Proxy"; textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "SOCKS5 VPN • no ads"; textSize = 13f; setTextColor(Color.parseColor("#8A90A0"))
            setPadding(0, 0, 0, dp(24))
        })

        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = pill("#1A1D24"); setPadding(dp(12), 0, dp(12), 0) }
        input = EditText(this).apply {
            hint = "socks5://user:pass@host:port"
            setHintTextColor(Color.parseColor("#5C6270")); setTextColor(Color.WHITE)
            background = null; textSize = 15f; maxLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(56), 1f))
        icon = TextView(this).apply { textSize = 24f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        row.addView(icon, LinearLayout.LayoutParams(dp(36), WRAP_CONTENT))
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val pasteBtn = Button(this).apply {
            text = "Paste"; setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
                if (!t.isNullOrBlank()) input.setText(t.trim())
            }
        }
        root.addView(pasteBtn, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        status = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor("#8A90A0")); setPadding(0, dp(12), 0, dp(24)) }
        root.addView(status)

        connectBtn = Button(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { onConnectClick() }
        }
        root.addView(connectBtn, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)))

        val testBtn = Button(this).apply {
            text = "Test IP (whoer.net)"; setTextColor(Color.WHITE); background = pill("#252A35")
            setOnClickListener { startActivity(Intent(this@MainActivity, TestIpActivity::class.java)) }
        }
        root.addView(testBtn, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })
        setContentView(root)

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { scheduleCheck() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        input.setText(getSharedPreferences("p", MODE_PRIVATE).getString("proxy", ""))
        ProxyVpnService.listener = { _, err ->
            runOnUiThread { if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show(); refreshButton() }
        }
        refreshButton()
    }

    private fun refreshButton() {
        val on = ProxyVpnService.running
        connectBtn.isEnabled = true
        connectBtn.text = if (on) "Disconnect" else "Connect"
        connectBtn.background = pill(if (on) "#D64545" else "#2E9B5F")
    }

    private fun scheduleCheck() {
        pending?.let { h.removeCallbacks(it) }
        val text = input.text.toString()
        getSharedPreferences("p", MODE_PRIVATE).edit().putString("proxy", text).apply()
        val cfg = ProxyParser.parse(text)
        if (text.isBlank()) { setIcon("", "#8A90A0"); status.text = ""; return }
        if (cfg == null) { setIcon("✗", "#D64545"); status.text = "Invalid format"; return }
        setIcon("…", "#8A90A0"); status.text = "Checking…"
        val my = ++seq
        pending = Runnable {
            Thread {
                val r = ProxyChecker.check(cfg)
                runOnUiThread {
                    if (my != seq) return@runOnUiThread
                    if (r.ok) { setIcon("✓", "#2E9B5F"); status.text = "Working • IP ${r.ip ?: "?"} • ${r.ms} ms" }
                    else { setIcon("✗", "#D64545"); status.text = r.error ?: "Failed" }
                }
            }.start()
        }
        h.postDelayed(pending!!, 600)
    }

    private fun setIcon(t: String, color: String) { icon.text = t; icon.setTextColor(Color.parseColor(color)) }

    private fun onConnectClick() {
        if (ProxyVpnService.running) {
            startService(Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_STOP))
            return
        }
        if (ProxyParser.parse(input.text.toString()) == null) { Toast.makeText(this, "Invalid proxy", Toast.LENGTH_SHORT).show(); return }
        val i = VpnService.prepare(this)
        if (i != null) startActivityForResult(i, 1) else startVpn()
    }

    private fun startVpn() {
        val c = ProxyParser.parse(input.text.toString()) ?: return
        connectBtn.isEnabled = false; connectBtn.text = "Connecting…"
        startService(Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_START)
            .putExtra("host", c.host).putExtra("port", c.port).putExtra("user", c.user).putExtra("pass", c.pass))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) startVpn()
    }

    override fun onResume() { super.onResume(); refreshButton() }
    override fun onDestroy() { ProxyVpnService.listener = null; super.onDestroy() }
}
