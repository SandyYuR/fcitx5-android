# SpaceKey 空格键

发送空格；同时承载一组内置的滑动 / 长按行为：

- **左右滑动**：在文本中移动光标
- **上下滑动**：跨行移动光标（发送 `FcitxKey_Up` / `FcitxKey_Down`）
- **长按**：根据全局选项执行空格长按行为

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"SpaceKey"` |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

::: tip 长按 / 滑动行为
SpaceKey 的滑动开关与长按行为由全局选项控制（**配置 → 键盘 → 空格键**），不在 JSON 中逐键配置。
:::

## Space 标签样式

fx 提供多种 Space 标签风格（**SubModeOnly** 等），由全局配置选择。

## 示例

```json
{ "type": "SpaceKey", "weight": 0.5 }
```

## 相关页面

- [按键类型总览](/features/keys/overview)
- [滑动操作](/features/keyboard/swipe-actions)
