# 重建与安装记录（2026-10-16，B 方案第二包）

## 构建

- `:app:assembleFxDebug` BUILD SUCCESSFUL in 27s
  （109 actionable tasks: 21 executed, 88 up-to-date）
- 完整日志：`reports/direct-rime-build2.log`
- `@JvmStatic + external` 组合编译通过，B 方案语法成立。

## 产物

- 路径：`direct-rime-proto/appbuild/app/build/outputs/apk/fx/debug/org.fxrime.ime-a21c1b2-arm64-v8a-debug.apk`
- 字节数：31,709,329（与上一包同字节数级别；文件名版本号仍为 a21c1b2，
  以 `LastWriteTime` 13:47 区分）。
- 注意：字节数与上一包相同，属增量构建的正常现象（改动仅 Kotlin 注解层）；
  dex 内容已变，以时间戳与 logcat 行为为准，不以字节数判新旧。

## 安装

- `adb devices -l`：e09303ba device（PJD110）在线。
- `adb install -r`：Success。
- logcat 已清空（CLEARED），待用户切到新输入法输入后抓取。
