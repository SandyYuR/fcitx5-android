# fx-rime-only 系统性代码审阅报告

审阅基线：SandyYuR/fcitx5-android，分支 fx-rime-only，提交 e51afd887fb623e4b8fa1baed554dc4e1ae2ef32。审阅结束时 HEAD 与 origin/fx-rime-only 一致，工作树干净。日期：2026-09-07。

## 1. 执行摘要

本次只读审阅覆盖 Android/Kotlin 主体、IME 生命周期、Fcitx/Rime JNI 与 C++ 前端、剪贴板同步、语音、更新器、数据导入、Gradle/CI、权限/备份/网络安全，以及 origin/docs 与当前 docs 的差异。静态索引约 672 个 Kotlin/Java/C++/XML/Gradle/AIDL 文件、约 103,641 行；app 主源码含约 403 个 Kotlin 文件、13 个 C/C++ 文件，测试文件 17 个。

Rime-only 的架构目标已基本实现，但继续自动发布 Nightly 前应处理以下发布阻断项：无鉴权 LAN Web 编辑桥；导出 AIDL 未使用 signature 权限；旧输入会话事件可写入新编辑器；Commit/Delete/Key 使用可丢弃 SharedFlow；主题 ZIP 可路径穿越；Fcitx STARTING/dispatcher/InputContextCache 生命周期缺陷；剪贴板默认明文 HTTP；CI 不跑测试/lint且 native 来源浮动。

## 2. 方法、基线与限制

已执行 fetch、ff-only 同步和 docs 分支获取。本地原本已是最新。代码历史主基线采用 3ad25fc9：它是 HEAD 的确切祖先，也是两份 Rime 方案明确记录的 fx2 分叉点；当前 origin/fx2 已与本分支双向分叉，不能把双向差异全部称作本 fork 新增。

原始 fx 文档口径为 origin/docs（tip f8e9f53f），当前改动文档为 fx-rime-only/docs。origin/docs 是 fxliang fork 的文档协作分支，不是官方 master 文档。

尝试运行 .\gradlew.bat :app:testFxDebugUnitTest :app:lintFxDebug --stacktrace，但在 Gradle 启动前失败：本机未配置 JAVA_HOME，PATH 也无 java。当前 checkout 的 native gitlink 子模块亦未初始化，因而未完成 CMake/link/APK/ELF 和真机验证。本文“确认”表示代码路径经静态复核成立，不等同于已运行 PoC。

## 3. 架构和文档结论

当前产品已经从“全功能 fx + 插件生态”变为独立包名、单 APK、Rime 唯一输入引擎：applicationId 为 org.fcitx.fcitx5.android.fx.rime（app/build.gradle.kts:12-16,48-54）；Rime native addon、librime 与预置数据并入主 APK；首次启动固定启用 rime（core/Fcitx.kt:415-423）；其他引擎和 plugin runtime 已删除。主题、布局/宏、候选栏、语音、同步、更新等应用层功能仍保留。LanguageKey 已改为向 Rime 发送独立 Shift，长按打开系统 IME picker（CommonKeyActionListener.kt:152-158）。

origin/docs 有约 62 个用户文档项，当前 docs 仅有 HANDOVER-rime-only.md、rime-integration-plan.md、rime-only-feasibility.md。安装、迁移、宏/布局、主题、剪贴板、更新、OEM、FAQ 等用户手册丢失；旧文档又会误导用户使用已删除的插件、mainline、LanguageKey 和其他引擎。

文档不一致包括：HANDOVER 多处仍用旧分支/快照；文档称 Nightly 已删除，但 ci.yml:104-183 已恢复；方案仍以待实施语气描述已完成工作并引用已删除路径；构建来源描述与 prepare_personal_build.sh 实际 checkout 的 SandyYuR 浮动 master 不一致；Play 标题、品牌标点和许可 website 仍有残留。

## 4. P0：发布前必须修复

### P0-1 无鉴权 LAN Web 编辑桥（严重，CWE-306/CWE-942）

证据：ImeWebEditorBridgeServer.kt:179-205 使用 ServerSocket(port) 绑定所有接口，端口固定可枚举；:208-364,455-489 提供布局、popup、主题读写/删除；:927-930 设置 Access-Control-Allow-Origin:*；:933-947 主动选择 LAN 地址。KeyboardGroupFragment.kt:329-370 启动后关闭提示不停止服务，最长运行 3 小时。

触发/影响：用户启动编辑桥后，同网攻击者或恶意网页可读取、修改、删除宏/布局/主题。修复：默认仅 loopback；LAN 模式生成至少 128-bit 随机 token，校验全部 API，限制 Origin，随机端口、短超时、持续前台提示，PUT/DELETE 要求设备端确认。

### P0-2 导出 AIDL 服务无权限保护（高，CWE-926/CWE-862）

证据：AndroidManifest.xml:43-45 声明 signature 级 IPC permission，但 :246-252 的 FcitxRemoteService 没有 android:permission。FcitxRemoteService.kt:66-128,137-139 无调用 UID/签名检查。AIDL 可重启引擎、注册 transformer、导入剪贴板；transformer 接收完整原文。

影响：任意应用可读取/篡改用户之后复制的口令、验证码、私钥，注入历史或重启 DoS。修复：service 挂载 signature IPC permission；Binder 方法再校验 calling UID 与签名，并限制 transformer 数、字段长度和频率。

### P0-3 旧输入会话事件可写入新编辑器（高）

证据：core/Fcitx.kt:405-412 将 native 事件送入全局 flow，事件无 InputContext/session id；FcitxInputMethodService.kt:605-684 消费 commit/delete/key/preedit 时使用当前 InputConnection。A 的延迟事件可在切换 B 后作用于 B。

修复：native 有副作用事件携带单调 context/session id；主线程消费前核对；编辑器切换加入失焦屏障并丢弃旧事件。

### P0-4 Commit/Delete/Key 被允许丢弃（高）

证据：core/Fcitx.kt:232-236 使用容量 15、DROP_OLDEST 的 MutableSharedFlow；:405-412 对所有事件混用 tryEmit 且忽略结果。主线程卡顿时可漏字、漏删或造成键状态错乱。

修复：commit/delete/key 使用 lossless FIFO Channel/actor；候选/preedit/status 才按类型合并，并建立拥塞测试。

### P0-5 主题 ZIP 直接 Zip Slip（高，CWE-22）

证据：ThemeFilesManager.kt:407-431 对 File(tempDir, entry.name) 直接 mkdir/outputStream，无 canonical containment，且在 JSON 校验前写出。ShareReceiveManager.kt:512-523,604-615,838-855 可从导出 MainActivity 自动识别并导入主题 ZIP。

影响：用户选择恶意 ZIP 后，../../files/... 可逃出 tempDir，覆盖应用沙箱内持久文件或 DoS。修复：统一安全 extractor；文件与目录都 canonicalize，要求 target 等于 root 或以 root+separator 开头；拒绝绝对路径、..、盘符、符号链接；限制条目数、单项/总大小、深度和压缩比。

### P0-6 Fcitx STARTING 阶段不可停止（高）

证据：FcitxDaemon.kt:124-146 首客户端触发异步 start，最后客户端离开调用 stop；Fcitx.kt:537-555 stop 只接受 READY，STARTING 时直接返回；FcitxDispatcher.kt:74-101 启动异常也未完整收敛状态。

影响：READY 前断连后引擎可零客户端常驻；启动异常可能永久卡在非 STOPPED。修复：串行 desiredRunning 状态机，支持 STARTING→STOPPING；取消启动或 READY 后补 stop；异常必须发布 STOPPED。

### P0-7 dispatcher teardown/wake 与 dispatch/stop 竞态（高）

证据：FcitxDispatcher.kt:108-139 的 accepting-check、offer、drain 无共同线性化锁；native-lib.cpp:457-475 reset p_dispatcher，而 :462-463 无空判断 schedule；drain 后入队的 coroutine 可永久悬挂。

修复：使用可关闭 Channel/actor或同一锁线性化 dispatch、wake、drain、teardown；任务必须执行或异常完成；native handle 单线程拥有。

### P0-8 InputContextCache 析构重入（高/C++ UB）

证据：inputcontextcache.h:59-76,93-104 在 map 内析构 unique_ptr；androidfrontend.cpp:407-408 的 release 又修改同一 cache。AndroidInputContext 析构若回调 owner，会在 erase/clear/evict 中重入容器。

修复：先从容器摘出节点并更新索引，再在容器外析构；析构禁止回调修改 owner 容器。

### P0-9 无 active InputContext 时分页模式空指针（高）

证据：androidfrontend.cpp:505-511 无条件调用 activeIC_->updateCandidates...；JNI native-lib.cpp:1067-1072 可直接到达。修复：activeIC 为空只保存 mode，activate 后补刷新。

### P0-10 默认明文剪贴板同步（高，CWE-319）

证据：AndroidManifest.xml:60,64 与 network_security_config.xml:2-4 全局允许明文；SettingsActivity.kt:100-102、MainService.kt:82-84 默认端点均为 HTTP；SyncClient.kt:1504-1510 和 OneClipEventClient.kt:171-178 对无 scheme 地址补 http。Basic Auth、ClipCascade 登录、剪贴板文本/图片/历史均可能暴露。

修复：默认 HTTPS/WSS；无 scheme 补 HTTPS 或拒绝；release 禁止全局 cleartext。私网 HTTP 必须显式启用并强警告，禁止 HTTPS→HTTP 重定向携带 Authorization。

### P0-11 CI/native 来源不可复现（高/供应链）

证据：prepare_personal_build.sh:5-8,22-25 checkout 外部浮动 master；:17 patch 失败仅 echo；ci.yml:83-86 随后直接发布。.gitmodules 的来源又与脚本不一致。

修复：固定完整 SHA并使用 lock 文件；脚本 set -euo pipefail；git apply --check 失败即终止；在 APK/release notes 记录各 SHA。

## 5. P1：高优先级正确性、隐私和持久化问题

1. 云备份和手工备份泄露敏感数据（中高）：allowBackup=true；data_extraction_rules.xml:3-30 和 full_backup_content.xml:2-15 未排除 databases/clbdb、默认 SharedPreferences、files/clipboardsync_state。SettingsActivity.kt:643-650 明文保存口令；UserDataManager.kt:111-121 导出未加密 ZIP。建议关闭云备份或明确排除，凭据迁 Keystore，手工导出排除或强 KDF+AEAD。
2. 语音旧会话回调污染新会话（高）：VoiceInputProviderManager.kt:466-534 未核对 callback generation；:1043-1084,1118-1125 的旧 finish drain 可向新 provider 发 endStream。每个 callback/finish 捕获并双重校验 token、generation、provider。
3. 迟到语音广播写入下一编辑器（高）：FcitxInputMethodService.kt:333-351 无 session token且使用当前 InputConnection。广播携 token+input generation，finishInput 立即失效。
4. 宏跨输入会话且取消遗留 modifier（高）：BaseKeyboard.kt:2199-2279,2302-2359 在 DOWN/UP 间 delay，无 finally。宏绑定 input generation；追踪成功 DOWN，在 NonCancellable finally 逆序补 UP，finishInput/onDestroy 兜底清零。
5. 布局异步保存可把未落盘修改标为已保存（高）：TextKeyboardLayoutEditorActivity.kt:719-721,2153-2181；LayoutDataManager.kt:385-431 在 IO 直接访问/重置主线程 mutable state。主线程创建 immutable SaveSnapshot+generation，IO 只写 snapshot，Mutex 串行。
6. SyncStateStore 迁移丢失窗口（高）：SyncStateStore.kt:89-112 queue 文件写后立即 remove legacy 并 apply。目标 durable 成功后再 commit 删除源；失败保留源。
7. 资产 symlink/file 类型迁移破坏（高）：DataHierarchy.kt:86-121 与 FileAction.kt:10-48 先 create/update 后 delete；旧 symlink→新 file 会沿链接覆盖目标。显式类型矩阵，先 unlink，再 temp+原子 rename并验 hash/type。
8. 用户数据导入非事务且无资源上限（中高）：UserDataManager.kt:271-300 原位覆盖，无 rollback；ZipStream.kt:14-30 无条目/大小/深度限制，startsWith 缺 separator，目录不校验。使用限额 extractor、staging 校验、原子交换/journal。
9. Ready 早于 callback 安装（中高）：native-lib.cpp:699-712 先发 Ready，再安装 callback，首次 rime 配置事件可能进入 no-op。应最后发 Ready。
10. 关机 Rime 保存不可靠（中高）：IME 退出跳过 Rime 全量同步是合理的防卡顿折中，但 shutdown 广播窗口无法保证异步同步完成。改为周期性 durable sync/maintenance job。
11. 语音重绑未 unlink 旧 DeathRecipient（高）：VoiceInputProviderManager.kt:605-648；旧 Binder 死亡可误杀新尝试。recipient 捕获 binder/connection/generation，重绑前 unlink。

## 6. P2：中优先级问题

- ClipboardEditActivity.kt:47-55,85-87：Room 更新后立即 finish，onDestroy cancel scope，编辑可丢。await 后再 finish。
- TextKeyboardLayoutEditorActivity.kt:281-350,484-503,793-803：重建后 load 未完成时 ActivityResult 被直接丢弃。缓存 pending result。
- UpdateCheckActivity.kt:194-200,584-695：hosts-mapped 下载旋转时取消并删除 partial。迁 WorkManager/FG service。
- BaseInputView.kt:89-125,165-167：候选长按异步结果可乱序或 detach 后 show。Job+generation+attached 检查。
- KeyboardWindow.kt:586-659：语音布局 snapshot 只比较 input class，同类型 editor 会误恢复。使用 session token。
- FcitxInputMethodService.kt:1530-1539,1991-2002：currentInputBinding=null 时跳过 deactivate。缓存 uid。
- SetupFragment.kt:17-40：binding 未在 onDestroyView 清空，可能泄漏旧 View。
- MainService.kt:397-427,1350-1358：ACTION_PAUSE 改 prefs 后仍无条件 start。pause 应 stop+stopSelfResult并提前返回。
- MacroEditorActivity.kt:1473-1477,1646,1717-1742：取消弹窗仍可能保留 code 修改。使用临时副本。
- native-lib.cpp:764-769：把 UTF-16 jchar 地址当未终止 UTF-8 char*，潜在越界/错码；当前未发现业务明确调用 Char overload。
- FcitxInputMethodService.kt:333-345 及 voice manager：info 日志包含完整语音文本。release 只记长度/事件。
- app/proguard-rules.pro:8-9：release 使用 -dontobfuscate；非功能 bug，但降低逆向门槛。
- AndroidAppConventionPlugin.kt:7-15,81-93 和 app/build.gradle.kts:91-101 使用 AGP internal API/反射，升级脆弱。
- CMake 版本冲突：ci.yml:67-69 安装 3.22.1，Versions.kt:15 强制 3.31.6。
- ABI 参数未 trim/合法性校验；声明支持四 ABI，CI 仅 arm64。
- native assets 直写 app/src/main/assets，切分支可能打入陈旧 rime-data/opencc。改用 per-variant generated assets。

## 7. 测试与 CI

配置盘点：AGP 9.3.1、Gradle 9.6.1、Kotlin 2.4.10、Java 17；compile/target 36、min 23；NDK 28.0.13004108；工程 CMake 3.31.6；Room 2.8.4、schema 1..7；支持集合声明四 ABI；release shrink/optimize/resource shrink，签名从环境注入。

CI 只跑 assembleFxRelease，不跑 JVM tests、lint、instrumentation。约 16 个 JVM 测试文件、111 个 @Test 不受保护。唯一 androidTest 仍测试已删除的 pinyin/wbx（FcitxTest.kt:48-63,107-128），且事件等待无 timeout。签名缺失时没有 fail-fast，发布前无 apksigner verify。没有 Room MigrationTestHelper、Gradle dependency locking 或 verification metadata。

最低 CI 门禁：./gradlew :app:testFxDebugUnitTest :app:lintFxDebug。发布再增加：签名 secret 非空校验；apksigner verify 并核对证书 SHA-256；四 ABI 或明确 arm64-only；minified release 安装/Rime/JNI 冒烟；API 23/36；Room 1→7 migration；Rime-only instrumentation（启动、候选、提交、Shift），所有等待加 withTimeout。

## 8. 建议修复顺序

第一批（阻断发布）：Web 编辑桥鉴权；AIDL signature 权限；安全 ZIP extractor；HTTPS 默认；输入副作用事件 lossless + session id；activeIC 空判断；固定 native SHA、patch fail-fast、tests/lint、签名验证。

第二批（生命周期与一致性）：Fcitx 状态机/dispatcher actor；InputContextCache 所有权；语音 token/generation；宏 finally 释放；布局 immutable snapshot；SyncStateStore durable 迁移；用户数据 staging/rollback。

第三批：重写 Rime-only 用户/维护文档；Room/备份/TLS/ABI/权限/升级测试；Perfetto 候选延迟与启动基线；处理 Activity/Fragment/下载旋转等 P2。

## 9. 正向观察

FileProvider 均 exported=false 且只暴露窄 cache 子目录；IME、QS Tile、DocumentsProvider 受系统绑定权限保护；动态语音 receiver 使用 signature PLUGIN 权限；已检查 PendingIntent 均 IMMUTABLE；未发现 WebView/addJavascriptInterface、Java 原生反序列化或 trust-all TLS；Room 未使用 destructive downgrade fallback，导入会拒绝更高 schema；近期提交已修复多项路径、网络超时、下载上限、候选刷新和 Rime 退出冻结问题。

## 10. 最终结论

该分支是跨 Kotlin/NDK/Rime/网络同步/编辑器的高改动量长期 fork。Rime 单引擎融合结构清晰，但安全边界、输入事件可靠性和生命周期状态机尚未达到可持续自动发布标准。建议将 Web 编辑桥、AIDL 权限、输入事件通道/会话隔离、ZIP、明文同步、Fcitx 状态机和 CI 可复现性设为发布阻断里程碑；修复并通过真机/API/ABI 验证前，Nightly 应明确标注测试用途。
