# 2026-11-15 Foxy API 配套 PR 二次审阅

本记录对应代码 [PR #39](https://github.com/SandyYuR/fcitx5-android/pull/39) 与文档 [PR #40](https://github.com/SandyYuR/fcitx5-android/pull/40) 的本机审阅和修改。结果限定于下列来源与本次工作区补丁，不继承此前提交的设备验收结论。

## 来源与评论

| 项目 | 固定来源 |
| --- | --- |
| 代码 PR HEAD | `74d3d04908873d6adce1e2403eaee449555e8047`，`feat/js-foxy-alignment` |
| 代码目标分支 | `3e0a870001a1f59ee33bc1e4dd0971b8d37b225d`，`fx-rime-only` |
| 文档 PR HEAD | `8180fbc1fce59485ed729f240995f1538c622fbd`，`docs-foxy-alignment` |
| 文档目标分支 | `f9100162c7262643accf2607f3e0ce81f7f007c5`，`docs` |
| 固定 SDK | `android-sdk-20261110-1`，librime `b5561910d9eba97a273e09e7996535679b730032` |
| SDK 压缩包 SHA-256 | `d22bf604adc844ac568c375b57b2ff575f3fa9c05e46f33ead394bd31bf815e9` |

重新检查了原审阅的 WSS、Promise 交错、取消、SSE 预算、能力表、WebSocket 入站队列和连接回收意见，以及最新两条评论：[无换行尾行丢失](https://github.com/SandyYuR/fcitx5-android/pull/39#discussion_r4577351103)、[发现阶段的 SSE 能力说明](https://github.com/SandyYuR/fcitx5-android/pull/40#discussion_r4577361528)。GitHub API 达到读取限额后从 PR 页面读取评论，并以 Git 远端引用核对 HEAD。

## 本次修改

| 问题 | 最终行为与验证入口 |
| --- | --- |
| 非 SSE 响应 `alpha\nbeta` 丢掉最后一行 | EOF 与超限分别判断；合法尾行照常交付。恰好 2 MiB 的 EOF、LF、CRLF 行接受，超限仍拒绝。`JsAppStreamsTest` 使用实际 TLS 响应覆盖边界。 |
| 空 `data:` 事件消失，空首行破坏多行连接 | 独立记录是否存在 `data` 字段，空值可形成事件，连接换行按字段数量计算。保留此前 EOF 交付含 `data` 的尾事件的兼容行为。 |
| 读取密钥期间端点或授权变化 | SSE 与 WebSocket 构造认证头后复核端点与授权；普通 HTTP 每次核验都从当前设置重建端点范围，避免异步等待前的旧快照继续授权。SSE 在密钥读取前后核对当前会话、能力及主机批准，并在同步发送前检查协程仍活动，拒绝已取消的密钥读取继续发送。 |
| 非法 WebSocket 请求头占用连接槽 | 完整同步请求校验在登记连接槽之前完成；上限为一时，非法头失败后仍可正常连接。 |
| 旧回调继续写设置；密钥或未知字段重名 | 持久化与来源失效使用同一个 Session 锁，写入前核对编辑器和来源。仅允许声明的 `text/select/switch/number`；同键出现密钥或未知类型时拒绝。`JsAppSourceLifecycleTest` 与 `JsVoiceFieldsTest` 守护。 |
| 旧式布局最多八按钮在窄屏被挤出 | 使用现有 `JsAppActionRow`，保留事件 ID 和字段规则；240 dp、字号 1.5、LTR/RTL 组件测试检查八个完整按钮及其事件。 |
| 初次求值等待宿主函数时取消，Kotlin 尚未取得 pending ID | 调用前分配宿主调用 ID，Prelude 使用该 ID 登记异步槽；初次求值尚未返回时取消或超时也能定位并清理。旧的两参数桥调用保留数字编号兼容。真实 QuickJS 回归检查取消后槽归零，以及宿主工作迟到完成后不重新登记槽。 |
| 能力与回调时序文档矛盾 | describe/import 的发现并集包含 SSE，但不能调用网络；App 与 voice 运行态能力分开。说明纯 JS Promise 与宿主 asyncFunction 的不同等待边界。 |

`JsVoiceInvokerInterleavingTest` 补充真实 QuickJS 回调依次等待 editor、UI 宿主异步函数后完成原 App Promise 的组合，检查结果归属与 pending 槽最终回收。原有调度隔离、流取消与预算实现继续保留；本次没有依据测试假造的宿主等待循环修改生产调度。

## 验证记录

本机验证完成时，修改保留为工作区补丁；以下 APK 使用表内代码 HEAD 加补丁构建。随后交付代码提交为 [`d69ac8049fe7005ff068ee068ecfd65b783cc86e`](https://github.com/SandyYuR/fcitx5-android/commit/d69ac8049fe7005ff068ee068ecfd65b783cc86e)，完整 Git tree 为 `def691d69d0b7b747be14ecf06e5dafe857c5583`。已核对该提交相对审阅 HEAD 的二进制 diff 与验证补丁摘要一致，源文件、资源及 SDK 锁保持相同。文档的交付提交由 [PR #40](https://github.com/SandyYuR/fcitx5-android/pull/40) 当前 HEAD 记录。

提交后基于 Git 距离和 SHA 生成的版本元数据会变化；本页 APK 摘要继续对应当时实际验证的 HEAD 加补丁，保留其构建身份。

代码补丁 `git diff --binary --no-ext-diff HEAD` 的 SHA-256 为 `3fddf1802059035c68ebeb5396816b5dcc06e7f6cc5ce7e0e6d6222c7e1bee67`，与上述代码 HEAD 合起来标识此次验证输入。

| 验证 | 最终结果 |
| --- | --- |
| `:app:testFxDebugUnitTest --tests 'org.fcitx.fcitx5.android.input.voice.*'` | 45 份 JUnit 报告，355 tests、0 failures、0 errors、0 skipped；包含真实 bundled 脚本契约、TLS 传输、布局组件及异步取消回归。 |
| ARM64 `:app:assembleFxDebug` | 构建通过；本次 APK 白名单、16 KiB 对齐与链接审计通过，无 Fcitx C++ 符号。 |
| x86_64 `:app:assembleFxDebug` | 构建通过；本次 APK 白名单、16 KiB 对齐与链接审计通过，无 Fcitx C++ 符号。 |
| 签名核对 | 两个 APK 均通过 `apksigner verify`，与当前 `rime` 中已安装 Debug APK 的证书摘要相同。 |

| APK | SHA-256 |
| --- | --- |
| ARM64 Debug | `48bc362b267a7714c8ce7d205b2acb071505cf6adcdbab08cefeeeb788eac1bd` |
| x86_64 Debug | `e9cdd0a8996b0945aff5795072923123606cc8a010632912d183aa44a9a9f6ae` |

Debug 证书 SHA-256 为 `86f8c54c1917b6ef2c71a43ae8e5100b1b5164b06702113289e4c89b2257b3eb`。APK 门禁使用本次生成的 APK、主仓库 `app/.cxx` 构建目录与固定 `native/rime-sdk`，每次构建通过后保存对应 ABI 的产物，避免被后续 ABI 构建覆盖。

此记录和三页接口说明保存后，收尾执行站点构建、链接、历史完整性、目录、搜索及 diff 检查。最终检查输出保存在本机 `.dsh-tmp/review-foxy-20261115/docs-final-*.log`，与代码测试、APK 和补丁摘要一起保留，不以旧检查继承新结果。

首轮 JVM 执行了 352 项测试，2 失败、0 错误、0 跳过。失败均来自新增测试：恢复空授权集合误用 `approve`，以及在独立宿主结果完成前再次 JS 求值造成测试自身等待循环。修正测试编排后重新验证；失败 XML 和日志保留于本机 `.dsh-tmp/review-foxy-20261115/`。文档链接和历史检查曾在构建生成页面前启动，缺少生成页的失败日志也保留；构建完成后的对应检查均通过。

补充最终 SSE 发送守卫前，352 项 JVM 与 ARM64 构建及 APK 门禁已通过；为让最终测试与 APK 对应同一补丁，主动停止当次 x86_64 构建，随后按最终补丁重新执行。停止的日志保留，不计作构建验收。

补充初次求值取消清理前，354 项 JVM 与 ARM64、x86_64 构建及两项 APK 门禁通过；该轮编译未包含新增的初次求值取消用例，因此单独保存为中间验证记录，最终结果以包含该修复与回归的完整测试和重新构建为准。

新增取消用例的首轮 355 项执行有 1 失败、0 错误、0 跳过：测试错误地要求取消一次求值也自动取消 QuickJS 自有 scope 中的宿主函数，实际 pending 槽清理已完成。回归改为先检查槽已回收，再独立完成宿主工作并检查迟到结果不复活槽，没有增加超时。网络任务与整个 App 会话的关闭仍走各自显式取消路径；单次调用清槽不代表任意宿主函数都会被自动取消。

## 设备边界

核对唯一 AVD `rime` 在线，序列号 `emulator-5574`，API 35、x86_64。本轮尚未安装 APK 或运行设备回归，保留其长期数据；设备安装仍须本机工作区明确授权。

现有 `ClipboardSearchDeviceTest` 会创建和切换临时 Rime profile、替换剪贴板测试数据、调整输入法及外观，并注明使用 disposable Debug emulator；本次没有在长期保留数据的 AVD 上直接运行它。JVM 的 TLS 传输和组件测量不能替代实际设备上的网络回调、输入连接、触摸及窗口验收。

现行接口与范围见 [JS 宿主协议](../implementation/js-voice-plugins)、[中文接口参考](/features/voice-input-js-reference-zh-CN)及[英文接口参考](/features/voice-input-js-en)。

## 后续独立复审：回调超时与 SSE 行格式

本轮从已推送的代码 `d69ac8049fe7005ff068ee068ecfd65b783cc86e` 与文档 `8aeeee6c5d9dee88399811752016668d77cfa561` 开始，重新读取两个 PR 的全部审阅和普通评论，核对前述问题已修复。此次新增修正如下：

| 问题 | 修正与回归 |
| --- | --- |
| 单个 WS 回调超时结束唯一消费者，SSE 回调超时结束流读取 | 两条入口共用 `JsAppCallbackDispatch`，隔离调用自己的超时，先检查父协程仍活动；普通取消、会话关闭和外层超时仍传播。真实 QuickJS 回归检查超时后仍交付下一条 WS／SSE 事件，等待槽归零；另测显式取消、消费者取消及外层超时。 |
| SSE 流首 BOM 丢掉第一事件，CR 分隔被当作正文 | SSE 专用读取器接受 LF、CRLF、CR，流首只剥一个 UTF-8 BOM。CR 立即结束本行，下一轮吞可选 LF；按已检查位置继续扫描，保留 2 MiB 行和事件预算。六个回归覆盖 BOM、CR 及时交付、逐字节分块、大小边界和普通文本兼容。 |
| 将 `app` 对话框节点误写为 Foxy 原有节点 | 从 Foxy 节点表移出，明确为中州鹅 App 对话框扩展。原始参考中的 `type: "app"` 是键盘调用入口，本宿主键盘继续使用精确插件／App 绑定。 |
| 本机引擎缓存路径过时 | 根据当前本机脚本，修正源码缓存与构建目录为工作区 `.build/rime/source-cache` 和 `.build/rime/windows`。 |

SSE 格式依据为 [WHATWG 事件流解析规则](https://html.spec.whatwg.org/multipage/server-sent-events.html#parsing-an-event-stream)；EOF 尾事件仍保留本宿主已有的兼容行为。本轮没有更改依赖、SDK 锁、签名、插件授权模型或手机数据。

修改前先重新执行现有 355 项 voice 测试，全部通过。加入回归后、SSE 修复前执行 27 项目标测试，4 失败、0 错误、0 跳过，复现 BOM、CR、分块及预算问题；回调超时隔离的四项测试通过。初版 CR 回归依赖服务端响应结束行为，最终改为受控响应源：完整 CR 事件交付前若继续读取，测试立即失败，避免用延时猜测及时交付。

修复后的首轮完整执行为 364 项、1 失败、0 错误、0 跳过。诊断确认失败的分块夹具协商了 HTTP/2，却用 `MockResponse.setChunkedBody` 预先写入 HTTP/1.1 chunk framing，导致 SSE 只得到结束事件。最终夹具明确使用 HTTP/1.1；另用每次仅读一字节的受控响应源检查 BOM 与 CRLF 跨缓冲边界。失败及诊断结果保留，不以延长等待或放宽断言处理。

本轮日志、失败及最终 JUnit、源码和 APK 身份保存在本机 `.dsh-tmp/foxy-second-review-20261115/`，与上文前一轮产物分开保留。本轮仍未安装 APK 或执行设备回归，设备边界不变。

本轮代码提交为 [`bbdf4f686af52a4c915c573a777d143469383422`](https://github.com/SandyYuR/fcitx5-android/commit/bbdf4f686af52a4c915c573a777d143469383422)，完整测试与 APK 构建在该提交生成后执行，以该提交及固定 SDK 为验证输入。

### 本轮最终验证

| 验证 | 结果 |
| --- | --- |
| 完整 voice JVM 测试 | 46 份 JUnit，365 tests、0 failures、0 errors、0 skipped，包含真实 bundled 脚本。 |
| ARM64 Debug | 构建、原生库白名单、16 KiB 对齐和链接审计通过。 |
| x86_64 Debug | 构建、原生库白名单、16 KiB 对齐和链接审计通过。 |
| APK 签名 | 两包均通过签名校验，证书与前轮保留的 Debug 证书摘要一致。 |

| 本轮 APK | SHA-256 |
| --- | --- |
| arm64-v8a Debug | `e1c72a137445f5eee547a1c5227ccf1daa2d6a14db044d8083dcfb295bba623f` |
| x86_64 Debug | `04c5d51350b8bc4feeff63425b572bbb84e746f22729c1e48a0212eed0c20b15` |

两包只包含对应 ABI 的 `libc++_shared.so`、`libdirect-rime.so`、`libquickjs.so`，链接审计使用本次 `app/.cxx` 与固定 `native/rime-sdk` 输入。本节保存后执行文档构建、链接、历史完整性、目录、搜索和 diff 检查；输出保留在本轮证据目录的 `docs-final-*.log`，同时由 PR #40 的构建检查验证文档提交。上述构建与测试不代表手机设备验收。

## 后续 CI 修复：关闭会话与后台回收竞态

代码提交 `bbdf4f686af52a4c915c573a777d143469383422` 推送后，[CI 的 arm64-v8a 构建任务](https://github.com/SandyYuR/fcitx5-android/actions/runs/43695275401/job/132754298746)在完整 `:app:testFxDebugUnitTest` 中执行 2413 项测试，有 1 项失败：`JsAppStreamsTest.sseEventsAreParsedAndDispatchedInOrder` 报 `NoSuchElementException`，日志定位到测试方法的 `JsAppStreamsTest.kt:86`。这是本次远端完整测试的失败结果；上节本地 365 项 voice 测试通过的记录继续保留，其测试范围与此次 CI 不同。

审阅定位到生产关闭路径中的集合竞态：`JsAppStreams.closeAll()` 对 `ConcurrentHashMap.entries` 调用 `toList()`。Kotlin 的集合转换会先读 `size`，当观察到仅一个条目时走单元素分支，直接取迭代器的下一项；若后台 `release()` 在这两步之间移除最后一条流，转换就会抛出 `NoSuchElementException`。并发映射允许安全遍历，但不能保证这次独立读取的大小与随后取得的迭代器内容一致。因此异常来自会话清理，不能作为 SSE 顺序断言不稳定而忽略。

`JsWebSocketTransport.closeAll()` 存在相同的 `connections.entries.toList()` 模式，连接失败或正常结束时的后台回收同样可能与关闭相遇，本次一并处理。修复范围是让关闭与新流／连接登记使用同一把锁串行化，并直接遍历并发映射关闭条目，去掉依赖预读集合大小的转换；已经由后台释放的条目保持幂等处理。取消授权等待、网络调用和打开等待槽的原有边界继续保留。

| 本轮来源 | 记录 |
| --- | --- |
| 失败代码提交 | `bbdf4f686af52a4c915c573a777d143469383422` |
| 开始修复时的文档提交 | `6bb51d976ca2b243bf600d1db3e4b1fa39ad87c0` |
| 失败 CI | Run `43695275401`，job `132754298746` |
| 本地证据目录 | `.dsh-tmp/foxy-ci-fix-20261115/` |
| 已保存证据 | `failed-arm64-job.log`、任务详情、检查注解、对应代码／文档提交的检查状态及两个 PR 的读取结果 |

最终修复来源及验证结果见下表。原失败日志与不利结果继续保留在上述证据目录，不将旧测试或旧 APK 结果计作此次修复通过；本机设备验收仍受前述边界约束。

修复前新增四项确定性回归，全部失败：Streams 与 WebSocket 的两项集合回收用例均得到 `CollectionsKt.toList` → `closeAll` 的 `NoSuchElementException`；两项登记用例均证明关闭线程提前结束，未等待已经通过检查的登记完成。失败 XML 与日志保留于本轮证据目录的 `race-before-fix-junit/` 和 `race-before-fix.log`。

修复在登记锁内设置关闭标志并用并发映射自身的遍历收集、移除条目，出锁后才取消 Job／Call／socket，避免取消回调持有登记锁。测试同时检查资源已失活及真实等待任务已取消，使用有界 latch／join 并确保失败时释放测试线程。

修复代码提交：[`4eeb272b906497a69254bb2fafae90bc4e27b2db`](https://github.com/SandyYuR/fcitx5-android/commit/4eeb272b906497a69254bb2fafae90bc4e27b2db)。

提交后的 voice JVM 测试执行 369 项（47 份 JUnit），失败／错误／跳过均为零，包含上述四项关闭竞态回归。完整 JVM 与远端 CI 结果如下。

### CI 修复后的最终验证

| 验证 | 结果与证据 |
| --- | --- |
| Linux PR CI 完整 JVM | [JVM unit tests](https://github.com/SandyYuR/fcitx5-android/actions/runs/43699131967/job/132766136460)：295 份 JUnit，2417 tests、0 failures、0 errors、0 skipped。 |
| 原失败 ARM64 任务 | [Full frontend ARM64 APK](https://github.com/SandyYuR/fcitx5-android/actions/runs/43699131905/job/132766136574) 通过；日志同样确认 2417 tests、0 failures、0 errors、0 skipped，后续 APK 门禁通过。 |
| 代码 CI 汇总 | 本次代码提交的 6 项检查全部成功，包括原生契约、引擎 smoke、完整前端触摸与引擎设备测试、Debug 构建及两项 JVM／ARM64 任务。 |
| 本机 voice JVM | 369 tests、0 failures、0 errors、0 skipped，包含四项确定性关闭竞态回归。 |
| 本机完整 JVM | 2417 tests、0 failures、0 errors、30 skipped；30 项均实际创建符号链接时被 Windows 拒绝。严格 JUnit 门禁因此未通过，环境限制如实保留；完整覆盖由上方 Linux CI 验证。 |
| 本机 ARM64／x86_64 Debug | 同一修复提交构建，两包均通过原生库白名单、16 KiB 对齐、链接及签名校验。 |

| CI 修复版 APK | SHA-256 |
| --- | --- |
| arm64-v8a Debug | `9b953f6e35aa3c9906d9efc9bebd5c7cc6966df21a8df24d3e4fd345746cef10` |
| x86_64 Debug | `88feb7d5bc6e1b93f05926a826cf308c48b30da9957a1b29f2fd26147c5f2500` |

本机跳过原因逐项保存在 `windows-symbolic-link-skips.json`，完整 XML 保存在 `full-jvm-junit/`；两个成功的 Linux JVM 日志与代码 CI 状态快照也保存在本轮证据目录。本轮没有修改测试过滤、跳过规则、CI workflow、SDK 锁或本机权限，也没有安装 APK 或操作本机模拟器。文档保存后重新运行五项门禁和 diff 检查，并由配套 PR #40 的新提交继续验证。
