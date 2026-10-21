# 中州鹅直连版：合并与文档站交付

代码 [PR #4](https://github.com/SandyYuR/fcitx5-android/pull/4) 与文档站
[PR #7](https://github.com/SandyYuR/fcitx5-android/pull/7) 均按 **Rebase and merge** 准备。
本页记录合并准备，不代表已经合并或部署。新检查及安装包以 PR 当前提交为准。

## 分类后的提交

代码目标分支为 `fx-rime-only`，最新基线 `b6810693620e391fca1bfb498ae10b29efda9fc2`。
共享 MD3 页面与控件已在默认分支 `579b1632` 中；最新基线另含语音结束时提交最后一条 partial 的修复。
两者均由基线承接，重组后不重复交付它们的旧提交。
移除功能分支中的同步 merge 提交，按以下顺序保留五个线性提交：

| 顺序 | 分类 | 内容与边界 |
| --- | --- | --- |
| 1 | 直连后端与完整前端 | 单一 `rime-main` owner、JNI、完整键盘与候选、会话与首键、提交保护、部署重载、设置与标识；删除 Fcitx 原生后端和无用依赖，保留适配后的前端与单测 |
| 2 | 引擎与源码来源 | 将旧 `ef1a16a` 产物更新为官方 `1b3417d2` 加定制源码 `446f8584`，同步 prebuilt pin 和许可证版本 |
| 3 | 日志导出修复 | 连续导出快照、文件选择与保存状态、logcat 子进程生命周期及回归测试 |
| 4 | CI 与验证工具 | 四 ABI、签名发布、APK/链接审计、设备回归、性能诊断及相关历史工具 |
| 5 | 文档交付 | 用户与维护手册、来源和补丁说明、性能证据、合并顺序与归档记录 |

第一组同时修改调用方与原生后端，避免在中间提交保留无法编译的旧 API。
第二组单独记录引擎来源，第一组使用此前验证的 `prebuilt/1126616c`。
除承接默认分支 `b6810693` 的语音 partial 修复外，最终产品源码、构建脚本、测试、工具、
资源与子模块 pin 均与重组前 `5a036470` 相同。本轮额外内容修改限于 README 与 `docs/`。提交 SHA 和 Git 派生版本标签会改变，APK 必须标明实际构建提交。
机器可读范围与基线见 [rebase 交付摘要](direct-rime-rebase-ready-2026-10-21.json)。

文档目标分支为 `docs`，基线 `edcf536f85576ce3e2b241e1eb5059534a972f51`。
独立整理为四类线性提交：站点导航与安装迁移、用户与维护手册、实施与性能证据、站点 CI 与链接检查。
保留键盘、布局、主题、剪贴板和 MD3 说明；旧 Fcitx 手册与设想明确标为历史。
文档站仅收录紧凑报告和必要截图，原始性能证据通过固定提交引用。

## 历史与依赖的追溯

| 仓库与用途 | 永久保留的重组前入口 |
| --- | --- |
| 应用旧提交、报告及已验证 APK 来源 | [v0-archive-direct-rime-before-rebase-20261021](https://github.com/SandyYuR/fcitx5-android/tree/v0-archive-direct-rime-before-rebase-20261021)，`5a036470` |
| 文档站重组前版本 | [v0-archive-direct-rime-docs-before-rebase-20261021](https://github.com/SandyYuR/fcitx5-android/tree/v0-archive-direct-rime-docs-before-rebase-20261021)，`74e6972b` |
| 应用固定的 prebuilt gitlink | [v0-archive-rime-prebuilt-before-rebase-20261021](https://github.com/SandyYuR/prebuilt/tree/v0-archive-rime-prebuilt-before-rebase-20261021)，`d297ddee` |
| prebuilder 合并前维护版本 | [v0-archive-rime-prebuilder-before-rebase-20261021](https://github.com/SandyYuR/prebuilder/tree/v0-archive-rime-prebuilder-before-rebase-20261021)，`574d9667` |
| 现有静态库实际构建源码 | [v0-archive-librime-fork-build-20261021](https://github.com/SandyYuR/prebuilder/tree/v0-archive-librime-fork-build-20261021)，`97f3dfb7` |
| 定制引擎产品源码 | [zhongzhou-20261021.1](https://github.com/SandyYuR/librime/tree/zhongzhou-20261021.1)，`446f8584` |

Rebase and merge 会产生新的合入 SHA；归档标签保证已有 gitlink、来源清单、测试报告与旧 APK
不会因分支删除失去入口。不要把 prebuilt pin 随意换成合并后 HEAD，也不要把旧 APK 标成新提交构建。
独立 librime 产品分支已经按功能保留补丁提交，本轮不重写它；迁移详情见
[源码维护说明](DIRECT_RIME_SOURCE_MAINTENANCE_2026-10-21.md)。

## 重组前已验证的运行版本

下列固定记录均属于 `5a036470c951eaaddf2ab62599d68c5f68a4796a` 或其固定依赖，
用于保留现有运行证据。重组后的构建结果与检查状态应在 PR 当前说明中核对，不能套用下列 APK 摘要。

| 检查 | 固定记录 | 结果 |
| --- | --- | --- |
| PR CI | [39579198669](https://github.com/SandyYuR/fcitx5-android/actions/runs/39579198669) | 通过；JVM、四 ABI Debug、APK/静态链接审计 |
| Direct Rime CI | [39579198641](https://github.com/SandyYuR/fcitx5-android/actions/runs/39579198641) | 通过；原生安全与设备回归；完整前端 24 项，23 通过、1 条件跳过、0 失败 |
| Commit CI | [39579190118](https://github.com/SandyYuR/fcitx5-android/actions/runs/39579190118) | 通过；签名 ARM64 Release、ART profile、502 项 JVM 测试 |
| 引擎与源码契约 | [39579453735](https://github.com/SandyYuR/prebuilder/actions/runs/39579453735) | 通过；四 ABI、162 项原生测试与 5 项源码契约检查 |

条件跳过项需要额外万象模型夹具；本轮历史整理没有重测或宣称性能改善。
万象与 Foxy 历史对照的配置、测量方法与局限保持原记录。

[重组前签名 ARM64 Release](https://github.com/SandyYuR/fcitx5-android/actions/runs/39579190118/artifacts/12335863906)
解压后为 `direct.apk`。Actions 产物有保留期限，归档源码不等于永久托管 APK。

| 项目 | 已核验的重组前值 |
| --- | --- |
| 应用名 / 包名 | 中州鹅 / `org.fcitx.fcitx5.android.fx.rime` |
| 版本 | `0.1.3-723-g5a036470`，versionCode `112` |
| APK SHA256 | `3490d76f26e9e1fc8e02b1d0a19b44225960bf5504348170c046ef70caa60c05` |
| 证书 SHA256 | `ba43006e1bfa3f60f46b2a9056f593a3a61445bd2571319fb1869e49cc36b1fe` |
| 原生库 | `libdirect-rime.so`、`libc++_shared.so` |
| 引擎来源 | 官方 `1b3417d2` + `SandyYuR/librime@446f8584`，`prebuilt@d297ddee` |

已核对来源、签名、DEX、ELF/map 与静态输入。Fcitx 原生符号、PictureSelector 类为零；
原裁剪、扫码及 IME 组件保留。静态输入为 librime、glog、LevelDB、Lua、Marisa、OpenCC、yaml-cpp 和 NDK 运行库。
ART profile 包含 78 个应用方法、3 个 sendKey 入口及 13 个后端协程入口，最终 APK 的 8 处 Material 布局已核对。
旧 [release-ready 摘要](direct-rime-release-ready-2026-10-21.json) 属于更早的 `df591f78`，仅作历史证据。

## 合入与发布顺序

1. 审阅并用 **Rebase and merge** 合入 [prebuilder #1](https://github.com/SandyYuR/prebuilder/pull/1)。该 PR 已是线性源码、构建修复与来源检查提交；保留上述构建归档标签。
2. 用 **Rebase and merge** 合入 [prebuilt #1](https://github.com/SandyYuR/prebuilt/pull/1)。两类提交分别记录引擎升级和源码来源，应用仍固定归档可达的 `d297ddee`。
3. 确认代码 PR #4 当前 head 的 CI、签名产物与审阅结果后，用 **Rebase and merge** 合入 `fx-rime-only`。不选择 Create a merge commit。
4. 默认分支重新生成签名 Nightly，核对该次实际源码、包名、签名和发布说明。重写历史会改变 Git 派生版本名与 APK 摘要，以新产物为准。
5. 用 **Rebase and merge** 合入文档 PR #7，再等待 Docs 工作流部署并核对导航和手册链接。
6. 交付完成后删除已合并的功能分支；保留归档标签、固定源码和历史 CI。CI 记录不需要为了颜色清空。

本轮不执行以上合并，也不直接推送默认分支或站点发布分支。

## 手册与迁移范围

日常操作见[用户手册](../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md)，
构建、引擎来源、补丁与升级见[维护手册](DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md)，
历史与性能证据见[文档目录](../direct-rime.md)。

本版使用原默认分支包名；同签名时可覆盖旧 Fcitx 版，升级前导出完整备份。
旧测试包 `io.github.sandyyur.rime.direct` 与本版数据隔离，需要导出再恢复。
多个输入法应使用独立 Rime 工作目录，通过同步目录交换用户词典。

用户在万象 base v18.1.3 + LTS、全拼、上下文调频、简码、小字集配置下认可日用。
历史[触摸对照](DIRECT_RIME_SCREEN_PERF_2026-10-17.md)没有证明稳定整链路提速；
[Foxy 对照](DIRECT_RIME_FOXY_FIXES_2026-10-18.md)与
[真机冷路径片段](DIRECT_RIME_COLD_IO_AND_LOG_EXPORT_2026-10-19.md)各有测量边界。
广泛厂商输入框、快速发送与长期用户库仍需观察；完整 XKB Compose 与
按应用 `app_options` 等价未完成，LevelDB `reuse_logs` 尚无断电耐久性结论。
