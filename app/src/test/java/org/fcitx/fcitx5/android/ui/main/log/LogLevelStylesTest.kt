/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.log

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.ColorUtils
import com.google.android.material.R as MaterialR
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 日志查看器按级别着色：六个级别在浅色/深色主题下都必须可辨（正文 11sp，对比度按 WCAG AA 要 >= 4.5:1），
 * 且主题属性缺失时要退回不透明的兜底色，不能解析成透明而让条目“颜色全没了”。
 *
 * 可辨性用 CIE76 色差判定而不是 HSL：HSL 只比 hue/lightness，灰色的 hue 恒为 0、饱和度完全不参与，
 * 会把「灰 Verbose vs 淡红 Error」判成同色（误报），也会放过真正难分的低饱和对。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class LogLevelStylesTest {

    private val levels = listOf('V', 'D', 'I', 'W', 'E', 'F')

    private fun logLine(level: Char) = "11-24 10:00:00.000  1000  1000 $level Tag: message"

    /** sRGB 通道做 gamma 展开，得到线性光强 0..1 */
    private fun linearize(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /**
     * CIE L*a*b*（D65 白点）。可辨性判据必须用感知色差而不是 HSL：
     * 灰色的 hue 恒为 0、饱和度被 HSL 判据完全忽略，于是「灰 vs 淡红」会被算成同色，
     * 这正是之前 HSL 判据误报的原因。
     */
    private fun toLab(color: Int): DoubleArray {
        val r = linearize(Color.red(color))
        val g = linearize(Color.green(color))
        val b = linearize(Color.blue(color))

        val x = (0.4124 * r + 0.3576 * g + 0.1805 * b) / 0.95047
        val y = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 1.00000
        val z = (0.0193 * r + 0.1192 * g + 0.9505 * b) / 1.08883

        fun f(t: Double): Double =
            if (t > 0.008856) Math.pow(t, 1.0 / 3.0) else (7.787 * t) + (16.0 / 116.0)

        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return doubleArrayOf(116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))
    }

    /** CIE76 色差：约 2.3 是刚可察觉，>= 25 表示六种级别色互相一眼可分 */
    private fun deltaE76(a: Int, b: Int): Double {
        val labA = toLab(a)
        val labB = toLab(b)
        val dl = labA[0] - labB[0]
        val da = labA[1] - labB[1]
        val db = labA[2] - labB[2]
        return Math.sqrt(dl * dl + da * da + db * db)
    }

    private fun backgroundColors(context: Context): List<Int> = listOf(
        android.R.attr.colorBackground,
        MaterialR.attr.colorSurfaceContainer,
        MaterialR.attr.colorSurface
    ).map { context.logColorOrDefault(it, Color.TRANSPARENT) }
        .filter { Color.alpha(it) != 0 }
        .distinct()

    private data class LogTheme(val style: Int, val dark: Boolean, val label: String)

    private val themes get() = listOf(
        LogTheme(R.style.Theme_FcitxSettings_Base_Light, dark = false, label = "light"),
        LogTheme(R.style.Theme_FcitxSettings_Base_Dark, dark = true, label = "dark")
    )

    /**
     * 主题「样式」本身不会切换资源限定符：只套 Base_Dark 而配置仍是 notnight 时，
     * @color/log_level_* 依旧从 values/ 里取到浅色值，于是深色背景上 Verbose 只有 3.49:1。
     * 所以配色校验必须让样式与 uiMode 的夜间位一起切换，才能真正读到 values-night/。
     */
    private fun logContext(theme: LogTheme): Context {
        val app = RuntimeEnvironment.getApplication()
        val configuration = Configuration(app.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (theme.dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        }
        return ContextThemeWrapper(app.createConfigurationContext(configuration), theme.style)
    }

    @Test
    fun everyLevelKeepsAAContrastAgainstTheListBackgroundInBothThemes() {
        for (theme in themes) {
            val context = logContext(theme)
            val backgrounds = backgroundColors(context)
            assertTrue("${theme.label} theme must expose a background color", backgrounds.isNotEmpty())
            for (level in levels) {
                val color = context.logLevelColor(logLevelStyle(level))
                assertEquals("Level $level must not resolve to a transparent color", 255, Color.alpha(color))
                for (background in backgrounds) {
                    val contrast = ColorUtils.calculateContrast(color, background)
                    assertTrue(
                        "Level $level contrast $contrast is too low against background " +
                            "#" + Integer.toHexString(background) + " in ${theme.label} theme",
                        contrast >= 4.5
                    )
                }
            }
        }
    }

    @Test
    fun allSixLevelsStayVisuallyDistinct() {
        for (theme in themes) {
            val context = logContext(theme)
            val colors = levels.map { context.logLevelColor(logLevelStyle(it)) }
            for (i in colors.indices) {
                for (j in i + 1 until colors.size) {
                    // 色差过小（旧 overlay 把 6 级压到 3 个 M3 角色）就等于分不清级别
                    val delta = deltaE76(colors[i], colors[j])
                    assertTrue(
                        "Levels ${levels[i]} and ${levels[j]} are indistinguishable in ${theme.label} theme: " +
                            "CIE76 deltaE $delta",
                        delta >= 25.0
                    )
                }
            }
        }
    }

    @Test
    fun eachLevelUsesItsOwnIconAndFallbackResource() {
        val styles = levels.map { logLevelStyle(it) }
        assertEquals("Each level needs a distinct icon", styles.size, styles.map { it.iconRes }.distinct().size)
        assertEquals(
            "Each level needs a distinct fallback color",
            styles.size,
            styles.map { it.fallbackColorRes }.distinct().size
        )
        // 解析不到级别的行按 Info 呈现，与旧行为一致
        assertEquals(logLevelStyle('I'), logLevelStyle(null))
        assertEquals(logLevelStyle('I'), logLevelStyle('X'))
    }

    @Test
    fun missingThemeAttributesFallBackToOpaqueLevelColors() {
        // 这个主题没有定义 colorLog*：解析会失败，必须退回不透明的兜底色而不是 0（全透明）
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), android.R.style.Theme_Material_Light)
        for (level in levels) {
            val color = context.logLevelColor(logLevelStyle(level))
            assertEquals("Fallback for level $level must be opaque", 255, Color.alpha(color))
            assertNotEquals("Fallback for level $level must not be transparent black", 0, color)
        }
        assertEquals(255, Color.alpha(context.logDefaultTextColor()))
    }

    @Test
    fun logLevelParsingReadsTheThreadtimeLevelLetter() {
        for (level in levels) {
            assertEquals(level, logLevelOf(logLine(level)))
        }
        assertNull(logLevelOf("--------- beginning of main"))
        assertNull(logLevelOf(""))
        // 崩溃页 append 的堆栈行没有级别
        assertNull(logLevelOf("java.lang.RuntimeException: boom"))
    }
}
