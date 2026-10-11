# 去 fcitx5 直连 librime：2026-10-07 再评估

> **文档状态**：当前维护研究稿，2026-10-07 更新。
>
> 本文由工作区根目录的 `去fcitx5直连librime中间层_可行性调研报告.md` 搬入文档分支并重写。当前事实以代码分支 `fx-rime-only` 的 `d156052b25aae54f4219f799416c9475e1a19128` 为准；文档源码位于 `docs` 分支的 `docs/maintainer/`，不再使用已经删除的 `rime-docs` worktree。

## 结论先行

“去掉 fcitx5、由 Kotlin/JNI 直接驱动 librime”在工程上仍然可行，但它不是当前架构，也没有在本仓库被实现或测量验证。当前实际链路仍是：

```text
Kotlin FcitxAPI
  -> FcitxDispatcher 的 fcitx-main 单线程
  -> JNI native-lib.cpp
  -> Fcitx::Instance() / AndroidFrontend / InputContext
  -> fcitx5-rime 的 RimeState
  -> librime API（process_key、commit、context、candidate 等）
  -> Fcitx UI 事件回流
```

因此本次再评估的建议是：**先建立输入延迟、部署吞键、事件回流和资源占用基线，再做隔离的直连原型；不要因为“少一层中间层”就预先宣称性能收益。** 如果基线显示主要耗时在 librime 查询、部署或候选渲染，删除 fcitx5 可能只增加迁移风险，而不能解决主瓶颈。

## 1. 核对快照与范围

| 项目 | 当前值 |
| --- | --- |
| 代码 worktree | `fx-rime-only` |
| 代码分支 / HEAD | `fx-rime-only` / `d156052b25aae54f4219f799416c9475e1a19128` |
| 对照基线 | `3ad25fc9` |
| 文档 worktree | `docs` 分支 |
| 文档分支 / HEAD | `docs` / `6fe57146fbb7ec43f51f7f19e69d65d1e44023e9` |
| 当前实现中的 fcitx5-rime 指针 | `107502d`（README / 构建记录中的当前值） |
| 本分支相对基线提交数 | `git rev-list --count 3ad25fc9..HEAD` = `218` |

本报告回答的是“现在是否值得把链路改成直连”，不是要求立即重写。历史稿中的旧行号、旧 worktree 和旧提交快照都不能当成当前实现证据。

## 2. 当前实际输入链路

### 2.1 启动与 JNI 边界

`app/src/main/cpp/native-lib.cpp` 的 `Java_org_fcitx_fcitx5_android_core_Fcitx_startupFcitx(...)` 启动 `Fcitx::Instance()`，并设置 `FCITX_CONFIG_HOME`、`FCITX_DATA_HOME`、`FCITX_ADDON_DIRS`、`XDG_*` 等运行时目录。`FCITX_ADDON_DIRS` 指向 APK 的 native library 目录，使内置 addon 能被 Fcitx 找到。

同一文件的 `sendKeyToFcitxString`、`sendKeyToFcitxChar` 和 `sendKeySymToFcitx` 将 Kotlin 侧的键名、状态、扫描码和抬起标志转换成 `fcitx::Key`，再调用 `Fcitx::Instance().sendKey(...)`。这不是 Kotlin 直接调用 librime；Fcitx 仍负责接收和路由按键。

### 2.2 线程与生命周期

`FcitxDispatcher` 使用名为 `fcitx-main` 的单线程 executor。启动后先执行 native startup，再循环 `nativeLoopOnce()`，每轮排空调度队列；有新任务时通过 `nativeScheduleEmpty()` 唤醒阻塞的 native loop。停止时会停止接收任务、唤醒 native loop，并返回尚未执行的 Runnable。

`FcitxAPI` 的接口契约明确写着底层操作总是派发到 fcitx 线程。`FcitxInputMethodService.postFcitxJob` 又在服务侧用 jobs channel 串行等待前一个操作完成，并用 binding/session generation 检查丢弃过期任务。这些行为是直连方案必须保留的语义，不是可自动消失的“中间层开销”。

### 2.3 fcitx5-rime 到 librime

`plugin/rime/src/main/cpp/fcitx5-rime/src/rimestate.cpp` 的 `RimeState::keyEvent(KeyEvent &event)` 当前负责：

1. 处理 compose；
2. 检查 librime maintenance mode 和 session 是否可用；
3. 将 Fcitx 的 `KeyStates` 组装为 librime 所需的状态掩码，并加入 release 标志；
4. 调用 `api->process_key(session, ...)`；
5. 读取并提交 `RimeCommit`；
6. 调用 `updateUI(ic)` 回流候选、预编辑和状态。

部署期间或没有有效 session 时，适配层会主动 `filterAndAccept()` 吞掉按键。这是有意的输入正确性保护：如果按键被当作未处理事件交回 Android frontend，可能会以普通字符落进编辑器，产生部署期间的垃圾文本。

### 2.4 定制 Rime 能力

当前 librime 头文件已经包含 input tab disambiguation API：`get_input_tabs`、`get_candidate_code`、`select_tab` 及其释放函数。`fcitx5-rime` 不是只使用最小的 `process_key`：

- `rimecandidate.cpp` 调用 `get_input_tabs`，过滤空 label 和非法 UTF-8，再生成可滚动的音节 tab 操作；
- `rimestate.cpp` 的 `selectTab` 调用 `select_tab` 并刷新 UI；
- 候选操作还依赖候选编码信息、候选动作、分页和状态事件。

如果改为直连，必须决定这些定制 API 是通过现有 librime ABI 继续暴露、补充新的 JNI 适配，还是在 Kotlin 层重新实现。不能只把 `process_key` 接过去就视为功能等价。

## 3. 事件回流与输入正确性风险

native 侧通过 `Fcitx.handleFcitxEvent` 回调 Kotlin。`Fcitx.kt` 当前用：

```kotlin
MutableSharedFlow<FcitxEvent<*>>(
    extraBufferCapacity = 15,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
```

回调先运行同步 handlers，再 `eventFlow_.tryEmit(event)`；输入服务在 `onCreate` 中获取并收集这个 flow。`DROP_OLDEST` 可能是为了避免 native 回调被慢消费者阻塞，但它意味着不能未经验证地假设所有事件快照都会被每个消费者看到。候选、预编辑、状态和提交事件的丢弃语义需要通过压力测试确认，不能把它简单写成“已经解决”或“必然丢字”。

直连原型要明确：

- 是否沿用同一个有界事件流，还是按状态快照/提交事件拆分通道；
- UI 更新是否允许合并，提交事件是否必须可靠到达；
- Android 输入连接、焦点、包名/UID 绑定和 session generation 如何与 Rime session 对齐；
- 部署期间如何阻止按键落回编辑器；
- native 重启或 daemon disconnect 时如何取消过期任务并恢复输入状态。

## 4. 直连方案能覆盖什么

librime 的 C API 通常可以提供会话创建/销毁、按键处理、context、候选获取与选字、提交文本、状态/方案查询和部署相关操作。现有 `rime_api.h` 也已暴露 input tab、候选 code 和 tab selection 的函数指针。

这说明直连不是 API 不够，而是集成边界需要重新实现。直连方案仍要补齐或重新确认：

| 领域 | 当前由 Fcitx / fcitx5-rime 提供的语义 | 直连必须验证的替代物 |
| --- | --- | --- |
| 按键 | Fcitx KeySym、modifier mask、release 标志、compose | Android `KeyEvent` 到 keysym/mask 的映射及组合键行为 |
| 生命周期 | AndroidFrontend、InputContext、focus/activate/deactivate | 每个编辑器和 UID 的 session、焦点切换、重绑与释放 |
| UI | FlushUIEvent、候选/预编辑/状态事件模型 | Kotlin/JNI 数据模型、合并策略、主线程刷新和提交可靠性 |
| 部署 | maintenance mode 检查、吞键、部署提示 | deploy 状态机、按键门控、失败恢复和用户提示 |
| 配置 | addon、RawConfig、schema/option 及 Fcitx 保存流程 | 直接调用 librime 配置的持久化、热重载与错误显示 |
| 定制能力 | tabs、candidate code、候选动作、分页、alt-trigger 等适配 | ABI 暴露、JNI 批量接口、兼容旧布局/设置和回归测试 |
| 进程/线程 | `fcitx-main` 单线程 loop 与任务队列 | librime 非线程安全边界、队列唤醒、退出时未执行任务处理 |

## 5. 成本、收益与不可直接推导的部分

### 5.1 可能的收益

理论上可以减少 Fcitx 事件路由、InputContext 和部分 JNI/对象转换开销，也可能让 Kotlin 直接得到更适合 UI 的批量状态。但这些只是候选收益；当前没有 Perfetto trace、端到端 p50/p95/p99、CPU、内存或事件丢弃计数来证明它们存在且足以抵偿迁移成本。

### 5.2 主要成本

迁移不是把一处 `process_key` 调用替换成 JNI：

- 要重做焦点、session、InputConnection、compose、候选动作和提交生命周期；
- 要重做 Fcitx 当前对 deployment、无 session 和 key release 的输入门控；
- 要为 tabs、candidate code 和现有定制行为设计 ABI 稳定的批量接口；
- 要重新验证 RawConfig、schema 选择、用户数据部署、备份/恢复和 daemon 重启；
- 要同时维护现有 Fcitx 路径和直连路径一段时间，才能做公平 A/B；
- 要覆盖真机低端设备和不同 Android 输入连接的输入正确性，而不只是单元测试能编译。

### 5.3 当前不能下的结论

以下结论目前都没有证据支持，应避免写进发布说明或架构决策：

- “去掉 Fcitx5 一定能降低输入延迟”；
- “JNI 次数是当前主要瓶颈”；
- “`DROP_OLDEST` 已经造成用户可见的丢字”；
- “直连可以无损复用现有所有 tabs、RawConfig 和部署行为”；
- “删除 fcitx5 后可以同步删除所有事件、焦点和生命周期代码”。

## 6. 建议的门控路线

### 阶段 A：先测当前基线

用 Perfetto 或等价 trace 记录至少以下时间点：

- Android key event 到 `FcitxAPI.sendKey`；
- JNI 进入 native 到 `Fcitx::Instance().sendKey`；
- `RimeState::keyEvent` / `process_key` 的耗时；
- commit/UI event 回调到 Kotlin；
- UI 收到候选/预编辑到实际渲染；
- 冷启动、切换 session、部署期间按键、长候选渲染的 p50/p95/p99；
- CPU、RSS/Java heap、native heap 和 event flow 的丢弃/积压计数。

没有这组基线，就无法判断收益来自减少路由、减少 JNI、还是根本不在此处。

### 阶段 B：隔离直连原型

保留现有 Fcitx 路径，新增受构建开关控制的直连通道，优先只覆盖一个可回滚的实验场景：创建一个 session、输入普通按键、读取 context、选候选、提交文本。JNI 接口应优先批量返回 context/commit/UI 状态，避免用许多细粒度调用放大新的边界成本。

原型必须保留显式的线程 owner 和 session generation；不要让 librime 调用从任意 Android coroutine 直接并发进入。直连路径出现错误时，应能切回当前 Fcitx 路径并保留用户数据。

### 阶段 C：做行为等价与性能 A/B

至少对比：普通输入、Shift/Control、key release、compose、候选分页/选字、schema 切换、input tabs、candidate code、部署吞键、焦点切换、多个编辑器、daemon 重启和断开重连。只有在行为等价后，才比较端到端延迟、CPU、内存和事件压力。

### 阶段 D：再决定是否迁移

若直连只减少很小的中间层耗时，却引入更高的事件/生命周期错误率，应保持 Fcitx 架构并优化测出的热点。只有当基线确认 Fcitx 路由是显著瓶颈，且原型能通过行为与资源门槛，才进入分阶段替换。

## 7. 本次核对记录

已核对的当前源码证据包括：

- `app/src/main/cpp/native-lib.cpp:510-565,756-781`：Fcitx 启动、运行目录和 `sendKey` JNI 入口；
- `app/src/main/java/org/fcitx/fcitx5/android/core/Fcitx.kt:233-237,401-419,447-469`：有界事件流、native 回调和 Fcitx 启动；
- `app/src/main/java/org/fcitx/fcitx5/android/core/FcitxDispatcher.kt:23-140`：`fcitx-main` 单线程 loop、队列和唤醒；
- `app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:480-597`：串行 jobs、generation 检查和事件收集；
- `plugin/rime/src/main/cpp/fcitx5-rime/src/rimestate.cpp:164-257`：compose、maintenance/session 门控、`process_key`、commit 和 UI 更新；
- `plugin/rime/src/main/cpp/fcitx5-rime/src/rimecandidate.cpp:185-252` 与 `rimestate.cpp:631-649`：input tabs 的读取、UTF-8 过滤和选择；
- `lib/fcitx5/src/main/cpp/prebuilt/librime/arm64-v8a/include/rime_api.h:525-564`：tabs、candidate code 和 tab selection ABI。

本次核对没有发现 `rime-main`、`RimeProcessKey` 的现有 Kotlin/JNI 直连实现，也没有运行 Perfetto A/B，因此“直连性能收益”仍是待验证假设。当前代码和 README 的引擎来源/补丁说明仍是本报告的事实参考。

## 8. 维护结论

当前应把这项工作标记为“可研究、未迁移、收益未测量”，而不是“已完成重构”或“已证明更快”。下一次推进的最小可交付物应是一份可复现的现有链路 trace 和一个能回滚的单 session 直连原型；在此之前不建议删除 Fcitx5、重写输入生命周期或改变用户数据格式。
