# CapsKey 大小写键

切换 / 锁定大小写状态。点击进入一次大写或退出大写状态；长按或双击进入锁定大写。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"CapsKey"` |
| `swipe` | object |  | 滑动时执行的 [MacroAction](/features/keys/macro-key#macroaction-结构) |
| `swipeLabel` | string |  | 滑动提示文字 |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

## 内置行为

- **点击**：发送普通 Caps 行为；当前已锁定时会退出锁定
- **长按 / 双击**：锁定大写
- **滑动**：配置了 `swipe` 时执行对应宏

## 示例

```json
{ "type": "CapsKey", "weight": 0.15 }
```

带滑动锁定 Caps：

```json
{
  "type": "CapsKey",
  "weight": 0.15,
  "swipe": { "macro": [ { "type": "tap", "keys": [ { "fcitx": "Caps_Lock" } ] } ] },
  "swipeLabel": "锁"
}
```

## 相关页面

- [按键类型总览](/features/keys/overview)
