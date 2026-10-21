# 致谢与差异说明

## 项目关系

```
fcitx5-android/fcitx5-android  (上游 / upstream)
        │
        └──fork──▶  fxliang/fcitx5-android (fx 分支)
                          │
                          └──fork──▶  SandyYuR/fcitx5-android  ◀── 本仓库
                                        └─ fx-rime-only 分支  中州鹅直连版（本文档对象）
                                        └─ docs 分支          本文档站（指南 + 用户手册 + 维护者文档）
```

本仓库的 **`fx-rime-only` 分支**以 [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android) 的 `fx` 分支（提交 `3ad25fc9`）为基线，把「Rime 只是众多输入法之一」专用化为「**Rime 是唯一的输入引擎**」，并在此之上持续开发自己的功能。

## 致谢

本项目能够存在，完全建立在以下项目之上，特此致谢：

### 直接上游

- **[fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android)** —— 本分支的功能基线。浮动键盘、可视化编辑器套件、QR 配置分享、MacroKey、Monet 主题、剪贴板同步、更新检查器等应用层能力均继承自它的 `fx` 分支。
- **[fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)** —— Android 平台输入法主体框架、Fcitx5 引擎移植、构建体系。

### Fcitx 与 Rime 生态

- **[Fcitx5](https://github.com/fcitx/fcitx5)** —— 项目历史输入法框架，直连 APK 已移除其原生后端
- **[Rime / librime](https://github.com/rime/librime)** —— 中州韵输入法引擎（本版的唯一输入引擎）
- **[fcitx5-rime](https://github.com/fcitx5/fcitx5-rime)** —— 项目历史 Rime 适配层，直连 APK 已移除；保留贡献致谢
- **[OpenCC](https://github.com/BYVoid/OpenCC)** —— 中文转换（保留在包内）
- **[Foxy 输入法](https://github.com/fxliang/foxy)** —— 符号 / 表情 / 颜文字面板的布局与数据格式来源

### 其他

- 内置键盘布局、主题与图标主题来自社区群友贡献；
- 其余依赖见各模块的 `LICENSE` 与应用内许可证清单。

### 协议

- 主仓库沿用上游的 **LGPL-2.1+** 许可
- 涉及的二进制依赖（fcitx5、librime 等）以各自原始许可分发

## 相对 fxliang fx 分支的主要差异

> 概览；逐条明细见主仓库 `git log --oneline 3ad25fc9..HEAD`。

### 直连 librime 与构建

- 保留 Android 前端，主应用通过 JNI 直接驱动 librime；删除原生 Fcitx Core、fcitx5-rime 和 AndroidFrontend。
- 原生库仅 `libdirect-rime.so`、`libc++_shared.so`；共享 prebuilt 中的其他产物不自动打包。
- [prebuilder](https://github.com/SandyYuR/prebuilder) 和 [prebuilt](https://github.com/SandyYuR/prebuilt) 固定版本与定制补丁，更新方法见[维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)。
- 主包名恢复为 `org.fcitx.fcitx5.android.fx.rime`，与本仓库旧 Fcitx 版同签名时覆盖升级；旧测试包需迁移。
- 不预装中文方案、完整词典或万象模型；方案需自行导入并部署。
- 保留上游署名、许可证和前端兼容标识；本项目独立维护。

### 输入与交互（本版新增/修改）

- 语言键短按发独立 Shift、长按弹 Rime 方案选单
- 候选词按住上滑选字 / 下滑呼出菜单；忘记词汇连删同音词
- 退格长按删空编码后保护正文；连删后上滑清空输入框
- Foxy 风格符号 / 表情 / 颜文字面板（约 6900 条），三类数据可自定义
- 剪贴板历史实时搜索；设置首页两层六入口 + 顶部搜索框
- 分体键盘按子布局单独配置、断点指定、空白占位键；空格键划动动作与长按重复输入空格
- 宏按键「显示文本」优先于标签并原样显示

## 包名与下载来源

- 主程序：包名 `org.fcitx.fcitx5.android.fx.rime`；安装与共存以实际包名和签名为准
- **没有插件 APK、没有 mainline 构建**
- 下载来源：[SandyYuR/fcitx5-android Releases](https://github.com/SandyYuR/fcitx5-android/releases)（含时间戳 Nightly 预发布）
- 应用内更新检查：默认查询本仓库的 Release

## 贡献与反馈

- 本项目的问题、bug、功能建议：[SandyYuR/fcitx5-android/issues](https://github.com/SandyYuR/fcitx5-android/issues)
- 涉及 fx 分支功能本身的问题：[fxliang/fcitx5-android/issues](https://github.com/fxliang/fcitx5-android/issues)
- 上游通用问题：[上游 issues](https://github.com/fcitx5-android/fcitx5-android/issues)
- 不确定时，先在本仓库提，会按需转向
