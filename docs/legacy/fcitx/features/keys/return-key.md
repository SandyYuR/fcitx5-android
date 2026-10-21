# ReturnKey 回车键

::: info 旧版 Fcitx 文档
这是直连版合入前的原站文档，固定于 `edcf536f`，仅对应当时的 Fcitx 版本。
[旧版完整目录](/legacy/fcitx/contents) · [旧版维护报告](/legacy/fcitx/maintainer/) · [当前中州鹅手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [原始文档](https://github.com/SandyYuR/fcitx5-android/blob/edcf536f85576ce3e2b241e1eb5059534a972f51/docs/features/keys/return-key.md)
:::


发送回车 / 换行 / 提交（根据当前输入框的 IME action）。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"ReturnKey"` |
| `swipe` | object |  | 滑动时执行的 [MacroAction](/legacy/fcitx/features/keys/macro-key#macroaction-结构) |
| `swipeLabel` | string |  | 滑动提示文字 |
| `swipeUp` / `swipeDown` | object |  | 上滑 / 下滑分别执行的 MacroAction（方向只由字段决定） |
| `swipeUpLabel` / `swipeDownLabel` | string |  | 上滑 / 下滑标签：上滑在上、下滑在下，不受主题「标点位置」影响 |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/legacy/fcitx/features/keys/overview#几乎所有按键都通用的字段)相同 |

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

- [按键类型总览](/legacy/fcitx/features/keys/overview)
