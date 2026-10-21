# 中州鹅引擎源码仓库与维护流程迁移

本轮将现有引擎定制从 prebuilder 的补丁文件迁入独立 librime 复刻仓库，建立源码、构建、产物和输入法的固定引用链。
这次迁移不新增输入算法，不改变学习策略；核心源码与升级后已验证版本逐文件一致。

## 仓库职责

| 仓库 | 当前用途 |
| --- | --- |
| [SandyYuR/librime](https://github.com/SandyYuR/librime/tree/zhongzhou) | 核心定制源码的唯一维护来源；`zhongzhou` 为产品分支，默认 `master` 保持官方跟踪用途 |
| [SandyYuR/prebuilder](https://github.com/SandyYuR/prebuilder/pull/1) | 固定源码和插件、编译四 ABI、执行原生测试、生成来源清单 |
| [SandyYuR/prebuilt](https://github.com/SandyYuR/prebuilt/pull/1) | 保存审核后的静态库、头文件及逐 ABI 来源/依赖/产物摘要 |
| [SandyYuR/fcitx5-android](https://github.com/SandyYuR/fcitx5-android/pull/4) | 固定 prebuilt gitlink，编译直连 APK，执行输入法和设备回归 |
| [SandyYuR/fcitx5-rime](https://github.com/SandyYuR/fcitx5-rime) | 已归档只读；归档前后所有分支提交保持一致，保留旧 Fcitx 版本及历史对照的重建来源 |

已通过 API 核实旧仓库 `archived=true`、新仓库确实复刻自 `rime/librime`。
当前 APK 不构建或运行 fcitx5-rime；归档不会阻止历史版本只读获取源码。

## 已迁入的功能与来源

官方基线为 `1b3417d2b6a11b5e50e0e1fa9dc396056aa11dce`；
产品源码固定 `446f8584eb01ab8f72b3f0b970fda3919b1c1a55`。
9 项原补丁分别导入成 9 个提交，另有一项维护说明提交。

| 导入提交 | 原补丁 |
| --- | --- |
| `5d76b2c` | `librime.patch` |
| `a709248` | `librime-conditional-tabs-test.patch` |
| `8c59510` | `librime-perf-syllabifier-cache-repeated-QuerySpelling-iterat.patch` |
| `5bdfe15` | `librime-perf-deploy-compile-independent-dictionaries-in-para.patch` |
| `d4ae2ae` | `librime-userdict-cache.patch` |
| `d670754` | `librime-fix-mapped-file-remap.patch` |
| `bf1daaf` | `librime-defer-composition-refresh-on-delete.patch` |
| `407401d` | `librime-pr1232-rewrite-filter.patch` |
| `02cbb3e` | `librime-detect-modifications-fingerprint.patch` |

[源码清单](https://github.com/SandyYuR/librime/blob/446f8584eb01ab8f72b3f0b970fda3919b1c1a55/downstream/manifest.json)
保留原 prebuilder 提交、补丁 SHA256、导入提交与初次迁移树。
已比较 382 个受版本控制的文件，均与原有有序补丁快照逐字节相同；Git 子模块条目也保持一致。

音节选择器、忘词延迟刷新与缓存失效、用户词典缓存、并行部署、mmap、rewrite 和启动指纹均在源码内。
PR #1232 当前仍未进入官方 master，本分支独立保留其实现与原作者署名。
“已支持 rewriter”表示组件已编译、注册；方案需配置后才运行，不自动替代万象现有 Lua。

## 构建方式

prebuilder 的两条构建路径现在都验证同一固定源码，不再应用核心 librime 补丁。
旧 9 项补丁文件及清单已从活动构建分支删除，历史仍可通过原固定提交追溯。
Lua、octagram、predict 的固定版本及插件补丁继续单独维护。

`source-manifest.json` 分别记录 `librime_repository` / `librime_commit` 和官方 `upstream_commit`。
`core_patch_mode` 为 `committed-source`，`patches` 为空；`integrated_patches` 只表示已经迁入源码的历史，不再逐次应用。
验证器拒绝错误 gitlink、未提交的核心改动、仓库来源不匹配及缺失的音节/忘词接口，不会重置开发者修改。

日常 CI 默认执行 `librime-only`，不构建 Fcitx 宿主工具；旧整套依赖构建需手动选择 `everything`。
四 ABI 继续使用原固定 Boost、LevelDB 等依赖，不追逐插件移动分支。
[新流程 CI 39578163543](https://github.com/SandyYuR/prebuilder/actions/runs/39578163543) 已通过四 ABI、162 项引擎测试及 4 项来源契约测试。
已下载核验 ZIP、插件、依赖和逐 ABI 归档/头文件摘要；四份 `librime.a` 均与原 `0fee6176` 产物逐字节相同。
本次 prebuilder CI 提交为 `97f3dfb707f76e7897557579630b904dc6dbb688`，由 `v0-archive-librime-fork-build-20261021` 保留；分支 `3a69d883` 与其树一致。
主应用固定的新 prebuilt 为 `d297ddee183b437d0fd8c9bdf37eced6ec8d09cd`。
[机器可读摘要](direct-rime-source-maintenance-2026-10-21.json)保存源码和产物来源；APK、签名与应用回归以代码 PR #4 的实际构建源码记录为准。

## 今后如何更新

1. 从 librime 的 `zhongzhou` 新开工作分支。功能按独立提交维护，补充必要测试。
2. 更新官方引擎时获取并合并官方固定提交，处理冲突，更新 `downstream/manifest.json` 的 `upstream_commit`；初次导入的 `migration` 是历史记录，不重写。
3. 审查音节选择、忘词/重新学习、rewriter、部署和指纹，审核后合入 `zhongzhou`。保持已发布提交及版本标签可追溯，不强推替换发布历史。
4. 在 prebuilder 新分支更新 librime gitlink，执行 `librime-only`。核对源码/插件/依赖摘要、四 ABI 结果与原生测试。
5. 将验证产物提交到 prebuilt 工作分支，核对每 ABI 归档和头文件摘要，再让输入法更新其 `native/prebuilt` gitlink。
6. 验证 APK 原生依赖、签名、ART profile、实际键盘/候选/部署/用户词库；完成代码与文档 PR。

librime fork 不替代构建仓库或二进制产物仓库。安装后的 APK 不需要连接这些仓库，日常输入仍经 JNI 直接调用静态链接的 librime。

## 验证边界

源码迁移保持旧实现一致，不能据此宣称提升输入速度。本轮不重复旧 Fcitx/Foxy 性能对照。
源码契约四项测试、原生四 ABI 和 162 项引擎测试已通过；主应用回归与签名包在 PR #4 固定到实际构建源码。
