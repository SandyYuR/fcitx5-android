# 直连 Rime：依赖清理与性能记录

分支：`feat/direct-rime-prototype`。本次工作以 `1ab4bbe` 的完整界面版为起点。
APK 继续使用包名 `io.github.sandyyur.rime.direct`、应用名「中州鹅」，保留原键盘与设置界面。

**对照范围更新：** 本页下文比较两个直连版本。原默认分支 Fcitx 与当前直连版在
用户实际万象配置下的对照、原始数据及完整迁移审计，见
[2026-10-17 默认分支对照与迁移完成情况](/maintainer/history/DIRECT_RIME_AUDIT_2026-10-17)。

## CI 修复

`1ab4bbe` 的界面任务失败于 `processFxDebugAndroidTestResources`，测试 APK 中
`app_name` 引用了测试命名空间不存在的 `direct_rime_app_name`。正式 APK 可正常构建。
`451c27e` 将生成值改为应用名文字，保留正式应用的资源引用；该提交的 PR CI 和直连工作流均通过。

## 实际依赖与处理流程

```text
Android 键盘事件 → Service 串行队列 → DirectRimeBackend → rime-main
  → JNI → librime process_key / get_context → Kotlin 事件 → 主线程候选与 InputConnection
```

直连输入不经过 Fcitx Instance、InputContext、AndroidFrontend、fcitx5-rime 或 libuv。
本次还移除了实际链接的 `Fcitx5Utils`、`Fcitx5Config`，以及四个工具 JNI 调用：

- 按键解析与序列化使用 `KeyCodec`。检查进仓库的 X11 键名、别名与 Unicode 对照数据保留原出处，
  构建时不读取 Fcitx 头文件。常见字符与已解析键有缓存，保留旧修饰键格式和布局数据。
- `IniCodec` 读取原分组、路径、引号与转义格式，写入继续使用应用的原子文件写入工具。
  不强制迁移用户的目录注册和 Rime 选项文件。
- `-PdirectRimeFrontend=true` 不包含 `:lib:fcitx5`，直接配置独立 CMake 工程。
  Rime 的预编译库、默认配置和 prelude 已移到 `native/`，gitlink 版本不变。
- 独立准备 APK 数据，不再安装旧 addon 配置、Fcitx 默认输入法列表或 gettext 资源。
  CI 在未检出 Core/addon 源码的情况下构建，并检查 APK、ELF 依赖与数据清单。

APK 只允许 `libdirect-rime.so`、`libc++_shared.so` 及 Android 系统依赖。
工具 `tools/verify-direct-rime-apk.py` 同时检查 16 KiB 页面和资源清单的完整性。

原 Kotlin 前端的 `FcitxAPI`、`FcitxEvent`、`FcitxDaemon` 等名称和布局/设置协议继续兼容。
它们是应用自己的界面契约，不会加载 Fcitx 引擎。旧引擎源码和默认构建入口保留供回归对照，
因此这里的「移除」指直连产品的构建、链接和运行依赖，不表示删除仓库里的旧版实现或原作者署名。

## 按键路径优化

1. 不再提取或跨 JNI 复制无消费者的 `get_candidate_code` 元数据。保留定制音节 tabs API。
2. 利用 Rime 的 schema/option 通知更新开关版本；一般字符按键不再重新打开方案配置和遍历开关。
3. 未变化的状态区和预编辑不重复发送，减少主线程工作。上屏事件仍逐条可靠交付，不使用丢弃策略。
4. 候选首屏相同不一定表示后续候选相同；已处理按键、动作和组合变化继续使展开候选刷新。
5. 状态区按钮配置只在挂载或配置变化时重读，保留图标与按钮变化的实时监听。

没有更换 librime、裁剪万象词典、取消调频，或默认关闭万象的注释与排序功能。

## 回放方法与限制

设备测试 `DirectRimeFrontendTest#replayPerformanceWorkload` 先预热 20 个词，再测量 100 个词，
记录 `sendKey` 返回耗时的 P50/P95/P99、事件数量和上屏结果。它测量调用方经过适配层、
native owner 和返回的时间，不包括 Android 显示完成时间，不能等同触摸到屏幕的延迟。

默认使用微型词库 `ni` →「你」。提供 `fixtureArchive` 参数时，在新的独立 profile 解压完整方案，
改用固定的八组全拼输入。结束后切回原 profile 并删除测试 profile。此测试用于一次性模拟器，
不应拿个人使用中的设置作为可随意重置的夹具。

```sh
adb shell am instrument -w -r \
  -e class org.fcitx.fcitx5.android.core.DirectRimeFrontendTest#replayPerformanceWorkload \
  io.github.sandyyur.rime.direct.test/androidx.test.runner.AndroidJUnitRunner
adb shell run-as io.github.sandyyur.rime.direct cat files/direct-rime-replay.json
```

固定 APK 对照可使用 `tools/input-perf/compare-apks.py BEFORE.apk AFTER.apk TESTS.apk`，
提供 `--wanxiang /path/to/fixture.zip` 则同时测试完整方案。工具只接受一次性模拟器，
通过应用 API 创建和清理测试目录、校验上屏序列，并把原始结果、APK SHA256 和模型映射保存在 `--output` 目录。

万象对照夹具来自官方 v18.1.3 `rime-wanxiang-base.zip`，加入官方 LTS
`wanxiang-lts-zh-hans.gram`；方案与模型均不随 APK 分发，也不写入用户自己的 profile。
对照需使用同一模拟器、相同档案与模型、相同预热及输入序列，并校验实际 APK 的 SHA256。
宿主编译负载、软件模拟和 JIT 都会影响耗时，不能据此承诺真机提升比例。

## 2026-10-17 对照结果

[完整回放工作流](https://github.com/SandyYuR/fcitx5-android/actions/runs/38985522871)通过。
同一 GitHub Ubuntu 22.04/KVM 任务、Android 35 `aosp_atd` x86_64 模拟器，
对照 `a713cccde9d47beaffcbcd3344a8f919c6e50a26` 与
`c50f767754c7476d594c9ca467fb51ad9421a870` 构建的 APK。
**基线已经是直连 Rime 完整界面版**；这次测量只比较本轮依赖清理与按键优化，
不能用于计算从原 Fcitx 引擎切换为直连后的收益。

万象输入循环为 `nihao, zhongguo, shurufa, wode, xingneng, zhongzhou, fangan, zhongwen`，
预热 20 词后测量 100 词、784 键；微型方案为 100 词、300 键。
执行顺序为优化前微型、优化前万象、优化后万象、优化后微型。
两次万象回放均在应用进程的 `/proc/self/maps` 中确认 LTS 模型已映射。
每组优化前后的 100 条上屏文本逐项相同，上屏事件数量均为 100。

| 方案 | 版本 | P50 / ms | P95 / ms | P99 / ms | 事件总数 |
| --- | --- | ---: | ---: | ---: | ---: |
| 微型词库 | 优化前 | 0.490 | 0.786 | 0.913 | 1,300 |
| 微型词库 | 优化后 | 0.468 | 0.782 | 0.976 | 700 |
| 万象 + LTS | 优化前 | 9.749 | 13.009 | 25.374 | 3,236 |
| 万象 + LTS | 优化后 | 9.472 | 13.536 | 25.771 | 1,668 |

万象的状态区、客户端预编辑原本各推送 784 次；内容未变时现在各为 0 次。
候选和输入面板仍各 784 次，上屏仍为 100 次，总事件量减少约 48.5%。
P50 略降，P95/P99 略升；**这一轮没有证明按键耗时有稳定提升**。
这只有一组顺序对照，没有真机显示延迟、冷启动或部署耗时数据；
也没有足够的分段采样把总耗时归因到 Lua、模型或候选快照。

原始 JSON 与 instrumentation 输出保存在
[perf/2026-10-17-wanxiang-direct](https://github.com/SandyYuR/fcitx5-android/tree/df591f782bc07248b1917fe6544a711e917ec022/docs/maintainer/perf/2026-10-17-wanxiang-direct)。
两版使用同一个测试 APK，因此 JSON 的 `harness_commit` 均指测试包构建时的基线提交，
实际被测应用由 `apk_sha256` 区分。固定输入、模型映射、上屏序列和事件计数均可复核。

| 输入或产物 | SHA256 |
| --- | --- |
| 官方万象 v18.1.3 ZIP | `fc6495ba8489a898737ca29883dedaf61ff3baf593c78900dc59102c919d0e63` |
| 官方 LTS 模型 | `20f968dfe5dc461d23c355b93aa63925d6d36a5fb8f459ccd5c4dcd1cae6e6b9` |
| 组合测试夹具 ZIP | `b02fc8d87352564ac25f025caeb6e4a474c37a002f1c23f02e14b837a64be34c` |
| CI 优化前 APK | `51b11598b28d8c17a871a04e8a5b0f2aac1ea0deccda1e9440713e7bcfba4698` |
| CI 优化后 APK | `f60c75d3650e1c036a01333d7e63e049a05ab542d839a7c16e3763b43165690e` |

来源：[万象 v18.1.3](https://github.com/amzxyz/rime-wanxiang/releases/tag/v18.1.3)、
[LTS 模型](https://github.com/amzxyz/rime-wanxiang-data/releases/tag/LTS)。
`tools/input-perf/prepare-wanxiang.py` 校验源文件 SHA256 并生成固定 ZIP；
这些词库和模型没有加入仓库，也不增加正式 APK 的大小。

## 构建验证与安装包

已完成 472 项完整应用 JVM 测试（其中 468 项沿用原项目，4 项验证独立按键与 INI），
2 项诊断模块 JVM 测试、native AddressSanitizer/UBSan，以及 ARM64/x86_64 APK 依赖与资源校验。
完整界面设备回归共 6 项，包含触摸输入、候选操作、密码/会话切换、布局与设置入口、
方案导入迁移、注释和开关分组，以及固定输入回放。
[直连 CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/38984375720)
四项任务全部通过，构建没有检出 Fcitx Core/addon 源码；
[原构建与 JVM CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/38985525823)
也通过，保留的旧版构建入口可用。

本次交付的本地 debug APK 构建自 `47155a5099ea1632d68fb0b91eecd0da2b1a0a18`；
后续提交只改对照工具、工作流与记录，产品代码相同。它们与 CI 自行生成的调试签名区分：
本地 APK 继续使用之前交付版本的签名，可覆盖安装。

| 本地交付 APK | SHA256 |
| --- | --- |
| `zhongzhougoose-decoupled-final-arm64-v8a-debug.apk` | `c593ac9ed5f25ce8f4f475eb42e3efac52facf525f6bf9b5131707cebeb36cbf` |
| `zhongzhougoose-decoupled-final-x86_64-debug.apk` | `d8078038abe48a700ea3e58f4cff586282cafc465f8c725db9139840ceebae15` |

签名证书 SHA256：`49554e18dbb5c87805b824e09a06db8d994f5e39c904621f17b1248517b9bcd5`。

## 万象的可选方向

上游的上下文调频在满足条件时预取最多 50 个候选，已有手动排序生效时最多处理 100 个。
这部分开销可能发生在获取候选时，不能只测 `process_key`。
缩小窗口或关闭对应功能会影响候选顺序，应单独选择、测量，不作为前端优化的默认行为。
标准方案中重复挂载的字符集过滤器也应先检查插入候选的语义，再考虑去重。

`spelling_hints` 和 `always_show_comments` 还供 Lua 生成预编辑、辅助码与拼音，
不应为了隐藏注释直接关闭。显示只使用方案最终的 `candidate.comment`。
