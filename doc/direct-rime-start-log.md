# 启动定位日志记录（2026-10-16，第三包）

## 加 Log 的 diff 摘要

`direct-rime-proto/appbuild/app/src/main/java/org/fxrime/direct/DirectRimeEngine.kt`
`start()` 内加 6 行 `android.util.Log` 直打（tag `DirectRimeStart`）：
enter state / sync begin / sync end / 缺 default.yaml / setup 结果 /
session 值 / ready / catch 分支。Timber 原有行保留。

## 新包构建安装

- `:app:assembleFxDebug` BUILD SUCCESSFUL in 12s（日志 `reports/direct-rime-build3.log`）
- APK 31,709,329 字节，`adb install -r` Success
- `force-stop` + `logcat -c` 后冷启动复现

## 复现抓到的 DirectRimeStart 日志（14:30:55，pid 28741）

```
service.onCreate begin
DirectRimeStart: enter state=STOPPED
DirectRimeStart: sync begin
DirectRimeStart: sync end
DirectRimeStart: setup=true shared=/data/user_de/0/.../usr/share/rime-data user=/data/user_de/0/.../rime
DirectRime: setup=true ... version=1.17.0
DirectRimeStart: session=-5476376611609922984
DirectRime: session=-5476376611609922984
DirectRimeStart: ready
service.onCreate duration=84ms
```

## 结论

- `sync` 通过、`nativeSetup` 返回 true、session 已创建、lifecycle 到 READY。
  引擎启动链本身已通，B 方案止血成立。
- 按键仍无上屏，问题在启动链下游（dispatchKey / native 门控 / 事件回流三段之一）。
  与 s-startlog 预测一致（定位到哪一步），无反常。
