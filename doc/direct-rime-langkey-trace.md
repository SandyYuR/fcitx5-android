# 语言键全链路追踪（2026-10-16，s-survey-langkey）

## 单击链路逐段（静态追踪，行号以 appbuild 现取为准）

| # | 段 | 文件:行 | 参数 |
|---|---|---|---|
| 1 | 语言键单击 | `CommonKeyActionListener.kt:183-188`（`LangSwitchAction`） | `service.sendStandaloneShiftTap()`，不走系统轮换 |
| 2 | 合成 Shift 点按 | `FcitxInputMethodService.kt:1655-1670` | `KEYCODE_SHIFT_LEFT`（Android 59），DOWN 后 hold `STANDALONE_MODIFIER_HOLD_MS=50ms`（`:143`），UP；50ms < librime 500ms 上限（`:124` 注释） |
| 3 | 转发 | `FcitxInputMethodService.kt:1529-1560`（`sendSimulatedKeyEvent`）→ `:1337-1376`（`forwardKeyEvent`） | `KeySym.fromKeyEvent`：Shift 的 `unicodeChar=0`，跳过字符分支（`KeySym.kt:45-57`），F1-F12 不匹配，最终 `keyCodeToSym(59)` → Shift keysym；`postFcitxJob { sendKey(sym, states, scanCode, up, ts) }`，顺序排队 |
| 4 | 引擎入口 | `DirectRimeEngine.kt:379-383`（`sendKey(sym: KeySym…)`）→ `:298`（`dispatchKey`） | `dispatchKey(code=scanCode, states, unicode=sym.sym, up)`；**注意 code 是 Linux scancode（Shift 左=42），不是 Android keyCode（59）** |
| 5 | native 组键 | `keymap.c:190-212`（`dr_build_key`） | unicode=65505（0xFFE1）非零且不在排除表 → `keysym=unicode=0xFFE1=XK_Shift_L`，keycode 被忽略；UP 时 `mask\|=DR_RELEASE_MASK(1<<30)`（`:209`，与 `rimestate.cpp:218` 的 `(1<<30)` 同值） |
| 6 | 门控 | `rime_bridge.c:337-341` | keysym 非零、有 session、非部署期 → `DR_DISPATCH_TO_RIME`，进 `process_key` |
| 7 | librime 切换逻辑 | `librime-src/src/rime/gear/ascii_composer.cc:60-128` | DOWN：`is_shift`，首次按下置 `shift_key_pressed_=true`，`toggle_expired_=now+500ms`，回 `kNoop`；UP：`release()` 为真且标志位在、50ms<500ms 内 → `ToggleAsciiModeWithKey(Shift_L)`，按 `default.yaml:60-66` 的 `switch_key`（`Shift_L: inline_ascii`）翻转 `ascii_mode`，同样回 `kNoop` |
| 8 | 回传 | `rime_bridge.c:347-349` | `kNoop` → `process_key=false` → `dispatch=PASSTHROUGH(3)`；`pack_state` 在切换**之后**读 `get_status`，`flags bit2`（`:189`，`is_ascii_mode`）应已是新值（`RimeSnapshot.kt:71` 的 `asciiMode` 回显） |
| 9 | Kotlin 收尾 | `DirectRimeEngine.kt:345-349` | PASSTHROUGH → `publish(snapshot)` + `return true`；但 `sendKey`（`:379-383`）**丢弃返回值**，无人把 Shift 交给编辑器；`publish` 的补发（`:238-255`）要求 `!lastKeyUp && !isModifier`：DOWN 被 `isModifier`（65505 在列，`:233-237`）拦住，UP 被 `!lastKeyUp` 拦住——新包 Shift 无补发 |

## 断点定位结论

1. **`dispatch=3 是正常现象，不是断点**。AsciiComposer 对 Shift 的 DOWN/UP 都回 `kNoop`
   （切换是内部直接改 option，不是靠返回值消费），直连链路如实回传即得 3。
   用户日志 `kc=42 uni=65505 dispatch=3`（kc=42 是 scancode，uni=65505 是 Shift keysym）
   恰好证明 Shift 以正确参数进了 `process_key`。
2. **符号上屏的根因是补发路径的 scancode 错位（映射错位）**。`publish` 补发用
   `FcitxKeyMapping.keyCodeToSym(lastKeyCode)`（`:240`），而 `lastKeyCode` 存的是
   scancode 42；Android `KEYCODE_N=42`（A=29 起顺排，N=42），查表得 `'n'` 的 keysym，
   补发即上屏一个 `n`。第十二包的 `isModifier`（查 unicode=65505）拦住了 DOWN，
   UP 本来就不补发——**旧包（无此拦）按语言键上屏 `n`**，与用户"误上屏符号"吻合。
3. **`ascii_mode` 是否真翻转，尚缺真机日志证实**。理论链路（UP 带 RELEASE 位、
   50ms 内、switch_key 认 Shift_L、processors 含 ascii_composer）应翻转；
   但切换无 UI 回显（无人监听 `asciiMode` 位变化），用户可能"切了却看不出来"，
   也可能 UP 根本没进引擎。判据要求以 `DirectRimeKey` 日志为准。

## 待真机验证的读数（修步合并回归用）

- `dispatch kc=42 … uni=65505 up=false` 与 `up=true` **成对出现**；
- UP 那条的 snapshot `flags bit2` 相对 DOWN 是否翻转（`asciiMode` 前后对照）；
- 单击后无符号上屏；长按仍出 `RimeSchemaMenuDialog` 方案选单（`CommonKeyActionListener.kt:107-115`，未动）。
