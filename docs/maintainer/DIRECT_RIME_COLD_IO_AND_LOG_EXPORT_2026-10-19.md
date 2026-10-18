# 新增真机日志：首次选词等待与连续保存日志

## 证据与边界

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
只在这个一次性测试 fixture 加入 `rime.lua` 包装 UserDb 的 open/fetch/update/query，
保留原返回值与学习行为，记录数据库类别、方法、墙钟时间，不记录数据库键、词或用户路径。
该脚本不进产品 APK、不改用户方案；修改后的 fixture 另存哈希。
它用于查调用路径，不能作为 ARM64 手机的性能数字或修改前后收益。

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

2. **交互线程调度。** 绑定、按键、选词/动作的同步 owner 工作期间，将 Linux nice
   从默认 0 临时提高到 Android `THREAD_PRIORITY_DISPLAY`（-4；已有更高优先级则不降低），
   finally 恢复；部署、同步和候选扫描不提高。厂商拒绝调整时回退正常处理。
   这针对调度竞争；不能消除文件等待，也未证明能消除真机 145ms 尖峰。

3. **首键需求保持。** 同代次曾被真实按键提升为需要 Rime 后，后续 metadata 变更必须继续
   把合并后的需求传给 key gate，避免原始 `needsRime=false` 将它降回非输入状态。
   增加改变 program metadata 的设备回归。这不是这两份日志慢选词的已证根因。

## 验证记录

本地 488 项 JVM 测试通过；ARM64 Debug 主 APK、native sanitizer 契约与 IO 包装器测试通过。
日志回归覆盖并发追加/清空/过滤时的快照、连续完整写入、输出失败/关闭/重试、缺失源与 null 输出、
待选文件恢复、重复触发、真实 LogActivity/ViewModel 工厂与页面重建。
设备回归、Release 下载和定点诊断结果完成后更新本节，未完成的检查不标通过。
