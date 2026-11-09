# 正确性约束与代码定位

本页保存中州鹅现行的输入、前端、配置和数据约束，以及从工作区 `AGENTS.md` 移出的详细定位表。修改对应区域前先核对生产调用与测试，不能只确认文件存在。历史报告用于解释来源，不替代当前实现。

下文简写的 Kotlin 路径相对主仓库 `app/src/main/java/org/fcitx/fcitx5/android/`；测试类在 `app/src/test/` 或 `app/src/androidTest/` 中查找。

## 输入链路与事件队列

`input/FcitxInputMethodService.kt` 的 `postFcitxKeyJob` 在入队前取得 ticket，再复用 `postFcitxJob` 的串行队列。`core/DirectRimeBackend.kt` 在单一 owner 线程操作 librime，并校验输入会话代次。

当前事件载体是 `core/InputEventQueue.kt`，不是早期报告中的 `Channel(UNLIMITED)`：

- `ArrayDeque` 保存有序事件批次，真实提交、删除、按键转发、Ready 等生命周期事件形成不可跨越的合并边界。
- 只有相邻展示批次可以合并，保留对应状态的最新值；启用输入统计时，它们可以附带有界的统计键记录，但不能把展示合并或统计丢弃策略扩展到输入副作用。
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

无方案时，密码/直接输入绑定仍允许通过同一按键门控转发 DOWN/UP；普通 Rime 编辑器不能因此获得“可用”状态。维护、过期和部署失败状态继续拒绝输入。`Native.beginInput` 内的会话创建、方案/开关恢复与首次状态发布都属于同步绑定阶段，保持交互线程优先级，失败时也必须恢复原优先级。回归入口是 `InputBindingStartupTest`。

新会话的方案与 `ascii_mode` 直接采用 `Native.beginInput` 返回的快照，不能用上一编辑器发布的状态再次调用 `select_schema` 或 `set_option`。方案的 `reset`、已保存的开关值和 `switcher/fix_schema_list_order` 均由 librime 决定；临时西文状态不能由前端回放到下一应用。同应用普通编辑器的 native 会话复用仍保留其实际状态，跨应用与敏感边界创建新会话。真实引擎回归入口是 `DirectRimeFrontendTest.newSessionsRespectRimeAsciiReset`、`newSessionsRespectRimeSchemaOrder`、`newSessionsKeepRimeSavedAscii`、`newSessionsKeepRimeRecentSchema` 与 `newApplicationDoesNotInheritInlineAscii`。

复用要求前后两个输入框的 native `noLearning` 都为 false、应用名非空且相同、原 session 仍有效。密码、PIN、禁止个性化学习和 Direct Boot 均隔离会话，回到普通输入框时也不能复用先前的敏感 session；仅清空 composition 不足以清除 Lua 等组件的内部状态。经过密码框后恢复 `ascii_mode` 的方案 reset 值符合这一契约，不能为保留 Shift 临时模式而重放前端快照。

状态区方案开关的 native action 8 和菜单中英文切换的 action 6 共用 `Engine::setOption`，仅将已部署 `default.yaml` 的 `switcher/save_options` 成员写入 `user.yaml` 的 `var/option/<name>`；内部下划线选项不保存。librime Switcher 使用 `.default`，不能合并当前输入方案下的同名列表。重部署、同步或重启后清除白名单缓存；互斥组和 `ascii_mode` 的关闭值与开启值都按列表保存，按键产生的临时西文变化不经过这一前端保存入口。会话恢复仍由 librime 执行，方案 `reset` 保持优先。菜单切换项用编码区相同的资源文案显示目标模式，英文状态的菜单入口也复用英文模式文案；内部 action ID、`ascii_mode` 名称与子模式身份不随显示名称更改。`engine_test.cpp` 检查写入范围，`DirectRimeFrontendTest.schemaSwitchPreferencesSurviveNewSessionsAndRestart` 使用真实 librime 检查跨会话、重启、互斥组、reset 和白名单更新；`modeMenuRemembersBothDirections` 与 `modeMenuWithoutSaveOptionsStaysSessionLocal` 检查菜单切换的保存与不保存边界。仅运行 fake API 测试不能证明真实恢复行为。

### 候选目标、坐标与展示快照

`CandidateWord.selectionTarget` 由生产者在发布前登记，包含编辑器/候选版本、原始序号及其局部页/全局含义。它不参与文字显示相等性。界面可以在不重绑相同文字的情况下更新目标，但不能用 backend 的最新版本给仍未加载完成的旧分页数据重新贴标签；点击时还需确认 adapter 项与 holder 实际显示的文字相符。`CandidateGenerationTracker` 同时观察引擎版本，避免第一屏相同就漏刷懒候选尾部。

方案菜单的当前项由 `SubModeManager.resolveSelectedSchemaAction` 从同一份动作列表的勾选状态解析，不能用 `currentIme().subMode` 的西文/密码直接输入标记推断方案。方案切换后，密码字段仍应保持直接输入和原编辑器焦点。

Android 光标位置以 UTF-16 计，服务交给引擎 API 的位置以显示文本 Unicode 码点计，Rime setter 使用原始编码 UTF-8 字节位置。`moveCursorIfCurrent` 校验排队时的编辑器及预编辑文本，再按需调用 `Native.rawInput(generation, revision)`。原始读取不消费 commit、不推进 revision，也不加入每键快照；`preeditCursorToRawBytes` 只接受可确定的文字、分隔符和边界映射，无法确定时重新发送当前 client preedit 恢复光标。“候选预览”不按预测词位置改写原始编码。

`refreshPresentation` 使用 native action 13 读取新结果，仍只消费一次待提交文字，但不调用 `set_caret_pos` 或重新 Compose。同模式重新呈现可以发送候选而不增加 `candidateVersion`；native `revision` 仍然前进。不能重新发送带旧 commit 的缓存快照，也不能把显示偏移传给原始光标 setter。

JNI 仅对未处理、无新 commit、同代次且所有展示值相同的结果复用字符串和数组。`Snapshot.reusePresentation` 必须创建新的不可变快照、使用新 revision 并清空旧 commit；接收方不得修改共享数组。缓存至多一份，文本预算为 64 KiB、数组字符串合计至多 1024 个；大结果走完整打包。停止、部署及同步返回时清理缓存。这是缓存预算，不是进程内存或延迟测量值。

### 同步完成与备份门控

`RimeSyncUserData` 的返回值表示是否启动维护线程。`Engine::sync` 等待线程后，还必须确认本次请求收到成功完成通知；失败在同次维护中保持有效，后续成功通知不能覆盖它，下一次请求则重新计数。原生同步会清理 session，不能继续复用旧句柄。运行时 `save()` 走相同维护门控；引擎未启动时跳过 native 同步，使已有用户文件仍可导出。系统关机广播直接进入最终关闭门控并等待 `Native.stop()`，不调用 `save()` 启动词典同步；关机门控一旦设置，迟到的连接或恢复回调不能重启引擎。真正复制备份时仍须通过下方停写入口，不能把同步成功当作停写完成。

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

用户词典枚举通过 `UserDictManager::GetUserDictList` 精确跳过恢复专用的 `.temp` 名称，不扩大到全部隐藏词典。每次整批同步在枚举前通过数据库自身的删除操作回收旧 `.temp`；锁定或删除失败须终止同步。单个快照恢复在打开临时库前安装清理守卫；打开或合并失败均须尝试清理，最终清理失败也须传播失败。下一次恢复必须先确认旧数据已清空，不能把两个词典的记录混入同一临时库。数据库实现不认识的文件只记录路径并保留，不能递归删除。状态栏入口用 `activateStatusAreaAction` 接住同步／部署异常并显示提示；底层 `save()` 仍传播失败，不能让备份把失败当成功。

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

标点键面与弹出项按布局和预设原文展示，`TextKeyboard.refreshSymbolKeyLabels` 保留样式刷新、辅助标签、字母大小写和显式显示文本的边界，`PopupComponent` 不再通过 Fcitx 标点映射替换标签。旧 `transformPunctuation` 字段继续兼容读取，但不能重接 `PunctuationComponent`、`PunctuationManager`、`chttrans` 页面或旧回调。Rime 的 `punctuator`、通用 YAML 预设和 OpenCC 数据继续保留；`TextKeyboardLabelsTest` 与真实 `DirectRimeFrontendTest.symbolKeysAndPopupsUseRimePunctuation` 分别验证键面和引擎上屏。

## 设置与主题

界面规范与共用组件见[界面设计](./design-system)。AI 面板与设置页通过同一主题入口取色；窗口重新分配标题、正文和操作区时不能重建输入连接或丢掉草稿。授权提示期间隐藏原操作区，关闭后不得重新绑定已失效的编辑器。

AI、剪贴板搜索和剪贴板编辑共用的 `JsAppDialog` 位于父 IME 窗口下方，原编码区和浮动候选在面板上层实时更新；编码宽高变化不驱动面板避让或复制到另一视图。`JsAppEditText` 只为当前本地编辑器按实际文字布局绘制并定时闪烁光标，不能伪造真实窗口焦点或改变原应用输入会话。忙碌、授权提示、选区、只读、隐藏、分离和关闭期间取消光标任务，恢复输入时立即显示。已有面板在键盘页面切换的布局请求期间不重新变透明；AI 按钮再次点击走当前会话的取消和预览恢复。AI 下拉选择项采用紧凑的 36 dp 普通行、上下各 4 dp 留白，整行可点，按实际文字测高并受键盘边界约束。`JsAppFloatingPanelTest` 检查实际光标像素、暂停/恢复和选项高度，`ClipboardSearchDeviceTest` 用真实软键、重叠区域截图像素及无窗口焦点的光标帧验证三种生产入口。

原生宏动作与万象值编辑表单在内容安装后清除 `FLAG_ALT_FOCUSABLE_IM`：初始没有文本框不能使动态新增、动作切换或原始 YAML 编辑失去系统输入法。`allowImeInput` 不用于需要保留原应用连接的 IME 附着面板。回归包括 `MacroEditorDialogLayoutTest` 的新旧动作输入、`WanxiangAdvancedActivityTest.booleanAndListDialogsCanSwitchToTextWithoutBlockingTheIme` 及设备上的动作确认回填；测试输入不提前写入布局或配置文件。

恢复进程的 UI 只读取主题资源，不可为统一外观引入偏好读取、主题管理器初始化或原生引擎。`restoreUiContext` 与主进程的 `SettingsUiPreferences.themedContext` 用途不同。

图片主题保存先写独立资源，最后原子发布主题定义；发布前的失败不得修改原主题图片。编辑器在持久化完成后才返回结果，未保存状态只携带轻量主题信息与私有缓存文件引用，不将大 Bitmap 放入 Bundle。

主题库按名称身份保留当前/日间/夜间选择，列表增删、移动或重命名不能依赖旧索引做位移推算；预览子视图须按卡片裁剪。普通表单与 AI 共用 `WrappingActionRow`，控件换行不改变事件 ID、编辑内容或保存时序。插件分组仍使用原始字段声明及 `visibleWhen`，密钥概况只复用 IO 中取得的状态。

主题预览监听与后台图片任务绑定视图生命周期；网格间距读取当前列数。预测性返回开关恢复默认时，以旧值和目标值是否不同决定是否重建宿主，不能假定默认值永远为 true。

主题网格的上下边距与行间距独立固定为 16 dp，不沿用按列宽算出的左右留白；单列和旋转都由 `ThemeGridResizeTest` 检查，仍须核对设备画面。

设置项搬家时同批修改 `SettingsGroupSpecs.kt`、承载页与 `SettingsSearchIndex.kt` 的 route/面包屑；新增项同步当前已有语言资源。`SettingsSearchIndexCoverageTest` 用于检查搜索覆盖。大资源文件用精确补丁插入/修改，不整文件读写重建。

主题“外观模式”的历史回退决定仍生效；只有用户明确要求重做，才重新评估数据迁移与主题切换耦合。
`normal_mode_theme` 不能加入 `ThemePrefs.dayNightModePrefNames`；未确认不把已存的 `follow_system_dark_mode` 布尔值改成三态。

## 输入统计与键盘测试

本节功能已于 2026-11-07 合入默认分支，对应 [`fx-rime-only@2e48466ef`](https://github.com/SandyYuR/fcitx5-android/tree/2e48466efdb6bf600302eea1dae898acf0b3d18c)。行为参考固定在 [`qxm888/trime-mie@415ff9c37f5c9b439e8455316b64d7fffe3cffc9`](https://github.com/qxm888/trime-mie/tree/415ff9c37f5c9b439e8455316b64d7fffe3cffc9)，数据层、表单和图表按行为规格独立实现，不包含上游统计实现代码。

- 输入统计入口位于“设置 → 便捷功能”列表最下方；`SettingsGroupSpecs` 与 `SettingsSearchIndex` 的入口和搜索面包屑必须一致。统计卡片将数值、单位和标签分层排布。
- 热力图收紧键间距与文字内边距，键面文字使用基于屏幕密度的固定字号，不随应用或系统字体缩放；键行高度也不因字号设置而放大。固定字号例外不扩展到普通页面文字、分布图或按键频次条形图。最小宽度按实际完整占比、键宽比例与内外间距计算，保留捕获的物理键位、分离空隙和左右方向。完整显示格式化后的百分比，非零且不足 0.1% 时显示为 `<0.1%`，不因窄键截断占比。
- 热力图能完整放入实际视口时隐藏“左右滑动查看完整键位”；只有确实溢出时才横向滚动并在当前测量周期显示提示，不用固定 320 dp 的建议宽度制造额外滚动。验证包含普通页面内宽、放大字体、真实窄屏、特殊键宽与分享图片，不能只用无边距的 320 dp 画布判断页面可完整展示。
- `WordCloudView` 的视觉规格对照上述上游版本：以 12–41 sp 的频次权重字号为基准，高频前六项加粗，使用主色系渐变和椭圆螺旋排布，画布基准高度为 280 dp。字号和放置仍受可用面积约束，显示条目不能超出画布或相互重叠；这不等于显示全部已保存字词，也不构成真机验收结论。
- 输入统计、键盘测试与万象工具文案维护英文默认值和中文翻译，其他应用语言回退到英文；`MissingTranslation` 例外仅限 `input_stats.xml`、`keyboard_test.xml`、`wanxiang.xml` 和 `wanxiang_advanced.xml` 的默认资源，不全局关闭翻译检查。
- `InputStatisticsSource` 只随原有真实按键 ticket 进入串行任务，保留编辑器代次和统计 epoch。`DirectRimeBackend` 将是否统计的布尔值随同一次 key/action JNI 调用传入；原生端仅返回打包为 `Long` 的操作前 UTF-16 码元长度与命令标记，不另调用 `Native.rawInput`，也不为统计生成 Java 原始编码字符串。用于速度分段的 `composing` 取操作后已有 Snapshot 的 preedit 状态，经按键与提交事件传到 actor；首键开始组合、退格清空、顶功自动上屏后仍留新组合都必须保留正确后态。关闭统计不增加这次 `get_input` 观察；开启时仍需读取并扫描原生编码，不能宣称零开销。汉字提交在 Service 确认原应用接受后记录，不能把状态刷新、重复快照或本地编辑连接当作上屏成功。
- `InputEventQueue` 在纯展示合并批次中按原顺序归集统计附属记录，生成最多 1024 条 entries 的 `StatisticsKeysEvent`，不再让每个统计键成为展示合并边界。真实 commit、forward、delete 和生命周期事件仍完整保序，不因统计而丢弃或合并。统计来源代次或 epoch 更换时移除旧附属记录；容量溢出只减少可选统计，经 `recordDropped` 反馈到 `rejectedEvents`，不能阻断真实输入或把少统计描述成丢键。
- Service 和 `InputStats` 双重检查敏感条件及启用状态。密码、可见／网页密码、PIN、禁止个性化学习、`TYPE_NULL`、首次解锁前与内部编辑不记录。关闭／重新开启、清除或导入会推进 epoch，拒绝旧源票据；actor 在有序操作边界复核，清除前已接收的事件不能在清除之后复活。
- 按键计数与热图分别来自准许的 Rime DOWN 和真实软键按压。热图方案来自引擎实际选中项，不使用统计页筛选；布局只在变化时生成快照，宏按键只取键面名称。`KeyboardStatisticsLayout` 限制行数与键数，拒绝占位键和不可交互键。
- `InputStats` 在单个 IO actor 中使用 SQLite 批量持久化，输入路径仅排队有界值。数据库位于 `noBackupFilesDir/input-stats`，防止普通整包备份未经一致性快照直接复制活跃数据库；专用 JSON 导入先校验、保留本机备份，再通过事务替换，不改变记录开关。
- `EndSession` 与已经接收的按键、提交命令按 FIFO 顺序处理。重复结束只能在没有新输入隔开的相邻边界间合并，不能因前一个结束命令尚未消费而吞掉后一个编辑器的边界。`onFinishInputView` 也结束速度段，防止隐藏键盘并清空原生组合后仍将后台停顿算入旧段。
- 统计关闭或没有待保存数据、待结束输入段时，actor 阻塞等待命令，不做每秒轮询。待保存数据以首次变更后一秒为固定期限，连续输入不推迟期限，达到 64 个样本可提前写入；未完成合成且数据已保存时也不设置定时唤醒。非合成段只保留一次五秒以上的结束期限，已排队输入先处理，不能被空闲结束操作越过。`StatsActorScheduleTest` 通过纯调度策略检查这些边界，不用机器耗时阈值代替正确性断言。
- 日记录支持本地自然周期与全部日期查询。趋势图按所选范围完整汇总：今天、本周、本月按日，本年按月，全部日期按年；较长年份范围合并相邻年份，不得只截取最近若干项，各柱之和仍等于范围总量。清选定范围保留生涯字数，清峰值同时移除其短窗口尾段。`clearAllData` 删除全部方案、日期、词频、布局、生涯记录、统计分享缓存及导入前备份和其 `.tmp` 文件，并关闭两个开关。清除不依赖“统计已启用”，不能在关闭时变成只改内存。
- 分享使用带 `recordingEpoch` 的数据快照，文字和图片分享都复核代次；异步图片生成也在写入与交付前检查。关闭、清除或导入后取消旧快照分享，不能由已有对话框或生成任务重新输出已清除数据。
- 计数只接受完整纯汉字提交，汉字按码点而非 UTF-16 单元计数；字词频次只在单独授权后排队文本。词云采用完整短词和单字，不作联网分词；每日期／方案最多 512 种字词、128 种热力标签。统计队列最多容纳 1024 个待处理输入事件，拒收数量可见，不能宣称不完整记录是精确总量。
- 有效速度段至少一秒；根据操作后的组合状态，没有未完编码时，五秒以上键间隔会分段，已有未完编码时保留段内停顿。一次操作同时上屏旧词并开始下一编码时，提交不能把 `composing` 强制清空。峰值由累计至少十秒的完整速度段计算，关联键速和码长来自同一窗口。保存尾段以支持重启续算，切换本地日期时切断段，避免跨日期归错时长。
- `KeyboardTestPanel` 的“文本／数字／密码／PIN”通常使用四列等宽布局；字号或可用宽度不足时改成两行两列，每格仍等宽，不让单个标签自行挤到下一行。PIN 是 `TYPE_CLASS_NUMBER | TYPE_NUMBER_VARIATION_PASSWORD`；密码类型同时设置掩码和 `IME_FLAG_NO_PERSONALIZED_LEARNING`。涉及密码的类型切换、关闭、宿主停止均清空临时内容；不写草稿，支持的系统版本禁用自动填充与内容捕获。Android 10（API 29）在挂载输入框前通过 `ContentCaptureManager` 关闭当前 Activity 的内容捕获，并在该 Activity 存续期间保持关闭；Android 11（API 30）及以上对输入框和面板设置 View 级内容捕获排除。普通文本仍走真实编辑器，遵循方案的正常学习及用户启用的统计设置；不保存草稿不代表禁止学习或统计。

数据验证入口为 `StatsDataTest`、`InputStatsTest`；输入交付、真实软键和页面验证见 `InputStatisticsBackendTest`、`InputStatisticsDeliveryTest` 与对应 UI 测试。测试存在及组件渲染不代表真机验收完成。用户操作与统计口径见[输入统计与键盘测试](/features/input-statistics)。

## 万象方案更新与配置编辑

本节记录万象更新与配置编辑的实现边界，应用源码入口见[现行功能覆盖表](/maintainer/changes/feature-coverage)。2026-11-07 的上游对照固定到 [Android 更新器源码树 `533962986f5fbbcb03f61be314f3d7268fa983b8`](https://github.com/amzxyz/rime-wanxiang-data/tree/533962986f5fbbcb03f61be314f3d7268fa983b8/apps/android/wanxiang-updater)。

应用采用 Kotlin 与现有 librime 实现下载、表单与配置编辑，不打包上游 Python 或 Compose 运行时。与独立更新器的区别是：目标固定为当前 Rime 工作目录，省略更新器自身升级及目标目录集管理；应用文件后调用本项目的真实 Rime 部署，保留维护门控与失败恢复。

- `WanxiangSelection.packages` 是更新范围的事实源。附加词库只属于 `ALL`、`DICTIONARY`、`EXTRA`；`SCHEME` 和 `MODEL` 即使仍保存先前勾选项也不能下载它们。全量包含模型，界面必须明确提示；`EXTRA` 至少选一项。
- `WanxiangUpdateWorker` 先准备暂存资源，`DirectRimeBackend` 在维护入口核对 profile 与规范路径后应用；不能把独立更新器的文件复制当作本项目部署完成。`WanxiangProtection` 在暂存及安装前检查保护规则；Pro 附加词库的辅助码字表在加工和安装时复核，不能混用中途变化的字表。
- `WanxiangDownloadProgress` 只计算有可靠总量的当前文件百分比，不能用资源项数估算字节进度。`OperationStatusView` 的百分比参数只在工作态生效，旧调用仍使用不定进度；下一文件、等待重试、解压和安装阶段清除上次的百分比。Worker 在元数据及响应头等待前发布当前资源名，进入后续阶段前等待最后一次异步进度写入完成，通知与页面使用相同阶段。
- 下载缓存命中也要发布已验证完成的字节进度。系统因网络约束停止任务时保留分片和已验证资源；仅明确 `CANCELLED` 或不可重试失败清理下载缓存。暂存解压目录始终清理，恢复任务重新准备文件，再交给原事务安装。
- `ResumableAssetDownload` 对 HTTP 404 在首次请求后最多重试三次，耗尽后抛出不可重试的 `MissingAssetDownload`。重试只清理分片及网络校验状态，保留 MediaStore 发布 URI，避免私有副本损坏后恢复时重复发布。万象跳过缺失资源并记录名称与地址；共用下载器的 APK／自定义更新 Worker 则终止任务，不能再触发整项 Worker 重试。其他错误不能作为缺失资源吞掉；跳过后准备阶段没有可安装文件时返回失败。
- `WanxiangUpdateResult` 将完整结果原子写入私有任务目录：`completed` 保存成功／无变更结果及跳过清单，`failure.json` 保存终止失败的原因及此前跳过的资源。WorkManager Data 仅放有界摘要，200 个合法附加词库同时缺失也不能因 10 KiB 上限把已安装更新变成失败。`WanxiangSkippedAsset` 保留组件、文件名、真实仓库来源和内部下载诊断；页面只显示仓库资源不可用及跳过的组件，不把 404 堆栈、长链接或更换下载源要求展示给用户。附加词库始终归属 GitHub，不能因主下载源选择 CNB 而误归因。页面在 IO 线程加载结果并拒绝旧任务的迟到结果；`NO_CHANGES` 和失败态同样保留简短清单，无可部署资源的失败态显示“本次未部署”，只有实际部署成功才提示其余内容已部署。兼容旧 `1`／`no-changes` 完成标记，损坏标记不能导致再次安装。对应 Worker、Activity、Result 测试守护这些行为。
- 两次保护过滤均可能清空暂存文件。`NO_CHANGES` 结果与完成标记保留此区别，页面显示未写入／未部署，进程恢复不能把它改成部署成功。`WanxiangDictionaryConverter.lite` 使用上游固定声调字符映射，保留 `ê` 等无关字符；不能用 NFD 归一化后一律删除组合标记替代该映射。
- `WanxiangUpdateActivity` 重新读取目录时清除过期目标，读取失败提供页面内重试，恢复成功清除对应加载错误。任务排队且尚无目标进度时显示等待说明，不能拿当前新目录代替已锁定的任务目录；写入前的目标校验继续保留。
- `WanxiangGithub` 使用现有设备密钥加密 Token，只向受支持的 `amzxyz` GitHub 仓库 API 附带授权头；普通下载和重定向目的地不获得 Token。目录、保护规则与网络配置可收起，适用范围提示和当前主要操作保持明确，不能以“已保存密钥”代替联网成功。
- `WanxiangAdvancedFiles.snapshot` 只读源配置、补丁与现有编译配置，不删除 `build/` 或隐式重建。共享默认配置可以读取；修复部署是单独的显式操作。草稿经指纹和目标核验后才进入保存事务，失败保留草稿并恢复被替换文件。
- 取消短语／简码关联须写入空 `dictionary` 引用。只调用 `reset` 删除补丁会重新露出源文件默认关联，导致取消勾选无效；这一点不能照搬上游相同的删除补丁逻辑。`WanxiangCommonSettingsTest` 覆盖补丁和直写模式下取消、重载及重新关联。
- `WanxiangYaml.fields` 除源文件字段外还枚举本地补丁新增路径，保持原有指令和注释处理。否则“添加配置项”保存后会从列表消失；`WanxiangAdvancedSettingsTest` 覆盖保存、重新打开和再次编辑。
- 恢复高级设置草稿时先校验已有指纹，再补载在保存实例状态时尚未完成的文件选择；已有编辑不能被重载覆盖。移除 `/=`、嵌套替换或映射合并覆盖须保留相邻字段的含义，复杂祖先补丁无法安全拆分时明确拒绝并保留整份草稿；后来的父字段编辑应移除已有子路径覆盖，不能依赖 YAML 插入顺序绕过 Rime 自身的路径排序。

页面继续使用 `SettingsUiActivity`、`EditorUi`、`MaterialSpinner` 与 `WrappingActionRow`，按范围隐藏不生效的选项，并将更新／保存状态与当前操作放在容易触及的位置。短窗口应能滚动到全部操作；用户步骤见[万象更新与高级设置](/manual/DIRECT_RIME_USER_GUIDE_zh-CN#万象更新与高级设置)。

Robolectric 页面及候选队列测试统一使用 `FcitxDaemonTestShadow`，每项用例安装独立 delegate，并在结束时清理；`@Resetter` 提供测试框架重置。不能给同一个 Kotlin singleton 注册多个 shadow 类型，否则复用沙箱后可能在页面销毁阶段发生类型转换异常。原页面导航、目标目录、保存回滚和候选顺序断言继续保留。

截至 2026-11-07，此功能的真机或模拟器行为验收尚未完成：本机没有连接的真实设备，模拟器缺少硬件加速，软件模式启动也未成功。组件渲染和 JVM 测试不能替代大字号、深浅色、实际触摸与真实方案更新的设备验收，旧提交的 CI 结果也不能代替最终提交验证。

## 自定义方案更新

功能参考 `Carlson24/LyraIME@e5b2f0101cc35e6ce8b8f2a9667a93d1d5b6979e` 的 `CustomTasksFragment`、`TaskModels` 和 `DownloadManager`，在本项目中独立实现。保存任务、网络/本地来源、解压/复制、排除规则及批量执行均保留；输出改用当前 Rime 目录内的相对路径，通过系统文件选择器授权读取来源。当前支持 ZIP、TAR/GZ/BZ2/XZ，不依赖上游的设备 `tar` 进程，也不包含 TAR.ZST 支持。

- `RimeSchemeUpdates` 将目标和已选任务写入私有不可变快照，后台任务不读取后来编辑的列表。`RimeUpdateCoordinator` 保留原万象任务名称，并互斥两种更新入口的启动。
- `RimeSchemeUpdateWorker` 复用断点下载和设备 Hosts 配置，不复用万象 GitHub Token。已知大小显示当前文件百分比，未知大小保留不定进度；进入解压或下一阶段前等待最后一次进度持久化。更换目录时拒绝旧任务，排队时从任务快照读取目标。
- `RimeSchemePayload` 检查相对路径、重复项、TAR链接、文件数量、膨胀大小和剩余空间，排除项同样计入解压限制。任务可向多个相对子目录准备资源，后面的任务覆盖前面准备的同名资源；准备阶段不写工作目录。
- 整批准备成功后由 `DirectRimeBackend` 的维护入口再次核对目标，停止引擎后进入原有恢复事务。`WanxiangUpdateTransaction` 的通用资源标志默认为false，仍可恢复旧万象日志；自定义更新与万象更新的失败恢复都在引擎重启前完成。取消不能中断正在提交或恢复的事务。
- 默认排除补丁、短语、调频文件；用户可以编辑这些排除规则，但隐藏状态、`build/`、`sync/`、用户词典、`user.yaml`、`installation.yaml` 仍不能作为资源写入。未安装任何资源时不部署；重置任务仅清理配置并保留私有恢复副本，不清理已安装文件。
- 页面复用 MD3 色板、卡片、输入框和折行操作行。路径默认折叠，主要操作固定；小窗口改为可滚动布局。编辑草稿跨旋转和文件选择保留，非法规则和路径不能保存。

JVM与组件渲染不能代替设备上的文件提供方、真实网络续传及实际方案部署验收；现有 Windows 环境的符号链接用例仍受权限限制。

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
