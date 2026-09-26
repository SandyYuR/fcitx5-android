/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fcitx.fcitx5.android.common.ipc.IClipboardEntryTransformer
import org.fcitx.fcitx5.android.common.ipc.IFcitxRemoteService
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.clipboard.HostClipboardFilter
import org.fcitx.fcitx5.android.utils.Const
import org.fcitx.fcitx5.android.utils.desc
import org.fcitx.fcitx5.android.utils.descEquals
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

class FcitxRemoteService : Service() {


    private val clipboardTransformerLock = Mutex()

    private val scope = MainScope() + CoroutineName("FcitxRemoteService")

    private val clipboardTransformers = CopyOnWriteArrayList<IClipboardEntryTransformer>()

    private fun orderedClipboardTransformers() =
        clipboardTransformers.toList()
            .sortedWith(compareByDescending<IClipboardEntryTransformer> { it.priority }.thenBy { it.desc })

    private fun transformClipboard(source: String): String {
        var result = HostClipboardFilter.transform(source)
        orderedClipboardTransformers().forEach {
            try {
                result = it.transform(result)!!
            } catch (e: Exception) {
                Timber.w("Exception while calling clipboard transformer '${it.desc}'")
                Timber.w(e)
            }
        }
        return result
    }

    private suspend fun updateClipboardManager() = clipboardTransformerLock.withLock {
        ClipboardManager.transformer =
            if (clipboardTransformers.isEmpty() && !HostClipboardFilter.isEnabled()) null else ::transformClipboard
        val transformers = buildList {
            HostClipboardFilter.description()?.let(::add)
            addAll(orderedClipboardTransformers().map { it.desc })
        }
        Timber.d("All clipboard transformers: ${transformers.joinToString()}")
    }

    private val binder = object : IFcitxRemoteService.Stub() {
        override fun getVersionName(): String = Const.versionName

        override fun getPid(): Int = Process.myPid()
        override fun restartFcitx() {
            FcitxDaemon.restartFcitx()
        }

        override fun registerClipboardEntryTransformer(transformer: IClipboardEntryTransformer) {
            Timber.d("registerClipboardEntryTransformer: ${transformer.desc}")
            if (transformer.description.isNullOrBlank()) {
                Timber.w("Cannot register ClipboardEntryTransformer of null or empty description")
                return
            }
            if (clipboardTransformers.any { it.descEquals(transformer) }) {
                Timber.w("ClipboardEntryTransformer ${transformer.desc} has already been registered")
                return
            }
            scope.launch {
                transformer.asBinder().linkToDeath({
                    unregisterClipboardEntryTransformer(transformer)
                }, 0)
                clipboardTransformers.add(transformer)
                clipboardTransformers.sortByDescending { it.priority }
                updateClipboardManager()
            }
        }

        override fun unregisterClipboardEntryTransformer(transformer: IClipboardEntryTransformer) {
            Timber.d("unregisterClipboardEntryTransformer: ${transformer.desc}")
            scope.launch {
                clipboardTransformers.remove(transformer)
                        || clipboardTransformers.removeAll { it.descEquals(transformer) }
                        || return@launch
                updateClipboardManager()
            }
        }

        override fun importRemoteClipboardEntry(
            text: String,
            originalText: String,
            originalRootUri: String,
            type: String,
            timestamp: Long,
            sensitive: Boolean
        ) {
            val filteredText = HostClipboardFilter.transform(text)
            runBlocking {
                ClipboardManager.importRemoteEntry(
                    text = filteredText,
                    originalText = when {
                        originalText.isNotEmpty() -> originalText
                        filteredText != text -> text
                        else -> ""
                    },
                    originalRootUri = originalRootUri,
                    type = type,
                    timestamp = timestamp,
                    sensitive = sensitive,
                    notifyListeners = false
                )
            }
        }
    }

    override fun onCreate() {
        Timber.d("FcitxRemoteService onCreate")
        super.onCreate()
        runBlocking { updateClipboardManager() }
    }

    override fun onBind(intent: Intent): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent): Boolean {
        Timber.d("FcitxRemoteService onUnbind: $intent")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        Timber.d("FcitxRemoteService onDestroy")
        scope.cancel()
        clipboardTransformers.clear()
        runBlocking { updateClipboardManager() }
    }
}
