/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Build
import android.os.Looper
import android.os.UserManager
import androidx.core.content.ContextCompat
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.core.DirectRimeConfig
import org.fcitx.fcitx5.android.core.data.DataDescriptor
import org.fcitx.fcitx5.android.core.data.DataHierarchy
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.data.FileSource
import org.fcitx.fcitx5.android.data.BundledPresets
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.input.config.DefaultConfigProvider
import org.fcitx.fcitx5.android.input.clipboard.ClipboardWindow
import org.fcitx.fcitx5.android.input.font.DefaultFontProvider
import org.fcitx.fcitx5.android.input.keyboard.AlphabetKey
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboard
import org.fcitx.fcitx5.android.input.keyboard.TextKeyboardLayoutState
import org.fcitx.fcitx5.android.testing.hostDatabaseContext
import org.fcitx.fcitx5.android.utils.AppUtil
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Exercise the real Application startup; do not replace onCreate with an editor-test shadow. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 35], application = Application::class, shadows = [StartupExitShadow::class])
class DirectBootStartupTest {
    private var application: FcitxApplication? = null
    private var startupComplete = false

    private class StorageAccess {
        val credentialReads = mutableListOf<String>()
        var externalReads = 0
        var unlockDuringRegistration = false
        var unlockReceiver: BroadcastReceiver? = null
    }

    /** Robolectric does not enforce Android's CE lock, so enforce it at the Context boundary. */
    private class StorageContext(base: Context, val access: StorageAccess) :
        ContextWrapper(hostDatabaseContext(base)) {
        private val unlocked get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            getSystemService(UserManager::class.java).isUserUnlocked

        private fun checkCredentialStorage(operation: String) {
            if (!unlocked && !isDeviceProtectedStorage) {
                access.credentialReads += operation
                throw IllegalStateException("Credential encrypted storage is unavailable before user unlock: $operation")
            }
        }

        override fun getApplicationContext(): Context = this
        override fun createDeviceProtectedStorageContext(): Context =
            StorageContext(baseContext.createDeviceProtectedStorageContext(), access)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            checkCredentialStorage("preferences/$name")
            return super.getSharedPreferences(name, mode)
        }
        override fun getFilesDir(): File {
            checkCredentialStorage("files")
            return super.getFilesDir()
        }
        override fun getNoBackupFilesDir(): File {
            checkCredentialStorage("no_backup")
            return super.getNoBackupFilesDir()
        }
        override fun getDataDir(): File {
            checkCredentialStorage("data")
            return super.getDataDir()
        }
        override fun getDatabasePath(name: String): File {
            checkCredentialStorage("database/$name")
            return super.getDatabasePath(name)
        }
        override fun getExternalFilesDir(type: String?): File? {
            if (!unlocked) {
                access.externalReads++
                return null
            }
            return super.getExternalFilesDir(type)
        }
        override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? {
            // Match the app's legacy registration of protected system broadcasts. The
            // NOT_EXPORTED compatibility path adds an unrelated permission on API 28.
            val result = ContextCompat.registerReceiver(baseContext, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
            if (filter.hasAction(Intent.ACTION_USER_UNLOCKED)) {
                access.unlockReceiver = receiver
                if (access.unlockDuringRegistration) {
                    shadowOf(getSystemService(UserManager::class.java)).setUserUnlocked(true)
                }
            }
            return result
        }
    }

    private fun startApplication(context: Context): FcitxApplication {
        val app = FcitxApplication().also { application = it }
        ReflectionHelpers.callInstanceMethod<Unit>(app, "attachBaseContext", ClassParameter.from(Context::class.java, context))
        app.onCreate()
        startupComplete = true
        return app
    }

    private fun finishStartupWork() {
        val scopes = listOfNotNull(application?.coroutineScope, ClipboardManager,
            ReflectionHelpers.getField<CoroutineScope>(BundledPresets, "scope"),
            ReflectionHelpers.getField<CoroutineScope>(IconThemeManager, "scope"))
        val deadline = System.nanoTime() + 20_000_000_000L
        do {
            shadowOf(Looper.getMainLooper()).idle()
            if (scopes.none { scope -> scope.coroutineContext[Job]?.children?.any { it.isActive } == true }) return
            Thread.sleep(10)
        } while (System.nanoTime() < deadline)
        error("Application startup work did not finish")
    }

    @After fun closeApplication() {
        application?.directBootLayoutSync?.let { sync ->
            sync.close()
            runBlocking { sync.join() }
        }
        if (!startupComplete) {
            application?.coroutineScope?.cancel()
            return
        }
        try {
            finishStartupWork()
        } finally {
            application?.coroutineScope?.cancel()
            ClipboardManager.closeDatabase()
        }
    }

    @Test fun lockedStartupUsesDevicePreferencesWithoutOpeningCredentialStorage() {
        val base = RuntimeEnvironment.getApplication()
        val credentialPrefs = PreferenceManager.getDefaultSharedPreferences(base)
        credentialPrefs.edit().putString("settings_ui_mode", "dark")
            .putBoolean("settings_ui_initialized", true).commit()
        shadowOf(base.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val access = StorageAccess()
        val context = StorageContext(base, access)
        val app = startApplication(context)

        assertTrue(app.isDirectBootMode)
        assertTrue(app.directBootAwareContext.isDeviceProtectedStorage)
        assertNotNull(AppPrefs.getInstance())
        assertNotNull(ThemeManager.activeTheme)
        assertTrue(PreferenceManager.getDefaultSharedPreferences(app.directBootAwareContext)
            .getBoolean("settings_ui_initialized", false))
        assertEquals("dark", credentialPrefs.getString("settings_ui_mode", null))
        // Reboot an already installed app. An up-to-date descriptor avoids reinstalling
        // unrelated asset symlinks; this test checks storage selection and CE cleanup.
        // AssetMigrationTest separately covers installing/replacing real asset links.
        val descriptor = app.assets.open(BuildConfig.DATA_DESCRIPTOR_NAME).bufferedReader().use {
            Json.decodeFromString<DataDescriptor>(it.readText())
        }
        val installed = DataHierarchy().apply { install(descriptor, FileSource.Main) }.downToDataDescriptor()
        app.directBootAwareContext.dataDir.resolve(BuildConfig.DATA_DESCRIPTOR_NAME)
            .writeText(Json.encodeToString(installed))
        DataManager.sync()
        val config = DirectRimeConfig(app.directBootAwareContext).apply { load() }
        assertEquals(app.directBootAwareContext.filesDir, config.root)
        assertTrue(config.user.listFiles().isNullOrEmpty())
        assertNull(UserConfigFiles.textKeyboardLayoutJson())
        assertNull(UserConfigFiles.fontsetJson())
        TextKeyboard.setPasswordDefaultLayout(true)
        try {
            val letters = TextKeyboard.getLayout(TextKeyboardLayoutState()).flatten()
                .filterIsInstance<AlphabetKey>().joinToString("") { it.character.lowercase() }
            assertEquals("qwertyuiopasdfghjklzxcvbnm", letters)
        } finally { TextKeyboard.setPasswordDefaultLayout(false) }
        val clipboardPrefs = ReflectionHelpers.callInstanceMethod<SharedPreferences>(ClipboardWindow(), "getSharedPreferences")
        assertSame(PreferenceManager.getDefaultSharedPreferences(app.directBootAwareContext), clipboardPrefs)
        val fontLoad = CountDownLatch(1)
        DefaultFontProvider().preloadFontsAsync { fontLoad.countDown() }
        assertTrue(fontLoad.await(5, TimeUnit.SECONDS))
        assertTrue("CE reads: ${access.credentialReads}", access.credentialReads.isEmpty())
        assertEquals("Locked startup uses built-in fonts and icons", 0, access.externalReads)

        StartupExitShadow.exitRequests = 0
        shadowOf(base.getSystemService(UserManager::class.java)).setUserUnlocked(true)
        requireNotNull(access.unlockReceiver).onReceive(app, Intent(Intent.ACTION_USER_UNLOCKED))
        assertEquals(1, StartupExitShadow.exitRequests)
        assertTrue("Storage changes only after process restart", app.isDirectBootMode)
        assertEquals("dark", credentialPrefs.getString("settings_ui_mode", null))
    }

    @Test fun unlockedStartupUsesCredentialSettingsAndDoesNotCopyPrivateValuesToDeviceStorage() {
        val base = RuntimeEnvironment.getApplication()
        val credentials = PreferenceManager.getDefaultSharedPreferences(base)
        credentials.edit().putBoolean("settings_ui_initialized", true)
            .putString("settings_ui_mode", "dark").putString("private_test_value", "keep in CE").commit()
        val device = PreferenceManager.getDefaultSharedPreferences(base.createDeviceProtectedStorageContext())
        device.edit().putBoolean("settings_ui_initialized", true).putString("settings_ui_mode", "light").commit()

        val app = startApplication(StorageContext(base, StorageAccess()))
        finishStartupWork()

        assertFalse(app.isDirectBootMode)
        assertFalse(app.directBootAwareContext.isDeviceProtectedStorage)
        assertEquals("dark", PreferenceManager.getDefaultSharedPreferences(app.directBootAwareContext)
            .getString("settings_ui_mode", null))
        assertSame(credentials, ReflectionHelpers.callInstanceMethod<SharedPreferences>(ClipboardWindow(), "getSharedPreferences"))
        assertEquals("light", device.getString("settings_ui_mode", null))
        assertFalse(device.contains("private_test_value"))
    }

    @Test fun lockedStartupReadsTheActiveSnapshotWithoutCredentialStorageOrProfilePreferences() {
        val base = RuntimeEnvironment.getApplication()
        val deviceContext = base.createDeviceProtectedStorageContext()
        PreferenceManager.getDefaultSharedPreferences(deviceContext).edit()
            .putString("text_keyboard_layout_profile", "stale-profile").commit()
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val keys = letters.map { """{"type":"AlphabetKey","main":"$it","alt":"1"}""" }.joinToString(",")
        val snapshot = File(deviceContext.filesDir, "direct_boot_keyboard/TextKeyboardLayout.json").apply {
            parentFile!!.mkdirs()
            // The custom default differs from built-in QWERTY; a schema layout must not win.
            writeText("""{"rime":{"default":[[$keys]],"九键":[[{"type":"AlphabetKey","main":"x"}]]}}""")
        }
        shadowOf(base.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val access = StorageAccess()
        val app = startApplication(StorageContext(base, access))
        assertNull(app.directBootLayoutSync)
        assertEquals(snapshot, DefaultConfigProvider.textKeyboardLayoutFile())
        TextKeyboard.setPasswordDefaultLayout(true)
        try {
            val actual = TextKeyboard.getLayout(TextKeyboardLayoutState()).flatten()
                .filterIsInstance<AlphabetKey>().joinToString("") { it.character.lowercase() }
            assertEquals(letters, actual)
        } finally { TextKeyboard.setPasswordDefaultLayout(false) }
        assertTrue("CE reads: ${access.credentialReads}", access.credentialReads.isEmpty())
        assertEquals(0, access.externalReads)
    }

    @Test @Config(sdk = [23]) fun androidMarshmallowDoesNotUseDeviceProtectedStorage() {
        val app = startApplication(StorageContext(RuntimeEnvironment.getApplication(), StorageAccess()))
        assertFalse(app.isDirectBootMode)
        assertNull(app.directBootLayoutSync)
        assertNull(UserConfigFiles.directBootTextKeyboardLayoutJson())
    }

    @Test fun malformedSnapshotKeysFallBackToBuiltinPasswordKeyboard() {
        val base = RuntimeEnvironment.getApplication()
        File(base.createDeviceProtectedStorageContext().filesDir,
            "direct_boot_keyboard/TextKeyboardLayout.json").apply {
            parentFile!!.mkdirs()
            writeText("""{"rime":{"default":[[{"type":"AlphabetKey","main":"a","alt":""}]]}}""")
        }
        shadowOf(base.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val access = StorageAccess()
        startApplication(StorageContext(base, access))
        TextKeyboard.setPasswordDefaultLayout(true)
        try {
            val actual = TextKeyboard.getLayout(TextKeyboardLayoutState()).flatten()
                .filterIsInstance<AlphabetKey>().joinToString("") { it.character.lowercase() }
            assertEquals("qwertyuiopasdfghjklzxcvbnm", actual)
        } finally { TextKeyboard.setPasswordDefaultLayout(false) }
        assertTrue(access.credentialReads.isEmpty())
        assertEquals(0, access.externalReads)
    }

    @Test fun unlockingDuringReceiverRegistrationSelectsCredentialStorageImmediately() {
        val base = RuntimeEnvironment.getApplication()
        shadowOf(base.getSystemService(UserManager::class.java)).setUserUnlocked(false)
        val access = StorageAccess().apply { unlockDuringRegistration = true }

        val app = startApplication(StorageContext(base, access))

        assertFalse(app.isDirectBootMode)
        assertFalse(app.directBootAwareContext.isDeviceProtectedStorage)
        assertTrue(access.credentialReads.isEmpty())
    }
}

/** Only process exit is intercepted; startup and the unlock receiver are production code. */
@Implements(AppUtil::class)
class StartupExitShadow {
    @Implementation fun exit() { exitRequests++ }
    companion object { var exitRequests = 0 }
}
