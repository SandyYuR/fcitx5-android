/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import android.app.Application
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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Runs authorized native probing in a disposable process so a crash cannot take down the IME. */
internal class RimeEngineProbeService : Service() {
    private val probing = AtomicBoolean(false)
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
        val callerUid = data.getInt(KEY_CALLER_UID, -1)
        val reply = message.replyTo
        if (!probing.compareAndSet(false, true)) {
            descriptor?.close()
            runCatching {
                reply.send(Message.obtain(null, MESSAGE_RESULT).apply {
                    this.data = Bundle().apply { putString(KEY_ERROR, "已有引擎正在探测，请稍后重试") }
                })
            }
            return@Handler true
        }
        Thread({
            val response = Bundle()
            try {
                require(callerUid > 0 && Process.myUid() != callerUid) {
                    "引擎探测服务没有运行在 Android 隔离 UID"
                }
                requireNotNull(descriptor) { "无法打开引擎文件" }.use { fd ->
                    val values = RimeEngineProbeNative.probe(fd.fd, entry, identity)
                    require(values.size == 3)
                    val capabilities = (values[2].split(',').filter(String::isNotBlank) +
                        "isolated-uid").distinct().joinToString(",")
                    response.putStringArray(KEY_RESULT, arrayOf(values[0], values[1], capabilities))
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

    override fun onDestroy() {
        super.onDestroy()
        // This service is bound only for one disposable probe. In particular, cancellation can
        // happen before onServiceConnected (and therefore before the caller knows our PID).
        // Ending the isolated process here makes unbinding the authoritative cleanup path.
        Process.killProcess(Process.myPid())
    }

    companion object {
        private const val MESSAGE_PROBE = 1
        private const val MESSAGE_RESULT = 2
        private const val KEY_DESCRIPTOR = "descriptor"
        private const val KEY_ENTRY = "entry"
        private const val KEY_IDENTITY = "identity"
        private const val KEY_CALLER_UID = "callerUid"
        private const val KEY_RESULT = "result"
        private const val KEY_ERROR = "error"
        private const val PROBE_TIMEOUT_MS = 20_000L
        private val probeInFlight = AtomicBoolean(false)

        fun isProbeProcess(context: Context): Boolean {
            val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else runCatching {
                File("/proc/self/cmdline").inputStream().use { input ->
                    val bytes = ByteArray(1024)
                    val count = input.read(bytes).coerceAtLeast(0)
                    String(bytes, 0, count).substringBefore('\u0000')
                }
            }.getOrDefault("")
            return isProbeProcessName(context.packageName, processName)
        }

        internal fun isProbeProcessName(packageName: String, processName: String): Boolean {
            val expected = "$packageName:rime_engine_probe"
            // Android gives isolated services an instance suffix after the manifest process name.
            return processName == expected || processName.startsWith("$expected:")
        }

        suspend fun probe(
            context: Context,
            file: File,
            info: RimeEngineStaticInfo
        ): RimeEngineRuntimeInfo {
            require(probeInFlight.compareAndSet(false, true)) { "已有引擎正在探测，请稍后重试" }
            try {
                return withContext(Dispatchers.Main.immediate) {
                    withTimeout(PROBE_TIMEOUT_MS) {
                        suspendCancellableCoroutine { continuation ->
                    var finished = false
                    var binding: RimeEngineProbeBinding? = null
                    lateinit var connection: ServiceConnection
                    fun finish(result: Result<RimeEngineRuntimeInfo>) {
                        if (finished) return
                        finished = true
                        binding?.close()
                        if (!continuation.isActive) return
                        result.fold(continuation::resume, continuation::resumeWithException)
                    }
                    val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
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
                            if (finished) return
                            if (binder == null) {
                                finish(Result.failure(IllegalStateException("无法连接引擎探测进程")))
                                return
                            }
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
                                        putInt(KEY_CALLER_UID, Process.myUid())
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
                    continuation.invokeOnCancellation {
                        // Cancellation may run off the main thread and may race bindService's
                        // return. Queue cleanup on the main looper so it observes the final bind
                        // state and can unbind even when onServiceConnected never arrived.
                        Handler(Looper.getMainLooper()).post {
                            if (!finished) {
                                finished = true
                                binding?.close()
                            }
                        }
                    }
                    if (!continuation.isActive) {
                        finished = true
                        return@suspendCancellableCoroutine
                    }
                    binding = RimeEngineProbeBinding(context, connection)
                    if (!binding.bind()) {
                        finish(Result.failure(IllegalStateException("无法绑定引擎探测进程")))
                    }
                        }
                    }
                }
            } finally {
                probeInFlight.set(false)
            }
        }
    }
}

/** A PID-independent lease for the one bound isolated probe service. */
internal class RimeEngineProbeBinding(
    private val context: Context,
    private val connection: ServiceConnection
) {
    private var bound = false

    fun bind(): Boolean {
        check(!bound) { "引擎探测服务已经绑定" }
        bound = context.bindService(
            Intent(context, RimeEngineProbeService::class.java),
            connection,
            Context.BIND_AUTO_CREATE
        )
        return bound
    }

    fun close() {
        if (!bound) return
        bound = false
        runCatching { context.unbindService(connection) }
    }
}

internal object RimeEngineProbeNative {
    init { System.loadLibrary("direct-rime") }
    external fun probe(fd: Int, entry: String, identity: String): Array<String>
}
