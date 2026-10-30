# fx2 代码复审报告 · 2026-10-11

> **文档状态**：2026-10-11 只读复审快照，由工作区 `D:/GitHub/fx2/CODE_REVIEW_fx2_2026-10-11.md` 原文搬入（仅把本地绝对路径链接改为文档站内可用的写法，正文结论逐字保留）。
>
> 本报告是对 2026-09-03 报告（93 个编号）的逐项复核：78 项已修复、9 项部分修复、1 项未修复、5 项因功能移除不再适用，并追加 8 项新纳入问题（含引擎启停回退 N8）。"已修复"指当前代码消除了旧报告的主要触发链，不等于每项已通过真机回归。
>
> 当前事实以代码分支 `fx-rime-only` 的 `3d0af21e` 为准；文档源码位于 `docs` 分支的 `docs/maintainer/`。

## 结论

以 2026-09-03 报告的 93 个编号逐项复核，当前结论为：**78 项已修复，9 项部分修复，1 项未修复，5 项因功能/构建移除而不再适用**。对照 Rime 专版交接后，另列 **8 项本轮新纳入的问题或相关回归**，包含此前漏审的引擎启停回退 N8。历史修复、测试记录与当前风险分开判定。

最先处理输入法可用性和数据正确性：引擎启停状态机、Direct Boot 启动、Android 10 圆角 API、Android 15 同步服务超时、数据库备份、异步保存快照及编辑器草稿；随后收尾同步内存边界和用户可见功能回归。详细顺序见下文。这里的“已修复”指当前代码消除了旧报告所描述的主要触发链，**不等于每项已通过真机回归**。

## 基线、范围与判定方式

- 历史基线：CODE_REVIEW_fx2_2026-09-03.md，原 fx2 `3ad25fc9`。保留原编号；历史建议作为待核实的资料，未作为本轮修改指令。
- 参考交接：[HANDOVER-rime-only.md](/legacy/fcitx/notes/HANDOVER-rime-only)（本地文件最后更新于 2026-10-11）。已替换先前的 fx2 修复交接参考，以其 Rime 专版机制、回退记录及验证边界辅助复核；历史 SHA/快照均以当前代码核对。文中 runbook、推送/发布流程作为参考资料，未作为本轮执行指令。
- 当前代码：`D:/GitHub/fx2/repo`，分支 `fx-rime-only`，提交 `3d0af21ec431b49bf5d9f4fb86a7d3b0cc22a51b`。
- 已从 origin 拉取并快进 `37aef03a` → `3d0af21e`，新增 22 个提交、73 个文件、+5813 / -702 行。本次复审不仅看这段增量，还回查旧报告 93 项涉及的当前代码。
- 复审覆盖 Kotlin/Android 主体、同步、导入导出、布局编辑、渲染缓存、生命周期及相关构建配置；新增复核 Kotlin 引擎启停与 JNI 退出边界（N8）。旧报告提及的另册 16 个安全条目未提供完整正文，不计入本次 93 项结论；本轮未完整重审原生 Fcitx/librime 及其定制补丁。
- 本地原有子模块偏移保留：gitlink 记录 `27cca6e0`，实际本地 Fcitx 子模块 `442edbc9`。因此本地 checkout 的原生引擎不等同于远端 gitlink。
- 只更新、复核和写报告；未修改项目源码、提交或推送。旧报告保持原样。此前当天的 5 项增量报告保留为 CODE_REVIEW_fx2_2026-10-11_INCREMENTAL.md，本报告取代它作为本次综合结论。

状态含义：已修复＝旧主要触发链消除；部分修复＝有有效修复但仍有同类残留或收尾缺口；未修复＝原主要实现仍在；不再适用＝功能/产物移除，不计作实现修复。当前优先级重新评定：P1 为可用性、数据丢失或高影响问题，P2 为局部行为/性能问题，P3 为低频优化或清理。旧报告的 P0/P1/P2 不直接沿用，避免把历史级别与当前残留混淆。

## 旧问题状态统计

| 分组 | 已修复 | 部分修复 | 未修复 | 不再适用 | 总数 |
|---|---:|---:|---:|---:|---:|
| A 崩溃与资源边界 | 10 | 1 | 0 | 0 | 11 |
| B 数据保存与恢复 | 7 | 3 | 0 | 2 | 12 |
| C 行为与生命周期 | 28 | 2 | 0 | 2 | 32 |
| D 主线程阻塞 | 11 | 3 | 0 | 0 | 14 |
| E 渲染热路径 | 11 | 0 | 0 | 0 | 11 |
| F 泄漏与状态增长 | 7 | 0 | 0 | 1 | 8 |
| G 网络与 IO | 4 | 0 | 1 | 0 | 5 |
| **合计** | **78** | **9** | **1** | **5** | **93** |

旧项仍需处理：**A9、B1、B5、B9、C21、C32、D7、D10、D11、G1**。其中 G1 当前未修，C32 是低优先级清理，其余有修复或结构变化但未闭环。B3/B4/C15/F3 随键盘内可拖动调整面板删除，C29 随独立插件构建移除而不再适用。

## 对照 Rime 专版交接后的调整

1. **追加引擎启停风险 N8，排到修复首位**。[交接的 09-26 回退记录](/legacy/fcitx/notes/HANDOVER-rime-only)明确把回退定位为对照包；当前 `git log/show` 及 Fcitx/FcitxLifecycle/FcitxDispatcher 仍保留回退后的实现。补查与独立复现确认三个问题没有收敛。这组问题早于本次拉取的 22 个提交，不能归因于最近这批 harness 修改；与旧 C31 的断连异常修复是不同问题。
2. **补充已有历史真机证据，限定适用范围**。[中央按钮行记录](/legacy/fcitx/notes/HANDOVER-rime-only)有 OnePlus 8 组场景回归（关联 E8）；[字体热刷新记录](/legacy/fcitx/notes/HANDOVER-rime-only)有虚拟键盘字号/字体热刷新验证（关联 D14）。这是交接所记录的历史结果，本轮没有重新执行；物理键盘分支、长按气泡和后续新布局仍不能一并判通过。交接也记载 09-28 符号面板和子模式“默认”项已装机验证，不再笼统写成所有 UI 改动都从未真机验证。
3. **收紧修复方案的行为约束**。[旧 swipe 契约](/legacy/fcitx/notes/HANDOVER-rime-only)要求无线索旧 swipe 保持旧槽，支持 N4 的往返缺陷判断；[候选角标契约](/legacy/fcitx/notes/HANDOVER-rime-only)要求栏内序号不加全局 indexOffset，N3 应刷新移动项的本地位置角标。[随机主题契约](/legacy/fcitx/notes/HANDOVER-rime-only)要求生成槽位不顺手切主题；N1 的 Direct Boot 修复必须保留这一行为。
4. **区分已经修好的 tabs 与待定的自定义辅助按键布局**。[纵向 tabs 滚动记录](/legacy/fcitx/notes/HANDOVER-rime-only)与当前代码一致：音节 tabs 超过六项已走可滚动 RecyclerView。自定义辅助按键仍按均分/rowHeightPercent 压缩，见 "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:971"。交接把未配置行高时的最小高度/滚动改动列为暂缓的设计限制，本报告不将它混入 N5，也不建议简单回退均分逻辑；N5 是另一个 Rime 方案对话框。
5. **修正构建证据的含义**。当前 `.github/workflows/ci.yml:159`确实只依赖 APK build，JVM 单测失败不会阻止 Nightly；本次 head 的测试仍已单独核实为通过。`prepare_personal_build.sh:8`会用适配层/prebuilt 的浮动 master，源码 gitlink 不足以唯一描述最终 APK。当前 gitlink 是 core `27cca6e0`、适配层 `107502d1`、prebuilt `1126616c`；交接中的旧适配层 SHA 不照抄为当前值。本轮未核对远端 run 的最终引擎 SHA/产物指纹，构建成功不等于原生行为验证。后续构建应记录实际 SHA、补丁结果与产物指纹；准备脚本的 patch 失败目前只 echo，宜区分已应用和失败并让真正失败中止。

93 项旧编号的状态计数保持不变；追加 N8 后，新纳入的问题组由 7 组变为 8 组，修复批次由 16 批变为 17 批。本次只修改报告及独立验证材料，未修改交接文档、其它 checkout 或项目源码。

## 修复顺序与验收目标

同级按影响范围、数据风险和触发频率排序；相关项可合并成一个修改批次。以下是修复建议，不是本轮已执行的修改。

| 顺序 | 优先级 | 编号 | 要做的事 | 最小验收 |
|---:|---|---|---|---|
| 1 | P1 | N8 | 启动可取消、失败收敛至 STOPPED、停止等待移出 Main | STARTING 停止/启动失败重试/慢退出三个故障注入均通过 |
| 2 | P1 | N1 | 随机主题读写改为 Direct Boot 安全路径 | 首次解锁前 IME 可启动；解锁后卡片存在且生成不换主题 |
| 3 | P1 | N2 | 两处 Outline.setPath 的版本保护改为 API 30，API < 30 退化为 setRoundRect（不可用 setConvexPath） | API 29 主题预览、上下不同圆角键盘无崩溃 |
| 4 | P1 | C21 | dataSync 超时取消任务并真正停止 Service | API 35/36 缩短配额后无 RemoteServiceException，不无条件重启 |
| 5 | P1 | B9 | checkpoint 失败中止备份，建立一致快照，兼容旧 WAL 备份 | 活跃读写中导出/恢复条目一致，含 WAL 的旧备份不丢记录 |
| 6 | P1 | N7 + D7 | Main 捕获唯一不可变保存快照，IO 只处理该快照 | 保存期间编辑/再保存后，磁盘、baseline、dirty 状态一致 |
| 7 | P1 | B1 | 宏/弹出/字体编辑器保存并恢复草稿 | 后台杀进程返回后内容、顺序及 dirty 状态仍在 |
| 8 | P1 | B5 | 零字节布局判为损坏；替换失败保留原文件并返回失败 | 空文件不静默回退覆盖，失败注入不破坏旧文件 |
| 9 | P1 | A9 | 网络帧/加密信封预检，下载流式落盘，压低全量复制峰值 | 超限帧/无 Content-Length/未知 URI 大小均受控；低堆设备验证 |
| 10 | P2 | N4 | 旧 swipe 编辑往返保留原方向语义 | 只改标签/颜色在 Auto、Up、Down 下行为不变 |
| 11 | P2 | N6 | OneClip 普通请求与 SSE 超时分离，取消时 cancel Call | 服务器接连不发响应时能超时；关闭同步及时退出 |
| 12 | P2 | N3 | 候选位置改变后刷新本地序号角标 | 实际 RecyclerView 插入/删除后栏内从 0/1 编号正确 |
| 13 | P2 | N5 | Rime 方案菜单恢复主题化滚动列表 | 15+ 方案、横屏、大字号能选择最后一项，保留主题外观 |
| 14 | P2 | D10 | 图标主题二维码解压、解析、保存全部后台化 | QR/相机入口不在 Main 读写主题文件，慢存储无卡顿 |
| 15 | P2 | D11 | 补齐宿主规则资源，规则加载后台化 | APK 含可用规则、首次启动无 Main 编译规则，示例 URL 清理正确 |
| 16 | P3 | G1 | 复用连接池/Dispatcher，同时隔离会话 cookie | 重连不反复创建池；切服务器/账号不串 cookie |
| 17 | P3 | C32 | 删除无入口 Activity 或接入明确入口 | 全仓引用/导航及 Manifest 一致 |

## 未闭环的旧问题

### [P1] B9：数据库导出并非一致快照，旧 WAL 备份可能丢记录

位置："app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:118"、"app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:273"、"app/src/main/java/org/fcitx/fcitx5/android/data/UserDataManager.kt:229"。

已修：导入前关闭 Room、拒绝高版本数据库、取消降级清表、处理旧目标库的 sidecar。残留在导出和旧格式兼容：checkpointDatabase 只 moveToFirst，没有读取 busy/log/checkpointed 结果，异常也只记日志；导出随后无条件过滤 WAL/SHM 并复制仍开着的主数据库。活跃读事务会阻止 checkpoint 完成，之后的写入/自动 checkpoint 也可与裸复制交错。

本地 SQLite 3.50.4 复现：保持一个读事务，再提交第二条记录，TRUNCATE 返回 `(1, 1, 0)`；主库单独复制后的记录数是 1，正在运行的库中是 2。此项是真实 SQLite 行为验证，未运行 Android Room。对于旧版本导出的、带匹配有效 WAL 的备份，当前导入无条件跳过 WAL，也会丢失只在 WAL 中的已提交记录。

建议：让 checkpoint 返回可验证结果，失败明确中止；使用适用于最低系统版本的一致快照策略，或在共同读写锁下暂停写入、完成 checkpoint/关闭连接后复制并恢复服务。旧备份先在隔离的临时目录恢复其匹配 WAL，生成完整主库，再校验 schema 并导入。旧报告“导入时直接过滤所有 WAL”的建议需修正；过滤只适用于已经生成一致主库的备份。SQLite 官方说明 [checkpoint busy 的返回列](https://www.sqlite.org/pragma.html#pragma_wal_checkpoint)，并提供 [一致快照的备份机制](https://www.sqlite.org/backup.html)。

### [P1] C21：Android 15 超时处理没有停止服务

位置："app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt:1561"，目标 SDK 36："build-logic/convention/src/main/kotlin/Versions.kt:13"。

服务类型已正确改成 dataSync，但 onTimeout 只 stopPeriodicSync、stopHealthMonitor、stopForegroundState，然后调用 super；整个 MainService 没有 stopSelf。撤销通知不满足系统的停止要求。在配额超时后，系统已不把它视为前台服务，几秒内未停止会触发 RemoteServiceException。此服务与 IME 没有 Manifest 进程隔离，异常可影响输入法进程。

建议：取消全部同步/连接工作、释放资源，最终调用 stopSelf/合适的 stopSelfResult。记录暂停原因，避免健康检查或下一次 IME 回调在配额未重置时立即重新启动。不能将“再次打开键盘”当作配额必然重置。按 [Android 官方超时测试说明](https://developer.android.com/develop/background-work/services/fgs/timeout) 在 API 35/36 调小时间预算验证。未在设备执行此项。

### [P1] B1：旋转规避已做，部分编辑器仍丢失进程恢复草稿

位置："app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/dialog/MacroEditorActivity.kt:714"、"app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/PopupEditorActivity.kt:155"、"app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/FontsetEditorActivity.kt:148"。

六个 Activity 的 configChanges 已补上，布局/按键/按钮编辑器也有草稿机制。但 MacroEditorActivity 没有 onSaveInstanceState，onCreate 始终从启动 Intent 读取 initialSteps，新增步骤、改顺序等只在内存中。PopupEditor 和 FontsetEditor 同样重读文件，没有保存编辑数据的草稿恢复。configChanges 只覆盖部分配置变化，不能保护系统回收进程。

建议：保存宏 steps、编辑目标、原始 baseline 和 dirty 信息；弹出/字体编辑保存同样的草稿，恢复优先于文件/Intent。大型内容沿用 LayoutDraftStore 的文件引用策略，避免把整份大数据塞入 Bundle。用后台进程回收验证；“不保留活动”只能补充验证，不能替代进程恢复场景。

### [P1] B5：空布局遗漏损坏保护，原子替换失败仍直接覆写

位置："app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/data/LayoutDataManager.kt:130"、"app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/data/LayoutDataManager.kt:544"。

有效修复仍在：一般保存写临时文件、sync、rename；非空文件解析失败时要求确认覆盖。但现存零字节文件使 fileHasContent=false，被当作没有用户文件，既不解析也不产生 ParseFailed 提示。用户打开的是默认布局，随后保存直接覆盖，旧损坏检测路径遗漏了“截断为 0 字节”的常见形态。

writeAtomically 的 rename 失败分支还直接 file.writeText，重新进入旧报告指出的非原子覆盖路径。因为临时文件本就在同目录，“跨文件系统”不是这条路径的正常理由。建议现存空文件也判损坏；替换失败保留原文件并返回保存失败；使用 AtomicFile 或等价恢复协议，明确处理 sync/替换失败。源码条件模型已经确认空文件没有损坏提示，未运行 Activity UI。

### [P1] A9：尺寸预检已补，网络信封与内存峰值仍有缺口

位置："app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/SyncClient.kt:1450"、"app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/ClipCascadeClient.kt:310"、"app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/ClipCascadeClient.kt:461"。

已做 HTTP/URI 上限、部分 metadata 预过滤、业务 Base64 解码前估算，不能再说完全无保护。但 readAtMost 仍用 ByteArrayOutputStream + toByteArray；32 MiB 文件继续叠加 Base64、JSON、加密明文/密文等副本。ClipCascade 在业务接收上限前已完整接收 text、拆 STOMP、解析信封并 Base64 解码 ciphertext，再创建 sealed 数组、解密；这里没有对信封与密文的预算检查。过大数据即使最终被业务拒绝，之前的分配已发生。

建议：按协议分别限制帧、信封、密文及解密后的 payload，尽可能在大分配前拒绝；HTTP 下载直接有限流写临时文件，multipart/可流式上传用 Source/RequestBody，Base64 协议降低单项预算并减少副本。仅给 onMessage 增加长度判断可减少后续复制，不能防止 WebSocket 库在回调前接收完整消息；传输层也需可执行的消息预算或分片协议。此项经代码确认，未做低堆 Android OOM 实测。

### [P2] D7：主要文件路径已异步，草稿仍在主线程完整序列化和写盘

位置："app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/TextKeyboardLayoutEditorActivity.kt:733"、"app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/TextKeyboardLayoutEditorActivity.kt:739"。

正常 load/save 已移到 IO。保存未提交草稿时，onSaveInstanceState 仍在 Main 构建完整 JSON 并同步写草稿文件；点击进入按键编辑器也会停止宿主 Activity，触发该路径。大布局或慢存储下仍有可见停顿风险。保存本身对 UI 活数据的并发访问另见 N7，比这一性能收尾更紧急。

建议：编辑过程按版本号更新不可变草稿，并去抖后台写入；onSaveInstanceState 只保存已写草稿的引用与必要的小型增量。需要确保最后一次编辑也可靠恢复，不能简单在生命周期回调里丢一个随即会被取消的异步写任务。验证草稿恢复与主线程帧时间。

### [P2] D10：图标二维码入口仍在 Main 解压、解析和保存

位置："app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/icon/IconThemeListActivity.kt:248"、"app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/icon/IconThemeListActivity.kt:262"、"app/src/main/java/org/fcitx/fcitx5/android/data/theme/IconThemeManager.kt:504"。

文件 JSON/ZIP 导入、二维码图像识别及 PNG 导出已后台化。图像识别后却回 Main 执行 XZ 解压并调用 importThemeFromJson；相机集齐片段也直接调用该函数。函数继续 JSON 解析、填缩略图、写文件、清理 PNG。旧“图标主题导入主线程工作”的问题仍在这些入口。

建议将集片后的解压/主题解析及保存放到 Default/IO，Main 仅更新列表和提示；导入文件、图片二维码、相机二维码应复用同一个 suspend 导入函数，避免入口只修一半。需要慢存储、复杂图标主题 UI 验证。

### [P2] D11：宿主规则同步加载，规则资源来源也未闭环

位置："app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/HostClipboardFilter.kt:10"、"app/src/main/java/org/fcitx/fcitx5/android/FcitxRemoteService.kt:134"、"app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt:533"。

旧 OutgoingClipboardFilter 与插件读取路径已删除，但宿主 HostClipboardFilter 仍在调用线程同步读 asset、解析 JSON、编译正则；FcitxRemoteService.onCreate 在 Main 用 runBlocking 调 updateClipboardManager，后者 isEnabled/description 直接触发懒加载。MainService 的两处 transform 也仍在 launch 外，没有预热入口。

另一个实际缺口：当前受版本控制的源码、构建脚本和资源里没有 `clearurls/data.min.json`，也没有下载/生成该文件的构建步骤；插件删除后没有把规则接入宿主打包。按当前构建定义，宿主打开此资源会失败并缓存 null，URL 清理静默失效。尚未下载最终 APK 检查资源，因此将资源结论限定于当前源码与构建定义；不声称当前已实测大规则主线程耗时。

交接 §3.1 明确删插件后出站过滤继续走 HostClipboardFilter，因此修复方向应是补齐宿主规则资源与后台初始化。提供受版本控制/构建验证的规则资产，后台初始化不可变规则集，Main 只发布就绪状态；相关转换也进入后台路径。加构建资产检查及示例 URL 功能验证。

### [P3] G1：重连时仍新建网络客户端

位置："app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/OneClipEventClient.kt:30"、"app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/ClipCascadeClient.kt:97"、"app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt:820"。

旧实现仍在：连接对象被重连循环重新创建，每个对象新建 client；close 取消连接，没有共享跨重连的池/dispatcher。update 的 sharedClient、同一个 ClipCascade 对象内 httpClient.newBuilder 不等于跨连接实例复用。

建议由共享基础 client.newBuilder 派生不同超时的 client，或明确共享 ConnectionPool/Dispatcher；cookie jar 仍按账号/服务端隔离。此项是资源/性能收尾，未观察到当前设备线程增长，不放在崩溃或数据问题之前。

### [P3] C32：仍保留无入口的镜像列表 Activity

位置："app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateMirrorListActivity.kt:24"，Manifest 已注册。

其他旧死字段及重复重载已移除，ActivityNotFoundException 隐患也已通过注册消除。当前全仓 UpdateMirrorListActivity 只有类定义与 Manifest 引用，没有导航入口，所以清理项没有完全完成。确认产品入口不需要独立列表时删除，或接入明确入口并验证返回路径。此项不是发布阻断项。

## 新增问题与相关回归

### [P1] N8：启停回退留下启动不可停止、失败卡状态及 Main 阻塞

位置："app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:559"、"app/src/main/java/org/fcitx/fcitx5/android/core/FcitxLifecycle.kt:43"、"app/src/main/java/org/fcitx/fcitx5/android/core/FcitxDispatcher.kt:108"、"app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt:139"；Main 调用点 "app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:2195"。交接来源：[Rime 专版交接](/legacy/fcitx/notes/HANDOVER-rime-only)。

`8843b858` 曾修正启停状态机，`e7e32462` 为定位卡键回归回退了 8 个文件；当前历史明确保留该回退，Fcitx.kt 最近一次修改仍是它。问题不是最近 22 个提交新引入的，也不能把原修复提交的存在当作当前已修证明。具体残留：

- **STARTING 无法停止**：Fcitx.stop 只允许 READY，启动期间最后一个 client 断开会直接跳过；生命周期 ON_STOP 同样只允许 READY。启动继续完成后可以留下无 client 的运行实例，或让紧接着的停止/重启请求失效。
- **启动异常后无法恢复**：Dispatcher.finally 清 isRunning/isAcceptingJobs 并 nativeExit，但没有通知 FcitxLifecycleRegistry 收敛状态。DataManager.sync/nativeStartup 等抛异常后仍停在 STARTING；Fcitx.start 要求 STOPPED、stop 要求 READY，于是普通重试与停止都被拒绝。
- **停止同步阻塞调用线程**：Dispatcher.stop 用 runBlocking 等 runningLock，覆盖 native loop 退出及 nativeExit。IME.onDestroy → FcitxDaemon.disconnect、Application 的重启广播等在 Main 直接调用；慢原生退出会阻塞界面，可能造成 ANR。JNI 里退出跳过 Rime 全量同步的 saveWithoutRime 修复仍在，应保留，但它不能保证原生退出永远快速。

独立复现直接编译当前 FcitxLifecycle/FcitxDispatcher，并提取 Fcitx.start/stop 原函数，外部 JNI/Android 依赖用受控替身：STARTING 调 stop 状态不变、没有请求 native exit；注入 startup 异常后仍为 STARTING，再 start 的次数仍为 1；注入 350 ms 原生退出延迟，stop 调用线程阻塞 367 ms。该验证确认 Kotlin 控制逻辑，未运行真实 JNI/Android ANR 场景。

建议按当前代码恢复可取消启动与完整状态收敛：用生命周期代号/互斥序列防止旧启动的 READY 事件污染新实例；任何启动失败和退出路径最终清理监听/队列并到 STOPPED；Main 发起停止后异步等待原生 teardown，把启动、停止、重启协调为可等待且串行的操作。`8843b858` 可作实现参考，集成时同时复测当初的“编码不上屏、状态区空白、切换才恢复”，避免仅凭历史提交直接回切。

最小验收：阻塞启动时 stop/restart/最后 client disconnect、startup 异常后重试、重复 stop/start、旧 READY 延迟到达、慢 nativeExit 及快速 IME 切换；Main 不等待原生退出，完成后无孤立引擎或永久 STARTING。按本报告当前分级列 P1 并排首位；交接原用 P0-6 的历史级别不直接套用。

### [P1] N1：随机主题的普通 prefs 访问破坏 Direct Boot 启动

位置："app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeManager.kt:99"、"app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeManager.kt:215"、"app/src/main/java/org/fcitx/fcitx5/android/FcitxApplication.kt:149"。

randomThemePrefs 使用普通 Application appContext，randomSlot 在对象初始化时立即 loadRandomSlot；读取在 runCatching 外。设备重启后首次解锁前，默认凭据加密存储不可访问，而 IME 声明 directBootAware 且 Application 仍初始化 ThemeManager。即使用户未启用随机主题，也会触发槽位读取并使启动失败。

仅吞读取异常不够：init 还无条件 ensureRandomSlotSeeded 并写同一 prefs。建议使用 Direct Boot 可用 context，或未解锁期间完整跳过槽位加载、生成与写入，解锁后再加载；需要此阶段保留随机主题时，同步相关偏好/主题数据至设备加密存储。遵守交接 §0.6.21 的槽位契约：解锁后卡片仍默认常驻，seeding 只生成、不切换活动主题。依据启动调用链与 [Android Direct Boot 存储说明](https://developer.android.com/privacy-and-security/direct-boot)，未在锁屏设备执行。

### [P1] N2：API 29 分支调用 API 30 的 Outline.setPath

位置："app/src/main/java/org/fcitx/fcitx5/android/input/bar/ContinuousCornerGeometry.kt:223"、"app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt:1748"。

两处用 >=Q 保护 setPath，该方法实际从 R/API 30 提供。Android 10 主题预览的 CardOutlineProvider 进入此路径，实际键盘上下圆角不同时也会调用，抛 NoSuchMethodError。纯几何单测不会触及系统方法。

[Outline 官方 API](https://developer.android.com/reference/android/graphics/Outline#setPath(android.graphics.Path))。需在 API 29 验证主题预览及不同圆角键盘，当前未执行设备复现。

**2026-10-19 修订**：原文"建议两处都改 >=R；API 29 用 setConvexPath"只覆盖了 Bug 1，且 API 29 用 setConvexPath 的建议是对的（Q 起该方法不再校验凸性，见下），但漏掉了 API 28 及更低设备的第二条崩溃路径：

- AOSP android-10.0.0_r1 的 "Outline.java" 全文没有 setPath，只有 setConvexPath → API 29 走 setPath 必抛 NoSuchMethodError，Bug 1 成立。
- AOSP android-9.0.0_r1 的 setConvexPath 方法体为"if (convexPath.isEmpty()) { setEmpty(); return; } if (!convexPath.isConvex()) { throw new IllegalArgumentException("path must be convex"); }"，即 R 之前真的校验凸性；android-10.0.0_r1 的同类方法已去掉该校验（注释"As of Q, it is no longer required to be convex"）。
- 而老 Skia（android-9.0.0_r1 "platform_external_skia src/core/SkPath.cpp"）的 Convexicator 是按坐标符号翻转次数判凸：`int sx = sign(vec.fX); fDx += (sx != fSx); ... if (fDx > 3 || fDy > 3) fConvexity = kConcave_Convexity;`（sign 为"负数=1/非负=0"二值）。按 "ContinuousCornerGeometry.addCardPath" 的真实几何逐行模拟：纯矩形（上下半径均 0）判 convex，**只要上圆角 > 0 就全部判 concave**（矩形四边自身已累计 dx=3、dy=4，越过阈值），与弧线真凸性无关，是旧启发式的几何盲区。
- 因此 API <= 28 的设备走 setConvexPath 会抛 IllegalArgumentException: path must be convex，且与圆角是否真凸无关。结论：两处都需要 >=R 才用 setPath，并且 **API < 30 不能退回 setConvexPath**，必须退化成 Outline.setRoundRect（旧机唯一无校验的四角同半径形状）。

修复已按此实施（"ContinuousCornerGeometry.applyCardOutline" + "legacyUniformRadius"，键盘侧拆成三条独立分支），见 "ContinuousCornerGeometry.kt:111-112"（legacyUniformRadius）与 ":129-145"（applyCardOutline）、"InputView.kt:1728-1765"。

### [P2] N3：候选窗口平移后栏内序号角标没有重新绑定

位置："app/src/main/java/org/fcitx/fcitx5/android/input/candidates/horizontal/HorizontalCandidateViewAdapter.kt:192"、"app/src/main/java/org/fcitx/fcitx5/android/input/candidates/horizontal/HorizontalCandidateViewAdapter.kt:151"。

栏内编号取绑定时 position；prefix/suffix 更新保留相同后缀，只发结构通知。活动候选可见性逻辑切出更靠后窗口时，后缀项移动但文本/引擎标签没变，不会重绑角标。

实际 CandidateUpdatePlan 源码复现：窗口 `[A,B,C,D,E]` 活动位置 2 → `[B,C,D,E]` 活动位置 1，只删除位置 0，没有内容/高亮重绑；从 1 编号仍显示 `[2,3,4,5]`，应为 `[1,2,3,4]`。从 0 编号与空引擎标签的序号回退同样受影响。

建议对编号依赖位置的模式，显式更新结构变更后移动的后缀项，或专门刷新可见 badge；保留引擎标签语义。交接 §0.6.14 明确序号表示栏内位置，因此不能通过加全局 indexOffset 来掩盖缺失重绑。补实际 RecyclerView 操作的仪器测试，手工重复 onBind 无法覆盖此问题。[RecyclerView 结构通知规则](https://developer.android.com/reference/androidx/recyclerview/widget/RecyclerView.Adapter#notifyItemRangeRemoved(int,int))。当前是源码计划复现，未执行 Android UI。

### [P2] N4：旧划动宏仅修改外观后保存，也会改变方向

位置："app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/dialog/KeyEditorActivity.kt:1220"、"app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/dialog/KeyEditorActivity.kt:1229"。

运行时/迁移保留没有方向线索的 legacy swipe，使其继续服从符号隐藏时 Auto/Up/Down 语义；编辑器却把它解释成一个物理方向，并在写回时无条件删除 swipe、写 swipeDown。仅修改标签、颜色或宽度也会丢上划行为，Up 用户可能方向反转。

最小数据：`{"type":"MacroKey","label":"x","tap":{"macro":["x"]},"swipe":{"macro":["!"]}}`。提取当前纯迁移/编辑函数执行：迁移返回 false 保留 swipe，编辑读写后只有 swipeDown。建议直到用户明确修改方向前保留 legacy 数据/语义，或依据原实际行为展开槽位。补 Auto/Up/Down 下仅修改非方向字段的往返验证。

### [P2] N5：Rime 方案菜单没有滚动容器

位置："app/src/main/java/org/fcitx/fcitx5/android/input/dialog/RimeSchemaMenuDialog.kt:70"。

原 single-choice 列表改成每行 48dp 的 RadioButton，全放在普通纵向 LinearLayout，再 setView(root)，没有 ScrollView/ListView/RecyclerView。15 个左右方案、横屏或大字号时后面条目超出窗口，用户无法滚动选择。

建议恢复主题化可滚动列表，或给方案区加受高度约束的滚动容器，保留交接 §0.6.20 的菜单整体背景色与主题一致性。验证较多方案、横屏、大字号最后一项可达。这与 §0.6.17 已改成可滚动的音节 tabs 是两个不同入口。代码结构已确认，未设备实测。

### [P2] N6：OneClip 初始化请求无读超时且不能取消

位置："app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/OneClipEventClient.kt:30"、"app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/OneClipEventClient.kt:64"、"app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/OneClipEventClient.kt:139"。

普通 GET `/` 和 `/api/current` 的 warmup 使用和 SSE 同一个 readTimeout(0) client，阻塞 execute。服务器/代理接收连接但不返回响应时，warmup 可永久占 IO 线程；close 只 cancel activeCall，而 warmup 的 Call 从未存入该字段。取消 syncJob 不能打断这次 execute，重试也无法推进；随后 SSE 建连的 connectSignal.await 也没有建立阶段超时。

这与 C16 已修的 ClipCascade 路径相似，但在另一个后端仍存在。建议普通请求独立有限读超时/整体 callTimeout，建立 SSE 有期限，存储并取消正在执行的普通请求 Call，或用支持协程取消的异步桥接。用接收请求后保持沉默的本地服务验证超时与关闭同步，不把长期 SSE 的 idle 误当连接失败。当前通过调用链确认，未执行网络复现。

### [P1] N7：异步保存的文件与 baseline 来自两份不同的活数据

位置："app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/data/LayoutDataManager.kt:486"、"app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/data/LayoutDataManager.kt:499"、"app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/data/LayoutDataManager.kt:513"，调用点 "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/TextKeyboardLayoutEditorActivity.kt:2454"。

IO 保存先从可变 entries 构建/序列化 JSON，再读取同一活 entries 得到 snapshotEntries，写前者，却把后者设成 originalEntries；主线程编辑未冻结，也没有版本/互斥保护。先序列化 `a`、随后用户改成 `b`、再捕获 baseline `b` 的合法时序下，磁盘是 `a`、UI/baseline 都是 `b`，hasChanges=false。退出不再提示，`b` 静默丢失。把 baseline 捕获移动到 write 之前，只修了“写盘过程中编辑”，没有覆盖两次快照读取之间的窗口；IO 内 normalization 本身也会修改 UI 共享对象。

源码顺序模型已复现 `disk=a, current=b, baseline=b, hasChanges=false`，不是完整 Android 保存路径运行。建议 Main 上一次性捕获深不可变快照与 revision；验证、规范化、序列化、写盘都只用该快照。成功后在 Main 发布该同一快照为 baseline；较新编辑仍保持 dirty。用 Mutex/队列串行化多次保存，避免共用 `.tmp` 相互覆盖；深复制需覆盖嵌套宏/map。补可控制两线程时序的保存一致性测试。

## 93 项逐项复核索引

每条标题沿用旧报告，编号链接回历史原文，证据链接指向当前 checkout。表中“修复”是本次源码复核判断，验证边界见最后一节。

### A · 崩溃与资源边界

| 编号 / 旧问题 | 当前状态 | 当前证据与判断 |
|---|---|---|
| A1（旧报告:68） 布局行内出现非对象元素 → 每次唤起键盘崩溃 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/utils/LayoutJsonUtils.kt:525"。行内非对象元素会被跳过并记录日志，旧崩溃入口已消除；对应解析测试仍在。 |
| A2（旧报告:87） 缺 `tap` 的 MacroKey：本项目自己就能写出让键盘崩溃的配置 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/utils/LayoutJsonUtils.kt:1360"。运行时跳过缺 tap 的宏键；LayoutDataManager.validateKey 同时拒绝保存缺 tap 的 MacroKey。 |
| A3（旧报告:105） 存储路径含裸 `%` → 点确定即崩溃 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/ui/StoragePathUtils.kt:26"。非法百分号及有损 UTF-8 解码回退原字符串。 |
| A4（旧报告:123） 测试连接期间旋屏/返回 → 必崩 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/ui/SettingsActivity.kt:212"。测试请求使用 viewLifecycleOwner.lifecycleScope，回主线程检查 isAdded，销毁视图时关闭进度弹窗。 |
| A5（旧报告:143） 镜像规则的 replacement 非法组引用 → 点下载即崩溃 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateCheckActivity.kt:527"。下载实际替换有完整异常处理；保存侧增加试运行校验。样例不匹配时校验可能漏掉错误，但下载会提示并中止，不再崩溃。 |
| A6（旧报告:152） 图标主题 ZIP 全量入内存 → zip bomb 导致 OOM 且拦不住 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/data/theme/IconThemeManager.kt:70"。压缩包输入 16 MiB、JSON 1 MiB、文件项 4 MiB、文件累计预算及项数受限，解压文件直接暂存磁盘，旧无界 ByteArray 列表已移除。 |
| A7（旧报告:170） XZ 解压未设内存上限 → 畸形二维码可触发 GB 级分配 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/share/LayoutQrTransferCodec.kt:222"。XZ 内存预算 4 MiB，并保留 256 KiB 输出限制。 |
| A8（旧报告:179） 内嵌 HTTP 服务按 Content-Length 直接分配 + 连接数无上限 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/webeditor/ImeWebEditorBridgeServer.kt:863"。请求体分配前检查 4 MiB 上限，超限响应 413；连接线程与队列有界。 |
| A9（旧报告:196） 同步收发全量入内存，尺寸过滤发生在解码之后 | 部分修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/ClipCascadeClient.kt:461"。HTTP、URI 读取有 32 MiB 上限，业务 Base64 解码有预检；WebSocket/STOMP/加密信封仍先完整解析和解密，传输仍有多份全量复制。见待办 A9。 |
| A10（旧报告:213） 选取图标时直接用 provider 提供的 DISPLAY_NAME 拼路径，并有 `!!` 断言 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/icon/IconThemeEditorActivity.kt:274"。显示名为空有回退，落盘使用净化文件名及扩展名白名单，读取为空正常报错。 |
| A11（旧报告:230） 7z 导入走 java.nio.file，而 desugar 不含 nio 且只有该分支无 SDK 守卫 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/ShareReceiveManager.kt:1765"。7z 判定与执行均保护 API 26；旧报告的低版本运行时崩溃未经真机确认，本次确认防护代码已到位。 |

### B · 数据保存与恢复

| 编号 / 旧问题 | 当前状态 | 当前证据与判断 |
|---|---|---|
| B1（旧报告:250） 六个编辑 Activity 漏 `configChanges`，且全模块 0 个 `onSaveInstanceState` → 旋屏丢失全部编辑 | 部分修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/dialog/MacroEditorActivity.kt:714"。6 个 Activity 已补 configChanges，布局、按键、按钮草稿已持久化；MacroEditor、PopupEditor、FontsetEditor 仍无自有草稿恢复。见待办 B1。 |
| B2（旧报告:259） 拖拽排序后编辑/删除写到错误条目，覆盖其他按钮 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/ButtonsCustomizerActivity.kt:1253"。拖动后点击使用当前 ViewHolder 位置及当前数据对象，避免改错/删错按钮。 |
| B3（旧报告:290） 进"调整按钮"面板拖动一次，永久删除 buttons.json 里的一部分按钮 | 不再适用 | `245e4bf1` 删除实现及入口。键盘内按钮调整面板已移除；不能据此声称旧分区保存算法已修复。设置页按钮编辑器仍保留。 |
| B4（旧报告:311） 拖拽结果只在 `onDetached` 保存，而销毁路径不触发它 | 不再适用 | `245e4bf1` 删除实现及入口。原面板与 InputView 的显示/隐藏路径一并移除，原“只在 detach 保存”的触发链不再存在。 |
| B5（旧报告:320） 布局保存非原子 + 解析失败静默回退 → 自定义布局永久丢失 | 部分修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/data/LayoutDataManager.kt:130"。正常保存有 tmp + sync + rename，非空损坏文件有覆盖确认；零字节文件遗漏损坏判定，rename 失败仍直接覆写。见待办 B5。 |
| B6（旧报告:329） 备份用秒级时间戳 + `overwrite=false`，同一秒二次保存静默失败 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/migration/DataMigrationManager.kt:67"。碰撞时追加毫秒/递增后缀，失败记录日志，旧同秒重名已处理。 |
| B7（旧报告:338） 每次加载都无条件跑迁移，且纯内存迁移失败却用旧备份覆盖完好的磁盘文件 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/data/LayoutDataManager.kt:173"。只在需要时迁移；内存迁移失败重读原内容并提示，不再用旧备份覆盖磁盘。 |
| B8（旧报告:347） 迁移逻辑静默删除子模式的 displayText | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/migration/DataMigrationManager.kt:241"。仅清理空映射，无匹配上下文但非空的显示文本映射保留。 |
| B9（旧报告:356） 导入备份时不关闭 Room、连 WAL 一起覆盖、降级即 dropAllTables | 部分修复 | "app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt:273"。已关闭 Room 后导入、拒绝更高 schema、去除降级清表；导出忽略 checkpoint busy/失败并裸复制，旧备份的有效 WAL 也被直接丢弃。见待办 B9。 |
| B10（旧报告:375） 历史增量下载了 N 条却只导入 1 条，游标照常推进 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt:948"。导入全部已接受历史项；SyncClipboard 有导入失败不推进游标的判断；只用末条更新系统剪贴板。 |
| B11（旧报告:392） 跨 Activity 结果靠实例字段闭包传递，宿主重建后静默丢弃 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/ButtonsCustomizerActivity.kt:611"。按钮目标 ID/宏结果 token 跨重建保存；KeyEditor 也按 slot 应用结果并恢复草稿，闭包消失有回退路径。 |
| B12（旧报告:410） 剪贴板暂存文件按源文件名命名 → 同名互相覆盖；32 文件上限与数据库不同步 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/utils/ClipboardUriStore.kt:137"。暂存名带 nanoTime，清理成功后回调 ClipboardManager 删除对应库内引用。缓存本身仍可能被系统回收，未将其视为永久媒体存储。 |

### C · 行为与生命周期

| 编号 / 旧问题 | 当前状态 | 当前证据与判断 |
|---|---|---|
| C1（旧报告:434） "按住说话"松手后反而新开一次录音，且无法停止 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt:258"。完成/错误清除 hold 标记，松手只结束仍活跃的会话。 |
| C2（旧报告:453） AIDL 回调在 binder 线程改状态，与主线程竞态 → AudioRecord 永不 release，麦克风常开 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceInputProviderManager.kt:583"。AIDL 错误及结束回调把 teardown 放入服务 Main lifecycleScope，消除原录音启动/结束交错入口。 |
| C3（旧报告:475） 结束路径无超时兜底 → 麦克风按钮被永久锁死 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceInputProviderManager.kt:80"。结束阶段有 3 秒看门狗并检查会话代号。 |
| C4（旧报告:494） 重复 `onReady` 时旧 queue 不 close、旧 job 不 cancel → 协程与 channel 泄漏 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceInputProviderManager.kt:1017"。重复 ready 会关闭旧队列、取消旧 feed，并保留已运行 capture；队列也已改成有界。 |
| C5（旧报告:512） `AudioRecord` 停止路径的空 catch 与无条件丢弃 worker | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceInputAudioCapture.kt:147"。停止/释放异常记日志，join 超时保留 worker 引用，read 后重新检查 running。 |
| C6（旧报告:531） `resetState()` 的取消被运行时开关守卫 → job 空转且按键永久失效 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt:154"。长按与重复 job 无条件取消/清状态，不再受 Enabled 标志守卫。 |
| C7（旧报告:550） 合成态热重建在按压中途换 view 实例 → 长按连删中断 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:1895"。正在按压的键延后重建，UP/CANCEL 后处理待重建列表。 |
| C8（旧报告:567） `dismissAllPopups()` 不区分 viewId → 双指打字时选中字符不上屏 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupComponent.kt:351"。关闭弹出层支持保留 viewId，BaseKeyboard 保留另一个仍按住的 popup；重设 dismiss job 前取消旧 job。 |
| C9（旧报告:587） 行缓存复用后不重放文本缩放 → 字号回弹 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:623"。行缓存复用结束后重放字号缩放。 |
| C10（旧报告:596） "空格滑动移光标"开关对缓存行永久失效 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:193"。缓存空格键复用时重放划动阈值与开关，偏好监听同步更新基线。 |
| C11（旧报告:614） 手势 baseline 从已包装的 listener 反推 → 监听链无上界增长 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:366"。保留创建时原始手势基线，避免复用时反复包装已包装 listener。 |
| C12（旧报告:630） 提交类按键会误提交预测候选，且跨线程读 RecyclerView 字段 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt:87"。以合成状态判断提交，不再跨线程读 RecyclerView，也不再仅凭预测候选误选词。 |
| C13（旧报告:648） "+" 添加键未从拖拽中排除 → View 重排而数据被静默拒绝 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/DraggableFlowLayout.kt:28"。“+” chip 排除拖拽/落点，按实际可拖项数限制索引。 |
| C14（旧报告:666） `FlowLayout.onMeasure` 在 AT_MOST 下永不换行，且忽略 `heightMeasureSpec` | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/FlowLayout.kt:25"。AT_MOST 按实际限制折行，测量包含 margin 并遵守高度约束。 |
| C15（旧报告:684） `dragInProgress` 只写不读 → 拖拽中顶栏被整体重建，按钮视觉上消失 | 不再适用 | `245e4bf1` 删除实现及入口。原 dragInProgress 工具栏调整路径已随面板移除；当前工具栏采用固定子 View 的 KawaiiBarRowLayout。 |
| C16（旧报告:706） `runBlocking` + `readTimeout(0)` + 握手无超时 → 不可取消的永久挂死 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/ClipCascadeClient.kt:116"。原 ClipCascade 分支去掉 runBlocking，STOMP 等待有 15 秒超时，普通 HTTP 有独立有限读超时。OneClip 的类似遗漏另列 N6，未算作本条已覆盖。 |
| C17（旧报告:724） 键盘一收起同步就整体停摆，而通知仍显示"正在同步" | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt:97"。IME 隐藏进入 idle 节奏，前台状态与截图开关解耦，通知状态随运行模式更新；屏幕熄灭暂停有明确状态。 |
| C18（旧报告:741） 锁外序列化非线程安全集合 → ConcurrentModificationException 被吞 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt:2266"。共享 map/list 先在锁内复制，再序列化快照。 |
| C19（旧报告:759） PBKDF2 迭代次数由服务器下发且默认值与上游不一致 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/ClipCascadeClient.kt:270"。采用服务器 rounds，拒绝非正数，对过大值设预算；默认 664937，低轮数不再强行抬高导致密钥不一致。超预算宜明确拒绝而非修改，属于后续兼容改进。 |
| C20（旧报告:775） 快捷设置磁贴只翻 pref，不启动/停止服务 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/service/QuickSyncTileService.kt:57"。磁贴真正调用 start/stop 服务并同步偏好。 |
| C21（旧报告:784） 前台服务类型与实际用途不符 | 部分修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt:1561"。Manifest 与 startForeground 已统一 dataSync，但超时只停循环/通知，没有 stopSelf。见待办 C21。 |
| C22（旧报告:794） 带 `//` 注释的布局文件在真键盘上解析失败并静默退回内置布局 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/config/ConfigProvider.kt:322"。运行时布局/弹出预设读取与编辑器一致，支持行注释。 |
| C23（旧报告:811） `JsonNull` 被当成字符串 `"null"` 显示在键面上 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/utils/LayoutJsonUtils.kt:390"。文本字段使用 contentOrNull，JsonNull 不再变成字面“null”。 |
| C24（旧报告:820） 删掉自定义宏后按钮仍执行旧宏 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:667"。所有配置按钮重绑宏或默认行为，删除宏会清掉旧 listener。 |
| C25（旧报告:838） 更新页轮询在 `onPause` 不停止 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateCheckActivity.kt:186"。暂停时移除 800 ms 轮询，恢复时按需重启。 |
| C26（旧报告:848） hosts 设置页 `onPause` 无条件写回，并每次清空 release 缓存 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateHostsSettingsActivity.kt:113"。提交一组 hosts 设置并校验来源；Repository 对未变化值不重复写/失效。 |
| C27（旧报告:857） 二维码长图超过约 23 片后自己导不回来 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/share/JsonFileQrShareManager.kt:30"。安全采样基于 QR 方块、最小尺寸改 256，支持当前 64 片上限内普通长图；未采用旧建议的分页，但原约 23 片必拒绝已消除。大图内存与识别率仍需设备验证。 |
| C28（旧报告:866） 签名比对用 API 28 的 `signingInfo`，而 minSdk=23 → Android 6~8.1 上插件 IPC 全被拒 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/utils/PackageSignatures.kt:36"。两个调用点使用全 API 可用的签名比较，不再依赖 API 28 signingInfo。当前内置 Rime 不依赖独立插件。 |
| C29（旧报告:882） CI 产出的插件写死上游主包名 → 装到 fx 主程序上 IPC 全断 | 不再适用 | `settings.gradle.kts:24`。Rime 已内置，旧独立插件模块及 assembleReleasePlugins 流程移除（b1bba67b 等）；原插件产物包名问题不再适用于当前构建。 |
| C30（旧报告:899） `Logcat` 关闭后无法再次打开，子进程从不回收 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/utils/Logcat.kt:92"。停止时清 process 字段、关闭读流、destroy 并取消 job；快照/清日志回收子进程。旧用户可见的同实例重启场景本就少见，未继续当 P1。 |
| C31（旧报告:916） `FcitxDaemon` 用 `CancellationException` 表示"连接已断开"，调用方静默结束 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt:37"。断连错误改普通 IllegalStateException 子类，调用方可观察失败；best-effort runIfReady 单独处理。 |
| C32（旧报告:932） 死代码与未注册 Activity | 部分修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateMirrorListActivity.kt:24"。其他列出的死字段/重复重载已清理，Activity 已注册；UpdateMirrorListActivity 仍仅定义/Manifest 引用，没有入口。见低优先级清理 C32。 |

### D · 主线程阻塞

| 编号 / 旧问题 | 当前状态 | 当前证据与判断 |
|---|---|---|
| D1（旧报告:948） 每次按键在主线程 `runBlocking` 等 fcitx 单线程 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt:798"。候选更新热路径复用已广播 panel，不再每键 runImmediately；低频创建/attach 的同步读取仍在，未宣称全部同步调用已删。 |
| D2（旧报告:967） 每次长按都同步读盘 + 解析整个 PopupPreset（缓存判定写在读盘之后） | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupComponent.kt:106"。读文件前判缓存/mtime，未变化不再解析。 |
| D3（旧报告:984） 每 800ms 在主线程对整个 APK 做 SHA-256 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateCheckActivity.kt:963"。校验移入后台并按 URI/大小/修改时间缓存，轮询不重复同步哈希。 |
| D4（旧报告:994） 粘贴剪贴板图片在 IME 主线程做整文件复制 + 最多 480ms `Thread.sleep` | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/utils/ClipboardUriStore.kt:51"。粘贴路径在 IO 暂存后回 Main 提交，重试用 delay；调用点已改 suspend/协程。 |
| D5（旧报告:1012） InputView 构造在主线程解码全尺寸壁纸，且同一张图解两次 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt:765"。构造使用纯色占位，背景在后台解码，共享降采样位图缓存。 |
| D6（旧报告:1033） `IconThemeManager` 的 object 初始化在主线程做磁盘 IO + SVG 解析 + 回写 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/data/theme/IconThemeManager.kt:170"。对象不再隐式刷新磁盘；Application 显式启动 IO 初始化，默认图标可先显示。 |
| D7（旧报告:1052） 布局的加载/解析/备份/序列化/写入全在主线程 | 部分修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/TextKeyboardLayoutEditorActivity.kt:739"。主要 load/save 已进 IO；onSaveInstanceState 仍完整序列化/写草稿，保存还在 IO 遍历/修改共享 UI 数据。见 D7 与 N7。 |
| D8（旧报告:1061） 分享接收的 ZIP 读取与 JSON/Base64/XZ 解析在主线程 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/ShareReceiveManager.kt:493"。自动识别 ZIP/JSON/Base64/XZ 在 Default，读文件用 IO；Loading 生命周期跟识别阶段分离。 |
| D9（旧报告:1078） `ThemeManager.refreshThemes()` 在主线程做全量磁盘扫描并可能回写 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeManager.kt:304"。目录扫描进 IO，列表/活动主题更新回 Main。随机主题的 Direct Boot 新问题另列 N1。 |
| D10（旧报告:1092） 图标主题导入/导出：主线程无上限读取与 PNG 压缩 | 部分修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/icon/IconThemeListActivity.kt:248"。文件 JSON/ZIP 导入与 PNG 输出已后台化并限制输入；二维码识别后 XZ 解压/JSON 处理/主题写盘仍在 Main。见待办 D10。 |
| D11（旧报告:1108） 复制任意 URL 会在主线程加载并编译整套 ClearURLs 规则 | 部分修复 | "app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/HostClipboardFilter.kt:10"。旧插件加载路径已移除，但宿主 filter 仍同步懒加载，RemoteService.onCreate 在 Main 触发；规则资源目前也未接入构建。见待办 D11。 |
| D12（旧报告:1125） 截图监听每 5 秒在主线程查 MediaStore，且作用域从不取消 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/ScreenshotClipboardWatcher.kt:108"。查询/复制用 IO，停止取消轮询及子任务，系统剪贴板更新回 Main。 |
| D13（旧报告:1147） 分词剪贴板对长文本无任何上限 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardTextTokenizer.kt:26"。入口和分词预算 4000 字/1500 token，选择使用 payload 刷新。 |
| D14（旧报告:1164） 字体刷新标志"只读不清" → 每次聚焦输入框都全量重建组件树 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:1825"。关键输入生命周期内延后整树重建，字体/字号刷新改用 generation；交接记录虚拟键盘路径已做真机热刷新，物理键盘分支及气泡仍欠验证。 |

### E · 渲染热路径

| 编号 / 旧问题 | 当前状态 | 当前证据与判断 |
|---|---|---|
| E1（旧报告:1190） 模糊遮罩每帧对每个按键做一次"全屏位图绘制" | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt:289"。合并按键裁剪路径后一次绘制模糊图。 |
| E2（旧报告:1212） 水波纹每帧为每个 ripple 新建 `RadialGradient` | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWaterRippleView.kt:68"。渐变 shader 缓存并变换位置/半径，不再每个 ripple 每帧新建。 |
| E3（旧报告:1228） 模糊区域重建抖动：onDraw 内自重绘 + 同帧两次全树遍历 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt:152"。重试预算 4 次，统一请求刷新，目标视图缓存减少重复全树收集。 |
| E4（旧报告:1247） 辅助栏位置/内容的无差别重建 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt:4154"。辅助栏位置去重，内容复用子 View；CandidatesView 仅真实可见性变化回调。 |
| E5（旧报告:1266） 候选栏每次按键"全量刷新 + 递归 view.post" | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/candidates/horizontal/HorizontalCandidateViewAdapter.kt:107"。当前 prefix/suffix 计划做增量通知，active 单独刷新，布局期间更新合并；原无预算递归已替换。新角标的位置依赖遗漏另列 N3。 |
| E6（旧报告:1285） `textKeys` 每次全树递归遍历并新建 List | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:1242"。按样式刷新失效的惰性 TextKeyView 列表缓存。 |
| E7（旧报告:1294） 布局编辑器每次 bind 重建整行 chip（含 GradientDrawable） | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/adapter/KeyboardLayoutAdapter.kt:414"。chip 与背景 Drawable 复用，拖拽只刷新涉及行。全量数据/样式替换仍允许完整刷新。 |
| E8（旧报告:1313） `ButtonsBarUi` 把 position 当 viewType，复用池永不命中 + bind 内自触发全量刷新 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/idle/ButtonsBarUi.kt:41"。已换成固定子 View 行布局，旧 position=viewType 及 bind→notify 循环均不存在；交接另记录过 OnePlus 真机悬浮/单手/状态区等 8 组中央按钮行回归。 |
| E9（旧报告:1330） 预览刷新替换全局 provider 并清空静态 keydef 缓存，每次重建整棵键盘 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/preview/KeyboardPreviewManager.kt:143"。预览通过临时覆盖与独立 cache 前缀，未替换实际 ConfigProvider；内容/环境 signature 相同短路。 |
| E10（旧报告:1350） `MotionEvent.obtain` 从不 `recycle` | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:2715"。分发复制 MotionEvent 后 finally 回收。 |
| E11（旧报告:1359） `notifyDataSetChanged` 的广泛滥用 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateAssetAdapter.kt:71"。旧报告指定的高频三处已增量化。低频更换整份列表/样式的 notifyDataSetChanged 仍在，不把 API 出现次数当缺陷数量。 |

### F · 泄漏与状态增长

| 编号 / 旧问题 | 当前状态 | 当前证据与判断 |
|---|---|---|
| F1（旧报告:1370） 8 个回调赋给 object 单例后永不清空 → 整棵 InputView 树泄漏，且重建后状态打到已销毁 UI | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt:852"。按 owner 释放 8 类语音 UI 回调，反注册剪贴板/主题/偏好监听，InputView detach 调用此清理。 |
| F2（旧报告:1395） `SplitKeyboardStateManager` 监听器只增不减，且是一套死代码 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt:429"。无消费者的单例 listener 机制与键盘注册已删除。 |
| F3（旧报告:1404） `ButtonsAdjustingWindow` 是 `data object` 单例，视图树与首个 InputView 的 context 绑定并常驻 | 不再适用 | `245e4bf1` 删除实现及入口。ButtonsAdjustingWindow 单例整体移除，原 context 常驻路径不存在。 |
| F4（旧报告:1413） 分词 job 挂在 service 作用域且从不取消 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/TokenizedClipboardWindow.kt:121"。保存 tokenizeJob 并取消，UI 回写使用同一 job 的 withContext(Main)。 |
| F5（旧报告:1433） 静态 keydef 缓存无上限 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt:192"。静态缓存使用 access-order LinkedHashMap，最多 12 项；旧死字段移除。 |
| F6（旧报告:1441） `Logcat` 子进程与 pipe fd 留到 GC | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/utils/Logcat.kt:46"。快照流 use 关闭并 wait/destroy；清日志进程 wait/destroy。未做长期 fd/进程压力实测。 |
| F7（旧报告:1447） 内嵌 HTTP 服务线程池无界 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/webeditor/ImeWebEditorBridgeServer.kt:109"。8 个连接 worker + 1 个 accept worker，有限队列/拒绝策略，stop 关闭 executor。 |
| F8（旧报告:1453） 剪贴板同步状态无界写入默认 SharedPreferences | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/SyncStateStore.kt:224"。同步状态迁出默认 prefs 至私有文件，单条内容 32 Ki 字符预算、条数预算；写入合并。旧报告“条数无上限”的描述不准确。 |

### G · 网络与 IO

| 编号 / 旧问题 | 当前状态 | 当前证据与判断 |
|---|---|---|
| G1（旧报告:1466） 每次重连都新建 `OkHttpClient` | 未修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/OneClipEventClient.kt:30"。OneClip/ClipCascade 都仍在每次创建连接实例时 new Builder，重连循环再建实例。update 模块复用不等于这两个后端已修。见待办 G1。 |
| G2（旧报告:1474） `MemoryCookieJar` 整体替换 host cookie → 会话丢失后进入重连循环 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/ClipCascadeClient.kt:571"。按 name 合并 cookie 并移除过期项，避免一次响应覆盖会话 cookie。 |
| G3（旧报告:1482） 接收过滤每条目重建（含正则编译） | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt:1875"。接收 filter 缓存，相关偏好变更失效；分隔 Regex 常量复用。 |
| G4（旧报告:1490） MediaStore 全表扫描 | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateCheckActivity.kt:447"。MediaStore 按名称 selection + 修改时间排序，不再把全表拉到 Kotlin 过滤。 |
| G5（旧报告:1498） hosts 抓取用裸 `OkHttpClient()` | 已修复 | "app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateRepository.kt:302"。hosts 抓取共享 client，并强制 HTTPS 来源。 |

## 验证证据与剩余验证

- 远端当前 head 的 [Commit CI #38124605545](https://github.com/SandyYuR/fcitx5-android/actions/runs/38124605545) 成功：Release arm64 构建、JVM 单测、Nightly 发布均成功。已核对 head SHA 与 jobs；构建通过不能排除本报告的运行时 API/生命周期问题。
- 本地已完成 `gradlew.bat :app:testFxDebugUnitTest --offline --console=plain`：**48 个测试套件、388 个用例全部通过，失败/错误/跳过均为 0**，`BUILD SUCCESSFUL in 1m 50s`。使用 `JAVA_HOME=C:/dev/jdk-17`，`ANDROID_HOME` / `ANDROID_SDK_ROOT=C:/dev/android-sdk`；SDK 36、Build Tools 36.1.0、NDK 28.0.13004108、CMake 3.31.6 均与项目声明匹配。
- 更正此前环境结论：第一次 `SDK location not found` 表示 Gradle 未发现 SDK，并非本机没有 SDK。用户提供的 `C:/dev`、`D:/GitHub/ChuanyiHooker/tools` 中有可用工具；本次只设置命令进程环境变量，没有修改系统环境或 local.properties。工具清单：tooling-check.md；构建日志：gradle-sdk-offline.log；JUnit XML 汇总：gradle-unit-test-summary.json；本地 Gradle 测试报告（构建机产物，未入库）。
- 当前 CI 未执行 `app/src/androidTest`；新增 CandidateOverflowTest / CandidateIndexBadgeTest 未在这次 CI 被运行。几何 JVM 测试也不调用真实 Outline API。
- 独立 Kotlin 2.4.10 验证：直接编译仓库 CandidateWord / CandidateUpdatePlan，执行 N3 更新计划；从当前源码提取纯迁移/编辑 helper 执行 N4。证据：run_repros.py、results.txt。
- 补充本地 SQLite 行为验证 B9；根据当前条件/语句顺序运行 B5、N7 模型。证据：reaudit_repros.py、reaudit-results.txt。SQLite 测试是真实数据库执行；B5/N7 是模型，不是 Activity/完整保存函数运行。
- 对照新交接后独立验证 N8：当前 Lifecycle/Dispatcher 原源码 + 提取的 Fcitx.start/stop，在受控 JNI/Android 替身下复现三类启停问题。证据：engine_lifecycle_repro.py、results.txt。这一独立复现不计入项目的 388 例；未修改或新增仓库内测试。
- 交接所记录的历史真机证据包括中央按钮行 8 组回归、虚拟键盘字体/字号热刷新、09-28 符号面板与子模式默认项验证；来源与边界见“对照 Rime 专版交接后的调整”。本轮未重新装机验证这些历史结果。
- `git diff 37aef03a..HEAD --check` 通过。项目工作区仅保留拉取前已有的 Fcitx 子模块版本偏移。
- Direct Boot、API 29/35/36、长方案菜单、录音生命周期、真实 RecyclerView、布局恢复与媒体粘贴仍需设备/模拟器回归。本地 JVM 单测已经通过；已修的 78 项中，UI、Binder、Room、资源释放和性能项仍多数只有源码确认及构建证据，未冒充真机通过。本次未运行完整原生构建或 androidTest；两个指定 SDK 目录均未发现 emulator 可执行文件。

建议的回归组合：启动/停止/重启故障注入与快速 IME 切换；API 23/25 检查 7z 拒绝及旧签名路径；API 29 检查圆角；API 35/36 检查 dataSync 超时；有锁屏密码的设备检查首次解锁前启动；打开大布局并在后台回收进程检查草稿；保存与继续编辑并发检查 dirty；数据库忙时导出及带有效 WAL 的旧备份恢复；真实候选结构通知与 Auto/Up/Down 宏往返；15+ Rime 方案横屏滚动。补上交接仍欠的 KeyboardWindow layerHistory 集成验证、音节 tab 首次选择/取消约束、首次安装或真实配置变更后的 Rime 部署期按键与提示，以及新手势、随机主题、双标签与圆角的真机检查。

本报告的阻断项来自代码和已记录验证，不以历史修复清单、CI 绿灯或问题数量替代验收。
