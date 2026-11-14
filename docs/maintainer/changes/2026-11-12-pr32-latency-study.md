# PR #32 首次输入延迟正式对照研究（2026-11-12）

**首次输入的长等待仍然存在，本轮尚未证明当前候选文字改动已经解决回退。** 这次研究重新构建三个固定源码版本，先做独立 pilot，再按预登记顺序进行正式对照；没有用此前的旧计时替代新测量，也没有把正确提交等同于性能改善。

原 **11.141 → 39.814ms** 来自[此前候选优化报告](../perf/candidate-latency-fix-2026-11-12/index.md)中的旧探针。其后[撤回复核](../perf/input-dispatch-2026-11-12/index.md)和[工具审阅修复](./2026-11-12-pr32-review-repair.md)分别保存了当时的事实。本页追加新的实验，原报告正文、来源和摘要含义继续保留。

## 固定对象与实际 driver

| 对象 | 固定来源 |
| --- | --- |
| `baseline` | `0380d70f16efb6a9946a164821d5cad7118ce4ff`，原默认基线 |
| `regressed` | `4d77cd79a580a9984130a5653b13f949ec5bc0a9`，原候选与发布优先级组合试验 |
| `current` | `adb6a4e33426333677934ac82f0a19997245556a`，字体缓存修复及范围受限的无注释候选文字路径 |
| 原始构建 driver | `483c2870a6136f61cd5a13c416829fcfcca274b0` |
| 恢复元数据、pilot 与正式采集 driver | `204acdea83ae84b311b72aff154e564739eec623` |
| 后续工具修复记录 | `c34a54efa0e1d35da4e8406e576a0ef7e388e801`，新增本机隔离运行器和同线程时钟门禁；84 项工具测试通过 |
| 诊断进程退出门禁 | `13de8dc273a504ca7ccd5ab87f19f3cb0506aca9`，等待上一诊断进程实际退出并保留轮询证据 |
| 最终工具收尾 | `e8bc4fe70a5f23bbe56b8e2607580d786e4ff1c1`，恢复唤醒状态、保留失败原始数据、消除 `python -O` 可绕过的门禁并保证失败路径清理；103 项工具测试通过 |

后三行属于采集后的工具修复，不是本次正式采集使用的 driver。固定产品、各 APK、共同签名、宿主和 probe 的摘要见[产品清单](../perf/pr32-latency-study-2026-11-12/products.json)。三个产品保留相同 Debug 包名与 CI 签名，使用同一份 v2 instrumentation 源码；产品源码只覆盖测试文件，不改运行时源码来适配对照。

三版的 `native/rime-sdk.lock.json` 摘要均为 `6500f364626c863bee98157e51b6539990bb0f4e392c38ec442b6b3e5eeaa92f`，对应 SDK `android-sdk-20261110-1`、librime `b5561910`。每版 APK 白名单和原生链接审计均使用本版构建目录及 SDK 路径，验证后才生成产品清单。

夹具复用既有 run `43018095886` 的 artifact `13853652309`：万象 **v18.1.10 base＋2026-11-07 LTS**，以及原全拼、上下文调频、简码、小字集配置。`fixture.zip` 的 SHA-256 固定为 `333e0a0c8c9beb8a942bd56d3f9c55f1c9123836635a4663744e23f21228404f`。运行前检查 ZIP、来源清单及配置摘要，没有改用浮动 LTS，也没有上传新的本机方案或密钥。

独立编辑器宿主在 CI 中只构建一次，各 VM 复用同一 APK。它在 `InputConnection.commitText` 入口记录提交时间，通过 TextWatcher 记录累计文本，通过 onDraw 记录绘制回调；onDraw 不等于 GPU 或屏幕已呈现。

## 方法与预登记

三版均在 API 35、AOSP ATD、x86_64 模拟器运行，使用 `swiftshader_indirect` 软件图形，关闭系统动画。构建与测量处于不同 job，避免 Gradle 与模拟器争用同一宿主。每个新进程前重新安装并恢复同一夹具，部署与数据恢复不计入输入计时；这些动作只发生在本次 CI 的可丢弃环境。

新进程不等于冷存储。系统页缓存、宿主负载和虚拟图形仍可能影响结果，因此本页使用“首次”表示进程内第一轮，用“warm”表示随后五轮，不声称已控制磁盘冷缓存。

探针协议为 `fixed_cadence_100ms_input_manager_async_cached_targets_v2`，摘要 `06d66c801a6caa2c4d009270ae53ca02bcfbf5a0f6c7f3cab98efd50dd307c93`。字母目标 View 和坐标提前缓存，DOWN 回调不再遍历所有窗口。两次 DOWN 计划间隔 100ms，各计划保持 20ms；分别保存计划、实际提交、提交返回和窗口接收时间。固定计划不能保证注入线程准时运行，实际迟到及按住时间继续单列。

主指标是首次 **`n` 计划 DOWN → `ni_ready`**；后者为探针观察到后端 `ni` 预编辑发布的时间，不是画面呈现时间。支持指标包括首次完整 `i` 触摸的 **注入 DOWN → 窗口 UP**、其 **注入 UP → 窗口 UP** 子区间，以及首次候选 **注入 UP → IC 入口**。不能只选较短的 UP 子区间而漏掉此前的发送迟到或等待。

正式方案在 dispatch 前保存，见[原始预登记](../perf/pr32-latency-study-2026-11-12/formal-plan-before-dispatch.json)：

- 两个 VM 各 6 个区组，种子分别为 `2026111201`、`2026111202`。
- 每区组三版各一个新进程；六种产品排列各出现一次，避免某一版一直最先或最后执行。
- 统计单位为新进程。每进程六次选词，首次单列，warm 先取该进程后五次的中位数。
- 正式比较使用同 VM、同区组内的差分，不混入 pilot 或带 trace 样本，不把六次选词当作六个独立新进程。
- 保存所有尝试及失败，不用重建或重采样替换不利结果。

## 运行批次与失败保留

| 批次 | 结果与接纳范围 |
| --- | --- |
| [首次构建 `43173173211`](https://github.com/SandyYuR/fcitx5-android/actions/runs/43173173211) | 三版应用及测试 APK、原生审计和宿主构建完成；最终签名元数据解析失败。没有测量样本，失败状态保留 |
| [恢复与 pilot `43176830239`](https://github.com/SandyYuR/fcitx5-android/actions/runs/43176830239) | 复用同一批 APK/宿主，仅补全元数据；正反两个无 trace VM 共 6 个新进程、36 次正确选词，另一个独立 profile VM 有 3 个进程、18 次正确选词 |
| [正式对照 `43178037142`](https://github.com/SandyYuR/fcitx5-android/actions/runs/43178037142) | 两个无 trace VM 均成功；共 36 个新进程、216 次正确选词，全部纳入各自区组 |
| 本机首次启动 | Windows 的 CRCRLF 行尾使只读 AVD 身份检查拒绝，无设备写入和性能样本；原失败日志保留，按实际格式修复后重试 |
| 本机隔离 pilot | baseline 完成 6 次正确提交，但时间戳校验失败；没有可接纳的本机性能对照，详见下文最小事件 |
| 本机绘制结构诊断 | current 完成 1 个诊断进程、6 次正确提交和 Perfetto 采集；探针替换 Drawable callback 并抓取 Java 栈，只用于阶段顺序和结构归因，不并入性能对照 |

首次 CI 构建失败的原输出为 `Expected one APK signer: baseline-tests.apk`。当时的 `apksigner` 使用 `V2 Signer:` 行格式，旧解析器只识别 `Signer #1`；[原始签名报告](../perf/pr32-latency-study-2026-11-12/original-baseline-tests-signer.txt)保留实际字节。这不是 APK 签名失效，也不是应用构建失败。

恢复过程检查原源码、probe、SDK、原 APK 审计中的完整 APK SHA，以及原生审计与实际打包 ELF 的 SHA；重新验证签名后生成清单。清单继续保留原构建 driver 和 `failed_build_run: 43173173211`，明确 `rebuilt_products: false`，不把恢复提交冒充重新构建的产品。失败 run 和原始审计材料均未删除。

## Pilot：长等待仍能出现

下表为首次 `i` 的**实际注入 UP → 窗口 UP**，单位 ms；每格只有一个新进程，不能作为稳定效应估计。

| VM | baseline | regressed | current |
| --- | ---: | ---: | ---: |
| 正序，无 trace | 0.358 | 2.107 | 93.702 |
| 反序，无 trace | 56.234 | 30.070 | 46.266 |
| 独立 profile | 43.510 | 38.722 | 14.788 |

正序 current 的首次注入迟到仅约 **0.377ms**，实际按住约 **19.416ms**，但注入后等待约 **93.702ms**。这次长等待不能用“注入线程晚发了九十多毫秒”解释。同进程 warm 的该等待中位数约 **0.556ms**，首次与后续差异明显。

三个 pilot 宿主分别为 Intel Xeon 8370C、Intel Xeon 8573C、AMD EPYC 9V45。profile 还增加了诊断开销，因此其较短数值没有用于替换无 trace 的不利结果。完整首次与 warm 数值见[pilot 派生表](../perf/pr32-latency-study-2026-11-12/pilot-summary-compact.csv)。

## 正式对照：按 VM 和区组计算

下表差分均为 **产品 − baseline**，正值表示更慢；每行来自同一 VM 的 6 个区组。范围为实际最小到最大值，不是置信区间，也不是两个独立中位数相减。

主指标：首次 `n` 计划 DOWN → `ni_ready`，单位 ms。

| VM | 对照 | 区组差中位数 | 区组差范围 | 负／正区组数 |
| --- | --- | ---: | ---: | ---: |
| forward | current − baseline | +4.955 | −19.288～+46.767 | 2／4 |
| forward | regressed − baseline | +25.886 | −22.888～+64.506 | 2／4 |
| reverse | current − baseline | +29.498 | −65.749～+91.496 | 3／3 |
| reverse | regressed − baseline | +4.840 | −4.966～+85.472 | 3／3 |

支持指标：首次完整 `i` 触摸，即注入 DOWN → 窗口 UP，单位 ms。

| VM | 对照 | 区组差中位数 | 区组差范围 | 负／正区组数 |
| --- | --- | ---: | ---: | ---: |
| forward | current − baseline | −9.460 | −17.217～+66.335 | 4／2 |
| forward | regressed − baseline | +25.735 | −18.038～+77.865 | 1／5 |
| reverse | current − baseline | +49.447 | −66.626～+90.813 | 2／4 |
| reverse | regressed − baseline | +7.169 | −6.481～+95.023 | 2／4 |

current 在一个 VM 的完整 `i` 触摸中位数较短，在另一个 VM 较长；预登记主指标的区组差中位数在两台 VM 都为正，且区组间波动很大。没有足够证据据此宣布首次延迟已修复，或把全部等待唯一归因于某一处候选代码。

首次与 warm 的主指标绝对值如下。warm 先取每进程后五次的中位数，再汇总六个进程；这些汇总用于显示首次成本，不代替上面的配对差分。

| VM / 产品 | 首次中位数（ms） | warm 中位数（ms） |
| --- | ---: | ---: |
| forward / baseline | 181.337 | 127.845 |
| forward / regressed | 191.492 | 127.757 |
| forward / current | 185.858 | 127.748 |
| reverse / baseline | 155.837 | 129.441 |
| reverse / regressed | 217.669 | 130.480 |
| reverse / current | 206.599 | 129.098 |

forward 宿主为 AMD EPYC 9V74，reverse 为 AMD EPYC 7763，均提供 4 个宿主 vCPU。各 VM 的完整区组值见[配对差分原值](../perf/pr32-latency-study-2026-11-12/formal-paired-first-raw.json)，全部 12 项指标的首次与 warm 进程值见[完整 CSV](../perf/pr32-latency-study-2026-11-12/formal-all-process-values.csv)。没有将两台宿主合成一个性能样本池，也没有以本次样本量估计可靠 P95。

## 时钟有效性与本机拒绝样本

本机隔离 pilot 中，第 2 轮 `i` 出现了无需跨线程或窗口匹配即可确认的逆序：

| 同一注入线程上的记录 | `elapsedRealtimeNanos` |
| --- | ---: |
| 提交 UP 前 | `71844756335753` |
| 注入调用返回后 | `71844756065831` |

返回记录比调用起点早 **269,922ns（0.269922ms）**。该样本被拒绝，没有将负值改成零或继续用于性能结论。六次文字提交正确只证明此次内容结果，不能挽救无效时间轴；底层时钟原因仍需独立诊断。

本机仅公开[最小事件与恢复摘要](../perf/pr32-latency-study-2026-11-12/local-clock-rejection-minimal.json)：原默认输入法、启用输入法集合、硬件键盘显示开关、屏幕超时及绘制设置均已恢复，诊断包无残留，恢复与审计错误数均为零。用户设备包清单、原始窗口信息及完整设备状态继续留在本机。

发现本机异常后，对全部 CI 原始记录追加独立检查：

```text
0 < inject_down ≤ inject_down_return ≤ inject_up ≤ inject_up_return
```

pilot/profile 的 162 次触摸和正式试验的 648 次触摸，合计 **810 次全部通过**，没有缺字段或逆序。[派生校验记录](../perf/pr32-latency-study-2026-11-12/injection-clock-validation.json)逐文件保留结果。新门禁与回归测试进入 `c34a54ef`，但原实验仍按实际采集 driver `204acdea` 记录，原 ZIP 和 JSON 未改写。

## 独立绘制诊断：方差集中在窗口收到 UP 之前

正式 36 个新进程的阶段拆分显示，主指标 `n planned DOWN → ni_ready` 与完整 `i injected DOWN → window UP` 的相关系数约为 **0.966**，与 `i injected UP → window UP` 约为 **0.927**，与注入提交迟到仅约 **0.033**，与窗口收到 `i UP` 后到 `ni_ready` 约为 **−0.08**。前两项本来就是主指标的一部分，这不是独立因果证明；它说明需要解释的方差主要发生在窗口收到第二个键之前，而不是候选提交到编辑器的末段。

配对区组方向也一致：

| 对照 | 主指标区组差中位数 | `i` 注入 UP → 窗口 UP | 窗口 UP → `ni_ready` |
| --- | ---: | ---: | ---: |
| forward / current − baseline | +4.955ms | +5.201ms | −1.645ms |
| reverse / current − baseline | +29.498ms | +49.573ms | +0.446ms |
| forward / regressed − baseline | +25.886ms | +25.094ms | −5.546ms |
| reverse / regressed − baseline | +4.840ms | +7.011ms | +0.413ms |

本机随后只对 current 做了一次**诊断型**绘制探针。六轮 `i` 注入 UP → 窗口 UP 为 **59.54～164.68ms**，而窗口 UP → `ni_ready` 稳定在 **33.63～37.21ms**。前五轮有完整线程覆盖：主线程 CPU 为 **23.18～80.98ms**，RenderThread 为 **6.60～14.32ms**，`postAndWait` 重叠为 **6.09～22.80ms**；Rime owner 只有首轮约 **44.82ms**，其余为零。第六轮主线程覆盖不完整，值保持 `null`，不作完整归因。

该探针安装了 108 个 Drawable callback，并在回调中抓取 Java 栈；共观察 175 次回调，其中候选 106、键盘 48、preedit 21。它明显放大了主线程 traversal、JIT 与 GC，不能充当无 trace 性能估计。前两轮在 `i` 等待区间没有候选 callback 仍出现 93.90ms 和 116.34ms 等待，多数候选回调是绘制、可见性和按压状态变化的结果，而非已证明的根因。原始完整 trace、设备状态和窗口清单留在本机，只公开[派生摘要](../perf/pr32-latency-study-2026-11-12/local-draw-diagnostic-summary.json)。

正式 CI 的独立 profile 也显示 `updateCandidates` 只有约 **0.147／0.201／0.261ms**，较长区间主要与 RenderThread、`eglSwapBuffersWithDamageKHR`、模拟器图形传输和 `postAndWait` 重叠。固定 100ms 的第二键会撞上首次候选帧；小幅代码时序变化可以把等待移入或移出指标边界。因此，“局部工作量减少但局部计时变长”可以由帧相位和同步边界解释，不能据此认定 librime 或候选绑定本身变慢。

current 中保留的普通候选快路径只作用于**无注释**候选；本夹具显示“你 nǐ”一类带注释候选，所以本轮 current − baseline 也不能当作该快路径的效果估计。现有证据支持继续保留范围受限的正确性修复和撤回其余试验，不支持重新扩大线程优先级、绕过默认 TextView 样式或发布新的端到端提速比例。

## 证据与研究状态

[证据目录](../perf/pr32-latency-study-2026-11-12/index.md)保存五份已按 GitHub artifact SHA-256 核验的原始测量 ZIP、精简汇总、预登记、独立复算和本机绘制诊断派生摘要；不包含 APK、模型、夹具、私有签名材料或完整本机 trace。pilot 的 216 个首次/warm 指标值，以及正式试验汇总为 144 组统计的 864 个逐进程指标值，均由原始纳秒记录独立重算并核对一致。

本页记录的是仍有长等待的研究阶段，未发布性能改善比例或修复验收结论。独立绘制诊断已经完成本轮阶段拆分，但同进程探针、Debug JIT、软件图形与主线程绘制同步仍互相影响。这里的 v2 每键直接调用 InputManager 异步注入，已避开旧 UiAutomationConnection 的每键表面事务同步；UiAutomation 用于测试身份、宿主启动及测量后的 dump，不能再把旧注入同步当成本轮长等待的已证实原因。后续若继续研究，应把驱动移到独立进程，以同一诊断 ID 串起 service queue、owner、native、publish、主线程消费、adapter 与 FrameTimeline 实际呈现，并扫描不同键间隔和帧相位；得到跨 VM 可复现的干预结果后再决定产品修改。
