# 介绍

## 什么是中州鹅

中州鹅是保留完整 Android 键盘、候选、布局编辑器、宏、主题和剪贴板功能的 Rime 输入法。
Android 前端继承 Fcitx5 for Android 与 fxliang 的 fx 分支，当前直连版通过 JNI 直接调用 librime。
Fcitx 原生 Core、fcitx5-rime 和 AndroidFrontend 已移除；前端历史类名与许可证署名仍保留。

本文档对应已通过 [代码 PR #4](https://github.com/SandyYuR/fcitx5-android/pull/4) 合入默认分支的直连版。
默认分支为 `fx-rime-only`；安装时按[安装说明](/guide/installation)核对 Release 或 CI 的源码和 APK，当前文档来源见[提交对照](/maintainer/changes/)。

## 包名与发行

应用名为“中州鹅”，主应用包名为 `org.fcitx.fcitx5.android.fx.rime`。
这个包名与本仓库旧 Fcitx 版相同，同签名时可覆盖升级，安装前先备份。
旧直连测试包 `io.github.sandyyur.rime.direct` 需要导出后在新包恢复。

不需要安装引擎插件 APK。APK 不预装中文输入方案、完整词典或万象语言模型；
先按[快速上手](/guide/quick-start)导入方案并部署。

## 能做什么

提供浮动、分体和单手键盘；可视化布局、Popup、宏、主题与字体编辑；
候选栏、符号面板、剪贴板历史与同步、语音入口、数据备份和应用内更新。
Rime 方案与屏幕键盘布局分别配置，换方案不会自动导入屏幕布局。

完整 XKB Compose、按应用 `app_options` 等价和广泛厂商真机回归仍在维护范围内。
布局的 Compose Override 指输入中更换键面，与完整 XKB Compose 不是同一功能。

- [用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN)
- [维护手册与 librime 更新](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)
- [致谢与差异说明](/about/credits)
