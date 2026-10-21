# CommaKey 逗号键

::: info 旧版 Fcitx 文档
这是直连版合入前的原站文档，固定于 `edcf536f`，仅对应当时的 Fcitx 版本。
[旧版完整目录](/legacy/fcitx/contents) · [旧版维护报告](/legacy/fcitx/maintainer/) · [当前中州鹅手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [原始文档](https://github.com/SandyYuR/fcitx5-android/blob/edcf536f85576ce3e2b241e1eb5059534a972f51/docs/features/keys/comma-key.md)
:::


逗号键，长按可调出表情、快捷短语与 Unicode 输入等菜单。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"CommaKey"` |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/legacy/fcitx/features/keys/overview#几乎所有按键都通用的字段)相同 |

## 内置行为

- **点击**：发送 `,`，是否转换为中文标点由当前输入方案处理
- **长按**：弹出 Emoji / QuickPhrase / Unicode 菜单

## 示例

```json
{ "type": "CommaKey" }
```

## 相关页面

- [按键类型总览](/legacy/fcitx/features/keys/overview)
- [SymbolKey](/legacy/fcitx/features/keys/symbol-key) —— 仅做单字符输入的简单符号键
