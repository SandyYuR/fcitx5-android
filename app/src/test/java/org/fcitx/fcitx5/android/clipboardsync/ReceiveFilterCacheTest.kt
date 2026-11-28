/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.clipboardsync

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ReceiveFilterCacheTest {
    @Test fun anOldConstructorCannotPublishAfterInvalidationReturns() {
        val cache = ReceiveFilterCache<Int>()
        val preference = AtomicInteger(1)
        val loaded = CountDownLatch(1)
        val release = CountDownLatch(1)
        val invalidating = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val old = executor.submit<Int> { cache.getOrLoad {
                val snapshot = preference.get()
                loaded.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                snapshot
            } }
            assertTrue(loaded.await(5, TimeUnit.SECONDS))
            preference.set(2)
            val invalidation = executor.submit {
                invalidating.countDown()
                cache.invalidate()
            }
            assertTrue(invalidating.await(5, TimeUnit.SECONDS))
            release.countDown()
            assertEquals(1, old.get(5, TimeUnit.SECONDS))
            invalidation.get(5, TimeUnit.SECONDS)
            assertEquals(2, cache.getOrLoad { preference.get() })
            assertEquals(2, cache.getOrLoad { error("The current generation is cached") })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
