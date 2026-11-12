# 万象 EVO 方案排查 · 2026-11-12

本次排查模拟器导入 EVO 专用方案后无法输入中文的问题。现场已确认使用新版 EVO provider，且绑定的是用户导入的正确工作目录；API 探测通过不能证明该目录里的方案能够编译和输入。

## 固定来源与结论

| 对象 | 固定来源或摘要 |
| --- | --- |
| 输入法 | `feat/rime-engine-variants@68bfd17bf` |
| EVO 引擎 | `build/android-engine-variants@06947eedd6236318cbf9a2745e2d0cfc65011af7` |
| x86_64 引擎载荷 SHA-256 | `7169a87917c1d7ff31e779d4c4a7bff3c8bb3001d3b8a1d2adcbab8c6c1789a5` |
| 当时本地修复 x86_64 载荷 SHA-256 | `05e1efb06f29b04534bf727da4ea283bb7f73d30fcc74a51b9a09004844eedb5`，基于上述提交、当时尚未提交的开发构建 |
| 本地修复源码树 | `e65c9c5675b7f08abe740798ece8fd7f70f99174`，以产物 manifest 的 `source_tree` 为准 |
| 用户原包 | `rime-wanxiang-rime-wanxiang-for-librime-evo.zip`，26,007,243 字节 |
| 原包 SHA-256 | `13cc080d2dd2700cc1b3d2f27ea71fd767d40eb9628d4abfc4fb23987943af36` |
| 本次文档基线 | `doc-rime-engine-variants@ab1cc7596f2b46ddfb74b4bae33fe3addc9d0d26` |

引擎提交已改用仓库内置 `librime-lua` 和 Lua 5.4.9。[ARM64 引擎 CI](https://github.com/SandyYuR/librime-evo/actions/runs/43148194156)及 [Commit CI](https://github.com/SandyYuR/librime-evo/actions/runs/43148194155) 均成功；这只确认各自的构建与检查范围，不代表原包在设备上可用。

## 方案内容问题

`wanxiang_algebra.yaml` 的“直接辅助”“间接辅助”错误缩进在“首道双拼”下面，导致列表补丁无法应用。仅修正这处缩进仍不足以完整部署，静态核对还发现：

- 10 个缺失补丁路径：`english/万象双拼`、`english/乱序17`、`english/国标双拼`、`english/汉心龙`、`english/自然龙`、`english/蓝天双拼`、`mixed/万象双拼`、`mixed/乱序17`、`mixed/蓝天双拼`、`reverse/万象双拼`。
- 2 个引用名对应的词典文件缺失：`wanxiang_abbrev`、`wanxiang_mixedcode`。包内另有 `abbrev.dict.yaml` 和 `dicts/mixed.dict.yaml`，名称与引用不同。
- `aux_code.yaml` 已在原包中，本轮没有把它当作缺失依赖。

旧会话通过的 `runnable-v2` 夹具删掉主方案中 56 组非自然码配置、英文方案中 14 组非全拼配置，并添加词典别名等适配。它绕过了未启用分支的错误，不能作为完整原包已通过的证据。诊断用裁剪方案也不能标为完整修复。

## 引擎未传播配置编译失败

原引擎日志出现 `error applying patch`、`unresolved dependency`、`error building config: wanxiang.schema`，目标 `build/wanxiang.schema.yaml` 并未生成；维护通知却为 `deploy/success`，汇总为 `1 success, 0 failure`。

当时本地修复基于上述 `06947eed`，为 `ConfigData` 记录当前编译结果，让 `ConfigFileUpdate` 检查加载、Link 和输出插件结果，并让 `WorkspaceUpdate` 传播 `default.yaml` 的编译失败。失败仍保留部分解析数据供既有 BestEffort 调用读取，不以清空配置替代错误传播。改动位于 EVO 构建仓库的 `src/rime/config/`、`src/rime/lever/deployment_tasks.cc`，回归入口为 `test/config_build_result_test.cc`。这轮开发验证后先提交为 `63eba27359885a9404252e35699976acb765abb0`；同日复核、修正及历史整理见下节。

## 验证记录与剩余范围

| 验证对象 | 已观察结果 |
| --- | --- |
| 原包 + 已安装 `06947eed` provider | 唯一 `rime` AVD（API 35、x86_64）执行 1 项设备测试，失败：`输入 ni 后没有候选` |
| 仅修正缩进的完整包 | 同一设备独立测试，仍以相同断言失败 |
| 原引擎 + 原配置的独立 native 进程 | 复现配置编译失败却发出 success；要求 failure 的探针正确判失败 |
| 本地修复引擎 + 原配置 | 独立 native 进程收到 1 次 failure、0 次 success，错误传播探针通过 |
| `06947eed` provider + `diagnostic-zrm-source.zip` | 真实 JNI 设备测试 `OK (1 test)`，53.975 秒；`ni` 有候选并上屏，external → builtin → external 往返成功；只覆盖自然码／全拼裁剪范围 |
| 本地修复引擎 + 同一诊断包 | 独立 native 进程部署通过，`ni` 候选包含“你、泥、拟、腻、逆、奶茶”，空格与候选选择均上屏“你” |
| 配置源码测试 | 最终测试夹具在设备复跑 6 个 suite 共 19 项，0.163 秒；failures、errors、disabled、skipped 均为 0 |
| 完整原方案 | 原包及仅修缩进版本均未通过，不以诊断包结果替代 |

诊断包测试仍有 `.gram` 模型缺失日志，但能够产生中文候选和上屏，因此该缺失影响整句模型能力，不是本次完全没有候选的直接原因；不能据此声称模型能力已验证。

本机证据保存在 `D:/GitHub/fx2-rime/.verify/evo-input-20261112/`：`original-device-test.txt`、`indent-device-test.txt`、`native-old-original-v2.txt`、`native-new-original.txt`、`zrm-source-device-test.txt`、`native-new-zrm.txt`、`native-config-results-final.xml`；`profile-fix/manifest.json` 固定原包与诊断包摘要、缺失引用及保留的方案分支。尚未把这些本机日志发布为站点附件。剩余范围为完整配套方案修复及其真实中文输入回归。

## 同日复核、修正与提交整理

复核 `e8c3de04`、`06947eed`、`63eba273` 时，确认 `63eba273` 的 [Commit CI](https://github.com/SandyYuR/librime-evo/actions/runs/43155546172) 在 clang-format 23.1.6 门禁失败，Linux、macOS、Windows、Docker 后续任务全部跳过；独立的 [ARM64 引擎构建](https://github.com/SandyYuR/librime-evo/actions/runs/43155546109) 成功不能替代这些检查。另用实际工具复现了 Windows 编码、重复构建选错载荷和目录重叠问题。

本轮修正包括：

- 按仓库规则格式化 `config_component.h` 和新部署测试。非注释 C++ token 逐项一致，未改变部署修复语义。
- APK XML、清单和摘要文本显式使用 UTF-8，避免中文 Windows 默认 CP936 写出的 XML 与 UTF-8 声明不一致。
- 本地说明和 CI 以本次提交及 ABI 选择精确 `.so` 路径，不再从保留旧产物的目录任取第一个文件。
- 在任何输出写入或目录删除前拒绝重叠输入、输出路径，避免许可证源目录被删除。
- 恢复 32 位 API 23 构建的 Lua `io.popen`；仅在大文件定位接口保留必要的 API 条件。此修正不改变当前 ARM64、x86_64 的 `popen` 分支。

用户授权后，将三个提交整理为两个职责独立的提交：

| 整理前提交 | 整理后提交 | 内容 |
| --- | --- | --- |
| `e8c3de04c07f5fb1761ce46174f710f30b24b09f`、`06947eedd6236318cbf9a2745e2d0cfc65011af7` | [`2060bb22d3a7e7acf7972bdbb8f025e11608a6a2`](https://github.com/SandyYuR/librime-evo/commit/2060bb22d3a7e7acf7972bdbb8f025e11608a6a2) | Android 构建与内置 Lua 合并，纳入上述打包和兼容修正 |
| `63eba27359885a9404252e35699976acb765abb0` | [`0503782405a86b2831c96bf9fb7ae9f6dc4ade9c`](https://github.com/SandyYuR/librime-evo/commit/0503782405a86b2831c96bf9fb7ae9f6dc4ade9c) | 独立保留部署失败传递与 5 项回归，修正格式及输出失败测试的跨平台入口 |

共同基线仍为 `8727992e067de6d1ee77135646fdabff67a9cd5b`，增量提交数由 3 变为 2。旧 HEAD 的 tree 是 `e65c9c5675b7f08abe740798ece8fd7f70f99174`；修复新增内容后，第一次整理前记录与中间 HEAD `52ef44ffd5b26a33655062760fc7d76aa28ee00d` 的 tree 均为 `1f6db9ca704ef67fb7dd6bbafcdbc05973c51c2f`。因此本轮修复确实改变了内容，而提交归并本身未改变当时已修正的源码树。

中间提交的 [Commit CI 43158403532](https://github.com/SandyYuR/librime-evo/actions/runs/43158403532) 已通过正式 clang-format 23 门禁，但 Linux 与 Docker 的输出失败回归暴露测试入口差异：用目录阻塞输出文件时，`ConfigFileUpdate` 会先读取该目录，在 Linux 抛出流异常，尚未到待验证的输出失败处理。后续只把该测试改为直接调用 `ConfigBuilder`，仍断言输出插件失败使 `IsBuildSuccessful()` 为 false，没有修改生产代码。第二提交据此改写为上表 `05037824`，最终 tree 为 `8106361d089bdcfa7d4f347d67cd2c87fc23f939`；与首轮整理结果的差异仅为此测试修正。librime 版本仍为 `1.17.0`，provider 版本中嵌入的提交短 SHA 随新提交更新。

旧引用、映射和恢复依据保存在本机 `D:/GitHub/fx2-rime/.verify/evo-fix-20261112/history-rewrite.json`、`history-before.txt` 及 `history-before.bundle`；没有把本机 bundle 上传到仓库。最终以核对过的远端 SHA 执行 `--force-with-lease` 推送，`origin/build/android-engine-variants` 已确认指向 `05037824`，引擎工作树干净。

| 本轮验证 | 结果及范围 |
| --- | --- |
| Android Python 回归 | `python-tests.txt`：26 项实际执行并通过，无失败、错误或跳过 |
| Lua I/O 编译与链接 | `lua/results.json`：armv7、x86 的 API 23/24，以及 ARM64、x86_64 的 API 23 共 6 个目标通过实际 Lua 源码编译与 `popen/pclose` 链接；未作这些目标的新增设备执行 |
| 中文 Windows 资源封装 | `packaging/real-cp936-probe.txt`：默认编码 CP936、UTF-8 mode 关闭，真实 `aapt2 compile/link` 与 `zipalign` 通过；未调用签名 |
| 部署格式等效 | `format/token-equivalence.json`：两文件非注释 C++ token 与格式化前一致；此前 19 项设备测试的功能范围保持如上 |
| 最终部署回归 | `native-tests.xml`：修正输出失败测试入口后，在唯一 `rime` AVD 重跑 19 项，0.190 秒，failures、errors、disabled、skipped 均为 0 |
| 最终 Commit CI | `05037824` 的[运行 43159358427](https://github.com/SandyYuR/librime-evo/actions/runs/43159358427) 成功；lint 与 13 个平台 job 共 14 项全部成功，无失败或跳过。MinGW 按既有工作流只构建、未执行测试，不能算完整运行测试覆盖 |
| 最终 ARM64 引擎 CI | `05037824` 的[运行 43159358436](https://github.com/SandyYuR/librime-evo/actions/runs/43159358436) 成功，已生成 `.so` 和 provider APK 两项 artifact；中间提交的 [43158403553](https://github.com/SandyYuR/librime-evo/actions/runs/43158403553) 仅保留作阶段证据 |

本轮只修复引擎与构建链，没有补写用户原方案缺失的 10 处规则或替换其工作目录；完整方案、全部输入模式和语法模型仍需配套方案修复后另行验证。文档准备基线为 `doc-rime-engine-variants@ab1cc7596`；文档合入与站点部署由文档分支流水线独立确认。

## 输入法端的签名兼容补充与合入核对

输入法合入前核对发现，旧实现对 API 28–32 调用了 API 33 才提供的 `PackageManager.PackageInfoFlags` 重载；异常会被发现入口捕获，并把已安装 provider 过滤为不兼容。补丁 [`99a4cf8c9cd79321438fd82296ec0705f32e3b64`](https://github.com/SandyYuR/fcitx5-android/commit/99a4cf8c9cd79321438fd82296ec0705f32e3b64) 按 API 版本选择重载，API 28 以上继续只用当前签名生成授权摘要，API 27 及以下使用旧签名字段。测试夹具修正为 [`1d2d165abe678d0f131675af8ea9e431dd79b45b`](https://github.com/SandyYuR/fcitx5-android/commit/1d2d165abe678d0f131675af8ea9e431dd79b45b)。

`RimeEnginePluginsTest` 在 API 27、28、32、33 的 Robolectric 运行时实际执行 4 项，经过 provider 发现、ELF 校验、当前证书读取与 `requireCurrent` 再核对；历史证书刻意设置为不同值。与 `RimeEngineProbeServiceTest` 的 3 项合计 7 项，失败、错误、跳过均为零。结果位于本机 `.verify/ime-merge-20261112/` 的两份 JUnit XML；这些是 JVM 平台分支回归，不能写成四个系统版本的设备验收。

合入前的 Android lint 还发现新增 60 个 `rime_engine_*` 键缺少五种现有语言的翻译。随后补齐德语、西班牙语、日语、韩语、俄语各 60 条，共 300 条文案，分别放入各语言的 `rime_engine_strings.xml`；长授权文案继续区分隔离探测与正式运行的完整信任边界，删除／撤销操作明确保留工作目录与数据。XML 键集合、格式占位符和重复键检查通过，没有用 suppress 或 baseline 隐藏 `MissingTranslation`。完整本地 `lintFxDebug` 最终为 0 errors、738 warnings；本轮未扩大修改既有 warnings，结果保存在 `.verify/ime-merge-20261112/lint-local.txt`、`lint-summary.json` 和 `translations-check.json`。

代码 [PR #33](https://github.com/SandyYuR/fcitx5-android/pull/33) 已于 2026-11-12 14:15（北京时间）按用户要求使用 Squash merge 合入默认分支 `fx-rime-only`，合入提交为 [`88a664d46ba82712d5457d86c037629c478b042c`](https://github.com/SandyYuR/fcitx5-android/commit/88a664d46ba82712d5457d86c037629c478b042c)，标题为“功能(Rime): 支持安全导入、授权与无感切换引擎”。合入前默认分支基线为 `0380d70f16efb6a9946a164821d5cad7118ce4ff`；PR 最终审阅头为 `dcf90a5a3aba13bfa16dc358b07179442ed0569c`。审阅头与默认分支合入提交的 Git tree 均为 `a1340cb5587ba46d73853f553bce2176d12b06b9`，已核对内容完全一致，没有因压缩产生新 SHA 而重复重放补丁。

配套文档通过 [PR #34](https://github.com/SandyYuR/fcitx5-android/pull/34) 合入 `docs`，同样采用用户指定的 Squash merge。本文更新时文档 PR 仍待最终检查与合并；文档站发布由合入 `docs` 后的 Pages 流水线独立确认。

原功能头 `68bfd17bf` 的 [Commit CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/43143493465)、[Direct Rime CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/43143497799)及 [Material 设置 API 34/35/36](https://github.com/SandyYuR/fcitx5-android/actions/runs/43143501732) 已成功。随后 `1d2d165a` 的 [PR CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/43162901132)与 [Direct Rime CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/43162901374) 仅保留为翻译补齐前的阶段记录。

最终审阅头 `dcf90a5a` 的 [PR CI 43165493812](https://github.com/SandyYuR/fcitx5-android/actions/runs/43165493812) 和 [Direct Rime CI 43165493798](https://github.com/SandyYuR/fcitx5-android/actions/runs/43165493798) 共 6 项检查全部成功：完整 JVM 单元测试、Debug APK／Android lint／原生链接审计、prototype、Android 引擎 smoke、完整前端 ARM64 APK、完整前端触摸与引擎测试。核对依据为本机 `checks-merged-head.json`、`pr-merged.json` 和 `merge-result.json`；默认分支的合入后构建与发布状态另行查看，不将 PR 成功当作新 APK 已发布。文档 PR 的首轮 [Docs CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/43164108670) 已通过，本次更新继续运行本地五项门禁并等待最新文档检查。

此输入法合入阶段保持 EVO 仓库不变，也没有改写内置 SDK 锁或用户方案。

日常操作见[引擎变体故障排查](/features/rime-enhancements#能切换引擎但没有中文候选)，维护验证边界见[引擎更新流程](/maintainer/engine-update#外部引擎的方案部署排查)。
