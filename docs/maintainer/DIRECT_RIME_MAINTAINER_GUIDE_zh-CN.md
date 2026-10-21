# 中州鹅直连版维护手册

本手册对应当前直连源码，主应用为 `:app`，包名 `org.fcitx.fcitx5.android.fx.rime`。
这是默认分支原包名；旧直连测试包 `io.github.sandyyur.rime.direct` 的数据须按用户手册迁移。
合并前工作分支为 `feat/direct-rime-prototype`，PR #4 的目标为 `fx-rime-only`；合并后在新的功能分支继续开发。
当前合并基线、CI 和文档站流程见[交付记录](DIRECT_RIME_RELEASE_READY_2026-10-21.md)。
历史 Fcitx 版固定归档为 `v0-archive-fcitx-before-direct-20261019`，不再用默认分支名称指代旧引擎。

## 构建与安装

需要 Java 17、Android SDK 36、NDK `28.0.13004108`、CMake `3.31.6`，最低 Android 版本为 6.0/API 23。
工具版本由 `build-logic` 固定，首次构建需要下载依赖。

```sh
# 在当前直连源码的功能分支执行；不要在默认分支直接试验。
./prepare_personal_build.sh
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug :app:testFxDebugUnitTest
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
python3 tools/audit-direct-rime-link.py app/build/outputs/apk/fx/debug/*.apk
```

产物在 `app/build/outputs/apk/fx/debug/`；Release 使用 `:app:assembleFxRelease`，产物在 `fx/release/`。
安装时按 `output-metadata.json` 选择当前构建文件，避免把目录里旧提交/其他 ABI 的 APK 当作新产物。
Release 需要自己的签名才能分发；本地校验用的 `release-unsigned.apk` 不能直接安装。
保管签名密钥，勿提交到仓库；覆盖升级必须使用相同包名与签名。

普通构建已固定为直连。`-PdirectRimeFrontend=true` 仅兼容旧脚本，false 会报错。
可选 `-PdirectRimePrototype=true :direct-rime:assembleDebug` 构建最小引擎诊断 APK，
包名为 `org.fcitx.fcitx5.android.fx.rime.lab`，名称“中州鹅引擎诊断”，与主应用独立安装；
它不是带完整键盘的日常产品。

## 输入事件链路

```text
Android 软键盘/物理按键
  → 输入服务串行队列与会话检查
  → DirectRimeBackend
  → rime-main owner 线程
  → Native JNI → librime session
  → 一次快照返回 commit、preedit、候选、方案与状态
  → Kotlin 事件 → 原 Android 键盘/候选界面与 InputConnection
```

librime 的 session 与操作由单一 owner 串行管理。输入会话变更后，旧任务/旧提交不再交付新输入框。
提交只消费一次，UI 刷新与短暂模式提示不重放 commit。
启动/绑定期间的首键可在原队列中短时等待；每次 Android 编辑器仍有独立代次。
同应用普通文本绑定复用原生 session，跨应用与敏感输入边界重建；
具体接收限制、状态清理和诊断标签见[响应性改进](DIRECT_RIME_RESPONSIVENESS_2026-10-18.md)。
非输入框页面不因窗口显示/focus 创建会话；真实按键及主动模式/方案操作才请求启用。
展开候选按最多 8 项分批进入 owner，批间允许按键排队，取消/版本失效不返回混合页面。
Ready/restart 重绑也遵循编辑器需求；同代次已由真实首键提出的需求不会被 TYPE_NULL 恢复撤销。
原生诊断进一步分开 core、commit/context/status/tabs 读取，并记录 core 线程 CPU、缺页及上下文切换增量。
提交日志的 request_result 只表示 InputConnection 请求结果，不是接收端上屏确认；见
[Foxy 审查后的修复](DIRECT_RIME_FOXY_FIXES_2026-10-18.md)。
交互同步调用临时使用 DISPLAY 线程优先级并 finally 恢复；core 的 IO 诊断按
open/read/write/sync/stat 分项，不能覆盖全部 mmap/后台等待。首次选词的同步刷盘实测及
日志保存的快照/子进程生命周期修复见[冷路径与日志导出](DIRECT_RIME_COLD_IO_AND_LOG_EXPORT_2026-10-19.md)。
界面读取缓存直接在调用线程执行；native 运算仍在 owner。首次候选点选的原 Fcitx 对照、
编辑器排版边界及 `select_queue` / `commit` 新计时见
[首次点选报告](DIRECT_RIME_CANDIDATE_TAP_2026-10-18.md)。
模式/方案提示通过输入面板的辅助文本显示，1500 ms 后只清理提示；新输入和新会话会提前清理。

## librime 从哪里来

| 层次 | 仓库及固定版本 |
| --- | --- |
| 上游引擎 | [rime/librime](https://github.com/rime/librime)，官方基线 `1b3417d2b6a11b5e50e0e1fa9dc396056aa11dce` |
| 中州鹅定制源码 | [SandyYuR/librime](https://github.com/SandyYuR/librime/tree/zhongzhou)，固定 `446f8584eb01ab8f72b3f0b970fda3919b1c1a55`，元数据 `1.17.0-1b3417d+zhongzhou.446f858` |
| Android 编译与插件准备 | [SandyYuR/prebuilder](https://github.com/SandyYuR/prebuilder)，本次 CI 提交 `97f3dfb707f76e7897557579630b904dc6dbb688`，由 `v0-archive-librime-fork-build-20261021` 保留 |
| 四 ABI 预编译产物 | [SandyYuR/prebuilt](https://github.com/SandyYuR/prebuilt)，主仓库 gitlink `d297ddee183b437d0fd8c9bdf37eced6ec8d09cd` |
| 公共预设 | [rime/rime-prelude](https://github.com/rime/rime-prelude)，`082425ea0684bca36474415d4a0e8db9b016487e` |

上游升级历史见[引擎更新记录](DIRECT_RIME_ENGINE_UPDATE_2026-10-21.md)；当前仓库职责、9 项功能导入与验证见[源码维护迁移](DIRECT_RIME_SOURCE_MAINTENANCE_2026-10-21.md)。

`native/prebuilt/librime/<ABI>/lib/librime.a` 静态链接入 `libdirect-rime.so`。
主应用自己的桥接位于 `direct-rime/src/main/cpp/`，前端适配位于 `DirectRimeBackend.kt`。
APK 原生库白名单只有 `libdirect-rime.so` 与 `libc++_shared.so`。
`leveldb-open.cpp` 在本 DSO 内通过公共 DB::Open 接口开启 reuse_logs。
本次只更新 librime，LevelDB 等依赖仍来自原 prebuilt 基线。更新 LevelDB 时应同步检查公共 Options 字段、每 ABI Open 导出和
`tools/input-perf/test-leveldb-open.py` 的恢复/显式同步回归，随后跑 Android 存储与万象验证。

共享 prebuilt 仓库也存有其他引擎的产物，主应用不链接它们。
prebuilder 的日常 CI 默认只编译 librime；手动选择历史 `everything` 流水线仍可能编译 Fcitx 宿主工具，用于其他组件的字典构建；
这与 librime 目标本身、当前 APK 的构建依赖和运行时依赖是不同层次。

## 更新 librime

1. 从 **SandyYuR/librime 的 `zhongzhou` 分支**新开功能或升级分支；核心补丁以独立源码提交维护，不再修改 prebuilder 中的旧补丁文件。
2. 更新官方引擎时获取并合并官方固定提交，处理冲突，更新 `downstream/manifest.json` 的 `upstream_commit`；初次导入的 `migration` 保留历史。审阅 API、插件兼容性和测试，通过后合入产品分支。
3. 在 **prebuilder 新分支**将 librime gitlink 更新到定制源码的确切提交。`scripts/verify-librime-source.py` 核验来源和接口；Lua、octagram、predict 仍按固定插件版本及插件补丁准备。
4. 执行默认 `librime-only` CI，复用固定依赖，运行四 ABI 和主机原生回归。核心 `patches=[]`，`integrated_patches` 只记录已迁入源码的历史，不能再应用一遍。
5. 将核验后的产物提交到 **prebuilt 新分支**，核对逐 ABI `share/rime/source-manifest.json` 中定制源码、官方基线、插件、依赖、归档和头文件摘要；在主应用更新 `native/prebuilt` 固定 gitlink。
6. 同步许可证中的引擎版本和本手册版本表，完成 JVM、原生安全、APK 依赖和设备输入回归，审核代码与文档 PR；实际方案继续验收上下文调频、简码、小字集、注释、音节、忘词和同步。

升级本应用不会自动跟踪 librime master；固定提交保证应用构建能复现。
详细依赖和历史改动见[迁移审计](DIRECT_RIME_AUDIT_2026-10-17.md)。

## 验证与 CI

本地 JVM 与构建检查：

```sh
./gradlew -PbuildABI=arm64-v8a :app:testFxDebugUnitTest :app:assembleFxDebug :app:assembleFxDebugAndroidTest :app:assembleFxRelease
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
```

完整前端设备测试需要可用的 Android 模拟器/设备；一次性 KVM 模拟器优先。
测试会创建临时 Rime profile 并切换系统输入法，完成后恢复原选择。

```sh
./gradlew -PbuildABI=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.core.DirectRimeFrontendTest \
  :app:connectedFxDebugAndroidTest
```

CI 的 `Direct Rime CI` 工作流覆盖 native ASan/UBSan、独立引擎 smoke、
完整前端构建以及实际触摸、模式提示、方案、设置、导入与布局编辑回归。
ATD 镜像的触屏测试需启用绘制；[测试脚本](https://github.com/SandyYuR/fcitx5-android/blob/5a036470c951eaaddf2ab62599d68c5f68a4796a/tools/test-direct-rime-frontend.sh)已处理。
APK 检查器核对动态依赖、资源、DEX 类定义及页对齐：64 位 16 KiB，32 位 4 KiB。
静态审计另外匹配 APK ELF build ID，并核对链接结束时记录的 map/未剥离库 SHA256，
只允许固定七组 Rime 归档及 NDK 支持库；未知归档或 Fcitx C++ 符号直接失败。
四 ABI PR artifact 和签名 Release 都附带 `native-link-audit.json`。
最后一轮库/组件清理及保留依据见[最终审阅](DIRECT_RIME_FINAL_REVIEW_2026-10-19.md)。

`PR CI` 构建四 ABI Debug 并运行 JVM 单测。所有 PR 设备/构建检查使用 GitHub 的临时合并提交，
同时验证目标分支的后续修复。`Commit CI` 在默认分支及本次合并分支构建签名 ARM64 Release、
检查包内 ART profile 并运行 JVM；默认分支的 Nightly 必须同时等待构建和单测成功。
签名密钥缺失时日用发布失败，不回退到临时测试证书。签名 Release 与源码、APK SHA、profile
审计一起保存在 `direct-rime-frontend-arm64-release` artifact；历史 Debug 包不能当作签名升级包。
`v0-archive-*` 标签仅保存开发历史，不发布 APK，也不参与 `versionName` 的 `git describe`。

Release 的 vital lint 通过不代表全量 `lintFxDebug` 没有历史问题。
首轮全量 lint 的原有错误记录保留在[前端接入文档](DIRECT_RIME_FRONTEND_2026-10-16.md)。

## 万象性能测试与分支

[性能审计](DIRECT_RIME_AUDIT_2026-10-17.md)使用含 Fcitx 的默认分支历史提交 `a714feff` 作对照，
与直连版使用相同 librime、方案、模型和回放程序。用户配置为 base、全拼、上下文调频、简码、小字集。
结果是模拟器中 API 到事件观察器的耗时，不包含触屏到屏幕显示完成，也不代表所有真机和长期用户库。

测试工具在 `tools/input-perf/`，数据在 `docs/maintainer/perf/`。
`rime-rebind-perf.yml` 定向比较此次修改前后的直连版本，覆盖万象模型实际映射、
第一次显示及后续重绑的立即输入/候选点击；不是默认分支 Fcitx 对照，也不测进程冷启动。
该流程仅手动运行；性能实验不参与普通提交的自动构建。
历史三个性能工作流保留手动触发：`wanxiang-perf.yml` 比较两个历史直连版本，
`fcitx-direct-perf.yml` 比较历史 Fcitx 与当前直连，`fcitx-direct-profile.yml` 追踪固定 APK。
原来四个 `perf/` 分支只是运行这些实验的临时入口，提交已进入工作分支，
用途和恢复 SHA 见[工作区整理记录](DIRECT_RIME_WORKSPACE_CLEANUP_2026-10-17.md)。

固定 APK 的追踪任务依赖指定比较运行的 4 个 APK artifact；artifact 有保存期限。
重新使用时先运行比较任务产生新 artifact，再把新的 run ID 传入追踪任务。
历史 Actions 运行保留审计价值，不能只因显示红色就删除；不需要为整理列表重跑所有旧实验。

[触摸与录屏报告](DIRECT_RIME_SCREEN_PERF_2026-10-17.md)使用用户上传的画面差分工具和 YAML，
比较默认分支 `cecea494` 与直连产品 `cfd0ddc4`，固定万象 v18.1.3 base + LTS。
工具在 `tools/screen-perf/`，专用中立编辑器、同一份 instrumentation 和独立色块对时用于两版，
按真实键位触摸并比较完整上屏文本。完整矩阵有三轮常速、快速和抖动场景，共 4,752 次触摸。

```sh
gh workflow run wanxiang-screen-perf.yml --ref YOUR_DIRECT_RIME_BRANCH -f pilot=false
```

可先用 `pilot=true` 校准；`reuse_run=<run ID>` 只复用该工作流的 `screen-comparison-apks`，
并核对 instrumentation 源码哈希。这个 APK artifact 保存 3 天，不能当作永久下载入口。
录屏及完整结果保存 14 天，报告中的压缩差分、时间戳、原始事件及摘要随分支永久归档。
LTS 标签可变；下载哈希不一致时应明确选择并记录新的模型版本，不能跳过检查。
2026-10-19 上游已替换同名 LTS 资产；历史 screen/cold 探针仍固定 10-17 的模型 SHA
`1849e5e4acbdbfaf41fceeb86b739d5d0c85d574bd8a688562cba1e6beb78488`，直接下载当前同名文件会校验失败。
复现历史结果需提供匹配该哈希的缓存；若改用新模型，应另建测试清单和结果，保留旧数据的固定来源。
当前回放用一次性 KVM 模拟器，勿在个人日用输入法数据上运行：它会重置专用测试档案及学习库。

`tools/foxy-perf/` 另提供两款未修改 ARM64 Release APK 的外部触摸驱动。
Foxy 使用上传的原包；两边都经 API 30 Google APIs 模拟器的 ARM64 翻译桥运行。
方法、编译/签名来源、配置核验及缓存边界见[工具说明](https://github.com/SandyYuR/fcitx5-android/blob/5a036470c951eaaddf2ab62599d68c5f68a4796a/tools/foxy-perf/README.md)。
复用 Release 必须验证产品目录与保存的源码哈希一致，不能把旧 APK 标为新提交。
Release 保留 R8 和 ART profile 编译/打包，使用 ProfileInstaller；检查二进制 profile
可运行 `python3 tools/foxy-perf/apk_profiles.py PATH.apk`。文本规则数量不等于实际编译收益。
原 `direct-rime-release.yml` 已收拢进 `Commit CI`，无需 Foxy 测试文件。
`direct-rime-cold-probe.yml` 与其他性能工作流一样仅手动运行；测试副本的 Lua marker
使用 ERROR 级别以通过 min_log_level=2，不代表产品发生异常，不进入用户 APK。

多个输入法可以共享同步目录，但不能同时使用同一 Rime 工作数据库。一次共用外部工作目录的环境中
曾出现用户词不显示，停止其他输入法并重启本应用后已恢复；处理步骤见用户手册。不要通过删除数据库
或绕过 LevelDB 锁来修复，也不要把这次已恢复的环境冲突记录成引擎学习功能回归。

## 设置中保留的兼容内容

- 中州韵设置的 `FcitxPreferenceFragment`、RawConfig/INI 是现有 Android 配置界面的数据适配，
  直接接到 Rime，不会启动 Fcitx Core。
- 旧布局/宏的动作 ID、`fcitx://` 和旧导航路由仍需兼容；旧全局选项、引擎列表和附加组件列表路由
  都转到唯一的中州韵设置，界面不再展示重复入口。
- Android 的服务、IPC 包名和前端类名暂留历史命名，仍承担输入服务和应用工具功能。
  更名需要单独处理保存状态、布局、备份和外部调用兼容。
- “关于”的 Fcitx5 for Android / fxliang 致谢、作者署名和许可证保留。
  当前设置不再提供 Fcitx 原生附加组件管理或旧 Fcitx 引擎快捷键配置。

## 仍待完成

完整 XKB Compose 行为、按应用 `app_options` 等价、配置模型进一步收口，
以及广泛厂商输入框、长句、快速连击和长期用户词库回归仍需继续验证。
这些不能因删除旧后端或清理名称而标记为完成。
