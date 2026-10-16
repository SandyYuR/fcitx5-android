# 语言键修复记录（2026-10-16，s-fix-langkey，第十三包）

## 修法（一处，最小改动）

`DirectRimeEngine.kt:238-243` 的 PASSTHROUGH 补发：不再用
`FcitxKeyMapping.keyCodeToSym(lastKeyCode)` 查表，改用 `lastUnicodeChar` 直传。

根因（见 `reports/direct-rime-langkey-trace.md`）：`lastKeyCode` 存的是 Linux
scancode（`dispatchKey` 的 code 参数），不是 Android keyCode；scancode 42 会命中
Android `KEYCODE_N`（A=29 起顺排）而错位成 `'n'` 上屏。`lastUnicodeChar` 已是
native 侧定好的 keysym（unicode 优先口径，与 `dr_build_key` 一致），可直接用。
附带删掉已无引用的 `import FcitxKeyMapping`。

```diff
-            val sym = FcitxKeyMapping.keyCodeToSym(lastKeyCode)
-                ?: lastUnicodeChar.takeIf { it > 0 }
+            // lastKeyCode 是 Linux scancode（dispatchKey 的 code 参数），不能喂给
+            // 按 Android keyCode 查表的 keyCodeToSym：如 scancode 42 会命中
+            // Android KEYCODE_N（A=29 起顺排）而错位成 'n' 上屏。lastUnicodeChar
+            // 已是 native 侧定好的 keysym（unicode 优先口径，与 dr_build_key 一致），直接用它。
+            val sym = lastUnicodeChar.takeIf { it > 0 }
```

Shift 本体（DOWN/UP 成对进 `process_key`、500ms 内、switch_key 认 Shift_L）
未动：第十二包的 `isModifier` 已拦住 Shift 的 DOWN 补发，UP 本来就不补发，
本次修的是其它键经同一补发表错位上屏的同类问题。

## 构建（第十三包）

- 命令：`appbuild` 下 `:app:assembleFxDebug -PbuildABI=arm64-v8a`
  （工具链环境变量见 `direct-rime-proto/HANDOVER_2026-10-16.md:136-144`）。
- 结果：`BUILD SUCCESSFUL in 19s`，见 `reports/direct-rime-build13.log`。
- 产物：`appbuild/app/build/outputs/apk/fx/debug/org.fxrime.ime-a21c1b2-arm64-v8a-debug.apk`，
  34,626,798 字节。

## 待真机验证（设备离线，`adb devices` 为空）

- `adb install -r`（Success 回执）；
- 单击语言键：`DirectRimeKey` 日志 Shift DOWN（kc=42 uni=65505 up=false）
  与 UP（up=true）成对出现，UP 条的 snapshot `flags bit2`（asciiMode）相对 DOWN 翻转，
  且无符号上屏；
- 长按语言键仍出 `RimeSchemaMenuDialog` 方案选单。
