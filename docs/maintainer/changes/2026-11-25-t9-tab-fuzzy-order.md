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

librime 定制源码 `8c92891f…`（官方基线 `f70ae3c0b2dab61553cc74516268c0633a93dba0`），标签 `android-sdk-20261125-3`，Release 发布于 2026-11-25T05:04:54Z：

| 产物 | 大小 | SHA-256 |
| --- | --- | --- |
| `rime-android-sdk.zip` | 22,614,507 B | `d62efe3d1d008ab6809a8f7c0cfaba382494bed27eb905b6be6d7e04860e7e7d` |
| `sdk-manifest.json` | 106,831 B | `ed37b02d2e01640d24f53ea8010cf987974dbc824c72df8a1a089bc0264808da` |
| `SHA256SUMS` | 87 B | `155b7f5d5968aabac974b27142bcb2bbc8411e1313de4e5d24d1a65ca8ee8f57` |

CI：Commit CI #66（`45548608039`）、Android SDK #44（`45548608100`）与 Android ARM64 engine #17（`45548608032`）在 `zhongzhou` push 上通过；Android SDK #45（`45550353907`）在标签 ref 上通过并由 CI 发布。四 ABI 齐全，同日旧标签 `android-sdk-20261125-1`／`-2` 与既有产物未覆盖。

## 输入法消费

`SandyYuR/fcitx5-android` 默认分支提交 `bcfe31fe3`（构建(引擎): 更新 Android SDK 到 android-sdk-20261125-3）把 `native/rime-sdk.lock.json` 更新为标签 `android-sdk-20261125-3`、`librime_commit` `8c92891f…`、`upstream_commit` `f70ae3c0…`、`sha256` `d62efe3d…`。

- 删除 `native/rime-sdk` 后重跑 `prepareRimeSdk`：BUILD SUCCESSFUL，生成的 `sdk-manifest.json` 记录同一对源码提交。
- `BUILD_ABI=x86_64 :app:assembleFxDebug` 产出 `…-3-gbcfe31fe3-fx-x86_64-debug.apk`（26,018,533 B）。
- `tools/verify-direct-rime-apk.py`：APK SHA-256 `f835d0c48b8469362a1b2d6898263e55e5a0f248f5b91b17d50cfd392b6188c3`，native 只有 `libc++_shared`／`libdirect-rime`／`libquickjs`，`fcitx_dependencies` 为空，16 KB 页大小。
- `tools/audit-direct-rime-link.py`：7 个静态预编译档案（librime 125 个对象），`fcitx_cpp_symbols` 为空，ELF build id `8c545d0372d22a6abea96c6d458fa14416b2110a`。
- 该 APK 与设备上安装包的 `lib/x86_64/libdirect-rime.so` SHA-256 相同（`7ee360a28092cb2af48ead869a203525dba77d4527bfc87d9fbb48687753be69`），设备结论对应同一 native 二进制。

## 设备验证

环境：唯一的 AOSP API 35 x86_64 模拟器，输入法内置 `SandyYuR/librime` 引擎，工作目录 `rime-wanxiang`，万象九键方案，键盘测试面板输入。

1. 九键输入 `743649426`（`shengxiao`）：预编辑为模糊归一后的 `shen xian`，前四候选 `神仙`／`渗线`／`生效`／`笙箫`，不选音节即可出“生效”；音栏为 `sheng:5`、`pie:3`／`qie:3`／`she:3`、`pi:2`／`qi:2`／`ri:2`，跨度 5 的 `sheng` 在最前，同跨度内按字母升序。
2. 清空后输入 `7436`（`shen`）出现 `shen` 音节栏；选中后 tab 列消失，候选仍含“升／圣／盛”（`sheng` 音节），展开候选为 `神 升 圣 盛 肾 审`、`省 生 剩`、`渗 深 声`、`胜 绳 森`，模糊派生的“生”保留。
3. 单键输入（如 `7`）仍显示单字母简拼 `p`／`q`／`r`／`s` 与原始按键 `7`，位置在列表末尾；关闭再重开面板后输入恢复原 T9 布局。

## 未覆盖

- 设备验证只覆盖 x86_64 模拟器与万象九键，未在真实手机、其他 Android 版本或其他 ABI 设备上复验。
- 未验证其他方案的九键音节栏；字母输入、无模糊规则方案的既有行为由 native 用例覆盖。
- 若后续发现保留范围仍宽，应按方案模糊规则收窄，不放宽“选 `shen` 后仍能选到生”和“不选音节能出生效”两条可见行为。
