/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.common

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import org.fcitx.fcitx5.android.R
import splitties.dimensions.dp
import splitties.resources.drawable
import splitties.resources.resolveThemeAttribute
import org.fcitx.fcitx5.android.utils.settingsColorStateList
import org.fcitx.fcitx5.android.utils.settingsColor
import splitties.resources.styledDimenPxSize
import splitties.resources.styledDrawable
import splitties.views.backgroundColor
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.after
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerVertically
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.core.Ui
import splitties.views.dsl.core.add
import com.google.android.material.checkbox.MaterialCheckBox
import splitties.views.dsl.core.imageButton
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.wrapContent
import splitties.views.imageDrawable
import splitties.views.setPaddingDp
import splitties.views.textAppearance

class DynamicListEntryUi(override val ctx: Context) : Ui {

    val handleImage = imageView {
        imageTintList = ctx.settingsColorStateList(androidx.appcompat.R.attr.colorPrimary)
        imageDrawable = drawable(R.drawable.ic_baseline_drag_handle_24)
        setPaddingDp(3, 0, 3, 0)
    }

    val multiselectCheckBox = MaterialCheckBox(ctx).apply { id = View.generateViewId() }

    val checkBox = MaterialCheckBox(ctx).apply { id = View.generateViewId() }

    val nameText = textView {
        setPaddingDp(0, 16, 0, 16)
        textAppearance = ctx.resolveThemeAttribute(android.R.attr.textAppearanceListItem)
    }

    val subText = textView {
        setPaddingDp(0, 0, 0, 12)
        visibility = View.GONE
        textAppearance = ctx.resolveThemeAttribute(android.R.attr.textAppearanceSmall)
        setTextColor(settingsColor(android.R.attr.textColorSecondary))
    }

    val editButton = imageButton {
        background = styledDrawable(android.R.attr.selectableItemBackground)
        imageTintList = ctx.settingsColorStateList(com.google.android.material.R.attr.colorOnSurfaceVariant)
        imageDrawable = drawable(R.drawable.ic_baseline_edit_24)
    }

    val settingsButton = imageButton {
        background = styledDrawable(android.R.attr.selectableItemBackground)
        // Keep the tint on the button: consumers may replace its icon while binding a row.
        imageTintList = ctx.settingsColorStateList(com.google.android.material.R.attr.colorOnSurfaceVariant)
        imageDrawable = drawable(R.drawable.ic_baseline_settings_24)
    }

    override val root: View = constraintLayout {
        layoutParams = ViewGroup.LayoutParams(matchParent, wrapContent)
        backgroundColor = settingsColor(com.google.android.material.R.attr.colorSurfaceContainer)
        minHeight = styledDimenPxSize(android.R.attr.listPreferredItemHeightSmall)

        val paddingStart = styledDimenPxSize(android.R.attr.listPreferredItemPaddingStart)

        add(handleImage, lParams {
            width = dp(30)
            height = matchConstraints
            centerVertically()
            startOfParent(paddingStart)
        })

        add(multiselectCheckBox, lParams {
            width = dp(30)
            height = matchConstraints
            centerVertically()
            after(handleImage, paddingStart)
        })

        add(checkBox, lParams {
            width = dp(30)
            height = matchConstraints
            centerVertically()
            after(multiselectCheckBox, paddingStart)
        })

        add(nameText, lParams {
            width = matchConstraints
            height = wrapContent
            topOfParent()
            above(subText)
            after(checkBox, paddingStart)
            before(editButton)
            verticalChainStyle = ConstraintLayout.LayoutParams.CHAIN_PACKED
        })

        add(subText, lParams {
            width = matchConstraints
            height = wrapContent
            below(nameText)
            bottomOfParent()
            after(checkBox, paddingStart)
            before(editButton)
        })

        add(editButton, lParams {
            width = dp(53)
            height = matchConstraints
            centerVertically()
            before(settingsButton)
        })

        add(settingsButton, lParams {
            width = dp(53)
            height = matchConstraints
            centerVertically()
            endOfParent()
        })
    }
}
