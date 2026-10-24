/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior.editor

import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers

/** Use real preferences and theme colors without starting the native input engine. */
@Implements(FcitxApplication::class)
class EditorApplicationShadow : org.robolectric.shadows.ShadowApplication() {
    @RealObject private lateinit var application: FcitxApplication
    @Implementation fun onCreate() {
        ReflectionHelpers.setStaticField(FcitxApplication::class.java, "instance", application)
        AppPrefs.init(application.getSharedPreferences("editor-test", 0))
        ReflectionHelpers.setStaticField(ThemeManager::class.java, "_activeTheme", ThemePreset.PixelLight)
    }
}
