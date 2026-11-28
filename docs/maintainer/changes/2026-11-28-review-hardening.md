# 2026-11-28 审阅修复分支与文档覆盖

本轮按真实提交差异核对本地修复分支的 21 笔提交、149 个不同代码路径。复核后，12 组补齐或更正现行说明，1 组精化措辞，8 组由既有契约完整承接；21 组均有对应文档，无未处理的覆盖缺口。这里的“覆盖”指变更的使用、协议、维护和验证边界已有准确说明。

> **分支状态：** 代码来自本地 `fix/review-hardening-20261128`，文档在 `docs-review-hardening-20261128` 补齐。本轮没有推送、合并默认分支或发布文档站。默认分支与已上线站点不会因本地记录自动更新。

## 固定范围

- 代码基线：`393f5b593db682091a52101df46dd68b096b0afc`。
- 代码终点：`959bd10072b07b81565959a7a21431ac24eb6470`。
- 文档复核基线：`392e9b25bb5a942530d965f16385a7e72686d4fc`；它已含前轮资源、存储及脚本授权说明的两笔文档提交。
- 逐提交完整 SHA、真实改动路径和现行页面映射见 [JSON](./commit-map-2026-11-28.json)。没有新增或改名功能页，现行源码入口继续由[功能覆盖表](./feature-coverage)统一维护。

## 逐组覆盖结果

| 组 | 代码提交与范围 | 本轮处理 | 现行说明 | 判断依据 |
| --- | --- | --- | --- | --- |
| 01 | `6a7f24ab1` 文件访问 | 既有契约足够 | [correctness-contracts](/maintainer/implementation/correctness-contracts) | 已有可信应用根、逐级链接检查、原子替换失败保旧契约。本组无需另立功能；本轮维护说明另澄清 Windows 验证与长期设备边界。 |
| 02 | `580537ec6` 备份恢复 | 本轮补齐／更正 | [user-data-restore](/maintainer/implementation/user-data-restore) | 补齐主偏好 XML 与权威 .bak 的统一可移植名称、其他变体排除及四个启动字段的类型预检。 |
| 03 | `d2151fc15` 剪贴板与同步 | 本轮补齐／更正 | [clipboard-sync](/features/clipboard-sync) · [correctness-contracts](/maintainer/implementation/correctness-contracts) | 补齐 OneClip 独立的单行 1 MiB、事件 2 MiB 预算；不借用 ClipCascade 或 JS App SSE 的限制，也不改变静默长连接规则。媒体身份与失效保护兑现原契约。 |
| 04 | `8515709d0` 方案解析 | 本轮补齐／更正 | [correctness-contracts](/maintainer/implementation/correctness-contracts) | 补齐万象编辑器的图深度、唯一节点、边、展开节点及 UTF-16 文本与输出预算，明确不推广到通用 Rime YAML 加载器。 |
| 05 | `6a29d9ce5` 更新事务 | 本轮补齐／更正 | [update-checker](/features/update-checker) · [hosts-downloads](/maintainer/implementation/hosts-downloads) | 补齐 Hosts 刷新持久请求身份、换源清旧映射与保留当前设置的合并规则；下载检查点的崩溃加固沿用既有恢复契约。 |
| 06 | `d89470b3c` 内置资源 | 本轮补齐／更正 | [icon-themes](/features/theme/icon-themes) · [theme-editor](/features/theme/theme-editor) · [correctness-contracts](/maintainer/implementation/correctness-contracts) | 长度与已证原版摘要双重核验、未知旧文件保留和缺失补装已有说明；本轮更正旧尺寸措辞及旧主题文件仍存在时的迁移承诺。朝花键位修正按原布局语义登记。 |
| 07 | `c727f1744` 主题导入 | 本轮补齐／更正 | [theme-editor](/features/theme/theme-editor) · [correctness-contracts](/maintainer/implementation/correctness-contracts) | 将原有编辑保存事务明确扩展到同名 ZIP 导入替换：独立新图片、原子发布定义、失败保旧、共享旧图延后回收，目录载荷也计预算。 |
| 08 | `8f9612c25` 主题界面 | 既有契约足够 | [theme-editor](/features/theme/theme-editor) · [correctness-contracts](/maintainer/implementation/correctness-contracts) | 已有颜色草稿、主题合并、字体图标失效及绘制契约足够；此次为实现修正，没有新增主题字段、入口或版本承诺。 |
| 09 | `55d8c00d6` 输入会话 | 本轮补齐／更正 | [swipe-actions](/features/keyboard/swipe-actions) · [correctness-contracts](/maintainer/implementation/correctness-contracts) | 补齐正文水平光标的代理对、选区收边与不可靠查询时停止的规则；不承诺组合音标或 ZWJ 字形簇分段。旧编辑器提交拒绝沿用既有归属契约。 |
| 10 | `1d2367085` 候选显示 | 既有契约足够 | [correctness-contracts](/maintainer/implementation/correctness-contracts) | 现行契约已区分字体全量失效和局部绑定代次、候选目标及过期拒绝；稳定标识与滑动夹具修正不新增用户功能。 |
| 11 | `af703b0d6` 输入面板 | 本轮补齐／更正 | [symbol-panel](/features/symbol-panel) · [correctness-contracts](/maintainer/implementation/correctness-contracts) | 补齐正常抬手提交与系统取消仅关闭浮层的区别；辅助栏比例、配置回退和弹出容量兑现已有说明。 |
| 12 | `704185e1d` 输入状态 | 既有契约足够 | [correctness-contracts](/maintainer/implementation/correctness-contracts) | 既有异步预览归属、内联建议退出与替换、回车动作恢复契约足够，无新增凭据读取或建议类型。 |
| 13 | `dfcca5e41` 语音采集 | 既有契约足够 | [correctness-contracts](/maintainer/implementation/correctness-contracts) · [voice-input](/features/voice-input) | 已有 3000 ms／31 包预滚完整回灌后开放实时入口、失败中止、每 feed 统计及代次隔离的精确契约。 |
| 14 | `2879399f9` 脚本宿主 | 可选措辞精化 | [js-voice-plugins](/maintainer/implementation/js-voice-plugins) · [voice-input-js](/features/voice-input-js) · [voice-input-js-en](/features/voice-input-js-en) · [voice-input-js-reference-zh-CN](/features/voice-input-js-reference-zh-CN) · [ai-assistant](/features/ai-assistant) | 版本授权、来源失效、App 能力和静态主机批准中英说明已有覆盖；本轮仅精化 AI 用户页，每次发送的生成、模型发现与纠正共享 60 秒、合计最多三次 HTTP。 |
| 15 | `1887c1b18` 布局编辑 | 本轮补齐／更正 | [qr-share-import](/features/qr-share-import) · [macrokey-editor](/features/editor/macrokey-editor) · [macro-key](/features/keys/macro-key) · [correctness-contracts](/maintainer/implementation/correctness-contracts) | 更正同名 QR 导入会替换编辑草稿并仍需保存的边界，包含高度和辅助栏；移除已有 Android KeyRef 二次保存会丢失的过时警告，保持新增类型控件限制。 |
| 16 | `885e02438` 设置工具与统计 | 本轮补齐／更正 | [input-statistics](/features/input-statistics) · [correctness-contracts](/maintainer/implementation/correctness-contracts) | 明确捕获与图表统一为 32 行、总计 128 键，超预算整体回退完整频次图；不扩大字词及热力标签数量。其余导出和目录记忆修正沿用原契约。 |
| 17 | `df1b959ac` 日志 | 既有契约足够 | [DIRECT_RIME_MAINTAINER_GUIDE_zh-CN](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN) | 维护手册已有 256 项／256 Ki 字符、64 项批写、普通记录汇总丢弃及关键记录背压；同毫秒交接落实原快照接实时的语义，不宣称日志永不丢失。 |
| 18 | `a90bf307a` 引擎兼容 | 本轮补齐／更正 | [rime-enhancements](/features/rime-enhancements) · [DIRECT_RIME_USER_GUIDE_zh-CN](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [engine-update](/maintainer/engine-update) · [correctness-contracts](/maintainer/implementation/correctness-contracts) | 明确无会话时通过引擎配置 API 读取 user 的 var/previously_selected_schema 并校验 schema_list，失败回首项；词典／prism 的降级检查另述。传统图形键符兼容修正不扩大 Compose 承诺。 |
| 19 | `58715b739` 性能工具 | 既有契约足够 | [DIRECT_RIME_MAINTAINER_GUIDE_zh-CN](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN) | 现行维护手册已有显式设备与 AVD 身份、破坏性重置开关、实际 APK 身份、after-tests 与 fixture/artifact 来源。工具内部修正无需重复新增功能页。 |
| 20 | `a99fe863d` 设备闭环 | 本轮补齐／更正 | [DIRECT_RIME_MAINTAINER_GUIDE_zh-CN](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN) · [LOCAL_BUILD_ENVIRONMENT_zh-CN](/maintainer/LOCAL_BUILD_ENVIRONMENT_zh-CN) · [documentation](/maintainer/documentation) | 补齐长期 rime 禁用 connected 生命周期、同签名覆盖安装与直接 instrumentation、引擎前置和隔离归还、文件恢复不等于系统授权；补记原生可执行位置对照及根因取证边界。 |
| 21 | `959bd1007` 入口说明 | 既有契约足够 | [DIRECT_RIME_USER_GUIDE_zh-CN](/manual/DIRECT_RIME_USER_GUIDE_zh-CN) · [rime-enhancements](/features/rime-enhancements) | README 新导航与现行用户手册入口已一致；无须为导航纠正增加新的使用步骤。 |

## 需要更正的使用边界

二维码布局导入确认后会切换目标文件并替换其编辑器草稿，连同高度和辅助栏一起导入，仍须保存；同名目标不保证自动另存。已有 Android 宏引用保留类型并按平台键名显示，新建入口仍不提供公开的 Android 类型或整数输入。正文左右光标只保证不拆开 UTF-16 代理对，不承诺组合音标或 ZWJ 字形簇分段；无法取得可靠位置或相邻文本时停止本次移动。符号浮层的系统取消只关闭，不提交选中项。

OneClip 的行／事件限制、万象编辑器的共享节点预算和 Hosts 映射请求身份分别记入各自协议，避免与 JS App SSE、Rime 通用 YAML 或持久下载任务身份混用。主题同名导入、资源升级和旧主题迁移按现有文件与已证摘要判定，去掉无条件迁移或仅凭长度替换的旧措辞。

## 前轮产品验证与本轮文档检查

前轮修复工作树已完成 374 套、3091 项应用 JVM 测试，失败／错误／跳过均为零；两个原生主机 CTest 目标通过。唯一 `rime` 模拟器为 API 35、x86_64、`emulator-5574`，最终 36 个设备用例为界面与路径 25、真实 librime 配置 1、HTTPS 宿主与取消 2、内置输入 4、指定 EVO 输入 4；配置用例内部运行 11 组 YAML、22 次启动停止。性能工具的 131 项离线测试沿用当时源码未变的结果。这些是前轮证据，不是本轮重新运行，也不代表每个修复都经过独立实机验收或所有语音／AI／SSE／WebSocket 供应商都已验证。

原生临时目录问题通过同字节可执行文件、同工作目录和 TMP 对照确认与运行映像位置相关：工作区中返回 Win32 拒绝访问，`D:/GitHub/tools` 中成功；MSVC 把失败进一步映射为 `not_a_directory`。精确拦截组件尚未取证。遵循本机工具目录后两个目标通过，没有修改 ACL 或安全设置。主机 fake API 契约测试不替代真实引擎的 YAML 设备验证。

长期使用的模拟器须采用授权的同签名覆盖安装和直接 instrumentation；`connected...AndroidTest` 管理安装卸载，不作为该设备的默认入口。此前流程误用导致卸载及外部恢复属主不符的情况已修正，文件快照不能证明系统授权、Keystore 或活跃数据库事务已恢复。最后保留用户后来正常部署的数据、指定 EVO 与系统授权，隔离测试逐字归还本次捕获的配置和原方案文件，没有执行旧快照全局回滚。当前规则见[维护手册](/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN)和[本机环境](/maintainer/LOCAL_BUILD_ENVIRONMENT_zh-CN)。

本轮仅修改文档、索引与映射，未改产品或测试代码，未运行设备或公网请求。

### 本轮文档门禁

最终文档树已构建成功（VitePress 1.6.4；292 项目录、46 条保留路由、709 篇当前搜索文档、3139 个下载附件）。构建输出含 Rollup 单块超过 500 KiB 的非阻断提示，无构建失败。六项检查均退出码 0：内部链接 345 页／32363 个链接／1794 个资源；遗留页 64 页、13 份维护报告与 83 个原始文件核验通过；目录 292 文档／46 路由，新增提交映射通过；搜索 8 个查询及 5 个范围检查通过；证据索引 57 个根／60 个逐文件章节／24 个目录行，错误 0；显式绑定修复代码分支的源码覆盖检查 1744 路径、136 个引用、当前错误 0。文档工作树 `git diff --check` 结果见最终提交记录。本轮未安装或变更依赖，也未运行产品、设备或公网测试。

## 相关记录

- [代码与文档对照索引](./)
- [现行功能文档与源码入口](./feature-coverage)
- [文档维护约定](/maintainer/documentation)
