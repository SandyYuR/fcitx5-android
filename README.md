# 中州鹅（Direct Rime）

<img src="artwork/zhongzhou-goose.svg" alt="中州鹅：展翅伸颈、咬住㞢的白鹅" width="96" height="96">

中州鹅是直接调用 [Rime 中州韵](https://github.com/rime) 的 Android 输入法，提供键盘、候选、布局编辑器、主题和 Material 3 设置界面。Rime 方案决定输入规则与词典，键盘布局决定屏幕按键和手势，两者分别配置。

[下载安装](https://github.com/SandyYuR/fcitx5-android/releases) · [用户手册](https://sandyyur.github.io/fcitx5-android/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [功能指南](https://sandyyur.github.io/fcitx5-android/features/overview) · [维护手册](https://sandyyur.github.io/fcitx5-android/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)

## 开始使用

1. 安装适合设备 ABI 的完整主应用，在 Android 设置中启用并选择“中州鹅”。常用 ARM64 手机可使用 Releases 的 Nightly，或成功的 `Commit CI` 中的 `direct-rime-frontend-arm64-release`。
2. 进入“输入与候选 → 中州韵设置”，导入完整 Rime 方案，或选择已有用户数据目录后部署。**APK 不预装中文方案、完整词典或万象模型**，首次只有键盘而无中文候选时，先完成这一步。
3. 短按语言键切换中英文，具体行为由方案的 `ascii_composer` 决定；长按打开方案选单，状态区可操作方案开关。方案与语言记忆遵循 Rime 的 `switcher/save_options`、`reset` 和方案列表设置。
4. 在“键盘”和“外观”调整布局、形态、主题与字体。设置搜索可按名称、别名和拼音定位入口。

详细步骤见[首次输入](https://sandyyur.github.io/fcitx5-android/guide/quick-start)和[方案、部署与同步](https://sandyyur.github.io/fcitx5-android/features/rime-enhancements)。

## 主要功能

| 功能 | 使用范围与说明 |
| --- | --- |
| 键盘与候选 | 分体、单手、浮动、滑动和长按；横向、展开与浮动候选，独立字体、注释和序号配置 |
| 布局与宏 | 应用内编辑、多份布局、方案子布局、合成态预览、弹出字符、宏及二维码分享；支持[电脑与局域网编辑器](https://sandyyur.github.io/fcitx5-android/features/online-editor) |
| 主题与设置外观 | 自定义、图片、随机与 Monet 主题，图标和字体；键盘主题与设置界面外观分别管理 |
| 语音 | [外部 AIDL 提供方](ASR_VOICE_INPUT_AIDL_INTEGRATION.md)与[JS 插件](https://sandyyur.github.io/fcitx5-android/features/voice-input-js)；内置千问、豆包、腾讯云识别及实时翻译，服务参数和密钥需自行配置 |
| AI 文本助手 | 浮动面板输入、阅读回复、预览并确认上屏；支持 Chat Completions、Messages、Responses 三种文本协议及自定义 HTTPS 端点，详见[配置与授权](https://sandyyur.github.io/fcitx5-android/features/ai-assistant) |
| 剪贴板与备份 | 分组历史、浮动搜索与编辑、远端同步；布局、主题、偏好和 Rime 数据的手动备份与恢复 |
| Rime 方案更新 | “数据与备份 → Rime方案更新”提供万象更新、高级配置与自定义资源任务；批次先暂存后应用，失败按事务恢复。万象工具仅适用于万象用户 |
| 输入统计与键盘测试 | [本地统计、字词云和键位热力](https://sandyyur.github.io/fcitx5-android/features/input-statistics)；统计和字词记录分别手动开启。设置页可临时测试文本、数字、密码和 PIN |

语音入口在“输入与候选 → 语音输入”；JS 插件与 AI 设置也可从“便捷功能 → JS 插件设置”进入。AI 按钮可在工具栏自定义中添加，生成后由用户确认上屏。

## 安装与数据

| 构建 | 包名与用途 |
| --- | --- |
| Release 主应用 | `org.fcitx.fcitx5.android.fx.rime`，日常使用 |
| Debug 主应用 | `org.fcitx.fcitx5.android.fx.rime.debug`，完整功能调试，与 Release 数据独立 |
| `.lab` 诊断包 | `org.fcitx.fcitx5.android.fx.rime.lab`，最小引擎诊断 |

覆盖升级需要同包名、同签名；换包名、签名或卸载前先备份。不同输入法各用独立的 Rime 工作目录，通过同步目录交换词典，避免同时打开同一数据库。Rime 同步与应用整包备份各有用途，见[备份与恢复](https://sandyyur.github.io/fcitx5-android/features/backup-restore)。

输入统计默认关闭，密码、PIN、隐私输入及内部编辑排除采集；统计数据使用单独的 JSON 备份。普通键盘测试仍遵循当前学习与统计设置。语音和 AI 的密钥由宿主加密保存，网络主机按插件授权；跨设备或卸载重装后的密钥迁移限制见手册。

## 开发与构建

开发者和 AI 编程助手先读 [AGENTS.md](AGENTS.md)，界面修改再读 [DESIGN.md](DESIGN.md)。完整手册、协议和验证记录维护在同仓库的 `docs` 分支，代码分支不存放整套手册。

使用 Java 17、Android SDK 36、NDK `28.0.13004108`、CMake `3.31.6`；具体版本与支持的 ABI 以 [Versions.kt](build-logic/convention/src/main/kotlin/Versions.kt) 为准。下面从仓库根构建 ARM64 Debug：

```sh
./prepare_personal_build.sh
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
python3 tools/audit-direct-rime-link.py app/build/outputs/apk/fx/debug/*.apk
```

产物在 `app/build/outputs/apk/fx/debug/`。非空 `BUILD_ABI` 环境变量优先于 `-PbuildABI`；支持逗号分隔，均未设置时构建全部 ABI。JVM 测试任务为 `:app:testFxDebugUnitTest`，按改动选择测试及设备门禁，见 AGENTS 与维护手册。

Windows 使用 `gradlew.bat` 和可用的 Python；没有 Bash 时，执行 `git submodule update --init --depth 1 native/rime-prelude`，再执行 `./gradlew.bat prepareRimeSdk`。Python 门禁传入本次 APK 的实际路径。维护文档中的本机 PowerShell 脚本不随仓库分发，无需复制作者的盘符和目录结构。

引擎来自 [SandyYuR/librime 的 zhongzhou](https://github.com/SandyYuR/librime/tree/zhongzhou)，由 [native/rime-sdk.lock.json](native/rime-sdk.lock.json) 固定 SDK 版本与摘要，Gradle 下载并校验。共享预设 `native/rime-prelude` 是唯一子模块；当前构建不使用旧 prebuilder/prebuilt。开发 SDK 仅显式接入 Debug，详见[原生依赖说明](native/README.md)和[引擎更新流程](https://sandyyur.github.io/fcitx5-android/maintainer/engine-update)。

`-PinputDiagnostics=true` 用于逐键性能诊断，与用户开启的统计、词云功能独立。`-PdirectRimePrototype=true` 额外包含 `.lab` 诊断模块；日常构建无需选择后端。

## 项目来源与范围

项目继承 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android) 与 [fxliang 的 fx 分支](https://github.com/fxliang/fcitx5-android) 的 Android 前端成果，独立维护直连 Rime 实现。旧 Fcitx 版固定在 `v0-archive-fcitx-before-direct-20261019`，资料保留在[历史文档](https://sandyyur.github.io/fcitx5-android/legacy/)。

完整 XKB Compose、按应用行为等价与广泛设备回归仍有边界；历史测试和性能数据保留各自的版本、方案与设备条件，不作为所有设备的保证。

感谢 Fcitx5 for Android、fxliang、Rime、OpenCC 与社区贡献者；方案更新、统计和测试面板的参考来源见对应功能页。保留上游署名与许可证，参见 [LICENSE](LICENSE) 和应用内第三方库清单。
