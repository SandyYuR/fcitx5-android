# 中州鹅直连版合并交付记录

日期：2026-10-19。合并入口：[PR #4](https://github.com/SandyYuR/fcitx5-android/pull/4)，
`feat/direct-rime-prototype` → `fx-rime-only`。本次整理将 PR 准备到可合并状态，实际合并由维护者执行。

最后审阅在原三个交付提交之后追加一项依赖清理与核验提交；
10 月 20 日另加[密码框方案菜单修复](DIRECT_RIME_PASSWORD_SCHEMA_MENU_2026-10-20.md)，
随后完成[部署重载与发行标识调整](DIRECT_RIME_REDEPLOY_AND_IDENTITY_2026-10-20.md)，共六项功能交付提交；
另在功能分支同步默认分支 `69fa5a9b`，保留其 MD3 设置与低版本主题修复并解决冲突。
最新打包库、无调用方依赖、组件清理及验证见[最终依赖审阅](DIRECT_RIME_FINAL_REVIEW_2026-10-19.md)；
下面的 1c/6378/205d 来源关系保留整理时点的事实，交付选择 PR 当前提交重新产生的签名 Release。

## 交付内容与日用反馈

中州鹅（`org.fcitx.fcitx5.android.fx.rime`）保留完整 Android 键盘、候选、布局编辑器、主题与设置，
由单一 `rime-main` owner 经 JNI 直接调用 librime。原生 Fcitx Core、fcitx5-rime、AndroidFrontend
及其构建工程已经删除；APK 原生库白名单为 `libdirect-rime.so` 和 `libc++_shared.so`。
仍在使用的 Android 前端类名、配置适配、旧布局动作与许可证署名见[维护手册](DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md)。

已交付的输入行为包括方案控制候选注释、分组状态开关、方案菜单去重、中英文及方案切换提示、
“中州韵（方案名）”空格标签与紧凑“㞢”、唯一的中州韵设置入口和更新后的关于页。
首键在绑定代次内顺序接收，同应用普通文本复用会话；跨应用和敏感输入隔离。
候选补取分批进行，已有上下文库通过 LevelDB 公共 API 复用有效 MANIFEST/WAL，日志先冻结为完整文件再导出。

用户在万象 base v18.1.3 + LTS、全拼、上下文调频、简码、小字集配置下反馈卡顿改善，
并确认当前版可以日常使用。两份已脱敏手机片段记录的首次选词 core 为 4.655/46.693ms，
同线程 sync 均为 0；不能据此推算所有设备的 P95 或快速发送的可靠性。
分析与证据见[冷路径报告](DIRECT_RIME_COLD_IO_AND_LOG_EXPORT_2026-10-19.md)。

用户词不显示的后续反馈发生在多个输入法共用外部工作目录时；停止其他输入法并重启后恢复，
用户确认无需更改产品。这次只补[共存与排错说明](../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md#多个-rime-输入法共存)，
未清空数据库或调整学习开关。各输入法应使用独立工作目录，通过同步目录交换学习数据。

## 源码、APK 与提交整理

| 来源 | 固定标识及含义 |
| --- | --- |
| 真机验收 Release | 源码 `9adb694932df4340bc48d359af2c788ad6809f3e`；[下载](https://github.com/SandyYuR/fcitx5-android/actions/runs/39180493901/artifacts/12155759113) |
| 验收 APK SHA256 | `966c17b7f8482e053f72f82802f621ee48bcb093d1189c7dd6c5b8886e4ad5ba` |
| Release 证书 SHA256 | `ba43006e1bfa3f60f46b2a9056f593a3a61445bd2571319fb1869e49cc36b1fe` |
| 整理前开发分支 | `6c26350f0961fccac99cfa322d057a61384fdd79`，相对原基线 `a714feff` 有 77 个开发提交 |
| 整合目标基线 | `92fd34ce060cb4625d99066443d294c453c4c693`，保留默认分支后续六项修复 |
| 收拢后的运行代码 | `6378e2ca1afcb83c6fd778703157c08774a802d9` |
| 构建与验证工具 | `205d278dc91aed4a6f9feb9ff111bf255a8e965c` |
| 文档与证据 | 本文件所在提交；历史实验数据保留原提交/模型/测量边界 |

前三个提交分别承载产品代码、构建与测试工具、文档与证据；最后审阅另有一个依赖清理提交。
原始调试中间态仍由历史标签保留。
收拢后的产品树等于旧直连树与目标基线的无冲突整合结果，另调整构建版本描述以排除归档标签；
没有继续修改已经认可的 Rime 热路径。六项前端修复包括最近记录与光标日志、剪贴板图片去重、
旧滑动方向兼容、布局管理/配色及弹出符号交给 Rime 处理。
来源核对见[整理清单](direct-rime-merge-preparation-2026-10-19.json)。

整合版包含这些后续修复，**不能把验收用 9adb APK 标作新提交构建**。
整合版签名 Release 由当前 PR 提交的 `Commit CI` 重新产生，并附带 `source.txt`、构建元数据、APK SHA 与 profile 审计；
具体运行和下载入口以 PR 描述的本轮验证表为准。仓库 Release 证书保持不变；
10 月 20 日恢复默认分支原包名，从旧直连测试包迁移需要先导出再恢复备份，不能覆盖该测试包。

## 开发分支归档

| 分支或历史 | 处理 | 恢复标签 |
| --- | --- | --- |
| 旧的 77 个直连开发提交 | 以三个交付提交替换，原历史保留 | `v0-archive-direct-rime-history-20261019` → `6c26350f` |
| `direct-rime-proto` | 早期 Muse 原型，保留源码标签后删除开发分支 | `v0-archive-direct-rime-proto-20261019` → `aa9ec5ed` |
| `pr/fx2-integrated` | 旧整合实验，无开放 PR；独有历史保留在标签后删除开发分支 | `v0-archive-fx2-integrated-20261019` → `85e36f4b` |
| 合并前 Fcitx 版本 | 固定完整源码，默认分支继续承接本 PR | `v0-archive-fcitx-before-direct-20261019` → `92fd34ce` |
| `fx-rime-only` | 保留默认分支，本次不直接写入 | — |
| `feat/direct-rime-prototype` | 保留至 PR 合并，作为当前交付分支 | — |
| `codex/md3-settings-rebuild` | 保留；[PR #5](https://github.com/SandyYuR/fcitx5-android/pull/5) 的独立设置界面开发 | — |
| `docs` | 保留；文档站及后续维护规划 | — |

此前四个 `perf/*`、临时 Foxy APK 分支和用户词库调查分支已经删除，不恢复这些临时入口。
Foxy 原 APK 不加入交付分支。历史 Actions 红绿记录继续保留；删除失败记录不能改善当前质量，
原始失败也有区分产品问题与测试前提错误的审计价值。

归档标签的 `v0-archive-*` 前缀是开发归档标识，不是软件版本或正式 Release。
它与旧 CI 的 tag 排除规则兼容，当前版本描述也显式排除该前缀。
例如恢复早期原型到新的本地分支：

```sh
git fetch origin --tags
git switch -c investigate/old-direct v0-archive-direct-rime-proto-20261019
```

历史性能报告引用的旧 SHA 继续由完整历史标签保留。已有旧开发分支的本地副本应先保存未提交内容，
再从新的远程 tip 新建工作分支，避免把 77 个旧提交重新合入交付历史。
PR #5 后续应更新直连基线并复核设置导航、兼容入口和构建。

## 合并检查与发布流程

合并时核对 PR **当前提交** 的 Checks 和描述中的验证表，不能只看旧 APK 的成功记录：

- `PR CI`：JVM 单测、四 ABI Debug、原生依赖/资源/DEX 白名单和页对齐。
- `Direct Rime CI`：ASan/UBSan、IO/LevelDB 恢复及显式 sync 契约、独立引擎 smoke、完整前端实际触摸/方案/设置/日志导出。
- `Commit CI`：仓库证书签名 ARM64 Release、包内 ART profile、原生依赖与源码/哈希元数据。
- PR 无合并冲突，当前目标基线的六项前端修复完整保留。

PR 构建与设备检查统一使用 GitHub 临时合并提交。完整万象大模型诊断和性能矩阵仅手动运行，
已有固定模型诊断结果在[冷路径验证索引](perf/2026-10-19-cold-io/validation.json)；本次不重复旧 Fcitx/Foxy 比较。
上游 LTS 同名资产已变化，历史哈希失配时须使用匹配缓存或另建新模型清单，不能静默替换历史基线。

合并后的默认分支由 `Commit CI` 构建签名版本，Nightly 同时等待构建及单测成功，
以“中州鹅 · Direct Rime”发布。签名密钥缺失时失败，不生成用于日用升级的临时证书包。
独立的临时签名 Release 工作流已收拢，避免重复出包；源代码、APK 摘要和 ART profile 核验随 artifact 保存。

## 已知范围

用户认可本机日用；广泛厂商输入框、进程冷启动、长句高压、快速发送与长期用户库仍需持续观察。
`reuse_logs` 上游标为 EXPERIMENTAL，已有恢复、WAL 轮转、追加失败和显式 sync 回归，尚无断电耐久性结论。
完整 XKB Compose 与按应用 `app_options` 等价仍未完成。
Fcitx/default 与 Foxy 的历史对照保留各自测量起点，不将不同轮次数字拼成整链路提速比例。

日常说明以[用户手册](../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md)为准，引擎更新以[维护手册](DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md)为准；
[会话索引](DIRECT_RIME_SESSION_2026-10-17.md)及历史报告用于追溯研发与验证过程。
