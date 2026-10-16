# dispatch 修复记录（2026-10-16，s-dfix）

## 诊断：dispatch=3，无方案空转

第四包冷启动（pid 12201）`DirectRimeKey` 实测：字母 a（kc=30 meta=0 uni=97）
`dispatch=3`（PASSTHROUGH，commit=null preedit=null cands=0），门控每次
`maint=false hasSession=true` —— 门控未误吞，键进了 librime 但 `process_key`
回 false。包内 `rime-data` 仅 4 文件，`default.yaml` 中 `schema_list: []`，
无方案可跑即空转。详见 `reports/direct-rime-dispatch-log.md`。

## 修复链（分包可回滚，先后共 8 包：第五至第十二）

1. **第五包——打入方案**：`app/src/main/cpp/CMakeLists.txt` 的 `install(FILES…)`
   加 10 个文件（luna_pinyin 6 + pinyin.yaml + stroke 2 + essay.txt）；
   `DirectRimeEngine.start()` 首次启动写用户目录 `default.custom.yaml`
  （`schema_list: [luna_pinyin]`）。`default.yaml` 本体 `schema_list: []` 未动。
   zip 核验包内 14 个 `rime-data` 文件全在，`adb install Success`。
   （第六包仅为 PASSTHROUGH 常量同步后的重建包，无独立修法，不占一项。）
2. **第七包——String 字符码**：虚拟键盘字母走 `sendKey(String)`，直连实现成
   `dispatchKey(code, states, 0, up)` —— unicode 传 0 掉进 scancode 错位分支。
   改为 `key.firstOrNull()?.code`。用户确认"能正常上屏字母了"。
3. **第八包——首次部署**：写完 `default.custom.yaml` 后跑一次
   `startMaintenance(false)` + `join`，等 `build/luna_pinyin.schema.yaml`
   编出来再建 session。首包曾因自动 `select_schema` 与部署线程回调竞态
   abort（`attempting to detach while still running code`），修法为回调只
   Attach 不 Detach + 首包不自动 select。
4. **第九包——去 Detach + 不自动 select**：`BUILD SUCCESSFUL in 1m 12s`，
   36,615,645 字节，`install Success`。用户确认"可以打字了，也能出候选和
   选词上屏了" —— dispatch 已由 3 转 0，判据第三条达成。
5. **第十包——收窄兜底**：`publish()` 的 KeyEvent 兜底开太大，TO_RIME 也补发
   导致"nihao 同步上屏"双写。收窄为仅 PASSTHROUGH 补发。
6. **第十一包——修饰键过滤**：Shift（kc=42 uni=65505）走 PASSTHROUGH 被补发
   上屏符号。加 `isModifier` 拦 Shift/Ctrl/Alt/Meta/Caps 的 DOWN 补发。
7. **第十二包——caret 越界**：native `cursor_pos` 是字节口径，中文/符号场景
   下 `PreeditUi.update(PreeditUi.kt:259)` 越界闪退（`begin 0, end 3, length 1`）。
   越界按末尾、空按 -1。`BUILD SUCCESSFUL in 12s`，`install Success`。
   落点注：修法在引擎侧 `DirectRimeEngine.kt:258-268` 的 `safeCaret`
   （`caretPos<0||>preedit.length` 取末尾）+ `FormattedText` 构造；`PreeditUi.kt`
   本体未动（与 `fx-rime-only` 同文件 `diff` 为空，见 `direct-rime-preeditui.diff`），
   日志原话"PreeditUi.kt:259"是崩溃栈落点而非改动文件。

## 修法 diff 索引（评估者补证用，行号以原型仓库工作区现取为准）

| 改动 | 文件 | 落点 |
|---|---|---|
| 27 处 `@JvmStatic`（B 方案，native 不动） | `direct-rime-proto/kotlin/org/fxrime/direct/RimeBridge.kt` | `nativeSetup` 起 27 个 external fun 全加（见 `direct-rime-canonical.diff:5-127`） |
| `DISPATCH_PASSTHROUGH=3` 常量 | `direct-rime-proto/kotlin/org/fxrime/direct/RimeSnapshot.kt` | `:38`（副本 `appbuild/.../RimeSnapshot.kt:38` 一致） |
| 枚举加项 + `process_key` 回传 consumed/dispatch + 去 Detach | `direct-rime-proto/native/direct_rime.h`、`/rime_bridge.c` | `direct-rime-canonical.diff:142-201`（`direct_rime.h:152-156`、`rime_bridge.c:190-199` 去 `DetachCurrentThread`） |
| 方案打包 10 文件 | `direct-rime-proto/appbuild/app/src/main/cpp/CMakeLists.txt` | `:67-81`（luna 6 + pinyin + stroke 2 + essay） |
| 首次启动写 `default.custom.yaml` + 首次部署 | `direct-rime-proto/appbuild/app/src/main/java/org/fxrime/direct/DirectRimeEngine.kt` | `:124-147`（`needDeploy` 见 `:145`，`startMaintenance+join` 紧随其后） |
| String 字符码 `key.firstOrNull()?.code` | 同上 | `sendKey(String)` 见 `:360-366` |
| PASSTHROUGH 兜底收窄 + `isModifier` | 同上 | `:222` 注释、`:233-239`（Shift/Ctrl/Alt/Meta/Caps DOWN 不补发） |
| `safeCaret` 字节口径收敛 | 同上 | `:258-268` |

`direct-rime-canonical.diff`（201 行）是 canonical 四文件（RimeBridge.kt /
RimeSnapshot.kt / direct_rime.h / rime_bridge.c）的 `git diff` 原样留存；
引擎侧与 CMakeLists 的修法在 `appbuild/`（git 忽略，见原型 `.gitignore`），
以改动备份 `build/mainrepo-revert-backup/*.diff` + 上表行号为证。

## 与预测对照

s-dfix 预测"dispatch 由 3 转为 0，用户确认有候选能上屏" —— 成立。
`schemas=[luna_pinyin]` 在日志证实，部署跑完，用户原话确认打字。

### dispatch=0 实测摘录（17:15，pid 15371，luna_pinyin 已部署）

```text
DirectRimeKey: dispatch kc=0 meta=536870912 uni=43 up=false … session=…7800 …
DirectRimeKey: dispatch=0 commit=+ preedit=null cands=0
DirectRimeKey: dispatch kc=12 meta=0 uni=45 up=false … session=…7800 …
DirectRimeKey: dispatch=0 commit=- preedit=null cands=0
DirectRimeKey: dispatch kc=13 meta=0 uni=61 up=false … session=…7800 …
DirectRimeKey: dispatch=0 commit=null preedit== cands=3
```

`+`/`-` 被 librime 消费直接上屏（TO_RIME，commit 非空），`=` 进预编辑并出
3 个候选 —— 与第四包全 3/2 的空转表（见 dispatch-log.md 按键链）形成对照。
后续冒出的双写/修饰键符号/caret 越界是同一链条下游的新断点，
已各修一处，不属本预测落空。
