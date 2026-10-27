/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input

import android.app.Application
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import org.fcitx.fcitx5.android.daemon.FcitxDisconnectedException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23, 28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class InputViewTaskDispatcherTest {
    private class Service : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this).apply {
            currentState = Lifecycle.State.STARTED
        }
        fun destroy() { lifecycle.currentState = Lifecycle.State.DESTROYED }
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test fun liveViewWorkIsPostedAndKeepsFifoOrder() {
        val service = Service()
        val view = Any()
        val tasks = InputViewTaskDispatcher(service, view) { view }
        val order = mutableListOf<Int>()
        tasks.post { order += 1 }
        tasks.post { order += 2 }
        assertTrue(order.isEmpty())
        idle()
        assertEquals(listOf(1, 2), order)
        service.destroy()
    }

    @Test fun serviceDestroyCancelsQueuedDisconnectedConnectionAccess() {
        val service = Service()
        val view = Any()
        val tasks = InputViewTaskDispatcher(service, view) {
            fail("Destroyed service must not even resolve its old view")
            view
        }
        val job = tasks.post { throw FcitxDisconnectedException("old IME service") }
        service.destroy()
        idle()
        assertTrue(job.isCancelled)
    }

    @Test fun completionArrivingAfterDestroyDoesNotEnqueueUiWork() {
        val service = Service()
        val view = Any()
        val tasks = InputViewTaskDispatcher(service, view) { view }
        service.destroy()
        val job = tasks.post { fail("Late font completion must be ignored") }
        idle()
        assertTrue(job.isCancelled)
    }

    @Test fun replacedInputViewCannotTouchTheOldOrNewKeyboard() {
        val service = Service()
        val oldView = Any()
        var currentView: Any? = oldView
        val tasks = InputViewTaskDispatcher(service, oldView) { currentView }
        tasks.post { throw FcitxDisconnectedException("obsolete view") }
        currentView = Any()
        idle()
        service.destroy()
    }

    @Test fun removedInputViewDropsPendingWork() {
        val service = Service()
        val view = Any()
        var currentView: Any? = view
        val tasks = InputViewTaskDispatcher(service, view) { currentView }
        tasks.post { fail("Removed view must not be refreshed") }
        currentView = null
        idle()
        service.destroy()
    }

    @Test fun newViewWorkSurvivesDiscardingOldViewWork() {
        val service = Service()
        val oldView = Any()
        val newView = Any()
        var currentView = oldView
        val order = mutableListOf<String>()
        val oldTasks = InputViewTaskDispatcher(service, oldView) { currentView }
        oldTasks.post { order += "old" }
        currentView = newView
        InputViewTaskDispatcher(service, newView) { currentView }.post { order += "new" }
        idle()
        assertEquals(listOf("new"), order)
        service.destroy()
    }

    @Test fun temporaryWindowChangesKeepWorkForTheSameInputView() {
        val service = Service()
        val view = Any()
        val tasks = InputViewTaskDispatcher(service, view) { view }
        var refreshed = false
        // Picker/window attachment and same-editor restarts do not replace the InputView.
        tasks.post { refreshed = true }
        idle()
        assertTrue(refreshed)
        service.destroy()
    }

    @Test fun backgroundFontCompletionAfterDestroyIsHarmless() {
        val service = Service()
        val view = Any()
        val tasks = InputViewTaskDispatcher(service, view) {
            fail("Late worker must not look up dependencies")
            view
        }
        service.destroy()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val job = worker.submit<kotlinx.coroutines.Job> {
                tasks.post { fail("Late worker must not refresh a keyboard") }
            }.get(5, TimeUnit.SECONDS)
            idle()
            assertTrue(job.isCancelled)
        } finally { worker.shutdownNow() }
    }
}
