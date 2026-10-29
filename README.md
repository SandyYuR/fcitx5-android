# 中州鹅（Direct Rime）

保留 Android 键盘、候选、布局编辑器、主题与设置界面，直接调用 [Rime 中州韵](https://github.com/rime)。
本分支已移除 Fcitx 原生后端。项目继承 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android)
与 [fxliang 的 fx 分支](https://github.com/fxliang/fcitx5-android) 的前端成果，保留上游署名和许可证；本项目独立维护。

| 项目 | 直连版 |
| --- | --- |
| 应用名 | 中州鹅 |
| 包名 | `org.fcitx.fcitx5.android.fx.rime` |
| 日常安装包 | 主应用 `:app`；`Commit CI` 的 `direct-rime-frontend-arm64-release`，或 Releases 中的中州鹅 Nightly |
| 历史 Fcitx 版 | 固定归档 `v0-archive-fcitx-before-direct-20261019`；不跟随默认分支继续更新 |

## 文档

用户手册、功能说明、实现协议与历史报告统一维护在 `docs` 分支与[文档站](https://sandyyur.github.io/fcitx5-android/)。
代码仓库只保留 README 入口与随接口同步的 [ASR 语音 AIDL 接入协议](ASR_VOICE_INPUT_AIDL_INTEGRATION.md)，其余内容以文档站为准。

- [用户手册](https://sandyyur.github.io/fcitx5-android/manual/DIRECT_RIME_USER_GUIDE_zh-CN)：安装、方案切换、键盘、主题、语音、备份与排错。
- [维护手册](https://sandyyur.github.io/fcitx5-android/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)：构建、librime 来源与更新、CI 和性能复现。
- [Windows 本机构建环境](https://sandyyur.github.io/fcitx5-android/maintainer/LOCAL_BUILD_ENVIRONMENT_zh-CN)：工具链位置、环境坑与子模块恢复。
- [外部语音输入](https://sandyyur.github.io/fcitx5-android/features/voice-input)：AIDL 提供方的接入方式。
- [JS 语音插件](https://sandyyur.github.io/fcitx5-android/features/voice-input-js)：导入 `.js` 插件、内置千问、豆包、腾讯云语音及翻译、插件接口与限制。
- [维护文档索引](https://sandyyur.github.io/fcitx5-android/maintainer/)与[完整直连历史](https://sandyyur.github.io/fcitx5-android/direct-rime)：实现演进、验证证据与归档。

## 快速上手

1. 安装适合设备 ABI 的主应用 APK，在系统设置中启用并选择“中州鹅”。
2. 在“中州韵设置”导入 Rime 方案，或将完整方案依赖放进当前用户数据目录，配置 `schema_list` 后部署。
3. 等部署完成后输入。语言键短按切换中英文，长按打开方案选单；状态区按组管理方案开关。

APK 不预装中文方案、完整词典或万象模型，只附带 Rime 通用预设。
首次启用有键盘但无中文候选时，先导入并部署方案。
Rime 方案和屏幕键盘布局分别设置，完整步骤见用户手册。

本版恢复默认分支原包名，签名相同时可覆盖旧 Fcitx 版。旧直连测试包需先备份、再在本版恢复数据。
覆盖升级需同包名、同签名；换包名、换签名或卸载前先备份。
多个输入法请分别使用独立的 Rime 工作目录，通过同步目录交换用户词典；同时打开同一工作库会造成数据库锁冲突。
最小 `:direct-rime` APK 是诊断工具，日常使用请选择完整主应用。

## 设置与语音输入

保留默认分支的 Material 3 设置与首次引导；在外观页调整设置配色、页面动画与返回手势。
Android 11 及以上支持设置配色跟随键盘主题，低版本保留兼容的主题处理。

语音入口支持两条路：外部 APK 通过 AIDL 提供识别能力，或者直接导入一个 `.js` 插件。
JS 插件跑在应用内置的 QuickJS 运行时里，内置通义千问、豆包、腾讯云语音识别与实时语音翻译，也可导入自定义脚本；两份入口都在
**设置 → 便利功能 → 语音输入**，密钥由宿主加密保存、不下发给脚本。
接入方式见[外部语音输入](https://sandyyur.github.io/fcitx5-android/features/voice-input)与
[JS 语音插件](https://sandyyur.github.io/fcitx5-android/features/voice-input-js)。

语音与收起键盘按钮可同时显示。

局域网 Web 编辑器在手机上显示地址和 6 位配对码；PC 打开地址后输入配对码，连接后浏览器自动保存本次凭据。
停止服务后配对失效，重新启动会生成新的配对码。
外部 IPC 客户端须与主应用使用相同签名，并声明主应用包名对应的 `.permission.IPC` 权限。

## 构建

使用 Java 17、Android SDK 36、NDK `28.0.13004108`、CMake `3.31.6`：

```sh
./prepare_personal_build.sh
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug :app:testFxDebugUnitTest
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
```

普通构建即为直连版，无需传直连开关。产物在 `app/build/outputs/apk/fx/debug/`。
需要逐键 CPU、文件 I/O 和事件交付统计时，构建时加 `-PinputDiagnostics=true`；
普通构建保留轻量 trace 切片，关闭这些细粒度统计，避免输入热路径承担测量开销。
Release、签名、设备测试、Windows 本机环境与引擎更新见维护手册。

## 当前范围

主应用通过单一 owner 线程和独立 JNI 驱动 librime；原生库仅包含 `libdirect-rime.so` 与 C++ 运行库，
外加 QuickJS 运行时供 JS 语音插件使用。
键盘、布局与宏、主题、候选、剪贴板、语音（AIDL 与 JS 插件）、同步与数据备份等 Android 功能继续提供。
重复的全局选项/附加组件入口已移除；旧布局快捷方式兼容转入中州韵设置。

完整 Compose、按应用行为等价和广泛真机回归仍需继续推进。
性能收益及其测量边界见[迁移审计](https://sandyyur.github.io/fcitx5-android/maintainer/DIRECT_RIME_AUDIT_2026-10-17)，不将历史实验结果扩大为所有设备的保证。

## 致谢与许可证

感谢 Fcitx5 for Android、fxliang、Rime、OpenCC 及社区贡献者。
许可证见 [LICENSE](LICENSE) 和应用内第三方库许可证清单。
