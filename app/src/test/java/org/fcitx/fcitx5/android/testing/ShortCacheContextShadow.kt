/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.testing

import android.content.Context
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.annotation.Resetter
import org.robolectric.shadows.ShadowContextImpl
import java.io.File
import java.nio.file.Files

/** Keep native SQLite fixture paths below Windows MAX_PATH, with a private cache per app. */
@Implements(className = "android.app.ContextImpl", isInAndroidSdk = false)
class ShortCacheContextShadow : ShadowContextImpl() {
    @RealObject private lateinit var context: Context
    @Implementation fun getCacheDir(): File = caches.getOrPut(context.applicationInfo.dataDir) {
        Files.createTempDirectory("ime-cache-").toFile()
    }
    companion object {
        private val caches = mutableMapOf<String, File>()
        @JvmStatic @Resetter fun clearCaches() {
            caches.values.forEach { cache ->
                if (cache.exists()) Files.walk(cache.toPath()).use { paths ->
                    paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
                }
            }
            caches.clear()
        }
    }
}
