# 新增真机日志：首次选词等待与连续保存日志

本文先记录 `12ab22cd` 的定位与 `9adb6949` 的修复验证，随后追加
[新包真机回访](#9adb-真机回访)。用户反馈有所好转；两次已记录的首次选词
core 为 4.655 / 46.693ms，同线程 sync 均为 0。本次未见旧 145ms 尖峰，但仍有冷等待，
不能据两份片段确认所有场景已经解决。

## 修复前两份真机日志

用户新增的两个文件名时间为 UTC 2026-10-18 23:32:39 / 23:33:29，正文为手机本地
2026-10-19 07:32 / 07:33。均来自签名 Release `12ab22cd82297638bee75ef30b31b3decb21decb`，
OnePlus PJD110 / Android 16。这不是本轮新修复包的测试结果。
仓库只保存[脱敏计时](perf/2026-10-19-cold-io/sanitized-phone-timing.json)，不提交用户原始日志和输入内容。

| 操作 | 原生 core 墙钟 | 同线程 CPU | 其他关键证据 |
| --- | ---: | ---: | --- |
| 第一份日志首键 | 84.504ms | 76.504ms | minor/major faults 2225/2094；owner 排队 0.828ms |
| 第一份日志首次选词 | 3.890ms | 1.002ms | 提交请求 accepted |
| 第一份日志跨应用建会话 | 708.743ms | 185.504ms | created=1；跨应用仍重建 Lua 状态 |
| 第二份日志首键 | 49.644ms | 31.710ms | minor/major faults 741/565；owner 排队 0.388ms |
| 第二份日志首次选词 | **145.358ms** | **16.524ms** | major fault=1；自愿/非自愿切换 62/30 |
| 第二份日志后续选词 | 4.513ms | 3.248ms | owner 排队 0.297ms |

慢选词的服务排队 0.758ms，owner 排队 0.217ms；读 commit/context/status/tabs 总计约 4µs。
07:32:58.873 的 InputConnection 提交请求 accepted，07:32:58.890 回报光标从 0 到 4。
这次有编辑器接收后的位置更新证据，不能再称这份日志复现了上一次“文字一直没有出现”。
位置更新不是聊天发送确认，也不能替代文字内容验收。

**145ms 的主要空档仍在 Rime 同步选词内部，不能归咎于候选绘制、JNI 快照或主线程提交调用。**
约 129ms 没有计入该线程 CPU，说明要继续区分阻塞与调度；单凭计数不能确定是哪一个文件，
major fault 也不能直接等同物理磁盘读取。首键的 CPU 与缺页更高，属于另一个需要处理的冷路径。
本轮不以“已找到全部根因/已经消除卡顿”结案。

## 源码追查与定点诊断

对照校验固定的万象 base v18.1.3 ZIP，而非只看其当前默认分支：

- `lua/wanxiang/context_reorder.lua` 的 `get_db()` 在需要时 `LevelDb(...):open()`；
  首次合法中文 commit 回调也会进入它。首次读写数据库可以落在选词到提交之间。
- `lua/wanxiang/input_statistics.lua` 已在 init 中 acquire DB，commit 时仍会读取/更新统计；
  不能将所有首次开库都归到统计模块。
- librime `Context::Commit()` 同步调用 commit notifier，再清空 composition。
  前端要等选词 C API 返回才取 commit，学习/数据库回调的等待会直接延后上屏。
- LevelDB 常规写入默认不启用逐笔 sync；DB 打开/恢复/manifest 等路径仍可能等待。
  不能把“关闭每次 fsync”当作已经发现的问题或关闭用户调频规避它。

新增独立工作流 `direct-rime-cold-probe.yml`：只测试本轮当前版本、用户两份 YAML 对应的
base + 固定 LTS，不再跑旧 Fcitx、修复前版本或 Foxy。一次部署，六次真实重建会话并选词。
只在这个一次性测试 fixture 的 `lua/wanxiang/userdb.lua` 加入 open/fetch/update/query 包装，
保留原返回值与学习行为，记录数据库类别、方法、墙钟时间，不记录数据库键、词或用户路径。
该脚本不进产品 APK、不改用户方案；修改后的 fixture 另存哈希。
它用于查调用路径，不能作为 ARM64 手机的性能数字或修改前后收益。

还核对了另一条优化思路：`UserDictionaryComponent` 的 `db_pool_` 是弱引用，旧 session
先销毁会让最后一个 DB 引用消失，下一 session 可能重新开库。直接改成“先建新 session，
再毁旧 session”并不稳妥：旧 UserDictionary 的 pending transaction 在析构时提交，
新字典 Load 可能先从数据库读取旧 `/tick`；当前 LevelDB Fetch 不读 WriteBatch 中未提交的数据。
本轮没有用改变析构顺序来换速度，也没有放宽跨应用/敏感输入的 Lua 状态隔离。
后续若确认开库是主要成本，应在引擎层设计有事务边界的资源保留，而不是暗改用户方案或关闭调频。

产品桥接新增 owner 当前操作内 open/read/pread/write/fsync/fdatasync/stat 的累计时间和次数，
也覆盖 LevelDB 的 fortified `__open_2`。不开文件探测、不记录路径、不增加日志文本内容。
它不覆盖 mmap 缺页、libc 内部 fopen 调用、所有元数据系统调用或后台 LevelDB 线程；
计时为调用墙钟，不能直接叫作纯 I/O 等待。原有 core CPU/缺页/调度计数继续保留。

## 当前修复

1. **连续保存日志竞态。** 原实现先打开/截断目标，再在 IO 线程读取
   `logView.currentLog`，遍历主线程还在追加的 ArrayList，存在明确的并发修改异常窗口；
   此时文件可能为空或不完整。新增日志没有保存失败堆栈，不能声称已经证明用户每次失败都是同一种异常。
   新实现主线程冻结过滤后的原始行及设备信息，IO 线程先生成完整私有暂存文件，成功后才打开文档选择器。
   保存只复制这份不可变文件；写入、关闭均成功才提示完成；阻止重复触发，SavedState/ViewModel
   跨页面重建保留待保存文件。提供者返回 null/写入异常会明确报错并记录堆栈；
   对 CreateDocument 新建的失败目标尽力删除，提供者拒绝删除时不能保证其不会留下空文件。
   同时修正 logcat 子进程在创建/退出交错时的资源清理：未完成创建也会在取消后终止；
   旧 reader 不清理新 reader；关闭移到 IO，先终止子进程再关闭可能阻塞的读取管道。
   这些是源码确认的风险，并非上传日志已经记录了文件句柄耗尽。

2. **交互线程调度。** 绑定、按键、选词/动作的同步 owner 工作期间，将 Linux nice
   从默认 0 临时提高到 Android `THREAD_PRIORITY_DISPLAY`（-4；已有更高优先级则不降低），
   finally 恢复；部署、同步和候选扫描不提高。厂商拒绝调整时回退正常处理。
   这针对调度竞争；不能消除文件等待，也未证明能消除真机 145ms 尖峰。

3. **首键需求保持。** 同代次曾被真实按键提升为需要 Rime 后，后续 metadata 变更必须继续
   把合并后的需求传给 key gate，避免原始 `needsRime=false` 将它降回非输入状态。
   增加改变 program metadata 的设备回归。这不是这两份日志慢选词的已证根因。

4. **减少重新开库的同步刷盘。** 固定版本的 LevelDB `Options::reuse_logs` 默认关闭，
   重开已有库会创建新的 MANIFEST/WAL，并执行同步 IO。桥接层通过 LevelDB 公共 API
   启用 reuse_logs，复用可复用的现有清单/日志；不修改 WriteOptions::sync、词典学习、
   事务和用户 YAML，不搬移用户数据，不提前猜测或重放候选上屏。
   不适合复用或追加失败时，由 LevelDB 自身走正常恢复路径。
   `leveldb-open.cpp` 使用同一固定 prebuilt 的公共头文件，CMake 从每个 ABI 的 archive
   解析 DB::Open 导出符号，无法匹配时构建失败，不硬编码 libc++ 的符号名称。

   Linux LevelDB 1.23 的机制回归：10 次已有库重开，默认 30 次 Sync；经过实际桥接后为 0。
   400 条记录经历重开与 WAL 轮转仍逐条匹配；注入 append 失败后仍完整恢复，必要的 Sync
   仍发生；显式 WriteOptions::sync=true 仍产生 Sync。该选项上游标注 EXPERIMENTAL，
   这些回归不等同断电/全量用户库验证；固定 Android binary 的存储回归与首次选词 IO
   另由一次性万象 fixture 验证。主机机制结果不能当作手机延迟降幅。

## 验证记录

产品源码 `9adb694932df4340bc48d359af2c788ad6809f3e`：本地与 CI 的 491 项 JVM 测试通过；
ARM64 Debug 主 APK、native sanitizer 契约、IO 包装器与 LevelDB 公共 API 存储回归通过。
日志回归覆盖并发追加/清空/过滤时的快照、连续完整写入、输出失败/关闭/重试、缺失源与 null 输出、
待选文件恢复、重复触发、真实 LogActivity/ViewModel 工厂与页面重建。
完整前端设备测试共 16 项，常规运行通过 15 项，1 项完整万象诊断按条件跳过；该项在下面的
独立定点工作流中实际执行通过，不把两种运行混为 16 项常规测试全部执行。

- [Direct Rime CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/39180498509)：
  原生契约、491 项 JVM、ARM64 APK、Android 原生 smoke 和完整前端设备测试均通过。
- [PR CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/39180498528)：JVM 与四 ABI Debug
  构建通过。PR 工作流使用临时合并提交 `96e66f85`；独立 ARM64/前端/定点工作流使用上述
  产品源码。四 ABI APK 的依赖清单检查通过；arm64-v8a / x86_64 的 ELF 对齐为 16KiB。
- [独立万象定点诊断](https://github.com/SandyYuR/fcitx5-android/actions/runs/39180494011)：
  六次重建会话后的首轮输入、选词及唯一提交通过，LTS 模型映射通过；用固定 Android prebuilt
  写入 400 条各 16KiB 记录，关闭重开后逐条读取匹配。

最终定点结果如下。core 是原生选词同步调用墙钟，sync 是同一线程内包装到的
fsync/fdatasync 累计墙钟；这不是触摸到编辑器上屏时间。

| 重建轮次 | 上下文库状态 | 选词 core | sync | Lua context_reorder open |
| --- | --- | ---: | ---: | ---: |
| 0 | 首次新建 | 4.539ms | 1.913ms | 3ms |
| 1 | 已有库 | 1.763ms | 0 | 1ms |
| 2 | 已有库 | 1.663ms | 0 | 0ms |
| 3 | 已有库 | 1.983ms | 0 | 0ms |
| 4 | 已有库 | 1.612ms | 0 | 0ms |
| 5 | 已有库 | 1.696ms | 0 | 1ms |

已有库五轮首次选词均未出现该线程的同步刷盘；初次新建数据库仍执行正常的同步创建。
这验证了减少重开刷盘的机制，没有复现并证明消除手机的 145ms 尖峰。
同轮 Debug 首键约 17–30ms、绑定约 97–128ms，冷路径仍有成本；设备为 CI 模拟器，
不得把它写成手机改善幅度。探针会改变 fixture 和运行开销，也不与前两轮数字作相减比较。

脱敏记录、fixture 哈希、前端设备报告、491 项 JVM 报告和主机存储机制结果见
[本轮证据目录](perf/2026-10-19-cold-io/)。原始用户日志不在其中。

## 最终签名包

[下载 ARM64 Release](https://github.com/SandyYuR/fcitx5-android/actions/runs/39180493901/artifacts/12155759113)，
解压后安装 `direct.apk`。独立签名构建工作流成功，产品源码与上述定点/前端验证一致：
`9adb694932df4340bc48d359af2c788ad6809f3e`；后续提交只归档文档和证据。

- APK SHA256：`966c17b7f8482e053f72f82802f621ee48bcb093d1189c7dd6c5b8886e4ad5ba`。
- 使用仓库 Release 签名，证书 SHA256：
  `ba43006e1bfa3f60f46b2a9056f593a3a61445bd2571319fb1869e49cc36b1fe`，与上一轮 `12ab22cd`
  Release 一致。应用仍为中州鹅，包名 `io.github.sandyyur.rime.direct`。
- 下载后重新校验了源码、APK/ZIP 哈希、签名、原生库依赖、DEX 旧后端与 16KiB 对齐。
  包内仅 `libdirect-rime.so` 和 `libc++_shared.so`，无旧 Fcitx 原生依赖。
- 包内 ART profile 的实际方法表命中 mangled sendKey 和后端协程入口；78 条应用方法记录
  不等于性能收益。审计与构建元数据归档于本轮证据目录的 `release-*`。

[完整验证索引](perf/2026-10-19-cold-io/validation.json)及
[文件校验表](perf/2026-10-19-cold-io/checksums.sha256)。下面已补入用户 Android 16 真机反馈，
不以 CI 组件计时替代真机表现；精确快速发送、独立进程冷启动与长期稳定性仍未完成验收。

## 9adb 真机回访

用户随后提供 `2026-10-19T01_17_15Z` / `01_17_44Z` 两份日志并反馈“感觉好了”。
均确认是本轮签名 `9adb6949` Release、同一 OnePlus PJD110 / Android 16。
正文覆盖北京时间 09:16–09:17。两个文件有时间重叠，来自同一已运行进程，不能当作两次独立进程冷启动；记录的 ordinal=1
是该绑定后的首次选词。只保存[脱敏事件与文件哈希](perf/2026-10-19-cold-io/phone-9adb-0117-sanitized.json)。

| 记录 | 09:16:58，generation 43 | 09:17:37，generation 66 |
| --- | ---: | ---: |
| 原生选词 core 墙钟 | 4.655ms | 46.693ms |
| core 同线程 CPU | 3.455ms | 7.422ms |
| 包装到的 open 累计墙钟 | 0 | 17.700ms |
| 包装到的 fsync/fdatasync 累计墙钟 | 0 | 0 |
| 服务/owner 排队 | 0.880 / 0.786ms | 1.795 / 0.386ms |
| InputConnection 提交调用 | 1.328ms，accepted | 2.741ms，accepted |
| 提交后编辑器位置回报 | 24→29 | 29→32 |

本次两条选词记录都未出现旧日志的 145ms 尖峰，且没有记录到选词线程的同步刷盘等待；
这与减少已有库重开同步 IO 的目标、用户体感改善方向一致。两次交互均记录 nice=-4，
优先级调整也已在该手机生效。不能把两个不同输入场景的值相减或算成确定的加速百分比。

第二次仍有 46.693ms core，其中约 39.271ms 未计入线程 CPU；open 包装累计 17.700ms，
说明仍有开库/开文件及调度冷成本。open 墙钟可能同时包含 CPU 与等待，不能与非 CPU
时间相加；没有文件名或库级 marker，不能仅凭它认定是哪一个数据库或声称所有剩余等待已定位。
第二份日志首键 core 为 32.332ms（CPU 20.077ms），原生调用共 36.149ms，input tabs
另占 3.571ms；后两键 core 为 18.274 / 17.973ms。首键冷成本也还存在。

三次已记录提交均 accepted，并有随后对应的位置更新；本次没有再次出现缺少接收后位置
回报的现象。位置回报不能证明精确文本、最终显示帧或聊天发送结果，日志也没有 Send
按钮时序，因此先前的快速发送问题仍不能据此宣布彻底解决。

两个导出文件分别为 55,180 / 71,766 字节，头部与正文正常，未见 `Log export failed`、
并发修改异常、应用 FATAL/ANR。重复导出空文件本次没有复现；导出快照早于目标写入，
也不能用一份快照中没有后续失败日志来证明之后所有保存均成功。Oplus 的反射/Receiver
和窗口析构报错来自厂商框架，片段中没有对应的输入法自身崩溃栈。

现有证据支持继续日常使用观察当前版本。后续如需再优化，应围绕可测量的开文件和冷计算
成本验证，保持数据库恢复与学习写入的正确性。

## 定位过程的历史证据

第一轮[定点诊断](https://github.com/SandyYuR/fcitx5-android/actions/runs/39178602959)
六次重建并选词、唯一提交和 LTS 映射断言通过；原生计时每次均记录同步刷盘：
core 4.060–5.601ms、其中 sync 1.845–3.057ms。首次选词确实存在同步 IO，且跨会话重复。
这不是复现手机的 145ms，也不证明该手机的全部等待都来自刷盘。
首轮库级 marker 采用 WARNING，被产品 min_log_level=2 抑制；JSON 又遇到 AGP 测试后卸载，
所以首轮只采用 logcat 中六次原生记录，不使用空 JSON。后续探针直接包装测试副本模块，
以诊断 ERROR marker 输出并校验标记存在；JSON 在卸载前写入 logcat，避免静默丢失数据。

[补齐库级标记的诊断](https://github.com/SandyYuR/fcitx5-android/actions/runs/39179387776)
已确认：六次首次选词均进入 `context_reorder` 的 `open`，分别记录 6/4/3/3/3/3ms；
对应 core 约 5.050–7.382ms，sync 2.116–3.831ms。stats 在此前绑定中已经 open。
这是把“可能在开库”等待缩小到具体组件的证据，不是手机 145ms 的精确分解，
也不能把两轮不同探针/主机负载下的数字相减当作性能收益。该运行首次尝试因 GitHub 403
拉取公开 rime-prelude 失败，仅重试该作业后通过。
