# CommaKey 逗号键

逗号键，长按可调出表情、快捷短语与 Unicode 输入等菜单。

## 字段

| 字段 | 类型 | 必填 | 说明 |
|------|------|:----:|------|
| `type` | string | ✓ | 固定为 `"CommaKey"` |
| `weight` / `rowHeightPercent` / 颜色字段 | — |  | 与[共通字段](/features/keys/overview#几乎所有按键都通用的字段)相同 |

## 内置行为

- **点击**：发送 `,`，是否转换为中文标点由当前输入方案处理
- **长按**：弹出 Emoji / QuickPhrase / Unicode 菜单

## 示例

```json
{ "type": "CommaKey" }
```

## 相关页面

- [按键类型总览](/features/keys/overview)
- [SymbolKey](/features/keys/symbol-key) —— 仅做单字符输入的简单符号键
