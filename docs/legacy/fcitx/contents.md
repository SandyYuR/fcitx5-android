# 旧版 Fcitx 文档完整目录

这里恢复直连版合入前原站的 **64 篇文档**，包括 13 篇维护报告和完整用户手册。
原文固定于文档分支 `edcf536f`；本站展示只适配内部链接并加上旧版提示。
当前直连版操作请看[中州鹅手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN)。

[原站首页](./) · [旧版维护报告](maintainer/) · [原始源码快照](source-snapshot-edcf536f.zip) · [恢复校验清单](restoration-manifest.json)

## 原站首页与说明

- [文档协作快速开始（docs 分支）](/legacy/fcitx/README)
- [index](/legacy/fcitx/)

## 入门指南

- [构建版本与数据共存](/legacy/fcitx/guide/builds-and-plugins)
- [核心概念](/legacy/fcitx/guide/concepts)
- [安装](/legacy/fcitx/guide/installation)
- [介绍](/legacy/fcitx/guide/introduction)
- [从上游迁移到靓企鹅·中州韵](/legacy/fcitx/guide/migrate-from-upstream)
- [快速上手](/legacy/fcitx/guide/quick-start)

## 功能与编辑器

- [候选窗增强](/legacy/fcitx/features/candidate-window)
- [剪贴板同步（内置）](/legacy/fcitx/features/clipboard-sync)
- [字体集编辑器（字体设定）](/legacy/fcitx/features/editor/fontset-editor)
- [键盘布局编辑器](/legacy/fcitx/features/editor/layout-editor)
- [MacroKey 编辑器](/legacy/fcitx/features/editor/macrokey-editor)
- [Popup 预设编辑器](/legacy/fcitx/features/editor/popup-editor)
- [Kawaii Bar 增强](/legacy/fcitx/features/kawaii-bar)
- [调整模式](/legacy/fcitx/features/keyboard/adjust-mode)
- [Compose Override 按键](/legacy/fcitx/features/keyboard/compose-override)
- [浮动键盘](/legacy/fcitx/features/keyboard/float-keyboard)
- [单手模式](/legacy/fcitx/features/keyboard/one-handed)
- [分体键盘](/legacy/fcitx/features/keyboard/split-keyboard)
- [滑动操作](/legacy/fcitx/features/keyboard/swipe-actions)
- [AlphabetKey 字母键](/legacy/fcitx/features/keys/alphabet-key)
- [BackspaceKey 退格键](/legacy/fcitx/features/keys/backspace-key)
- [CapsKey 大小写键](/legacy/fcitx/features/keys/caps-key)
- [CommaKey 逗号键](/legacy/fcitx/features/keys/comma-key)
- [Fcitx 键名速查](/legacy/fcitx/features/keys/fcitx-keys)
- [LanguageKey 语言键](/legacy/fcitx/features/keys/language-key)
- [LayoutSwitchKey 布局切换键](/legacy/fcitx/features/keys/layout-switch-key)
- [MacroKey 宏键](/legacy/fcitx/features/keys/macro-key)
- [按键类型总览](/legacy/fcitx/features/keys/overview)
- [ReturnKey 回车键](/legacy/fcitx/features/keys/return-key)
- [SpaceKey 空格键](/legacy/fcitx/features/keys/space-key)
- [SymbolKey 符号键](/legacy/fcitx/features/keys/symbol-key)
- [在线编辑器](/legacy/fcitx/features/online-editor)
- [功能总览](/legacy/fcitx/features/overview)
- [Rime 引擎（内置）](/legacy/fcitx/features/rime-enhancements)
- [共享导入与解压](/legacy/fcitx/features/shared-import)
- [文本编辑底部栏](/legacy/fcitx/features/text-editor)
- [磨砂按键](/legacy/fcitx/features/theme/frosted-blur)
- [Monet 编辑器](/legacy/fcitx/features/theme/monet)
- [设置界面外观与动画](/legacy/fcitx/features/theme/settings-appearance)
- [QR 分享与导入](/legacy/fcitx/features/theme/share-import)
- [主题编辑器](/legacy/fcitx/features/theme/theme-editor)
- [更新检查器与镜像](/legacy/fcitx/features/update-checker)

## 完整用户手册

- [靓企鹅·中州韵 用户指南（Rime-only 版）](/legacy/fcitx/manual/RIME_ONLY_USER_GUIDE_zh-CN)

## 维护报告与历史方案

- [【历史归档】去掉 fcitx5 底层、自建 librime 直连中间层的可行性调研报告](/legacy/fcitx/maintainer/ARCHIVE_DIRECT_RIME_FEASIBILITY_ORIGINAL_2026-10-02)
- [【历史归档】f5 输入法精简与 Rime 整合实施方案](/legacy/fcitx/maintainer/ARCHIVE_RIME_INTEGRATION_PLAN)
- [【历史归档】将 fx2 分支裁剪为「Rime 专用输入法」可行性报告](/legacy/fcitx/maintainer/ARCHIVE_RIME_ONLY_FEASIBILITY)
- [fx-rime-only 系统性代码审阅报告](/legacy/fcitx/maintainer/CODE_REVIEW_REPORT_2026-09-07)
- [fx2 代码复审报告 · 2026-10-11](/legacy/fcitx/maintainer/CODE_REVIEW_fx2_2026-10-11)
- [去 fcitx5 直连 librime：2026-10-07 再评估](/legacy/fcitx/maintainer/DIRECT_RIME_REEVALUATION_2026-10-07)
- [字体刷新遗留入口评估报告：`FontProviders` 的一次性布尔标志 `needsRefresh`](/legacy/fcitx/maintainer/FONT_REFRESH_LEGACY_FLAG_REPORT_2026-10-04)
- [Foxy 风格符号面板搬运评估（当前实现复盘）](/legacy/fcitx/maintainer/FOXY_SYMBOL_CATALOG_REVIEW_2026-10-07)
- [交接报告 — fx-rime-only（靓企鹅·中州韵，rime 专版）](/legacy/fcitx/maintainer/HANDOVER-rime-only)
- [fx-rime-only 性能专项审阅报告](/legacy/fcitx/maintainer/PERFORMANCE_REVIEW_REPORT_2026-09-07)
- [MD3 设置界面实施记录与合并说明](/legacy/fcitx/maintainer/SETTINGS_MD3_IMPLEMENTATION_2026-10-20)
- [设置界面 Material 3 / M3 Expressive 改造规划](/legacy/fcitx/maintainer/SETTINGS_MD3_PLAN_2026-10-18)
- [设置界面重构方案（2026-09-28 调查报告）](/legacy/fcitx/maintainer/SETTINGS_RESTRUCTURE_PROPOSAL_2026-09-28)

## 疑难解答

- [常见问题](/legacy/fcitx/troubleshooting/faq)
- [OEM 自启动与后台限制](/legacy/fcitx/troubleshooting/oem-startup)
- [反馈问题](/legacy/fcitx/troubleshooting/report-issue)

## 关于项目

- [参与文档修改](/legacy/fcitx/about/contribute-docs)
- [致谢与差异说明](/legacy/fcitx/about/credits)
- [支持与捐赠](/legacy/fcitx/about/donate)
