# 2026-11-13 JS 插件工具整合

本轮把两个会话中的同类目标收敛为一条完整链路：用户可以选择 AI 按钮使用的 App，在按钮编辑阶段把任意已注册文本 App 绑定成直达动作，导出、替换或恢复内置插件源码；用户提供的元书免密钥翻译脚本已适配为 APK 内置 App。本文记录固定来源、实际验证和仍未覆盖的范围，不把文件已写入等同于已经合入或发布。

## 来源与目标

| 项目 | 固定来源 |
| --- | --- |
| 代码基线 | `fx-rime-only@88a664d46ba82712d5457d86c037629c478b042c` |
| 文档基线 | `docs@08ffd6f035adead6bf70f90ac7dd1d13baed77c7` |
| 元书原脚本 | 本机用户文件 `翻译-免密钥.js`，SHA-256 `99b0ca85e64b9671afcaaea83c6c8923517f8adbefe9c94c1d24c098da2bf15f`；原文件未改 |
| 对照实现 | `boomker/fcitx5-android@ea7a0e602df49599f6786f77c164a4e4d2344352`，只读核对源码导出、定向导入和目录选择规则 |
| 图标与翻译设备验证提交 | `8c910f6c265405648eca61798cfb1331b5667b1d`（包含功能提交 `2b554c935dd1d1a7beab63ab5625432df5dd2f9e`、翻译面板修复 `c6c3adf270f812f818557765b0780b847487d8f9` 与 Google 翻译图标） |
| CI 补修提交 | `d82283dc90b2e3edfcee5c046378fdef322a6599`（补齐五种语言的 JS 工具与插件管理文案，并修正剪贴板设备测试的助手启动 API） |
| 本轮文档提交 | 提交本文时的 `documentation/js-plugin-tools` HEAD |

代码提交的完整 SHA 在创建文档提交前写入本文及[现行功能入口](./feature-coverage)，使新增源码链接不依赖可移动分支名。

## 实现范围

- `JsAppSelection` 保存完整插件 ID 与 App ID；AI 短按运行和长按配置使用同一解析结果。目标消失时清除失效选择并回退内置助手，稍后重新导入不会自动复活旧选择。
- `js_tools` 只作为“编辑按钮”里的添加模板。选定 App 后保存稳定动态动作 ID，短按直接打开精确插件／App，长按进入对应插件设置；可同时绑定多个 App。目标缺失不回退其他 App，旧通用占位引导回编辑页重新绑定，密码与 PIN 输入框拒绝启动。
- 插件详情可导出当前有效源码、定向导入同 ID 替换、恢复 APK 内置版本。明确替换不比较版本号，旧的同 ID 导入记录不会自动抢占内置；设置和密钥保留，旧 App／主机授权撤销。同 ID 的活动 App 或语音会话在来源变化时失效。
- 普通导入插件升级可以调整 App 列表，失效的助手首选随目录重建；已有语音能力不得删除。内置替换始终以 APK 自带入口为最低契约，自定义旧版本额外增加的 App 不会变成后续版本必须永久保留的入口。
- 目录重建会清理已经不再注册 provider 的 `js:` 语音首选；App-only 内置插件的自定义语音覆盖恢复内置后不会留下死项，原本就是语音的内置 provider 恢复后仍保留选择。
- APK 新增 `youdao-free-translate` App。打开只读取选区，不自动读取剪贴板或联网；点击翻译后才向静态声明的 `aidemo.youdao.com` 发送文本。标题下说明、方向标题和成功提示不占面板高度；翻译成功后原文与只读译文同时留在可视区域，底部将清空、作原文、再译与插入／替换用短标签紧凑排成一行。只有确认才 commit；目标编辑器拒绝临时 composition 时，译文和确认入口仍保留。关闭、取消、清空及失败均撤销旧预览。
- 翻译直达按钮使用 Google Material Symbols 同系列的 `translate`「文/A」图标，替代通用地球图标；资源沿用项目的 Apache 2.0 许可说明，其他 JS App 保留通用工具图标。
- 翻译前缀要求空格或冒号，避免误删“英语很好”“中文测试”的首字。2026-11-14 对公开演示端点逐项、间隔请求：简体中文、英语、日语、韩语、法语、德语、俄语和西班牙语返回有效译文；繁体中文和意大利语目标返回 `errorCode=102`，因此不在选择项和前缀中。连续请求还可能返回 `errorCode=411`，界面明确说明这是服务频率限制，不代表所选语言不受支持。公开接口的后续可用性与翻译质量仍不由 APK 保证。

## 验证记录

以下本机结果绑定图标与翻译设备验证提交及对应 APK；后续 CI 补修另行记录，缺项、失败或环境阻塞不写成通过。

| 检查 | 结果 |
| --- | --- |
| JS／语音目标 JVM 与管理 UI | 2026-11-14 图标与翻译设备验证提交 `8c910f6c265405648eca61798cfb1331b5667b1d` 执行 39 份 JUnit XML、301 项；失败 0、错误 0、跳过 0，报告时间 02:55:59，`tools/check-junit-results.py` 返回 0 |
| Debug 构建、APK 白名单和链接审计 | 图标与翻译设备验证提交 `8c910f6c265405648eca61798cfb1331b5667b1d` 的 x86_64 Debug 构建通过，包名保留 `.debug`，versionCode 为 114，versionName 尾部为 `3-g8c910f6c2`；两项 APK 门禁重新执行均返回 0，链接审计使用主代码 `app/.cxx` 与 `native/rime-sdk` 的绝对路径。对应设备验证 APK SHA-256 `ad55d8905310b2452ccb2ca8002ac93a4494889ae3f3f1a7aab9e94c3e7ed1dc` |
| `rime` AVD | 2026-11-14 仅用 `adb -s emulator-5574` 操作上述图标与翻译设备验证 APK：原签名保数据 `install -r` 后 `firstInstallTime` 仍为 2026-11-09 15:04:36；工具栏「文/A」图标可见。选区 `hello` 点击翻译显示 `你好`，双框与四个单行短按钮可见，点“替换”后宿主变为 `你好`；改动原文后确认旧结果被拒绝；新面板选择日语后点击翻译显示 `こんにちは`，关闭后宿主恢复 `你好`。导航模式为 2、三项动画为 1，测试结束关闭临时测试面板 |
| 元书适配脚本 QuickJS 契约 | 真实 bundled 脚本经生产 `JsVoicePrelude.SOURCE` 执行 12 项：显式确认、预览被拒时保留译文、双框、作原文、连续翻译、前缀、取消别名、语言清单及 411 两类返回均通过 |
| 公开翻译端点 | 只发送“hello”“你好”“软件”等人工示例。间隔请求确认简中、英、日、韩、法、德、俄、西方向返回 HTTP 200、`errorCode=0`；繁中、意大利语目标返回 `102`，连续请求可返回 `411`。未发送用户文件内容；结果不保证后续服务可用 |
| 文档门禁 | 图标与翻译设备验证提交对应的文档树执行 VitePress 构建、链接、历史完整性、目录与搜索检查，以及 `git diff --check`，均返回 0；页面与附件链接错误为 0 |

设备回归只使用工作区唯一 AVD `rime`（AOSP API 35、`emulator-5574`），全程直接使用 ADB 读取和操作，保留长期数据并通过原签名 `adb install -r` 安装；未新建、克隆、清空或卸载模拟器。AVD 是模拟设备，不等于物理真机。真实 LLM 服务未提供账号密钥，未作模型请求；有道公开演示接口只作上述人工示例。

图标与翻译设备验证提交 `8c910f6c265405648eca61798cfb1331b5667b1d` 的本机证据保留在工作区 `.dsh-tmp/js-plugin-tools-handoff-20261114/`：`committed-final.xml` 记录正式上屏后的 `你好`，`japanese-final.png` 显示双框及日语译文，`cancelled-final.xml` 记录关闭后恢复的原文。此前 `c6c3adf270f812f818557765b0780b847487d8f9` 与 APK SHA-256 `586c441bba0f6c08ee914356cbd5f6bf4e0152202c2cb62c9d162a3a43846f73` 的设备回归保留在旧验证目录，不作为 `8c910f6c265405648eca61798cfb1331b5667b1d` 图标版本的验收证据。

## 2026-11-14 CI 补修

[代码 PR #37](https://github.com/SandyYuR/fcitx5-android/pull/37) 在提交 `8c910f6c265405648eca61798cfb1331b5667b1d` 的首轮远端检查出现两项失败；本机图标与翻译设备验证结果不等同于远端整套门禁通过。

| 失败检查 | 来源与原因 |
| --- | --- |
| PR CI / Debug APK build | [job 132118458613](https://github.com/SandyYuR/fcitx5-android/actions/runs/43492911401/job/132118458613) 已成功构建 APK，但 `lintFxDebug` 报告 25 个 `MissingTranslation`：新增 JS 工具与插件管理文案缺少德语、西班牙语、日语、韩语和俄语译文；后续 APK／链接审计因该失败跳过 |
| Direct Rime CI / Full frontend touch and engine tests | [job 132118459909](https://github.com/SandyYuR/fcitx5-android/actions/runs/43492911390/job/132118459909) 在 `compileFxDebugAndroidTestKotlin` 失败，`ClipboardSearchDeviceTest.kt:554` 仍调用已移除的 `JsApps.open`；设备测试尚未开始执行 |

补修提交 `d82283dc90b2e3edfcee5c046378fdef322a6599` 在上述五种语言中补齐 25 个键，共 125 条译文；剪贴板设备测试改用 `JsApps.openExact(service, assistant.id, "llm_assistant")`，通过插件 ID 与 App ID 精确打开内置助手。旧 APK 与设备证据继续绑定 `8c910f6c265405648eca61798cfb1331b5667b1d`，不改写成补修提交的设备验收。

| 补修检查 | 结果 |
| --- | --- |
| 本机 Android lint | 补修工作树执行 `:app:lintFxDebug` 成功，耗时 6 分 3 秒；报告 Error 0、Fatal 0、`MissingTranslation` 0、Warning 738，随后提交为 `d82283dc90b2e3edfcee5c046378fdef322a6599` |
| 本机 AndroidTest 编译 | 同一补修工作树执行 `:app:compileFxDebugAndroidTestKotlin` 成功，耗时 1 分 14 秒；仅编译测试源码，未执行设备测试 |
| PR #37 新提交远端检查 | 截至北京时间 2026-11-14 09:42:56，`d82283dc90b2e3edfcee5c046378fdef322a6599` 的 [PR CI 43541272571](https://github.com/SandyYuR/fcitx5-android/actions/runs/43541272571) 与 [Direct Rime CI 43541272602](https://github.com/SandyYuR/fcitx5-android/actions/runs/43541272602) 均完成且成功，六项 job 全部通过：JVM unit tests、Debug APK build、prototype、android_smoke、Full frontend ARM64 APK、Full frontend touch and engine tests。lint、APK／链接审计和前端设备步骤均执行成功 |

本机保存的远端证据为 `.dsh-tmp/js-plugin-tools-ci-20261114/pr37-d82283dc-ci-final.json`，包含相同 HEAD、核对时间和六项 job 的状态与步骤。前端设备工作流执行其既定测试清单；`ClipboardSearchDeviceTest` 在本轮仅完成编译，不计为该测试类已实际执行。

## 模拟器故障处理

测试前 AVD 桌面出现系统 ANR 焦点窗口，`dumpsys activity lastanr` 记录 2026-11-13 19:52:25：Launcher 没有 focused window，系统负载和内存正常。保存 ANR、窗口、输入分发与截图证据后，对现有 `rime` 做保留数据的普通重启；重启后 `lastanr` 为空，Launcher 恢复 focused window，手势导航和三项系统动画保持启用。未执行 wipe-data、`pm clear`、卸载或更换 AVD。

## 限制

- 没有物理真机验收；AVD 不能替代厂商系统窗口、触控和性能差异。
- 没有真实 LLM 模型服务联调；协议测试使用生产脚本与模拟响应。
- 内置翻译依赖第三方公开演示接口，可能限流、改变或停止服务。
- “恢复内置”会保留休眠的自定义源码，重新启用需再次定向导入；当前界面不另设“删除休眠源码”按钮。
- App `input` 字段目前不逐键向脚本发送 change 事件。用户编辑原文或改变目标后，确认操作会重新核对字段并拒绝旧结果；“作原文”会先撤销临时预览，再把只读译文放回原文框开始下一轮。
