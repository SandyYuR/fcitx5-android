# 粘滞修饰键压缩合入 · 2026-11-26

本轮将 [PR #57](https://github.com/SandyYuR/fcitx5-android/pull/57) 的四个修饰键补丁压缩为默认分支上的一个功能提交，补齐使用说明、正确性契约与验证记录，并清理本次临时分支和构建目录。功能用法见[粘滞修饰键](/features/keys/sticky-modifier-key)。

## 固定来源与合入边界

| 对象 | 完整提交或校验值 |
| --- | --- |
| 合入前默认分支 | `6a42fa288f5bea20564873ea9b1a2fc8c0aa3495` |
| 验证候选 | `11d1efa6fd628c5d832d88f2f0a1ca6f1449295a` |
| 候选源码树 | `0b893fcb547e7a1b80d2ad5e6abd7a3d6aedc59e` |
| 最终压缩提交 | [`c1307b794f6376977e17145c0a24b433f952e0bc`](https://github.com/SandyYuR/fcitx5-android/commit/c1307b794f6376977e17145c0a24b433f952e0bc) |
| 文档工作基线 | `docs@42af42795d696b9fb92a59aa39e39d3ae6867351` |

默认分支此前已重写 JS 语音提交。本轮从新基线重放原作者修饰键提交及维护者的三个后续修复，四项 `git range-diff` 均为相同补丁。语音生产代码、语音测试和整个主资源目录 `app/src/main/assets` 与合入前默认分支完全一致；不重放旧的 `f48ab57429cc53f36d69053a0215dbb18756d83a` 语音提交。引擎 SDK 继续使用基线固定的 `android-sdk-20261125-4`。

合并后已实际核对：唯一父提交为上述基线，相对基线仅新增一个提交，源码树与候选完全一致；主代码工作区已快进到该压缩提交。单提交文档映射见 [JSON](./commit-map-2026-11-26.json)。

## 原作者与维护者贡献

| 贡献者 | 工作内容 | 原审阅提交 → 最终候选中的提交 |
| --- | --- | --- |
| @lihuichang（原作者） | 实现 Ctrl / Shift / Alt 粘滞状态、组合派发、手势消费边界、状态外观和布局配置；修复长按弹窗松手额外 click，并补充初始资源与测试。 | `ccaa0178b47931e84e192ea09e22217b1df65fdc` → `e1772c02b6c833556505e167f1aacc0d2ce00ebf` |
| @SandyYuR（维护者） | 修复字面逗号、句号等快捷键被吞，合并符号必需的 Shift 并去重，修复粘滞 Shift 消费一次性大写，新增 12 项派发回归。 | `124f2ade4bff132628b80918d6b66b3efa4a70d2` → `3bbbaa06d154f5827e083220c5071ca0ff16f413` |
| @SandyYuR（维护者） | 贯通文本编辑布局的两个编辑入口和嵌套上下文，过滤不支持的粘滞键类型，按配置值绑定选项，保留并标明旧非法草稿，新增 6 项编辑器回归。 | `dd0149db1f1445747e0a60f2a379df80b9c51b51` → `6313d5080d18a38681b6b465e7a4326b9c4a59ea` |
| @SandyYuR（维护者） | 补齐德语、西班牙语、日语、韩语、俄语的不可用键型提示，修复 MissingTranslation。另完成审阅、变基、验证、压缩合入与文档维护。 | `081136d3fb1fa7531fb2f8485d36a9d390b98222` → `11d1efa6fd628c5d832d88f2f0a1ca6f1449295a` |

默认分支最终仅保留压缩提交；上述 SHA 用于追溯审阅和验证，提交说明保留双方共同作者归属。

## 行为与文档

- [粘滞修饰键](/features/keys/sticky-modifier-key)说明配置值、组合方式、点亮和取消、可消费手势及示例。
- 单击，以及等价于单键 tap 的长按、上滑、下滑可消费状态；Repeat、双击、左右滑、弹窗长按和多步宏保持原约定。仅 Shift 的字母按虚拟键交付，其余组合沿物理按键路径交给 Rime，再由既有流程决定是否转发给应用。
- 符号保留产生该符号所需的 Shift；字母布局中的大写名称不会自动给 Ctrl+A 加 Shift。一次性大写被消费一次，锁定大写保持锁定。
- [文本编辑底栏](/features/text-editor)不支持独立的粘滞状态机，因此 `text_editor` 编辑器禁止新选或保存该键型；旧配置准确显示为不可用并保留草稿，用户明确改成受支持类型后才能应用。普通键盘与辅助栏保留粘滞键支持。
- 对应维护入口为[正确性契约](/maintainer/implementation/correctness-contracts#粘滞修饰键)、[维护门禁](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN#按改动选择验证门禁)及[功能覆盖表](./feature-coverage)。

## 验证与边界

| 验证对象 | 来源与结果 |
| --- | --- |
| 本地 JVM | 候选 `11d1efa6f`：19 份 JUnit XML，188 项测试，失败 / 错误 / 跳过均为 0；含原 182 项相关测试与 6 项 JS PCM 对齐回归。 |
| Debug 与 lint | 候选 `11d1efa6f`：ARM64 `:app:assembleFxDebug :app:lintFxDebug` 实际通过；不修改 lint baseline。 |
| APK 与 native 链接 | 本轮 Debug APK 内容与链接审计通过；22 DEX、预期 3 个共享库、ARM64 16 KiB 对齐，无旧 Fcitx 后端依赖。 |
| 变基后远端 CI | [PR CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/45674039183)与[Direct Rime CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/45674039262)均成功；后者前端触摸与引擎原始报告为 60 项测试、失败 / 错误 / 跳过均为 0。 |
| 历史签名包 | 旧源码 `081136d3f` 的 [Commit CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/45655377298)已通过四 ABI 签名 Release 与 JVM；这些 APK 不作为新基线候选的构建证据。 |

本轮 Debug APK SHA-256 为 `7b03b55f0279df8c8204e25264f3e13c9814a2578e19a25c56833542ecaf3126`，native ELF build ID 为 `d26fa397b84441d62b3861d88305dcca7593bb14`。独立只读复核核对了四提交范围、干净工作树及语音路径空差异；原始测试、构建、lint 和 APK 记录见下方附件。

本轮未操作本机 AVD、未安装 APK，也未重新进行人工真机、真实宿主 InputConnection 或像素验收。原作者在初版 PR 说明中报告的真机测试属于原作者记录；旧源码的远端设备结果与本轮候选结果分开登记。

## 证据与清理

原始审阅反例、历次修复记录、最终候选 JUnit XML / lint 报告、原始构建输出、Git 核对及最终候选与旧版远端前端报告归档为 [validation-evidence.zip](../audit/2026-11-26-pr57-sticky-modifier/validation-evidence.zip)，逐文件摘要与来源见 [manifest.json](../audit/2026-11-26-pr57-sticky-modifier/manifest.json)。归档共 55 项、3,649,433 字节，SHA-256 为 `4ee2dbaa0dae103d72f5a6c928860c155225538792f809fc4ca56cb14fc8d40f`；清理前已逐项核对全部字节数及摘要。

已删除主仓库专用 CI 分支 `ci/pr57-sticky-modifier-dd0149db1`、本地 `fix-pr57-sticky-dispatch` 与 `backup/pr57-before-sdk4-rebase-20261125`、过期作者跟踪引用、专用 PR worktree、31 个临时文件及两个 CI 下载目录。绝对路径、旧 SHA 与删除结果见 [cleanup.json](../audit/2026-11-26-pr57-sticky-modifier/cleanup.json)。保留原作者 fork 功能分支、主代码与 docs worktree、其他会话工作区、本机手机数据及共享工具缓存；主工作区 `native/rime-prelude` 的初始化状态、固定 SHA、URL 与 active 配置均保持原样。

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
