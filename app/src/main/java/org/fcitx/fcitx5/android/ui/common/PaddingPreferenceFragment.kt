/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.CallSuper
import org.fcitx.fcitx5.android.ui.main.modified.MyPreferenceFragment
import org.fcitx.fcitx5.android.utils.applyNavBarInsetsBottomPadding

abstract class PaddingPreferenceFragment : MyPreferenceFragment() {

    private var expressiveListStyle: ExpressivePreferenceListStyle? = null

    @CallSuper
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ) = super.onCreateView(inflater, container, savedInstanceState).apply {
        listView.applyNavBarInsetsBottomPadding()
    }

    @CallSuper
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        @Suppress("DEPRECATION")
        run {
            setDivider(null)
            setDividerHeight(0)
        }
        expressiveListStyle = ExpressivePreferenceListStyle(listView).also { it.attach() }
    }

    override fun onDestroyView() {
        expressiveListStyle?.detach()
        expressiveListStyle = null
        super.onDestroyView()
    }
}
