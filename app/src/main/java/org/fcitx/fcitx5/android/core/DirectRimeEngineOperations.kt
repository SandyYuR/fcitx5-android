/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import org.fcitx.directrime.Native
import org.fcitx.fcitx5.android.core.engine.ResolvedRimeEngine
import org.fcitx.fcitx5.android.core.engine.RimeEngineKind
import org.fcitx.fcitx5.android.core.engine.RimeEnginePlugins
import org.fcitx.fcitx5.android.core.engine.RimeEngineRepository
import org.fcitx.fcitx5.android.core.engine.RimeEngineSelection
import java.io.File

private const val EXTERNAL_STOP_TIMEOUT_MS = 10_000L
private const val EXTERNAL_START_TIMEOUT_MS = 180_000L

/** Engine selection and slot operations run synchronously on the existing native owner. */
internal class DirectRimeEngineOperations(
    private val context: Context,
    private val directBootMode: Boolean,
    private val config: DirectRimeConfig,
    private val repository: () -> RimeEngineRepository,
    private val shared: () -> File,
    private val versionName: String,
    private val owner: Owner,
) {
    /** The backend keeps the only running flag, snapshot and presentation state. */
    interface Owner {
        var started: Boolean
        fun clearSnapshot()
        fun clearStartupError()
        fun refreshSchemas()
        fun resetSlotPresentationCaches()
    }

    var activeSelection = RimeEngineSelection.Builtin
        private set
    var newActivation = false
        private set
    private var maintenanceActivationSelection: RimeEngineSelection? = null
    private val processHandler = Handler(Looper.getMainLooper())
    private var externalOperationWatchdog: Runnable? = null

    /** The requested engine failed, but the persisted fallback is already running again. */
    class EngineSwitchRecoveredException(message: String, cause: Exception) :
        IllegalStateException(message, cause)

    fun resetStartupState() {
        newActivation = false
        activeSelection = RimeEngineSelection.Builtin
    }

    fun startupSucceeded() {
        if (!directBootMode) repository().activationSucceeded(activeSelection)
        cancelExternalOperationWatchdog()
    }

    fun startupFailed(error: Exception) {
        val failedSelection = activeSelection
        cancelExternalOperationWatchdog()
        // A stable builtin failure stays visible. A selected external or explicit transition
        // must leave the persisted fallback for a fresh process, never load it in this one.
        if (!directBootMode &&
            (failedSelection.kind != RimeEngineKind.BUILTIN || newActivation)) {
            repository().activationFailed(failedSelection,
                "${if (failedSelection.kind == RimeEngineKind.BUILTIN) "内置" else "外部"}引擎启动失败：${error.message ?: error.javaClass.simpleName}")
            scheduleProcessRestart()
        }
    }

    fun completeMaintenanceActivation() {
        maintenanceActivationSelection?.let { selection ->
            repository().activationSucceeded(selection)
            maintenanceActivationSelection = null
            cancelExternalOperationWatchdog()
        }
    }

    fun failMaintenanceActivation(error: Exception): Boolean {
        val failedExternal = maintenanceActivationSelection
        if (failedExternal != null) {
            maintenanceActivationSelection = null
            repository().activationFailed(failedExternal,
                "外部引擎重新启动失败：${error.message ?: error.javaClass.simpleName}")
            // Setup may have partially run; the persisted fallback starts in the next process.
            owner.started = false
            cancelExternalOperationWatchdog()
            scheduleProcessRestart()
        }
        return failedExternal != null
    }

    fun configureSelectedEngine(): Boolean {
        if (directBootMode) {
            activeSelection = RimeEngineSelection.Builtin
            return false
        }
        val (state, forceFullCheck) = repository().beginActivation()
        newActivation = forceFullCheck
        // Set this before payload publication, provider revalidation or profile checks. Any of
        // those can fail and must roll back the persisted external selection, not blame builtin.
        activeSelection = state.active
        if (state.active.kind != RimeEngineKind.BUILTIN) {
            armExternalOperationWatchdog(EXTERNAL_START_TIMEOUT_MS)
        }
        val plugins = if (state.active.kind == RimeEngineKind.PLUGIN) {
            RimeEnginePlugins.discover(context, state.active.id)
        } else emptyList()
        val resolved = repository().resolveActive(plugins)
        require(resolved.selection == state.active) {
            "已选择的外部引擎文件、签名或安装状态发生变化"
        }
        // resolveActive snapshots a provider payload by authorized hash. Re-query package
        // certificate and metadata after that copy so an update race cannot inherit consent.
        if (state.active.kind == RimeEngineKind.PLUGIN) {
            RimeEnginePlugins.requireCurrent(context,
                plugins.singleOrNull() ?: error("引擎提供 APK 不可用"))
        }
        val profiles = config.directoryState().findByName("Profiles")?.value.orEmpty()
            .lineSequence().filter(String::isNotBlank).toSet()
        val targetProfile = when (resolved) {
            ResolvedRimeEngine.Builtin -> state.builtinProfile
                ?.takeIf { (forceFullCheck || state.restoreBuiltinProfile) && it in profiles }
            is ResolvedRimeEngine.External -> resolved.profile.also {
                require(it in profiles) { "引擎绑定的方案工作目录不存在：$it" }
                config.requireExternalEngineProfile(it)
            }
        }
        if (targetProfile != null && targetProfile != config.current) config.switch(targetProfile)
        when (resolved) {
            ResolvedRimeEngine.Builtin -> Native.selectBuiltin()
            is ResolvedRimeEngine.External -> {
                val file = File(resolved.filePath)
                require(file.isFile) { "外部引擎文件不存在" }
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    Native.configureExternal(descriptor.fd, resolved.entrySymbol, resolved.payloadSha256)
                }
            }
        }
        return forceFullCheck
    }

    fun switchVariantOnOwner() {
        require(!directBootMode) { "首次解锁前不能切换 Rime 引擎" }
        val requested = repository().state()
        if (requested.active == activeSelection && requested.activation == null) return
        val requestedSelection = requested.active

        // Keep this outside the recoverable target-load block. If the current engine cannot
        // finalize cleanly, starting any other slot in the contaminated process is unsafe; an
        // armed external watchdog remains responsible for the process-level fallback.
        stopNativeForEngineSwitch()
        try {
            val fullCheck = configureSelectedEngine()
            require(activeSelection == requestedSelection) { "待切换引擎状态已变化" }
            startConfiguredEngine(fullCheck)
            owner.refreshSchemas()
            owner.resetSlotPresentationCaches()
            repository().activationSucceeded(activeSelection)
            cancelExternalOperationWatchdog()
            owner.clearStartupError()
        } catch (targetError: Exception) {
            cancelExternalOperationWatchdog()
            // start() can fail after selecting the target slot. stop() is idempotent and clears
            // any session/notification handler that was installed before the failure surfaced.
            owner.started = false
            armExternalOperationWatchdog(EXTERNAL_STOP_TIMEOUT_MS)
            try {
                Native.stop()
                cancelExternalOperationWatchdog()
            } catch (stopError: Exception) {
                targetError.addSuppressed(stopError)
                // The target slot could still own sessions, callbacks or partly initialized
                // module state. Never load the fallback into this contaminated process.
                scheduleProcessRestart()
                throw targetError
            }

            try {
                repository().activationFailed(requestedSelection,
                    "引擎切换失败，已恢复先前引擎：${targetError.message ?: targetError.javaClass.simpleName}")
                val fallbackFullCheck = configureSelectedEngine()
                check(activeSelection != requestedSelection) { "没有可用的回退引擎" }
                startConfiguredEngine(fallbackFullCheck)
                owner.refreshSchemas()
                owner.resetSlotPresentationCaches()
                repository().activationSucceeded(activeSelection)
                cancelExternalOperationWatchdog()
                owner.clearStartupError()
            } catch (recoveryError: Exception) {
                if (recoveryError !== targetError) targetError.addSuppressed(recoveryError)
                owner.started = false
                cancelExternalOperationWatchdog()
                // The activation record is deliberately left attempted. A fresh process will
                // reject the incomplete fallback and continue to the built-in safety net.
                scheduleProcessRestart()
                throw targetError
            }
            throw EngineSwitchRecoveredException(
                "目标引擎加载失败，已恢复先前引擎：${targetError.message ?: targetError.javaClass.simpleName}",
                targetError)
        }
    }

    /** Stop only the current native slot; unlike [stopForRestart], selection may differ. */
    private fun stopNativeForEngineSwitch() {
        if (!owner.started) return
        // Even the trusted built-in slot must not leave the visible switch UI waiting forever.
        // The watchdog is a last-resort crash-consistent fallback, never the normal switch path.
        armExternalOperationWatchdog(EXTERNAL_STOP_TIMEOUT_MS)
        // A throwing finalize leaves this slot unusable. Keep the watchdog armed and prevent the
        // maintenance recovery path from re-opening input before the guarded process exits.
        owner.started = false
        owner.clearSnapshot()
        Native.stop()
        cancelExternalOperationWatchdog()
    }

    private fun startConfiguredEngine(fullCheck: Boolean) {
        armExternalOperationWatchdog(EXTERNAL_START_TIMEOUT_MS)
        Native.start(shared().absolutePath, config.user.absolutePath, versionName,
            fullCheck = fullCheck)
        owner.started = true
    }

    private fun prepareMaintenanceRestart() {
        if (!directBootMode) {
            val persisted = repository().state()
            if (persisted.active != activeSelection ||
                (activeSelection.kind == RimeEngineKind.BUILTIN && persisted.activation != null)) {
                scheduleProcessRestart()
                error("引擎选择已改变，正在用新进程应用设置")
            }
        }
        if (!directBootMode && activeSelection.kind != RimeEngineKind.BUILTIN &&
            maintenanceActivationSelection == null) {
            val (guarded, _) = repository().beginActivation()
            if (guarded.active != activeSelection) {
                // A previous restart never completed. beginActivation already persisted the
                // fallback; this process still owns the old DSO and must not initialize it again.
                scheduleProcessRestart()
                error("外部引擎上次重新启动未完成，正在恢复回退引擎")
            }
            maintenanceActivationSelection = activeSelection
            val boundProfile = repository().activeExternalProfile()
                ?: error("活动外部引擎没有绑定工作目录")
            config.requireExternalEngineProfile(boundProfile)
            if (config.current != boundProfile) config.switch(boundProfile)
        }
    }

    fun stopForRestart() {
        prepareMaintenanceRestart()
        if (!owner.started) return
        if (activeSelection.kind != RimeEngineKind.BUILTIN) {
            armExternalOperationWatchdog(EXTERNAL_STOP_TIMEOUT_MS)
        }
        Native.stop()
        owner.started = false
        cancelExternalOperationWatchdog()
    }

    fun restart() {
        prepareMaintenanceRestart()
        if (owner.started) stopForRestart()
        if (activeSelection.kind != RimeEngineKind.BUILTIN) {
            armExternalOperationWatchdog(EXTERNAL_START_TIMEOUT_MS)
        }
        Native.start(shared().absolutePath, config.user.absolutePath, versionName, fullCheck = true)
        owner.started = true
    }

    private fun scheduleProcessRestart() {
        cancelExternalOperationWatchdog()
        processHandler.postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 500)
    }

    private fun armExternalOperationWatchdog(delayMillis: Long) {
        cancelExternalOperationWatchdog()
        val watchdog = Runnable {
            android.util.Log.e("RimeLifecycle", "engine operation timed out; killing guarded process")
            android.os.Process.killProcess(android.os.Process.myPid())
        }
        externalOperationWatchdog = watchdog
        processHandler.postDelayed(watchdog, delayMillis)
    }

    private fun cancelExternalOperationWatchdog() {
        externalOperationWatchdog?.let(processHandler::removeCallbacks)
        externalOperationWatchdog = null
    }
}
