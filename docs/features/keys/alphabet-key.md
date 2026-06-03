# AlphabetKey 字母键

最常用的按键类型：点击发送 `main` 字符，滑动发送 `alt` 字符。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"AlphabetKey"` |
| `main` | string | ✓ | 主字符（通常一个字符），如 `"Q"` / `"中"` |
| `alt` | string | ✓ | 滑动备用字符，如 `"1"` |
| `displayText` | string \| map |  | 自定义键面显示文本，详见 [overview](/features/keys/overview#显示文本-displaytext-的多模式格式) |
| `composeOverride` | object |  | 输入过程中样式覆盖；见 [Compose Override](/features/keyboard/compose-override) |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

## 字符与按键事件

- `main` 一般是一个字符；输入法（Fcitx）会将其作为按键事件处理（拼音方案下会进入候选流程）
- `alt` 在用户滑动该键时发送
- 编辑器会要求 `main` / `alt` 都是单个字符；如果不需要滑动备用行为，使用 [SymbolKey](/features/keys/symbol-key) 或 [MacroKey](/features/keys/macro-key) 更合适
- 如果需要发送一段字符串、或更复杂行为，使用 [MacroKey](/features/keys/macro-key)

## 示例

```json
{ "type": "AlphabetKey", "main": "Q", "alt": "1" }
```

按方案区分显示文字（仓颉显示偏旁、其他方案显示字母）：

```json
{
  "type": "AlphabetKey",
  "main": "Q",
  "alt": "1",
  "displayText": { "倉頡五代": "手" }
}
```

## 相关页面

- [按键类型总览](/features/keys/overview)
- [MacroKey 细化](/features/keys/macro-key)
- [滑动操作](/features/keyboard/swipe-actions)
