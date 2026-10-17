# 直连 Rime：默认分支对照与迁移完成情况

本页回答两个不同的问题：直连产品是否还运行 Fcitx 原生引擎，以及仓库是否已经彻底改成独立项目。
前者已完成解耦；后者尚未完成，不能将整个迁移计划标记为全部完成。

## 对照基线纠正

[上一轮记录](DIRECT_RIME_DECOUPLING_2026-10-16.md)比较的是两个直连版本，
只能检验那轮缓存与事件更新优化，不能证明删除 Fcitx 中间层的收益。
其原始数据仍保留，结论范围不扩大。

本轮基线固定为默认分支 `fx-rime-only` 的
`a714feff77fd79082807b9c7141e0515c8c83f73`，包含 Fcitx core、AndroidFrontend 和 fcitx5-rime。
使用该提交自带的 `prepare_personal_build.sh` 和普通 `:app:assembleFxDebug` 构建，
不启用直连属性，不修改应用主源码、构建配置或输入路径。
只在 `androidTest` 中加入共用回放程序。

两版都使用同一 gitlink 的 librime（prebuilt `1126616c3ec569617da772e30d7a97f6b2694172`），
相同官方万象 v18.1.3 + LTS 夹具、Android 35 `aosp_atd` x86_64/KVM 模拟器与 Debug 构建。
每次创建新的 profile，预热 20 词，然后回放 100 词、784 键；每版做三次，顺序为
Fcitx / 直连、直连 / Fcitx、Fcitx / 直连。另一版进程在每次回放前强制停止。
两版都必须确认 LTS 模型实际映射，且校验所有上屏文本和逐键候选文本顺序相同。

主要场景按用户补充的实际配置：**万象 base、全拼、上下文调频开启、简码开启、小字集**。
`prepare-wanxiang.py --preset user-full-pinyin` 在同一官方方案与模型上加入三个开关的 reset 补丁，
不修改词典、拼写或候选窗口。组合夹具 SHA256 为
`8c9c5bb8d42552286d8b052353aa87b14c835b7882a58f8c2a9487bcab33e293`。
回放程序还检查实际状态区包含「上下文开」「简码开」「小字集」，避免只改文件而未生效。
每轮的新用户库会在预热中学习，尚不包含个人长期词频或已有手动排序记录。
语料是八组固定全拼的重复回放；开关启用并不意味着已经覆盖简码输入、长句、
退格修改、展开长候选及快速连击等所有实际用法。

补充场景保留上游默认全拼。上游 `context_reorder` 初始关闭，新档案没有手动排序记录；
这组数据只用于对照默认负载，不能替代上述用户配置。

主要指标为 API 发出按键至同一个收集器观察到候选事件，以及空格至上屏事件。
单独记录 `sendKey` 返回时间，避免把 Fcitx 延后刷新的 UI 工作漏在时间之外。
这些指标不包含触摸识别、主线程布局或屏幕显示完成时间。
两版差值包含适配器实现、数据打包和事件交付的差异，不能全部归因为某一个 Fcitx 路由函数。
同时记录进程 CPU 时间、RSS/PSS、Java/native 堆；额外一次直连追踪用于分解
`process_key` 与候选快照，追踪轮不混入三轮耗时对照。
CPU/内存包含同一套 instrumentation 的成本，不能当作无测试程序参与的正式版资源占用。

回放程序：[EngineComparisonTest.kt](../../tools/input-perf/EngineComparisonTest.kt)。
构建和设备流程：[fcitx-direct-perf.yml](../../.github/workflows/fcitx-direct-perf.yml)、
[compare-engines.py](../../tools/input-perf/compare-engines.py)。

### 用户实际配置的主要结果

[实际配置工作流](https://github.com/SandyYuR/fcitx5-android/actions/runs/39002393966)
六轮回放通过。直连对照构建提交为 `c5ee1c9a3d1bbc5024f7acd7326bf4ac341d05ac`；
此提交及本轮其他提交只添加测试工具、工作流和记录，应用实现与此前交付版一致。
原始逐键耗时、开关状态、上屏序列、候选摘要、APK 哈希与统计均保存于
[实际配置回放](perf/2026-10-17-fcitx-user-wanxiang/)。
下表为三轮各自分位数的中位数，耗时单位为 ms。

| 指标 | 默认分支 Fcitx | 当前直连版 | 降幅 |
| --- | ---: | ---: | ---: |
| 按键到候选 P50 | 11.458 | 10.774 | 6.0% |
| 按键到候选 P95 | 16.025 | 14.896 | 7.0% |
| 按键到候选 P99 | 29.516 | 27.689 | 6.2% |
| 空格到上屏 P50 | 1.833 | 1.795 | 2.1% |
| 100 词总耗时 | 8,973 | 8,298 | 7.5% |
| 100 词进程 CPU 时间 | 9,277 | 8,583 | 7.5% |

候选 P50 的三轮范围分别为 Fcitx 11.346–11.470 ms、直连 10.700–10.781 ms；
P95 分别为 15.909–16.673 ms、14.349–14.942 ms。
六轮的 100 条上屏文本、784 个逐键候选文本列表及顺序完全相同，
候选摘要均为 `d395e0f58ff63c66dc705535914cdec162216e39264e63d6ee1112042a794908`。
每轮均产生 100 个 CommitString、784 个 PagedCandidate 和 784 个 InputPanel 事件，
没有通过减少候选更新次数取得这组收益。

回放后 PSS 中位数为 Fcitx 249.1 MiB、直连 246.9 MiB，约差 2.2 MiB；
三轮范围分别为 248.0–250.0 MiB、246.5–247.9 MiB。
这组结果不支持宣称内存占用大幅下降，也不包含个人长期词频库的负载。

同 ABI、同 Debug 变体的 APK 从 27,543,910 字节降为 23,320,722 字节，约小 15.3%。
原生库未压缩总大小从 13,911,832 字节降为 5,540,632 字节。
详细条目和两个固定 APK 的 SHA256 见 [APK 审计](perf/2026-10-17-fcitx-user-wanxiang/apk-audit.json)。
模型不包含在 APK 中，因此这些体积数据不包含万象词库或 LTS 模型。
它们是 CI 签名的 x86_64 测试包；用户 ARM64 设备继续使用此前交付的包。

### 同一 APK 的直连热点追踪

[补采工作流](https://github.com/SandyYuR/fcitx5-android/actions/runs/39003412245)通过。
该任务直接下载主要对照中的固定 APK，不重新构建引擎；直连 APK 的 SHA256 仍为
`27b76aa4e1c5ac68645e0dcda53935d6caaf545d326b8d5a95ef386cfde23945`。
这是独立追踪轮，不混入上面的三轮耗时统计。

Perfetto 的 `wanxiang.measure` 开始和结束标记完整，测量窗口为 8,742.778 ms，
其中恰好有 784 个完整 `process_key` 和 784 个完整 `snapshot` 切片。
追踪轮的上屏文本和候选摘要与主要六轮相同；追踪数据未报告 ftrace 丢失或缓冲区覆盖。

| 测量窗口内的切片 | 样本数 | 合计 / ms | 占窗口时长 | P50 / ms | P95 / ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| librime `process_key` | 784 | 7,673.445 | 87.8% | 10.233 | 14.028 |
| 原生快照读取及复制 | 784 | 208.546 | 2.4% | 0.198 | 0.726 |

这里的百分比是该轮墙钟时间的分解，不是 CPU 占用率；其余时间包含 JNI/Kotlin、
协程调度和测试收集器等成本，仍不包含触摸与屏幕渲染。
追踪已把主要耗时定位在 Rime 调用内，但还没有把 translator、Lua 各过滤器、
LTS 查询和数据库访问分别分解，不能据此断言某一个万象模块是瓶颈。
仅优化快照也无法解释或实现大幅的逐键提速。

原始追踪文件为工作流的 `wanxiang-native-profile` 产物，6,691,149 字节，
SHA256 为 `65b4428b753a9b804efaadd32cceceee9d63303a6f43ccdfde7dcc9439120876`。
仓库保存 [追踪轮输入输出](perf/2026-10-17-fcitx-user-wanxiang/traced-profile-direct.json)、
[统计](perf/2026-10-17-fcitx-user-wanxiang/analysis.json)和
[来源与校验记录](perf/2026-10-17-fcitx-user-wanxiang/provenance.json)，不提交追踪二进制。
下载并解压产物后可复算：

```sh
python3 tools/input-perf/analyze-engines.py \
  docs/maintainer/perf/2026-10-17-fcitx-user-wanxiang \
  --trace-processor /path/to/trace_processor_shell \
  --trace /path/to/wanxiang-direct.perfetto-trace
```

早期采集使用信号探测进程存活，Android 的权限拒绝会被误判为采集结束，
导致文件为空或缺少结尾；这些追踪均排除。当前采集检查进程目录、周期性 flush，
等待消费者自然退出，分析程序还强制检查完整测量窗口内的切片数量。

### 默认配置的补充结果

[三轮工作流](https://github.com/SandyYuR/fcitx5-android/actions/runs/39001461692)通过。
原始数据与统计见 [默认配置回放](perf/2026-10-17-fcitx-default-wanxiang/)。
下表是每轮分位数的中位数，不把不同轮次混成一组样本。

| 后端 | 按键到候选 P50 / ms | P95 / ms | P99 / ms | 空格到上屏 P50 / ms | 100 词进程 CPU / ms |
| --- | ---: | ---: | ---: | ---: | ---: |
| 默认分支 Fcitx | 10.181 | 14.518 | 29.064 | 1.720 | 8,402 |
| 当前直连版 | 9.718 | 13.699 | 26.915 | 1.651 | 7,888 |

默认配置下，三轮的候选 P50/P95 有约 4.5%/5.6% 的改善，进程 CPU 约低 6.1%。
所有轮次各 100 条上屏、784 次候选事件，上屏与逐键候选文本完全相同；事件数量也相同。
因此上一轮“相较早期直连版减少约一半事件”的结果，不能再套用于这次 Fcitx 基线。

回放后的 PSS 范围为 Fcitx 248.7–283.0 MiB、直连 244.9–246.2 MiB。
内存采样受 GC 与页面驻留影响，Fcitx 组波动较大，不能把全部 PSS 差值都算成 Core 本身占用。
原生实际加载也已核对：Fcitx 进程有 Core/Config/Utils、AndroidFrontend、Rime addon 等 9 项库，
直连进程只有 `libdirect-rime.so`、`libc++_shared.so`；两边都映射了 LTS 模型。

这组任务的额外追踪输出为空，未纳入热点分析；补采使用上节实际配置的固定 APK。

## 原计划逐项核对

依据 [2026-10-07 再评估稿](https://github.com/SandyYuR/fcitx5-android/blob/docs/docs/maintainer/DIRECT_RIME_REEVALUATION_2026-10-07.md)
的 A–D 门控，以及 [2026-10-02 历史稿](https://github.com/SandyYuR/fcitx5-android/blob/docs/docs/maintainer/ARCHIVE_DIRECT_RIME_FEASIBILITY_ORIGINAL_2026-10-02.md)
阶段 0–5 的范围核对。
这不是按代码行数计算的完成百分比。

| 领域 | 当前完成情况 | 尚未完成或明确边界 |
| --- | --- | --- |
| 阶段 0：基线与性能证据 | 已有可复用的回放与诊断；本轮补真实 Fcitx 默认版对照 | 真机触摸到显示、冷启动、部署时延、长候选及持续快速输入的综合对照仍缺失 |
| 阶段 1：独立输入闭环 | 已完成：专属 owner、JNI 批量快照、按键、候选、选字、分页与上屏；完整原 UI 可用 | 不能由普通中文输入通过推导所有硬件键行为等价 |
| 阶段 2：业务与生命周期 | 已实现焦点/session generation、密码绕过、部署门控、同步、目录/ZIP 导入、迁移与备份接口 | 原 Fcitx 完整 XKB Compose 没有等价实现；未复刻各 UID 的历史 composition 缓存；多厂商 InputConnection 和高压切换未充分验证 |
| 阶段 3：定制能力 | 已桥接 input tabs/选 tab、候选动作/忘词、分页与 levers 方案选择 | 不再提取没有产品消费者的 candidate code 元数据；万象可见注释使用最终 `candidate.comment` |
| 阶段 4：设置收口 | 按键与 INI 已独立；schema/option、部署与方案选择直接调用 Rime API | 仍有自建 RawConfig 描述、`fcitx://` 路由及原 INI 文件协议；未移植旧适配器对 `fcitx5.yaml/app_options` 的完整处理；全局/addon 接口只支持直连版的子集 |
| 阶段 5：切换与清理 | 直连构建不配置 Fcitx Gradle 工程，不检出 Core/addon 即可构建；APK 无 Fcitx 原生库、旧 addon 数据或工具 JNI | 默认分支未切换；构建开关默认仍为旧后端；旧 native 源码、构建路径、部分未执行的旧类及命名仍保留 |

按历史稿六个阶段的完整范围，阶段 1、3 已完成，阶段 0、2、4、5 部分完成，
即 **2 项完成、4 项部分完成**。直连包的原生构建与运行依赖清理已经完成；
阶段 5 还包括默认切换和仓库清理，因此整体仍标为部分完成。
业务等价、配置模型收口、广泛设备/性能验证与仓库级最终清理仍有工作。
使用自建类型继续兼容旧配置，不等于运行 Fcitx Config 库；但它也不是彻底去掉旧接口设计。

仓库清理宜先拆分 direct/legacy 源码集和工厂：让直连 APK 不再编译旧 `Fcitx`、
`FcitxDispatcher` 类，随后把活跃的公共 API/事件与设置描述逐步改为引擎中立模型。
全面替换名称本身不会减少已运行的原生中间层，也不应破坏布局、备份和 URI 兼容。

## 哪些 Fcitx 仍存在、哪些实际运行

| 部分 | 直连包中是否实际运行 | 说明 |
| --- | --- | --- |
| Fcitx core / InputContext / AndroidFrontend / fcitx5-rime / 原生 clipboard、notification addon / libuv | 否 | 不参与直连 CMake、链接或加载 |
| Fcitx5Utils / Fcitx5Config | 否 | 工具 JNI 已删除，按键与 INI 使用 Kotlin |
| `FcitxAPI`、`FcitxDaemon`、生命周期与连接管理 | 是，作为应用 Kotlin 公共层 | daemon 根据 `DIRECT_RIME` 选择 `DirectRimeBackend`；不创建 Fcitx 实例 |
| `FcitxEvent`、RawConfig、`fcitx://`、旧布局/设置标识 | 是，作为兼容协议 | 由直连适配器生成/处理，不调用 Fcitx 原生 API |
| `FcitxInputMethodService`、原键盘、主题、编辑器与设置 | 是 | 保留原 Android 前端功能；它们属于此项目的应用代码 |
| `Fcitx.kt`、`FcitxDispatcher` 与旧 native 源码 | 不走直连输入路径 | 仍在仓库；已从交付 Debug APK 的 DEX 确认仍包含旧类，完整源码清理尚未做 |
| `FcitxComponentPlugin` 与共享 prebuilt 中的 `libuv` 等历史依赖 | 不在应用中运行 | 构建插件直连模式仅保留空兼容任务；共享依赖仓库仍存放其他引擎和历史库，直连不链接、不打包它们 |
| 仓库默认版、未指定直连属性的常规构建 | 是，仍使用原 Fcitx 后端 | 不能把直连包的依赖结论套用到这些 APK |

交付 ARM64 APK 的原生库与 DEX 类定义检查结果见
[运行依赖审计](direct-rime-runtime-audit-2026-10-17.json)。DEX 中存在类不代表它被执行；
本轮设备回放已在应用进程内检查实际加载的原生库：原版有 9 项应用库，
直连版仅有上述 2 项，两者均实际映射 LTS 模型。

当前直连产品需显式构建：

```sh
git submodule update --init --depth 1 native/prebuilt native/rime-prelude
./gradlew -PdirectRimeFrontend=true -PbuildABI=arm64-v8a :app:assembleFxDebug
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
```

交付包名为 `io.github.sandyyur.rime.direct`；普通 Fcitx Debug 包名为
`org.fcitx.fcitx5.android.fx.rime.debug`。当前两者的产品和默认构建入口仍有区别。

## 后续优化应如何选择

本轮同条件对照的候选 P50/P95 收益约为 6%/7%，直连追踪的主要耗时在 `process_key` 内。
原生中间层移除带来了可测但有限的收益；后续优先细分引擎/方案耗时和测量真实 UI 渲染，
不能把接口改名或仓库清理当作新的逐键性能收益。
已有确定性清理包括候选 code 复制、逐键开关配置遍历和重复状态推送；不再把减少事件量自动解释为提速。

可以继续检查重复的 profile 启动/部署、快照读取与 JNI/Kotlin 分配、候选渲染增量更新，
以及空候选/未处理 release 的快照成本。任何缓存都必须保持候选尾部、开关和 session 正确失效。

已经从当前代码确认、但尚未实施的新机会：

- `restartNative()` 先 `Native.start()`，其中执行一次 `start_maintenance(false)` 并等待，
  随后又 `Native.deploy()` 执行 `start_maintenance(true)` 并等待。
  可以把启动时是否 fullcheck 作为明确参数，合为一次检查；收益需另测目录切换与部署，不能算作逐键提升。
- 互斥开关组目前逐个 `act(8, ...)` 清除其他选项，再设置目标选项，每步都会生成完整快照并发布事件。
  可新增批量选项动作，一次 owner 事务后生成一份快照，减少开关操作时的重复刷新和中间状态。
- 对未处理的 release 也读取完整 context/status/tabs。可以研究更细的失效信号，
  但不能直接跳过所有 release：Rime 的 ASCII 切换可在 Shift 等修饰键释放时生效。
  这主要关系硬件键盘与修饰键宏；普通虚拟字符键通常只发送 press，本轮 784 键回放也采用这一方式。

万象的上下文调频、手动排序与过滤器还可能在惰性候选获取阶段产生开销；
关闭功能、裁剪候选窗口或修改词典会改变行为，应独立做功能可选的对照，不能混作中间层优化收益。
本次核对的 v18.1.3 已缓存同一上下文下候选的 1/2-Gram 精确读取结果，
因此“再缓存一次上下文 DB 查询”不能当作尚未实施的新优化。
`context_reorder` 的候选扫描上限为 50；有手动排序记录或当前调整动作时，
`super_sequence` 的排序扫描上限为 100。它们是下一步细分采样的对象，不是已经证实的耗时排名。
本轮没有个人手动排序记录，也没有调低这些上限来换取速度。
同一字符集过滤器在链中出现两次，也应先核对中间自定义词条、重排和兜底历史的语义，不能直接去重。

上述后续机会尚未修改产品代码；本轮交付的是公平对照、可复算证据与迁移审计，
此前交付的 ARM64 APK 和签名不变。产品实现所在提交的
[PR CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/39001903526)和
[直连检查](https://github.com/SandyYuR/fcitx5-android/actions/runs/39001903539)均已通过；
本轮主要回放和补采工作流也均通过。
