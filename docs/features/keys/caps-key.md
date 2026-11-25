# CapsKey 大小写键

切换 / 锁定大小写状态。点击进入一次大写或退出大写状态；长按或双击进入锁定大写。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"CapsKey"` |
| `swipe` | object |  | 滑动时执行的 [MacroAction](/features/keys/macro-key#macroaction-结构) |
| `swipeLabel` | string |  | 滑动提示文字 |
| `swipeUp` / `swipeDown` | object |  | 上滑 / 下滑分别执行的 MacroAction（方向只由字段决定） |
| `swipeUpLabel` / `swipeDownLabel` | string |  | 上滑 / 下滑标签：上滑在上、下滑在下，不受主题「标点位置」影响 |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

## 内置行为

- **点击**：发送普通 Caps 行为；当前已锁定时会退出锁定
- **长按 / 双击**：锁定大写
- **滑动**：配置了 `swipe` 时执行对应宏

要先点亮 Shift、再与下一次单键组合，可使用[StickyModifierKey 粘滞修饰键](/features/keys/sticky-modifier-key)。它与 CapsKey 的大小写状态分别管理；仅启用粘滞 Shift 输入字母后，一次大写会复位，锁定大写保持锁定。

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
