/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core

import org.fcitx.directrime.Native
import org.fcitx.directrime.RawInputState
import org.fcitx.directrime.Snapshot
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

/** One shadow type for the process-wide Native singleton; each test supplies fresh behavior. */
internal interface NativeTestDelegate {
    fun sessionStats(): LongArray = error("Unexpected native sessionStats")
    fun operationTiming(): LongArray = error("Unexpected native operationTiming")
    fun beginInput(value: Long, program: String, sensitive: Boolean): Snapshot = error("Unexpected native beginInput")
    fun key(value: Long, sym: Int, mask: Int, release: Boolean): Snapshot = error("Unexpected native key")
    fun key(value: Long, sym: Int, mask: Int, release: Boolean, statistics: Boolean): Snapshot = key(value, sym, mask, release)
    fun action(value: Long, previous: Long, action: Int, index: Int, name: String): Snapshot = error("Unexpected native action")
    fun action(value: Long, previous: Long, action: Int, index: Int, name: String, statistics: Boolean): Snapshot = action(value, previous, action, index, name)
    fun options(): Array<String> = error("Unexpected native options")
    fun schemaEntries(): Array<String> = error("Unexpected native schemaEntries")
    fun candidates(value: Long, offset: Int, limit: Int): Array<String> = error("Unexpected native candidates")
    fun rawInput(value: Long, previous: Long): RawInputState? = error("Unexpected native rawInput")
    fun end(value: Long): Snapshot = error("Unexpected native end")
    fun selectBuiltin(): Unit = error("Unexpected native selectBuiltin")
    fun configureExternal(fd: Int, entrySymbol: String, identity: String): Unit = error("Unexpected native configureExternal")
    fun start(shared: String, user: String, version: String, fullCheck: Boolean): Unit = error("Unexpected native start")
    fun stop(): Unit = error("Unexpected native stop")
    fun sync(): Boolean = error("Unexpected native sync")
}

@Implements(Native::class, isInAndroidSdk = false)
internal class NativeTestShadow {
    companion object {
        @Volatile lateinit var delegate: NativeTestDelegate
        @JvmStatic @Implementation fun __staticInitializer__() {
            ReflectionHelpers.setStaticField(Native::class.java, "INSTANCE", ReflectionHelpers.callConstructor(Native::class.java))
        }
    }
    @Implementation fun sessionStats() = delegate.sessionStats()
    @Implementation fun operationTiming() = delegate.operationTiming()
    @Implementation fun beginInput(value: Long, program: String, sensitive: Boolean) = delegate.beginInput(value, program, sensitive)
    @Implementation fun key(value: Long, sym: Int, mask: Int, release: Boolean, statistics: Boolean) = delegate.key(value, sym, mask, release, statistics)
    @Implementation fun action(value: Long, previous: Long, action: Int, index: Int, name: String, statistics: Boolean) =
        delegate.action(value, previous, action, index, name, statistics)
    @Implementation fun options() = delegate.options()
    @Implementation fun schemaEntries() = delegate.schemaEntries()
    @Implementation fun candidates(value: Long, offset: Int, limit: Int) = delegate.candidates(value, offset, limit)
    @Implementation fun rawInput(value: Long, previous: Long) = delegate.rawInput(value, previous)
    @Implementation fun end(value: Long) = delegate.end(value)
    @Implementation fun selectBuiltin() = delegate.selectBuiltin()
    @Implementation fun configureExternal(fd: Int, entrySymbol: String, identity: String) = delegate.configureExternal(fd, entrySymbol, identity)
    @Implementation fun start(shared: String, user: String, version: String, fullCheck: Boolean) = delegate.start(shared, user, version, fullCheck)
    @Implementation fun stop() = delegate.stop()
    @Implementation fun sync() = delegate.sync()
}
