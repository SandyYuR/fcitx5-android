# 中州鹅

<img src="artwork/zhongzhou-goose.svg" alt="中州鹅：展翅伸颈、咬住㞢的白鹅" width="96" height="96">

中州鹅是一款直接调用 [Rime 中州韵](https://github.com/rime) 引擎的 Android 输入法。**Rime 方案**决定怎么打字、打出什么，**键盘布局**决定屏幕上有哪些键和手势，两者分开设置。

[下载安装](https://github.com/SandyYuR/fcitx5-android/releases) · [用户手册](https://sandyyur.github.io/fcitx5-android/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [功能说明](https://sandyyur.github.io/fcitx5-android/features/overview) · [反馈问题](https://github.com/SandyYuR/fcitx5-android/issues)

## 三步开始使用

1. **安装**：在 [Releases](https://github.com/SandyYuR/fcitx5-android/releases) 下载与设备 ABI 一致的 `zhongzhoue-<版本>-<abi>.apk`，多数手机选 `arm64-v8a`。安装后在系统设置中启用并切换到「中州鹅」。
2. **导入方案**：安装包**不含**中文方案和词典。打开「中州鹅 → 输入与候选 → 中州韵设置」，导入完整的 Rime 方案（或指向已有的用户目录）并部署；万象方案用户也可以在「数据与备份 → Rime方案更新」里下载和更新。跳过这一步只能打英文。
3. **开始打字**：短按语言键切换中英文，长按打开方案选单。布局、主题和字体在「键盘」「外观」里调整，设置页可以按名称或拼音搜索。

详细步骤见[第一次中文输入](https://sandyyur.github.io/fcitx5-android/guide/quick-start)。

## 主要功能

| | |
| --- | --- |
| [键盘](https://sandyyur.github.io/fcitx5-android/features/overview) | 分体、浮动、单手键盘；滑动与长按手势；[布局编辑器](https://sandyyur.github.io/fcitx5-android/features/editor/layout-editor)可在手机上编辑，也可用[电脑或局域网网页](https://sandyyur.github.io/fcitx5-android/features/online-editor)编辑 |
| [候选](https://sandyyur.github.io/fcitx5-android/features/candidate-window) | 工具栏候选栏、展开面板与浮动候选窗（可跟随光标）；万象方案可手动调整候选顺序 |
| [外观](https://sandyyur.github.io/fcitx5-android/features/theme/theme-editor) | 自定义、图片、随机与 Monet 主题，[图标主题](https://sandyyur.github.io/fcitx5-android/features/theme/icon-themes)，「水彩」「普蓝」成套主题，按键、候选、注释字体分别设置 |
| [语音与 AI](https://sandyyur.github.io/fcitx5-android/features/voice-input-js) | 外部语音应用与 JS 语音插件（内置通义千问、豆包、腾讯云）；[AI 文本助手](https://sandyyur.github.io/fcitx5-android/features/ai-assistant)；有道、哔哩哔哩翻译工具。云服务账号与密钥需自备 |
| [剪贴板](https://sandyyur.github.io/fcitx5-android/features/clipboard-sync) | 分组历史、搜索与编辑，可选远端同步 |
| [数据](https://sandyyur.github.io/fcitx5-android/features/backup-restore) | 完整备份与恢复（默认加密）；[Rime 方案更新](https://sandyyur.github.io/fcitx5-android/features/rime-enhancements#万象方案更新与高级设置)；可选的[输入统计](https://sandyyur.github.io/fcitx5-android/features/input-statistics)（默认关闭） |

## 安装须知

- 主应用包名为 `org.fcitx.fcitx5.android.fx.rime`。Debug 版带 `.debug` 后缀，可与正式版共存，数据互相独立。
- 覆盖升级要求同包名、同签名；换包名、换签名或卸载前请先[备份](https://sandyyur.github.io/fcitx5-android/features/backup-restore)。
- Rime 引擎已内置，不需要另装插件 APK；需要时可在「输入与候选 → Rime引擎变体」接入外部引擎。
- 与其他 Rime 输入法同时使用时，各自用独立的工作目录，通过同步目录交换词典。
- 隐私：输入统计默认关闭，密码、PIN 等敏感输入不计入；语音和 AI 的密钥加密保存，联网按插件逐个授权。

本项目沿用 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android) 的键盘与设置界面，但引擎直连 Rime、不含 Fcitx 后端。包名、安装包、文档和反馈渠道都与上游不同，上游的发行版和群组不适用。

## 参与开发

开发前先读 [AGENTS.md](AGENTS.md)，改界面再读 [DESIGN.md](DESIGN.md)。手册、协议与验证记录在仓库的 `docs` 分支，入口是[维护手册](https://sandyyur.github.io/fcitx5-android/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)。

工具链为 Java 17、Android SDK 36、NDK `28.0.13004108`、CMake `3.31.6`，以 [Versions.kt](build-logic/convention/src/main/kotlin/Versions.kt) 为准。在仓库根目录构建 ARM64 Debug：

```sh
./prepare_personal_build.sh
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
python3 tools/audit-direct-rime-link.py app/build/outputs/apk/fx/debug/*.apk
```

- JVM 测试：`:app:testFxDebugUnitTest`。非空的 `BUILD_ABI` 环境变量优先于 `-PbuildABI`，可用逗号分隔多个 ABI。
- Windows：用 `gradlew.bat`；没有 Bash 时先执行 `git submodule update --init --depth 1 native/rime-prelude` 和 `gradlew.bat prepareRimeSdk`。
- 在线开发：GitHub **Code → Codespaces**（建议 4 核 8 GB），首次启动会装好工具链并下载锁定的 Rime SDK；构建和测试分别对应 `bash .devcontainer/verify-build.sh` 与 `bash .devcontainer/test.sh`。
- 内置引擎来自 [SandyYuR/librime 的 zhongzhou 分支](https://github.com/SandyYuR/librime/tree/zhongzhou)，版本由 [native/rime-sdk.lock.json](native/rime-sdk.lock.json) 固定，详见[原生依赖说明](native/README.md)。
- 维护者选项：`-PinputDiagnostics=true` 生成逐键性能诊断包，`-PdirectRimePrototype=true` 额外构建 `.lab` 引擎诊断包（`org.fcitx.fcitx5.android.fx.rime.lab`）；日常构建不需要。

## 致谢与许可

项目继承 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android) 与 [fxliang 的 fx 分支](https://github.com/fxliang/fcitx5-android) 的前端成果。旧的 Fcitx 版本固定在标签 `v0-archive-fcitx-before-direct-20261019`，资料保留在[历史文档](https://sandyyur.github.io/fcitx5-android/legacy/)。

感谢 Fcitx5 for Android、fxliang、Rime、OpenCC 与所有贡献者。许可证见 [LICENSE](LICENSE)，第三方库清单见应用内说明。
