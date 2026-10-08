/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import android.text.TextPaint
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.FloatRange
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.updateLayoutParams
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.PunctuationPosition
import org.fcitx.fcitx5.android.input.AutoScaleTextView
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.utils.styledFloat
import org.fcitx.fcitx5.android.utils.unset
import splitties.dimensions.dp
import splitties.views.dsl.constraintlayout.centerHorizontally
import splitties.views.dsl.constraintlayout.centerInParent
import splitties.views.dsl.constraintlayout.constraintLayout
import splitties.views.dsl.constraintlayout.lParams
import splitties.views.dsl.constraintlayout.parentId
import splitties.views.dsl.core.add
import splitties.views.dsl.core.imageView
import splitties.views.dsl.core.lParams
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.view
import splitties.views.dsl.core.wrapContent
import splitties.views.existingOrNewId
import splitties.views.imageResource
import splitties.views.padding
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

interface SwipeHintAwareKeyView {
    fun shouldTriggerAltBySwipe(totalY: Int, fallback: SwipeSymbolDirection): Boolean
}

abstract class KeyView(
    ctx: Context,
    var theme: Theme,
    val def: KeyDef.Appearance,
    horizontalGapScale: Float = 1f
) :
    CustomGestureView(ctx) {

    private companion object {
        private const val THEME_COLOR_REF_PREFIX = "theme:"
    }

    val bordered: Boolean
    val borderStroke: Boolean
    val rippled: Boolean
    val radius: Float
    val hMargin: Int
    val vMargin: Int
    protected val cornerLabelHorizontalSafeInset: Int
    protected val cornerLabelTopSafeInset: Int

    init {
        val prefs = ThemeManager.prefs
        bordered = prefs.keyBorder.getValue()
        borderStroke = prefs.keyBorderStroke.getValue()
        rippled = prefs.keyRippleEffect.getValue()
        radius = dp(prefs.keyRadius.getValue().toFloat())
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val hMarginPref =
            if (landscape) prefs.keyHorizontalMarginLandscape else prefs.keyHorizontalMargin
        val vMarginPref =
            if (landscape) prefs.keyVerticalMarginLandscape else prefs.keyVerticalMargin
        val hScale = horizontalGapScale.coerceIn(0.5f, 1f)
        val hMarginValue = (hMarginPref.getValue().toFloat() * hScale).roundToInt().coerceAtLeast(0)
        hMargin = if (def.margin) dp(hMarginValue) else 0
        vMargin = if (def.margin) dp(vMarginPref.getValue()) else 0
        // Keep corner labels inside rounded key backgrounds. The old fixed 3dp/1dp inset was
        // enough for square-ish keys but could place the glyph over a larger rounded corner.
        cornerLabelHorizontalSafeInset = max(dp(3), min((radius * 0.5f).roundToInt(), dp(12)))
        cornerLabelTopSafeInset = max(dp(1), min((radius * 0.2f).roundToInt(), dp(4)))
    }

    private val cachedLocation = intArrayOf(0, 0)
    private val cachedBounds = Rect()
    private var boundsValid = false
    val bounds: Rect
        get() = cachedBounds.also {
            if (!boundsValid) updateBounds()
        }

    fun invalidateCachedBounds() {
        boundsValid = false
    }

    /**
     * KeyView content left margin, in percentage of parent width
     */
    @FloatRange(0.0, 1.0)
    var layoutMarginLeft = 0f

    /**
     * KeyView content right margin, in percentage of parent width
     */
    @FloatRange(0.0, 1.0)
    var layoutMarginRight = 0f

    var onWaterRippleRequest: ((keyView: KeyView, localX: Float, localY: Float) -> Unit)? = null

    /**
     * [KeyView] contains 2 parts: `TouchEventView` and `AppearanceView`.
     *
     * `TouchEventView` is the outer [CustomGestureView] that handles touch events.
     *
     * `AppearanceView` in the inner [ConstraintLayout], it can be smaller than its parent,
     * and holds the [bounds] for popup.
     */
    protected val appearanceView = constraintLayout {
        // sync any state from parent
        isDuplicateParentStateEnabled = true
    }

    init {
        // trigger setEnabled(true)
        isEnabled = true
        isClickable = true
        isHapticFeedbackEnabled = false
        if (def.viewId > 0) {
            id = View.generateViewId()
            tag = def.viewId
        }
        if (def.transparentBackground) {
            // 空白占位键：底、边框、阴影、按压高亮一概不画，键面直接透出键盘底色。
            // 放在最前面判断，是为了不被下面任何一支"主题开着描边/圆角"的分支接管——
            // 用户要的是"看不见的占位"，任何一层底色都会让它变成"没字的按键"。
            appearanceView.background = null
            appearanceView.foreground = null
        } else if (usesSpecialBackground()) {
            appearanceView.background = null
            appearanceView.foreground = null
        } else if ((bordered && def.border != Border.Off) || def.border == Border.On) {
            applyStandardBackground(theme)
        } else {
            setupPressHighlight()
        }
        add(appearanceView, lParams(matchParent, matchParent))
    }

    private fun resolveMonetColor(resourceName: String?): Int? {
        val name = resourceName?.takeIf { it.isNotBlank() } ?: return null
        val colorResId = context.resources.getIdentifier(name, "color", "android")
        if (colorResId == 0) return null
        return runCatching { context.getColor(colorResId) }.getOrNull()
    }

    private fun resolveThemeTokenColor(theme: Theme, token: String): Int? {
        return when (token) {
            "backgroundColor" -> theme.backgroundColor
            "barColor" -> theme.barColor
            "keyboardColor" -> theme.keyboardColor
            "keyBackgroundColor" -> theme.keyBackgroundColor
            "keyTextColor" -> theme.keyTextColor
            "candidateTextColor" -> theme.candidateTextColor
            "candidateLabelColor" -> theme.candidateLabelColor
            "candidateCommentColor" -> theme.candidateCommentColor
            "altKeyBackgroundColor" -> theme.altKeyBackgroundColor
            "altKeyTextColor" -> theme.altKeyTextColor
            "accentKeyBackgroundColor" -> theme.accentKeyBackgroundColor
            "accentKeyTextColor" -> theme.accentKeyTextColor
            "keyPressHighlightColor" -> theme.keyPressHighlightColor
            "keyShadowColor" -> theme.keyShadowColor
            "popupBackgroundColor" -> theme.popupBackgroundColor
            "popupTextColor" -> theme.popupTextColor
            "spaceBarColor" -> theme.spaceBarColor
            "dividerColor" -> theme.dividerColor
            "clipboardEntryColor" -> theme.clipboardEntryColor
            "genericActiveBackgroundColor" -> theme.genericActiveBackgroundColor
            "genericActiveForegroundColor" -> theme.genericActiveForegroundColor
            else -> null
        }
    }

    private fun resolveColorOverride(staticColor: Int?, colorRef: String?, theme: Theme = this.theme): Int? {
        val refValue = colorRef?.takeIf { it.isNotBlank() }
        val resolved = if (refValue != null && refValue.startsWith(THEME_COLOR_REF_PREFIX)) {
            resolveThemeTokenColor(theme, refValue.removePrefix(THEME_COLOR_REF_PREFIX))
        } else {
            resolveMonetColor(refValue)
        }
        return resolved ?: staticColor
    }

    protected fun resolveBackgroundColor(theme: Theme, defaultColor: Int): Int {
        return resolveColorOverride(def.backgroundColor, def.backgroundColorMonet) ?: defaultColor
    }

    protected fun resolveShadowColor(theme: Theme): Int {
        return resolveColorOverride(def.shadowColor, def.shadowColorMonet) ?: theme.keyShadowColor
    }

    protected fun resolveTextColor(defaultColor: Int): Int {
        return resolveColorOverride(def.textColor, def.textColorMonet) ?: defaultColor
    }

    protected fun resolveAltTextColor(defaultColor: Int): Int {
        return resolveColorOverride(def.altTextColor, def.altTextColorMonet) ?: defaultColor
    }

    protected fun punctuationPositionForKey(): PunctuationPosition {
        val character = (def as? KeyDef.Appearance.AltText)?.character?.singleOrNull()
        return if (character != null && (character in 'a'..'z' || character in 'A'..'Z')) {
            ThemeManager.prefs.punctuationPosition.getValue()
        } else {
            PunctuationPosition.Bottom
        }
    }

    private fun defaultBackgroundColor(theme: Theme): Int = when (def.variant) {
        Variant.Normal, Variant.AltForeground -> theme.keyBackgroundColor
        Variant.Alternative -> theme.altKeyBackgroundColor
        Variant.Accent -> theme.accentKeyBackgroundColor
    }

    private fun usesPillShape(): Boolean = ThemeManager.prefs.specialKeyOvalShape.getValue() && when (def.viewId) {
        R.id.button_return, R.id.button_layout_switch -> true
        else -> false
    }

    fun blurClipRadius(clipWidth: Int, clipHeight: Int): Float {
        val maxRadius = min(clipWidth, clipHeight) * 0.5f
        return if (usesPillShape()) {
            maxRadius
        } else {
            radius.coerceIn(0f, maxRadius)
        }
    }

    private fun usesSpecialBackground(): Boolean = when (def.viewId) {
        R.id.button_space -> !bordered
        // Return keeps accent pill background even when key border is disabled.
        R.id.button_return -> !bordered || usesPillShape()
        R.id.button_layout_switch -> usesPillShape()
        else -> false
    }

    private fun applyStandardBackground(theme: Theme) {
        val bkgColor = resolveBackgroundColor(theme, defaultBackgroundColor(theme))
        val borderOrShadowWidth = dp(1)
        appearanceView.background = if (borderStroke) borderedKeyBackgroundDrawable(
            bkgColor, resolveShadowColor(theme),
            radius, borderOrShadowWidth, hMargin, vMargin
        ) else shadowedKeyBackgroundDrawable(
            bkgColor, resolveShadowColor(theme),
            radius, borderOrShadowWidth, hMargin, vMargin
        )
        setupPressHighlight()
    }

    private fun applyPillBackground(theme: Theme) {
        val bkgColor = resolveBackgroundColor(theme, defaultBackgroundColor(theme))
        val borderOrShadowWidth = dp(1)
        appearanceView.background = if (bordered) {
            if (borderStroke) {
                borderedPillKeyBackgroundDrawable(
                    bkgColor, resolveShadowColor(theme),
                    borderOrShadowWidth, hMargin, vMargin
                )
            } else {
                shadowedPillKeyBackgroundDrawable(
                    bkgColor, resolveShadowColor(theme),
                    borderOrShadowWidth, hMargin, vMargin
                )
            }
        } else {
            insetPillDrawable(hMargin, vMargin, bkgColor)
        }
        appearanceView.padding = 0
        setupPressHighlight(
            insetPillDrawable(
                hMargin, vMargin,
                if (rippled) Color.WHITE else theme.keyPressHighlightColor
            )
        )
    }

    private fun setupPressHighlight(mask: Drawable? = null) {
        if (rippled) {
            background = null
            appearanceView.foreground = RippleDrawable(
                ColorStateList.valueOf(theme.keyPressHighlightColor), null,
                mask ?: highlightMaskDrawable(Color.WHITE)
            )
            return
        }

        background = null
        appearanceView.foreground = if (bordered && borderStroke && mask == null) {
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    borderedKeyBackgroundDrawable(
                        Color.TRANSPARENT, resolveShadowColor(theme),
                        radius, dp(2), hMargin, vMargin
                    )
                )
            }
        } else {
            StateListDrawable().apply {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    // use mask drawable as highlight directly
                    mask ?: highlightMaskDrawable(theme.keyPressHighlightColor)
                )
            }
        }
    }

    private fun highlightMaskDrawable(@ColorInt color: Int): Drawable {
        return if (bordered) insetRadiusDrawable(hMargin, vMargin, radius, color)
        else InsetDrawable(ColorDrawable(color), hMargin, vMargin, hMargin, vMargin)
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        // 纯外观键（空白占位键）永远保持不透明：它的 isEnabled 为 false 只是因为
        // "不该接收点击"，键面文字却是用户特意设置的装饰内容，套用 disabledAlpha
        // 会把它一直显示成半透明——那不是用户要的效果。
        appearanceView.alpha = if (enabled || def.staticDisplay) 1f else styledFloat(android.R.attr.disabledAlpha)
    }

    fun updateBounds() {
        val (x, y) = cachedLocation.also { appearanceView.getLocationInWindow(it) }
        cachedBounds.set(x, y, x + appearanceView.width, y + appearanceView.height)
        boundsValid = true
    }

    open fun setTextScale(scale: Float) {
        // default implementation does nothing
    }

    protected open fun onAppearanceLayoutChanged(width: Int, height: Int) {
        // default implementation does nothing
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        boundsValid = false
        if (layoutMarginLeft != 0f || layoutMarginRight != 0f) {
            val w = right - left
            val h = bottom - top
            val layoutWidth = (w * (1f - layoutMarginLeft - layoutMarginRight)).roundToInt()
            appearanceView.updateLayoutParams<LayoutParams> {
                leftMargin = (w * layoutMarginLeft).roundToInt()
                rightMargin = (w * layoutMarginRight).roundToInt()
            }
            // sets `measuredWidth` and `measuredHeight` of `AppearanceView`
            // https://developer.android.com/guide/topics/ui/how-android-draws#measure
            appearanceView.measure(
                MeasureSpec.makeMeasureSpec(layoutWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
            )
        }
        super.onLayout(changed, left, top, right, bottom)
        onAppearanceLayoutChanged(appearanceView.width, appearanceView.height)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN && rippled) {
            onWaterRippleRequest?.invoke(this, event.x, event.y)
        }
        return super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val specialKeyOvalShapeEnabled = ThemeManager.prefs.specialKeyOvalShape.getValue()
        when (def.viewId) {
            R.id.button_space -> {
                if (bordered) return
                val bkgRadius = dp(3f)
                val minHeight = dp(26)
                val hInset = dp(10)
                val vInset = if (h < minHeight) 0 else min((h - minHeight) / 2, dp(16))
                appearanceView.background = insetRadiusDrawable(
                    hInset, vInset, bkgRadius, resolveBackgroundColor(theme, theme.spaceBarColor)
                )
                // InsetDrawable sets padding to container view; remove padding to prevent text from bing clipped
                appearanceView.padding = 0
                // apply press highlight for background area
                setupPressHighlight(
                    insetRadiusDrawable(
                        hInset, vInset, bkgRadius,
                        if (rippled) Color.WHITE else theme.keyPressHighlightColor
                    )
                )
            }
            R.id.button_return -> {
                // Return uses special pill background when border is disabled,
                // and also when Gboard-style special key oval shape is enabled.
                if ((!bordered || specialKeyOvalShapeEnabled) && def.border == Border.Special) {
                    applyPillBackground(theme)
                }
            }
            R.id.button_layout_switch -> {
                if (specialKeyOvalShapeEnabled && def.border == Border.Special) {
                    applyPillBackground(theme)
                }
            }
        }
    }

    /**
     * Update theme without rebuilding view
     */
    open fun updateTheme(newTheme: Theme) {
        theme = newTheme

        if (def.transparentBackground) {
            // 换主题不能把占位键的"看不见"弄丢：见 init 里的同一分支。
            appearanceView.background = null
            appearanceView.foreground = null
        } else if (usesSpecialBackground()) {
            appearanceView.background = null
            appearanceView.foreground = null
        } else if ((bordered && def.border != Border.Off) || def.border == Border.On) {
            applyStandardBackground(newTheme)
        } else {
            appearanceView.background = null
            setupPressHighlight()
        }

        val w = appearanceView.width
        val h = appearanceView.height
        if (w > 0 && h > 0) {
            onSizeChanged(w, h, w, h)
        }
    }
}

@SuppressLint("ViewConstructor")
class ToolbarTextKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.ToolbarText,
    horizontalGapScale: Float = 1f
) : KeyView(ctx, theme, def, horizontalGapScale) {
    /**
     * AutoScale label that keeps the canonical fixed dp size: unlike keyboard keys it never
     * participates in the configurable text-scale setting (a 40dp toolbar row scaled to a
     * fraction of 21dp is what made these digits invisible), but it is still a real
     * [AutoScaleTextView] wired to the "key_main_font" slot, so the user's configured key
     * typeface and keyboard font size settings keep applying. BaseKeyboard applies the
     * typeface in batch via [org.fcitx.fcitx5.android.input.keyboard.BaseKeyboard.applyConfiguredFonts];
     * here we seed the configured size directly so the first frame is already correct.
     */
    val label = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        background = null
        gravity = Gravity.CENTER
        text = def.displayText
        setTextSize(
            TypedValue.COMPLEX_UNIT_SP,
            org.fcitx.fcitx5.android.input.font.FontProviders.getFontSize(
                "key_main_font", def.textSize
            )
        )
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        fontKey = "key_main_font"
        setTypeface(typeface, def.textStyle)
        setTextColor(
            resolveTextColor(
                when (def.variant) {
                    Variant.Normal -> theme.keyTextColor
                    Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                    Variant.Accent -> theme.accentKeyTextColor
                }
            )
        )
    }

    init {
        appearanceView.apply {
            add(label, lParams(matchParent, matchParent) {
                centerInParent()
            })
        }
    }

    /**
     * Re-read the configured size, ignoring the keyboard text-scale argument.
     *
     * [BaseKeyboard.reapplyTextScale] runs after every layout reload, which makes this the
     * reliable hook for "key_main_font_size" changes: [org.fcitx.fcitx5.android.input.font.FontProviders.fontGeneration]
     * now covers sizes as well as typefaces, so a size-only edit invalidates the row cache and
     * the reload re-runs this override with the fresh size. The scale argument is deliberately
     * ignored — toolbar digits must never be shrunk to invisibility.
     */
    override fun setTextScale(scale: Float) {
        // In a method body `def` is the inherited KeyView.def (KeyDef.Appearance), so smart
        // cast to ToolbarText before reading the size — same idiom as TextKeyView above.
        if (def is KeyDef.Appearance.ToolbarText) {
            label.setTextSize(
                TypedValue.COMPLEX_UNIT_SP,
                org.fcitx.fcitx5.android.input.font.FontProviders.getFontSize(
                    "key_main_font", def.textSize
                )
            )
            label.requestLayout()
            label.invalidate()
        }
    }

    override fun updateTheme(newTheme: Theme) {
        super.updateTheme(newTheme)
        label.setTextColor(
            resolveTextColor(
                when (def.variant) {
                    Variant.Normal -> newTheme.keyTextColor
                    Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
                    Variant.Accent -> newTheme.accentKeyTextColor
                }
            )
        )
    }
}

@SuppressLint("ViewConstructor")
open class TextKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.Text,
    horizontalGapScale: Float = 1f
) :
    KeyView(ctx, theme, def, horizontalGapScale) {
    /**
     * 构造期的外观定义，保留 [KeyDef.Appearance.Text] 静态类型。
     *
     * 基类的 `def` 是 `KeyDef.Appearance`，方法体里拿不到 `textSize`；而构造参数只做
     * 属性初始化、不进入方法体，所以这里显式留一份引用。
     */
    private val textAppearance: KeyDef.Appearance.Text = def

    /**
     * 主标签的基准字号（sp），不含手势/单手模式的缩放系数。
     *
     * 以前这是 `private val`，即「构造期快照」：KeyView 实例会被长期复用（KeyboardWindow
     * 把 keyboard 实例缓存进 map，BaseKeyboard 又复用 reusableRowsCache 里的行），所以只在
     * 构造时读一次配置字号的话，用户改完 fontset 后就必须等「行被真正重建」才能看到新字号
     * ——候选侧此前那个「保存后不即时生效、要打几个字才好」是同一类问题。
     * 改成方法后，[setTextScale] 每次都能取到最新配置。
     * `getFontSize` 自带结果缓存，重复调用开销可忽略。
     */
    private fun resolveBaseMainTextSizeSp(): Float = when (textAppearance.viewId) {
        R.id.button_space -> textAppearance.textSize
        R.id.button_layout_switch -> textAppearance.textSize
        else -> org.fcitx.fcitx5.android.input.font.FontProviders.getFontSize(
            "key_main_font", textAppearance.textSize
        )
    }

    val mainText = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        background = null
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = Gravity.CENTER
        text = def.displayText
        setTextSize(TypedValue.COMPLEX_UNIT_SP, resolveBaseMainTextSizeSp())
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        // Set font key for batch setting in BaseKeyboard.reloadLayout()
        fontKey = "key_main_font"
        setTypeface(typeface, def.textStyle)
        setTextColor(
            resolveTextColor(
                when (def.variant) {
                    Variant.Normal -> theme.keyTextColor
                    Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                    Variant.Accent -> theme.accentKeyTextColor
                }
            )
        )
    }

    init {
        appearanceView.apply {
            if (def.viewId == R.id.button_space) {
                val insetPadding = dp(10)
                mainText.setPadding(insetPadding + hMargin, 0, insetPadding + hMargin, 0)
                add(mainText, lParams(matchParent, wrapContent) {
                    centerInParent()
                })
            } else {
                mainText.setPadding(hMargin, 0, hMargin, 0)
                add(mainText, lParams(matchParent, wrapContent) {
                    centerInParent()
                })
            }
        }
    }

    override fun setTextScale(scale: Float) {
        // 每次都重读配置字号（而不是用构造期快照乘 scale）：复用旧行、只走
        // reapplyTextScale() 的刷新路径也能自愈，见 resolveBaseMainTextSizeSp 的说明。
        val baseSize = resolveBaseMainTextSizeSp()
        mainText.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSize * scale)
        mainText.requestLayout()
    }

    override fun updateTheme(newTheme: Theme) {
        super.updateTheme(newTheme)
        mainText.setTextColor(
            resolveTextColor(
                when (def.variant) {
                    Variant.Normal -> newTheme.keyTextColor
                    Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
                    Variant.Accent -> newTheme.accentKeyTextColor
                }
            )
        )
    }
}

@SuppressLint("ViewConstructor")
class AltTextKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.AltText,
    horizontalGapScale: Float = 1f
) :
    TextKeyView(ctx, theme, def, horizontalGapScale), SwipeHintAwareKeyView {
    private enum class AltTextLayoutMode {
        TopRight,
        TopCenter,
        Bottom,
        DirectionalSingleTopRight,
        DirectionalSingleTopCenter,
        DirectionalSingleBottom,
        DirectionalTopBottom,
        DirectionalTopRight,
        Hidden
    }

    /**
     * 副标签的基准字号（sp）。与 [TextKeyView.resolveBaseMainTextSizeSp] 同理，不用
     * 构造期快照，改由 [setTextScale] 每次重读，避免复用旧行时拿不到新字号。
     */
    private fun resolveBaseAltTextSizeSp(): Float =
        org.fcitx.fcitx5.android.input.font.FontProviders.getFontSize(
            "key_alt_font", 10.666667f
        )

    private var lastLayoutMode: AltTextLayoutMode? = null

    val altText = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = Gravity.CENTER
        setPadding(hMargin, 0, hMargin, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, resolveBaseAltTextSizeSp())
        // Set font key for batch setting in BaseKeyboard.reloadLayout()
        fontKey = "key_alt_font"
        setTypeface(typeface, Typeface.BOLD)
        text = def.altText
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        setTextColor(
            resolveAltTextColor(
                when (def.variant) {
                    Variant.Normal, Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                    Variant.Accent -> theme.accentKeyTextColor
                }
            )
        )
    }

    val altText1 = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = Gravity.CENTER
        setPadding(hMargin, 0, hMargin, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, resolveBaseAltTextSizeSp())
        fontKey = "key_alt_font"
        setTypeface(typeface, Typeface.BOLD)
        text = def.altText1 ?: ""
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        setTextColor(
            resolveAltTextColor(
                when (def.variant) {
                    Variant.Normal, Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                    Variant.Accent -> theme.accentKeyTextColor
                }
            )
        )
    }

    private fun applyTopRightAltTextPadding(label: AutoScaleTextView) {
        // Keep the safe area in the LayoutParams margin so the glyph, not only the
        // TextView's content box, stays clear of the rounded corner.
        label.setPadding(0, 0, 0, 0)
    }

    private fun applyBottomAltTextPadding(label: AutoScaleTextView = altText) {
        label.setPadding(hMargin, 0, hMargin, 0)
    }

    private fun activeDirectionalLabel(): AutoScaleTextView =
        if (!altText.text.isNullOrBlank()) altText else altText1

    private fun resetDirectionalAltText1() {
        altText1.visibility = View.GONE
        altText1.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = unset
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = unset
        }
    }

    init {
        appearanceView.apply {
            add(altText, lParams(0, wrapContent))
            add(altText1, lParams(0, wrapContent))
        }
        applyLayout()
    }

    override fun setTextScale(scale: Float) {
        super.setTextScale(scale)
        altText.setTextSize(TypedValue.COMPLEX_UNIT_SP, resolveBaseAltTextSizeSp() * scale)
        altText.requestLayout()
        altText1.setTextSize(TypedValue.COMPLEX_UNIT_SP, resolveBaseAltTextSizeSp() * scale)
        altText1.requestLayout()
        lastLayoutMode = null
        applyLayout()
    }

    private fun applyTopRightAltTextPosition(label: AutoScaleTextView = altText) {
        val hidden = if (label === altText) altText1 else altText
        hidden.visibility = View.GONE
        label.visibility = View.VISIBLE
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = parentId
            topToBottom = unset
            bottomToBottom = parentId
            bottomToTop = unset
            topMargin = 0
            bottomMargin = 0
        }
        label.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = parentId
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = unset
            leftToLeft = parentId
            rightToRight = parentId
            leftMargin = hMargin
            rightMargin = hMargin + cornerLabelHorizontalSafeInset
            topMargin = vMargin + cornerLabelTopSafeInset
            bottomMargin = 0
        }
        applyTopRightAltTextPadding(label)
        label.gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    private fun applyTopCenterAltTextPosition(label: AutoScaleTextView = altText) {
        val hidden = if (label === altText) altText1 else altText
        hidden.visibility = View.GONE
        label.visibility = View.VISIBLE
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = parentId
            topToBottom = unset
            bottomToBottom = parentId
            bottomToTop = unset
            topMargin = 0
            bottomMargin = 0
        }
        label.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = parentId
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = unset
            leftToLeft = parentId
            rightToRight = parentId
            leftMargin = hMargin
            rightMargin = hMargin
            topMargin = vMargin + cornerLabelTopSafeInset
            bottomMargin = 0
        }
        applyBottomAltTextPadding(label)
        label.gravity = Gravity.CENTER
    }

    private fun applyBottomAltTextPosition(label: AutoScaleTextView = altText) {
        val hidden = if (label === altText) altText1 else altText
        hidden.visibility = View.GONE
        label.visibility = View.VISIBLE
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (ThemeManager.prefs.moveMainTextForAltLabel.getValue()) {
                topToTop = parentId
                topToBottom = unset
                bottomToBottom = unset
                bottomToTop = label.existingOrNewId
                topMargin = vMargin
                bottomMargin = 0
            } else {
                centerInParent()
                topToTop = parentId
                topToBottom = unset
                bottomToBottom = parentId
                bottomToTop = unset
                topMargin = 0
                bottomMargin = 0
            }
        }
        label.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = unset
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = parentId
            leftToLeft = parentId
            rightToRight = parentId
            leftMargin = hMargin
            rightMargin = hMargin
            topMargin = 0
            bottomMargin = vMargin + dp(2)
        }
        applyBottomAltTextPadding(label)
        label.gravity = Gravity.CENTER
    }

    private fun applyDirectionalTopBottomAltTextPosition() {
        val hasUpLabel = !altText.text.isNullOrBlank()
        val hasDownLabel = !altText1.text.isNullOrBlank()
        val moveMainText = ThemeManager.prefs.moveMainTextForAltLabel.getValue()
        altText.visibility = if (hasUpLabel) View.VISIBLE else View.GONE
        altText1.visibility = if (hasDownLabel) View.VISIBLE else View.GONE
        altText.gravity = Gravity.CENTER
        altText1.gravity = Gravity.CENTER
        altText.setPadding(hMargin, 0, hMargin, 0)
        altText1.setPadding(hMargin, 0, hMargin, 0)

        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = parentId
            bottomToTop = if (moveMainText && hasUpLabel) mainText.existingOrNewId else unset
            bottomToBottom = if (moveMainText && !hasUpLabel) parentId else unset
            leftToLeft = parentId
            rightToRight = parentId
            topMargin = vMargin
            bottomMargin = 0
        }
        altText1.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = if (moveMainText && !hasDownLabel) parentId else unset
            topToBottom = if (moveMainText && hasDownLabel) mainText.existingOrNewId else unset
            bottomToBottom = parentId
            leftToLeft = parentId
            rightToRight = parentId
            topMargin = 0
            bottomMargin = vMargin + dp(2)
        }
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (moveMainText) {
                topToTop = if (hasUpLabel) unset else parentId
                topToBottom = if (hasUpLabel) altText.existingOrNewId else unset
                bottomToBottom = if (hasDownLabel) unset else parentId
                bottomToTop = if (hasDownLabel) altText1.existingOrNewId else unset
                topMargin = 0
                bottomMargin = 0
            } else {
                centerInParent()
                topToTop = parentId
                topToBottom = unset
                bottomToBottom = parentId
                bottomToTop = unset
                topMargin = 0
                bottomMargin = 0
            }
        }
    }

    private fun applyDirectionalTopRightAltTextPosition() {
        applyTopRightAltTextPosition(activeDirectionalLabel())
    }

    private fun applyNoAltTextPosition() {
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            // reset
            topMargin = 0
            bottomToTop = unset
            // set
            topToTop = parentId
            bottomToBottom = parentId
        }
        altText.visibility = View.GONE
        altText1.visibility = View.GONE
        applyBottomAltTextPadding()
        altText.gravity = Gravity.CENTER
    }

    private fun resolveLayoutMode(keyHeight: Int): AltTextLayoutMode {
        val pref = punctuationPositionForKey()
        if (def.directionalSwipeLabels) {
            if (pref == PunctuationPosition.None) return AltTextLayoutMode.Hidden
            val hasUpLabel = !altText.text.isNullOrBlank()
            val hasDownLabel = !altText1.text.isNullOrBlank()
            if (!hasUpLabel && !hasDownLabel) return AltTextLayoutMode.Hidden

            if (hasUpLabel && hasDownLabel) {
                if (keyHeight > 0) {
                    val contentHeight = keyHeight - vMargin * 2
                    val mainHeight = mainText.paint.run { fontMetrics.bottom - fontMetrics.top }
                    val upHeight = altText.paint.run { fontMetrics.bottom - fontMetrics.top }
                    val downHeight = altText1.paint.run { fontMetrics.bottom - fontMetrics.top }
                    // Use the legacy single-label capacity as the fallback boundary. A
                    // normal row can keep both labels unless it cannot even fit the main
                    // content plus one label; only then collapse to the upper-right label.
                    val singleLabelMinHeight = mainHeight + max(upHeight, downHeight) + dp(1)
                    if (contentHeight < singleLabelMinHeight) return AltTextLayoutMode.DirectionalTopRight
                }
                return AltTextLayoutMode.DirectionalTopBottom
            }

            // A single directional label uses the same geometry as AlphabetKey. The
            // label keeps its physical swipe direction, while its visual position and
            // compact-height fallback follow the theme preference.
            val preferred = when (pref) {
                PunctuationPosition.TopRight -> AltTextLayoutMode.DirectionalSingleTopRight
                PunctuationPosition.TopCenter -> AltTextLayoutMode.DirectionalSingleTopCenter
                PunctuationPosition.Bottom -> AltTextLayoutMode.DirectionalSingleBottom
                PunctuationPosition.None -> AltTextLayoutMode.Hidden
            }
            if (keyHeight <= 0) return preferred

            val contentHeight = keyHeight - vMargin * 2
            val mainHeight = mainText.paint.run { fontMetrics.bottom - fontMetrics.top }
            val altHeight = activeDirectionalLabel().paint.run { fontMetrics.bottom - fontMetrics.top }
            // These are the old pre-direction-label thresholds.
            val compactMinHeight = max(mainHeight, altHeight + cornerLabelTopSafeInset)
            val stackedMinHeight = mainHeight + altHeight + dp(1)

            return when (preferred) {
                AltTextLayoutMode.DirectionalSingleBottom -> when {
                    contentHeight >= stackedMinHeight -> AltTextLayoutMode.DirectionalSingleBottom
                    contentHeight >= compactMinHeight -> AltTextLayoutMode.DirectionalSingleTopRight
                    else -> AltTextLayoutMode.Hidden
                }
                AltTextLayoutMode.DirectionalSingleTopCenter -> when {
                    contentHeight >= stackedMinHeight -> AltTextLayoutMode.DirectionalSingleTopCenter
                    contentHeight >= compactMinHeight -> AltTextLayoutMode.DirectionalSingleTopRight
                    else -> AltTextLayoutMode.Hidden
                }
                AltTextLayoutMode.DirectionalSingleTopRight -> when {
                    contentHeight >= compactMinHeight -> AltTextLayoutMode.DirectionalSingleTopRight
                    else -> AltTextLayoutMode.Hidden
                }
                else -> AltTextLayoutMode.Hidden
            }
        }
        if (altText.text.isNullOrBlank()) return AltTextLayoutMode.Hidden

        val preferred = when (pref) {
            PunctuationPosition.TopRight -> AltTextLayoutMode.TopRight
            PunctuationPosition.TopCenter -> AltTextLayoutMode.TopCenter
            PunctuationPosition.Bottom -> AltTextLayoutMode.Bottom
            PunctuationPosition.None -> AltTextLayoutMode.Hidden
        }
        if (keyHeight <= 0) return preferred

        val contentHeight = keyHeight - vMargin * 2
        val mainHeight = mainText.paint.run { fontMetrics.bottom - fontMetrics.top }
        val altHeight = altText.paint.run { fontMetrics.bottom - fontMetrics.top }
        // Keep a small guard gap but avoid over-conservative fallback to top-right.
        val compactMinHeight = max(mainHeight, altHeight + cornerLabelTopSafeInset)
        val stackedMinHeight = mainHeight + altHeight + dp(1)

        return when (preferred) {
            AltTextLayoutMode.Bottom -> when {
                contentHeight >= stackedMinHeight -> AltTextLayoutMode.Bottom
                contentHeight >= compactMinHeight -> AltTextLayoutMode.TopRight
                else -> AltTextLayoutMode.Hidden
            }
            AltTextLayoutMode.TopCenter -> when {
                contentHeight >= stackedMinHeight -> AltTextLayoutMode.TopCenter
                contentHeight >= compactMinHeight -> AltTextLayoutMode.TopRight
                else -> AltTextLayoutMode.Hidden
            }
            AltTextLayoutMode.TopRight -> when {
                contentHeight >= compactMinHeight -> AltTextLayoutMode.TopRight
                else -> AltTextLayoutMode.Hidden
            }
            AltTextLayoutMode.DirectionalSingleTopRight,
            AltTextLayoutMode.DirectionalSingleTopCenter,
            AltTextLayoutMode.DirectionalSingleBottom,
            AltTextLayoutMode.DirectionalTopBottom,
            AltTextLayoutMode.DirectionalTopRight -> AltTextLayoutMode.Hidden
            AltTextLayoutMode.Hidden -> AltTextLayoutMode.Hidden
        }
    }

    private fun applyLayout(keyHeight: Int = appearanceView.height) {
        val mode = resolveLayoutMode(keyHeight)
        if (mode == lastLayoutMode) return
        lastLayoutMode = mode
        val keepDirectionalLabel = when (mode) {
            AltTextLayoutMode.DirectionalSingleTopRight,
            AltTextLayoutMode.DirectionalSingleTopCenter,
            AltTextLayoutMode.DirectionalSingleBottom,
            AltTextLayoutMode.DirectionalTopBottom,
            AltTextLayoutMode.DirectionalTopRight -> true
            else -> false
        }
        if (!keepDirectionalLabel) resetDirectionalAltText1()
        when (mode) {
            AltTextLayoutMode.Bottom,
            AltTextLayoutMode.DirectionalSingleBottom -> applyBottomAltTextPosition(
                if (mode == AltTextLayoutMode.DirectionalSingleBottom) activeDirectionalLabel() else altText
            )
            AltTextLayoutMode.TopRight,
            AltTextLayoutMode.DirectionalSingleTopRight -> applyTopRightAltTextPosition(
                if (mode == AltTextLayoutMode.DirectionalSingleTopRight) activeDirectionalLabel() else altText
            )
            AltTextLayoutMode.TopCenter,
            AltTextLayoutMode.DirectionalSingleTopCenter -> applyTopCenterAltTextPosition(
                if (mode == AltTextLayoutMode.DirectionalSingleTopCenter) activeDirectionalLabel() else altText
            )
            AltTextLayoutMode.DirectionalTopBottom -> applyDirectionalTopBottomAltTextPosition()
            AltTextLayoutMode.DirectionalTopRight -> applyDirectionalTopRightAltTextPosition()
            AltTextLayoutMode.Hidden -> applyNoAltTextPosition()
        }
    }

    fun refreshAltTextLayout() {
        lastLayoutMode = null
        applyLayout()
    }

    override fun shouldTriggerAltBySwipe(totalY: Int, fallback: SwipeSymbolDirection): Boolean {
        if (totalY == 0) return false
        return when (lastLayoutMode ?: resolveLayoutMode(appearanceView.height)) {
            AltTextLayoutMode.Bottom -> totalY > 0
            AltTextLayoutMode.TopRight, AltTextLayoutMode.TopCenter -> totalY < 0
            AltTextLayoutMode.DirectionalSingleTopRight,
            AltTextLayoutMode.DirectionalSingleTopCenter,
            AltTextLayoutMode.DirectionalSingleBottom -> if (!altText.text.isNullOrBlank()) {
                totalY < 0
            } else {
                totalY > 0
            }
            AltTextLayoutMode.DirectionalTopBottom -> when {
                totalY < 0 -> !altText.text.isNullOrBlank()
                totalY > 0 -> !altText1.text.isNullOrBlank()
                else -> false
            }
            AltTextLayoutMode.DirectionalTopRight -> if (!altText.text.isNullOrBlank()) {
                totalY < 0
            } else {
                totalY > 0
            }
            AltTextLayoutMode.Hidden -> fallback.checkY(totalY)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        lastLayoutMode = null
        applyLayout()
    }

    override fun onAppearanceLayoutChanged(width: Int, height: Int) {
        applyLayout(height)
    }

    override fun updateTheme(newTheme: Theme) {
        super.updateTheme(newTheme)
        altText.setTextColor(
            resolveAltTextColor(
                when (def.variant) {
                    Variant.Normal, Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
                    Variant.Accent -> newTheme.accentKeyTextColor
                }
            )
        )
        altText1.setTextColor(
            resolveAltTextColor(
                when (def.variant) {
                    Variant.Normal, Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
                    Variant.Accent -> newTheme.accentKeyTextColor
                }
            )
        )
        lastLayoutMode = null
        applyLayout()
    }
}

@SuppressLint("ViewConstructor")
class ImageAltTextKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.ImageAltText,
    horizontalGapScale: Float = 1f,
    private val iconSlot: String? = null
) : KeyView(ctx, theme, def, horizontalGapScale), SwipeHintAwareKeyView {
    private enum class AltTextLayoutMode {
        TopRight,
        TopCenter,
        Bottom,
        DirectionalSingleTopRight,
        DirectionalSingleTopCenter,
        DirectionalSingleBottom,
        DirectionalTopBottom,
        DirectionalTopRight,
        Hidden
    }

    /**
     * 副标签基准字号（sp）；同 [AltTextKeyView]，每次 [setTextScale] 重读配置，
     * 不复用构造期快照，这样沿用旧行也能跟上 fontset 的字号改动。
     */
    private fun resolveBaseAltTextSizeSp(): Float =
        org.fcitx.fcitx5.android.input.font.FontProviders.getFontSize(
            "key_alt_font", 10.666667f
        )

    private var currentMainTextScale = 1f
    private var lastLayoutMode: AltTextLayoutMode? = null
    private var iconThemeTintWithTheme: Boolean? = null

    // Reused for measuring main-text height in resolveLayoutMode().
    // Configured lazily and re-used across calls to avoid per-swipe Skia paint allocation.
    private val mainTextMeasurePaint = TextPaint()
    private var cachedMainTextHeight = -1f
    private var cachedAltTextHeight = -1f

    private fun invalidateTextMetricsCache() {
        cachedMainTextHeight = -1f
        cachedAltTextHeight = -1f
    }

    val img = imageView { configure(theme, def.src, def.variant) }.apply {
        imageTintList = ColorStateList.valueOf(
            resolveTextColor(
                when (def.variant) {
                    Variant.Normal -> theme.keyTextColor
                    Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                    Variant.Accent -> theme.accentKeyTextColor
                }
            )
        )
    }

    val altText = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = Gravity.CENTER
        setPadding(hMargin, 0, hMargin, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, resolveBaseAltTextSizeSp())
        fontKey = "key_alt_font"
        setTypeface(typeface, Typeface.BOLD)
        text = def.altText
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        setTextColor(
            resolveAltTextColor(
                when (def.variant) {
                    Variant.Normal, Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                    Variant.Accent -> theme.accentKeyTextColor
                }
            )
        )
    }

    val altText1 = view(::AutoScaleTextView) {
        isClickable = false
        isFocusable = false
        scaleMode = AutoScaleTextView.Mode.Proportional
        gravity = Gravity.CENTER
        setPadding(hMargin, 0, hMargin, 0)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, resolveBaseAltTextSizeSp())
        fontKey = "key_alt_font"
        setTypeface(typeface, Typeface.BOLD)
        text = def.altText1 ?: ""
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG_LTR
        setTextColor(
            resolveAltTextColor(
                when (def.variant) {
                    Variant.Normal, Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                    Variant.Accent -> theme.accentKeyTextColor
                }
            )
        )
    }

    private fun applyTopRightAltTextPadding(label: AutoScaleTextView) {
        // Keep the safe area in the LayoutParams margin so the glyph, not only the
        // TextView's content box, stays clear of the rounded corner.
        label.setPadding(0, 0, 0, 0)
    }

    private fun applyBottomAltTextPadding(label: AutoScaleTextView = altText) {
        label.setPadding(hMargin, 0, hMargin, 0)
    }

    private fun activeDirectionalLabel(): AutoScaleTextView =
        if (!altText.text.isNullOrBlank()) altText else altText1

    private fun resetDirectionalAltText1() {
        altText1.visibility = View.GONE
        altText1.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = unset
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = unset
        }
    }

    init {
        appearanceView.apply {
            add(img, lParams(wrapContent, wrapContent))
            add(altText, lParams(0, wrapContent))
            add(altText1, lParams(0, wrapContent))
        }
        reapplyIconThemeOverride()
        applyLayout()
    }

    fun reapplyIconThemeOverride() {
        if (iconSlot == null) return
        val iconInfo = IconThemeManager.resolveIconDrawableInfo(iconSlot)
        if (iconInfo == null) {
            iconThemeTintWithTheme = null
            applyIconTint(theme)
            return
        }
        iconThemeTintWithTheme = iconInfo.tintWithTheme
        img.setImageDrawable(iconInfo.drawable)
        applyIconTint(theme)
    }

    private fun applyIconTint(currentTheme: Theme) {
        val shouldTint = iconThemeTintWithTheme != false
        if (shouldTint) {
            img.imageTintList = ColorStateList.valueOf(
                resolveTextColor(
                    when (def.variant) {
                        Variant.Normal -> currentTheme.keyTextColor
                        Variant.AltForeground, Variant.Alternative -> currentTheme.altKeyTextColor
                        Variant.Accent -> currentTheme.accentKeyTextColor
                    }
                )
            )
        } else {
            img.imageTintList = null
            img.drawable?.setTintList(null)
        }
    }

    override fun setTextScale(scale: Float) {
        currentMainTextScale = scale
        altText.setTextSize(TypedValue.COMPLEX_UNIT_SP, resolveBaseAltTextSizeSp() * scale)
        altText.requestLayout()
        altText1.setTextSize(TypedValue.COMPLEX_UNIT_SP, resolveBaseAltTextSizeSp() * scale)
        altText1.requestLayout()
        invalidateTextMetricsCache()
        lastLayoutMode = null
        applyLayout()
    }

    private fun applyTopRightAltTextPosition(label: AutoScaleTextView = altText) {
        val hidden = if (label === altText) altText1 else altText
        hidden.visibility = View.GONE
        label.visibility = View.VISIBLE
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = parentId
            topToBottom = unset
            bottomToBottom = parentId
            bottomToTop = unset
            startToStart = parentId
            endToEnd = parentId
            topMargin = 0
            bottomMargin = 0
        }
        label.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = parentId
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = unset
            leftToLeft = parentId
            rightToRight = parentId
            leftMargin = hMargin
            rightMargin = hMargin + cornerLabelHorizontalSafeInset
            topMargin = vMargin + cornerLabelTopSafeInset
            bottomMargin = 0
        }
        applyTopRightAltTextPadding(label)
        label.gravity = Gravity.END or Gravity.CENTER_VERTICAL
    }

    private fun applyTopCenterAltTextPosition(label: AutoScaleTextView = altText) {
        val hidden = if (label === altText) altText1 else altText
        hidden.visibility = View.GONE
        label.visibility = View.VISIBLE
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = parentId
            topToBottom = unset
            bottomToBottom = parentId
            bottomToTop = unset
            startToStart = parentId
            endToEnd = parentId
            topMargin = 0
            bottomMargin = 0
        }
        label.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = parentId
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = unset
            leftToLeft = parentId
            rightToRight = parentId
            leftMargin = hMargin
            rightMargin = hMargin
            topMargin = vMargin + cornerLabelTopSafeInset
            bottomMargin = 0
        }
        applyBottomAltTextPadding(label)
        label.gravity = Gravity.CENTER
    }

    private fun applyBottomAltTextPosition(label: AutoScaleTextView = altText) {
        val hidden = if (label === altText) altText1 else altText
        hidden.visibility = View.GONE
        label.visibility = View.VISIBLE
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (ThemeManager.prefs.moveMainTextForAltLabel.getValue()) {
                topToTop = parentId
                topToBottom = unset
                bottomToBottom = unset
                bottomToTop = label.existingOrNewId
                startToStart = parentId
                endToEnd = parentId
                topMargin = vMargin
                bottomMargin = 0
            } else {
                centerInParent()
                topToTop = parentId
                topToBottom = unset
                bottomToBottom = parentId
                bottomToTop = unset
                startToStart = parentId
                endToEnd = parentId
                topMargin = 0
                bottomMargin = 0
            }
        }
        label.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = unset
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = parentId
            leftToLeft = parentId
            rightToRight = parentId
            leftMargin = hMargin
            rightMargin = hMargin
            topMargin = 0
            bottomMargin = vMargin + dp(2)
        }
        applyBottomAltTextPadding(label)
        label.gravity = Gravity.CENTER
    }

    private fun applyDirectionalTopBottomAltTextPosition() {
        val hasUpLabel = !altText.text.isNullOrBlank()
        val hasDownLabel = !altText1.text.isNullOrBlank()
        val moveMainText = ThemeManager.prefs.moveMainTextForAltLabel.getValue()
        altText.visibility = if (hasUpLabel) View.VISIBLE else View.GONE
        altText1.visibility = if (hasDownLabel) View.VISIBLE else View.GONE
        altText.gravity = Gravity.CENTER
        altText1.gravity = Gravity.CENTER
        altText.setPadding(hMargin, 0, hMargin, 0)
        altText1.setPadding(hMargin, 0, hMargin, 0)

        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = parentId
            bottomToTop = if (moveMainText && hasUpLabel) img.existingOrNewId else unset
            bottomToBottom = if (moveMainText && !hasUpLabel) parentId else unset
            leftToLeft = parentId
            rightToRight = parentId
            topMargin = vMargin
            bottomMargin = 0
        }
        altText1.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = if (moveMainText && !hasDownLabel) parentId else unset
            topToBottom = if (moveMainText && hasDownLabel) img.existingOrNewId else unset
            bottomToBottom = parentId
            leftToLeft = parentId
            rightToRight = parentId
            topMargin = 0
            bottomMargin = vMargin + dp(2)
        }
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            if (moveMainText) {
                topToTop = if (hasUpLabel) unset else parentId
                topToBottom = if (hasUpLabel) altText.existingOrNewId else unset
                bottomToBottom = if (hasDownLabel) unset else parentId
                bottomToTop = if (hasDownLabel) altText1.existingOrNewId else unset
                topMargin = 0
                bottomMargin = 0
            } else {
                centerInParent()
                topToTop = parentId
                topToBottom = unset
                bottomToBottom = parentId
                bottomToTop = unset
                topMargin = 0
                bottomMargin = 0
            }
        }
    }

    private fun applyDirectionalTopRightAltTextPosition() {
        applyTopRightAltTextPosition(activeDirectionalLabel())
    }

    private fun applyNoAltTextPosition() {
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            topToTop = parentId
            bottomToBottom = parentId
            startToStart = parentId
            endToEnd = parentId
            topMargin = 0
            bottomMargin = 0
            bottomToTop = unset
        }
        altText.visibility = View.GONE
        altText1.visibility = View.GONE
        applyBottomAltTextPadding()
        altText.gravity = Gravity.CENTER
    }

    private fun resolveLayoutMode(keyHeight: Int): AltTextLayoutMode {
        val pref = punctuationPositionForKey()
        if (def.directionalSwipeLabels) {
            if (pref == PunctuationPosition.None) return AltTextLayoutMode.Hidden
            val hasUpLabel = !altText.text.isNullOrBlank()
            val hasDownLabel = !altText1.text.isNullOrBlank()
            if (!hasUpLabel && !hasDownLabel) return AltTextLayoutMode.Hidden

            val contentHeight = keyHeight - vMargin * 2
            val measuredIconHeight = img.measuredHeight.takeIf { it > 0 } ?: 0
            val drawableIconHeight = img.drawable?.intrinsicHeight?.takeIf { it > 0 } ?: dp(24)
            val iconHeight = max(measuredIconHeight, drawableIconHeight).toFloat()
            val mainHeight = cachedMainTextHeight.takeIf { it >= 0f } ?: run {
                val mainTextSizeSp = org.fcitx.fcitx5.android.input.font.FontProviders.getFontSize(
                    "key_main_font",
                    23f
                ) * currentMainTextScale
                mainTextMeasurePaint.textSize = TypedValue.applyDimension(
                    TypedValue.COMPLEX_UNIT_SP,
                    mainTextSizeSp,
                    resources.displayMetrics
                )
                mainTextMeasurePaint.typeface = org.fcitx.fcitx5.android.input.font.FontProviders
                    .resolveTypeface("key_main_font", Typeface.DEFAULT)
                val h = mainTextMeasurePaint.run { fontMetrics.bottom - fontMetrics.top }
                cachedMainTextHeight = h
                h
            }

            if (hasUpLabel && hasDownLabel) {
                if (keyHeight > 0) {
                    val upHeight = altText.paint.run { fontMetrics.bottom - fontMetrics.top }
                    val downHeight = altText1.paint.run { fontMetrics.bottom - fontMetrics.top }
                    // Use the legacy single-label capacity as the fallback boundary. A
                    // normal row can keep both labels unless it cannot even fit the main
                    // content plus one label; only then collapse to the upper-right label.
                    val singleLabelMinHeight = max(iconHeight, mainHeight) + max(upHeight, downHeight) + dp(1)
                    if (contentHeight < singleLabelMinHeight) return AltTextLayoutMode.DirectionalTopRight
                }
                return AltTextLayoutMode.DirectionalTopBottom
            }

            // A single directional label uses the same geometry as AlphabetKey. The
            // label keeps its physical swipe direction, while its visual position and
            // compact-height fallback follow the theme preference.
            val preferred = when (pref) {
                PunctuationPosition.TopRight -> AltTextLayoutMode.DirectionalSingleTopRight
                PunctuationPosition.TopCenter -> AltTextLayoutMode.DirectionalSingleTopCenter
                PunctuationPosition.Bottom -> AltTextLayoutMode.DirectionalSingleBottom
                PunctuationPosition.None -> AltTextLayoutMode.Hidden
            }
            if (keyHeight <= 0) return preferred

            val normalizedMainHeight = max(iconHeight, mainHeight)
            val altHeight = activeDirectionalLabel().paint.run { fontMetrics.bottom - fontMetrics.top }
            // These are the old pre-direction-label thresholds.
            val compactMinHeight = max(normalizedMainHeight, altHeight + cornerLabelTopSafeInset)
            val stackedMinHeight = normalizedMainHeight + altHeight + dp(1)

            return when (preferred) {
                AltTextLayoutMode.DirectionalSingleBottom -> when {
                    contentHeight >= stackedMinHeight -> AltTextLayoutMode.DirectionalSingleBottom
                    contentHeight >= compactMinHeight -> AltTextLayoutMode.DirectionalSingleTopRight
                    else -> AltTextLayoutMode.Hidden
                }
                AltTextLayoutMode.DirectionalSingleTopCenter -> when {
                    contentHeight >= stackedMinHeight -> AltTextLayoutMode.DirectionalSingleTopCenter
                    contentHeight >= compactMinHeight -> AltTextLayoutMode.DirectionalSingleTopRight
                    else -> AltTextLayoutMode.Hidden
                }
                AltTextLayoutMode.DirectionalSingleTopRight -> when {
                    contentHeight >= compactMinHeight -> AltTextLayoutMode.DirectionalSingleTopRight
                    else -> AltTextLayoutMode.Hidden
                }
                else -> AltTextLayoutMode.Hidden
            }
        }
        if (altText.text.isNullOrBlank()) return AltTextLayoutMode.Hidden

        val preferred = when (pref) {
            PunctuationPosition.TopRight -> AltTextLayoutMode.TopRight
            PunctuationPosition.TopCenter -> AltTextLayoutMode.TopCenter
            PunctuationPosition.Bottom -> AltTextLayoutMode.Bottom
            PunctuationPosition.None -> AltTextLayoutMode.Hidden
        }
        if (keyHeight <= 0) return preferred

        val contentHeight = keyHeight - vMargin * 2
        val measuredIconHeight = img.measuredHeight.takeIf { it > 0 } ?: 0
        val drawableIconHeight = img.drawable?.intrinsicHeight?.takeIf { it > 0 } ?: dp(24)
        val iconHeight = max(measuredIconHeight, drawableIconHeight).toFloat()
        val mainHeight = cachedMainTextHeight.takeIf { it >= 0f } ?: run {
            val mainTextSizeSp = org.fcitx.fcitx5.android.input.font.FontProviders.getFontSize(
                "key_main_font",
                23f
            ) * currentMainTextScale
            mainTextMeasurePaint.textSize = TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_SP,
                mainTextSizeSp,
                resources.displayMetrics
            )
            mainTextMeasurePaint.typeface = org.fcitx.fcitx5.android.input.font.FontProviders
                .resolveTypeface("key_main_font", Typeface.DEFAULT)
            val h = mainTextMeasurePaint.run { fontMetrics.bottom - fontMetrics.top }
            cachedMainTextHeight = h
            h
        }
        val altHeight = cachedAltTextHeight.takeIf { it >= 0f } ?: run {
            val h = altText.paint.run { fontMetrics.bottom - fontMetrics.top }
            cachedAltTextHeight = h
            h
        }
        val normalizedMainHeight = max(iconHeight, mainHeight)
        val compactMinHeight = max(normalizedMainHeight, altHeight + cornerLabelTopSafeInset)
        val stackedMinHeight = normalizedMainHeight + altHeight + dp(1)

        return when (preferred) {
            AltTextLayoutMode.Bottom -> when {
                contentHeight >= stackedMinHeight -> AltTextLayoutMode.Bottom
                contentHeight >= compactMinHeight -> AltTextLayoutMode.TopRight
                else -> AltTextLayoutMode.Hidden
            }
            AltTextLayoutMode.TopCenter -> when {
                contentHeight >= stackedMinHeight -> AltTextLayoutMode.TopCenter
                contentHeight >= compactMinHeight -> AltTextLayoutMode.TopRight
                else -> AltTextLayoutMode.Hidden
            }
            AltTextLayoutMode.TopRight -> when {
                contentHeight >= compactMinHeight -> AltTextLayoutMode.TopRight
                else -> AltTextLayoutMode.Hidden
            }
            AltTextLayoutMode.DirectionalSingleTopRight,
            AltTextLayoutMode.DirectionalSingleTopCenter,
            AltTextLayoutMode.DirectionalSingleBottom,
            AltTextLayoutMode.DirectionalTopBottom,
            AltTextLayoutMode.DirectionalTopRight -> AltTextLayoutMode.Hidden
            AltTextLayoutMode.Hidden -> AltTextLayoutMode.Hidden
        }
    }

    private fun applyLayout(keyHeight: Int = appearanceView.height) {
        val mode = resolveLayoutMode(keyHeight)
        if (mode == lastLayoutMode) return
        lastLayoutMode = mode
        val keepDirectionalLabel = when (mode) {
            AltTextLayoutMode.DirectionalSingleTopRight,
            AltTextLayoutMode.DirectionalSingleTopCenter,
            AltTextLayoutMode.DirectionalSingleBottom,
            AltTextLayoutMode.DirectionalTopBottom,
            AltTextLayoutMode.DirectionalTopRight -> true
            else -> false
        }
        if (!keepDirectionalLabel) resetDirectionalAltText1()
        when (mode) {
            AltTextLayoutMode.Bottom,
            AltTextLayoutMode.DirectionalSingleBottom -> applyBottomAltTextPosition(
                if (mode == AltTextLayoutMode.DirectionalSingleBottom) activeDirectionalLabel() else altText
            )
            AltTextLayoutMode.TopRight,
            AltTextLayoutMode.DirectionalSingleTopRight -> applyTopRightAltTextPosition(
                if (mode == AltTextLayoutMode.DirectionalSingleTopRight) activeDirectionalLabel() else altText
            )
            AltTextLayoutMode.TopCenter,
            AltTextLayoutMode.DirectionalSingleTopCenter -> applyTopCenterAltTextPosition(
                if (mode == AltTextLayoutMode.DirectionalSingleTopCenter) activeDirectionalLabel() else altText
            )
            AltTextLayoutMode.DirectionalTopBottom -> applyDirectionalTopBottomAltTextPosition()
            AltTextLayoutMode.DirectionalTopRight -> applyDirectionalTopRightAltTextPosition()
            AltTextLayoutMode.Hidden -> applyNoAltTextPosition()
        }
    }

    fun refreshAltTextLayout() {
        lastLayoutMode = null
        applyLayout()
    }

    override fun shouldTriggerAltBySwipe(totalY: Int, fallback: SwipeSymbolDirection): Boolean {
        if (totalY == 0) return false
        return when (lastLayoutMode ?: resolveLayoutMode(appearanceView.height)) {
            AltTextLayoutMode.Bottom -> totalY > 0
            AltTextLayoutMode.TopRight, AltTextLayoutMode.TopCenter -> totalY < 0
            AltTextLayoutMode.DirectionalSingleTopRight,
            AltTextLayoutMode.DirectionalSingleTopCenter,
            AltTextLayoutMode.DirectionalSingleBottom -> if (!altText.text.isNullOrBlank()) {
                totalY < 0
            } else {
                totalY > 0
            }
            AltTextLayoutMode.DirectionalTopBottom -> when {
                totalY < 0 -> !altText.text.isNullOrBlank()
                totalY > 0 -> !altText1.text.isNullOrBlank()
                else -> false
            }
            AltTextLayoutMode.DirectionalTopRight -> if (!altText.text.isNullOrBlank()) {
                totalY < 0
            } else {
                totalY > 0
            }
            AltTextLayoutMode.Hidden -> fallback.checkY(totalY)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        invalidateTextMetricsCache()
        lastLayoutMode = null
        applyLayout()
    }

    override fun onAppearanceLayoutChanged(width: Int, height: Int) {
        applyLayout(height)
    }

    override fun updateTheme(newTheme: Theme) {
        super.updateTheme(newTheme)
        applyIconTint(newTheme)
        altText.setTextColor(
            resolveAltTextColor(
                when (def.variant) {
                    Variant.Normal, Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
                    Variant.Accent -> newTheme.accentKeyTextColor
                }
            )
        )
        altText1.setTextColor(
            resolveAltTextColor(
                when (def.variant) {
                    Variant.Normal, Variant.AltForeground, Variant.Alternative -> newTheme.altKeyTextColor
                    Variant.Accent -> newTheme.accentKeyTextColor
                }
            )
        )
        reapplyIconThemeOverride()
        lastLayoutMode = null
        applyLayout()
    }
}

@SuppressLint("ViewConstructor")
class ImageKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.Image,
    horizontalGapScale: Float = 1f,
    private val iconSlot: String? = null
) :
    KeyView(ctx, theme, def, horizontalGapScale) {
    private var iconThemeTintWithTheme: Boolean? = null
    val img = imageView { configure(theme, def.src, def.variant) }.apply {
        imageTintList = ColorStateList.valueOf(
            resolveTextColor(
                when (def.variant) {
                    Variant.Normal -> theme.keyTextColor
                    Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                    Variant.Accent -> theme.accentKeyTextColor
                }
            )
        )
    }

    init {
        appearanceView.apply {
            add(img, lParams(wrapContent, wrapContent) {
                centerInParent()
            })
        }
        reapplyIconThemeOverride()
    }

    fun reapplyIconThemeOverride() {
        if (iconSlot == null) return
        val iconInfo = IconThemeManager.resolveIconDrawableInfo(iconSlot)
        if (iconInfo == null) {
            iconThemeTintWithTheme = null
            applyIconTint(theme)
            return
        }
        iconThemeTintWithTheme = iconInfo.tintWithTheme
        img.setImageDrawable(iconInfo.drawable)
        applyIconTint(theme)
    }

    private fun applyIconTint(currentTheme: Theme) {
        val shouldTint = iconThemeTintWithTheme != false
        if (shouldTint) {
            img.imageTintList = ColorStateList.valueOf(
                resolveTextColor(
                    when (def.variant) {
                        Variant.Normal -> currentTheme.keyTextColor
                        Variant.AltForeground, Variant.Alternative -> currentTheme.altKeyTextColor
                        Variant.Accent -> currentTheme.accentKeyTextColor
                    }
                )
            )
        } else {
            img.imageTintList = null
            img.drawable?.setTintList(null)
        }
    }

    override fun updateTheme(newTheme: Theme) {
        super.updateTheme(newTheme)
        applyIconTint(newTheme)
        reapplyIconThemeOverride()
    }
}

private fun ImageView.configure(theme: Theme, @DrawableRes src: Int, variant: Variant) = apply {
    isClickable = false
    isFocusable = false
    imageTintList = ColorStateList.valueOf(
        when (variant) {
            Variant.Normal -> theme.keyTextColor
            Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
            Variant.Accent -> theme.accentKeyTextColor
        }
    )
    imageResource = src
}

@SuppressLint("ViewConstructor")
class ImageTextKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.ImageText,
    horizontalGapScale: Float = 1f,
    private val iconSlot: String? = null
) :
    TextKeyView(ctx, theme, def, horizontalGapScale) {
    private var iconThemeTintWithTheme: Boolean? = null
    val img = imageView {
        configure(theme, def.src, def.variant)
        imageTintList = ColorStateList.valueOf(
            resolveTextColor(
                when (def.variant) {
                    Variant.Normal -> theme.keyTextColor
                    Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
                    Variant.Accent -> theme.accentKeyTextColor
                }
            )
        )
    }

    init {
        appearanceView.apply {
            add(img, lParams(dp(13), dp(13)))
        }
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerHorizontally()
            bottomToBottom = parentId
            bottomMargin = vMargin + dp(4)
            topToTop = unset
        }
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerHorizontally()
            topToTop = parentId
        }
        reapplyIconThemeOverride()
        updateMargins(resources.configuration.orientation)
    }

    fun reapplyIconThemeOverride() {
        if (iconSlot == null) return
        val iconInfo = IconThemeManager.resolveIconDrawableInfo(iconSlot)
        if (iconInfo == null) {
            iconThemeTintWithTheme = null
            applyIconTint(theme)
            return
        }
        iconThemeTintWithTheme = iconInfo.tintWithTheme
        img.setImageDrawable(iconInfo.drawable)
        applyIconTint(theme)
    }

    private fun applyIconTint(currentTheme: Theme) {
        val shouldTint = iconThemeTintWithTheme != false
        if (shouldTint) {
            img.imageTintList = ColorStateList.valueOf(
                resolveTextColor(
                    when (def.variant) {
                        Variant.Normal -> currentTheme.keyTextColor
                        Variant.AltForeground, Variant.Alternative -> currentTheme.altKeyTextColor
                        Variant.Accent -> currentTheme.accentKeyTextColor
                    }
                )
            )
        } else {
            img.imageTintList = null
            img.drawable?.setTintList(null)
        }
    }

    private fun updateMargins(orientation: Int) {
        when (orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> {
                mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    bottomMargin = vMargin + dp(2)
                }
                img.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    topMargin = vMargin + dp(4)
                }
            }
            else -> {
                mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    bottomMargin = vMargin + dp(4)
                }
                img.updateLayoutParams<ConstraintLayout.LayoutParams> {
                    topMargin = vMargin + dp(8)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        updateMargins(newConfig.orientation)
    }

    override fun updateTheme(newTheme: Theme) {
        super.updateTheme(newTheme)
        applyIconTint(newTheme)
        reapplyIconThemeOverride()
    }
}
