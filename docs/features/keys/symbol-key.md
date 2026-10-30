# SymbolKey 符号键

这是沿用历史类型名的标点键。当前默认点击发送标准句号 `.` 作为按键事件，交给 Rime 方案决定最终标点；`label` 只控制键面显示，不直接决定上屏文字。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"SymbolKey"` |
| `label` | string | ✓ | 键面显示文本；默认点击仍发送标准句号 `.` |
| `swipe` | object |  | 滑动时执行的 [MacroAction](/features/keys/macro-key#macroaction-结构) |
| `swipeLabel` | string |  | 滑动提示文字 |
| `swipeUp` / `swipeDown` | object |  | 上滑 / 下滑分别执行的 MacroAction（方向只由字段决定） |
| `swipeUpLabel` / `swipeDownLabel` | string |  | 上滑 / 下滑标签：上滑在上、下滑在下，不受主题「标点位置」影响 |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

## 与 AlphabetKey 的区别

- `AlphabetKey` 的字符会被输入法处理（拼音方案下会进入候选）
- `SymbolKey` 默认发送 `.`，参与方案的标点处理；键面写 `@` 并不表示会输入 `@`

需要原样输入 `@` 或一段固定文字时，使用 [MacroKey](/features/keys/macro-key) 的 `text` step。需要输入其他字符并交给 Rime 处理时，使用 AlphabetKey 或相应按键宏。

## 符号 / 表情 / 颜文字面板（本版）

键盘上的 `!?#`、`:-)`、`^_^` 等入口打开独立的符号、表情和颜文字面板，区别于单个 SymbolKey。面板提供左侧分组或底部栏两种风格，支持自定义 JSON 数据；选中条目直接上屏。
详见[符号面板](/features/symbol-panel)，不用从旧用户手册的章节号查找当前设置。

## 示例

下例键面显示句号，实际发送 `.`，由当前方案选择半角或全角等标点。

```json
{ "type": "SymbolKey", "label": "。" }
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
