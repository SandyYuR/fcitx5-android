# 中州鹅（Direct Rime）

<img src="artwork/zhongzhou-goose.svg" alt="中州鹅：展翅伸颈、咬住㞢的白鹅" width="96" height="96">

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
代码仓库保留 README 入口、与组件同步的 [界面设计约定](DESIGN.md) 和随接口同步的 [ASR 语音 AIDL 接入协议](ASR_VOICE_INPUT_AIDL_INTEGRATION.md)，完整手册以文档站为准。

- [用户手册](https://sandyyur.github.io/fcitx5-android/manual/DIRECT_RIME_USER_GUIDE_zh-CN)：安装、方案切换、键盘、主题、语音、备份与排错。
- [维护手册](https://sandyyur.github.io/fcitx5-android/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)：构建、librime 来源与更新、CI 和性能复现。
- [Windows 本机构建环境](https://sandyyur.github.io/fcitx5-android/maintainer/LOCAL_BUILD_ENVIRONMENT_zh-CN)：工具链位置、SDK 缓存与环境排查。
- [Windows 本地引擎开发](https://sandyyur.github.io/fcitx5-android/maintainer/local-engine-development)：从本地源码生成开发 SDK 和独立 Debug APK。
- [外部语音输入](https://sandyyur.github.io/fcitx5-android/features/voice-input)：AIDL 提供方的接入方式。
- [JS 语音插件](https://sandyyur.github.io/fcitx5-android/features/voice-input-js)：导入 `.js` 插件、内置千问、豆包、腾讯云语音及翻译、插件接口与限制。
- [AI 文本助手（测试版）](https://sandyyur.github.io/fcitx5-android/features/ai-assistant)：浮动面板输入、发送 AI、阅读回复与确认上屏；三种协议、自定义端点和域名授权。
- [维护文档索引](https://sandyyur.github.io/fcitx5-android/maintainer/)与[完整直连历史](https://sandyyur.github.io/fcitx5-android/direct-rime)：实现演进、验证证据与归档。

## 快速上手

1. 安装适合设备 ABI 的主应用 APK，在系统设置中启用并选择“中州鹅”。
2. 在“中州韵设置”导入 Rime 方案，或将完整方案依赖放进当前用户数据目录，配置 `schema_list` 后部署。
3. 等部署完成后输入。语言键短按切换中英文，长按打开方案选单；状态区按组管理方案开关。

新会话的方案与中英文状态由 Rime 配置恢复，方案的 `reset` 默认值和 `switcher/fix_schema_list_order` 保持有效；同应用的普通输入框可复用引擎会话。

状态区 Rime 菜单中的“英文模式”／“中文模式”表示点击后切换到的模式，与编码区提示使用相同文案。菜单切换和方案开关均按 `switcher/save_options` 保存偏好；要记住中英文选择，需将 `ascii_mode` 加入该列表，方案的 `reset` 仍优先。

APK 不预装中文方案、完整词典或万象模型，只附带 Rime 通用预设。
“设置 → 数据与备份”的“Rime方案更新”分组包含万象更新与自定义更新。自定义更新可保存多个网络或本地资源任务，按相对子目录解压或复制，支持排除规则、单项执行与批量执行；整个批次准备好后才写入当前工作目录并部署，失败恢复原资源。支持 ZIP、TAR、TAR.GZ、TAR.BZ2、TAR.XZ，单个来源最多 512 MiB。功能参考 [LyraIME](https://github.com/Carlson24/LyraIME) 的自定义更新，使用本项目的下载、MD3 设置与引擎事务接口独立实现。
万象更新与高级设置仅适用于使用万象方案的用户。可在“设置 → 数据与备份 → 万象更新”下载万象方案、词库和模型，更新当前 Rime 工作目录并自动重新部署；支持可编辑的防覆盖规则、加密保存 GitHub Token 及附加词库下载与 Lite/Pro 转换。全量更新包含较大的语言模型；附加词库仅随全量、仅词库或仅附加词库范围下载，下载后需在主词典的 `import_tables` 中引用。
万象更新页面的“高级设置”可编辑当前方案：默认写入 `.custom.yaml` 补丁，也可选择直写源文件；支持常用参数联动、拼写/辅助码/模糊音选择及列表、映射的结构化编辑。打开页面只读取配置，不触发部署；默认配置可从共享目录读取。保存时校验目录与文件是否变化，部署失败会恢复原文件，缺失的部署文件可通过“修复部署”恢复。高级设置使用 Kotlin 和现有 librime，不包含 Python 运行时。
支持 Base / Pro / Lite / Pure、正式与预览通道，以及 CNB / GitHub 下载源；更新保留用户词典、自定义补丁和同步数据。
首次启用有键盘但无中文候选时，先安装方案并部署。
Rime 方案和屏幕键盘布局分别设置，完整步骤见用户手册。

本版恢复默认分支原包名，签名相同时可覆盖旧 Fcitx 版。旧直连测试包需先备份、再在本版恢复数据。
覆盖升级需同包名、同签名；换包名、换签名或卸载前先备份。
多个输入法请分别使用独立的 Rime 工作目录，通过同步目录交换用户词典；同时打开同一工作库会造成数据库锁冲突。
最小 `:direct-rime` APK 是诊断工具，日常使用请选择完整主应用。

## 设置与语音输入

保留默认分支的 Material 3 设置与首次引导；在外观页调整设置配色、页面动画与返回手势。
Android 11 及以上支持设置配色跟随键盘主题，低版本保留兼容的主题处理。
AI 助手面板共用设置界面的配色，编辑器与表单统一输入框、下拉框和操作按钮样式。
“便捷功能”列表末尾的“输入统计与字词云”支持按方案和日期查看汉字上屏、速度、码长、词云及实际键位热力，并可导出 JSON 或分享统计图片。热力图使用固定键内字号，优先完整展示；仅在实际宽度不足时横向滚动并显示提示。统计与字词记录分别手动开启，密码、PIN、隐私输入和内部编辑面板不参与统计；统计数据使用单独备份。功能与视觉参考 [同文咩版](https://github.com/qxm888/trime-mie)，使用本项目现有组件独立实现。
设置页右上角的键盘按钮可打开临时测试面板，切换文本、数字、密码与 PIN 输入框。面板不保存测试草稿，普通文本仍遵循当前输入法的学习与统计设置；密码和 PIN 排除统计。测试面板交互参考 [同文咩版](https://github.com/qxm888/trime-mie)，使用本项目现有组件独立实现并增加 PIN 类型。
主题库分开展示预览、名称与操作；插件设置按参数、密钥和权限分组，导入、备份及更新页提供持续可见的阶段和结果提示。
剪贴板条目可在键盘上方直接编辑，保留原应用的输入连接；布局管理默认只展开当前启用项。更新器在下载前校验已有包、复用匹配文件，并清理可确认已安装的自有旧下载。

语音入口支持两条路：外部 APK 通过 AIDL 提供识别能力，或者直接导入一个 `.js` 插件。
JS 插件跑在应用内置的 QuickJS 运行时里，内置通义千问、豆包、腾讯云语音识别与实时语音翻译，也可导入自定义脚本。
语音服务入口位于 **设置 → 输入与候选 → 语音输入**；JS 插件可从该页的“JS 插件设置”或 **设置 → 便捷功能 → JS 插件设置** 管理，密钥由宿主加密保存、不下发给脚本。
接入方式见[外部语音输入](https://sandyyur.github.io/fcitx5-android/features/voice-input)与
[JS 语音插件](https://sandyyur.github.io/fcitx5-android/features/voice-input-js)。

长按工具栏语音图标可切换首选语音引擎；语音与收起键盘按钮可同时显示。
语音插件的网络主机须先声明并获批准，可在插件设置里撤销。AI 文本助手还允许从已配置的 HTTPS 地址提取主机，首次访问新域名时逐一询问授权，批准后才发送文本与密钥。
助手输入后点击“发送 AI”，在面板内阅读回复，再“确认上屏”；未生成结果时不会因旧的“确认”事件直接关闭。

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

引擎由 [SandyYuR/librime](https://github.com/SandyYuR/librime/tree/zhongzhou) 自行构建并发布 Android SDK；输入法通过 `native/rime-sdk.lock.json` 固定版本与摘要，Gradle 自动下载和校验。当前构建无需 prebuilder/prebuilt 仓库，详情见 [原生依赖说明](native/README.md)。

## 当前范围

主应用通过单一 owner 线程和独立 JNI 驱动 librime；原生库仅包含 `libdirect-rime.so` 与 C++ 运行库，
外加 QuickJS 运行时供 JS 语音插件使用。
键盘、布局与宏、主题、候选、剪贴板、语音（AIDL 与 JS 插件）、同步与数据备份等 Android 功能继续提供。
语音入口位于“输入与候选”；主题页区分“主题库”和对所有主题生效的“键盘样式”。普通主题编辑支持草稿恢复，图片与主题定义在完整保存后发布。网页编辑器保持跨窗口宽度的预览比例，普通编辑无需等待二维码组件加载。
重复的全局选项/附加组件入口已移除；旧布局快捷方式兼容转入中州韵设置。
标点键和弹出项保留布局与预设定义的文字，单字符标点继续发送按键供 Rime 方案处理。
旧 Fcitx 标点映射和 `chttrans` 设置链已移除；Rime 标点预设和 OpenCC 简繁转换继续保留。

完整 Compose、按应用行为等价和广泛真机回归仍需继续推进。
性能收益及其测量边界见[迁移审计](https://sandyyur.github.io/fcitx5-android/maintainer/DIRECT_RIME_AUDIT_2026-10-17)，不将历史实验结果扩大为所有设备的保证。

## 致谢与许可证

感谢 Fcitx5 for Android、fxliang、Rime、OpenCC 及社区贡献者。
许可证见 [LICENSE](LICENSE) 和应用内第三方库许可证清单。
