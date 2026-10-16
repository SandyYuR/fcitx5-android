# 直连 Rime：依赖清理与性能记录

分支：`feat/direct-rime-prototype`。本次工作以 `1ab4bbe` 的完整界面版为起点。
APK 继续使用包名 `io.github.sandyyur.rime.direct`、应用名「中州鹅」，保留原键盘与设置界面。

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
提供 `--wanxiang /path/to/fixture.zip` 则同时测试完整方案。工具只接受可 root 的一次性 AOSP 模拟器，
会准备独立的恢复目录、校验上屏序列，并把原始结果、APK SHA256 和模型映射保存在 `--output` 目录。

万象对照夹具来自官方 v18.1.3 `rime-wanxiang-base.zip`，加入官方 LTS
`wanxiang-lts-zh-hans.gram`；方案与模型均不随 APK 分发，也不写入用户自己的 profile。
对照需使用同一模拟器、相同档案与模型、相同预热及输入序列，并校验实际 APK 的 SHA256。
宿主编译负载、软件模拟和 JIT 都会影响耗时，不能据此承诺真机提升比例。

测量结果和最终构建验证将在完成回放后补入本页。

已完成的构建检查：472 项完整应用 JVM 测试（其中 468 项沿用原项目，4 项验证独立按键与 INI），
2 项诊断模块 JVM 测试，native AddressSanitizer/UBSan，以及 ARM64/x86_64 APK 依赖与资源校验。
`aaf403b` 的直连 CI 四项任务全部通过，完整界面设备回归共 6 项；构建任务没有检出 Fcitx Core/addon 源码。

## 万象的可选方向

上游的上下文调频在满足条件时预取最多 50 个候选，已有手动排序生效时最多处理 100 个。
这部分开销可能发生在获取候选时，不能只测 `process_key`。
缩小窗口或关闭对应功能会影响候选顺序，应单独选择、测量，不作为前端优化的默认行为。
标准方案中重复挂载的字符集过滤器也应先检查插入候选的语义，再考虑去重。

`spelling_hints` 和 `always_show_comments` 还供 Lua 生成预编辑、辅助码与拼音，
不应为了隐藏注释直接关闭。显示只使用方案最终的 `candidate.comment`。
