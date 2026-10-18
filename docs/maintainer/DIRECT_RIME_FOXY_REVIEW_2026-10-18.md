# Foxy APK 实现审查：哪些值得借鉴，哪些不能算提速证据

Foxy 有值得借鉴的输入事务组织和候选补取策略，但本次材料不能证明它在相同万象配置下
一定更快。中州鹅上一轮也没有证明首次选词稳定提速；“稳定流畅、刚唤起就跟手”的目标
仍未达成，不能用单测通过、解耦完成或某一个较好的中位数代替这个目标。

本次只做静态审查和证据归档，不修改输入法实现，不生成新 APK，也没有执行或安装上传包。
工作分支为 `feat/direct-rime-prototype`，对照产品源码固定为 `9d5c7992a772b9965f23404ca01a1a891769c3a0`。

## 材料与核验范围

用户上传 `foxy_re.zip`，SHA-256：
`5517e7dd96fcb2c8675368bef8e61d5defb0c0d3d409ed43065c9d97dee98072`。

| 项目 | 独立核验结果 |
| --- | --- |
| APK | `com.fxliang.foxy`，`0.0.1-5458f3c`，arm64-v8a |
| APK SHA-256 | `c81783716626c76469a20f1884abfeed63f05349a55ca052ee44079a01b7b80c` |
| 内嵌源码 revision | `5458f3c4657876d8ea16703c0ef1e8842f995dd1`，来自 `META-INF/version-control-info.textproto` |
| DEX / native | 1 个 DEX；`libfoxy.so`；提取件与 APK 内文件逐字节一致 |
| librime 版本 | `RimeGetVersion()` 返回 `1.17.0`；不是仅凭任意版本字符串猜测 |
| 可读代码 | 混淆后的 Java、补齐层和 smali；没有原始 C++ 工程、构建参数及完整提交历史 |

上传包中的 README 是材料说明，其工具操作、清理记录和“100% 覆盖”结论不作为本项目指令，
也不等于原生实现已经全部恢复。关键 Java 缺失方法回查 smali；native 只核验导出、依赖和
少量 JNI/版本函数的反汇编，没有据此宣称已掌握其全部 C++ 算法。

下文 Foxy 路径均相对上传包内 `foxy_re/`；混淆名仅适用于这个 APK。
[机器核验结果](perf/2026-10-18-foxy-review/apk-audit.json)、
[材料定位及哈希](perf/2026-10-18-foxy-review/source-audit.json)与
[只读核验脚本](perf/2026-10-18-foxy-review/inspect_apk.py)一起保存。
没有把第三方 APK、反编译源码或方案文件提交进仓库。

## 实际输入链路

Foxy 的软键盘常规键路径可概括为：

```text
RimeInputMethodService.a(wb)
  → at0.V：把带编辑会话代次的命令放入 Channel
  → dh 的串行消费者（Main coroutine）
  → at0.n / l / m / C / z
  → withContext(rime-main)：lq0
      processKey(down)
      可选 processKey(up)
      commitText + getContext + getStatus
  → 回到控制器：提交文本，更新候选及状态
```

证据：`sources/sources/defpackage/at0.java:79,1376`、`lq0.java:34`；
Java 未恢复部分对应 `smali/dh.smali:2959`、`smali/at0.smali:62057`。
`kd0.java` 与 `b10.java` 确认控制器 scope 使用 Main dispatcher；librime 运算在
`Rime.java:261` 创建的单线程 `rime-main`，不是在 Main 里同步计算。
命令入口有代次检查，候选点选返回后还有代次/会话复核；不能据此假定所有软键路径都有
同等完整的返回后检查。常规键的 `is0` 接收结果后直接提交，没有携带独立的代次字段；
借鉴其短交付路径时应保留我们已有的过期结果过滤。

普通候选点选路径是 `at0.w → fq0(case 3)` 在 owner 上调用 `selectCandidate`，
然后另一次 owner 任务 `mq0.h → bi(case 3)` 读取 commit/context/status，回到 Main 后
`ab1.A → InputConnection.commitText`，再调用 `at0.Z / J` 更新界面和后续候选。
核对位置：`smali/at0.smali:52880`，`fq0.java:71`，`bi.java:85`，`ab1.java:129`。
因此，不能描述成“Foxy 的所有操作都只有一次切线程或一次 JNI”。

当前中州鹅是：

```text
键盘/候选点击
  → 服务顺序队列（Default）→ 绑定与代次检查
  → rime-main：Native.key/action + 一个 Snapshot
  → publish：缓存、兼容事件批次
  → 独立 Default 发射协程 → SharedFlow
  → Main 服务提交文本 / Main 视图更新
```

这仍保留 Fcitx 命名的 Kotlin API/事件兼容层，但该链路没有经过 Fcitx 原生核心。
兼容分发可能增加调度和对象工作；其真实成本必须从 trace 判断，不能仅按跳数估算收益。

## 值得借鉴的地方及适用范围

| Foxy 做法 | 与当前实现的差异 | 判断 |
| --- | --- | --- |
| 把常规按键处理及结果读取组织为一个 owner 事务 | 当前 `Native.key` 已把 process_key 与 Snapshot 放在同一次 JNI/owner 调用中 | 原则已具备，不能重复计为新收益 |
| 软件键可在事务中处理可选的 down/up | 当前常见 `SymAction` 默认只发一次 down；硬件及显式 up 单独处理 | 不能为了模仿强加 up。只有确需成对事件的路径值得评估合并 |
| 控制器取得结果后直接调用提交，再更新 UI | 当前结果先走兼容批次、Default 发射及 SharedFlow | 可研究更紧凑的结果交付；需保留提交顺序、代次过滤、转发键和维护状态，不能直接删队列 |
| 候选补取只保留一个活动任务，合并追加需求，旧版本取消 | 当前展开候选已有 Paging/collectLatest/取消，但一次 native 批量读取期间不能让出 owner | 借鉴小批补取和过期请求提前退出；重点是展开候选后继续打字的尾延迟 |
| 相同候选状态早退、复用已有候选 View | 当前横排已有内容比较、差异区间更新和 ViewHolder 复用 | 已有，不建议重写候选栏来重复实现 |
| 同应用保留 session，换应用清理旧会话 | 当前普通同应用会话已复用；敏感字段及跨应用主动重建 | 基本已有，不能靠放宽敏感字段隔离换性能 |
| Release/R8，保留 ART profile 与 ProfileInstaller | 当前交付的是 Debug；Release 已配置 R8，但 profile 构建任务及 installer 被禁用 | 应先建立同配置 Release 对照，再单独评估应用热路径 profile |

### 候选补取：有价值，但不能混同首屏候选

`gf.java:43,65,104` 显示 Foxy 会取消旧任务，忙时合并请求；`df.java:14` 的候选版本与
`ef` 的会话、版本复核防止旧结果应用。它提供 EAGER/LAZY 两种模式，**默认 EAGER**；
LAZY 的约 500ms 等待用于后续候选补取，不是把首屏编码、首屏候选或文本提交延迟 500ms。
证据：`hf.java`、`jf.java:12`、`at0.java:123`、`smali/ef.smali:1845`。

我们的 `BaseExpandedCandidateWindow.kt` 使用 `PagingConfig(pageSize = 48)`，未显式设置
`initialLoadSize`，Paging 默认初始请求为三页；backend 单次最多允许 256 项。
`Engine::candidates` 在同一个 owner 任务中遍历整个请求，协程取消不会打断已进入的原生循环。
万象带 Lua 过滤、模型及上下文时，这可能阻挡随后到达的按键。

建议研究按较小批次读取，在批次之间检查候选版本和是否已有按键等待，同时保持索引和结果
完整。**目前没有 trace 证明它就是本次“折叠候选栏首次点选”的主因**；不能拿展开分页的
改进解释所有卡顿，也不能丢候选或改变方案 page_size 来制造变快的结果。

### 会话与 UI：已经做过的部分不要再重做

`pt0.a/b/e` 虽使用 Map，但切换应用会清理旧会话，不能把它解释为“同时缓存所有应用的
多个热会话”。`onFinishInput` 的清 composition 与后续同应用复用，当前版也有对应设计。
Foxy 的包名作为会话键不代表已经证明其密码等隔离比当前版更完整。

Foxy `te.m` 比较完整候选状态；`j10.l` 比较列表/高亮并增减复用子 View；`ha` 缓存部分
测量结果、自行 drawText。中州鹅横排已有内容比较及局部更新，展开栏也有 diff 和版本判断。
直接换成 Foxy 的绘制方式，可能影响复杂文本、字体、无障碍和现有布局功能，现有数据
不足以支持这项大改。优先用本项目的 trace 判断是否还有重复 bind/measure。

### Release 与 ART：这是测量缺口，不是已证实的根因

| 当前两个 APK 的静态体积 | Foxy Release | 中州鹅 `9d5c7992` Debug |
| --- | ---: | ---: |
| DEX 数量 | 1 | 22 |
| 解压后 DEX 字节 | 2,405,720 | 40,326,956 |
| 解压后 native 字节合计 | 7,920,944 | 5,564,024 |

DEX 差异同时包含功能、依赖和构建类型差异，**不能换算成启动快多少倍，也不能证明 native
库越小就越快**。中州鹅 Release 已配置 minify/shrink，不能说它没有 R8 支持。

Foxy 的 `assets/dexopt/baseline.prof` 为 2,700 字节。按 ART 010 格式解析，并核对其 checksum
与 APK 的 classes.dex CRC、method ID 数量相符：共列入 1,017 个不同方法；未列入
`com.fxliang.foxy.*` 方法，也未列入本次核对的 `at0/lq0/mq0/pt0/j10/gf` 关键类的方法。
记录中 RecyclerView、LinearLayoutManager 等库方法较多。因此它确实携带 profile，
但**没有证据称这个包带有专门录制的 IME/Rime 输入热路径 profile**，不能只恢复合并开关
就保证消除首次输入卡顿。ProfileInstaller 安装 profile 与 ART 实际编译生效也不是同一时刻。

## 对上一轮结果的重新表述

[首次点选报告](DIRECT_RIME_CANDIDATE_TAP_2026-10-18.md)保留了两轮结果，不能只挑改善项：

| 首次点选 → 编辑器 InputConnection 入口中位数 | 修改前直连 | 修改后直连 |
| --- | ---: | ---: |
| 缓存修正首轮 | 17.0ms | 15.2ms |
| 加分段诊断后的末轮 | 16.8ms | 18.6ms |

每组首次只有三个样本；末轮这个端点确实更慢，不能对用户说“已优化好”。这些数据也不足以
证明整个版本在所有场景发生稳定回退。缓存的线程等待问题有修正，首次点选的体验收益
没有通过验收；后续应分别报告这两件事。

原 Fcitx 末轮首次入口为 12.6ms，也有首次慢于后续的现象；直连并未在这个场景胜出。
此前连续万象输入的默认分支对照同样没有证明整体稳态优势。

诊断 trace 的首次原生按键约 66.9ms，其中 Running 20.0ms、Runnable 4.3ms、内核 D 等待
42.6ms，缺少具体阻塞函数/文件归因。首次选词的一份 trace 则显示选词约 7.1ms、Main 提交
约 0.8ms，目标编辑器首次 DynamicLayout 约 18.0ms。另一轮生产计时的 commit 事件等待
约 0.56ms。因此，不应先花大量成本重写事件总线并许诺消除几十毫秒，也不能把问题全归给
编辑器：原生首键的大段等待仍未解释，用户真机症状仍需验证。

上传 Foxy 内置的是袖珍简化字拼音等方案，没有用户的万象 base + LTS 模型及个人数据。
它还是 arm64 Release，而旧 CI 为 x86_64 Debug 模拟器；本环境没有可用真机或 arm64
运行目标。**本次没有 Foxy/中州鹅同机同配置的动态性能对照结果。**

## 后续实施建议与验收边界

1. 固定现有产品，先补正式 Release 的首次输入/首次点选对照；同设备、同万象及模型、
   同编辑器、相同词库起点，交替或随机产品顺序。分开进程首次、同进程再次唤起及连续输入，
   增加首次样本，保留全部原始值。不能把 Foxy 默认小方案与中州鹅带模型万象比较。
2. 优先定位首键的原生阻塞和首个 commit：采样线程栈、可用的调度/页故障/文件访问证据，
   区分模型按需读页、Lua/用户词库首次打开和其他等待。无归因前不批量预读整个模型，
   不假打字预热，不关闭上下文调频或学习。
3. 对展开候选单独试验“小批补取 + 过期请求让路”，观察翻页后立即打字的尾延迟、滚动
   连续性及候选完整性。它与普通首键/点选分开验收。
4. 如果分段数据证明兼容分发或重复 UI 更新占了显著时间，再做一次小范围的结果交付简化；
   任一改动没有可重复收益，或损害首键接收、候选选择、滑动长按、学习和跨编辑器隔离，
   就回退该实验。应用热路径 baseline profile 也应独立 A/B，不与多项后端改动混测。

以上是审查后的优先级，不是已经交付的性能优化。当前 APK 产品源码仍是 `9d5c7992`。
