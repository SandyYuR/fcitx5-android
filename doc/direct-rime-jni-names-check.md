# JNI 27 处绑定契约逐名对照（2026-10-16，现取补证）

来源：`direct-rime-proto/kotlin/org/fxrime/direct/RimeBridge.kt`
（`external fun` 名）对 `direct-rime-proto/native/rime_bridge.c`
（`Java_org_fxrime_direct_RimeBridge_*` 导出名），各排序后 `diff` 为空。

计数：Kotlin 侧 `external fun` 27 个、`@JvmStatic` 28 个（含普通 fun
`onRimeNotification` 的 1 个）；native 侧导出 27 个、第二参数 `jclass` 27 处。

| # | Kotlin external fun | native 导出 | 对应 |
|---|---|---|---|
| 1 | nativeChangePage | nativeChangePage | 对上 |
| 2 | nativeClearComposition | nativeClearComposition | 对上 |
| 3 | nativeClearTabs | nativeClearTabs | 对上 |
| 4 | nativeCommitComposition | nativeCommitComposition | 对上 |
| 5 | nativeCreateSession | nativeCreateSession | 对上 |
| 6 | nativeDeleteCandidate | nativeDeleteCandidate | 对上 |
| 7 | nativeDestroySession | nativeDestroySession | 对上 |
| 8 | nativeFinalize | nativeFinalize | 对上 |
| 9 | nativeFindSession | nativeFindSession | 对上 |
| 10 | nativeGetCandidateCode | nativeGetCandidateCode | 对上 |
| 11 | nativeGetCaretPos | nativeGetCaretPos | 对上 |
| 12 | nativeGetInput | nativeGetInput | 对上 |
| 13 | nativeGetInputTabs | nativeGetInputTabs | 对上 |
| 14 | nativeGetOption | nativeGetOption | 对上 |
| 15 | nativeGetSchemaList | nativeGetSchemaList | 对上 |
| 16 | nativeGetVersion | nativeGetVersion | 对上 |
| 17 | nativeIsMaintenanceMode | nativeIsMaintenanceMode | 对上 |
| 18 | nativeJoinMaintenanceThread | nativeJoinMaintenanceThread | 对上 |
| 19 | nativeProcessKey | nativeProcessKey | 对上 |
| 20 | nativeSelectCandidate | nativeSelectCandidate | 对上 |
| 21 | nativeSelectCandidateOnCurrentPage | nativeSelectCandidateOnCurrentPage | 对上 |
| 22 | nativeSelectSchema | nativeSelectSchema | 对上 |
| 23 | nativeSelectTab | nativeSelectTab | 对上 |
| 24 | nativeSetOption | nativeSetOption | 对上 |
| 25 | nativeSetup | nativeSetup | 对上 |
| 26 | nativeStartMaintenance | nativeStartMaintenance | 对上 |
| 27 | nativeSyncUserData | nativeSyncUserData | 对上 |

原始清单：`reports/direct-rime-jni-names-kotlin.txt`、
`reports/direct-rime-jni-names-native.txt`（各 27 行）。

结论：27 处全部一一同名对应，无多余、无缺失；B 方案（只动 Kotlin 侧补
注解、native 27 处 `jclass` 不动）覆盖全部绑定契约。重建后同点 Abort 未再现、
引擎启动链全通（见 `reports/direct-rime-start-log.md`），旁证修法生效。
