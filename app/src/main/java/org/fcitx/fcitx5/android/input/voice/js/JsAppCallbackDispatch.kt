/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Keep a failed network callback from stopping later events in the same App session. */
internal object JsAppCallbackDispatch {
    suspend fun run(callback: suspend () -> Unit) {
        try {
            callback()
        } catch (error: TimeoutCancellationException) {
            // The invocation's own deadline is a callback failure. A cancelled stream/session
            // (including an outer deadline) must still stop delivery instead of being swallowed.
            currentCoroutineContext().ensureActive()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // One plugin callback must not suppress later frames or replace its UI error.
        }
    }
}
