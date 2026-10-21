# 无调用方代码清理

在 `feat/direct-rime-prototype` 继续检查[旧后端清理](DIRECT_RIME_CLEANUP_2026-10-17.md)
后留下的纯 Kotlin 辅助实现。产品修改提交：`6c470b8b901f2109cc748447b6f69fad0be6fc9e`。
默认分支和历史 Fcitx 性能对照基线保持不变。

## 删除内容与依据

| 范围 | 已删除内容 | 依据 |
| --- | --- | --- |
| 字符串工具 | `FcitxUtils.kt`、`StringEscapeTest.kt` | 正式代码无调用；原测试仅测试旧工具自身 |
| 旧 JNI 转换 | `FcitxEvent.create`、仅供它使用的候选缓存、`UnknownEvent`、整数枚举转换；`FormattedText.fromByteCursor`；`AddonInfo` 的整数分类构造函数 | 旧 JNI 已移除；当前桥接只调用 `org.fcitx.directrime.Native` 并返回 `Snapshot`，Kotlin 直接构造前端事件 |
| 生命周期便利方法 | `launchWhenReady`、`launchWhenStopped`、`whenStopped` | 没有调用；仍在使用的生命周期、`whenReady` 与连接管理保留 |
| 独立工具文件 | `ImmutableGraph.kt`、`IsJavaIdentifier.kt`、`PackageSignatures.kt` | 全仓库无调用、注册或资源引用；删除签名工具未改变任何现有校验入口 |
| 未接入的界面辅助类 | `TextKeyboardLayoutProfilePickerDialog`、`MemoryConfigProvider`、`AlphabetDigitKey`、`ImagePickerSwitchKey`、`TextPickerSwitchKey` | 无创建、反射或布局引用；当前布局编辑、预览和符号面板另有实际实现 |
| 未使用的模型包装 | `ScoredThemeCandidate`、`ThemeCandidateMetadata` 及其未使用 getter；`OneClipHistoryResponse`、`OneClipHistoryItem` | 无消费方或反序列化入口；实际主题评分和剪贴板同步模型保留 |
| 无调用的扩展函数 | 对话框负向/中性按钮辅助方法及属性、`insetOvalDrawable`、`circlePressHighlightDrawable`、`borderDrawable`、`errorArg`、`switchToNextIME` | 静态调用和导入均不存在 |
| 过期构建配置 | `core.*` 的整包 ProGuard keep 规则 | 专供已移除的旧 JNI；直连 `Native`、`Snapshot` 的显式 keep 规则保留 |

原 `StringEscapeTest` 的 7 组转义兼容数据迁入实际使用的 `IniCodecTest`，
验证已有配置的读写格式，不保留一份只为测试存在的旧实现。
APK 检查器现在拒绝 `FcitxUtils` 类及其内部类；对前一轮 APK 的负向检查已确认能检出。
同时修正 `native/README.md` 中“可以切回旧后端”的过期说明。

## 检查范围与保留项

对 469 个生产 Kotlin 文件的顶层类型、函数进行引用筛查，并核对仓库内的测试、
XML/Manifest、JSON/YAML 配置、ProGuard、原生源码及代码生成注册。
删除后，这次筛选条件下不再有“定义在本文件仅出现一次、其他生产文件无引用”的候选。
这属于针对无调用方代码的筛查，不是所有成员和动态路径的完整可达性证明。

只有其他文件引用较少，并不等于未使用：颜色/候选计算类型、状态机和按钮动作在同文件内
仍有调用；KSP provider 有服务注册；Android 组件有 Manifest 注册。这些没有删除。
`FcitxAPI`、`FcitxEvent`、`FcitxLifecycle`、`FcitxDaemon`、键盘/设置、RawConfig/INI
仍承担实际前端行为，保留。共享 prebuilt 固定版本及 librime、Rime bridge、输入热路径不变。

## 验证

本轮使用普通直连构建入口，执行 ARM64 Debug、androidTest APK、Release（R8/vital lint）
构建及全部应用 JVM 单测；结果与产物摘要记录在
[`direct-rime-unused-code-validation-2026-10-17.json`](https://github.com/SandyYuR/fcitx5-android/blob/d50e2272de795e9110cc497a0f37017551f54747/docs/maintainer/direct-rime-unused-code-validation-2026-10-17.json)。

| 检查 | 结果 |
| --- | --- |
| ARM64 Debug、androidTest APK、Release（R8/vital lint） | 全部构建通过 |
| 应用 JVM 单测 | 471 项通过；0 失败、0 跳过 |
| 原配置转义兼容用例 | 7 组输入在实际 `IniCodec` 的读写检查中通过 |
| Debug / Release DEX | 分别 22 / 2 个 DEX；核对的 18 组已删除类及内部类均为 0 |
| APK 原生依赖、资源清单、16 KiB 页 | 两种构建均通过；仅直连 Rime 库与 C++ 运行库 |

本地完整构建耗时 7 分 42 秒。交付包为
`zhongzhougoose-unused-cleanup-arm64-v8a-debug.apk`，23,159,566 字节，
签名与此前直连包相同，可以覆盖安装。Release 本地检查包为未签名产物。
旧工具的 2 项自测删除，配置兼容数据迁入 1 项真实 INI 编解码测试，
因此单测总数由 472 变为 471。

设备输入回归由 PR #4 的 CI 在推送后执行，设备结果应以对应提交的 Checks 为准。
