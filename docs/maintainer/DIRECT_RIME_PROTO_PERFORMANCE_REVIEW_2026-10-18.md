# 早期直连原型的可借鉴点与冷启动审查

本报告只审查代码、提出验证与改进顺序，**未修改实现，未运行新的性能 A/B，未构建 APK**。
用户提到的日志
`D:\GitHub\fx2-rime\日志\io.github.sandyyur.rime.direct-2026-10-17T13_27_09Z.txt`
原为 Windows 本机路径，随后已收到附件并完成[日志补充分析](DIRECT_RIME_USER_LOG_REVIEW_2026-10-18.md)。
日志只覆盖已运行进程约 21 秒，没有录到进程冷启动，因此不能据此确定冷启动卡顿根因。

## 结论

**有值得借鉴的地方，首先是减少原生会话重建，其次是评估内部存储路径。**
但旧版代码不足以证明它整体更快，也没有找到专门针对 Muse 的预读或模型缓存优化。
当前版本的冷启动接收按键、重复初始化、逐键快照和事件积压均有需要进一步验证的地方。
这些不因“已经移除 Fcitx Core”而自动消失。

| 优先级 | 事项 | 已知事实与判断 |
| --- | --- | --- |
| P0 | 首键接收与 Ready 的定义 | STARTING 时捕获的引擎按键会被忽略；需先区分没有处理与处理很慢 |
| P1 | 逻辑输入会话与原生会话分离 | 当前每次有效 `begin` 都销毁、重建原生 session；旧版更多地复用 |
| P1 | 启动维护及临时 session | 当前启动等待维护完成，还可能先创建启动 session、再为真实输入框重建 |
| P1 | 内部目录与外部目录 I/O | 旧版默认内部设备保护目录；当前默认应用外部文件目录，可做同代码对照 |
| P2 | 按需快照与 UI 积压 | 当前逐键读取 tabs；无界事件队列可能积累过期展示状态，尚无该次运行证据 |
| P2 | 退出及重新唤起 | `stop()` 会同步等待 owner 和 native finalize，可能阻塞调用线程 |

上述 P0/P1 是检查顺序，不是已经测出的耗时排序。

## 比较范围和证据

| 对象 | 固定版本 |
| --- | --- |
| 用户指定的早期分支 `direct-rime-proto` | `aa9ec5edd13615b9af493add6cb3b67671cee842` |
| 当前分支 `feat/direct-rime-prototype` | `e8b1282eaff480ac429d6270e27f735af198ec13` |
| 两版共同的 prebuilt | `1126616c3ec569617da772e30d7a97f6b2694172` |
| prebuilt 记录的官方 librime 源码 | `ef1a16aa2c962bccf347b116443331f39d56e037`，另有 prebuilder 补丁 |

当前主应用走 `DirectRimeBackend → Native → Engine`。
`direct-rime` 最小诊断应用中的 `RimeRuntime` 是另一条入口，不能把其启动 trace 当成主应用已具备的计时。
审查没有切换默认分支，也没有将旧原型合并进来。

以下是固定源码的永久链接，表内的源文件简称均指向这些版本：

- 旧版：[DirectRimeEngine][old-engine]、[RimeThreadOwner][old-owner]、[rime_bridge.c][old-native]。
- 当前：[DirectRimeBackend][backend]、[Engine][engine]、[JNI bridge][bridge]、[输入服务][service]、[Daemon][daemon]、[配置目录][config]。
- 上游：[Service / Session][rime-service]、[Engine 组件构造][rime-engine]；[prebuilder 补丁清单][rules]说明实际二进制还含定制补丁。

### Muse 和 LTS 不能混成一次代码对照

用户在旧版使用 Muse、在当前版使用万象 base + LTS，是两项同时变化的条件。
源码里的旧默认部署检查仍写着 `luna_pinyin`，仓库没有固定那次 Muse 的模型哈希与有效用户配置。
这不否认用户实际使用了 Muse，而是说明不能从源码确认当次运行加载的具体模型。

两版 prebuilt 相同，已有的引擎缓存补丁并不是旧分支独有优势。
模型大小、文件格式、Lua/滤镜设置、用户词库、存储路径和冷热缓存都可能影响长尾延迟；
不能仅凭模型名称断言 Muse 更小或更快。

## 1. 首先排查首键是否根本没有进入引擎

当前的链路是：

```text
普通字母键 → postFcitxKeyJob
  → 当场 captureInputSession：只有 acceptsInput 才返回代次
  → jobs 等待引擎 Ready
  → 若当场捕获为 null，之后仍不执行 sendKey
```

代码依据：输入服务 506–511 行、Daemon 55 行、Backend 54 行。
`acceptsInput = isReady && !maintenance`，因此 STARTING 时按下的普通引擎键不会在启动后补发。
键盘 `KeyView` 默认可交互，`BaseKeyboard` 根据键的动作配置设置 enabled；
审查所见路径没有以引擎 Ready 统一禁止这些按键。
**如果键盘先显示、启动尚未完成，存在首键被忽略的可达路径。**
这与“按键排队后晚一点显示”是不同问题；是否发生在用户这份日志对应的操作中，尚不能确认。

还有两个需要一起处理的状态问题：

- Backend 在启动的 `finally` 中发布 READY，即使启动抛异常；`acceptsInput` 没有同时核对
  `started`、原生快照是否 ready、快照代次是否等于当前输入代次。
  旧版 `isReady` 至少同时要求 `session != 0`，这种“实际可用才算就绪”的思想值得保留。
- 启动完成时会自增 epoch；Android 的 `onStartInput` 也会自增 epoch。
  若 Android 先捕获代次、native 启动随后再次自增，排队的 `startInputSession` 会因代次不符返回。
  是否出现取决于调度，需验证真实编辑器绑定、`client_app` 和早期按键的时间线。

建议先补齐冷启动状态设计与验收：区分启动、可绑定、可输入、维护失败；
初始化期间的同一输入框按键采用明确策略和可观测计数。
如设计有界等待队列，只允许当前有效编辑器、有限时长与数量，切换输入框或销毁立即作废；
部署期间的按键不应被顺带改成部署完成后重放。
不要用绕过代次检查或把全部状态强行标成 Ready 来消除等待。

## 2. 最值得借鉴：保留温热原生会话，继续隔离逻辑输入会话

旧版 `activate` / `focus` 只有在 `session == 0` 时创建，失焦主要更新逻辑代次。
当前 `onStartInput → startInputSession → Native.begin` 每次有效执行都会进入：

```text
destroy_session(old)
→ get_schema_list
→ create_session
→ set_property(client_app)
→ snapshot
→ 可能恢复 schema / ascii
```

依据：旧 Engine 481–500 行；当前输入服务 1751–1774 行、Backend 117–133 行、原生 Engine 100–109 行。
`restarting=true` 还先执行 `focus(false)`，失焦/解绑也可能销毁原生会话。

上游并不是只为 `create_session` 分配一个轻量 ID：
`Service::CreateSession → Session::Session → Engine::Create → ConcreteEngine::InitializeComponents`。
重新应用方案也会重建 processors、translators、filters 等组件。
对含 Lua、调频和模型的万象，减少这种初始化有合理的性能价值。
**但底层资源可能被 librime 或项目缓存复用，不能据此声称每次都完整重读整个 LTS 文件。**

建议评估将 Android 编辑器的逻辑代次和可复用的原生 session 分离，
先覆盖同一编辑器的 `restartInput` 与短暂隐藏/再显示，再考虑跨应用复用。
必须保留：

- 旧结果和候选点击失效检查、commit 只交付一次。
- 清空 composition、tabs、高亮与未消费提交，防止前一输入框的内容进入后一输入框。
- 更新 `client_app`，重新应用敏感输入的 `_no_learning`、ASCII/方案及相关会话状态。
- 部署、目录切换、备份恢复、失败恢复时强制重建的能力。

旧版 `activate/deactivate` 本身没有完整做这些清理，**不建议直接复制其长寿命 session 实现**。

## 3. 冷启动维护与初始化重复

当前 Backend 的启动顺序为：

```text
owner: DataManager.sync → config.load
     → Native.start(initialize + start_maintenance(false) + join)
     → schemaEntries → begin(启动 session) → READY
随后真实编辑器绑定可能再 begin
```

维护是在 owner 上等待，通常不会直接阻塞 Android 主线程，但会延后引擎可输入的时刻。
启动先创建 session、真实绑定后再创建的情况可能重复构造组件，需以时间线确认，不能假定每次必定发生。

旧版只在 `build/luna_pinyin.schema.yaml` 不存在时触发启动维护，
它提示了“已部署且没有变化时走快速路径”的方向；但这个硬编码检查不适用于万象，
也不能发现用户配置、字典、插件或版本更新，不能照搬。

`start_maintenance(false)` 也不等于每次全量部署，实际 librime 已有快速检查及指纹补丁。
应先分别测量资产同步、配置读取、维护检查、session 构造和首键，
再决定是否需要上层的有效性快速判断；首次安装、配置变化和部署失败恢复仍应完整维护。
不能简单删掉 `join`，否则可能让输入与部署并发。

当前没有明确的模型预读流程；文件 mmap 成功不等于所有页已读入物理内存。
进程启动、首次查询和首次上下文调频都可能产生额外开销，但没有对应的启动分段日志/trace 不能归因到某一个。
优先减少重复构造，再考虑有证据支持的按需预取；不要向真实用户词库注入预热词，
也不建议默认把整个大模型强制常驻内存。

## 4. 旧版内部存储路径值得做单变量验证

旧版 `user_data_dir = DataManager.dataDir/rime`，通常在应用内部的设备保护存储。
当前 `DirectRimeConfig.root` 优先使用 `getExternalFilesDir(null)`，
默认用户目录在应用外部文件目录下，也允许注册其他目录。

Android 上这类目录经常仍在手机内置闪存上，不能称作“必然在 SD 卡上”。
但路径可能经过不同的 emulated/FUSE 实现，若用户配置了真正的外置介质，差异还会更大。
词典文件打开、mmap 缺页、LevelDB 写入的延迟值得验证，不能仅凭路径断言一定更慢。

建议先用当前代码、同一部手机、相同模型和配置，对两个专用测试目录做内部/当前路径对照。
先核实用户实际路径与剩余空间，不改动日常档案。
若确有收益，再设计内部运行目录与导入/备份目录的关系，包括存储占用、更新检测及备份兼容。
这比立即修改模型算法更容易隔离变量。

## 5. 逐键快照、事件与界面：可以优化，但不要退回旧版做法

### 按需补充快照

两版都把按键处理后的 commit、context、status 批量返回，当前已具备这一优势。
旧版常规 `pack_state` 不获取 tabs；当前 `Engine::snapshot` 每次调用 `get_input_tabs` 并转换标签，
JNI 再构造 typed Snapshot；Backend 将其转换成面板事件。
可以评估仅在输入段/光标/相关选项变化时更新 tabs，或为额外展示元数据提供版本缓存。
这只是候选方向；旧版没有同等完整的 tabs 展示，不能把省掉功能算作等价提速。

当前 `bridge.cpp` 每次查找 Snapshot class/constructor，也可以评估缓存 JNI 标识。
但旧版对多个整数槽反复查找 `Integer.valueOf`、装箱，并没有天然更便宜。
JNI 标识缓存应排在会话重建、模型 I/O 等主要嫌疑之后。

### 展示状态积压

当前 owner 通过无界 Channel 发出批次，再经 SharedFlow 给 UI。
优点是不会因为 UI 慢就按 `DROP_OLDEST` 丢掉 commit；
但输入比 UI 消费快时，展示事件可能排队。
同代次内旧候选和预编辑可能已经过时，单靠输入代次过滤不会丢弃它们。

可评估把“必须按序且只交付一次”的 commit/转发键与“可以合并”的展示状态分开，
在保持先后关系、候选 revision 和点击有效性的条件下，每帧只应用最新展示状态。
**不能直接把全部事件改成 DROP_OLDEST，也不能用取消整个处理协程的方式丢掉提交。**

已有优化不应重复做：当前 Backend 缓存选项并去重部分事件；
候选组件对相同数据早退，adapter 已做内容比较及局部更新。
`value.handled` 时仍主动发候选事件还承担展开候选尾部失效的职责，
不能只因首屏相同就删除它；应分离“尾部结果失效通知”和“首屏重新绘制”。

### 退出与主线程

当前 `Daemon.disconnect` 在连接数为零时调用 `stop()`；Backend 的 stop 使用 `runBlocking(owner)`，
会等待原生会话销毁与 finalize。若从服务销毁等主线程路径进入，存在阻塞主线程的可能。
短时间再次唤起还可能赶上上一次的退出。
旧版 stop 同样阻塞，还会关闭 owner executor，不能作为这部分的改进模板。
后续可评估异步退出与短暂保温，但备份/恢复等要求数据完全停止的场景仍需明确屏障。

主线程上的键盘创建、字体/主题、候选布局和剪贴板同步服务启动也需要看 trace。
不能看到 Choreographer 的 skipped frames 就认定是 Rime 模型在主线程运行；
当前 Native.start 的正常入口是在 owner。

## 6. 如何把代码嫌疑落实到用户这次卡顿

已收到首份日志的证据与版本核对见下文第 8 节；完整定位仍需核对 APK 提交/ABI、机型、Android 版本、实际档案路径、
引擎与服务是否重启，以及现象究竟是输入延迟、画面掉帧，还是首字未处理。
不会只凭文件名认定它就是当前固定提交。

已有 `FcitxColdStart` 记录服务/视图创建，但当前异步启动意味着
`service.onCreate duration` 不包含引擎完整启动时间。
旧原型同步启动日志中的某个毫秒值也不能与它直接相比。

建议的分段观测：

| 阶段 | 要记录的证据 |
| --- | --- |
| 启动 | sync、config、initialize、maintenance/join、create_session、schema、options、可输入时刻 |
| 首键 | 到达、当场就绪/代次、服务队列等待、owner 队列等待、处理/拒绝原因 |
| 引擎 | 已有 process_key / snapshot trace，另区分 tabs、状态配置读取 |
| 交付 | publish 入队、实际 emit、UI 收到、编辑器 commit、画面呈现 |
| 系统 | 主线程长任务、GC、缺页/存储等待、调度与其他服务活动 |

现有 InputDiagnostics 在 emitter 中才记时间戳，不能反映事件在前面的无界 Channel 中等了多久；
它也没有覆盖服务队列与 owner 队列等待。计数间隙不能直接当作用户丢字。
日志只需阶段、代次、长度和耗时，无需逐键输出完整输入内容。

## 7. 建议后续实施顺序与验收

1. **先补冷启动观测和首键状态验证。** 在可重复场景确认 Ready 前按键策略及代次绑定，
   不先修改模型或同时改多个热点。
2. **再减少会话重建。** 优先同一输入框 restart/隐藏再显示，保留旧结果隔离与清理规则，
   核验中英文状态、敏感输入、跨应用、快速切焦点及候选点击。
3. **对存储路径和启动重复工作做单变量测试。** 证实后才改变运行数据布局或维护策略。
4. **有逐键证据后再改快照/事件展示合并。** commit 和硬件键配对必须完整；
   验证展开候选、tabs、方案/模式提示以及变更时旧候选失效。

测试应区分：首次安装/部署、已部署后的进程冷启动、同进程重新绑定、
同一编辑器 restartInput、切换编辑器、首次长词/调频查询及持续输入。
force-stop 不会可靠清除系统页缓存，不能把它叫作完全冷盘测试。
首轮可在当前版本同一手机上采集多次原始时间线，再确定要改哪个环节；
样本较少时报告中位数、P95 和最大值，不用少量冷启动样本包装稳定的 P99 收益。

如后续确需量化旧版优势，应在功能可比前提下固定同一方案、模型哈希、用户配置、词库、
手机、构建类型、存储路径与冷热条件，先做旧/新代码配同一模型；
Muse/LTS 切换另做独立实验。当前没有这样的测量，故本报告不报提速百分比。

前一份[触摸报告](DIRECT_RIME_SCREEN_PERF_2026-10-17.md)在部署、预热、清空编辑器后才开始计时，
不覆盖用户所说的冷启动首键；快速计划还没有达到 120ms 实际节奏。
其结果不能用来否定这次卡顿感受，也不能代替本报告提出的冷启动验证。

## 8. 已收到的用户日志补充

[日志分析与时间线](DIRECT_RIME_USER_LOG_REVIEW_2026-10-18.md)及
[结构化证据摘要](direct-rime-user-log-analysis-2026-10-18.json)已归档。
日志来自 `fb8357c0` Debug 合并构建；其输入服务和原生 Engine 与本报告审查的当前版本完全相同。
约 21 秒内有 11 次 `onStartInput`，10 次初始选择为 `[-1,-1]`，多次涉及系统文档选择器往返。
这些使“有效编辑器之外或短暂重绑时是否重复初始化”更值得优先验证。
相邻候选状态日志间隔不能当作 native 耗时；首键忽略、模型缺页等仍是未被这份片段证实的代码/运行时嫌疑。

**交付范围：仅报告、索引与日志证据文档。实现保持原样；卡顿根因仍需分段采样验证。**

[old-engine]: https://github.com/SandyYuR/fcitx5-android/blob/aa9ec5edd13615b9af493add6cb3b67671cee842/app/src/main/java/org/fxrime/direct/DirectRimeEngine.kt
[old-owner]: https://github.com/SandyYuR/fcitx5-android/blob/aa9ec5edd13615b9af493add6cb3b67671cee842/app/src/main/java/org/fxrime/direct/RimeThreadOwner.kt
[old-native]: https://github.com/SandyYuR/fcitx5-android/blob/aa9ec5edd13615b9af493add6cb3b67671cee842/native/rime_bridge.c
[backend]: https://github.com/SandyYuR/fcitx5-android/blob/e8b1282eaff480ac429d6270e27f735af198ec13/app/src/main/java/org/fcitx/fcitx5/android/core/DirectRimeBackend.kt
[engine]: https://github.com/SandyYuR/fcitx5-android/blob/e8b1282eaff480ac429d6270e27f735af198ec13/direct-rime/src/main/cpp/engine.cpp
[bridge]: https://github.com/SandyYuR/fcitx5-android/blob/e8b1282eaff480ac429d6270e27f735af198ec13/direct-rime/src/main/cpp/bridge.cpp
[service]: https://github.com/SandyYuR/fcitx5-android/blob/e8b1282eaff480ac429d6270e27f735af198ec13/app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt
[daemon]: https://github.com/SandyYuR/fcitx5-android/blob/e8b1282eaff480ac429d6270e27f735af198ec13/app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt
[config]: https://github.com/SandyYuR/fcitx5-android/blob/e8b1282eaff480ac429d6270e27f735af198ec13/app/src/main/java/org/fcitx/fcitx5/android/core/DirectRimeConfig.kt
[rime-service]: https://github.com/rime/librime/blob/ef1a16aa2c962bccf347b116443331f39d56e037/src/rime/service.cc
[rime-engine]: https://github.com/rime/librime/blob/ef1a16aa2c962bccf347b116443331f39d56e037/src/rime/engine.cc
[rules]: https://github.com/SandyYuR/prebuilder/blob/4121ce63125bb73b5802199dea92dbb5101e8b36/src/Rules/LibRime.hs
