# 功能总览

本页一站式列出本版本的主要功能。它们分为两类：**继承自 fx 分支的应用层功能**（基线 [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android) `fx` 分支）与**本分支（Rime-only）的专属改动**。点击进入相应子页查看详细说明。

::: tip
关于本仓库与 fxliang / 上游的关系、致谢请见 [关于 → 致谢与差异说明](/about/credits)。
:::

## Rime 专版

| 功能 | 说明 | 链接 |
|------|------|------|
| Rime 引擎内置 | librime 静态链接进主 APK，免插件，方案选单 / tabs / 部署全内建 | [查看](/features/rime-enhancements) |
| 自备方案 | 不预置方案与 essay.txt；放入用户数据目录 + patch `schema_list` + 部署 | [查看](/guide/quick-start) |
| 配置指纹部署 | 只有配置真的变了才重新部署；部署期间按键只丢弃不上屏，键盘内显示「正在部署」 | [查看](/features/rime-enhancements) |
| 语言键行为 | 短按发 Shift 切中英文，长按弹出 Rime 方案选单 | [查看](/features/rime-enhancements) |
| 候选词手势 | 按住上滑选字（按字拆分提交）、按住下滑呼出菜单（忘记词汇连删同音词） | [查看](/features/rime-enhancements) |
| 退格增强 | 长按删空编码后保护正文；连删后上滑一次性清空输入框 | [查看](/features/rime-enhancements) |
| Foxy 风格符号面板 | 符号 / 表情 / 颜文字三面板约 6900 条，颜文字单列，支持自定义 JSON 数据 | [查看](/guide/concepts) |

## 键盘形态

| 功能 | 说明 | 链接 |
|------|------|------|
| 浮动键盘 | 可任意拖动、调整尺寸、移动手柄；横屏可自动浮动（不覆盖手动形态） | [查看](/features/keyboard/float-keyboard) |
| 分体键盘 | 横屏自动分屏，含校准 UI；可按子布局单独配分体布局、指定断点、空白占位键推挤 | [查看](/features/keyboard/split-keyboard) |
| 单手模式 | 左/右手切换，适配大屏 | [查看](/features/keyboard/one-handed) |
| 调整模式 | 可视化调整键盘大小、位置 | [查看](/features/keyboard/adjust-mode) |
| Compose Override 键 | 运行时切换的复合键 + 编辑器支持 | [查看](/features/keyboard/compose-override) |
| 可配置滑动操作 | 支持滑动标签；空格键划动动作与光标移动开关互斥（开关优先） | [查看](/features/keyboard/swipe-actions) |

## 可视化编辑器套件

| 功能 | 说明 | 链接 |
|------|------|------|
| TextKeyboard 布局 Profile | 多套完整布局文件，设置页或按钮动作中切换 | [查看](/features/editor/layout-editor) |
| 键盘布局编辑器 | JSON + 可视化双轨编辑；子模式下拉框带「默认」项与中文说明 | [查看](/features/editor/layout-editor) |
| Popup 预设编辑器 | 应用内编辑 Popup 预设 JSON | [查看](/features/editor/popup-editor) |
| MacroKey 编辑器 | 宏按键可视化编辑、多种 action 类型；「显示文本」优先于标签并原样显示 | [查看](/features/editor/macrokey-editor) |
| 字体集编辑器 | 按位置（主键、副键、候选、预编辑、Popup）独立配置字体 | [查看](/features/editor/fontset-editor) |

## 主题增强

| 功能 | 说明 | 链接 |
|------|------|------|
| 主题编辑器 | HSV 色板、颜色项语义分组折叠、子目录主题、多编码 ZIP 导入 | [查看](/features/theme/theme-editor) |
| 随机主题 | 生成随机配色并按对比度/色相/饱和度/明度给 0–100 美学评分；可一键应用、重新随机、复制为自定义主题 | [查看](/features/theme/theme-editor) |
| 编码区 / 工具栏圆角 | 编码区圆角半径与工具栏上方圆角半径（0–48dp）接续成一条连续弧线 | [查看](/features/theme/theme-editor) |
| Monet 编辑器 | 基于系统 Monet 动态取色生成主题；不支持时明确提示 | [查看](/features/theme/monet) |
| 磨砂按键 | Frosted blur 效果，含预览同步 | [查看](/features/theme/frosted-blur) |
| QR 分享与导入 | 通过二维码分享布局/Popup/主题，扫码/扫文件导入 | [查看](/features/theme/share-import) |

## 候选窗与状态栏

| 功能 | 说明 | 链接 |
|------|------|------|
| 候选窗增强 | 浮动候选窗"始终显示"、定位修复、滚动候选、高亮圆角 | [查看](/features/candidate-window) |
| 候选项序号角标 | 工具栏候选栏候选项显示序号；位置可选左上 / 右上 / 右下 / 左下 | [查看](/features/candidate-window) |
| 长候选横向滚动 | 超长候选保留完整宽度，横向拖拽查看被挡住的文字，不再截断 | [查看](/features/candidate-window) |
| Kawaii Bar 增强 | 横向滚动、按钮均布、工具栏大小（80%–200%，横向足迹恒定）、亮暗主题一键切换、拖拽自定义 | [查看](/features/kawaii-bar) |
| 候选栏预设 | 紧凑 / 标准 / 宽松三组预设；候选正文与注释字体字号分别配置 | [查看](/features/candidate-window) |

## 按键类型

| 类型 | 说明 | 链接 |
|------|------|------|
| 按键类型总览 | 全部按键类型一览表（含空白占位键） | [查看](/features/keys/overview) |
| **MacroKey**（重点） | 可绑定点击/滑动/长按三组宏序列 | [查看](/features/keys/macro-key) |
| AlphabetKey | 字母键（含 swipe 替代字符、宽度权重） | [查看](/features/keys/alphabet-key) |
| LayoutSwitchKey | 布局切换键（含符号/表情/颜文字面板跳转目标） | [查看](/features/keys/layout-switch-key) |
| 空白占位键 | 只占位、完全不接收触摸；用于分体推挤与调位置 | [查看](/features/keys/overview) |
| 其他单功能键 | Symbol / Caps / Backspace / Return / Space / Comma / Language | 见[总览](/features/keys/overview) |

## 其他功能

| 功能 | 说明 | 链接 |
|------|------|------|
| 剪贴板同步（内置） | 兼容 SyncClipboard / Oneclip / ClipCascade；历史实时搜索（打字即过滤，只查本机） | [查看](/features/clipboard-sync) |
| 更新检查器 | 检查本仓库 Release（含 Nightly），镜像规则 + 自定义 hosts，平滑速度显示 | [查看](/features/update-checker) |
| 共享导入与解压 | 系统分享自动识别导入，支持 ZIP/7z 自动解压 | [查看](/features/shared-import) |
| 在线编辑器 | 局域网 Web 编辑布局/Popup/主题（注意仅在可信网络开启） | [查看](/features/online-editor) |
| 设置搜索 | 首页搜索框支持中文名/英文别名/拼音，多词都要命中，点结果直达并滚动定位 | [查看](/manual/RIME_ONLY_USER_GUIDE_zh-CN) |
| 自定义按键音 | 导入 WAV/MP3/OGG/M4A/FLAC，不可用时回退系统音效 | [查看](/manual/RIME_ONLY_USER_GUIDE_zh-CN) |

## 数据来源

本总览基于 `fx-rime-only` 分支相对基线 `3ad25fc9`（fxliang `fx` 分支）的提交历史整理，如需逐条明细可在主仓库执行：

```bash
git log --oneline 3ad25fc9..HEAD
```
