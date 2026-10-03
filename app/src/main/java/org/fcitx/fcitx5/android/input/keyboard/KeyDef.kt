/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Typeface
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.data.InputFeedbacks

open class KeyDef(
    val appearance: Appearance,
    val behaviors: Set<Behavior>,
    val popup: Array<Popup>? = null,
    /**
     * Icon theme slot name for this key's icon override.
     * When non-null and the active IconTheme provides a value,
     * it replaces the built-in drawable resource.
     */
    val iconSlot: String? = null
) {
    /**
     * Optional per-row height percentage hint contributed by this key.
     * The effective row height is the max value among keys in the same row.
     */
    var rowHeightPercent: Float? = null

    /**
     * 分体键盘的手动分界标记：为 true 时表示「本键之后把这一行断开成左右两半」。
     *
     * 仅在该键所属行**没有任何**手动标记时才走自动分界（按宽度取几何中点，见
     * `BaseKeyboard.chooseSplitIndex`）。同一行出现多个标记时以**最后一个**为准，
     * 这样复制中间键、拖动按键之后标记仍然跟着键走、结果可预期。
     *
     * 有意做成「键上的一枚开关」而不是行上的一个下标：下标会在增删/拖动按键后错位，
     * 而标记随 `KeyDef` 一起被复制、移动，不需要任何同步逻辑。
     */
    var splitAfter: Boolean = false

    /**
     * Optional key definition that is used while IME is in composing state.
     * This must keep the same key type/size semantics as the base key.
     */
    var composeOverride: KeyDef? = null

    /**
     * When true on a compose override key, it uses its own independent color settings.
     * When false (default), compose override follows base key color state.
     */
    var independentColor: Boolean = false

    sealed class Appearance(
        val percentWidth: Float,
        val variant: Variant,
        val border: Border,
        val margin: Boolean,
        val viewId: Int,
        val soundEffect: InputFeedbacks.SoundEffect,
        val textColor: Int? = null,
        val textColorMonet: String? = null,
        val altTextColor: Int? = null,
        val altTextColorMonet: String? = null,
        val backgroundColor: Int? = null,
        val backgroundColorMonet: String? = null,
        val shadowColor: Int? = null,
        val shadowColorMonet: String? = null
    ) {
        /**
         * 完全不绘制键底、边框、阴影与按压高亮，键面直接透出键盘底色。
         *
         * 这是「空白占位键」关闭自定义颜色时的形态：用户要的就是一个看不见的占位，
         * 而 [Border.Off] 只能做到"不画边框"——主题开着描边/阴影时仍会留下一块可见的
         * 底和投影，看起来像个没字的按键，而不是不存在。
         *
         * 做成独立字段而不是构造参数，与 [KeyDef.splitAfter] 同一理由：只有占位键需要它，
         * 为它给六个 Appearance 子类各加一个构造参数，就意味着每次增删键型都要再同步
         * `withColorsFrom` / `withIdentityFrom` 的一整排拷贝分支——那正是"改个颜色就把
         * 透底弄丢"最容易被漏掉的地方。
         */
        var transparentBackground: Boolean = false

        /**
         * 纯外观键：键面内容由用户原样指定，不参与任何状态改写，也不因"不可点击"而变淡。
         *
         * 影响两处：① `TextKeyboard` 的 Shift/Caps 大小写与标点映射都跳过它——占位键上写
         * 一个 `G` 就是想要 `G`，跟着 Shift 变来变去毫无意义；② `KeyView.setEnabled`
         * 不再套用 `disabledAlpha`，否则装饰文字会一直显示成半透明。
         */
        var staticDisplay: Boolean = false

        enum class Variant {
            Normal, AltForeground, Alternative, Accent
        }

        enum class Border {
            Default, On, Off, Special
        }

        open class Text(
            val displayText: String,
            val textSize: Float,
            /**
             * `Int` constants in [Typeface].
             * Can be `NORMAL`(default), `BOLD`, `ITALIC` or `BOLD_ITALIC`
             */
            val textStyle: Int = Typeface.NORMAL,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard,
            textColor: Int? = null,
            textColorMonet: String? = null,
            altTextColor: Int? = null,
            altTextColorMonet: String? = null,
            backgroundColor: Int? = null,
            backgroundColorMonet: String? = null,
            shadowColor: Int? = null,
            shadowColorMonet: String? = null
        ) : Appearance(
            percentWidth,
            variant,
            border,
            margin,
            viewId,
            soundEffect,
            textColor,
            textColorMonet,
            altTextColor,
            altTextColorMonet,
            backgroundColor,
            backgroundColorMonet,
            shadowColor,
            shadowColorMonet
        )

        /**
         * Compact toolbar label (NumberRow in the Kawaii bar).
         *
         * The label keeps the canonical fixed [textSize] and never participates in the
         * keyboard text-scale setting — a 40dp toolbar row with a scaled-down font is what made
         * the digits invisible — but it still renders through the normal key-label pipeline, so
         * the user's configured key typeface ("key_main_font") and font-size settings apply.
         * Text color follows the active theme via the inherited [variant].
         */
        class ToolbarText(
            displayText: String,
            textSize: Float,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Off,
            margin: Boolean = false
        ) : Text(
            displayText = displayText,
            textSize = textSize,
            percentWidth = percentWidth,
            variant = variant,
            border = border,
            margin = margin
        )

        class AltText(
            displayText: String,
            val altText: String,
            val character: String,
            textSize: Float,
            /**
             * `Int` constants in [Typeface].
             * Can be `NORMAL`(default), `BOLD`, `ITALIC` or `BOLD_ITALIC`
             */
            textStyle: Int = Typeface.NORMAL,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            textColor: Int? = null,
            textColorMonet: String? = null,
            altTextColor: Int? = null,
            altTextColorMonet: String? = null,
            backgroundColor: Int? = null,
            backgroundColorMonet: String? = null,
            shadowColor: Int? = null,
            shadowColorMonet: String? = null,
            /**
             * True when [displayText] was explicitly configured by the user (as opposed to being
             * derived from the key's label). Such a label is never rewritten to lower case by the
             * Shift/Caps transform in TextKeyboard.updateAlphabetKeys(): a MacroKey whose
             * displayText is "A" would otherwise still be drawn as "a" at Shift-off, which is
             * exactly the "not really customizable" complaint this flag exists to fix.
             *
             * Turning a single lower-case letter into upper case on Shift is still allowed, so the
             * usual Shift behaviour is preserved.
             */
            val keepDisplayTextCase: Boolean = false
        ) : Text(
            displayText,
            textSize,
            textStyle,
            percentWidth,
            variant,
            border,
            margin,
            viewId,
            InputFeedbacks.SoundEffect.Standard,
            textColor,
            textColorMonet,
            altTextColor,
            altTextColorMonet,
            backgroundColor,
            backgroundColorMonet,
            shadowColor,
            shadowColorMonet
        )

        class Image(
            @DrawableRes
            val src: Int,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard,
            textColor: Int? = null,
            textColorMonet: String? = null,
            altTextColor: Int? = null,
            altTextColorMonet: String? = null,
            backgroundColor: Int? = null,
            backgroundColorMonet: String? = null,
            shadowColor: Int? = null,
            shadowColorMonet: String? = null
        ) : Appearance(
            percentWidth,
            variant,
            border,
            margin,
            viewId,
            soundEffect,
            textColor,
            textColorMonet,
            altTextColor,
            altTextColorMonet,
            backgroundColor,
            backgroundColorMonet,
            shadowColor,
            shadowColorMonet
        )

        class ImageAltText(
            @DrawableRes
            val src: Int,
            val altText: String,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            soundEffect: InputFeedbacks.SoundEffect = InputFeedbacks.SoundEffect.Standard,
            textColor: Int? = null,
            textColorMonet: String? = null,
            altTextColor: Int? = null,
            altTextColorMonet: String? = null,
            backgroundColor: Int? = null,
            backgroundColorMonet: String? = null,
            shadowColor: Int? = null,
            shadowColorMonet: String? = null
        ) : Appearance(
            percentWidth,
            variant,
            border,
            margin,
            viewId,
            soundEffect,
            textColor,
            textColorMonet,
            altTextColor,
            altTextColorMonet,
            backgroundColor,
            backgroundColorMonet,
            shadowColor,
            shadowColorMonet
        )

        class ImageText(
            displayText: String,
            textSize: Float,
            /**
             * `Int` constants in [Typeface].
             * Can be `NORMAL`(default), `BOLD`, `ITALIC` or `BOLD_ITALIC`
             */
            textStyle: Int = Typeface.NORMAL,
            @DrawableRes
            val src: Int,
            percentWidth: Float = 0.1f,
            variant: Variant = Variant.Normal,
            border: Border = Border.Default,
            margin: Boolean = true,
            viewId: Int = -1,
            textColor: Int? = null,
            textColorMonet: String? = null,
            altTextColor: Int? = null,
            altTextColorMonet: String? = null,
            backgroundColor: Int? = null,
            backgroundColorMonet: String? = null,
            shadowColor: Int? = null,
            shadowColorMonet: String? = null
        ) : Text(
            displayText,
            textSize,
            textStyle,
            percentWidth,
            variant,
            border,
            margin,
            viewId,
            InputFeedbacks.SoundEffect.Standard,
            textColor,
            textColorMonet,
            altTextColor,
            altTextColorMonet,
            backgroundColor,
            backgroundColorMonet,
            shadowColor,
            shadowColorMonet
        )
    }

    sealed class Behavior {
        class Press(
            val action: KeyAction
        ) : Behavior()

        class LongPress(
            val action: KeyAction
        ) : Behavior()

        class Repeat(
            val action: KeyAction
        ) : Behavior()

        class Swipe(
            val action: KeyAction
        ) : Behavior()

        class DoubleTap(
            val action: KeyAction
        ) : Behavior()
    }

    sealed class Popup {
        open class Preview(val content: String) : Popup()

        class AltPreview(content: String, val alternative: String) : Preview(content)

        sealed class Keyboard : Popup() {
            data class Preset(val label: String, val transformPunctuation: Boolean = true) :
                Keyboard()

            class Explicit(val items: Array<String>) : Keyboard()
        }

        class Menu(val items: Array<Item>) : Popup() {
            class Item(
                val label: String,
                @DrawableRes val icon: Int,
                val action: KeyAction,
                val iconSlot: String? = null
            )
        }

        /**
         * Represents a longPress macro action that appears in the popup keyboard
         * as the first candidate item.
         * @param displayLabel The text to display in the popup candidate
         * @param action The KeyAction to execute when selected
         * @param baseLabel The base label to lookup remaining candidates from PopupPreset
         */
        class LongPressKeyboard(
            val displayLabel: String,
            val action: KeyAction,
            val baseLabel: String
        ) : Popup()
    }
}
