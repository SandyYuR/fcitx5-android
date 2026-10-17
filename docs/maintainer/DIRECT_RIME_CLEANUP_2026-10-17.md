# 直连 Rime：旧后端与构建残留清理

本轮在 `feat/direct-rime-prototype` 清理已经不参与直连输入的实现。
默认分支 `fx-rime-only` 不修改；历史 Fcitx 对照仍固定为 `a714feff`，
通过独立 checkout 构建，原始数据见[迁移审计](DIRECT_RIME_AUDIT_2026-10-17.md)。

## 已清理

- 删除旧 `Fcitx.kt`、`FcitxDispatcher.kt` 和依赖已移除拼音引擎的旧 `FcitxTest`。
  连接管理固定创建 `DirectRimeBackend`，不再保留选择旧后端的分支。
- 删除 `app/src/main/cpp` 的旧 JNI、AndroidFrontend、通知 addon、addon loader、
  InputContext cache、对象转换和 gettext 构建及翻译文件。
- 删除 `:lib:fcitx5` 工程及其 CMake 查找脚本，移除 Core、fcitx5-rime 和未使用的
  essay/luna-pinyin/stroke 子模块入口、旧 addon CMake 和旧追踪补丁。
- 删除 Fcitx component/header、native library、plugin application 构建插件与注册，
  以及仅供它们使用的 AGP CMake 安装任务和反射。
- 主应用只配置直连 CMake、Rime assets 与固定包名；删除旧 APK 包名、构建分支、
  `DIRECT_RIME` 条件和不再使用的 Android subtype 切换保护。
- `prepare_personal_build.sh` 只检出固定的 prebuilt/prelude，不再获取或打 Core/addon 补丁。
  常规 CI 删除 ECM/gettext 和旧 CMake 安装步骤，增加 APK 原生依赖与 DEX 检查。
- 删除已不链接的 Fcitx Core/addon、libuv、libintl-lite 的原生依赖元数据。
  原 Android 前端的作者署名、源文件许可证和项目致谢继续保留。

## 当前构建入口

```sh
./prepare_personal_build.sh
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
```

普通构建即为「中州鹅」，包名 `io.github.sandyyur.rime.direct`。
旧命令里的 `-PdirectRimeFrontend=true` 继续兼容；显式传 false 会报错并指向历史版本，
不会悄悄构建另一种后端。可选 `:direct-rime` 诊断 APK 仍用于原生/InputConnection 测试，
日常产品为带完整界面的 `:app`。

## 检查方式

`verify-direct-rime-apk.py` 除了检查原生库白名单、ELF 依赖、64 位 ABI 的 16 KiB 对齐与资源清单，
还读取每个 DEX 的 class definitions，拒绝 `Fcitx`、`FcitxDispatcher` 及其内部类。
这不会把注释、字符串引用或仍在使用的 `FcitxAPI` 等兼容类型误判成旧后端。
新规则对上轮交付 APK 的负向检查已确认能够检出其中未执行的旧类。
检查器支持一次核验多个 ABI 的 APK；ARMv7/x86 按 Android 的 32 位要求检查 4 KiB，
避免把 NDK 自带 32 位 C++ 运行库误判为 16 KiB 不兼容。64 位要求保持 16 KiB。

本地验证使用普通构建命令（不传直连开关），产品代码提交为 `a49eb9b8`：

| 检查 | 结果 |
| --- | --- |
| ARM64 Debug、测试 APK、Release（含 R8 与 vital lint） | 构建通过 |
| 应用 JVM 单测 | 472 项通过，0 失败、0 跳过 |
| Debug APK | 22 个 DEX；旧后端及内部类定义为 0 |
| Release APK | 2 个 DEX；旧后端及内部类定义为 0 |
| 两种 APK 的原生依赖、16 KiB 页与资源清单 | 均通过，仅直连库和 C++ 运行库 |
| 普通 Gradle 工程清单 | 无 `:lib:fcitx5` |
| 显式旧后端选择 | 按预期拒绝，提示历史基线 |
| 历史基线源码、准备脚本及 gitlink | 保留且核对通过，Rime prebuilt pin 相同 |

交付 Debug 包为 `zhongzhougoose-cleanup-arm64-v8a-debug.apk`，23,182,134 字节，
SHA256 为 `184b687139fdbd8c196d1cc57cc6908679d002eff8696a2f40064d68a80a255e`。
签名证书 SHA256 为 `49554e18dbb5c87805b824e09a06db8d994f5e39c904621f17b1248517b9bcd5`，
与此前交付直连包相同，可以覆盖升级。Release 本地检查使用未签名包，不作为安装交付。
机器可读结果见[验证记录](direct-rime-cleanup-validation-2026-10-17.json)。

本轮源码的设备回归由 [PR #4 的 Checks](https://github.com/SandyYuR/fcitx5-android/pull/4/checks)
中的 `Full frontend touch and engine tests` 及独立引擎 smoke 任务执行；
它们与 JVM、native ASan/UBSan 和多 ABI APK 检查一起提供对应提交的回归结果。

首次 Release 依赖下载遇到本地 Gradle 未使用环境代理的问题，使用环境代理及系统 CA 后
完整构建通过；没有把代理或证书路径写入仓库配置。

## 仍在使用的代码与未完成事项

- 原键盘、主题、布局编辑器、设置和连接生命周期继续运行；
  `FcitxAPI`、`FcitxEvent`、RawConfig、INI、`fcitx://` 与旧布局标识仍用于兼容，
  本轮没有用改名或删除来替代协议迁移。
- 完整 XKB Compose、按应用 `app_options` 等行为等价、配置模型收口和广泛真机验证仍未完成。
  清理不等于全部迁移门槛已经通过，也不改变上一轮约 6%/7% 的候选延迟对照结果。
- 共享 prebuilt 仓库内部仍存放其他引擎及历史库；它们不参与本分支链接或 APK，
  本轮保持该仓库的 gitlink，不改动其他使用者的共享预编译产物。
- 历史对照工作流中的旧路径只用于独立检出的固定提交，属于仍可运行的对照工具。
  旧原始数据、历史分析、许可证与署名不作为废代码删除。
