# 2026-11-17 按键图标主题与水彩、普蓝成套主题

本分支把按键、数字键盘、工具栏、状态区与文本编辑栏按钮接入图标主题，并内置「水彩」「普蓝」两套成套主题（配色 + 键盘布局 + 图标主题 + 按键副字体）。代码在 `feat/key-svg-icons`，未合入默认分支。提交在 2026-11-19 按主题整理为 6 个并强推；「普蓝」原名「蓝狐狸」，同日更名。

> **后续复核：** 这 6 个提交已于 2026-11-19 合入默认分支 `fx-rime-only`，整理后的 SHA 依次为 `8e6749c9`、`da8561cd`、`229d7cd2`、`460d3939`、`d2b992b6`、`577872dd`（与下表 `064b0c33c` … `bae629c18` 一一对应）。下文“未合入默认分支”为当时状态；现行说明见[图标主题](/features/theme/icon-themes)与[主题编辑器](/features/theme/theme-editor)，对照登记见[2026-11-22 提交对照](./2026-11-22-code-doc-gap-review)。

## 提交与产物

| 提交 | 内容 |
| --- | --- |
| `064b0c33c` | 功能(键盘): 按键支持图标主题槽位并叠加副标签 |
| `3576cf09c` | 功能(界面): 工具栏与标题栏按钮接入图标主题 |
| `115fb6b81` | 功能(键盘): 增加分体键盘工具栏动作 |
| `be249be18` | 功能(主题): 内置水彩与普蓝成套主题 |
| `1f8b1a378` | 修复(主题): 按评审修正迁移、颜色与图标刷新 |
| `bae629c18` | 修复(主题): 统一图标主题扫描与选择的发布边界 |

合并时把原 `15a0e1ed6` 的 8 条翻译按首次引用它们的提交拆开：4 条随第一个提交，4 条随成套主题提交；原 `e666bedd0` 与 `4beb213d2` 合并为一个主题提交，直接采用后者整理后的资源，避免中间提交先加 1259 行再删除。旧 8 提交（`873562edd`）与 19 提交（`c5914ac3a`）历史分别保留在本地 `backup/key-svg-icons-8-commits`、`backup/key-svg-icons-19-commits`，均未推送。

分支已变基到默认分支 `23b8e70be`。整理前的树为 `6c2e4336`；当前树 `0b1a18f` 是在默认分支新增改动之上的结果，六个提交的补丁与整理前一致。

## 按键图标槽位

`KeyDef` 增加 `iconSlot`，`KeyView` 在键面底层画图标；有图标时主标签隐藏，副标签叠在上层。关闭「显示按键副标签」只影响绘制，上滑、下滑仍按副标签实际存在与否触发。

- `keys.letter.a` … `keys.letter.z`：字母键，一枚图标覆盖大小写两种键面；
- `keys.capslock.none/once/lock`、`keys.backspace`、`keys.return.{default,go,search,send,next,previous,done}`、`keys.language` 与 `keys.language_ascii`、`keys.space`、`keys.comma`、`keys.period`；
- `keys.numpad` 与 `keys.numpad.0`–`9`、`add/subtract/multiply/divide/equal/decimal/at/percent/space/layout`：数字键盘层；
- `text_editor.layout/select_all/cut/copy/paste/backspace`：文本编辑栏；
- `toolbar.*`、`system.*`：工具栏、状态区、剪贴板与扩展窗口的标题栏按钮（返回键三处共用一个槽位）。

布局 JSON 的按键可写 `iconSlot`，槽位名取自图标主题，详见[图标主题](/features/theme/icon-themes#在布局里指定槽位)。

图标层固定 24 dp 方框（`KEY_ICON_SIZE_DP`），按键过矮时按短边 `0.68` 收缩，再按 8 px 分桶，避免宽键上的图案比窄键大一圈。多色 SVG 在深色主题下会把三通道都 ≤ `0x30` 的近黑笔画换成按键文字色，色点等其它颜色保留；单色 SVG 仍整体染色，缓存键带文字色后缀。回车键则先记录槽位，组件重建后按当前图标主题重新解析，不再因为 `EditorInfo` 不重发而回落到系统箭头。

## 图标刷新与副标签

- 行缓存按布局签名分存，中文与 ASCII 各有一份。`BaseKeyboard.forEachKeyViewIncludingCachedRows` 同时遍历当前行与 `reusableRowsCache`，`refreshIconTheme()` 与 `refreshAltLabelVisibility()` 都走它；`KeyboardWindow` 的图标主题回调遍历 `keyboards.values`，隐藏的数字键盘与文本编辑栏一起刷新。
- `__layer__:` 层子布局不再被 `layoutUsesSubMode` 当成「按方案细分」，平铺布局的中文与 ASCII 共用同一份行缓存，切过去不会复用带旧图标的行。
- 切图标主题时 `BaseKeyboard.refreshIconTheme()` 先刷新全部键视图，再调用 `onIconThemeRefreshed()` 钩子；`TextKeyboard` 在钩子里按当前 `CapsState` 重选 once/lock 槽位，之后重放动作回车图标。
- SVG 预取与绘制缓存都带主题代次：`IconThemeManager` 在 `activeTheme` 更换时清缓存并推进代次，预取和发布在写回前再校验一次；缓存操作由 `svgCacheLock` 包住，避免同名主题保存后旧任务把旧内容写到新键上。
- 「副标签存在时移动主字符」同时移动图标层：`KeyView.applyIconLayerPosition` 对 `Bottom`（标签在 `altText`）与 `DirectionalDownBottom`（下滑标签在 `altText1`）把图标改为 `topToTop` + `bottomToTop = 副标签`，角落标签仍居中。`LetterIconKeyTest` 断言开关打开时图标上移且不压住副标签、关闭后回到垂直居中。

## 水彩成套主题

- 资源：`bundled/themes/水彩.json`（由「水彩（浅色）」改名）、`bundled/keyboard_layouts/TextKeyboardLayout.水彩.json`（`rime.default` 4 行 + `__layer__:数字` + `text_editor`）、`bundled/icon_themes/水彩.json`（93 槽）、`bundled/fonts/字体图标.otf`。
- 2026-11-19 按 foxy 最新素材重生成：字母与功能键图标按墨迹面积归一（`target_area=2600`、`max_edge=62`），统一视觉大小；逗号与句号改用 foxy 行内 SVG（`keys.comma`／`keys.period`），不再依赖内置 `CommaKey` 的笑脸图案；空格文字色写 `#5c6b89`；功能键逐键写 `textColor: #12151A`，深色配色下也保持可读。
- Shift 三态按 foxy 原图重绘：`none` 原样、`once` 去掉底部横线、`lock` 保留横线；`toolbar.floating_toggle` 改用「尺寸」图标，并新增 `toolbar.split_keyboard`（「分体」图标）。
- 数字层 4 行，首列宽 0.16、末列宽 0.15，与主键盘退格、回车同宽；键面用 `keys.numpad.*` 图标，`MacroKey` 同时保留 `label` 作为非水彩图标主题下的回退文字。
- `ThemeKits` 按主题名登记布局、图标主题与按键副字体；`ThemeListFragment` 在配套未就位时询问「一并应用」还是「仅应用配色」，成套应用顺序为布局 → 图标主题 → 副字体 → 配色，配套已就位时不弹框。副字体只改 `key_alt_font` 一项，保留用户配过的其它字体与字号；字体文件尚未落盘或 `fontset.json` 读取失败时跳过写入。
- 改名迁移：`ThemeManager.legacyThemeNames` 把「水彩（浅色）」迁到「水彩」；`BundledPresets` 升级时先退役仍是原版内容的旧文件，再改选择，用户改过的旧文件继续沿用。`FcitxApplication` 在 `installAsync` 完成后再扫一次主题列表，避免启动时的异步扫描早于资源落盘。

## 普蓝成套主题

从 foxy 蓝狐狸套件移植，2026-11-19 更名「普蓝」并重做资源：

- 资源：`bundled/themes/普蓝.json`、`bundled/keyboard_layouts/TextKeyboardLayout.普蓝.json`（`rime.default` 4 行 + `__layer__:数字` + `text_editor` 1 行）、`bundled/icon_themes/普蓝.json`（56 槽：26 个字母手写字形、Shift 三态、退格／回车／语言／`keys.numpad`，以及工具栏与谷歌风格光标、快捷短语等操作图标）。数字与符号没有美术资源，普蓝原包的数字键本就是纯文字键，因此数字层按键用 `label` 显示字符，不引用不存在的 `keys.numpad.0`–`9` 槽位。
- 数字层为 5 列 4 行（`+ 1 2 3 ×` / `− 4 5 6 ÷` / `@ 7 8 9 ⌫` / `ABC = 0 . ↵`）；文本编辑栏 6 键（`ABC` 文字键 + 全选／剪切／复制／粘贴 + 退格）。两个 `ABC` 都用 `LayoutSwitchKey` 显示文字，不借用 `keys.numpad.layout` 图标——foxy 的 `foxy.LayoutDefault` 是 `SwitchKeyboardLayout(layoutId=default)`，没有 icon override。
- 布局：第 2 行两端用 `PlaceholderKey` 内缩（`EmptyKey` 在本工程不是已知类型，会回落成带文字的默认键）；底行 `?123` 用 `MacroKey` + `iconSlot: keys.numpad` 显示狐狸尾巴图标。`LayoutSwitchKey` 的 JSON `subLabel` 被解析为切换目标，不显示为副标签。
- 字母键 `MacroKey` 写显式 `displayText`（大写字母），`TextKeyboard.updateAlphabetKeys` 在 `CapsState.None` 看到显式文本就保留原样，实现普蓝固定大写；`SpaceKey` 的 `textColor` 用主题 `KEY_TEXT`，不再沿用 `#FF5C6B89`。
- 工具栏 `toolbar.floating_toggle` 改用「尺寸」原图，`toolbar.split_keyboard` 用「分体」图标；补谷歌图标主题的 17 个槽位（`toolbar.undo/redo/more/icon_theme/virtual_keyboard/browse_user_data/edit_fontset`、`keys.cursor_up/down/left/right`、`keys.home/end/pageup/pagedown`、`keys.quickphrase/unicode`）。
- 配色：`keyboardColor` 与 `backgroundColor` 取同色。`Theme.backgroundDrawable(keyBorder)` 在 `key_border` 开启（默认）时用 `backgroundColor` 画键盘容器，`keyboardColor` 实际不参与，两者不同会让同一主题在开关两种取值下呈现两种底色。功能键用 `altKeyBackgroundColor`（#D1D3D9）。
- 副字体：`ThemeKits` 登记 `keyAltFont = 字体图标.otf`，与水彩共用同一份文件（两包字体 sha256 相同），不重复落盘。
- `BundledPresets` 把旧「蓝狐狸」三份资源登记为退役（56240 / 19729 / 709），并把 `assetSizes` 中普蓝布局的上一版字节数登记为 9436（本次新布局 9902）；`LegacyThemeFilenameTest` 的内置主题数量断言由 13 改为 14。
- 更名迁移：`ThemeKits` 第二条改为「普蓝」；`ThemeManager.legacyThemeNames` 把「蓝狐狸」迁到「普蓝」；`BundledPresets.ensureLayoutProfile` 把布局选择从「蓝狐狸」改到「普蓝」；`IconThemeManager.restoreActiveTheme` 把活动图标主题从「蓝狐狸」改到「普蓝」。迁移只在原版旧文件被退役删除后执行，用户改过、安装器保留的旧文件继续沿用其选择。

## 配色、布局颜色与资源升级

- `ThemeKits.Kit` 增加 `specialKeyOvalShape`，水彩与普蓝都设为 `false`；`ThemeListFragment.applyKit` 在写主题前应用，`null` 表示不改用户已有的选择。
- 两套主题改用 foxy 的实际颜色：键面 `#FFFFFF`、键盘底 `#F7F7F5`、功能键 `#CBCED5`、动作键 `#073B97`、文字 `#12151A`。
- 布局 JSON 的 `backgroundColorMonet`／`textColorMonet`／`shadowColorMonet` 支持以 `theme:` 前缀引用主题字段，由新增的 `LayoutKeyColorResolver` 解析；文本编辑栏按钮同样接入（`FoxyTextEditingUi.createConfiguredButton`）。功能键与动作键在布局里显式写 `textColor`，深色下保持可读。
- 新增 `utils/ColorLiteral.kt` 的 `parseArgb`：支持 `#RGB`、`#ARGB`、`#RRGGBB`、`#AARRGGBB`、`0x` 与十进制；6 位形式补 `FF` alpha。`LayoutJsonUtils.parseOptionalInt`、`LayoutKeyColors.parseColor`、`LayoutJsonUtils.normalizeKeyValue`、`KeyEditorForm.parseColorInt` 与 `LayoutDataManager.normalizeKeyValue` 统一走该实现。
- `BundledPresets.installAsset` 去掉「已 handled 且目标文件不存在就跳过」的分支：登记过但文件被删除的内置资源会重新补装；用户改过的文件仍按字节数判断并保留。首次启动时自动选择第一套内置布局，之后保留用户显式选择的「默认布局」。

## 工具栏分体按钮

- `ButtonAction.kt` 新增 `SplitKeyboardAction`：`id = "split_keyboard"`、`iconSlot = "toolbar.split_keyboard"`、`defaultIcon = R.drawable.ic_split_keyboard_24`、`defaultLabelRes = R.string.keyboard_modes_category_split`，执行时切换 `AppPrefs.keyboard.splitKeyboardEnabled`。
- `IconTheme.TOOLBAR_SLOTS` 增加槽位，`KawaiiBarComponent.updateButtonsState` 把它纳入激活态刷新，`IconThemeEditorActivity` 补默认图标映射；新增矢量图 `drawable/ic_split_keyboard_24.xml`。
- 默认 `ButtonsLayoutConfig.default()` 不预置该按钮；需要时由用户在按钮布局里自行添加到工具栏或状态区。

## 评审修订

外部评审逐轮提出的问题均已核对，成立的项已修复；按主题合并如下：

| 主题 | 处理 |
| --- | --- |
| 手势与副标签 | 「显示按键副标签」只影响绘制，滑动按副标签实际存在与否触发；图标层跟随 `Bottom`／`DirectionalDownBottom` 两种底部布局让位。 |
| 编辑器与保存 | 父键与合成态覆盖键各自的 `iconSlot` 都原样保留；改宽度、换动作、嵌套编辑不再丢槽位；颜色输入、布局归一化与数据层统一用 `ColorLiteral`。 |
| 迁移与启动 | 先退役原版旧文件、再执行改名迁移，用户改过的选择不被覆盖；扫描前保留已保存的图标主题名；显式选择的「默认布局」不被首次安装覆盖；`fontset.json` 读取失败时不写坏字体配置。 |
| 刷新与缓存 | 复用行、隐藏键盘与文本编辑栏一起刷新；Shift 的 once/lock 槽位在图标主题变化后保持；动作回车图标重放；合成态按活动定义取槽位并比较后重建；SVG 预取与绘制缓存按主题代次失效，缓存读写同锁。 |
| 扫描与发布 | `beginScan()` 在 `selectionLock` 内一次取保存名、选择版本与注册表版本；保存、删除、发布都推进 `registryVersion`，过期扫描不发布；冲突后基于新快照重扫；扫描只读，缩略图回填延后到发布边界，并先比对 SHA-256。 |
| 槽位与资源 | `IconTheme.KEY_SLOTS` 补 `keys.comma`、`keys.period` 与 `text_editor.*`；内置布局引用的槽位由 `IconThemeSlotCatalogTest` 与 `ALL_SLOTS` 锁定；被删除的内置资源会重新补装。 |
| 颜色与形状 | 成套主题可一并关闭操作键的椭圆外形；布局颜色支持 `theme:` 引用并覆盖功能键、动作键文字色。 |

## 验证

- JVM：完整单元测试 2517 项，失败／错误 0，跳过 30。定向覆盖包括 `LetterIconKeyTest` 22、`TextKeyboardLabelsTest` 6、`AltLabelLayoutTimingTest` 20、`BundledPresetsUpgradeTest` 16、`KeyEditorFormTest` 15、`IconThemeManagerCacheSelectionTest` 15、`LayoutJsonUtilsTest` 53、`TextKeyboardCapsIconRefreshTest` 1、`ThemeKitFontFailureTest` 1、`ColorLiteralTest` 5、`IconThemeSlotCatalogTest` 2、`FontsetConfigFailureTest` 2、`LegacyThemeFilenameTest` 与 `LayoutDataManagerColorTest`，均无失败。第二轮评审期间 `DirectBootLayoutSyncTest` 曾在 Windows 上因 `layout.json` 被写入方占用失败一次，单独重跑与后续复跑均通过，属文件占用竞态。
- 设备（水彩）：字母键 SVG 与色点、逗号与句号的行内图标、空格文字 `#5c6b89`、`?123` 数字飘带、数字层 4 行且末列与主键盘退格／回车等宽、文本编辑栏六个图标、工具栏九键（含「尺寸」浮动键与「分体」键）、Shift 单次态无底线而锁定态带横线、状态区／剪贴板标题栏按钮、回车图标在连续切换主题后仍保持、深色主题下多色图标笔画跟随文字色、成套应用与仅应用配色两条路径、副字体随套装写入 `fontset.json` 并在键面渲染 全/剪/复/贴/撤/重/目 图标。
- 设备（普蓝）：字母键盘四行照 foxy 排列（无独立数字行、数字与符号作副标签、第二行两端内缩无文字）、字母固定大写且不随 Shift 关闭变小写、空格标签用主题 `KEY_TEXT`、数字层 5 列 4 行纯文字键与 `altKeyBackgroundColor` 功能键、`ABC` 显示文字而非 `?123` 图标、工具栏九枚线稿（含「尺寸」浮动键，分体键默认不预置）、文本编辑栏 `ABC` 文字键与全选／剪切／复制／粘贴／退格五枚图标、回车图标连续切换主题后保持、`?123` 狐狸尾巴图标。
- 设备（两套主题 × 三种形态）：水彩与普蓝的主键盘、数字层、文本编辑栏各拍一张，共六张，均为 `1080×848`，取自同一次 Debug 构建，用作 PR 与文档预览（`watercolor-*-20261119-final.png`、`pulan-*-20261119-final.png`）；另拼三列两行总览 `key-svg-icons-grid-20261119.png`（3264×1708）。
- 设备（改名迁移竞态）：把旧「蓝狐狸」图标（56240 字节）与 `active_icon_theme=蓝狐狸` 写回设备，并删除 `bundled_presets.xml` 让内置资源重新安装；启动日志出现 `Retired bundled asset icon_themes/蓝狐狸.json` 与 `Failed to load icon theme: 蓝狐狸.json`，最终 `active_icon_theme` 为「普蓝」，旧文件已删除。
- 设备（图标主题与副标签）：水彩 ⇄ 谷歌来回切换，中文与 ASCII 两侧都在同一次切换内换掉、无残留；「副标签存在时移动主字符」开／关时字母图标 `img.top` 由 54 px 变 34 px（键高 171、图标 64，居中值 53.5），关闭后复位且不压住副标签。
- 设备（默认布局保留）：把 `text_keyboard_layout_profile` 设为 `default` 后重装并重启，值仍为 `default`，内置布局文件同时存在。
- 设备（最终冒烟）：装上 `6-gbae629c18` 并重设输入法，水彩键盘、字母 SVG、工具栏九键与 Shift 单次态均正常，`logcat` 没有图标主题相关错误或致命异常。扫描期间的交错无法在设备上稳定构造，只由确定性单测覆盖。
- CI（`bae629c18`）：`android_smoke`、`prototype`、Full frontend touch and engine tests、Full frontend ARM64 APK、Debug APK build、JVM unit tests、build_commit (arm64-v8a) 与 unit_test 全部 success（run 44553133470、44553133524、44554352138）；`nightly_release` skipped。
- 最终 Debug APK（x86_64，`6-gbae629c18`，26009369 字节）摘要 `68d20c3dcfd0d992d1cad36d1e6ac2e52ceeb7e5282a981ae9dffa706f6c89bd`，已安装到 `emulator-5574`；内置资源：水彩图标 188389、布局 30784、配色 838，普蓝图标 83553、布局 9902、配色 701。
- 未验证：未在真机与非 x86_64 ABI 上复测；未做性能采样。搜索／发送动作回车在键盘已打开时切换图标主题的路径只做了代码审查与构建验证，未构造对应 `EditorInfo` 实测。Shift 态切图标主题、损坏 `fontset.json` 时应用套装、合成态覆盖键槽位、编辑器颜色往返与扫描期间选择／保存／删除只有确定性单测或代码路径核对，设备上难以在不离开键盘、不破坏本机数据的前提下构造。

## 已知限制

- 为让「磁盘写入 + 注册表发布 + 活动主题替换」对扫描不可分割，`saveTheme()` 在 `selectionLock` 内完成原子写；极慢存储上会短暂阻塞选择与删除。扫描只在锁内取快照与发布，磁盘解析仍在锁外。
- 两套图标已在生成阶段按墨迹面积归一（`target_area=2600`、`max_edge=62`），同一套内视觉大小基本一致；但归一按包围盒面积估算，极宽、极窄或带大块透明留白的画稿仍会有肉眼差异，要完全一致只能逐枚调整画稿。
- 深色下多色图标的近黑笔画换色只覆盖 `fill`／`stroke` 属性里的纯色值，`currentColor`、渐变与 `style` 内联颜色不参与。
