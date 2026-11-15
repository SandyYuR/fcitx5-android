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
