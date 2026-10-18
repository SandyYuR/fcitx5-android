# 首键接收、会话复用与首次选词

本次是在用户授权后实施的修改，承接[旧原型审查](DIRECT_RIME_PROTO_PERFORMANCE_REVIEW_2026-10-18.md)
与[真机日志分析](DIRECT_RIME_USER_LOG_REVIEW_2026-10-18.md)。此前两份文档是修改前的审查记录。
对照版本固定为修改前直连分支 `2f6a0ea08527831d3f07df12499f5b72d5ffc0bd`；
含 Fcitx 的默认分支对照仍见[此前万象报告](DIRECT_RIME_SCREEN_PERF_2026-10-17.md)。

## 用户补充与日志结论

用户补充的复现场景是：唤起后立即打字，编码和候选不跟手；第一次候选点选也慢，
重复两次后恢复。它同时涉及绑定、首次查询和选词，不能只测 `create_session`。

原日志来自已经运行的同一进程，有 11 次 `onStartInput`，大量文件选择器和本应用之间的重绑。
相邻回调与候选状态日志之间的 509–1189ms 不是逐键耗时；原记录没有阶段关联，
不能据此断言模型缺页、GC 或厂商 E 级日志就是根因。此次修正有源码依据的重复工作，
并补上新构建的阶段计时，没有把旧日志的嫌疑写成已经证明的因果。

## 本次实现

### 1. 按键到来时先登记，沿原队列顺序交付

输入服务在 `onStartInput` 中同步登记编辑器代次、应用、输入能力及是否需要 Rime，
再异步绑定原生会话。启动及绑定尚未完成时，软键和硬件键可取得该代次的 ticket，
进入现有服务顺序队列，等待引擎和编辑器都可用后处理。

没有新增第二条按键队列，也没有在主线程等待 native。启动不再创建临时 session，
也不再额外递增代次，使已经登记的首键失效。Ready 后重放当前 Android 绑定是幂等的。

等待绑定的按键最多 128 个事件、最长 5 秒；超过边界会记录拒绝/未交付日志，
不允许很久之前的输入突然写入。编辑器切换、解绑、停止及手动部署会立即作废旧 ticket。
手动部署/目录替换期间继续拦截新键，不在完成后回放。

### 2. 逻辑编辑器代次与原生 session 分开

同一个非空应用标识内，普通文本绑定可以复用一个仍有效的 native session。
每个新代次仍清理 composition、候选、输入标签和未交付 commit，重新设置 `client_app`
和 `_no_learning`。旧代次的按键、动作和输出不能交付新输入框。

失焦时清理并停用 session，保留其已加载组件；不再每次销毁、重建整套 Lua/翻译器。
跨应用、匿名应用、进入或离开敏感输入、session 失效、部署及引擎停止时重建。
敏感边界保守重建，因为 `clear_composition` 不能清除任意 Lua 组件的私有运行状态。

普通文本复用会保留同应用内方案开关、组件缓存和方案自身的上下文状态；
这不是完整重置 Lua 虚拟机。万象上下文调频的已有学习记录仍按方案管理。
输入完成后最多保留一个 session，可能比立即销毁占用更多驻留内存；停止引擎会释放它。

### 3. 无编辑需求的回调不提前创建 session

严格的 `TYPE_NULL + IME_NULL` 静默回调只更新逻辑绑定、清空界面。
显式显示键盘或收到真实硬件字符时再启用 Rime；首键在启用前登记，保留终端输入路径。
不使用 `initialSel=[-1,-1]` 猜测是否为非编辑器。

另外，绑定时已设置目标学习标志，随后相同的 `setCapFlags` 不再重复进入 native、
读取一次完整快照。输入方案、LTS 模型、数据目录和万象配置不作性能性改写。

## 新诊断怎么读

| 标签/trace | 内容与边界 |
| --- | --- |
| `RimeLifecycle startup` | 资产、配置、native 启动各阶段毫秒数 |
| `RimeLifecycle input_binding` | 代次、inputType、imeOptions、是否需要 Rime、restarting |
| `RimeLifecycle bind` | 绑定耗时、实际新增/复用 session 次数、学习标志 |
| `RimeLifecycle binding_inactive` | 非编辑绑定或失焦，未创建新 native session |
| `RimeInputQueue` | 等待绑定的键交付/拒绝及原因；不记录键值 |
| `RimeInputTiming key` | 每次绑定前 3 个键及耗时较长的键：owner 等待、native、publish 微秒数 |
| `RimeInputTiming select` | 每次绑定前 2 次选词：owner 等待、native + publish 微秒数 |
| `RimeInputTiming select_rejected` | 会话/快照已变更，旧动作未执行 |
| `rime.direct.*` trace | initialize、maintenance、create/reuse、process_key、snapshot、select_candidate |

这些数字不包含实际帧呈现。服务队列等待与 owner 等待也应区分：`owner_wait_us`
从调用后端时起算，不包括更早的服务排队；等待绑定的 ticket 另有 `wait_ms`。
没有记录完整输入内容。可继续用维护手册中的 `inputDiagnostics` 与系统 trace 排查 UI 消费、
主线程或缺页；不能从 native 快直接推出打字画面已经流畅。

## 验证

- 原生契约测试覆盖复用、跨应用/匿名/敏感边界、旧代次拒绝、失焦后上下键拦截、
  清除未交付 commit/标签、部署及停止后的重建，保留 ABI 与线程所有权检查。
- JVM 的 `InputKeyGateTest` 覆盖启动首键、绑定尚未完成、跨编辑器、超时、容量释放、
  部署拦截、启动失败和非编辑器拒绝。
- 完整前端新增非编辑器转为实际输入的回归，以及六轮重绑后立即触摸 `n`、`i`、候选“你”。
  每轮要求首键保留、候选一次点击上屏、无重复 commit。
- `Rime immediate input and rebind comparison` 使用同一界面测试分别构建固定修改前版本与当前版本，
  只覆盖测试源码，不改对照产品代码。使用 v18.1.3 base、固定 LTS 哈希和用户 YAML；
  与上一轮一样显式补入用户确认开启的 `context_reorder`。模型必须实际 mmap。

界面测试用第一次显示及后续 `restartInput`，只等待 Android 绑定回调，不等待 native ready、
不输入预热词。结果是注入触摸到观察到状态/编辑器的耗时，包含注入和轮询开销，
不是屏幕光子延迟。fixture 准备已启动引擎，因此也不是杀进程后的冷启动测试。
每个版本只有六轮，单个模拟器、固定先后顺序，不作稳定分位数或真机流畅度保证。

本地原生 ASan/UBSan 检查通过；当前受限执行环境不支持 LeakSanitizer 的 ptrace 扫描，
本地关闭 leak 扫描，CI 仍运行原有完整 sanitizer 命令。构建与设备结果在完成后追加。

## 尚未宣称解决的部分

真正进程冷启动仍需初始化和维护检查，跨应用或敏感边界仍会创建组件。
首个模型查询、Lua 首次执行和模型页面冷缓存仍可能较慢；本次没有靠模拟输入、
偷偷学习或更换模型预热。需要结合新阶段日志和相同手机复测，决定是否继续处理 I/O、
主线程 UI 或方案内部耗时，不能将这次会话复用称作已经消除所有冷启动卡顿。
