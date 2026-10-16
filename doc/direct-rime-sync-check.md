# 同步范围核对（2026-10-16）

## 1. Kotlin 侧

- 仅 `RimeSnapshot.kt` 不同：canonical 比 appbuild 副本多 3 行
 （`DISPATCH_PASSTHROUGH = 3` 及注释）。`diff` 实测仅此一处。
- 其余四文件（RimeThreadOwner、SessionGenerationGate、DeployKeyGate、
  RimeBridge）`diff -q` 无差异，无需同步。
- `DirectRimeEngine.kt:231` 已引用 `DISPATCH_PASSTHROUGH`，同步常量后自行成立。

## 2. Native 侧

- `appbuild/app/src/main/cpp/CMakeLists.txt:107`：
  `add_subdirectory("${DIRECT_RIME_DIR}" direct-rime-build)`，
  直接引用 `direct-rime-proto/native` canonical，无自带副本，无需复制。
- `rime_bridge.c` / `direct_rime.h` 的 PASSTHROUGH 改动随引用自动生效。

## 结论

需同步的只有 `appbuild/app/src/main/java/org/fxrime/direct/RimeSnapshot.kt`
缺的 3 行常量。与 s-sync-check 预测一致，无反常。
