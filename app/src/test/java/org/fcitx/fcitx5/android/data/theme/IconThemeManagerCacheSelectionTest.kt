/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.drawable.ColorDrawable
import android.util.LruCache
import com.caverock.androidsvg.SVG
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.util.concurrent.atomic.AtomicInteger

/**
 * 图标主题缓存的同步边界与扫描收尾的选择恢复。
 *
 * 这两条都只在「解析/扫描跑到一半，主题又被人换了」的时序里出错，正常单线程路径看不出来，
 * 所以测试直接驱动内部接缝：给 publishSvg 一个已经过期的代次，给 restoreSelection 一个
 * 「扫描开始后用户又点了主题」的选择计数。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class IconThemeManagerCacheSelectionTest {
    private val svgText =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#000000" d="M0 0h24v24H0z"/></svg>"""
    private val otherSvgText =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#ff0000" d="M0 0h12v24H0z"/></svg>"""

    private val savedNames = mutableListOf<String>()

    private fun generation(): Int =
        ReflectionHelpers.getField<AtomicInteger>(IconThemeManager, "svgPrewarmGeneration").get()

    private fun publish(key: String, content: String, generation: Int) {
        ReflectionHelpers.callInstanceMethod<Any?>(
            IconThemeManager, "publishSvg",
            ClassParameter.from(String::class.java, key),
            ClassParameter.from(String::class.java, content),
            ClassParameter.from(SVG::class.java, SVG.getFromString(content)),
            ClassParameter.from(Int::class.javaPrimitiveType!!, generation)
        )
    }

    private fun drawableCache(): LruCache<String, IconThemeManager.IconDrawableInfo> =
        ReflectionHelpers.getField(IconThemeManager, "drawableCache")

    private fun publishDrawable(key: String, generation: Int) {
        ReflectionHelpers.callInstanceMethod<Any?>(
            IconThemeManager, "publishDrawable",
            ClassParameter.from(String::class.java, key),
            ClassParameter.from(
                IconThemeManager.IconDrawableInfo::class.java,
                IconThemeManager.IconDrawableInfo(ColorDrawable(0xFF112233.toInt()), tintWithTheme = false)
            ),
            ClassParameter.from(Int::class.javaPrimitiveType!!, generation)
        )
    }

    private fun cached(key: String, content: String): SVG? =
        ReflectionHelpers.callInstanceMethod(
            IconThemeManager, "cachedSvg",
            ClassParameter.from(String::class.java, key),
            ClassParameter.from(String::class.java, content)
        )

    private fun installTheme(name: String): IconTheme {
        val theme = IconTheme(name = name, icons = mapOf("keys.letter.q" to svgText))
        IconThemeManager.saveTheme(theme)
        savedNames += name
        return theme
    }

    @After
    fun restoreSelectionAndCleanUp() {
        IconThemeManager.selectTheme(IconThemeManager.iconThemes.first())
        savedNames.forEach { IconThemeManager.deleteTheme(it) }
        savedNames.clear()
    }

    /** 旧代次的解析结果即使晚到，也不能写进新主题的缓存键。 */
    @Test
    fun staleGenerationPublishIsRejected() {
        val stale = generation()
        val key = "keys.letter.q:stale generation test"
        IconThemeManager.selectTheme(IconTheme(name = "stale generation test"))

        publish(key, svgText, stale)

        assertNull("旧代次的结果不应进入缓存", cached(key, svgText))
    }

    /** 光栅化结果同样按代次把关：换主题后才完成的旧图标不能留在新主题的缓存里。 */
    @Test
    fun staleDrawablePublishIsRejected() {
        val stale = generation()
        val key = "keys.letter.q:drawable stale test"
        IconThemeManager.selectTheme(IconTheme(name = "drawable stale test"))

        publishDrawable(key, stale)

        assertNull("旧代次的图标不应进入绘制缓存", drawableCache().get(key))
    }

    /** 代次没变时照常写入，避免把「防旧结果」写成「永不缓存」。 */
    @Test
    fun currentGenerationDrawablePublishIsStored() {
        val key = "keys.letter.q:drawable current test"

        publishDrawable(key, generation())

        assertNotNull(drawableCache().get(key))
    }

    /** 同名主题换了内容：旧条目命中失败，新内容写入后旧内容也读不到。 */
    @Test
    fun cacheEntryIsBoundToTheSvgContentItWasParsedFrom() {
        val generation = generation()
        val key = "keys.letter.q:content identity test"

        publish(key, svgText, generation)
        assertNotNull(cached(key, svgText))
        assertNull("内容不同时旧条目不能命中", cached(key, otherSvgText))

        publish(key, otherSvgText, generation)
        assertNotNull(cached(key, otherSvgText))
        assertNull("新内容写入后旧内容不再命中", cached(key, svgText))
    }

    /** 扫描期间用户新选了主题：收尾不能再用扫描前的旧名盖回去。 */
    @Test
    fun rescanRestoreKeepsTheSelectionMadeWhileScanning() {
        val first = installTheme("rescan selection first")
        val second = installTheme("rescan selection second")
        try {
            IconThemeManager.selectTheme(first)
            val versionAtStart = IconThemeManager.selectionVersionForRescan()

            // 扫描进行中，用户在列表里点了另一个主题。
            IconThemeManager.selectTheme(second)

            IconThemeManager.restoreSelection(first.name, versionAtStart)

            assertEquals(second.name, IconThemeManager.activeTheme.name)
        } finally {
            if (savedNames.remove(first.name)) IconThemeManager.deleteTheme(first.name)
            if (savedNames.remove(second.name)) IconThemeManager.deleteTheme(second.name)
        }
    }

    /** 扫描期间没有新选择时，仍按扫描前保存的名字恢复。 */
    @Test
    fun rescanRestoreAppliesTheSavedNameWhenNothingChanged() {
        val theme = installTheme("rescan selection unchanged")
        IconThemeManager.selectTheme(IconThemeManager.iconThemes.first())
        val versionAtStart = IconThemeManager.selectionVersionForRescan()

        IconThemeManager.restoreSelection(theme.name, versionAtStart)

        assertEquals(theme.name, IconThemeManager.activeTheme.name)
    }

    /** 「蓝狐狸」旧名只在原文件退役后迁移到「普蓝」；这里验证迁移这一半。 */
    @Test
    fun retiredLegacyNameRestoresTheRenamedTheme() {
        val pulan = installTheme("普蓝")
        val versionAtStart = IconThemeManager.selectionVersionForRescan()

        IconThemeManager.restoreSelection("蓝狐狸", versionAtStart)

        assertEquals("普蓝", IconThemeManager.activeTheme.name)
        IconThemeManager.deleteTheme(pulan.name)
        savedNames.remove(pulan.name)
    }
}
