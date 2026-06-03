# ReturnKey 回车键

发送回车 / 换行 / 提交（根据当前输入框的 IME action）。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"ReturnKey"` |
| `swipe` | object |  | 滑动时执行的 [MacroAction](/features/keys/macro-key#macroaction-结构) |
| `swipeLabel` | string |  | 滑动提示文字 |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

## 智能图标

ReturnKey 会根据当前输入框的 imeOptions 自动显示 "Done" / "Search" / "Send" / "Go" 等图标，无需在 JSON 中配置。

## 示例

```json
{ "type": "ReturnKey", "weight": 0.2 }
```

带滑动调用换行符的备选行为（在不支持回车的输入框中插入换行）：

```json
{
  "type": "ReturnKey",
  "weight": 0.2,
  "swipe": { "macro": [ { "type": "text", "text": "\n" } ] },
  "swipeLabel": "↵"
}
```

## 相关页面

- [按键类型总览](/features/keys/overview)
