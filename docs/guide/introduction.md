# 介绍

## 什么是 Fcitx5 for Android (fx)

**fx** 是 [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android) 仓库的一个分支，基于上游 [fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 进行的深度二次开发版本。

它保留了上游的全部基础能力 —— Fcitx5 引擎、Rime、拼音、五笔、仓颉等输入方案 —— 同时新增了大量上游不具备的特性，包括浮动键盘、可视化编辑器套件、QR 配置分享、Monet 主题、剪贴板同步、内置更新检查器等。

::: tip 这份文档的范围
本文档 **只覆盖 fx 相对上游新增或修改的功能**。

上游已具备的通用机制（基础键盘输入、输入方案切换、Fcitx5 框架原理等）请直接参考 [上游仓库 Wiki](https://github.com/fcitx5-android/fcitx5-android/wiki)。
:::

## 与上游的关系

- fx 定期合并上游变更，**不会替代上游**
- Release 同时提供 **fx 构建**（包名 `org.fcitx.fcitx5.android.fx`，可与上游并存）和 **mainline 构建**（包名 `org.fcitx.fcitx5.android`，与上游互相覆盖）
- 魔改插件 APK 包名沿用上游（如 `...plugin.rime`），可被任一 fxliang 主程序构建直接加载；上游主程序则因签名校验拒绝加载 fxliang 插件

完整的构建版本对比、共存策略与插件兼容性矩阵见 [构建版本与插件兼容性](/guide/builds-and-plugins)；致谢见 [关于 → 致谢与差异说明](/about/credits)。

## 适合谁

适合：

- 想体验浮动键盘 / 分体键盘 / 单手模式 的用户
- 想在应用内可视化编辑键盘布局、主题、Popup 的用户
- 想通过二维码分享或导入他人配置的用户
- 希望剪贴板与桌面端 / 其他设备同步的用户
- 希望使用内置更新检查器（支持镜像）拉取最新版本的用户

不适合：

- 期望与官方上游完全一致行为的用户（请用上游版本）
- 严格要求"无第三方修改"的环境（请用上游版本）

## 设计目标

- **保持开源、隐私友好**：与上游一致，无云端上传（剪贴板同步功能需用户主动配置目标服务）
- **可扩展**：插件化方案与编辑器配置（JSON）
- **桌面用户的迁移体验**：Rime 配置兼容、键盘布局可手改 JSON

## 相关链接

- [fxliang/fcitx5-android (fx)](https://github.com/fxliang/fcitx5-android) —— 本仓库
- [上游 fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)
- [Fcitx5 官网](https://fcitx-im.org/)
- [Rime 输入法引擎](https://rime.im/)
