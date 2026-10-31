/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.testing

import java.nio.file.Files
import java.nio.file.FileSystemException
import java.nio.file.Path
import org.junit.Assume

object FilesystemFixtures {
    /** Only a proven missing host privilege is unavailable; Linux CI must run these cases. */
    fun symbolicLink(link: Path, target: Path): Path {
        try { return Files.createSymbolicLink(link, target) }
        catch (error: FileSystemException) {
            val reason = error.reason.orEmpty()
            if (System.getProperty("os.name").startsWith("Windows") &&
                (reason.contains("privilege", ignoreCase = true) || reason.contains("所需的特权"))) {
                Assume.assumeNoException("Host lacks symbolic-link privilege; required on Linux CI", error)
            }
            throw error
        }
    }
}
