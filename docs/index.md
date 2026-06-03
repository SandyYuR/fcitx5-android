---
layout: home

hero:
  name: Fcitx5 for Android
  text: fx 魔改分支
  tagline: 在上游 fcitx5-android/fcitx5-android 基础上深度定制 —— 浮动键盘、可视化编辑器、QR 分享、MacroKey 宏键、Monet 主题、内置剪贴板同步等
  image:
    src: /logo.png
    alt: Fcitx5 for Android
  actions:
    - theme: brand
      text: 快速上手
      link: /guide/quick-start
    - theme: alt
      text: 功能总览
      link: /features/overview
    - theme: alt
      text: 下载
      link: /guide/installation
    - theme: alt
      text: GitHub
      link: https://github.com/fxliang/fcitx5-android

features:
  - icon: 🎹
    title: 浮动 / 分屏 / 单手键盘
    details: 浮动键盘可拖拽、调整尺寸；横屏自动分屏并支持校准；左右手单手模式适配大屏。
    link: /features/keyboard/float-keyboard
    linkText: 查看键盘特性
  - icon: 🛠️
    title: 可视化编辑器套件
    details: 应用内编辑键盘布局、Popup 预设、主题、MacroKey、字体集，所见即所得；JSON 配置可手改可分享。
    link: /features/editor/layout-editor
    linkText: 查看编辑器
  - icon: 📷
    title: 二维码分享与扫码导入
    details: 布局、Popup、主题等配置可生成二维码分享；扫码或扫文件即可导入他人配置。
    link: /features/theme/share-import
    linkText: 了解 QR 分享
  - icon: 🎨
    title: 主题增强
    details: 简易主题编辑器、Monet 动态取色、磨砂按键、HSV 颜色选择器、子目录主题与多编码 ZIP 导入。
    link: /features/theme/theme-editor
    linkText: 查看主题增强
  - icon: 🎛️
    title: MacroKey 宏键
    details: 全新按键类型，单键即可执行点击/滑动/长按三种宏序列，支持按键、文本、快捷键、层切换等多种 step。
    link: /features/keys/macro-key
    linkText: 了解 MacroKey
  - icon: 📋
    title: 内置剪贴板同步
    details: 主程序自带剪贴板同步，兼容 SyncClipboard / Oneclip / ClipCascade；支持文本条目分词选择。
    link: /features/clipboard-sync
    linkText: 了解剪贴板同步
  - icon: 🔄
    title: 自带更新检查器
    details: 直接拉取 fxliang/fcitx5-android 的 GitHub Release，支持镜像下载，下载速度平滑显示。
    link: /features/update-checker
    linkText: 了解更新检查
---

## 这是什么？

这是 **[fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android)** 仓库 `fx` 分支的最终用户文档。

[fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android) 是 [上游 fcitx5-android/fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 的一个深度魔改分支，相对上游已合入 300+ 次提交、70+ 个新功能，新增了浮动键盘、可视化编辑器套件、QR 配置分享、MacroKey 宏键、Monet 主题、内置剪贴板同步、内置更新检查器等大量特性。

> **不是上游官方文档。** 想了解上游版本请前往 [上游仓库](https://github.com/fcitx5-android/fcitx5-android)。

## 这份文档的定位

- **只覆盖 [fxliang](https://github.com/fxliang/fcitx5-android) 相对上游新增或修改的功能**
- 上游已有的通用功能（基础键盘输入、Fcitx5 引擎机制等）请参考 [上游 Wiki](https://github.com/fcitx5-android/fcitx5-android/wiki)
- 目标读者：**使用者**，不是开发者

## 快速导航

- 第一次使用？→ [安装](/guide/installation) → [快速上手](/guide/quick-start)
- 想知道 fxliang 加了什么？→ [功能总览](/features/overview)
- 想自定义键盘外观？→ [主题编辑器](/features/theme/theme-editor) / [布局编辑器](/features/editor/layout-editor)
- 想用浮动键盘？→ [浮动键盘](/features/keyboard/float-keyboard)
- 遇到问题？→ [常见问题](/troubleshooting/faq) · [OEM 关联启动](/troubleshooting/oem-startup)
- 想了解与上游的差异 / 致谢？→ [关于](/about/credits)
