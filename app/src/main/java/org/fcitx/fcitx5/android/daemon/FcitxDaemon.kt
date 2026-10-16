/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.daemon

import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.BuildConfig
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.Fcitx
import org.fcitx.fcitx5.android.core.FcitxAPI
import org.fcitx.fcitx5.android.core.FcitxLifecycle
import org.fcitx.fcitx5.android.core.InputEngineBackend
import org.fcitx.fcitx5.android.core.lifeCycleScope
import org.fcitx.fcitx5.android.core.whenReady
import org.fxrime.direct.DirectRimeEngine
import org.fcitx.fcitx5.android.daemon.FcitxDaemon.connect
import org.fcitx.fcitx5.android.daemon.FcitxDaemon.disconnect
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.notificationManager
import timber.log.Timber
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Thrown when an operation is attempted on a connection that has already been disconnected.
 *
 * An ordinary exception on purpose: this used to be a `CancellationException`, which coroutine
 * machinery treats as normal cancellation and swallows, so callers never saw the failure
 * (see C31).
 */
class FcitxDisconnectedException(connectionName: String) :
    IllegalStateException("$connectionName is disconnected")

/**
 * Manage the singleton instance of [Fcitx]
 *
 * To use fcitx, client should call [connect] to obtain a [FcitxConnection],
 * and call [disconnect] on client destroyed. Client should not leak the instance of [FcitxAPI],
 * and must use [FcitxConnection] to access fcitx functionalities.
 *
 * The instance of [Fcitx] always exists,but whether the dispatcher runs and callback works depend on clients, i.e.
 * if no clients are connected, [Fcitx.stop] will be called.
 *
 * Functions are thread-safe in this class.
 */
object FcitxDaemon {

    /**
     * 引擎后端选择。
     *
     * - [InputEngineBackend]：Fcitx（走 fcitx5）与 DirectRimeEngine（Kotlin/JNI 直连
     *   librime）实现同一接口，daemon 只依赖接口，不关心底下是不是 fcitx5。
     * - 为什么保留切换开关而不是直接删掉 Fcitx：报告 §6 阶段 B 要求原型「出错能切回」。
     *   迁移应先把入口抽象出来再换实现，而不是一次性删除后无法回退。
     * - 默认走直连。要回退到 fcitx5 路径，用
     *   `-PFXRIME_ENGINE_FALLBACK=true` 重新构建即可（见 app/build.gradle.kts 的字段定义）。
     */
    private val backend: InputEngineBackend by lazy {
        // 回退开关：构建期 BuildConfig 常量。默认 false = 走直连。
        // 要回退到 fcitx5 引擎，改这里的默认值或用 -PFXRIME_ENGINE_FALLBACK=true 覆盖。
        // 不用系统属性：读 android.os.SystemProperties 是隐藏 API，要反射，且属性名
        // 写错时静默走默认值，反而不易排查。
        if (BuildConfig.FXRIME_ENGINE_FALLBACK) {
            Timber.w("FcitxDaemon: 构建开关开启，回退到 fcitx5 引擎")
            Fcitx(appContext)
        } else {
            Timber.i("FcitxDaemon: 使用直连 librime 引擎")
            DirectRimeEngine(appContext)
        }
    }

    private val realFcitx: InputEngineBackend get() = backend

    // don't leak fcitx instance
    private val fcitxImpl by lazy { object : FcitxAPI by realFcitx {} }

    private fun mkConnection(name: String) = object : FcitxConnection {

        /**
         * Throws [FcitxDisconnectedException], not `CancellationException`.
         *
         * In coroutine semantics a CancellationException means "this work was cancelled
         * normally", so structured concurrency swallowed it: callers could not tell a real
         * failure from a cancellation and no catch/onFailure branch ever ran (see C31).
         */
        private inline fun <T> ensureConnected(block: () -> T): T =
            if (name in clients)
                block()
            else throw FcitxDisconnectedException(name)

        override fun <T> runImmediately(block: suspend FcitxAPI.() -> T): T {
            return try {
                ensureConnected {
                    runBlocking(realFcitx.lifeCycleScope.coroutineContext) {
                        block(fcitxImpl)
                    }
                }
            } catch (e: FcitxDisconnectedException) {
                Timber.d("Connection $name disconnected, cancelling operation")
                throw e
            }
        }

        override suspend fun <T> runOnReady(block: suspend FcitxAPI.() -> T): T {
            return try {
                ensureConnected {
                    realFcitx.lifecycle.whenReady { block(fcitxImpl) }
                }
            } catch (e: FcitxDisconnectedException) {
                Timber.d("Connection $name disconnected while waiting for ready")
                throw e
            }
        }

        override fun runIfReady(block: suspend FcitxAPI.() -> Unit) {
            try {
                ensureConnected {
                    if (realFcitx.isReady)
                        realFcitx.lifeCycleScope.launch {
                            block(fcitxImpl)
                        }
                }
            } catch (e: FcitxDisconnectedException) {
                // runIfReady is best-effort by contract, so this stays swallowed — but now it
                // only swallows this one specific condition.
                Timber.d("Connection $name disconnected, skipping operation")
            }
        }

        override val lifecycleScope: CoroutineScope
            get() = realFcitx.lifecycle.lifecycleScope

    }

    private val lock = ReentrantLock()

    private val clients = mutableMapOf<String, FcitxConnection>()
    private var userDataIoInProgress = false

    /**
     * Create a connection
     */
    fun connect(name: String): FcitxConnection = lock.withLock {
        if (name in clients)
            return@withLock clients.getValue(name)
        if (!userDataIoInProgress && realFcitx.lifecycle.currentState == FcitxLifecycle.State.STOPPED) {
            Timber.d("FcitxDaemon start fcitx")
            realFcitx.start()
        }
        val new = mkConnection(name)
        clients[name] = new
        return@withLock new
    }

    /**
     * Dispose the connection
     */
    fun disconnect(name: String): Unit = lock.withLock {
        if (name !in clients)
            return
        clients -= name
        if (clients.isEmpty() && !userDataIoInProgress) {
            Timber.d("FcitxDaemon stop fcitx")
            realFcitx.stop()
        }
    }

    /**
     * Restart fcitx instance while keep the clients connected
     */
    fun restartFcitx() = lock.withLock {
        if (userDataIoInProgress) {
            Timber.d("Skip restarting fcitx during user data backup or restore")
            return@withLock
        }
        val id = RESTART_ID++
        NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_sync_24)
            .setContentTitle(appContext.getString(R.string.fcitx_daemon))
            .setContentText(appContext.getString(R.string.restarting_fcitx))
            .setOngoing(true)
            .setProgress(100, 0, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build().let { appContext.notificationManager.notify(id, it) }
        realFcitx.stop()
        realFcitx.start()
        FcitxApplication.getInstance().coroutineScope.launch {
            // cancel notification on ready
            realFcitx.lifecycle.whenReady {
                appContext.notificationManager.cancel(id)
            }
        }
    }

    /**
     * Stop fcitx instance regardless of connected clients.
     * Should only be used before importing user configuration files,
     * then the App must be restarted as soon as possible.
     *
     * This method blocks until fully stopped.
     */
    fun stopFcitx() {
        realFcitx.stop()
    }

    /**
     * Start fcitx instance.
     * Should only be used when it has been stopped **AND** user data importing failed.
     */
    fun startFcitx() {
        lock.withLock { if (!userDataIoInProgress) realFcitx.start() }
    }

    /** Caller holds a ready connection. Keep new clients from starting during replacement. */
    fun beginUserDataRestore() = beginUserDataIo()

    fun beginUserDataBackup() = beginUserDataIo()

    private fun beginUserDataIo() {
        lock.withLock {
            check(!userDataIoInProgress) { "A user data backup or restore is already in progress." }
            userDataIoInProgress = true
        }
        try {
            realFcitx.stop()
            check(realFcitx.lifecycle.currentState == FcitxLifecycle.State.STOPPED) {
                "The input engine could not be stopped for user data backup or restore."
            }
        } catch (e: Exception) {
            finishFailedUserDataRestore()
            throw e
        }
    }

    /** On success the process exits with the guard held; on failure resume connected clients. */
    fun finishFailedUserDataRestore() = lock.withLock {
        userDataIoInProgress = false
        if (clients.isNotEmpty() && realFcitx.lifecycle.currentState == FcitxLifecycle.State.STOPPED) realFcitx.start()
    }

    /** Backup resumes the engine on both success and failure, before opening the save picker. */
    fun finishUserDataBackup() = finishFailedUserDataRestore()

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                appContext.getText(R.string.fcitx_daemon),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = CHANNEL_ID }
            appContext.notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Reuse a connection for remote service
     */
    fun getFirstConnectionOrNull() = clients.firstNotNullOfOrNull { it.value }


    private const val CHANNEL_ID = "fcitx-daemon"
    private var RESTART_ID = 0

}
