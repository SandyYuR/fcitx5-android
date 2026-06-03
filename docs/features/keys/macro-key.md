# MacroKey 宏键

`MacroKey` 是 fx 引入的最具表现力的按键类型 —— **一个键可以绑定点击 / 滑动 / 长按三个独立的"宏序列"**，每个序列由若干 **step** 组成，可依次发送按键、提交文本、执行编辑命令、切换布局层、触发应用动作等。

::: tip 如何编辑
推荐通过 [MacroKey 编辑器](/features/editor/macrokey-editor) 可视化创建，避免手写 JSON 出错。如果你想手改，本页列出全部字段与 step 类型作为参考。
:::

## 字段一览

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"MacroKey"` |
| `label` | string | ✓ | 键面主标签 |
| `altLabel` | string |  | swipe 时显示的副标签 |
| `longPressLabel` | string |  | 长按弹出菜单中的提示标签 |
| `displayText` | string \| map |  | 与 [overview](/features/keys/overview#显示文本-displaytext-的多模式格式) 一致的多模式显示文本 |
| **`tap`** | object | ✓ | 点击时执行的 [MacroAction](#macroaction-结构) |
| `swipe` | object |  | 滑动时执行的 MacroAction |
| `longPress` | object |  | 长按时执行的 MacroAction |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

## MacroAction 结构

`tap` / `swipe` / `longPress` 三者的值都是一个 **MacroAction**：

```json
{ "macro": [ <step 1>, <step 2>, ... ] }
```

其中 `macro` 数组中的每个元素是一个 **step** 对象，按顺序执行。step 类型由对象内的 `type` 字段决定。

## Step 类型清单

| `type` | 用途 | 关键字段 |
|--------|------|---------|
| [`tap`](#1-tap-点击键) | 按下并立即释放一个或多个键 | `keys: KeyRef[]` |
| [`down`](#2-down-按下) | 按下键（不释放，常与 up 配对） | `keys: KeyRef[]` |
| [`up`](#3-up-释放) | 释放之前按下的键 | `keys: KeyRef[]` |
| [`text`](#4-text-直接提交文本) | 直接 commit 一段文本 | `text: string` |
| [`edit`](#5-edit-编辑命令) | 复制 / 粘贴 / 全选等系统编辑命令 | `action: string` |
| [`shortcut`](#6-shortcut-快捷键组合) | modifier + 主键的组合键 | `modifiers: KeyRef[]`, `key: KeyRef` |
| [`app`](#7-app-应用内动作) | 触发应用内预定义动作 | `id: string` |
| [`layer`](#8-layer-布局层切换) | 切换 / 单次切换 文本布局层 | `mode: "to"\|"osl"`, `target: string` |

### KeyRef 引用按键的两种方式

任何接受 `keys` / `key` 字段的 step，里面的元素都是 KeyRef 对象：

| 形式 | 含义 | 示例 |
|------|------|------|
| `{"fcitx": "<sym>"}` | Fcitx 虚拟键名（**推荐**） | `{"fcitx": "Return"}`, `{"fcitx": "Shift_L"}`, `{"fcitx": "a"}`, `{"fcitx": "space"}` |
| `{"android": <code>}` | Android 键码（整数，*高级用法*） | `{"android": 29}`（即字母 A） |

> 常用 Fcitx 键名：`Return` / `BackSpace` / `Tab` / `space` / `Escape` / `Shift_L` / `Control_L` / `Alt_L` / `Up` / `Down` / `Left` / `Right` / `Home` / `End` / `Delete` / 字母数字直接小写 / `F1`–`F12`。字母键的大小写由 Shift / Caps 等状态决定；完整分类列表见 [Fcitx 键名速查](/features/keys/fcitx-keys)。

::: warning Android KeyRef 不在编辑器 UI 中
[MacroKey 编辑器](/features/editor/macrokey-editor) **只暴露 `fcitx` 形式**的按键引用 —— 没有"按 Android 键码"的输入控件。

`{"android": <code>}` 仅在 **手改 JSON** 时可用。**如果你不清楚目标键码、不熟悉 Android `KeyEvent` 常量含义，请不要使用它**：

- 不同 Android 版本 / 厂商 ROM 下，部分键码语义可能不一致
- 同一个键大多在 Fcitx 键名表里都有对应（且语义更稳定），优先用 `fcitx`
- 编辑器侧不会读取 / 显示 Android KeyRef，下次进编辑器再保存可能把它丢失（建议这类按键不要再用编辑器二次编辑）
:::

---

### 1. `tap` 点击键

按下并立即释放，等价于敲一次键。

```json
{ "type": "tap", "keys": [ { "fcitx": "Return" } ] }
```

多键一次性"敲下"（顺序）：

```json
{ "type": "tap", "keys": [ { "fcitx": "BackSpace" }, { "fcitx": "BackSpace" } ] }
```

### 2. `down` 按下

按下键但不释放，需要后续用 `up` 释放。常用于自定义 modifier 组合（如果 `shortcut` 不够用）。

```json
{ "type": "down", "keys": [ { "fcitx": "Shift_L" } ] }
```

### 3. `up` 释放

释放之前 `down` 过的键。

```json
{ "type": "up", "keys": [ { "fcitx": "Shift_L" } ] }
```

### 4. `text` 直接提交文本

绕过按键模拟，直接 commit 一段字符串到输入框。常用于插入符号、常用片段。

```json
{ "type": "text", "text": ".com" }
```

```json
{ "type": "text", "text": "https://" }
```

### 5. `edit` 编辑命令

触发系统级编辑动作，避免手写 `Ctrl+C` 之类的组合（也避开了不同系统/输入框的兼容差异）。

| `action` 值 | 含义 |
|-------------|------|
| `copy` | 复制 |
| `cut` | 剪切 |
| `paste` | 粘贴 |
| `selectAll` | 全选 |
| `undo` | 撤销 |
| `redo` | 重做 |

```json
{ "type": "edit", "action": "paste" }
```

### 6. `shortcut` 快捷键组合

modifier 键 + 主键的组合，比手写 `down`/`up` 简单且自动处理释放顺序。

```json
{
  "type": "shortcut",
  "modifiers": [ { "fcitx": "Ctrl_L" } ],
  "key": { "fcitx": "c" }
}
```

```json
{
  "type": "shortcut",
  "modifiers": [ { "fcitx": "Ctrl_L" }, { "fcitx": "Shift_L" } ],
  "key": { "fcitx": "z" }
}
```

### 7. `app` 应用内动作

触发 Fcitx5-android 内置的应用级动作，`id` 为动作标识。常见用法：打开特定设置页、切换主题、触发字体集等。具体 id 列表以 [MacroKey 编辑器](/features/editor/macrokey-editor) 中可选项为准。

```json
{ "type": "app", "id": "open_settings" }
```

### 8. `layer` 布局层切换

跳转到指定文本布局层（layer），用于多层键盘（如字母层↔符号层）。

| `mode` 值 | 含义 |
|-----------|------|
| `to` | 直接切换并停留在目标层 |
| `osl` | One-Shot Layer：切换到目标层，按下一个键后自动返回 |

```json
{ "type": "layer", "mode": "to",  "target": "symbol" }
```

```json
{ "type": "layer", "mode": "osl", "target": "shifted" }
```

## 完整示例

::: details 邮箱键 @ / .com
点击发 `@`，滑动发 `.com`：

```json
{
  "type": "MacroKey",
  "label": "@",
  "altLabel": ".com",
  "tap":   { "macro": [ { "type": "text", "text": "@" } ] },
  "swipe": { "macro": [ { "type": "text", "text": ".com" } ] }
}
```
:::

::: details 复制 / 剪切 / 粘贴一键三态
点击复制，滑动剪切，长按粘贴：

```json
{
  "type": "MacroKey",
  "label": "📋",
  "altLabel": "✂",
  "longPressLabel": "粘贴",
  "tap":       { "macro": [ { "type": "edit", "action": "copy"  } ] },
  "swipe":     { "macro": [ { "type": "edit", "action": "cut"   } ] },
  "longPress": { "macro": [ { "type": "edit", "action": "paste" } ] }
}
```
:::

::: details 字符串模板序列
点击输出 `<div></div>` 并把光标移到中间：

```json
{
  "type": "MacroKey",
  "label": "<div>",
  "tap": {
    "macro": [
      { "type": "text", "text": "<div></div>" },
      { "type": "tap",  "keys": [ { "fcitx": "Left" }, { "fcitx": "Left" },
                                  { "fcitx": "Left" }, { "fcitx": "Left" },
                                  { "fcitx": "Left" }, { "fcitx": "Left" } ] }
    ]
  }
}
```
:::

::: details 符号层 OSL（一次性切换）
点击进入符号层、输入一个符号后自动返回：

```json
{
  "type": "MacroKey",
  "label": "Sym",
  "tap": {
    "macro": [ { "type": "layer", "mode": "osl", "target": "symbol" } ]
  }
}
```
:::

::: details 带 modifier 的混合宏
按 Home → Shift+End → 复制（选中整行复制）：

```json
{
  "type": "MacroKey",
  "label": "拷贝行",
  "tap": {
    "macro": [
      { "type": "tap", "keys": [ { "fcitx": "Home" } ] },
      { "type": "shortcut", "modifiers": [ { "fcitx": "Shift_L" } ], "key": { "fcitx": "End" } },
      { "type": "edit", "action": "copy" }
    ]
  }
}
```
:::

## 常见问题

**Q: 为什么我的 `down` / `up` 不成对生效？**
A: 推荐用 `shortcut` 代替手写 down/up；若必须用 down/up，请确保最终 step 中释放所有按下的键，否则 modifier 会"卡住"。

**Q: `text` 与 `tap` 的字母键有什么区别？**
A: `text` 是直接提交字符串（IME commit），不经过按键事件，不会触发输入法的候选 / 联想。`tap` 字母键会被输入法处理（如拼音可能产生候选）。

**Q: app action 的 id 列表去哪查？**
A: 应用内 [MacroKey 编辑器](/features/editor/macrokey-editor) 选择 step 类型为 app 时会列出当前版本所有可用动作。id 集合会随版本迭代。

## 相关页面

- [MacroKey 编辑器](/features/editor/macrokey-editor) —— 可视化编辑入口
- [Fcitx 键名速查](/features/keys/fcitx-keys) —— `{"fcitx": "<sym>"}` 可用的 sym 列表
- [按键类型总览](/features/keys/overview) —— 共通字段
- [Compose Override](/features/keyboard/compose-override)
- [滑动操作](/features/keyboard/swipe-actions)
