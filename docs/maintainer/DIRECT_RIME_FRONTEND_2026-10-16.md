# 原应用界面接入直连 librime

日期：2026-10-16。分支：`feat/direct-rime-prototype`。默认构建仍使用原 Fcitx 引擎。

第一轮 `:direct-rime` 是最小实验工具，没有承接原应用的布局和设置。这一轮改为在 `:app`
复用原界面，仅替换输入引擎后端。面向实际使用和布局测试，请安装本页的 **完整界面版**。

## 构建与安装

```bash
git submodule update --init --recursive
./prepare_personal_build.sh
./gradlew -PdirectRimeFrontend=true -PbuildABI=arm64-v8a \
  :app:assembleFxDebug :app:testFxDebugUnitTest
```

产物：`app/build/outputs/apk/fx/debug/io.github.sandyyur.rime.direct-*-arm64-v8a-debug.apk`。
应用名称 **Rime 直连**，包名 **`io.github.sandyyur.rime.direct`**，与第一轮实验 APK 相同。
完整界面版可覆盖同签名的实验包；与原输入法包名不同，独立存储，可以并存。
GitHub Actions 的 `direct-rime-frontend-arm64-debug` artifact 是完整界面版。
`direct-rime-arm64-debug` artifact 仍是底层诊断工具。

安装后：

1. 打开「Rime 直连」，按原应用的引导启用并切换输入法。
2. 第一轮实验包已部署的数据会从 `files/rime/user` 复制到新的默认 Rime 目录。
   原数据保留；目标已有文件时不覆盖。由于输入法服务组件更换，需要在系统中重新选择一次输入法。
3. 新安装可进入「Rime 设置 → 导入 Rime 方案 ZIP」。ZIP 根目录包含 `default.yaml`
   或 `default.custom.yaml`，及其 schema、词典、Lua 等依赖。导入会新建独立目录并自动部署。
4. 原来的键盘布局、布局编辑器、主题、图标主题、工具栏、符号面板和剪贴板入口均在原应用界面中。
   「数据与备份」继续使用原数据目录和备份流程。
5. 长按语言键可打开方案菜单；候选栏可选字、翻页、展开和长按忘词。

不内置完整中文词库；沿用原版由用户部署方案的方式。诊断模块中的微型测试词库不随完整界面 APK 分发。

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
- 直连 APK 的动态库只有 `direct-rime`、C++ runtime、`Fcitx5Utils` 和 `Fcitx5Config`。
  后两者仅复用布局按键语法与 INI 文件读写；没有打包或启动 Fcitx Core、AndroidFrontend、
  addon loader 或 fcitx5-rime 路由。
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

原生契约测试继续使用第一轮文档中的 AddressSanitizer/UBSan 命令，新增全局候选、迭代器释放、
全局选字/忘词、UTF-8 光标位置和可选 levers 缺失的检查。

完整界面的设备测试：

```bash
./gradlew -PdirectRimeFrontend=true -PbuildABI=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.core.DirectRimeFrontendTest \
  :app:connectedFxDebugAndroidTest
```

`DirectRimeFrontendTest` 使用一次性测试 profile，验证候选、翻页、展开后的全局选择、方案菜单、
预编辑切换、失效会话和提交不重放，并通过原软键盘的触摸事件与原候选适配器向真实 EditText 上屏中文。
测试结束恢复原 profile、输入法选择和键盘偏好。请在一次性模拟器运行。
软件模拟器可将 instrumentation 参数 `waitMs` 调到 `120000`。

本机无 KVM，TCG 在大体积 debug DEX 的首次校验阶段可能超出 Android 进程启动时限。
这次排查的 ANR 栈位于 ART DexFileVerifier，尚未进入 Application 或输入引擎。
停止构建用的空闲 JVM 释放内存，并通过 `cmd package compile -m verify -f <package>`
预先完成校验后，再运行设备测试；GitHub Actions 使用 KVM。

## 仍需验证的范围

此轮解决原界面、布局和设置缺失的问题，没有给出性能提升结论。
完整软硬件键盘、所有厂商编辑器、复杂 compose、长期运行和密集焦点切换仍需真机回归。
直连后端目前使用一个活动 session，沿用共享方案/中西文状态；原 Fcitx 的多 UID composition 缓存
和所有全局/附加组件选项没有一一模拟，界面只提供后端实际支持的 Rime 配置。

第一轮的测量方法与原始 trace 见 [实验记录](DIRECT_RIME_IMPLEMENTATION_2026-10-16.md)。
现在可以使用相同界面继续做原引擎与直连后端的 A/B，仍需相同真机、方案、用户词频初态和输入序列。
