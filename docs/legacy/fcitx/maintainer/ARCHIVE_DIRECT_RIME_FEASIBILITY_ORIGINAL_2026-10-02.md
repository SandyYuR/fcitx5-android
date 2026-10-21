# 【历史归档】去掉 fcitx5 底层、自建 librime 直连中间层的可行性调研报告

::: info 旧版 Fcitx 文档
这是直连版合入前的原站文档，固定于 `edcf536f`，仅对应当时的 Fcitx 版本。
[旧版完整目录](/legacy/fcitx/contents) · [旧版维护报告](/legacy/fcitx/maintainer/) · [当前中州鹅手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [原始文档](https://github.com/SandyYuR/fcitx5-android/blob/edcf536f85576ce3e2b241e1eb5059534a972f51/docs/maintainer/ARCHIVE_DIRECT_RIME_FEASIBILITY_ORIGINAL_2026-10-02.md)
:::


> **归档说明**：本文为 2026-10-02 原文逐字归档（原文件 `去fcitx5直连librime中间层_可行性调研报告.md`，原存于工作区外）。2026-10-11 已据此搬入文档分支并改写为《[直连 librime 再评估（2026-10-07）](/legacy/fcitx/maintainer/DIRECT_RIME_REEVALUATION_2026-10-07)》；**重写版是当前结论，本文仅作历史留存**，行号、提交快照与工作区路径均为 2026-10-02 原样，不要当成当前实现证据。

> **【2026-10-16 整理注记】** 原归档于 `docs/archive/`，现随归档目录整体并入 `docs/maintainer/`（文件名加 `ARCHIVE_` 前缀），内容未动。

> 对象项目：`fx-rime-only`（靓企鹅·中州韵，基于 fcitx5-android fx 分支）
> 参考实现：`com.fxliang.foxy`（反编译工作区 `D:\GitHub\foxy\foxy_re`）
> 日期：2026-10-02 ｜ 结论：**工程上可行，成本中等；但"提升性能"应作为次要收益，不要当成主要理由。**

---

## 0. 一句话结论

去掉 fcitx5、让 Kotlin UI 直接通过 JNI 驱动 librime C API，是**可行**的——而且**同一作者 fxliang 已经做过一个这样的输入法（Foxy），可直接作为架构范本**。自定义键盘布局、主题、符号面板等 UI 可以**几乎无损保留**，因为它们本就只消费"候选/编码/上屏"这几股数据流，不关心引擎是谁。

但有两点必须说清楚：

1. **性能收益是"确定但有限"的**。真正的输入延迟大头在 librime 的词典查询与 Android 侧候选渲染，这两块去掉 fcitx5 都不会变。fcitx5 省掉的是"事件循环轮询 + 调用链固定开销 + 一次异步 UI 回弹延迟 + 冷启动加载一堆 addon"。收益更多体现在**冷启动、内存占用、架构可控性**上，而非单次按键的毫秒数。把它当"提升打字速度"的银弹会失望。
2. **会丢掉本项目独有的音节 tab 消歧能力**，除非在新中间层里继续桥接定制 librime 的 `get_input_tabs`/`select_tab`。Foxy 恰恰没有这个功能（见第 2 节实证）。

---

## 1. 当前架构：fcitx5 在链路里到底做了什么

当前 `fx-rime-only` 的输入链路是三层嵌套：

```
Kotlin UI（键盘/候选栏/设置）
   │  ~50 个 JNI 函数（native-lib.cpp）
   ▼
fcitx5 core（Instance + EventLoop/libuv + InputContext + AddonManager）
   │  androidfrontend（把 Android 伪装成一个 fcitx5 前端）
   ▼
fcitx5-rime addon（rimeengine / rimestate / rimesession / rimecandidate / rimeaction）
   │  ~40 个 librime C API 调用
   ▼
librime（静态链接 librime.a，真正的输入引擎）
```

### fcitx5 在"每一次按键"热路径上插入的抽象（实测调用链）

> 来源：`androidfrontend.cpp`、`rimeengine.cpp:847`、`rimestate.cpp:164/405`

1. Kotlin → JNI `sendKey` → `AndroidFrontend::keyEvent`（经 addon 导出函数指针间接层）
2. 构造 `fcitx::KeyEvent` → `activeIC_->keyEvent()`（`AndroidInputContext : InputContextV2`）
3. InputContext → Instance → 引擎虚派发 → `RimeEngine::keyEvent`（拦截 deploy/sync 热键）
4. 取每个 InputContext 挂的 `RimeState` → `RimeState::keyEvent`：fcitx KeyStates → IBus 掩码转换 → `api->process_key`
5. `get_commit` → 上屏回调；`updateUI` → `get_context` → 构造 `RimeCandidateList` → `updateUserInterface(InputPanel)`
6. **关键**：UI 更新不是函数返回值，而是触发 `InputContextFlushUIEvent`，被 `AndroidFrontend` 构造时注册的 `watchEvent` 回调异步捕获，再经各类 `*Callback` 走 JNI 回弹到 Kotlin。

fcitx5 为这条链路强加的东西：`Key/KeyStates/KeyEvent` + compose 组合键、`InputContext(V2)/InputContextManager/FocusGroup/InputContextCache(LRU)`、addon 导出间接层、`InputMethodEngine` 虚派发、`InputContextProperty`（把 RimeState 挂到每个 IC）、`watchEvent/FlushUIEvent` 事件机制、`InputPanel + CandidateList` 的 5 个子接口、`Text`（富文本）+ outputFilter、`CapabilityFlags`、`StatusArea/Action/Menu`、`EventDispatcher`（跨线程）、以及由 **JVM 用 `loopOnce()` 驱动的 libuv 事件循环**。

### fcitx5 实际提供的"增值"能力（去掉后要自己补）

- 候选分页（bulk + paged 双模式）、候选动作菜单（忘记词汇）、方案选单 + 状态栏 action
- `deleteSurrounding` / `repositionCursor`（光标前后文删除、重定位）
- 焦点管理 + 按程序共享/隔离 session + 能力标志（密码框等）
- compose 组合键、输入法切换行为、通知节流、部署期 overlay 提示与吞键
- 配置系统：GlobalConfig / AddonConfig / InputMethodConfig 的 **desc + cfg** 描述机制（整个设置 UI 的基础）

---

## 2. 关键实证：Foxy 就是"无 fcitx5 直连 librime"的现成范本

> 来源：`D:\GitHub\foxy\foxy_re\sources\java\com\fxliang\foxy\Rime.java`（712 行）、`_analysis\_jni_exports.txt`（41 个 JNI 导出）

Foxy（`com.fxliang.foxy`，与本项目同一上游作者 fxliang）本质被其 README 一句话定性为"**librime 中文输入法前端 + libgit2 方案包管理**"。它的 JNI 导出只有 **41 个**，其中 **32 个全是 Rime**（另外 9 个是 LibGit2 方案包管理），**没有一行 fcitx5**。

Foxy 的 `Rime` 单例直接把 librime C API 一一映射成 native 方法，并用**一个专用单线程 executor（线程名 `rime-main`）** 串行化所有引擎调用：

```java
public final class Rime {
    static { System.loadLibrary("foxy"); }

    // —— 直接对应 librime C API 的 native 方法 ——
    public final native long    createSession();
    public final native boolean processKey(long session, int keycode, int mask);  // ← 直接 RimeProcessKey
    public final native Object[] getContext(long session);                         // ← 候选/编码
    public final native String  commitText(long session);                          // ← 上屏
    public final native boolean selectCandidateOnCurrentPage(long session, int i);
    public final native boolean changePage(long session, boolean backward);
    public final native boolean deleteCandidate(long session, int i, boolean z);
    public final native Object[] getStatus(long session);
    public final native Object[] getSchemaList();
    public final native boolean selectSchema(long session, String id);
    public final native void    setOption(long session, String opt, boolean on);
    public final native boolean startMaintenance(boolean full);                    // ← 部署
    public final native boolean syncUserData();
    public final native void    setNotificationCallback(Object cb);
    // ……共 32 个

    // 所有调用都 submit 到 rime-main 单线程，避免 librime 非线程安全问题
    private <T> T runOnRime(ex thunk) {
        return getRimeThread().submit(() -> thunk.a()).get();
    }
}
```

`getContext()` 返回一个 `Object[]`，由 Kotlin 侧 `parseContext()` 解析成候选对象（含 `page_size/page_no/is_last_page/highlighted_index/num_candidates/select_keys` 等字段，正好对应 `RimeMenu`）。`getStatus()` 同理解析出 `ascii_mode/schema_id/composing` 等。

**这证明了什么**：

- 一个完整可用的 Rime Android 输入法，**只需约 32 个 librime 直连调用 + 一个单线程调度器**就能跑起来，比本项目 ~50 个 JNI（其中大半是 fcitx5 配置/addon/InputMethodManager/StatusArea 管理）更精简。
- 分页、选字键、方案列表、状态、部署、通知——**librime 原生 API 全都有**，Foxy 没有为它们重写任何引擎逻辑。
- Foxy 的 32 个 JNI 里**没有任何 `*Tab*` / `candidateCode` 符号**，印证了"音节 tab 消歧"是本项目基于定制 librime 补丁的独有能力，不是 librime 标配（见第 6 节风险）。

---

## 3. librime C API 能否撑起完整闭环？能。

> 来源：`librime-src/src/rime_api.h`、`rime_api_deprecated.h`、`rime_levers_api.h`、`rime/key_table.h`

| 环节 | librime 原生 API | 是否自带 |
|------|------------------|----------|
| 会话 | `create_session` / `find_session` / `destroy_session` | ✅ |
| 按键 | `process_key(session, keycode, mask)` | ✅（需自己转 keysym） |
| 候选/编码 | `get_context` → `RimeContext{composition, menu, commit_text_preview, select_labels}` | ✅ |
| **分页** | `RimeMenu{page_size, page_no, is_last_page, num_candidates, select_keys}` + `change_page` | ✅ **自带，无需自写分页** |
| **选字键** | `RimeMenu.select_keys` / `RimeContext.select_labels` + `select_candidate_on_current_page` | ✅ **自带** |
| 上屏 | `get_commit` / `commit_composition` | ✅ |
| 状态 | `get_status` → `RimeStatus{schema_id, is_ascii_mode, is_composing...}` | ✅ |
| 删词 | `delete_candidate(_on_current_page)` | ✅ |
| 高亮 | `highlight_candidate(_on_current_page)` | ✅ |
| 方案 | `get_schema_list` / `select_schema` / `get_current_schema` | ✅ |
| **方案选单数据** | levers API 的 `get_available/selected_schema_list` + `select_schemas` | ✅ 自带 |
| 配置读写 | `config_open` / `config_get/set_*` / `config_begin_map/list` 遍历 / `user_config_open` | ✅ 全套 |
| 部署 | `start_maintenance` / `deploy` / `deploy_schema` / `sync_user_data` | ✅ |
| 部署进度 | 通知回调（type="deploy", value="start/success/failure"） | ✅ |
| 通知 | `set_notification_handler`（schema/option/deploy 三类消息） | ✅ |

**结论**：按键→候选→上屏→翻页→选字→删词→方案切换→配置读写→部署，整条闭环 librime C API 都能完成。分页和选字键这两个最容易被误以为"要自己写"的东西，librime 本就提供。

### 按键编码：唯一必须自建的引擎侧转换

librime 的 `process_key(keycode, mask)` 要求 **X11 keysym + X11/ibus 修饰键掩码**（`key_table.h` 直接 `#include <X11/keysym.h>`，mask 用 `RimeModifier` 枚举：`kShiftMask/kControlMask/kAltMask/kReleaseMask`）。因此中间层必须实现一张 `Android KeyEvent → X11 keysym` 的映射：

- 字母数字可直接算；功能键查表（DEL→BackSpace、ENTER→Return、方向键、Space、符号键）
- 可见字符走 Unicode 码点（librime 接受 Unicode 作 keysym），可绕开大量符号映射
- 从 metaState 合成 Shift/Ctrl/Alt 掩码；大小写靠 keysym 本身区分（'A' vs 'a'）

复杂度中等、有界，几百行可覆盖——这是所有 IME 前端的标准工作，当前 `rimestate.cpp:208-219` 做的就是这件事（只是起点是 fcitx::Key 而非 Android KeyEvent）。

---

## 4. UI 能不能原样保留？能，而且这是最省心的部分

用户关心的"自定义键盘布局、主题、符号/表情面板、候选栏、设置页"——这些**几乎全部位于纯 Kotlin 层，与 fcitx5 引擎解耦**。它们的输入只有三股数据流：

1. **候选列表**（文本 + 注释 + 高亮 + 分页信息）
2. **preedit / 编码串**（带光标与高亮段）
3. **上屏文本**（commit）

外加几个控制信号（方案列表、ascii/中英状态、部署进度）。这些数据流在新架构里改为"从 librime `get_context`/`get_commit`/`get_status` 直接拿"，**UI 组件完全不用动**。

**需要适配的 UI 相关薄层**（都不碰视图代码本身）：

- 候选栏数据源：从"fcitx5 CandidateEntity 回调"改为"解析 `RimeMenu`"——Foxy 的 `parseContext()` 就是现成写法。
- 设置页：当前设置 UI 深度依赖 **fcitx5 的 RawConfig desc 描述机制**（`dumpDescription`）。Rime 自身的方案配置是 YAML，没有这套"自动生成设置表单"的描述。**这是 UI 层改动最大的一处**——引擎相关的设置（全局配置、addon 开关、InputMethod 配置）会随 fcitx5 一起消失，需要改为直接读写 Rime YAML（`user_config_open` / `config_*`）或自定义设置模型。好在本项目大量设置本就是"布局/主题/符号/候选栏"等**应用自有设置**，这些与 fcitx5 无关，不受影响。

---

## 5. 性能分析：收益来自哪里，不来自哪里

### 去掉 fcitx5 **确实能省**的开销

- **冷启动**：不再构造 `fcitx::Instance`、`AddonManager` 动态发现/加载一批 addon、注册 loader、初始化事件循环。Foxy 的启动路径明显更短。
- **内存与包体**：fcitx5 core + androidfrontend + 一系列 addon 的常驻对象与代码段被整体移除。
- **每次按键的固定调用链开销**：少掉 addon 导出函数指针间接层、InputContext 查找、引擎虚派发、`InputContextProperty` 挂载、`Key/Text` 对象分配。
- **一次异步 UI 回弹延迟**：当前 UI 更新走 `FlushUIEvent → watchEvent → callback` 的事件驱动异步回弹，可能跨一个调度/vsync 边界。直连后可在 `process_key` 返回后立即 `get_context` 同步取数据。
- **事件循环轮询**：当前 JVM 需用 `loopOnce()` 驱动 libuv 事件循环，直连后这套机制整体不需要。

### 去掉 fcitx5 **省不掉**的开销（输入延迟真正的大头）

- **librime `process_key` 本身**：词典查询、翻译、过滤——无论前端是谁，这部分一模一样。
- **JNI 跨界**：Kotlin ↔ native 的调用成本不变（甚至因为要多传数组而相近）。
- **Android 候选栏渲染**：candidate 的 measure/layout/绘制是 UI 线程大头。本项目 README"六、稳定性与性能"里"候选栏改前后缀 diff 增量刷新"优化的正是这块——**它与 fcitx5 无关**。

### 诚实判断

> 去 fcitx5 对**单次按键延迟**的改善是"确定但量级有限"的（削减固定开销 + 一次异步回弹），**最可感知的收益在冷启动、内存和架构可控性**。若目标纯粹是"打字更快"，应先用项目里现成的"输入法性能对比报告与测试工具包"实测当前瓶颈分布，再决定——有把性能收益高估的风险。

---

## 6. 迁移工作量评估

> 来源：对 fcitx5-rime 适配层（实读约 4400 行）的逐文件拆解

| 性质 | 占比 | 说明 |
|------|------|------|
| 纯 fcitx5↔librime 胶水 | ~30%（1200–1500 行） | addon 导出/声明、callback 转发、Key/Text 适配、候选接口桥接、session、DTO。**去 fcitx5 后大部分消失或缩小。** |
| 真正业务逻辑（须移植） | ~50%（2000–2300 行） | 数据目录多 profile 管理、levers 多选器、方案菜单/action、通知/deploy overlay、preedit 构造、候选 select/delete/snapshot、分页双模式、tabs。**本质都是对 librime API 的薄封装，可直接移植。** |
| 可直接删除 | ~20%（300–400 行） | `InputContextCache`(LRU)、`FocusGroup`/多 IC、xkb CapsLock、DBus service、桌面 ExternalOption——**Android 单场景不需要。** |

**真正要"白写"的基础设施**（而这恰是 fcitx5 最重的部分，去掉后反而更简单）：

1. **Key → Rime keysym/mask 翻译层**（见第 3 节）：几百行静态映射 + 修饰键合成。
2. **UI 回调模型**：从 fcitx5 事件驱动异步回弹，改为"`process_key` 后同步 `get_context` + 主动推 UI"（Foxy 模式）。
3. **线程调度**：换掉 libuv/EventDispatcher，用一个 `rime-main` 单线程 executor 串行化引擎调用（Foxy 就这么做）+ Android `Handler` 回主线程更新 UI。
4. **InputConnection 对接**：commit/setComposingText/deleteSurroundingText/光标重定位——直接对接 Android `InputConnection`，不再经 fcitx5 的 `deleteSurrounding`/`repositionCursor`。
5. **部署期吞键 + overlay**：本项目重要 UX 修复（`rimestate.cpp:183-202`），逻辑简单（查 `is_maintenance_mode`），必须保留。

---

## 7. 风险与权衡

| 风险 | 严重度 | 说明与对策 |
|------|--------|-----------|
| **音节 tab 消歧能力丢失** | 高 | `get_input_tabs`/`select_tab`/`get_candidate_code` 是本项目**定制 librime 补丁**（`prebuilder/patches/librime.patch`）才有的 API，原版 librime 没有，Foxy 也没用。新中间层若要保留此卖点，JNI 必须继续桥接这套定制 API，且仍需维护定制 librime 构建链（8 个补丁的顺序契约照旧）。 |
| **levers 多选器依赖** | 中 | 方案多选、用户词典备份/恢复走 levers 模块（`find_module("levers")`），需在新链路确认 levers 仍编入并可用。 |
| **设置系统重写** | 中 | fcitx5 的 RawConfig desc 自动表单机制消失，引擎相关设置需改为直接读写 Rime YAML 或自建设置模型。应用自有设置（布局/主题/符号）不受影响。 |
| **失去 fcitx5 生态红利** | 中 | 一旦将来想重新支持其他引擎（拼音/码表/日文等）、或复用 fcitx5 上游的 bug 修复与新特性，就得自己从头做。本项目已是"Rime-only"专版，这个损失相对可接受。 |
| **线程安全/生命周期回归** | 中 | fcitx5 帮忙管理的焦点、session 生命周期、跨线程调度要自己正确实现，否则容易出现 ANR、session 泄漏、部署与输入竞态。Foxy 的单线程 executor + submit().get() 是一个经过验证的稳妥范式。 |
| **一次性迁移成本** | 中 | 约 2000+ 行业务逻辑移植 + 基础设施重建 + 全面回归（输入、候选、分页、方案切换、部署、同步、各布局/主题）。 |
| **维护者是 vibe coding 单人** | 中 | README 显示全部改动由 vibe coding 完成。重写引擎适配层是高风险大改，建议分阶段、可回滚。 |

---

## 8. 建议路线

**推荐：分阶段、保留回退能力，而不是一次性推倒。**

1. **阶段 0 — 实测立论**（必做）：先用项目现成的"输入法性能对比报告与测试工具包"，量化当前按键延迟中 librime / fcitx5 调用链 / UI 渲染三者的占比。**用数据确认 fcitx5 真的是瓶颈，再投入重写**；若瓶颈在 UI 渲染或 librime 查询，重写收益会很小。
2. **阶段 1 — 建一条并行的直连通道**：参照 Foxy 的 `Rime` 单例，新增一个直连 librime 的 JNI 封装（32 个核心调用 + `rime-main` 单线程 executor），先在一个 flavor/开关后面跑通"按键→候选→上屏→翻页→选字"最小闭环，UI 复用现有组件。
3. **阶段 2 — 补齐业务逻辑**：移植方案切换、状态、部署/同步、通知、部署期吞键 overlay；对接 InputConnection 的 commit/compose/删除。
4. **阶段 3 — 定制能力桥接**：决定是否保留音节 tab 消歧与 levers 多选器；要保留就把定制 librime 的 `get_input_tabs`/`select_tab` 等接入新 JNI。
5. **阶段 4 — 设置系统收口**：引擎相关设置改为直接读写 Rime YAML；应用自有设置原样保留。
6. **阶段 5 — 切换与清理**：全面回归通过后，把 fcitx5 core + androidfrontend + fcitx5-rime addon 整体移除，删掉 LRU/FocusGroup/xkb/DBus 等约 20% 死代码。

**何时不建议做**：如果阶段 0 实测显示 fcitx5 开销占按键延迟不足两三成，而主要痛点在候选渲染或词典查询，那么重写的性能回报不足以覆盖其风险和工作量——此时更应把精力放在 UI 增量刷新和 librime 侧优化上。

---

## 附：核心证据索引

- 当前 JNI 边界：`fx-rime-only/app/src/main/cpp/native-lib.cpp`（~50 个 JNI，含大量 fcitx5 config/addon/IM 管理）
- 热路径适配层：`fx-rime-only/plugin/rime/src/main/cpp/fcitx5-rime/src/rimestate.cpp:164/405`、`rimeengine.cpp:847`、`app/src/main/cpp/androidfrontend/androidfrontend.cpp`
- librime API：`librime-src/src/rime_api.h`、`rime_levers_api.h`、`rime/key_table.h`
- 定制 tab 补丁：`prebuilder/patches/librime.patch`（`get_input_tabs`/`select_tab`/`get_candidate_code`）
- **范本实现**：`D:\GitHub\foxy\foxy_re\sources\java\com\fxliang\foxy\Rime.java`（32 个 librime 直连 JNI，无 fcitx5）、`_analysis\_jni_exports.txt`（41 个导出，Rime 32 + LibGit2 9）
