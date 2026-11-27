# LanguageKey 语言键

直连版的语言键不再是"切换 Fcitx 输入方案"的按钮，而是**中英文切换 + Rime 方案选单**的入口。

## 行为（本版）

- **短按**：向 Rime 发送一次**独立 Shift**，交给当前方案的 `ascii_composer` / `switch_key` 规则处理——中英文怎么切、什么组合下切，完全由 Rime 配置决定（这与其它发行版"轮换 Fcitx 输入法"的行为不同）；
- **长按**：弹出 **Rime 方案选单**，点选即切换方案（与宏「应用操作」里的「Rime 方案选单」、空格键长按的「显示 Rime 方案选单」、默认 `Control+grave` / `F4` 同一入口；工具栏与状态区的按钮列表不提供这一项）；
- **切换到其它 Android 输入法**：长按**工具栏（Kawaii Bar）的语言切换按钮**打开系统输入法选择器，也可将[空格键长按](/features/keys/space-key)设为「显示输入法选择对话框」，或使用宏动作「切换系统输入法」。键盘上的语言键长按仍只打开方案选单。

::: warning 别混两个入口
语言键短按 = 中英文切换（Shift）；语言键长按 = Rime 方案选单；**工具栏**语言按钮长按 = 系统（Android）输入法选择器。三者是三个不同的事。
:::

## 键面图标随中英状态切换（本版新增）

语言键的键面图标与工具栏语言切换按钮共用一套内置图案：**中文态**显示「中/En」，**ASCII 态**显示「En/中」。

- **键盘语言键**：图标主题可分别覆盖 `keys.language`（中文态）与 `keys.language_ascii`（ASCII 态）。ASCII 槽位缺失时回退内置「En/中」，不会沿用中文态那枚图标；
- **工具栏语言切换按钮**：对应 `toolbar.language_switch` 与 `toolbar.language_switch_ascii`。ASCII 槽位缺失时沿用中文态槽位，未适配的主题两个状态显示同一图标；两者都没配才用内置「En/中」；
- 按钮编辑弹窗里还为语言切换按钮提供「**ASCII 状态图标**」一项，可单独选择或清除，只影响工具栏按钮，不改图标主题文件。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"LanguageKey"` |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

## 与 SpaceKey 长按的区别

- `SpaceKey` 的长按行为可配置，例如轮换 Rime 方案、打开系统输入法选择器或按住说话；
- `LanguageKey` 长按固定是 **Rime 方案选单**，不参与该全局选项。

## 示例

```json
{ "type": "LanguageKey", "weight": 0.1 }
```

## 相关页面

- [Rime 引擎（内置）](/features/rime-enhancements) —— 中英文切换与方案选单
- [按键类型总览](/features/keys/overview)
- [SpaceKey](/features/keys/space-key)
- [Kawaii Bar 增强](/features/kawaii-bar) —— 工具栏语言按钮（长按开系统选择器）
