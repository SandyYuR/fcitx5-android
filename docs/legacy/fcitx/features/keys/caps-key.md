# CapsKey 大小写键

::: info 旧版 Fcitx 文档
这是直连版合入前的原站文档，固定于 `edcf536f`，仅对应当时的 Fcitx 版本。
[旧版完整目录](/legacy/fcitx/contents) · [旧版维护报告](/legacy/fcitx/maintainer/) · [当前中州鹅手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [原始文档](https://github.com/SandyYuR/fcitx5-android/blob/edcf536f85576ce3e2b241e1eb5059534a972f51/docs/features/keys/caps-key.md)
:::


切换 / 锁定大小写状态。点击进入一次大写或退出大写状态；长按或双击进入锁定大写。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"CapsKey"` |
| `swipe` | object |  | 滑动时执行的 [MacroAction](/legacy/fcitx/features/keys/macro-key#macroaction-结构) |
| `swipeLabel` | string |  | 滑动提示文字 |
| `swipeUp` / `swipeDown` | object |  | 上滑 / 下滑分别执行的 MacroAction（方向只由字段决定） |
| `swipeUpLabel` / `swipeDownLabel` | string |  | 上滑 / 下滑标签：上滑在上、下滑在下，不受主题「标点位置」影响 |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/legacy/fcitx/features/keys/overview#几乎所有按键都通用的字段)相同 |

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

- [按键类型总览](/legacy/fcitx/features/keys/overview)
