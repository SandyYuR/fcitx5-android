# 原应用界面接入直连 librime

日期：2026-10-16。分支：`feat/direct-rime-prototype`。默认构建仍使用原 Fcitx 引擎。

第一轮 `:direct-rime` 是最小实验工具，没有承接原应用的布局和设置。这一轮改为在 `:app`
复用原界面，仅替换输入引擎后端。面向实际使用和布局测试，请安装本页的 **完整界面版**。

## 构建与安装

```bash
git submodule update --init --depth 1 native/prebuilt native/rime-prelude
./gradlew -PdirectRimeFrontend=true -PbuildABI=arm64-v8a \
  :app:assembleFxDebug :app:testFxDebugUnitTest
```

产物：`app/build/outputs/apk/fx/debug/io.github.sandyyur.rime.direct-*-arm64-v8a-debug.apk`。
应用名称 **中州鹅**，包名 **`io.github.sandyyur.rime.direct`**，与第一轮实验 APK 相同。
完整界面版可覆盖同签名的实验包；与原输入法包名不同，独立存储，可以并存。
GitHub Actions 的 `direct-rime-frontend-arm64-debug` artifact 是完整界面版。
`direct-rime-arm64-debug` artifact 仍是底层诊断工具。

安装后：

1. 打开「中州鹅」，按原应用的引导启用并切换输入法。
2. 第一轮实验包已部署的数据会从 `files/rime/user` 复制到新的默认 Rime 目录。
   原数据保留；目标已有文件时不覆盖。由于输入法服务组件更换，需要在系统中重新选择一次输入法。
3. 新安装可进入「Rime 设置 → 导入 Rime 方案 ZIP」。ZIP 根目录包含 `default.yaml`
   或 `default.custom.yaml`，及其 schema、词典、Lua 等依赖。导入会新建独立目录并自动部署。
4. 原来的键盘布局、布局编辑器、主题、图标主题、工具栏、符号面板和剪贴板入口均在原应用界面中。
   「数据与备份」继续使用原数据目录和备份流程。
5. 长按语言键可打开方案菜单；候选栏可选字、翻页、展开和长按忘词。

不内置完整中文词库；沿用原版由用户部署方案的方式。诊断模块中的微型测试词库不随完整界面 APK 分发。

中文界面的空格键默认显示「中州韵（方案名）」，紧凑模式使用「㞢（方案名）」，
英文模式显示为「英文模式」。内部 `Latin Mode` 标识保留，用于兼容已有布局匹配。
非中文界面保留 `Rime` / `Latin Mode` 显示。

## 实现

```text
原 MainActivity / 布局、主题与设置
原 FcitxInputMethodService / InputView / 候选组件
                    ↓ FcitxAPI（原界面契约）
         FcitxDaemon 选择 DirectRimeBackend
                    ↓ rime-main 单线程
              direct-rime JNI / Engine
                    ↓
            固定版本的 patched librime
```

- `InputEngine` 把 daemon 管理的启停与界面 API 分开。默认仍实例化 `Fcitx`，
  仅显式传入 `-PdirectRimeFrontend=true` 才实例化 `DirectRimeBackend`。
- 直连 APK 的动态库只有 `libdirect-rime.so` 和 `libc++_shared.so`。
  按键解析与 INI 读写已改为 Kotlin，删除 `frontend-utils.cpp` 及 `Fcitx5Utils`、`Fcitx5Config` 链接。
  直连构建不再包含 `:lib:fcitx5`，也不需要检出 Fcitx Core 或 fcitx5-rime 的源码。
  共用的固定版本 librime 及其依赖在 `native/prebuilt`，共享配置在 `native/rime-data` 和 `native/rime-prelude`。
- JNI 共用第一轮的线程、generation、revision 和 UTF-16 边界，新增原界面所需的方案名、
  候选高亮、commit preview、全局候选遍历、光标定位、方案开关与 levers 方案选择。
- 保留原候选事件模型、状态区菜单模型和缓存，因此布局随方案切换、语言键、布局编辑器的方案下拉框
  都使用原组件。展开候选的全局选择/忘词与页内选择分别调用对应的 librime API。
- 快照转换为原界面事件时，提交只生成一次；刷新 UI 使用新的 native 快照，不重放旧的 commit。
  事件通过独立发送协程与不丢弃的缓冲分发，慢消费者不会堵塞持有 librime 的线程。
- Android 输入会话在主线程同步作废；键盘按键在进入服务队列前捕获 session，
  原生执行和交付 InputConnection 前再次检查。部署中收到的按键不会在部署结束后补执行。
- 密码输入不进入 librime，敏感输入设置 `_no_learning`。物理按下/抬起配对透传，
  虚拟键继续使用原输入法的退格、回车、光标和组合键处理。

## 配置和数据

沿用应用自己的 `getExternalFilesDir()` 下 `config/` 与 `data/`，没有读取其他包的数据。
`config/conf/rime-data-dir.conf` 沿用原目录注册格式和 V2 UTF-8 路径编码。
支持创建、切换、重命名、删除内部目录，以及注册外部目录。删除外部目录时只取消注册，不删除用户文件。

原设置界面可操作预编辑显示方式、用户目录、目录管理、启用的方案、部署和词典同步。
方案选择通过 librime levers 写入原 Rime 配置，随后重新部署。目录切换/部署全部在引擎 owner 线程串行执行。

ZIP 导入先在新目录检查路径、文件数量和解压容量，再注册新 profile，保留原 profile。
部署失败时切回原目录；失败导入的文件仍可检查修复。第一轮数据迁移也使用独立临时目录，保留来源文件。

## 验证

后续解耦、CI 修复、回放方法和性能对照见
[依赖清理与性能记录](DIRECT_RIME_DECOUPLING_2026-10-16.md)。下面保留首轮完整界面回归的记录。

原生契约测试继续使用第一轮文档中的 AddressSanitizer/UBSan 命令，新增全局候选、迭代器释放、
全局选字/忘词、UTF-8 光标位置和可选 levers 缺失的检查。

本轮 ARM64 和 x86_64 完整界面 APK 已构建，468 项原应用 JVM 测试全部通过。
Android 15 x86_64 模拟器的 4 项设备测试全部通过，覆盖实际点按原键盘、候选上屏、
原设置与主题页面、布局编辑器、方案选择、ZIP 导入和第一轮数据迁移。
模拟器测试确认实际进程没有加载 Fcitx Core 或 AndroidFrontend 动态库。
最终提交 `826ba31e` 的 [云端验证](https://github.com/SandyYuR/fcitx5-android/actions/runs/38905886844)
四个任务全部成功，Android 15 KVM 模拟器的 4 项完整界面测试全部通过。
下列截图来自这次云端测试。

干净云端模拟器另外暴露了停用输入法后后台方案更新仍调用系统子类型切换的问题。
直连版只有一个 Android 子类型，现已让方案/中西文变化只在 librime 内完成，
并仅在输入法信息实际变化时发送 `IMChangeEvent`，避免每个按键重复刷新布局。

可核对的截图：[原主界面](direct-rime-frontend/settings.png)、
[Rime 设置](direct-rime-frontend/rime-settings.png)、
[布局编辑器](direct-rime-frontend/layout-editor.png)、
[原键盘向真实编辑器上屏「你」](direct-rime-frontend/commit.png)。
机器可读交付记录见 [验证记录](direct-rime-frontend-validation-2026-10-16.json)。

全量 `:app:lintFxDebug` 未通过：318 errors、615 warnings，其中 286 项错误为缺失翻译。
已逐项核对这 318 项错误定位的源码行，均存在于默认分支基线 `a714feff`，
本轮新增直连与导入页面没有剩余的 Lint error。本次没有屏蔽这些原有问题或声称全量 Lint 通过。
现有原生资源生成任务需要同一任务图先配置 CMake，复查时使用
`./gradlew -PdirectRimeFrontend=true -PbuildABI=arm64-v8a,x86_64 :app:assembleFxDebug :app:lintFxDebug`。

完整界面的设备测试：

```bash
./gradlew -PdirectRimeFrontend=true -PbuildABI=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.core.DirectRimeFrontendTest \
  :app:connectedFxDebugAndroidTest
```

`DirectRimeFrontendTest` 使用一次性测试 profile，验证候选、翻页、展开后的全局选择、方案菜单、
预编辑切换、失效会话和提交不重放，并通过原软键盘的触摸事件与原候选适配器向真实 EditText 上屏中文。
测试结束恢复原 profile、输入法选择和键盘偏好。请在一次性模拟器运行。
AOSP ATD 镜像默认关闭绘制，触屏/截图测试前需执行
`adb shell setprop debug.hwui.drawing_enabled true`；CI 已显式开启。
云端使用 `bash tools/test-direct-rime-frontend.sh`，在每个实际触点前等待窗口位置稳定，
分别确认首键与后续键的编码，并在结束前保存截图和 logcat 至测试 artifact。
软件模拟器可将 instrumentation 参数 `waitMs` 调到 `120000`。

本机无 KVM，TCG 在大体积 debug DEX 的首次校验阶段可能超出 Android 进程启动时限。
这次排查的 ANR 栈位于 ART DexFileVerifier，尚未进入 Application 或输入引擎。
停止构建用的空闲 JVM 释放内存，并通过 `cmd package compile -m verify -f <package>`
预先完成校验后，再运行设备测试；GitHub Actions 使用 KVM。

## 万象注释与开关修正

候选栏现在仅显示 librime 的 `candidate.comment`，与展开候选保持一致，不再把
`get_candidate_code` 取得的编码追加到注释。这让万象的 `comment_off / tone_hint / toneless_hint`
及其 Lua 注释过滤器决定实际显示内容。

功能开关只在展开状态区展示，不再重复添加到方案菜单。对齐 fcitx5-rime 的动作模型：
二态开关显示「当前状态 → 目标状态」；每个 `options` 互斥组只占一个按钮，显示当前选项，
点击展开该组选择列表。没有 `states` 的内部开关不展示。

新增 `schemaCommentsAndSwitchGroupsMatchRime` 设备回归：使用微型 Lua 过滤器和万象同结构的
两组三态开关，验证关/带调/无调注释、普通与展开候选一致、组间独立、切换方向和方案菜单去重。
此测试不下载或打包万象的完整词库，完整界面测试类现共 5 项。

## 仍需验证的范围

此轮解决原界面、布局和设置缺失的问题，没有给出性能提升结论。
完整软硬件键盘、所有厂商编辑器、复杂 compose、长期运行和密集焦点切换仍需真机回归。
直连后端目前使用一个活动 session，沿用共享方案/中西文状态；原 Fcitx 的多 UID composition 缓存
和所有全局/附加组件选项没有一一模拟，界面只提供后端实际支持的 Rime 配置。

第一轮的测量方法与原始 trace 见 [实验记录](DIRECT_RIME_IMPLEMENTATION_2026-10-16.md)。
现在可以使用相同界面继续做原引擎与直连后端的 A/B，仍需相同真机、方案、用户词频初态和输入序列。
