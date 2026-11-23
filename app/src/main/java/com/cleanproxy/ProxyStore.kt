package com.cleanproxy

import android.content.Context

data class SavedProxy(val name: String, val text: String)

object ProxyStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("p", Context.MODE_PRIVATE)

    fun textFromConfig(c: ProxyConfig) = buildString {
        append("socks5://")
        if (c.user != null) { append(c.user); append(':'); append(c.pass ?: ""); append('@') }
        append(c.host); append(':'); append(c.port)
    }

    fun load(ctx: Context): MutableList<SavedProxy> {
        val raw = prefs(ctx).getString("history", "") ?: ""
        return raw.split('\n').filter { it.contains('\t') }
            .map { SavedProxy(it.substringBefore('\t'), it.substringAfter('\t')) }.toMutableList()
    }

    fun store(ctx: Context, list: List<SavedProxy>) {
        prefs(ctx).edit().putString("history", list.joinToString("\n") {
            it.name.replace('\t', ' ').replace('\n', ' ') + "\t" + it.text
        }).apply()
    }

    // Saves under the given name; an existing entry with the same name is replaced.
    fun save(ctx: Context, name: String, text: String) {
        val list = load(ctx)
        list.removeAll { it.name.equals(name, ignoreCase = true) }
        list.add(0, SavedProxy(name, text))
        store(ctx, list)
    }
}
