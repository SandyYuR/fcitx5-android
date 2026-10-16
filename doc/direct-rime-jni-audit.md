# JNI 绑定契约审计（2026-10-16，真机 logcat 驱动）

## 崩溃证据（实测）

```
Abort message: 'JNI DETECTED ERROR IN APPLICATION: jclass has wrong type:
  org.fxrime.direct.RimeBridge
  from boolean org.fxrime.direct.RimeBridge.nativeSetup(...)'
Fatal signal 6 (SIGABRT) in tid ... (rime-main-0)
```

`DirectRimeEngine.start` → `RimeBridge.nativeSetup` → 崩溃，引擎从未启动，
所以根本打不出字。

## 27 处逐条核对

Kotlin 侧（`kotlin/org/fxrime/direct/RimeBridge.kt`）：`object RimeBridge` 内
27 个 `external fun`（行 28–120），除 `onRimeNotification` 有 `@JvmStatic` 外，
其余 26 个均无 `@JvmStatic` → 编译后是**实例方法**，JNI 第二参数应为 `jobject`。

Native 侧（`native/rime_bridge.c`）：27 个导出（行 218–593）第二参数全为
`jclass cls` → 与实例方法不匹配，运行时报 `jclass has wrong type` 并 abort。
`grep -c` 实测：`jclass` 27 处、`jobject` 仅 2 处（均为内部 helper `set_int/set_str`，
与绑定无关）。

`cls` 参数实际使用：仅 `nativeSetup:224` 处 `NewGlobalRef(cls)` 缓存类；
其余 26 处全是 `(void)cls` 占位，无实际用途。

反方向回调 `onRimeNotification` 有 `@JvmStatic`（真静态），native 侧
`GetStaticMethodID` 查它不受本次改动影响。

## 结论：Kotlin 侧补注解，不动 native

`object` 内 `@JvmStatic external fun` 生成真静态方法，JNI 函数名不变
（仍为 `Java_org_fxrime_direct_RimeBridge_native*`），第二参数变为 `jclass`，
与现有 native 实现正好匹配。作者给 `onRimeNotification` 加过 `@JvmStatic`，
说明补注解与现有风格一致，且只动一个 Kotlin 文件，风险最小。

- 需改：`RimeBridge.kt` 27 个 `external fun` 逐个加 `@JvmStatic`
 （含 `onRimeNotification` 已有的 1 个，共 28 个 `@JvmStatic`，其中 27 新增）。
  注意：`onRimeNotification` 是普通 fun 不是 external，不计入 27。
- 不动：`rime_bridge.c` 全部 27 处 `jclass` 保持原样。
- 推翻条件：若重建安装后仍在同点以同样 Abort 崩溃，则此判断被推翻。
