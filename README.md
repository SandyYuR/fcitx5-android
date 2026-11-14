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

输入链路与线程、候选和维护边界见[Rime 直连架构](https://sandyyur.github.io/fcitx5-android/maintainer/implementation/direct-rime-architecture)。

使用 Java 17、Android SDK 36、NDK `28.0.13004108`、CMake `3.31.6`；具体版本与支持的 ABI 以 [Versions.kt](build-logic/convention/src/main/kotlin/Versions.kt) 为准。下面从仓库根构建 ARM64 Debug：

```sh
./prepare_personal_build.sh
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
python3 tools/audit-direct-rime-link.py app/build/outputs/apk/fx/debug/*.apk
```

产物在 `app/build/outputs/apk/fx/debug/`。非空 `BUILD_ABI` 环境变量优先于 `-PbuildABI`；支持逗号分隔，均未设置时构建全部 ABI。JVM 测试任务为 `:app:testFxDebugUnitTest`，按改动选择测试及设备门禁，见 AGENTS 与维护手册。

Windows 使用 `gradlew.bat` 和可用的 Python；没有 Bash 时，执行 `git submodule update --init --depth 1 native/rime-prelude`，再执行 `./gradlew.bat prepareRimeSdk`。Python 门禁传入本次 APK 的实际路径。维护文档中的本机 PowerShell 脚本不随仓库分发，无需复制作者的盘符和目录结构。

内置引擎来自 [SandyYuR/librime 的 zhongzhou](https://github.com/SandyYuR/librime/tree/zhongzhou)，由 [native/rime-sdk.lock.json](native/rime-sdk.lock.json) 固定 SDK 版本与摘要，Gradle 下载并校验。用户另行授权的 `.so` 或引擎提供 APK 属于运行时变体，不改写该锁。共享预设 `native/rime-prelude` 是唯一子模块；当前构建不使用旧 prebuilder/prebuilt。开发 SDK 仅显式接入 Debug，详见[原生依赖说明](native/README.md)和[引擎更新流程](https://sandyyur.github.io/fcitx5-android/maintainer/engine-update)。

`-PinputDiagnostics=true` 用于逐键性能诊断，与用户开启的统计、词云功能独立。`-PdirectRimePrototype=true` 额外包含 `.lab` 诊断模块；日常构建无需选择后端。

已有候选/连续选词探针的事件 JSON 与 Perfetto trace 可用 `python3 tools/input-perf/associate-events.py --help` 查看逐事件归因用法。工具逐事件核对 trace 范围、进程和线程状态覆盖，缺失数据不会当作有效的零 CPU；失败仍保留 SQL、调度与 JSON。带 trace 的归因与无 trace 性能对照分别报告。

固定节拍探针为 `tools/input-perf/CadenceCandidateTapTest.kt`（复制到 androidTest 中的 `CandidateTapTest.kt`），通过测试专用 InputManager 异步注入，分别记录计划、实际提交及窗口接收时间。v2 提前缓存目标 View 与坐标，窗口回调只检查该目标的位置和附着状态，不再递归搜索整个视图树。固定的是计划节拍，注入线程迟到仍须独立报告。实际保持时长从注入 DOWN 起至少 20ms，DOWN 迟到不会压缩触摸；新样本声明保持策略并单独校验，失败也保留原始 IME 数据。旧记录按其原始探针摘要和策略解释，不能直接作为当前探针的重测结果。它仅用于 API 35 一次性调试模拟器，不进入产品 APK。`python3 tools/input-perf/run-cadence.py --help` 列出来源清单、夹具摘要、执行顺序和独立 trace 参数；分析时使用 `associate-events.py --kind cadence`，不为可合并的中间编码伪造引擎终点。

运行器要求显式传入 `--serial`、`--expected-avd-name` 和 `--allow-destructive-reset`，在设备身份核对完成前不会写设备。该协议会卸载 Debug 包并清空其数据，只能用于已获准清空的一次性测试 AVD，不能直接用于保留日常数据的模拟手机；缺少重置选项会直接退出。所有 ADB 命令绑定同一序列号，不采用环境默认设备。v2 产物清单必须使用 `fixed_cadence_100ms_input_manager_async_cached_targets_v2` 协议和本次探针摘要；旧 `cached_letters` 数据仍可分析，但不能混作本次测量。

需要保留原应用数据的本机结构诊断使用 `tools/input-perf/run-isolated-study.py`。它只接管运行前确认不存在的三个独立诊断包，拒绝采用已有包；临时输入法、设置和屏幕唤醒状态均在原始状态记录后修改并逐项恢复。仅当原前台是本项目 `MainActivity` 时才恢复并验证该窗口，不会擅自启动其他应用。运行仍须显式指定串号、AVD 名称及 `--allow-owned-test-apps`，失败样本和恢复结果会保留，未通过时间轴或完整性校验的数据不能进入性能汇总。

候选字形簇只在真正上滑选字时拆分；分页快照按接收组件能力选择一种表示，普通列表组件仍使用列表事件。字体变化会清理文字测量缓存。这些改动的历次数据和限制见[输入链路复核](https://sandyyur.github.io/fcitx5-android/maintainer/perf/current-input-pipeline-2026-11-13/)与[绘制等待调查](https://sandyyur.github.io/fcitx5-android/maintainer/perf/render-wait-2026-11-14/)，旧性能结果不作为当前组合已提速的证明。

工具回归可在没有设备时运行：`python3 -m unittest discover -s tools/input-perf -p 'test_*.py' -v`。PR CI 执行这些测试并编译固定节拍探针，普通前端设备回归不会执行会准备测试方案的性能探针。

`Candidate latency study` 工作流用于复核首个 i 的分发等待：先以同一个探针构建冻结基线、旧候选试验和指定完整 SHA 的当前版，再在独立 CI 模拟器中读取同一固定夹具。`run-study.py` 的 pilot 明确记录执行顺序，formal 三版本采用六种排列的均衡块；两版本时使用 ABBA/BAAB 块。每个新进程前恢复测试夹具，首轮与后五轮分开统计，同时报告完整 i 触摸、n 到 ni 响应、注入迟到和点选到 IC，不能只比较 UP 提交后的局部等待。trace 是独立 pilot，不并入无 trace 对照；这些脚本只能在明确获准清空的测试 AVD 中执行。

## 项目来源与范围

项目继承 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android) 与 [fxliang 的 fx 分支](https://github.com/fxliang/fcitx5-android) 的 Android 前端成果，独立维护直连 Rime 实现。旧 Fcitx 版固定在 `v0-archive-fcitx-before-direct-20261019`，资料保留在[历史文档](https://sandyyur.github.io/fcitx5-android/legacy/)。

完整 XKB Compose、按应用行为等价与广泛设备回归仍有边界；历史测试和性能数据保留各自的版本、方案与设备条件，不作为所有设备的保证。

感谢 Fcitx5 for Android、fxliang、Rime、OpenCC 与社区贡献者；方案更新、统计和测试面板的参考来源见对应功能页。保留上游署名与许可证，参见 [LICENSE](LICENSE) 和应用内第三方库清单。
