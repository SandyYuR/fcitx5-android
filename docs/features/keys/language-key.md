# LanguageKey 语言切换键

切换 Fcitx 内已启用的输入方案；长按可弹出系统输入法列表。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"LanguageKey"` |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

## 行为

- **点击**：切换到下一个 Fcitx 输入方案
- **长按**：调用系统输入法选择器

## 与 SpaceKey 长按切换的区别

- `SpaceKey` 的长按行为由全局选项决定，是否默认为切换输入法可配置
- `LanguageKey` 始终是 **切换 Fcitx 输入方案** 专用键

## 示例

```json
{ "type": "LanguageKey", "weight": 0.1 }
```

## 相关页面

- [按键类型总览](/features/keys/overview)
- [SpaceKey](/features/keys/space-key)
- [Kawaii Bar 增强](/features/kawaii-bar) —— 状态栏上的语言切换按钮
