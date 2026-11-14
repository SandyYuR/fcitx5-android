# 候选布局与按键发布优化验证（2026-11-12）

本轮针对[首键与点选调查](../wanxiang-2026-11-08/supplement-2026-11-11/investigation-2026-11-11/index.md)实施局部修改。**最终版本在两台对照 VM 上都减少了首次候选布局 CPU，首次点选到编辑器 IC 入口的中位数也都降低；冷启动首键尚未全面稳定，首个 i 的分发等待还有较慢结果。** 不将这些局部改善表述为所有卡顿已解决。

代码在 [PR #32](https://github.com/SandyYuR/fcitx5-android/pull/32)，文档在 [PR #31](https://github.com/SandyYuR/fcitx5-android/pull/31)。默认分支不直接推送或合并。旧报告的原始文件保持原字节，本页的三轮实验独立保存。

## 修改与来源

- 候选角标在开启时才创建 TextView；普通候选使用普通文字，高亮不重建其文本。注释仍保留独立字体、字号与颜色。
- 测量与绘制共用已有纯文字；颜色独立的区段不复制测量 Paint，仍按绘制的 CharacterStyle 边界分段。实际字体和缩放变化继续使缓存失效。
- 候选已显式设置外观，省去通用 TextView 组件样式解析；其他键盘文字保留原默认样式。相同布局参数不再反复设置。
- `sendKey` 的原生调用、结果发布及转发入队在同一短交互优先级作用域，保持现有 `THREAD_PRIORITY_DISPLAY` 数值和 `finally` 恢复。新增 `key_publish` / `action_publish` 分段，未增加线程或 dispatcher。

输入 ticket、generation、候选与音节版本、事件保序、学习和维护事务均保持原协议。没有关闭上下文调频、用户学习、注释、音节选择、候选或模型功能，没有修改 JNI、SDK 锁及引擎源码。

| 对象 | 固定版本 |
| --- | --- |
| 修复前 | 默认分支冻结源码 `0380d70f16efb6a9946a164821d5cad7118ce4ff`；复用原 Debug APK，SHA256 `ac2362b4d12f952187cab7d81a6a54476f0d7061d58867053030952ee39e7f37` |
| 第一轮 | `015bde3a4205d63ee0b2bcb58a46c2a7f8b8cd92`，首次候选精简与字体缓存失效 |
| 第二轮 | `4e28ceebe057345a678c5746d3ab8d413cf77a27`，补充显式候选样式及按实际字体变化失效 |
| 最终测量 | `4d77cd79a580a9984130a5653b13f949ec5bc0a9`，增加按键发布优先级作用域 |
| 最终测量 ARM64 Debug | SHA256 `dd90f320def9b8efefa38ceafbe4689628dbe7ca11f8dc809e06343f0948800c` |
| 引擎 | SDK `android-sdk-20261110-1`，librime `b5561910`；[原锁文件](./sdk-lock.json) |

分支整理后的 PR 提交与实测源码对应关系见 [source-map.json](./source-map.json)。应用、SDK 和构建配置源树经过逐路径比较；整理历史及 README/工具变化会改变生成版本元数据，**不把旧 APK 改记成新提交已经重测**。完整实测源码和实验工具保存在[分支归档](../../audit/development-branches-2026-11-12/index.md)。

## 相同配置与计时方法

沿用正式批次 `43018095886` 的 **万象 v18.1.10 base + 2026-11-07 LTS**，以及用户原两份 YAML：全拼、上下文调频开、简码开、小字集。逐批校验夹具、模型和 APK 摘要，不下载浮动最新版，不混入个人历史词库。探针继续检查实际模型映射、无 Fcitx Core 和三个运行期开关。

Debug 在 API35 AOSP ATD x86_64、软件图形、KVM 的 Actions VM 中运行。每轮两台 VM，一台先 before 后 after，另一台相反；每台每版 3 个新进程，每进程真实输入和点选 6 次。两份原 Kotlin 探针逐字节相同。另每台每版各 1 个新进程采集 trace，独立用于归因。部署不计时，系统文件缓存不属于存储冷启动。

每轮 **12 个无 trace 进程 + 4 个 trace 进程，96 次选词全部正确**；三轮共 288 次。表中首次每版 n=6，来自两台 VM 的各 3 个进程，不是 6 台独立机器。热态和首次不混池，不用这批样本声称可靠 P95 或日常丢键率。

- 外部 UP 是注入前记录的时间；IME dispatch UP 是窗口回调入口，二者之间的等待不能漏算。
- IC 是独立编辑器 `commitText` 入口；TextWatcher 是文本变化回调，都不是屏幕发光时间。
- trace CPU 取对应线程与 `sched` 的实际交集；wall 包含等待，不能把独立分位数相加。
- 首次 n 的编码事件终点是探针收集时间；首词组合指标还包含探针反应、找坐标及注入停顿，不能当成真实打字速度。

## 最终版本：无 trace 结果

以下单位 ms，汇总为 6 个新进程首次值的中位数。完整范围、逐进程数值和两个 VM 分组见[派生表](./iteration-3/comparison.json)。

| 首次测量 | 修复前 | 最终修复版 |
| --- | --- | --- |
| n dispatch UP → 编码事件收集 | 50.857 | 41.076 |
| i 注入 UP → IME dispatch UP | 11.141 | 39.814 |
| 候选外部 UP → IC 入口 | 9.037 | 6.280 |
| 候选外部 UP → TextWatcher | 16.733 | 14.651 |
| 候选 dispatch UP → TextWatcher | 16.152 | 10.123 |

按 VM 拆开，仍用各自 3 进程的中位数：

| VM 与产品执行顺序 | 指标 | 修复前 | 最终修复版 |
| --- | --- | --- | --- |
| A：before → after | n → 编码事件 | 75.768 | 64.641 |
| B：after → before | n → 编码事件 | 31.497 | 36.319 |
| A | i 分发前等待 | 21.732 | 51.953 |
| B | i 分发前等待 | 0.550 | 37.119 |
| A | 候选外部 UP → IC | 10.733 | 6.762 |
| B | 候选外部 UP → IC | 7.342 | 5.798 |
| A | 候选外部 UP → TextWatcher | 15.441 | 11.722 |
| B | 候选外部 UP → TextWatcher | 18.025 | 17.581 |

点选到 IC 两个分组均较短，合并中位数约低 30.5%；TextWatcher 的改善幅度较小。首键 n 在 VM B 仍较长，**不能只用合并中位数宣称首键全面改善**。

首个 i 的分发等待在两台 VM 中反而更长，原因尚未确定。该探针会先在主线程查找键位，再注入事件；此前工作会影响实际投递时刻。这个限制不构成删掉较慢样本的理由，也不能单凭某个间隔推断整段输入的真实触屏延迟。后续需严格节拍的独立注入或真机 trace 验证。

## 最终版本：首次候选布局与 native 归因

每台每版只有 **1 个独立 trace 新进程**，不并入上述无 trace 表。取首次 n 的 native core 完成后、首次 i 分发前的 RecyclerView 布局窗口，保留全部匹配记录；本轮每份 trace 均只有一条对应布局。单位 ms：

| VM | 布局 wall：前 → 后 | 布局 CPU：前 → 后 |
| --- | --- | --- |
| A | 14.504 → 12.689 | 12.310 → 10.754 |
| B | 16.352 → 9.840 | 10.700 → 7.219 |

两组布局 CPU 分别约低 **12.6% / 32.5%**，支持减少首次构造工作的方向；样本数量不足以给出普遍固定比例或真机收益。

首个 n 的 `process_key` 仍有实际成本：VM A 的 CPU **18.412 → 17.550ms**，VM B **13.286 → 15.774ms**；wall 分别为 **38.846 → 58.863ms**、**18.420 → 34.414ms**。计算与等待仍未全面改善。新发布作用域不会减少 Rime API 内部的首次计算，不能据此宣称模型、Lua 或磁盘等待已经优化。

四份 trace 的 **48 个 n/select 锚点**都找到唯一 core，Perfetto error/data_loss/lost/drop 检查无正值。SQL、调度、线程状态与逐事件关联见[分析 ZIP](./iteration-3/analysis.zip)。

## 中间两轮也完整保留

| 轮次 | 无 trace 首选 UP → IC 中位数：前 → 后 | 无 trace n → 编码中位数：前 → 后 | 判断 |
| --- | --- | --- | --- |
| 第一轮 | 5.454 → 12.144ms | 61.906 → 51.764ms | 点选较慢；两个 VM 的布局 CPU 方向不一致，没有证实稳定提速 |
| 第二轮 | 11.419 → 7.042ms | 44.469 → 73.970ms | 点选及布局 CPU 有局部改善，但首键较慢，未宣称完成修复 |
| 最终轮 | 9.037 → 6.280ms | 50.857 → 41.076ms | 点选及布局改善，首键分组不一致，i 分发等待仍有较慢数据 |

每轮运行于不同 VM，且后两轮源码不同，**不能跨轮把数值拼成逐步提速曲线**。中间结果保留，不以重新采样替换不利记录；选择最终修改依据可避免的构造、缓存失效和调度边界，最终行为与局限继续由本轮完整对照评估。

## 回归、失败与未验证范围

最终实测源码通过 **131 项目标 JVM 用例、14 项 Android 设备用例，失败/错误/跳过均为零**。此前两轮各有 103 项 JVM，设备分别为 12/13 项。测试覆盖候选目标与保序、字体、注释颜色、角标几何、溢出、触摸取消及优先级异常恢复。

原始 ARM64/x86_64 Debug 构建、APK 白名单、16 KiB 对齐与原生链接审计通过；最终包原生库为 `libdirect-rime.so`、`libc++_shared.so`、`libquickjs.so`，没有 Fcitx Core 依赖。131/14 与仓库完整 CI 的用例互有重叠，不相加成独立覆盖数量。[实测源码的完整 CI 记录](./measured-source-ci.json)保存了通过状态。

整理后 `986e2c72` 的[全部 CI 检查](./consolidated-source-ci.json)也通过：JVM、Debug、ARM64 完整前端、真实引擎 smoke、前端触摸与候选回归、原生 sanitizer。候选字体、角标与溢出测试已加入常规设备脚本；这批是整理后源码的回归验证，没有重新计时，不能替换上表 `4d77cd79` 的原始性能来源。

失败和流程调整均在[元数据](./metadata/43128260193.json)中保留：

- `43128260193` 在编译时发现新角标创建调用错误，修正后再构建；没有计时样本。
- `43128806005` 构建、目标 JVM 和产物审计成功，设备任务在 JDK11 工具链初始化时失败；补显式 Java17 后复用同一批 APK，在 `43129460879` 完成验证，没有用不同 APK 替换测试。
- `43132129826` 尚在并发队列时取消，改成先单独构建、再复用产物验证；没有运行或计时样本。

未重新测旧 Fcitx、Foxy、正式 Release、真机或长期个人大词库。每进程只选 6 次，不用于判断第 8/16 次周期。JNI 主机 sanitizer 的覆盖缺口、首次 native CPU 细分与真实手机上的分发等待仍未解决；架构拆分建议见[后端审查核对](../../history/direct-rime-backend-review-2026-11-12.md)。

## 原始证据

| 轮次 | 真实采集 ZIP | 组件回归 | 派生表 / 目标 JVM |
| --- | --- | --- | --- |
| 第一轮 | [正序](./iteration-1/before-after.zip)、[反序](./iteration-1/after-before.zip) | [设备](./iteration-1/device-tests.zip) | [派生表](./iteration-1/comparison.json)、[JUnit](./iteration-1/unit-results.zip) |
| 第二轮 | [正序](./iteration-2/before-after.zip)、[反序](./iteration-2/after-before.zip) | [设备](./iteration-2/device-tests.zip) | [派生表](./iteration-2/comparison.json)、[JUnit](./iteration-2/unit-results.zip) |
| 最终轮 | [正序](./iteration-3/before-after.zip)、[反序](./iteration-3/after-before.zip) | [设备](./iteration-3/device-tests.zip) | [派生表](./iteration-3/comparison.json)、[JUnit](./iteration-3/unit-results.zip) |

各轮 `products.json`、`build-validation.json`、`native-link-audit.json` 保存来源、APK 摘要、探针摘要和审计；原始 ZIP 已包含 trace，不重复提交展开文件。全目录另有 [SHA256 清单](./checksums.sha256.txt)，不提交 APK、模型、ELF 或视频。

当前保留的通用离线工具是 `tools/input-perf/associate-events.py`，旧实验完整驱动已归入分支源码归档。采集失败与异常时间戳必须使工具退出失败，仍保存可复核输出。本次没有合并默认分支或发布 Release。
