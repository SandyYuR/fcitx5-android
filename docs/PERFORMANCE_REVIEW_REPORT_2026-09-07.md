# fx-rime-only 性能专项审阅报告

审阅基线：SandyYuR/fcitx5-android，分支 `fx-rime-only`，提交 `e51afd887fb623e4b8fa1baed554dc4e1ae2ef32`。日期：2026-09-07。

## 1. 执行摘要

本报告从综合代码审阅中单独抽出性能问题，并追加审阅输入热路径、候选 UI、JNI/native、启动、内存、Room/I/O、网络同步、后台功耗和构建产物。结论按“当前确认热点”“条件性热点”“测量后才能决定”“历史问题已修复”分开，避免把旧问题重复立项，也避免把正确性缺陷包装成优化。

当前最高优先级不是重写 UI 或批量 JNI，而是：

1. **剪贴板同步随 IME 焦点切换反复取消并重建 polling/SSE/WebSocket**，会造成额外 HTTP、鉴权、握手和首轮 pull。
2. **ClipCascade 健康检查误入不支持的 polling 路径**，健康连接也可能周期性触发失败、连接测试和 PBKDF2/多次 HTTP。
3. **截图 watcher 在熄屏后仍每 5 秒查询 MediaStore**；网络循环虽已停，截图轮询没有同步停止。
4. **语音 PCM 使用无界 Channel**，provider 变慢或 Binder 卡住时，内存按录音时长线性增长。
5. **IME 命令和 fcitx dispatcher 是两级无界串行队列**，慢 Rime、重启或停顿会放大尾延迟和堆积；键事件不能靠丢弃解决。
6. **展开候选存在 offset 重复刷新 + DiffUtil 全 false 的放大链**，可导致重复 page load、整页 rebind 和布局。
7. **浮动键盘缩放每个 MOVE 强制整树 layout**；自定义水波纹每键维持全键盘 saveLayer，分别是高频手势和条件性 GPU 热点。
8. **Release 仍在每个 native 事件进入时先构造 DEBUG 日志字符串**，是风险最低、最适合先验证的按键热路径优化。

近期代码已经修复了横向候选每键全量刷新、常态双布局、额外一至两帧 post 延迟、字体/图标主线程初始化、模糊每帧重复建模等旧热点。不要以旧文档描述重新发起这些项目。

## 2. 审阅方法与限制

- 静态复核当前 Kotlin/Java/C++/Gradle 源码，并交叉检查 `docs/rime-integration-plan.md` 与综合审阅报告。
- 重点跟踪触摸 ACTION_UP → IME 命令队列 → fcitx dispatcher → Rime/native callback → SharedFlow → 主线程候选 bind/layout/draw。
- 检查轮询间隔、连接生命周期、无界队列、整对象读取、全量 RecyclerView 刷新、布局/绘制、启动部署与发布优化配置。
- 未完成运行验证：本机无 `JAVA_HOME`/`java`，Gradle 在启动前失败；native gitlink 子模块未初始化；checkout 中也没有 APK/AAB/SO。因此本文不给出伪造的毫秒、mAh、APK 或 ELF 数字。
- “确认”表示代码路径静态成立；实际收益、触发频率分布和设备差异仍须用 Release 真机验证。

优先级含义：P1 = 应尽快测量并修复；P2 = 有明确成本但依赖场景或规模；P3 = 只有 profile 证明占比后再做。收益为相对预期，不是已测结果。

## 3. P1：当前确认的高价值性能问题

### P1-1 IME 焦点生命周期反复重建同步连接

**证据**：`FcitxInputMethodService.kt:1583-1589,1613-1620` 在 start-input/start-input-view 路径发起同步；`:1960-1963,1981-1984` 在两个 finish 路径停止。`MainService.kt:202-211` 在 quick-sync 开启时的“stop”仍会重新启动；`:405-413,524-533` 每次命令都会 refresh runtime；`:608-672` 先取消当前 polling/SSE/WebSocket，再创建新循环并立即 pull。OneClip 连接包含预请求和 SSE 建连，ClipCascade 包含登录、校验、用户信息和 WebSocket。

**影响**：普通 focus/finish 周期可能多次断开、重连、重新鉴权和拉取；网络、CPU、radio active time 与同步首响应都会抖动。事件后端受影响尤其明显。

**建议**：

- 把“服务是否启用/端点是否改变”和“IME 当前是否活跃”拆开。
- 焦点变化只更新 runtime cadence，不重建同一 endpoint/credential 的事件连接。
- 给连接配置建立稳定 fingerprint；只有 endpoint、backend、credential、网络 generation 或明确 reconnect 变化时重建。
- start/stop intent 幂等化；同一状态重复命令不触发 refresh。

**预期收益**：高，目标是一次 focus/unfocus 周期 0 次事件连接重建，任一 backend 始终不超过 1 条活动 SSE/WS。

**风险**：中。必须验证设置变更、Binder 重连、网络切换、进程恢复和手动 reconnect 仍可靠。

### P1-2 ClipCascade 健康检查进入不支持的 polling 分支

**证据**：`MainService.kt:547-600` 的事件后端健康循环会在 fallback 条件满足时调用 `checkRemoteClipboard()`；`:799-827` 进入通用 fetch；`SyncClient.kt:126-128` 对 ClipCascade 明确抛出 `UnsupportedOperationException`。`MainService.kt:1929-1970` 又把失败送入连接测试/退避路径。健康检查/fallback 常量位于 `:86-88,1755-1772`。

**影响**：健康 WebSocket 空闲时也可能周期性执行注定失败的 fetch，随后做完整连接测试；造成不必要网络交换、日志、CPU 和密码派生成本，并可能误判连接状态。

**建议**：ClipCascade 跳过 polling fallback，以 WebSocket activity、ping/pong、close reason 和 last-message timestamp 作为健康依据；只有流断开或超过 stale 门限才重连。OneClip 也应按事件流能力明确区分 fallback 策略。

**预期收益**：高，消除健康空闲时的失败请求和测试握手。

**风险**：低到中。需验证服务器不发业务消息时 ping/pong 足以判断存活。

### P1-3 熄屏后截图 watcher 仍每 5 秒查询 MediaStore

**证据**：`ScreenshotClipboardWatcher.kt:33-40,135-144` 注册 ContentObserver 后仍启动固定 5 秒 fallback poll；`:94-109` 的 stop 会正确取消 poll。`MainService.kt:322-341` 熄屏时停止网络、health 与前台运行，但没有停止 watcher；`:535-544` 仅按 service/quick-sync/screenshot pref 决定 watcher。

**影响**：非交互状态仍保留周期 ContentResolver 查询。理论上是 720 次/小时，Doze 会延后，不能把理论值当成实测，但路径本身成立。

**建议**：熄屏立即 stop watcher；亮屏时重新注册并执行一次补偿查询。优先依赖 ContentObserver，5 秒 poll 仅在亮屏且确有 ROM 兼容需求时启用，并记录命中率以决定是否延长到 30–60 秒。

**预期收益**：高功耗收益，目标非交互态周期 MediaStore 查询为 0。

**风险**：中。部分 ROM 可能漏 ContentObserver，必须用亮屏补查覆盖。

### P1-4 语音 PCM 使用无界 Channel

**证据**：`VoiceInputProviderManager.kt:937-952,1002-1022` 两处建立 `Channel<QueuedAudio>(UNLIMITED)`，每包还 `copyOfRange`；`:955-985` 单协程串行 Binder `feedAudio`。默认 16 kHz/16-bit/mono、约 100 ms 一包时，PCM 约 32 KB/s，另有数组和队列对象开销。

**影响**：provider 卡顿、Binder 阻塞或处理速度低于采集速度时，堆按会话时长线性增长；长会话可能引起 GC、OOM，且积压音频变成高延迟识别。

**建议**：

- 用按“最大音频时长”定义的有界队列，而不是任意包数。
- 明确过载策略：短暂抖动可保留；超过预算后中止会话并报告 provider-too-slow，或在 provider 协议明确允许时丢最旧包。
- 记录 queue depth、oldest packet age、Binder call duration 和 dropped/aborted reason。

**预期收益**：高稳定性收益，内存从 O(会话/阻塞时间) 变为固定上界。

**风险**：中高。随意丢音频会降低识别质量；策略必须成为协议语义并做准确率回归。

### P1-5 两级无界 IME→fcitx 队列放大尾延迟

**证据**：`FcitxInputMethodService.kt:148` 为 `Channel<Job>(UNLIMITED)`；`:443-457` 每项创建 LAZY Job/闭包并 trySend；`:534-540` 单消费者逐项 join。随后 `FcitxDispatcher.kt:25-36,57,126-139` 再分配 WrappedRunnable，进入 ConcurrentLinkedQueue 并逐项 wake；`:83-90` 醒后 drain。

**影响**：普通软键至少涉及 Job + dispatcher runnable；宏/模拟 down/up 更多。Rime 慢、native STARTING、重启或停顿时，两层同时积压，旧 session 工作即使执行时被 generation 拒绝，也已经占用内存和排队时间。

**建议**：先加两级 queue depth、high-water、enqueue→start、start→finish。若确认显著：

- 第一层改为直接顺序执行命令 block 的 actor，避免“Channel 里再放 LAZY Job”。
- 命令分类：commit/key/delete 等必须 FIFO、lossless；selection/paging/capability 等幂等状态才可 latest-wins 合并。
- dispatcher 的 wake 合并只在正确性状态机修好且 profile 证明有价值后做。

**预期收益**：中高，重点是 stall 后 P95/P99 和内存，而非单键平均值。

**风险**：高。键序零丢失是硬门槛，不能用 DROP 策略换性能。

### P1-6 展开候选存在重复 refresh 放大链

**证据**：`HorizontalCandidateComponent.kt:123-138` 的 offset SharedFlow 没有 distinct；`:237-260` 在 layout completed 中可能重复 emit。`BaseExpandedCandidateWindow.kt:171-179` 每个正 offset 都 reset position 并 `refreshWithOffset`；`PagingCandidateViewAdapter.kt:34-42` 的 DiffUtil 两项永远返回 false；`:48-52` 每次 offset 刷新 Paging。

**影响**：候选布局 → emit offset → refresh paging → 全可见项判不同 → rebind/layout，形成重复 page load 和主线程分配放大。展开大候选页、输入中刷新或连续翻页最明显。

**建议**：

1. 先保存 `lastEmittedOffset` 或在 collector 使用 distinctUntilChanged。
2. offset 与 candidate generation/total 分开；相同 offset 但候选 generation 变化时才 refresh 数据。
3. DiffUtil 使用稳定候选身份和 CandidateWord 内容比较；全局 idx 通过 payload 或 holder 绑定位置更新，不能直接忽略 offset。

**预期收益**：高于一般 RecyclerView 微调，目标相同 generation/offset 不产生 page load，翻页仅绑定实际变化项。

**风险**：中。须回归重复候选、选择全局索引、首个 replay、Rime 重启和 expanded/keyboard 切换。

### P1-7 浮动键盘缩放每个 MOVE 强制整树 layout

**证据**：`InputView.kt:917-923,955-960` 每次 ACTION_MOVE 立即应用宽/高；`:1963-1984` 改 LayoutParams、刷新 bounds、invalidate outline、requestLayout 并更新 handle。开启模糊时 bounds 变化还会使 key region 重建。

**影响**：触摸采样可达 60–120 Hz；键盘约几十个 KeyView 和复杂布局，拖拽时可能同一帧多次 traversal。它不是普通打字热路，但在缩放体验上影响高。

**建议**：用 Choreographer/postOnAnimation 合并同一帧内的 MOVE，只提交最新尺寸；更激进方案是在 MOVE 用 scale/translation 预览，UP 时一次提交 LayoutParams。所有方案必须让 hit region、模糊 mask、预编辑和 handle 与视觉同步。

**预期收益**：高（限定于浮动缩放场景），目标每帧最多一次 performTraversals。

**风险**：中。

### P1-8 自定义水波纹的全键盘离屏层成本

**证据**：`KeyboardWaterRippleView.kt:91-121` 每个 ripple 持续约 630–970 ms、最多 8 个并发；`:186-268` 每帧 `saveLayer(0,0,width,height)`，绘制 ripple 后对全部 occluder 逐个 CLEAR，并持续 postInvalidateOnAnimation。shader 和 occluder snapshot 已缓存，旧的 Java 分配热点已修复。

**影响**：快速打字时动画可能常驻；中低端 GPU、120 Hz、透明/复杂主题下存在 fill-rate、离屏合成和功耗压力。

**建议**：只有 GPU profile 越门槛后，才把 layer 裁剪到活动 ripple bounds，按交集过滤 occluder，或评估 RenderNode/预录 mask。不要在没有像素回归的情况下改变孔洞效果。

**预期收益**：条件性高。

**风险**：中高；需多指、重叠 ripple 与截图像素对比。

## 4. P2：明确可优化但优先级次之

### P2-1 Release 热路径提前构造 DEBUG 日志字符串

`Fcitx.kt:405-412` 每个 native callback 执行 `Timber.d("Handling $event")`；`FcitxEvent.kt:16-20` 候选事件 toString 会 joinToString。`utils/Timber.kt:18-23` 的 ConciseTree 到 log() 才丢弃 DEBUG，插值和 toString 已发生。建议在格式化前用 release/verbose gate，或改真正惰性的日志调用；保留 trace。风险低。合入门槛：Release 1000 键 alloc/key 下降至少 5%或节省至少 0.05 ms/key。

### P2-2 native flush 固定产生 InputPanel 和 Candidate/Paged 两套对象

`androidfrontend.cpp:351-365` 同一 flush 顺序更新 panel 与 candidate；`:94-148` 构造 candidate vector。`native-lib.cpp:573-613,659-681` 每类回调创建 Object[]、装箱和 Java 候选对象；候选字符串/对象是 payload 主体。通常一个接受的键会产生多个 native→Java callback，但具体数量必须动态统计。

先测 callback histogram、转换 CPU 和 allocations/key。只有 JNI+分发 P95 ≥0.3 ms/key 或占 UP→候选 ≥5%，原型又能让端到端 P95 改善 ≥5%/1 ms，才考虑复合快照或去重。批量 JNI只能省 envelope，省不掉候选字符串复制，并有事件顺序风险。

### P2-3 PagedCandidate 被包装成 legacy 再广播

`InputView.kt:3384-3390` 新建 legacy CandidateListEvent.Data，先广播 legacy 再广播 paged；`InputBroadcaster.kt:58-64` 两轮遍历 receivers。横向组件收到 legacy 后在 paged 活跃时多半丢弃。建议 receiver 声明 capability，或统一 CandidateSnapshot；必须保留 KawaiiBar、KeyboardWindow 的 empty 语义与 500 ms legacy fallback。收益中、风险中。

### P2-4 浮动候选每次有效变化全量刷新

`PagedCandidatesUi.kt:126-153` 数据或方向变化即 `notifyDataSetChanged`；holder bind 重建富文本、监听器和 LayoutParams。已有 equality skip，问题仅在真实每键变化时。建议前后缀 diff、activeIndex payload、监听器在 create 时通过 holder position 读取。收益中、风险中。

### P2-5 CandidatesView 成对事件可能重复 updateUi/layout

`CandidatesView.kt:155-180` InputPanel 与 PagedCandidate 都完整 updateUi；`:193-204` 重写 preedit layout params。Rime 是否每键成对发送需设备确认。建议拆分 panel/paged 更新，并缓存 reversed、inputPanel 和最终几何；目标一键最多一次相关 layout。风险低到中。

### P2-6 横向候选剩余的宽度预测和 metadata 全刷

`HorizontalCandidateComponent.kt:283-345` AutoFillWidth 预测每候选重复获取 font epoch、buildString、查宽度缓存，缓存超过 512 整体清空；`:470-504` 每次更新还分配 pending Triple。`HorizontalCandidateViewAdapter.kt:68-117` 已有前后缀 diff，但内容相同、total/indexOffset/font metadata 变化的 `:95-97` 仍全刷。

建议一次 update 只解析一次 font epoch/TextPaint；用有界 LRU 和无需先 buildString 的稳定 key；拆 total-only、index payload 与 font full refresh。先做 5/9/16 候选 microbenchmark，避免为 O(5) 代码过度设计。

### P2-7 候选富文本在 active 切换时重复构造

CandidateItemUi 的 active 状态会重新 render，方向键移动时旧/新两项都可能 rebuild SpannedString。可缓存 normal/active CharSequence，key 包括 CandidateWord、font generation、theme color；或让颜色成为独立状态。收益低到中，需控制缓存大小。

### P2-8 SyncStateStore 写队列不按 key 合并

`SyncStateStore.kt:59-81` 每次 write 捕获完整 String 并提交 task；`:119-126` 单线程 executor 默认无界。虽然单值持久化限制 32K，但 burst 会保留多份已过期值并重复 tmp-write/rename。建议维护 dirty-key 集合和 latest value，每个 key 同时最多一个 drain task。收益中，风险低到中；跨 key 若无全局顺序要求可独立合并。

### P2-9 Clipboard 新增时做 O(N) 全对象保留清理

`ClipboardManager.kt:571-607` 分三类读取完整 ClipboardEntry 列表、排序、建 retained set 和 deletion list。数据库规模增长后，每次插入成本和分配随 N 增长。建议 DAO 用 SQL WHERE/source/type/pinned + ORDER BY id DESC + LIMIT/OFFSET，只返回 id 并批量标记；为筛选列建合适索引。收益中，风险中，必须保持 local/remote/media/pinned 语义。

### P2-10 DataManager 每次 native startup 都解析/diff/重写 descriptor

`Fcitx.kt:440-463` 在 fcitx-main 启动先同步执行 `DataManager.sync()`；`DataManager.kt:67-131` 即使同进程已 synced，也读旧/包内 descriptor、建 hierarchy/diff，并重写 descriptor。首次安装/升级复制资产不可避免，但 warm restart 有潜在冗余。

建议先 trace parse/diff/write/copy。若非首次启动 P95 ≥10 ms 或占 onCreate→Ready ≥5%，再加入“同进程已成功 sync 且资产 generation 未变”的安全快路；callbacks 语义仍要执行。风险中。

### P2-11 自定义 file: 图标可在主线程重复 I/O/解析

ButtonsBar bind 会进入 ButtonIconFile.loadDrawable；file icon 可同步 readBytes/解析 XML、SVG 或 Drawable.createFromPath。IconThemeManager 已有后台初始化和 LRU，但独立 file 路径没有等价 runtime cache。建议按 canonical path + mtime + size 缓存 ConstantState/解析产物并 clone，文件变更精准失效；或后台预解码后主线程 apply。用 StrictMode 验证主线程 disk read。风险中，注意 Drawable state 共享。

### P2-12 文件上传后的重复整文件读取和碰撞比较

`MainService.kt:2280-2295` 上传后再次 `readBytes()` 计算 hash，32 MB 文件会再次分配；`SyncClient.kt:928-946` 名称冲突候选使用 `readBytes().contentEquals`，极端情况下重复大量读取。改为流式 SHA-256、size+hash 或固定 buffer compare，峰值降为常量。收益中，风险低。

### P2-13 主题 ZIP 仍整包读内存并尝试三次编码

`ThemeFilesManager.kt:348-379` import/decode 使用无界 `src.readBytes()`，然后 UTF-8/GBK/Big5 多次解析；`:413-431` 解压也无条目/总量限制。这里首先是安全/稳定性问题，修复限额 extractor 同时降低峰值内存和重复 I/O。IconThemeManager 已有压缩包和解压预算，不要把它重复列为未修。

### P2-14 QR 长图峰值可达百 MB 级

`LayoutQrBitmapUtil.kt:45-60,128-150,208-248` 最多 64 页的长图可能达到约 53M 像素；RGB_565 主图约 106 MB，ARGB_8888 预览约 212 MB，另有临时 QR bitmap。它是手动分享路径，不是日常热路。建议分页导出、逐页文件或真正流式编码；风险中，涉及分享兼容性。

### P2-15 Baseline Profile 被主动禁用

`AndroidAppConventionPlugin.kt:110-113` 禁用 Merge/Expand/Compile ART profile，`app/build.gradle.kts:196-200` 排除 profileinstaller。Release 已启用 minify、optimization 和 resource shrink（`AndroidAppConventionPlugin.kt:42-48`），`-dontobfuscate` 本身不是运行时性能问题。

建议建立 Macrobenchmark/profile 生成模块，覆盖 service onCreate→input view→Ready→首候选，而不是盲目恢复 profile。若 arm64 Release 冷/温启动 P95 改善 ≥5%或 ≥20 ms 且包增量可接受，再启用。Rime native 初始化不会因 Java profile 自动变快。

## 5. P3：只有 profile 证明后再做

1. **普通字母 String JNI 快路**：当前 String 路每键 GetStringUTFChars + keySymFromString；只对已知单 ASCII/预解析 KeySym 建 primitive 快路。现有 Char JNI 有转换正确性问题，不能拿来优化。门槛：sendKey JNI P95 ≥0.10 ms 或占端到端 ≥2%。
2. **dispatcher wake 合并**：每 dispatch 都 nativeScheduleEmpty，而 loop 醒后会 drain。只有每键 >1.5 wake 且 wake CPU ≥0.1 ms/key或占比 ≥5%，才考虑 queue 0→1 transition 唤醒；必须先解决 teardown 线性化正确性。
3. **JNI callback 合包**：继续暂缓。预计只省少量 envelope/Flow resume，候选 payload 复制仍在，事件排序风险高。
4. **触摸 region Rect/IntArray、候选位置 Pair 等小分配**：拖拽/anchor update profile 显示 allocation 占比后再用 scratch object/packed value；不要牺牲可读性。
5. **Vivo workaround MOVE 分配**：只在受影响 Vivo 真机证明成本后缓存 target offset；多指和 popup 路由风险高。
6. **native packaging/ABI/ELF 调优**：当前无产物，不能判断 legacy packaging、压缩、strip、LTO 或 section 大小。先取得 CI arm64 Release artifact，用 apkanalyzer、llvm-size、readelf 和 dlopen trace。

## 6. 已修复或已有限制的旧热点

以下不应再作为“新问题”重复报告：

- **横向候选每键全量刷新已修**：`HorizontalCandidateViewAdapter.kt:59-130` 已有 content equality、前后缀 range diff、stable ids 和 active 两项局刷；只剩 metadata 特例。
- **AutoFillWidth 常态双布局已修**：`HorizontalCandidateComponent.kt:475-492` 先预测 overflow，第二 pass 仅为误判/未布局兜底。
- **候选额外一至两帧 post 延迟已修**：legacy 同步处理，ensure-visible 在 layout completed 消费，不再递归 post。
- **asList 快照分配已修**：候选快照直接保留 array 并 contentEquals；CandidateWord 还有 256 项 intern cache。intern 发生在 JNI 对象已创建之后，不能声称消除了 native marshalling。
- **KawaiiBar bind 内重入 full notify 已修**：现在只在 size/config/icon/layout-mode 低频变化时全刷，且按钮数约十个，不是每键热路。
- **字体主线程加载已修**：单后台 executor 预加载、原子发布、字号结果缓存、font generation 失效。
- **IconTheme 主线程扫描已修**：后台 init/scan，并有约 16 MB drawable cache；只剩独立 ButtonIconFile 路径。
- **模糊旧热点已修大部**：key region/mask 合并、API 31+ RenderNode GPU blur、dirty rebuild、postOnAnimation 合并、壁纸下采样和 16 MB 缓存。不要描述成每键主线程解码或无限 invalidate。
- **水波纹 shader/位置对象旧分配已修**：shader 分桶、occluder snapshot 缓存、并发 ripple 上限 8；当前剩余是全层 GPU 成本。
- **主键盘不是每键重建**：行树 LRU、signature 复用、compose-aware 定点替换和 KeyboardWindow 实例复用均已存在。
- **同步网络熄屏轮询已停**：`shouldRunSyncLoops` 要求 screen interactive；SCREEN_OFF interval 常量当前基本不可达。剩余的是截图 watcher 未停。
- **网络载荷已有 32 MB 帽**，缩略图已 192×192/RGB_565，壁纸缓存按字节限制，Web 编辑器线程池/body 也有上限。
- **IME 退出不再同步执行完整 Rime sync**：这是避免 10 秒级 teardown 的正确折中。应做周期性 durable sync，不要恢复退出阻塞。

## 7. 正确性问题不能当作性能优化

- `MutableSharedFlow(extraBufferCapacity=15, DROP_OLDEST)` 会丢 commit/delete/key 是 P0 正确性问题。修复要做 lossless FIFO + session id，不应以“减少积压”继续丢事件。
- Fcitx STARTING/STOP、dispatcher wake/teardown、InputContextCache 等竞态必须先保证任务执行或异常完成，再谈 wake 合并。
- ClipCascade fallback 的 `UnsupportedOperationException` 是逻辑错误，同时造成性能浪费；修复依据首先是 backend 语义。
- 主题 ZIP 的无界读取/Zip Slip 首先是安全和稳定性问题，性能收益是附带结果。
- 不建议 fire-on-DOWN、关闭 UP 触觉、Compose 重写、整套 UI 重写或无数据 native 合包。

## 8. 基准与观测方案

### 8.1 输入到候选的主指标

沿已有 trace：`KeyView ACTION_UP dispatch` → `sendKey JNI` → native/Rime ProcessKey → callback → `HandleFcitxEvent` → `collectFcitxEvent` → `updateCandidates` → bind/layout/draw → frame present。

新增：

- 两级 queue depth/high-water、enqueue/start/finish。
- callback type/count/key、candidate objects/key、bytes allocated/key。
- RecyclerView onBind、layout pass、notify type。
- FrameTimeline present timestamp，不能只以 notify 完成当“可见”。

场景：5/9/16 候选，短/长 comment，横向/浮动/展开，冷/温引擎，100 与 1000 键，低端 60 Hz + 中端 120 Hz。

建议门：

- UP→candidate visible：报告 P50/P95/P99；优化后 P95 至少改善 5%或 1 ms才支持中高风险热路径改造。
- 常规打字 jank <3%，P95 frame <刷新周期；P99 不回退超过 10%。
- 200 字不出现频繁 GC；报告 Java/native/graphics PSS 与 alloc bytes/key。
- 注入 Rime 10/30/100/250/1000 ms 延迟，键事件零丢失，记录恢复后的 oldest wait/P99。

### 8.2 UI/GPU

- 浮动宽/高缩放固定 1 秒手势：ACTION_MOVE 次数、performTraversals/frame、FrameTimeline P95/P99、jank；目标每帧最多一次 traversal，拖拽 jank <5%。
- 水波纹 10 秒、8–12 键/秒，on/off、60/120 Hz：GPU render stages、saveLayer 范围、jank%、graphics memory 和功耗；像素对比多 ripple/孔洞。
- 展开候选连续翻 10 页与输入 100 键：offset emits、PagingSource loads、refresh、onBind、GC、frame P95。
- 自定义 10 个复杂 SVG：StrictMode disk read、首次与热 reload 主线程耗时。

### 8.3 网络与后台功耗

矩阵：SyncClipboard/OneClip/ClipCascade × active/idle/screen-off/Doze × healthy/refused/blackhole/burst。

指标：pull/h、handshake/focus、并存连接数、bytes/h、radio-active、wakeups、CPU、mAh/h、同步 P50/P95。硬目标：

- 焦点只改变 cadence 时 0 reconnect。
- 每 backend ≤1 事件连接。
- screen-off 网络和截图周期查询均为 0；亮屏补查不漏截图。
- ClipCascade 健康空闲不调用 polling fetch，不出现 UnsupportedOperationException。

### 8.4 数据、内存与启动

- Room：100/1k/10k 条，含长文本；测 insert+trim SQL P50/P95、cursor rows/bytes、alloc/GC，并逐类核对保留集合。
- 文件：1/8/32 MB、0/10/100 名称碰撞；记录读取次数/总字节、peak heap、hash/compare 时间。
- QR：1/16/32/64 页，在 128/256/512 MB heap 设备测峰值、GC、编码时间和失败率。
- 启动：30 轮无更新/模拟升级/慢存储；service.onCreate→Ready→首键，分段 DataManager parse/diff/write/copy、Rime startup、view inflate/layout。
- Baseline Profile：arm64 minified Release A/B，30 次 cold/warm，报告 TTID/TTFD、Ready 和首候选；P95 ≥5%或 20 ms收益才启用。

## 9. 建议实施顺序

### 第一批：高收益、语义明确

1. 修 ClipCascade 健康路径，禁止 WS backend fallback polling。
2. 同步 runtime 幂等化，焦点变化不重建连接。
3. 熄屏停止 ScreenshotClipboardWatcher，亮屏一次补查。
4. Release DEBUG 日志构造前门控。
5. 展开候选 offset distinct/generation 门控，再修 DiffUtil/idx payload。

### 第二批：稳定性和尾延迟

1. 语音 PCM 有界预算与 provider-too-slow 语义。
2. 采集两级 IME/fcitx 队列数据；将第一层 LAZY Job 队列收敛为 typed actor，幂等状态才合并。
3. SyncStateStore 按 key latest-wins。
4. Room trim 改 SQL id-only。
5. 文件 hash/compare 流式化。

### 第三批：UI 与启动

1. 浮动缩放 frame-coalescing。
2. 浮动/展开候选细粒度 bind 和富文本缓存。
3. ButtonIconFile 缓存/后台预解析。
4. DataManager warm restart 快路。
5. 基于 A/B 决定 Baseline Profile。

### 第四批：严格测量门控

水波纹裁剪、JNI 快路/复合 callback、dispatcher wake 合并、Rect/Pair 微分配、native packaging/LTO/ABI 体积调优。

## 10. 最终判断

当前代码的候选主路径已经经过多轮优化，旧报告中最显眼的全量 refresh、双布局和 post 帧延迟大多不再成立。下一阶段应把性能工作从“猜测 JNI 次数”转为可量化的四条主线：

- 输入 P95/P99 与 queue backlog；
- 展开/浮动/特效的 frame/GPU；
- 同步连接次数、后台 wakeup 和网络字节；
- 启动、Room、文件操作的规模曲线。

就静态证据而言，优先修连接生命周期、ClipCascade fallback、熄屏截图 poll 和无界音频队列，通常比 JNI 合包或 UI 重写更可能获得真实、低争议的性能与功耗收益。所有涉及输入顺序和 Rime 回调的改造都必须以零丢键、事件顺序和 session 隔离为硬门槛。
