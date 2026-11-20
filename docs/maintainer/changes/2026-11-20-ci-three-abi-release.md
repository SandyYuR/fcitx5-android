# 默认分支三 ABI 发布 · 2026-11-20

本记录对应代码默认分支 `fx-rime-only` 的提交 `c037dc143`（提交标题「构建(CI): 三 ABI 同时构建并按 ABI 命名发布资产」）。本次只改 CI、发布脚本与其说明，不改变输入法运行时代码。

## 现象与处理

| 现象 | 处理 |
| --- | --- |
| 默认分支 Commit CI 只构建签名 ARM64 Release，armv7 与 x86_64 用户只能自行构建 | `build_commit` 改为 `arm64-v8a`、`armeabi-v7a`、`x86_64` 三路矩阵，并启用 `fail-fast: true`；任一路失败会取消其余构建，`nightly_release` 不执行 |
| 三路产物都叫 `direct.apk`，下载后无法区分 ABI | 新增 `tools/foxy-perf/prepare-release-assets.py`，读取 APK 内的 `lib/<abi>/libdirect-rime.so` 与同目录 `build-metadata.json`，校验后统一复制为 `zhongzhoue-<versionName>-<abi>.apk`，并把版本号写入 `version.txt` |
| Release 描述缺少入口，更新记录需要手工维护 | 描述固定为文档站、用户手册、键盘布局编辑器三个链接，加 `## 更新记录`；记录由 `git log` 从上一个 semver 标签生成，最多 50 条 |
| 本机发布脚本只能出 ARM64 | `build-release.sh` 改为 `-PbuildABI="${BUILD_ABI:-arm64-v8a}"`，默认行为不变 |

## 实现边界

- 发布矩阵是三种 ABI，不是四种：引擎 SDK 仍支持 `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64`，但 `x86` 不进入默认分支发布。
- 全成或全不成：`prepare-release-assets.py` 要求三个期望 ABI 各恰好一个、ABI 唯一、`versionName` 一致且合法、`commitHash` 全部等于 `--expected-commit`；先写 `<output>.tmp`，全部通过后再原子替换 `--output`，失败不留下发布目录。
- 版本与标签来自构建元数据：Nightly 标签仍为 `nightly-<versionName>-<UTC 时间戳>`，`prerelease: true` 不变；不使用资产文件名反推版本。
- Release 更新记录只列非合并提交；没有上一个 semver 标签时退化为最近 30 条。
- 三个构建作业与 `unit_test` 全部成功后才会下载产物并发布；单元报告仍要求实际执行且失败、错误、跳过为零。

## 验证

| 检查 | 结果 |
| --- | --- |
| workflow YAML | `ruamel.yaml 0.19.1` 解析通过；矩阵为三 ABI，Release 步骤顺序与 `final-release/*.apk` 上传 glob 符合预期 |
| shell 语法 | 抽取 11 个 `run:` 块，替换表达式占位符后交 Git for Windows `bash -n`，0 个语法错误 |
| 发布脚本成功路径 | 合成三个 APK 与元数据后产出 `zhongzhoue-0.1.3-852-g577872dd-{arm64-v8a,armeabi-v7a,x86_64}.apk` 与 `version.txt`，表头打印 ABI、大小与 SHA-256 |
| 发布脚本失败路径 | 缺 ABI、重复 ABI、APK 混 ABI、提交不符、非法版本名均非零退出，且不留下输出目录 |
| 真实 CI | Commit CI [#570](https://github.com/SandyYuR/fcitx5-android/actions/runs/44691738106) 的全部作业均成功：三路签名 Release、`unit_test` 与 `nightly_release` 的 conclusion 都是 success |
| Nightly 发布 | 标签 `nightly-0.1.3-853-gc037dc14-20261120-070834`，`prerelease: true`；资产为 `zhongzhoue-0.1.3-853-gc037dc14-arm64-v8a.apk`（10,499,995 字节）、`zhongzhoue-0.1.3-853-gc037dc14-armeabi-v7a.apk`（10,161,702 字节）、`zhongzhoue-0.1.3-853-gc037dc14-x86_64.apk`（10,626,821 字节），描述开头为三个入口链接与自动生成的更新记录 |

文档同步范围：本记录；`docs/guide/installation.md` 补充三 ABI 资产命名；`docs/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md` 与 `docs/maintainer/engine-update.md` 更新 Commit CI 的 ABI 描述。

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
