# BackspaceKey 退格键

删除前一个字符；长按连续删除；左右滑动进入连续选择 / 删除流程。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"BackspaceKey"` |
| `swipe` | object |  | 上下滑动触发的 [MacroAction](/features/keys/macro-key#macroaction-结构) |
| `swipeLabel` | string |  | 上下滑动提示文字 |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

## 内置行为

- **点击**：删除一个字符（等价于发 `BackSpace`）
- **长按**：连续删除
- **左右滑动**：先移动删除范围，松手后删除选中的范围
- **上下滑动**：仅当配置了 `swipe` 时触发对应宏；左右滑动删除流程不会被覆盖

## 示例

```json
{ "type": "BackspaceKey", "weight": 0.15 }
```

配置上滑 / 下滑为"按词删除"：

```json
{
  "type": "BackspaceKey",
  "weight": 0.15,
  "swipe": {
    "macro": [
      { "type": "shortcut", "modifiers": [ { "fcitx": "Ctrl_L" } ], "key": { "fcitx": "BackSpace" } }
    ]
  },
  "swipeLabel": "←词"
}
```

## 相关页面

- [按键类型总览](/features/keys/overview)
- [滑动操作](/features/keyboard/swipe-actions)
