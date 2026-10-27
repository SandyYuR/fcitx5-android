package org.fcitx.fcitx5.android.clipboardsync

import android.app.Application
import android.content.Intent
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.android.controller.ServiceController
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MainServiceStopTest {
    private lateinit var controller: ServiceController<MainService>
    private lateinit var service: MainService

    @Before fun setUp() {
        controller = Robolectric.buildService(MainService::class.java).create()
        service = controller.get()
    }

    @After fun tearDown() { controller.destroy() }

    private fun enabled(value: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(service).edit()
            .putBoolean("quick_sync", value).commit()
    }

    private fun assertStopped() {
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "serviceRunning"))
        assertFalse(MainService.shouldAutoStart(service))
    }

    @Test fun quotaTimeoutStopsServiceCancelsWorkAndPersistsPause() {
        PreferenceManager.getDefaultSharedPreferences(service).edit()
            .putString("server_address", "https://fixture.example").commit()
        enabled(true)
        assertTrue(MainService.shouldAutoStart(service))
        ReflectionHelpers.setField(service, "serviceRunning", true)
        val scope = ReflectionHelpers.getField<CoroutineScope>(service, "scope")
        val worker = Job(scope.coroutineContext[Job])
        // Invoke our callback directly: this checks service teardown, not the OS quota timer.
        service.onTimeout(7, 1)
        assertStopped()
        assertTrue(worker.isCancelled)
    }

    @Test fun pauseActionDoesNotFallThroughToStart() {
        enabled(true)
        service.onStartCommand(Intent().setAction(ACTION_PREFIX + "PAUSE"), 0, 1)
        assertStopped()
    }

    @Test fun queuedFocusRefreshCannotUndoQuotaPause() {
        enabled(true)
        service.onTimeout(1, 1)
        service.onStartCommand(Intent().setAction(ACTION_PREFIX + "START"), 0, 2)
        assertStopped()
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "stopRequested"))
    }

    @Test fun staleReconnectNotificationCannotUndoPause() {
        enabled(false)
        service.onStartCommand(Intent().setAction(ACTION_PREFIX + "RECONNECT"), 0, 1)
        assertStopped()
    }

    @Test fun nullRestartIntentDoesNotStartDisabledSync() {
        enabled(false)
        service.onStartCommand(null, 0, 1)
        assertStopped()
    }

    @Test fun anUnconfiguredServerDoesNotStartNetworkWorkOrClearTheUsersEnableChoice() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(service)
        prefs.edit().remove("server_address").remove("server_address_syncclipboard")
            .remove("server_address_oneclip").remove("server_address_clipcascade")
            .remove("server_address_custom").putInt("sync_server_scheme_version", 1).commit()
        enabled(true)
        service.onStartCommand(Intent().setAction(ACTION_PREFIX + "START")
            .putExtra("force_enable_sync", true), 0, 1)
        assertTrue(shadowOf(service).isStoppedBySelf)
        assertFalse(ReflectionHelpers.getField<Boolean>(service, "serviceRunning"))
        assertTrue(prefs.getBoolean("quick_sync", false))
    }

    companion object {
        private const val ACTION_PREFIX = "org.fcitx.fcitx5.android.clipboardsync.action."
    }
}
