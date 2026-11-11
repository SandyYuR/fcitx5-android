/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.core.engine

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import timber.log.Timber
import java.io.File
import java.security.MessageDigest

internal object RimeEnginePlugins {
    const val DISCOVERY_ACTION = "org.rime.plugin.MANIFEST"
    private const val API_VERSION = "org.rime.plugin.API_VERSION"
    private const val RIME_API_ABI = "org.rime.plugin.RIME_API_ABI"
    private const val PLUGIN_ID = "org.rime.plugin.PLUGIN_ID"
    private const val SONAME = "org.rime.plugin.SONAME"
    private const val LIBRIME_VERSION = "org.rime.plugin.LIBRIME_VERSION"
    private const val CHANNEL = "org.rime.plugin.CHANNEL"
    private val libraryName = Regex("[A-Za-z0-9._+-]{1,128}\\.so")

    fun discover(context: Context): List<RimePluginEngineCandidate> {
        val manager = context.packageManager
        val intent = Intent(DISCOVERY_ACTION).addCategory(Intent.CATEGORY_DEFAULT)
        val resolved = if (Build.VERSION.SDK_INT >= 33) {
            manager.queryIntentActivities(intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.queryIntentActivities(intent, PackageManager.GET_META_DATA)
        }
        return resolved.mapNotNull { info ->
            runCatching {
                val application = info.activityInfo?.applicationInfo
                    ?: throw IllegalArgumentException("缺少插件应用信息")
                require(application.packageName != context.packageName) { "不能把主应用识别为引擎插件" }
                val metadata = application.metaData ?: Bundle.EMPTY
                val apiVersion = metadata.intValue(API_VERSION)
                val rimeApiAbi = metadata.intValue(RIME_API_ABI)
                require(apiVersion == 1) { "发现协议版本不受支持：$apiVersion" }
                require(rimeApiAbi == 1) { "Rime API ABI 不受支持：$rimeApiAbi" }
                val soname = metadata.stringValue(SONAME)
                require(soname.matches(libraryName)) { "插件 SONAME 无效" }
                val root = File(application.nativeLibraryDir).canonicalFile
                val library = File(root, soname).canonicalFile
                require(library.parentFile == root && library.isFile) { "插件没有解压 $soname" }
                val staticInfo = RimeEngineElf.inspect(library, "${application.packageName}/$soname")
                RimePluginEngineCandidate(
                    packageName = application.packageName,
                    displayName = manager.getApplicationLabel(application).toString()
                        .ifBlank { application.packageName },
                    providerId = metadata.stringValue(PLUGIN_ID, application.packageName),
                    apiVersion = apiVersion,
                    rimeApiAbi = rimeApiAbi,
                    soname = soname,
                    declaredLibrimeVersion = metadata.stringValueOrNull(LIBRIME_VERSION),
                    channel = metadata.stringValueOrNull(CHANNEL),
                    certificateSha256 = certificateDigest(manager, application.packageName),
                    libraryPath = library.absolutePath,
                    staticInfo = staticInfo
                )
            }.onFailure {
                Timber.w(it, "Ignoring incompatible Rime engine plugin ${info.activityInfo?.packageName}")
            }.getOrNull()
        }.distinctBy(RimePluginEngineCandidate::packageName).sortedBy { it.displayName.lowercase() }
    }

    private fun certificateDigest(manager: PackageManager, packageName: String): String {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val info = manager.getPackageInfo(packageName,
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()))
            info.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures.orEmpty()
        }
        require(signatures.isNotEmpty()) { "插件没有签名证书" }
        return signatures.map { signature ->
            MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }.sorted().joinToString(":")
    }

    private fun Bundle.intValue(name: String): Int = when (val value = get(name)) {
        is Int -> value
        is String -> value.toIntOrNull()
        else -> null
    } ?: -1

    private fun Bundle.stringValue(name: String, fallback: String = ""): String =
        stringValueOrNull(name)?.takeIf(String::isNotBlank) ?: fallback

    private fun Bundle.stringValueOrNull(name: String): String? = when (val value = get(name)) {
        is String -> value
        null -> null
        else -> value.toString()
    }
}
