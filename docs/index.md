---
layout: home

hero:
  name: 靓企鹅·中州韵
  text: Rime 专版 Android 输入法
  tagline: 基于 fxliang/fcitx5-android 的 fx 分支继续开发 —— 一个 APK 内置 Fcitx5 底盘与 Rime 引擎，Rime 是唯一输入引擎；浮动键盘、可视化编辑器、主题、剪贴板同步等应用层能力全部保留
  image:
    src: /logo.png
    alt: 靓企鹅·中州韵
  actions:
    - theme: brand
      text: 快速上手
      link: /guide/quick-start
    - theme: alt
      text: 功能总览
      link: /features/overview
    - theme: alt
      text: 下载安装
      link: /guide/installation
    - theme: alt
      text: GitHub
      link: https://github.com/SandyYuR/fcitx5-android

features:
  - icon: 🀄
    title: Rime 引擎内置，免插件
    details: librime 静态链接进主 APK，方案选单、候选 tabs、部署与同步全部内建；不需要再装任何插件 APK。
    link: /features/rime-enhancements
    linkText: 了解 Rime 引擎
  - icon: 🎹
    title: 浮动 / 分体 / 单手键盘
    details: 浮动键盘可拖拽、调整尺寸；分体键盘支持按方案单独配布局与断点；左右手单手模式适配大屏。
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
  - icon: 📋
    title: 剪贴板同步与历史搜索
    details: 兼容 SyncClipboard / Oneclip / ClipCascade；剪贴板窗口支持打字即搜的实时过滤，只查本机、不外发数据。
    link: /features/clipboard-sync
    linkText: 了解剪贴板同步
  - icon: ⌨️
    title: Foxy 风格符号面板
    details: 符号 / 表情 / 颜文字三面板约 6900 条内置条目，左侧分组栏 + 右侧网格；三类数据均可替换为自定义 JSON。
    link: /guide/concepts
    linkText: 了解符号面板
  - icon: 🔄
    title: 自带更新检查器
    details: 直接拉取本仓库 SandyYuR/fcitx5-android 的 GitHub Release（含 Nightly 预发布），支持镜像规则与自定义 hosts。
    link: /features/update-checker
    linkText: 了解更新检查
---

## 这是什么？

这是 **[SandyYuR/fcitx5-android](https://github.com/SandyYuR/fcitx5-android)** 仓库 `fx-rime-only` 分支构建的应用 **靓企鹅·中州韵**（其他语言显示名 Fcitx5.fx.rime，包名 `org.fcitx.fcitx5.android.fx.rime`）的最终用户文档。

本版本以 [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android) 的 `fx` 分支为基线继续专用化：把「Rime 只是众多输入法之一」改成「**Rime 是唯一的输入引擎**」，fcitx5-rime 并入主 APK，删除了拼音、码表等其他输入链路与整套插件框架；同时保留了 fx 分支的键盘形态、编辑器、主题、剪贴板同步等应用层能力，并持续增加自有改进。

::: warning 首次安装须知
本版 **不预置任何输入方案，也不附带 essay.txt 预置词汇表**。装好、启用之后键盘能正常弹出，但**打不出汉字**——这是预期状态，不是故障。请按 [快速上手](/guide/quick-start) 放入你自己的方案并部署一次。
:::

## 这份文档的定位

- 覆盖 **Rime 专版行为**（方案部署、语言键、候选手势等）与**继承自 fx 分支的应用层功能**（键盘形态、编辑器、主题等）
- Fcitx5 框架与 Rime 本身的通用用法请参考 [上游 Wiki](https://github.com/fcitx5-android/fcitx5-android/wiki) 与 [Rime 官方文档](https://github.com/rime/home/wiki)
- 目标读者：**使用者**，不是开发者
- 更完整的逐项操作手册见 [用户指南](/manual/RIME_ONLY_USER_GUIDE_zh-CN)

## 快速导航

- 第一次使用？→ [安装](/guide/installation) → [快速上手](/guide/quick-start)（含放入方案与部署）
- 想知道这个版本能做什么？→ [功能总览](/features/overview)
- 想配置 Rime 方案？→ [Rime 引擎（内置）](/features/rime-enhancements)
- 想要逐项的完整操作手册？→ [用户指南](/manual/RIME_ONLY_USER_GUIDE_zh-CN)
- 想自定义键盘外观？→ [主题编辑器](/features/theme/theme-editor) / [布局编辑器](/features/editor/layout-editor)
- 想用浮动键盘？→ [浮动键盘](/features/keyboard/float-keyboard)
- 从上游 / fxliang 迁移数据？→ [从上游迁移](/guide/migrate-from-upstream)
- 遇到问题？→ [常见问题](/troubleshooting/faq)
- 维护者（引擎 runbook / 审阅报告）？→ [交接报告](/maintainer/HANDOVER-rime-only) / [审阅报告](/maintainer/CODE_REVIEW_REPORT_2026-09-07)
- 想了解与 fxliang / 上游的关系与致谢？→ [关于](/about/credits)
