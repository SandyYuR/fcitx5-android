# 同步完成记录（2026-10-16）

## 同步内容

`direct-rime-proto/appbuild/app/src/main/java/org/fxrime/direct/RimeSnapshot.kt`
补 3 行（与 canonical 一致）：

```kotlin
/* 进了 librime 但它不处理（process_key 返回 false）：按键必须落回编辑器，
 * 既不能吞也不能当空状态——否则用户按了键没有任何反应。 */
const val DISPATCH_PASSTHROUGH = 3
```

## 验证

- 同步后 `diff canonical 副本`：输出为空（EXIT=0）。
- `DirectRimeEngine.kt:231` 的 `DISPATCH_PASSTHROUGH` 引用自行成立，无需改动。
- 其余四 Kotlin 文件此前已无差异；native 侧为引用关系，无需复制。

与 s-sync 预测一致，无反常。此同步关闭 u-zwh611 的成因（待 AdvancePlan 后 resolve）。
