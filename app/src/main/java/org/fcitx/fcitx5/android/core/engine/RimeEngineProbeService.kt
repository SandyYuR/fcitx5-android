/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Runs authorized native probing in a disposable process so a crash cannot take down the IME. */
internal class RimeEngineProbeService : Service() {
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what != MESSAGE_PROBE) return@Handler false
        val data = message.data
        val descriptor = if (Build.VERSION.SDK_INT >= 33) {
            data.getParcelable(KEY_DESCRIPTOR, ParcelFileDescriptor::class.java)
        } else {
            @Suppress("DEPRECATION") data.getParcelable(KEY_DESCRIPTOR)
        }
        val entry = data.getString(KEY_ENTRY).orEmpty()
        val identity = data.getString(KEY_IDENTITY).orEmpty()
        val reply = message.replyTo
        runCatching {
            reply.send(Message.obtain(null, MESSAGE_STARTED).apply {
                this.data = Bundle().apply { putInt(KEY_PID, Process.myPid()) }
            })
        }.getOrElse {
            Process.killProcess(Process.myPid())
            return@Handler true
        }
        Thread({
            val response = Bundle()
            try {
                requireNotNull(descriptor) { "无法打开引擎文件" }.use { fd ->
                    val values = RimeEngineProbeNative.probe(fd.fd, entry, identity)
                    require(values.size == 3)
                    response.putStringArray(KEY_RESULT, values)
                }
            } catch (error: Throwable) {
                response.putString(KEY_ERROR, error.message ?: error.javaClass.simpleName)
            }
            runCatching { reply.send(Message.obtain(null, MESSAGE_RESULT).apply { this.data = response }) }
            Handler(Looper.getMainLooper()).postDelayed({
                Process.killProcess(Process.myPid())
            }, 250)
        }, "rime-engine-probe").start()
        true
    })

    override fun onBind(intent: Intent): IBinder = messenger.binder

    companion object {
        private const val MESSAGE_PROBE = 1
        private const val MESSAGE_RESULT = 2
        private const val MESSAGE_STARTED = 3
        private const val KEY_DESCRIPTOR = "descriptor"
        private const val KEY_ENTRY = "entry"
        private const val KEY_IDENTITY = "identity"
        private const val KEY_RESULT = "result"
        private const val KEY_ERROR = "error"
        private const val KEY_PID = "pid"
        private const val PROBE_TIMEOUT_MS = 20_000L

        fun isProbeProcess(context: Context): Boolean = runCatching {
            File("/proc/self/cmdline").inputStream().use { input ->
                val bytes = ByteArray(1024)
                val count = input.read(bytes).coerceAtLeast(0)
                String(bytes, 0, count).substringBefore('\u0000') == "${context.packageName}:rime_engine_probe"
            }
        }.getOrDefault(false)

        suspend fun probe(
            context: Context,
            file: File,
            info: RimeEngineStaticInfo
        ): RimeEngineRuntimeInfo = withContext(Dispatchers.Main.immediate) {
            withTimeout(PROBE_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    var bound = false
                    var finished = false
                    var probePid = -1
                    lateinit var connection: ServiceConnection
                    fun finish(result: Result<RimeEngineRuntimeInfo>) {
                        if (finished) return
                        finished = true
                        if (bound) runCatching { context.unbindService(connection) }
                        result.fold(continuation::resume, continuation::resumeWithException)
                    }
                    val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
                        if (message.what == MESSAGE_STARTED) {
                            probePid = message.data.getInt(KEY_PID, -1)
                            return@Handler true
                        }
                        if (message.what != MESSAGE_RESULT) return@Handler false
                        val error = message.data.getString(KEY_ERROR)
                        if (error != null) {
                            finish(Result.failure(IllegalArgumentException(error)))
                        } else {
                            val values = message.data.getStringArray(KEY_RESULT)
                            val size = values?.getOrNull(1)?.toIntOrNull()
                            if (values == null || values.size != 3 || size == null) {
                                finish(Result.failure(IllegalStateException("引擎探测结果无效")))
                            } else {
                                finish(Result.success(RimeEngineRuntimeInfo(
                                    librimeVersion = values[0],
                                    apiDataSize = size,
                                    capabilities = values[2].split(',').filter(String::isNotBlank)
                                )))
                            }
                        }
                        true
                    })
                    connection = object : ServiceConnection {
                        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                            if (finished || binder == null) return
                            val descriptor = runCatching {
                                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                            }.getOrElse { finish(Result.failure(it)); return }
                            try {
                                Messenger(binder).send(Message.obtain(null, MESSAGE_PROBE).apply {
                                    replyTo = reply
                                    data = Bundle().apply {
                                        putParcelable(KEY_DESCRIPTOR, descriptor)
                                        putString(KEY_ENTRY, info.entrySymbol)
                                        putString(KEY_IDENTITY, info.sha256)
                                    }
                                })
                            } catch (error: Throwable) {
                                finish(Result.failure(error))
                            } finally {
                                descriptor.close()
                            }
                        }

                        override fun onServiceDisconnected(name: ComponentName?) {
                            finish(Result.failure(IllegalStateException("引擎探测进程异常退出")))
                        }

                        override fun onBindingDied(name: ComponentName?) {
                            finish(Result.failure(IllegalStateException("引擎探测进程崩溃")))
                        }

                        override fun onNullBinding(name: ComponentName?) {
                            finish(Result.failure(IllegalStateException("无法启动引擎探测进程")))
                        }
                    }
                    val intent = Intent(context, RimeEngineProbeService::class.java)
                    bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                    if (!bound) finish(Result.failure(IllegalStateException("无法绑定引擎探测进程")))
                    continuation.invokeOnCancellation {
                        finished = true
                        if (bound) runCatching { context.unbindService(connection) }
                        if (probePid > 0 && probePid != Process.myPid()) {
                            runCatching { Process.killProcess(probePid) }
                        }
                    }
                }
            }
        }
    }
}

internal object RimeEngineProbeNative {
    init { System.loadLibrary("direct-rime") }
    external fun probe(fd: Int, entry: String, identity: String): Array<String>
}
