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
import org.fcitx.fcitx5.android.data.theme.ThemePrefs.AltLabelSide
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

    /**
     * 构造时绑定的主题图标槽位，由持有图层的子类覆盖。
     *
     * 合成态可能换成 `composeOverride` 里的键定义，槽位跟着变；[BaseKeyboard] 在合成态
     * 切换时比对它，不一致就重建视图，否则键面会继续显示基础键的图标。
     */
    open val themedIconSlot: String? get() = null

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
    internal var statisticsKeyLabel: String? = null
    internal var onStatisticsPress: ((KeyView) -> Unit)? = null
    private var statisticsTouchActive = false

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

    private fun resolveColorOverride(staticColor: Int?, colorRef: String?, theme: Theme = this.theme): Int? {
        return LayoutKeyColorResolver.resolve(context, theme, staticColor, colorRef)
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

    /** Return true when the measured key size changes its child constraints. */
    protected open fun onAppearanceMeasured(width: Int, height: Int): Boolean = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (onAppearanceMeasured(appearanceView.measuredWidth, appearanceView.measuredHeight)) {
            // Solve the new label constraints before laying out children. Updating them after
            // super.onLayout can leave old bounds on screen until an unrelated layer switch.
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
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
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (isEnabled && !statisticsTouchActive) {
                statisticsTouchActive = true
                onStatisticsPress?.invoke(this)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> statisticsTouchActive = false
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN && rippled) {
            onWaterRippleRequest?.invoke(this, event.x, event.y)
        }
        return super.onTouchEvent(event)
    }

    override fun onConfirmedOverlappingTap(x: Float, y: Float) {
        onStatisticsPress?.invoke(this)
        if (rippled) onWaterRippleRequest?.invoke(this, x, y)
        super.onConfirmedOverlappingTap(x, y)
    }

    override fun onDetachedFromWindow() {
        statisticsTouchActive = false
        super.onDetachedFromWindow()
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
     * 空格键的自定义标签（布局里的 `SpaceKey.label`）。
     *
     * 非空时由 [org.fcitx.fcitx5.android.input.keyboard.TextKeyboard.updateSpaceLabel]
     * 直接显示这段文字；为空时仍按当前方案名与 `spaceKeyLabelMode` 计算。之所以放在
     * 视图上而不是只改 `displayText`：默认文案要随方案/中英状态实时刷新，两者不能
     * 用同一个字段互相覆盖。
     */
    var spaceLabelOverride: String? = null

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
    horizontalGapScale: Float = 1f,
    /**
     * 主题图标槽位（字母键是 `keys.letter.<小写字母>`）。
     *
     * 非空且主题里配了该槽位时，图标作为键面**底层**：主字符隐藏，副标签仍按原有布局
     * 叠在图标上层（叠加，而不是各占一半位置）。主题没配该槽位时整层 GONE，键面与
     * 以前完全一致。
     */
    private val iconSlot: String? = null
) :
    TextKeyView(ctx, theme, def, horizontalGapScale), SwipeHintAwareKeyView {
    override val themedIconSlot: String? get() = iconSlot
    private enum class AltTextLayoutMode {
        TopRight,
        TopCenter,
        Bottom,

        /**
         * 非方向性标签（字母键的标点副标签）在主题里开启「副标签显示在侧边」后，
         * 贴到所选侧（左/右）的角落。滑动方向不变：顶部标签仍上滑、底部标签仍下滑。
         */
        TopLeft,
        BottomLeft,
        BottomRight,

        /**
         * 方向性标签：位置直接由标签的方向决定（上标签在上、下标签在下），
         * **不读**主题的「标点位置」。键高不够时对应退到同侧的角落版本；
         * 主题里开启「副标签显示在侧边」后，固定放在所选侧（左/右）的角落。
         */
        DirectionalUpTopCenter,
        DirectionalUpTopRight,
        DirectionalUpTopLeft,
        DirectionalDownBottom,
        DirectionalDownBottomRight,
        DirectionalDownBottomLeft,
        DirectionalTopBottom,
        DirectionalCorners,
        DirectionalCornersLeft,
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

    /**
     * 手势方向用的布局：与副标签是否显示无关。
     *
     * 关掉副标签只是不画，键上仍然有上/下滑标签；如果手势也按 Hidden 走，就会退回
     * [SwipeSymbolDirection] 的默认方向，同一个键的可用滑动方向被主题设置改掉。
     */
    private var lastGestureLayoutMode: AltTextLayoutMode? = null

    /** 上次布局时“副标签存在时移动主字符”的取值；开关变了要重排图标层。 */
    private var lastMoveMainTextForAltLabel: Boolean? = null

    /**
     * 键面图标层。插在 [appearanceView] 的 index 0，也就是主字符下面：
     * 先画图标、再画副标签，文字自然叠在图标之上。
     */
    val img = imageView {
        isClickable = false
        isFocusable = false
        scaleType = ImageView.ScaleType.FIT_CENTER
        visibility = View.GONE
    }

    /** null = 还没解析过图标；false = SVG 自带配色，不跟随主题文字色。 */
    private var iconThemeTintWithTheme: Boolean? = null

    /** 图标层边长（px，8px 分桶）；0 表示还没测到键面尺寸。 */
    private var iconSizePx = 0

    /** 多色图标当前用的笔画色（键面文字色）；主题换了要按新颜色重新光栅化。 */
    private var iconInkColor: Int? = null

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

    private fun applyCornerAltTextPadding(label: AutoScaleTextView) {
        // Keep the safe area in the LayoutParams margin so the glyph, not only the
        // TextView's content box, stays clear of the rounded corner.
        label.setPadding(0, 0, 0, 0)
    }

    private fun applyBottomAltTextPadding(label: AutoScaleTextView = altText) {
        label.setPadding(hMargin, 0, hMargin, 0)
    }

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
            // 图标在最底层：TextKeyView 已经把 mainText 加进来了，这里插到 index 0。
            addView(img, 0, lParams(0, 0) { centerInParent() })
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
        lastGestureLayoutMode = null
        applyLayout()
    }

    /**
     * 把副标签贴到键的某个角落（上/下 × 左/右），主字符保持居中、标签不占额外高度。
     * 「键高不够时自动退到角落」与「主题里手动指定侧边」共用这里，只是角落参数不同。
     */
    private fun applyCornerAltTextPosition(
        label: AutoScaleTextView,
        atTop: Boolean,
        atLeft: Boolean,
        hideSibling: Boolean = true
    ) {
        if (hideSibling) {
            val hidden = if (label === altText) altText1 else altText
            hidden.visibility = View.GONE
        }
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
            topToTop = if (atTop) parentId else unset
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = if (atTop) unset else parentId
            leftToLeft = parentId
            rightToRight = parentId
            leftMargin = if (atLeft) hMargin + cornerLabelHorizontalSafeInset else hMargin
            rightMargin = if (atLeft) hMargin else hMargin + cornerLabelHorizontalSafeInset
            topMargin = if (atTop) vMargin + cornerLabelTopSafeInset else 0
            bottomMargin = if (atTop) 0 else vMargin + dp(2)
        }
        applyCornerAltTextPadding(label)
        // 设置里的左右是**物理**左右，边距也是按 left/right 写的，所以这里必须用
        // LEFT/RIGHT：Gravity.START/END 在 RTL 布局下会解析成相反方向，标签会跑到另一边。
        label.gravity = (if (atLeft) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
    }

    private fun applyTopRightAltTextPosition(
        label: AutoScaleTextView = altText,
        hideSibling: Boolean = true
    ) = applyCornerAltTextPosition(label, atTop = true, atLeft = false, hideSibling = hideSibling)

    private fun applyTopLeftAltTextPosition(
        label: AutoScaleTextView = altText,
        hideSibling: Boolean = true
    ) = applyCornerAltTextPosition(label, atTop = true, atLeft = true, hideSibling = hideSibling)

    private fun applyBottomRightAltTextPosition(
        label: AutoScaleTextView,
        hideSibling: Boolean = true
    ) = applyCornerAltTextPosition(label, atTop = false, atLeft = false, hideSibling = hideSibling)

    private fun applyBottomLeftAltTextPosition(
        label: AutoScaleTextView,
        hideSibling: Boolean = true
    ) = applyCornerAltTextPosition(label, atTop = false, atLeft = true, hideSibling = hideSibling)

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

    /**
     * 图标层与主字符共用同一条“有副标签就上移”的规则。
     *
     * 只有把主字符顶到标签上方的那两种底部布局才跟着上移：[AltTextLayoutMode.Bottom]
     * 的标签在 [altText]，方向性下滑标签在 [altText1]；角落标签压在键角上，主字符保持
     * 居中，图标也不动。每次布局都重设上下约束，避免从底部模式切到其它模式时留下旧锚点。
     */
    private fun applyIconLayerPosition(mode: AltTextLayoutMode) {
        if (iconSlot == null) return
        val bottomLabel = when (mode) {
            AltTextLayoutMode.Bottom -> altText
            AltTextLayoutMode.DirectionalDownBottom -> altText1
            else -> null
        }
        val move = bottomLabel != null &&
            ThemeManager.prefs.moveMainTextForAltLabel.getValue()
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            startToStart = parentId
            endToEnd = parentId
            if (move) {
                topToTop = parentId
                topToBottom = unset
                bottomToBottom = unset
                bottomToTop = bottomLabel!!.existingOrNewId
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
    }

    private fun applyDirectionalTopBottomAltTextPosition() {
        val hasUpLabel = !altText.text.isNullOrBlank()
        val hasDownLabel = !altText1.text.isNullOrBlank()
        // Two directional labels keep the main content centered regardless of the movement
        // preference. Anchoring each label to its own edge avoids an overflowing three-row chain.
        altText.visibility = if (hasUpLabel) View.VISIBLE else View.GONE
        altText1.visibility = if (hasDownLabel) View.VISIBLE else View.GONE
        altText.gravity = Gravity.CENTER
        altText1.gravity = Gravity.CENTER
        altText.setPadding(hMargin, 0, hMargin, 0)
        altText1.setPadding(hMargin, 0, hMargin, 0)

        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = parentId
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = unset
            leftToLeft = parentId
            rightToRight = parentId
            leftMargin = 0
            rightMargin = 0
            topMargin = vMargin
            bottomMargin = 0
        }
        altText1.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = unset
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = parentId
            leftToLeft = parentId
            rightToRight = parentId
            leftMargin = 0
            rightMargin = 0
            topMargin = 0
            bottomMargin = vMargin + dp(2)
        }
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerInParent()
            topToTop = parentId
            topToBottom = unset
            bottomToBottom = parentId
            bottomToTop = unset
            topMargin = 0
            bottomMargin = 0
        }
    }

    private fun applyDirectionalCornersAltTextPosition(atLeft: Boolean = false) {
        val hasUpLabel = !altText.text.isNullOrBlank()
        val hasDownLabel = !altText1.text.isNullOrBlank()
        mainText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerInParent()
            topToTop = parentId
            topToBottom = unset
            bottomToBottom = parentId
            bottomToTop = unset
            topMargin = 0
            bottomMargin = 0
        }
        // hideSibling = false：两个标签同时显示，各自占一个角；单个标签模式才需要
        // 隐藏另一个（那由 applyLayout 的 resetDirectionalAltText1 收尾）。
        altText.visibility = if (hasUpLabel) View.VISIBLE else View.GONE
        altText1.visibility = if (hasDownLabel) View.VISIBLE else View.GONE
        if (hasUpLabel) {
            applyCornerAltTextPosition(altText, atTop = true, atLeft = atLeft, hideSibling = false)
        }
        if (hasDownLabel) {
            applyCornerAltTextPosition(altText1, atTop = false, atLeft = atLeft, hideSibling = false)
        }
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

    /**
     * 副标签的几何布局：只看标签内容与键高，**不看**「显示按键副标签」这个开关。
     *
     * 手势方向必须基于这个结果：开关关掉时标签不画，但键上的上/下滑标签仍然存在，
     * 方向不能跟着一起消失（见 [shouldTriggerAltBySwipe]）。
     */
    private fun resolveGestureLayoutMode(keyHeight: Int): AltTextLayoutMode {
        val pref = punctuationPositionForKey()
        if (def.directionalSwipeLabels) {
            // 方向性标签（上滑/下滑标签）的位置只由标签自己的方向决定：上滑标签在上面、
            // 下滑标签在下面。**刻意不读主题的「标点位置」**——那个设置是给字母键的标点
            // 副标签用的；一旦让方向性标签跟随它，用户换个主题就会把按键的划动方向与
            // 标签位置整体翻转（下滑键被画成上滑、动作也跟着变），这是要修掉的 bug。
            val hasUpLabel = !altText.text.isNullOrBlank()
            val hasDownLabel = !altText1.text.isNullOrBlank()
            if (!hasUpLabel && !hasDownLabel) return AltTextLayoutMode.Hidden

            // 主题里手动开启「副标签显示在侧边」后，位置只由该设置决定，不再看键高：
            // 上标签贴所选侧的顶角、下标签贴同侧的底角。
            if (ThemeManager.prefs.altLabelOnSide.getValue()) {
                val atLeft = ThemeManager.prefs.altLabelSide.getValue() == AltLabelSide.Left
                return when {
                    hasUpLabel && hasDownLabel ->
                        if (atLeft) AltTextLayoutMode.DirectionalCornersLeft
                        else AltTextLayoutMode.DirectionalCorners
                    hasUpLabel ->
                        if (atLeft) AltTextLayoutMode.DirectionalUpTopLeft
                        else AltTextLayoutMode.DirectionalUpTopRight
                    else ->
                        if (atLeft) AltTextLayoutMode.DirectionalDownBottomLeft
                        else AltTextLayoutMode.DirectionalDownBottomRight
                }
            }

            if (keyHeight <= 0) {
                return when {
                    hasUpLabel && hasDownLabel -> AltTextLayoutMode.DirectionalTopBottom
                    hasUpLabel -> AltTextLayoutMode.DirectionalUpTopCenter
                    else -> AltTextLayoutMode.DirectionalDownBottom
                }
            }

            val contentHeight = keyHeight - vMargin * 2
            val mainHeight = mainText.paint.run { fontMetrics.bottom - fontMetrics.top }
            val upHeight = altText.paint.run { fontMetrics.bottom - fontMetrics.top }
            val downHeight = altText1.paint.run { fontMetrics.bottom - fontMetrics.top }
            val labelHeight = max(upHeight, downHeight)
            // 角落标签压在键的角落上，不占额外的高度，只要键本身放得下它。
            val cornerMinHeight = max(mainHeight, labelHeight + cornerLabelTopSafeInset)

            if (hasUpLabel && hasDownLabel) {
                // Both labels are anchored independently; movement never changes their layout.
                val stackedMinHeight = mainHeight + labelHeight + dp(1)
                return when {
                    contentHeight >= stackedMinHeight -> AltTextLayoutMode.DirectionalTopBottom
                    contentHeight >= cornerMinHeight -> AltTextLayoutMode.DirectionalCorners
                    else -> AltTextLayoutMode.Hidden
                }
            }

            val stackedMinHeight = mainHeight + labelHeight + dp(1)
            return if (hasUpLabel) {
                when {
                    contentHeight >= stackedMinHeight -> AltTextLayoutMode.DirectionalUpTopCenter
                    contentHeight >= cornerMinHeight -> AltTextLayoutMode.DirectionalUpTopRight
                    else -> AltTextLayoutMode.Hidden
                }
            } else {
                when {
                    contentHeight >= stackedMinHeight -> AltTextLayoutMode.DirectionalDownBottom
                    contentHeight >= cornerMinHeight -> AltTextLayoutMode.DirectionalDownBottomRight
                    else -> AltTextLayoutMode.Hidden
                }
            }
        }
        if (altText.text.isNullOrBlank()) return AltTextLayoutMode.Hidden

        val preferred = when (pref) {
            PunctuationPosition.TopRight -> AltTextLayoutMode.TopRight
            PunctuationPosition.TopCenter -> AltTextLayoutMode.TopCenter
            PunctuationPosition.Bottom -> AltTextLayoutMode.Bottom
            PunctuationPosition.None -> AltTextLayoutMode.Hidden
        }
        // 主题里手动开启「副标签显示在侧边」后，字母键的标点副标签也跟随所选侧：
        // 上滑标签贴所选侧的顶角、下滑标签贴同侧的底角。位置只由该设置决定，不再看键高，
        // 与方向性标签分支保持一致；滑动方向不变（顶部标签仍上滑、底部标签仍下滑）。
        if (ThemeManager.prefs.altLabelOnSide.getValue()) {
            val atLeft = ThemeManager.prefs.altLabelSide.getValue() == AltLabelSide.Left
            return when (preferred) {
                AltTextLayoutMode.TopRight, AltTextLayoutMode.TopCenter ->
                    if (atLeft) AltTextLayoutMode.TopLeft else AltTextLayoutMode.TopRight
                AltTextLayoutMode.Bottom ->
                    if (atLeft) AltTextLayoutMode.BottomLeft else AltTextLayoutMode.BottomRight
                else -> AltTextLayoutMode.Hidden
            }
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
            else -> AltTextLayoutMode.Hidden
        }
    }

    /** 副标签的绘制布局：开关关掉时直接 Hidden，不占位、不重新测量，键面只剩图标或主字符。 */
    private fun resolveLayoutMode(keyHeight: Int): AltTextLayoutMode =
        if (ThemeManager.prefs.showKeyAltLabels.getValue()) resolveGestureLayoutMode(keyHeight)
        else AltTextLayoutMode.Hidden

    private fun applyLayout(keyHeight: Int = appearanceView.height): Boolean {
        val iconLayerChanged = updateIconLayerSize(height = keyHeight)
        val gestureMode = resolveGestureLayoutMode(keyHeight)
        val mode = if (ThemeManager.prefs.showKeyAltLabels.getValue()) gestureMode
        else AltTextLayoutMode.Hidden
        val moveMainText = ThemeManager.prefs.moveMainTextForAltLabel.getValue()
        if (mode == lastLayoutMode && gestureMode == lastGestureLayoutMode &&
            moveMainText == lastMoveMainTextForAltLabel
        ) return iconLayerChanged
        lastLayoutMode = mode
        lastGestureLayoutMode = gestureMode
        lastMoveMainTextForAltLabel = moveMainText
        val keepDirectionalLabel = when (mode) {
            AltTextLayoutMode.DirectionalUpTopRight,
            AltTextLayoutMode.DirectionalUpTopLeft,
            AltTextLayoutMode.DirectionalUpTopCenter,
            AltTextLayoutMode.DirectionalDownBottom,
            AltTextLayoutMode.DirectionalDownBottomRight,
            AltTextLayoutMode.DirectionalDownBottomLeft,
            AltTextLayoutMode.DirectionalTopBottom,
            AltTextLayoutMode.DirectionalCorners,
            AltTextLayoutMode.DirectionalCornersLeft -> true
            else -> false
        }
        if (!keepDirectionalLabel) resetDirectionalAltText1()
        when (mode) {
            AltTextLayoutMode.Bottom -> applyBottomAltTextPosition()
            AltTextLayoutMode.TopRight -> applyTopRightAltTextPosition()
            AltTextLayoutMode.TopCenter -> applyTopCenterAltTextPosition()
            AltTextLayoutMode.TopLeft -> applyTopLeftAltTextPosition()
            AltTextLayoutMode.BottomLeft -> applyBottomLeftAltTextPosition(altText)
            AltTextLayoutMode.BottomRight -> applyBottomRightAltTextPosition(altText)
            AltTextLayoutMode.DirectionalUpTopCenter -> applyTopCenterAltTextPosition(altText)
            AltTextLayoutMode.DirectionalUpTopRight -> applyTopRightAltTextPosition(altText)
            AltTextLayoutMode.DirectionalUpTopLeft -> applyTopLeftAltTextPosition(altText)
            AltTextLayoutMode.DirectionalDownBottom -> applyBottomAltTextPosition(altText1)
            AltTextLayoutMode.DirectionalDownBottomRight -> applyBottomRightAltTextPosition(altText1)
            AltTextLayoutMode.DirectionalDownBottomLeft -> applyBottomLeftAltTextPosition(altText1)
            AltTextLayoutMode.DirectionalTopBottom -> applyDirectionalTopBottomAltTextPosition()
            AltTextLayoutMode.DirectionalCorners -> applyDirectionalCornersAltTextPosition()
            AltTextLayoutMode.DirectionalCornersLeft ->
                applyDirectionalCornersAltTextPosition(atLeft = true)
            AltTextLayoutMode.Hidden -> applyNoAltTextPosition()
        }
        applyIconLayerPosition(mode)
        return true
    }

    fun refreshAltTextLayout() {
        lastLayoutMode = null
        lastGestureLayoutMode = null
        lastMoveMainTextForAltLabel = null
        applyLayout()
    }

    /**
     * 重新解析主题图标（切主题 / 切图标主题时由 BaseKeyboard.refreshIconTheme 调用）。
     */
    fun reapplyIconThemeOverride() {
        if (iconSlot == null) return
        if (iconSizePx > 0) applyIconDrawable(iconSizePx)
        lastLayoutMode = null
        lastGestureLayoutMode = null
        applyLayout()
    }

    /**
     * 按当前键面尺寸调整图标层，返回是否真的改了布局。
     *
     * 图标盒只由键高决定，与键宽无关。主题里的图标共用同一个 viewBox（水彩是
     * 0 0 66.4 66.4），同一个框才能画出同一个大小；以前按 min(宽, 高) 取框，?123
     * 这类宽键的框比字母键大一半，同一张图在宽键上就大一圈。键太矮时按比例收一次，
     * 免得图标顶出键面。
     *
     * 尺寸按 8px 分桶：键面尺寸抖几个像素不值得重新栅格化一次 SVG（每次都是一次
     * androidSVG 解析 + 一张位图），而 8px 的差别在键面上看不出来。
     */
    private fun updateIconLayerSize(
        width: Int = appearanceView.measuredWidth,
        height: Int = appearanceView.measuredHeight
    ): Boolean {
        if (iconSlot == null) return false
        if (width <= 0 || height <= 0) return false
        val fixed = (KEY_ICON_SIZE_DP * resources.displayMetrics.density).roundToInt()
        val raw = min(fixed, (height * KEY_ICON_MAX_RATIO).roundToInt())
        val size = max(8, raw / 8 * 8)
        if (size == iconSizePx) return false
        iconSizePx = size
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            this.width = size
            this.height = size
        }
        applyIconDrawable(size)
        return true
    }

    private fun applyIconDrawable(sizePx: Int) {
        val slot = iconSlot ?: return
        val ink = iconTextColor()
        iconInkColor = ink
        val info = IconThemeManager.resolveIconDrawableInfo(slot, sizePx, ink)
        if (info == null) {
            iconThemeTintWithTheme = null
            img.setImageDrawable(null)
            img.visibility = View.GONE
            mainText.visibility = View.VISIBLE
            // 主字符回来了，无障碍描述交还给文字本身，别留下上一个主题的字母。
            contentDescription = null
            return
        }
        iconThemeTintWithTheme = info.tintWithTheme
        img.setImageDrawable(info.drawable)
        applyIconTint()
        img.visibility = View.VISIBLE
        // 有图标时主字符让位：图标就是键面主体，文字副标签仍叠在上层。
        mainText.visibility = View.GONE
        // 主字符 GONE 之后 TalkBack 就只剩副标签可读了，把字母挂到键的无障碍描述上。
        contentDescription = mainText.text?.toString()?.takeIf { it.isNotBlank() }
    }

    /** 图标层该用的文字色：多色图标拿它替换近黑笔画，单色图标直接 tint 成这个颜色。 */
    private fun iconTextColor(): Int = resolveTextColor(
        when (def.variant) {
            Variant.Normal -> theme.keyTextColor
            Variant.AltForeground, Variant.Alternative -> theme.altKeyTextColor
            Variant.Accent -> theme.accentKeyTextColor
        }
    )

    private fun applyIconTint() {
        if (iconThemeTintWithTheme == false) {
            // SVG 自带配色（多色图标）时不套主题文字色，否则会把原色一起染掉。
            img.imageTintList = null
            img.drawable?.setTintList(null)
            return
        }
        img.imageTintList = ColorStateList.valueOf(iconTextColor())
    }

    override fun shouldTriggerAltBySwipe(totalY: Int, fallback: SwipeSymbolDirection): Boolean {
        if (totalY == 0) return false
        return when (lastGestureLayoutMode ?: resolveGestureLayoutMode(appearanceView.height)) {
            AltTextLayoutMode.Bottom,
            AltTextLayoutMode.BottomLeft,
            AltTextLayoutMode.BottomRight -> totalY > 0
            AltTextLayoutMode.TopRight,
            AltTextLayoutMode.TopCenter,
            AltTextLayoutMode.TopLeft -> totalY < 0
            // 方向性标签：上标签只认上滑、下标签只认下滑；标签放在哪个角落不影响动作方向。
            AltTextLayoutMode.DirectionalUpTopCenter,
            AltTextLayoutMode.DirectionalUpTopRight,
            AltTextLayoutMode.DirectionalUpTopLeft -> totalY < 0
            AltTextLayoutMode.DirectionalDownBottom,
            AltTextLayoutMode.DirectionalDownBottomRight,
            AltTextLayoutMode.DirectionalDownBottomLeft -> totalY > 0
            AltTextLayoutMode.DirectionalTopBottom,
            AltTextLayoutMode.DirectionalCorners,
            AltTextLayoutMode.DirectionalCornersLeft -> when {
                totalY < 0 -> !altText.text.isNullOrBlank()
                totalY > 0 -> !altText1.text.isNullOrBlank()
                else -> false
            }
            AltTextLayoutMode.Hidden -> fallback.checkY(totalY)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        lastLayoutMode = null
        lastGestureLayoutMode = null
        applyLayout()
    }

    override fun onAppearanceMeasured(width: Int, height: Int): Boolean = applyLayout(height)

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
        applyIconTint()
        // 多色图标的近黑笔画是按旧文字色烧进位图的，主题一换就得按新颜色重新光栅化；
        // 单色图标靠 tint，不受影响。
        if (iconThemeTintWithTheme == false && iconSlot != null && iconSizePx > 0 &&
            iconInkColor != iconTextColor()
        ) {
            applyIconDrawable(iconSizePx)
        }
        lastLayoutMode = null
        lastGestureLayoutMode = null
        applyLayout()
    }

    private companion object {
        /**
         * 图标盒边长（dp）。图标 SVG 墨迹通常只占 viewBox 的六到七成，24dp 的盒子落到
         * 键面上，字形高度约等于键高的三成，与主字符被替换掉时的大小相当。
         */
        private const val KEY_ICON_SIZE_DP = 24f

        /** 键面太矮时的上限比例，避免图标顶出键面。 */
        private const val KEY_ICON_MAX_RATIO = 0.68f
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
    override val themedIconSlot: String? get() = iconSlot
    private enum class AltTextLayoutMode {
        TopRight,
        TopCenter,
        Bottom,

        /**
         * 非方向性标签（字母键的标点副标签）在主题里开启「副标签显示在侧边」后，
         * 贴到所选侧（左/右）的角落。滑动方向不变：顶部标签仍上滑、底部标签仍下滑。
         */
        TopLeft,
        BottomLeft,
        BottomRight,

        /**
         * 方向性标签：位置直接由标签的方向决定（上标签在上、下标签在下），
         * **不读**主题的「标点位置」。键高不够时对应退到同侧的角落版本；
         * 主题里开启「副标签显示在侧边」后，固定放在所选侧（左/右）的角落。
         */
        DirectionalUpTopCenter,
        DirectionalUpTopRight,
        DirectionalUpTopLeft,
        DirectionalDownBottom,
        DirectionalDownBottomRight,
        DirectionalDownBottomLeft,
        DirectionalTopBottom,
        DirectionalCorners,
        DirectionalCornersLeft,
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

    /**
     * 手势方向用的布局：与副标签是否显示无关。
     *
     * 关掉副标签只是不画，键上仍然有上/下滑标签；如果手势也按 Hidden 走，就会退回
     * [SwipeSymbolDirection] 的默认方向，同一个键的可用滑动方向被主题设置改掉。
     */
    private var lastGestureLayoutMode: AltTextLayoutMode? = null
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

    private fun applyCornerAltTextPadding(label: AutoScaleTextView) {
        // Keep the safe area in the LayoutParams margin so the glyph, not only the
        // TextView's content box, stays clear of the rounded corner.
        label.setPadding(0, 0, 0, 0)
    }

    private fun applyBottomAltTextPadding(label: AutoScaleTextView = altText) {
        label.setPadding(hMargin, 0, hMargin, 0)
    }

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
        lastGestureLayoutMode = null
        applyLayout()
    }

    /**
     * 把副标签贴到键的某个角落（上/下 × 左/右），图标保持居中、标签不占额外高度。
     * 「键高不够时自动退到角落」与「主题里手动指定侧边」共用这里，只是角落参数不同。
     */
    private fun applyCornerAltTextPosition(
        label: AutoScaleTextView,
        atTop: Boolean,
        atLeft: Boolean,
        hideSibling: Boolean = true
    ) {
        if (hideSibling) {
            val hidden = if (label === altText) altText1 else altText
            hidden.visibility = View.GONE
        }
        label.visibility = View.VISIBLE
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
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
        label.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = if (atTop) parentId else unset
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = if (atTop) unset else parentId
            leftToLeft = parentId
            rightToRight = parentId
            leftMargin = if (atLeft) hMargin + cornerLabelHorizontalSafeInset else hMargin
            rightMargin = if (atLeft) hMargin else hMargin + cornerLabelHorizontalSafeInset
            topMargin = if (atTop) vMargin + cornerLabelTopSafeInset else 0
            bottomMargin = if (atTop) 0 else vMargin + dp(2)
        }
        applyCornerAltTextPadding(label)
        // 设置里的左右是**物理**左右，边距也是按 left/right 写的，所以这里必须用
        // LEFT/RIGHT：Gravity.START/END 在 RTL 布局下会解析成相反方向，标签会跑到另一边。
        label.gravity = (if (atLeft) Gravity.LEFT else Gravity.RIGHT) or Gravity.CENTER_VERTICAL
    }

    private fun applyTopRightAltTextPosition(
        label: AutoScaleTextView = altText,
        hideSibling: Boolean = true
    ) = applyCornerAltTextPosition(label, atTop = true, atLeft = false, hideSibling = hideSibling)

    private fun applyTopLeftAltTextPosition(
        label: AutoScaleTextView = altText,
        hideSibling: Boolean = true
    ) = applyCornerAltTextPosition(label, atTop = true, atLeft = true, hideSibling = hideSibling)

    private fun applyBottomRightAltTextPosition(
        label: AutoScaleTextView,
        hideSibling: Boolean = true
    ) = applyCornerAltTextPosition(label, atTop = false, atLeft = false, hideSibling = hideSibling)

    private fun applyBottomLeftAltTextPosition(
        label: AutoScaleTextView,
        hideSibling: Boolean = true
    ) = applyCornerAltTextPosition(label, atTop = false, atLeft = true, hideSibling = hideSibling)

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
        // Keep the same centered two-label layout as text keys, with either movement setting.
        altText.visibility = if (hasUpLabel) View.VISIBLE else View.GONE
        altText1.visibility = if (hasDownLabel) View.VISIBLE else View.GONE
        altText.gravity = Gravity.CENTER
        altText1.gravity = Gravity.CENTER
        altText.setPadding(hMargin, 0, hMargin, 0)
        altText1.setPadding(hMargin, 0, hMargin, 0)

        altText.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = parentId
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = unset
            leftToLeft = parentId
            rightToRight = parentId
            leftMargin = 0
            rightMargin = 0
            topMargin = vMargin
            bottomMargin = 0
        }
        altText1.updateLayoutParams<ConstraintLayout.LayoutParams> {
            width = 0
            topToTop = unset
            topToBottom = unset
            bottomToTop = unset
            bottomToBottom = parentId
            leftToLeft = parentId
            rightToRight = parentId
            leftMargin = 0
            rightMargin = 0
            topMargin = 0
            bottomMargin = vMargin + dp(2)
        }
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerInParent()
            topToTop = parentId
            topToBottom = unset
            bottomToBottom = parentId
            bottomToTop = unset
            topMargin = 0
            bottomMargin = 0
        }
    }

    private fun applyDirectionalCornersAltTextPosition(atLeft: Boolean = false) {
        val hasUpLabel = !altText.text.isNullOrBlank()
        val hasDownLabel = !altText1.text.isNullOrBlank()
        img.updateLayoutParams<ConstraintLayout.LayoutParams> {
            centerInParent()
            topToTop = parentId
            topToBottom = unset
            bottomToBottom = parentId
            bottomToTop = unset
            topMargin = 0
            bottomMargin = 0
        }
        // hideSibling = false：两个标签同时显示，各自占一个角。
        altText.visibility = if (hasUpLabel) View.VISIBLE else View.GONE
        altText1.visibility = if (hasDownLabel) View.VISIBLE else View.GONE
        if (hasUpLabel) {
            applyCornerAltTextPosition(altText, atTop = true, atLeft = atLeft, hideSibling = false)
        }
        if (hasDownLabel) {
            applyCornerAltTextPosition(altText1, atTop = false, atLeft = atLeft, hideSibling = false)
        }
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

    /**
     * 副标签的几何布局：只看标签内容与键高，**不看**「显示按键副标签」这个开关。
     *
     * 手势方向必须基于这个结果：开关关掉时标签不画，但键上的上/下滑标签仍然存在，
     * 方向不能跟着一起消失（见 [shouldTriggerAltBySwipe]）。
     */
    private fun resolveGestureLayoutMode(keyHeight: Int): AltTextLayoutMode {
        val pref = punctuationPositionForKey()
        if (def.directionalSwipeLabels) {
            // 方向性标签的位置只由标签方向决定（上标签在上、下标签在下），刻意不读
            // 主题的「标点位置」——见 AltTextKeyView.resolveGestureLayoutMode 的同一说明。
            val hasUpLabel = !altText.text.isNullOrBlank()
            val hasDownLabel = !altText1.text.isNullOrBlank()
            if (!hasUpLabel && !hasDownLabel) return AltTextLayoutMode.Hidden

            // 主题里手动开启「副标签显示在侧边」后，位置只由该设置决定，不再看键高：
            // 上标签贴所选侧的顶角、下标签贴同侧的底角。
            if (ThemeManager.prefs.altLabelOnSide.getValue()) {
                val atLeft = ThemeManager.prefs.altLabelSide.getValue() == AltLabelSide.Left
                return when {
                    hasUpLabel && hasDownLabel ->
                        if (atLeft) AltTextLayoutMode.DirectionalCornersLeft
                        else AltTextLayoutMode.DirectionalCorners
                    hasUpLabel ->
                        if (atLeft) AltTextLayoutMode.DirectionalUpTopLeft
                        else AltTextLayoutMode.DirectionalUpTopRight
                    else ->
                        if (atLeft) AltTextLayoutMode.DirectionalDownBottomLeft
                        else AltTextLayoutMode.DirectionalDownBottomRight
                }
            }

            fun normalizedMainHeight(): Float {
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
                return max(iconHeight, mainHeight)
            }

            if (keyHeight <= 0) {
                return when {
                    hasUpLabel && hasDownLabel -> AltTextLayoutMode.DirectionalTopBottom
                    hasUpLabel -> AltTextLayoutMode.DirectionalUpTopCenter
                    else -> AltTextLayoutMode.DirectionalDownBottom
                }
            }

            val contentHeight = keyHeight - vMargin * 2
            val mainHeight = normalizedMainHeight()
            val upHeight = altText.paint.run { fontMetrics.bottom - fontMetrics.top }
            val downHeight = altText1.paint.run { fontMetrics.bottom - fontMetrics.top }
            val labelHeight = max(upHeight, downHeight)
            // 角落标签压在键的角落上，不占额外的高度，只要键本身放得下它。
            val cornerMinHeight = max(mainHeight, labelHeight + cornerLabelTopSafeInset)

            if (hasUpLabel && hasDownLabel) {
                // Both labels are anchored independently; movement never changes their layout.
                val stackedMinHeight = mainHeight + labelHeight + dp(1)
                return when {
                    contentHeight >= stackedMinHeight -> AltTextLayoutMode.DirectionalTopBottom
                    contentHeight >= cornerMinHeight -> AltTextLayoutMode.DirectionalCorners
                    else -> AltTextLayoutMode.Hidden
                }
            }

            val stackedMinHeight = mainHeight + labelHeight + dp(1)
            return if (hasUpLabel) {
                when {
                    contentHeight >= stackedMinHeight -> AltTextLayoutMode.DirectionalUpTopCenter
                    contentHeight >= cornerMinHeight -> AltTextLayoutMode.DirectionalUpTopRight
                    else -> AltTextLayoutMode.Hidden
                }
            } else {
                when {
                    contentHeight >= stackedMinHeight -> AltTextLayoutMode.DirectionalDownBottom
                    contentHeight >= cornerMinHeight -> AltTextLayoutMode.DirectionalDownBottomRight
                    else -> AltTextLayoutMode.Hidden
                }
            }
        }
        if (altText.text.isNullOrBlank()) return AltTextLayoutMode.Hidden

        val preferred = when (pref) {
            PunctuationPosition.TopRight -> AltTextLayoutMode.TopRight
            PunctuationPosition.TopCenter -> AltTextLayoutMode.TopCenter
            PunctuationPosition.Bottom -> AltTextLayoutMode.Bottom
            PunctuationPosition.None -> AltTextLayoutMode.Hidden
        }
        // 主题里手动开启「副标签显示在侧边」后，字母键的标点副标签也跟随所选侧：
        // 上滑标签贴所选侧的顶角、下滑标签贴同侧的底角。位置只由该设置决定，不再看键高，
        // 与方向性标签分支保持一致；滑动方向不变（顶部标签仍上滑、底部标签仍下滑）。
        if (ThemeManager.prefs.altLabelOnSide.getValue()) {
            val atLeft = ThemeManager.prefs.altLabelSide.getValue() == AltLabelSide.Left
            return when (preferred) {
                AltTextLayoutMode.TopRight, AltTextLayoutMode.TopCenter ->
                    if (atLeft) AltTextLayoutMode.TopLeft else AltTextLayoutMode.TopRight
                AltTextLayoutMode.Bottom ->
                    if (atLeft) AltTextLayoutMode.BottomLeft else AltTextLayoutMode.BottomRight
                else -> AltTextLayoutMode.Hidden
            }
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
            else -> AltTextLayoutMode.Hidden
        }
    }

    /** 副标签的绘制布局：开关关掉时直接 Hidden，不占位、不重新测量，键面只剩图标或主字符。 */
    private fun resolveLayoutMode(keyHeight: Int): AltTextLayoutMode =
        if (ThemeManager.prefs.showKeyAltLabels.getValue()) resolveGestureLayoutMode(keyHeight)
        else AltTextLayoutMode.Hidden

    private fun applyLayout(keyHeight: Int = appearanceView.height): Boolean {
        val gestureMode = resolveGestureLayoutMode(keyHeight)
        val mode = if (ThemeManager.prefs.showKeyAltLabels.getValue()) gestureMode
        else AltTextLayoutMode.Hidden
        if (mode == lastLayoutMode && gestureMode == lastGestureLayoutMode) return false
        lastLayoutMode = mode
        lastGestureLayoutMode = gestureMode
        val keepDirectionalLabel = when (mode) {
            AltTextLayoutMode.DirectionalUpTopRight,
            AltTextLayoutMode.DirectionalUpTopLeft,
            AltTextLayoutMode.DirectionalUpTopCenter,
            AltTextLayoutMode.DirectionalDownBottom,
            AltTextLayoutMode.DirectionalDownBottomRight,
            AltTextLayoutMode.DirectionalDownBottomLeft,
            AltTextLayoutMode.DirectionalTopBottom,
            AltTextLayoutMode.DirectionalCorners,
            AltTextLayoutMode.DirectionalCornersLeft -> true
            else -> false
        }
        if (!keepDirectionalLabel) resetDirectionalAltText1()
        when (mode) {
            AltTextLayoutMode.Bottom -> applyBottomAltTextPosition()
            AltTextLayoutMode.TopRight -> applyTopRightAltTextPosition()
            AltTextLayoutMode.TopCenter -> applyTopCenterAltTextPosition()
            AltTextLayoutMode.TopLeft -> applyTopLeftAltTextPosition()
            AltTextLayoutMode.BottomLeft -> applyBottomLeftAltTextPosition(altText)
            AltTextLayoutMode.BottomRight -> applyBottomRightAltTextPosition(altText)
            AltTextLayoutMode.DirectionalUpTopCenter -> applyTopCenterAltTextPosition(altText)
            AltTextLayoutMode.DirectionalUpTopRight -> applyTopRightAltTextPosition(altText)
            AltTextLayoutMode.DirectionalUpTopLeft -> applyTopLeftAltTextPosition(altText)
            AltTextLayoutMode.DirectionalDownBottom -> applyBottomAltTextPosition(altText1)
            AltTextLayoutMode.DirectionalDownBottomRight -> applyBottomRightAltTextPosition(altText1)
            AltTextLayoutMode.DirectionalDownBottomLeft -> applyBottomLeftAltTextPosition(altText1)
            AltTextLayoutMode.DirectionalTopBottom -> applyDirectionalTopBottomAltTextPosition()
            AltTextLayoutMode.DirectionalCorners -> applyDirectionalCornersAltTextPosition()
            AltTextLayoutMode.DirectionalCornersLeft ->
                applyDirectionalCornersAltTextPosition(atLeft = true)
            AltTextLayoutMode.Hidden -> applyNoAltTextPosition()
        }
        return true
    }

    fun refreshAltTextLayout() {
        lastLayoutMode = null
        lastGestureLayoutMode = null
        applyLayout()
    }

    override fun shouldTriggerAltBySwipe(totalY: Int, fallback: SwipeSymbolDirection): Boolean {
        if (totalY == 0) return false
        return when (lastGestureLayoutMode ?: resolveGestureLayoutMode(appearanceView.height)) {
            AltTextLayoutMode.Bottom,
            AltTextLayoutMode.BottomLeft,
            AltTextLayoutMode.BottomRight -> totalY > 0
            AltTextLayoutMode.TopRight,
            AltTextLayoutMode.TopCenter,
            AltTextLayoutMode.TopLeft -> totalY < 0
            // 方向性标签：上标签只认上滑、下标签只认下滑；标签放在哪个角落不影响动作方向。
            AltTextLayoutMode.DirectionalUpTopCenter,
            AltTextLayoutMode.DirectionalUpTopRight,
            AltTextLayoutMode.DirectionalUpTopLeft -> totalY < 0
            AltTextLayoutMode.DirectionalDownBottom,
            AltTextLayoutMode.DirectionalDownBottomRight,
            AltTextLayoutMode.DirectionalDownBottomLeft -> totalY > 0
            AltTextLayoutMode.DirectionalTopBottom,
            AltTextLayoutMode.DirectionalCorners,
            AltTextLayoutMode.DirectionalCornersLeft -> when {
                totalY < 0 -> !altText.text.isNullOrBlank()
                totalY > 0 -> !altText1.text.isNullOrBlank()
                else -> false
            }
            AltTextLayoutMode.Hidden -> fallback.checkY(totalY)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        invalidateTextMetricsCache()
        lastLayoutMode = null
        lastGestureLayoutMode = null
        applyLayout()
    }

    override fun onAppearanceMeasured(width: Int, height: Int): Boolean = applyLayout(height)

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
        lastGestureLayoutMode = null
        applyLayout()
    }
}

@SuppressLint("ViewConstructor")
class ImageKeyView(
    ctx: Context,
    theme: Theme,
    def: KeyDef.Appearance.Image,
    horizontalGapScale: Float = 1f,
    private val iconSlot: String? = null,
    /**
     * 与 [iconSlot] 成对的 ASCII 状态槽位（目前只有语言键使用）。非空时该键随输入法
     * 中/西文状态换图标：主题配了 ASCII 槽位就用主题图标，否则回落到 [srcAlt]。
     */
    private val iconSlotAlt: String? = null,
    @DrawableRes private val srcAlt: Int? = null
) :
    KeyView(ctx, theme, def, horizontalGapScale) {
    private var iconThemeTintWithTheme: Boolean? = null
    private val defaultSrc: Int = def.src
    private var asciiMode = false
    override val themedIconSlot: String? get() = iconSlot
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

    /**
     * 切换中/西文状态的键面图标。只有配了 ASCII 槽位的键（语言键）响应，
     * 其它图标键调用无副作用。
     */
    fun setAsciiMode(ascii: Boolean) {
        if (iconSlotAlt == null || asciiMode == ascii) return
        asciiMode = ascii
        reapplyIconThemeOverride()
    }

    fun reapplyIconThemeOverride() {
        val slot = if (asciiMode) iconSlotAlt ?: iconSlot else iconSlot
        if (slot == null) return
        val iconInfo = IconThemeManager.resolveIconDrawableInfo(slot)
        if (iconInfo == null) {
            iconThemeTintWithTheme = null
            // 主题没有该槽位时回落到内置图标。ASCII 态有自己的一枚，不能沿用中文态那枚，
            // 所以这里必须显式重设，而不能依赖构造时已经设好的 def.src。
            img.imageResource = if (asciiMode) srcAlt ?: defaultSrc else defaultSrc
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
    override val themedIconSlot: String? get() = iconSlot
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
