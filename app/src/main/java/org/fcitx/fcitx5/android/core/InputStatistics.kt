/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Present only on a real, privacy-eligible key job after its existing ticket was captured. */
internal class InputStatisticsSource(val generation: Long, val epoch: Long, val stillEnabled: () -> Boolean) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<InputStatisticsSource>
}

/**
 * Code length and command status describe the input before the operation; composing describes
 * its resulting preedit so an unfinished first key retains the speed session across a pause.
 * Raw input itself never enters the event or storage queues.
 */
internal data class InputOperationStatistics(
    val epoch: Long,
    val schemaId: String,
    val schemaName: String,
    val codeLength: Int,
    val command: Boolean,
    val composing: Boolean,
    val wallTimeMs: Long,
    val monotonicTimeMs: Long
)
