# 致谢与差异说明

## 项目关系

```
fcitx5-android/fcitx5-android  (上游 / upstream)
        │
        └──fork──▶  fxliang/fcitx5-android  ◀── 本仓库
                          │
                          └─ master 分支    跟随上游
                          └─ fx 分支     深度魔改（本文档对象）
```

本仓库的 **`fx` 分支** 是基于上游 [`fcitx5-android/fcitx5-android`](https://github.com/fcitx5-android/fcitx5-android) 的 **二次开发分支**，定期合并上游变更，并在此基础上增加大量新功能与修改。

## 致谢上游

本项目能够存在，完全建立在以下上游与上游的上游之上，特此致谢：

### 主要上游

- **[fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)** —— 提供 Android 平台输入法主体框架、Fcitx5 引擎移植、构建体系。所有 fx 的新功能都构建在此之上。

### Fcitx 生态

- **[Fcitx5](https://github.com/fcitx/fcitx5)** —— 核心输入法框架
- **[fcitx5-chinese-addons](https://github.com/fcitx/fcitx5-chinese-addons)** —— 拼音、双拼等中文方案
- **[libime](https://github.com/fcitx/libime)** —— 输入法基础库

### 第三方引擎与库

- **[Rime / librime](https://github.com/rime/librime)** —— Rime 输入法引擎
- 其余依赖见各模块的 `LICENSE` 与 `README`

### 协议

- 主仓库与 fx 分支沿用上游的 **LGPL-2.1** 许可
- 涉及的二进制依赖（fcitx5、librime 等）以各自原始许可分发

## 与上游的主要差异

> 本节给出概览，详细功能说明见 [fx 功能总览](/features/overview)。

### 全新功能（上游没有的）

- **浮动键盘** + 拖拽/调整尺寸 + 调整模式（adjust mode）
- **分体键盘** 含校准 UI 与智能默认值
- **单手模式**
- **键盘布局编辑器**（基于 JSON 配置，应用内可视化编辑）
- **Popup 预设编辑器**
- **主题编辑器** + Monet 动态取色 + 磨砂按键 + HSV 取色
- **MacroKey** 宏按键（含可视化编辑器、多种 action 类型、可配置滑动行为）
- **二维码分享 / 导入** 布局、Popup、主题等配置
- **内置剪贴板同步**（兼容 SyncClipboard / Oneclip / ClipCascade、文本条目分词选择）
- **共享内容自动导入** + ZIP/7z 自动解压
- **文本编辑器插件**（支持大文件）
- **GitHub Release 更新检查器** + 镜像下载
- **字体集系统**（fontset.json，按位置指定不同字体）
- **Compose Override 键**
- **可配置滑动操作** + 滑动标签
- **TextKeyboard 多布局 Profile** / **子模式布局** / **层切换工作流**

### 增强修改（在上游已有功能上扩展）

- **Kawaii Bar**：横向滚动、按钮均布、语言切换按钮、激活状态颜色指示、拖拽自定义
- **候选窗**：浮动候选窗"始终显示"选项、定位修复、滚动候选、高亮圆角
- **Rime 集成**：方案选择器、状态区多选、Shift_L toggle 关闭选项、插件重装流程
- **主题导入**：子目录主题、多编码 ZIP 兼容
- **键盘按键**：宽度权重（AlphabetKey weight）、Gboard 风格圆角、波纹效果可配色

### 已知不打算合并上游的部分

部分修改属于 fx 的取舍（例如界面布局调整、特定的默认值），不一定会向上游 PR。如需了解某项功能是否计划上游化，可在 [issues](https://github.com/fxliang/fcitx5-android/issues) 中询问。

## 包名与下载来源

- 主程序提供两个构建：
  - **fx 构建**：包名 `org.fcitx.fcitx5.android.fx` —— **可与上游并存**
  - **mainline 构建**：包名 `org.fcitx.fcitx5.android` —— 与上游互相覆盖
- **魔改插件**：包名沿用上游 `org.fcitx.fcitx5.android.plugin.*`，单个 APK 同时兼容 fx / 上游两套主程序
- **下载来源**：[fxliang/fcitx5-android Releases](https://github.com/fxliang/fcitx5-android/releases)
- **应用内更新检查**：默认查询 fxliang 仓库的 Release

完整的版本对比、共存策略与插件兼容性矩阵见 [构建版本与插件兼容性](/guide/builds-and-plugins)。

## 贡献与反馈

- fx 相关问题、bug、功能建议：[fxliang/fcitx5-android/issues](https://github.com/fxliang/fcitx5-android/issues)
- 涉及上游的通用问题：[上游 issues](https://github.com/fcitx5-android/fcitx5-android/issues)
- 不确定时，先在 fxliang 仓库提，会按需转向上游
