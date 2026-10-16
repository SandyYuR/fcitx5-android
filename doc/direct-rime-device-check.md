# 设备与 APK 现查（只读实测，2026-10-16）

## 1. adb 实测原文

命令：`D:/GitHub/tools/android-sdk/platform-tools/adb.exe devices -l`

输出：

```
List of devices attached
```

无设备序列号，设备仍离线，与上一会话阻塞点一致。

## 2. APK 现状

- 路径：`direct-rime-proto/appbuild/app/build/outputs/apk/fx/debug/org.fxrime.ime-a21c1b2-arm64-v8a-debug.apk`
- 字节数：29,461,634（与交接报告一致）
- `output-metadata.json`：`applicationId org.fxrime.ime.debug`，`variantName fxDebug`，
  ABI `arm64-v8a`，`versionCode 112`，`versionName a21c1b2`
- `build-metadata.json`：`commitHash a21c1b2…`，对应 direct-rime-proto 接入提交
- so/dex 既有核验结论（交接第 67–69 行：`libdirect_rime.so`、27 条 JNI 导出、
  dex 含 `DirectRimeEngine`）为历史记录，本轮未重验，按原报告引用，
  不当作本轮新证据。

## 结论

设备离线待验成立：冒烟四步不可执行。现存 APK 为 PASSTHROUGH 缺口
（u-zwh611）引入前构建，重建前须先同步常量。
