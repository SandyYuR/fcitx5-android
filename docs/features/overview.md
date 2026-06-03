# fx 功能总览

本页一站式列出 **fx 相对上游新增或显著修改的功能**。点击进入相应子页查看详细说明（标 ⚠️ 的为待补充页面，结构已就绪、内容正在撰写中）。

::: tip
关于本仓库与上游的关系、致谢请见 [关于 → 致谢与差异说明](/about/credits)。
:::

## 键盘形态

| 功能 | 说明 | 链接 |
|------|------|------|
| 浮动键盘 | 可任意拖动、调整尺寸、移动手柄 | [查看](/features/keyboard/float-keyboard) |
| 分屏键盘 | 横屏自动分屏，含校准 UI 与智能默认 | [查看](/features/keyboard/split-keyboard) |
| 单手模式 | 左/右手切换，适配大屏 | [查看](/features/keyboard/one-handed) |
| 调整模式 | 可视化调整键盘大小、位置 | [查看](/features/keyboard/adjust-mode) |
| Compose Override 键 | 运行时切换的复合键 + 编辑器支持 | [查看](/features/keyboard/compose-override) |
| 可配置滑动操作 | 支持滑动标签；部分功能键与符号键可绑定滑动宏 | [查看](/features/keyboard/swipe-actions) |

## 可视化编辑器套件

| 功能 | 说明 | 链接 |
|------|------|------|
| TextKeyboard 布局 Profile | 可维护多套完整文本键盘布局文件，并在设置页或按钮动作中切换 | [查看](/features/editor/layout-editor#布局-profile-与切换) |
| 键盘布局编辑器 | JSON + 可视化双轨编辑，支持子模式、层切换、多布局文件 | [查看](/features/editor/layout-editor) |
| Popup 预设编辑器 | 应用内编辑 Popup 预设 JSON | [查看](/features/editor/popup-editor) |
| MacroKey 编辑器 | 宏按键可视化编辑、多种 action 类型（app/文本编辑/字体集等） | [查看](/features/editor/macrokey-editor) |
| 字体集编辑器 | 按位置（主键、副键、候选、预编辑、Popup）独立配置字体 | [查看](/features/editor/fontset-editor) |

## 主题增强

| 功能 | 说明 | 链接 |
|------|------|------|
| 主题编辑器 | 简易主题编辑器、HSV 色板、子目录主题、多编码 ZIP 导入 | [查看](/features/theme/theme-editor) |
| Monet 编辑器 | 基于系统 Monet 动态取色生成主题 | [查看](/features/theme/monet) |
| 磨砂按键 | Frosted blur 效果，含预览同步 | [查看](/features/theme/frosted-blur) |
| QR 分享与导入 | 通过二维码分享布局/Popup/主题，扫码/扫文件导入 | [查看](/features/theme/share-import) |

## 候选窗与状态栏

| 功能 | 说明 | 链接 |
|------|------|------|
| 候选窗增强 | 浮动候选窗"始终显示"、定位修复、滚动候选、高亮圆角 | [查看](/features/candidate-window) |
| Kawaii Bar 增强 | 横向滚动、按钮均布、语言切换按钮、激活色指示、拖拽自定义 | [查看](/features/kawaii-bar) |

## 按键类型

| 类型 | 说明 | 链接 |
|------|------|------|
| 按键类型总览 | 全部 10 种按键类型一览表 | [查看](/features/keys/overview) |
| **MacroKey**（重点） | 全新按键，可绑定点击/滑动/长按三组宏序列 | [查看](/features/keys/macro-key) |
| AlphabetKey | 字母键（含 swipe 替代字符） | [查看](/features/keys/alphabet-key) |
| LayoutSwitchKey | 布局切换键 | [查看](/features/keys/layout-switch-key) |
| 其他单功能键 | Symbol / Caps / Backspace / Return / Space / Comma / Language | 见[总览](/features/keys/overview) |

## 其他增强

| 功能 | 说明 | 链接 |
|------|------|------|
| 剪贴板同步（内置） | 主程序自带剪贴板同步；兼容 SyncClipboard / Oneclip / ClipCascade；含文本条目分词选择 | [查看](/features/clipboard-sync) |
| 更新检查器与镜像 | GitHub Release 检查、镜像下载、平滑速度显示 | [查看](/features/update-checker) |
| 共享导入与解压 | 系统分享自动识别导入，支持 ZIP/7z 自动解压 | [查看](/features/shared-import) |
| 文本编辑器插件 | 应用内文本编辑器，支持大文件与低内存优化 | [查看](/features/text-editor) |
| Rime 集成增强 | 方案选择器、状态区多选、Shift_L toggle 关闭选项 | [查看](/features/rime-enhancements) |

## 数据来源

本总览基于 `fx` 相对 `upstream/master` 的 commit 历史（截至文档生成时约 300+ commits）整理。如需获取实时变更，可在仓库内执行：

```bash
git log --oneline upstream/master..fx
```
