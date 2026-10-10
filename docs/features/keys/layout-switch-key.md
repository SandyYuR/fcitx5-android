# LayoutSwitchKey 布局切换键

切换到指定的文本键盘布局。常用于在 QWERTY ↔ 数字符号 ↔ 符号面板之间跳转。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"LayoutSwitchKey"` |
| `label` | string | ✓ | 键面主标签，如 `"?123"`、`"ABC"` |
| `subLabel` | string |  | 目标布局名；留空表示回到默认文本键盘 |
| `swipe` | object |  | 一个 [MacroAction](/features/keys/macro-key#macroaction-结构)，可用作滑动备用动作 |
| `swipeLabel` | string |  | 滑动时显示的提示文字 |
| `swipeUp` / `swipeDown` | object |  | 上滑 / 下滑分别执行的 MacroAction（方向只由字段决定） |
| `swipeUpLabel` / `swipeDownLabel` | string |  | 上滑 / 下滑标签：上滑在上、下滑在下，不受主题「标点位置」影响 |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

::: tip 切换"层" vs 切换"布局文件"
- **LayoutSwitchKey** 切换的是键盘布局目标：`subLabel` 为内置布局名、布局文件中的 layout key，或空字符串
- 如果只是在当前输入方案布局内切换"层"，用 [MacroKey](/features/keys/macro-key#8-layer-布局层切换) 的 `layer` step
:::

## 直达符号面板的目标（本版）

编辑器里 `subLabel` 目标列表新增了三个面板选择器目标，可放一枚「一键直达某类面板」的键：

| 目标 | 写入配置的值 |
|------|--------------|
| 符号选择器 | `Symbol` |
| 表情选择器 | `Emoji` |
| 颜文字选择器 | `Kaomoji` |

列表里显示为 `Symbol（符号面板）` 等带括号提示的形式，括号只是给人看的。颜文字面板早期叫 `Emoticon`，**旧配置仍能正常识别**，无需手动迁移。

## 示例

```json
{ "type": "LayoutSwitchKey", "label": "?123", "subLabel": "Symbol" }
```

```json
{
  "type": "LayoutSwitchKey",
  "label": "ABC",
  "subLabel": "",
  "swipe": { "macro": [ { "type": "text", "text": " " } ] }
}
```

## 相关页面

- [按键类型总览](/features/keys/overview)
- [MacroKey - layer step](/features/keys/macro-key#8-layer-布局层切换)
