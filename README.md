# 靓企鹅·中州韵（Rime-only）

基于 [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android) 的 `fx` 分支继续开发，将 Rime 深度内置进主 APK，并裁剪为只提供 Rime 输入法的 Android 版本。项目不是 Fcitx5 或 Rime 官方发行版。

- 简体/繁体中文应用名：**靓企鹅·中州韵**
- 其他语言应用名：**Fcitx5.fx.rime**
- 包名：`org.fcitx.fcitx5.android.fx.rime`
- 默认分支：`fx-rime-only`
- 用户手册：[Rime-only 简体中文用户指南](docs/RIME_ONLY_USER_GUIDE_zh-CN.md)
- 下载：[GitHub Releases](https://github.com/SandyYuR/fcitx5-android/releases)

## 相对 fxliang 的主要改动

在 fxliang/fx 的基础上，全部使用 vibe coding 做出改动。目前主要做了这些改动：

- 移除与Rime无关组件，将Rime插件内置。
- 重新组织 Kawaii Bar：左侧按钮固定作为“状态区”入口，剪贴板提示显示时可直接点击关闭。
- 工具栏新增亮/暗主题一键切换，并支持一键恢复 Monet 默认配色映射。
- 支持导入、选择和持久化自定义按键音，支持 WAV、MP3、OGG、M4A 和 FLAC；音效不可用时回退到系统音效。
- 候选正文与注释支持分别配置字体和字号，并增加可选的“默认高亮第一个候选”设置。

在这些功能改动之外，本分支还继续完善了性能、稳定性和安全性：

- 改进输入会话状态管理，修复手动数字布局在输入框重启、输入法或语言切换，以及语音输入结束后的布局恢复问题。
- 优化字体加载、键盘和候选栏刷新及缓存处理，减少不必要的重建与卡顿。
- 提升设置、布局数据和剪贴板同步的可靠性，改进原子保存、网络请求和数据传输限制，降低卡死、数据损坏和内存占用风险。
- 候选栏使用结构 diff，减少额外帧延迟、重复 measure/layout 和每键分配。
- 将布局、主题、字体、剪贴板图片、分享和网络等 IO 工作移出主线程，并为缓存、ZIP、图片、HTTP 和同步数据设置边界。
- 修复语音输入、剪贴板同步、备份迁移、键盘弹窗、多点触控、布局编辑器生命周期等问题。
- 布局编辑器草稿改存私有文件，避免 TransactionTooLargeException；快照名经过白名单和 canonical path 校验，阻止路径穿越。
- 修复数字布局覆盖、?123 与 BACK 的层历史，并避免 IME 退出时触发 Rime 全量同步造成切回键盘卡顿。

本 Rime-only 分支以 fxliang 的 `fx` 分支、提交 `3ad25fc9` 为基线继续专用化。详细历史和维护注意事项见 [交接文档](docs/HANDOVER-rime-only.md)。

## Rime-only 改动

### Rime 内置

- 将原独立的 fcitx5-rime 插件并入主 APK，静态链接 `librime.a`。
- 随 APK 安装 Rime prelude、essay、luna-pinyin、stroke 和默认配置，Rime 数据复用共享 OpenCC 资源。
- 首次启动只启用 `rime`；主设置页直接进入“中州韵设置”。
- 默认虚拟键盘是应用内置 TextKeyboard QWERTY 布局，不是另一个输入法引擎。

### 已移除

- Fcitx 内置拼音、码表、custom phrase native 链路，以及 libime、fcitx5-chinese-addons、pinyin-lm、table-data 等相关模块。
- anthy、chewing、hangul、jyutping、sayura、thai、unikey、text-editor、clipboard-filter 等插件模块。
- Android 英文键盘 addon、imselector、spell、unicode 和 quickphrase。
- 第三方插件发现、签名白名单、插件运行时服务和相关设置入口。
- mainline flavor；当前仅保留 `fx` flavor。

OpenCC、Fcitx 核心底盘、剪贴板、主题、候选栏、语音输入、数据同步，以及 librime 内置的 Lua/octagram 等能力仍然保留。“Rime-only”表示只提供 Rime 作为输入法引擎，不表示删除应用的其他辅助功能。

### 中英文切换

- 语言键单击向 Rime 发送一次独立 Shift（down → 50 ms → up），交给 ascii_composer/switch_key 处理。
- 语言键长按打开 Android 系统输入法选择器。
- Rime 默认 Shift_L 行为是 inline_ascii；如需整体切换中英文，可在用户配置中改为 commit_text 或 commit_code。

## Rime addon 更新

Rime 适配层源码来自 [SandyYuR/fcitx5-rime](https://github.com/SandyYuR/fcitx5-rime)：

1. 在该 fork 中合并官方 [fcitx/fcitx5-rime](https://github.com/fcitx/fcitx5-rime) 更新，并保留 fxliang/Sandy 的 tabs、schema 和 Shift/alt-trigger 定制。
2. 主仓库 CI 运行 `prepare_personal_build.sh`，动态 checkout 该 fork 的 master，并把 fcitx5-alt-trigger-v4point1.patch 应用到 Fcitx5 core。
3. 更新后必须真机回归“打字 → 点音节 tab → 选词”和语言键 Shift 切换；编译成功不足以验证这些时序行为。

主仓库中的 fcitx5-rime gitlink 是 CI checkout 前的占位，实际构建来源以 prepare_personal_build.sh 为准。

## librime 引擎更新

当前引擎版本：**librime 1.17.0-35f23e9**。

1. [SandyYuR/prebuilder](https://github.com/SandyYuR/prebuilder) pin 官方 rime/librime 提交，并按顺序应用 fxliang 的功能、音节缓存、词典并行部署和用户词典缓存补丁。
2. prebuilder 的 workflow_dispatch 构建四 ABI 静态库，通过 BOT_TOKEN 自动推送到 [SandyYuR/prebuilt](https://github.com/SandyYuR/prebuilt)。
3. 主仓库 CI checkout prebuilt master，将对应 ABI 的 librime.a 静态链接进 APK。
4. 更新后同步 app/licenses/libraries/librime.json，并验证 RimeGetInputTabs / RimeSelectTab 等定制 API、新增 API、APK 构建和真机行为。

librime 不需要单独 fork：官方提交由 prebuilder gitlink 固定，定制保存在补丁中。不要直接合并官方 prebuilt，否则会换成不含 fxliang 定制补丁的引擎。完整步骤见交接文档的 Rime 引擎更新 runbook。

## 构建

准备 Java 17、Android SDK/NDK 和 CMake，并初始化子模块：

~~~bash
git submodule update --init --recursive
./prepare_personal_build.sh
./gradlew :app:assembleFxDebug
# 或
./gradlew :app:assembleFxRelease
~~~

APK 输出：

- Debug：app/build/outputs/apk/fx/debug/
- Release：app/build/outputs/apk/fx/release/

当前没有 mainline flavor，也没有 assembleMainline 任务。包名与 fx2 的 org.fcitx.fcitx5.android.fx 不同，因此可共存安装；两者数据隔离，不会自动迁移。

Rime shared data 位于应用内部 usr/share/rime-data；用户数据位于该包 external files 目录下 data/rime。

## CI 与 Release

- 代码 push 运行 Commit CI，当前构建 Ubuntu 22.04 / arm64-v8a 的 :app:assembleFxRelease。
- fx-rime-only 构建成功后创建带时间戳的 Nightly prerelease，并附带 APK。
- Release APK 依赖仓库 secrets：SIGNING_KEY、KEY_ALIAS、KEY_PASSWORD。
- 当前 CI 不运行 JVM 单测；可在本地运行 :app:testFxDebugUnitTest。

## 致谢与许可证

感谢 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android)、[fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android)、[Rime](https://github.com/rime) 及相关项目开发者。

许可证见 [LICENSE](LICENSE) 及应用内第三方库许可证清单。
