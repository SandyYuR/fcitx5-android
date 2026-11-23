> 本页的 Fcitx 键名和布局字段属于保留的前端兼容格式，直连桥会将按键转换给 Rime；这不表示 APK 仍运行 Fcitx 原生引擎。完整 XKB Compose 仍在已知未完成范围内。

# Fcitx 键名速查

在 MacroKey 的 step 中，`{"fcitx": "<sym>"}` 形式的 KeyRef 用一个字符串引用一个具体按键。本页给出常用的 `<sym>` 列表与中文说明，并按用途分组。

::: tip 选用建议
- 编辑器里直接选 —— 编辑器使用内置的常用键名列表（如 `Enter`、`Space`、`A`–`Z`、`Ctrl_L`），写法与本页的 X11 风格名称不完全相同
- 手写 JSON 时按本页查
- 数字 / 字母直接写字面形式（`a`、`1`），不需要 X11 风格的 `KEY_A`
- 推荐用 `fcitx` 形式而不是 [`android` 键码](/features/keys/macro-key#keyref-引用按键的两种方式)（编辑器不暴露 android 形式）
:::

## 编辑 / 文本相关

| `<sym>` | 含义 |
|---------|------|
| `Return` | 回车 / 确定 |
| `BackSpace` | 退格（向前删除） |
| `Delete` | 删除（向后删除） |
| `Tab` | 制表符 |
| `Escape` | ESC，常用于取消候选 / 退出输入 |
| `Insert` | 插入键 |
| `space` | 空格 |

## 方向 / 光标

| `<sym>` | 含义 |
|---------|------|
| `Up` / `Down` / `Left` / `Right` | 四向方向键 |
| `Home` | 行首 |
| `End` | 行尾 |
| `Page_Up` | 向上翻页 |
| `Page_Down` | 向下翻页 |

## 修饰键（Modifier）

| `<sym>` | 含义 |
|---------|------|
| `Shift_L` / `Shift_R` | 左 / 右 Shift |
| `Ctrl_L` / `Ctrl_R` | 左 / 右 Ctrl |
| `Alt_L` / `Alt_R` | 左 / 右 Alt |
| `Meta_L` / `Meta_R` | 左 / 右 Meta（Win 键） |
| `Super_L` / `Super_R` | 左 / 右 Super |
| `Hyper_L` / `Hyper_R` | 左 / 右 Hyper |
| `Caps_Lock` | 大写锁定 |

`shortcut` step 的 `modifiers` 只识别上表的 `Ctrl_*` / `Shift_*` / `Alt_*` / `Meta_*` / `Super_*` / `Hyper_*`，以及 `Mode_switch`、`ISO_Level3_Shift`、`ISO_Level5_Shift`；写成 X11 的 `Control_L` 会被当作非修饰键忽略。

::: tip Modifier 与 shortcut step
通常不需要手写 `down` + `up` 来按住 modifier，用 [`shortcut` step](/features/keys/macro-key#_6-shortcut-快捷键组合) 一步搞定：

```json
{ "type": "shortcut",
  "modifiers": [ { "fcitx": "Ctrl_L" } ],
  "key": { "fcitx": "c" } }
```
:::

## 字母与数字

| 范围 | 写法 |
|------|------|
| 字母 | `a` ~ `z` |
| 数字 | `0` ~ `9` |

::: tip 字母按键与大小写
单个字母的大小写有含义：写小写（如 `a`）按普通按键发送；写大写（如 `A`）会自动附带 Shift 发送，输出大写。应用内宏编辑器的字母列表是 `A`–`Z`，选中后保存的就是大写形式。模拟 Caps Lock 开启时，两者的 Shift 判断反转。

手写 JSON 时需要小写输出就写小写。需要字面固定的文本时，用 [`text` step](/features/keys/macro-key#_4-text-直接提交文本) 直接提交更可靠。
:::

## 常用 ASCII 符号

代码里这些符号有专门的命名（X11 keysym 风格），写时用名字而不是字面字符：

| `<sym>` | 字符 | 含义 |
|---------|------|------|
| `space` | ` ` | 空格 |
| `exclam` | `!` | 感叹号 |
| `at` | `@` | 艾特 |
| `numbersign` | `#` | 井号 |
| `dollar` | `$` | 美元 |
| `percent` | `%` | 百分号 |
| `ampersand` | `&` | 和 |
| `apostrophe` | `'` | 单引号 |
| `parenleft` / `parenright` | `(` / `)` | 左 / 右圆括号 |
| `asterisk` | `*` | 星号 |
| `plus` | `+` | 加号 |
| `comma` | `,` | 逗号 |
| `minus` | `-` | 减号 / 连字符 |
| `period` | `.` | 句点 |
| `slash` | `/` | 斜杠 |
| `colon` | `:` | 冒号 |
| `semicolon` | `;` | 分号 |
| `less` / `greater` | `<` / `>` | 小于 / 大于 |
| `equal` | `=` | 等号 |
| `question` | `?` | 问号 |
| `bracketleft` / `bracketright` | `[` / `]` | 左 / 右方括号 |
| `backslash` | `\` | 反斜杠 |
| `asciicircum` | `^` | 抑扬符 |
| `underscore` | `_` | 下划线 |
| `grave` | `` ` `` | 反引号 |
| `braceleft` / `braceright` | `{` / `}` | 左 / 右花括号 |
| `bar` | `\|` | 竖线 |
| `asciitilde` | `~` | 波浪号 |
| `quotedbl` | `"` | 双引号 |

::: tip 直接走 text step 更简单
单纯想"插入一个字符串"时，用 [`text` step](/features/keys/macro-key#_4-text-直接提交文本) 直接写字面字符更直观，不需要查 keysym 名：

```json
{ "type": "text", "text": "@" }
```

`fcitx` 形式适合需要触发 IME 按键事件（拼音候选、Rime 翻页等）的场景。
:::

## 功能键

| `<sym>` | 含义 |
|---------|------|
| `F1` ~ `F12` | 12 个标准功能键 |

## 小键盘（Numpad）

| `<sym>` | 含义 |
|---------|------|
| `KP_0` ~ `KP_9` | 小键盘数字 |
| `KP_Enter` | 小键盘回车 |
| `KP_Add` / `KP_Subtract` / `KP_Multiply` / `KP_Divide` | 加 / 减 / 乘 / 除 |
| `KP_Decimal` | 小数点 |

## IME 特定切换键

主要用于日 / 韩等 CJK 引擎；中文方案通常用不上，列出供参考：

| `<sym>` | 含义 |
|---------|------|
| `Eisu_toggle` | 日文：英数切换 |
| `Kana_Lock` | 日文：假名锁定 |
| `Hiragana_Katakana` | 日文：平假名 / 片假名切换 |
| `Zenkaku_Hankaku` | 日文：全角 / 半角切换 |
| `Hangul` | 韩文：韩字模式 |
| `Hangul_Hanja` | 韩文：汉字候选 |

## 完整列表去哪查

本页列的是常用子集。应用内宏编辑器的可选键名是内置列表（含字母、数字、修饰键、方向与编辑键、符号、小键盘、`F1`–`F35` 及多媒体键）。不在内置映射里的名称会按 Fcitx 键名直接交给输入引擎处理，属于标准 X11 keysym 命名的多数也能用 —— 实在不确定可在 [issues](https://github.com/SandyYuR/fcitx5-android/issues) 中问。

## 相关页面

- [MacroKey 细化](/features/keys/macro-key) —— step 类型与 KeyRef 引用方式
- [MacroKey 编辑器](/features/editor/macrokey-editor)
- [按键类型总览](/features/keys/overview)
