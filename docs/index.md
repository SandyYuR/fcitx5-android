---
layout: home

hero:
  name: 中州鹅
  text: 直连 librime 的 Android 输入法
  tagline: 保留完整键盘、候选、布局编辑器、主题与剪贴板，直接调用 Rime 引擎
  image:
    src: /logo.png
    alt: 中州鹅
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
    details: librime 静态链接进主 APK，方案选单、分组开关、部署与同步全部内建；不需要再装任何插件 APK。
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

中州鹅保留 Fcitx5 for Android 与 fxliang fx 分支的 Android 前端，直接调用 librime。
当前手册对应已通过 [PR #4](https://github.com/SandyYuR/fcitx5-android/pull/4) 合入默认分支的直连版，
包名为 `org.fcitx.fcitx5.android.fx.rime`。主应用、迁移方式和已知范围见[当前状态](/guide/direct-rime-migration)。

::: info 版本与文档范围
默认分支 `fx-rime-only` 已切换为直连版。签名 Nightly、实际源码与补丁验证见[交付记录](/maintainer/DIRECT_RIME_RELEASE_READY_2026-10-21)。
旧 Fcitx 版手册和实施方案保留在历史入口，当前操作以中州鹅手册为准。
:::

::: warning 首次安装须知
APK 不预装中文方案、完整词典或万象语言模型。安装并启用后，先导入完整方案并部署，见[快速上手](/guide/quick-start)。
:::

## 快速导航

- [安装与升级](/guide/installation) · [快速上手](/guide/quick-start) · [数据迁移与共存](/guide/builds-and-plugins)
- [完整用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [Rime 设置与引擎来源](/features/rime-enhancements)
- [功能总览](/features/overview) · [布局编辑器](/features/editor/layout-editor) · [设置外观](/features/theme/settings-appearance)
- [当前基线、APK、CI 和合入顺序](/maintainer/DIRECT_RIME_RELEASE_READY_2026-10-21)
- [维护手册与 librime 更新](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN) · [完整直连文档](/direct-rime)
- [常见问题](/troubleshooting/faq) · [反馈问题](/troubleshooting/report-issue) · [致谢](/about/credits)
