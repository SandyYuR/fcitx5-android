/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.data.theme

import android.graphics.drawable.ColorDrawable
import android.util.LruCache
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.fcitx.fcitx5.android.FcitxApplication
import org.fcitx.fcitx5.android.ui.main.settings.behavior.editor.EditorApplicationShadow
import org.fcitx.fcitx5.android.utils.FileUtil
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 图标主题缓存的同步边界与扫描收尾的选择恢复。
 *
 * 这些都只在「解析/扫描跑到一半，主题又被人换了」的时序里出错，正常单线程路径看不出来，
 * 所以测试直接驱动内部接缝：给 publishSvg 一个已经过期的代次；用 beginScan 的可控读取
 * 卡住扫描起点，再让选择与保存/删除发生在其间，最后用 applyScannedThemes 判断旧扫描结果
 * 该不该发布。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = FcitxApplication::class, shadows = [EditorApplicationShadow::class])
class IconThemeManagerCacheSelectionTest {
    private val svgText =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#000000" d="M0 0h24v24H0z"/></svg>"""
    private val otherSvgText =
        """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><path fill="#ff0000" d="M0 0h12v24H0z"/></svg>"""

    private val savedNames = mutableListOf<String>()
    private val manualNames = mutableSetOf<String>()
    private val manualFiles = mutableListOf<File>()
    private val testJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

    /**
     * 必须拿到管理器实际使用的目录：Robolectric 每个测试方法的 external-files 根不同，
     * 而 IconThemeManager.themeDir 是进程内只初始化一次的 lazy，按当前测试重新拼路径会指向
     * 另一个目录，fixture 与生产扫描就看不见彼此。
     */
    private fun themeDir(): File =
        ReflectionHelpers.callInstanceMethod<File?>(IconThemeManager, "getThemeDir")
            ?: error("icon theme directory unavailable")

    private fun themeFile(name: String): File = File(themeDir(), "$name.json").also { manualFiles += it }

    /** 合法的主题文件，但没有可选的 thumbnail_svg——回填分支的真实输入。 */
    private fun writeThemeWithoutThumbnail(name: String, iconSvg: String = svgText): File {
        manualNames += name
        return themeFile(name).also {
            FileUtil.writeAtomically(
                it,
                testJson.encodeToString(IconTheme(name = name, author = "test", icons = mapOf("keys.letter.q" to iconSvg)))
            )
        }
    }

    private fun readTheme(f: File): IconTheme = testJson.decodeFromString(f.readText())

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
        return IconThemeManager.iconThemes.first { it.name == name }
    }

    /** 磁盘表面：从目录重新解析，绕开注册表与活动对象。 */
    private fun themesOnDisk(): List<IconTheme> =
        IconThemeManager.scanThemeDirectory()?.themes ?: error("icon theme directory unavailable")

    private fun savedName(): String? =
        FcitxApplication.getInstance().directBootAwareContext
            .getSharedPreferences("icon_theme", 0)
            .getString("active_icon_theme", null)


    @After
    fun restoreSelectionAndCleanUp() {
        IconThemeManager.selectTheme(IconThemeManager.iconThemes.first())
        savedNames.forEach { IconThemeManager.deleteTheme(it) }
        manualNames.forEach { IconThemeManager.deleteTheme(it) }
        manualFiles.forEach { runCatching { it.delete() } }
        savedNames.clear()
        manualNames.clear()
        manualFiles.clear()
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

    /** 扫描期间没有新选择时，仍按扫描前保存的名字恢复。 */
    @Test
    fun rescanRestoreAppliesTheSavedNameWhenNothingChanged() {
        val theme = installTheme("rescan selection unchanged")
        IconThemeManager.selectTheme(IconThemeManager.iconThemes.first())
        val snapshot = IconThemeManager.beginScan { theme.name }

        IconThemeManager.applyScannedThemes(IconThemeManager.iconThemes.drop(1), snapshot)

        assertEquals(theme.name, IconThemeManager.activeTheme.name)
        assertEquals(theme.name, savedName())
    }

    /** 「蓝狐狸」旧名只在原文件退役后迁移到「普蓝」；这里验证迁移这一半。 */
    @Test
    fun retiredLegacyNameRestoresTheRenamedTheme() {
        val pulan = installTheme("普蓝")
        val snapshot = IconThemeManager.beginScan { "蓝狐狸" }

        IconThemeManager.applyScannedThemes(IconThemeManager.iconThemes.drop(1), snapshot)

        assertEquals("普蓝", IconThemeManager.activeTheme.name)
        assertEquals("普蓝", savedName())
        IconThemeManager.deleteTheme(pulan.name)
        savedNames.remove(pulan.name)
    }

    /**
     * G1：保存名与选择版本必须一次取走。扫描起点读保存名时被卡住，另一个线程立即选择；
     * 选择必须等到起点快照完成，收尾也不能再用旧名盖掉这次选择。
     */
    @Test
    fun scanStartReadsSavedNameAndSelectionVersionAtomically() {
        val first = installTheme("scan snapshot first")
        val second = installTheme("scan snapshot second")
        IconThemeManager.selectTheme(first)

        val readerEntered = CountDownLatch(1)
        val releaseReader = CountDownLatch(1)
        val selectAttempted = CountDownLatch(1)
        val selectFinished = CountDownLatch(1)
        var snapshot: IconThemeManager.ScanSnapshot? = null

        val scanner = Thread {
            snapshot = IconThemeManager.beginScan {
                readerEntered.countDown()
                releaseReader.await()
                first.name
            }
        }
        val selector = Thread {
            selectAttempted.countDown()
            IconThemeManager.selectTheme(second)
            selectFinished.countDown()
        }
        try {
            scanner.start()
            assertTrue(readerEntered.await(5, TimeUnit.SECONDS))
            selector.start()
            assertTrue(selectAttempted.await(5, TimeUnit.SECONDS))
            assertFalse(
                "扫描起点持有 selectionLock 时，用户选择不能插进保存名与版本之间",
                selectFinished.await(300, TimeUnit.MILLISECONDS)
            )
        } finally {
            // 断言失败时也必须放行读取并回收线程，否则 @After 的选择会永久等在这把锁上。
            releaseReader.countDown()
            scanner.join(5_000)
            selector.join(5_000)
        }

        val started = snapshot ?: error("scan start did not produce a snapshot")
        assertEquals(first.name, started.savedName)
        val scanned = IconThemeManager.iconThemes.drop(1)
        assertTrue(IconThemeManager.applyScannedThemes(scanned, started))
        assertEquals("扫描期间的选择必须保留", second.name, IconThemeManager.activeTheme.name)
        assertEquals(second.name, savedName())
    }

    /**
     * G2：扫描期间先选 B 再删 B，旧扫描结果不得把 B 从过期列表里恢复出来。
     * 四面对照：磁盘、注册表、活动主题、保存名。
     */
    @Test
    fun staleScanCannotResurrectADeletedTheme() {
        val theme = installTheme("scan deleted theme")
        IconThemeManager.selectTheme(theme)
        val snapshot = IconThemeManager.beginScan()
        val scannedBeforeDelete = IconThemeManager.iconThemes.drop(1)

        // 扫描读盘之后、发布之前：用户又选了一次 B，然后把它删掉。
        IconThemeManager.selectTheme(theme)
        IconThemeManager.deleteTheme(theme.name)

        assertFalse(
            "注册表已变，旧扫描结果必须丢弃",
            IconThemeManager.applyScannedThemes(scannedBeforeDelete, snapshot)
        )
        assertTrue(
            "磁盘上不能重新出现已删除的主题",
            themesOnDisk().none { it.name == theme.name }
        )
        assertTrue(
            "注册表里不能重新出现已删除的主题",
            IconThemeManager.iconThemes.none { it.name == theme.name }
        )
        assertEquals("Default", IconThemeManager.activeTheme.name)
        assertEquals("Default", savedName())
        savedNames.remove(theme.name)
    }

    /**
     * §4(1)：扫描开始后新建并选择 B。旧扫描写的是没有 B 的列表，若被发布会把这次成功
     * 保存的选择回落成默认。
     */
    @Test
    fun themeSavedAndSelectedWhileScanningSurvivesTheStaleScan() {
        val snapshot = IconThemeManager.beginScan()
        val staleScanned = IconThemeManager.iconThemes.drop(1)
        val name = "scan created theme"
        IconThemeManager.saveTheme(IconTheme(name = name, icons = mapOf("keys.letter.q" to svgText)))
        savedNames += name
        IconThemeManager.selectTheme(IconThemeManager.iconThemes.first { it.name == name })

        assertFalse(
            "扫描期间保存推进了注册表版本，旧结果必须丢弃",
            IconThemeManager.applyScannedThemes(staleScanned, snapshot)
        )
        assertTrue(
            "保存的文件必须仍在磁盘上",
            themesOnDisk().any { it.name == name }
        )
        assertTrue(
            "注册表必须保留扫描期间新建的主题",
            IconThemeManager.iconThemes.any { it.name == name }
        )
        assertEquals(name, IconThemeManager.activeTheme.name)
        assertEquals(name, savedName())
    }

    /**
     * §4(2)：扫描期间同名保存了新版 A。旧扫描发布旧 A 不能把磁盘、注册表和活动对象
     * 回滚到旧内容。
     */
    @Test
    fun sameNameResaveWhileScanningIsNotRolledBackByTheStaleScan() {
        val name = "scan same name theme"
        val original = installTheme(name)
        IconThemeManager.selectTheme(original)
        val snapshot = IconThemeManager.beginScan()
        val staleScanned = IconThemeManager.iconThemes.drop(1)
        val updated = original.copy(icons = mapOf("keys.letter.q" to otherSvgText))

        IconThemeManager.saveTheme(updated)

        assertFalse(
            "同名保存推进了注册表版本，旧扫描结果必须丢弃",
            IconThemeManager.applyScannedThemes(staleScanned, snapshot)
        )
        assertEquals(
            "磁盘必须是新内容",
            otherSvgText,
            themesOnDisk().first { it.name == name }.icons["keys.letter.q"]
        )
        assertEquals(
            "注册表不能回滚到旧内容",
            otherSvgText,
            IconThemeManager.iconThemes.first { it.name == name }.icons["keys.letter.q"]
        )
        assertEquals(
            "活动对象不能回滚到旧内容",
            otherSvgText,
            IconThemeManager.activeTheme.icons["keys.letter.q"]
        )
        assertEquals(name, savedName())
    }

    /**
     * H2（读侧）：扫描只读盘，缩略图回填只能在发布边界内发生。若扫描自己写盘，这里在
     * 发布之前就能看到文件字节变化，用户此后的同名保存仍会被这轮旧回填覆盖。
     */
    @Test
    fun scanIsReadOnlyAndPublishBackfillsTheThumbnail() {
        val name = "scan thumbnail read only"
        val file = writeThemeWithoutThumbnail(name)
        val before = file.readBytes()

        val scanned = IconThemeManager.scanThemeDirectory() ?: error("icon theme directory unavailable")

        assertArrayEquals("扫描阶段必须只读，不能写回缩略图", before, file.readBytes())
        assertNotNull(
            "内存里的扫描结果仍可带缩略图供列表展示",
            scanned.themes.first { it.name == name }.thumbnailSvg
        )
        assertTrue(IconThemeManager.applyScannedThemes(scanned, IconThemeManager.beginScan()))
        assertNotNull("发布后磁盘应完成缩略图回填", readTheme(file).thumbnailSvg)
    }

    /**
     * H2：注册表版本没变、但文件在扫描后被外部改写时，回填也不能用扫描时的旧内容覆盖它。
     * 这是内容摘要核对存在的意义：版本号只覆盖管理器自己的保存/删除路径。
     */
    @Test
    fun staleScanCannotBackfillOverAnExternalEdit() {
        val name = "scan thumbnail external edit"
        val file = writeThemeWithoutThumbnail(name)

        val scanned = IconThemeManager.scanThemeDirectory() ?: error("icon theme directory unavailable")
        FileUtil.writeAtomically(
            file,
            testJson.encodeToString(
                IconTheme(name = name, author = "external", icons = mapOf("keys.letter.q" to otherSvgText))
            )
        )

        assertTrue(
            "注册表未变时发布本身仍应生效",
            IconThemeManager.applyScannedThemes(scanned, IconThemeManager.beginScan())
        )
        assertEquals(
            "外部改写不能被扫描时的旧回填覆盖",
            otherSvgText,
            readTheme(file).icons["keys.letter.q"]
        )
    }

    /** H2：旧扫描读到的待回填内容，不能在用户同名保存新版后落盘覆盖。 */
    @Test
    fun staleScanCannotBackfillOverASameNameSave() {
        val name = "scan thumbnail stale same"
        val original = IconTheme(name = name, icons = mapOf("keys.letter.q" to svgText))
        IconThemeManager.saveTheme(original)
        savedNames += name
        val file = themeFile(name)
        FileUtil.writeAtomically(file, testJson.encodeToString(original))
        val before = file.readBytes()

        val scanned = IconThemeManager.scanThemeDirectory() ?: error("icon theme directory unavailable")
        assertArrayEquals(before, file.readBytes())
        val snapshot = IconThemeManager.beginScan()
        IconThemeManager.saveTheme(original.copy(icons = mapOf("keys.letter.q" to otherSvgText)))

        assertFalse(IconThemeManager.applyScannedThemes(scanned, snapshot))
        assertEquals(
            "旧扫描的缩略图回填不能覆盖同名保存的新内容",
            otherSvgText,
            readTheme(file).icons["keys.letter.q"]
        )
    }

    /** H2：发布前主题已被删除时，旧扫描不能借缩略图回填把文件重新创建出来。 */
    @Test
    fun staleScanCannotRecreateADeletedThemeDuringBackfill() {
        val name = "scan thumbnail deleted"
        val original = IconTheme(name = name, icons = mapOf("keys.letter.q" to svgText))
        IconThemeManager.saveTheme(original)
        savedNames += name
        val file = themeFile(name)
        FileUtil.writeAtomically(file, testJson.encodeToString(original))

        val scanned = IconThemeManager.scanThemeDirectory() ?: error("icon theme directory unavailable")
        val snapshot = IconThemeManager.beginScan()
        IconThemeManager.deleteTheme(name)

        assertFalse(IconThemeManager.applyScannedThemes(scanned, snapshot))
        assertFalse("已删除的主题文件不能被旧回填复活", file.exists())
    }

    /**
     * H1：过期扫描被整批丢弃后必须基于新快照重扫，否则扫描期间才落盘的未注册主题
     * （例如安装器刚写入的内置资源）会一直缺席到进程重启。
     */
    @Test
    fun staleScanIsRetriedUntilUnregisteredDiskThemeIsLoaded() = runBlocking {
        val registered = installTheme("scan retry registered")
        IconThemeManager.selectTheme(registered)
        val unregisteredName = "scan retry unregistered"
        IconThemeManager.saveTheme(
            IconTheme(name = unregisteredName, icons = mapOf("keys.letter.q" to otherSvgText))
        )
        val unregisteredFile = themeFile(unregisteredName)
        val unregisteredJson = unregisteredFile.readText()
        IconThemeManager.deleteTheme(unregisteredName)
        FileUtil.writeAtomically(unregisteredFile, unregisteredJson)
        manualNames += unregisteredName
        assertTrue(
            "fixture 必须模拟「盘上有、注册表没有」",
            IconThemeManager.iconThemes.none { it.name == unregisteredName }
        )

        val firstScan = CountDownLatch(1)
        val releaseFirstScan = CountDownLatch(1)
        val worker = launch(Dispatchers.IO) {
            IconThemeManager.reconcileScans { attempt ->
                if (attempt == 0) {
                    firstScan.countDown()
                    releaseFirstScan.await()
                }
            }
        }
        try {
            assertTrue("第一次扫描应停在发布前", firstScan.await(5, TimeUnit.SECONDS))
            IconThemeManager.saveTheme(registered.copy(icons = mapOf("keys.letter.q" to otherSvgText)))
            releaseFirstScan.countDown()
            withTimeout(10_000) { worker.join() }

            assertEquals(
                "用户同名保存的新内容必须保留在磁盘",
                otherSvgText,
                readTheme(themeFile(registered.name)).icons["keys.letter.q"]
            )
            assertEquals(
                "注册表不能回滚到旧内容",
                otherSvgText,
                IconThemeManager.iconThemes.first { it.name == registered.name }.icons["keys.letter.q"]
            )
            assertEquals(
                "活动对象不能回滚到旧内容",
                otherSvgText,
                IconThemeManager.activeTheme.icons["keys.letter.q"]
            )
            assertEquals(registered.name, savedName())
            val loaded = IconThemeManager.iconThemes.firstOrNull { it.name == unregisteredName }
            assertNotNull("被丢弃扫描里的盘上新主题必须由重扫补上", loaded)
            IconThemeManager.selectTheme(loaded!!)
            assertEquals("重扫补上的主题必须能正常选择", unregisteredName, IconThemeManager.activeTheme.name)
            assertEquals(unregisteredName, savedName())
        } finally {
            releaseFirstScan.countDown()
            worker.cancel()
            IconThemeManager.deleteTheme(unregisteredName)
            unregisteredFile.delete()
        }
    }
}
