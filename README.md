# 中州鹅（Direct Rime）

<img src="artwork/zhongzhou-goose.svg" alt="中州鹅：展翅伸颈、咬住㞢的白鹅" width="96" height="96">

中州鹅是 Android 输入法，直接调用 [Rime 中州韵](https://github.com/rime) 引擎：**方案**决定怎么打字、打出什么，**键盘布局**决定屏幕上有哪些键和手势，两者分开配置。

它沿用 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android) 的前端成果，独立维护直连 Rime 的实现，不含 Fcitx 后端。因此本项目的包名、安装包、文档和反馈渠道都与上游不同，上游的发行版和群组并不适用。

[下载安装](https://github.com/SandyYuR/fcitx5-android/releases) · [用户手册](https://sandyyur.github.io/fcitx5-android/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [功能说明](https://sandyyur.github.io/fcitx5-android/features/overview) · [反馈问题](https://github.com/SandyYuR/fcitx5-android/issues)

## 安装与首次使用

1. 在 [Releases](https://github.com/SandyYuR/fcitx5-android/releases) 下载匹配设备 ABI 的 APK（常见手机为 ARM64），安装后在系统设置中启用并切换到「中州鹅」。
2. **安装包不含中文方案和词典。** 打开「中州鹅」→「输入与候选 → 中州韵设置」，导入完整 Rime 方案（或指向已有的用户数据目录）并部署；跳过这步只有英文键盘、打不出中文。
3. 短按语言键切换中英文（具体行为由方案的 `ascii_composer` 决定），长按打开方案选单；方案与语言的记忆遵循 Rime 的 `switcher`、`save_options` 设置。
4. 在「键盘」和「外观」里调整布局、形态、主题与字体。设置页支持按名称、别名和拼音搜索。

详细步骤见[首次输入](https://sandyyur.github.io/fcitx5-android/guide/quick-start)和[方案、部署与同步](https://sandyyur.github.io/fcitx5-android/features/rime-enhancements)。

## 功能一览

| 功能 | 说明 |
| --- | --- |
| 键盘与候选 | 分体、单手、浮动键盘，滑动与长按手势；横向、展开、浮动候选，字体、注释与序号可分别设置 |
| 布局编辑器 | 应用内编辑与预览，多份布局、方案子布局、弹出字符、宏和二维码分享，也支持[电脑与局域网编辑器](https://sandyyur.github.io/fcitx5-android/features/online-editor) |
| 主题 | 自定义、图片、随机与 Monet 主题，图标与字体；键盘主题和设置界面外观分别管理 |
| 语音输入 | [AIDL 提供方](ASR_VOICE_INPUT_AIDL_INTEGRATION.md)与 [JS 插件](https://sandyyur.github.io/fcitx5-android/features/voice-input-js)，内置千问、豆包、腾讯云识别，密钥自备 |
| [AI 助手](https://sandyyur.github.io/fcitx5-android/features/ai-assistant) | 浮动面板中对话，预览确认后上屏；支持 Chat Completions、Messages、Responses 协议和自定义 HTTPS 端点 |
| 剪贴板与备份 | 分组历史、浮动搜索与编辑、远端同步；布局、主题、偏好和 Rime 数据的手动备份与恢复 |
| Rime 方案更新 | 「数据与备份 → Rime方案更新」先暂存后应用，失败按事务恢复；万象工具只对万象用户有意义 |
| 输入统计 | [字词云和键位热力](https://sandyyur.github.io/fcitx5-android/features/input-statistics)，统计与字词记录分别开启，默认关闭 |

语音入口在「输入与候选 → 语音输入」，JS 插件与 AI 设置在「便捷功能」，AI 按钮可在工具栏自定义中添加。

## 包名与数据

| 构建 | 包名 | 用途 |
| --- | --- | --- |
| Release 主应用 | `org.fcitx.fcitx5.android.fx.rime` | 日常使用 |
| Debug 主应用 | `org.fcitx.fcitx5.android.fx.rime.debug` | 调试用，数据与 Release 独立 |
| `.lab` 诊断包 | `org.fcitx.fcitx5.android.fx.rime.lab` | 最小引擎诊断 |

覆盖升级要求同包名、同签名；换包名、换签名或卸载前先备份。不同输入法各用独立的 Rime 工作目录，通过同步目录交换词典，不要同时打开同一数据库。详见[备份与恢复](https://sandyyur.github.io/fcitx5-android/features/backup-restore)。

统计默认关闭，密码、PIN、隐私输入与内部编辑不计入采集，统计数据单独以 JSON 备份。语音与 AI 的密钥由宿主加密保存，网络主机按插件授权。

## 参与开发

开发前请先读 [AGENTS.md](AGENTS.md)，改界面再读 [DESIGN.md](DESIGN.md)。完整手册、协议与验证记录在仓库的 `docs` 分支（[维护手册](https://sandyyur.github.io/fcitx5-android/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)），代码分支不存放整套手册。

工具链为 Java 17、Android SDK 36、NDK `28.0.13004108`、CMake `3.31.6`，版本以 [Versions.kt](build-logic/convention/src/main/kotlin/Versions.kt) 为准。从仓库根构建 ARM64 Debug：

```sh
./prepare_personal_build.sh
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
python3 tools/audit-direct-rime-link.py app/build/outputs/apk/fx/debug/*.apk
```

产物在 `app/build/outputs/apk/fx/debug/`；JVM 测试为 `:app:testFxDebugUnitTest`。非空 `BUILD_ABI` 环境变量优先于 `-PbuildABI`，可逗号分隔多个 ABI。Windows 用 `gradlew.bat`，缺少 Bash 时先执行 `git submodule update --init --depth 1 native/rime-prelude` 与 `gradlew.bat prepareRimeSdk`。

也可以用 GitHub **Code → Codespaces**（建议 4 核 8 GB）在线开发：首次启动会装好工具链并下载校验锁定的 Rime SDK，之后在 **Terminal → Run Task** 选择构建或测试任务，等价命令是 `bash .devcontainer/verify-build.sh` 与 `bash .devcontainer/test.sh`。

内置引擎来自 [SandyYuR/librime 的 zhongzhou 分支](https://github.com/SandyYuR/librime/tree/zhongzhou)，版本与摘要由 [native/rime-sdk.lock.json](native/rime-sdk.lock.json) 固定；用户自行授权的 `.so` 或引擎 APK 属于运行时变体，不改写该锁。详见[原生依赖说明](native/README.md)。`-PinputDiagnostics=true` 生成逐键性能诊断包，`-PdirectRimePrototype=true` 额外构建 `.lab` 诊断模块，日常构建都不需要。

性能探针、归因脚本与设备门禁只面向维护者，用法、数据协议和历次结论见[维护手册](https://sandyyur.github.io/fcitx5-android/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)，不在日常使用和贡献的必经路径上。

## 项目来源

项目继承 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android) 与 [fxliang 的 fx 分支](https://github.com/fxliang/fcitx5-android) 的前端成果。旧的 Fcitx 版本固定在 `v0-archive-fcitx-before-direct-20261019`，资料保留在[历史文档](https://sandyyur.github.io/fcitx5-android/legacy/)。

感谢 Fcitx5 for Android、fxliang、Rime、OpenCC 与所有贡献者。上游署名与许可证见 [LICENSE](LICENSE) 和应用内第三方库清单。
