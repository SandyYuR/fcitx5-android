# 2026-11-16 至 11-22 代码提交与文档对照 · 2026-11-22

本页以代码默认分支 **`fx-rime-only`，`b4670127df20e8a8c2a6b3ef59c694df5aa3529e`** 为事实源，以 `docs@e19ec592` 为文档基线，逐条核对 2026-11-16 至 2026-11-22（UTC）合入默认分支的 41 个提交。日期按 Asia/Shanghai 显示，因此末尾几项显示为 11-23。上一轮固定基线见[2026-10-30 核对](./)与[2026-11-05 核对](./2026-11-05)；现行功能与源码入口见[覆盖表](./feature-coverage)。

本页只做代码与文档的对照登记，不重新执行 Android、云端服务或性能测试；各对照页与报告中的验证结论仍以其原文和条件为准。

## 逐提交对照

状态说明：**已登记**＝有对应日期对照页；**已说明**＝现行功能页或维护页已写，暂无独立对照页；**缺口**＝现行文档未说明；**与文档矛盾**＝现行代码与已发布说明不符。

| 提交 / 日期 | 代码改动 | 状态 | 文档位置 | 备注 |
| --- | --- | --- | --- | --- |
| [f340e09a4](https://github.com/SandyYuR/fcitx5-android/commit/f340e09a4d596c209934e50da2fd7d63038f780b)<br>2026-11-16 | 功能(引擎): 更新 Rime 与详细日志、完善九键模糊筛选 (#41) | 已登记 | [音节筛选与模糊拼写](./2026-11-16-syllable-fuzzy-filter-fix) · [引擎更新](/maintainer/engine-update) |  |
| [95c0fafb2](https://github.com/SandyYuR/fcitx5-android/commit/95c0fafb21bb29752ae04c4518284fb6138d7307)<br>2026-11-17 | 功能(键盘): 空格键支持自定义键面标签 | 已说明 | [SpaceKey](/features/keys/space-key) · [覆盖表 11-17 增量](./feature-coverage) | 无独立对照页，覆盖表已列 |
| [61c3b2637](https://github.com/SandyYuR/fcitx5-android/commit/61c3b2637db60865b3df5467200a49e6bc6c7943)<br>2026-11-17 | 功能(工具栏): 语言切换按钮按中英状态切换图标 | 已说明 | [LanguageKey](/features/keys/language-key) · [Kawaii Bar](/features/kawaii-bar) | 同上 |
| [1ee9d1bfc](https://github.com/SandyYuR/fcitx5-android/commit/1ee9d1bfc5dfe3d914254a1829fe61444324561e)<br>2026-11-17 | 功能(主题): 副标签可固定显示在按键侧边 | 已说明 | [主题编辑器](/features/theme/theme-editor) | 同上 |
| [2d0e24872](https://github.com/SandyYuR/fcitx5-android/commit/2d0e2487263d78644c20690fb59867b6c91493d9)<br>2026-11-17 | 功能(工具栏): 有道与哔哩哔哩翻译按钮改用官方标志 | 已说明 | [图标主题](/features/theme/icon-themes) | 同上 |
| [d20deb92e](https://github.com/SandyYuR/fcitx5-android/commit/d20deb92e11215e3a7fe93985547eeccb4cdda3b)<br>2026-11-17 | 修复(键盘): 水波纹覆盖辅助栏按键并按窗口坐标换算 | 已说明 | [覆盖表 11-17 增量](./feature-coverage) | 同上 |
| [b856c244d](https://github.com/SandyYuR/fcitx5-android/commit/b856c244de8f344952be37a5d83efbd4ccdc5162)<br>2026-11-18 | 修复(键盘): 空格键自定义标签在合成态与重建后丢失 | 已登记 | [空格标签与键盘状态修复](./2026-11-18-space-label-deferred-rebuild) | 该页只写末提交 `81271880`，未逐条列出同批五个提交 |
| [c74347d3e](https://github.com/SandyYuR/fcitx5-android/commit/c74347d3ecf05c2641cc0905b4d5ef63d270ca92)<br>2026-11-18 | 修复(工具栏): 旧图标主题在 ASCII 态掉回内置语言图标 | 已登记 | 同上 | 同上 |
| [7155f7e30](https://github.com/SandyYuR/fcitx5-android/commit/7155f7e30c1e7d6309c95a5ddbc5fffb85c24a8a)<br>2026-11-18 | 修复(键盘): 副标签侧边位置在 RTL 下左右翻面 | 已登记 | 同上 | 同上 |
| [d73753f12](https://github.com/SandyYuR/fcitx5-android/commit/d73753f12ed65de12983d15378c61b9e540b8d25)<br>2026-11-18 | 修复(键盘): 单字母空格键标签被 Caps 改写成大写 | 已登记 | 同上 | 同上 |
| [812718800](https://github.com/SandyYuR/fcitx5-android/commit/8127188008f47e385aa3a660e4e796a31f7d9994)<br>2026-11-18 | 修复(键盘): 延迟重建后刷新空格键标签 | 已登记 | 同上 |  |
| [b3d832f0e](https://github.com/SandyYuR/fcitx5-android/commit/b3d832f0e1b52208b1042f7aa2b88b654ea404fa)<br>2026-11-18 | 构建(Codespaces): 配置中州鹅云端开发与容器验证 (#46) | **缺口** | — | 维护手册与本机构建页均未提 `.devcontainer/`、`codespaces.yml` |
| [b937cb796](https://github.com/SandyYuR/fcitx5-android/commit/b937cb79698312cb24f41042eab8faf269df8708)<br>2026-11-18 | 修复(输入统计): 区分热力图频次并兼容 Monet 取色 | **缺口** | [输入统计](/features/input-statistics) | 未写五档热度、未按键中性色与 4.5:1 文字对比 |
| [abda9da86](https://github.com/SandyYuR/fcitx5-android/commit/abda9da86888aba5cf258c070ec0dd1f8713de30)<br>2026-11-18 | 功能(JS 工具): 自动在面板标题栏提供工具切换 | **缺口** | [JS 插件](/features/voice-input-js) | 未写标题栏直达图标与切换菜单（`JsAppSwitcher`）；11-19 对照页只提到同批推送 |
| [dfc400711](https://github.com/SandyYuR/fcitx5-android/commit/dfc400711058cca38ac2900126e3fbd72e140670)<br>2026-11-19 | 修复(工具栏): 内嵌建议态显示返回箭头并可退出 | 已登记 | [工具栏内嵌建议退出](./2026-11-19-inline-suggestion-exit) · [Kawaii Bar](/features/kawaii-bar) |  |
| [34828a3db](https://github.com/SandyYuR/fcitx5-android/commit/34828a3dba117d40f4c876f2e431348e6f14a4a7)<br>2026-11-19 | 维护(仓库): issue 模板只保留使用手册入口 | 已登记 | 同上 | 仓库入口，不在文档站重复 |
| [23b8e70be](https://github.com/SandyYuR/fcitx5-android/commit/23b8e70be5269f7295bfbef2869dfa26edd43c54)<br>2026-11-19 | 文档(README): 改写为面向用户的项目说明 | 已登记 | 同上 | 同上 |
| [8e6749c92](https://github.com/SandyYuR/fcitx5-android/commit/8e6749c927c9fbfe6127e46abb7be5c55fc2692d)<br>2026-11-19 | 功能(键盘): 按键支持图标主题槽位并叠加副标签 | 已说明，记录过时 | [图标主题](/features/theme/icon-themes) · [按键图标主题](./2026-11-17-key-svg-icons) | 对照页仍写“未合入默认分支”，SHA 为整理前的 `064b0c33c` 等 |
| [da8561cd8](https://github.com/SandyYuR/fcitx5-android/commit/da8561cd8df7f500a0a812fadc515761ff1a2ee0)<br>2026-11-19 | 功能(界面): 工具栏与标题栏按钮接入图标主题 | 已说明，记录过时 | 同上 | 同上 |
| [229d7cd27](https://github.com/SandyYuR/fcitx5-android/commit/229d7cd273e24bff9367688d20140025ceceedf9)<br>2026-11-19 | 功能(键盘): 增加分体键盘工具栏动作 | 已说明，记录过时 | 同上 · [主题编辑器](/features/theme/theme-editor) | 同上 |
| [460d3939c](https://github.com/SandyYuR/fcitx5-android/commit/460d3939c7a1535806942593bd4f4025347cb166)<br>2026-11-19 | 功能(主题): 内置水彩与普蓝成套主题 | 已说明，记录过时 | 同上 | 同上 |
| [d2b992b6b](https://github.com/SandyYuR/fcitx5-android/commit/d2b992b6b8d8ff740cfa98d828d81b70429aaadc)<br>2026-11-19 | 修复(主题): 按评审修正迁移、颜色与图标刷新 | 已说明，记录过时 | 同上 | 同上 |
| [577872ddd](https://github.com/SandyYuR/fcitx5-android/commit/577872ddda1fd82723981e0e757ea85b14a94206)<br>2026-11-19 | 修复(主题): 统一图标主题扫描与选择的发布边界 | 已说明，记录过时 | 同上 | 同上 |
| [5fb2acd0e](https://github.com/SandyYuR/fcitx5-android/commit/5fb2acd0e3d93bf713a8ad68236a1017b9069cdb)<br>2026-11-20 | 构建(CI): 四 ABI 同时构建并按 ABI 命名发布资产 | **与文档矛盾** | [默认分支 ABI 发布](./2026-11-20-ci-three-abi-release) | 文档记录的是已不在默认分支的 `c037dc143`（三 ABI）；现行 Commit CI 为四 ABI，含 `x86` |
| [e1014f9cf](https://github.com/SandyYuR/fcitx5-android/commit/e1014f9cf313c5ba2612170597db4ce5a93da8c3)<br>2026-11-20 | 构建(CI): Direct Rime 完整前端构建改为手动 | 已说明 | [维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN) · [引擎更新](/maintainer/engine-update) | 无独立对照页 |
| [858e48510](https://github.com/SandyYuR/fcitx5-android/commit/858e48510f669b23b9e5d006d427037ad94336e6)<br>2026-11-20 | 构建(native): 更新 Rime SDK 锁到 android-sdk-20261120-1 | **缺口** | — | 现行页仍写 `android-sdk-20261116-2` 为现行 SDK |
| [4200b65d9](https://github.com/SandyYuR/fcitx5-android/commit/4200b65d9ba9802a4d6e75d00a6619eb2732443e)<br>2026-11-20 | 修复(统计): 按行号还原键盘热力图 | 部分说明 | [输入统计](/features/input-statistics) | 未写按行号分组及旧归档兼容 |
| [d2745125e](https://github.com/SandyYuR/fcitx5-android/commit/d2745125e41ef67ff3e2c63c31b7442e01d5ed16)<br>2026-11-20 | 修复(设置): 预览键盘改用真实输入法布局 | **缺口** | [主题编辑器](/features/theme/theme-editor) · [分体键盘](/features/keyboard/split-keyboard) | 主题页、分体校准页预览改按真实布局分节，未登记 |
| [f26ea22f4](https://github.com/SandyYuR/fcitx5-android/commit/f26ea22f404062679ae238fa2cb12134192a0370)<br>2026-11-21 | 修复(设置): 布局编辑器可预览与编辑层子布局 | 已说明 | [布局编辑器](/features/editor/layout-editor#层子布局与数字键盘) |  |
| [899eedbf1](https://github.com/SandyYuR/fcitx5-android/commit/899eedbf1b7eb8475f7f2a3e162e6ec014763be5)<br>2026-11-21 | 功能(键盘): 数字键盘自动跟随布局的数字子布局 | 已说明 | [布局编辑器](/features/editor/layout-editor#层子布局与数字键盘) |  |
| [8bb9fe7af](https://github.com/SandyYuR/fcitx5-android/commit/8bb9fe7af7c0d11387fcdf2d00ae1881b043bde6)<br>2026-11-21 | 功能(宏按键): 增加输入统计与常用应用动作入口 | 已登记 | [MacroKey 应用动作](./2026-11-21-macrokey-app-actions) |  |
| [b50c9dfe1](https://github.com/SandyYuR/fcitx5-android/commit/b50c9dfe1e1b690e8debaa10ef6c5c666ab89a4e)<br>2026-11-21 | 功能(网页编辑器): 同步键盘副标签主题偏好 | 已登记 | [网页编辑器对齐](./2026-11-21-web-editor-parity) |  |
| [ee0e8a91b](https://github.com/SandyYuR/fcitx5-android/commit/ee0e8a91b5065ad5ff5c93fd171700fdff4f311b)<br>2026-11-22 | 功能(候选窗口): 支持跟随光标及实体键盘位置设置 (#48) | **缺口** | [候选窗增强](/features/candidate-window) | 未写「跟随光标」位置与「实体键盘候选窗口位置」 |
| [9fe78204d](https://github.com/SandyYuR/fcitx5-android/commit/9fe78204dda51c40ec78155f7da2437c77326807)<br>2026-11-22 | 修复(键盘): 保留双指同键点按并分别处理取消 | **缺口** | — | 修复类，建议在键盘行为或正确性约束补一句 |
| [1a03df8d1](https://github.com/SandyYuR/fcitx5-android/commit/1a03df8d17328fae5a22226270f0cf1fb068997a)<br>2026-11-22 | 优化(输入): 后台预热 Rime 模型和主词典 | 已说明 | [直连架构](/maintainer/implementation/direct-rime-architecture) · [CyIME 与冷缓存](/maintainer/perf/cyime-wanxiang-2026-11-21/) | 覆盖表未列 `DirectRimeAssetPrewarmer` |
| [9a01676c1](https://github.com/SandyYuR/fcitx5-android/commit/9a01676c1ba0defed584303783b3118efbed619f)<br>2026-11-23 | 修复(符号面板): 恢复长按浮层的滑动选择与松手关闭 | **缺口** | [符号面板](/features/symbol-panel) | 未写长按浮层可滑动选择、松手关闭 |
| [e50ef7636](https://github.com/SandyYuR/fcitx5-android/commit/e50ef7636717a378ef8686258d3e2eaff2d0c814)<br>2026-11-23 | 修复(键盘): 密码框可选用 Rime 输入并迁移开关到中州韵设置 | 已说明 | [Rime 引擎与中州韵设置](/features/rime-enhancements) · [输入统计](/features/input-statistics) | 覆盖表未列 |
| [467415cbb](https://github.com/SandyYuR/fcitx5-android/commit/467415cbbbb5ce73ad6374263421ab7846b9cc74)<br>2026-11-23 | 功能(候选): 万象拼音支持手动排序菜单 (#51) | 已说明 | [候选窗增强](/features/candidate-window#万象拼音手动排序) | 覆盖表原写 PR #52，已更正 |
| [e1c5ec3ed](https://github.com/SandyYuR/fcitx5-android/commit/e1c5ec3ed0c5b02441a5569cdf71ac90c5e0dee6)<br>2026-11-23 | 优化(键盘): 复用气泡并直接重绘固定尺寸标签 | 已说明 | [气泡、输入调度与长句 CPU](/maintainer/perf/input-fluency-2026-11-22/) |  |
| [8697a7ebb](https://github.com/SandyYuR/fcitx5-android/commit/8697a7ebbcf0cb88fef90c206191aeaebe3480f9)<br>2026-11-23 | 优化(输入): 在 Rime 线程消费交互任务队列 | 已说明 | 同上 · [直连架构](/maintainer/implementation/direct-rime-architecture) |  |
| [b4670127d](https://github.com/SandyYuR/fcitx5-android/commit/b4670127df20e8a8c2a6b3ef59c694df5aa3529e)<br>2026-11-23 | 修复(输入): 等待按键通过 Rime 队列恢复 | 已说明 | 同上 |  |

## 需要更新的现行页面

| 缺口 | 代码依据 | 建议更新位置 |
| --- | --- | --- |
| Commit CI 已改为四 ABI：`arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64`，`prepare-release-assets.py` 以四个期望 ABI 校验 | `.github/workflows/ci.yml` 第 30–37 行矩阵、第 221 行 `--expected-abis`；提交 `5fb2acd0` | [默认分支 ABI 发布](./2026-11-20-ci-three-abi-release)补“后续更正”一节；[维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)、[引擎更新](/maintainer/engine-update)、[安装与升级](/guide/installation)改为四种签名包与四个资产 |
| 现行 SDK 锁为 `android-sdk-20261120-1`，librime `7c61add03d395ca651a1cca9dfd8f31807298a8d`，ZIP SHA-256 `fb574ba4…e80f` | `native/rime-sdk.lock.json` 第 4–7 行；提交 `858e4851` | [引擎更新](/maintainer/engine-update)、[维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)、[Rime 引擎与中州韵设置](/features/rime-enhancements#音节标签与模糊拼写)、[正确性约束](/maintainer/implementation/correctness-contracts)中“现行 SDK 为 `android-sdk-20261116-2`”的句子改为历史表述并补新锁；新版 SDK 的引擎侧变化需按 librime 提交另行核对 |
| 浮动候选窗口新增「跟随光标」位置；「根据输入设备而定」模式下另有「实体键盘候选窗口位置」 | `FloatingCandidatesPosition.kt` 第 34 行；`AppPrefs.kt` 第 560–561 行；`values-zh-rCN/strings.xml` 第 548、553 行；提交 `ee0e8a91` | [候选窗增强](/features/candidate-window)「独立候选窗」一节；用户手册候选窗口段落；覆盖表补 `FloatingCandidatesPosition`、`CandidatesView` |
| 符号面板长按浮层恢复滑动选择、松手提交并关闭 | `SymbolPanelUi.kt`、`PickerWindow.kt`；提交 `9a01676c` | [符号、表情与颜文字](/features/symbol-panel) |
| JS 面板标题栏自动列出同插件目录的其他 JS App：宽度允许时显示直达图标，其余进入切换菜单 | `JsAppSwitcher.kt`；提交 `abda9da8` | [JS 插件](/features/voice-input-js)与 [JS 宿主协议](/maintainer/implementation/js-voice-plugins) |
| 热力图五档热度、未按键中性色、文字对比；按行号分组并兼容旧归档 | `StatsHeatmapRows.kt`；提交 `b937cb79`、`4200b65d` | [输入统计](/features/input-statistics)按键热力一节 |
| 主题页与分体校准页预览改按真实输入法布局分节 | 提交 `d2745125` | [主题编辑器](/features/theme/theme-editor)、[分体键盘](/features/keyboard/split-keyboard) |
| GitHub Codespaces 开发环境与容器 CI | `.devcontainer/devcontainer.json`、`.github/workflows/codespaces.yml`；提交 `b3d832f0` | [维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)构建环境一节 |
| 双指按同一键时分别处理两次点按与取消 | `BaseKeyboard.kt`；提交 `9fe78204` | 键盘行为说明或[正确性约束](/maintainer/implementation/correctness-contracts)补一句 |
| 按键图标主题与成套主题已于 11-19 进入默认分支 | 提交 `8e6749c9`…`577872dd` | [按键图标主题与成套主题](./2026-11-17-key-svg-icons)开头补“已合入默认分支”及整理后 SHA 对应 |

## 本次同步

上表“缺口”“与文档矛盾”按 `docs@e19ec592` 登记，同一批文档修改已处理：

- 四 ABI 发布：[安装与升级](/guide/installation)、[用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN)、[维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)、[引擎更新](/maintainer/engine-update)、[更新检查](/features/update-checker)改为四种签名包；[默认分支 ABI 发布](./2026-11-20-ci-three-abi-release)页首补“后续更正”，原记录保留。
- 现行 SDK 锁 `android-sdk-20261120-1`：[引擎更新](/maintainer/engine-update)、[维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)、[Rime 引擎与中州韵设置](/features/rime-enhancements)、[正确性约束](/maintainer/implementation/correctness-contracts)。
- 候选窗口「跟随光标」与「实体键盘候选窗口位置」：[候选窗增强](/features/candidate-window)、用户手册、[功能总览](/features/overview)。
- 符号面板长按浮层滑动选择与松手关闭：[符号、表情与颜文字](/features/symbol-panel)。
- JS 面板标题栏工具切换：[JS 插件](/features/voice-input-js)、[中文参考](/features/voice-input-js-reference-zh-CN)、用户手册。
- 热力图五档与按行号还原：[输入统计](/features/input-statistics)。
- 主题页预览按真实布局：[主题编辑器](/features/theme/theme-editor)。
- Codespaces 与容器 CI：[维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)、[Windows 本机构建](/maintainer/LOCAL_BUILD_ENVIRONMENT_zh-CN)、[维护首页](/maintainer/)。
- PR #50 服务队列在 `rime-main` 上执行、模型与主词典预热：[正确性约束](/maintainer/implementation/correctness-contracts)、[直连架构](/maintainer/implementation/direct-rime-architecture)、[Rime 引擎与中州韵设置](/features/rime-enhancements)。
- [按键图标主题与成套主题](./2026-11-17-key-svg-icons)页首补“已合入默认分支”与整理后 SHA，原记录保留。
- [覆盖表](./feature-coverage)更正万象手动排序为 PR #51 并补本轮源码入口；[对照索引](./)、[验证证据目录](/maintainer/evidence)、维护侧栏“代码与文档对照”同步补齐。

仍未写入现行文档：双指同键分别处理点按与取消（`9fe78204`）属内部修复，暂只在本页登记。

## 查证边界

对照依据为本地解压的默认分支源码、提交说明及 GitHub 提交文件列表；“已说明”只表示文档已有对应描述，不代表其文字与代码逐句一致。新版 SDK 的引擎侧提交与 `x86` Release 包尚未在本页核验运行结果，不写成已验证。
