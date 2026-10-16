# 安装记录（2026-10-16）

## devices 实测

```
List of devices attached
e09303ba               device product:PJD110 model:PJD110 device:OP5929L1 transport_id:3
```

OnePlus PJD110 在线（与上一轮构建期间实测一致；此前 s-device 步骤的离线结论
已被本次实测取代——设备是中途连上的）。

## install 实测

```
Performing Streamed Install
Success
```

包：`direct-rime-proto/appbuild/app/build/outputs/apk/fx/debug/org.fxrime.ime-a21c1b2-arm64-v8a-debug.apk`
（31,709,329 字节，含 PASSTHROUGH 同步）。

与 s-install 预测一致，无反常。
