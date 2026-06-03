# SymbolKey 符号键

直接提交一个符号，通常适合标点或单字符符号。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"SymbolKey"` |
| `label` | string | ✓ | 键面显示的符号文本，也是实际提交的文本；建议使用单个字符 |
| `swipe` | object |  | 滑动时执行的 [MacroAction](/features/keys/macro-key#macroaction-结构) |
| `swipeLabel` | string |  | 滑动提示文字 |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

## 与 AlphabetKey 的区别

- `AlphabetKey` 的字符会被输入法处理（拼音方案下会进入候选）
- `SymbolKey` 直接提交字符到输入框，不触发候选

适合放标点、符号等不希望被输入法"吃掉"的字符。如果要提交一段固定文本，用 [MacroKey](/features/keys/macro-key) 的 `text` step 更明确。

## 示例

```json
{ "type": "SymbolKey", "label": "@" }
```

```json
{
  "type": "SymbolKey",
  "label": "。",
  "swipe": { "macro": [ { "type": "text", "text": "，" } ] },
  "swipeLabel": "，"
}
```

## 相关页面

- [按键类型总览](/features/keys/overview)
- [AlphabetKey](/features/keys/alphabet-key)
