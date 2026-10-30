# 中州鹅直连版维护手册

本页描述默认分支 `fx-rime-only` 的现行维护方式；2026 年 10 月 30 日的源码核对范围与固定提交见[代码与文档对照](/maintainer/changes/)。功能操作见[用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN)。
历史实验、阶段结论和当时的测试数量在[直连版历史](/maintainer/history/)中保留，含 Fcitx 层的旧版资料在[靓企鹅中州韵档案](/legacy/)中保留。

## 仓库与模块

| 位置 | 职责 |
| --- | --- |
| `app/` | 完整主应用、输入法前端、设置、布局、主题、语音与数据功能 |
| `direct-rime/src/main/cpp/` | 主应用使用的 JNI 桥与 `libdirect-rime.so` |
| `direct-rime/src/engine/` | 引擎封装、owner 线程与快照；独立诊断模块复用 |
| `lib/common/` | IPC、剪贴板及语音 AIDL 公共接口 |
| `native/prebuilt` | SandyYuR 定制静态库，版本由主仓库 gitlink 固定 |
| `native/rime-prelude` | Rime 共享预设子模块 |
| `native/rime-data/default.yaml` | 空方案列表的共享默认配置，不删除 `schema_list: []` |
| `build-logic/`、`tools/`、`.github/workflows/` | 构建版本权威、验证脚本与 CI |

当前只有 `native/prebuilt` 和 `native/rime-prelude` 两个子模块。旧 `lib/fcitx5`、`plugin/rime`、`app/src/main/cpp` 已移除；本地遗留的未跟踪目录不属于构建输入。
代码分支不放 `docs/`；站点、协议和报告统一在 `docs` 分支。

## 构建与安装

工具版本由 `build-logic/convention/src/main/kotlin/Versions.kt` 及构建配置决定。当前使用 Java 17、SDK 36、NDK `28.0.13004108`、CMake `3.31.6`，最低 Android 6.0 / API 23。

```sh
git status --short --branch
git submodule status
./prepare_personal_build.sh
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
python3 tools/audit-direct-rime-link.py app/build/outputs/apk/fx/debug/*.apk
```

`buildABI` 支持 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`。非空 `BUILD_ABI` 环境变量优先于 `-PbuildABI` 属性；两者均未设置时才构建全部支持 ABI。
本机日常脚本与当前 PR/发布 CI 选择 ARM64，设备回归可按需要构建 x86_64。不要把“引擎产物支持四 ABI”写成“当前 PR CI 打包四 ABI”。

| 包 | 包名 | 用途 |
| --- | --- | --- |
| Release 主应用 | `org.fcitx.fcitx5.android.fx.rime` | 日常使用，需要有效签名 |
| Debug 主应用 | `org.fcitx.fcitx5.android.fx.rime.debug` | 完整功能调试，数据与 Release 独立 |
| 独立诊断 APK | `org.fcitx.fcitx5.android.fx.rime.lab` | 最小引擎诊断，不是日用输入法 |

普通构建固定为直连，不需要选择后端。旧参数 `-PdirectRimeFrontend=true` 只作兼容，显式 `false` 会报错；`-PdirectRimePrototype=true` 才额外包含诊断模块。
`-PinputDiagnostics=true` 开启细粒度输入统计，日常构建避免承担这些统计开销。

Debug 产物在 `app/build/outputs/apk/fx/debug/`，Release 在 `fx/release/`。核对 `output-metadata.json`、文件时间及来源提交，不要从目录中随意挑旧 APK。
安装 Debug 后也需要自行导入方案并部署。签名或包名变化前先备份；同包名同签名才可能覆盖升级。

Windows 使用工作区的一键脚本，不另改系统环境：

```powershell
pwsh -File D:\GitHub\fx2-rime\build-debug.ps1
pwsh -File D:\GitHub\fx2-rime\build-debug.ps1 -Test
pwsh -File D:\GitHub\fx2-rime\build-debug.ps1 -Install
pwsh -File D:\GitHub\fx2-rime\build-release.ps1
```

环境异常先看[Windows 本机构建](/maintainer/LOCAL_BUILD_ENVIRONMENT_zh-CN)。Release 签名、安装到用户设备与对外发布各有独立授权和验证要求。

## 输入事件链路

```text
软键盘 / 物理按键
  → FcitxInputMethodService 串行队列（入队前获取 ticket）
  → DirectRimeBackend 与会话代次校验
  → rime-main 单一 owner 线程 → JNI → librime session
  → commit / preedit / 候选 / 方案 / 状态的一次快照
  → InputEventQueue 的有序批次 → InputConnection 与界面
```

`FcitxInputMethodService` 等保留的类名、兼容键名和布局字段不代表旧原生后端仍在运行。

| 必须保持 | 源码 / 验证入口 |
| --- | --- |
| 入队前取得按键 ticket，只有一条服务按键队列 | `InputKeyGate`、`postFcitxJob`、`InputKeyGateTest` |
| commit、delete、key down/up 有序且无损 | `InputEventQueue` 保留副作用批次；纯展示状态只在相邻无副作用批次间合并 |
| 结果消费前校验 session/generation | `prepareInputSession`、`requireInputSession` 与 binding |
| 组合改变时取消候选分页，批间允许其他工作运行 | 顶层函数 `readCandidatePage`、`CandidatePageReaderTest` |
| 部署、同步和重载期间不处理按键，结束后恢复 | `maintain`、`maintenanceMutex`、`keyGate` |
| 刷新和状态提示不能重放 commit | `publishWithoutCommit`、native action 9 |
| DOWN 有兜底 UP，线程优先级在 finally 恢复 | 键盘生命周期、`InteractiveThreadPriority` |

当前事件载体是 `InputEventQueue` 的批次队列；其 `Channel.CONFLATED` 仅发出唤醒信号，不承载输入正文。容量等待发生在进入 owner 前，独立消费者仍可请求候选。早期 `Channel(UNLIMITED)` 的描述属于历史实现，不应据此恢复旧结构。

历史问题与修正过程见[响应性记录](/maintainer/history/DIRECT_RIME_RESPONSIVENESS_2026-10-18)、[候选点选](/maintainer/history/DIRECT_RIME_CANDIDATE_TAP_2026-10-18)和[后续输入回归](/maintainer/audit/direct-rime-2026-10-25/)。

## librime 从哪里来

来源链为 **官方 rime/librime → SandyYuR/librime 的 zhongzhou 定制源码 → SandyYuR/prebuilder → SandyYuR/prebuilt → 主仓库 gitlink**。
定制源码包含 tabs、音节和用户词典缓存等功能，不能用官方 prebuilt 直接覆盖。

```sh
git ls-tree HEAD native/prebuilt native/rime-prelude
git -C native/prebuilt rev-parse HEAD
```

以当前 gitlink 和预编译元数据为准，不照抄历史报告的 SHA。定制源码的固定来源、迁移与当时的验证记录见[源码维护迁移](/maintainer/history/DIRECT_RIME_SOURCE_MAINTENANCE_2026-10-21)和[引擎更新记录](/maintainer/history/DIRECT_RIME_ENGINE_UPDATE_2026-10-21)。

`librime.a` 静态链接进 `libdirect-rime.so`。共享 prebuilt 中其他引擎的文件不意味着主 APK 链接了它们。
LevelDB 的 `reuse_logs` 与恢复验证由 `tools/input-perf/test-leveldb-open.py` 等工具守护；调整依赖时同步核对实际导出和存储行为。

### 更新 librime

1. 从 `SandyYuR/librime` 的 `zhongzhou` 新开升级分支；核心补丁维护在源码提交中，不回到 prebuilder 重复打补丁。
2. 在明确的官方提交上更新，处理冲突、插件兼容性与测试，更新 `downstream/manifest.json` 的来源记录。
3. 在 prebuilder 中固定定制源码提交，运行来源检查；Lua、octagram、predict 等按固定版本准备。
4. 获得授权后运行构建与验证，产出各 ABI 静态库并核对源码元数据、接口和符号。
5. 在 prebuilt 仓库保存并验证产物；外部仓库 push 与 CI 调度须有明确授权。
6. 主应用更新到经过验证的 gitlink，出包并运行 APK、链接和设备门禁，同步文档。不能自行猜测或只按“最新”前移 pin。

## 语音宿主与原生打包

AIDL 外部提供方与 JS 插件共用会话、录音、静音判定和回填。JS 提供方使用 `js:` ID 前缀，QuickJS 依赖由版本目录固定。

当前 APK 原生白名单是 **`libdirect-rime.so`、`libc++_shared.so`、`libquickjs.so`**，权威为 `tools/verify-direct-rime-apk.py` 的 `allowed`。
任何新增 `.so` 都必须同时更新并审阅白名单，不能因为 Debug 能构建就认为发布门禁会通过。

语音宿主的密钥、逐插件主机授权、prelude/stub 双侧接口和收尾预算见[JS 宿主协议](/maintainer/implementation/js-voice-plugins)。
目标测试加载真实 bundled 脚本；JVM 使用 `quickjs-kt-jvm` 替换 Android native 依赖。

## 按改动选择验证门禁

| 改动 | 最低检查 |
| --- | --- |
| 文档和站点 | 人工核对、`git diff --check`、站点构建、链接与历史完整性检查；布局调整检查桌面及窄屏 |
| 纯 Kotlin | 相关 JVM 测试，必要时 Debug 出包 |
| UI、IME、协程和输入链路 | 目标测试 + Debug；行为改动补设备回归 |
| JS 语音 | `--tests 'org.fcitx.fcitx5.android.input.voice.*'` + Debug；API 改动补真实脚本契约测试 |
| JNI、native 或 librime | native 出包 + APK 白名单和链接审计 |
| 键盘形态、主题、工具栏尺寸 | 真机或模拟器截图与实际输入，不能仅凭 JVM 单测 |
| Release / 签名 | 本地签名脚本 + `apksigner verify --print-certs` |

```sh
./gradlew :app:testFxDebugUnitTest --tests '完整测试类名'
tools/test-direct-rime-frontend.sh
tools/test-material-settings.sh
```

默认分支当前的 PR CI 运行 ARM64 Debug 与 JVM，Commit CI 运行签名 ARM64 Release、ART profile 审计与 JVM；Direct Rime CI 覆盖原生诊断与完整前端回归，Material settings UI 覆盖 MD3 页面。
实际执行项以 workflow 内容为准；CI 不跑全量 lint。测试类“仍在仓库里”不等于仍被 CI 执行。

本机已知 Windows 失败涉及文件覆盖式 `renameTo` 与符号链接权限；只有再次核对原因一致，才能标成已有环境失败。
没有跑的检查明确写“未运行”。Gradle 构建成功只证明能出包，不证明功能已经验收。

## 资产、数据与配置维护

- 修改内置布局、主题时，在 `BundledPresets.assetSizes` 登记**上一版**字节数，保护用户已修改文件。
- 资产描述采用相对路径、POSIX 分隔符与流式摘要；新增、移动或同名文件必须触发正确增量更新。
- 完整备份先停止原生写入，再归档缓存；保存词典不等于停止引擎。外部目录并发修改须检测并报错。
- 密钥在备份中保持密文，Android Keystore 密钥不导出；跨设备后需重填语音密钥。
- 设置入口变化同步分组、承载页、搜索索引与现有语言资源，避免出现入口能点但搜不到的情况。

协议详见[同步接收](/maintainer/implementation/clipcascade-receive-limits)、[加密备份](/maintainer/implementation/protected-backup-format)、[整包恢复](/maintainer/implementation/user-data-restore)。

## 性能与历史证据

性能工具分别位于 `tools/input-perf/`、`tools/candidate-perf/` 和 `tools/screen-perf/`。复现前核对比较版本、方案、模型、ABI、构建类型、设备、输入速度和计时区间。
相同文件名的远端模型也可能换过内容，应按原报告哈希找匹配夹具，不能跳过检查。
手动触发性能 workflow、上传日志或设备数据前取得明确授权。

所有原始报告及 ZIP/JSON/截图见[验证证据目录](/maintainer/evidence)。历史 Actions artifact 有保留期限，不能当成永久下载或当前版本证明。
完整 XKB Compose、按应用行为等价和广泛真机回归仍有边界，不因旧后端被移除而自动视为完成。


输入、键盘、布局、主题和数据保护的详细约束与故障定位，见[正确性约束与代码定位](./implementation/correctness-contracts)。
