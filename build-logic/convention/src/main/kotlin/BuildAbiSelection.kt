/* SPDX-License-Identifier: LGPL-2.1-or-later */

/** Resolve the optional APK/AAB ABI selection once, before configuring AGP. */
internal fun selectBuildAbis(value: String?, supported: Set<String>): List<String> {
    require(supported.isNotEmpty())
    if (value == null) return supported.toList()
    val selected = value.split(',').map(String::trim)
    require(selected.isNotEmpty() && selected.none(String::isEmpty)) {
        "BUILD_ABI/buildABI must contain a non-empty comma-separated ABI list."
    }
    require(selected.all { it in supported }) {
        "BUILD_ABI/buildABI contains an unsupported ABI. Supported values: ${supported.joinToString()}."
    }
    return selected.distinct()
}

internal fun requestsBundle(tasks: List<String>, projectPath: String): Boolean = tasks.any { requested ->
    val name = requested.substringAfterLast(':')
    val path = requested.substringBeforeLast(':', "")
    name.startsWith("bundle") && (path.isEmpty() || path == projectPath || path == projectPath.removePrefix(":"))
}
