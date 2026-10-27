package org.fcitx.fcitx5.android.data.clipboard

import androidx.annotation.WorkerThread
import org.fcitx.fcitx5.android.common.clearurls.ClearUrlsRuleFilter
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.readBounded
import timber.log.Timber

object HostClipboardFilter {
    private const val RULES_ASSET = "clearurls/data.min.json"

    private val filter = ClipboardUrlFilter {
        appContext.assets.open(RULES_ASSET).use { it.readBounded(512 * 1024).toString(Charsets.UTF_8) }
    }

    fun isEnabled(): Boolean = filter.isEnabled()
    fun description(): String = filter.description()

    @WorkerThread
    fun transform(text: String): String = filter.transform(text)
}

/** Metadata is independent of loading; the first URL is filtered on its calling worker. */
internal class ClipboardUrlFilter(private val loadRules: () -> String) {
    private val filter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        runCatching { ClearUrlsRuleFilter(loadRules()) }.onFailure {
            Timber.w("Failed to load bundled ClearURLs rules: %s", it.javaClass.simpleName)
        }.getOrNull()
    }

    // This built-in feature is bundled with the APK. Metadata queries happen on service
    // Main and must not open/parse the rules or wait for their lazy initialization.
    fun isEnabled(): Boolean = true

    fun description(): String = "ClearURLs(host)"

    @WorkerThread
    fun transform(text: String): String {
        if (!text.startsWith("http://", ignoreCase = true) && !text.startsWith("https://", ignoreCase = true)) return text
        val activeFilter = filter ?: return text
        return runCatching { activeFilter.transform(text) }
            .onFailure { Timber.w("Failed to filter clipboard URL: %s", it.javaClass.simpleName) }
            .getOrDefault(text)
    }
}
