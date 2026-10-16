# 真机验证·安装（2026-10-16，s-v-install，第十四包）

设备：e09303ba（PJD110，`adb devices -l` 在线）。

- APK：`direct-rime-proto/appbuild/app/build/outputs/apk/fx/debug/org.fxrime.ime-a21c1b2-arm64-v8a-debug.apk`，
  35,161,408 字节（本包，含设置页三项实现 + 语言键补发表修法）。
- 安装：`adb install -r` 回执原文：

```text
Performing Streamed Install
Success
```

- 包内核对（`dumpsys package org.fxrime.ime.debug`）：`versionName=a21c1b2`；
  输入法 ID：`org.fxrime.ime.debug/org.fcitx.fcitx5.android.input.FcitxInputMethodService`
  （`ime list -a` 实测）。
- logcat 现状：按 `DirectRimeStart,DirectRimeKey` tag 过滤当前无输出——正常：
  引擎 `start()` 只在该输入法被用户选中启用后才跑，需用户切输入法并调起键盘后
  再抓启动链（下一步 s-v-start）。
