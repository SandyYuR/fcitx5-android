# EVO 引擎设置与降级部署 · 2026-11-24

本次调整面向 EVO（librime-evo）外部引擎与万象 EVO 方案：把中州韵引擎相关入口集中到设置首页，放宽“部署通知失败但工作区实际可用”的启动门禁，并支持按方案切换 EVO 的输入类型。改动已通过 PR [#56](https://github.com/SandyYuR/fcitx5-android/pull/56) 以 squash 方式合并为 [`a6d7b5348863d94c09aefae7ec11b15dbb02e20e`](https://github.com/SandyYuR/fcitx5-android/commit/a6d7b5348863d94c09aefae7ec11b15dbb02e20e)，标题「调整(设置): 新增中州韵引擎分栏并完善 EVO 部署与输入类型支持」。合并前最终 head 为 `d2ee5c2005822fd7f28b9342f59b72f0d1d82e59`，base 为 `9df8449f68b7c124cfe0a8481c12383aae8c2ce8`；分支共 6 个提交、44 个文件，+1601/−100。随后按第四轮 review 结论在默认分支直接提交 [`e3ea3af93`](https://github.com/SandyYuR/fcitx5-android/commit/e3ea3af9389b6d51fafde401cc23f19aa3a96490)（父 `a6d7b5348`，4 个文件 +516/−111）：把可用性判断收紧到 EVO 实际编译产物（主词典表与主 prism），并修正输入类型 patch 的整组替换语义。

## 背景与根因

中州鹅此前把 Rime 维护任务的 `deploy/failure` 通知当作致命错误：只要本次部署有任一任务失败，`Engine::start()` 就在完整检查后抛出“部署失败，请检查方案文件后重新部署”，即使默认配置、方案和主词典已经生成。对比之下，Foxy 只检查用户目录下 `build/default.yaml` 是否存在，因此同一份引擎与方案在 Foxy 上可以继续输入。

原样万象 EVO 方案缺 `wanxiang_abbrev.dict.yaml` 与 `wanxiang_mixedcode.dict.yaml` 两个包装词典，部署日志因此保持 `finished updating schemas: 0 success, 1 failure`，但主方案、拼音棱镜和主词典表均已生成，实际输入可以出候选。缺文件属于方案内容，不在应用侧补齐；应用侧只把“通知失败”和“当前工作区是否可启动”分开判断。

## 设置入口与页面调整

设置首页新增「中州韵引擎」分栏，位于「日常调整」之前，包含三个入口：

- 中州韵引擎设置：原「中州韵设置」。
- 中州韵引擎变体：原「Rime 引擎变体」，仍进入运行时引擎管理页。
- 中州韵方案更新：原「Rime方案更新」分组改为一个二级页，页内保留「万象更新」与「自定义更新」两个入口。

「中州韵引擎设置」页的顺序调整为：

1. 启用的输入方案
2. 编辑器内预编辑
3. 用户数据目录
4. 数据目录管理
5. 导入 Rime 方案 ZIP
6. 重新部署
7. 同步用户词典
8. 允许在密码框中使用输入法（页尾）

没有独立的“输入类型”设置项；输入类型按钮属于「启用的输入方案」页中的具体方案行，见下文。旧设置搜索项、旧布局快捷方式与相关文案同步改为新入口；搜索“输入类型”会定位到「启用的输入方案」页。

## 部署通知与工作区可用性分离

新增的可用性判断要求以下条件同时成立，才会在 `deployFailed_` 为真时清除失败门禁并记录“降级”：

- 用户目录下 `build/default.yaml` 是普通文件；
- 引擎已启动，且 `get_schema_list` 返回非空方案列表；
- 当前方案的 `build/<schema>.schema.yaml` 存在且非空；
- 打开当前方案后，`translator/dictionary` 指向的词典名合法，且对应的 `build/<dict>.table.bin` 与主 prism 的 `build/<prism>.prism.bin` 都是普通文件且大小大于零。

主 prism 按 EVO 的 `DictionaryComponent` 规则解析：优先 `translator/prism`；`translator/prisms` 引用 `input_types` 时，按当前输入类型（`reset`，否则 `enabled` 首项）匹配 `option` 并取对应 `name`，列表形式取首项；两者都未声明时使用 `build/dictionaries/<dict>/algebra` 的首个分组。显式名称优先限定为 `<dict>.<prism>`。旧引擎缺少 map/list 查询 API 时退回 `<dict>` 与 `input_types/reset`／`enabled/@0` 的 YAML 检查。当前方案优先取已有会话的当前方案；没有会话时，若 `switcher/fix_schema_list_order` 为真则取方案列表第一项，否则读取 `user.yaml` 的 `previously_selected_schema`，只有它出现在方案列表中才采用，最后才退回列表第一项。

只有上述判断成立时才清除 `deployFailed_` 并置 `deployDegraded_`；否则仍按原有失败关闭处理。`gate()` 仍以 `deployFailed_` 为准，因此不会把失败日志改写成成功，也不会补齐缺失文件。降级只表示“当前工作区能启动并提供输入”，不表示方案完整、部署成功或整句模型已验证。目前设置页还没有降级警告。

## EVO 输入类型

方案在 `wanxiang.schema.yaml` 的 `input_types` 中声明 `options`，并以 `wanxiang.custom.yaml` 的 patch 覆盖 `enabled` 与 `reset`。应用只读取这些声明，不另存一份本地状态。patch 按 Rime 的键排序与整组替换语义应用：平铺 `input_types/enabled` 只改该子键；整组 `input_types` 会替换整个组，因此需重新声明 `options`。合并后过滤未知项；`enabled` 缺失视为全部声明选项，`reset` 必须属于 `enabled`。

「启用的输入方案」多选页中，每个方案行右侧按需显示输入类型按钮：

- 方案没有声明 `input_types` 时隐藏按钮；
- 非 EVO 引擎时按钮置灰，说明“仅在使用 librime-evo 引擎时可用”；
- 只有 `RimeEvoEngines.isEvo` 判定为真时才能打开并切换。

对话框标题为“输入类型 · 方案名”，单选项的 label 来自方案的 `input_types/options`；当前值优先取 `reset`，没有则取 `enabled` 第一项，`enabled` 缺失时即首个声明选项。

选择后先重新核对 EVO 引擎与当前工作目录，再只改写当前方案 `wanxiang.custom.yaml` 的 `input_types/enabled: [选项]` 与 `input_types/reset: 选项`。写入保留注释、键序、换行和其它键，采用原子写入并做结构校验。若改的是当前运行方案，随后对引擎执行 `set_option(选项, true)` 并触发重新部署；其它方案只写文件，等下次激活时由方案 reset 生效。成功、已保存但重新部署失败、保存失败分别给出提示；保存后不回滚。

## 验证

| 检查 | 实际范围 |
| --- | --- |
| JVM 单元测试 | `RimeInputTypesRepositoryTest` 18 项、`PluginSettingsUiTest` 3 项、`SettingsEntryNavigationTest` 12 项、`SettingsSearchIndexCoverageTest` 3 项，失败/错误/跳过均为零 |
| 原生引擎契约 | 使用 NDK 编译后在设备运行，输出 `engine contract: passed`；包含降级用例、`translator/prism`、`translator/prisms` 与 `input_types` 引用、`algebra` 缺省、`fix_schema_list_order` 分支，以及旧 `input_types/reset` 与 `enabled/@0` 回退；原有 `failDeploy` 仍失败关闭 |
| x86_64 Debug 构建 | `:app:assembleFxDebug`、`:app:assembleFxDebugAndroidTest`（`-PbuildABI=x86_64`）成功；设备验证包基于 `a6d7b5348` 并包含 `e3ea3af93` 的工作区改动，versionName 为 `nightly-0.1.3-876-ga6d7b534-20261124-123945-0-ga6d7b5348`；最终源码提交为 `e3ea3af93` |
| 设备门禁 | 在最终安装包上，`installedProviderIsDiscoveredProbedAndRunsThroughDirectBridge` 为 `OK (1 test)`，26.809s；原样 EVO 方案（含 `wanxiang-lts-zh-hans.dlm`）部署后输入 `ni` 出候选并上屏，日志只有两个包装词典缺失，无 `.dlm` 错误 |
| 输入类型实机切换 | 在最终安装包上切到“自然码”，`wanxiang.custom.yaml` 只保留 enabled/reset，重新部署后输入 `wo` 出候选“我、握、沃、卧、窝、涡”；随后推回原 pinyin custom 并重新部署，按钮恢复“全拼” |

设备只覆盖 `emulator-5574`（AOSP API 35，x86_64）；未覆盖 ARM64 或其它 ABI。设备门禁使用的引擎 DSO 为 librime-evo@9d27c24b 的 x86_64 构建，SHA-256 `977167F8F977B1230AED7C57B28545D8830346029E09A26D07922D5850E3E2F8`；原样方案 ZIP 的 SHA-256 为 `2AF5F0A6C3065FAFF958352E38C0B87723EA66ED6E18E9FE3839788805711B86`。

## 边界与未覆盖

- 不修改用户方案目录，不补 `wanxiang_abbrev` / `wanxiang_mixedcode` 包装词典；部署日志中的 1 failure 仍会存在。
- 不改写部署日志，不把部分失败报告为成功；设置页没有降级提示。
- 不验证 `.dlm` 整句模型的质量或完整性；只能确认缺失模型不会阻止基本候选。
- 未覆盖 ARM64 设备、其它 ABI、真实用户方案更新流程与长期同步行为。
- 输入类型实机切换已在最终安装包上重复；仍只覆盖 `emulator-5574`，未覆盖 ARM64 或其它 ABI。

## 相关入口

- [PR #56](https://github.com/SandyYuR/fcitx5-android/pull/56)
- [`e3ea3af93`：按 EVO 编译语义判断部署可用性](https://github.com/SandyYuR/fcitx5-android/commit/e3ea3af9389b6d51fafde401cc23f19aa3a96490)
- [引擎更新手册：外部引擎的方案部署排查](../engine-update.md#外部引擎的方案部署排查)
- [正确性契约：EVO 部署降级](../implementation/correctness-contracts.md#evo-部署降级)
- [正确性契约：EVO 输入类型](../implementation/correctness-contracts.md#evo-输入类型)
- [中州鹅用户指南：中州韵引擎](../../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md)
- [功能说明：中州韵引擎设置](../../features/rime-enhancements.md)
- [EVO Android 构建整理 · 2026-11-23](2026-11-23-evo-android-engine.md)
