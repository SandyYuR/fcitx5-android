# 粘滞修饰键编辑与点亮交互 · 2026-11-27

本轮将 [PR #59](https://github.com/SandyYuR/fcitx5-android/pull/59) 压缩为默认分支上的一个功能提交，改写粘滞修饰键的抬手确认、点亮配色、方向 / 长按附加动作与编辑器交互，并按最新默认分支适配本轮新增资源与设备测试。功能用法见[粘滞修饰键](/features/keys/sticky-modifier-key)。

## 固定来源与合入边界

| 对象 | 完整提交或校验值 |
| --- | --- |
| 合入前默认分支 | `1f9a239d33f26ddfe8a80126fa7451da78f7c111` |
| 原 PR 分支提交 | `a518c264efa5fb5530f5b3e6e0329361f215f17e`、`74cd4d364af385829b7d50441f0df5eefd7eb792` |
| PR 合并时的 CI 集成提交 | `604c5970093f8e2bc84009bfbe3e7fa987ffb3e4`（`74cd4d364` 与 `1f9a239d3` 的合并结果） |
| PR 审阅修复提交 | `34132e331349f43810ab0ef97875072231f1ef57` |
| CI 适配提交 | `56ee8cef6191053815baf63d7530a08511c705d1` |
| GitHub squash 结果 | `96c251657ae2ad0260324728e4a2cdc5d084a54c`（双父提交，未采用） |
| 最终单提交 | [`590e0bbc0ddcc629342f968e258fa142527d3bd1`](https://github.com/SandyYuR/fcitx5-android/commit/590e0bbc0ddcc629342f968e258fa142527d3bd1) |
| 文档工作基线 | `docs@88766b124686d46c29e89d939d3872937883a19a` |

GitHub 的 squash 合并在 PR 仓库上产生的是双父合并提交，违反默认分支保持线性的既有约定。因此以 `96c251657` 的源码树 `e1f26afae5cf1ad37a344c26e4c2fd78b04191d4` 为依据，重建唯一父提交 `590e0bbc0`：父提交为 `1f9a239d3`，标题为「功能(键盘): 完善粘滞修饰键的编辑与点亮交互」，正文保留合并说明、CI 适配说明与共同作者归属。重建后已核对：父提交数量为 1、源码树与原 squash 结果完全一致、`1f9a239d3..590e0bbc0` 的提交计数为 1。默认分支以 lease 强推更新到该提交，强制更新前的远端提交为 `96c251657`。

`1f9a239d3` 之前的两次默认分支改动（`8b273e8a390846c993c0283a863a47719168d94a`、`16730aef6cf62b0245af2412c2b499e64e7b9903`）以及 `1f9a239d3` 本身不在本轮 PR 范围内，由各自会话维护，本轮只登记它们与本轮提交之间的基线关系。单提交文档映射见 [JSON](./commit-map-2026-11-27.json)。

## 原作者与维护者贡献

| 贡献者 | 工作内容 | 提交 |
| --- | --- | --- |
| @lihuichang（原作者） | 把点亮改为抬手确认、新增点亮配色与方向 / 长按附加动作、调整键型下拉顺序、手势按下不再提前短路，并补充生命周期、控制器、水波纹与编辑器测试。 | `a518c264e` → `74cd4d364` |
| @SandyYuR（维护者） | 审阅后修复三处问题：粘滞键宏结果在草稿重建后丢失另一方向、被消费按压的迟到抬手重新锁存、水波纹遮挡与实际键底颜色不一致；新增控制器、派发、编辑器与水波纹回归。 | `34132e331` |
| @SandyYuR（维护者） | 修复 PR CI：补齐默认分支新增的引擎元数据资源与五个语言的缺失译文，设备测试改为通过 `RimeEngineStore` 真实授权夹具加载页面，原生契约测试补充缺失头文件。 | `56ee8cef6` |
| @SandyYuR（维护者） | 完成融合提交 `590e0bbc0` 的线性化、默认分支 lease 强推、CI 复核与文档更新。 | `590e0bbc0` |

## 行为与文档

- 粘滞修饰键改为**按下只回显、抬手才点亮**：物理按下只登记待确认手势，有效抬手才切换点亮；长按、方向滑动、滑出键外或触摸被取消都不改变状态。
- 一次可消费单键取走当时全部已点亮修饰键；参与消费的按压在其后抬手时只释放占位，不得重新点亮。被消费的按压未松开前，同一修饰键再次按下按重复按压拒绝。
- 新增点亮外观：`activeBackgroundColor` / `activeTextColor`（及 Monet 版本）在点亮时生效，未设置时沿用主题的选中项背景与文字色；水波纹遮挡判定跟随当前实际键底颜色。
- 新增可选的上滑 / 下滑 / 长按附加宏动作与方向标签；它们按按下前的状态执行，不切换也不消费粘滞状态。填写方向标签后键面才换成带副文字的外观。
- 键型下拉顺序调整：粘滞修饰键移到数字键盘之后、空白占位键与宏键之前；粘滞键不提供「合成态按键」入口，`text_editor` 仍不支持该键型。
- 对应文档：[粘滞修饰键](/features/keys/sticky-modifier-key)、[键盘布局编辑器](/features/editor/layout-editor)、[按键类型总览](/features/keys/overview)、[用户指南](/manual/DIRECT_RIME_USER_GUIDE_zh-CN)、[正确性契约](/maintainer/implementation/correctness-contracts#粘滞修饰键)、[维护门禁](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN#按改动选择验证门禁)及[功能覆盖表](./feature-coverage)。

## 验证与边界

| 验证对象 | 来源与结果 |
| --- | --- |
| PR CI（修复前） | [PR CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/45927181617)与[Direct Rime CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/45927181577)：JVM 通过，lint、原生契约与设备测试失败。 |
| 修复后 PR CI | [PR CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/45937444085)与[Direct Rime CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/45937444030)均成功；后者含前端触摸与引擎测试。 |
| 默认分支 CI | 最终单提交 `590e0bbc0` 的 [Commit CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/45951373498)成功；被替换的 `96c251657` 运行已作废。 |
| 本机 JVM | 审阅与修复期间实际运行相关 23 份 JUnit 报告共 286 项测试，失败 / 错误 / 跳过均为 0；默认分支全量 JVM 另有 14 项符号链接相关失败，均落在本轮未改动的 `UserDataRestore*`、`WanxiangUpdateTransactionTest`、`AssetMigrationTest` 等类，并在修复前基线上复现，属 Windows 宿主符号链接限制。 |
| Debug 与原生契约 | 审阅修复后的 ARM64 Debug 构建通过；APK 内容与 native 链接审计通过（22 DEX、预期 3 个共享库、ARM64 16 KiB 对齐）；合并后的 staging 树 lint 通过，原生契约测试以 MSVC 编译并运行通过，Linux ASan 结果以远端 CI 为准。 |

本轮未操作本机 AVD、未安装 APK，也未进行人工真机外观与水波纹像素验收；作者在 PR 说明中报告的测试属于作者记录，本轮的设备结论以远端 CI 为准。

## 清理

PR 审阅与修复使用的临时 worktree、本地修复分支、CI 日志、探针与报告已在本轮结束后删除；主代码默认分支保持在 `590e0bbc0`，与远端同步，主工作区 `native/rime-prelude` 的初始化状态与固定 SHA 未变。原作者 fork 的功能分支保留，未删除远端分支。

[![](https://img.shields.io/badge/powered_by-dsh-4D6BFE?style=flat-square&logo=deepseek&logoColor=white)](https://github.com/deepseek-ai/deepseek-harness)
