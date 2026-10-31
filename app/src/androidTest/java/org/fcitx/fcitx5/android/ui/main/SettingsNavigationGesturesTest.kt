/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main

import android.app.Activity
import android.content.Intent
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.viewpager2.widget.ViewPager2
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.prefs.SettingsUiMotionPreferences
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.main.settings.SettingsRoute
import org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupFragment
import org.fcitx.fcitx5.android.ui.main.settings.group.SettingsGroupSpecs
import org.fcitx.fcitx5.android.ui.main.settings.theme.CustomThemeActivity
import org.fcitx.fcitx5.android.ui.main.settings.theme.SettingsUiAppearanceFragment
import org.fcitx.fcitx5.android.ui.main.settings.theme.SettingsUiMotionFragment
import org.fcitx.fcitx5.android.ui.main.settings.theme.ThemeFragment
import org.fcitx.fcitx5.android.utils.navigateWithAnim
import org.junit.Assert.*
import org.junit.Test

/** Real system edge gestures, with animations enabled, on a disposable emulator. */
@SdkSuppress(minSdkVersion = 34)
class SettingsNavigationGesturesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }

    private fun capture(name: String) = shell("screencap -p /data/local/tmp/settings-$name.png")

    private fun awaitCondition(description: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            onMain { ready = condition() }
            if (ready) { instrumentation.waitForIdleSync(); return }
            SystemClock.sleep(30)
        }
        capture("failure")
        fail(description)
    }

    private fun host(activity: MainActivity) =
        activity.supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment

    private fun awaitPage(activity: MainActivity, expected: Class<*>) =
        awaitCondition("Page did not settle: ${expected.simpleName}") {
            val page = host(activity).childFragmentManager.primaryNavigationFragment
            activity.hasWindowFocus() && page != null && expected.isInstance(page) && page.isResumed &&
                page.view?.let { it.width > 0 && kotlin.math.abs(it.translationX) < 1f &&
                    kotlin.math.abs(it.translationY) < 1f && kotlin.math.abs(it.scaleX - 1f) < 0.01f &&
                    kotlin.math.abs(it.scaleY - 1f) < 0.01f && it.alpha > 0.99f } == true
        }

    private fun awaitWindow(activity: Activity) = awaitCondition("Activity must regain focus without the IME") {
        activity.hasWindowFocus() && !activity.isFinishing && activity.window.decorView.width > 0 &&
            activity.window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) != true
    }

    private fun launchMain(route: SettingsRoute? = null): MainActivity =
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_RUN)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .apply { route?.let { putExtra(MainActivity.EXTRA_SETTINGS_ROUTE, it) } }) as MainActivity

    private fun navigate(activity: MainActivity, route: SettingsRoute) =
        onMain { host(activity).navController.navigateWithAnim(route) }

    private fun finish(activity: Activity) {
        onMain { if (!activity.isFinishing) activity.finish() }
        instrumentation.waitForIdleSync()
    }

    private fun gestures(block: (SettingsUiMotionPreferences.Selection) -> Unit) {
        val old = SettingsUiMotionPreferences.read(context)
        shell("pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS")
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean("need_notifications", false).commit()
        SettingsUiMotionPreferences.save(context, old.copy(
            enter = SettingsUiMotionPreferences.Style.HORIZONTAL,
            returning = SettingsUiMotionPreferences.Style.FOLLOW, predictiveBack = true))
        try { block(old) } finally { SettingsUiMotionPreferences.save(context, old) }
    }

    private fun injectPointer(down: Long, action: Int, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try { assertTrue("Touch event must be injected", instrumentation.uiAutomation.injectInputEvent(event, true)) }
        finally { event.recycle() }
    }

    private fun swipeBack(activity: Activity, cancel: Boolean, right: Boolean, name: String) {
        val width = activity.resources.displayMetrics.widthPixels.toFloat()
        val y = activity.resources.displayMetrics.heightPixels * 0.6f
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, progress: Float) {
            val x = if (right) width - 2f - progress * width else 2f + progress * width
            injectPointer(down, action, x, y)
        }
        var released = false
        try {
            send(MotionEvent.ACTION_DOWN, 0f)
            for (step in 1..12) { send(MotionEvent.ACTION_MOVE, step * 0.025f); SystemClock.sleep(20) }
            capture("$name-progress")
            if (cancel) {
                for (step in 11 downTo 0) { send(MotionEvent.ACTION_MOVE, step * 0.025f); SystemClock.sleep(20) }
                send(MotionEvent.ACTION_UP, 0f)
            } else {
                for (step in 13..24) { send(MotionEvent.ACTION_MOVE, step * 0.025f); SystemClock.sleep(20) }
                send(MotionEvent.ACTION_UP, 0.6f)
            }
            released = true
        } finally {
            if (!released) send(MotionEvent.ACTION_CANCEL, 0f)
        }
        if (cancel) {
            // Android 14 finishes its cancellation spring after app focus returns. Starting
            // another injected gesture immediately can make that callback reset the new
            // ProgressAnimator and the framework then drops its onBackInvoked callback.
            SystemClock.sleep(700)
            instrumentation.waitForIdleSync()
        }
    }

    @Test fun cancellingThenCompletingBothEdgeGesturesKeepsNavigationConsistent() = gestures { old ->
            for (style in listOf(SettingsUiMotionPreferences.Style.HORIZONTAL,
                SettingsUiMotionPreferences.Style.VERTICAL, SettingsUiMotionPreferences.Style.SCALE,
                SettingsUiMotionPreferences.Style.FADE)) {
                SettingsUiMotionPreferences.save(context, old.copy(enter = style,
                    returning = SettingsUiMotionPreferences.Style.FOLLOW, predictiveBack = true))
                val activity = launchMain(SettingsRoute.SettingsGroup(SettingsGroupSpecs.ID_APPEARANCE))
                try {
                    awaitPage(activity, SettingsGroupFragment::class.java)
                    navigate(activity, SettingsRoute.SettingsUiAppearance)
                    awaitPage(activity, SettingsUiAppearanceFragment::class.java)
                    swipeBack(activity, cancel = true, right = false, name = style.value + "-cancel")
                    awaitPage(activity, SettingsUiAppearanceFragment::class.java)
                    capture("${style.value}-cancelled")
                    swipeBack(activity, cancel = false, right = true, name = style.value + "-commit")
                    awaitPage(activity, SettingsGroupFragment::class.java)
                    navigate(activity, SettingsRoute.Theme)
                    awaitPage(activity, ThemeFragment::class.java)
                    capture("theme-${style.value}")
                } finally { finish(activity) }
            }
    }

    @Test fun repeatedBackGesturesReturnThroughEachDestination() = gestures {
        val activity = launchMain(SettingsRoute.SettingsGroup(SettingsGroupSpecs.ID_APPEARANCE))
        try {
            awaitPage(activity, SettingsGroupFragment::class.java)
            navigate(activity, SettingsRoute.SettingsUiAppearance)
            awaitPage(activity, SettingsUiAppearanceFragment::class.java)
            navigate(activity, SettingsRoute.SettingsUiMotion)
            awaitPage(activity, SettingsUiMotionFragment::class.java)
            swipeBack(activity, cancel = false, right = true, name = "repeated-first")
            awaitPage(activity, SettingsUiAppearanceFragment::class.java)
            swipeBack(activity, cancel = false, right = false, name = "repeated-second")
            awaitPage(activity, SettingsGroupFragment::class.java)
            swipeBack(activity, cancel = false, right = true, name = "repeated-third")
            awaitPage(activity, MainFragment::class.java)
            onMain { assertFalse(activity.isFinishing) }
            capture("repeated-home")
        } finally { finish(activity) }
    }

    @Test fun searchQuerySurvivesChildReturnAndCancelledBackThenClearsOnCommit() = gestures {
        val activity = launchMain()
        val query = context.getString(R.string.settings_group_appearance)
        fun search() = activity.findViewById<EditText>(R.id.settings_search_input)
        try {
            awaitPage(activity, MainFragment::class.java)
            onMain { search().setText(query); search().clearFocus() }
            awaitCondition("Search query must be shown") { search().text.toString() == query }
            navigate(activity, SettingsRoute.SettingsUiAppearance)
            awaitPage(activity, SettingsUiAppearanceFragment::class.java)
            swipeBack(activity, cancel = false, right = false, name = "search-child-return")
            awaitPage(activity, MainFragment::class.java)
            onMain { assertEquals(query, search().text.toString()) }
            swipeBack(activity, cancel = true, right = true, name = "search-cancel")
            awaitPage(activity, MainFragment::class.java)
            onMain { assertEquals(query, search().text.toString()) }
            swipeBack(activity, cancel = false, right = true, name = "search-clear")
            awaitCondition("Completed back must clear search without finishing the Activity") {
                !activity.isFinishing && activity.hasWindowFocus() && search().text.isEmpty()
            }
            capture("search-cleared")
        } finally { finish(activity) }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }

    private fun dialog(activity: Activity): View? = WindowInspector.getGlobalWindowViews().firstOrNull {
        it !== activity.window.decorView && it.findViewById<View>(android.R.id.button1)?.isShown == true
    }

    @Test fun unsavedThemeRequiresAnExplicitDiscardAfterCompletedBack() = gestures {
        val original = ThemePreset.PixelLight.deriveCustomNoBackground("Gesture original")
        val draftName = "Gesture draft ${SystemClock.uptimeMillis()}"
        val activity = instrumentation.startActivitySync(Intent(context, CustomThemeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            .putExtra(CustomThemeActivity.ORIGIN_THEME, original)) as CustomThemeActivity
        try {
            awaitWindow(activity)
            onMain {
                val rename = descendants(activity.window.decorView).first {
                    it.isShown && it.contentDescription?.toString() == activity.getString(R.string.theme_name)
                }
                assertTrue(rename.performClick())
            }
            awaitCondition("Theme rename dialog must open") { dialog(activity) != null }
            onMain {
                val root = checkNotNull(dialog(activity))
                descendants(root).filterIsInstance<EditText>().first().setText(draftName)
                root.findViewById<View>(android.R.id.button1).performClick()
            }
            awaitWindow(activity)
            swipeBack(activity, cancel = true, right = false, name = "draft-cancel")
            awaitWindow(activity)
            onMain {
                assertEquals(draftName, activity.supportActionBar?.title.toString())
                assertNull(dialog(activity))
            }
            swipeBack(activity, cancel = false, right = true, name = "draft-prompt")
            awaitCondition("Completed back must ask about unsaved changes") {
                dialog(activity)?.findViewById<TextView>(android.R.id.message)?.text?.toString() ==
                    activity.getString(R.string.theme_unsaved_message)
            }
            capture("draft-confirmation")
            onMain {
                assertFalse(activity.isFinishing)
                checkNotNull(dialog(activity)).findViewById<View>(android.R.id.button2).performClick()
            }
            awaitWindow(activity)
            onMain { assertEquals(draftName, activity.supportActionBar?.title.toString()) }
            swipeBack(activity, cancel = false, right = false, name = "draft-discard")
            awaitCondition("Discard confirmation must open again") { dialog(activity) != null }
            onMain {
                val discard = checkNotNull(dialog(activity)).findViewById<TextView>(android.R.id.button3)
                assertEquals(activity.getString(R.string.theme_discard), discard.text.toString())
                discard.performClick()
            }
            awaitCondition("Explicit discard must close the editor") { activity.isFinishing || activity.isDestroyed }
            onMain { assertFalse(ThemeManager.getAllThemes().any { theme -> theme.name == draftName }) }
        } finally { finish(activity) }
    }

    private fun swipePager(pager: ViewPager2, toNext: Boolean) {
        val bounds = Rect()
        onMain {
            val location = IntArray(2)
            pager.getLocationOnScreen(location)
            bounds.set(location[0], location[1], location[0] + pager.width, location[1] + pager.height)
        }
        assertTrue(bounds.width() > 0 && bounds.height() > 0)
        val down = SystemClock.uptimeMillis()
        val start = if (toNext) 0.8f else 0.2f
        val end = if (toNext) 0.2f else 0.8f
        val y = bounds.top + minOf(bounds.height() / 2f, 32f * pager.resources.displayMetrics.density)
        fun send(action: Int, position: Float) = injectPointer(down, action,
            bounds.left + bounds.width() * position, y)
        var released = false
        try {
            send(MotionEvent.ACTION_DOWN, start)
            for (step in 1..16) {
                send(MotionEvent.ACTION_MOVE, start + (end - start) * step / 16)
                SystemClock.sleep(20)
            }
            send(MotionEvent.ACTION_UP, end)
            released = true
        } finally { if (!released) send(MotionEvent.ACTION_CANCEL, start) }
    }

    @Test fun themePagerSwipesRemainSeparateFromCancelledAndCompletedSystemBack() = gestures {
        val activity = launchMain(SettingsRoute.SettingsGroup(SettingsGroupSpecs.ID_APPEARANCE))
        try {
            awaitPage(activity, SettingsGroupFragment::class.java)
            navigate(activity, SettingsRoute.Theme)
            awaitPage(activity, ThemeFragment::class.java)
            lateinit var pager: ViewPager2
            onMain { pager = descendants(activity.window.decorView).filterIsInstance<ViewPager2>().single() }
            for (index in listOf(1, 0, 1)) {
                swipePager(pager, toNext = index == 1)
                awaitCondition("Theme tab $index must settle after a horizontal swipe") {
                    pager.currentItem == index && pager.scrollState == ViewPager2.SCROLL_STATE_IDLE
                }
                capture("theme-tab-$index")
            }
            swipeBack(activity, cancel = true, right = false, name = "theme-page-cancel")
            awaitPage(activity, ThemeFragment::class.java)
            onMain { assertEquals(1, pager.currentItem) }
            swipeBack(activity, cancel = false, right = true, name = "theme-page-commit")
            awaitPage(activity, SettingsGroupFragment::class.java)
        } finally { finish(activity) }
    }
}
