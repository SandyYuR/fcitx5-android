/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.core.data

sealed interface FileAction {
    val path: String

    /**
     * To create or update a file, we need its source.
     */
    interface Sourced {
        val src: FileSource
    }

    data class CreateSymlink(override val path: String, val src: String) : FileAction

    data class CreateDir(override val path: String) : FileAction

    data class CreateFile(override val path: String, override val src: FileSource) :
        FileAction,
        Sourced

    data class UpdateFile(override val path: String, override val src: FileSource) :
        FileAction,
        Sourced

    data class DeleteFile(override val path: String) : FileAction

    data class DeleteDir(override val path: String) : FileAction
}
