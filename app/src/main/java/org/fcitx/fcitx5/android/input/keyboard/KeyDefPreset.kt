/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.graphics.Typeface
import androidx.annotation.DrawableRes
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.FcitxKeyMapping
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.core.KeySym
import org.fcitx.fcitx5.android.data.InputFeedbacks
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Border
import org.fcitx.fcitx5.android.input.keyboard.KeyDef.Appearance.Variant
import org.fcitx.fcitx5.android.input.picker.PickerWindow

// Import Macro types
import org.fcitx.fcitx5.android.input.keyboard.MacroAction
import org.fcitx.fcitx5.android.input.keyboard.MacroStep
import org.fcitx.fcitx5.android.input.keyboard.KeyRef

val NumLockState = KeyStates(KeyState.NumLock, KeyState.Virtual)

class SymbolKey(
    val symbol: String,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.Normal,
    popup: Array<Popup>? = null,
    textColor: Int? = null,
    textColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null,
    val swipe: MacroAction? = null,
    val swipeLabel: String? = null,
    val swipeUp: MacroAction? = null,
    val swipeDown: MacroAction? = null,
    val swipeUpLabel: String? = null,
    val swipeDownLabel: String? = null
) : KeyDef(
    if (swipeLabel.isNullOrEmpty() && swipeUpLabel.isNullOrEmpty() && swipeDownLabel.isNullOrEmpty()) {
        Appearance.Text(
            displayText = symbol,
            textSize = 23f,
            percentWidth = percentWidth,
            variant = variant,
            textColor = textColor,
            textColorMonet = textColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
    } else {
        Appearance.AltText(
            displayText = symbol,
            altText = swipeUpLabel ?: if (swipeDownLabel == null) swipeLabel.orEmpty() else "",
            altText1 = swipeDownLabel,
            character = symbol,
            textSize = 23f,
            percentWidth = percentWidth,
            variant = variant,
            textColor = textColor,
            textColorMonet = textColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        ).apply {
            directionalSwipeLabels = swipeUpLabel != null || swipeDownLabel != null
        }
    },
    buildSet {
        add(Behavior.Press(KeyAction.FcitxKeyAction(symbol)))
        if (swipeUp != null || swipeDown != null) {
            add(Behavior.Swipe(
                upMacro = swipeUp,
                downMacro = swipeDown,
                legacyMacro = swipe,
                overrideDefaults = true
            ))
        } else {
            swipe?.let { add(Behavior.Swipe(it)) }
        }
    },
    popup ?: arrayOf(
        Popup.Preview(symbol),
        Popup.Keyboard.Preset(symbol)
    )
)

/**
 * 字母按键。
 *
 * 显示文本语义与 [MacroKey] 一致：**显式设置的 [displayText] 优先，并按原样显示**
 * （不做 Shift/Caps 大小写改写）。
 *
 * ⚠️「是否显式设置」以 **JSON 里有没有这个字段**为准，**不要**拿它与 [character] 比较。
 * 早先那版判定写的是「与主字符相同即视为未设置」，看起来能省掉冗余字段，实际会把
 * 「主字符大写 Q + 显示文本大写 Q」这种合理诉求静默丢掉——编辑器存不住该字段，手改
 * 配置文件也不生效，用户只能绕道把主字符改成小写。同值不等于没有意图。
 *
 * 未设置时（字段缺失，或按方案分组但当前方案没有取值）回落到 [character]，并保留
 * 字母键固有的 Shift 大小写切换。
 *
 * @param character 实际输入的字符
 * @param punctuation 划动输入的备选字符
 * @param displayText 键面显示文本；null / 空串表示未设置
 */
class AlphabetKey(
    val character: String,
    val punctuation: String,
    val displayText: String? = null,
    variant: Variant = Variant.Normal,
    popup: Array<Popup>? = null,
    weight: Float? = null,
    textColor: Int? = null,
    textColorMonet: String? = null,
    altTextColor: Int? = null,
    altTextColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null
) : KeyDef(
    Appearance.AltText(
        displayText = displayText?.takeIf { it.isNotEmpty() } ?: character,
        altText = punctuation,
        character = character,
        textSize = 23f,
        variant = variant,
        percentWidth = weight ?: 0.1f,
        textColor = textColor,
        textColorMonet = textColorMonet,
        altTextColor = altTextColor,
        altTextColorMonet = altTextColorMonet,
        backgroundColor = backgroundColor,
        backgroundColorMonet = backgroundColorMonet,
        shadowColor = shadowColor,
        shadowColorMonet = shadowColorMonet,
        keepDisplayTextCase = !displayText.isNullOrEmpty()
    ),
    setOf(
        Behavior.Press(KeyAction.FcitxKeyAction(character)),
        Behavior.Swipe(KeyAction.FcitxKeyAction(punctuation))
    ),
    popup ?: arrayOf(
        Popup.AltPreview(character, punctuation),
        Popup.Keyboard.Preset(character)
    )
)

class AlphabetDigitKey(
    val character: String,
    altText: String,
    val sym: Int,
    popup: Array<Popup>? = null
) : KeyDef(
    Appearance.AltText(
        displayText = character,
        altText = altText,
        character = character,
        textSize = 23f
    ),
    setOf(
        Behavior.Press(KeyAction.FcitxKeyAction(character)),
        Behavior.Swipe(KeyAction.SymAction(KeySym(sym), NumLockState))
    ),
    popup ?: arrayOf(
        Popup.AltPreview(character, altText),
        Popup.Keyboard.Preset(character)
    )
) {
    constructor(
        char: String,
        digit: Int,
        popup: Array<Popup>? = null
    ) : this(
        char,
        digit.toString(),
        FcitxKeyMapping.FcitxKey_KP_0 + digit,
        popup
    )
}

class CapsKey(
    percentWidth: Float = 0.15f,
    textColor: Int? = null,
    textColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null,
    val swipe: MacroAction? = null,
    val swipeLabel: String? = null,
    val swipeUp: MacroAction? = null,
    val swipeDown: MacroAction? = null,
    val swipeUpLabel: String? = null,
    val swipeDownLabel: String? = null
) : KeyDef(
    if (swipeLabel.isNullOrEmpty() && swipeUpLabel.isNullOrEmpty() && swipeDownLabel.isNullOrEmpty()) {
        Appearance.Image(
            src = R.drawable.ic_capslock_none,
            viewId = R.id.button_caps,
            percentWidth = percentWidth,
            variant = Variant.Alternative,
            textColor = textColor,
            textColorMonet = textColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
    } else {
        Appearance.ImageAltText(
            src = R.drawable.ic_capslock_none,
            altText = swipeUpLabel ?: if (swipeDownLabel == null) swipeLabel.orEmpty() else "",
            altText1 = swipeDownLabel,
            viewId = R.id.button_caps,
            percentWidth = percentWidth,
            variant = Variant.Alternative,
            textColor = textColor,
            textColorMonet = textColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        ).apply {
            directionalSwipeLabels = swipeUpLabel != null || swipeDownLabel != null
        }
    },
    buildSet {
        add(Behavior.Press(KeyAction.CapsAction(false)))
        add(Behavior.LongPress(KeyAction.CapsAction(true)))
        add(Behavior.DoubleTap(KeyAction.CapsAction(true)))
        if (swipeUp != null || swipeDown != null) {
            add(Behavior.Swipe(
                upMacro = swipeUp,
                downMacro = swipeDown,
                legacyMacro = swipe,
                overrideDefaults = true
            ))
        } else {
            swipe?.let { add(Behavior.Swipe(it)) }
        }
    },
    iconSlot = "keys.capslock.none"
)

class LayoutSwitchKey(
    displayText: String,
    val to: String = "",
    percentWidth: Float = 0.15f,
    variant: Variant = Variant.Alternative,
    textColor: Int? = null,
    textColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null,
    val swipe: MacroAction? = null,
    val swipeLabel: String? = null,
    val swipeUp: MacroAction? = null,
    val swipeDown: MacroAction? = null,
    val swipeUpLabel: String? = null,
    val swipeDownLabel: String? = null
) : KeyDef(
    if (swipeLabel.isNullOrEmpty() && swipeUpLabel.isNullOrEmpty() && swipeDownLabel.isNullOrEmpty()) {
        Appearance.Text(
            displayText,
            textSize = 16f,
            textStyle = Typeface.BOLD,
            percentWidth = percentWidth,
            variant = variant,
            border = Border.Special,
            viewId = R.id.button_layout_switch,
            textColor = textColor,
            textColorMonet = textColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
    } else {
        Appearance.AltText(
            displayText = displayText,
            altText = swipeUpLabel ?: if (swipeDownLabel == null) swipeLabel.orEmpty() else "",
            altText1 = swipeDownLabel,
            character = displayText,
            textSize = 16f,
            textStyle = Typeface.BOLD,
            percentWidth = percentWidth,
            variant = variant,
            border = Border.Special,
            viewId = R.id.button_layout_switch,
            textColor = textColor,
            textColorMonet = textColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        ).apply {
            directionalSwipeLabels = swipeUpLabel != null || swipeDownLabel != null
        }
    },
    buildSet {
        add(Behavior.Press(KeyAction.LayoutSwitchAction(to)))
        if (swipeUp != null || swipeDown != null) {
            add(Behavior.Swipe(
                upMacro = swipeUp,
                downMacro = swipeDown,
                legacyMacro = swipe,
                overrideDefaults = true
            ))
        } else {
            swipe?.let { add(Behavior.Swipe(it)) }
        }
    },
    arrayOf(
       Popup.Menu(
        // PickerWindow symbols or numberkeyboard switch
        arrayOf(
            Popup.Menu.Item(
                "Symbols",
                R.drawable.ic_baseline_emoji_symbols_24,
                KeyAction.LayoutSwitchAction(PickerWindow.Key.Symbol.name),
                iconSlot = "keys.symbols"
            ),
            Popup.Menu.Item(
                "NumPad",
                R.drawable.ic_number_pad,
                KeyAction.LayoutSwitchAction(NumberKeyboard.Name),
                iconSlot = "keys.numpad"
            )
        )
       )
    )
)

class BackspaceKey(
    percentWidth: Float = 0.15f,
    variant: Variant = Variant.Alternative,
    textColor: Int? = null,
    textColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null,
    val swipe: MacroAction? = null,
    val swipeLabel: String? = null,
    val swipeUp: MacroAction? = null,
    val swipeDown: MacroAction? = null,
    val swipeUpLabel: String? = null,
    val swipeDownLabel: String? = null
) : KeyDef(
    if (swipeLabel.isNullOrEmpty() && swipeUpLabel.isNullOrEmpty() && swipeDownLabel.isNullOrEmpty()) {
        Appearance.Image(
            src = R.drawable.ic_baseline_backspace_24,
            percentWidth = percentWidth,
            variant = variant,
            viewId = R.id.button_backspace,
            soundEffect = InputFeedbacks.SoundEffect.Delete,
            textColor = textColor,
            textColorMonet = textColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
    } else {
        Appearance.ImageAltText(
            src = R.drawable.ic_baseline_backspace_24,
            altText = swipeUpLabel ?: if (swipeDownLabel == null) swipeLabel.orEmpty() else "",
            altText1 = swipeDownLabel,
            percentWidth = percentWidth,
            variant = variant,
            viewId = R.id.button_backspace,
            soundEffect = InputFeedbacks.SoundEffect.Delete,
            textColor = textColor,
            textColorMonet = textColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        ).apply {
            directionalSwipeLabels = swipeUpLabel != null || swipeDownLabel != null
        }
    },
    buildSet {
        add(Behavior.Press(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))))
        add(Behavior.Repeat(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_BackSpace))))
    },
    iconSlot = "keys.backspace"
)

class CommaKey(
    percentWidth: Float,
    variant: Variant,
    textColor: Int? = null,
    textColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null
) : KeyDef(
    Appearance.ImageText(
        displayText = ",",
        textSize = 23f,
        percentWidth = percentWidth,
        variant = variant,
        src = R.drawable.ic_baseline_tag_faces_24,
        textColor = textColor,
        textColorMonet = textColorMonet,
        backgroundColor = backgroundColor,
        backgroundColorMonet = backgroundColorMonet,
        shadowColor = shadowColor,
        shadowColorMonet = shadowColorMonet
    ),
    setOf(
        Behavior.Press(KeyAction.FcitxKeyAction(","))
    ),
    arrayOf(
        Popup.Preview(","),
        Popup.Menu(
            arrayOf(
                Popup.Menu.Item(
                    "Emoji",
                    R.drawable.ic_baseline_tag_faces_24,
                    KeyAction.PickerSwitchAction(),
                    iconSlot = "keys.emoji"
                )
            )
        )
    ),
    iconSlot = "keys.emoji"
)

class LanguageKey(
    percentWidth: Float = 0.1f,
    textColor: Int? = null,
    textColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null
) : KeyDef(
    Appearance.Image(
        src = R.drawable.ic_baseline_language_24,
        variant = Variant.AltForeground,
        viewId = R.id.button_lang,
        percentWidth = percentWidth,
        textColor = textColor,
        textColorMonet = textColorMonet,
        backgroundColor = backgroundColor,
        backgroundColorMonet = backgroundColorMonet,
        shadowColor = shadowColor,
        shadowColorMonet = shadowColorMonet
    ),
    setOf(
        Behavior.Press(KeyAction.LangSwitchAction),
        // Rime 专版：语言键短按做中/西文切换（CommonKeyActionListener），长按弹出
        // Rime 方案切换菜单（RimeSchemaMenuDialog），系统输入法选单仍可长按工具栏
        // 语言切换按钮进入。
        Behavior.LongPress(KeyAction.ShowRimeSchemaMenuAction)
    ),
    iconSlot = "keys.language"
)

/**
 * 空格键。
 *
 * @param swipe 划动时执行的 macro（可选）。**与「划动空格键以移动光标」开关互斥**：
 *   开关开启时光标手势完全接管空格键，这里配置的划动动作不生效；关闭开关后划动动作
 *   才接管（判定见 `SpaceSwipeMode.resolveSpaceSwipeEnabled`）。注意这里**不注册**
 *   [KeyDef.Behavior.Swipe]：通用 Swipe 绑定会把阈值改写成
 *   `disabledSwipeThreshold`/`inputSwipeThreshold`，那会直接打断光标模式的横向移动，
 *   因此空格键的手势在 `BaseKeyboard.createKeyView` 里自建。
 * @param swipeLabel 划动动作的提示文字（可选）。非空时改用 `AltText` 外观，
 *   让它像其它可划动键一样在键面上显示划动提示。
 */
class SpaceKey(
    percentWidth: Float = 0f,
    textColor: Int? = null,
    textColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null,
    val swipe: MacroAction? = null,
    val swipeLabel: String? = null
) : KeyDef(
    if (swipeLabel.isNullOrEmpty()) {
        Appearance.Text(
            displayText = " ",
            textSize = 13f,
            percentWidth = percentWidth,
            border = Border.Special,
            viewId = R.id.button_space,
            soundEffect = InputFeedbacks.SoundEffect.SpaceBar,
            textColor = textColor,
            textColorMonet = textColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
    } else {
        Appearance.AltText(
            displayText = " ",
            altText = swipeLabel,
            character = " ",
            textSize = 13f,
            percentWidth = percentWidth,
            border = Border.Special,
            viewId = R.id.button_space,
            textColor = textColor,
            textColorMonet = textColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
    },
    setOf(
        Behavior.Press(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_space))),
        Behavior.LongPress(KeyAction.SpaceLongPressAction)
    )
)

class ReturnKey(
    percentWidth: Float = 0.15f,
    textColor: Int? = null,
    textColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null,
    val swipe: MacroAction? = null,
    val swipeLabel: String? = null,
    val swipeUp: MacroAction? = null,
    val swipeDown: MacroAction? = null,
    val swipeUpLabel: String? = null,
    val swipeDownLabel: String? = null
) : KeyDef(
    if (swipeLabel.isNullOrEmpty() && swipeUpLabel.isNullOrEmpty() && swipeDownLabel.isNullOrEmpty()) {
        Appearance.Image(
            src = R.drawable.ic_baseline_keyboard_return_24,
            percentWidth = percentWidth,
            variant = Variant.Accent,
            border = Border.Special,
            viewId = R.id.button_return,
            soundEffect = InputFeedbacks.SoundEffect.Return,
            textColor = textColor,
            textColorMonet = textColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        )
    } else {
        Appearance.ImageAltText(
            src = R.drawable.ic_baseline_keyboard_return_24,
            altText = swipeUpLabel ?: if (swipeDownLabel == null) swipeLabel.orEmpty() else "",
            altText1 = swipeDownLabel,
            percentWidth = percentWidth,
            variant = Variant.Accent,
            border = Border.Special,
            viewId = R.id.button_return,
            soundEffect = InputFeedbacks.SoundEffect.Return,
            textColor = textColor,
            textColorMonet = textColorMonet,
            backgroundColor = backgroundColor,
            backgroundColorMonet = backgroundColorMonet,
            shadowColor = shadowColor,
            shadowColorMonet = shadowColorMonet
        ).apply {
            directionalSwipeLabels = swipeUpLabel != null || swipeDownLabel != null
        }
    },
    buildSet {
        add(Behavior.Press(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Return))))
        if (swipeUp != null || swipeDown != null) {
            add(Behavior.Swipe(
                upMacro = swipeUp,
                downMacro = swipeDown,
                legacyMacro = swipe,
                overrideDefaults = true
            ))
        } else {
            swipe?.let { add(Behavior.Swipe(it)) }
        }
    },
    arrayOf(
        Popup.Menu(
            arrayOf(
                Popup.Menu.Item(
                    "Emoji", R.drawable.ic_baseline_tag_faces_24, KeyAction.PickerSwitchAction(),
                    iconSlot = "keys.emoji"
                )
            )
        )
    ),
    iconSlot = "keys.return.default"
)

class ImageLayoutSwitchKey(
    @DrawableRes
    icon: Int,
    to: String,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.AltForeground,
    viewId: Int = -1,
    iconSlot: String? = null
) : KeyDef(
    Appearance.Image(
        src = icon,
        percentWidth = percentWidth,
        variant = variant,
        viewId = viewId
    ),
    setOf(
        Behavior.Press(KeyAction.LayoutSwitchAction(to))
    ),
    iconSlot = iconSlot
)

class ImagePickerSwitchKey(
    @DrawableRes
    icon: Int,
    to: PickerWindow.Key,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.AltForeground,
    viewId: Int = -1,
    iconSlot: String? = null
) : KeyDef(
    Appearance.Image(
        src = icon,
        percentWidth = percentWidth,
        variant = variant,
        viewId = viewId
    ),
    setOf(
        Behavior.Press(KeyAction.PickerSwitchAction(to))
    ),
    iconSlot = iconSlot
)

class TextPickerSwitchKey(
    text: String,
    to: PickerWindow.Key,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.AltForeground,
    viewId: Int = -1
) : KeyDef(
    Appearance.Text(
        displayText = text,
        textSize = 16f,
        percentWidth = percentWidth,
        variant = variant,
        viewId = viewId,
        textStyle = Typeface.BOLD
    ),
    setOf(
        Behavior.Press(KeyAction.PickerSwitchAction(to))
    )
)

/**
 * 空白占位键：占据布局宽度、不接收任何输入、也不参与任何状态改写的纯外观键。
 *
 * ## 两种形态
 *
 * - **完全空白**（主/副字符都留空、自定义颜色关闭，即默认）：键面什么都不画，
 *   键底完全透明，看起来就是键盘上的一块空白区域。用途是把某一行的按键挤到想要的
 *   位置，或者留出一块拇指休息区。
 * - **纯装饰**（填了字符或打开自定义颜色）：键面画用户指定的文字，但**依然不可点击**。
 *   与"把普通键的点击动作做成空宏"有本质区别——后者仍会震动、仍有按压高亮、仍会
 *   进入长按/滑动的判定，用户会以为自己点到了什么却没反应。
 *
 * ## 为什么不可点击这件事必须在类型层面成立
 *
 * [behaviors] 与 [popup] 都为空，`BaseKeyboard.applyBehaviorPopupBindings` 会据此把
 * 视图置为 `isEnabled = false` / `isClickable = false`——触摸事件根本不进入这个视图。
 * 这不是"点了没反应"，而是**完全不参与触摸**，因此也不会挡住同一位置上父容器的滚动。
 */
class PlaceholderKey(
    displayText: String = "",
    altText: String = "",
    percentWidth: Float = 0.1f,
    transparentBackground: Boolean = true,
    textColor: Int? = null,
    textColorMonet: String? = null,
    altTextColor: Int? = null,
    altTextColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null,
    textSize: Float = 16f
) : KeyDef(
    Appearance.AltText(
        displayText = displayText,
        altText = altText,
        character = displayText,
        textSize = textSize,
        percentWidth = percentWidth,
        // 两种形态的"外形"要一起切，不能只切 transparentBackground：
        // - 完全空白：不画边框、不留外边距。留了外边距会在相邻键之间露出底色缝隙；
        //   而 Border 一旦不是 Off，KeyView 就会走 applyStandardBackground 画出键底。
        // - 纯装饰：反过来——用默认边框与默认间距，才是一枚"和别的键一样、只是点不动"的键。
        border = if (transparentBackground) Border.Off else Border.Default,
        margin = !transparentBackground,
        textColor = textColor,
        textColorMonet = textColorMonet,
        altTextColor = altTextColor,
        altTextColorMonet = altTextColorMonet,
        backgroundColor = backgroundColor,
        backgroundColorMonet = backgroundColorMonet,
        shadowColor = shadowColor,
        shadowColorMonet = shadowColorMonet
    ).apply {
        this.transparentBackground = transparentBackground
        this.staticDisplay = true
    },
    // 空集 + 无 popup = 不可点击、无长按、无滑动，见类注释。
    emptySet(),
    null
)

class MiniSpaceKey(
    percentWidth: Float = 0.15f,
    textColor: Int? = null,
    textColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null
) : KeyDef(
    Appearance.Image(
        src = R.drawable.ic_baseline_space_bar_24,
        percentWidth = percentWidth,
        variant = Variant.Alternative,
        viewId = R.id.button_mini_space,
        textColor = textColor,
        textColorMonet = textColorMonet,
        backgroundColor = backgroundColor,
        backgroundColorMonet = backgroundColorMonet,
        shadowColor = shadowColor,
        shadowColorMonet = shadowColorMonet
    ),
    setOf(
        Behavior.Press(KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_space)))
    ),
    iconSlot = "keys.space"
)

class NumPadKey(
    displayText: String,
    val sym: Int,
    textSize: Float = 16f,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.Normal,
    textColor: Int? = null,
    textColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null
) : KeyDef(
    Appearance.Text(
        displayText,
        textSize = textSize,
        percentWidth = percentWidth,
        variant = variant,
        textColor = textColor,
        textColorMonet = textColorMonet,
        backgroundColor = backgroundColor,
        backgroundColorMonet = backgroundColorMonet,
        shadowColor = shadowColor,
        shadowColorMonet = shadowColorMonet
    ),
    setOf(
        Behavior.Press(KeyAction.SymAction(KeySym(sym), NumLockState))
    )
)

/**
 * Macro 按键，支持自定义 tap/swipe/longPress 行为
 *
 * 显示文本的取值优先级：显式设置的 [displayText] > [label]（标签文本）。
 * 前者按用户填写的原样渲染（不做 Shift/Caps 大小写改写），因为那是用户的显式选择；
 * 未填 displayText 时回落到 label，并保留原有的 Shift 大写行为。
 *
 * @param label 标签文本（未设置 displayText 时作为显示文本）
 * @param displayText 用户显式设置的显示文本（可选，优先级高于 label）
 * @param altLabel 旧版统一划动备选显示文本（可选，兼容旧布局）
 * @param swipeUpLabel 上滑时显示的标签文本（可选）
 * @param swipeDownLabel 下滑时显示的标签文本（可选）
 * @param longPressLabel 长按时在 Popup 选单中显示的标签文本（可选）
 * @param tap 点击时执行的 macro
 * @param swipeUp 上划执行的 macro（可选）
 * @param swipeDown 下划执行的 macro（可选）
 * @param swipe 旧版单个划动 macro，作为兼容回退（可选）
 * @param longPress 长按时执行的 macro（可选）
 * @param percentWidth 按键宽度比例
 * @param variant 样式变体
 * @param popup 弹出菜单（可选）
 */
class MacroKey(
    val label: String,
    val displayText: String? = null,
    val character: String = label,
    val altLabel: String? = null,
    val swipeUpLabel: String? = null,
    val swipeDownLabel: String? = null,
    val longPressLabel: String? = null,
    val tap: MacroAction,
    val swipeUp: MacroAction? = null,
    val swipeDown: MacroAction? = null,
    val swipe: MacroAction? = null,
    val longPress: MacroAction? = null,
    percentWidth: Float = 0.1f,
    variant: Variant = Variant.Normal,
    popup: Array<Popup>? = null,
    textColor: Int? = null,
    textColorMonet: String? = null,
    altTextColor: Int? = null,
    altTextColorMonet: String? = null,
    backgroundColor: Int? = null,
    backgroundColorMonet: String? = null,
    shadowColor: Int? = null,
    shadowColorMonet: String? = null
) : KeyDef(
    Appearance.AltText(
        displayText = displayText?.takeIf { it.isNotEmpty() } ?: label,
        altText = if (swipeUpLabel != null || swipeDownLabel != null) swipeUpLabel.orEmpty() else altLabel.orEmpty(),
        altText1 = swipeDownLabel,
        character = character,
        textSize = 23f,
        percentWidth = percentWidth,
        variant = variant,
        textColor = textColor,
        textColorMonet = textColorMonet,
        altTextColor = altTextColor,
        altTextColorMonet = altTextColorMonet,
        backgroundColor = backgroundColor,
        backgroundColorMonet = backgroundColorMonet,
        shadowColor = shadowColor,
        shadowColorMonet = shadowColorMonet,
        keepDisplayTextCase = !displayText.isNullOrEmpty()
        ).apply {
            directionalSwipeLabels = swipeUpLabel != null || swipeDownLabel != null
        },
    buildBehaviors(tap, swipeUp, swipeDown, swipe, longPress),
    // Popup 候选沿用「实际显示的文本」：显式 displayText 优先，未设置时回落到标签文本，
    // 与改动前（label 即显示文本）的候选列表保持一致。
    buildPopup(popup, tap, displayText?.takeIf { it.isNotEmpty() } ?: label, longPress, longPressLabel)
) {
    private companion object {
        private val FCITX_SYMBOL_LABELS = mapOf(
            "grave" to "`",
            "asciitilde" to "~",
            "tilde" to "~",
            "minus" to "-",
            "underscore" to "_",
            "equal" to "=",
            "plus" to "+",
            "bracketleft" to "[",
            "braceleft" to "{",
            "bracketright" to "]",
            "braceright" to "}",
            "backslash" to "\\",
            "bar" to "|",
            "semicolon" to ";",
            "colon" to ":",
            "apostrophe" to "'",
            "quotedbl" to "\"",
            "comma" to ",",
            "less" to "<",
            "period" to ".",
            "greater" to ">",
            "slash" to "/",
            "question" to "?",
            "exclam" to "!",
            "at" to "@",
            "numbersign" to "#",
            "dollar" to "$",
            "percent" to "%",
            "asciicircum" to "^",
            "ampersand" to "&",
            "asterisk" to "*",
            "parenleft" to "(",
            "parenright" to ")",
            "bracket_l" to "[",
            "bracket_r" to "]",
            "multiply" to "*",
            "add" to "+",
            "subtract" to "-",
            "divide" to "/",
            "separator" to ",",
            "kp_0" to "0",
            "kp_1" to "1",
            "kp_2" to "2",
            "kp_3" to "3",
            "kp_4" to "4",
            "kp_5" to "5",
            "kp_6" to "6",
            "kp_7" to "7",
            "kp_8" to "8",
            "kp_9" to "9",
            "kp_add" to "+",
            "kp_subtract" to "-",
            "kp_multiply" to "*",
            "kp_divide" to "/",
            "kp_decimal" to ".",
            "kp_equal" to "=",
            "kp_separator" to ",",
            "kp_tab" to "Tab",
            "kp_space" to "Space",
            "kp_enter" to "Enter"
        )

        fun buildBehaviors(
            tap: MacroAction,
            swipeUp: MacroAction?,
            swipeDown: MacroAction?,
            swipe: MacroAction?,
            longPress: MacroAction?
        ): Set<Behavior> {
            return buildSet {
                if (tap.hasExecutableStep()) {
                    add(Behavior.Press(tap))
                }
                val executableUp = swipeUp?.takeIf { it.hasExecutableStep() }
                val executableDown = swipeDown?.takeIf { it.hasExecutableStep() }
                val executableLegacy = swipe?.takeIf { it.hasExecutableStep() }
                if (executableUp != null || executableDown != null || executableLegacy != null) {
                    add(
                        Behavior.Swipe(
                            upMacro = executableUp,
                            downMacro = executableDown,
                            legacyMacro = executableLegacy,
                            overrideDefaults = executableUp != null || executableDown != null
                        )
                    )
                }
                longPress?.takeIf { it.hasExecutableStep() }?.let { add(Behavior.LongPress(it)) }
            }
        }

        /**
         * Build popup based on macro content
         * - If longPress macro is configured, it appears as the first candidate in the popup
         * - If tap macro has only one tap key, generate popup for that key
         * - Otherwise, generate popup based on label (preview on press, menu on long press)
         */
        fun buildPopup(
            explicitPopup: Array<Popup>?,
            tap: MacroAction,
            label: String,
            longPress: MacroAction? = null,
            longPressLabel: String? = null
        ): Array<Popup>? {
            // If explicit popup is provided, use it
            if (explicitPopup != null) {
                return explicitPopup
            }

            val tapExecutable = tap.hasExecutableStep()
            val longPressExecutable = longPress?.hasExecutableStep() == true
            if (!tapExecutable && !longPressExecutable) {
                return null
            }

            val popupList = mutableListOf<Popup>()

            // Check if there's exactly one tap step with one key
            val singleTapKey = if (tap.steps.size == 1 && tap.steps[0] is MacroStep.Tap) {
                val tapStep = tap.steps[0] as MacroStep.Tap
                if (tapStep.keys.size == 1) tapStep.keys[0] else null
            } else null

            val otherPopups = if (singleTapKey != null) {
                // Generate popup based on the single key
                when (singleTapKey) {
                    is KeyRef.Fcitx -> {
                        val display = FCITX_SYMBOL_LABELS[singleTapKey.code.lowercase()] ?: singleTapKey.code
                        // Keep AlphabetKey-like alt preview for letters.
                        if (display.length == 1 && display[0].isLetter()) {
                            val upper = display.uppercase()
                            arrayOf(
                                Popup.AltPreview(display, upper),
                                Popup.Keyboard.Preset(display)
                            )
                        } else {
                            // Symbol/emoji/non-letter keys should still expose popup keyboard like SymbolKey.
                            arrayOf(
                                Popup.Preview(display),
                                Popup.Keyboard.Preset(display)
                            )
                        }
                    }
                    is KeyRef.Android -> {
                        // Android key codes show as numbers
                        arrayOf(Popup.Preview(singleTapKey.code.toString()))
                    }
                }
            } else {
                // Non-single-tap case: generate popup based on label
                // If label is single letter, generate same popup as AlphabetKey
                if (label.length == 1 && label[0].isLetter()) {
                    val upper = label.uppercase()
                    arrayOf(
                        Popup.AltPreview(label, upper),
                        Popup.Keyboard.Preset(label)
                    )
                } else {
                    // Non-letter labels should still expose popup keyboard.
                    arrayOf(
                        Popup.Preview(label),
                        Popup.Keyboard.Preset(label)
                    )
                }
            }

            popupList.addAll(otherPopups)

            // Register LongPressKeyboard after previews so its gesture listener runs first on key-up.
            // The longPress macro still appears as the first candidate inside popup keyboard UI.
            if (longPressExecutable) {
                val validLongPress = requireNotNull(longPress)
                val displayLabel = longPressLabel?.takeIf { it.isNotBlank() } ?: label
                popupList.add(Popup.LongPressKeyboard(displayLabel, validLongPress, label))
            }
            return popupList.toTypedArray()
        }

        private fun MacroAction.hasExecutableStep(): Boolean {
            return steps.any { step ->
                when (step) {
                    is MacroStep.Down -> step.keys.isNotEmpty()
                    is MacroStep.Up -> step.keys.isNotEmpty()
                    is MacroStep.Tap -> step.keys.isNotEmpty()
                    is MacroStep.Text -> step.text.isNotEmpty()
                    is MacroStep.Edit -> step.action.isNotBlank()
                    is MacroStep.AppAction -> step.id.isNotBlank()
                    is MacroStep.Shortcut -> true
                    is MacroStep.LayerSwitch ->
                        step.mode == KeyAction.LayerSwitchMode.BACK || step.target.isNotBlank()
                }
            }
        }
    }
}
