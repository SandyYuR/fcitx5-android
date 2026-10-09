/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings.theme

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.LinearLayout.LayoutParams
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.ColorUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeFilesManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePreset
import org.fcitx.fcitx5.android.ui.common.withLoadingDialog
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropContract
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropOption
import org.fcitx.fcitx5.android.ui.main.CropImageActivity.CropResult
import org.fcitx.fcitx5.android.utils.DarkenColorFilter
import org.fcitx.fcitx5.android.utils.DeviceUtil
import org.fcitx.fcitx5.android.utils.item
import org.fcitx.fcitx5.android.utils.parcelable
import org.fcitx.fcitx5.android.utils.toast
import androidx.core.view.isVisible
import splitties.dimensions.dp
import splitties.resources.color
import splitties.resources.resolveThemeAttribute
import splitties.resources.styledColor
import splitties.resources.styledDrawable
import splitties.views.backgroundColor
import splitties.views.bottomPadding
import splitties.views.dsl.appcompat.switch
import splitties.views.dsl.constraintlayout.above
import splitties.views.dsl.constraintlayout.before
import splitties.views.dsl.constraintlayout.below
import splitties.views.dsl.constraintlayout.bottomOfParent
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.endOfParent
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.matchConstraints
import splitties.views.dsl.constraintlayout.packed
import splitties.views.dsl.constraintlayout.startOfParent
import splitties.views.dsl.constraintlayout.topOfParent
import splitties.views.dsl.constraintlayout.topToTopOf
import splitties.views.dsl.core.add
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.seekBar
import splitties.views.dsl.core.textView
import splitties.views.dsl.core.verticalLayout
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.dsl.core.wrapInScrollView
import splitties.views.gravityVerticalCenter
import splitties.views.horizontalPadding
import splitties.views.textAppearance
import splitties.views.topPadding
import java.io.File

class CustomThemeActivity : AppCompatActivity() {
    sealed interface BackgroundResult : Parcelable {
        @Parcelize
        data class Updated(
            val oldName: String,
            val theme: Theme.Custom
        ) : BackgroundResult

        @Parcelize
        data class Created(val theme: Theme.Custom) : BackgroundResult

        @Parcelize
        data class Deleted(val name: String) : BackgroundResult
    }

    class Contract : ActivityResultContract<Theme.Custom?, BackgroundResult?>() {
        override fun createIntent(context: Context, input: Theme.Custom?): Intent =
            Intent(context, CustomThemeActivity::class.java).apply {
                putExtra(ORIGIN_THEME, input)
            }

        override fun parseResult(resultCode: Int, intent: Intent?): BackgroundResult? =
            intent?.parcelable(RESULT)
    }

    private val toolbar by lazy {
        view(::Toolbar) {
            backgroundColor = styledColor(android.R.attr.colorPrimary)
            elevation = dp(4f)
        }
    }

    private lateinit var previewUi: KeyboardPreviewUi
    private var previewScale = 1f
    private val colorPreviewDrawables = mutableMapOf<String, GradientDrawable>()
    private val colorEditItems = listOf(
        ThemeColorEditItem(R.string.theme_color_background, "Background", R.string.theme_color_group_background, { t: Theme.Custom -> t.backgroundColor }, { t: Theme.Custom, c: Int -> t.copy(backgroundColor = c) }),
        ThemeColorEditItem(R.string.theme_color_bar, "Bar", R.string.theme_color_group_background, { t: Theme.Custom -> t.barColor }, { t: Theme.Custom, c: Int -> t.copy(barColor = c) }),
        ThemeColorEditItem(R.string.theme_color_keyboard, "Keyboard", R.string.theme_color_group_background, { t: Theme.Custom -> t.keyboardColor }, { t: Theme.Custom, c: Int -> t.copy(keyboardColor = c) }),
        ThemeColorEditItem(R.string.theme_color_key_background, "Key Background", R.string.theme_color_group_keys, { t: Theme.Custom -> t.keyBackgroundColor }, { t: Theme.Custom, c: Int -> t.copy(keyBackgroundColor = c) }),
        ThemeColorEditItem(R.string.theme_color_key_text, "Key Text", R.string.theme_color_group_keys, { t: Theme.Custom -> t.keyTextColor }, { t: Theme.Custom, c: Int -> t.copy(keyTextColor = c) }),
        ThemeColorEditItem(R.string.theme_color_alt_key_background, "Alt Key Background", R.string.theme_color_group_keys, { t: Theme.Custom -> t.altKeyBackgroundColor }, { t: Theme.Custom, c: Int -> t.copy(altKeyBackgroundColor = c) }),
        ThemeColorEditItem(R.string.theme_color_alt_key_text, "Alt Key Text", R.string.theme_color_group_keys, { t: Theme.Custom -> t.altKeyTextColor }, { t: Theme.Custom, c: Int -> t.copy(altKeyTextColor = c) }),
        ThemeColorEditItem(R.string.theme_color_accent_key_background, "Accent Key Background", R.string.theme_color_group_keys, { t: Theme.Custom -> t.accentKeyBackgroundColor }, { t: Theme.Custom, c: Int -> t.copy(accentKeyBackgroundColor = c) }),
        ThemeColorEditItem(R.string.theme_color_accent_key_text, "Accent Key Text", R.string.theme_color_group_keys, { t: Theme.Custom -> t.accentKeyTextColor }, { t: Theme.Custom, c: Int -> t.copy(accentKeyTextColor = c) }),
        ThemeColorEditItem(R.string.theme_color_key_press_highlight, "Key Press Highlight", R.string.theme_color_group_keys, { t: Theme.Custom -> t.keyPressHighlightColor }, { t: Theme.Custom, c: Int -> t.copy(keyPressHighlightColor = c) }),
        ThemeColorEditItem(R.string.theme_color_key_shadow, "Key Shadow", R.string.theme_color_group_keys, { t: Theme.Custom -> t.keyShadowColor }, { t: Theme.Custom, c: Int -> t.copy(keyShadowColor = c) }),
        ThemeColorEditItem(R.string.theme_color_water_ripple, ThemeColorEditItem.ID_WATER_RIPPLE, R.string.theme_color_group_keys, { t: Theme.Custom -> t.waterRippleColor ?: computeWaterRippleColor(t) }, { t: Theme.Custom, c: Int -> t.copy(waterRippleColor = c) }),
        ThemeColorEditItem(R.string.theme_color_space_bar, "Space Bar", R.string.theme_color_group_keys, { t: Theme.Custom -> t.spaceBarColor }, { t: Theme.Custom, c: Int -> t.copy(spaceBarColor = c) }),
        ThemeColorEditItem(R.string.theme_color_candidate_text, "Candidate Text", R.string.theme_color_group_candidates, { t: Theme.Custom -> t.candidateTextColor }, { t: Theme.Custom, c: Int -> t.copy(candidateTextColor = c) }),
        ThemeColorEditItem(R.string.theme_color_candidate_label, "Candidate Label", R.string.theme_color_group_candidates, { t: Theme.Custom -> t.candidateLabelColor }, { t: Theme.Custom, c: Int -> t.copy(candidateLabelColor = c) }),
        ThemeColorEditItem(R.string.theme_color_candidate_comment, "Candidate Comment", R.string.theme_color_group_candidates, { t: Theme.Custom -> t.candidateCommentColor }, { t: Theme.Custom, c: Int -> t.copy(candidateCommentColor = c) }),
        ThemeColorEditItem(R.string.theme_color_popup_background, "Popup Background", R.string.theme_color_group_popup, { t: Theme.Custom -> t.popupBackgroundColor }, { t: Theme.Custom, c: Int -> t.copy(popupBackgroundColor = c) }),
        ThemeColorEditItem(R.string.theme_color_popup_text, "Popup Text", R.string.theme_color_group_popup, { t: Theme.Custom -> t.popupTextColor }, { t: Theme.Custom, c: Int -> t.copy(popupTextColor = c) }),
        ThemeColorEditItem(R.string.theme_color_clipboard_entry, "Clipboard Entry", R.string.theme_color_group_popup, { t: Theme.Custom -> t.clipboardEntryColor }, { t: Theme.Custom, c: Int -> t.copy(clipboardEntryColor = c) }),
        ThemeColorEditItem(R.string.theme_color_divider, "Divider", R.string.theme_color_group_misc, { t: Theme.Custom -> t.dividerColor }, { t: Theme.Custom, c: Int -> t.copy(dividerColor = c) }),
        ThemeColorEditItem(R.string.theme_color_generic_active_background, "Generic Active Background", R.string.theme_color_group_misc, { t: Theme.Custom -> t.genericActiveBackgroundColor }, { t: Theme.Custom, c: Int -> t.copy(genericActiveBackgroundColor = c) }),
        ThemeColorEditItem(R.string.theme_color_generic_active_foreground, "Generic Active Foreground", R.string.theme_color_group_misc, { t: Theme.Custom -> t.genericActiveForegroundColor }, { t: Theme.Custom, c: Int -> t.copy(genericActiveForegroundColor = c) })
    )

    /**
     * 不变式：[colorEditItems] 中**同组项必须相邻**。
     *
     * 渲染时按 `groupRes` 变化插入小标题（见 [scrollView]），因此只要同组项相邻，
     * 每个分组头就恰好出现一次。挪动某项时连它的 `groupRes` 一起改即可，
     * 不需要同步维护第二张分组表。
     */
    private val colorEditorLauncher =
        registerForActivityResult(ThemeColorEditorActivity.Contract()) { result ->
            result ?: return@registerForActivityResult
            val item = colorEditItems.firstOrNull { it.id == result.fieldName } ?: return@registerForActivityResult
            if (result.color == null) {
                if (item.id == ThemeColorEditItem.ID_WATER_RIPPLE) {
                    theme = theme.copy(waterRippleColor = null)
                    colorPreviewDrawables[item.id]?.setColor(computeWaterRippleColor(theme))
                    applyThemePreview(theme)
                    refreshSaveButtonState()
                }
                return@registerForActivityResult
            }
            val originalTheme = theme
            val originalColor = item.getter(originalTheme)
            if (result.color == originalColor) return@registerForActivityResult
            theme = item.setter(originalTheme, result.color)
            colorPreviewDrawables[item.id]?.setColor(result.color)
            applyThemePreview(theme)
            refreshSaveButtonState()
        }

    private fun computeWaterRippleColor(theme: Theme.Custom): Int {
        val shadow = theme.keyShadowColor
        val background = ColorUtils.setAlphaComponent(theme.keyboardColor, 255)
        val contrast = ColorUtils.calculateContrast(shadow, background)
        return if (contrast < 1.35) {
            ColorUtils.blendARGB(shadow, theme.accentKeyBackgroundColor, 0.72f)
        } else {
            ColorUtils.blendARGB(shadow, theme.accentKeyBackgroundColor, 0.28f)
        }
    }

    /**
     * 颜色分组的小标题。
     *
     * 刻意做成**不可点击**（不加 ripple、不设点击监听）：它只是视觉分隔，
     * 若沿用 [createTextView] 的 `ripple = true`，用户会以为点它有反应。
     */
    private fun createColorGroupHeader(@StringRes string: Int) = textView {
        setText(string)
        gravity = gravityVerticalCenter
        textSize = 13f
        setTextColor(styledColor(android.R.attr.textColorSecondary))
        horizontalPadding = dp(16)
        topPadding = dp(12)
        background = styledDrawable(android.R.attr.selectableItemBackground)
    }

    /** 折叠指示图标：展开时朝上、收起时朝下。 */
    private fun expandIcon(expanded: Boolean) =
        if (expanded) R.drawable.ic_baseline_expand_less_24 else R.drawable.ic_baseline_expand_more_24

    private fun createTextView(@StringRes string: Int? = null, ripple: Boolean = false) = textView {
        if (string != null) {
            setText(string)
        }
        gravity = gravityVerticalCenter
        textAppearance = resolveThemeAttribute(android.R.attr.textAppearanceListItem)
        horizontalPadding = dp(16)
        if (ripple) {
            background = styledDrawable(android.R.attr.selectableItemBackground)
        }
    }

    private fun promptRenameTheme() {
        val input = EditText(this).apply {
            setText(theme.name)
            setSelection(text.length)
            setSingleLine(true)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.theme_name)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newName = input.text?.toString()?.trim().orEmpty()
                if (newName.isEmpty()) {
                    toast(R.string.theme_name_empty)
                    return@setOnClickListener
                }
                if (newName == theme.name) {
                    dialog.dismiss()
                    return@setOnClickListener
                }
                val nameClashes = ThemeManager.getAllThemes().any { it.name == newName }
                if (nameClashes) {
                    toast(R.string.exception_theme_name_clash)
                    return@setOnClickListener
                }
                theme = theme.copy(name = newName)
                supportActionBar?.title = toThemeLabel(newName)
                applyThemePreview(theme)
                refreshSaveButtonState()
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun toThemeLabel(name: String): String {
        return if (name.length == 36 && name.count { it == '-' } == 4) {
            name.take(8)
        } else {
            name
        }
    }

    private fun formatArgbHex(color: Int): String = String.format(
        "#%02X%02X%02X%02X",
        Color.alpha(color),
        Color.red(color),
        Color.green(color),
        Color.blue(color)
    )

    private fun parseArgbHex(input: String): Int? {
        val normalized = input.trim().removePrefix("#")
        val hex = when (normalized.length) {
            6 -> "FF$normalized"
            8 -> normalized
            else -> return null
        }
        return try {
            java.lang.Long.parseLong(hex, 16).toInt()
        } catch (_: Exception) {
            null
        }
    }

    private fun GradientDrawable.previewColorOrNull(): Int? = color?.defaultColor

    private fun currentBackgroundDrawable(themeForBackground: Theme.Custom): BitmapDrawable? {
        return if (themeForBackground.backgroundImage != null)
            runCatching { backgroundStates.filteredDrawable }.getOrNull()
        else null
    }

    private lateinit var candidateTextPreview: TextView
    private lateinit var candidateLabelPreview: TextView
    private lateinit var candidateCommentPreview: TextView
    private lateinit var popupPreview: TextView
    private lateinit var dividerPreview: View
    private lateinit var clipboardPreview: TextView
    private lateinit var genericActivePreview: TextView
    private lateinit var scorePreview: TextView

    private fun updateSupplementColorPreview(themeForPreview: Theme.Custom) {
        candidateTextPreview.setTextColor(themeForPreview.candidateTextColor)
        candidateLabelPreview.setTextColor(themeForPreview.candidateLabelColor)
        candidateCommentPreview.setTextColor(themeForPreview.candidateCommentColor)
        popupPreview.setTextColor(themeForPreview.popupTextColor)
        popupPreview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4f)
            setColor(themeForPreview.popupBackgroundColor)
        }
        dividerPreview.setBackgroundColor(themeForPreview.dividerColor)
        clipboardPreview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4f)
            setColor(themeForPreview.clipboardEntryColor)
        }
        genericActivePreview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(4f)
            setColor(themeForPreview.genericActiveBackgroundColor)
        }
        genericActivePreview.setTextColor(themeForPreview.genericActiveForegroundColor)
    }

    private fun applyThemePreview(
        themeForPreview: Theme.Custom,
        background: BitmapDrawable? = currentBackgroundDrawable(themeForPreview),
        persistRandomTheme: Boolean = true
    ) {
        if (persistRandomTheme && ThemeManager.isRandomTheme(themeForPreview) &&
            ThemeManager.currentRandomTheme != themeForPreview
        ) {
            ThemeManager.updateRandomTheme(themeForPreview)
        }
        previewUi.setTheme(themeForPreview, background)
        updateSupplementColorPreview(themeForPreview)
        updateScorePreview(themeForPreview)
    }

    private fun updateScorePreview(themeForPreview: Theme.Custom = theme) {
        if (!::scorePreview.isInitialized) return
        if (!ThemeManager.isRandomTheme(themeForPreview)) {
            scorePreview.visibility = View.GONE
            return
        }
        val score = if (ThemeManager.currentRandomTheme == themeForPreview) {
            ThemeManager.randomThemeScore ?: ThemeManager.scoreTheme(themeForPreview)
        } else {
            ThemeManager.scoreTheme(themeForPreview)
        }
        scorePreview.visibility = View.VISIBLE
        scorePreview.text = getString(
            R.string.random_theme_score,
            score.total,
            score.contrast,
            score.hue,
            score.sat,
            score.light
        )
        scorePreview.setTextColor(themeForPreview.altKeyTextColor)
    }

    private fun refreshColorPreviews() {
        colorEditItems.forEach { item ->
            colorPreviewDrawables[item.id]?.setColor(item.getter(theme))
        }
    }

    private fun updatePreviewScale() {
        if (!::previewUi.isInitialized) return
        val contentHeight = ui.height - toolbar.height
        val currentHeight = previewUi.intrinsicHeight
        if (contentHeight <= 0 || currentHeight <= 0 || previewScale <= 0f) return
        val baseHeight = (currentHeight / previewScale).toInt().coerceAtLeast(1)
        val maxPreviewHeight = (contentHeight * 0.36f).toInt().coerceAtLeast(dp(140))
        val newScale = (maxPreviewHeight.toFloat() / baseHeight).coerceIn(0.35f, 1f)
        if (kotlin.math.abs(newScale - previewScale) < 0.01f) return
        previewScale = newScale
        previewUi.setSizeScale(previewScale)
    }

    private var layoutChangeJob: android.view.Choreographer.FrameCallback? = null
    private fun registerLayoutChangeObserver() {
        val observer = object : android.view.Choreographer.FrameCallback {
            private var lastWidth = 0
            private var lastHeight = 0
            
            override fun doFrame(frameTimeNanos: Long) {
                val w = ui.width
                val h = ui.height
                if (w != lastWidth || h != lastHeight) {
                    lastWidth = w
                    lastHeight = h
                    updatePreviewScale()
                }
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
        layoutChangeJob = observer
        android.view.Choreographer.getInstance().postFrameCallback(observer)
    }
    
    private fun unregisterLayoutChangeObserver() {
        layoutChangeJob?.let {
            android.view.Choreographer.getInstance().removeFrameCallback(it)
        }
        layoutChangeJob = null
    }

    private fun findInlineEditorIndex(parent: ViewGroup): Int {
        for (i in 0 until parent.childCount) {
            if (parent.getChildAt(i).tag == INLINE_COLOR_EDITOR_TAG) return i
        }
        return -1
    }

    private fun createCheckerboardDrawable(tileSize: Int): BitmapDrawable {
        val size = tileSize * 2
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        val paint = android.graphics.Paint()
        paint.color = 0xFFCCCCCC.toInt()
        canvas.drawRect(0f, 0f, tileSize.toFloat(), tileSize.toFloat(), paint)
        canvas.drawRect(tileSize.toFloat(), tileSize.toFloat(), size.toFloat(), size.toFloat(), paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawRect(tileSize.toFloat(), 0f, size.toFloat(), tileSize.toFloat(), paint)
        canvas.drawRect(0f, tileSize.toFloat(), tileSize.toFloat(), size.toFloat(), paint)
        return BitmapDrawable(resources, bitmap).apply {
            setTileModeXY(android.graphics.Shader.TileMode.REPEAT, android.graphics.Shader.TileMode.REPEAT)
        }
    }

    private fun createInlineColorEditor(
        initialColor: Int,
        allowClear: Boolean = false,
        onPreview: (Int) -> Unit,
        onConfirm: (Int) -> Unit,
        onClear: (() -> Unit)? = null,
        onCancel: () -> Unit
    ): View {
        var editingColor = initialColor
        val alphaState = ThemeColorEditorAlphaState()
        var currentHue = 0f
        var currentSaturation = 1f
        var currentValue = 1f
        
        // Initialize HSV values
        val hsv = FloatArray(3)
        Color.colorToHSV(initialColor, hsv)
        currentHue = hsv[0]
        currentSaturation = hsv[1]
        currentValue = hsv[2]
        
        // Local function to update color from HSV
        fun updateColor() {
            val color = Color.HSVToColor(
                alphaState.alphaForHsvEdit(editingColor),
                floatArrayOf(currentHue, currentSaturation, currentValue)
            )
            editingColor = color
        }
        
        // Forward references for cross-component access
        var svPickerRef: SaturationValuePickerView? = null
        var argbInputRef: EditText? = null
        var alphaPreviewRef: AlphaPreviewSlider? = null
        var hueSliderRef: HueSliderView? = null
        var internalTextUpdate = false

        fun syncColorWidgets(syncInputText: Boolean) {
            alphaPreviewRef?.setColor(editingColor)
            alphaPreviewRef?.setAlpha(Color.alpha(editingColor))
            if (syncInputText) {
                val text = formatArgbHex(editingColor)
                if (argbInputRef?.text?.toString() != text) {
                    internalTextUpdate = true
                    argbInputRef?.setText(text)
                    argbInputRef?.setSelection(text.length)
                    internalTextUpdate = false
                }
            }
        }
        
        lateinit var editorView: LinearLayout
        val removeEditorView = { (editorView.parent as? ViewGroup)?.removeView(editorView) }

        // Create the main editor view
        editorView = LinearLayout(this).apply {
            tag = INLINE_COLOR_EDITOR_TAG
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            backgroundColor = styledColor(android.R.attr.colorBackground)

            // ========== 1. Saturation/Value Picker + Hue Slider ==========
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER

                // Saturation/Value picker (square)
                val svPicker = SaturationValuePickerView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(180), dp(180))
                    setSaturation(currentSaturation)
                    setValue(currentValue)
                    setHue(currentHue)
                    onColorChanged = { s, v ->
                        currentSaturation = s
                        currentValue = v
                        updateColor()
                        syncColorWidgets(syncInputText = true)
                        onPreview(editingColor)
                    }
                }
                svPickerRef = svPicker
                addView(svPicker)

                val hueSlider = HueSliderView(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(180)).apply {
                        marginStart = dp(12)
                    }
                    setHue(currentHue)
                    onHueChanged = { hue ->
                        currentHue = hue
                        svPickerRef.setHue(currentHue)
                        updateColor()
                        syncColorWidgets(syncInputText = true)
                        onPreview(editingColor)
                    }
                }
                hueSliderRef = hueSlider
                addView(hueSlider)

                val alphaPreview = AlphaPreviewSlider(context).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(28), dp(180)).apply {
                        marginStart = dp(12)
                    }
                    orientation = AlphaPreviewSlider.Orientation.Vertical
                    setAlpha(Color.alpha(editingColor))
                    setColor(editingColor)
                    onAlphaChanged = { alpha ->
                        alphaState.recordAlphaEdit()
                        editingColor = Color.argb(
                            alpha,
                            Color.red(editingColor),
                            Color.green(editingColor),
                            Color.blue(editingColor)
                        )
                        syncColorWidgets(syncInputText = true)
                        onPreview(editingColor)
                    }
                }
                alphaPreviewRef = alphaPreview
                addView(alphaPreview)
            })

            // ========== 2. ARGB Input (single) ==========
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8), 0, dp(8))

                addView(TextView(context).apply {
                    text = "ARGB:"
                    textSize = 14f
                    layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
                    setTextColor(styledColor(android.R.attr.textColorPrimary))
                })
                
                val argbInput = EditText(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                    isSingleLine = true
                    setText(formatArgbHex(editingColor))
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    setTextColor(styledColor(android.R.attr.textColorPrimary))
                    setHintTextColor(styledColor(android.R.attr.textColorHint))
                    backgroundTintList = ColorStateList.valueOf(styledColor(android.R.attr.colorControlActivated))
                    addTextChangedListener(object : android.text.TextWatcher {
                        override fun afterTextChanged(s: android.text.Editable?) {
                            if (internalTextUpdate) return
                            parseArgbHex(s.toString())?.let { color ->
                                alphaState.recordArgbEdit()
                                editingColor = color
                                val hsv = FloatArray(3)
                                Color.colorToHSV(editingColor, hsv)
                                currentHue = hsv[0]
                                currentSaturation = hsv[1]
                                currentValue = hsv[2]
                                svPickerRef?.setHue(currentHue)
                                hueSliderRef?.setHue(currentHue)
                                svPickerRef?.setSaturation(currentSaturation)
                                svPickerRef?.setValue(currentValue)
                                syncColorWidgets(syncInputText = false)
                                onPreview(editingColor)
                            }
                        }
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    })
                }
                argbInputRef = argbInput
                addView(argbInput)
            })

            // ========== 3. OK/Cancel Buttons ==========
            val buttonRow = LinearLayout(this@CustomThemeActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(8), 0, 0)
                gravity = Gravity.CENTER

                addView(Button(this@CustomThemeActivity).apply {
                    text = getString(R.string.custom_theme_confirm)
                    layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        onConfirm(editingColor)
                        removeEditorView()
                    }
                })

                addView(View(this@CustomThemeActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(16), 1)
                })

                if (allowClear) {
                    addView(Button(this@CustomThemeActivity).apply {
                        text = getString(R.string.clear)
                        layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                        setOnClickListener {
                            onClear?.invoke()
                            removeEditorView()
                        }
                    })

                    addView(View(this@CustomThemeActivity).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(16), 1)
                    })
                }

                addView(Button(this@CustomThemeActivity).apply {
                    text = getString(R.string.custom_theme_cancel)
                    layoutParams = LinearLayout.LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        onCancel()
                        removeEditorView()
                    }
                })
            }
            addView(buttonRow)
        }
        
        return editorView
    }

    private val variantLabel by lazy {
        createTextView(R.string.dark_keys, ripple = true)
    }
    private val variantSwitch by lazy {
        switch {
            // Use dark keys by default
            isChecked = false
        }
    }

    private val brightnessLabel by lazy {
        createTextView(R.string.brightness)
    }
    private val brightnessValue by lazy {
        createTextView()
    }
    private val brightnessSeekBar by lazy {
        seekBar {
            max = 100
        }
    }

    private val blurRadiusLabel by lazy {
        createTextView(R.string.background_blur_radius)
    }
    private val blurRadiusValue by lazy {
        createTextView()
    }
    private val blurRadiusSeekBar by lazy {
        seekBar {
            max = 25 // Max blur radius
        }
    }

    private val cropLabel by lazy {
        createTextView(R.string.recrop_image, ripple = true)
    }
    private val chooseImageLabel by lazy {
        createTextView(R.string.add_background_image, ripple = true)
    }

    private val supplementPreview by lazy {
        verticalLayout {
            val padH = dp(30)
            val padV = dp(4)
            setPadding(padH, padV, padH, padV)

            val oneLineRow = android.widget.LinearLayout(this@CustomThemeActivity).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL

                fun addGap(widthDp: Int = 10) {
                    addView(
                        View(this@CustomThemeActivity),
                        android.widget.LinearLayout.LayoutParams(dp(widthDp), 1)
                    )
                }

                candidateTextPreview = TextView(this@CustomThemeActivity).apply { text = getString(R.string.custom_theme_preview_candidate) }
                candidateLabelPreview = TextView(this@CustomThemeActivity).apply { text = getString(R.string.custom_theme_preview_label) }
                candidateCommentPreview = TextView(this@CustomThemeActivity).apply { text = getString(R.string.custom_theme_preview_comment) }
                popupPreview = TextView(this@CustomThemeActivity).apply {
                    text = getString(R.string.custom_theme_preview_popup)
                    val hp = dp(10)
                    val vp = dp(3)
                    setPadding(hp, vp, hp, vp)
                }
                dividerPreview = View(this@CustomThemeActivity)
                clipboardPreview = TextView(this@CustomThemeActivity).apply {
                    text = getString(R.string.custom_theme_preview_clipboard)
                    val hp = dp(8)
                    val vp = dp(3)
                    setPadding(hp, vp, hp, vp)
                }
                genericActivePreview = TextView(this@CustomThemeActivity).apply {
                    text = getString(R.string.custom_theme_preview_active)
                    val hp = dp(8)
                    val vp = dp(3)
                    setPadding(hp, vp, hp, vp)
                }

                scorePreview = TextView(this@CustomThemeActivity).apply {
                    textSize = 12f
                    gravity = Gravity.CENTER
                    setPadding(0, dp(4), 0, dp(4))
                }

                addView(candidateTextPreview)
                addGap()
                addView(candidateLabelPreview)
                addGap()
                addView(candidateCommentPreview)
                addGap()
                addView(popupPreview)
                addGap(8)
                addView(dividerPreview, android.widget.LinearLayout.LayoutParams(dp(1), dp(18)))
                addGap(8)
                addView(clipboardPreview)
                addGap()
                addView(genericActivePreview)
                addGap()
                addView(scorePreview)
            }

            val scroll = android.widget.HorizontalScrollView(this@CustomThemeActivity).apply {
                isHorizontalScrollBarEnabled = false
                addView(
                    oneLineRow,
                    android.widget.FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                )
            }
            addView(scroll, android.widget.LinearLayout.LayoutParams(matchParent, wrapContent))
        }
    }

    private val scrollView by lazy {
        val lineHeight = dp(48)
        val itemMargin = dp(30)
        // colorsContainer will hold editable color rows for Theme fields
        val colorsContainer = verticalLayout {
            val lineHeight = dp(48)
            var inlinePreviewDirty = false

            // 22 项平铺是一长串，用户很难定位「我想改候选的字色」该往下翻到哪里。
            // 按语义分组 + 可折叠：默认只展开前 EXPANDED_GROUP_COUNT 组，
            // 其余收起（计划「该隐藏的隐藏」：细节收进折叠）。
            // 同组项在 colorEditItems 里相邻，故每组只建一个容器。
            var lastGroupRes = 0
            var groupContainer: LinearLayout? = null
            var groupIndex = 0
            for (item in colorEditItems) {
                if (item.groupRes != lastGroupRes) {
                    lastGroupRes = item.groupRes
                    val group = verticalLayout { }
                    val expanded = groupIndex < EXPANDED_GROUP_COUNT
                    group.isVisible = expanded
                    val header = createColorGroupHeader(item.groupRes).apply {
                        compoundDrawablePadding = dp(8)
                        setCompoundDrawablesWithIntrinsicBounds(0, 0, expandIcon(expanded), 0)
                        setOnClickListener {
                            group.isVisible = !group.isVisible
                            setCompoundDrawablesWithIntrinsicBounds(
                                0, 0, expandIcon(group.isVisible), 0
                            )
                        }
                    }
                    add(header, android.widget.LinearLayout.LayoutParams(matchParent, wrapContent))
                    add(group, android.widget.LinearLayout.LayoutParams(matchParent, wrapContent))
                    groupContainer = group
                    groupIndex++
                }
                val label = createTextView(null, ripple = true).apply {
                    text = getString(item.titleRes)
                }
                val preview = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dp(4f)
                    setSize(dp(28), dp(28))
                    setColor(item.getter(theme))
                }
                colorPreviewDrawables[item.id] = preview
                label.setCompoundDrawablesWithIntrinsicBounds(null, null, preview, null)
                label.compoundDrawablePadding = dp(12)
                label.setOnClickListener {
                    val displayedColor = preview.previewColorOrNull() ?: item.getter(theme)
                    if (DeviceUtil.isHMOS) {
                        colorEditorLauncher.launch(
                            ThemeColorEditorActivity.EditorInput(
                                fieldName = item.id,
                                titleRes = R.string.edit_color,
                                initialColor = displayedColor,
                                allowClear = item.id == ThemeColorEditItem.ID_WATER_RIPPLE
                            )
                        )
                        return@setOnClickListener
                    }
                    val parent = label.parent as ViewGroup
                    var insertIndex = parent.indexOfChild(label) + 1
                    val existingEditorIndex = findInlineEditorIndex(parent)
                    if (existingEditorIndex >= 0) {
                        if (existingEditorIndex == insertIndex) {
                            parent.removeViewAt(existingEditorIndex)
                            if (inlinePreviewDirty) {
                                applyThemePreview(theme)
                                inlinePreviewDirty = false
                            }
                            return@setOnClickListener
                        }
                        parent.removeViewAt(existingEditorIndex)
                        if (inlinePreviewDirty) {
                            applyThemePreview(theme)
                            inlinePreviewDirty = false
                        }
                        insertIndex = parent.indexOfChild(label) + 1
                    }

                    val originalTheme = theme
                    val originalColor = displayedColor
                    val editor = createInlineColorEditor(
                        initialColor = originalColor,
                        allowClear = item.id == ThemeColorEditItem.ID_WATER_RIPPLE,
                        onPreview = { c ->
                            val changed = c != originalColor
                            if (changed) {
                                inlinePreviewDirty = true
                                val tmpTheme = item.setter(originalTheme, c)
                                applyThemePreview(
                                    tmpTheme,
                                    currentBackgroundDrawable(originalTheme),
                                    persistRandomTheme = false
                                )
                            } else if (inlinePreviewDirty) {
                                inlinePreviewDirty = false
                                applyThemePreview(theme)
                            }
                        },
                        onConfirm = { c ->
                            if (c != originalColor) {
                                theme = item.setter(originalTheme, c)
                                preview.setColor(c)
                                applyThemePreview(theme)
                                refreshSaveButtonState()
                            }
                            inlinePreviewDirty = false
                        },
                        onClear = {
                            theme = theme.copy(waterRippleColor = null)
                            preview.setColor(computeWaterRippleColor(theme))
                            applyThemePreview(theme)
                            refreshSaveButtonState()
                            inlinePreviewDirty = false
                        },
                        onCancel = {
                            if (inlinePreviewDirty) {
                                applyThemePreview(theme)
                                inlinePreviewDirty = false
                            }
                        }
                    )
                    parent.addView(editor, insertIndex)
                }
                // 加进当前分组容器而不是外层：否则折叠只会藏住组头、条目仍全部可见。
                groupContainer?.add(
                    label,
                    android.widget.LinearLayout.LayoutParams(matchParent, lineHeight)
                )
            }
        }

        constraintLayout {
            bottomPadding = dp(24)
            add(chooseImageLabel, lParams(matchConstraints, lineHeight) {
                topOfParent()
                centerHorizontally(itemMargin)
                above(cropLabel)
            })
            add(cropLabel, lParams(matchConstraints, lineHeight) {
                below(chooseImageLabel)
                centerHorizontally(itemMargin)
                above(variantLabel)
            })
            add(variantLabel, lParams(matchConstraints, lineHeight) {
                below(cropLabel)
                startOfParent(itemMargin)
                before(variantSwitch)
                above(brightnessLabel)
            })
            add(variantSwitch, lParams(wrapContent, lineHeight) {
                topToTopOf(variantLabel)
                endOfParent(itemMargin)
            })
            add(brightnessLabel, lParams(matchConstraints, lineHeight) {
                below(variantLabel)
                startOfParent(itemMargin)
                before(brightnessValue)
                above(brightnessSeekBar)
            })
            add(brightnessValue, lParams(wrapContent, lineHeight) {
                topToTopOf(brightnessLabel)
                endOfParent(itemMargin)
            })
            add(brightnessSeekBar, lParams(matchConstraints, wrapContent) {
                below(brightnessLabel)
                centerHorizontally(itemMargin)
                above(blurRadiusLabel)
            })
            // Blur radius controls
            add(blurRadiusLabel, lParams(matchConstraints, lineHeight) {
                below(brightnessSeekBar)
                startOfParent(itemMargin)
                before(blurRadiusValue)
                above(blurRadiusSeekBar)
            })
            add(blurRadiusValue, lParams(wrapContent, lineHeight) {
                topToTopOf(blurRadiusLabel)
                endOfParent(itemMargin)
            })
            add(blurRadiusSeekBar, lParams(matchConstraints, wrapContent) {
                below(blurRadiusLabel)
                centerHorizontally(itemMargin)
                above(colorsContainer)
            })
            // add the colors container below brightness controls
            add(colorsContainer, lParams(matchConstraints, wrapContent) {
                below(blurRadiusSeekBar)
                centerHorizontally(itemMargin)
                bottomOfParent()
            })
        }.wrapInScrollView {
            isFillViewport = true
        }
    }

    private val ui by lazy {
        constraintLayout {
            add(toolbar, lParams(matchParent, wrapContent) {
                topOfParent()
                centerHorizontally()
            })
            // Wrapper to center the preview UI horizontally
            val previewWrapper = FrameLayout(this@CustomThemeActivity)
            add(previewWrapper, lParams(matchConstraints, wrapContent) {
                below(toolbar)
                centerHorizontally()
            })
            previewWrapper.addView(previewUi.root, FrameLayout.LayoutParams(wrapContent, wrapContent).apply {
                gravity = android.view.Gravity.CENTER
            })
            add(supplementPreview, lParams(matchConstraints, wrapContent) {
                below(previewWrapper)
                centerHorizontally()
            })
            add(scrollView, lParams {
                below(supplementPreview)
                centerHorizontally()
                bottomOfParent()
                topMargin = dp(8)
            })
        }
    }

    private var newCreated = true

    private lateinit var theme: Theme.Custom
    private lateinit var initialThemeSnapshot: Theme.Custom
    private var originalThemeName: String? = null
    private var backgroundControlsBound = false
    private var suppressVariantSwitchCallback = false
    private var saveMenuItem: MenuItem? = null

    private val isRandomMode: Boolean
        get() = ThemeManager.isRandomTheme(theme)

    private fun effectiveThemeForDirtyCheck(): Theme.Custom {
        val bg = theme.backgroundImage ?: return theme
        return theme.copy(
            backgroundImage = bg.copy(
                brightness = brightnessSeekBar.progress,
                blurRadius = blurRadiusSeekBar.progress.toFloat(),
                cropRect = backgroundStates.cropRect,
                cropRotation = backgroundStates.cropRotation
            )
        )
    }

    private fun hasUnsavedChanges(): Boolean {
        if (newCreated) return true
        if (!::initialThemeSnapshot.isInitialized) return false
        if (backgroundStates.srcImageDirty || backgroundStates.pendingSrcUri != null) return true
        return effectiveThemeForDirtyCheck() != initialThemeSnapshot
    }

    private fun refreshSaveButtonState() {
        val enabled = hasUnsavedChanges()
        saveMenuItem?.isEnabled = enabled
        saveMenuItem?.icon?.alpha = if (enabled) 255 else 90
    }

    private class BackgroundStates {
        lateinit var launcher: ActivityResultLauncher<CropOption>
        var srcImageExtension: String? = null
        var srcImageDirty: Boolean = false
        var pendingSrcUri: Uri? = null
        var pendingSrcFile: File? = null
        var cropRect: Rect? = null
        var cropRotation: Int = 0
        lateinit var croppedBitmap: Bitmap
        lateinit var filteredDrawable: BitmapDrawable
        lateinit var srcImageFile: File
        lateinit var croppedImageFile: File

        fun hasStorageFiles(): Boolean =
            this::srcImageFile.isInitialized && this::croppedImageFile.isInitialized
    }

    private val backgroundStates by lazy { BackgroundStates() }

    private inline fun whenHasBackground(
        block: BackgroundStates.(Theme.Custom.CustomBackground) -> Unit,
    ) {
        if (theme.backgroundImage != null)
            block(backgroundStates, theme.backgroundImage!!)
    }

    private fun BackgroundStates.setKeyVariant(
        background: Theme.Custom.CustomBackground,
        darkKeys: Boolean
    ) {
        val template = if (darkKeys) ThemePreset.TransparentLight else ThemePreset.TransparentDark
        theme = template.deriveCustomBackground(
            theme.name,
            background.croppedFilePath,
            background.srcFilePath,
            brightnessSeekBar.progress,
            background.cropRect,
            background.cropRotation
        )
        applyThemePreview(theme, filteredDrawable)
        refreshSaveButtonState()
    }

    /**
     * Resolve background file path, handling both absolute and relative paths.
     */
    private fun resolveBackgroundFile(filePath: String): File {
        val file = File(filePath)
        if (file.exists()) {
            return file
        }
        // Try relative to theme directory
        val appFilesDir = getExternalFilesDir(null)
        if (appFilesDir != null) {
            val themeDir = File(appFilesDir, "theme")
            val relativeFile = File(themeDir, filePath)
            if (relativeFile.exists()) {
                return relativeFile
            }
        }
        return file
    }

    private fun updateBlurRadiusLabel(progress: Int) {
        blurRadiusValue.text = if (progress == 0) getString(R.string.no_blur) else "$progress"
    }

    private fun updateBackgroundEditorVisibility() {
        val hasBackground = theme.backgroundImage != null
        chooseImageLabel.visibility = if (isRandomMode) View.GONE else View.VISIBLE
        chooseImageLabel.setText(
            if (hasBackground) R.string.change_background_image else R.string.add_background_image
        )
        val visibility = if (!isRandomMode && hasBackground) View.VISIBLE else View.GONE
        cropLabel.visibility = visibility
        variantLabel.visibility = visibility
        variantSwitch.visibility = visibility
        brightnessLabel.visibility = visibility
        blurRadiusLabel.visibility = visibility
        brightnessSeekBar.visibility = visibility
        blurRadiusSeekBar.visibility = visibility
    }

    private fun ensureBackgroundStorageFiles() {
        if (backgroundStates.hasStorageFiles()) return
        val (cropped, src) = ThemeFilesManager.newBackgroundImagesForTheme(theme.name)
        backgroundStates.croppedImageFile = cropped
        backgroundStates.srcImageFile = src
    }

    private fun bindBackgroundControls() {
        if (backgroundControlsBound) return
        backgroundControlsBound = true
        backgroundStates.launcher = registerForActivityResult(CropContract()) { result ->
            when (result) {
                CropResult.Fail -> {
                    if (newCreated) cancel()
                }
                is CropResult.Success -> {
                    ensureBackgroundStorageFiles()
                    val sourceUri = result.srcUri
                    val contentType = contentResolver.getType(sourceUri)
                    val sourceExtension = MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(contentType)
                        ?: MimeTypeMap.getFileExtensionFromUrl(sourceUri.toString())
                            .takeIf { it.isNotBlank() }
                    backgroundStates.srcImageExtension = sourceExtension
                    backgroundStates.srcImageDirty = true
                    backgroundStates.pendingSrcUri = sourceUri
                    backgroundStates.pendingSrcFile = result.srcFile
                    backgroundStates.cropRect = result.rect
                    backgroundStates.cropRotation = result.rotation
                    backgroundStates.croppedBitmap = result.bitmap
                    backgroundStates.filteredDrawable = BitmapDrawable(resources, backgroundStates.croppedBitmap)

                    if (theme.backgroundImage == null) {
                        theme = theme.copy(
                            backgroundImage = Theme.Custom.CustomBackground(
                                croppedFilePath = backgroundStates.croppedImageFile.absolutePath,
                                srcFilePath = backgroundStates.srcImageFile.absolutePath,
                                brightness = 70,
                                cropRect = backgroundStates.cropRect,
                                cropRotation = backgroundStates.cropRotation,
                                blurRadius = 10f
                            )
                        )
                        brightnessSeekBar.progress = 70
                        blurRadiusSeekBar.progress = 10
                        suppressVariantSwitchCallback = true
                        variantSwitch.isChecked = !theme.isDark
                        suppressVariantSwitchCallback = false
                    }
                    updateBackgroundEditorVisibility()
                    updateBlurRadiusLabel(blurRadiusSeekBar.progress)
                    backgroundStates.updateState()
                    refreshSaveButtonState()
                }
            }
        }
        chooseImageLabel.setOnClickListener {
            if (theme.backgroundImage == null) {
                backgroundStates.launchCrop(
                    previewUi.intrinsicWidth.coerceAtLeast(1),
                    previewUi.intrinsicHeight.coerceAtLeast(1),
                    pickNewSource = true
                )
            } else {
                AlertDialog.Builder(this@CustomThemeActivity)
                    .setTitle(R.string.change_background_image)
                    .setItems(
                        arrayOf(
                            getString(R.string.change_background_image),
                            getString(R.string.clear_background_image)
                        )
                    ) { _, which ->
                        when (which) {
                            0 -> backgroundStates.launchCrop(
                                previewUi.intrinsicWidth.coerceAtLeast(1),
                                previewUi.intrinsicHeight.coerceAtLeast(1),
                                pickNewSource = true
                            )
                            1 -> clearBackgroundImage()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
        cropLabel.setOnClickListener {
            if (theme.backgroundImage == null) return@setOnClickListener
            backgroundStates.launchCrop(
                previewUi.intrinsicWidth.coerceAtLeast(1),
                previewUi.intrinsicHeight.coerceAtLeast(1),
                pickNewSource = false
            )
        }
        variantLabel.setOnClickListener {
            variantSwitch.isChecked = !variantSwitch.isChecked
        }
        variantSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (suppressVariantSwitchCallback) return@setOnCheckedChangeListener
            whenHasBackground { background ->
                setKeyVariant(background, darkKeys = isChecked)
            }
        }
        brightnessSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser && theme.backgroundImage != null) {
                    backgroundStates.updateState()
                    refreshSaveButtonState()
                }
            }
        })
        blurRadiusSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                updateBlurRadiusLabel(progress)
                if (fromUser && theme.backgroundImage != null) {
                    backgroundStates.updateState()
                    refreshSaveButtonState()
                }
            }
        })
    }

    private fun clearBackgroundImage() {
        if (theme.backgroundImage == null) return
        theme = theme.copy(backgroundImage = null)
        backgroundStates.srcImageExtension = null
        backgroundStates.srcImageDirty = false
        backgroundStates.pendingSrcUri = null
        backgroundStates.pendingSrcFile?.delete()
        backgroundStates.pendingSrcFile = null
        applyThemePreview(theme)
        updateBackgroundEditorVisibility()
        refreshSaveButtonState()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // recover from bundle
        val originTheme = intent?.parcelable<Theme.Custom>(ORIGIN_THEME)?.also { t ->
            theme = t
            originalThemeName = t.name
            whenHasBackground {
                // Resolve relative path to absolute path
                val croppedFile = resolveBackgroundFile(it.croppedFilePath)
                croppedImageFile = croppedFile
                srcImageFile = resolveBackgroundFile(it.srcFilePath)
                cropRect = it.cropRect
                cropRotation = it.cropRotation
                croppedBitmap = BitmapFactory.decodeFile(croppedFile.absolutePath)
                filteredDrawable = BitmapDrawable(resources, croppedBitmap)
            }
            newCreated = false
        }
        // create new
        if (originTheme == null) {
            val (n, c, s) = ThemeFilesManager.newCustomBackgroundImages()
            backgroundStates.apply {
                croppedImageFile = c
                srcImageFile = s
            }
            // Use dark keys by default
            theme = ThemePreset.TransparentDark.deriveCustomBackground(n, c.path, s.path)
        }
        previewUi = KeyboardPreviewUi(this, theme)
        enableEdgeToEdge()
        ViewCompat.setOnApplyWindowInsetsListener(ui) { _, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = windowInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            ui.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                leftMargin = navBars.left
                rightMargin = navBars.right
            }
            toolbar.topPadding = statusBars.top
            scrollView.bottomPadding = navBars.bottom
            ui.post { updatePreviewScale() }
            windowInsets
        }
        // show Activity label on toolbar
        setSupportActionBar(toolbar)
        // show back button
        supportActionBar!!.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = toThemeLabel(theme.name)
        setContentView(ui)
        bindBackgroundControls()
        registerLayoutChangeObserver()
        applyThemePreview(theme)
        whenHasBackground { background ->
            brightnessSeekBar.progress = background.brightness
            blurRadiusSeekBar.progress = background.blurRadius.toInt()
            suppressVariantSwitchCallback = true
            variantSwitch.isChecked = !theme.isDark
            suppressVariantSwitchCallback = false
            updateBlurRadiusLabel(blurRadiusSeekBar.progress)
        }
        updateBackgroundEditorVisibility()

        if (newCreated) {
            cropLabel.visibility = View.GONE
            previewUi.onSizeMeasured = { w, h ->
                backgroundStates.launchCrop(w, h, pickNewSource = true)
            }
        } else {
            whenHasBackground {
                updateState()
            }
        }

        onBackPressedDispatcher.addCallback {
            cancel()
        }

        initialThemeSnapshot = effectiveThemeForDirtyCheck()
        refreshSaveButtonState()
    }

    private fun BackgroundStates.launchCrop(w: Int, h: Int, pickNewSource: Boolean) {
        val editSourceUri = pendingSrcUri
            ?: if (hasStorageFiles() && srcImageFile.exists()) Uri.fromFile(srcImageFile) else null
        if (pickNewSource || editSourceUri == null) {
            launcher.launch(CropOption.New(w, h))
        } else {
            launcher.launch(
                CropOption.Edit(
                    width = w,
                    height = h,
                    sourceUri = editSourceUri,
                    initialRect = cropRect,
                    initialRotation = cropRotation
                )
            )
        }
    }

    @SuppressLint("SetTextI18n")
    private fun BackgroundStates.updateState() {
        val progress = brightnessSeekBar.progress
        brightnessValue.text = "$progress%"
        filteredDrawable.colorFilter = DarkenColorFilter(100 - progress)
        val blurRadius = blurRadiusSeekBar.progress.toFloat()
        previewUi.setBackgroundWithBlur(filteredDrawable, croppedBitmap, blurRadius, progress)
    }

    private fun cancel() {
        setResult(
            RESULT_CANCELED,
            Intent().apply { putExtra(RESULT, null as BackgroundResult?) }
        )
        finish()
    }

    private fun done() {
        if (!hasUnsavedChanges()) {
            cancel()
            return
        }
        lifecycleScope.withLoadingDialog(this) {
            try {
                var outputTheme = theme
                whenHasBackground {
                    withContext(Dispatchers.IO) {
                        if (srcImageDirty && pendingSrcUri != null) {
                            srcImageExtension?.takeIf { ext -> ext.isNotBlank() }?.let { ext ->
                                val parent = srcImageFile.parentFile
                                val stem = srcImageFile.name.substringBeforeLast('.', srcImageFile.name)
                                srcImageFile = File(parent, "$stem.$ext")
                            }
                            theme = theme.copy(
                                backgroundImage = it.copy(
                                    srcFilePath = srcImageFile.absolutePath
                                )
                            )
                            srcImageFile.parentFile?.mkdirs()
                            srcImageFile.delete()
                            var copied = false
                            runCatching {
                                val input = pendingSrcFile?.takeIf { it.exists() && it.length() > 0L }?.inputStream()
                                    ?: run {
                                        val srcUri = pendingSrcUri!!
                                        contentResolver.openInputStream(srcUri)
                                            ?: if (srcUri.scheme == "file") {
                                                val srcPath = srcUri.path
                                                    ?: throw IllegalStateException(getString(R.string.exception_theme_src_image))
                                                File(srcPath).inputStream()
                                            } else {
                                                throw IllegalStateException(getString(R.string.exception_theme_src_image))
                                            }
                                    }
                                input.use { stream ->
                                    srcImageFile.outputStream().use { output -> stream.copyTo(output) }
                                }
                                copied = true
                            }.getOrNull()

                            if (!copied) {
                                srcImageFile.outputStream().use {
                                    croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                                }
                            }
                            if (!srcImageFile.exists() || srcImageFile.length() == 0L) {
                                srcImageFile = croppedImageFile
                                theme = theme.copy(
                                    backgroundImage = it.copy(
                                        srcFilePath = croppedImageFile.absolutePath
                                    )
                                )
                            }
                            srcImageDirty = false
                            pendingSrcUri = null
                            pendingSrcFile?.delete()
                            pendingSrcFile = null
                        }

                        croppedImageFile.delete()
                        croppedImageFile.outputStream().use {
                            croppedBitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    }
                }
                outputTheme = theme
                whenHasBackground {
                    outputTheme = outputTheme.copy(
                        backgroundImage = it.copy(
                            brightness = brightnessSeekBar.progress,
                            blurRadius = blurRadiusSeekBar.progress.toFloat(),
                            cropRect = cropRect,
                            cropRotation = cropRotation
                        )
                    )
                }
                outputTheme = withContext(Dispatchers.IO) {
                    ThemeFilesManager.alignBackgroundAssetsWithThemeName(outputTheme)
                }
                setResult(
                    RESULT_OK,
                    Intent().apply {
                        putExtra(
                            RESULT,
                            if (newCreated)
                                BackgroundResult.Created(outputTheme)
                            else
                                BackgroundResult.Updated(
                                    oldName = originalThemeName ?: outputTheme.name,
                                    theme = outputTheme
                                )
                        )
                    })
                finish()
            } catch (e: Exception) {
                timber.log.Timber.e("Exception when saving custom theme: ${e.stackTraceToString()}")
                toast(e)
                return@withLoadingDialog
            }
        }
    }

    private fun delete() {
        setResult(
            RESULT_OK,
            Intent().apply {
                putExtra(RESULT, BackgroundResult.Deleted(theme.name))
            }
        )
        finish()
    }

    private fun promptDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_theme)
            .setMessage(getString(R.string.delete_theme_msg, theme.name))
            .setPositiveButton(android.R.string.ok) { _, _ ->
                delete()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun randomizeInEditor() {
        val result = ThemeManager.randomizeTheme()
        theme = result.theme
        initialThemeSnapshot = theme
        refreshColorPreviews()
        applyThemePreview(theme)
        toast(
            getString(
                R.string.random_theme_toast,
                result.total,
                result.contrast,
                result.hue,
                result.sat,
                result.light
            )
        )
        refreshSaveButtonState()
    }

    private fun promptCopyAsCustomTheme() {
        val input = EditText(this).apply {
            setText("")
            hint = getString(R.string.theme_name)
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.random_theme_copy)
            .setView(input)
            .setPositiveButton(android.R.string.ok, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text?.toString()?.trim().orEmpty()
                if (name.isEmpty()) {
                    toast(R.string.theme_name_empty)
                    return@setOnClickListener
                }
                if (name == ThemeManager.RANDOM_THEME_NAME) {
                    toast(R.string.random_theme_name_reserved)
                    return@setOnClickListener
                }
                if (ThemeManager.getTheme(name) != null) {
                    toast(R.string.exception_theme_name_clash)
                    return@setOnClickListener
                }
                setResult(
                    RESULT_OK,
                    Intent().apply {
                        putExtra(RESULT, BackgroundResult.Created(theme.copy(name = name)))
                    }
                )
                finish()
            }
        }
        dialog.show()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        val iconTint = styledColor(android.R.attr.colorControlNormal)
        if (isRandomMode) {
            menu.item(R.string.random_theme_generate, R.drawable.ic_random_theme_24, iconTint, true) {
                randomizeInEditor()
            }
            menu.item(R.string.random_theme_copy, R.drawable.ic_baseline_content_copy_24, iconTint, true) {
                promptCopyAsCustomTheme()
            }
        } else {
            if (!newCreated) {
                val deleteTint = color(R.color.red_400)
                menu.item(R.string.save, R.drawable.ic_baseline_delete_24, deleteTint, true) {
                    promptDelete()
                }
            }
            menu.item(R.string.theme_name, R.drawable.ic_baseline_edit_24, iconTint, true) {
                promptRenameTheme()
            }
            saveMenuItem = menu.item(R.string.save, R.drawable.ic_baseline_check_24, iconTint, true) {
                done()
            }
        }
        refreshSaveButtonState()
        updateScorePreview()
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        android.R.id.home -> {
            cancel()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        unregisterLayoutChangeObserver()
        super.onDestroy()
    }

    companion object {
        private const val INLINE_COLOR_EDITOR_TAG = "inline_color_editor"
        const val RESULT = "result"
        const val ORIGIN_THEME = "origin_theme"

        /**
         * 默认展开的颜色分组数（其余收起，点组头展开）。
         *
         * 取 2 = 「背景与顶栏」+「按键」：这两组覆盖用户最常改的项，
         * 而候选/弹出/分隔线留待需要时展开。数值按计划「细节收进折叠」定。
         */
        private const val EXPANDED_GROUP_COUNT = 2
    }
}

/**
 * 主题编辑器里的一行颜色项。
 *
 * [id] 与 [titleRes] **必须分开**（2026-09-28 解耦）：
 * 原先只有一个英文 `name`，它同时承担「显示给用户」和「逻辑标识」两个职责，
 * 于是没法本地化——一改显示文案就会破坏 `colorPreviewDrawables` 的 map 键、
 * `fieldName` 往返匹配和「水波纹可清除」的判断。
 *
 * - [id]：稳定的内部标识，只用于 map 键、匹配和 Intent 往返，**不参与持久化**
 *   （主题以 JSON 存盘，字段名来自 `Theme.Custom` 的属性名，与此无关）。
 * - [titleRes]：只用于界面显示，可自由翻译。
 */
data class ThemeColorEditItem(
    @StringRes val titleRes: Int,
    val id: String,
    /** 所属分组的小标题，用于在编辑器里插入分组头。 */
    @StringRes val groupRes: Int,
    val getter: (Theme.Custom) -> Int,
    val setter: (Theme.Custom, Int) -> Theme.Custom
) {
    companion object {
        /** 唯一允许「清除」（回落自动计算）的颜色项。 */
        const val ID_WATER_RIPPLE = "Water Ripple"
    }
}
