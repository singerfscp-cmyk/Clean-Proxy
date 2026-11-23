package com.cleanproxy

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private val GRAY = "#8A90A0"
    private val RED = "#D64545"
    private val GREEN = "#2E9B5F"

    private lateinit var input: EditText
    private lateinit var icon: TextView
    private lateinit var status: TextView
    private lateinit var connectBtn: Button
    private lateinit var fName: EditText
    private lateinit var fProto: Spinner
    private lateinit var fServer: EditText
    private lateinit var fPort: EditText
    private lateinit var fAuth: Spinner
    private lateinit var fUser: EditText
    private lateinit var fPass: EditText
    private lateinit var fDns: Spinner
    private lateinit var credBox: LinearLayout

    private val h = Handler(Looper.getMainLooper())
    private var seq = 0
    private var pending: Runnable? = null
    private var syncing = false
    private var ready = false
    private var restoring = false
    private var current: ProxyConfig? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pill(color: String) = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = dp(14).toFloat() }

    private fun edit(hint: String) = EditText(this).apply {
        this.hint = hint
        setHintTextColor(Color.parseColor("#5C6270")); setTextColor(Color.WHITE)
        background = null; textSize = 15f; setSingleLine()
        setPadding(dp(14), 0, dp(14), 0)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }

    private fun spinner(items: List<String>) = Spinner(this).apply {
        adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_item, items).also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun boxed(label: String, v: View): LinearLayout {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(this).apply {
            text = label; textSize = 12f; setTextColor(Color.parseColor(GRAY)); setPadding(dp(4), dp(10), 0, dp(4))
        })
        val wrap = LinearLayout(this).apply { background = pill("#1A1D24"); gravity = Gravity.CENTER_VERTICAL }
        wrap.addView(v, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)))
        col.addView(wrap, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        return col
    }

    private fun watcher(onChange: () -> Unit) = object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { if (!syncing) onChange() }
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProxyVpnService.initLogging(this)
        val scroll = ScrollView(this).apply { setBackgroundColor(Color.parseColor("#0F1115")); isFillViewport = true }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(56), dp(20), dp(28))
        }
        scroll.addView(root, MATCH_PARENT, WRAP_CONTENT)

        root.addView(TextView(this).apply {
            text = "Clean Proxy"; textSize = 26f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "SOCKS5 VPN • no ads"; textSize = 13f; setTextColor(Color.parseColor(GRAY)); setPadding(0, 0, 0, dp(20))
        })

        // --- Paste box ---
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; background = pill("#1A1D24"); setPadding(dp(12), 0, dp(12), 0) }
        input = EditText(this).apply {
            hint = "Paste: socks5://user:pass@host:port"
            setHintTextColor(Color.parseColor("#5C6270")); setTextColor(Color.WHITE)
            background = null; textSize = 15f; maxLines = 2
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        row.addView(input, LinearLayout.LayoutParams(0, dp(56), 1f))
        icon = TextView(this).apply { textSize = 24f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER }
        row.addView(icon, LinearLayout.LayoutParams(dp(36), WRAP_CONTENT))
        root.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val btnRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun rowBtn(label: String, action: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { action() }
        }
        btnRow.addView(rowBtn("Paste") {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val t = cm.primaryClip?.getItemAt(0)?.coerceToText(this@MainActivity)?.toString()
            if (!t.isNullOrBlank()) input.setText(t.trim())
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        btnRow.addView(rowBtn("Save") { saveCurrent() },
            LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
        btnRow.addView(rowBtn("Saved proxies") {
            startActivityForResult(Intent(this@MainActivity, SavedProxiesActivity::class.java), 3)
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.4f).apply { leftMargin = dp(6) })
        root.addView(btnRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(8) })

        status = TextView(this).apply { textSize = 14f; setTextColor(Color.parseColor(GRAY)); setPadding(0, dp(10), 0, dp(6)) }
        root.addView(status)

        // --- Manual form (optional, synced with paste box) ---
        root.addView(TextView(this).apply {
            text = "Or fill manually"; textSize = 14f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(14), 0, 0)
        })
        fName = edit("New Profile")
        root.addView(boxed("Profile name", fName))
        fProto = spinner(listOf("SOCKS5"))
        root.addView(boxed("Protocol", fProto))
        fServer = edit("Server (or paste full proxy here)")
        root.addView(boxed("Server", fServer))
        fPort = edit("Port").apply { inputType = InputType.TYPE_CLASS_NUMBER }
        root.addView(boxed("Port", fPort))
        fAuth = spinner(listOf("Username/Password", "None"))
        root.addView(boxed("Authentication method", fAuth))

        credBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fUser = edit("Username")
        credBox.addView(boxed("Username", fUser))
        fPass = edit("Password").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val passRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        passRow.addView(fPass, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        passRow.addView(TextView(this).apply {
            text = "👁"; textSize = 20f; setPadding(dp(12), 0, dp(14), 0)
            setOnClickListener {
                val hidden = (fPass.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD) != 0
                fPass.inputType = InputType.TYPE_CLASS_TEXT or
                    (if (hidden) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                fPass.setSelection(fPass.text.length)
            }
        })
        credBox.addView(boxed("Password", passRow))
        root.addView(credBox)

        fDns = spinner(listOf("Proxy", "Public DNS (1.1.1.1)"))
        root.addView(boxed("DNS resolution", fDns))

        connectBtn = Button(this).apply {
            textSize = 17f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            setOnClickListener { onConnectClick() }
        }
        root.addView(connectBtn, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)).apply { topMargin = dp(24) })

        root.addView(Button(this).apply {
            text = "Test IP (whoer.net)"; setTextColor(Color.WHITE); background = pill("#252A35")
            setOnClickListener { startActivity(Intent(this@MainActivity, TestIpActivity::class.java)) }
        }, LinearLayout.LayoutParams(MATCH_PARENT, dp(52)).apply { topMargin = dp(12) })

        root.addView(TextView(this).apply {
            text = "View log"; setTextColor(Color.parseColor(GRAY)); gravity = Gravity.CENTER
            setPadding(0, dp(18), 0, dp(6)); setOnClickListener { showLog() }
        })
        setContentView(scroll)

        // --- wiring ---
        input.addTextChangedListener(watcher { pasteChanged() })
        val fw = watcher { formChanged() }
        fServer.addTextChangedListener(fw); fPort.addTextChangedListener(fw)
        fUser.addTextChangedListener(fw); fPass.addTextChangedListener(fw)
        fName.addTextChangedListener(watcher { saveState() })
        fAuth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                credBox.visibility = if (pos == 0) View.VISIBLE else View.GONE
                if (!syncing && ready) formChanged()
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        fDns.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { if (ready) saveState() }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        val prefs = getSharedPreferences("p", MODE_PRIVATE)
        val savedName = prefs.getString("name", "") ?: ""
        val savedDns = prefs.getInt("dns", 0)
        val savedProxy = prefs.getString("proxy", "") ?: ""
        restoring = true
        fName.setText(savedName)
        fDns.setSelection(savedDns)
        restoring = false
        input.setText(savedProxy)
        ready = true

        ProxyVpnService.listener = { _, err ->
            runOnUiThread {
                if (err != null) {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    setStatus("VPN error: $err", RED)
                }
                refreshButton()
            }
        }
        refreshButton()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    // ---------- sync logic ----------
    private fun textFromConfig(c: ProxyConfig) = buildString {
        append("socks5://")
        if (c.user != null) { append(c.user); append(':'); append(c.pass ?: ""); append('@') }
        append(c.host); append(':'); append(c.port)
    }

    private fun configFromForm(): ProxyConfig? {
        val host = fServer.text.toString().trim()
        val port = fPort.text.toString().trim().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65535) return null
        val useAuth = fAuth.selectedItemPosition == 0 && fUser.text.isNotBlank()
        return ProxyConfig(host, port,
            if (useAuth) fUser.text.toString() else null,
            if (useAuth) fPass.text.toString() else null)
    }

    private fun fillForm(c: ProxyConfig) {
        syncing = true
        fServer.setText(c.host); fPort.setText(c.port.toString())
        if (c.user != null) { fAuth.setSelection(0); fUser.setText(c.user); fPass.setText(c.pass ?: "") }
        else { fAuth.setSelection(1); fUser.setText(""); fPass.setText("") }
        credBox.visibility = if (c.user != null) View.VISIBLE else View.GONE
        syncing = false
    }

    private fun pasteChanged() {
        val c = ProxyParser.parse(input.text.toString())
        current = c
        if (c != null) fillForm(c)
        onConfigChanged(true)
    }

    private fun formChanged() {
        val s = fServer.text.toString().trim()
        if (s.contains(':') || s.contains('@') || s.contains('/')) {
            val full = ProxyParser.parse(s)
            if (full != null) {   // a whole proxy string was pasted into the Server field
                current = full
                fillForm(full)
                syncing = true; input.setText(textFromConfig(full)); syncing = false
                onConfigChanged(false)
                return
            }
        }
        val c = configFromForm()
        val t = c?.let { textFromConfig(it) } ?: ""
        if (c == current && t == input.text.toString()) return
        current = c
        syncing = true; input.setText(t); syncing = false
        onConfigChanged(false)
    }

    private fun setIcon(t: String, color: String) { icon.text = t; icon.setTextColor(Color.parseColor(color)) }
    private fun setStatus(t: String, color: String) { status.text = t; status.setTextColor(Color.parseColor(color)) }

    private fun saveState() {
        if (restoring) return
        getSharedPreferences("p", MODE_PRIVATE).edit()
            .putString("proxy", current?.let { textFromConfig(it) } ?: input.text.toString())
            .putString("name", fName.text.toString())
            .putInt("dns", fDns.selectedItemPosition).apply()
    }

    private fun onConfigChanged(fromPaste: Boolean) {
        pending?.let { h.removeCallbacks(it) }
        saveState()
        val cfg = current
        val my = ++seq
        if (cfg == null) {
            if (input.text.isBlank() && fServer.text.isBlank()) { setIcon("", GRAY); setStatus("", GRAY) }
            else if (fromPaste) { setIcon("✗", RED); setStatus("Invalid format", RED) }
            else { setIcon("", GRAY); setStatus("Enter server and port", GRAY) }
            return
        }
        setIcon("…", GRAY); setStatus("Checking…", GRAY)
        pending = Runnable {
            Thread {
                val r = ProxyChecker.check(cfg)
                runOnUiThread {
                    if (my != seq) return@runOnUiThread
                    if (r.ok) { setIcon("✓", GREEN); setStatus("Working • IP ${r.ip ?: "?"} • ${r.ms} ms", GREEN) }
                    else { setIcon("✗", RED); setStatus(r.error ?: "Failed", RED) }
                }
            }.start()
        }
        h.postDelayed(pending!!, 600)
    }


    // ---------- saved proxies ----------
    private fun saveCurrent() {
        val c = current
        if (c == null) { Toast.makeText(this, "Enter a valid proxy first", Toast.LENGTH_SHORT).show(); return }
        val text = textFromConfig(c)
        val name = fName.text.toString().trim()
        if (name.isNotEmpty()) {
            ProxyStore.save(this, name, text)
            Toast.makeText(this, "Saved: $name", Toast.LENGTH_SHORT).show()
            return
        }
        val et = EditText(this).apply { hint = "Proxy name"; setSingleLine(); setPadding(dp(24), dp(16), dp(24), dp(16)) }
        AlertDialog.Builder(this).setTitle("Name this proxy").setView(et)
            .setPositiveButton("Save") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isEmpty()) {
                    Toast.makeText(this, "Name is required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                fName.setText(n)
                ProxyStore.save(this, n, text)
                Toast.makeText(this, "Saved: $n", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null).show()
    }

    // ---------- VPN ----------
    private fun refreshButton() {
        val on = ProxyVpnService.running
        connectBtn.isEnabled = true
        connectBtn.text = if (on) "Disconnect" else "Connect"
        connectBtn.background = pill(if (on) RED else GREEN)
    }

    private fun onConnectClick() {
        if (ProxyVpnService.running) {
            startService(Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_STOP))
            return
        }
        if (current == null) { Toast.makeText(this, "Enter a valid proxy first", Toast.LENGTH_SHORT).show(); return }
        val i = VpnService.prepare(this)
        if (i != null) startActivityForResult(i, 1) else startVpn()
    }

    private fun startVpn() {
        val c = current ?: return
        connectBtn.isEnabled = false; connectBtn.text = "Connecting…"
        val i = Intent(this, ProxyVpnService::class.java).setAction(ProxyVpnService.ACTION_START)
            .putExtra("host", c.host).putExtra("port", c.port).putExtra("user", c.user).putExtra("pass", c.pass)
            .putExtra("name", fName.text.toString().ifBlank { c.host })
            .putExtra("dns", fDns.selectedItemPosition)
        ProxyVpnService.log("Connect clicked")
        startService(i)
    }

    private fun showLog() {
        val tv = TextView(this).apply {
            text = ProxyVpnService.logText(this@MainActivity); setTextIsSelectable(true); textSize = 11f
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        AlertDialog.Builder(this).setTitle("Log").setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("Close", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) startVpn()
        if (requestCode == 3 && resultCode == RESULT_OK && data != null) {
            fName.setText(data.getStringExtra("name") ?: "")
            input.setText(data.getStringExtra("text") ?: "")
        }
    }

    override fun onResume() { super.onResume(); refreshButton() }
    override fun onDestroy() { ProxyVpnService.listener = null; super.onDestroy() }
}
