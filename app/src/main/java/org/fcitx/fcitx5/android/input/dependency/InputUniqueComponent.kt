/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.dependency

import org.mechdancer.dependency.UniqueComponent
import kotlin.reflect.KClass

/** Explicit dependency identity avoids walking Kotlin generic supertypes on keyboard creation. */
abstract class InputUniqueComponent<T : InputUniqueComponent<T>>(
    final override val type: KClass<T>
) : UniqueComponent<T>() {
    final override fun equals(other: Any?): Boolean = this === other || type.java.isInstance(other)
    final override fun hashCode(): Int = type.java.hashCode()
}
