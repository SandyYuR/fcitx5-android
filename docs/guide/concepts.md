# 核心概念

::: warning 待补充
本页内容正在撰写修订中。
:::

理解以下几个核心概念，可以帮助你更顺畅地配置和使用 Fcitx5 for Android。

## 输入法（Input Method）

一种"语言 + 方案"的组合。例如「拼音」、「五笔」、「Rime - 朙月拼音」等。可在配置中启用多个，并通过语言键或已配置的空格长按行为切换。

## Addon（插件）

Fcitx5 框架中的可扩展模块。安卓版以独立 APK 形式分发，例如 Rime addon、Chinese Addons（包含拼音、双拼等）。

## 全局配置 vs 输入法配置

- **全局配置**：影响所有输入法的行为（如候选栏样式、键盘高度、主题）
- **输入法配置**：单个输入方案的细节设置（如拼音的模糊音、Rime 方案选择）

## TextKeyboard 布局 Profile

TextKeyboard 布局 Profile 是一套完整的文本键盘布局配置。`default` Profile 对应 `config/TextKeyboardLayout.json`，其他 Profile 对应 `config/TextKeyboardLayout.<profile>.json`。

Profile 用来在多套完整键盘布局之间切换；布局 JSON 内部还可以有子模式、layer 和 `LayoutSwitchKey`，这些属于同一 Profile 内部的布局结构。

## 候选栏

显示候选词的横条，可设置为横向、竖向、Kawaii 等样式。

## 用户数据

包括词频、自定义词、Rime 配置等，存放于应用私有目录，卸载应用会一并删除（建议定期备份）。
