# 致谢与差异说明

## 项目关系

```
fcitx5-android/fcitx5-android  (上游 / upstream)
        │
        └──fork──▶  fxliang/fcitx5-android (fx 分支)
                          │
                          └──fork──▶  SandyYuR/fcitx5-android  ◀── 本仓库
                                        └─ fx-rime-only 分支  Rime 专版（本文档对象）
                                        └─ rime-docs 分支     维护者文档
                                        └─ docs 分支          本文档站
```

本仓库的 **`fx-rime-only` 分支**以 [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android) 的 `fx` 分支（提交 `3ad25fc9`）为基线，把「Rime 只是众多输入法之一」专用化为「**Rime 是唯一的输入引擎**」，并在此之上持续开发自己的功能。

## 致谢

本项目能够存在，完全建立在以下项目之上，特此致谢：

### 直接上游

- **[fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android)** —— 本分支的功能基线。浮动键盘、可视化编辑器套件、QR 配置分享、MacroKey、Monet 主题、剪贴板同步、更新检查器等应用层能力均继承自它的 `fx` 分支。
- **[fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)** —— Android 平台输入法主体框架、Fcitx5 引擎移植、构建体系。

### Fcitx 与 Rime 生态

- **[Fcitx5](https://github.com/fcitx/fcitx5)** —— 核心输入法框架
- **[Rime / librime](https://github.com/rime/librime)** —— 中州韵输入法引擎（本版的唯一输入引擎）
- **[fcitx5-rime](https://github.com/fcitx5/fcitx5-rime)** —— Rime 适配层（本版使用 [SandyYuR fork](https://github.com/SandyYuR/fcitx5-rime)，含 fxliang 定制与本版修复）
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

### 裁剪为 Rime 专版

- fcitx5-rime 并入主 APK（静态链接），**删除整套插件框架**与插件 APK 分发
- 删除拼音 / 码表 native 链路（保留 OpenCC）与相关 UI；删除 9 个其他语言/功能插件
- 移除 `mainline` flavor；包名改为 `org.fcitx.fcitx5.android.fx.rime`（可与旧版并存，数据隔离）
- **不预置任何输入方案**，不附带 `essay.txt`

### 引擎与构建链

- Rime 源码与 prebuilt 改用自有 fork（[fcitx5-rime](https://github.com/SandyYuR/fcitx5-rime) / [prebuilder](https://github.com/SandyYuR/prebuilder) / [prebuilt](https://github.com/SandyYuR/prebuilt)），构建不再依赖 fxliang 账号
- librime 定制补丁链：音节缓存、词典并行部署、用户词典缓存、词典文件重映射修复、忘记词汇连删同音词修复、`rewrite` 滤镜、**末位的配置指纹补丁**
- **配置指纹替代时间戳**：冷启动不再每次全量部署
- **部署期按键不再漏给编辑器**：只丢弃并在键盘内显示「正在部署」提示
- JNI 边界净化非法 UTF-8，避免候选标签导致进程中止

### 输入与交互（本版新增/修改）

- 语言键短按发独立 Shift、长按弹 Rime 方案选单
- 候选词按住上滑选字 / 下滑呼出菜单；忘记词汇连删同音词
- 退格长按删空编码后保护正文；连删后上滑清空输入框
- Foxy 风格符号 / 表情 / 颜文字面板（约 6900 条），三类数据可自定义
- 剪贴板历史实时搜索；设置首页两层六入口 + 顶部搜索框
- 分体键盘按子布局单独配置、断点指定、空白占位键；空格键划动动作与长按重复输入空格
- 宏按键「显示文本」优先于标签并原样显示

## 包名与下载来源

- 主程序：包名 `org.fcitx.fcitx5.android.fx.rime`，debug 变体为 `...fx.rime.debug`（可与 Release 并存）
- **没有插件 APK、没有 mainline 构建**
- 下载来源：[SandyYuR/fcitx5-android Releases](https://github.com/SandyYuR/fcitx5-android/releases)（含时间戳 Nightly 预发布）
- 应用内更新检查：默认查询本仓库的 Release

## 贡献与反馈

- 本项目的问题、bug、功能建议：[SandyYuR/fcitx5-android/issues](https://github.com/SandyYuR/fcitx5-android/issues)
- 涉及 fx 分支功能本身的问题：[fxliang/fcitx5-android/issues](https://github.com/fxliang/fcitx5-android/issues)
- 上游通用问题：[上游 issues](https://github.com/fcitx5-android/fcitx5-android/issues)
- 不确定时，先在本仓库提，会按需转向
