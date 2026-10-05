# 介绍

## 什么是靓企鹅·中州韵（Fcitx5.fx.rime）

**靓企鹅·中州韵**（其他语言显示名 Fcitx5.fx.rime）是 [SandyYuR/fcitx5-android](https://github.com/SandyYuR/fcitx5-android) 仓库 `fx-rime-only` 分支构建的 Android 输入法：一个 APK 里同时包含 Fcitx5 底盘、Rime（中州韵）引擎与键盘界面。

它基于 [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android) 的 `fx` 分支（提交 `3ad25fc9`）继续专用化开发，核心取舍是：**Rime 是唯一的输入引擎**。

::: tip 这份文档的范围
本文档覆盖 Rime 专版行为与继承自 fx 分支的应用层功能。

Fcitx5 框架的通用机制请参考 [上游仓库 Wiki](https://github.com/fcitx5-android/fcitx5-android/wiki)；Rime 本身的用法（schema 语法、YAML 配置）请参考 [Rime 官方文档](https://github.com/rime/home/wiki)。
:::

## 「Rime-only」的含义

- **fcitx5-rime 已并入主 APK**（静态链接 `librime.a`），不需要再安装插件 APK；
- 原版的拼音、码表、custom phrase 等 native 链路，以及其他语言插件（anthy、chewing、hangul、jyutping、sayura、thai、unikey、text-editor、clipboard-filter）和第三方插件发现框架**均已移除**；
- **保留**：OpenCC、Fcitx 核心底盘、浮动/分体/单手键盘、布局与宏编辑器、主题、候选栏、符号面板、语音输入、剪贴板与远端同步、数据备份，以及 librime 自带的 Lua/octagram 等能力；
- 应用内**不预置任何输入方案**，也不附带 `essay.txt`（约 6 MB）——方案需自备，见 [快速上手](/guide/quick-start)。

## 与 fxliang / 上游的关系

- 基线：fxliang 的 `fx` 分支提交 `3ad25fc9`，其后按本项目的需求继续开发（引擎链路自有 fork：[fcitx5-rime](https://github.com/SandyYuR/fcitx5-rime) / [prebuilder](https://github.com/SandyYuR/prebuilder) / [prebuilt](https://github.com/SandyYuR/prebuilt)）；
- 只有**一个构建版本**：包名 `org.fcitx.fcitx5.android.fx.rime`，**没有 mainline 构建，也没有插件 APK**；
- 包名与上游（`org.fcitx.fcitx5.android`）和 fxliang fx 构建（`org.fcitx.fcitx5.android.fx`）都不同，可以**并存安装**，数据互相隔离、不自动迁移；
- 本项目不是 Fcitx5 或 Rime 的官方发行版。

完整的共存关系与数据迁移见 [构建版本与数据共存](/guide/builds-and-plugins) 与 [从上游迁移](/guide/migrate-from-upstream)；致谢见 [关于 → 致谢与差异说明](/about/credits)。

## 适合谁

适合：

- **以 Rime 为唯一输入方案的用户**（桌面端用中州韵 / weasel，想在 Android 上获得一致体验）
- 想要浮动键盘 / 分体键盘 / 单手模式、可视化编辑布局与主题的用户
- 想通过二维码分享或导入他人配置的用户
- 希望剪贴板与桌面端 / 其他设备同步的用户
- 愿意自己准备 Rime 方案文件（`*.schema.yaml` + 词典）的用户

不适合：

- 想用拼音 / 码表等非 Rime 引擎的用户（请用 [fxliang 版本](https://github.com/fxliang/fcitx5-android)或[上游](https://github.com/fcitx5-android/fcitx5-android)）
- 期望开箱即打、不想自己准备方案的用户

## 设计目标

- **一个 APK 装完即用**：引擎、数据、界面一体分发，无插件依赖链
- **保持开源、隐私友好**：无云端上传（剪贴板同步需用户主动配置目标服务；日志不含输入原文）
- **桌面用户的迁移体验**：Rime 配置兼容、布局 JSON 可手改
- **部署可预期**：只有配置真的变了才重新部署，部署期间按键不会被误上屏

## 相关链接

- [SandyYuR/fcitx5-android](https://github.com/SandyYuR/fcitx5-android) —— 本仓库（`fx-rime-only` 分支为开发分支，[`rime-docs`](https://github.com/SandyYuR/fcitx5-android/tree/rime-docs) 分支为文档）
- [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android) —— 功能基线来源
- [上游 fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)
- [Fcitx5 官网](https://fcitx-im.org/) · [Rime 输入法引擎](https://rime.im/)
