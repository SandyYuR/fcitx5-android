/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import android.app.Application
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Build
import android.os.Bundle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [27, 28, 32, 33], application = Application::class)
class RimeEnginePluginsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun discoversProviderWithItsCurrentSigningCertificateAcrossAndroidVersions() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.packageManager
        val packageName = "fixture.rime.engine"
        val libraryDirectory = temporary.newFolder("lib")
        val engine = File(libraryDirectory, "librime.so").apply { writeBytes(engineElf()) }
        val current = Signature(byteArrayOf(1, 2, 3, 4))
        val old = Signature(byteArrayOf(5, 6, 7, 8))
        val app = ApplicationInfo().apply {
            this.packageName = packageName
            nativeLibraryDir = libraryDirectory.absolutePath
            enabled = true
            flags = ApplicationInfo.FLAG_INSTALLED
            metaData = Bundle().apply {
                putInt("org.rime.plugin.API_VERSION", 1)
                putInt("org.rime.plugin.RIME_API_ABI", 1)
                putString("org.rime.plugin.PLUGIN_ID", "fixture-engine")
                putString("org.rime.plugin.SONAME", engine.name)
            }
        }
        val activity = ActivityInfo().apply {
            this.packageName = packageName
            name = "$packageName.Provider"
            applicationInfo = app
            enabled = true
            exported = true
        }
        shadowOf(manager).installPackage(PackageInfo().apply {
            this.packageName = packageName
            applicationInfo = app
            activities = arrayOf(activity)
            @Suppress("DEPRECATION")
            signatures = arrayOf(if (Build.VERSION.SDK_INT >= 28) old else current)
            if (Build.VERSION.SDK_INT >= 28) {
                signingInfo = SigningInfo().also {
                    shadowOf(it).setSignatures(arrayOf(current))
                    shadowOf(it).setPastSigningCertificates(arrayOf(old, current))
                }
            }
        })
        shadowOf(manager).addResolveInfoForIntent(
            Intent(RimeEnginePlugins.DISCOVERY_ACTION).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setPackage(packageName)
            },
            ResolveInfo().apply { activityInfo = activity }
        )

        val candidates = RimeEnginePlugins.discover(context, packageName)
        assertEquals("Compatible provider must remain discoverable", 1, candidates.size)
        val provider = candidates.single()
        assertEquals(packageName, provider.packageName)
        assertEquals("fixture-engine", provider.providerId)
        assertEquals(digest(current), provider.certificateSha256)
        assertEquals(RimeEngineElf.sha256(engine), provider.staticInfo.sha256)
        assertEquals(provider, RimeEnginePlugins.requireCurrent(context, provider))
        assertTrue(provider.certificateSha256 != digest(old))
    }

    private fun digest(signature: Signature) = MessageDigest.getInstance("SHA-256")
        .digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }

    // A self-contained, non-executed ELF lets discovery exercise the real parser
    // and PackageManager together without depending on a locally built SDK.
    private fun engineElf(): ByteArray {
        val bytes = ByteArray(0x2000)
        val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        data.put(0, 0x7f); data.put(1, 'E'.code.toByte()); data.put(2, 'L'.code.toByte()); data.put(3, 'F'.code.toByte())
        data.put(4, 2); data.put(5, 1); data.put(6, 1)
        val machine = when (RimeEngineElf.processAbi()) {
            "arm64-v8a" -> 183
            "x86_64" -> 62
            else -> error("This JVM fixture requires a 64-bit host")
        }
        data.putShort(16, 3); data.putShort(18, machine.toShort()); data.putInt(20, 1)
        data.putLong(32, 64); data.putLong(40, 0x1000)
        data.putShort(52, 64); data.putShort(54, 56); data.putShort(56, 2)
        data.putShort(58, 64); data.putShort(60, 4)
        data.putInt(64, 1); data.putInt(68, 5)
        data.putLong(96, bytes.size.toLong()); data.putLong(104, bytes.size.toLong()); data.putLong(112, 0x4000)
        val dynamic = 120
        data.putInt(dynamic, 2); data.putInt(dynamic + 4, 6)
        data.putLong(dynamic + 8, 0x600); data.putLong(dynamic + 16, 0x600)
        data.putLong(dynamic + 32, 64); data.putLong(dynamic + 40, 64); data.putLong(dynamic + 48, 8)
        val strings = "\u0000rime_get_api\u0000librime.so\u0000".toByteArray()
        strings.copyInto(bytes, 0x400)
        data.putInt(0x518, 1); data.put(0x51c, 0x12); data.putShort(0x51e, 1)
        data.putLong(0x600, 14); data.putLong(0x608, 14)
        data.putLong(0x610, 5); data.putLong(0x618, 0x400)
        data.putLong(0x620, 10); data.putLong(0x628, strings.size.toLong())
        fun section(index: Int, type: Int, offset: Long, size: Long, link: Int, entrySize: Long) {
            val start = 0x1000 + index * 64
            data.putInt(start + 4, type); data.putLong(start + 24, offset); data.putLong(start + 32, size)
            data.putInt(start + 40, link); data.putLong(start + 56, entrySize)
        }
        section(1, 3, 0x400, strings.size.toLong(), 0, 1)
        section(2, 11, 0x500, 48, 1, 24)
        section(3, 6, 0x600, 64, 1, 16)
        return bytes
    }
}
