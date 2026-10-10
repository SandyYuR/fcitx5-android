# SymbolKey 符号键

直接提交一个符号，通常适合标点或单字符符号。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"SymbolKey"` |
| `label` | string | ✓ | 键面显示的符号文本，也是实际提交的文本；建议使用单个字符 |
| `swipe` | object |  | 滑动时执行的 [MacroAction](/features/keys/macro-key#macroaction-结构) |
| `swipeLabel` | string |  | 滑动提示文字 |
| `swipeUp` / `swipeDown` | object |  | 上滑 / 下滑分别执行的 MacroAction（方向只由字段决定） |
| `swipeUpLabel` / `swipeDownLabel` | string |  | 上滑 / 下滑标签：上滑在上、下滑在下，不受主题「标点位置」影响 |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

## 与 AlphabetKey 的区别

- `AlphabetKey` 的字符会被输入法处理（拼音方案下会进入候选）
- `SymbolKey` 直接提交字符到输入框，不触发候选

适合放标点、符号等不希望被输入法"吃掉"的字符。如果要提交一段固定文本，用 [MacroKey](/features/keys/macro-key) 的 `text` step 更明确。

## 符号 / 表情 / 颜文字面板（本版）

键盘上的 `!?#`、`:-)`、`^_^` 等按键打开的是**符号面板**（以及表情、颜文字面板）。本版将其整体换成 Foxy 输入法风格：左侧分组栏 + 右侧符号网格、面板独占键盘区域、约 6900 条内置条目，三类数据都可换成自定义 JSON。这与 SymbolKey 是两回事——面板是独立的全键盘界面，选中即上屏、不经过 Rime 编码。详见[核心概念 → 符号面板](/guide/concepts)与[用户指南 §5.8](/manual/RIME_ONLY_USER_GUIDE_zh-CN)。

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
