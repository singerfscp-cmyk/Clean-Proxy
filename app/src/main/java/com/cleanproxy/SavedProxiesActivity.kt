package com.cleanproxy

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SavedProxiesActivity : Activity() {
    private lateinit var listBox: LinearLayout

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun pill(color: String) = GradientDrawable().apply { setColor(Color.parseColor(color)); cornerRadius = dp(14).toFloat() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0F1115"))
            setPadding(dp(16), dp(48), dp(16), dp(16))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "←"; textSize = 26f; setTextColor(Color.WHITE); setPadding(dp(4), 0, dp(16), 0)
            setOnClickListener { finish() }
        })
        header.addView(TextView(this).apply {
            text = "Saved Proxies"; textSize = 22f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(header)

        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(listBox, MATCH_PARENT, WRAP_CONTENT)
        root.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f).apply { topMargin = dp(12) })
        setContentView(root)
        refresh()
    }

    private fun smallBtn(label: String, color: String, action: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setTextColor(Color.WHITE)
        background = pill(color); minWidth = 0; minHeight = 0
        setPadding(dp(14), dp(8), dp(14), dp(8))
        setOnClickListener { action() }
    }

    private fun refresh() {
        listBox.removeAllViews()
        val items = ProxyStore.load(this)
        if (items.isEmpty()) {
            listBox.addView(TextView(this).apply {
                text = "No saved proxies yet.\nOn the main screen enter a proxy, type a Profile name, then tap Save."
                textSize = 14f; setTextColor(Color.parseColor("#8A90A0")); setPadding(dp(4), dp(24), dp(4), 0)
            })
            return
        }
        items.forEachIndexed { idx, sp ->
            val c = ProxyParser.parse(sp.text)
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL; background = pill("#1A1D24")
                setPadding(dp(14), dp(10), dp(10), dp(10))
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; isClickable = true
                setOnClickListener { pick(sp) }
            }
            info.addView(TextView(this).apply {
                text = sp.name; textSize = 16f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
            })
            info.addView(TextView(this).apply {
                text = if (c != null) "${c.host}:${c.port}" + (if (c.user != null) "  •  ${c.user}" else "") else sp.text
                textSize = 12f; setTextColor(Color.parseColor("#8A90A0"))
            })
            row.addView(info, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            row.addView(smallBtn("Edit", "#2F6FDE") { edit(idx) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
            row.addView(smallBtn("Delete", "#D64545") { confirmDelete(idx) },
                LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { leftMargin = dp(8) })
            listBox.addView(row, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) })
        }
    }

    private fun pick(sp: SavedProxy) {
        setResult(RESULT_OK, Intent().putExtra("name", sp.name).putExtra("text", sp.text))
        finish()
    }

    private fun confirmDelete(idx: Int) {
        val items = ProxyStore.load(this)
        if (idx !in items.indices) return
        AlertDialog.Builder(this).setTitle("Delete \"${items[idx].name}\"?")
            .setPositiveButton("Delete") { _, _ ->
                val list = ProxyStore.load(this)
                if (idx in list.indices) { list.removeAt(idx); ProxyStore.store(this, list) }
                refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun edit(idx: Int) {
        val items = ProxyStore.load(this)
        if (idx !in items.indices) return
        val sp = items[idx]
        val c = ProxyParser.parse(sp.text)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        fun field(hint: String, value: String?, number: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(value ?: ""); setSingleLine()
            inputType = if (number) InputType.TYPE_CLASS_NUMBER
            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }
        val fn = field("Name", sp.name)
        val fs = field("Server", c?.host)
        val fp = field("Port", c?.port?.toString(), true)
        val fu = field("Username (optional)", c?.user)
        val fpw = field("Password (optional)", c?.pass)
        listOf(fn, fs, fp, fu, fpw).forEach { box.addView(it) }
        AlertDialog.Builder(this).setTitle("Edit proxy")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Save") { _, _ ->
                val typed = fs.text.toString().trim()
                val full = if (typed.contains("://") || typed.contains('@') || typed.contains(':')) ProxyParser.parse(typed) else null
                val host = full?.host ?: typed
                val port = full?.port ?: fp.text.toString().trim().toIntOrNull()
                val user = if (full != null) full.user else fu.text.toString().ifBlank { null }
                val pass = if (full != null) full.pass else if (user != null) fpw.text.toString() else null
                if (host.isEmpty() || port == null || port !in 1..65535) {
                    Toast.makeText(this, "Invalid server or port", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val name = fn.text.toString().trim().ifBlank { "$host:$port" }
                val list = ProxyStore.load(this)
                if (idx in list.indices) {
                    list[idx] = SavedProxy(name, ProxyStore.textFromConfig(ProxyConfig(host, port, user, pass)))
                    ProxyStore.store(this, list)
                }
                refresh()
            }
            .setNegativeButton("Cancel", null).show()
    }
}
