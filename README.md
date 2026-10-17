# 中州鹅（Direct Rime）

保留 Android 键盘、候选、布局编辑器、主题与设置界面，直接调用 [Rime 中州韵](https://github.com/rime)。
本分支已移除 Fcitx 原生后端。项目继承 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android)
与 [fxliang 的 fx 分支](https://github.com/fxliang/fcitx5-android) 的前端成果，保留上游署名和许可证；本项目独立维护。

| 项目 | 本分支 |
| --- | --- |
| 应用名 | 中州鹅 |
| 包名 | `io.github.sandyyur.rime.direct` |
| 工作分支 | `feat/direct-rime-prototype` |
| 日常安装包 | 主应用 `:app`；Actions 的 `direct-rime-frontend-arm64-debug` |
| 历史 Fcitx 版 | 默认分支 `fx-rime-only`，与本分支分开维护 |

## 文档

- [用户手册](docs/manual/DIRECT_RIME_USER_GUIDE_zh-CN.md)：安装、万象、方案切换、键盘、主题、备份与排错。
- [维护手册](docs/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md)：构建、librime 来源与更新、CI 和性能复现。
- [本会话交付索引](docs/maintainer/DIRECT_RIME_SESSION_2026-10-17.md)：版本演进、安装包摘要和验证证据。
- [完整文档目录](docs/README.md)：实施、解耦、清理和历史方案归档。

## 快速上手

1. 安装适合设备 ABI 的主应用 APK，在系统设置中启用并选择“中州鹅”。
2. 在“中州韵设置”导入 Rime 方案，或将完整方案依赖放进当前用户数据目录，配置 `schema_list` 后部署。
3. 等部署完成后输入。语言键短按切换中英文，长按打开方案选单；状态区按组管理方案开关。

APK 不预装中文方案、完整词典或万象模型，只附带 Rime 通用预设。
首次启用有键盘但无中文候选时，先导入并部署方案。
Rime 方案和屏幕键盘布局分别设置，完整步骤见用户手册。

旧 Fcitx 版可以与本版共存。覆盖升级需同包名、同签名；换包名、换签名或卸载前先备份。
最小 `:direct-rime` APK 是诊断工具，日常使用请选择完整主应用。

## 构建

使用 Java 17、Android SDK 36、NDK `28.0.13004108`、CMake `3.31.6`：

```sh
./prepare_personal_build.sh
./gradlew -PbuildABI=arm64-v8a :app:assembleFxDebug :app:testFxDebugUnitTest
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/debug/*.apk
```

普通构建即为直连版，无需传直连开关。产物在 `app/build/outputs/apk/fx/debug/`。
Release、签名、设备测试与引擎更新见维护手册。

## 当前范围

主应用通过单一 owner 线程和独立 JNI 驱动 librime；原生库仅包含 `libdirect-rime.so` 与 C++ 运行库。
键盘、布局与宏、主题、候选、剪贴板、语音入口、同步与数据备份等 Android 功能继续提供。
重复的全局选项/附加组件入口已移除；旧布局快捷方式兼容转入中州韵设置。

完整 Compose、按应用行为等价和广泛真机回归仍需继续推进。
性能收益及其测量边界见[迁移审计](docs/maintainer/DIRECT_RIME_AUDIT_2026-10-17.md)，不将历史实验结果扩大为所有设备的保证。

## 致谢与许可证

感谢 Fcitx5 for Android、fxliang、Rime、OpenCC 及社区贡献者。
许可证见 [LICENSE](LICENSE) 和应用内第三方库许可证清单。
