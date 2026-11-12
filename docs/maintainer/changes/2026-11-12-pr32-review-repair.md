# PR #32 审阅修复与候选文字范围调整（2026-11-12）

本次处理 [PR #32](https://github.com/SandyYuR/fcitx5-android/pull/32) 审阅发现的性能工具问题，并恢复范围受限的无注释候选文字优化。字体测量缓存修复继续保留；本次没有恢复此前整套候选性能补丁，也没有新增已经提速的验收结论。

[首个 i 分发等待复核报告](../perf/input-dispatch-2026-11-12/index.md) 记录的是此前撤回补丁时的代码与试验结果。该报告的 `10cc6331`、`b03e9b46`、`11.141 → 39.814ms` 等来源和数值继续按历史含义保存，不能改记为本次修改的性能结果。

旧报告仅在顶部增加后续修复入口，原正文逐字节保留。[原校验清单](../perf/input-dispatch-2026-11-12/checksums.sha256.txt)继续描述冻结原件，其中 `index.md` 的摘要对应[追加注记前的说明页](https://github.com/SandyYuR/fcitx5-android/blob/fd3b65ecc07bce2a38a01afcd307135e4b2f7eed/docs/maintainer/perf/input-dispatch-2026-11-12/index.md)；不通过改写历史摘要掩盖这次注记，原始附件保持不变。

## 来源与交付边界

| 对象 | 来源或状态 |
| --- | --- |
| 默认分支冻结基线 | `0380d70f16efb6a9946a164821d5cad7118ce4ff` |
| 本次代码修复起点 | `10cc633175ce3cb832e2ab098497c34915e291e7` |
| 本次文档修复起点 | `fd3b65ecc07bce2a38a01afcd307135e4b2f7eed`，PR #31 的报告分支 |
| 产品与工具修复提交 | `8801309478aa981cf45128df9be6f0e99d260bc8` |
| 最终代码提交 | `adb6a4e33426333677934ac82f0a19997245556a`，补充 Python 3.10 测试兼容性，已推送代码 PR 分支 |
| 对应最终代码 CI | `43166258826`（Direct Rime CI）、`43166258811`（PR CI）；收尾时登记终态 |

代码与文档分别维护在现有 [PR #32](https://github.com/SandyYuR/fcitx5-android/pull/32) 和 [PR #31](https://github.com/SandyYuR/fcitx5-android/pull/31)。写入修复文件、通过本机门禁、提交、推送及合并是不同状态；本页在最终收尾时按实际完成情况登记。

## 归因分析器：缺失证据不得变成零 CPU

旧 `tools/input-perf/associate-events.py` 可以在事件区间超出 trace 覆盖范围、或相应线程的调度记录不完整时，输出看似有效的零 CPU。仅检查 trace 中存在调度记录，不能证明每个输入区间都有足够证据。

修复后对 `candidate`、`cadence` 和 `repeat` 使用一致的来源与区间校验：

- 输入等待和 native 锚点必须完整落在有效的 `trace_bounds` 内，反向区间直接失败。
- 以 Perfetto `upid` 区分进程实例；相同 PID 被复用时仍不能混成一个被测进程。
- 对应线程的状态记录必须覆盖整个区间，不能有缺口或重叠；`Running` 时间还须与 `sched` 的实际运行交集一致。
- 缺失或歧义的 CPU 值输出为 `null`，并登记 `validation_errors`、返回失败；完整观察到的空闲区间仍允许合法的零 CPU。
- 失败时继续保存原始 SQL、查询输出和 `associated.json`，便于复查；不为合并掉的中间 `n` 展示伪造 native 终点。

这是工具接受条件的修复，不表示此前全部归档数据错误。旧档案的完整性和本次修复的拒绝能力分别验证，不通过合成反例推翻未出现该反例的历史结果。

## 基准运行器：明确目标与清空授权

`tools/input-perf/run-cadence.py` 的原基准依赖逐产品清空应用并恢复相同夹具。模拟器也可能保存长期数据，不能仅因串号以 `emulator-` 开头就把它当成可丢弃设备。

运行器现在要求同时传入 `--serial`、`--expected-avd-name` 和 `--allow-destructive-reset`。缺少清空授权时在任何子进程前退出；串号、在线状态或 AVD 名称不符时，只完成必要的只读查询，不执行设备设置、安装、卸载或数据写入。所有 ADB 路径，包括 instrumentation 的 `Popen` 和失败 trace 回收，都显式携带同一个 `-s` 串号。

本次保留原清空式对照协议，未暗中改成继承旧用户词库的模式。调用者仍须确认目标可用于该基准；本机长期保留数据的 `rime` AVD 不能因增加命令参数而被视为可清空。来源清单、夹具摘要、协议和产品 `.debug` 包名在设备写入前检查；运行清单记录目标身份及清空授权，Windows 支持查找 `aapt2.exe`。模块导入不再解析命令行或操作设备。

## 探针 v2：提前缓存实际触摸目标

旧固定节拍探针缓存了字母坐标，但 DOWN 回调内仍通过 `find` 遍历全局窗口和视图树，给被测主线程增加了额外工作。

`CadenceCandidateTapTest.kt` 现在提前保存包含 `View` 与坐标的 `Target`。DOWN 回调只核对该目标仍附着、可见，且实际触摸坐标落在其当前范围内；目标被替换或失效时使测试失败，不临时重新查找一个目标来掩盖变化。候选目标在 `ni` 展示稳定后、点选计时前取得。

新采集协议标识为 `fixed_cadence_100ms_input_manager_async_cached_targets_v2`。运行器要求使用该协议及匹配的探针摘要；分析器继续接受旧 `fixed_cadence_100ms_input_manager_async_cached_letters` 档案，保留两者身份，不混合比较。

两次 DOWN 仍计划间隔 100ms、各计划保持 20ms，并分别记录计划、实际提交和窗口接收时间。固定计划不保证注入线程每次准时执行；调度迟到仍须单列，不能只选实际提交之后较短的等待来宣称改善。

## 仅恢复无注释候选的普通文字路径

本次产品调整限于 `CandidateItemUi.kt` 的三处：

| 入口 | 行为 |
| --- | --- |
| `renderCandidate` | 无注释候选直接保留候选 String，用 `currentTextColor` 绘制正文，不为整段正文构造颜色 span；清除不适用的注释签名 |
| `setActive` | 无注释候选切换高亮时不重建文字，继续更新正文颜色、角标颜色及背景；有注释候选仍走原来的 span 渲染 |
| `refreshConfiguredFont` | 正文字体和字号照常应用；无注释时跳过注释字体签名计算及由该签名触发的文字重建 |

这减少了可明确识别的临时 span 和重复文字设置。视觉、字体、颜色、注释切换及候选目标协议仍须通过回归核对，不能把源码减少分配或调用本身写成已经测得的端到端收益。

发布优先级扩展、绕过默认 TextView 样式、角标延迟构造、测量 Paint 精简和跳过布局参数设置均继续撤回。`DirectRimeBackend`、JNI、引擎、SDK 锁、事件队列和选择目标协议不因本次范围调整改变。

## 验证记录与尚未验收的范围

| 检查 | 实际结果或待完成项 |
| --- | --- |
| 分析器自动测试 | 14 项通过；覆盖超界、缺口、重复进程、调度缺失、合法零 CPU、历史协议及失败证据保留 |
| 运行器自动测试 | 11 项通过；所有子进程均为 mock，覆盖未授权、不匹配、显式串号、旧协议、Release 包误用、正常清空路径和失败 trace 回收；没有操作真实设备 |
| 工具测试合计 | 25 项通过，不与历史 Android/JVM 用例数量相加 |
| 旧分析输出兼容复核 | 4 份旧 CSV 查询输出、共 72 个输入等待区间通过新校验；见下方复核边界 |
| Kotlin/JVM 候选回归 | 77 个实际用例，失败、错误、跳过均为零；包含新的 `CandidateRenderingStateTest` 在 API 23 / 28 的 8 次执行 |
| 本机 Debug / 测试编译 | ARM64 Debug、候选设备用例和 v2 固定节拍探针均编译通过；APK 白名单、16 KiB 对齐与原生链接审计通过 |
| 候选组件设备像素回归 | 本提交的 Direct Rime CI 原始 XML 有 54 项实际用例，失败／错误／跳过均为零；新增 `CandidateRenderingTest` 的三个像素／缓存用例均执行通过。本机未安装或运行模拟器 |
| 文档站五项门禁及 diff | 构建、链接、历史完整性、目录、搜索与 diff 检查通过；后续结果注记在文档 CI 再验证 |
| 对应代码提交的 CI | 最终 `adb6a4e3` 的两个运行正在执行；25 项工具测试已在远端 Python 3.10 环境通过；不沿用 `10cc6331` 的历史 CI |

旧 CSV 复核使用[原分析附件](../perf/input-dispatch-2026-11-12/analysis.zip)中最后一轮固定节拍的正序/反序、默认/候选四组查询输出。旧查询未导出 `upid`，离线适配只对已确认的单进程记录补入同一个合成实例标识，以重播新覆盖校验；这不是重新采集，也不是用旧字段证明已经检测了 PID 复用。原始 CSV、ZIP 与历史结果保持不变，多实例拒绝能力另由自动测试覆盖。

本机验证使用 D 盘独立工作树，基于 `10cc6331` 加本次补丁；11 个改动文件经换行归一化后逐项核对，与 `adb6a4e3` 完全一致。`88013094 → adb6a4e3` 仅修改 Python 测试的清理方式，Android 产品及设备测试源码相同。该验证 APK 的生成版本标识仍为旧基线，不能将其字节标记成 CI 的新提交产物。Windows 检出采用 `core.symlinks=false` 的文件表示，没有将未能创建的发布素材／嵌套 wrapper 链接当作已验证的符号链接行为；构建使用仓库根 Gradle Wrapper，固定 Rime 预设子模块正常初始化。未修改主工作区与长期模拟器数据。

首个修复提交 `88013094` 的 Direct Rime CI 和两条 Debug／lint 检查通过，但两个 PR CI 的 JVM job 在前置 Python 测试失败：Ubuntu 22.04 的 Python 3.10 没有 `TestCase.enterContext`，11 个运行器用例在 `setUp` 抛出 `AttributeError`，因此后续远端 JVM 业务测试当时未执行。`adb6a4e3` 改用 `contextlib.ExitStack` 与 `addCleanup`，没有升级 CI 环境或改变产品实现。本机禁用该新 API 后重跑全部 25 项用例通过，随后远端 Python 3.10 的同一步骤也通过；保留[首次失败的运行](https://github.com/SandyYuR/fcitx5-android/actions/runs/43164464711)，不把它记成成功。

原始与派生验证依据：[设备 XML 和诊断归档](../perf/pr32-review-repair-2026-11-12/frontend-results.zip)、[本次设备用例核验](../perf/pr32-review-repair-2026-11-12/frontend-validation.json)、[本机 JVM XML](../perf/pr32-review-repair-2026-11-12/local-jvm-results.zip)、[工具测试输出](../perf/pr32-review-repair-2026-11-12/tool-tests.txt)、[本机构建源码对应](../perf/pr32-review-repair-2026-11-12/source-validation.json)、[旧采样兼容重播摘要](../perf/pr32-review-repair-2026-11-12/association-replay.json)、[本机文档门禁](../perf/pr32-review-repair-2026-11-12/docs-gates.json)。设备归档 SHA-256 为 `15c678f2ef694aa99a9378067864dcbbec0e218a913fff340b822d8cec957b4d`，对应本次运行的 artifact `13918709948`，与 GitHub artifact digest 一致。

本次尚无新的无 trace 性能对照、真机或 Release 提速结论。旧 `1,032` 次选词正确、`51` 项前端用例以及旧候选版的有利或不利计时，均仍属于各自历史源码和测试条件，不作为本次候选文字调整的性能验收。
