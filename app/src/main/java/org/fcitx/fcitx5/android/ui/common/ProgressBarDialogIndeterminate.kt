/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.ui.common

import android.animation.ValueAnimator
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.LifecycleCoroutineScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.utils.getGlobalSettings
import splitties.dimensions.dp
import splitties.views.dsl.core.add
import splitties.views.dsl.core.horizontalMargin
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.verticalMargin

@Suppress("FunctionName")
fun Context.ProgressBarDialogIndeterminate(@StringRes title: Int): MaterialAlertDialogBuilder {
    val builder = MaterialAlertDialogBuilder(this)
    // Custom content must inherit the dialog overlay as well as the settings palette.
    val dialogContext = builder.context
    return builder
        .setTitle(title)
        .setView(dialogContext.verticalLayout {
            val shouldAnimate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ValueAnimator.areAnimatorsEnabled()
            } else {
                getGlobalSettings<Float>(Settings.Global.ANIMATOR_DURATION_SCALE) > 0f
            }
            add(if (shouldAnimate) {
                LinearProgressIndicator(dialogContext).apply {
                    isIndeterminate = true
                }
            } else {
                MaterialTextView(dialogContext).apply {
                    setText(R.string.please_wait)
                    setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                }
            }, lParams {
                width = matchParent
                verticalMargin = dp(24)
                horizontalMargin = dp(24)
            })
        })
        .setCancelable(false)
}

fun LifecycleCoroutineScope.withLoadingDialog(
    context: Context,
    @StringRes title: Int = R.string.loading,
    threshold: Long = 200L,
    action: suspend () -> Unit
) {
    var loadingDialog: AlertDialog? = null
    // Show dialog on Main thread
    val loadingJob = launch(Dispatchers.Main) {
        delay(threshold)
        loadingDialog = context.ProgressBarDialogIndeterminate(title).show()
    }
    launch {
        try {
            action()
        } finally {
            loadingJob.cancelAndJoin()
            loadingDialog?.dismiss()
        }
    }
}

fun LifecycleCoroutineScope.withProgressLoadingDialog(
    context: Context,
    @StringRes title: Int = R.string.loading,
    threshold: Long = 200L,
    action: suspend (updateMessage: (String) -> Unit) -> Unit
) {
    var loadingDialog: AlertDialog? = null
    var message = ""
    val updateMessage: (String) -> Unit = { updated ->
        launch(Dispatchers.Main) {
            message = updated
            loadingDialog?.setMessage(updated)
        }
    }
    val loadingJob = launch(Dispatchers.Main) {
        delay(threshold)
        loadingDialog = context.ProgressBarDialogIndeterminate(title)
            .setMessage(message)
            .show()
    }
    launch {
        try {
            action(updateMessage)
        } finally {
            loadingJob.cancelAndJoin()
            loadingDialog?.dismiss()
        }
    }
}
