/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Keep queued UI work with the service and InputView that requested it. */
internal class InputViewTaskDispatcher(
    private val service: LifecycleOwner,
    private val view: Any,
    private val currentView: () -> Any?
) {
    // Main (rather than Main.immediate) retains the original posted/FIFO behavior.
    fun post(action: () -> Unit) = service.lifecycleScope.launch(Dispatchers.Main) {
        if (currentView() === view) action()
    }
}
