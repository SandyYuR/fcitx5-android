# 开发分支整合与源码归档（2026-11-12）

本次按用户要求，将可维护的产品改动和通用验证整理进一个面向默认分支的代码 PR，其余临时实验分支先归档再清理。文档继续在 docs 分支维护，代码分支不增加 docs 目录。

| 保留入口 | 用途 |
| --- | --- |
| [PR #32](https://github.com/SandyYuR/fcitx5-android/pull/32) / `fix/first-candidate-latency-20261112` | 候选构造、按键发布及常规回归/归因工具，目标 `fx-rime-only` |
| [PR #31](https://github.com/SandyYuR/fcitx5-android/pull/31) / `report/wanxiang-retest-20261108` | 历史与新增性能证据、架构与审查说明，目标 `docs` |

代码 PR 归为三个线性提交：候选渲染及对应回归、按键发布作用域、常规测试与通用工具。重整前后应用、SDK 及构建配置源树一致，版本哈希与 Git 距离会变化；历史实测 APK 继续按原始 SHA 记录，不冒充重整后包已重新测过。

## 四个实验分支的处理

| 原分支 | 原 tip | 处理理由 |
| --- | --- | --- |
| `perf/wanxiang-latest-20261108` | `178d5128` | 固定日期两版万象基准，仅保留作证据复现 |
| `perf/wanxiang-current-20261111` | `1c050540` | 三版测量与异常核验工具，固定输入及产物，归档 |
| `perf/investigate-input-latency-20261111` | `c780cf55` | 单次归因驱动、采集包装器与临时 workflow，归档；通用事件分析器已整理进入代码 PR |
| `perf/first-candidate-fix-20261112` | `baf8d60b` | 修复实验的构建/双顺序采集工作流，归档；产品改动以代码 PR 的三类提交为准 |

前两个分支的工作树基于旧 `2e48466`；它们的脚本会按固定输入另行检出被测源码。**工具工作树的快照不等于当次被测应用源码**，不能整体并入当前默认分支，避免引入旧版文件或覆盖后来功能。被测版本以各报告的输入、APK 摘要和源码映射为准。

临时 workflow 未替换默认分支原有定义，临时启用的性能工作流恢复禁用。长期保留的分析工具不下载固定模型或依赖已到期的 Actions 产物；默认设备回归增加候选字体、角标与溢出测试，现有恢复、JNI、引擎及门控测试保留。

## 完整快照与补丁

| 内容 | 完整工作树源码 | 本分支提交系列 |
| --- | --- | --- |
| 实测产品 `4d77cd79` | [ZIP](./measured-product-source.zip) | [mbox](./measured-product-commits.mbox.txt) |
| 万象 11 月 8 日 | [ZIP](./wanxiang-20261108-source.zip) | [mbox](./wanxiang-20261108-commits.mbox.txt) |
| 万象 11 月 11 日 | [ZIP](./wanxiang-20261111-source.zip) | [mbox](./wanxiang-20261111-commits.mbox.txt) |
| 首键/点选归因 | [ZIP](./latency-investigation-20261111-source.zip) | [mbox](./latency-investigation-20261111-commits.mbox.txt) |
| 候选修复实验 | [ZIP](./candidate-fix-20261112-source.zip) | [mbox](./candidate-fix-20261112-commits.mbox.txt) |

[原始分支清单](./original-branches.json)保存完整 SHA、分叉基线、快照与补丁摘要以及 gitlink。ZIP 含当时全部受跟踪源码；不递归打包子模块，固定 SDK 和子模块仍按其锁文件/来源准备。`mbox.txt` 是原始 mbox 的逐字节下载副本，可在记录的基线上供 `git am` 审阅与重放，重放不保证原提交哈希或旧 APK 字节。

所有 ZIP 均完成 CRC 与 SHA256 校验，不含 APK、模型、原生库、签名或视频。未跟踪的用户文件与真实输入法目录不在归档或清理范围。[最终整理记录](./consolidation.json)与 [SHA256](./checksums.sha256.txt)对应实际分支处理。

性能修改和三轮完整结果见[候选布局与按键发布验证](../../perf/candidate-latency-fix-2026-11-12/index.md)。旧的 797 文件、三版补充 1,063 文件和此前归因 35 文件继续按各自原摘要保存；没有删除历史文档、旧 CI 或证据。
