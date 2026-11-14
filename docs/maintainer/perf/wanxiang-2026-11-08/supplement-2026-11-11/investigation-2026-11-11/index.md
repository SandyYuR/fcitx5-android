# 首键与候选点选延迟定向调查（2026-11-11 起）

这是对[三版复测](../index.md)中异常数据的后续调查。**首键的较长耗时确实复现，但不能全部算作引擎计算：首次 Rime 调用约 53–59ms，实际线程 CPU 约 18–20ms，另外包含显著的线程等待。首次候选布局也能阻塞后续输入分发。** 原先一次 56.75ms 的点击分发等待没有相应 trace，尚不能给出它的确定根因。

本次不修改应用、SDK 或引擎，不重跑旧 Fcitx 和上次直连。只使用原测量已经核验的当前 APK、方案与模型，新增定向追踪及独立测试宿主；**有 trace 的数值只用于归因，不追加到之前无 trace 的性能对比表中。** 目录日期表示任务启动与被测源码冻结日；后续任务完成于北京时间 11 月 12 日，原始记录统一保留 UTC。

## 对象、方法和计时边界

| 项目 | 本次固定对象 |
| --- | --- |
| 当前默认分支源码 | `0380d70f16efb6a9946a164821d5cad7118ce4ff` |
| Debug | 原构建 `43015193478` 的 `current.apk` / `current-tests.apk`，未重新编译；应用 SHA256 `ac2362b4d12f952187cab7d81a6a54476f0d7061d58867053030952ee39e7f37` |
| 原签名 Release | `090a43e1485b9a6d76a28a31a6503c41e3e3db29`；SHA256 `0a96e27c8ee355e862bcf391804b55d22546062ebc9625ec91a397c8c22dca67` |
| 两份应用代码关系 | `090a43e1 → 0380d70f` 只改三份根说明；与上次直连 `2e48466e` 间的 22 个提交、162 个文件变化仍按原报告保留 |
| 引擎 | SDK `android-sdk-20261110-1`，librime `b5561910d9eba97a273e09e7996535679b730032`，未换依赖 |
| 万象与配置 | v18.1.10 base、2026-11-07 LTS、原两份 YAML；全拼、上下文调频、简码、小字集；逐次校验原夹具摘要，不取浮动最新版 |
| Debug 环境 | API35 AOSP ATD、x86_64、KVM、软件图形；独立 Actions VM |
| Release 环境 | API30 Google x86_64、原签名 ARM64 包、native bridge；与 Debug 的架构和系统不同 |
| trace 分段 | APK 中已有的 `rime.direct.process_key`、`action_core`、`select_candidate`、`snapshot`，以及系统调度、主线程布局等 |
| 分析器 | 官方 Perfetto v59.1，构建 `1e9c61e9844affd7dccd3d42d9ea9f3a3d10cf6d`；二进制 SHA256 `2434129a3af4dc9e2034a476f7b76447b490975213e301769310d3bd0d5d6458` |

候选内部探针沿用原 AndroidTest 二进制：3 个新进程，每进程 6 次真实选词，共 18 次。首个 `n` 的起点是 **IME Window.Callback 的 dispatch UP**，终点是编码事件收集；它不是外部触摸到画面发光。CPU 时间取该 native span 对应线程与系统 `sched` 的实际交集，不用 wall time 冒充 CPU。

连续选词通过独立编辑器真实点选：`ni → 你` 与 `zaoshanghao → 早上好` 各 16 次，离开编辑器后返回再各 16 次，共 64 次；末键后等待至少 1000ms 并观察稳定候选。这里的 `fresh/rebind` 指编辑器阶段，**Debug 已为 Android15 绑定而预先启动应用，不是冷进程**。IC 时间在宿主 `commitText` 入口记录，早于 `super.commitText`，不等同于 TextWatcher 或可见绘制。

部署和坐标校准不计时，使用独立可丢弃模拟器中的工作库。校准只学习「你」「早上好」，没有导入个人历史词库；连续选词保留本轮学习轨迹，不能代表长期大词库。未删除或解锁任何真实用户数据库。

## 首个 n：首次计算与线程等待同时存在

三次新进程的逐事件分段如下，单位 ms。表中各值来自同一个事件的时间窗，不是把独立分位数相减或相加。

| 新进程 | UP → 编码事件收集 | Rime `process_key` wall | 该 span 的线程 CPU | runnable 等待 R/R+ | D 状态 | 后续 snapshot |
| --- | --- | --- | --- | --- | --- | --- |
| 1 | 71.592 | 58.030 | 19.005 | 3.165 | 35.861 | 0.624 |
| 2 | 77.106 | 58.886 | 20.100 | 38.786 | 0 | 0.699 |
| 3 | 60.195 | 52.796 | 17.538 | 35.258 | 0 | 0.801 |

R/R+ 表示线程已可运行但未获 CPU。进程 2、3 中，在这段等待期间，模拟器两颗虚拟 CPU 同时运行了大量 `RenderThread` 与 ranchu 图形合成服务工作；这两个线程在对应窗口分别消耗约 **45.39/38.45ms** 和 **41.25/31.02ms**。这支持软件图形与输入争用 CPU 的解释，不能把 53–59ms 全算成万象/Rime 的运算，也不能直接套用为手机的引擎耗时。

进程 1 有 **35.861ms D 状态**，但 trace 中 `io_wait`、`blocked_function` 没有有效归因数据。**D 状态不能单凭名称断定是词库读盘、缺页或 fsync。** 本 APK 没有开启逐键 I/O/fault 诊断，本轮也没有为取得它而重新编译应用。

首次真正 CPU 仍约 18–20ms，热态 `n` 的 CPU 约数 ms，因此首次使用确有额外计算成本。现有 native span 覆盖整个 `RimeProcessKey`，还不能进一步拆成 Lua、词典、模型或某个插件各占多少。不能据此断言「模型导致」「Lua 导致」或确定是近期某个提交引入。

这三次的 snapshot 均低于 1ms，当前证据不支持优先围绕 JNI 快照复制做大改。snapshot 不包含 Android 界面后续布局，后者仍可能较慢。

## 输入分发前的等待：首次候选布局是一个已证实来源

原无 trace 批次 `current-2` 首次选词：外部注入 UP 到 IME dispatch UP 为 **56.751866ms**，随后到 TextWatcher 为 **9.467167ms**，合计约 **66.219ms**。只看后一个内部指标会漏掉前面的等待。原记录没有 trace，因此不能把此次 56.75ms 确定归到 UI 或 Rime。

新增的三个候选 trace 没有复现同样的 56ms 选词等待，但进程 1 的第一个 `i` 出现了可直接对齐的 **18.167266ms** 分发等待：

| 同一事件 | BOOTTIME 时间戳（ns）或时长 |
| --- | --- |
| 注入 UP | `65474956452` |
| IME dispatch UP | `65493123718` |
| 正在占用 IME 主线程的 `Choreographer#doFrame 953` | 开始 `65463654469`，耗时 **29.349005ms** |
| 帧内 `layout` | **23.148268ms** |
| 帧内 `RV OnLayout` | **22.932136ms** |

该帧在注入 UP 后约 18.047ms 才结束，之后约 0.12ms 输入才分发。因而在这个样本里，**首次候选 RecyclerView 布局确实挡住了下一输入事件**。整帧、layout、RV OnLayout 内主线程实际 CPU 分别为 **16.639、12.160、11.944ms**，其余也含等待，不能把 22.93ms 全当作布局计算。span 之间存在包含关系，29.35、23.15 和 22.93ms 不能相加。

当前候选项的 `CandidateItemUi` 使用 `AutoScaleTextView`、候选/注释 span 与自定义测量；代码路径与布局 trace 一致，但现有数据还不足以指出其中哪一段应替换。JIT 与文本相关首次工作也有重叠，未取得方法级 CPU 占比，不能把整个布局时间都算给 JIT。生产候选绑定的代次、位置与触摸目标校验仍需保持。

## 连续选词：检查第 8 次与第 16 次

原无 trace 正式包中单字第 8 次存在较高值：当前版首编辑器六进程的第 8 次 UP → TextWatcher 为 **15.381–24.728ms**，返回编辑器后为 **13.481–26.085ms**。这一位置恰好又是旧探针每阶段的末次，因此新增探针把阶段延长到 16 次，并在同一进程离开编辑器后返回，区分次数与末次边界。原始异常的独立复算见 [original-anomalies.json](./original-anomalies.json)。

复算同一批旧数据，上次直连第 8 次的两个阶段中位数为 **19.42/16.75ms**，当前为 **18.87/16.50ms**，旧 Fcitx 为 **14.52/13.55ms**。因此这一形态在上次直连也已存在，不能当成本次默认分支最近改动才引入的证据；仍不能据此确认是哪一个组件造成。

Debug 的 64 次提交全部正确，以下为原生 `action_core` wall（ms）；每格是各自一次实际事件，不能作为跨版本性能提升表。

| 词例 / 编辑器 | 第 1 次 | 第 4 次 | 第 8 次 | 第 16 次 |
| --- | --- | --- | --- | --- |
| 你 / 首编辑器 | 1.145 | 0.300 | 3.575 | 1.008 |
| 你 / 返回后 | 0.273 | 0.306 | 0.326 | 0.459 |
| 早上好 / 首编辑器 | 0.852 | 2.357 | 0.327 | 0.439 |
| 早上好 / 返回后 | 0.344 | 0.317 | 0.311 | 0.404 |

单字首编辑器的第 8 次仍有约 **3.575ms wall / 3.455ms CPU** 的小峰，外部 UP → IC 为 **7.035ms**；主要是真正的 native CPU 工作。短语的峰移到第 4 次，约 **2.357ms wall / 2.328ms CPU**。返回后的第 8/16 次没有相同峰值，**不支持“每八次必有一次大停顿”**。但此 Debug/x86 结果不能直接解释原 Release/ARM64 native bridge 的更高峰值。

本次 Debug 单字两个编辑器 UP → IC 的范围分别为 **2.947–8.724ms**、**2.547–3.736ms**；短语为 **2.634–6.490ms**、**2.576–3.788ms**。每组只有一个进程内的 16 次事件，候选已稳定后才点选，不是输入首键或可信的日常 P95。

### 原签名 ARM64 Release 的单项 trace 对照

最后在 [43105223152](https://github.com/SandyYuR/fcitx5-android/actions/runs/43105223152/job/130806710918) 成功执行原签名当前正式包：单字 1 个新进程、首编辑器 16 次、往返后再 16 次，**32 次提交全部正确**。使用原外部宿主，SHA256 `aa609882c23099fa9ff8a29ae94da9baaa4af90a6153e81d56b554c70e723b09`；模型/Core、恢复的工作库文件纳秒时间及固定 APK 摘要继续核验。API30 采用可工作的 `--background`，先完成短 trace 预检，再部署及运行计时序列。

下表单位 ms，CPU 是选词线程在 core span 中实际运行的时间；总延迟端点是 **IC 入口**。

| 编辑器 / 次数 | 外部 UP → IC | `action_core` wall | core CPU | snapshot |
| --- | --- | --- | --- | --- |
| 首编辑器 / 1 | 12.218 | 10.163 | 9.531 | 0.125 |
| 首编辑器 / 8 | 5.695 | 2.193 | 1.699 | 0.042 |
| 首编辑器 / 16 | 2.872 | 1.509 | 1.509 | 0.042 |
| 返回后 / 1 | 2.992 | 1.574 | 1.525 | 0.039 |
| 返回后 / 8 | 3.319 | 1.451 | 1.437 | 0.038 |
| 返回后 / 16 | 3.434 | 1.749 | 1.749 | 0.042 |

本轮首编辑器总延迟范围 **2.727–12.218ms**，返回后 **2.520–4.103ms**，没有重现原无 trace 批次的第 8 次大峰。第 16 次也没有相同峰值。因此目前只能确认它**不是在所有这些条件下固定每八次发生**；这是单进程诊断、另一次 VM 和带 trace 的样本，不能证明旧峰值不存在，也不能据此估算日常发生率。

首次点选约 **10.163ms core wall / 9.531ms CPU**，支持首次点选也有真实的原生线程计算成本；此处 CPU 同时可能包含 ARM64 native bridge 的翻译执行开销，尚不能全归到某个 Rime 算法。后续 core 多在约 1.4–2.2ms，未见对应的大段 D 等待。首次较慢不只是前端分发的现象，后续优化需要兼顾 native 首次工作与候选布局。

**本轮 APK 没有改动，所以这些较短数值不是修复收益。** 也不拿它替换原正式批次的 6 进程数据、与 Debug 数值横比或宣称问题已经消失。原记录、这次完整 32 次记录、trace 与失败启动批次均独立保留。

## 源码核查及下一步优先级

核查使用与 SDK 锁一致的 librime `b5561910`，不是当前浮动上游；[源码摘录与字节摘要](./source-audit.json)保留对应的固定提交、路径、行号与万象 base ZIP 摘要：

- `UserDictionary::NewTransactionForCommit` 按同一 context/commit 共享学习事务；`CommitPendingTransaction` 不存在按第八次选择计数的分支。`LevelDb::CommitBatch` 使用默认 `leveldb::WriteOptions()`，未找到“每八次显式同步刷盘”的依据。这不表示数据库路径绝无成本。
- 原生包和固定插件来源对应 Lua **5.4.9**，不是 LuaJIT；不能用 LuaJIT 热循环阈值解释第八次。
- 万象 `super_processor` 的 `max_repeat=8` 约束重复输入字符，不能当作每八次候选提交的计数器。上下文 Lua 的学习和模型路径尚无函数级分段证据。

| 优先级 | 建议 | 本次依据与验收要求 |
| --- | --- | --- |
| 1 | 先减少首次候选主线程布局、重复测量/绑定 | 已观察到 RecyclerView 布局阻塞下一键。先补主线程方法级计时，再在保持主题、注释、字体与触摸目标正确的前提下改动；同时测首候选和下一键，不能只看 native 时间 |
| 2 | 继续拆解首次按键与选词里的真实 CPU 与等待 | Debug 首键 18–20ms CPU、Release 首选约 9.53ms CPU 是本批实际成本，首键另外存在明显调度等待。需方法/插件级证据后再决定缓存或按需准备；不能用虚拟输入提前学习、降低方案功能或随意提高线程优先级代替归因 |
| 3 | 正式包连续选词峰值保留独立跟踪 | 同包同架构 trace 能定位耗时属于排队、native CPU、等待还是编辑器；仅一条 16 次序列不足以证明不存在尾延迟，不能据“第八次”猜测固定刷盘或关闭用户学习 |
| 较后 | JNI snapshot 的进一步压缩 | 当前首键 snapshot 0.624–0.801ms，不是本批几十 ms 停顿的主要部分；先处理已证实的大块 |

以上是按证据排序的后续方向。**本次只调查和归档，没有实施或宣称已验证性能修复。** 真机是否存在相同调度/布局比例、首次 Rime CPU 的细分、原 56.75ms 点击等待的确定原因，仍未验证。

## 实验完整性与失败记录

候选三份 trace 的 36 个 `n/select`、Debug 连续选词的 64 个、Release 的 32 个事件锚点，合计 **132 个锚点**均找到唯一对应的 native core；六份有效诊断 trace 检查 Perfetto `stats` 的 error/data_loss/lost/drop，无正值。三类实验共 **114 次选词全部正确**；不是 114 个独立冷进程。时钟使用 Android `elapsedRealtimeNanos` 与 Perfetto BOOTTIME 对齐，未把 uptime 或墙钟混入。

分析器曾只按进程名字筛选调度，第三份 trace 的元数据在部分时点名为 `zygote64`，会漏掉 CPU。已改为从 native slice 的线程/UPID 确认归属，并重算全部结果；本页没有使用该次错误的零 CPU 值。SQL 与派生结果一同保留，便于独立复算。

Debug 连续探针有三次宿主/环境失败，另有两次 Release 采集启动失败，全部保留原 artifact，没有记作应用崩溃，也没有混进成功样本：

| 批次 | 失败位置 | 处理与数据接纳 |
| --- | --- | --- |
| `43035871103` repeat | `run-as` 无权读取外部工作库 `user.yaml` | 改为模拟器 root 读同一文件；校准完成，无正式样本 |
| `43037095724` | Android15 宿主焦点/IME 绑定未建立 | 在宿主获焦后重新选择 IME，并先启动应用解除 stopped 状态；无正式样本 |
| `43100344896` | 16 次之后调用 ATD 不包含的系统 Settings Activity，独立宿主异常 | 改为宿主自己的无输入框页面；缺少完整阶段 JSON，整次不纳入；增加分阶段保存与失败时拉回 trace |
| `43103250948` Release | 包装器调用旧 `adb()` 辅助函数时传入不支持的 `input` 参数 | 改为 `subprocess.run` 输入配置；采集启动前失败，没有计时样本 |
| `43104334216` Release | API30 的 Perfetto 启动返回 1，旧包装器未保存 stderr | 保存 stdout/stderr，按帮助中的能力选择启动参数，并在部署前做短 trace 预检；不凭缺失 stderr 断定单一原因；没有计时样本 |

修复只在独立测试宿主/包装器，不改变应用事件队列、学习、会话或候选行为。最后成功 Debug 连续任务为 `43101253611`。Release 使用原有外部宿主，在 API30 Google 镜像中执行，不用上述 Android15 新宿主。

## 证据与复现

原始附件、摘要与批次来源见 [evidence.json](./evidence.json)，本子目录自身校验见 [checksums.sha256.txt](./checksums.sha256.txt)。父报告原有 1,063 个文件以及 11 月 8 日原始 797 个文件保持原字节与原摘要；本调查是独立增补，不改写旧证据。

- [候选原始 ZIP](./candidate-trace.zip)：事件 JSON、logcat、配置和三份 Perfetto trace。
- [Debug 连续选词 ZIP](./repeat-trace.zip)：两词例完整宿主回调、截图、配置、映射与 trace。
- [原签名 Release 单项 ZIP](./release-control-trace.zip)：32 次实际点选、原宿主回调与 trace；包含部署前的短 trace 预检，预检不进入计时样本。
- [归因派生表](./derived.json)、[分析输出 ZIP](./analysis.zip)：逐事件关联、调度/状态、所有执行 SQL；派生表可重新生成。
- [三次宿主失败 1](./repeat-initial-failure.zip)、[失败 2](./repeat-root-failure.zip)、[失败 3](./repeat-bind-failure.zip)。
- [Release 包装器失败](./release-trace-start-failure.zip)、[API30 trace 启动失败](./release-api30-start-failure.zip)；对应控制台记录也保留在 `metadata/` 中。

工具在代码分支 `perf/investigate-input-latency-20261111` 的 `tools/latency-investigation-20261111/`，固定分析版本为 [`c780cf55`](https://github.com/SandyYuR/fcitx5-android/tree/c780cf55ea2b23770bc2bbe8d063082d780fa7b2/tools/latency-investigation-20261111)，不是默认分支。实际测量驱动分别为候选 `8f25c2e2`、Debug 连续 `fd24e1b4`、Release `0c8ebf18`；最后一个工具提交仅补保存帮助命令的 stderr，不倒改实际测量版本。下例只解析已下载证据，不操作设备：

```sh
unzip candidate-trace.zip -d candidate
python3 tools/latency-investigation-20261111/analyze-trace.py \
  --trace candidate/current-1.perfetto-trace --data candidate/current-1.json \
  --kind candidate --processor /path/to/trace_processor \
  --output candidate-analysis/process-1
# 其余两进程同样解析；repeat/ni、repeat/phrase 使用 --kind repeat。
# Release 同样用 --kind repeat，输出放 release-analysis/。
python3 tools/latency-investigation-20261111/summarize-traces.py \
  --evidence /path/to/extracted-evidence --output derived.json
```

完整摘要、工具固定提交、Release 结果和实际归档检查记录见证据清单。原始 ZIP 包含 trace；不将 APK、模型、native ELF 或 Perfetto 可执行文件提交到文档仓库。所有任务结束后，临时启用的工作流 `387536957` 已恢复 `disabled_manually`。此次未合并、发布或修改默认分支。
