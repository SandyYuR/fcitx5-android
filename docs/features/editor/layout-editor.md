# 键盘布局编辑器

fx 提供完整的 **应用内键盘布局编辑器**，配合底层的 JSON 配置文件 (`config/TextKeyboardLayout.json`)，允许你：

- 不写代码、所见即所得地改键盘
- 同时拥有 JSON 可手改、可版本管理、可分享的能力

::: warning 待补充
界面截图与具体操作指引正在撰写中。
:::

## 核心能力

| 能力 | 说明 |
|------|------|
| 多布局文件 | 支持多个 TextKeyboardLayout profile，方便方案切换 |
| 子模式布局 (submode) | 同一方案在不同子模式下可使用不同布局 |
| 层切换 (layer) | 通过 MacroKey `to/osl` action 在布局层间跳转 |
| 行高百分比 | 按行配置相对高度；同行取最大值，所有行再归一化 |
| 按键宽度权重 | 多数按键支持 weight 字段：省略走类型默认，`0` 参与分配剩余宽度，正数按整行比例占宽 |
| 主题色 token | 按键颜色可引用主题色 token，跟随主题切换 |
| 全量校验 | 编辑过程中进行完整数据校验，避免崩溃 |
| 标题指示 | 编辑器标题显示当前正在编辑的布局名 |
| 退出确认 | 未保存变更时弹出确认对话框 |

## 入口

进入 **配置 → 键盘 → 文本键盘布局**（具体路径以应用内为准），从列表中选择一个布局进入编辑器。

也可以在电脑浏览器中使用 [在线编辑器](https://fxliang.github.io/f5a-see-me/){target="_blank" rel="noopener"} 修改布局，再通过二维码导入到手机端。

## 布局 Profile 与切换

TextKeyboard 布局支持多个 **Profile**。每个 Profile 对应一份完整的文本键盘布局 JSON 文件，适合保存不同用途的键盘配置，例如日常输入、单手布局、符号增强布局、实验布局等。

文件命名规则：

| Profile | 文件 |
|---------|------|
| `default` | `config/TextKeyboardLayout.json` |
| 其他名称 | `config/TextKeyboardLayout.<profile>.json` |

在应用内可以：

- 在键盘布局编辑器中创建、复制、重命名、删除不同 Profile
- 在 **配置 → 键盘 → 文本键盘布局文件** 中选择当前启用的 Profile
- 将 **TextKeyboard 布局文件选择** 动作放到 Kawaii Bar / 按钮区，输入时快速切换 Profile

Profile 切换的是整份 TextKeyboard 布局文件；它不同于布局 JSON 内部的 `layer` 切换，也不同于 `LayoutSwitchKey` 在当前布局内跳到某个布局目标。

## 配置文件位置

默认布局文件存放在应用私有目录的 `config/TextKeyboardLayout.json`；其他 profile 使用 `config/TextKeyboardLayout.<profile>.json`。

## 配色与按键格式

支持在 layout JSON 中直接定义颜色，按键属性可包括：

- `main` / `alt` / `label` / `displayText`
- MacroKey 的点击、滑动、长按宏；部分功能键的滑动宏
- 宽度 weight
- 主题色 token 引用

## 分享与导入

布局可通过二维码分享（含预览缩略图）：见 [QR 分享与导入](/features/theme/share-import)。

## 字段速查

布局 JSON 中每一个按键的 `type` 字段决定其支持的属性。完整字段表见 [按键类型总览](/features/keys/overview)；特别地 [MacroKey](/features/keys/macro-key) 因 step 体系丰富，单独成页。

## 相关页面

- [按键类型总览](/features/keys/overview) —— 所有 type 与字段
- [MacroKey](/features/keys/macro-key) —— step 类型与字段
- [在线编辑器](/features/online-editor)
- [Popup 编辑器](/features/editor/popup-editor)
- [MacroKey 编辑器](/features/editor/macrokey-editor)
- [字体集编辑器](/features/editor/fontset-editor)
- [Compose Override](/features/keyboard/compose-override)
- [滑动操作](/features/keyboard/swipe-actions)
