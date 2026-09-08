# Agent 项目操作指南

本文用于帮助后续 Agent 快速、安全地接手本仓库。它是操作规范，不是历史快照；凡分支、提交、依赖版本、CI 状态或外部仓库状态可能变化的内容，都必须在执行任务时重新核对。

## 1. 项目定位

- 项目：靓企鹅·中州韵（Rime-only），非 Fcitx5 或 Rime 官方发行版。
- 默认开发分支：`fx-rime-only`。
- 当前仅保留 `fx` flavor；不要使用已经删除的 `mainline` flavor 或 `assembleMainline` 任务。
- 应用 ID：`org.fcitx.fcitx5.android.fx.rime`。
- 产品目标：Rime 是唯一输入引擎；主题、布局/宏、候选栏、语音、剪贴板与远端同步、更新等应用层能力仍保留。
- 技术栈：Android/Kotlin、`InputMethodService`、Fcitx5、Rime/librime、JNI/C++、Gradle、CMake、NDK、Room、协程。

## 2. 信息源优先级

发生冲突时按以下顺序判断，不要照抄旧文档：

1. 当前 Git 状态、源码、Gradle/CMake 配置、脚本和 `.github/workflows/ci.yml`。
2. `README.md` 与 `docs/RIME_ONLY_USER_GUIDE_zh-CN.md`。
3. `docs/CODE_REVIEW_REPORT_2026-09-07.md` 与 `docs/PERFORMANCE_REVIEW_REPORT_2026-09-07.md` 中的风险和验证建议；报告中的提交号、行号与“尚未修复”状态必须重新核对。
4. `docs/HANDOVER-rime-only.md` 的引擎更新 runbook 和故障经验。
5. `docs/rime-integration-plan.md`、`docs/rime-only-feasibility.md` 仅作为历史设计依据。

`HANDOVER-rime-only.md` 中的旧分支名、旧工作区状态、提交数量、固定 SHA 和外部仓库快照都不是当前事实。当前分支、HEAD、上游、子模块和外部 fork 状态一律用 Git 重新获取。

## 3. 每项任务开始前

先进入真正的仓库根目录，不要把父目录当作仓库。至少执行：

~~~bash
git rev-parse --show-toplevel
git status --short --branch
git branch --show-current
git rev-parse HEAD
git rev-parse --abbrev-ref --symbolic-full-name '@{upstream}'
git remote -v
git submodule status --recursive
~~~

然后遵守以下规则：

1. 明确用户要求、允许修改的范围和验收条件；不把“审阅”自动扩大为重构，不把“修改文件”自动扩大为提交或发布。
2. 读取所有准备修改的文件及其直接调用方、测试和配置。不要仅凭文件名或历史文档推断现状。
3. 识别工作树中已有修改、未跟踪文件和子模块修改。默认它们属于用户；不得覆盖、回退、格式化或夹带提交。
4. 原生构建前单独检查关键 gitlink/子模块：
   - `lib/fcitx5/src/main/cpp/fcitx5`
   - `lib/fcitx5/src/main/cpp/prebuilt`
   - `plugin/rime/src/main/cpp/fcitx5-rime`
5. 用当前代码确认相关行为是否已经修复，避免重复实施审阅报告或历史方案中的旧建议。
6. 中大型任务先拆分为可验证步骤；每一步保持改动集中、可解释、可回退。

## 4. 仓库地图

- `app/`：主 APK、IME、UI、Room、网络同步、主题/布局/宏、语音及 JNI 入口。
  - `app/src/main/java/`：Kotlin/Java 主体。
  - `app/src/main/cpp/`：Android frontend、JNI、native addon 与 CMake 集成。
  - `app/src/main/res/`、`app/src/fx/res/`：通用和 fx 品牌资源。
  - `app/src/test/`、`app/src/androidTest/`：JVM 与设备测试。
  - `app/schemas/`：Room schema 历史；数据库变更时必须同步并验证迁移。
  - `app/licenses/libraries/`：内置 native/Rime 依赖版本和许可证元数据。
- `lib/common/`：公共 Android/Kotlin 能力。
- `lib/fcitx5/`：Fcitx5 native 构建、预编译资产和 JNI 支撑。
- `plugin/rime/`：内置的 fcitx5-rime 适配层来源。
- `codegen/`：代码生成模块。
- `build-logic/`：Gradle convention plugins、SDK/NDK/CMake/ABI 与数据生成逻辑。
- `prepare_personal_build.sh`：实际 CI 原生来源准备和 Fcitx core 补丁脚本。
- `.gitmodules`：gitlink 元数据；不等同于准备脚本最终采用的构建来源。
- `.github/workflows/ci.yml`：当前 CI/Nightly 行为的事实源。
- `docs/`：用户指南、审阅报告、历史方案和维护 runbook。

## 5. 构建准备

基础要求以当前 `build-logic` 和 CI 为准。当前工程要求 Java 17，并使用 Android SDK、NDK 和 CMake；开始前检查 `build-logic/convention/src/main/kotlin/Versions.kt`，不要长期依赖本文中的版本快照。

首次原生构建通常需要：

~~~bash
git submodule update --init --recursive
./prepare_personal_build.sh
./gradlew :app:assembleFxDebug
~~~

Windows 可使用 Git Bash/WSL 运行 shell 脚本，并用 `.\gradlew.bat` 执行 Gradle。先确认 `JAVA_HOME`、Android SDK/NDK 和 CMake 可用。

### prepare 脚本的特殊注意事项

`prepare_personal_build.sh` 会联网并修改子模块工作区：

- 把 fcitx5-rime checkout 到 `SandyYuR/fcitx5-rime` 的远端分支；
- 在 Fcitx5 core 内先执行 `git checkout -- .`，再应用适配补丁；
- 把 prebuilt checkout 到 `SandyYuR/prebuilt` 的远端分支。

因此：

- 文档或纯 Kotlin 小改动不需要无条件运行此脚本。
- 运行前必须确认上述子模块没有需要保留的本地修改。
- 脚本当前使用浮动远端来源，且补丁失败可能只输出提示；必须检查输出、实际 checkout SHA 和补丁结果，不能只看脚本退出码。
- 对供应链或发布可复现性进行修改时，应优先固定完整 SHA、让补丁失败立即终止，并记录最终来源 SHA。

## 6. 常用验证命令

Linux/macOS/Git Bash 示例：

~~~bash
# 指定测试类时优先运行目标测试
./gradlew :app:testFxDebugUnitTest --tests '完整测试类名'

# 本地最低通用门禁
./gradlew :app:testFxDebugUnitTest
./gradlew :app:lintFxDebug
./gradlew :app:assembleFxDebug

# 发布构建；只有发布相关任务才是默认必需
./gradlew :app:assembleFxRelease
~~~

Windows PowerShell 将 `./gradlew` 替换为 `.\gradlew.bat`。APK 输出目录：

- Debug：`app/build/outputs/apk/fx/debug/`
- Release：`app/build/outputs/apk/fx/release/`

不要把“Gradle 通过”描述为“功能已验证”。按改动类型选择门禁：

| 改动类型 | 最低验证 | 追加验证 |
| --- | --- | --- |
| 文档 | 链接、路径、术语、命令人工核对；`git diff --check` | 与当前 README、脚本、CI 交叉检查 |
| 纯 Kotlin/工具函数 | 目标 JVM 测试 | 全量 unit test、lint、Debug build |
| UI、IME 生命周期、协程 | 目标测试、lint、Debug build | 真机切换输入框/应用/输入法与进程恢复 |
| JNI、C++、Fcitx/Rime | 初始化来源、检查补丁、native APK build | Release 真机输入回归；涉及发布声明时验证目标 ABI |
| Room、备份、ZIP、文件迁移 | 目标测试、schema/迁移检查 | 旧版本升级、失败回滚、恶意/超限输入测试 |
| 网络、Binder、权限、剪贴板 | 目标测试、lint、manifest/config 审阅 | 鉴权、TLS、跨应用、断网重连与隐私测试 |
| Release/签名/ABI | CI 等价 Release build | `apksigner verify`、证书指纹、安装和关键路径冒烟 |

当前 CI 主要在 Ubuntu 22.04 上为 `arm64-v8a` 执行 `:app:assembleFxRelease`，不等于四 ABI 已被 CI 验证，也不默认运行 JVM test、lint 或 instrumentation。`fx-rime-only` 成功后会创建时间戳 Nightly prerelease；不要声称 Nightly 已删除。文档-only 路径当前通常不会触发 Commit CI。

涉及真实输入行为时，至少回归：

1. 首次启用后只加载 Rime，键盘和候选正常出现。
2. 连续打字、候选刷新、选词、提交、删除和按键顺序无丢失、重复或乱序。
3. 快速切换输入框、应用、输入法和屏幕状态后，旧会话事件不会写入新编辑器。
4. “打字 → 点击音节 tab → 选词”正常。
5. 语言键短按产生一次 Shift 切换，长按打开系统输入法选择器。
6. 受影响的部署/同步、布局/宏、主题、语音、剪贴板和备份路径正常。

若环境、凭据、子模块、设备或时间限制阻止验证，明确写出未运行项、原因和剩余风险；禁止伪造成功、耗时或性能数字。

## 7. Rime/Fcitx 引擎更新

必须区分两条链路。

### 7.1 fcitx5-rime 适配层与 Fcitx core 补丁

- 适配层来自 `SandyYuR/fcitx5-rime`。
- 主仓库 CI 通过 `prepare_personal_build.sh` 动态 checkout，并将适配仓库中的 `fcitx5-alt-trigger-v4point1.patch` 应用到 Fcitx5 core。
- 主仓库的 fcitx5-rime gitlink 可能只是准备前占位；实际来源以脚本执行结果为准。

更新时：先 fetch 并记录实际 SHA，审阅上游差异和定制差异，执行 `git apply --check`，确认补丁真正应用，再构建并做 tabs、schema、Shift/alt-trigger 真机回归。

### 7.2 librime 静态归档

- `SandyYuR/prebuilder` 固定官方 librime 提交，并按配方顺序应用定制补丁。
- prebuilder CI 构建多 ABI 静态归档并推送至 `SandyYuR/prebuilt`。
- 主仓库准备脚本再取得 prebuilt 产物并链接进 APK。

更新时使用 `docs/HANDOVER-rime-only.md` 的 runbook，但先重新核对仓库、workflow、补丁列表、分支和 SHA。核心要求：

1. 对每个补丁先做可应用性检查；冲突要逐项合并，不能忽略 reject。
2. 严格保持 prebuilder 配方中的补丁顺序。
3. 重新生成补丁后做“从干净上游依序重放”的回环验证，结果必须零差异。
4. 触发外部 CI、推送 prebuilder/prebuilt 或使用 token 前必须获得用户明确授权；不得输出或提交 secrets。
5. 产物完成后核对所有预期 ABI、归档和定制 C API 符号，例如 tabs/select-tab API；不能仅凭文件存在判断成功。
6. 同步 `app/licenses/libraries/librime.json` 等版本/许可证元数据。
7. 回到主仓库记录实际来源 SHA，完成 APK 构建和上述真机回归。

不要直接用官方 prebuilt 覆盖 SandyYuR 产物，否则可能静默丢失 tabs、音节缓存、用户词典缓存等定制。不要把历史 runbook 中的 SHA 当作当前 pin。

## 8. 正确性、安全和隐私边界

代码审阅报告中的高风险面在当前代码确认修复前一律视为待验证，尤其包括：

- LAN Web 编辑桥的绑定地址、随机凭据、Origin、端口、超时和写/删确认；
- 导出 Binder/AIDL 服务的 signature 权限、调用者身份和资源限额；
- 主题/备份 ZIP、SAF、文件迁移的路径穿越、symlink、压缩炸弹、事务与回滚；
- 剪贴板/截图/语音/备份中的敏感数据、明文 HTTP、日志与 Android 云备份；
- Fcitx 启停状态机、dispatcher teardown、native handle 和 InputContext 生命周期；
- 输入 session/generation 身份、异步回调过期以及跨编辑器污染；
- SharedFlow/Channel 容量、背压和事件丢失。

不可破坏的输入约束：

1. commit、delete、key down/up 等有副作用事件必须无损、严格有序；不能用 `DROP_OLDEST`、忽略 `tryEmit` 失败或丢键来换取性能。
2. 所有延迟/异步输入结果必须绑定 InputContext 或单调 session/generation，并在消费前再次校验。
3. 发送 DOWN 后必须保证对应 UP；取消、异常、`finishInput` 和销毁路径都要兜底释放 modifier。
4. 生命周期修复必须覆盖 STARTING/READY/STOPPING/STOPPED、异常启动、零客户端和切换竞态。
5. 不在 release 日志中记录完整剪贴板、语音文本、输入内容、凭据、token 或私有路径。

外部网络上传代码/日志/用户数据、权限或安全边界弱化、依赖新增/升级、签名与发布、生产或外部仓库变更都属于高影响操作：先说明范围、风险和回退方式，并取得明确授权。新增依赖必须核对来源并固定明确版本。

## 9. 性能修改原则

先读 `docs/PERFORMANCE_REVIEW_REPORT_2026-09-07.md`，区分“当前热点”“条件性热点”“仅测量后实施”和“历史已修复”，再用当前源码复核。

- 正确性优先于吞吐量和平均延迟；不得把事件丢失包装成优化。
- 在 Release 真机上建立改前/改后基线，关注 input-to-candidate 的 P50/P95/P99、丢键/乱序、主线程帧、队列深度、内存、网络重连和后台功耗。
- 优先消除重复连接、轮询、全量刷新、主线程 I/O、无界队列和 release 热路径无效日志。
- JNI batching、输入/UI 架构重写、fire-on-DOWN、禁用触感、候选缓存策略变化均需 profile 证据与正确性回归。
- 不重复立项已经修复的旧热点；先用 Git 和代码证明问题仍存在。

性能结论必须附测试设备、构建类型、场景、样本量和指标。没有运行测量时只能称为静态推断。

## 10. 文档与兼容性

行为、配置、命令或用户数据路径变化时同步检查：

- `README.md`：产品定位、构建、CI、引擎来源。
- `docs/RIME_ONLY_USER_GUIDE_zh-CN.md`：用户操作、迁移、安全提示和 FAQ。
- 审阅报告：仅在复核证据后更新风险状态。
- `docs/HANDOVER-rime-only.md`：适合补充可复用 runbook/故障经验，不要继续堆叠易过期的“当前工作树快照”。

不要恢复已删除的插件、其他输入引擎、`mainline` flavor 或旧包名描述。涉及配置/备份/Room/Rime 数据时明确旧版本兼容、失败回滚和数据不丢失策略。

## 11. Git 与发布纪律

- 未经明确要求，不创建 commit、不 push、不创建 PR/Release、不触发外部 workflow。
- 提交时只暂存本任务文件，优先 `git add <明确路径>`，避免 `git add .` 或 `git add -A` 夹带用户修改。
- 使用非交互命令，保留 hooks。未获明确许可不得 amend、force-push、rebase 用户工作、`reset --hard`、`clean -f` 或删除分支。
- 不直接 push 到 main/master；对 `fx-rime-only` 的 push 也必须以本次用户明确要求为准，不能沿用历史授权。
- 发布前核对分支、版本、签名 secret、产物、证书、ABI、release notes 和 Nightly 条件。
- 任何命令失败都先调查原因；非零退出、被终止、沙箱拒绝和测试失败不能被忽略。

## 12. 完成与交接清单

任务结束前执行适用项：

~~~bash
git diff --check
git diff --stat
git status --short --branch
~~~

并完成：

1. 复读 diff，确认只有预期文件，无 secrets、构建产物、临时日志、绝对本机路径或无关格式化。
2. 运行最相关的目标测试，再运行可承受的 unit/lint/build；修复由本次改动引入的问题。
3. 若修改 native、输入时序或生命周期，记录真机验证场景和结果。
4. 若没有运行某项验证，说明具体原因和下一步，不笼统写“未测试”。
5. 最终报告列出主要修改文件、行为变化、验证命令/结果、已知限制；不要宣称未验证的 ABI、安全性、性能或真机行为。
6. 若要交给下一位 Agent，留下：目标与范围、当前 branch/HEAD、工作树状态、已改文件、已运行验证、失败输出摘要、剩余风险和下一条可执行命令。动态信息放在交接消息中，不写死进本指南。
