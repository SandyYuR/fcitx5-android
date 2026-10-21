# 用户日志补充：输入会话重绑与卡顿定位

本页补充[旧原型与冷启动审查](DIRECT_RIME_PROTO_PERFORMANCE_REVIEW_2026-10-18.md)。
本次只分析用户上传日志和固定版本源码，未修改实现、未构建诊断包、未新增性能测试。
用户随后授权的实现、补充复现条件与验证见[响应性改进](DIRECT_RIME_RESPONSIVENESS_2026-10-18.md)；
本页保留修改前的证据范围。

## 可以得出的结论

**这份日志加强了“重新绑定时重复初始化值得优先检查”的判断，但还不能确定卡顿根因。**
它不是完整的进程冷启动记录，不能用来证明首键被忽略、LTS 首次加载慢、模型缺页或某次 GC 卡顿。
能够确认的是：同一进程内频繁发生输入会话切换，其中多处在约半秒后才出现候选状态日志。
这些是需要分段采样的线索，不是 librime 耗时测量。

## 日志来源与版本

| 项目 | 记录 |
| --- | --- |
| 原文件 | `io.github.sandyyur.rime.direct-2026-10-17T13_27_09Z.txt` |
| 文件 SHA-256 | `9c80abe7603de2f88436c494bef0a89ca1cc3e6a0e96975ed597a960043c7951` |
| 大小／行数 | 73,183 字节，504 行，其中 483 行带 logcat 时间戳 |
| 手机 | OnePlus PJD110，系统构建 `PJD110_16.0.10.501(CN01)` |
| Android API | 36（Android 16），屏幕 1440×3168，density 3.5 |
| 应用包名／类型 | `io.github.sandyyur.rime.direct`，Debug |
| 头部 Build Git Hash | `fb8357c02a699e275edc388b637af37b091f8bb9` |
| 头部构建时间 | `2026-10-17T06:01:39Z` |
| 日志窗口 | `10-17 21:26:48.941`–`21:27:10.062`，约 21.121 秒 |
| PID | 所有带时间戳的行均为 26267 |

时间按客户端 UTC+08:00 理解，与文件名的 UTC 时间相符；logcat 行本身没有时区字段。
原日志保留在本会话附件中；仓库保存[结构化摘要和必要摘录](direct-rime-user-log-analysis-2026-10-18.json)，
可用原文件的行号与 SHA-256 核对。

### 日志构建与当前分支的关系

`fb8357c0` 是将 `4456c110` 合入 `cecea494` 的 PR 临时合并提交，
不是此前审查固定的 `e8b1282e`。版本名里出现 `cecea494`，不应据此误认成含 Fcitx 的默认版。

固定源码对照结果：

| 文件／路径 | `fb8357c0` 对比 `e8b1282e` |
| --- | --- |
| `FcitxInputMethodService.kt` | Git blob 完全相同 |
| `direct-rime/src/main/cpp/engine.cpp` | Git blob 完全相同 |
| `DirectRimeBackend.kt` | 后者新增模式／方案的短暂提示；原生会话创建、接收门控等相关路径未改变 |
| `FcitxDaemon.kt` | 差异是重启通知文案资源，捕获会话和连接管理路径未改变 |

因此相关代码风险仍适用于当前实现，不能把“装了较早构建”直接当成卡顿解释，
也不能声称升级现有提示版本就已修复这一问题。
以上是源码对照，日志没有提供实际 APK 哈希或模型映射，不能替代二进制核验。

源码依据：[日志版本的输入服务][log-service]、[原生引擎][log-engine]、[后端][log-backend]。

## 片段中实际发生的事情

- `service.onStartInput restarting=false` 出现 **11 次**，`onStartInputView` 出现 3 次。
- 11 次中有 **10 次**记录 `initialSel=[-1,-1]`，另一次为 `[0,0]`。
- 片段后半段反复在本应用与 `com.android.documentsui` 之间绑定／解绑，
  同时可见 `MainActivity`、`LogActivity` 和窗口创建／销毁记录。
- 没有记录到服务 `onCreate`、输入视图首次创建、引擎初始化开始/结束或 Ready 时间线。
- 没有逐键关联 ID、会话代次、native 分段时间或真实画面呈现时间。

日志标签叫 `FcitxColdStart`，但其中的 `service.onStartInput` 只表示输入会话回调，
**不等于进程冷启动或引擎重新启动**。同一 PID 也不排除进程内的会话重建。

### 回调与候选状态日志的邻接间隔

下表规则是：从每次 `onStartInput` 向后找下一次同名回调之前，第一条包含完整单词
`CandidatesUpdated` 的主线程 `EventStateMachine` 日志；不把 `ExpandedCandidatesUpdated` 算进去。
这只是时间邻接，不确认该状态来自当前回调，也不表示候选已经在屏幕呈现。

| 原文件行号 | onStartInput 时刻 | 初始选择 | 首条候选状态日志行号／时刻 | 相邻日志间隔 |
| --- | --- | --- | --- | --- |
| 40 | 21:26:52.604 | [-1,-1] | 61／21:26:53.510 | 906ms |
| 67 | 21:26:55.173 | [-1,-1] | 下一回调前没有 | 不计算 |
| 70 | 21:26:55.236 | [0,0] | 100／21:26:56.425 | 1189ms |
| 187 | 21:26:59.509 | [-1,-1] | 215／21:27:00.191 | 682ms |
| 260 | 21:27:01.222 | [-1,-1] | 273／21:27:01.768 | 546ms |
| 319 | 21:27:02.192 | [-1,-1] | 336／21:27:02.785 | 593ms |
| 361 | 21:27:04.389 | [-1,-1] | 369／21:27:04.927 | 538ms |
| 398 | 21:27:05.462 | [-1,-1] | 400／21:27:06.156 | 694ms |
| 413 | 21:27:07.095 | [-1,-1] | 430／21:27:07.675 | 580ms |
| 459 | 21:27:07.965 | [-1,-1] | 461／21:27:08.620 | 655ms |
| 474 | 21:27:09.402 | [-1,-1] | 488／21:27:09.911 | 509ms |

最早两个样本可能夹有用户等待、按键或其他操作；后面也夹有窗口动画、生命周期和调度。
**不能把表内 509–1189ms 写成引擎耗时、首键延迟或一组性能分位数。**
日志中厂商的 `InputEventInfo` 没有键位/按键关联 ID，不能拿它与候选日志强行一一配对。

## 这使哪个代码嫌疑更值得先查

### 1. 不需要文本输入的上下文是否仍在构造 Rime 会话

当前及日志版本的输入服务都会先计算 `isNullType`，但仍无条件调用：

```text
startInputSession → Native.begin → destroy_session / create_session
setCapFlags
仅 focus(true) 受 !isNullType 条件限制
```

`isTypeNull()` 的实际定义是 `inputType == TYPE_NULL && imeOptions == IME_NULL`。
仅凭日志中的 `initialSel=[-1,-1]` **不能证明**它满足该条件；日志缺少 inputType/imeOptions。
不过大量这种回调与文件选择器往返一起出现，使这条路径成为值得优先采样的对象。

如果确认没有文本编辑需求却重建了完整引擎，可考虑只更新逻辑绑定，
到真正需要文本输入时再确保原生会话可用。必须保留 TYPE_NULL 应用、终端和硬件键的正常转发，
不能把所有 TYPE_NULL 上下文简单禁用。

### 2. 频繁重绑是否在做无效或重复初始化

21:26:55.173 与 55.236 两次 `onStartInput` 仅隔 **63ms**。
现有代次过滤可能跳过尚未执行的旧任务，因此不能把 11 次回调等同于 11 次 native 创建。
但已经进入 native 的旧初始化不因稍后的代次变化自动变成零成本。
应记录实际 create/destroy 次数、每次代次及耗时，再验证能否安全复用。

旧原型的会话复用思想仍值得借鉴；这次日志将检查重点从泛泛的模型性能，
进一步指向“主进程已经活着时，重新绑定与首次编辑需要多久”。

### 3. 要把等待发生在哪一层拆开

从回调到候选状态日志之间可能包含：服务串行队列、owner 队列、会话/方案初始化、
首键查询、快照构造、事件 Channel/SharedFlow、主线程消费及 UI 状态更新。
这份 logcat 没有将它们分开。
建议的下一份诊断首先覆盖这些阶段及实际输入上下文，不先更换模型或合并多项优化。

同时，片段包含实时日志页与文档选择器交互；日志页逐条把文本加入 RecyclerView，
会占用主线程。需要外部捕获来减少操作日志页本身对场景的干扰，
不能把导出日志时的窗口活动当作日常打字的纯引擎基准。
导出文件名在点击菜单时生成，实际日志内容是在选定保存位置、回到 CreateDocument 回调后写出；
选择保存位置期间的输入绑定活动也可能进入文件，文件名并不表示冷启动采集起点。

## 错误与运行时提示怎么解读

本片段有 9 条 E 级日志：6 条 `OplusPredictiveBackController: NoSuchMethodException`、
3 条 `OplusBracketLog` 的 ViewMirror 不支持提示，均来自厂商框架标签。
没有看到 `FATAL EXCEPTION`、ANR 标记或 Rime 启动失败堆栈。
这不证明完全没有卡顿，也不足以把厂商报错判为根因或要求修改 librime。

5 条 `HeapTask is not gc_task` 没有 GC 暂停时长，不能据此断言发生了长时间 GC。
同样，没有 `Skipped ... frames` 不代表每一帧都正常；本文件没有可用于统计掉帧的呈现数据。

日志也没有给出万象版本、实际方案开关、`.gram` 路径、模型 mmap、缺页或 I/O 时长。
因此不能从它判定 Muse/LTS 的优劣，也不能确认内部/外部存储是这次卡顿的原因。

## 下一步采集与实施边界

实现仍保持不变。后续若用户确认进入修改阶段，建议先完成以下最小诊断：

1. 在当前真机记录每次绑定的 inputType/imeOptions、是否可输入、逻辑代次，
   以及 native session 创建/销毁的开始、结束和次数。
2. 用同一 ID 串起首键到达、接收/拒绝、owner 执行、publish、emit、UI 消费，
   再配合系统 trace 判断主线程、缺页或 GC；不记录完整输入内容。
3. 分别复现首次唤起、同输入框重新启动、切换输入框和文件选择器往返，
   不把它们混成一个“冷启动”样本。

仅补完整 logcat 时，可在 Windows PowerShell 的一个终端先运行：

```powershell
adb logcat -T 1 -v threadtime -b main -b system -b crash > .\rime-start-and-input.txt
```

保持采集，在手机按平时方式复现，并记下卡顿大致时刻，结束后 Ctrl+C。
如要复现进程冷启动，采集必须在进程重新启动之前开始；不要在采集起点固定旧 PID。
这条命令只是记录，不会新增应用尚未输出的分段时间；完整归因仍需要上述诊断或系统 trace。

本次更新的是证据范围与排查优先级，**没有把任一嫌疑标为已定位根因或已修复**。

[log-service]: https://github.com/SandyYuR/fcitx5-android/blob/fb8357c02a699e275edc388b637af37b091f8bb9/app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt#L1751
[log-engine]: https://github.com/SandyYuR/fcitx5-android/blob/fb8357c02a699e275edc388b637af37b091f8bb9/direct-rime/src/main/cpp/engine.cpp#L100
[log-backend]: https://github.com/SandyYuR/fcitx5-android/blob/fb8357c02a699e275edc388b637af37b091f8bb9/app/src/main/java/org/fcitx/fcitx5/android/core/DirectRimeBackend.kt#L126
