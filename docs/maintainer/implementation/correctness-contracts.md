# 正确性约束与代码定位

本页保存中州鹅现行的输入、前端、配置和数据约束，以及从工作区 `AGENTS.md` 移出的详细定位表。修改对应区域前先核对生产调用与测试，不能只确认文件存在。历史报告用于解释来源，不替代当前实现。

下文简写的 Kotlin 路径相对主仓库 `app/src/main/java/org/fcitx/fcitx5/android/`；测试类在 `app/src/test/` 或 `app/src/androidTest/` 中查找。

## 输入链路与事件队列

`input/FcitxInputMethodService.kt` 的 `postFcitxKeyJob` 在入队前取得 ticket，再复用 `postFcitxJob` 的串行队列。`core/DirectRimeBackend.kt` 在单一 owner 线程操作 librime，并校验输入会话代次。

当前事件载体是 `core/InputEventQueue.kt`，不是早期报告中的 `Channel(UNLIMITED)`：

- `ArrayDeque` 保存有序事件批次，提交、删除、按键、Ready 等非展示事件形成边界，不跨边界合并。
- 只有相邻且全部由展示状态构成的批次可以合并，保留对应状态的最新值；不能把该策略扩展到输入副作用。
- `available` 的 `Channel.CONFLATED` 只用于唤醒消费者，不承载正文。看到这个常量不能据此断言正在丢键。
- `awaitCapacity` 在进入 native owner 前等待；独立消费者仍可请求候选，不能让 owner 阻塞等待 UI 消费造成死锁。

`InputEventQueueTest` 检查慢 UI 状态合并、提交/Ready 顺序和背压时 owner 仍可读取；`InputKeyGateTest` 检查按键接收门控。检查工作流是否仍执行这些测试，并核对测试行为，而不是只看名称。

其他输入约束：

| 约束 | 当前入口 |
| --- | --- |
| 异步交付前验证 session/generation；同代次 `needsRime` 只提升 | `DirectRimeBackend.prepareInputSession` / `requireInputSession` |
| 分页遇组合变化必须取消，批间让出执行机会 | `core/CandidatePageReader.kt` 中的顶层函数 `readCandidatePage` |
| 部署、同步与重载期间关闭按键处理，finally 恢复 | `DirectRimeBackend.maintain` 与 `keyGate` |
| commit 只消费一次，刷新/状态提示不得重发 | `publishWithoutCommit` 与 native action 9 |
| DOWN 必须有兜底 UP；focus 不覆盖显式 inactive binding | `focus` / `deactivate`、`KeyboardInputLifecycleTrackerTest` |
| 临时线程优先级必须恢复；提升失败不拒键 | `core/InteractiveThreadPriority.kt` |

## 备份与内置资产

- 完整备份先通过 `FcitxDaemon.beginUserDataBackup` 暂停原生写入，再生成缓存归档；`save()` 不能证明 Rime/LevelDB 已停止写入。恢复同样使用已有停机与事务入口。
- 共享目录在归档期间被其他写入者修改时须报错。多个输入法各用独立工作库，不删数据库或 `LOCK` 绕过占用。
- 更新 `app/src/main/assets/bundled/` 内布局或主题时，在 `data/BundledPresets.kt` 的 `assetSizes` 登记上一版字节数；登记新字节数会破坏用户改动保护。
- Debug 与 Release 的包名隔离属于迁移约定。文件图标的真实位置是 `input/config/ButtonIconFile.kt`，主题文件管理在 `data/theme/ThemeFilesManager.kt`。

完整事务见[整包恢复](./user-data-restore)与[加密备份](./protected-backup-format)。

## 键盘形态与渲染

| 约束 | 实现入口 |
| --- | --- |
| 不在布局回调中同步重建视图树；自动形态不写手动偏好，切换后显式重建 | `input/keyboard/BaseKeyboard.kt`、`toggleFloatingMode(persist = false)` |
| 工具栏尺寸统一换算，44 dp 足迹和 40 dp 最小宽度不随百分比变，候选字体/格子独立 | `input/bar/ToolbarMetrics.kt` |
| API 30 以下只退化 `setRoundRect`；不能把 `setConvexPath` 当安全退路 | `input/bar/ContinuousCornerGeometry.applyCardOutline` |
| 自定义版本判据用 `@ChecksSdkIntAtLeast(api = 30)`，分支让静态分析可识别 | 不使用 `@Suppress("NewApi")` 掩盖调用错误 |

键盘形态、工具栏和主题显示必须用真机或模拟器画面验证；涉及边缘、留白或圆角时按需测量像素/墨迹，不能用 JVM 单测结果代替。

## 布局字段与手势兼容

| 约束 | 实现 / 回归入口 |
| --- | --- |
| 子模式和分体条目统一解析，不在调用方 substring 或改内部键字符串 | `LayoutJsonUtils.VARIANT_SUBMODE_PREFIX` 与 `parseEntryKey` |
| 空格光标移动优先于自定义划动；空格不注册通用 `Swipe` | `SpaceSwipeMode.kt` 的顶层函数 `resolveSpaceSwipeEnabled`、`SpaceSwipeModeTest` |
| 合成中退格上滑仅清编码（Escape），非合成才清正文；连删边界单独保护 | `input/BackspaceBoundaryGuard.kt`、`BackspaceBoundaryGuardTest` |
| `PlaceholderKey` 不注册 behavior/popup | `KeyDefPreset.kt`、按键行为绑定 |
| override 链末尾继承 `transparentBackground` / `staticDisplay` 等显示标志 | `KeyDef.Appearance`、`inheritPresentationFlagsFrom` |
| `AlphabetKey.displayText` 是否显式设置按 JSON 字段存在性判断，不用值是否等于字符判断 | 显式文本不作 Shift/Caps 改写 |
| 默认子模式可合法映射 null，不以 null 判越界 | `SubModeManager.isValidSpinnerPosition`、`SubModeSpinnerSelectionTest` |
| 候选预设是写值动作，不切换模式，数值保持偏好允许范围 | `AppPrefs.Candidates`、`CandidatesPresetsTest` |
| 符号、表情、颜文字使用 catalog 与 locale 回退，面板独占键盘区 | 不改 `recentKey` / `Emoticon`；中文术语用“自定义/自訂” |
| 文本编辑底栏只从 `text_editor` 布局读取，不硬编码另一排按钮 | 新动作必须进入 `TextEditingWindow.executeTextEditingAction` 白名单 |

字段的用户说明见[按键参考](/features/keys/overview)和[布局编辑器](/features/editor/layout-editor)。特别注意：当前 SymbolKey 默认发送标准句号供方案处理，键面标签不等于上屏字符。

## 设置与主题

图片主题保存先写独立资源，最后原子发布主题定义；发布前的失败不得修改原主题图片。编辑器在持久化完成后才返回结果，未保存状态只携带轻量主题信息与私有缓存文件引用，不将大 Bitmap 放入 Bundle。

主题预览监听与后台图片任务绑定视图生命周期；网格间距读取当前列数。预测性返回开关恢复默认时，以旧值和目标值是否不同决定是否重建宿主，不能假定默认值永远为 true。

设置项搬家时同批修改 `SettingsGroupSpecs.kt`、承载页与 `SettingsSearchIndex.kt` 的 route/面包屑；新增项同步当前已有语言资源。`SettingsSearchIndexCoverageTest` 用于检查搜索覆盖。大资源文件用精确补丁插入/修改，不整文件读写重建。

主题“外观模式”的历史回退决定仍生效；只有用户明确要求重做，才重新评估数据迁移与主题切换耦合。
`normal_mode_theme` 不能加入 `ThemePrefs.dayNightModePrefNames`；未确认不把已存的 `follow_system_dark_mode` 布尔值改成三态。

## 语音与网络

AIDL 与 JS 复用会话和录音链路，密钥、主机授权、回调预算、stub/prelude 一致性都保持[JS 宿主协议](./js-voice-plugins)的约束。
远端剪贴板和语音缓冲可以有独立资源预算，但不能把它们的过载策略照搬到按键/提交事件上。

## 按症状定位

下表保留已有维护经验。路径发生变化时用真实符号、测试名和症状重新定位；不能因旧文件名未命中就判定问题不再需要守护。

| 症状 / 机制关键词 | 先看这里 |
|---|---|
| 首键丢字、启动中按键无反应、切应用后首键无效 | `core/InputKeyGate.kt`(门控与 Ticket)、`input/FcitxInputMethodService.kt` 的 `postFcitxKeyJob` / `postFcitxJob` 串行队列 |
| 翻页卡住、候选串页、长候选只加载一半 | 顶层函数 `readCandidatePage` 在 `core/CandidatePageReader.kt`、`DirectRimeBackend.getCandidates`、`input/candidates/expanded/ExpandedCandidateRefreshTest.kt` |
| 部署/同步/导入方案时乱码或吞键 | `DirectRimeBackend` 的 `maintain{}` 与 `redeploy`/`synchronize`/`reloadConfig`/`import-profile` |
| 同一段文字上屏两次 | `publishWithoutCommit`、native action 9、快照 `candidateVersion` 与 commit 守卫。✗ 把带 commit 的快照重发一遍当「刷新」 |
| 候选序号角标、长候选滚动 | `input/candidates/CandidateIndexResolver`、`input/candidates/expanded/`;`CandidateIndexBadgeTest`/`CandidateOverflowTest`(androidTest) |
| 工具栏 / Kawaii Bar 尺寸、图标与按钮间距 | `input/bar/ToolbarMetrics.kt`、`input/bar/ui/idle/`;`ToolbarMetricsTest`/`KawaiiBarRowMetricsTest`。✗ 让 `BUTTON_FOOTPRINT_DP` 跟着百分比缩放 |
| 卡片圆角在 Android 10 崩溃(`path must be convex`) | `input/bar/ContinuousCornerGeometry.applyCardOutline`;真机墨迹测量见 `docs/maintainer/history/DIRECT_RIME_SCREEN_PERF_2026-10-17.md` |
| 键盘形态/分体/浮动、浮动手柄位置 | `input/keyboard/BaseKeyboard.kt`、`input/FloatingMoveHandlePlacement.kt`、`input/keyboard/SplitRowWidthsTest.kt`。✗ 在布局回调里同步重建视图树 |
| 布局编辑器保存丢配置、子模式/分体条目错乱 | `ui/main/settings/behavior/utils/LayoutJsonUtils.kt`、`ui/main/settings/behavior/data/LayoutDraftStore.kt`;`LayoutJsonUtilsTest`/`SplitLayoutSaveJsonTest` |
| 布局选择器(profile)行为 | `TextKeyboardLayoutProfileManagerActivity`/`TextKeyboardLayoutEditorActivity`;`KeyboardLayoutAdapterViewTest`(androidTest) |
| 符号/表情/颜文字面板组名或导入异常 | `input/picker/SymbolCatalog.kt`、`SymbolPanelUi.kt`、`PickerWindow.kt`、`PickerWindowPreset.kt`;`SymbolCatalogParseTest` |
| 空格划动 / 划动移光标开关不生效 | `input/keyboard/SpaceSwipeMode.kt`、`BaseKeyboard.kt`、`AppPrefs.spaceSwipeMoveCursor` |
| 空格长按(切方案/选单/语音) | `input/keyboard/CommonKeyActionListener.kt` 的 `SpaceLongPressAction` 分支;`SpaceKeyDefTest` |
| 退格在编码区/正文之间跳 | `input/BackspaceBoundaryGuard.kt`;`BackspaceBoundaryGuardTest` |
| 设置页入口消失、搜索搜不到、跳转错页 | `ui/main/settings/group/SettingsGroupSpecs.kt`、`ui/main/settings/search/SettingsSearchIndex.kt`、`SettingsSearchQuery.kt`;`SettingsSearchIndexCoverageTest`。✗ 只改偏好与承载页、漏登记索引 |
| 方案菜单/子模式下拉越界 | `ui/main/settings/behavior/manager/SubModeManager.kt`;`SubModeSpinnerSelectionTest` |
| 主题切换被打断、日/夜跟随异常 | `data/theme/ThemePrefs.kt`(`dayNightModePrefNames`)、`ThemeManager.kt`;`ThemeSerializationTest` |
| MD3 设置页布局/配色回归 | `tools/test-material-settings.sh`(诊断图落 `app/build/reports/androidTests/material-settings-diagnostics/`) |
| 内置布局/主题没更新、或覆盖了用户改动 | `data/BundledPresets.kt` 的 `assetSizes`(登记的是**上一版**字节数)、`app/src/main/assets/bundled/`。✗ 登记成本次的新字节数——判据立刻失效 |
| 日志导出截断、子进程泄漏 | `ui/main/log/LogExportStore.kt`/`LogExportViewModel.kt`、`utils/Logcat.kt`;`LogExportStoreTest`/`LogcatLifecycleTest` |
| 语音结束时文本消失 | `input/voice/VoiceInputProviderManager.kt`(partial 提交);`VoicePcmQueueBudgetTest` |
| 语音插件设置页打不开、报 `ReferenceError: __n_xxx is not defined` | `input/voice/js/JsVoiceInputProvider.kt` 里的 `object JsVoiceStubNatives`(无网络求值用的原生桩)与 `JsVoicePrelude.kt` 的 `__native` 是否同时声明了该 API;`JsVoiceProviders.describe` |
| 语音插件导入失败 / 被拒绝 | `input/voice/js/JsVoiceImporter.kt`(体积、解析、`JsVoiceImportPolicy`)、`JsVoicePluginMeta`(id 形状、host 归一化、内置 id 保留);`JsVoiceImporterTest` |
| 语音插件导入后列表里没有 / 首选语音失效 | `JsVoiceProviders.install()`/`reload()` 的注册回收、`JsVoicePluginStore.list()` 的缺失文件清理、`AppPrefs.keyboard.preferredVoiceInput` 是否指向已删插件;`VoiceInputProviderManager.isProviderId` |
| 语音插件连不上、鉴权 401、域名被拒 | `JsVoiceHelpers.isAllowedUrl`(声明域白名单)、`JsVoiceHeaders`(认证头与前缀规则、保留头)、`JsVoiceSettingsStore`(密钥读写);`JsVoiceHeadersTest`/`JsVoiceHelpersTest` |
| 语音插件识别结果丢失、收尾卡住 | `JsVoiceInputProvider` 的 START/AUDIO/MESSAGE/STOP 超时与 `FINISH_WATCHDOG_MS`、`VoiceInputProviderManager` 的收尾看门狗分支 |
| 备份/恢复后用户词库异常 | `data/RimeProfileBackup`/`UserDataRestoreTransaction`、`FcitxDaemon.beginUserDataBackup` |
| 冷路径 IO、候选/按键耗时测量 | `tools/input-perf/`(含 `test-leveldb-open.py`、`probe-cold-io.sh`)、`tools/candidate-perf/`、`tools/screen-perf/` |
| 合并/交付/归档标签与历史决策 | `docs/maintainer/history/DIRECT_RIME_RELEASE_READY_2026-10-21.md`、`DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md` |
| 旧 Fcitx 实现与历史故障经验 | `docs/legacy/fcitx/notes/HANDOVER-rime-only.md`(顶部已标「历史维护记录」,**只作史料,不作现行依据**) |
