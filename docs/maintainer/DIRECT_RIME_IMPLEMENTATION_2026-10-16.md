# librime 直连实验：实现与验证

日期：2026-10-16。代码基于 `a714feff`，工作分支 `feat/direct-rime-prototype`。

本次交付是一个可以独立安装和实际输入的直连实验版本，以及当前链路的可选测量工具。
尚未完成原输入法全部 UI、配置、布局和编辑器行为的迁移；没有证据证明直连更快。

## 安装与构建

实验 APK 包名 **`io.github.sandyyur.rime.direct`**，名称 **Rime 直连实验**。
它使用自己的进程、应用私有目录和输入法服务，可以与原版及靓企鹅同时安装。

```bash
git submodule init
git config submodule.lib/fcitx5/src/main/cpp/prebuilt.url https://github.com/SandyYuR/prebuilt.git
git submodule update --depth 1 --jobs 4
./gradlew -PdirectRimePrototype=true -PbuildABI=arm64-v8a \
  :direct-rime:assembleDebug :direct-rime:testDebugUnitTest :direct-rime:lintDebug
```

产物：`direct-rime/build/outputs/apk/debug/direct-rime-debug.apk`。
默认构建不包含 `:direct-rime`，不会改变现有 APK 的包名或输入引擎。
GitHub Actions 的 **Direct Rime experiment** 提供 ARM64 Debug APK artifact。

安装后打开实验应用：

1. 点击“安装小型测试方案”，或导入自己的 Rime 用户目录 ZIP。
2. ZIP 根目录直接放 `default.yaml` 或 `default.custom.yaml`、schema、词典、Lua 等文件，不能再套一层用户目录。
3. 启用“Rime 直连实验”输入法，并通过系统输入法选择器切换。
4. 重新聚焦编辑框。测试方案输入 `ni` 选“你”，`hao` 选“好”，`xiao` 选“😀”。
5. 候选长按忘词；“方案”循环切换方案；音节 tabs 在候选上方；部署时停止接收按键。

原输入法和原用户目录不会被读取或修改。导入操作先在私有临时目录解压、检查路径和容量，
成功后替换实验用户目录，前一份实验数据保留于 `files/rime/previous`。
可在实验室点击“恢复上一份实验数据”切回前一份并重新部署。
发生错误也可通过系统选择器切回原输入法，或卸载实验 APK。

## 实现选择

```text
Android InputMethodService（主线程、InputConnection）
  → service 串行事务队列 + session generation
  → rime-main 专属单线程
  → 一次 JNI：process_key / action → Snapshot
  → patched librime C API
  → 主线程一次消费 commit，更新 composing span 与候选 UI
```

`direct-rime` 没有依赖 `:lib:fcitx5`、FcitxAPI、AndroidFrontend 或 fcitx5-rime。
CMake 只链接父仓库 gitlink 固定的 SandyYuR librime 和其静态依赖；不是另取官方 librime。
当前固定版本：prebuilt `1126616c3ec569617da772e30d7a97f6b2694172`。

选择独立 APK 的原因是 librime 有进程全局 runtime、模块和日志状态。
在原 APK 的 Fcitx addon 之外再初始化同一运行库，会把“路由差异”与“重复引擎”混在一起。
独立进程还能让 native 崩溃和实验部署与当前使用中的输入法隔离。

### 保留的输入边界

- native Engine 校验创建它的线程，禁止任意 coroutine 并发调用 librime。
- 每次开始输入、结束输入、解绑和部署均使 generation 过期；排队前、native 执行前、交付编辑器前均检查。
- 候选与 tabs 操作携带产生它们的 snapshot revision，避免旧候选按钮误选新候选。
- 提交文本包含在单次操作结果中，不使用 `DROP_OLDEST` 事件流，不在 UI 重建时重放。
- 部署、失败、无方案或无 session 时按下和抬起均被拦截；部署前已排队的旧任务也不能恢复执行。
- 密码框绕过 librime，不进入用户词典。所有输入文本均不写入诊断日志。
- 保留 Shift/Caps/Ctrl/Alt/Super 和 IBus release 掩码；硬件键盘使用 Android 键值映射。
- 读取 context、status、候选 code 和 input tabs；可选扩展通过 `RIME_API_AVAILABLE` 检查。
- tab 过滤空标签和非法 UTF-8；筛选后索引与原 span 绑定；JNI 使用 UTF-16，支持 supplementary-plane 字符。

### 当前边界

本 APK 的键盘和实验室 UI 是用于验证引擎的最小实现。原版的自定义布局、主题、剪贴板、
语音、RawConfig、alt-trigger、复杂 compose、编辑器光标同步、所有厂商 InputConnection
差异、完整按键映射和多编辑器压力场景尚未达到行为等价。
基本 Android dead key 组合已有处理，不能据此宣称替代了 Fcitx 的完整 XKB compose 行为。
当前采用单个聚焦编辑器 session，不缓存多个 UID 的历史 composition。
这些限制阻止直接替换原版，实验版本不会成为默认引擎。

## 原链路基线追踪

```bash
./prepare_personal_build.sh
./gradlew -PinputDiagnostics=true -PbuildABI=arm64-v8a :app:assembleFxDebug
tools/input-perf/capture.sh baseline.perfetto-trace 30000
python3 tools/input-perf/summarize.py baseline.perfetto-trace --trace-processor /path/to/trace_processor
```

同样捕获直连 APK。Perfetto 配置已包含两个 Debug 包名。默认配置关闭原链路诊断计数。

| 位置 | trace / counter |
| --- | --- |
| Kotlin/JNI 外层 | 已有 `sendKey JNI` |
| Fcitx 路由整体 | `rime.fcitx.route` |
| librime 查询 | `rime.fcitx.process_key` / `rime.direct.process_key` |
| 回调与消费 | 已有 `HandleFcitxEvent`、`rime.fcitx.consume` |
| 编辑器提交 | `rime.fcitx.commit` / `rime.direct.commit` |
| 候选更新 | 已有 `updateCandidates` / `rime.direct.render` |
| 直连状态打包、启动、部署 | `rime.direct.snapshot/startup/deploy` |
| 原事件流 | `rime.events.produced/received/sequence_gaps/pending/age_ns` |
| 进程资源 | Perfetto process stats、sched；另采 `dumpsys meminfo` / `dumpsys gfxinfo` |

`sequence_gaps` 表示一个持续收集的服务消费者看到的序号缺口，不能直接等同于丢字；
`pending` 是生产与消费的序号距离，不是 SharedFlow 内部队列长度。
首次订阅之前的事件不计入缺口。每 256 条事件仅记录数量与延迟，不记录文本。
trace 的 render/updateCandidates 是 UI 更新调用耗时，**不是屏幕显示完成时间**；
脚本输出的是各段耗时 p50/p95/p99，**不是端到端输入延迟**。
端到端延迟仍需在实际使用的输入源与目标编辑器上结合 input/frame 时间线测量。

原 addon 的 trace 以仓库内补丁应用到固定 gitlink，未更新引擎版本或改写按键逻辑。
重复运行准备脚本会先去掉自己应用的 trace 补丁；发现其他源码修改仍拒绝覆盖。

## 可重复验证

原生安全契约测试无需 Android 设备：

```bash
c++ -std=c++17 -Wall -Wextra -Werror -pthread -fsanitize=address,undefined -g \
  -I lib/fcitx5/src/main/cpp/prebuilt/librime/arm64-v8a/include \
  direct-rime/src/main/cpp/engine.cpp direct-rime/src/main/cpp/engine_test.cpp -o /tmp/engine-test
/tmp/engine-test
```

测试使用 fake RimeApi 校验维护门控、失效 session、提交仅消费一次、release/Super 掩码、
线程归属、旧 generation/revision、候选全局编码索引与页内选字索引、tab 的 UTF-8/索引映射、
扩展 ABI 缺失、部署失败后恢复、setup/finalize 生命周期。
宿主环境处于 ptrace 下时 LeakSanitizer 无法运行；本次使用 `ASAN_OPTIONS=detect_leaks=0`
运行 AddressSanitizer/UBSan，不能据此宣称通过内存泄漏检查。

JVM 测试覆盖 ZIP 越界、绝对路径、容量限制、旧实验数据保留与完整替换。

Android instrumentation 使用独立 APK 的小型测试方案，不读取原版数据：

```bash
./gradlew -PdirectRimePrototype=true -PbuildABI=x86_64 \
  :direct-rime:assembleDebug :direct-rime:assembleDebugAndroidTest
adb install -r direct-rime/build/outputs/apk/debug/direct-rime-debug.apk
adb install -r direct-rime/build/outputs/apk/androidTest/debug/direct-rime-debug-androidTest.apk
adb shell am instrument -w io.github.sandyyur.rime.direct.test/androidx.test.runner.AndroidJUnitRunner
```

## 迁移判断

只有在同一真机、相同方案与词典副本、相同用户词频初态、相同输入序列和渲染条件下，
才能比较延迟和资源。分别记录冷启动、预热、部署、长候选、焦点切换和重启。
不要将这个最小 UI 的刷新时间与原版完整 UI 的时间直接解释为 Fcitx 路由收益。
模拟器仅验证功能和 ABI；不能作为低端真机性能结论。

接下来的替换条件是：补齐原版所需功能、通过编辑器行为矩阵，再用基线证据选择优化热点。
当前没有删除原版 Fcitx5，也没有改动原版用户数据格式。

## 本次验证结果

| 检查 | 本地结果 |
| --- | --- |
| 独立 APK ARM64 / x86_64 | Debug 构建通过 |
| 独立 APK Lint | 0 errors；仍有 SDK 更新、硬编码实验 UI 字符串等 warning |
| C++ 安全契约 | AddressSanitizer（不含 leak 检查）+ UBSan 通过 |
| ZIP 导入、恢复 JVM 测试 | 2 项通过 |
| Android 15 x86_64 模拟器 | 3 项 instrumentation 通过 |
| 真实 InputConnection | `ni` → `你`、切换编辑框后 `hao` → `好`，第一框内容保持不变 |
| 原版 + 诊断开关 | ARM64 Debug 构建通过，468 项 JVM 测试通过 |
| 重复执行源码准备脚本 | 通过，保持固定 gitlink 和已知补丁 |

这些检查没有覆盖低端真机性能、完整输入法功能等价、所有编辑器和所有 compose 序列。
`InputConnectionSmokeTest` 也接受 `-e imeComponent package/Service`，可在已准备相同测试方案的
基线输入法上复用相同编辑器和按键序列。该参数不安装或修改基线方案；必须使用一次性测试设备。
