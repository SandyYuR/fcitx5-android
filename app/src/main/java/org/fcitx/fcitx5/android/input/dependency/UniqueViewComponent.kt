/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.dependency

import android.view.View
import org.mechdancer.dependency.Dependent
import org.mechdancer.dependency.IUniqueComponent
import org.mechdancer.dependency.manager.ManagedHandler
import org.mechdancer.dependency.manager.managedHandler
import kotlin.reflect.KClass

abstract class UniqueViewComponent<T : UniqueViewComponent<T, V>, V : View>(
    final override val type: KClass<T>
) :
    IUniqueComponent<T>,
    Dependent,
    ManagedHandler by managedHandler() {

    abstract val view: V

    override fun equals(other: Any?): Boolean = this === other || type.java.isInstance(other)

    override fun hashCode(): Int = type.java.hashCode()

}

