# 2026-11-16 音节筛选与模糊拼写修复

> **后续复核：** 用户进一步明确九键音节栏只应显示本音节拼写，同时保留“转／装”“转速／专属”等方案允许的模糊候选。修复已整理为 `zhongzhou@9a317501…`，正式 `android-sdk-20261116-2` 已发布并核验，输入法默认分支重写为 `f340e09a…` 后按精确 lease 强推；本次 Commit CI、Direct Rime CI、实际设备与 Linux native 补验均完成，签名 Release 和 Nightly 已发布。下文保留原发布事实，字面展示与模糊候选分别验证的过程见[literal 音节栏与模糊候选复核](#literal-音节栏与模糊候选复核)，最终固定身份见[新版 SDK 消费与默认分支重写](#新版-sdk-消费与默认分支重写)。

本轮处理完整音节标签筛选中的模糊拼写回归，并配套更新输入法 SDK 与文档。源码修复、SDK 交付和输入法验证分别记录；原始版本与产物证据继续保留在[2026-11-15 引擎与日志记录](2026-11-15-engine-sdk-spdlog.md)。

## 固定源码与历史整理

最终 librime 源码为 [`8bbdf7733b31162a41baeb2b3bfe0f9962dc4a9a`](https://github.com/SandyYuR/librime/commit/8bbdf7733b31162a41baeb2b3bfe0f9962dc4a9a)。音节筛选提交修订后移到分支末尾；#1274 与下游文档提交因顺序变化获得新 SHA，manifest 的现行 `upstream_prs` 中 #1274 指针同步更新。历史 migration、原始来源与日期记录继续保留旧 SHA。

| 整理前提交 | 整理后提交 | 内容 |
| --- | --- | --- |
| `a586daa5e86a7dadf287187606f76983a494c1ae` | `8bbdf7733b31162a41baeb2b3bfe0f9962dc4a9a` | 修订音节排序与有序模糊筛选，移到末尾 |
| `e6f26cb0af0492e77c3f3569560687344f036cda` | `3bbbe5e1b248d48d8940917ab178e36c79161b3d` | #1274 紧凑词库与映射保护 |
| `ae914836746a0427dc18124273e1d4a9064df108` | `3d9c8543d8b4a13848fa98989601f3bd475a458a` | 下游文档与现行来源指针 |

提交总数仍为 1996，其中下游独有提交 12 个。最终源码与已完成宿主验证的 `ee7aa5d20c237b7fdc884fd7d173868a5a6e55d3` 相比，仅 `downstream/manifest.json` 的 #1274 现行指针一行变化；生产代码与测试字节一致。已发布的旧 SDK 标签和产物不覆盖。

## 被审版本与触发条件

被审源码提交为 librime `a586daa5e86a7dadf287187606f76983a494c1ae`。输入法代码 PR #41 的被审 HEAD 为 `c595263f2accb4959ee0a6b8228ac08d480e1d85`，其中 `android-sdk-20261115-2` 引用的 librime `da8d1e7c41c8abddea4f5a55b2983f91866b84a3` 包含同样的筛选实现。

原逻辑从 `speller/algebra` 抽出 `derive`／`fuzz` 后单独执行，遗漏了部署时前后规则的作用。例如：

```yaml
- xform/^qi$/ri/
- derive/^qi$/pi/
- xlit/abcdefghijklmnopqrstuvwxyz/22233344455566677778889999/
```

部署时 `qi` 先变成 `ri`，第二条规则不再命中；抽取后的逻辑却仍把 `qi → pi` 当成模糊关系。九键输入 `74` 并点选 `pi` 时，“七”因此被错误保留。仅比较完整 algebra 的最终数字结果也不能解决，因为 `qi`、`pi` 等本来就可能重码。

## 修复语义

`FuzzySpellingProjection` 重放完整的已部署 algebra，起点保持字典中的原始带调音节，按每条规则的添加与删除语义推进分支。只有实际命中的 `derive`／`fuzz` 建立模糊来源；后续变换传播已经建立的来源，删除规则连同分支移除来源。普通 `xform` 本身不会建立模糊关系，例如 `n → en` 的变换不能让点选 `en` 保留“嗯”。

每条最终分支同时保留经过的模糊标签及最终拼写 key。匹配时既核标签，也核实际 prism 的索引是否属于所选输入边；尾部分隔符、canonicalize 和补全边使用与音节图构建相同的匹配入口。这样既保留真实模糊关联，也排除只有最终数字编码碰撞、分支被删除或最终匹配到其他输入边的候选。

直接完整音节匹配仍忽略声调，单键简拼仍按首字母约束；没有模糊规则的方案继续按原直接匹配行为处理。输入法前端的 generation／`tabsRevision` 保护和输入 owner 不因本轮引擎算法修复改变。

## 回归覆盖

`test/script_translator_tab_test.cc` 的 `ScriptTranslatorTab` 保留原 6 个用例，新增 12 个，共 18 个用例。新增覆盖包括前置变换使原 derive 失效、derive 使用变换后的拼写、derive／fuzz 在最终键碰撞时保留来源、原始带调拼写、分支删除、匹配其他输入边、模糊后归一化、普通变换不建立模糊关系，以及尾部分隔符、canonicalize 和补全输入。

## 本机与远端验证

| 验证范围 | 实际结果与限制 |
| --- | --- |
| 宿主 `ScriptTranslatorTab` | 18 个目标用例实际执行通过；失败、禁用和错误均为 0 |
| 宿主完整 CTest | 2／2 目标通过；`rime_memory_test` 43 个、`rime_test` 210 个用例实际执行通过；另有历史 `DISABLED_CaseTranspose` 未执行，不能按完整无跳过验收 |
| Android SDK 固定构建脚本 Python 测试 | 22 个用例通过 |
| 输入法 `OrderedSpellingTabSdkTest` | 新增 3 个真实打包引擎回归已编译，并在 `09f710d7…` 的 Direct Rime CI `android_smoke` 中实际执行通过；失败、错误和跳过均为 0；分别检查前置变换使 derive 失效、真实 derive 在九键重码时保留关联、带调 derive 经后续归一化保持关联 |
| 最终源码 CI | [zhongzhou Commit CI 43746215579](https://github.com/SandyYuR/librime/actions/runs/43746215579) 的 14 个 job 全部通过 |

宿主用例结果限定于上述源码输入；编译 AndroidTest 本身不表示已完成设备验收。

## 正式 SDK 发布与核验

新 SDK 已发布为 [`android-sdk-20261116-1`](https://github.com/SandyYuR/librime/releases/tag/android-sdk-20261116-1)。输入法 `native/rime-sdk.lock.json` 更新到下表的正式标签、源码与包摘要；已发布的 `android-sdk-20261115-2` 及其固定产物保持原身份。

| SDK 身份 | 固定值 |
| --- | --- |
| Release 标签 | `android-sdk-20261116-1` |
| annotated tag 对象 | `224e4f735f4c0755737758abcd173e5d3b77cc5d` |
| 标签对应 librime 源码 | `8bbdf7733b31162a41baeb2b3bfe0f9962dc4a9a` |
| 官方基线 | `bb56876890ba60f446fa2640c244b1794b965eda` |
| 正式 `rime-android-sdk.zip` SHA-256 | `a566e36a3d050264308906b156ed627d37e89829d80bdbacc97f20b8fd6e8dfd` |
| 源码 tree | `daea73bea00e1e82651df4136ddc4cba640639f0` |
| Android 来源锁 SHA-256 | `54decf4d02a7fbdf97957adf0fd1b01d6f0fb49cbc7ea29e5cffef562f25cf33` |
| 构建工具 | NDK `28.0.13004108`、CMake `3.31.6`、Android API 23 |

| 交付验证 | 结果 |
| --- | --- |
| [SDK 分支运行 43746215697](https://github.com/SandyYuR/librime/actions/runs/43746215697) | 四 ABI、host 与 package 全部通过；分支运行的 release job 按流程跳过，不作为正式发布证据 |
| [正式标签运行 43747303355](https://github.com/SandyYuR/librime/actions/runs/43747303355) | 7 个 job 全部通过，无跳过，包含实际 Release 发布 |
| 标签 host 音节目标回归 | 18 个用例实际执行通过；失败、错误、跳过和禁用均为 0 |
| 标签 host 完整回归 | 262 个用例通过，另有 1 个历史 disabled 用例未执行；与目标回归的无跳过结果分开记录 |
| 本机下载正式 Release ZIP | 包摘要与锁一致，881 个文件及 28 个静态归档逐项核验通过 |
| 四 ABI 来源与链接 | `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 均匹配 `8bbdf773…` 源码、上述源码 tree 与来源锁；最终链接全部通过 |

分支 CI 构建 ZIP 与正式标签 ZIP 的 SHA-256 不同。输入法仅消费上述正式标签 ZIP，不能把分支包摘要或产物替代 Release 包。SDK 发布和静态链接通过不替代输入法 APK 中实际打包引擎的设备回归；应用侧本机验证及 PR CI 的对应源码和 APK 结果见下节。

## 输入法消费与验证

输入法配套源码为 [`09f710d7b2cb213d7d1a72245c9b2ab18e052fe9`](https://github.com/SandyYuR/fcitx5-android/commit/09f710d7b2cb213d7d1a72245c9b2ab18e052fe9)，已更新 [PR #41](https://github.com/SandyYuR/fcitx5-android/pull/41) 的 `feat/rime-engine-log-switch` 分支。该提交消费上述正式 SDK，并保留原生日志开关、旧性能研究冻结身份和新的有序音节设备回归。

| 本机验证 | 实际结果 |
| --- | --- |
| `prepareRimeSdk` | 正式 SDK 下载、提取和校验通过 |
| `RimeSdkArchiveTest` | 10 个用例实际执行；失败、错误和跳过均为 0 |
| 相关应用 JVM 回归 | 引擎操作 24 个、交互契约 9 个、队列 1 个，共 34 个用例实际执行；失败、错误和跳过均为 0 |
| `tools/input-perf` Python 回归 | 117 个用例实际执行；失败、错误和跳过均为 0；旧研究仍固定 `6500f364…`，不随新应用锁改变 |
| ARM64 Debug APK | 构建通过；APK 25,708,796 字节，`libdirect-rime.so` 5,189,080 字节 |
| APK 原生白名单与链接审计 | 通过，page size 16,384；SDK 7 个静态库中包含 spdlog，审计记录 spdlog 5 objects／librime 130 objects；无 Fcitx 依赖 |
| x86_64 `.lab` 与 AndroidTest APK | 正式 SDK 构建和编译通过；设备实际执行结果见下方 Direct Rime CI 记录 |

本机 ARM64 Debug APK SHA-256 为 `e786106bc68f7bfd87ff9f4dad0c6105222a83395958899ebbb0d831dc111921`，仅对应 `09f710d7…` 和上述正式 SDK 输入。11 月 15 日的旧 APK 与设备结果不用于新版 SDK 验收。

[Direct Rime CI 43750197415](https://github.com/SandyYuR/fcitx5-android/actions/runs/43750197415) 的 `android_smoke` 已通过。该次原始 JUnit XML 共 9 个用例实际执行，失败、错误和跳过均为 0，其中 `OrderedSpellingTabSdkTest` 的 3 个新增用例全部实际执行并通过。证据为该运行的 artifact `14175857206`，按下载的原始 XML 核对实际 classname、testcase 与计数。

同次 Direct Rime CI 的完整 frontend 触摸与引擎设备 job 通过。原始 JUnit XML 共 60 个用例实际执行，失败、错误和跳过均为 0，总时长 84.971 秒；对应 artifact `14176641227`。此 job 与上述 9 项 `android_smoke` 分别核对和记录。

| 原始证据 ZIP | SHA-256 |
| --- | --- |
| `android_smoke`，artifact `14175857206` | `222b78dc7a63a3f1fb408efdebf785a1c231038b165012d09afad21631e47fa5` |
| 完整 frontend 设备，artifact `14176641227` | `13477b6d10abf479715a1eee693013de2cea5adb25f311de2490fb88e034edf8` |
| ARM64 完整 job，artifact `14175713553` | `9c012b370253bd82880b1829251620c63f92ce67ba69b4d493df310ce52fa77f` |

Direct Rime CI `43750197415` 已全部完成，4／4 个 job 成功。ARM64 完整 job 的 artifact `14175713553` 已按原始 XML 核对 295 份 JUnit 报告、2417 个实际用例，失败、错误和跳过均为 0；提取后运行仓库 `check-junit-results.py` 退出码为 0，计数一致，证据 ZIP 的 SHA-256 与 GitHub artifact 摘要一致。

[PR CI 43750197404](https://github.com/SandyYuR/fcitx5-android/actions/runs/43750197404) 已全部完成，2／2 个 job 成功，包含 APK job 的 Android lint。JVM job 日志中 `check-junit-results.py` 独立核对 295 份报告、2417 个用例，失败、错误和跳过均为 0，与上述 Direct Rime CI 的原始 XML 计数一致；性能工具 117 项也实际执行通过。上述结论限定于 `09f710d7…` 和正式 SDK，不继承旧 SDK 的验收。

PR CI 的 SDK 完整性与离线恢复步骤实际执行成功，`:convention:test` 未以 `FROM-CACHE` 代替执行。该 CI 未上传 build-logic 原始 JUnit XML，因此远端归档测试的逐项计数未独立复核；上表 `RimeSdkArchiveTest` 10 项全零结果限定于本机门禁，应用 JVM 2417 项和设备目标 3 项则有本次远端原始 XML 核验。

## 签名 Release 补充门禁

同一输入法源码 `09f710d7…` 的手动 [Commit CI 43751694193](https://github.com/SandyYuR/fcitx5-android/actions/runs/43751694193) 随后完成，签名 ARM64 Release 与 `unit_test` 均成功。该运行上传了 build-logic 原始 XML，补齐远端 `RimeSdkArchiveTest` 的逐项核验：10 个用例实际执行，失败、错误和跳过均为 0，时间戳 `2026-11-15T19:19:50.218Z`、耗时 8.809 秒；日志中的 `:convention:test` 没有 `FROM-CACHE` 或 `UP-TO-DATE`。

签名 Release APK SHA-256 为 `5d91aae316253eb7bb01a3540fcabee0159f8112ba5a876ef77084e029c25dd9`。产物源码与元数据均为 `09f710d7…`，内置 SDK 为上述正式 `android-sdk-20261116-1`、包摘要 `a566e36a…`、librime `8bbdf773…`。本机 `apksigner verify` 通过，v1／v2 签名有效；APK 白名单通过，原生链接审计匹配实际 `.so`，七个静态输入包含 spdlog 且无 Fcitx 依赖；打包 ART profile 包含 89 个方法，并保留 sendKey 与协程入口。

| 补充证据 ZIP | artifact ID | SHA-256 |
| --- | --- | --- |
| `unit-test-reports`，含 SDK 归档原始 XML | `14176138891` | `2de7da85f069acec3ecbaa869ad88c184c9788eaef431666f5be6fb6f0dd59fc` |
| 签名 ARM64 Release、来源及审计 | `14176922106` | `af55eb9dc455507a809ae1d4de548318df64a18093bb19aa5dd1605c53e935f8` |

该运行的 `nightly_release` 因分支为 `feat/rime-engine-log-switch`，按仅主分支发布的条件正常跳过；上述 APK 属于 CI 产物，未发布 Nightly。

## literal 音节栏与模糊候选复核

本节保留第一轮字面过滤方案的对照与构建事实。该阶段尚未收紧“无 `derive/fuzz` 时保持原行为”的兼容边界，也未统一 `ü/v` 标签；后续单向规则复现和最终修订另见[双模糊与字面标签最终修复](#双模糊与字面标签最终修复)。第一轮小词典未复现“转速／专属”，不表示所有声母、韵母组合都没有问题。

2026-11-16 后续复核以用户指定的 PR #3 修改前提交 [`2b2c15cfc516a42d3b87d7f3eabbd7ec71df15ef`](https://github.com/SandyYuR/librime/commit/2b2c15cfc516a42d3b87d7f3eabbd7ec71df15ef) 和 `8bbdf7733b31162a41baeb2b3bfe0f9962dc4a9a` 为基线。前者与本页原被审提交 `a586daa5…` 的 `script_translator.cc/.h`、原六项测试及其词典夹具字节一致。用户随后明确：当前关注的是“转／装”，并提供 `librime-tab-literal-spelling.patch` 与 `wanxiang_t9.custom.yaml`，要求音节栏只显示本音节拼写匹配按键的标签，同时补足选 `zuan` 后“转速／专属”的候选保护回归。

### 展示与候选分开约束

本轮实现保留 `8bbdf773…` 的有序 `FuzzySpellingProjection::Matches` 候选筛选，新增 `SpellsLiterally` 用于九键完整音节栏展示。输入 `9826` 时只显示 `zuan:4`，不显示模糊派生的 `zhuan`／`zhuang`；输入 `94826` 时只显示 `zhuan:5`。候选图仍可包含方案允许的“转／专／装／钻”；选中展示出来的 `zuan:4` 后，这些单字及“转速／专属”应继续保留。输入下一音节并继续点选 `su`／`shu` 时也分别保留对应整词。

literal 投影按完整 algebra 顺序处理普通变换和分支删除，另外保留 `NormalizeTabLabel` 前后相同、类型为 normal 且非 correction 的派生分支。该条件让去调或大小写编码分支继续工作，同时排除改变音节的模糊派生。它只在当前位置为数字且具有 `xlit` 映射时启用；完整标签不由简拼、补全或纠错生成，非数字输入、单键简拼与原始按键标签沿原路径处理。

用户原补丁只跟随删除原拼写的变换，跳过所有非删除 `derive`。提供的万象配置却依赖 `derive/^(.*)$/\U$1/` 产生大写分支，再用大写到数字的 `xlit` 编码。直接应用原补丁会切断这条路径，使完整音节标签消失；本轮修订保留上述不改变音节名的编码分支。

原排序测试随新展示契约调整：五键 `74364` 对应 `sheng:5`，不再展示从模糊候选解码得到的 `shen:5`；四键 `7436` 展示 `shen:4`，候选仍保留“生”。因此，在四键 `7436` 下强制选择 `sheng:4` 的早期对照不代表本轮要求的有效 UI 操作。最终工作树是在 `8bbdf773…` 上应用并修订 literal 补丁，未采用此前临时恢复旧实现的方向。

### 固定输入与对照结果

`test/script_translator_literal_tab_test.cc` 内嵌用户配置的全部二十九条 algebra，逐条保持原内容与顺序，包括带调模糊规则、去调、大写派生和数字映射。测试使用独立小词典，保留 `zhuǎn`、`zhuān`、`zhuāng` 等原始带调编码；不访问用户方案、词库或设置。配置和原补丁的固定身份如下。

| 用户输入 | SHA-256 |
| --- | --- |
| `wanxiang_t9.custom.yaml` | `06c3c40bf3b6b4fd82f69939ffdd2f8a86607b9d56cae2763f36d784cbd1b1e0` |
| `librime-tab-literal-spelling.patch` | `2c7cb50bd22258588067d5488f7ccbf2031295cfbbfb9f1865c78c9334d97543` |

新增 `ScriptTranslatorLiteralTab` 共九项：四项独立检查首音节和下一音节的完整标签，五项检查选 `zuan` 前后单字、整词及继续选下一音节后的候选。展示与候选断言分开，标签缺失不会掩盖候选丢失问题。第一轮另加 `ScriptTranslatorLiteralBoundary` 三项，分别检查纯 `xlit` 的九键补全标签隐藏、简拼标签隐藏而候选保留，以及非数字全拼输入仍保留原补全音节栏；当时目标范围合计为原十八项、用户配置九项和边界三项，共三十项。前两项的展示期望属于第一轮方案，后续按兼容要求恢复旧行为，不能把它们当作现行无模糊方案的契约。

| 对照实现 | 实际执行与结果 |
| --- | --- |
| `8bbdf773…` 基线 | 原十八项与新增九项，共二十七项；二十三项通过，四项 literal 展示检查失败 |
| 原 PR `2b2c15c…` 的筛选 | 仅将 `script_translator.cc/.h` 换入相同当前基底，执行新增九项；五项候选检查通过，四项展示检查失败 |
| `8bbdf773…` 直接应用用户原补丁 | 二十七项中二十二项通过；四项 literal 展示检查与一项原排序检查失败，完整标签因编码分支被跳过而缺失 |
| 第一轮修订的 literal 实现 | 首轮二十七项全部通过；补足三项边界并格式化后的最终三十项全部实际执行通过，失败、错误和跳过均为 0；原十八项中的展示期望按新契约更新，有序候选用例保留 |

“转速／专属”五项候选保护检查在旧 PR 与 `8bbdf773…` 基线上均已通过，本轮没有复现用户反馈的丢词现象。因此，本次结果是修正音节栏展示并新增候选保护，不能表述为已定位或修复该丢词反馈的根因。测试虽使用完整二十九条 algebra，但词典和运行环境是宿主夹具，不代表完整万象部署或设备验收。

第一轮格式化后的宿主完整回归中，`rime_test` 实际执行 222 项通过，`rime_memory_test` 实际执行 43 项通过，合计 265 项实际通过。另有历史 `DISABLED_CaseTranspose` 未执行；完整宿主结果不能写成无跳过全量验收，三十项目标回归的无跳过结果单独成立。

### 开发 SDK 与 Debug APK 验证

本机执行 `build-engine-local.ps1 -Abi x86_64 -BuildApp -Jobs 4`，退出码为 0，完成 x86_64 引擎链接、开发 SDK 导出和应用 Debug 出包。开发 SDK 标记为 `development: true`，其 source tree 与当前 librime 工作树快照及应用实际消费的 SDK 一致；正式发行锁没有变更。

| 第一轮构建身份 | 固定值 |
| --- | --- |
| librime 基准提交 | `8bbdf7733b31162a41baeb2b3bfe0f9962dc4a9a` |
| 未提交源码快照 tree | `4530838d095bde3b18b4aa0615d534b613fab46b` |
| 开发 SDK ABI | `x86_64` |
| Android 来源锁 SHA-256 | `54decf4d02a7fbdf97957adf0fd1b01d6f0fb49cbc7ea29e5cffef562f25cf33` |
| 输入法 HEAD | `cd9a105bf41b2ee3e16046619a6075f1a91e419a`，工作树干净 |
| Debug APK 字节数 | 25,864,623 |
| Debug APK SHA-256 | `dd26ff61d20e19632d929d1513cd904adc6d2a466d9e91672993814e7ec9117a` |

同一 APK 的原生白名单、16 KiB 对齐与链接审计通过。打包库为 `libc++_shared.so`、`libdirect-rime.so` 和 `libquickjs.so`；链接审计核对七个静态库，包括 librime 与 spdlog，没有 Fcitx 依赖。入口已执行两项门禁，随后为同一 APK 保存结构化审计结果，不能将这次复核计为第二个构建产物。

该 APK 尚未安装。本轮只验证宿主行为和 x86_64 开发构建，未完成设备行为或完整万象方案验收，也未执行四 ABI 发行构建或发布新 SDK。

### 本地修改与证据边界

第一轮固定对照保存在本机 `D:/GitHub/fx2-rime/.dsh-tmp/librime-tab-literal-20261116/`，未作为站点附件上传。`user-custom.yaml` 与 `user.patch` 保存上述原始输入；`baseline-tabs.log/.xml`、`original-pr-tabs.log/.xml`、`user-patch-tabs.log/.xml` 与 `fixed-tabs.log/.xml` 分别保存四种实现的首轮结果，相邻 build 日志记录构建过程；`final-tabs.log/.xml` 保存最终三十项目标结果，`final-rime.log/.xml` 与 `final-memory.log/.xml` 保存完整宿主结果。此前探索性的反向选择和临时回退证据仍保留在 `.dsh-tmp/librime-tab-compare-20261116/`，不作为最终展示契约的通过证明。

`build-verification.json` 固定源码快照、SDK、应用 HEAD 与 APK 身份，`apk-verification.json` 和 `apk-link-audit.json` 记录同一 APK 的白名单、对齐与链接结果。本机开发 SDK 和 APK 仅用于此次验证，不能冒充上文已发布的正式 SDK 或签名 Release。

第一轮结束时，本机 librime `fix/t9-tab-order-fuzzy` 工作树保留 spdlog、#1274 与有序候选筛选，并加入 literal 展示过滤及回归。该阶段代码和文档均未提交或推送，未安装设备 APK、发布新 SDK 或更新输入法消费锁。上述源码快照、APK 和检查结果仅证明当时的第一轮实现；后续代码变更、提交及验证须分别记录。

## 双模糊与字面标签最终修复

后续用户提供 `librime-tab-typed-only.patch`，将问题缩为单向 `zh → z`、`ang → an` 两条规则、去调和九键编码。独立原生探针对 `zhongzhou@8bbdf773…` 验证：输入 `926` 时，栏内会出现 `zan`、`zhan`、`zang`、`zhang`，以及同键字面音节 `wan`、`yan`。声母规则在前时，选 `zhan` 只剩 `zhan`，选 `zang` 则保留 `zang/zhang`；调换两条规则的顺序，会改变中间标签能保留的分组。选实际字面标签 `zan` 时，两种顺序均保留 `zan/zhan/zang/zhang`，并排除 `wan/yan`。这一真实引擎结果补齐了第一轮小词典没有覆盖的单向双模糊场景。

附件的逐字母过滤与当前字面投影都能去掉这些中间标签，但附件在数字分隔符 `1` 和含 `derive` 的普通全拼补全上发生误删。原生探针还确认 `lü/lǖ/lǜ/lv` 会生成 `lü`、`lv` 两个标签，选择后把同组声调候选分开。因此，最终修订继续沿真实 algebra、prism 和分隔符匹配，同时收紧无 `derive/fuzz` 方案的兼容边界，并统一适用音节标签与候选比较的规范化。

用户随后明确要求修好、验证后提交并推送到 librime 的 `fix/t9-tab-order-fuzzy`。本次修订让 `zhuang` 的 `948264` 输入不再提供派生的 `zuan` 标签，保留实际字面标签及方案可达的模糊候选；最小正反规则顺序、明确规则集合的穷举、同键非相关音节、声调与 `ü/v`、分隔符、连续选择、退格及清空已完成下述宿主回归。构建和提交结果分别记录，第一轮结果不自动继承。

转发补丁的 SHA-256 为 `fe757f97b9b145d498015593c58223477624646313a4e44093d9a31b6dfd052e`。评估的原生记录保存在本机 `.dsh-tmp/librime-tab-typed-review-20261116/`；其中部分探针只打印诊断，不能把探针通过数解释成全部产品契约都已满足。规则的测试期望按实际方向和当前输入图定义；仅写 `zh → z` 时，不要求引擎自动增加 `z → zh`，也不把任意相互依赖的 algebra 改序视为等价操作。

### 最终修订边界

`UseLiteralTabLabels` 统一控制展示与候选规范化的适用域：仅在存在 `derive/fuzz` 的方案中，跳过真实前导分隔符后检查数字输入与反向字母映射。没有这两类规则时不建立模糊投影，完整音节栏和选中行为保持 `8bbdf773…` 的原语义；第一轮主动隐藏无模糊方案补全、简拼栏的改动已收回。

在此域内，`NormalizeT9TabLabel` 把原有去调、大小写处理与 `ü/Ü → v` 一起用于字面拼写、展示标签、直接候选比较和派生标签查找。派生路径缓存仍保存原有内容，只在 T9 匹配时规范化比较；普通 `StripTones`、非数字输入和 `RimeSelectTab` 的 API 行为不作全局改写。字面匹配保留真实 delimiter、canonicalizer 和 prism 路径，避免按字母数字字符猜测输入边界。

### 宿主验证与穷举范围

| 验证 | 固定结果 |
| --- | --- |
| `8bbdf773…` 执行同一最终目标测试 | 41 项实际执行，15 项不满足本次新契约；原始结果保留 |
| 修订后目标回归 | 41 项实际执行全部通过，失败、错误、禁用和跳过均为 0 |
| 单向最小规则 | 声母在前、韵母在前各检查 6 次真实字面选择，包括 `zan` 四音节可达集合及 `wan/yan` 排除 |
| 双向规则穷举 | 两种顺序各检查 52 个数字串、66 次完整字面标签选择、198 个无调／一声／四声词条；共 132 次选择比较，覆盖测试字典的全部字面标签 |
| 指定反馈与交互 | `948264` 只提供 `zhuang:6`，选择后保留装及规则允许的转／专／钻；8 音节词组逐段选择与逐步撤销均保持候选；退格先撤销选择、再删键，清空后重新输入不复活旧约束 |
| 兼容边界 | `lü/lǖ/lǚ/lǜ/lv` 五种拼写统一为一个 `lv` 标签并全部保留；数字分隔符前置／后置、无模糊方案补全／简拼、含 derive 的非数字全拼补全均通过 |
| 完整宿主回归 | `rime_test` 实际 233 项、`rime_memory_test` 实际 43 项，共 276 项通过；另有 1 项历史 `DISABLED_CaseTranspose` 未执行，不作为无跳过全量验收 |

最终 41 项由原 `ScriptTranslatorTab` 十八项，以及 `ScriptTranslatorLiteralTab` 十项、`ScriptTranslatorLiteralBoundary` 三项、`ScriptTranslatorLiteralTabExhaustive` 两项和 `ScriptTranslatorLiteralTabRegression` 八项组成。穷举的预期分组独立声明，未调用引擎的投影实现来生成答案；两种规则顺序分别与相同预期比较。测试词典包含 `z/c/s` 与 `zh/ch/sh`、`an/ang`、`uan/uang`、`en/eng`、`in/ing` 组合，以及明确隔离的 `ian/iang`、`yan/yang` 和同键 `yuan/zuan`、`wan/zan` 等边界。它是对该测试规则和字典集合的穷举，不代表所有方案或全部汉语音节。

穷举最初直接采用带 UTF-8 元音字符类的规则，例如 `([^iuy])([āáǎàa])n$`，出现两项失败。`boost::regex<char>` 按字节匹配，该写法可能让 `[^iuy]` 消耗带调元音的首字节，使本来想排除的 `ian/yan` 组仍命中规则；因此实际规则行为与独立预期不一致。独立穷举夹具改用完整元音分支，例如 `([^iuy])(ā|á|ǎ|à|a)n$`，使排除规则与声明的预期一致。用户提供的完整二十九条 algebra 夹具和原始配置文件保持原样；不能把这次夹具更正表述为引擎会拒绝用户原规则实际允许的关系。

本轮证据位于 `.dsh-tmp/librime-t9-fix-20261116/`：`initial-fixture-tabs.*` 保留最初两项失败，`baseline-final-tabs.xml` 保存基线的 15 项失败，`final-tabs.xml` 保存最终 41 项通过；`final-rime.xml`、`final-memory.xml` 保存完整宿主结果。所有词典和 schema 均在隔离宿主测试目录生成，未替换用户方案或学习库。

### 最终构建与 fix 分支交付

修复首次交付的提交为 [`455a78f999501d8a68be03caa5743fdcfb674342`](https://github.com/SandyYuR/librime/commit/455a78f999501d8a68be03caa5743fdcfb674342)，标题为“修复(音节): 九键只显示字面标签并保留组合模糊候选”。本机完成验证后正常提交并推送到 `fix/t9-tab-order-fuzzy`，远端分支已核对为同一完整 SHA；当时 `zhongzhou` 为 `8bbdf7733b31162a41baeb2b3bfe0f9962dc4a9a`，随后授权的历史整理见本节末尾。

本机再次通过 `build-engine-local.ps1 -Abi x86_64 -BuildApp -Jobs 4` 完成开发 SDK 和 Debug APK。构建时先使用未提交源码快照，随后生成的提交 tree 与该快照严格相同；SDK 清单中的基准提交与实际源码 tree 分别记录，不能只看基准 SHA 就把产物当成旧引擎。

| 最终身份与门禁 | 结果 |
| --- | --- |
| 已提交源码 tree／开发 SDK source tree | `31b3085f35525ac1de5aec3416a2a42e85db2be8`，二者一致 |
| 开发 SDK 基准提交 | `8bbdf7733b31162a41baeb2b3bfe0f9962dc4a9a`；`development: true` |
| ABI／Android 来源锁 | `x86_64`／`54decf4d02a7fbdf97957adf0fd1b01d6f0fb49cbc7ea29e5cffef562f25cf33` |
| 输入法 HEAD | `cd9a105bf41b2ee3e16046619a6075f1a91e419a`，工作树干净，发行 SDK 锁未变 |
| Debug APK 字节数 | 28,146,106 |
| Debug APK SHA-256 | `c64e1d963fa672d3e00310b27f590ed557bfd141c1aff501c623a5e23cd610d9` |
| 打包 native ELF build ID | `ab734bac5acb770e0e6bc760e64bdc4cc68a53d2` |
| APK 原生门禁 | 白名单、16 KiB 对齐及链接审计通过，七个静态库输入与本次 APK 一致 |

构建与来源核验见本轮目录的 `local-engine-app.log`、`build-verification.json`、`apk-verification.json` 和 `apk-link-audit.json`。该 APK 未安装，未完成设备行为或用户完整方案验收；未创建 SDK 发布标签，未覆盖现有 SDK，也未更新应用发行锁。文档工作树的这三份同步说明仍为未提交修改，不随 librime 分支推送自动发布。

推送自动触发的 [Commit CI 43810143351](https://github.com/SandyYuR/librime/actions/runs/43810143351) 已完成，运行的 HEAD 为 `455a78f999501d8a68be03caa5743fdcfb674342`，与远端 fix 分支一致。14／14 个 job 均为 `completed / success`，失败和跳过均为 0，覆盖 lint、Docker、Linux、macOS 和 Windows 构建。`ci-final.json` 固定运行身份及逐 job 结果，`ci-current-run.json`、`ci-jobs.json` 保留原始响应。本机宿主、开发 SDK 和 APK 门禁的结果与此源码 CI 分开记录；源码 CI 通过不等于已执行本轮四 ABI Android SDK 发行流水线或设备验收。

### 默认分支与网页手动入口

同日经用户授权，将 `SandyYuR/librime` 的 GitHub 默认分支由 `master` 改为 `zhongzhou`，并复读 API 确认；本机 `origin/HEAD` 已同步指向 `origin/zhongzhou`。这项设置变更没有移动源码分支或提交。`android-engine.yml` 原本就声明了 `workflow_dispatch`，此前默认 `master` 缺少该文件；`zhongzhou` 与 `fix/t9-tab-order-fuzzy` 的工作流 blob 均为 `0c08afbc0ecb3b63ff6e1fd98f259fa181bc2bf5`，无需修改工作流代码即可使用网页手动入口。

本次仅调整默认分支设置，没有触发外置引擎构建；核验保存在本轮证据目录的 `default-branch-update.json`。网页的分支选择与运行步骤见[运行时引擎变体与提供 APK](../engine-update.md#运行时引擎变体与提供-apk)。

### 用户网页手动运行的 ARM64 外置引擎

默认分支设置完成后，用户自行在网页选择 fix 分支，发起 [Android ARM64 engine 43813362140](https://github.com/SandyYuR/librime/actions/runs/43813362140)。事件为 `workflow_dispatch`，源码为 `455a78f999501d8a68be03caa5743fdcfb674342`、tree 为 `31b3085f35525ac1de5aec3416a2a42e85db2be8`，ABI 为 `arm64-v8a`。该次运行完成并通过，随后下载的两个 artifact 经安全提取和来源、摘要、签名校验；这是用户主动运行的构建，不是默认分支设置自动触发的构建。

| 产物身份 | 固定值 |
| --- | --- |
| SO artifact 外层 ZIP SHA-256 | `eadcfbe9a177ef8033422543a8f6fab23653edf40db0c5930e5f8f663f2d4783` |
| APK artifact 外层 ZIP SHA-256 | `64cc46437a905a6a1304f4ff4443e5c1b71963d48a6bd93b40466528705cb3cb` |
| `librime-455a78f9-arm64-v8a.so` | 5,603,992 字节；SHA-256 `e83c356c5b4f337211fd5e1423d84c7146d35d3b6fad8f035f00f513ecdc1429` |
| `librime-engine-455a78f9-arm64-v8a.apk` | 2,142,624 字节；SHA-256 `b7086ad84fd8ce59601a50b6d93e2e630a1afc16df4632a4bfe6e03952ae8d62` |
| 提供方包名／版本／通道 | `org.rime.plugin.sandyyur.librime`／`1.17.0-455a78f9`／`test` |
| APK 签名证书 SHA-256 | `ea0c89fd72dcd518be28a1297b7c5774e48e26de831cc17c50e51a8515ca8872` |

两份 `SHA256SUMS` 的 13 项和 14 项内容全部核对通过；APK 附带的 `.idsig` 另行登记摘要。源码清单匹配上述 commit、tree 和 ABI，源码链接检查通过，产物没有开发快照标记。SO 通过 ELF 门禁，SONAME 为 `librime.so`，导出仅 `rime_get_api`，LOAD 段均为 16 KiB 对齐，动态依赖仅为允许的 Android 系统库。APK 内的 SO 与独立 SO 字节相同，`apksigner` 验签通过，实际 APK 元数据确认为 `test` 通道。

核验记录位于 `.dsh-tmp/librime-t9-fix-20261116/engine-ci-43813362140/verification.json`。这些产物仍属于旧 fix 的 `455a78f9…/31b3085f…` 身份，未改写为后续整理提交的 SHA 或 tree；本机核验没有安装 APK。该流程上传的是外置引擎与提供方 APK，未创建正式 SDK 发布标签，输入法发行 SDK 锁也未更新。

### 用户实际测试反馈与 zhongzhou 历史整理

用户随后报告实际测试已可用，并明确授权把 fix 修复合入重写 `zhongzhou` 的音节提交，更新标题和说明；同一 librime 仓库中需要同步更正的前置文档提交一并重写。该反馈记录为用户实际使用结果，不冒充本机执行了完整设备测试套件；旧 fix 的手动构建和产物身份仍固定到其原 SHA，不能移作新提交的产物元数据。

| 整理前 | 整理后 | 内容 |
| --- | --- | --- |
| `3d9c8543d8b4a13848fa98989601f3bd475a458a` | [`695bd40ddbd641518909463c82a44477645ba4fe`](https://github.com/SandyYuR/librime/commit/695bd40ddbd641518909463c82a44477645ba4fe) | 更正文档中的默认分支、外置引擎自动构建的分支与路径同时匹配条件，并补网页手动步骤 |
| `8bbdf7733b31162a41baeb2b3bfe0f9962dc4a9a` 加 `455a78f999501d8a68be03caa5743fdcfb674342` | [`9a3175013def3d4d051fa57eb3acbbb201139e8e`](https://github.com/SandyYuR/librime/commit/9a3175013def3d4d051fa57eb3acbbb201139e8e) | 合并为“修复(音节): 九键仅显示字面标签并保留声韵组合模糊候选”，说明完整最终行为和验证范围 |

新 `zhongzhou` tree 为 `a902b2c6880153302f809de281081ee24f28ec8c`。相对已验收 fix tree `31b3085f35525ac1de5aec3416a2a42e85db2be8`，只有 `downstream/README.zh-CN.md` 和 `downstream/android/README.zh-CN.md` 内容变化；其余全部 blob 与 mode 一致，包括生产源码、测试、构建脚本和工作流。它是带两份说明更正的历史整理，不把不同的整树 SHA 写成相同，也不把旧产物重新标成来自新 SHA。

重写保留原作者身份与作者日期，并以已核对旧远端 SHA 的精确 lease 强推 `zhongzhou`；远端复核为 `9a317501…`，本地干净且 ahead/behind 为 `0/0`。总提交数由 fix 的 1997 回到与整理前 `zhongzhou` 相同的 1996，下游独有提交仍为 12 个。恢复引用为 `refs/codex/backup/t9-rewrite-20261116/zhongzhou` 与 `refs/codex/backup/t9-rewrite-20261116/fix`。所有远端 `android-sdk-*` 标签的对象和解析后提交均逐条核对不变，远端 `fix/t9-tab-order-fuzzy` 仍为 `455a78f9…`；证据保存在 `.dsh-tmp/librime-t9-rewrite-20261116/rewrite-verification.json`。

新 HEAD 的推送自动触发的三条工作流均已完成并通过，均按完整 SHA `9a3175013def3d4d051fa57eb3acbbb201139e8e` 核对：

| 工作流 | 最终结果 |
| --- | --- |
| [Commit CI 43815366662](https://github.com/SandyYuR/librime/actions/runs/43815366662) | 14／14 个 job 成功，失败和跳过均为 0 |
| [Android SDK 43815366608](https://github.com/SandyYuR/librime/actions/runs/43815366608) | 四 ABI、宿主回归与打包共 6 个 job 成功；release 仅在 `android-sdk-*` 标签运行，本次分支推送按预期跳过 |
| [Android ARM64 engine 43815366513](https://github.com/SandyYuR/librime/actions/runs/43815366513) | 来源检查、构建、链接校验、APK 打包校验及两份产物上传全部成功 |

三条流水线共 15 件 artifact 的 run ID、源码 SHA、未过期状态及摘要元数据已核对。新 SDK 总包 `rime-android-sdk` 的 artifact ID 为 `14201489651`，外置引擎 SO 和 APK 分别为 `14202337905`、`14202850072`。本次未下载并独立复验新产物内部内容；前一节的下载、ELF、签名和载荷字节核验仍只对应旧 fix 产物。最终结果与原始 run/jobs/artifacts 响应保存在 `.dsh-tmp/librime-t9-rewrite-20261116/ci-final.json` 及同目录文件。

此次没有新建 SDK 发布标签，输入法发行 SDK 锁尚未更新；这些 docs 工作树修改也没有因源仓库重写而被提交或推送。

## 新版 SDK 消费与默认分支重写

随后用户明确授权删除 librime 的 `fix/t9-tab-order-fuzzy` 远端与本地分支，并更新输入法默认分支引擎，直接重写 [`cd9a105bf41b2ee3e16046619a6075f1a91e419a`](https://github.com/SandyYuR/fcitx5-android/commit/cd9a105bf41b2ee3e16046619a6075f1a91e419a) 后强推。由于消费端要求正式且不可变的 SDK，用户进一步授权在 librime `9a3175013def3d4d051fa57eb3acbbb201139e8e` 创建新的 `android-sdk-20261116-2` 标签并发布；旧 SDK 标签和包不覆盖。

| 更新前身份 | 固定值 |
| --- | --- |
| librime `zhongzhou` | `9a3175013def3d4d051fa57eb3acbbb201139e8e` |
| 指定删除的 fix 分支 | `455a78f999501d8a68be03caa5743fdcfb674342` |
| 输入法默认分支旧 HEAD／强推 lease | `cd9a105bf41b2ee3e16046619a6075f1a91e419a` |
| 输入法 HEAD 父提交 | `2411d9d135be90d601e27958d1be930930a57223` |
| 输入法总提交数 | 2804 |

### 新标签与正式 SDK

新 SDK 已发布为 [`android-sdk-20261116-2`](https://github.com/SandyYuR/librime/releases/tag/android-sdk-20261116-2)。正式包、单独的清单和 `SHA256SUMS` 已下载，摘要一致；包内逐文件清单、全部归档对象 ELF、必需 API、锁定依赖、工具链、OpenCC 数据与许可证均核验通过。

| 新版 SDK 身份 | 固定值 |
| --- | --- |
| annotated tag 对象 | `99e8d2f34832e6f2f8a328b2f329873817ee616e` |
| 标签对应 librime 源码 | `9a3175013def3d4d051fa57eb3acbbb201139e8e` |
| 源码 tree | `a902b2c6880153302f809de281081ee24f28ec8c` |
| 官方基线 | `bb56876890ba60f446fa2640c244b1794b965eda` |
| 正式 `rime-android-sdk.zip` SHA-256 | `fbfed3c1f6679b9a65f151a22f1afae72db71d54463f3ee8bdf27df29bea6a7c` |
| 发布 `sdk-manifest.json` SHA-256 | `164c6dbfc59bb58f5143b2c92049ffbde5b3d265cde399456921aa7fb431cba1` |
| Android 来源锁 SHA-256 | `54decf4d02a7fbdf97957adf0fd1b01d6f0fb49cbc7ea29e5cffef562f25cf33` |
| 逐文件核验 | 881 个文件 |
| 四 ABI | `armeabi-v7a`、`arm64-v8a`、`x86`、`x86_64` |
| 四 ABI 构建工具与链接元数据 | NDK `28.0.13004108`、CMake `3.31.6`、API 23，source tree 均匹配，link smoke 均为 `passed` |

[正式标签 Android SDK CI 43842049303](https://github.com/SandyYuR/librime/actions/runs/43842049303) 的 7 个 job 全部成功，包含实际 Release 发布。下载的标签 host 原始 JUnit XML 中，285 项实际执行，失败、错误与跳过均为 0；另有历史 `RimeCorrectorSearchTest.DISABLED_CaseTranspose` 未执行，单列而不算通过。`ScriptTranslatorTab` 与 literal 系列目标共 41 项实际执行，失败、错误、跳过和禁用均为 0。

正式 SDK 核验记录位于 `.dsh-tmp/ime-engine-refresh-20261116/release/audit-release.json`。标签 host 原始证据 ZIP SHA-256 为 `97eaf77b83dcc7d598c18f1efd880787af8e0b24a635b770b613d6370309d56e`，逐 XML 核对结果位于 `.dsh-tmp/engine-update-audit-20261116/audit-tag-host.json`。它们对应本次标签和源码，分支 SDK artifact 与旧 fix 外置引擎仍各自保持原身份。

### 输入法重写、推送与本机门禁

输入法本地默认分支已把 `cd9a105bf41b2ee3e16046619a6075f1a91e419a` 直接 amend 为 `f340e09a4d596c209934e50da2fd7d63038f780b`，消费上述正式标签、源码和 SDK 摘要；父提交仍为 `2411d9d135be90d601e27958d1be930930a57223`，总提交数仍为 2804。原作者身份与作者日期保留；提交标题和说明按最终 SDK、字面标签与组合模糊行为重写。`OrderedSpellingTabSdkTest` 保留原三项并新增四项真实打包引擎回归，覆盖字面 `zuan`、单向声母与韵母规则的两种顺序，以及继续选择下一音节。

本机完成以下验证后，使用 `--force-with-lease=refs/heads/fx-rime-only:cd9a105bf41b2ee3e16046619a6075f1a91e419a` 推送；远端复核为 `f340e09a4d596c209934e50da2fd7d63038f780b`，本地 ahead/behind 为 `0/0`，父提交和 2804 次提交计数一致。相对旧 HEAD 的内容差异只在 `native/rime-sdk.lock.json` 与 `OrderedSpellingTabSdkTest.kt`，原日志功能与历史性能研究冻结身份继续保持。推送记录位于 `.dsh-tmp/ime-engine-refresh-20261116/push-verification.json`。

| 本机本次输入验证 | 实际结果与范围 |
| --- | --- |
| `prepareRimeSdk` | 正式 SDK 准备与校验通过 |
| `RimeSdkArchiveTest` | 实际重新执行 10 项；失败、错误和跳过均为 0 |
| 应用相关 JVM | `DirectRimeEngineOperationsTest`、`DirectRimeInteractionContractTest`、`InputEventQueueTest` 共 42 项实际执行；失败、错误和跳过均为 0 |
| direct-rime JVM | 4 项实际执行；失败、错误和跳过均为 0 |
| ARM64 Debug APK | 25,712,350 字节；SHA-256 `e504e4e8875e125880dc1b991243baf3b351e0eedc74411122e3f0b20ac32035` |
| APK 原生白名单与链接审计 | 通过，16 KiB 对齐；审计匹配本次实际打包 `.so` 和本次 SDK 输入 |
| x86_64 `.lab` 与 AndroidTest | 编译通过；`OrderedSpellingTabSdkTest` 7 项编译成功，当前未在本机设备执行 |
| Windows native 契约 | `input-statistics-contract` 通过；`engine-contract` 在实际 `create_symlink` 时返回缺少权限，记为环境阻塞，不能算两项目标全部通过 |

ARM64 APK 与输入摘要保存在 `.dsh-tmp/ime-engine-refresh-20261116/debug-product.json`。Windows native 失败原始日志保留在 `.dsh-tmp/ime-engine-refresh-20261116/native-ctest.log`，没有预删目标或按类名豁免。

### 本次 CI 与实际设备验收

强推触发的 [Commit CI 43844286655](https://github.com/SandyYuR/fcitx5-android/actions/runs/43844286655) 与唯一一次既有 [Direct Rime CI 43844306797](https://github.com/SandyYuR/fcitx5-android/actions/runs/43844306797) 均已完成，分别为 3／3 和 4／4 个 job 成功。后者按 `fx-rime-only@f340e09a4d596c209934e50da2fd7d63038f780b`、`scope=all` 发起，调度记录位于 `.dsh-tmp/ime-engine-refresh-20261116/direct-rime-dispatch.json`；最终结论以本次原始报告和日志核验为准。

| 本次远端实际执行 | 原始证据核验结果 |
| --- | --- |
| Commit CI 应用 JVM | 295 份报告、2417 项实际执行；失败、错误和跳过均为 0 |
| Commit CI `RimeSdkArchiveTest` | 原始 XML 10 项实际执行；失败、错误和跳过均为 0；与应用合计 2427 项 |
| Direct Rime CI 完整前端 ARM64 JVM | 2417 项实际执行；失败、错误和跳过均为 0 |
| Direct Rime CI `android_smoke` | 13 项实际执行，含 `OrderedSpellingTabSdkTest` 7 项、`PluginSdkSmokeTest` 1 项和其他引擎／输入连接 5 项；失败、错误和跳过均为 0 |
| Direct Rime CI 完整前端触摸与引擎设备 | 60 项实际执行；失败、错误和跳过均为 0 |
| Direct Rime CI prototype JVM | 4 项实际执行；失败、错误和跳过均为 0 |
| Linux native 安全与存储检查 | prototype 的 `Test native engine safety contract` 及 `Verify LevelDB open reuse, explicit sync and recovery fallback` 步骤实际执行成功，补齐 native 安全契约与 LevelDB 打开复用、显式同步和恢复回退检查 |

两次应用 `:app:testFxDebugUnitTest` 和 SDK `:convention:test` 日志均显示实际执行，没有用 `FROM-CACHE` 代替运行。下载的原始 artifact 已核对官方 digest，原始 XML 经仓库 `check-junit-results.py` 核对，报告、testcase 计数与失败／错误／跳过一致。最终汇总、逐 XML 摘要和任务日志见 `.dsh-tmp/ime-engine-refresh-20261116/final-ci-summary.json`、同目录各 `*-junit-audit.json` 与 `job-log-test-audit.json`；Linux native 和 LevelDB 的步骤结果保留在 `ci-status.json`。

这些设备结果对应本次 `f340e09a…` 和正式 SDK，不能转写为本机已经安装或操作了唯一 AVD。Windows native 的真实符号链接权限阻塞仍保留；本次 Linux CI 成功不改写该失败原始事实。此前用户使用反馈、旧 fix 产物和旧版 SDK 的设备结果各自保持原范围。

### 签名 Release 与 Nightly

Commit CI 生成的签名 ARM64 Release APK 已下载核验，源码元数据、版本、内置 SDK 锁与清单匹配本次构建。APK 实际打包 native SHA-256 和 ELF build ID 与同一 CI 的链接审计一致，七个静态输入包含 spdlog，无 Fcitx C++ 符号；ART profile 包含 89 个方法。

| 本次发行 APK 身份 | 固定值 |
| --- | --- |
| 输入法源码 | `f340e09a4d596c209934e50da2fd7d63038f780b` |
| APK 版本 | `0.1.3-830-gf340e09a` |
| APK 字节数 | 10,400,233 |
| APK SHA-256 | `3942ebdc946211f001f5f8d5dad09f3acb1b444b5c01295376016338461d5837` |
| 实际打包 native SHA-256 | `b2b177d0f5cf520c8193c7d6d310a45b05877e34a87e754589998c9c37a742d1` |
| ELF build ID | `aefb2c6fce747472f529c0fb944b4f391f61e5c9` |
| 签名 Release artifact ZIP SHA-256 | `060b4af0298f86ea42aa07a1f12a04cef7748b0dd067b94b83c8c41d6a91c0fd` |
| ART profile 方法数 | 89 |

既有工作流随后发布新 [Nightly `nightly-0.1.3-830-gf340e09a-20261116-100559`](https://github.com/SandyYuR/fcitx5-android/releases/tag/nightly-0.1.3-830-gf340e09a-20261116-100559)，标签实际解析为 `f340e09a…`，Release 的 `direct.apk` asset digest 与上述 APK SHA-256 一致。核验保存在 `.dsh-tmp/ime-engine-refresh-20261116/signed-release-audit.json` 与 `nightly-release.json`，不只依赖 Release 的 `target_commitish`。

### 分支删除、恢复与最终状态

按用户授权，librime 的 `fix/t9-tab-order-fuzzy` 本地分支与远端分支均已删除，删除前完整 SHA 为 `455a78f999501d8a68be03caa5743fdcfb674342`。保留的 `zhongzhou` 为 `9a3175013def3d4d051fa57eb3acbbb201139e8e`，源码工作树干净；原已发布 SDK 标签和旧产物继续保留。

| 已存在的本机恢复依据 | 对应旧源码 |
| --- | --- |
| `refs/codex/backup/t9-rewrite-20261116/fix` | `455a78f999501d8a68be03caa5743fdcfb674342` |
| `refs/codex/backup/t9-rewrite-20261116/zhongzhou` | `8bbdf7733b31162a41baeb2b3bfe0f9962dc4a9a` |
| `refs/tags/nightly-0.1.3-830-gcd9a105b-20261115-194716` | 输入法旧 HEAD `cd9a105bf41b2ee3e16046619a6075f1a91e419a` |

最终输入法默认分支为 `f340e09a4d596c209934e50da2fd7d63038f780b`，本地与远端一致、ahead/behind `0/0`，工作树干净，总提交数仍为 2804。删除与恢复引用核验记录位于 `.dsh-tmp/ime-engine-refresh-20261116/git-final-verification.json`，强推精确 lease 另见同目录 `push-verification.json`。代码推送完成时，这些文档仅保存为独立 docs 工作树修改，尚未提交或发布；随后文档同步见下节。

### 回归测试保留确认

原提交 `cd9a105bf…` 相对父提交的统计为 `+379 -51`，首次重写的 `f340e09a…` 为 `+454 -51`。新增的 75 行来自 `OrderedSpellingTabSdkTest.kt` 的四项设备回归和辅助函数调整；相对原提交，另一个变化文件为 `native/rime-sdk.lock.json`，只更新正式 SDK 标签、包摘要和 librime 源码 SHA。四项回归检查字面标签、单向声母与韵母规则的两种顺序，以及连续选择下一音节，均已在上述实际打包 SDK 的设备运行中通过。

随后曾临时将测试文件恢复为原提交版本并推送 `3f591ada11d3253bd2ce1d8f85158553aee77931`，此时相对原提交仅有 SDK 锁变化，整条提交统计回到 `+379 -51`。用户了解测试用途后明确要求保留回归，并恢复带回归的原重写提交。最终按 `--force-with-lease=refs/heads/fx-rime-only:3f591ada11d3253bd2ce1d8f85158553aee77931` 强推回 `f340e09a4d596c209934e50da2fd7d63038f780b`，完整提交对象、父提交与上述已验证输入保持一致，本地与远端 `0/0`，提交数仍为 2804。

此次恢复记录位于 `.dsh-tmp/ime-engine-refresh-20261116/regressions-restored.json`；临时回退与推送记录保留在 `lock-only-rewrite.json` 和 `lock-only-push-verification.json`。临时回退版本的 [Commit CI 43848305713](https://github.com/SandyYuR/fcitx5-android/actions/runs/43848305713) 已取消，不作为最终交付证据。最终交付继续对应本节 `f340e09a…` 的 SDK、CI 和签名 Nightly 固定身份。

### 对应文档提交同步

文档同步范围为现行引擎更新、维护手册、Rime 功能、正确性契约、功能覆盖表及本日期记录，共六个文件，合入原 [#42 文档提交 `973808bc317c26e8e97d97daa176cad8911f9571`](https://github.com/SandyYuR/fcitx5-android/commit/973808bc317c26e8e97d97daa176cad8911f9571)。重写保留原作者与作者日期，父提交仍为 `01c9464758efef130edd2b1ac663a5a226d20ee6`，总提交数仍为 2338；正式 SDK 和代码提交固定身份沿用上文，旧源码、失败样本和旧产物证据继续保留。

原文档提交的本机恢复引用为 `refs/codex/backup/docs-engine-refresh-20261116/docs`。本轮文档重写、精确 lease 推送与站点部署记录分别保存在 `.dsh-tmp/ime-engine-refresh-20261116/docs-publication/`；站点发布由既有 Docs 工作流按 `docs` 分支的实际提交执行，代码发布与文档发布分别核验。
