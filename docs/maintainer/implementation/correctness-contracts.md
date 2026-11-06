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
| commit 只消费一次，展示刷新不得移动光标或重放旧提交 | `refreshPresentation` 与 native action 13；action 9 仅用于真实原始编码光标移动 |
| 候选点选绑定实际显示的数据版本与原始序号 | `CandidateSelectionTarget`、`selectCandidateIfCurrent`；横向、展开和浮动入口共用服务串行队列 |
| DOWN 必须有兜底 UP；focus 不覆盖显式 inactive binding | `focus` / `deactivate`、`KeyboardInputLifecycleTrackerTest` |
| 临时线程优先级必须恢复；提升失败不拒键 | `core/InteractiveThreadPriority.kt` |

无方案时，密码/直接输入绑定仍允许通过同一按键门控转发 DOWN/UP；普通 Rime 编辑器不能因此获得“可用”状态。维护、过期和部署失败状态继续拒绝输入。会话创建、方案/中英文恢复与首次状态发布都属于同步绑定阶段，不能在恢复方案前提前降回普通线程优先级。回归入口是 `InputBindingStartupTest`。

### 候选目标、坐标与展示快照

`CandidateWord.selectionTarget` 由生产者在发布前登记，包含编辑器/候选版本、原始序号及其局部页/全局含义。它不参与文字显示相等性。界面可以在不重绑相同文字的情况下更新目标，但不能用 backend 的最新版本给仍未加载完成的旧分页数据重新贴标签；点击时还需确认 adapter 项与 holder 实际显示的文字相符。`CandidateGenerationTracker` 同时观察引擎版本，避免第一屏相同就漏刷懒候选尾部。

方案菜单的当前项由 `SubModeManager.resolveSelectedSchemaAction` 从同一份动作列表的勾选状态解析，不能用 `currentIme().subMode` 的西文/密码直接输入标记推断方案。方案切换后，密码字段仍应保持直接输入和原编辑器焦点。

Android 光标位置以 UTF-16 计，服务交给引擎 API 的位置以显示文本 Unicode 码点计，Rime setter 使用原始编码 UTF-8 字节位置。`moveCursorIfCurrent` 校验排队时的编辑器及预编辑文本，再按需调用 `Native.rawInput(generation, revision)`。原始读取不消费 commit、不推进 revision，也不加入每键快照；`preeditCursorToRawBytes` 只接受可确定的文字、分隔符和边界映射，无法确定时重新发送当前 client preedit 恢复光标。“候选预览”不按预测词位置改写原始编码。

`refreshPresentation` 使用 native action 13 读取新结果，仍只消费一次待提交文字，但不调用 `set_caret_pos` 或重新 Compose。同模式重新呈现可以发送候选而不增加 `candidateVersion`；native `revision` 仍然前进。不能重新发送带旧 commit 的缓存快照，也不能把显示偏移传给原始光标 setter。

JNI 仅对未处理、无新 commit、同代次且所有展示值相同的结果复用字符串和数组。`Snapshot.reusePresentation` 必须创建新的不可变快照、使用新 revision 并清空旧 commit；接收方不得修改共享数组。缓存至多一份，文本预算为 64 KiB、数组字符串合计至多 1024 个；大结果走完整打包。停止、部署及同步返回时清理缓存。这是缓存预算，不是进程内存或延迟测量值。

### 同步完成与备份门控

`RimeSyncUserData` 的返回值表示是否启动维护线程。`Engine::sync` 等待线程后，还必须确认本次请求收到成功完成通知；失败在同次维护中保持有效，后续成功通知不能覆盖它，下一次请求则重新计数。原生同步会清理 session，不能继续复用旧句柄。运行时 `save()` 走相同维护门控；引擎未启动时跳过 native 同步，使已有用户文件仍可导出。真正复制备份时仍须通过下方停写入口，不能把同步成功当作停写完成。

会话销毁后，Lua 的 `Memory` 对象可能尚未回收，仍占用 LevelDB。同步维护先通过 `stop` / `initialize(false)` 释放模块与词库资源，再发起同步；不重跑进程级 `setup`，也不强制重编方案。初始化失败时不能继续同步并覆盖错误；`PluginSdkSmokeTest` 实际覆盖 Lua 已学习词条的同步与重启读取。

## 首次解锁前启动

`FcitxApplication.onCreate` 的所有设置初始化使用已选择的 `directBootAwareContext`。DE Context 的外部文件目录仍可能受凭据保护，`DirectRimeConfig` 在此模式只使用 DE 内部文件目录中的独立空 profile。解锁后的 `DirectBootLayoutSync` 在 IO worker 中把当前布局同步为固定的 DE 快照，锁屏时由 `DefaultConfigProvider` 读取，不扫描 CE 或外部目录。字体、图标资源和旧 CE 资产清理等待解锁；不把用户方案、词库、剪贴板历史或密钥搬到 DE。

布局快照按内容比较并原子替换；外部卷暂不可用时保留最后完整快照并退避重试，明确删除或无效 JSON 则清理快照。密码布局解析失败、为空或只有不可交互的占位键时，继续选择回退链中的下一项，最后回到内置 QWERTY。`DirectBootLayoutStoreTest`、`DirectBootLayoutSyncTest` 与 `DirectBootStartupTest` 分别覆盖存储、监听同步和真实初始化边界，不能代替真机冷重启验证。

`DirectoryObservers` 按规范路径共享唯一 `FileObserver`，布局快照与键盘热重载通过独立订阅使用它；最后一个订阅退出才停止监听。目录替换后重新挂载并拒绝旧代次事件，`DirectoryObserverRegistryTest` 守护这个约束。

锁定状态检查与解锁接收器注册之间需要复查解锁状态，避免错过广播后一直停留在 DE 模式。已经进入 DE 模式的进程收到解锁广播时仍退出，由正常 CE 初始化恢复用户设置；同步只保持原有 CE → DE 偏好白名单，不反向覆盖。`DirectBootStartupTest` 对普通 Context 的锁定存储访问抛异常，调用真实 Application 初始化，并检查正常解锁启动和注册竞态；不能用替换整个 `onCreate` 的 shadow 代替此回归。

## 备份与内置资产

- 完整备份先通过 `FcitxDaemon.beginUserDataBackup` 暂停原生写入，再生成缓存归档；`save()` 不能证明 Rime/LevelDB 已停止写入。恢复同样使用已有停机与事务入口。
- 共享目录在归档期间被其他写入者修改时须报错。多个输入法各用独立工作库，不删数据库或 `LOCK` 绕过占用。
- 更新 `app/src/main/assets/bundled/` 内布局或主题时，在 `data/BundledPresets.kt` 的 `assetSizes` 登记上一版字节数；登记新字节数会破坏用户改动保护。
- 用实际旧 Git 对象或发布资产核对尺寸。纠正登记时，安装器还记录本次更新所匹配的旧尺寸，只重试仍存在的未改原版；旧处理记录不能导致用户删除的主题重新出现。`BundledPresetsUpgradeTest` 使用真实旧谷歌图标 JSON 检查升级、错误旧标记、用户修改和删除。
- Debug 与 Release 的包名隔离属于迁移约定。文件图标的真实位置是 `input/config/ButtonIconFile.kt`，主题文件管理在 `data/theme/ThemeFilesManager.kt`。

完整事务见[整包恢复](./user-data-restore)与[加密备份](./protected-backup-format)。

用户词典枚举通过 `ListUserDictNames` 跳过恢复流程保留的 `.temp` 名称；不删除目录、不扩大到全部隐藏词典。状态栏入口用 `activateStatusAreaAction` 接住同步／部署异常并显示提示；底层 `save()` 仍传播失败，不能让备份把失败当成功。

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
| 分体行标记只位于行首，首键隐藏或被移动/删除/替换时仍保留；复制单键不复制整行设置 | `SplitRowLayoutPolicy`、`LayoutKeyOperations`、`LayoutJsonUtils.parseKeyJsonArray` |
| “合成中”只改变布局预览，不修改保存内容或实际输入状态；文本编辑面板不提供该开关 | `KeyboardPreviewManager`、`TextKeyboardLayoutEditorActivity` |
| 候选预设是写值动作，不切换模式，数值保持偏好允许范围 | `AppPrefs.Candidates`、`CandidatesPresetsTest` |
| 符号、表情、颜文字使用 catalog 与 locale 回退，面板独占键盘区 | 不改 `recentKey` / `Emoticon`；中文术语用“自定义/自訂” |
| 文本编辑底栏只从 `text_editor` 布局读取，不硬编码另一排按钮 | 新动作必须进入 `TextEditingWindow.executeTextEditingAction` 白名单 |

字段的用户说明见[按键参考](/features/keys/overview)和[布局编辑器](/features/editor/layout-editor)。特别注意：当前 SymbolKey 默认发送标准句号供方案处理，键面标签不等于上屏字符。

## 设置与主题

界面规范与共用组件见[界面设计](./design-system)。AI 面板与设置页通过同一主题入口取色；窗口重新分配标题、正文和操作区时不能重建输入连接或丢掉草稿。授权提示期间隐藏原操作区，关闭后不得重新绑定已失效的编辑器。

恢复进程的 UI 只读取主题资源，不可为统一外观引入偏好读取、主题管理器初始化或原生引擎。`restoreUiContext` 与主进程的 `SettingsUiPreferences.themedContext` 用途不同。

图片主题保存先写独立资源，最后原子发布主题定义；发布前的失败不得修改原主题图片。编辑器在持久化完成后才返回结果，未保存状态只携带轻量主题信息与私有缓存文件引用，不将大 Bitmap 放入 Bundle。

主题库按名称身份保留当前/日间/夜间选择，列表增删、移动或重命名不能依赖旧索引做位移推算；预览子视图须按卡片裁剪。普通表单与 AI 共用 `WrappingActionRow`，控件换行不改变事件 ID、编辑内容或保存时序。插件分组仍使用原始字段声明及 `visibleWhen`，密钥概况只复用 IO 中取得的状态。

主题预览监听与后台图片任务绑定视图生命周期；网格间距读取当前列数。预测性返回开关恢复默认时，以旧值和目标值是否不同决定是否重建宿主，不能假定默认值永远为 true。

主题网格的上下边距与行间距独立固定为 16 dp，不沿用按列宽算出的左右留白；单列和旋转都由 `ThemeGridResizeTest` 检查，仍须核对设备画面。

设置项搬家时同批修改 `SettingsGroupSpecs.kt`、承载页与 `SettingsSearchIndex.kt` 的 route/面包屑；新增项同步当前已有语言资源。`SettingsSearchIndexCoverageTest` 用于检查搜索覆盖。大资源文件用精确补丁插入/修改，不整文件读写重建。

主题“外观模式”的历史回退决定仍生效；只有用户明确要求重做，才重新评估数据迁移与主题切换耦合。
`normal_mode_theme` 不能加入 `ThemePrefs.dayNightModePrefNames`；未确认不把已存的 `follow_system_dark_mode` 布尔值改成三态。

## 语音与网络

更新器下载前通过 `UpdateDownloadStore` 核对明确的下载 ID／URI；只有文件内容与当前已安装 base APK 的 SHA-256 相同才可清理对应自有下载。安装回调不是成功依据，未知归属、内容变化、未安装或无摘要且发行身份不符的包不能删除或误复用。核对和摘要计算在 IO 线程，不能进入输入热路径。

AIDL 与 JS 复用会话和录音链路，密钥、主机授权、回调预算、stub/prelude 一致性都保持[JS 宿主协议](./js-voice-plugins)的约束。
远端剪贴板和语音缓冲可以有独立资源预算，但不能把它们的过载策略照搬到按键/提交事件上。

## 按症状定位

下表保留已有维护经验。路径发生变化时用真实符号、测试名和症状重新定位；不能因旧文件名未命中就判定问题不再需要守护。

| 症状 / 机制关键词 | 先看这里 |
|---|---|
| 首键丢字、启动中按键无反应、切应用后首键无效 | `core/InputKeyGate.kt`(门控与 Ticket)、`input/FcitxInputMethodService.kt` 的 `postFcitxKeyJob` / `postFcitxJob` 串行队列 |
| 翻页卡住、候选串页、长候选只加载一半 | 顶层函数 `readCandidatePage` 在 `core/CandidatePageReader.kt`、`DirectRimeBackend.getCandidates`、`input/candidates/expanded/ExpandedCandidateRefreshTest.kt` |
| 部署/同步/导入方案时乱码或吞键 | `DirectRimeBackend` 的 `maintain{}` 与 `redeploy`/`synchronize`/`reloadConfig`/`import-profile` |
| 同一段文字上屏两次 | `refreshPresentation`、native action 13、`Snapshot.reusePresentation` 与 commit 守卫。✗ 把带 commit 的快照重发一遍当「刷新」 |
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
