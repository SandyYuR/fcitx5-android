# 中州鹅直连版：合并与文档站交付
上游引擎已另行升级，以下签名包与 CI 属于升级前基线；最新引擎来源和验证见[引擎更新记录](DIRECT_RIME_ENGINE_UPDATE_2026-10-21.md)及代码 PR #4。

本页是当前合并入口；10 月 16–20 日的报告继续作为历史证据保留。
代码 PR 为 [#4](https://github.com/SandyYuR/fcitx5-android/pull/4)，
`feat/direct-rime-prototype` → `fx-rime-only`。文档站通过独立 PR 合入 `docs` 分支。
这里记录准备状态，不代表代码或站点已经合并发布。

## 合并范围

中州鹅保留完整 Android 键盘、候选、布局、宏、主题、剪贴板、语音与设置，
由单一 `rime-main` owner 经 JNI 直接调用 librime。Fcitx 原生 Core、
fcitx5-rime、AndroidFrontend 及旧构建工程已从本 PR 删除。
前端历史类名、布局键名、IPC 标识和许可证署名继续保留，详见
[维护手册](DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md)。

默认分支当前基线为 `579b1632116b7523ed969dc8b37029e9bed73bd2`。
共享 MD3 页面、控件、对话框与菜单焦点处理已在该提交独立落入默认分支，
不再作为本 PR 独有功能交付。`df591f78` 已同步该基线并解决冲突，
保留直连会话管理、日志导出修复及专属导入页面。

本轮仅整理文档和 PR，未改动已验证的应用代码，也未重写旧实验历史。
建议以 **Create a merge commit** 合并代码 PR，保留已有来源和验证引用；
旧 77 个开发提交已收拢，原历史由归档标签保留。

## 当前构建与验证

验证源码：`df591f782bc07248b1917fe6544a711e917ec022`。
本页后续的文档提交不应被标作这个 APK 的构建来源。

| 检查 | 固定记录 | 结果 |
| --- | --- | --- |
| PR CI | [39555900504](https://github.com/SandyYuR/fcitx5-android/actions/runs/39555900504) | 通过；JVM、四 ABI Debug、APK 与静态链接审计 |
| Direct Rime CI | [39555900547](https://github.com/SandyYuR/fcitx5-android/actions/runs/39555900547) | 通过；原生安全、LevelDB 恢复、独立引擎和完整前端设备回归 |
| Commit CI | [39555897343](https://github.com/SandyYuR/fcitx5-android/actions/runs/39555897343) | 通过；仓库证书签名 ARM64 Release、ART profile、502 项 JVM 测试 |
| 完整前端设备报告 | [12326161067](https://github.com/SandyYuR/fcitx5-android/actions/runs/39555900547/artifacts/12326161067) | 24 项，23 通过、1 条件跳过、0 失败；22 张 MD3 浅深色截图 |

条件跳过项为需要另行提供大模型夹具的万象诊断；不等于完成了本轮万象性能重测。
本次复核现有测试和产物，没有重复运行旧 Fcitx/Foxy 性能对照。

[下载签名 ARM64 Release](https://github.com/SandyYuR/fcitx5-android/actions/runs/39555897343/artifacts/12326146738)，
解压后安装 `direct.apk`。Actions 产物有保留期限；过期后按该源码自行构建，
或使用合并后 Releases 中由默认分支新生成的 Nightly，不能把两者视为同一个 APK。

| 项目 | 已核验值 |
| --- | --- |
| 应用名 / 包名 | 中州鹅 / `org.fcitx.fcitx5.android.fx.rime` |
| 版本 | `0.1.3-719-gdf591f78`，versionCode `112` |
| APK SHA256 | `f7629e416473d4a23db11072f6ad529afe81cb5b78b96f70ec108cb734bf1b64` |
| 证书 SHA256 | `ba43006e1bfa3f60f46b2a9056f593a3a61445bd2571319fb1869e49cc36b1fe` |
| 原生库 | `libdirect-rime.so`、`libc++_shared.so` |
| 静态输入 | librime、glog、LevelDB、Lua、Marisa、OpenCC、yaml-cpp，另含 NDK 运行库 |

已核对 ZIP 摘要、`source.txt`、构建元数据、APK 摘要、签名、包名及原生 ELF/map。
Fcitx 原生符号和 PictureSelector 类为零；原自定义裁剪、扫码和 IME 组件保留。
ART profile 与实际 DEX 对齐：78 个应用方法，含 3 个 sendKey 入口和 13 个后端协程入口。
AAPT 核对最终签名 APK 的 8 处实际布局，更新器四页与日志页均为 MaterialToolbar。
机器可读摘要见 [release-ready-2026-10-21.json](direct-rime-release-ready-2026-10-21.json)。

## 文档与站点

日常操作以[用户手册](../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md)为准，
构建、librime 来源和升级以[维护手册](DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md)为准。
[文档目录](../direct-rime.md)收录当前入口、性能证据与实施历史。

文档站更新从当前 `docs` 分支单独准备：保留键盘、布局、主题、剪贴板和 MD3 说明，
更新品牌、安装迁移、Rime 设置与构建来源，接入当前手册和维护报告。
旧 Fcitx 手册和设想保留历史入口，避免把旧“全局选项”“附加组件”和适配层说明
当作直连版操作步骤。站点 PR 要求 `npm ci`、VitePress 构建与内部链接检查通过。

建议合入顺序：

1. 审阅代码 PR #4，确认基线、验证记录和迁移说明，合入 `fx-rime-only`。
2. 默认分支 CI 重新构建签名 Nightly，核对该次发布的源码、包名和签名。
3. 合入面向 `docs` 的文档 PR，等待 Docs 工作流部署，再核对站点导航与手册入口。
4. 两边确认交付后删除对应功能分支；归档标签和历史 CI 记录继续保留。

本轮不提前合并代码，也不直接推送 `docs` 发布站点。

## 迁移与已知范围

本版使用原默认分支包名；同签名时可覆盖旧 Fcitx 版，升级前导出完整备份。
旧直连测试包 `io.github.sandyyur.rime.direct` 与本版数据隔离，需先导出再恢复。
多个输入法分别使用独立 Rime 工作目录，通过同步目录交换用户词典。

用户在万象 base v18.1.3 + LTS、全拼、上下文调频、简码、小字集配置下认可日用。
历史[触摸对照](DIRECT_RIME_SCREEN_PERF_2026-10-17.md)未证明稳定整链路提速；
[Foxy 对照](DIRECT_RIME_FOXY_FIXES_2026-10-18.md)和
[真机冷路径片段](DIRECT_RIME_COLD_IO_AND_LOG_EXPORT_2026-10-19.md)各有测量边界。
广泛厂商输入框、快速发送和长期用户库仍需观察；完整 XKB Compose 与
按应用 `app_options` 等价未完成，LevelDB `reuse_logs` 尚无断电耐久性结论。
