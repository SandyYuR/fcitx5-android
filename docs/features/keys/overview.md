# 按键类型总览

键盘布局通过 `TextKeyboardLayout.json` 的 JSON 数据结构定义 —— 顶层是一个二维数组：外层数组的每个元素代表一行，每行又是按顺序排列的按键对象数组。文件里的换行只是排版便利，**真正划分行的是数组嵌套结构而不是文本换行**。每个按键都有一个 `type` 字段决定其行为类型，再配合该类型支持的具体字段。

::: tip 编辑入口
所有按键的字段都可以在 [键盘布局编辑器](/features/editor/layout-editor) 中可视化修改，也可以直接手改 JSON。
:::

## 类型一览

| `type` 值 | 用途 | 必填字段 | 详细页 |
|----------|------|----------|--------|
| `AlphabetKey` | 字母 / 字符键，支持 swipe 替代字符 | `main`, `alt` | [详情](/features/keys/alphabet-key) |
| `MacroKey` | **宏键** —— 单键执行多步动作 | `label`, `tap.macro` | [详情](/features/keys/macro-key) |
| `LayoutSwitchKey` | 切换到指定文本布局 | `label` | [详情](/features/keys/layout-switch-key) |
| `SymbolKey` | 符号键（直接提交符号） | `label` | [详情](/features/keys/symbol-key) |
| `CapsKey` | 大小写切换键 | — | [详情](/features/keys/caps-key) |
| `BackspaceKey` | 退格键，支持左右滑动选择删除范围 | — | [详情](/features/keys/backspace-key) |
| `ReturnKey` | 回车键 | — | [详情](/features/keys/return-key) |
| `SpaceKey` | 空格键，含切换/光标移动等隐含行为 | — | [详情](/features/keys/space-key) |
| `CommaKey` | 逗号键（含表情符号快捷菜单） | — | [详情](/features/keys/comma-key) |
| `LanguageKey` | 输入法 / 子模式语言切换 | — | [详情](/features/keys/language-key) |

## 几乎所有按键都通用的字段

下表列出多数按键类型都接受的"共通字段"，各类型详情页只会列出 **该类型独有** 的字段。

| 字段 | 类型 | 范围 / 说明 |
|------|------|-------------|
| `weight` | float | `0.0–1.0`。正数表示按键宽度占整行的比例。**省略字段（或 `null`）走该按键类型的默认值**（见下方[宽度规则](#宽度-weight-规则)）；显式写 `0` 表示参与分配行内剩余空间 |
| `rowHeightPercent` | float | `1–100`。该键 **声明**的相对行高；同行先取所有按键中此值的**最大值**，所有行再归一化。详见下方[行高规则](#行高-rowheightpercent-规则) |
| `textColor` | int / hex | `0xAARRGGBB` 形式或十进制；按键主标签颜色 |
| `textColorMonet` | string | Material You 色 token 名（Android 12+），可替代 `textColor` |
| `altTextColor` | int / hex | swipe 出现的副标签颜色 |
| `altTextColorMonet` | string | 同上的 Monet 版 |
| `backgroundColor` | int / hex | 按键背景色 |
| `backgroundColorMonet` | string | 同上的 Monet 版 |
| `shadowColor` | int / hex | 阴影颜色（按键浮雕） |
| `shadowColorMonet` | string | 同上的 Monet 版 |
| `swipe` | object | 一个 [MacroAction](/features/keys/macro-key#step-类型清单) 对象（部分按键支持） |
| `swipeLabel` | string | 在键面上显示的滑动提示文字 |
| `composeOverride` | object | 输入过程中（IME composing 状态）的样式覆盖 |

## 宽度 weight 规则

`weight` 控制按键宽度占该行的比例。取值范围 `0.0–1.0`。

### 留空时按"类型默认值"处理

**省略 `weight` 字段** 或显式写 `"weight": null` 都视为"未指定"，按该按键类型的内置默认值处理（在代码里硬编码）：

| 按键类型 | 默认 weight | 直观感受 |
|---------|:----------:|---------|
| `AlphabetKey` | `0.1` | 标准字母键宽 |
| `MacroKey` | `0.1` | 与字母键同宽 |
| `SymbolKey` | `0.1` | 与字母键同宽 |
| `CommaKey` | `0.1` | 与字母键同宽 |
| `LanguageKey` | `0.1` | 与字母键同宽 |
| `CapsKey` | `0.15` | 略宽（功能键） |
| `BackspaceKey` | `0.15` | 略宽（功能键） |
| `ReturnKey` | `0.15` | 略宽（功能键） |
| `LayoutSwitchKey` | `0.15` | 略宽（功能键） |
| `SpaceKey` | `0`（剩余空间） | 占据该行剩余全部空间 |

::: tip
所以 SpaceKey 默认不需要写 `weight`，自然就铺满行内剩下的位置 —— 这也是为什么"空格行"通常只在两侧放少量功能键、中间留给空格的写法能直接工作。
:::

### 显式写值

- `0` —— 参与分配行内剩余宽度（与 `SpaceKey` 不写时的行为一致）
- `> 0` —— 按比例占整行；同一行多个键的 weight 总和接近 1.0 时正好填满，超过时按约束布局规则压缩
- 负值、超出 1.0 等异常值：编辑器会阻止保存，手写 JSON 时不应使用

### 修改默认按键的宽度

如果你要让一个 `ReturnKey` 比内置默认更窄（比如 0.1），直接写：

```json
{ "type": "ReturnKey", "weight": 0.1 }
```

这会覆盖该类型的内置默认。

## 行高 rowHeightPercent 规则

`rowHeightPercent` 字段 **对所有按键类型都有效**，用来在单个 layout 内做"按行变化"的高度控制。

### 取值与含义

- 取值范围：`1–100`（浮点数）
- 含义：按键所在行的相对高度百分比
- 不写时由同一 layout 内其他行的显式百分比分配剩余高度；没有任何显式值时各行等高

### 同行多键时的合并规则

::: tip 同行采用逻辑（重要）
**一行中如果有多个按键各自声明了 `rowHeightPercent`，该行先取所有按键 `rowHeightPercent` 的最大值，再与其他行一起归一化。**
:::

也就是说：

- 你只需要在一行里的 **任意一个按键** 上写 `rowHeightPercent`，整行都会按这个高度渲染
- 同一行上写了多个不同值时 —— 不会冲突，也不会平均；**先取最大**
- 想让某行更矮，要让该行**所有**按键都不写或都写较小值；任何一个按键写了较大值都会"撑起"整行

### 典型用法

- 顶部"功能行"（数字行）写一个较小的 `rowHeightPercent`，让它比主键盘行更紧凑
- 底部"空格行"放高一点，方便误触

::: details 例：第一行（数字键）矮一些
该行的 `0` 键声明 `rowHeightPercent: 8`，其余键不写 —— 这一行会先按 8 参与行高分配，再与其他行一起归一化。

```json
[
  { "type": "AlphabetKey", "main": "1", "alt": "!" },
  { "type": "AlphabetKey", "main": "2", "alt": "@" },
  { "type": "AlphabetKey", "main": "3", "alt": "#" },
  { "type": "AlphabetKey", "main": "0", "alt": ")", "rowHeightPercent": 8 }
]
```
:::

::: details 例：撑起的反例（容易踩坑）
若想让整行变矮，但只有一个键留了 `rowHeightPercent: 18`，这一行仍会按 18 参与分配。要让整行更矮，需要 **去掉那个 18 的声明**（或把它改小）。
:::

## 颜色字段约定

- `textColor` 等数值字段：接受 `0xAARRGGBB` 形式（如 `0xFFFFFFFF`）或十进制等价值
- `textColorMonet` 等 Monet 字段：接受 Material You 色 token（如 `primary` / `onSurface` / `surfaceContainer`）。系统切换主题色时按键颜色会跟随
- **两者同时配置时，Monet 优先**（Android 12+）

## 显示文本 displayText 的多模式格式

`AlphabetKey` / `MacroKey` 等支持的 `displayText` 字段，可以是：

- 普通字符串：`"displayText": "A"` —— 永远显示这个文字
- 模式 → 文本映射：`{"倉頡五代": "手"}` —— 当前 Rime 子模式 label 或 name 匹配 key 时显示对应值；未匹配时回退到该按键的基础文本

## 真实 JSON 片段

::: details 一个简单字母行
```json
[
  { "type": "AlphabetKey", "main": "Q", "alt": "1" },
  { "type": "AlphabetKey", "main": "W", "alt": "2" },
  { "type": "AlphabetKey", "main": "E", "alt": "3" },
  { "type": "BackspaceKey", "weight": 0.15 }
]
```
:::

::: details 包含 MacroKey 的混合行
```json
[
  {
    "type": "MacroKey",
    "label": "@",
    "altLabel": ".com",
    "tap":   { "macro": [ { "type": "text", "text": "@" } ] },
    "swipe": { "macro": [ { "type": "text", "text": ".com" } ] }
  },
  { "type": "SpaceKey", "weight": 0.5 },
  { "type": "LayoutSwitchKey", "label": "ABC", "subLabel": "" }
]
```
:::

## 相关页面

- [键盘布局编辑器](/features/editor/layout-editor) —— 可视化编辑这些字段
- [MacroKey 细化](/features/keys/macro-key) —— 全部 step 类型与字段
- [Fcitx 键名速查](/features/keys/fcitx-keys) —— `fcitx` 形式 KeyRef 的可用键名
- [滑动操作](/features/keyboard/swipe-actions) —— swipe 行为的整体设计
- [Compose Override](/features/keyboard/compose-override) —— `composeOverride` 字段的用法
