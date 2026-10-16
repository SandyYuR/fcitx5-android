# 直连遗留项复核结论与下一步计划（2026-10-16）

## 结论

1. 接线差异收敛：appbuild 与主仓库的差异仅为直连四件套（接线分支、override、
   新增接口与 `org.fxrime.direct` 六文件、回退开关）加 appbuild 副本本地构建适配，
   无无关功能改动混入；direct-rime-proto 未提交三处（RimeSnapshot/direct_rime.h/
   rime_bridge.c）属同一语义修复（PASSTHROUGH 透传），去向明确。
2. 设备仍离线，冒烟不可执行：官方 adb 路径实测输出仅 `List of devices attached`。
3. 现存 APK（29,461,634 字节）为 PASSTHROUGH 缺口引入前构建：下一步不是直接冒烟，
   而是先同步常量、重建、再冒烟。

## 依据

- `reports/direct-rime-wiring-diff.md`：diff 实测逐项归属，结论接线差异收敛成立。
- `reports/direct-rime-device-check.md`：adb 实测原文、APK 路径/字节数/metadata。
- 未解释项 u-zwh611：appbuild 副本 `RimeSnapshot.kt` 缺 `DISPATCH_PASSTHROUGH`，
  而 `DirectRimeEngine.kt:231` 已引用。
- so/dex 既有核验结论沿引交接报告第 67–69 行，本轮未重验。

## 尚未确定的事项

- u-zwh611（开着）：同步 canonical 常量进 appbuild 副本后是否一次编过，
  影响重建是否可直接进行。
- 端到端输入、引擎启动、部署、代次门控、UI、配置、性能（交接第 109–117 行）
  均未验证，在有设备前一律标待核，不编数字。
- 真机回归零方案时应断言打不出字（AGENTS.md 门禁）。

## 待您决策

1. 是否授权把 canonical 三处 PASSTHROUGH 改动同步进 appbuild 副本并重建 APK？
   （只读复核已做完，重建是写操作，需明确授权。）
2. 是否授权提交 direct-rime-proto 的三处改动与交接报告？授权边界是什么
   （仅复核不写 / 可 commit 但不 push / 可重构建）？
3. 设备就绪后是否授权冒烟四步（`adb install`、`ime enable`、`ime set`、
   输入 `nihao` 验证候选＋`logcat | grep DirectRime`）？install 与 ime 切换为
   不可撤销动作，执行前需您逐项放行。
4. 本轮是否只到复核结论为止，后续等您接回设备再继续？
