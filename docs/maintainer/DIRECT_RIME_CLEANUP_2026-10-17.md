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

`verify-direct-rime-apk.py` 除了检查原生库白名单、ELF 依赖、16 KiB 页与资源清单，
还读取每个 DEX 的 class definitions，拒绝 `Fcitx`、`FcitxDispatcher` 及其内部类。
这不会把注释、字符串引用或仍在使用的 `FcitxAPI` 等兼容类型误判成旧后端。
新规则对上轮交付 APK 的负向检查已确认能够检出其中未执行的旧类。

构建、单测、设备回归及交付 APK 的验证结果将在本轮完成后记录于本页。

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
