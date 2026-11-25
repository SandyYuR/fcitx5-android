# 2026-11-25 九键音节栏排序与模糊音节保留

本轮修九键完音节栏的两个用户可见问题，交付 `android-sdk-20261125-3`，并在输入法默认分支更新版本锁、用本机设备回归确认。触发来源是用户提供的补丁 `librime-tab-fuzzy-and-order.patch`；补丁未在设备上验证，本文记录实际采用的收窄实现与验证范围。

## 问题

万象方案九键输入 `743649426`（`shengxiao`）时：

- 不选音节，候选含“生效”。
- 在音节栏点 `shen` 后，“生”等模糊派生候选消失；直接输入字母 `shenxiao` 又能出“生效”，说明方案模糊规则本身没问题。
- 音节栏按占用按键数升序排列，`pi`／`qi`／`ri` 在前，最想选的 `sheng` 被挤到后面。

## 根因

1. `ScriptSyllabifier::ApplyTabConstraints` 的完整音节分支只保留“解码名等于所选标签”的音节。`derive/(...e)ng(.*)$/$1n$2/` 从字典音节 `sheng` 派生出 `shen` 拼写：边上的标签是 `shen`，解码名仍是 `sheng`，被判定不匹配删除，于是“生”消失。
2. `ScriptTranslator::CollectSyllableTabs` 按音节图 `end_pos` 升序、同长按内部音节 ID 排序，没有字母序（`fo` 会排在 `en` 前面）。

## 修复

`src/rime/gear/script_translator.cc`（zhongzhou `8c92891fe08149b753c375ba71b5049dc2a7c40b`）：

- `CollectSyllableTabs` 在收集音节标签前记下 `first_syllable_tab`，收集完后只对 `[first_syllable_tab, end)` 做 `stable_sort`：`span` 大在前，同 `span` 按 `label` 字母升序。简拼（xlit）标签与原始按键标签仍在排序之后追加，位置不变；没有数字音节时排序区间为空，字母输入的标签保持为空。
- `ApplyTabConstraints` 的完整音节分支在 `decoded_base == label_base` 之外，增加“方案模糊投影能否从所选标签派生出该音节”的判断（`FuzzySpellingMatches`，`script_translator.cc:839-849`，调用 `FuzzySpellingProjection::Matches`）。只有当前输入边上确实存在“标签→该音节”的模糊派生关系才保留，而不是按边类型 `kFuzzySpelling` 粗放保留所有同跨度边。这样选 `shen` 后保留 `sheng` 音节，但不把无关音节带进候选。
- 未改：单字母简拼、原始按键标签的生成位置；table／reverse_lookup 翻译器的标签；App 端代码。

用户补丁的保留规则是“边长度已与所选范围一致，且类型为 `kFuzzySpelling` 时保留”。实际实现没有用边类型，因为同跨度边既包括方案模糊规则派生出的音节，也包括仅数字重码或补全进入的音节；改用既有 `FuzzySpellingProjection` 关联后，`shen` 仍能保留 `sheng`，同时不会把方案未书写的反向关系带进来。

## 回归测试

`test/script_translator_tab_test.cc` 新增 `ScriptTranslatorTab` 用例，关键三条：

- `OrdersSyllableTabsLongestFirstThenAlphabetically`：5 键输入 tabs 为 `sheng:5|pie:3|qie:3|she:3|p:1|q:1|r:1|s:1|7:1`；`shen` 是模糊拼写、需要 4 键，不在 5 键边上显示；单字母简拼与原始按键标签在末尾。
- `FuzzyTabSelectionKeepsDerivedSyllable`：4 键输入 tabs 为 `shen:4|p:1|q:1|r:1|s:1|7:1`；选中 `shen` 后候选同时含“沈”和模糊派生音节的“生”。
- `FuzzyTabSelectionKeepsWholeSentence`：`shenxiao` 整句未选中时含“生效”，选中 `shen` 后仍含“生”和“生效”。

其余用例覆盖单字母与原始按键标签留在末尾、无模糊规则方案不变、字母输入无音节栏、混合输入的展示方向、派生关系必须到达所选输入边等边界。

`test/script_translator_literal_tab_test.cc` 的既有字面标签与模糊保护用例（`ShowsOnlyZuanForLiteralZuanKeys`、`ZuanSelectionKeepsZhuanSuPhrase`、`ZhuangInputDoesNotOfferZuanOrDropZhuang` 等）继续通过；测试数据为 `data/test/dictionary_tab_test.dict.yaml`、`dictionary_tab_ordered_test.dict.yaml`、`dictionary_tab_mixed_test.dict.yaml`。

## 交付

第一版 librime 定制源码 `8c92891f…`（官方基线 `f70ae3c0b2dab61553cc74516268c0633a93dba0`），标签 `android-sdk-20261125-3`，Release 发布于 2026-11-25T05:04:54Z：

| 产物 | 大小 | SHA-256 |
| --- | --- | --- |
| `rime-android-sdk.zip` | 22,614,507 B | `d62efe3d1d008ab6809a8f7c0cfaba382494bed27eb905b6be6d7e04860e7e7d` |
| `sdk-manifest.json` | 106,831 B | `ed37b02d2e01640d24f53ea8010cf987974dbc824c72df8a1a089bc0264808da` |
| `SHA256SUMS` | 87 B | `155b7f5d5968aabac974b27142bcb2bbc8411e1313de4e5d24d1a65ca8ee8f57` |

CI：Commit CI #66（`45548608039`）、Android SDK #44（`45548608100`）与 Android ARM64 engine #17（`45548608032`）在 `zhongzhou` push 上通过；Android SDK #45（`45550353907`）在标签 ref 上通过并由 CI 发布。四 ABI 齐全，同日旧标签 `android-sdk-20261125-1`／`-2` 与既有产物未覆盖。

### 同步上游 1.18.0 后重建（`android-sdk-20261125-4`）

`zhongzhou` 随后同步上游 1.18.0，定制源码推进到 `83fbd0810e8c24db87f3b9dc9acce193401c7c4f`（官方基线 `1c0a468e7fff2b2763400abe1e892ed66d08b00e`）。源码 CI 与 Android SDK CI 在 `83fbd081` 上通过（Commit CI 唯一非成功项是 macos-26 构建被取消，按既有约定不计入；Linux／Windows／Docker／lint 全通过），标签 `android-sdk-20261125-4` 在该提交上创建并由 CI 发布：

| 产物 | 大小 | SHA-256 |
| --- | --- | --- |
| `rime-android-sdk.zip` | 22,640,937 B | `ed02b4f090a28899389e476082be46312a31e20cb91b3d2782c387ab294d2bfa` |
| `sdk-manifest.json` | 106,831 B | 由 Release 附带，`api_digest` `54be4177…` |
| `SHA256SUMS` | 87 B | 内容即上述 ZIP 摘要 |

`sdk-manifest.json` 复核：`schema=1`、`librime_commit=83fbd081…`、`upstream_commit=1c0a468e…`、四 ABI 齐全，与版本锁一致。`-4` 与既有 `-1`／`-2`／`-3` 标签及产物并存，未覆盖任何已发布内容。

## 输入法消费

`SandyYuR/fcitx5-android` 默认分支原提交 `bcfe31fe3`（构建(引擎): 更新 Android SDK 到 android-sdk-20261125-3）把 `native/rime-sdk.lock.json` 更新为标签 `android-sdk-20261125-3`、`librime_commit` `8c92891f…`、`upstream_commit` `f70ae3c0…`、`sha256` `d62efe3d…`。

`-4` 发布后，该提交按用户授权改写为 `1b8cbf2c0`（同为“构建(引擎): 更新 Android SDK 到 android-sdk-20261125-4”，作者与提交日期保持原样），只把锁改成标签 `android-sdk-20261125-4`、`librime_commit` `83fbd081…`、`upstream_commit` `1c0a468e…`、`sha256` `ed02b4f0…`；其上提交按原样重放为默认分支头 `f48ab5742`。`git diff --stat b063722f5 f48ab5742` 只有 `native/rime-sdk.lock.json` 的 4 行增删，其余树完全一致。

`-3` 阶段的消费记录（已被 `-4` 取代，保留追溯）：删除 `native/rime-sdk` 后重跑 `prepareRimeSdk` 成功；`BUILD_ABI=x86_64 :app:assembleFxDebug` 产出 `…-3-gbcfe31fe3-fx-x86_64-debug.apk`（26,018,533 B），APK SHA-256 `f835d0c48b8469362a1b2d6898263e55e5a0f248f5b91b17d50cfd392b6188c3`，ELF build id `8c545d0372d22a6abea96c6d458fa14416b2110a`。

`-4` 阶段的复验：

- 把 Release 的 `rime-android-sdk.zip` 放进 Gradle 的 SDK 缓存后重跑 `prepareRimeSdk --offline`：BUILD SUCCESSFUL。`RimeSdkArchive.extract` 对 `schema_version`、`librime_commit`、`upstream_commit`、`abis` 的硬校验全部通过，说明标签、源码提交与 ZIP 摘要三者自洽。
- `BUILD_ABI=x86_64 :app:assembleFxDebug` 产出 `…-4-gf48ab5742-fx-x86_64-debug.apk`（26,057,597 B），APK SHA-256 `c509aacbf7ea55bb05938477b7e018e3b8cfa361dd49dfe744a8fb9d63e90b22`。
- `tools/verify-direct-rime-apk.py`：native 只有 `libc++_shared`／`libdirect-rime`／`libquickjs`，`fcitx_dependencies` 为空，page size 16384，ClearURLs 206 providers。
- `tools/audit-direct-rime-link.py`：7 个静态预编译档案（librime 125 个对象、spdlog 5、marisa 9、opencc 19、yaml-cpp 24、leveldb 34、lua 32），`fcitx_cpp_symbols` 为空，ELF build id `c4a7070ea2b1cbd561dcd69db81d216f4a3d31d0`，`apk_native_sha256` `c17164e8b8f29bb8fdbec89263c0f98c6ce5f24d46e6dafe178635ccf14baaa9`，`map_sha256` `9c5baa7d9bbb6ae079231371a748086328557a879a81731dae2992fd4f7a1e06`。
- 该 APK 用 `adb install -r` 覆盖安装在同一模拟器上（保留数据），设备结论对应本次 `-4` native 二进制。

## 设备验证

环境：唯一的 AOSP API 35 x86_64 模拟器，输入法内置 `SandyYuR/librime` 引擎，工作目录 `rime-wanxiang`，万象九键方案，键盘测试面板输入；安装的是 `-4` 构建的 APK（`adb install -r` 覆盖，保留设备数据）。

1. 九键输入 `743649426`（`shengxiao`）：预编辑为模糊归一后的 `shen xian`，前四候选 `神仙`／`渗线`／`生效`／`笙箫`，不选音节即可出“生效”；音节栏自上而下为 `sheng`、`shen`、`pie`、`qie`、`she`、`pi`、`qi`，跨度 5 的 `sheng` 在最前，同跨度内按字母升序（`pie`＜`qie`＜`she`、`pi`＜`qi`）。
2. 清空后单独输入 `7436`（`shen`）：音节栏首位为 `shen`（其后 `pie`／`qie`／`she`／`pi`／`qi`／`ri`）。选中 `shen` 后 tab 列消失，候选仍含“升／圣／盛／生”（`sheng` 音节）；展开候选第二行为 `省 生 剩`，即模糊派生出的“生”在选音节后依然可选，并可正常上屏。
3. 单键输入（如 `7`）仍显示单字母简拼 `p`／`q`／`r`／`s` 与原始按键 `7`，位置在列表末尾；关闭再重开面板后输入恢复原 T9 布局。

设备侧限制：纵向音节栏是高亮条目自绘视图，`uiautomator` 抓不到节点，`adb shell input tap` 在部分布局下会落到候选行或工具栏行。上表的音节栏顺序与“选中 `shen`”结论通过逐步截图比对与候选区实际内容确认，未使用自动化断言。

## 未覆盖

- 设备验证只覆盖 x86_64 模拟器与万象九键，未在真实手机、其他 Android 版本或其他 ABI 设备上复验。
- 未验证其他方案的九键音节栏；字母输入、无模糊规则方案的既有行为由 native 用例覆盖。
- 若后续发现保留范围仍宽，应按方案模糊规则收窄，不放宽“选 `shen` 后仍能选到生”和“不选音节能出生效”两条可见行为。
