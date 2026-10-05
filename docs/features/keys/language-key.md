# LanguageKey 语言键

Rime-only 版的语言键不再是"切换 Fcitx 输入方案"的按钮，而是**中英文切换 + Rime 方案选单**的入口。

## 行为（本版）

- **短按**：向 Rime 发送一次**独立 Shift**，交给当前方案的 `ascii_composer` / `switch_key` 规则处理——中英文怎么切、什么组合下切，完全由 Rime 配置决定（这与其它发行版"轮换 Fcitx 输入法"的行为不同）；
- **长按**：弹出 **Rime 方案选单**，点选即切换方案（与工具栏的「方案选单」按钮、默认 `Control+grave` / `F4` 同一入口）；
- **切换到其它 Android 输入法**：长按**工具栏（Kawaii Bar）的语言切换按钮**打开系统输入法选择器，或在宏的「应用操作」里使用「切换系统输入法」动作。语言键的长按已被方案选单占用，不承担这个职责。

::: warning 别混两个入口
语言键短按 = 中英文切换（Shift）；语言键长按 = Rime 方案选单；**工具栏**语言按钮长按 = 系统（Android）输入法选择器。三者是三个不同的事。
:::

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"LanguageKey"` |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

## 与 SpaceKey 长按的区别

- `SpaceKey` 的长按行为由全局选项决定（轮换输入法 / 按住说话 / 重复输入空格等，可配）；
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
