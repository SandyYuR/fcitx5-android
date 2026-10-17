# 中州鹅直连版维护手册

本手册针对 `feat/direct-rime-prototype`，主应用为 `:app`，包名 `io.github.sandyyur.rime.direct`。
默认分支仍有独立的 Fcitx 版本；在本工作分支维护直连版，不在默认分支试验。

## 构建与安装

需要 Java 17、Android SDK 36、NDK `28.0.13004108`、CMake `3.31.6`，最低 Android 版本为 6.0/API 23。
工具版本由 `build-logic` 固定，首次构建需要下载依赖。

```sh
git switch feat/direct-rime-prototype
./prepare_personal_build.sh
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug :app:testFxDebugUnitTest
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
```

产物在 `app/build/outputs/apk/fx/debug/`；Release 使用 `:app:assembleFxRelease`，产物在 `fx/release/`。
安装时按 `output-metadata.json` 选择当前构建文件，避免把目录里旧提交/其他 ABI 的 APK 当作新产物。
Release 需要自己的签名才能分发；本地校验用的 `release-unsigned.apk` 不能直接安装。
保管签名密钥，勿提交到仓库；覆盖升级必须使用相同包名与签名。

普通构建已固定为直连。`-PdirectRimeFrontend=true` 仅兼容旧脚本，false 会报错。
可选 `-PdirectRimePrototype=true :direct-rime:assembleDebug` 构建最小引擎诊断 APK，
它不是带完整键盘的日常产品。

## 输入事件链路

```text
Android 软键盘/物理按键
  → 输入服务串行队列与会话检查
  → DirectRimeBackend
  → rime-main owner 线程
  → Native JNI → librime session
  → 一次快照返回 commit、preedit、候选、方案与状态
  → Kotlin 事件 → 原 Android 键盘/候选界面与 InputConnection
```

librime 的 session 与操作由单一 owner 串行管理。输入会话变更后，旧任务/旧提交不再交付新输入框。
提交只消费一次，UI 刷新与短暂模式提示不重放 commit。
模式/方案提示通过输入面板的辅助文本显示，1500 ms 后只清理提示；新输入和新会话会提前清理。

## librime 从哪里来

| 层次 | 仓库及固定版本 |
| --- | --- |
| 上游引擎 | [rime/librime](https://github.com/rime/librime)，`ef1a16aa2c962bccf347b116443331f39d56e037`，元数据 `1.17.0-ef1a16a` |
| Android 编译与定制补丁 | [SandyYuR/prebuilder](https://github.com/SandyYuR/prebuilder)，本次产物记录的提交 `4121ce63125bb73b5802199dea92dbb5101e8b36` |
| 四 ABI 预编译产物 | [SandyYuR/prebuilt](https://github.com/SandyYuR/prebuilt)，主仓库 gitlink `1126616c3ec569617da772e30d7a97f6b2694172` |
| 公共预设 | [rime/rime-prelude](https://github.com/rime/rime-prelude)，`082425ea0684bca36474415d4a0e8db9b016487e` |

`native/prebuilt/librime/<ABI>/lib/librime.a` 静态链接入 `libdirect-rime.so`。
主应用自己的桥接位于 `direct-rime/src/main/cpp/`，前端适配位于 `DirectRimeBackend.kt`。
APK 原生库白名单只有 `libdirect-rime.so` 与 `libc++_shared.so`。

共享 prebuilt 仓库也存有其他引擎的产物，主应用不链接它们。
共享 prebuilder 的完整 `everything` 流水线仍可能编译 Fcitx 宿主工具，用于其他组件的字典构建；
这与 librime 目标本身、当前 APK 的构建依赖和运行时依赖是不同层次。

## 更新 librime

1. 在 **prebuilder 仓库的新分支**中更新官方 librime 固定提交，审阅上游 ABI/API 和插件兼容变化。
2. 检查 `src/Rules/LibRime.hs` 及对应补丁。当前核心补丁依次涉及基础定制、音节缓存、
   词典并行部署、用户词典缓存、映射文件修复、连删行为修复、rewrite 滤镜和配置指纹。
   **配置指纹补丁最后应用**；Lua、octagram 等插件也有各自补丁。
3. 构建四 ABI，验证预编译产物包含现有定制头文件与扩展字段，如 `get_input_tabs` / `free_input_tabs`。
   不能只换一个官方二进制文件，否则可能丢失项目依赖的功能或造成 ABI 不匹配。
4. 将验证后的产物发布到 prebuilt，记录上游、prebuilder 和产物提交；
   在本仓库把 `native/prebuilt` 切到该固定提交并提交 gitlink。
5. 同步 `app/licenses/libraries/` 下的引擎及变动依赖版本，更新本手册的固定版本表。
6. 跑 JVM、原生安全检查、四 ABI APK 核验与设备输入测试；用实际万象用户库验证部署、
   上下文调频、简码、小字集、注释、分组开关、候选翻页、忘词与同步。

升级本应用不会自动跟踪 librime master；固定提交保证应用构建能复现。
详细依赖和历史改动见[迁移审计](DIRECT_RIME_AUDIT_2026-10-17.md)。

## 验证与 CI

本地 JVM 与构建检查：

```sh
./gradlew -PbuildABI=arm64-v8a :app:testFxDebugUnitTest :app:assembleFxDebug :app:assembleFxDebugAndroidTest :app:assembleFxRelease
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
```

完整前端设备测试需要可用的 Android 模拟器/设备；一次性 KVM 模拟器优先。
测试会创建临时 Rime profile 并切换系统输入法，完成后恢复原选择。

```sh
./gradlew -PbuildABI=x86_64 \
  -Pandroid.testInstrumentationRunnerArguments.class=org.fcitx.fcitx5.android.core.DirectRimeFrontendTest \
  :app:connectedFxDebugAndroidTest
```

CI 的 `Direct Rime experiment` 工作流覆盖 native ASan/UBSan、独立引擎 smoke、
完整前端构建以及实际触摸、模式提示、方案、设置、导入与布局编辑回归。
ATD 镜像的触屏测试需启用绘制；[测试脚本](../../tools/test-direct-rime-frontend.sh)已处理。
APK 检查器核对动态依赖、资源、DEX 类定义及页对齐：64 位 16 KiB，32 位 4 KiB。

Release 的 vital lint 通过不代表全量 `lintFxDebug` 没有历史问题。
首轮全量 lint 的原有错误记录保留在[前端接入文档](DIRECT_RIME_FRONTEND_2026-10-16.md)。

## 万象性能测试与分支

[性能审计](DIRECT_RIME_AUDIT_2026-10-17.md)使用含 Fcitx 的默认分支历史提交 `a714feff` 作对照，
与直连版使用相同 librime、方案、模型和回放程序。用户配置为 base、全拼、上下文调频、简码、小字集。
结果是模拟器中 API 到事件观察器的耗时，不包含触屏到屏幕显示完成，也不代表所有真机和长期用户库。

测试工具在 `tools/input-perf/`，数据在 `docs/maintainer/perf/`。
三个性能工作流保留手动触发：`wanxiang-perf.yml` 比较两个历史直连版本，
`fcitx-direct-perf.yml` 比较历史 Fcitx 与当前直连，`fcitx-direct-profile.yml` 追踪固定 APK。
原来四个 `perf/` 分支只是运行这些实验的临时入口，提交已进入工作分支，
用途和恢复 SHA 见[工作区整理记录](DIRECT_RIME_WORKSPACE_CLEANUP_2026-10-17.md)。

固定 APK 的追踪任务依赖指定比较运行的 4 个 APK artifact；artifact 有保存期限。
重新使用时先运行比较任务产生新 artifact，再把新的 run ID 传入追踪任务。
历史 Actions 运行保留审计价值，不能只因显示红色就删除；不需要为整理列表重跑所有旧实验。

## 设置中保留的兼容内容

- 中州韵设置的 `FcitxPreferenceFragment`、RawConfig/INI 是现有 Android 配置界面的数据适配，
  直接接到 Rime，不会启动 Fcitx Core。
- 旧布局/宏的动作 ID、`fcitx://` 和旧导航路由仍需兼容；旧全局选项、引擎列表和附加组件列表路由
  都转到唯一的中州韵设置，界面不再展示重复入口。
- Android 的服务、IPC 包名和前端类名暂留历史命名，仍承担输入服务和应用工具功能。
  更名需要单独处理保存状态、布局、备份和外部调用兼容。
- “关于”的 Fcitx5 for Android / fxliang 致谢、作者署名和许可证保留。
  当前设置不再提供 Fcitx 原生附加组件管理或旧 Fcitx 引擎快捷键配置。

## 仍待完成

完整 XKB Compose 行为、按应用 `app_options` 等价、配置模型进一步收口，
以及广泛厂商输入框、长句、快速连击和长期用户词库回归仍需继续验证。
这些不能因删除旧后端或清理名称而标记为完成。
