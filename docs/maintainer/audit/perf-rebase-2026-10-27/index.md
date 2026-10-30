# perf 分支变基与分类整理 · 2026-10-27

::: info 固定提交的历史记录
本页记录整理完成时的 `9c9013ca`，其中代码、安装包、文件树和验收来源均保持原值。随后只调整文档与 PR ABI 选择的 `710e4ef9`，见[当前合入准备](/maintainer/history/review-2026-10)；不将本页设备结果改记为新包结果。
:::

## 变更范围

- 默认分支基准：`fx-rime-only` / `6af8bc0b7ca048a304952a4cbc4525e6c9f247b1`。
- 整理前：`7e5c2c1b0efe5493cf1cbf991ec45f28645ee0ee`；远端备份分支 `backup/perf-before-rebase-20261027`。
- 整理后：`9c9013ca4efa6dcd305737c0e2de3a0023785a9a`，默认分支之上 12 个分类提交。
- 按用户要求移除 `98f3713c64e4c5cbdd23b6360e4686d968e49d77` 和 `d66c9e4aa3a411673a7fc557f22f3c804765f18c`；两者不在新 perf 历史中。
- 原 perf 独有 62 个提交，去掉 2 个、保留其余 60 个，并更新与默认分支新行标题栏对应的测试。完整路径分组和原提交映射见[机器记录](./commit-map.json)。

## 分类后的提交

| 类别 | 提交 | 文件数（共享集成文件可能分次出现） |
| --- | --- | --- |
| 基础(构建): 规范 ABI 与资源生成并补齐有界 I/O | [`d721a171`](https://github.com/SandyYuR/fcitx5-android/commit/d721a17137746ba50c8d7d9ffc6fdd4eba1a71ac) | 21 |
| 优化(输入): 合并直连生命周期、会话隔离与候选提示 | [`b234f3e5`](https://github.com/SandyYuR/fcitx5-android/commit/b234f3e5b034611b6c286d50414980741c2ceb07) | 75 |
| 修复(资产): 统一安全迁移与内置预设同步 | [`bd66ad02`](https://github.com/SandyYuR/fcitx5-android/commit/bd66ad025bae498a8a43c83def3abb928ee7d6b7) | 5 |
| 修复(编辑器): 合并草稿、保存快照与页面返回结果 | [`36019dc7`](https://github.com/SandyYuR/fcitx5-android/commit/36019dc7f637e4405a515eac4fe83cc815e98f62) | 29 |
| 修复(主题): 校验导入、兼容旧名并后台初始化 | [`3949be8b`](https://github.com/SandyYuR/fcitx5-android/commit/3949be8b12be74ef960233e821076860ef72a6d6) | 17 |
| 修复(日志与弹窗): 留存崩溃栈并完成取消后的窗口清理 | [`04b5631a`](https://github.com/SandyYuR/fcitx5-android/commit/04b5631af86f62ed12018773cc757a50ac1ecb03) | 9 |
| 修复(剪贴板同步): 保护保存顺序、约束载荷并后台过滤 | [`bfa58713`](https://github.com/SandyYuR/fcitx5-android/commit/bfa587131f4c87b9c6f845f3e995d2b95a068be9) | 42 |
| 修复(语音): 按编辑器与请求会话隔离 AIDL 和浮窗回调 | [`1288e470`](https://github.com/SandyYuR/fcitx5-android/commit/1288e470a8185df88b97fd95021fe87d672ecc46) | 13 |
| 修复(访问): 加固局域网配对、网页保存与文件 IPC | [`40e017db`](https://github.com/SandyYuR/fcitx5-android/commit/40e017db462f87488ebe4df4577bbdb9c55e78e0) | 9 |
| 修复(更新): 持久化断点下载并安全发布 | [`4bbb58a8`](https://github.com/SandyYuR/fcitx5-android/commit/4bbb58a8dfe8659b4b9ebc51cc97fddf97f5f082) | 16 |
| 修复(备份恢复): 加密备份并隔离恢复进程与目录事务 | [`f08f204a`](https://github.com/SandyYuR/fcitx5-android/commit/f08f204a5529c92941cf9b15c296771df7e69a77) | 28 |
| 维护(文档与CI): 汇总协议、资产校验与发行范围 | [`9c9013ca`](https://github.com/SandyYuR/fcitx5-android/commit/9c9013ca4efa6dcd305737c0e2de3a0023785a9a) | 10 |

## 默认分支最后一项改动与旧后续修复

默认分支 `6af8bc0b` 将新增、上移、下移操作固定在行标题栏；`keysFlow` 只复用实际按键。旧 `d66c9e4a` 修复的是隐藏箭头与普通键／新增键共用池导致的不可见状态残留。该复用路径已消失，因此无需重新加入旧生产代码修复。

新分支的 `KeyboardLayoutAdapter.kt` 与 `DraggableFlowLayout.kt` 与默认分支逐字一致。拖动高亮使用卡片底色并恢复原 ColorStateList，保留默认分支的圆角和底色修复；活动页面保留新的下移入口及独立的草稿、返回结果修复。

默认分支的旧设备测试仍按“操作键在键区中”的布局检查，需随新设计更新。已覆盖空行、增删按键、首／中／末／单行、同一 holder 重绑，并保留 API28 的拖动卡片回归。没有为通过测试重新引入旧的混合复用结构。

## 完整性核对

普通变基并更新行标题栏测试后的参考提交为 `c17863c29c2a3ea9c482f5a32a15f2ca11e0bbf0`。分类整理后的文件树与参考树完全一致，均为 `2c8d28fc1387c03f2b8b8b6b21f9ed341bebec2a`。该核对只证明整理没有额外改变最终文件，构建与设备验证另列。[树与分支核对记录](./tree-verification.json)。

## 验证

验证针对最终 `9c9013ca`：

| 范围 | 结果 | 记录 |
| --- | --- | --- |
| 本地完整应用 JVM | 1234 项，零失败、错误或跳过 | [统计](./local-jvm-summary.json) |
| 本地构建逻辑 | 6 项，零失败、错误或跳过 | [统计](./local-jvm-summary.json) |
| Debug 与 AndroidTest 构建 | 通过；编译器堆 2 GiB | [完整构建输出](./local-build.txt) |
| 新包设备回归 | API35 两项通过，包版本 9c9013ca | [原始输出](./row-header-device-passed.txt)、[来源与范围](./device-summary.json) |
| CI 完整应用 JVM | 1234 项，零失败或跳过 | [下载并校验摘要后的统计](./ci-jvm-summary.json)、[相关方法](./ci-key-editor-cases.txt) |
| 签名 ARM64 Release | 通过；仅直连 Rime/C++ 原生库、2 个 dex、16 KiB 对齐 | [实际校验输出](./arm64-apk-verification.json) |

[CI 40428247791](https://github.com/SandyYuR/fcitx5-android/actions/runs/40428247791) 全绿，[签名 ARM64 产物](https://github.com/SandyYuR/fcitx5-android/actions/runs/40428247791/artifacts/12701436839)。[工作流和产物摘要](./ci-summary.json)。CI 在临时验证分支执行，验证的 SHA 与当前远端 perf 完全相同；临时分支已清理。

两项设备用例检查真实 Android 控件的绑定、复用与边界；拖动卡片底色和圆角回归在 API28 Robolectric 执行。此前输入、提示、关机与恢复设备记录保留其原始 SHA，没有改记为本批新包执行。

本地编译和模拟器并行时接近工作区 8 GiB 内存上限，暂时停止模拟器后完成全量构建；随后重启、重新安装新包并执行上述设备用例。该资源调整没有修改源码。

发行与 CI 仍仅 arm64-v8a；本地 x86_64 Debug 用于无 KVM 的 API35 模拟器，不作为真机性能数据。
