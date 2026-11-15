# 2026-11-16 音节筛选与模糊拼写修复

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
