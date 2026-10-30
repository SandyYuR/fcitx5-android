# librime 上游更新与 Android 定制兼容

当前源码维护已迁入独立 librime 复刻仓库，见[源码维护迁移](/maintainer/history/DIRECT_RIME_SOURCE_MAINTENANCE_2026-10-21)。本页固定构建与补丁路径保留为对应阶段的历史记录。

本轮把上游源码从 `ef1a16aa2c962bccf347b116443331f39d56e037` 更新至
`1b3417d2b6a11b5e50e0e1fa9dc396056aa11dce`，包含 23 个提交。
官方版本号仍为 1.17.0，项目使用 `1.17.0-1b3417d` 区分固定源码。
[上游差异](https://github.com/rime/librime/compare/ef1a16aa2c962bccf347b116443331f39d56e037...1b3417d2b6a11b5e50e0e1fa9dc396056aa11dce)。

## 合入内容

- 反查按照 translator 的分隔符切分音节。
- 用户库对极小 dee 数值的处理及相关告警修正。
- 内部合成按键不再产生多余的 thru 提交历史。
- 组件创建耗时统计，以及处理器、分段器、翻译器和过滤器按运行条件启用。
- chord 处理、路径的 C++20 兼容、配置/词典与构建细节修正。

现有万象配置继续使用原来的字符串组件列表；不会自动替用户改成条件组件，
也不以合入上游提交本身宣称获得了某个性能提升比例。

## 保留与适配的定制

保留 tabs、音节缓存、词典并行部署、用户词典缓存、映射文件修复、忘词保护、
rewrite 滤镜及冷启动配置指纹。配置指纹仍最后应用；共享顺序在
`prebuilder/patches/librime-series`，常规构建与定向构建读取同一清单。

上游将组件列表改为带条件和启用状态的条目，原来的 tabs / 主词典遍历不能直接照搬。
适配后两者只访问已启用的 translator，避免已禁用组件仍暴露 tabs。
新增 `DownstreamTabsAndDictionaryRespectConditions` 原生回归覆盖开、关和再次关闭。
反查保留上游分隔符修复，配置指纹适配新 UTF-8 路径接口；未清空用户词库或调整学习策略。

Lua、octagram、predict 插件保持原 gitlink，Boost 1.90、glog、LevelDB、Lua、
Marisa、OpenCC 和 yaml-cpp 仍使用原 `1126616c` 产物，依赖归档哈希逐个核对。
上游 Android CMake 仍允许这些依赖版本，本轮没有扩大成整套依赖升级。

## 源码与产物

| 层次 | 固定来源 |
| --- | --- |
| 官方 librime | `1b3417d2b6a11b5e50e0e1fa9dc396056aa11dce` |
| prebuilder 分支提交 | `bcfb896fd531caad2e95b8a580b45fb58956f171`，内容与验证合并树一致 |
| 实际 CI 构建提交 | `0af671f902680e00e4842d92ba49e373db0cf938`，由 `v0-archive-librime-build-20261021` 保留 |
| 新 prebuilt gitlink | `0fee61763cd40776ecaa5af3200f6b296d20262f` |
| 旧 prebuilt / 回退基线 | `1126616c3ec569617da772e30d7a97f6b2694172` |

构建与补丁：[prebuilder PR #1](https://github.com/SandyYuR/prebuilder/pull/1)。
四 ABI 产物：[prebuilt PR #1](https://github.com/SandyYuR/prebuilt/pull/1)。
每个 ABI 的 `librime/<ABI>/share/rime/source-manifest.json` 记录上游、插件、
有序补丁、工具链、依赖和归档/头文件摘要；根目录更新清单汇总四 ABI。
上游源码、prebuilder 的验证合并提交和 prebuilt 是不同层次，不能互相替代。

## 验证与测量边界

[prebuilder CI #39568210547](https://github.com/SandyYuR/prebuilder/actions/runs/39568210547)
已通过四 ABI 构建及 162 项主机原生测试，覆盖上游功能与已有定制。
已下载核对产物 ZIP、静态归档、公开头文件、插件与依赖摘要。
本地 ARM64 完整调试 APK、Android 测试 APK 及 JVM 测试构建通过；
引擎契约的 ASan/UBSan 检查通过（本地容器不支持 LSan 的 ptrace 环境，禁用了泄漏检查；CI 仍使用默认 sanitizer 设置）。

主应用接入后要求 `PR CI`、`Direct Rime CI`、`Commit CI` 验证四 ABI APK、
JVM、原生安全、ART profile、输入、候选、部署、日志与设置；具体运行和签名包
以[代码 PR #4](https://github.com/SandyYuR/fcitx5-android/pull/4)中固定到应用源码的记录为准。
[原生构建摘要](/maintainer/direct-rime-engine-update-2026-10-21.json)保留本次固定来源。

万象回归使用 base v18.1.3、原用户配置及 SHA256 为
`1849e5e4acbdbfaf41fceeb86b739d5d0c85d574bd8a688562cba1e6beb78488`
的 LTS 模型。上游同名资产已替换，不能静默换模型来延续历史数据。
冷路径诊断可通过 `model_cache_commit` 提供 prebuilt 仓库中临时缓存的固定提交，
恢复已校验的公开模型；只缓存官方模型及其 CC BY 4.0 许可证，不包含个人词库。
校验原模型摘要后才准备测试方案，测试后删除临时分支。
该诊断验证模型实际映射、选词提交及 400 条独立数据库记录关闭/重开后的内容；
它不替代个人长期词库或厂商输入框验收，也没有重跑旧 Fcitx/Foxy 对照。

## 应用回归记录

固定应用源码 `8acf23ef90312f301a714f336e8f12134231c9fe`，prebuilt `0fee6176`。
后续纯文档提交不标作该 APK 的构建来源。

| 检查 | 固定记录及结果 |
| --- | --- |
| 四 ABI APK / JVM / 依赖审计 | [39571175644](https://github.com/SandyYuR/fcitx5-android/actions/runs/39571175644)，通过 |
| 原生安全 / 恢复 / 独立引擎 / 完整前端 | [39571175677](https://github.com/SandyYuR/fcitx5-android/actions/runs/39571175677)，通过；完整前端 24 项中 23 通过、1 个大模型条件跳过、0 失败 |
| 独立万象大模型诊断 | [39571190659](https://github.com/SandyYuR/fcitx5-android/actions/runs/39571190659)，1 项通过、0 跳过；覆盖前述默认未传大模型而跳过的项目 |
| 主应用 JVM | [39571165367](https://github.com/SandyYuR/fcitx5-android/actions/runs/39571165367)，502 项通过 |

万象报告核对了模型实际映射、6 轮均有候选提交，以及 400 条独立记录关闭/重开后的内容。
该 Debug 模拟器后端的 `select_ms` 为 2.325–6.372 ms，每轮提交 2 字符；
这是后端操作完成时间，不能替代真机触摸/画面延迟或升级前后性能对照。
[原始报告与夹具摘要](https://github.com/SandyYuR/fcitx5-android/actions/runs/39571190659/artifacts/12332771882)
已下载核对 ZIP 摘要；临时公开模型缓存分支在测试后删除。
签名 Release 同属运行 `39571165367`，最终包摘要和证书核验记录以 PR #4 为准。

## 更新、回退与数据

主应用固定新 gitlink，不会追踪移动分支。今后可在 prebuilder 新分支执行
`librime-only` 工作流，复核补丁和插件、下载验证四 ABI，再更新主仓库 gitlink 与许可证版本。
这条定向构建路径不需要编译 Fcitx 宿主工具。

升级前备份；首次启用新版可能需要部署，等待完成后输入。
如需回退，应在工作分支恢复旧 gitlink 与对应许可证版本并重新构建，先保存用户数据，
不要通过删除用户词库、上下文库或锁文件处理兼容问题。
