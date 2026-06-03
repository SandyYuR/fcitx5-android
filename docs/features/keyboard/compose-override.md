# Compose Override 按键

**Compose Override** 是按键的一个可选字段：当 IME 处于 **正在输入** 的状态（有预编辑文本或候选词时）键面会临时切换为你指定的"覆盖版"，输入结束后自动还原。

最直观的用例：拼音输入声母时，把字母键临时换成带声调的形式（如 `a` → `ā`），帮助记忆 / 视觉提示。

## 生效条件

代码里的判断很明确（见 `KeyboardWindow` / `BaseKeyboard` 的 composing 状态更新）：

```
composing = !preeditEmpty || !candidateEmpty
```

也就是 **只要 IME 有非空的预编辑或候选**，就进入 composing 状态，所有按键的 `composeOverride` 立刻生效；一旦预编辑和候选都清空（上屏、退出、清除），立刻恢复原样。

::: tip 由 IME 状态触发，不是开关
你不需要手动"按某个键"切到 compose 层 —— 任何方案在打字过程中自然进入这个状态。退出输入（上屏 / Esc / 删完）也自然还原。
:::

## 字段结构

`composeOverride` 写在按键对象内部，本质上是一份"compose 状态下要替换显示和行为的按键定义"，不是对外层按键做增量 patch。

```json
{
  "type": "AlphabetKey",
  "main": "a",
  "alt": "1",
  "composeOverride": {
    "type": "AlphabetKey",
    "main": "ā",
    "alt": "1",
    "independentColor": false
  }
}
```

### 按键定义字段

`composeOverride` 内部按一个按键定义来解析。`type` 可以省略，解析时会自动补成外层按键的 `type`；文档示例显式写出 `type`，是为了更清楚地表达它是一份按键定义。

常见类型需要写的字段：

| 外层类型 | composeOverride 里的按键定义字段 |
|----------|-------------------------------|
| `AlphabetKey` | `type` / `main` / `alt` / `displayText` / 颜色字段 |
| `MacroKey` | `type` / `label` / `tap` / `altLabel` / `swipe` / `longPress` / 颜色字段 |
| `SymbolKey` | `type` / `label` / 颜色字段 |
| `LayoutSwitchKey` | `type` / `label` / `subLabel` / 颜色字段 |

颜色字段含 `textColor` / `altTextColor` / `backgroundColor` / `shadowColor` 及对应的 Monet 版（参见 [按键类型总览](/features/keys/overview#颜色字段约定)）。

### `independentColor` 字段（重要）

| 取值 | 行为 |
|------|------|
| `false`（默认） | 继承外层按键的颜色；其余显示和行为来自 composeOverride 的按键定义 |
| `true` | 让 composeOverride 里的颜色字段独立生效，与外层不混 |

### 字段约束

`composeOverride` 是一份临时按键定义，有几个硬性限制：

- **不能改尺寸**：`weight`、`rowHeightPercent` 在 composeOverride 里会被忽略（保存编辑器时会自动剔除）。compose 是临时换面，不重排键盘
- **不能嵌套**：composeOverride 里再写一个 composeOverride **不被允许**（解析时丢弃）
- **type 应保持一致**：composeOverride 的 `type` 缺省时会自动推导成外层按键的 type；编辑器按同类型配置。不要手写成另一个按键类型，否则显示和行为可能与外层按键不匹配
- **行为字段按类型写齐**：例如 `MacroKey` 必须有 `tap`；`AlphabetKey` 建议同时写 `main` 和 `alt`，不要把 composeOverride 当成“只覆盖某个字段”的简写

## 真实示例

::: details 拼音字母换声调形式
基础键面是 `a`，输入过程中（拼音 composing）显示为 `ā`：

```json
{
  "type": "AlphabetKey",
  "main": "a",
  "alt": "1",
  "composeOverride": {
    "type": "AlphabetKey",
    "main": "ā",
    "alt": "1"
  }
}
```
:::

::: details 给 compose 状态加独立颜色
输入中字母颜色变蓝，帮助视觉区分"在打字 / 没在打字"：

```json
{
  "type": "AlphabetKey",
  "main": "e",
  "alt": "3",
  "composeOverride": {
    "type": "AlphabetKey",
    "main": "ē",
    "alt": "3",
    "textColor": "0xFF1976D2",
    "independentColor": true
  }
}
```

不写 `independentColor: true` 的话，`textColor` 不会生效（仍会继承外层颜色）。
:::

::: details MacroKey 在 compose 时换动作
平时这个键发 `?`；输入过程中（拼音候选界面）改为"删除一个字符"，方便快速纠错：

```json
{
  "type": "MacroKey",
  "label": "?",
  "tap": { "macro": [ { "type": "text", "text": "?" } ] },
  "composeOverride": {
    "type": "MacroKey",
    "label": "⌫",
    "tap": { "macro": [ { "type": "tap", "keys": [ { "fcitx": "BackSpace" } ] } ] }
  }
}
```
:::

## 适用 / 不适用场景

✅ **适合用**

- 输入时希望键面给出辅助信息（声调字母、拼音对照、双拼提示等）
- 输入时需要不同的辅助动作（compose 时按某键就翻页 / 删字 / 选字）
- compose 状态独立配色，强化"我正在输入"的视觉反馈

❌ **不适合用**

- 想"长期切换"键盘外观 —— 用 [LayoutSwitchKey](/features/keys/layout-switch-key) 切布局文件
- 想"按一下就进 compose 状态" —— compose 状态由 IME 决定，不能用按键强行触发
- 想给整行 / 整个键盘换颜色 —— composeOverride 是按键级别，不是键盘级别

## 与其他机制的对比

| 机制 | 何时生效 | 触发方 |
|------|---------|--------|
| **Compose Override** | IME 正在输入（有 preedit / 候选） | 自动，IME 状态驱动 |
| [Layer 切换（`layer` step）](/features/keys/macro-key#8-layer-布局层切换) | 用户按下绑定该 step 的按键 | 手动 |
| [LayoutSwitchKey](/features/keys/layout-switch-key) | 用户按下该键，切换到目标布局文件 | 手动 |

## 编辑器支持

[键盘布局编辑器](/features/editor/layout-editor) 中，每个按键的属性面板都有 "Compose Override" 折叠区，可以可视化设置上述字段，不必手写 JSON。

## 相关页面

- [按键类型总览](/features/keys/overview) —— 共通字段（颜色、weight、rowHeightPercent）
- [MacroKey 细化](/features/keys/macro-key)
- [键盘布局编辑器](/features/editor/layout-editor)
