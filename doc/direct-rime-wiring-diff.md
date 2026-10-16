# 直连接线差异核对（只读复核，2026-10-16）

对照基线：`direct-rime-proto/HANDOVER_2026-10-16.md` 第 88 行提交清单
（`InputEngineBackend.kt`、`FcitxDaemon.kt`、`Fcitx.kt`、`DirectRimeEngine.kt`、`app/build.gradle.kts`）
与第 163–176 行文件清单。

## 1. appbuild 与主仓库的差异（`diff` 实测）

| 文件 | 差异摘要 | 归属 |
|---|---|---|
| `…/daemon/FcitxDaemon.kt` | +3 import（BuildConfig、InputEngineBackend、DirectRimeEngine）；`realFcitx` 改为 `backend: InputEngineBackend` 惰性分支，默认直连、`FXRIME_ENGINE_FALLBACK` 时回退 fcitx5 | 直连 |
| `…/core/Fcitx.kt` | 类声明改实现 `InputEngineBackend`；`start/stop` 加 `override`（544/559 行） | 直连 |
| `…/core/InputEngineBackend.kt` | 新增（主仓库无此文件）：`FcitxAPI + FcitxLifecycleOwner` 最小接口 | 直连 |
| `…/org/fxrime/direct/` | 新增 6 文件（DirectRimeEngine、RimeBridge、RimeSnapshot、RimeThreadOwner、SessionGenerationGate、DeployKeyGate）；主仓库 `fx-rime-only` 无此目录 | 直连 |
| `app/build.gradle.kts` | 回退开关 `FXRIME_ENGINE_FALLBACK`（44–51 行）属直连；另有 `appIdBase org.fxrime / 后缀 .ime`、`NDK_PATH` 覆盖、`replace` 前缀三处，属 appbuild 副本本地构建适配（a21c1b2 说明 appbuild 系主仓库副本、不入库） | 开关直连，其余为副本本地适配 |

## 2. direct-rime-proto 未提交改动（`git status` 实测，master）

- 3 个 modified：`kotlin/…/RimeSnapshot.kt`（+`DISPATCH_PASSTHROUGH = 3`）、`native/direct_rime.h`（`dr_dispatch` 加 `DR_DISPATCH_PASSTHROUGH`）、`native/rime_bridge.c`（`process_key` 返回值回传，false 即透传）。三处是同一语义修复：librime 不消费的键必须落回编辑器（输入正确性红线，AGENTS.md §0.7.6；对标 `rimestate.cpp:238-243`）。
- 未跟踪：`HANDOVER_2026-10-16.md` + `build/` 下构建日志与核验目录（`apk-build*.log`、`kt-compile*.log`、`apk-check/` 等），均为过程产物，无源码。

## 3. 同步缺口（与预测不符，另记反常）

canonical `kotlin/…/RimeSnapshot.kt` 已有 `DISPATCH_PASSTHROUGH`（grep 计数 1），
但 appbuild 副本 `RimeSnapshot.kt` 仍只有 0–2（grep 计数 0），而 appbuild 的
`DirectRimeEngine.kt:231` 已引用 `DISPATCH_PASSTHROUGH`（grep 计数 1）。
即 appbuild 副本内部常量表与引用不一致；现存 APK（10-16 08:30，29,461,634 字节）
是该缺口引入前构建的，重建前须先把 canonical 常量同步进 appbuild 副本。

## 结论

接线差异收敛成立：除副本本地构建适配外，无无关功能改动混入；未提交三处属同一
直连修复。但 appbuild 副本存在 PASSTHROUGH 常量不同步（见 §3），已登记为未解释项。
