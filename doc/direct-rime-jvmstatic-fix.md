# @JvmStatic 补注解记录（2026-10-16，B 方案）

## 改动

`sed` 在两处 `RimeBridge.kt` 的每个 `    external fun` 前插入
`    @JvmStatic`，再把 canonical 复制到 appbuild 副本：

- canonical：`direct-rime-proto/kotlin/org/fxrime/direct/RimeBridge.kt`
- 副本：`direct-rime-proto/appbuild/app/src/main/java/org/fxrime/direct/RimeBridge.kt`

## 验证

- 两处 `@JvmStatic` 计数各 28（27 新增 + `onRimeNotification` 原有 1 个）。
- `diff -q` 两边 IDENTICAL。
- `rime_bridge.c` 27 处 `jclass` 未动。
- 与 s-jvmstatic 预测一致，无反常。编译亲验见下一步 s-rebuild2。
