/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.input.voice.js

import android.content.Context
import android.widget.LinearLayout
import org.fcitx.fcitx5.android.ui.common.WrappingActionRow

/** Uses the same whole-button wrapping as settings forms. */
internal class JsAppActionRow(context: Context) : WrappingActionRow(context)

/** A root header can stay above scrolling content without changing any plugin events. */
internal class JsAppHeaderRow(context: Context) : LinearLayout(context)
