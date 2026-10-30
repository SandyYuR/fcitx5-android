# 旧 Fcitx 审阅报告与当前 Rime 直连版对照 · 2026-10-24

## 基准与判定边界

- 代码：`perf/direct-rime-startup-20261024`，[`710e4ef9`](https://github.com/SandyYuR/fcitx5-android/commit/710e4ef93f20eaf1d80d93913be1edfaefdb097d)。最近续修更新：2026-10-27。
- 默认分支 `fx-rime-only` 在本轮读取时为 `6af8bc0b`；perf 已变基到该提交，整理为默认分支之上的 12 项分类提交；perf 修复尚未合入默认分支。
- 对照原文：[2026-09-07](/legacy/fcitx/notes/CODE_REVIEW_REPORT_2026-09-07)（`e51afd88`）及 [2026-10-11](/legacy/fcitx/notes/CODE_REVIEW_fx2_2026-10-11)（`3d0af21e`，含 10-19 修订）。原快照保持原文，不修改其历史结论。
- 两份旧报告当时的 Rime-only 仍使用 Fcitx 原生后端；现在是保留 Android 前端、直接调用 librime。旧 C++ 缓存、dispatcher 与插件移除不代表 UI、语音、同步和备份问题同时消失。
- 当前状态来自源码、基线差异和本轮测试。`当前未见旧触发链` 表示对应防护仍在或原触发代码已改变；不表示逐项通过真机验收。源码确认与设备待验证项目分开列出。
- 9 月 P1/P2 原文无子编号，本表的 `P1-n/P2-n` 按原列表顺序定位；10 月保留 A1–G5 与 N1–N8。不同报告有重复问题，表格行数不等于独立缺陷数。

## 当前分类提交

2026-10-27 按用户要求移除 `98f3713c`、`d66c9e4a`，变基到默认分支 `6af8bc0b`，其余 60 个原提交及新结构回归测试整理为 12 个提交。[分类列表、完整性核对与新验证](/maintainer/audit/perf-rebase-2026-10-27/)。旧后续可见性补丁无需重加：标题栏操作和实际按键不再共用复用池。

本批随后将最后一项文档与 CI 提交重写为 `710e4ef9`：四篇说明译为中文并迁入文档站，普通 PR 构建明确 ARM64。应用与测试源码未变，上一轮 `9c9013ca` 的原始设备证据保留。[当前代码及文档 PR、验证与合入准备](/maintainer/history/review-2026-10)。

## 整理前的分批提交（历史记录）

下表 SHA 及测试结果对应整理前历史，原证据保留；它们与当前提交的关系见[完整映射](/maintainer/audit/perf-rebase-2026-10-27/commit-map.json)。其中 98f/d66 已从当前 perf 历史移除。

| 提交 | 内容 | 验证（未标完整者为定向执行） |
| --- | --- | --- |
| `a38ac510` | 主题名/背景路径边界、悬空链接、主题/符号/按键音读取限额、严格原子替换 | 完整 581 项 |
| `3a82e280` | 网页原子保存、方案目录中断恢复日志、布局独立保存快照、空文件损坏提示 | 完整 590 项 |
| `ad56c9b7` | 主题启动后台化、评分缓存、首次解锁前存储保护、偏好同步移出 Main | 完整 592 项 |
| `d8772107` | OneClip 预热超时/取消与 SSE 建连期限，跨重连复用连接池并隔离账号 Cookie | 完整 598 项 |
| `c81ac086` | Android 6 ZIP/菜单/颜色 API 兼容，API 23 与 28 框架测试 | 完整 604 项 |
| `d3ff4115` | Android 15 同步超时/暂停真正停止服务，阻止排队请求自动重启 | 完整 609 项 |
| `9de1e5e0` | 事务内生成数据库快照，恢复旧备份有效 WAL，二次校验数据库版本 | 完整 629 项 |
| `116ecabc` | 语音重绑清理死亡监听，按连接与登记身份过滤过期回调 | 完整 636 项 |
| `b2e14f0d` | 语音 AIDL 回调、音频收尾和布局快照按编辑器会话隔离，保留同编辑器重启 | 完整 657 项 |
| `95745ca8` | 宏会话校验、按键所有权及取消释放，保留 Down/Up 语义 | 完整 670 项 |
| `26a0c90f` | 合并语音回调主线程调度，连接与会话校验共用一次排队 | 完整 672 项 |
| `ea6229a4` | 资产文件/目录/链接类型安全迁移、原子替换、递归排除内置预设 | 完整 694 项 |
| `38815b63` | 宏按键选择确认后提交，按对象身份删除重复项；取消/返回不改原数据 | 完整 710 项 |
| `7dddf7b9` | 剪贴板保存先落库，关闭不取消已确认写入，新旧 Intent/保存结果隔离 | 完整 738 项 |
| `b2c4631a` | 宏 JSON/旧格式共用独立规范化基准，移除完整宏内容日志 | 完整 754 项 |
| `1c8a7d04` | 旧服务排队布局回调崩溃；布局/字体任务绑定 Service 生命周期和 InputView 身份 | 完整 770 项 |
| `98f3713c` | 按用户指定同步默认分支 f1234452：行下移及独立行操作样式 | 后续兼容回归 |
| `d66c9e4a` | 隐藏行箭头复用为普通键/新增键时恢复可见性 | 10 项定向 |
| `a536f468` | STOMP、JSON、Base64、加密前预算与减少副本 | 53 项定向；A9 仍部分修复 |
| `53477190` | 移动候选角标局部重绑及保留项字体更新 | 25 项定向 |
| `6c22d0cc` | 多方案菜单滚动、大字号换行 | 10 项框架；1 项设备 |
| `c80f3ce0` | 异步菜单会话/候选/锚点校验，native owner 再检查 | 完整 863 项 |
| `d06f7eab` | 移除构建私有 API 与产物篡改，校验 ABI 和 bundle 选择 | 构建逻辑 6 项，实际配置/构建 |
| `0177badc` | 二维码图标主题集片、解压、解析和保存后台化 | 18 项框架 |
| `229a344a` | 更新页进入镜像规则列表 | 设备导航回归 |
| `1db12cec` | 页面销毁后仍完成加载弹窗清理 | 12 项框架 |
| `4bb3486c` | 候选菜单保留编辑器焦点，补真实 IME 会话回归 | 4 项框架；完整 897 项应用 JVM + 6 项构建逻辑 |
| `e3471045` | 方案菜单测试显式点按聚焦并补状态诊断（仅 AndroidTest） | 四种输入类型设备菜单场景通过；保留两次原前置条件失败 |
| `e146d2cb` | 固定 ClearURLs 规则及许可证，后台过滤/有序入队、保留编码、回流及回调释放 | 完整 953 项应用 JVM + 构建逻辑 6 项；Python 4 项；设备 2 项 |
| `e2ce518a` | 普通/辅助键返回等待模型恢复，绑定原文件/条目/令牌 | 完整 969 项应用 JVM；夹具修正后设备 2 项 |
| `e7a696fe` | 启用辅助栏测试夹具并检查返回前置条件（仅 AndroidTest） | 真实子页面返回、重建与撤销检查通过 |
| `6f96066b` | 扩展命名/导入等待，保存内嵌结果与再次重建前的表单选择 | 完整 981 项应用 JVM + 6 项构建逻辑、4 项 Python |
| `516252eb` | 扩展第二次恢复中的内嵌颜色与选择检查（仅 AndroidTest） | 两项布局原生场景通过 |
| `1c96acd9` | 修正短按同步注入被拖成长按，保留失败现场与实际按住时长（仅测试） | 完整 983 项 JVM；20/331 ms 手势框架用例通过 |
| `b193b9ff` | 补查目标编辑框实际绑定回调，实际 DOWN/UP 采集代号（仅 AndroidTest） | 组合 3 项与独立 1 项通过，共 12 轮输入/重绑 |
| `8a39f2fe` | 草稿实际内容去重、临时写入后原子替换、失败保留成功快照引用及软链接保护 | 完整 991 项应用 JVM + 6 项构建逻辑 |
| `7ec08c8e` | 复用历史快照后台准备草稿 JSON，单任务/最新待办、逐段匹配与关闭取消 | 完整 1002 项应用 JVM + 6 项构建逻辑、4 项 Python；D7 仍部分修复 |
| `ccfa8636` | 旧方向宏6键型×4方向×4符号位置的编辑/偏好变更往返验证 | API23/28共14项，N4验证关闭 |
| `e9d5f6ee` | 宏、弹出、字体编辑器文件草稿及原始比较基准恢复 | API23/28共12项；B1关闭 |
| `2dd2d5af` | 兼容6个内置主题旧slug文件名，保留冲突文件及安全边界 | API23/28共8项 |
| `ea16a375` | 私有留存Java崩溃栈并纳入重新打开后的日志导出 | 6项新增；完整1042项应用JVM+6项构建逻辑，ARM64 CI通过 |
| `c36a8a1a` | 可选语音广播回退的请求/输入框校验、重复拒绝与能力声明 | 20项新增语音执行，正常AIDL协议保留 |
| `f37b27b6` | 空binding解绑仍使旧会话/按键失效的框架验证 | 4项新增；完整1066项应用JVM+6项构建逻辑、4项Python |
| `143ccf73` | 新地址默认HTTPS、旧地址迁移与阻断凭据降级重定向 | 30项定向通过，含19项新增 |
| `11137251` | 系统备份外观白名单、手动默认分块认证加密与旧ZIP兼容 | 21项定向通过，含16项新增 |
| `c2ba6510` / `c72115de` | WebSocket 读取层帧/分片预算及隔离连接池回归断言 | 完整1125项应用JVM+6项构建逻辑，ARM64 CI通过 |
| `d3cb8769` | hosts 下载持久任务、断点续传与安全发布恢复 | 31项定向；完整1156项应用JVM+6项构建逻辑，ARM64 CI通过 |
| `bb9d2a78` | 整包恢复跨进程日志、启动回滚、旧偏好备份与未替换WAL | 新增41项；完整1197项应用JVM+6项构建逻辑，六次实际主机进程强制结束；ARM64 CI全绿 |
| `544f84a8` | 恢复后的残留清理后台化，启动和输入不等待清理锁 | 新增7项；完整1204项应用JVM+6项构建逻辑，ARM64 CI全绿 |
| `8dca031c` | 兼容缓存父目录的平台别名，拒绝暂存叶目录重定向 | 新增6项；完整1210项应用JVM+6项构建逻辑，ARM64 CI全绿 |
| `93942a2e` | 实际学习、系统关机和新内核中的词典排序探针（仅AndroidTest） | 8dca031c模拟器关机验证通过，新内核新进程仍保留学习排序 |
| `209cca0e` | 偏好XML值类型、结构与按堆预算限额 | 新增8项；完整1218项应用JVM+6项构建逻辑；CI签名构建通过，单测遇夹具清理竞态，后续9b1f958c处理 |
| `9b1f958c` | 后台只清理已恢复目录、测试销毁夹具前等待清理 | 41项定向、完整1220项应用JVM+6项构建逻辑、ARM64 CI通过 |
| `3e09e289` | Android恢复交接与真实SQLite/WAL读回探针（仅AndroidTest） | 209cca0e模拟器成功页、新进程偏好/数据库读回通过 |
| `976ab05f` / `018fcf3e` / `7e5c2c1b` | 提示从可见帧计时，owner线程处理及API显式转发 | 最终1233项CI应用测试、ARM64签名构建和3项设备回归通过，含6轮重绑 |
| `3ca22462` | 显示确认检查与命名键表首次加载隔离（仅AndroidTest） | 保留两次补充失败及最终通过记录 |

本次还修正两项补充发现：JSON 宏入口缺初始基准导致未编辑即误报已修改；完整宏内容写入调试日志。两者均由 `b2c4631a` 修复，不额外算作 139 条旧报告中的新行。宏/弹出/字体编辑器的进程草稿恢复已由 e9d5f6ee 处理，列 B1。

模拟器后续又实际复现一项旧布局回调崩溃，`1c8a7d04` 已增加 Service 生命周期取消和 InputView 身份核对；它属于 139 条之外的补充发现，不据此减少原表残留。详见[异常栈与回归](/maintainer/audit/queued-layout-2026-10-25/)。

本轮还修复了加载弹窗取消时跳过 dismiss 的清理遗漏，并在设备上复现候选菜单夺走编辑器窗口焦点，后者由 `4bb3486c` 改为不获取焦点。两项为补充发现，不重复计入旧报告的 139 条。详见[本轮验证与修复前失败](/maintainer/audit/sync-candidates-2026-10-25/)。

历史上按指定同步的 f1234452 对应 `98f3713c`。本次按用户要求移除该提交及 `d66c9e4a`，改为变基到默认分支 `6af8bc0b`，并保留其标题栏操作和拖动卡片底色实现。

此前同分支 `9b1926db`、`f54eb713`、`032a7ca5` 已修输入视图泛型反射、诊断开销、队列背压/生命周期、方案重叠与保存失败、同步迁移、IPC/SAF/LAN 访问边界。

## 仍需要处理的项目

以下按影响合并；设备待验证和构建取舍另见逐项表。

截至本次续修，原表不再有“仍存在/部分修复”的代码项；P1-10 已完成本次模拟器正常关机持久化验证，真机与突然断电不在已验收范围。P1-8 已接入跨进程恢复日志，Android 页面交接、主进程杀退及偏好/WAL读回已通过；真实卷拔插仍需补验。新增的恢复残留清理阻塞启动问题已由544f84a8处理。139 条包含跨报告重复项及工程取舍。

构建范围：用户明确仅需要 arm64-v8a 发布构建，P2-15 按需求关闭；此项为范围确认，不计作本批代码修复。

| 优先级 | 问题 | 旧报告编号 | 当前残留与建议 | 代码 |
| --- | --- | --- | --- | --- |
| P1 | 关机词典持久化 | 9 月 P1-10 | 待学习词典、关机与重启设备验证；不以调用 save 推定完成。 | [FcitxApplication.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/FcitxApplication.kt) |


## 2026-10-11：93 项及 N1–N8 逐项对照

93 项旧编号当前状态：不再适用 6 项；当前未见旧触发链 77 项；本次修复 9 项；部分修复 0 项；按需求保留 1 项。追加 8 项单独列出。

### A 组

| 编号 / 原问题 | 旧报告状态 | 当前状态 | 当前依据 |
| --- | --- | --- | --- |
| A1（旧报告:68） 布局行内出现非对象元素 → 每次唤起键盘崩溃 | 已修复 | 当前未见旧触发链 | 行内非对象元素会被跳过并记录日志，旧崩溃入口已消除；对应解析测试仍在。 [LayoutJsonUtils.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/utils/LayoutJsonUtils.kt) |
| A2（旧报告:87） 缺 `tap` 的 MacroKey：本项目自己就能写出让键盘崩溃的配置 | 已修复 | 当前未见旧触发链 | 运行时跳过缺 tap 的宏键；LayoutDataManager.validateKey 同时拒绝保存缺 tap 的 MacroKey。 [LayoutJsonUtils.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/utils/LayoutJsonUtils.kt) |
| A3（旧报告:105） 存储路径含裸 `%` → 点确定即崩溃 | 已修复 | 当前未见旧触发链 | 非法百分号及有损 UTF-8 解码回退原字符串。 [StoragePathUtils.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/ui/StoragePathUtils.kt) |
| A4（旧报告:123） 测试连接期间旋屏/返回 → 必崩 | 已修复 | 当前未见旧触发链 | 测试请求使用 viewLifecycleOwner.lifecycleScope，回主线程检查 isAdded，销毁视图时关闭进度弹窗。 [SettingsActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/ui/SettingsActivity.kt) |
| A5（旧报告:143） 镜像规则的 replacement 非法组引用 → 点下载即崩溃 | 已修复 | 当前未见旧触发链 | 下载实际替换有完整异常处理；保存侧增加试运行校验。样例不匹配时校验可能漏掉错误，但下载会提示并中止，不再崩溃。 [UpdateCheckActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateCheckActivity.kt) |
| A6（旧报告:152） 图标主题 ZIP 全量入内存 → zip bomb 导致 OOM 且拦不住 | 已修复 | 当前未见旧触发链 | 压缩包输入 16 MiB、JSON 1 MiB、文件项 4 MiB、文件累计预算及项数受限，解压文件直接暂存磁盘，旧无界 ByteArray 列表已移除。 [IconThemeManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/data/theme/IconThemeManager.kt) |
| A7（旧报告:170） XZ 解压未设内存上限 → 畸形二维码可触发 GB 级分配 | 已修复 | 当前未见旧触发链 | XZ 内存预算 4 MiB，并保留 256 KiB 输出限制。 [LayoutQrTransferCodec.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/share/LayoutQrTransferCodec.kt) |
| A8（旧报告:179） 内嵌 HTTP 服务按 Content-Length 直接分配 + 连接数无上限 | 已修复 | 当前未见旧触发链 | 请求体分配前检查 4 MiB 上限，超限响应 413；连接线程与队列有界。 [ImeWebEditorBridgeServer.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/webeditor/ImeWebEditorBridgeServer.kt) |
| A9（旧报告:196） 同步收发全量入内存，尺寸过滤发生在解码之后 | 部分修复 | 本次修复 | c2ba6510补齐Socket读取层的帧/分片累计上限与堆预算；24项新增、完整1125项通过。[验证、CI红转绿及兼容边界](/maintainer/audit/websocket-receive-2026-10-26/)。 |
| A10（旧报告:213） 选取图标时直接用 provider 提供的 DISPLAY_NAME 拼路径，并有 `!!` 断言 | 已修复 | 当前未见旧触发链 | 显示名为空有回退，落盘使用净化文件名及扩展名白名单，读取为空正常报错。 [IconThemeEditorActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/icon/IconThemeEditorActivity.kt) |
| A11（旧报告:230） 7z 导入走 java.nio.file，而 desugar 不含 nio 且只有该分支无 SDK 守卫 | 已修复 | 当前未见旧触发链 | 7z 判定与执行均保护 API 26；旧报告的低版本运行时崩溃未经真机确认，本次确认防护代码已到位。 [ShareReceiveManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/ShareReceiveManager.kt) |

### B 组

| 编号 / 原问题 | 旧报告状态 | 当前状态 | 当前依据 |
| --- | --- | --- | --- |
| B1（旧报告:250） 六个编辑 Activity 漏 `configChanges`，且全模块 0 个 `onSaveInstanceState` → 旋屏丢失全部编辑 | 部分修复 | 本次修复 | e9d5f6ee 为 Macro/Popup/Fontset 编辑器保存独立文件草稿及原始比较基准，Bundle 只携带文件名，失败时仅小数据可内联；字体恢复后正确启用保存菜单。12个API23/28执行覆盖连续新Activity+Parcel、大文本Binder预算、多实例隔离、弹出数据/字体顺序字号及损坏恢复。未确认选择框重建按取消处理，已确认的主体编辑保留。 [MacroEditorActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/dialog/MacroEditorActivity.kt) |
| B2（旧报告:259） 拖拽排序后编辑/删除写到错误条目，覆盖其他按钮 | 已修复 | 当前未见旧触发链 | 拖动后点击使用当前 ViewHolder 位置及当前数据对象，避免改错/删错按钮。 [ButtonsCustomizerActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/ButtonsCustomizerActivity.kt) |
| B3（旧报告:290） 进"调整按钮"面板拖动一次，永久删除 buttons.json 里的一部分按钮 | 不再适用 | 不再适用 | `245e4bf1` 删除实现及入口。键盘内按钮调整面板已移除；不能据此声称旧分区保存算法已修复。设置页按钮编辑器仍保留。 [build.gradle.kts](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/build.gradle.kts) |
| B4（旧报告:311） 拖拽结果只在 `onDetached` 保存，而销毁路径不触发它 | 不再适用 | 不再适用 | `245e4bf1` 删除实现及入口。原面板与 InputView 的显示/隐藏路径一并移除，原“只在 detach 保存”的触发链不再存在。 [build.gradle.kts](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/build.gradle.kts) |
| B5（旧报告:320） 布局保存非原子 + 解析失败静默回退 → 自定义布局永久丢失 | 部分修复 | 本次修复 | 现存零字节布局纳入损坏提示；写入统一走 FileUtil，替换失败保留原文件。覆盖 B5 旧触发链，草稿恢复另见 B1/D7。 [LayoutDataManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/data/LayoutDataManager.kt) |
| B6（旧报告:329） 备份用秒级时间戳 + `overwrite=false`，同一秒二次保存静默失败 | 已修复 | 当前未见旧触发链 | 碰撞时追加毫秒/递增后缀，失败记录日志，旧同秒重名已处理。 [DataMigrationManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/migration/DataMigrationManager.kt) |
| B7（旧报告:338） 每次加载都无条件跑迁移，且纯内存迁移失败却用旧备份覆盖完好的磁盘文件 | 已修复 | 当前未见旧触发链 | 只在需要时迁移；内存迁移失败重读原内容并提示，不再用旧备份覆盖磁盘。 [LayoutDataManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/data/LayoutDataManager.kt) |
| B8（旧报告:347） 迁移逻辑静默删除子模式的 displayText | 已修复 | 当前未见旧触发链 | 仅清理空映射，无匹配上下文但非空的显示文本映射保留。 [DataMigrationManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/migration/DataMigrationManager.kt) |
| B9（旧报告:356） 导入备份时不关闭 Room、连 WAL 一起覆盖、降级即 dropAllTables | 部分修复 | 本次修复 | 9de1e5e0 在 Room 事务内逐行生成独立 SQLite 快照，保留类型、Room 元数据和自增序列；旧 ZIP 的匹配 WAL/journal 在临时目录恢复，恢复后再查 user_version，拒绝更高版本及损坏主库/孤立 WAL。20 项 API 23/28 SQLite 与 ZIP 集成用例通过。 [ClipboardManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/ClipboardManager.kt) |
| B10（旧报告:375） 历史增量下载了 N 条却只导入 1 条，游标照常推进 | 已修复 | 当前未见旧触发链 | 导入全部已接受历史项；SyncClipboard 有导入失败不推进游标的判断；只用末条更新系统剪贴板。 [MainService.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt) |
| B11（旧报告:392） 跨 Activity 结果靠实例字段闭包传递，宿主重建后静默丢弃 | 已修复 | 当前未见旧触发链 | 按钮目标 ID/宏结果 token 跨重建保存；KeyEditor 也按 slot 应用结果并恢复草稿，闭包消失有回退路径。 [ButtonsCustomizerActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/ButtonsCustomizerActivity.kt) |
| B12（旧报告:410） 剪贴板暂存文件按源文件名命名 → 同名互相覆盖；32 文件上限与数据库不同步 | 已修复 | 当前未见旧触发链 | 暂存名带 nanoTime，清理成功后回调 ClipboardManager 删除对应库内引用。缓存本身仍可能被系统回收，未将其视为永久媒体存储。 [ClipboardUriStore.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/utils/ClipboardUriStore.kt) |

### C 组

| 编号 / 原问题 | 旧报告状态 | 当前状态 | 当前依据 |
| --- | --- | --- | --- |
| C1（旧报告:434） "按住说话"松手后反而新开一次录音，且无法停止 | 已修复 | 当前未见旧触发链 | 完成/错误清除 hold 标记，松手只结束仍活跃的会话。 [CommonKeyActionListener.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt) |
| C2（旧报告:453） AIDL 回调在 binder 线程改状态，与主线程竞态 → AudioRecord 永不 release，麦克风常开 | 已修复 | 当前未见旧触发链 | AIDL 错误及结束回调把 teardown 放入服务 Main lifecycleScope，消除原录音启动/结束交错入口。 [VoiceInputProviderManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceInputProviderManager.kt) |
| C3（旧报告:475） 结束路径无超时兜底 → 麦克风按钮被永久锁死 | 已修复 | 当前未见旧触发链 | 结束阶段有 3 秒看门狗并检查会话代号。 [VoiceInputProviderManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceInputProviderManager.kt) |
| C4（旧报告:494） 重复 `onReady` 时旧 queue 不 close、旧 job 不 cancel → 协程与 channel 泄漏 | 已修复 | 当前未见旧触发链 | 重复 ready 会关闭旧队列、取消旧 feed，并保留已运行 capture；队列也已改成有界。 [VoiceInputProviderManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceInputProviderManager.kt) |
| C5（旧报告:512） `AudioRecord` 停止路径的空 catch 与无条件丢弃 worker | 已修复 | 当前未见旧触发链 | 停止/释放异常记日志，join 超时保留 worker 引用，read 后重新检查 running。 [VoiceInputAudioCapture.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceInputAudioCapture.kt) |
| C6（旧报告:531） `resetState()` 的取消被运行时开关守卫 → job 空转且按键永久失效 | 已修复 | 当前未见旧触发链 | 长按与重复 job 无条件取消/清状态，不再受 Enabled 标志守卫。 [CustomGestureView.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CustomGestureView.kt) |
| C7（旧报告:550） 合成态热重建在按压中途换 view 实例 → 长按连删中断 | 已修复 | 当前未见旧触发链 | 正在按压的键延后重建，UP/CANCEL 后处理待重建列表。 [BaseKeyboard.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt) |
| C8（旧报告:567） `dismissAllPopups()` 不区分 viewId → 双指打字时选中字符不上屏 | 已修复 | 当前未见旧触发链 | 关闭弹出层支持保留 viewId，BaseKeyboard 保留另一个仍按住的 popup；重设 dismiss job 前取消旧 job。 [PopupComponent.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupComponent.kt) |
| C9（旧报告:587） 行缓存复用后不重放文本缩放 → 字号回弹 | 已修复 | 当前未见旧触发链 | 行缓存复用结束后重放字号缩放。 [BaseKeyboard.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt) |
| C10（旧报告:596） "空格滑动移光标"开关对缓存行永久失效 | 已修复 | 当前未见旧触发链 | 缓存空格键复用时重放划动阈值与开关，偏好监听同步更新基线。 [BaseKeyboard.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt) |
| C11（旧报告:614） 手势 baseline 从已包装的 listener 反推 → 监听链无上界增长 | 已修复 | 当前未见旧触发链 | 保留创建时原始手势基线，避免复用时反复包装已包装 listener。 [BaseKeyboard.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt) |
| C12（旧报告:630） 提交类按键会误提交预测候选，且跨线程读 RecyclerView 字段 | 已修复 | 当前未见旧触发链 | 以合成状态判断提交，不再跨线程读 RecyclerView，也不再仅凭预测候选误选词。 [CommonKeyActionListener.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/CommonKeyActionListener.kt) |
| C13（旧报告:648） "+" 添加键未从拖拽中排除 → View 重排而数据被静默拒绝 | 已修复 | 当前未见旧触发链 | “+” chip 排除拖拽/落点，按实际可拖项数限制索引。 [DraggableFlowLayout.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/DraggableFlowLayout.kt) |
| C14（旧报告:666） `FlowLayout.onMeasure` 在 AT_MOST 下永不换行，且忽略 `heightMeasureSpec` | 已修复 | 当前未见旧触发链 | AT_MOST 按实际限制折行，测量包含 margin 并遵守高度约束。 [FlowLayout.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/FlowLayout.kt) |
| C15（旧报告:684） `dragInProgress` 只写不读 → 拖拽中顶栏被整体重建，按钮视觉上消失 | 不再适用 | 不再适用 | `245e4bf1` 删除实现及入口。原 dragInProgress 工具栏调整路径已随面板移除；当前工具栏采用固定子 View 的 KawaiiBarRowLayout。 [build.gradle.kts](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/build.gradle.kts) |
| C16（旧报告:706） `runBlocking` + `readTimeout(0)` + 握手无超时 → 不可取消的永久挂死 | 已修复 | 当前未见旧触发链 | 原 ClipCascade 分支去掉 runBlocking，STOMP 等待有 15 秒超时，普通 HTTP 有独立有限读超时。OneClip 的类似遗漏另列 N6，未算作本条已覆盖。 [ClipCascadeClient.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/ClipCascadeClient.kt) |
| C17（旧报告:724） 键盘一收起同步就整体停摆，而通知仍显示"正在同步" | 已修复 | 当前未见旧触发链 | IME 隐藏进入 idle 节奏，前台状态与截图开关解耦，通知状态随运行模式更新；屏幕熄灭暂停有明确状态。 [MainService.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt) |
| C18（旧报告:741） 锁外序列化非线程安全集合 → ConcurrentModificationException 被吞 | 已修复 | 当前未见旧触发链 | 共享 map/list 先在锁内复制，再序列化快照。 [MainService.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt) |
| C19（旧报告:759） PBKDF2 迭代次数由服务器下发且默认值与上游不一致 | 已修复 | 当前未见旧触发链 | 采用服务器 rounds，拒绝非正数，对过大值设预算；默认 664937，低轮数不再强行抬高导致密钥不一致。超预算宜明确拒绝而非修改，属于后续兼容改进。 [ClipCascadeClient.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/ClipCascadeClient.kt) |
| C20（旧报告:775） 快捷设置磁贴只翻 pref，不启动/停止服务 | 已修复 | 当前未见旧触发链 | 磁贴真正调用 start/stop 服务并同步偏好。 [QuickSyncTileService.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/service/QuickSyncTileService.kt) |
| C21（旧报告:784） 前台服务类型与实际用途不符 | 部分修复 | 本次修复 | d3ff4115 在 onTimeout、暂停及前台启动被拒后停止任务、监听和连接，调用 stopSelf 并持久化暂停；迟到 START/RECONNECT 不能自动恢复，需显式启用。5 项服务回归通过，Android 15 系统配额倒计时仍待设备验证。 [MainService.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt) |
| C22（旧报告:794） 带 `//` 注释的布局文件在真键盘上解析失败并静默退回内置布局 | 已修复 | 当前未见旧触发链 | 运行时布局/弹出预设读取与编辑器一致，支持行注释。 [ConfigProvider.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/config/ConfigProvider.kt) |
| C23（旧报告:811） `JsonNull` 被当成字符串 `"null"` 显示在键面上 | 已修复 | 当前未见旧触发链 | 文本字段使用 contentOrNull，JsonNull 不再变成字面“null”。 [LayoutJsonUtils.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/utils/LayoutJsonUtils.kt) |
| C24（旧报告:820） 删掉自定义宏后按钮仍执行旧宏 | 已修复 | 当前未见旧触发链 | 所有配置按钮重绑宏或默认行为，删除宏会清掉旧 listener。 [KawaiiBarComponent.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt) |
| C25（旧报告:838） 更新页轮询在 `onPause` 不停止 | 已修复 | 当前未见旧触发链 | 暂停时移除 800 ms 轮询，恢复时按需重启。 [UpdateCheckActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateCheckActivity.kt) |
| C26（旧报告:848） hosts 设置页 `onPause` 无条件写回，并每次清空 release 缓存 | 已修复 | 当前未见旧触发链 | 提交一组 hosts 设置并校验来源；Repository 对未变化值不重复写/失效。 [UpdateHostsSettingsActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateHostsSettingsActivity.kt) |
| C27（旧报告:857） 二维码长图超过约 23 片后自己导不回来 | 已修复 | 当前未见旧触发链 | 安全采样基于 QR 方块、最小尺寸改 256，支持当前 64 片上限内普通长图；未采用旧建议的分页，但原约 23 片必拒绝已消除。大图内存与识别率仍需设备验证。 [JsonFileQrShareManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/share/JsonFileQrShareManager.kt) |
| C28（旧报告:866） 签名比对用 API 28 的 `signingInfo`，而 minSdk=23 → Android 6~8.1 上插件 IPC 全被拒 | 已修复 | 不再适用 | 原独立引擎插件的包签名读取路径与 PackageSignatures.kt 已随插件运行时移除；不声称旧插件实现被修好。 [settings.gradle.kts](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/settings.gradle.kts) |
| C29（旧报告:882） CI 产出的插件写死上游主包名 → 装到 fx 主程序上 IPC 全断 | 不再适用 | 不再适用 | `settings.gradle.kts:24`。Rime 已内置，旧独立插件模块及 assembleReleasePlugins 流程移除（b1bba67b 等）；原插件产物包名问题不再适用于当前构建。 [build.gradle.kts](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/build.gradle.kts) |
| C30（旧报告:899） `Logcat` 关闭后无法再次打开，子进程从不回收 | 已修复 | 当前未见旧触发链 | 停止时清 process 字段、关闭读流、destroy 并取消 job；快照/清日志回收子进程。旧用户可见的同实例重启场景本就少见，未继续当 P1。 [Logcat.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/utils/Logcat.kt) |
| C31（旧报告:916） `FcitxDaemon` 用 `CancellationException` 表示"连接已断开"，调用方静默结束 | 已修复 | 当前未见旧触发链 | 断连错误改普通 IllegalStateException 子类，调用方可观察失败；best-effort runIfReady 单独处理。 [FcitxDaemon.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/daemon/FcitxDaemon.kt) |
| C32（旧报告:932） 死代码与未注册 Activity | 部分修复 | 本次修复 | 229a344a 在更新页面的溢出菜单增加镜像规则管理，复用结果返回后刷新配置的 launcher；列表 Activity 已注册且现在有可达入口，设备导航回归通过。 [UpdateMirrorListActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateMirrorListActivity.kt) |

### D 组

| 编号 / 原问题 | 旧报告状态 | 当前状态 | 当前依据 |
| --- | --- | --- | --- |
| D1（旧报告:948） 每次按键在主线程 `runBlocking` 等 fcitx 单线程 | 已修复 | 当前未见旧触发链 | 候选更新热路径复用已广播 panel，不再每键 runImmediately；低频创建/attach 的同步读取仍在，未宣称全部同步调用已删。 [KeyboardWindow.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt) |
| D2（旧报告:967） 每次长按都同步读盘 + 解析整个 PopupPreset（缓存判定写在读盘之后） | 已修复 | 当前未见旧触发链 | 读文件前判缓存/mtime，未变化不再解析。 [PopupComponent.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/popup/PopupComponent.kt) |
| D3（旧报告:984） 每 800ms 在主线程对整个 APK 做 SHA-256 | 已修复 | 当前未见旧触发链 | 校验移入后台并按 URI/大小/修改时间缓存，轮询不重复同步哈希。 [UpdateCheckActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateCheckActivity.kt) |
| D4（旧报告:994） 粘贴剪贴板图片在 IME 主线程做整文件复制 + 最多 480ms `Thread.sleep` | 已修复 | 当前未见旧触发链 | 粘贴路径在 IO 暂存后回 Main 提交，重试用 delay；调用点已改 suspend/协程。 [ClipboardUriStore.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/utils/ClipboardUriStore.kt) |
| D5（旧报告:1012） InputView 构造在主线程解码全尺寸壁纸，且同一张图解两次 | 已修复 | 当前未见旧触发链 | 构造使用纯色占位，背景在后台解码，共享降采样位图缓存。 [InputView.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt) |
| D6（旧报告:1033） `IconThemeManager` 的 object 初始化在主线程做磁盘 IO + SVG 解析 + 回写 | 已修复 | 当前未见旧触发链 | 对象不再隐式刷新磁盘；Application 显式启动 IO 初始化，默认图标可先显示。 [IconThemeManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/data/theme/IconThemeManager.kt) |
| D7（旧报告:1052） 布局的加载/解析/备份/序列化/写入全在主线程 | 部分修复 | 按需求保留 | 3a82e280 已隔离常规 IO 保存；7ec08c8e 复用已有历史独立快照后台准备 JSON，仅一个编码任务并合并最新待办，逐段核对当前内容。未完成的内嵌编辑/缓存未就绪或失败仍同步导出，生命周期文件写入仍在 Main，因此保留部分修复。8a39f2fe 另补实际内容去重、原子替换与失败保留旧快照。 [TextKeyboardLayoutEditorActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/TextKeyboardLayoutEditorActivity.kt) 用户明确优先保住最新编辑，要求保留同步兜底；本项按需求保留，不计作代码修复。 |
| D8（旧报告:1061） 分享接收的 ZIP 读取与 JSON/Base64/XZ 解析在主线程 | 已修复 | 当前未见旧触发链 | 自动识别 ZIP/JSON/Base64/XZ 在 Default，读文件用 IO；Loading 生命周期跟识别阶段分离。 [ShareReceiveManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/ShareReceiveManager.kt) |
| D9（旧报告:1078） `ThemeManager.refreshThemes()` 在主线程做全量磁盘扫描并可能回写 | 已修复 | 当前未见旧触发链 | refreshThemes 保留后台扫描；ad56c9b7 进一步移除对象初始化扫描、启动评分与首次生成的 Main 工作。 [ThemeManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeManager.kt) |
| D10（旧报告:1092） 图标主题导入/导出：主线程无上限读取与 PNG 压缩 | 部分修复 | 本次修复 | 0177badc 图片及相机集片统一进入后台串行 importer；XZ 解压、JSON 解析和保存均在 IO，保留旧格式并向上传递取消，写入失败不重试另一格式。18 个 API 23/28 框架执行通过；原有 JSON/ZIP 读取限额及后台 PNG 压缩保留。 [IconThemeListActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/icon/IconThemeListActivity.kt) |
| D11（旧报告:1108） 复制任意 URL 会在主线程加载并编译整套 ClearURLs 规则 | 部分修复 | 本次修复 | e146d2cb 固定上游 11086f40 的 206 个 provider，附原始许可证与来源校验；元数据不加载规则，首次系统复制在后台先做内置过滤再进插件，同步过滤/入队串行后台化。修复编码值破坏、共享解析器并发、重定向预算及旧原始 URL 状态回流，释放空/已销毁服务回调。56 个新增 JVM 执行、4 个包校验用例及 API35 原生规则测试通过。 [HostClipboardFilter.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/data/clipboard/HostClipboardFilter.kt) |
| D12（旧报告:1125） 截图监听每 5 秒在主线程查 MediaStore，且作用域从不取消 | 已修复 | 当前未见旧触发链 | 查询/复制用 IO，停止取消轮询及子任务，系统剪贴板更新回 Main。 [ScreenshotClipboardWatcher.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/ScreenshotClipboardWatcher.kt) |
| D13（旧报告:1147） 分词剪贴板对长文本无任何上限 | 已修复 | 当前未见旧触发链 | 入口和分词预算 4000 字/1500 token，选择使用 payload 刷新。 [ClipboardTextTokenizer.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/ClipboardTextTokenizer.kt) |
| D14（旧报告:1164） 字体刷新标志"只读不清" → 每次聚焦输入框都全量重建组件树 | 已修复 | 当前未见旧触发链 | 关键输入生命周期内延后整树重建，字体/字号刷新改用 generation；交接记录虚拟键盘路径已做真机热刷新，物理键盘分支及气泡仍欠验证。 [FcitxInputMethodService.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt) |

### E 组

| 编号 / 原问题 | 旧报告状态 | 当前状态 | 当前依据 |
| --- | --- | --- | --- |
| E1（旧报告:1190） 模糊遮罩每帧对每个按键做一次"全屏位图绘制" | 已修复 | 当前未见旧触发链 | 合并按键裁剪路径后一次绘制模糊图。 [InputView.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt) |
| E2（旧报告:1212） 水波纹每帧为每个 ripple 新建 `RadialGradient` | 已修复 | 当前未见旧触发链 | 渐变 shader 缓存并变换位置/半径，不再每个 ripple 每帧新建。 [KeyboardWaterRippleView.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWaterRippleView.kt) |
| E3（旧报告:1228） 模糊区域重建抖动：onDraw 内自重绘 + 同帧两次全树遍历 | 已修复 | 当前未见旧触发链 | 重试预算 4 次，统一请求刷新，目标视图缓存减少重复全树收集。 [InputView.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt) |
| E4（旧报告:1247） 辅助栏位置/内容的无差别重建 | 已修复 | 当前未见旧触发链 | 辅助栏位置去重，内容复用子 View；CandidatesView 仅真实可见性变化回调。 [InputView.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/InputView.kt) |
| E5（旧报告:1266） 候选栏每次按键"全量刷新 + 递归 view.post" | 已修复 | 当前未见旧触发链 | 当前 prefix/suffix 计划做增量通知，active 单独刷新，布局期间更新合并；原无预算递归已替换。新角标的位置依赖遗漏另列 N3。 [HorizontalCandidateViewAdapter.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/candidates/horizontal/HorizontalCandidateViewAdapter.kt) |
| E6（旧报告:1285） `textKeys` 每次全树递归遍历并新建 List | 已修复 | 当前未见旧触发链 | 按样式刷新失效的惰性 TextKeyView 列表缓存。 [TextKeyboard.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt) |
| E7（旧报告:1294） 布局编辑器每次 bind 重建整行 chip（含 GradientDrawable） | 已修复 | 当前未见旧触发链 | chip 与背景 Drawable 复用，拖拽只刷新涉及行。全量数据/样式替换仍允许完整刷新。 [KeyboardLayoutAdapter.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/adapter/KeyboardLayoutAdapter.kt) |
| E8（旧报告:1313） `ButtonsBarUi` 把 position 当 viewType，复用池永不命中 + bind 内自触发全量刷新 | 已修复 | 当前未见旧触发链 | 已换成固定子 View 行布局，旧 position=viewType 及 bind→notify 循环均不存在；交接另记录过 OnePlus 真机悬浮/单手/状态区等 8 组中央按钮行回归。 [ButtonsBarUi.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/bar/ui/idle/ButtonsBarUi.kt) |
| E9（旧报告:1330） 预览刷新替换全局 provider 并清空静态 keydef 缓存，每次重建整棵键盘 | 已修复 | 当前未见旧触发链 | 预览通过临时覆盖与独立 cache 前缀，未替换实际 ConfigProvider；内容/环境 signature 相同短路。 [KeyboardPreviewManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/preview/KeyboardPreviewManager.kt) |
| E10（旧报告:1350） `MotionEvent.obtain` 从不 `recycle` | 已修复 | 当前未见旧触发链 | 分发复制 MotionEvent 后 finally 回收。 [BaseKeyboard.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt) |
| E11（旧报告:1359） `notifyDataSetChanged` 的广泛滥用 | 已修复 | 当前未见旧触发链 | 旧报告指定的高频三处已增量化。低频更换整份列表/样式的 notifyDataSetChanged 仍在，不把 API 出现次数当缺陷数量。 [UpdateAssetAdapter.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateAssetAdapter.kt) |

### F 组

| 编号 / 原问题 | 旧报告状态 | 当前状态 | 当前依据 |
| --- | --- | --- | --- |
| F1（旧报告:1370） 8 个回调赋给 object 单例后永不清空 → 整棵 InputView 树泄漏，且重建后状态打到已销毁 UI | 已修复 | 当前未见旧触发链 | 按 owner 释放 8 类语音 UI 回调，反注册剪贴板/主题/偏好监听，InputView detach 调用此清理。 [KawaiiBarComponent.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/bar/KawaiiBarComponent.kt) |
| F2（旧报告:1395） `SplitKeyboardStateManager` 监听器只增不减，且是一套死代码 | 已修复 | 当前未见旧触发链 | 无消费者的单例 listener 机制与键盘注册已删除。 [BaseKeyboard.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt) |
| F3（旧报告:1404） `ButtonsAdjustingWindow` 是 `data object` 单例，视图树与首个 InputView 的 context 绑定并常驻 | 不再适用 | 不再适用 | `245e4bf1` 删除实现及入口。ButtonsAdjustingWindow 单例整体移除，原 context 常驻路径不存在。 [build.gradle.kts](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/build.gradle.kts) |
| F4（旧报告:1413） 分词 job 挂在 service 作用域且从不取消 | 已修复 | 当前未见旧触发链 | 保存 tokenizeJob 并取消，UI 回写使用同一 job 的 withContext(Main)。 [TokenizedClipboardWindow.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/clipboard/TokenizedClipboardWindow.kt) |
| F5（旧报告:1433） 静态 keydef 缓存无上限 | 已修复 | 当前未见旧触发链 | 静态缓存使用 access-order LinkedHashMap，最多 12 项；旧死字段移除。 [TextKeyboard.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/TextKeyboard.kt) |
| F6（旧报告:1441） `Logcat` 子进程与 pipe fd 留到 GC | 已修复 | 当前未见旧触发链 | 快照流 use 关闭并 wait/destroy；清日志进程 wait/destroy。未做长期 fd/进程压力实测。 [Logcat.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/utils/Logcat.kt) |
| F7（旧报告:1447） 内嵌 HTTP 服务线程池无界 | 已修复 | 当前未见旧触发链 | 8 个连接 worker + 1 个 accept worker，有限队列/拒绝策略，stop 关闭 executor。 [ImeWebEditorBridgeServer.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/webeditor/ImeWebEditorBridgeServer.kt) |
| F8（旧报告:1453） 剪贴板同步状态无界写入默认 SharedPreferences | 已修复 | 当前未见旧触发链 | 私有文件存储、内容与条目预算保留；f54eb713 进一步修复迁移确认及 pending/dirty 的并发发布。 [SyncStateStore.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/SyncStateStore.kt) |

### G 组

| 编号 / 原问题 | 旧报告状态 | 当前状态 | 当前依据 |
| --- | --- | --- | --- |
| G1（旧报告:1466） 每次重连都新建 `OkHttpClient` | 未修复 | 本次修复 | d8772107 中 OneClip 复用 HTTP/SSE 客户端，ClipCascade 基于共享 transport 派生并隔离账号 Cookie。 [OneClipEventClient.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/OneClipEventClient.kt) |
| G2（旧报告:1474） `MemoryCookieJar` 整体替换 host cookie → 会话丢失后进入重连循环 | 已修复 | 当前未见旧触发链 | 按 name 合并 cookie 并移除过期项，避免一次响应覆盖会话 cookie。 [ClipCascadeClient.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/ClipCascadeClient.kt) |
| G3（旧报告:1482） 接收过滤每条目重建（含正则编译） | 已修复 | 当前未见旧触发链 | 接收 filter 缓存，相关偏好变更失效；分隔 Regex 常量复用。 [MainService.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt) |
| G4（旧报告:1490） MediaStore 全表扫描 | 已修复 | 当前未见旧触发链 | MediaStore 按名称 selection + 修改时间排序，不再把全表拉到 Kotlin 过滤。 [UpdateCheckActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateCheckActivity.kt) |
| G5（旧报告:1498） hosts 抓取用裸 `OkHttpClient()` | 已修复 | 当前未见旧触发链 | hosts 抓取共享 client，并强制 HTTPS 来源。 [UpdateRepository.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/update/UpdateRepository.kt) |

### N1–N8

| 编号 | 原问题 | 当前状态 | 当前依据 |
| --- | --- | --- | --- |
| N1 | 随机主题破坏 Direct Boot | 本次修复 | ad56c9b7 移除静态槽位读取，解锁前跳过凭据主题扫描/生成/写入；有锁定状态框架测试，完整开机输入仍需设备回归。 [ThemeManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeManager.kt) |
| N2 | 旧版 Outline.setPath/凸性崩溃 | 当前未见旧触发链 | 当前两处均为 API 30 以上 setPath，低版本 setRoundRect；旧报告 10-19 修订已记录此修复。 [ContinuousCornerGeometry.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/bar/ContinuousCornerGeometry.kt) |
| N3 | 候选移动后序号角标不重绑 | 本次修复 | 53477190 为移动后的保留后缀发送角标专用 payload，只刷新位置与 active，不重建文本；同时修正字体变化遇到候选变化时漏绑的后缀。15 个计划测试、10 个真实 RecyclerView 框架执行通过。 [HorizontalCandidateViewAdapter.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/candidates/horizontal/HorizontalCandidateViewAdapter.kt) |
| N4 | 旧 swipe 外观编辑改变方向 | 本次验证通过 | ccfa8636 在 API23/28 的实际共享表单验证6种支持方向宏的键型、4种方向与4种符号位置，含改外观及更换偏好后再次打开；14个测试执行、每个API96个组合通过。未观察到旧固定写入下划的触发链。 |
| N5 | Rime 方案菜单没有滚动容器 | 本次修复 | 6c22d0cc 增加滚动容器，方案名换行且保留最小触控高度；10 个 API 23/28 框架执行及 API 35 原生大字号、长名称、末项选择回归通过。 [RimeSchemaMenuDialog.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/dialog/RimeSchemaMenuDialog.kt) |
| N6 | OneClip 预热无限等待/不可取消 | 本次修复 | d8772107 普通 HTTP 有界且可取消，SSE 建连设期限、建立后保留无限 idle；6 项真实 localhost 网络用例通过。 [OneClipEventClient.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/network/OneClipEventClient.kt) |
| N7 | 磁盘与保存 baseline 来自两份活数据 | 本次修复 | 3a82e280 Main 一次捕获深拷贝，IO 与 baseline 使用同一份快照；后续编辑保留 dirty，有实际保存和嵌套宏拷贝测试。 [LayoutDataManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/data/LayoutDataManager.kt) |
| N8 | 引擎启动不能停止/失败卡状态/Main 阻塞 | 已修复 | 9b1926db 的直连 desiredRunning 状态机处理 STARTING 停止、失败与重启，普通停止异步等待 native owner。同步备份屏障单独保留。 [EngineLifecycleController.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/core/EngineLifecycleController.kt) |

## 2026-09-07：P0/P1/P2 逐项对照

### P0

| 编号 | 原问题 | 当前状态 | 当前依据 |
| --- | --- | --- | --- |
| P0-1 | LAN Web 编辑桥无鉴权 | 已修复 | 032a7ca5 增加六位配对、限速、会话 Cookie 与 Host/Origin 校验；尊重局域网 PC 编辑用途，未套用旧报告逐次设备确认的交互建议。 [ImeWebEditorBridgeServer.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/webeditor/ImeWebEditorBridgeServer.kt) |
| P0-2 | 导出 AIDL 无签名权限 | 已修复 | 032a7ca5 为 Service 与全部 Binder 入口增加权限检查。 [FcitxRemoteService.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/FcitxRemoteService.kt) |
| P0-3 | 旧输入事件写入新编辑器 | 已修复 | 直连事件带 inputSession；消费前核对 generation，解绑先 invalidate。语音广播仍另列 P1-3。 [FcitxInputMethodService.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt) |
| P0-4 | Commit/Delete/Key 可丢弃 | 已修复 | 直连 InputEventQueue 保序保存副作用事件；9b1926db 合并状态并增加输入背压。 [InputEventQueue.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/core/InputEventQueue.kt) |
| P0-5 | 主题 ZIP 路径穿越 | 已修复 | 已有 resolveEntryTarget 限制解压目标；a38ac510 补齐 JSON 主题名、背景最终写入路径与读取入口限额。 [ThemeFilesManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/data/theme/ThemeFilesManager.kt) |
| P0-6 | STARTING 阶段不可停止 | 已修复 | 9b1926db 使用 desiredRunning、串行启动/停止和失败收敛；普通断连异步关闭。 [EngineLifecycleController.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/core/EngineLifecycleController.kt) |
| P0-7 | 旧 dispatcher teardown/wake 竞态 | 不再适用 | FcitxDispatcher/native dispatcher 实现已移除；直连 owner 与新生命周期另有测试。 [EngineLifecycleController.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/core/EngineLifecycleController.kt) |
| P0-8 | InputContextCache 析构重入 | 不再适用 | 旧 Fcitx C++ cache 与 AndroidInputContext 已删除，没有这条析构调用链。 [build.gradle.kts](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/build.gradle.kts) |
| P0-9 | activeIC 为空时分页崩溃 | 不再适用 | androidfrontend.cpp/activeIC_ 已移除；不把移除记作修好旧实现。 [build.gradle.kts](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/build.gradle.kts) |
| P0-10 | 剪贴板同步默认明文 HTTP | 本次修复 | 143ccf73 使新地址默认HTTPS，显式HTTP保留局域网兼容；一次迁移将既有隐式HTTP地址写为明确HTTP，移除新安装的硬编码局域网端点，并在网络层阻止HTTPS降级重定向发送凭据。真实TLS重定向与API23/28迁移等19项新增执行通过。 |
| P0-11 | native 来源浮动与 patch 失败忽略 | 已修复 | prepare_personal_build.sh 使用 set -euo pipefail，按已提交的 native gitlink 初始化，不再 checkout 浮动 master 或忽略旧补丁失败。 [prepare_personal_build.sh](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/prepare_personal_build.sh) |

### P1

| 编号 | 原问题 | 当前状态 | 当前依据 |
| --- | --- | --- | --- |
| P1-1 | 云备份/手动备份包含敏感数据 | 本次修复 | 11137251 系统云备份与设备转移仅收录独立的外观白名单快照，主偏好、凭据、剪贴板库与Rime数据不在范围内；手动默认密码认证加密，独立小块避免整包缓冲，错误密码/篡改/截断/重排/超限/取消均在接触恢复数据前失败。旧ZIP导入与明确标注的明文ZIP导出保留兼容；16项新增执行通过。 |
| P1-2 | 语音旧回调/finish 操作新会话 | 本次修复 | b2e14f0d 为六类 AIDL 回调统一在 Main 执行时核对提供方/回调/编辑器会话；音频采集和旧 feed 错误按代号隔离；drain 前捕获 endStream 的提供方与会话，完成后再核对，避免关闭新识别。保留同一编辑器 restartInput，切换/结束编辑器则失效并停止采集。含真实管理器 drain 的 21 项新增回归。 [VoiceInputProviderManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceInputProviderManager.kt) |
| P1-3 | 迟到语音广播写入下一编辑器 | 本次修复 | c36a8a1a 对可选浮窗回退使用随机请求令牌+原编辑器生命周期，在Main同步验证，最终结果仅一次；停止/新请求/切换编辑器失效，畸形extra拒绝。只为声明支持令牌的FloatingService启用该回退，无令牌旧广播不直接上屏；正常AIDL接口不变。8个纯逻辑+12个API23/28实际接收器/能力声明执行通过。 |
| P1-4 | 宏跨会话和取消后 modifier 未释放 | 本次修复 | 95745ca8 为宏绑定编辑器会话并在每步/延迟后校验，按所有者登记 DOWN 与原始释放动作；Tap/Shortcut/异常取消清理所属按键，退出/解绑/隐藏输入视图取消宏。会话失效后仅清理本地修饰键，不向旧或新 InputConnection 发 UP；原编辑器内正常补 UP。保留成功 Down-only 宏等待后续 Up 的语义。13 项回归通过。 [BaseKeyboard.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/BaseKeyboard.kt) |
| P1-5 | 异步布局保存访问 UI 活数据 | 本次修复 | 3a82e280 在 Main 捕获深拷贝、串行保存，IO 只处理独立副本，并发布其同一份 baseline。 [LayoutDataManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/data/LayoutDataManager.kt) |
| P1-6 | SyncStateStore 迁移丢失窗口 | 已修复 | f54eb713 写入成功后才删除旧 prefs，失败保留源与 overlay，后续写入可重试。 [SyncStateStore.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/SyncStateStore.kt) |
| P1-7 | 资产 symlink/file 类型迁移破坏 | 本次修复 | ea6229a4 将资产迁移改为先删旧类型、再建目录/文件、最后建链接；补文件与目录互换、空目录和中断重试，删除不跟随链接，文件用原子替换。目录排除递归生效，内置预设留在 APK，不再重复同步到数据目录。22 项 API 23/28 真实文件/软链接及当前资产清单回归通过。 [DataManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/core/data/DataManager.kt) |
| P1-8 | 用户数据恢复非事务/无上限 | 本次修复 | bb9d2a78 接入独立恢复进程、跨目录全局日志及初始化前恢复锁；保留冲突/未连接卷，方案操作检查待恢复路径，修复旧偏好备份及未替换数据库WAL处理。新增41项、完整1197项通过，六个主机子进程被强制结束后恢复通过。Android端交接与实际卷拔插待补验。[协议、测试与边界](/maintainer/audit/user-data-restore-2026-10-27/)。 |
| P1-9 | Ready 早于旧 JNI callback 安装 | 不再适用 | 旧 native-lib.cpp 已删除；直连生命周期先安装输入 gate 再发布 READY。 [DirectRimeBackend.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/core/DirectRimeBackend.kt) |
| P1-10 | 关机保存窗口不可靠 | 本次模拟器验证通过 | 实际学习后不主动保存，正常关机广播触发保存；重启后确认内核、boot_count及native PID变化，用户词典导出与新进程排序均保留“泥”。限小词表软件模拟器正常关机，不证明真机或突然断电。[日志、读回与来源](/maintainer/audit/native-shutdown-2026-10-27/)。 |
| P1-11 | 语音重绑遗留 DeathRecipient | 本次修复 | 116ecabc 统一登记/移除 DeathRecipient，重绑先 unlink；死亡通知在 Main 执行时核对独立登记身份，过期通知不影响新绑定；迟到连接先核对 activeConnection，断开/空绑定按发出回调的连接核对。7 项 Binder 生命周期用例通过。 [VoiceInputProviderManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceInputProviderManager.kt) |

### P2

| 编号 | 原问题 | 当前状态 | 当前依据 |
| --- | --- | --- | --- |
| P2-1 | 剪贴板编辑 finish 抢先取消数据库更新 | 本次修复 | 7dddf7b9 将已确认的保存交给应用级任务，正常写入后再关闭；复制与 lastEntry 通知均在 Room 成功后执行，拒绝更新已删除记录；新 Intent 隔离迟到加载和旧保存结果。14 个真实 Activity/Room/SQLite 场景在 API 23/28 共 28 项执行通过，覆盖关闭、失败重试、重复点击和新旧条目交错。 [ClipboardEditActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/ClipboardEditActivity.kt) |
| P2-2 | 布局未加载完成时丢 ActivityResult | 本次修复 | e2ce518a、6f96066b 为按键、辅助栏、布局/文件命名、图片及扫码结果建立可保存的等待队列，加载/草稿/UI 完成后才应用；内部子页面绑定文件/条目/令牌，拒绝旧结果。内嵌宏/颜色/组合键结果及原选择/表单跨再次重建保留。28 个新增 API23/28 框架执行通过；普通/辅助按键已有真实子页面返回与重建回归。 [TextKeyboardLayoutEditorActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/TextKeyboardLayoutEditorActivity.kt) |
| P2-3 | hosts 下载随 Activity 销毁取消 | 本次修复 | d3cb8769 将 API29+ hosts 下载交给持久 WorkManager，保留页面外进度、强 ETag 断点与安全发布恢复；API23/28 保留系统下载。31项定向、完整1156项应用JVM与6项构建逻辑通过，ARM64 CI全绿。[验证与边界](/maintainer/audit/hosts-downloads-2026-10-27/)。 |
| P2-4 | 候选长按菜单异步结果过期 | 本次修复 | c80f3ce0 将请求、编辑器、当前视图、候选版本与锚点绑定身份一起核对；换候选、隐藏或回收即取消菜单，点击后在 native owner 再次原子核对。23 个定向测试通过；候选及方案菜单设备回归见本轮审计。 [BaseInputView.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/BaseInputView.kt) |
| P2-5 | 语音布局快照仅按 input class 失效 | 本次修复 | b2e14f0d 为语音布局快照绑定编辑器令牌；新编辑器即使 input class 相同也丢弃旧快照，只有同一编辑器的 restartInput 保留恢复资格。编辑器令牌与回调回归通过；本次未做真机语音 UI 验收。 [KeyboardWindow.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard/KeyboardWindow.kt) |
| P2-6 | binding 为空时跳过 deactivate | 本次验证通过 | f37b27b6 在 API23/28 实际调用 null currentInputBinding 的 onUnbindInput：编辑器令牌/引擎代号失效、旧按键缓存清空；准备下一输入框后旧key ticket仍被拒绝，共4个框架执行。直连输入gate在UID提前返回前已失效，旧Fcitx activeIC后果不再适用；不冒充OEM多屏硬件验收。 |
| P2-7 | SetupFragment view binding 泄漏 | 已修复 | 当前 onDestroyView 清空 nullable binding。 [SetupFragment.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/setup/SetupFragment.kt) |
| P2-8 | PAUSE action 后仍无条件 start | 本次修复 | d3ff4115 对 PAUSE 先完整停止并提前返回；关闭偏好也移除服务，迟到启动/重连不能重新打开同步。已包含服务回归。 [MainService.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/clipboardsync/MainService.kt) |
| P2-9 | 宏编辑取消仍保留 code 修改 | 本次修复 | 38815b63 对话框只编辑 KeyData 副本，确认后提交；取消/返回不改原键，删除按对象身份定位，避免误删相同的导入键或未确认的新键。8 个真实 Material 对话框场景在 API 23/28 共 16 项执行通过。宏编辑器的完整进程草稿恢复仍另列 B1。 [MacroEditorActivity.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/dialog/MacroEditorActivity.kt) |
| P2-10 | 旧 JNI jchar/UTF-8 错误转换 | 不再适用 | 旧 native-lib.cpp 与 Char overload 路径已删除。 [build.gradle.kts](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/build.gradle.kts) |
| P2-11 | 语音日志包含完整识别文本 | 已修复 | 浮动广播及 provider 的 partial/final 日志已改为长度，未再沿用旧报告的全文日志断言。 [VoiceInputProviderManager.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/src/main/java/org/fcitx/fcitx5/android/input/voice/VoiceInputProviderManager.kt) |
| P2-12 | release 不混淆 | 保留的构建选择 | -dontobfuscate 仍在；属于逆向难度/调试取舍，不列成功能故障。 [proguard-rules.pro](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/proguard-rules.pro) |
| P2-13 | AGP/Gradle internal API | 本次修复 | d06f7eab 移除 Gradle internal provider 反射、AGP 包任务内部输入修改及编译后删除 Kotlin metadata；使用标准打包产物。完整构建验证见本轮审计。 [AndroidAppConventionPlugin.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/build-logic/convention/src/main/kotlin/AndroidAppConventionPlugin.kt) |
| P2-14 | CI/工程 CMake 版本冲突 | 已修复 | 当前 Commit CI 由固定工程版本准备 CMake，不再硬装 3.22.1。 [ci.yml](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/.github/workflows/ci.yml) |
| P2-15 | ABI 参数与普通 CI 覆盖不足 | 按需求关闭 | d06f7eab 已完成参数校验与构建 API 修复。用户于 2026-10-26 明确仅需 arm64-v8a 发布构建，四 ABI 覆盖不列为待修问题；现有 ARM64 CI 符合此范围。发布使用 BUILD_ABI=arm64-v8a 或 -PbuildABI=arm64-v8a。 [NativeBaseConventionPlugin.kt](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/build-logic/convention/src/main/kotlin/NativeBaseConventionPlugin.kt) |
| P2-16 | native assets 写入源码目录 | 已修复 | 当前 prepareDirectRimeAssets 写入 build/generated/direct-rime-assets，不再把 native 生成资源直接写进 src/main/assets。 [build.gradle.kts](https://github.com/SandyYuR/fcitx5-android/blob/710e4ef93f20eaf1d80d93913be1edfaefdb097d/app/build.gradle.kts) |

机器可读索引：[JSON 对照表](/maintainer/direct-rime-legacy-review-comparison-2026-10-24.json)。

## CI、文档与验证记录

- 旧报告“CI 不跑 JVM 测试”已过时：当前 Commit CI 有独立 unit_test，默认分支 Nightly 同时依赖 build_commit 与 unit_test；`prepare_personal_build.sh` 不再覆盖浮动 native 分支。
- 完整 lint、ARM64 Release 真机验收、Room 各版本迁移及完整 Direct Boot/FGS 配额/语音/多方案菜单仍未成为当前普通提交门禁；CI 成功不能替代这些验收。
- 旧用户/维护文档已在 docs 分支与文档站恢复入口，代码 README 指向它们。历史标题中的“未修复”只代表其基准时点。
- 整理前7e5c2c1b的CI完整应用JVM测试1233项、零失败/跳过；构建逻辑6项沿用018本地完整回归，相关源码未变。[最新审计记录](/maintainer/audit/status-hints-2026-10-27/)。包含多版本 Robolectric、Native SQLite及六次独立主机进程强制结束；后续另完成209cca0e的Android交接及读回，详见下方独立记录。
- 真实 localhost HTTP 用例覆盖配对后直接写入/读回应用布局文件、连接静默、关闭与协程取消、SSE 建连期限及建立后的正常静默。
- 方案目录测试模拟日志已持久化、移动前后、配置提交前后的中断状态；验证生产恢复函数的回滚/完成与冲突保留，不冒充物理断电实测。该历史方案日志不保护整包备份；后续 bb9d2a78 已另行接入整包恢复日志。
- APK 核验：22 个 dex，仅 libdirect-rime.so 与 libc++_shared.so，无 Fcitx native 依赖或旧后端类，16 KiB 对齐；4bb3486c 及更早 APK 缺规则的历史情况保留在旧审计中；当前 6f96066b 包含固定规则、许可证与来源文件，并验证不复制到 Rime 数据目录。
- Android 6 标准 UTF-8 ZIP 可读；平台没有 Charset ZIP 构造函数，GBK/Big5 条目名返回受控错误，Android 7 以上保留多编码兼容。
- 上一轮 `ea6229a4` 新增 API 35 AOSP x86_64 Debug 设备侧回归：2 项通过，含 6 轮真实触屏输入、候选点选与编辑器重绑；1 项英文模式提示显示断言失败，该时点原因待查；后续7e5c2c1b已定位并通过可见帧确认回归，原始失败仍保留。[模拟器结果、截图与边界](/maintainer/audit/direct-rime-2026-10-25/)。无 KVM，耗时不用于真机性能比较；仍未新增真机基准、完整锁屏开机或 minified ARM64 Release 装机验证。
- 历史 `b2c4631a` 已在同一模拟器安装并检查真实编辑界面：JSON 宏初始未修改、选 B 后取消仍为 A、剪贴板确认后在真实 SQLite 文件读回新值。[本轮截图、数据与来源](/maintainer/audit/editor-saves-2026-10-25/)。关闭期间写入由 JVM 受控延迟测试覆盖，本轮设备侧竞态检查尚未完成，不计为设备通过。
- 历史 `1c8a7d04` 的设备回归通过：3 轮输入法切换/宿主 restartInput 后无 crash；触屏输入 N、I 并点选“你”，宿主 A 上屏“你”且仅一次 commit。该检查针对本轮额外发现的旧布局回调崩溃，[异常栈、截图与验证边界](/maintainer/audit/queued-layout-2026-10-25/)。
- 历史 lint 的 449 errors/656 warnings 属于 aef0c21e，不能当成本提交重新测得的数量。
- 历史 ARM64 CI：[Commit CI 40190089463](https://github.com/SandyYuR/fcitx5-android/actions/runs/40190089463) 对应 `1c8a7d04`，完整 JVM 单测与 ARM64 Release 构建均已通过。
- [下载历史 ARM64 Release 产物](https://github.com/SandyYuR/fcitx5-android/actions/runs/40190089463/artifacts/12598505126)，对应 `1c8a7d04`。前三批 `b2c4631a` 的 CI 也通过，旧产物和元数据保留在 JSON 历史中。中间提交 `7dddf7b9` 的构建由最终提交替代后取消，不记为构建失败。

- 前一阶段提交 `4bb3486c`：[Commit CI 40213813097](https://github.com/SandyYuR/fcitx5-android/actions/runs/40213813097)，完整 JVM 单测与 ARM64 Release 构建均已通过。
- [下载前一阶段 ARM64 Release](https://github.com/SandyYuR/fcitx5-android/actions/runs/40213813097/artifacts/12607275703)，运行代码对应 `4bb3486c`；原生设备测试使用同源 x86_64 Debug，不将它当作 ARM64 Release 装机验收。

- 历史设备记录：`4bb3486c` 的候选菜单、行按钮、长方案列表和镜像导航通过；原方案菜单测试两次在 Web 密码框显示前置条件失败，`e3471045` 加入真实点按聚焦后四种类型完成菜单场景。无点按的程序化类型切换行为仍待定位，[所有原始结果与截图](/maintainer/audit/sync-candidates-2026-10-25/)。

- 最终独立宿主触屏回归通过：4bb3486c，A=`你`、B 为空，单次 commit；[本轮截图和实际数据](/maintainer/audit/sync-candidates-2026-10-25/)。

- 上一批 `e146d2cb`：[CI 40219854407](https://github.com/SandyYuR/fcitx5-android/actions/runs/40219854407) 的 JVM、Python 包校验与 ARM64 Release 构建均通过；[下载该批 ARM64 Release](https://github.com/SandyYuR/fcitx5-android/actions/runs/40219854407/artifacts/12608557908)。API35 x86_64 Debug 的规则库检查和 6 轮输入重绑通过，[原始结果](/maintainer/audit/clearurls-2026-10-25/)。

- 前一批运行代码 `6f96066b`：[CI 40247691050](https://github.com/SandyYuR/fcitx5-android/actions/runs/40247691050) 成功，[下载 ARM64 Release](https://github.com/SandyYuR/fcitx5-android/actions/runs/40247691050/artifacts/12619626685)。983 项本地应用 JVM 通过；最终设备组合 3 项、独立输入 1 项全部通过，共 12 轮触屏输入与重绑，无额外提交。[原始正/负结果与诊断边界](/maintainer/audit/editor-results-2026-10-26/)。516252eb、1c96acd9、b193b9ff 均只改测试。

- 触屏补充诊断抓到同步注入把 20 ms 目标短按拉长到 331–547 ms，超过候选 300 ms 按住阈值；已修正注入并补查真实 Android 绑定。原首 n 断言的单独根因尚未证明，保留失败、代号采样说明与系统日志；最终 12 轮通过不替代这些历史记录。

- 本轮 `8a39f2fe` 修复草稿漏存及替换失败，`7ec08c8e` 后台准备 JSON；补充发现不额外减少原 139 行，D7 继续保留部分修复。[完整来源、1002 项 JVM 统计与验证边界](/maintainer/audit/draft-persistence-2026-10-26/)。该批 [ARM64 CI 40269824527](https://github.com/SandyYuR/fcitx5-android/actions/runs/40269824527) 的 JVM、Python 校验和签名 Release 均成功，[下载本批 ARM64 APK](https://github.com/SandyYuR/fcitx5-android/actions/runs/40269824527/artifacts/12628551356)，对应 `7ec08c8e`。

- 前批修复 B1、P1-3，并验证关闭 N4、P2-6；该时点原表剩5条需处理、1条关机持久化待设备验证。额外修复主题预设命名兼容及Java崩溃日志留存，未把缺栈的一次返回闪退算作已修复。[完整1066项与范围](/maintainer/audit/remaining-checklist-2026-10-26/)。该时点可下载 [ea16a375 ARM64 Release](https://github.com/SandyYuR/fcitx5-android/actions/runs/40279688181/artifacts/12632018949)，包含主题和日志修复；后续c36a8a1a语音校验及f37b27b6解绑测试尚未包含在该包中。

- D7范围确认：用户于2026-10-26明确选择保留同步兜底、优先保住最新编辑。本项标记“按需求保留”，不计为本批代码修复；后台JSON预备仍保留，未改为仅恢复最近后台检查点。

## WebSocket续修与CI状态

`c2ba6510`新增组装前限额；`c72115de`补连接池架构回归。该批[CI40319452811全绿](https://github.com/SandyYuR/fcitx5-android/actions/runs/40319452811)，[ARM64安装包](https://github.com/SandyYuR/fcitx5-android/actions/runs/40319452811/artifacts/12648094518)。[1125项测试、堆采样与失败原始记录](/maintainer/audit/websocket-receive-2026-10-26/)。

## hosts 下载续修

P2-3 已由 d3cb8769 处理；[验证与兼容说明](/maintainer/audit/hosts-downloads-2026-10-27/)。后续 P1-8 已由 bb9d2a78 接入跨进程日志；该改动未包含在 d3cb8769 产物中。P1-10 后续已完成本次模拟器关机验证。

## 整包恢复续修

[bb9d2a78 的协议、1197项测试及设备验收边界](/maintainer/audit/user-data-restore-2026-10-27/)。[该批CI全绿](https://github.com/SandyYuR/fcitx5-android/actions/runs/40366006633)，[签名ARM64安装包](https://github.com/SandyYuR/fcitx5-android/actions/runs/40366006633/artifacts/12672830385)包含整包恢复改动；后续544f84a8已完成启动清理优化，1204项回归及ARM64 CI通过。[新验证](/maintainer/audit/user-data-restore-2026-10-27/startup-cleanup/)。

## 恢复启动性能续修

544f84a8 已把可用目录恢复与递归清理分开，并避免启动/输入等待清理锁；[1204项回归与有效性约束](/maintainer/audit/user-data-restore-2026-10-27/startup-cleanup/)。544f84a8与后续8dca031c的ARM64 CI均已通过。后续209cca0e的偏好校验及7e5c2c1b的提示修复均已完成，最终产物以文末最新CI为准。

## 偏好类型校验与关机验收

209cca0e在恢复前检查偏好值类型、嵌套和按堆预算的文件限额；[1218项回归与CI](/maintainer/audit/user-data-restore-2026-10-27/preferences-types/)。P1-10已有[本次模拟器正常关机后的词典读回证据](/maintainer/audit/native-shutdown-2026-10-27/)；原表代码项没有新增待处理行。恢复进程Android交接及历史英文提示均已通过本轮模拟器功能回归；一次主题返回崩溃仍按用户要求等待有效栈。

## 恢复交接验收与后台清理边界

209cca0e的Android恢复成功页与新进程读回已通过，[偏好、真实WAL及交接PID](/maintainer/audit/user-data-restore-2026-10-27/android-handoff/)。首次instrumentation清理会停止恢复进程，宿主在其结束后重启保留请求；记录该测试框架边界，不将预期杀进程当成应用崩溃。

209cca0e的CI发现测试销毁目录与后台清理并发，签名构建成功但单测失败；9b1f958c等待夹具清理结束，并约束后台不恢复待处理live root。[原始失败、41项定向与完整1220项结果](/maintainer/audit/user-data-restore-2026-10-27/background-boundary/)。新的[ARM64 CI40411170854](https://github.com/SandyYuR/fcitx5-android/actions/runs/40411170854)已全绿；3e09e289只增加设备测试。

## 英文提示续修完成与最终交付

7e5c2c1b已补齐从可见帧计时、owner线程处理及API包装显式转发。[实际确认记录、原始失败、1233项CI与3项设备通过](/maintainer/audit/status-hints-2026-10-27/)。该批[CI40417349690全绿](https://github.com/SandyYuR/fcitx5-android/actions/runs/40417349690)，[签名ARM64产物](https://github.com/SandyYuR/fcitx5-android/actions/runs/40417349690/artifacts/12696406274)包含全部已推送修复。

原139行没有剩余“仍存在/部分修复”的代码项；P1-10正常关机与P1-8 Android交接已有各自设备证据。D7同步兜底、仅ARM64构建按用户需求保留；一次缺栈主题返回崩溃仍按用户指示等待复现。真机大词库、完整Direct Boot/系统配额、实际外置卷拔插与物理断电等仍是未完成的验收边界，不把它们写成模拟器已验证。

## 2026-10-27 变基后的验证（9c9013ca）

该轮代码 `9c9013ca` 已通过本地 1234 项应用 JVM 与 6 项构建逻辑回归、Debug/AndroidTest 构建及两项 API35 行标题栏设备用例。[新CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/40428247791) 单元测试与签名 ARM64 构建通过。[本轮完整记录](/maintainer/audit/perf-rebase-2026-10-27/)。上文提示、输入、关机及恢复设备记录保留原 SHA 和范围，没有把旧包测试改记为本次设备执行。

## 中文文档归并与当前合入验证

当前代码 `710e4ef9` 的 [PR CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/40509320725) 与 [Commit CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/40509323655) 均通过，完整应用 JVM 1234 项、ARM64 Debug 及签名 Release 成功。文档站统一维护中文功能及实现说明，保留 83 份旧原件和所有原始验收来源。[合入准备](/maintainer/history/review-2026-10) · [本次验证与文档迁移](/maintainer/audit/merge-prep-2026-10-27/)。旧设备记录仍按其实际运行提交查阅。
