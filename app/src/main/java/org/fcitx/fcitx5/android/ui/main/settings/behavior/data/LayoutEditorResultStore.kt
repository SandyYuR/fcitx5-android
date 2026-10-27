/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.data

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import androidx.activity.result.ActivityResult
import androidx.annotation.MainThread
import androidx.core.os.BundleCompat
import java.util.UUID

/** Main-thread mailbox. Results must wait until both the layout and its draft are restored. */
@MainThread
internal class LayoutEditorResultStore {
    enum class Channel { KEY, AUX, PROFILE, LAYOUT, IMAGE, SCAN }
    data class Target(val profile: String, val entry: String, val token: String)
    data class Pending(val channel: Channel, val target: Target?, val data: Intent)

    private val awaiting = mutableMapOf<Channel, Target>()
    private val pending = linkedMapOf<Channel, Pending>()

    fun begin(channel: Channel, profile: String, entry: String, intent: Intent) {
        val target = Target(profile, entry, UUID.randomUUID().toString())
        awaiting[channel] = target
        intent.putExtra(EXTRA_REQUEST_TOKEN, target.token)
    }

    fun receive(channel: Channel, result: ActivityResult) {
        val expected = awaiting[channel]
        val token = result.data?.getStringExtra(EXTRA_REQUEST_TOKEN)
        if (token != null && token != expected?.token) return
        if (result.resultCode != Activity.RESULT_OK) {
            awaiting.remove(channel)
            return
        }
        val data = result.data ?: return
        // A late/replayed result cannot consume a newer request. Tokenless results are only
        // for a child launched before this protocol existed; resolve their target after load.
        if (expected != null && token != expected.token) return
        awaiting.remove(channel)
        pending[channel] = Pending(channel, expected, data)
    }

    fun takeAll(): List<Pending> = pending.values.toList().also { pending.clear() }

    fun takeNext(): Pending? = pending.entries.firstOrNull()?.let { pending.remove(it.key) }

    fun save(out: Bundle) {
        out.putBundle(STATE, Bundle().apply {
            awaiting.forEach { (channel, target) -> putBundle("awaiting_${channel.name}", target.toBundle()) }
            putStringArrayList("order", ArrayList(pending.keys.map { it.name }))
            pending.forEach { (channel, result) ->
                putBundle("pending_${channel.name}", Bundle().apply {
                    result.target?.let { putBundle("target", it.toBundle()) }
                    putParcelable("data", result.data)
                })
            }
        })
    }

    fun restore(state: Bundle?) {
        val saved = state?.getBundle(STATE) ?: return
        awaiting.clear()
        pending.clear()
        Channel.entries.forEach { channel ->
            saved.getBundle("awaiting_${channel.name}")?.toTarget()?.let { awaiting[channel] = it }
        }
        saved.getStringArrayList("order").orEmpty().forEach { name ->
            val channel = Channel.entries.firstOrNull { it.name == name } ?: return@forEach
            val result = saved.getBundle("pending_$name") ?: return@forEach
            result.classLoader = Intent::class.java.classLoader
            val data = BundleCompat.getParcelable(result, "data", Intent::class.java) ?: return@forEach
            pending[channel] = Pending(channel, result.getBundle("target")?.toTarget(), data)
        }
    }

    private fun Target.toBundle() = Bundle().apply {
        putString("profile", profile)
        putString("entry", entry)
        putString("token", token)
    }

    private fun Bundle.toTarget(): Target? {
        return Target(getString("profile") ?: return null,
            getString("entry") ?: return null, getString("token") ?: return null)
    }

    companion object {
        private const val STATE = "pending_layout_editor_results"
        private const val EXTRA_REQUEST_TOKEN = "layout_editor_request_token"

        fun attachRequestToken(request: Intent, result: Intent): Intent = result.apply {
            request.getStringExtra(EXTRA_REQUEST_TOKEN)?.let { putExtra(EXTRA_REQUEST_TOKEN, it) }
        }
    }
}
