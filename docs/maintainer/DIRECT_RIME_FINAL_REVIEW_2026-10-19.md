# 合并前最终审阅：运行依赖与 APK 残留

审阅基线为 `1c6917887762bb12242566c55afec90281d7eab4`，后续清理放在同一个 PR #4 的独立审阅提交中。
交付包、当前源码与最终 CI 入口以 [PR #4 描述](https://github.com/SandyYuR/fcitx5-android/pull/4)为准。

## 发现与处理

| 项目 | 证据 | 处理 |
| --- | --- | --- |
| PictureSelector | 项目无调用、反射或资源引用；库自己的两个 Activity 和一个 FileProvider 使旧签名 Release 保留 84 个 `com.luck.picture` 类 | 删除依赖及版本声明；APK 检查器拒绝其类再次进入产物 |
| lifecycle-common-java8 | 固定版本 2.11.0 的 JAR 没有类；实际生命周期 API 已由其他 Lifecycle 模块提供 | 删除空兼容包的直接声明，保留实际使用的生命周期库 |
| Sora 编辑器 | 只有未使用的版本目录别名，从未进入运行依赖图；Release 无其类 | 删除过期别名，不将它计作 APK 体积收益 |
| ZXing 默认扫码 Activity | 所有扫码入口都通过 `QrScanOptions` 选择自家的 `QrScanCaptureActivity` | 删除默认 Activity 的 Manifest 注册；保留 ZXing 库和自定义扫描界面所需基类 |
| Room 多进程失效服务 | 项目只有一个剪贴板数据库构建入口，未启用多实例失效通知，组件均在默认进程 | 删除可选 `MultiInstanceInvalidationService` 注册；保留 Room 数据库与迁移 |
| 原生核验缺口 | 只有 `.so` 名称和 `DT_NEEDED` 无法说明共享库内静态合入了什么 | 增加链接 map、链接结束时的 map/未剥离库 SHA256 绑定、APK ELF build ID 匹配与静态归档白名单 |

清理后的运行时模块标识从 182 减为 180，移除的是 PictureSelector 和空的 Java8 兼容模块，没有新增运行依赖。
这个计数来自 Gradle 运行依赖报告，排除只列出的版本约束；不等于 APK 中的类数量或启动时加载的库数量。
PictureSelector 的类为 **84 → 0**。裁剪、扫码、输入服务及许可证界面继续提供。
原默认 Fcitx 和 Foxy 的性能矩阵未重跑；本轮属于依赖与包体核验，没有新的打字延迟收益结论。

## Fcitx 原生层与静态链接

主应用的唯一原生入口是 `org.fcitx.directrime.Native`，加载 `direct-rime`。
APK 每个 ABI 的原生库白名单仍是 `libdirect-rime.so`、`libc++_shared.so`；后者来自固定 NDK。
`DT_NEEDED` 只允许上述库和 Android 系统库；额外藏在 assets 的 `.so`、构建用 `.a` 也会拒绝。

新增审计检查原生库的真实静态归档贡献，而不是只看文件名：

| 归档 | 实际用途 |
| --- | --- |
| `librime.a` | Rime 核心、部署/配置接口及编入其中的模块注册 |
| `libglog.a` | 原生引擎日志 |
| `libleveldb.a` | 用户词典、上下文学习数据 |
| `liblua_static.a` | Lua 组件，万象实际使用 |
| `libmarisa.a` | Rime 词典和索引 |
| `libopencc.a` | 简繁转换 |
| `libyaml-cpp.a` | YAML 配置解析 |

另允许固定 NDK 的运行支持归档。未知静态输入和 Fcitx C++ 符号都会使检查失败。
`librime.a` 内的 Lua、octagram、predict 等模块有注册入口，按方案启用；
不能仅因某个用户方案未选择一个模块就将它当成无入口的库。
保留 `WHOLE_ARCHIVE` 是为了保留这些静态注册，避免方案导入后缺少组件。

共享 `native/prebuilt` 中还存有 LibIME、粤语、日文/韩文引擎、Boost 等其他产物和词典，
它们不在本应用的链接输入或生成 assets 来源中。共享 checkout 不等于 APK 内容。
本轮没有更换 librime/prebuilt 固定版本，也未修改输入、候选、学习和数据库处理逻辑。

## 仍然保留的 Fcitx 来源代码

APK 中仍有 `org.fcitx…` 的项目类，它们组成当前 Android 前端：

- 键盘、候选、布局、主题、剪贴板与设置页面。
- `FcitxAPI/Event/Lifecycle/Daemon` 等兼容接口；实际引擎实例固定为 `DirectRimeBackend`。
- `:lib:common` 中的 Binder/语音/剪贴板 IPC、剪贴板元数据与 URL 过滤，均有当前调用。
- 保存的布局动作、图标 ID、`fcitx://` 设置路由，以及上游署名和许可证。

这些命名与兼容数据继续使用。原生 Fcitx Core、fcitx5-rime、AndroidFrontend 及旧 JNI 实现不在构建中。
最小诊断应用的 Activity/Service 也不随完整主应用打包；主应用只共用三类引擎/导入适配代码。

## 确认需要保留的其他依赖

| 依赖组 | 当前入口 |
| --- | --- |
| AndroidX、Material、Splitties | 页面、布局、生命周期、候选分页与按键界面；XML/Manifest 和间接调用一起核对 |
| MechDancer dependency | `InputView` 的组件管理，实际创建 `DynamicScope` |
| Arrow core / functions | 配置的 Either 处理、主题迁移的 compose/identity；functions 的 API 也在 `arrow.core` 包中，不能只按包名判为未使用 |
| Android Image Cropper / AndroidSVG | 自定义裁剪页、图标主题和 SVG 图标 |
| ZXing core / embedded | 布局、主题和图标主题的二维码生成、识别及扫码 |
| XZ / Commons Compress 及其传递库 | 二维码传输编解码、分享接收的 7z 导入；7z 有 API 26 限制 |
| OkHttp / DocumentFile | 剪贴板同步、网络请求与文档目录处理 |
| Room / SQLite | 剪贴板持久化、分页与数据库迁移 |
| AboutLibraries | 关于页的第三方许可证列表；压缩后资源可能改名，不能按原文件名不存在就判定被删 |
| Startup / ProfileInstaller | 包内 ART profile 的安装；不是旧 Fcitx 后台引擎 |

“没有在每次打字时执行”不等于无用途；仅编译期使用、可内联的 API 也不一定保留独立运行类。
构建用 Gradle/KSP/codegen 另行检查，不把它们当成 APK 的运行库。

## 检查范围与验证

代码复核覆盖输入代次与首键、owner 串行调用、候选点选与分批补取、JNI 字符串/快照、
部署和目录切换、导入暂存与回退、日志快照导出、当前构建与发布入口。
本轮未发现需要扩改输入处理逻辑的确定性新问题。

本地预检覆盖 ARM64 Release/Debug、设备测试 APK 编译、498 项应用 JVM 测试、
APK 的 DEX/原生依赖/页对齐/资源检查，以及 map 和实际产物的绑定检查。
负向验证使用旧的真实 PictureSelector APK，并检查未知静态库、Fcitx 符号、
不匹配的 ELF build ID 和失效的 map/库绑定会被拒绝。
CI 继续检查四 ABI 构建、原生安全契约、引擎 smoke 和完整前端设备回归；
签名 Release 另外保存 `native-link-audit.json`。当前运行结果见 PR 描述与 Checks。

脱敏审计数据在 [audit/2026-10-19-final-review/](audit/2026-10-19-final-review/)。
本地预检包来自提交前工作树，构建元数据仍可能带旧 HEAD，明确标为非交付包；
交付只选择当前已提交源码的 CI 签名产物，不将旧 1c 包标作清理后版本。

手动检查当前构建：

```sh
python3 tools/verify-direct-rime-apk.py app/build/outputs/apk/fx/release/*.apk
python3 tools/audit-direct-rime-link.py app/build/outputs/apk/fx/release/*.apk
```

本地有旧 APK 时先按 `output-metadata.json` 选择本次文件。静态审计必须在对应构建工作区执行，
已有链接 map、未剥离输出和绑定文件；检查会拒绝用不同构建的库来解释 APK。
来源、更新步骤和保留兼容内容见[维护手册](DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md)。
