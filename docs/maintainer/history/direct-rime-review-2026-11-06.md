# 直连 Rime 层审阅与同类项目对照（2026-11-06）

本报告记录一次固定版本的代码审阅、原生探针和本地安装包研究。生产代码未因本次审阅而修改。这里的性能结论分为“已确认的重复工作”和“需要设备测量的成本”，不将旧报告的数据或桌面探针视为当前 Android 性能。

后续实现与验收另见[2026-11-06 引擎同步与输入修复](../changes/2026-11-06-engine-input-fixes.md)。本页保留修改前的代码与探针结论。

## 判断

当前直连层的总体方向合理，已经具备比较完整的输入正确性保护。它通过 Kotlin 保留前端契约，用单一 owner 线程调用 JNI 和 librime；没有理由为了性能重新引入 Fcitx 原生层，也没有证据支持把同一个 librime runtime 拆成多个并发执行线程。

值得优先处理的是三个具体缺口：

1. 普通候选点选没有携带显示时的候选版本，排队期间候选变化后可能选错词。
2. “刷新候选展示”借用了设置光标接口，混用了显示文本与原始编码坐标，并额外触发引擎重组。
3. 同步完成后的返回值仍表示任务启动成功，后台同步失败可能被调用方当作成功。

之后再优化无变化抬键的快照构造、按需候选数据和冷启动诊断。现有代码不足以证明本项目比 Foxy、元书或同文更快或更慢；本轮没有连接的 Android 设备。

## 审阅基线与证据范围

| 对象 | 固定来源 |
| --- | --- |
| 主应用 | `ae4a13438be015c7546fce0060c96c140e3c4ab6`，`fx-rime-only`；本轮涉及的直连层文件与该提交一致 |
| 实际消费的引擎 | `native/rime-sdk.lock.json`：`android-sdk-20261105-2`，librime `456fc2f3f69137d9246512b8c07245734526ba24`，SDK SHA-256 `e3b27bfae6e7f0673862f7134eba1f39b3a99ab29af5d25ecc385f9f04ace0ff` |
| 本地引擎源码 | `30511eed172a8a8ee08b8532fe097febcd75e514`；已确认它与上述锁定引擎提交之间的 `src/` 没有差异，不能将本地 HEAD 误写成已消费的 SDK 源码版本 |
| Foxy | 用户提供的 `foxy_re`，APK 版本 `0.0.1-20f1709`，SHA-256 `b0f80fefe1abdd604ce273d98b1fa4b81df44f22ac83e76027556130b5ac3e37` |
| 元书 Android | 用户提供的 `元书_0.1.0.apks` 与合并 APK；包名 `com.ihsiao.apps.hamster`，versionCode 47，versionName 0.1.0，ARM64 |
| 网上项目 | 下方表格中的当日默认分支固定提交；实际下载源文件并核对，不只参考 README |

主工作区及 docs worktree 当时存在其他 UI、AI 和文档改动。本报告保留这些修改，追加验证使用相同提交的独立源码副本。Foxy 是反编译快照；元书本轮 JADX 报 38 个反编译错误，相关入口可以阅读，但两者都不是完整、可验证构建的原始源码。不能据此断言其全部异常处理或 native 实现细节。

## 需要修正的具体问题

### P2：候选点选只记录序号，没有绑定显示版本

生产路径：

- [横向候选点击](https://github.com/SandyYuR/fcitx5-android/blob/ae4a13438be015c7546fce0060c96c140e3c4ab6/app/src/main/java/org/fcitx/fcitx5/android/input/candidates/horizontal/HorizontalCandidateComponent.kt#L385)和[展开候选点击](https://github.com/SandyYuR/fcitx5-android/blob/ae4a13438be015c7546fce0060c96c140e3c4ab6/app/src/main/java/org/fcitx/fcitx5/android/input/candidates/expanded/window/BaseExpandedCandidateWindow.kt#L263)捕获 `idx`。
- [postCandidateSelection](https://github.com/SandyYuR/fcitx5-android/blob/ae4a13438be015c7546fce0060c96c140e3c4ab6/app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt#L536)通过已有按键队列执行，ticket 保护的是编辑器代次。
- [performAction / select](https://github.com/SandyYuR/fcitx5-android/blob/ae4a13438be015c7546fce0060c96c140e3c4ab6/app/src/main/java/org/fcitx/fcitx5/android/core/DirectRimeBackend.kt#L479)到执行时才捕获 `snapshot`；普通 `select` / `selectGlobal` 不传 `candidateContext`。

可触发的顺序是：界面仍显示 A 列表 → 一个按键已排在服务队列中 → 用户点击 A 的第一个词 → 前面的按键先将引擎推进至 B 列表 → 点选按 B 的序号 0 执行。同一编辑器代次始终有效，执行时获取的快照也有效，因此现有两层检查都不能识别这个旧点击。

这不是要求将点选移出串行队列。正确做法是让候选视图或分页数据携带它实际显示的 `CandidateActionContext`，随点击入队，并在 owner 上再次检查。不要在点击时临时读取“当前 backend 版本”代替显示版本，因为 backend 可能已经领先 UI。

[长按候选菜单](https://github.com/SandyYuR/fcitx5-android/blob/ae4a13438be015c7546fce0060c96c140e3c4ab6/app/src/main/java/org/fcitx/fcitx5/android/input/BaseInputView.kt#L273)和 `triggerCandidateActionIfCurrent` 已有可复用的版本保护。展开面板还需要把版本关联到实际分页项目，避免新代次开始取数时旧项目仍可点击。

验证状态：隔离 JVM 探针已复现。测试调用真实服务入队方法，按生产顺序逐项 join，并运行实际 backend，仅模拟 JNI；“旧候选”的序号 0 最终选中了“新候选”，同一旧版本的受保护动作则被拒绝。修复后应覆盖连续输入后快速点选、展开列表异步换页、同位置不同词、失效编辑器和无变化 key-up。

### P2：展示刷新调用光标 setter，坐标也不等价

[setCandidatePagingMode / publishWithoutCommit](https://github.com/SandyYuR/fcitx5-android/blob/ae4a13438be015c7546fce0060c96c140e3c4ab6/app/src/main/java/org/fcitx/fcitx5/android/core/DirectRimeBackend.kt#L545)以 `act(9, value.cursorBytes)` 刷新。[native action 9](https://github.com/SandyYuR/fcitx5-android/blob/ae4a13438be015c7546fce0060c96c140e3c4ab6/direct-rime/src/main/cpp/engine.cpp#L268)实际调用 `set_caret_pos`。

这有两个不同后果：

- 快照里的 `cursorBytes` 来自 `RimeContext.composition.cursor_pos`，是显示 preedit 的 UTF-8 偏移；`set_caret_pos` 接收原始 input 的偏移。插入拼音分隔符、preedit transform、已转为汉字的片段都会让二者不同。例如原始 `nihao` 的偏移 3 与显示 `ni hao` 的偏移 4 可以描述同一个视觉位置，把 4 写回 raw input 就会改变光标。
- 即使两者数值恰好相同，[Context::set_caret_pos](https://github.com/SandyYuR/librime/blob/456fc2f3f69137d9246512b8c07245734526ba24/src/rime/context.cc#L315)仍无条件触发 `update_notifier_`，继而进入 [ConcreteEngine::OnContextUpdate → Compose](https://github.com/SandyYuR/librime/blob/456fc2f3f69137d9246512b8c07245734526ba24/src/rime/engine.cc#L216)。一次展示模式刷新会重新进入组合处理，成本取决于方案和 Lua。

另外，[moveCursor](https://github.com/SandyYuR/fcitx5-android/blob/ae4a13438be015c7546fce0060c96c140e3c4ab6/app/src/main/java/org/fcitx/fcitx5/android/core/DirectRimeBackend.kt#L508)把传入位置当作 Kotlin 字符串下标，而[服务调用方](https://github.com/SandyYuR/fcitx5-android/blob/ae4a13438be015c7546fce0060c96c140e3c4ab6/app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt#L2088)保留的是旧 Fcitx 契约的 Unicode code point 数。带补充平面字符时，还存在 code point 与 UTF-16 下标差异；仅改 UTF-8 编码转换不能解决全部映射问题。

建议先将“读取/重新发布展示状态”做成无光标副作用的入口；随后明确区分原始编码偏移、显示 UTF-8 偏移和 Android UTF-16 位置，为实际移动光标建立映射。继续保持 commit 只消费一次的约束。Trime 的 [ContextProto](https://github.com/osfans/trime/blob/0f7bc782c235602f2952ef166ba9167a6d9fee15/app/src/main/java/com/osfans/trime/core/RimeProto.kt#L39) 将 composition/preedit 与原始 input/caret 分开保存，是一个可参考的数据建模方向，并不代表其所有映射场景已经验证。

验证状态：代码调用链已确认，隔离 JVM 探针中实际 `setCandidatePagingMode(0)` 调用了一次光标 setter，将模拟的原始偏移 3 写成显示偏移 4。该探针验证参数传递和副作用入口，未运行真实方案转换；设备上仍应回归双拼转换、拼音空格、分段选词、Emoji 以及合成中的分页模式切换。

### P2：同步启动成功被当作同步完成成功

[Engine::sync](https://github.com/SandyYuR/fcitx5-android/blob/ae4a13438be015c7546fce0060c96c140e3c4ab6/direct-rime/src/main/cpp/engine.cpp#L455)保存 `sync_user_data()` 的布尔值，等待维护线程，再直接返回该值。

但是实际 [RimeSyncUserData](https://github.com/SandyYuR/librime/blob/456fc2f3f69137d9246512b8c07245734526ba24/src/rime_api_impl.h#L142)返回 `StartMaintenance()` 的结果；任务结果由 [Deployer::Run](https://github.com/SandyYuR/librime/blob/456fc2f3f69137d9246512b8c07245734526ba24/src/rime/deployer.cc#L79)以 `deploy=success/failure` 通知。

本轮编译实际 `engine.cpp` 并使用 fake RimeApi 验证：线程启动返回 true，随后通知 failure，`Engine::sync()` 仍返回 true，而下一次绑定得到 `DeployFailed`。这会让[调用方的 check(Native.sync())](https://github.com/SandyYuR/fcitx5-android/blob/ae4a13438be015c7546fce0060c96c140e3c4ab6/app/src/main/java/org/fcitx/fcitx5/android/core/DirectRimeBackend.kt#L642)漏掉同步失败。

建议为本次维护保存明确的完成状态，在 join 后返回“已启动且本次完成成功”；不要仅复用可能属于前一次维护的错误标记。还应核对 `save()` 直接调用 `Native.sync()` 的备份入口：Rime 同步会清理 session，它不等同于普通内存 flush。备份停写协议仍需保留。

这一点可以直接借鉴 [Trime 的 syncUserData](https://github.com/osfans/trime/blob/0f7bc782c235602f2952ef166ba9167a6d9fee15/app/src/main/java/com/osfans/trime/core/Rime.kt#L140)：分别处理是否启动和 DeployMessage 的成功/失败。

## 当前实现值得保留的部分

| 机制 | 本轮确认的生产作用 |
| --- | --- |
| Kotlin 与 native 双重 owner 约束 | `newSingleThreadExecutor("rime-main")` 加 `Engine::owner()`，维护回调只写原子状态；不在回调线程改 JNI/session |
| 入队前的 ticket | `postFcitxKeyJob` 在服务队列前捕获编辑器代次；启动等待有数量和时长边界，失效编辑器拒绝交付 |
| 提交与展示分开处理 | `InputEventQueue` 只合并相邻纯展示批次，不跨 commit/key/Ready 边界；其 CONFLATED 通道只是唤醒通知 |
| owner 外的背压等待 | `awaitCapacity` 在进入 owner 前运行，避免 UI 等候选、owner 又等 UI 消费的循环等待 |
| 保守的 session 复用 | 同一非空应用内的普通编辑器可复用；跨应用、敏感输入前后和未知应用重建，清理旧组合与 commit |
| 成批 JNI 快照 | 一次返回提交及其对应状态；缓存 JNI class/constructor，已有严格 UTF-8/UTF-16 处理 |
| 状态与候选去重 | `optionsRevision` 避免每键重读所有开关；无变化、未处理的 release 减少 Kotlin 发布；候选分批读取并校验版本 |
| 依赖和链接约束 | 固定 SDK/来源 SHA，静态链接白名单、link map 审计以及独立诊断 APK；保留 Kotlin Fcitx 类型名不等于恢复 Fcitx 原生后端 |

这些机制说明直连层已经超出了最小 JNI 包装器的水平。输入正确性所需的序列化、版本校验和副作用事件保存应作为优化前提。

## 性能分析与实施顺序

### 1. 先去掉“刷新时重新组合”

上面的光标 setter 问题既是正确性问题，也是最具体的多余引擎工作。应先分离无副作用刷新，再测模式切换、展开候选时的 `rime.direct.action_core` 和候选加载耗时。不能从静态代码给出固定毫秒收益。

### 2. 无变化 key-up 的优化现在发生得偏晚

本轮原生计数探针对实际 `engine.cpp` 输入 1,000 组 DOWN/UP；fake API 的每次 UP 都未处理、没有 commit、展示与 DOWN 相同。结果：

| 项目 | 调用次数 |
| --- | ---: |
| process_key | 2,000 |
| get_commit | 2,000 |
| get_context | 2,000 |
| get_status | 2,000 |
| get_input_tabs | 2,000 |

这是调用数量，不是延迟基准。当前 [Engine::key → snapshot](https://github.com/SandyYuR/fcitx5-android/blob/ae4a13438be015c7546fce0060c96c140e3c4ab6/direct-rime/src/main/cpp/engine.cpp#L233)先复制 C++ 字符串和候选，JNI 再构造 Java 字符串、四组数组和 Snapshot，最后 Kotlin 才判断 `isUnchangedRelease`。

可以先在 native 边界复用不变的展示部分，返回必要的新 revision、handled 和 commit，降低 JNI/Java 分配；schema/name/ascii 可研究利用可靠的 option/schema 失效信号缓存。进一步跳过 context/tabs 查询需要更严格的引擎变化标识。

**不能简单跳过所有 UP，也不能把 handled=false 当作绝对无副作用。** 释放键、和弦输入、Lua 处理、选项变化和懒候选尾部都要保持契约；现有 release 和候选版本测试应继续通过。

### 3. 候选分批正确，但“8 个候选”不是时间上限

`readCandidatePage` 每批 8 个、批间 yield，是合理的公平性设计。真实 `candidate_list_from_index` 只设置索引，`Menu::GetCandidateAt` 使用已有缓存或按需 `Prepare`；本轮没有发现“每批都从第一个候选重新遍历到 offset”的实现，不能据此宣称存在平方级退化。

风险在于生成单个新候选也可能触发较重的 translator/filter/Lua。同步 native 调用内无法由 Kotlin yield 抢占。因此建议测批次的 wall time、生成候选数和排队键延迟，再决定是否使用 1/4/8/16 的自适应批量及适度预取。降低批量也无法解决某一次 Lua 调用本身很慢的问题。

另外，`Native.candidates().toList().chunked(2).map(...)` 有可减少的中间集合；可以线性构建最终数组。它属于小优化，优先级低于重新组合、冷 I/O 和大候选生成。

### 4. 首次输入成本仍取决于 session、方案与存储

当前只复用同应用的普通 session；跨应用或敏感边界必须重建。大型方案的词典映射、Lua 初始化、OpenCC、LevelDB 打开和缺页仍可能造成首键/首次选词尖峰。移除一层包装不会消除这些工作。

当前已包含全绑定过程的临时线程优先级提升，以及 [LevelDB reuse_logs 包装](https://github.com/SandyYuR/fcitx5-android/blob/ae4a13438be015c7546fce0060c96c140e3c4ab6/direct-rime/src/main/cpp/leveldb-open.cpp)。后者没有关闭 WriteOptions::sync。旧首键报告只能作为场景和排查入口，不能据此判断新 SDK 的尖峰已消失。

不建议未经测量就跨应用保留多个 Lua session、放宽敏感输入隔离、关闭同步写或主动清理词库文件。可以先量出“创建 / 复用 / 查询 / 文件 I/O / UI 上屏”各阶段，再做有边界的缓存。

### 5. 诊断需要补齐完整按键路径和内存趋势

现有 INPUT_DIAGNOSTICS 已提供 native 阶段、owner 等待、I/O/缺页和事件交付指标；普通构建也记录少量绑定与首次点选数据。仍值得补充：

- 普通按键从 ticket 捕获、服务队列、owner 队列到 Android 提交/首帧的关联 ID，而不只是 native 切片。
- 入队与 native key 分别计数，区分队列积压、启动超时拒绝和编辑器失效。
- 调试/诊断模式中的 Java heap、native heap、PSS 和 Lua 内存趋势，区分“有意保留一个 warm session”和持续增长。
- 相同方案下的冷启动、复用、跨应用、敏感输入、展开深分页、连续打字及首次选词分布。

元书安装包中可以确认 `RimeNative.luaMemoryBytes()`、owner 线程检查以及 native heap/PSS 采集，是可借鉴的可观测性方向。当前看不到这些指标不等于已经存在内存泄漏。

## 同类项目对照

| 项目与固定来源 | 实际看到的实现 | 可借鉴之处与适用边界 |
| --- | --- | --- |
| [同文 Trime](https://github.com/osfans/trime/tree/0f7bc782c235602f2952ef166ba9167a6d9fee15)，Android | 单线程 RimeDispatcher、等待过久告警；批量 RimeResponse；按分页模式选择输出的候选模型；同步等待完成消息 | 借鉴原始输入/显示输入分离、展示数据按需打包、维护完成结果。其 SharedFlow 配置 DROP_OLDEST，且提交经过该流，本项目不应复制这一溢出策略；这也是保留现有有序提交队列的理由 |
| 用户提供的 Foxy，Android 反编译快照 | `Rime.java` 的 `rime-main` 单线程执行器、JNI API、context/status 解析；一部分入口通过 submit/get，另有 async 包装 | 单线程直连方向一致。调用 submit/get 的入口是否堵住 UI 必须逐调用方判断；不能仅凭这个 helper 判定整个 Foxy 有 ANR，也不能据较少代码证明其更快 |
| 用户提供的元书 0.1.0，Android 安装包 | `e64` 中单 owner 执行器及线程断言；NativeRimeContext/Status；候选按 30 个增量读取；Lua 与 native 内存诊断 | 优先借鉴诊断和数据模型。byte[] 边界仍有后续解码和对象构造，不能直接推断比 String JNI 更省；30 个一批也未必比本项目的 8 个更适合重型方案 |
| [Hamster](https://github.com/imfuxiao/Hamster/tree/65693706d01fc6c19ed6071968e542b0a2ef3f36)，iOS，默认分支提交日期为 2025-05-13 | RimeKit 集中封装；setup 只执行一次；按需创建 session；候选分页入口 | 借鉴 API 分层、进程级初始化与会话级状态分离。本项目已经实现其中多项；此 Swift/ObjC 仓库不能当作所附元书 Android APK 的原始源码 |
| [鼠须管 Squirrel](https://github.com/rime/squirrel/tree/b7970a93b05ae1ca1bcd97888fd6aa3cd0458d3e)，macOS | 控制器管理 session；显式消费 commit；保留和弦释放及应用选项；组合选择区与 UTF-8/Swift 字符串位置处理 | 适合参考组合文本和宿主编辑器协议的细节。AppKit 生命周期、系统输入上下文和桌面内存条件不能直接照搬到 Android |
| [小狼毫 Weasel](https://github.com/rime/weasel/tree/f96ed24a8a535961b6ffc7160d98851a69cee4f8)，Windows | IPC session ID 与 Rime session 分开；每会话状态；按键后响应与 UI 更新；维护门控 | 适合参考宿主会话身份与引擎会话分离。多客户端桌面服务模型不构成 Android 引入多 session 池的充分理由 |

Trime 的 JNI 批量响应已经存在，因此“只有本项目做了批量 JNI”不成立。相反，本项目的上下文失效保护和提交保序值得独立保留。比较应看完整调用链，而非只数 JNI 方法或类的长度。

Foxy、元书的反编译内容仅留在本地研究目录，没有作为开源源码引用或复制进生产代码。借鉴开源项目实现时仍应按各自实际文件许可证处理；本次未引入代码或依赖。

## 安装包核对与比较限制

两种元书包装里共有的 `classes.dex` 与全部 6 个 ARM64 原生库逐文件 SHA-256 完全一致，且两侧没有独有的 DEX/SO。因此本轮将它们作为一个代码样本。

- APKS：71,699,806 字节，SHA-256 `a998bbd45a87f96514f23e0acb279792f508b675bc43bd983c21a92980eb59cc`。
- 合并 APK：87,168,705 字节，SHA-256 `6a9ac66df8a49cf6589995e0fd5bb7acc549b05a24f8bc128a5713d56c486335`。
- 同一个 `librime_jni.so`：3,384,472 字节，SHA-256 `5cf873f27da064a8f45a3cbb84d80f4602d7d1c5c10bed290cc2146f3c3378bd`。

打包压缩方式导致外层体积不同，不代表性能不同。元书含方案 ZIP，Foxy 也有内置 Rime 数据；本项目不预装中文方案。不同 APK/DSO 的功能范围、静态链接内容和构建选项不同，不能用总体积直接评定直连层效率。

## 本轮验证

| 检查 | 结果与边界 |
| --- | --- |
| 当前工作区核心 JVM 测试 | Gradle 成功；15 份报告，98 项记录，其中 84 执行通过、14 跳过，无 failure/error |
| 严格 JUnit 报告检查 | 未通过零跳过条件。14 项均来自 AssetMigrationTest 的实际 Windows 符号链接创建失败，错误为“客户端没有所需的特权”，未计为通过 |
| 原生已有契约 | 实际 engine.cpp + 锁定 SDK 头文件，Windows MSVC 运行 6 个未改的非文件系统契约函数，全部通过 |
| 原生调用计数 | 1,000 组 DOWN/UP 复现快照读取次数；没有据此推算毫秒或耗电 |
| 原生同步失败探针 | 复现 failure 通知之后 sync 仍返回 true；这是缺陷复现成功，不是被测行为合格 |
| 候选点选和刷新 JVM 探针 | 隔离副本运行 1 项探针测试，复现上述 2 个场景；failure/error/skipped 均为 0。通过表示预期缺陷已复现，并不表示行为正确 |
| 完整 Linux native gate | 未运行；Windows 便携探针用空 sys/resource.h 兼容 INPUT_DIAGNOSTICS=0，未替代 ASan/UBSan、I/O 包装及真实符号链接用例 |
| Android 功能和性能 | adb devices 为空；未安装 APK、未跑真机/模拟器回归、未比较端到端 P50/P95/P99、内存或电量 |
| 新版 Debug/Release APK | 本次审阅未出包、未签名，也不将旧 APK 当作本次构建结果 |

追加 JVM 探针最初被主工作区正在修改的 Material 样式属性阻断；这个资源链接错误不属于直连层发现。桌面工具建立的 C 盘 worktree 又因写入权限无法运行 Gradle，因此后续使用 D 盘相同提交的独立本地副本。所有缓存/测试临时目录继续使用工作区工具环境；未更改系统权限。

本轮核对了生产引用和 CI 调用：输入门控、事件队列、候选读取均在实际链路；direct-rime/pr/ci 工作流仍有相应 JVM、native、APK 和设备测试入口。“CI 中存在入口”只说明测试配置仍在，不表示这些外部工作流在本轮被触发或通过。

本轮证据：[汇总与样本摘要](../audit/direct-rime-review-20261106/summary.json)、[JVM 探针源码](../audit/direct-rime-review-20261106/ReviewCandidateSelectionProbeTest.kt.txt)、[原生探针源码](../audit/direct-rime-review-20261106/native-probe.cpp.txt)、[原生运行输出](../audit/direct-rime-review-20261106/native-probe.txt)。源码附件使用 `.txt` 后缀以便站点直接下载，复核时移除该后缀。完整下载副本、反编译输出和测试日志保留在本地 `.dsh-tmp/direct-rime-review-20261106/`，没有复制用户安装包或反编译源码到文档仓库。

复核 JVM 探针时，可将它放入同一固定提交的独立 checkout 的 app 测试源码目录，运行 `:app:testFxDebugUnitTest --tests org.fcitx.fcitx5.android.core.ReviewCandidateSelectionProbeTest`。原生探针与生产 `engine.cpp` 一同编译，include 使用锁定 SDK 的 librime 头文件；Windows 兼容头只用于本轮 `INPUT_DIAGNOSTICS=0` 的便携运行。C 盘受限 worktree 的归档请求被桌面工具的主 checkout 保护拒绝，因此保留该目录，未手工删除。

## 后续验收建议

先修复候选显示版本、刷新坐标和同步结果三个问题，再开展一次统一性能测量：

1. 使用同一台 ARM64 设备、同一刷新率和相近温度；固定每个 APK、引擎、方案、Lua 和用户词库初始快照。每个输入法使用自己的工作库。
2. 分开测进程冷启动、同应用复用、跨应用重绑、敏感输入返回、首键、首次点选、连续输入和展开深分页。
3. 同时记录触摸/硬键事件、服务队列、owner 等待、native core/context/tabs、commit 请求和目标应用首帧。系统注入/轮询时间单独标记。
4. 每场景报告有效样本数、P50/P95/P99及异常样本；少量冷启动样本只列原始值/中位数，不包装成稳定的 P99。记录 PSS、Java/native/Lua 内存与 GC/缺页信息。
5. 每次优化都同时检查丢字、重复提交、DOWN/UP 配对、过期编辑器和懒候选尾部，随后再比较速度。

这会回答“慢在直连桥、方案、存储还是 UI”，也能判断无变化快照复用和候选批量调整是否值得实施。
