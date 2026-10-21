# 工作区、性能临时分支与 Actions 整理

所有文档在 `feat/direct-rime-prototype` 维护。默认分支不用于本次修改。

本文保留 2026-10-17 的整理过程；后续提交收拢与旧开发分支的恢复标签见
[2026-10-19 合并交付记录](DIRECT_RIME_MERGE_READINESS_2026-10-19.md)。原始历史已由归档标签保留。

## 四个 perf 分支

| 已删除的远程分支 | 用途 | 最后提交 |
| --- | --- | --- |
| `perf/direct-rime-wanxiang-20261016` | 两个直连版本之间的万象回放 | `c50f767754c7476d594c9ca467fb51ad9421a870` |
| `perf/fcitx-vs-direct-20261017` | 含 Fcitx 默认分支与直连的默认配置对照 | `647aad1caa51b42bd3ebd69746897d83660f3b52` |
| `perf/fcitx-vs-direct-user-20261017` | base/全拼、上下文调频、简码、小字集对照 | `c5ee1c9a3d1bbc5024f7acd7326bf4ac341d05ac` |
| `perf/fcitx-direct-profile-20261017` | 对固定比较 APK 做原生热点追踪 | `00963a910a877b85e6fb089f9b21c5ec029855be` |

删除前逐个核对：四个 tip 都是已推送的工作分支提交 `4456c110` 的祖先，
`git rev-list --count feat/direct-rime-prototype..分支` 均为 0，没有开放 PR 使用这些分支。
使用精确旧 tip 的 lease 与 atomic push 删除，随后核对远程 `perf/*` 列表为空。
删除的是分支引用，不是提交、工具或实验数据。

必要时可从表中提交恢复，例如：

```sh
git switch -c perf/fcitx-vs-direct-user-20261017 c5ee1c9a3d1bbc5024f7acd7326bf4ac341d05ac
# 需要恢复远程入口时再显式推送该分支。
```

三个性能工作流去掉上述临时分支的 push 自动触发，保留 `workflow_dispatch`。
固定 APK 追踪任务要求显式提供 `source_run`，不再默认读取保存期限可能已过的运行产物。

## 历史 Actions 红绿记录

历史失败不使当前构建失效。记录可解释曾经修过的 CI、环境或构建问题，也可用于对照。
本次保留历史运行记录，尤其保留性能基线、APK 哈希、日志和最终成功运行的链接。

artifact 与运行记录有不同的保存期限。旧 APK/trace artifact 到期后，原始指标、来源、
固定提交及关键日志仍在 `docs/maintainer/perf/` 和对应 JSON 中。
需要重复热点追踪时应先产生新的比较 APK artifact，再传入新的 run ID。
无需仅为清理红色标记删除运行，也不需要重跑全部旧实验。

## 工作区文件处理

清理采用明确路径清单，不执行全盘 `git clean -fdx`。
移除可重建的项目 `build/`、`.cxx/`、`.gradle/`、`.kotlin/`、生成 assets、
本会话测试 AVD、已校验并有来源记录的万象测试下载，以及旧后端工作副本。
旧后端的仓库源码已在 Git 历史中，另保留小型源码归档，不保存重复构建缓存和预编译副本。

工作区保留 SDK、NDK、JDK、全局依赖缓存和原 debug 签名，以便后续继续构建及覆盖安装。
用户上传的 attachments、环境配置、共享目录不在清理范围。
最新主应用 APK 留在 artifacts 根目录；旧 APK、诊断日志、性能原始材料分类归档，
本地索引记录文件位置与摘要。APK、密钥、大模型和 trace 二进制不加入 Git 仓库。

最终处理路径、释放空间和产物索引见本机 `artifacts/workspace-cleanup-2026-10-17.json`；
可随源码保存的汇总记录见 [工作区清理结果](https://github.com/SandyYuR/fcitx5-android/blob/d50e2272de795e9110cc497a0f37017551f54747/docs/maintainer/workspace-cleanup-2026-10-17.json)。
