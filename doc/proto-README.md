# direct-rime-proto · 去 fcitx5 直连 librime 的隔离原型

> 本报告对应 `docs-site-worktree/docs/maintainer/DIRECT_RIME_REEVALUATION_2026-10-07.md`
> 里建议的「阶段 B：隔离直连原型」。
> **它不是迁移方案，也不是性能结论。** 它回答一个更窄的问题：
> 在本机没有真机、没有模拟器、没有 MSVC/MinGW 的条件下，
> 「Kotlin/JNI 直连 librime」这条路能走到哪一步、哪些部分只能停在未验证。

---

## 0. 一句话结论

直连 librime **在本机上可以编出来、可以链接、可以导出完整的 JNI 接口**，
按键映射与门控这两块纯逻辑**可以在没有真机的情况下真跑并通过 59 条用例**；
但**端到端行为、性能、部署与焦点语义均未经真机验证**，
因此本报告不包含任何「直连更快 / 直连等价」的主张。

三件事已实测，三件事没测：

| 已实测 | 未测（写明未测，不编数字） |
|---|---|
| arm64 `.so` 产出并核验为 ELF64/AArch64/DYN | 端到端输入延迟 p50/p95/p99 |
| 27 条 JNI 导出 + `rime_get_api` 符号存在 | CPU / RSS / native heap 占用 |
| keymap wasm 在 Node 中真跑，59 条用例全过 | 真机焦点切换、部署吞键、多编辑器行为 |
| 静态链档已消除对 `libc++_shared.so` 的 NEEDED 依赖（见 §2.4） | `.so` 从未在设备上加载过（无真机执行） |

---

## 1. 为什么这样建（与报告的关系）

报告 §5.3 列了五条「当前不能下的结论」，本原型逐条遵守：

1. 「去掉 Fcitx5 一定能降低输入延迟」——未测。本原型没有任何 trace。
2. 「JNI 次数是当前主要瓶颈」——未测。批量接口是**设计选择**，不是性能结论。
3. 「`DROP_OLDEST` 已经造成用户可见的丢字」——未测。本原型改用显式代次门控，
   但**没有**与原链路做丢弃计数对比。
4. 「直连可以无损复用现有所有 tabs、RawConfig 和部署行为」——原型只暴露了
   tabs / candidate code / select_tab 的 ABI，**没有**验证与现有布局/设置兼容。
5. 「删除 fcitx5 后可以同步删除所有事件、焦点和生命周期代码」——本原型反过来说：
   这三类代码**必须重写**，见 §3 的盘点表。

报告 §6 阶段 B 的原文要求：「原型必须保留显式的线程 owner 和 session generation；
不要让 librime 调用从任意 Android coroutine 直接并发进入。」
这两条在原型里分别由 `RimeThreadOwner` 与 `SessionGenerationGate` 承担。

---

## 2. 仓库里有什么

```
direct-rime-proto/
├── native/
│   ├── direct_rime.h      97 行  口径头：掩码位、keysym 常量、dispatch 枚举
│   ├── keymap.c          222 行  KeyEvent → (keysym, mask) 纯逻辑，零 libc 依赖
│   ├── rime_bridge.c     593 行  JNI 层，27 条导出，其中 9 条状态变更入口返回 Object[]
│   └── build.sh           92 行  非 Gradle 构建 + readelf/nm 核验
├── kotlin/org/fxrime/direct/
│   ├── RimeSnapshot.kt   131 行  Object[] 槽位常量表与解包
│   ├── RimeThreadOwner.kt 101 行 单线程 owner（线程名 rime-main，daemon）
│   ├── SessionGenerationGate.kt 80 行 代次门控
│   ├── DeployKeyGate.kt   83 行 部署期按键门控
│   └── RimeBridge.kt     169 行 27 条 external 声明 + 通知落地
├── test/run-keymap.mjs   229 行 wasm 用例（Node 真跑）
├── test/kotlin/GateTests.kt      JVM 用例，86 条断言（不依赖真机与 librime）
├── tools/kotlinc.sh       42 行 本机 kotlinc 封装（含缺 jar 的报错对照）
├── tools/kotlin-test.sh          编译 + 跑 GateTests.kt
├── tools/kotlin-mutation.sh      变异检验：注入 6 个已知缺陷，确认用例会变红
├── baseline-repos.txt           动手前六个仓库的 git 状态基线
└── build/                       产物与核验记录
```

### 2.1 掩码位：为什么不需要重排

这是本次最关键的核实结果。报告 §2.3 只说「fcitx5-rime 调 librime API」，
没说修饰键位是否需要转换。核对结果：

- librime `src/rime/key_table.h:15-45`：`kShiftMask=1<<0`、`kLockMask=1<<1`、
  `kControlMask=1<<2`、`kMod1Mask=kAltMask=1<<3`、`kSuperMask=1<<26`、`kReleaseMask=1<<30`。
- fcitx5 `src/lib/fcitx-utils/keysym.h:22-59`：`Shift=1<<0`、`CapsLock=1<<1`、
  `Ctrl=1<<2`、`Alt=1<<3`、`Super2=1<<26`。
- 决定性证据 `plugin/rime/src/main/cpp/fcitx5-rime/src/rimestate.cpp:208-238`。
  **注意：这里不是「原样传入」，而是三步变换**（早先的表述写成「原样、无位重排」，
  不准确，已按源码改正）：

  1. `:208-210` 先做**掩码截取**：
     `auto states = event.rawKey().states() & KeyStates{KeyState::Mod1, KeyState::CapsLock, KeyState::Shift, KeyState::Ctrl, KeyState::Super};`
     —— 只保留这五位，其余位（NumLock、Hyper、Meta…）被丢掉。
  2. `:211-214` 若 Super 位置位，则**补 Super2 位**：
     `if (states.test(KeyState::Super)) { states |= KeyState::Super2; }`
     （Super2 = `1U<<26`，IBus virtual super）
  3. `:216-219` 若是抬起事件，**补 release 位**：
     `if (event.isRelease()) intStates |= (1 << 30);`

  然后才 `api->process_key(session, event.rawKey().sym(), intStates)`。

  所以 fcitx 侧的真实语义是「**过滤到五位 + Super→Super2 补位 + release 补位**」。
  这仍是**同一批位定义、无需数值重排**（Shift=1<<0、CapsLock=1<<1、Ctrl=1<<2、
  Alt=1<<3、release=1<<30 两侧一致），但推导路径不是「原样透传」。

结论：直连只需把 Android `metaState` 映射到同一批位，不需要数值转换层。
`dr_metastate_to_mask` 只映射 Shift/CapsLock/Ctrl/Alt/Super 五位，
**刻意不映射 NumLock**——因为 `rimestate.cpp:208` 的
`KeyStates{KeyState::Mod1, CapsLock, Shift, Ctrl, Super}` 也只取这五位。

**与 fcitx 路径的差异（已知且有意）**：我的实现
`native/keymap.c:184` 是 `if (android_metastate & AMETA_META_ON) mask |= DR_SUPER_MASK;`，
而 `DR_SUPER_MASK = 1u<<26`（`native/direct_rime.h:24-29`）。
即把 Android 的 META_META_ON **直接映到 Super2 位（1<<26）**，
没有走 fcitx 那步「先置 Super(1<<6) 再补 Super2」。
**数值结果与 fcitx 路径一致**（最终都是 1<<26），但语义来源不同：
Android 的 metaState 没有区分 Super 与 Super2 的余地，
所以这里是一次直接映射，而不是「补位」。

### 2.2 批量 JNI：一次按键的往返是常数

借鉴 foxy 的做法（native 返回 `Object[]`，Java 侧拆包），
`pack_state()` 一次调用 `get_commit` + `get_context` + `get_status` 并打包进一个数组。

**准确口径（此前表述过宽，已修正）**：27 条导出里**不是全部**返回这个 shape。
真正走 `pack_state` 的是 **9 条**会改变引擎状态的入口（`pack_state` 共 10 个调用点，
其中 2 个同属 `nativeProcessKey` 的门控分支与正常分支）：

`nativeProcessKey` `nativeSelectCandidate` `nativeSelectCandidateOnCurrentPage`
`nativeChangePage` `nativeDeleteCandidate` `nativeCommitComposition`
`nativeClearComposition` `nativeSelectTab` `nativeClearTabs`

其余 18 条是查询或生命周期类，返回的是各自该返回的东西（`String`、`Boolean`、
`String[]` 方案列表、tab 扁平数组等），不需要也不应该打包快照。
判据要求的是「**状态变更**接口为批量接口」，这一条对这 9 条成立；
把「27 条全为批量接口」说成全称是错的。槽位表：

| 槽 | 内容 | 槽 | 内容 |
|---|---|---|---|
| 0 | dispatch | 9 | is_last_page |
| 1 | commit text | 10 | select_keys |
| 2 | preedit | 11 | commit_text_preview |
| 3 | caret_pos | 12 | schema_id |
| 4 | sel_start | 13 | schema_name |
| 5 | sel_end | 14 | status flags（位压缩） |
| 6 | page_no | 15 | candidate count |
| 7 | page_size | 16.. | N 对 (text, comment) |
| 8 | highlighted_index | | |

status 位序：bit0 disabled / bit1 composing / bit2 ascii_mode / bit3 full_shape /
bit4 simplified / bit5 traditional / bit6 ascii_punct。

这不是「JNI 次数少所以更快」的论证——那正是报告 §5.3 禁止的结论。
它只是让「一次按键固定 N 次 JNI」成为可数、可核的设计事实。

### 2.3 门控：down 与 up 必须成对吞

`rimestate.cpp:183-202` 的原文是 maintenance mode 或无 session 时
`event.filterAndAccept()`，注释解释了原因：未处理的键会被 frontend 当普通字符
提交进编辑器，产生垃圾文本；**按下与抬起都要吞**，编辑器不能收到没有 keydown 的 keyup。

直连后没有 AndroidFrontend 帮我们 filter，所以这个门控必须显式重建：
native 侧 `dr_gate_key()` 判一次，Kotlin 侧 `DeployKeyGate` 再判一次并维护
「down/up 都吞」的不变式。用例里专门钉了分支优先级：
**映射失败（DROP）优先于部署门控（SWALLOW）**——不可映射的键不该被吞，应放行给编辑器。

### 2.4 C++ 运行时的两种打包方式（默认档 / 静态链档）

librime 与其依赖都是 C++ 库，链接时必然牵入 C++ 运行时。这里有两种打包方式，
由 `STATIC_LIBCXX` 环境变量选择，产物文件名不同，**可并存**：

| | 默认档（不设变量） | 静态链档（`STATIC_LIBCXX=1`） |
|---|---|---|
| 产物 | `libdirect_rime.so` | `libdirect_rime.static.so` |
| 大小 | 6,916,848 字节 | 15,060,768 字节（+8.1 MB） |
| NEEDED 含 `libc++_shared.so` | **是** | **否** |
| NEEDED 全量 | liblog / libandroid / **libc++_shared** / libm / libdl / libc | liblog / libandroid / libm / libdl / libc |
| `llvm-nm -D -u` 里的 `std::__ndk1` 未定义符号 | 208 个 | **0 个** |
| 核验读数落在 | `build/arm64-v8a/verify.txt` | `build/arm64-v8a/verify-static.txt` |

**为什么要有静态链档**：默认档把 `libc++_shared.so` 列为 NEEDED，但产物目录并不随附它。
只把 `.so` 交给别人、而对方的 APK 里没有这个运行时，就会 `dlopen` 失败。
静态链档把运行时链进自身，这个前提就消失了。

**取舍**：代价是体积 +8.1 MB。若最终目标是打进 APK，而 APK 里本来就带
`libc++_shared.so`（现有 fx-rime-only 的 APK 里就有，1,253,544 字节），
那么默认档更省体积；只有当 `.so` 要独立分发或宿主不保证该运行时时，静态链档才值得。

**这两档都只证明了「不会因缺运行时而失败」，不等于「能加载」**——两档产物都从未在
设备上 `dlopen` 过，见 §5 第 7 项。

> ⚠️ 曾有记录称「`-static-libstdc++` 在 NDK 上是 argument unused，解决不了」。
> 那是在 **clang（C 驱动）** 下试出来的结论；换成 **clang++ 驱动**后该选项确实生效，
> 上表是实测读数。旧结论勿再照抄。

---

## 3. 报告 §4 七领域盘点：哪些补上了，哪些没有

这是本原型相对报告最有价值的部分——不是「做出来了」，而是**逐领域说明做到了哪一层**。

| 领域 | 当前由 Fcitx 提供 | 原型里的替代物 | 状态 |
|---|---|---|---|
| **按键** | Fcitx KeySym / mask / release / compose | `keymap.c`：`dr_keycode_to_keysym` + `dr_metastate_to_mask` + `dr_build_key` | ✅ 逻辑已真跑（59 条）。compose 未实现（见下） |
| **生命周期** | AndroidFrontend / InputContext / focus | `RimeThreadOwner` + `SessionGenerationGate` + session 增删查 | ⚠️ **部分**。session 有了，但「每个编辑器/UID 一个 session 与焦点切换重绑」**未接线**——那需要 IMS，本机无真机 |
| **UI** | FlushUIEvent、候选/预编辑/状态事件 | `RimeSnapshot` 批量快照 + 代次校验 | ⚠️ **部分**。数据模型有了，主线程刷新策略与提交可靠性**未验证**（无真机，无法断言不丢字） |
| **部署** | maintenance 检查、吞键、提示 | native `g_deploy_running` + `DeployKeyGate` + deploy 三态通知 | ⚠️ **部分**。门控逻辑已真跑，但「失败恢复与用户提示」只留了通知通路，**未做 UI** |
| **配置** | addon / RawConfig / Fcitx 保存流程 | `nativeSetOption` / `nativeGetOption` / `nativeSelectSchema` | ❌ **基本未补**。schema/option 能读写，但 RawConfig、用户数据部署、备份恢复**完全没做** |
| **定制能力** | tabs / candidate code / 候选动作 / 分页 | `nativeGetInputTabs` / `nativeSelectTab` / `nativeClearTabs` / `nativeGetCandidateCode` + 分页三个入口 | ✅ **ABI 已暴露**（报告 §2.4 点名的三项都有）。但「兼容旧布局/设置与回归测试」**未做** |
| **进程/线程** | `fcitx-main` 单线程 loop | `RimeThreadOwner`（`rime-main`，daemon，submit().get()） | ✅ 已建。退出时未执行任务**不静默丢**（先 drain，超时才 shutdownNow，丢弃数可读） |

**盘点结论**：七领域里 3 项（按键、定制 ABI、线程 owner）落到实处，
配置领域基本空白，生命周期/UI/部署三领域只做到「数据结构与门控逻辑」、
缺少与真实 IMS 的接线。这印证了报告 §5.2 的判断——
迁移不是把一处 `process_key` 换成 JNI。

---

## 4. 已实测的证据（可复跑）

| 项 | 命令 | 读数 |
|---|---|---|
| native 构建 | `bash native/build.sh` | 退出码 0，`build/arm64-v8a/libdirect_rime.so` **6,916,848 字节** |
| 产物格式 | `llvm-readelf -h` | `Class: ELF64` / `Type: DYN` / `Machine: AArch64` |
| 符号 | `llvm-nm -D --defined-only` | `rime_get_api` 在 `0x1fc5c4`；`Java_org_fxrime_direct_RimeBridge_*` **27 条** |
| Kotlin 编译 | `bash tools/kotlinc.sh kotlin build/classes` | 退出码 0，11 个 `.class` |
| Kotlin 门控类真跑 | `bash tools/kotlin-test.sh` | JDK 21，**passed=86 failed=0** |
| 用例有效性 | `bash tools/kotlin-mutation.sh` | 注入 6 个已知缺陷，**6/6 全部被检出** |
| 按键映射真跑 | `node test/run-keymap.mjs` | Node v26.10.0，wasm 1,592 字节，**passed=59 failed=0** |

### 4.1 Kotlin 侧从「只编译」进到「真跑」

`DeployKeyGate` / `SessionGenerationGate` / `RimeThreadOwner` 三个类都落在输入正确性
红线上，但最初只编译过、没跑过一行。这三个类刻意只依赖 JDK 与 kotlin-stdlib
（不 import `android.*`），因此**不需要真机、模拟器或 librime**，JDK 上直接执行：

```bash
bash tools/kotlin-test.sh        # 86 条断言
bash tools/kotlin-mutation.sh    # 确认这 86 条不是空转
```

**为什么还要跑变异检验**：全绿本身不构成证据。用例若写空转（断言写错、分支没走到），
照样全绿。做法是往被测源码里注入已知缺陷，看用例是否变红。第一轮就抓到真实缺口：

> 把 `shutdown()` 改成「直接 `shutdownNow()` 且恒返回 0」（即**退出时静默丢弃排队任务**），
> 用例当时**全绿 passed=82 failed=0**。原因是关闭前任务都已 join 完、队列为空，
> 丢弃数本来就是 0——「排队任务被静默丢弃」这个场景根本没被测到。
> 补 `testShutdownDropsReported`（占住线程 + 排队 4 个任务 + 200ms 超时关闭）后，
> 该变异被 2 条用例捕获。

这条不变式正是报告 §4 点名的红线（「退出时未执行任务的处理必须是显式的」），
漏测它等于漏测整条红线。6 个变异与检出情况见 `build/kotlin-mutation.txt`：

| 变异 | 注入的缺陷 | 检出 |
|---|---|---|
| 1 | 部署期只吞 down 不吞 up | failed=5 |
| 2 | 代次门控不校验代次 | failed=6 |
| 3 | 退出时静默丢弃排队任务且不上报 | failed=2 |
| 4 | 关闭后提交静默拒绝不计数 | failed=3 |
| 5 | 解包槽位错位（COMMIT 读成 PREEDIT） | failed=1 |
| 6 | 候选解包步长错（+2 改 +3） | failed=4 |

变异 5/6 还顺带暴露一个写法问题：变异 6 原本以 `IndexOutOfBoundsException` 崩掉，
而非给出可定位的失败信息。已在用例里改为先断言条数再索引。

27 条导出（完整清单）：`nativeSetup` `nativeFinalize` `nativeGetVersion`
`nativeCreateSession` `nativeFindSession` `nativeDestroySession` `nativeProcessKey`
`nativeSelectCandidate` `nativeSelectCandidateOnCurrentPage` `nativeChangePage`
`nativeDeleteCandidate` `nativeCommitComposition` `nativeClearComposition`
`nativeSetOption` `nativeGetOption` `nativeGetSchemaList` `nativeSelectSchema`
`nativeGetInputTabs` `nativeSelectTab` `nativeClearTabs` `nativeGetCandidateCode`
`nativeIsMaintenanceMode` `nativeStartMaintenance` `nativeJoinMaintenanceThread`
`nativeSyncUserData` `nativeGetInput` `nativeGetCaretPos`。

---

## 5. 未验证清单（不要跳过这一节）

以下项目**在本机条件下无法验证**，全部标注为未测，不含任何估计数字：

1. **端到端输入延迟**：无 Perfetto trace、无真机。p50/p95/p99 全部未测。
2. **事件丢弃计数**：没有与原链路的 `MutableSharedFlow(DROP_OLDEST)` 做对比压力测试。
   原型改用显式代次门控，但**不宣称**它比原链路丢得少。
3. **资源占用**：未测 CPU / RSS / Java heap / native heap。
4. **真机输入正确性**：无设备、无模拟器（SDK 内无 emulator / system-images，
   `adb devices` 为空）。焦点切换、多编辑器、key release、compose、部署吞键
   这些只能靠真机断言的行为**一条都没跑**。
   → 区分清楚：三个门控类的**纯逻辑**已由 `GateTests.kt` 86 条在 JVM 上真跑
   （含变异检验证明用例有效），但那只证明**逻辑自洽**；它们在真实 IME 生命周期里
   是否被正确调用、down/up 在真机上是否真的成对，仍然未测。
5. **compose 语义**：原型未实现。现有链路的 compose 由 fcitx 承担，
   直连后需要自己接 `KeyCharacterMap` 的 compose 状态机——本次没有做。
6. **等价性**：59 条 wasm 用例的期望值溯源到读到的源码
   （`key_table.h`、`FcitxKeyMapping.keyCodeToSym()`、`KeySym.fromKeyEvent()`），
   **不是**真机实测。用例与被测代码都是本次写的，全绿只证明内部自洽，
   不证明与原链路行为一致。
7. **`.so` 的真机加载**：默认档 `llvm-readelf -d` 显示 NEEDED 含 `libc++_shared.so`
   （另需 `liblog.so` / `libandroid.so` / `libm.so` / `libdl.so` / `libc.so`），
   而产物目录**没有随附** `libc++_shared.so`；真机加载时若 APK 未包含它会 `dlopen` 失败。
   → 这一项**已用静态链档消除**（见 §2.4）：`STATIC_LIBCXX=1` 产出的
   `libdirect_rime.static.so` NEEDED 只剩 5 个系统库，不再依赖 `libc++_shared.so`。
   但**「不会因缺运行时而失败」不等于「加载成功」**：两个档的产物都从未在设备上
   `dlopen` 过，也没有打进 APK 验证。真机执行仍属未测。

---

## 6. 怎么复跑

前置：本机没有 cmake 3.31.6（SDK 只有 3.22.1，主仓库 `Versions.kt` 要求 3.31.6），
因此**不走 Gradle**；Kotlin 侧改用 gradle 缓存里的 `kotlin-compiler-embeddable` 直接编。

```bash
export TEMP=/cygdrive/d/GitHub/fx2-rime/.dsh-tmp TMP=/cygdrive/d/GitHub/fx2-rime/.dsh-tmp
cd direct-rime-proto

bash native/build.sh                 # 产 .so + keymap.wasm + build/arm64-v8a/verify.txt
STATIC_LIBCXX=1 bash native/build.sh # 另产静态链档 .static.so + verify-static.txt
node test/run-keymap.mjs             # wasm 真跑，写 build/wasm-test.txt
bash tools/kotlinc.sh kotlin build/classes   # Kotlin 侧编译
bash tools/kotlin-test.sh            # Kotlin 门控类真跑，86 条，写 build/kotlin-test.txt
bash tools/kotlin-mutation.sh        # 变异检验，写 build/kotlin-mutation.txt
```

`build/` 下只有证据文本入库（`*.txt`、`verify.txt`、`verify-static.txt`）；
`.so`/`.wasm`/`.class`/`.o` 已被 `.gitignore` 排除——它们可由上面的命令重出，重出后
`libdirect_rime.so` 的 md5 应为 `be27e406cc38acf69141b38fbdde0e90`（6,916,848 字节），
`libdirect_rime.static.so` 的 md5 应为 `de87e0d8e29bada00ff9e3714c5c9529`（15,060,768 字节），
`keymap.wasm` 应为 1,592 字节。核对记录见 `build/build-rerun2.txt`。

三个坑，复跑时会撞上（都已在脚本注释里写明）：

- 默认 `%TEMP%` 无写权限，clang 报 `unable to make temporary file: Permission denied`
  → 必须先 `export TEMP/TMP`。
- `clang++` 驱动会把 `.c` 当 C++ 编，jni.h 走 C++ 分支 → 20 条错误。
  → 用 `clang -x c` 先编 `.o`，再单独用 `clang++` 链接（librime 需要 C++ 运行时）。
- 只链 `librime.a` 会缺 glog 的 `google::LogMessage::LogMessage`，
  再缺 `std::__ndk1::ios_base::init`，再缺 `__android_log_write`
  → 依赖列表见 `build.sh`，末尾必须带 `-llog -landroid`。
- 想消掉 `libc++_shared.so` 依赖要加 `-static-libstdc++`，**但它只在 clang++ 驱动下生效**。
  早期在 clang（C 驱动）下试过并得出「argument unused，无效」的结论，
  那个结论只对 C 驱动成立——静态链档就是这么来的（见 §2.4）。

跑 Kotlin 单测另有两个坑（已封在 `tools/kotlin-test.sh` 里）：

- **必须 `MSYS_NO_PATHCONV=1`**：否则 MSYS bash 会把 classpath 里的
  `build/classes-test;C:/...` 当成路径列表改写，Java 侧报
  `NoClassDefFoundError: kotlin/jvm/internal/Intrinsics`——**看起来像缺 jar，实为路径被改**。
- 加 `-Dstdout.encoding=UTF-8`，否则中文断言名在 Windows 控制台是乱码。

---

## 7. 与 foxy 的关系（只借鉴，不复制）

`D:\GitHub\foxy\foxy_re` 是 jadx/CFR 反编译产物（96 处 `??`、305 条语法错误，
不是合法 Java），只作只读参考。借鉴的是三处**架构约定**：

- 单线程 owner + 同步阻塞派发（foxy 是 `rime-main`，原型沿用同名）。
- native 返回 `Object[]` 扁平数组，Java 侧拆包，避免逐字段回调。
- IMS 侧先处理 BACK/DEL 与面板状态，再决定要不要交给引擎。

**没有**复制任何代码、注释或命名。foxy 是 librime 直连的现成样例，
这反过来说明报告 §7 说的「未发现现有直连实现」是指**本仓库**内没有，
不是这类实现不存在。

---

## 8. 下一步（对应报告阶段 C，不在本次范围）

报告阶段 C 要求「行为等价与性能 A/B」。这需要真机，本机不具备，
因此**本次不做**，只列出需要什么：

- 一台 arm64 真机 + Perfetto trace；
- 与原链路的双通道开关（报告阶段 B 要求「出现错误时能切回 Fcitx 路径」——
  原型是独立仓库，**没有**与主仓库接线，也就没有这个开关）；
- 至少 12 组场景对比：普通输入、Shift/Control、key release、compose、
  候选分页/选字、schema 切换、input tabs、candidate code、部署吞键、
  焦点切换、多个编辑器、daemon 重启与断开重连。

在拿到这些之前，本原型的结论止于：**直连在技术上走得通，但只走完了三分之一。**
