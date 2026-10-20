# 设置界面 Material 3 / M3 Expressive 改造规划

> 原始规划 · 2026-10-18。2026-10-20 已完成 MD3 主线实现，已提交代码 [PR #5](https://github.com/SandyYuR/fcitx5-android/pull/5)，合并状态以该 PR 为准。实际交付范围、用户反馈、相对规划的调整与验证边界见[实施记录与合并说明](/maintainer/SETTINGS_MD3_IMPLEMENTATION_2026-10-20)。
>
> 下文保留原始审阅时的源码快照和设计建议；「本轮交付规划」「新增」「建议」等表述指 2026-10-18 的规划语境，不等同于当前默认分支已经发布全部功能。
>
> 审计对象：`fx-rime-only`，本次读取 HEAD 为 `92fd34ce060cb4625d99066443d294c453c4c693`。该 SHA 仅标识此次源码快照，不作为未来实施基线。

推荐在现有 **View + Fragment + Preference** 架构上完成设置界面的 M3 Expressive 改造：保留六个首页入口、现有功能页面和数据契约；统一组件、色彩、排版、动效与返回体验。在「外观」内新增「设置界面外观」，提供亮色、暗色、跟随系统，以及系统 Monet 和 **MCU（Material Color Utilities）** 官方算法生成的 CMF 2026 配色。

不需要为了 MD3 先重写整个设置导航或输入法。Compose 可以作为未来局部演进选项，但不是此次实现 MD3、Expressive 列表或预测性返回的前提。

建议先审阅下文「入口映射」「主题隔离」「颜色方案」「决策表」，再决定是否启动实施。

## 1. 范围与不变量

本轮交付规划报告。应用源码、依赖版本、Manifest、偏好、键盘资源与引擎均未因本报告修改。报告存放在 `docs` worktree；文档站目录登记属于报告交付。

实施时以三条不变量验收：

1. **入口身份保持**：旧入口名称、route/Intent 目标、参数和操作语义保持；不合并或删除功能，不增加指向同一设置的常规重复入口。现有搜索捷径、外部 Intent 直达仍然保留。
2. **数据兼容**：现有 SharedPreferences key、类型、枚举取值与默认值保持；旧布局、主题、备份可继续读取。新增设置界面偏好采用独立 key，不占用键盘主题 key。
3. **行为等价**：原有确认、校验、权限申请、保存/取消、长按动作、导入导出和返回语义保持。动效不能触发额外部署、同步、保存或删除。

可改变：排序、页面内分区、间距、容器、形状、字体层级、颜色和转场。新增主题与调色板入口属于本次新能力。

本报告的“完整动效”指**交互状态覆盖完整**：进入、退出、取消、打断、重试、恢复、减少动态效果均有定义；不指每一行都增加动画，也不指所有 Android 版本都能提供同一系统手势能力。

## 2. 现状审计与技术判断

### 2.1 已核实的项目情况

| 项目 | 当前源码事实 | 对方案的影响 |
| --- | --- | --- |
| Android | minSdk 23，compileSdk / targetSdk 36 | 保留 Android 6 起的兼容；预测性返回按系统能力分层 |
| UI | AppCompatActivity、Fragment、PreferenceFragmentCompat、RecyclerView、XML 和 Splitties DSL | 主线采用 View 改造，复用偏好与导航 |
| 直接声明依赖 | Material Components 1.14.0；Activity 1.13.0；Navigation 2.9.8；Preference 1.2.1；AppCompat 1.7.1 | 不能把“升级到 Material 3”误写为必须新增 Compose |
| Compose | 当前版本表与 app 构建未配置 Compose UI / Material3 | Compose 是新增技术栈，非已有组件的简单升级 |
| 设置主题 | 日间 `Theme.MaterialComponents.Light.NoActionBar.Bridge`，夜间 `Theme.MaterialComponents.DayNight.NoActionBar.Bridge` | 实际仍是旧 Bridge 主题；依赖版本新不等于页面已经 MD3 |
| 首页 | 常驻搜索；「日常调整」4 入口、「按需进入」2 入口 | 不需要改造成泛用“账户/通知/安全”目录 |
| 导航 | `SettingsRoute` + NavHostFragment；`navigateWithAnim` 使用 `R.animator.nav_default_*` | 已有 Animator 基础，不能断言现有预测性返回完全不支持 |
| 返回 | 首页搜索有条件启用 OnBackPressedCallback；工具栏根返回可 `moveTaskToBack(false)` | 搜索清空、页面出栈和根页退出必须分别设计 |
| 外观 | ThemeFragment 有键盘预览、主题/配置两 tab；独立主题、图标、字体等编辑器 | 现有键盘主题功能和预览需保留 |
| Manifest | 主 Manifest 未显式声明 `enableOnBackInvokedCallback` | 实施核对 merged manifest 与实际设备行为，不能仅凭缺少属性判定关闭 |
| 搜索 | 静态声明 132 条搜索记录；集中 key 映射 87 项，另有条目直接指定定位 key | 这是搜索目录规模，不是全部设置数；引擎动态配置、菜单和编辑器内部操作另计 |

Fragment、Transition 的**最终解析版本未在本轮运行 Gradle 解析**。本地缓存存在多个版本，缓存存在不证明当前选中；实施阶段应使用 dependencyInsight 核对后锁定必要版本。

本轮没有操作真机、截图或录像；没有测得帧率、主题切换耗时或 OEM 手势表现。下文是源码审计与设计建议，不把线框或预期体验当作实测效果。

初始主仓库已有 Fcitx 子模块修改：`inputmethodengine.cpp/.h`、`instance.cpp/.h`；本轮未处理它们。`git submodule status --recursive` 遇到本机 MSYS 权限错误，因此递归状态未完整取得；本轮报告工作不依赖原生构建。

### 2.2 最新官方能力核对

| 能力 | 本次查证结论 | 规划决定 |
| --- | --- | --- |
| View 版 Expressive | Material Components 1.14.0 已有 Expressive 主题、列表和相关组件；本地 AAR 也有 ListItemLayout / ListItemCardView、MaterialButtonGroup 和 spring token | 使用项目现有版本先做能力验证，无需先升级依赖 |
| Compose 版 | 官方发布页当前列出稳定版 1.4.0、预发布版 1.5.0-beta01 | 不把 beta 当稳定版；仅作为备选路线参考 |
| MCU 2026 | 官方 Java 源码存在 `SPEC_2026`、`ColorSpec2026`、`SchemeCmf` | CMF 2026 有真实官方算法依据 |
| 已有 MDC 内置颜色工具 | 本地 1.14.0 AAR 未发现 SchemeCmf / ColorSpec；其 DynamicScheme 为库内部限制 API | 不从内部工具类硬接 2026；不假设已有依赖自动包含它 |
| Expressive motion | 官方区分 spatial / effects spring，View 有 token，但并非所有 View 组件自动采用新 motion | 逐组件审计和补齐，不能只换父主题就宣称完整支持 |

来源：[MDC 1.14.0 发布说明](https://github.com/material-components/material-components-android/releases/tag/1.14.0)、[View 主题接入](https://github.com/material-components/material-components-android/blob/1.14.0/docs/getting-started.md)、[Compose Material3 发布页](https://developer.android.com/jetpack/androidx/releases/compose-material3)、[MCU ColorSpec](https://github.com/material-foundation/material-color-utilities/blob/main/java/dynamiccolor/ColorSpec.java)、[MDC 内部 DynamicScheme](https://github.com/material-components/material-components-android/blob/1.14.0/lib/java/com/google/android/material/color/utilities/DynamicScheme.java)。在线 main 链接会变化，实施前必须固定具体算法版本/提交。

## 3. 入口映射：先保证功能不丢

### 3.1 首页排序

推荐保留原有「日常调整 / 按需进入」两个分类，日常调整顺序改为 **输入与候选 → 外观 → 键盘 → 便捷功能**。外观由第 3 项升到第 2 项，曝光新增能力；输入仍优先。缺乏真实使用统计，排序属于设计判断，不以“用户使用频率数据”作依据。

| 原入口 | 当前位置 | 建议位置 | 既有目标保持 | 持久化 / 风险 |
| --- | --- | --- | --- | --- |
| 搜索 | 首页第 0 项 | 标题下常驻搜索区域 | 现有搜索、跳转与定位 | 查询/焦点/返回恢复是主要风险 |
| 输入与候选 | 日常调整 1 | 日常调整 1 | SettingsGroup("input") | 导航自身无偏好 key |
| 键盘 | 日常调整 2 | 日常调整 3 | VirtualKeyboard | 导航自身无偏好 key |
| 外观 | 日常调整 3 | 日常调整 2 | SettingsGroup("appearance") | 既有入口不改名 |
| 便捷功能 | 日常调整 4 | 日常调整 4 | SettingsGroup("convenience") | 导航自身无偏好 key |
| 数据与备份 | 按需进入 1 | 按需进入 1 | DataBackup | 导入有覆盖后果，保留确认流程 |
| 高级 | 按需进入 2 | 按需进入 2 | Advanced | 兼容性开关不重设 |
| 常见问题 / 开发者 / 关于 | 首页溢出菜单 | 原溢出菜单 | 原 URL / Developer / About | 保留原名称与发现方式 |

不新增首页账户、通知、隐私、安全分组：当前输入法的任务结构不适合直接套用通用 App 模板。也不新增无依据的实时状态卡或启动时的同步网络查询。

### 3.2 子页与动作映射

下表的“保持”意味着整页内既有条目、条件可见项、菜单和动作保留，不只是保留一张空的页面外壳。纯导航/动作没有存储 key 时标记为“无”，不人为给它制造偏好。

| 原入口或功能族 | 当前路径 | 目标位置 / 容器 | key / 路由契约 | 风险与保留要求 |
| --- | --- | --- | --- | --- |
| 中州韵设置 | 输入与候选 | 原位置，首项 | InputMethodConfig(name, "rime")；动态配置路径保持 | 方案、部署、同步、目录等实际内容按引擎配置生成；不得手工重做成不完整静态列表 |
| 全局选项 | 输入与候选 | 原位置 | GlobalConfig；配置树路径保持 | 条件可见与快捷键行为保持 |
| 候选栏样式 | 输入与候选 | 原位置 | KeyboardGroup(5) | `horizontal_candidate_style` 等原 key 保持 |
| 候选窗口、快速预设 | 输入与候选 | 原位置；参数按视觉分区 | CandidatesWindow | 快速预设仍为动作；原范围、默认值保持 |
| 键盘尺寸 | 键盘 | 原首项 | KeyboardGroup(0) | `keyboard_height_percent` 等保持 |
| 分体、浮动与单手键盘 | 键盘 | 原第 2 项 | KeyboardModes | 互斥、自动切换、校准与方向偏好保持 |
| 按键行为 / 按键反馈 | 键盘 | 原位置，各自分段列表 | KeyboardGroup(1 / 2) | 空格互斥、长按延迟、声音与振动行为保持 |
| 工具栏、编辑按钮 | 键盘 | 原位置；编辑按钮仍在工具栏页 | KeyboardGroup(3) | `toolbar_height_percent`；键盘 ToolbarMetrics 不受设置页尺寸影响 |
| 键盘布局自定义 | 键盘 | 原位置 | KeyboardGroup(4) | `editors_category` 定位保持 |
| 修改键盘定义 / 键盘布局管理 / 切换键盘定义配置 / 数字键盘布局 | 键盘布局自定义 | 原位置，维持编辑与选择的不同任务 | 原 Activity / 选择器；`tool_text_layout_manager` 定位 | JSON 保留键、空项、占位键语义保持 |
| 弹出字符设定 / 在线编辑器 | 键盘布局自定义 | 原位置 | PopupEditorActivity / 原在线编辑流程 | 启停、导入、权限与安全边界保持 |
| 主题 | 外观 | 外观中「键盘外观」视觉分区，旧标题仍为「主题」 | Theme；原主题/配置两 tab | 真实键盘预览继续受 ThemeManager 驱动 |
| 图标主题 / 字体设定 | 外观 | 与主题同行分区，顺序保持 | IconThemeListActivity / FontsetEditorActivity | 导入、编辑、预览、分享和删除确认保持 |
| 跟随系统夜间模式、亮色模式主题、暗色模式主题 | 主题 → 配置 | 完整保持 | `follow_system_dark_mode` / `light_mode_theme` / `dark_mode_theme` | 不升级为设置界面的三态，不改变类型 |
| 剪贴板 / 远端剪贴板同步设置 | 便捷功能 → 剪贴板 | 原路径；同步页沿用 Activity | Clipboard → ClipboardSyncSettingsActivity | 同步账户、连接、权限、数据不可被换肤触发 |
| 文本编辑 | 便捷功能 | 原位置 | TextEditing；`text_editing_style` 等 | text_editor 布局与动作白名单保持 |
| 表情和符号 / 三类自定义数据 | 便捷功能 | 原位置，分区样式统一 | Symbol；catalog 与 recentKey 保持 | 导入和删除交互不增加手势快捷删除 |
| 语音输入 | 便捷功能 | 原位置 | KeyboardGroup(6) | `show_voice_input_button` / `preferred_voice_input`；授权时机保持 |
| 浏览用户数据目录 | 数据与备份 | 原位置 | 无；原 DocumentsProvider Intent | Android 10+ 长按附加路径仍保留 |
| 导出用户数据 | 数据与备份 | 原位置 | 无；原导出流程 | 完整备份的原生停写契约保持 |
| 导入用户数据 | 数据与备份 | 页内末项，以警示摘要区别 | 无；原导入流程 | 原目标选择、校验、覆盖确认与恢复流程保持 |
| 兼容性开关、附加组件 | 高级 | 原页内顺序原则保持，视觉分区 | Advanced / AddonList；原 keys | 这里是现存引擎配置，不借改造恢复已删除插件 |
| 实时日志 / 详细记录日志 / 重启实例 | 菜单 → 开发者 | 原位置；重启在页面内清晰区分 | Developer；`verbose_log`，其余为动作 | 不把重启、日志等挂到常驻状态卡 |
| 关于内更新、隐私政策、开源许可、代码、构建信息 | 菜单 → 关于 | 原位置，以信息列表呈现 | 原 Activity / URL / Intent | 不改升级检查行为与网络时机 |
| 设置界面外观（新增） | 无 | 外观页首分区 | 新的设置界面 route 和独立 keys | 仅控制设置页面；不修改旧主题入口 |

「主题」名称保持，通过分区标题「设置界面 / 键盘外观」解释作用范围。禁止把旧「主题」入口直接替换成新的 App 外观页。

### 3.3 搜索与兼容直达

继续使用 `SettingsGroupSpecs`、实际承载页和 `SettingsSearchIndex` 三方一致的维护方式。新增项同步偏好定义、规格/承载页、搜索目录及英文/简体/繁体资源。

现有搜索面包屑有一些历史分类，例如语音记录使用 `P_KEYBOARD`，实际从便捷功能进入；候选窗口和部分便捷功能仍使用泛化 `P_ANDROID`。建议在实施本次目录核对时修正**面包屑**，保持实际 route 和 key；不把现有目录照搬成新的用户层级。

搜索回归需覆盖：别名匹配、输入焦点、主题配置 tab 自动打开、定位 key 消费、隐藏条目处理、Activity 目标不污染下一次定位，以及返回结果列表后保留查询。不要因改为 MD3 SearchBar 而改变搜索的上述语义。

层级验收采用“旧路径深度不增加；新增设置界面外观从首页两次进入可达”。不承诺所有旧功能一律两级可达——当前编辑器及引擎配置本身有更深层级。

## 4. 视觉方案与组件映射

### 4.1 页面形态

首页使用大标题、常驻搜索、两组分段列表。通过 surface 层次与首尾圆角形成分组，不把六个入口做成六张巨型卡片；列表条目保留完整标题、摘要与方向指示。标题收起后成为紧凑顶栏，列表滚动和状态恢复连续。

以下为布局线框，**不是当前截图或已实现界面**：

```text
首页                            外观
← 靓企鹅·中州韵          ⋮       ← 外观
   搜索设置                     设置界面
                                [ 设置界面外观                 › ]
日常调整                        [ 跟随系统 · CMF 2026 / 海盐     ]
[ 输入与候选               › ]
[ 外观                     › ]  键盘外观
[ 键盘                     › ]  [ 主题                         › ]
[ 便捷功能                 › ]  [ 图标主题                     › ]
                                [ 字体设定                     › ]
按需进入
[ 数据与备份               › ]  设置界面外观页
[ 高级                     › ]  [亮色] [暗色] [跟随系统]
                                配色预览：标题 / 正文 / 开关 / 按钮
                                色彩来源：系统动态 / 预设配色
                                配色：海盐 / 松林 / 莓果 / ...
                                风格：CMF 2026 / 柔和 / 鲜明 / ...
                                更多外观选项              ›
```

建议视觉起点：内容外边距约 16–24dp，分组间距约 24dp；单行和双行列表使用相应组件密度并允许内容增高；大标题约 28–32sp、正文约 16sp、摘要约 14sp。具体以 Material 类型与形状 token 为准，这些是项目初稿，不是声称所有 MD3 页面必须采用的固定数值。

颜色分工：`surface` 做页面底色，`surfaceContainer*` 做分组与弹层；正文使用 `onSurface`，摘要使用 `onSurfaceVariant`；选中态使用 container/onContainer 配对；危险动作使用 error 语义。保留日志级别色的可读性，单独校验。设置页主色不改写键盘预览的实际配色。

### 4.2 View 主线与 Compose 备选

| 当前组件 | 推荐 View 实现 | 若未来局部 Compose | 保留点 |
| --- | --- | --- | --- |
| AppCompat Toolbar | MaterialToolbar + AppBarLayout 的 Expressive 样式 | 相应 TopAppBar | 根返回与子页标题一致 |
| 普通 Preference 行 | 定制 Preference layout，适配 ListItemLayout / ListItemCardView 分段状态 | ListItem + 分组容器 | 不重写 ManagedPreference 存储/校验逻辑 |
| Switch / Checkbox | MaterialSwitch / MaterialCheckBox，对接原 Preference 变更回调 | Switch / Checkbox | 每次点击只写一次；整行与控件不双重触发 |
| 单选设置 | Material 单选对话框或 BottomSheetDialogFragment | AlertDialog / ModalBottomSheet | entryValues、取消语义保持 |
| 三态外观 | 单选按钮组，宽度不足改纵向单选列表 | SingleChoiceSegmentedButtonRow 或单选列表 | 200% 字体下仍可辨读全部文字 |
| SliderPreference | Material Slider 与原范围/步长/格式适配 | Slider | 不增加写入频率；昂贵动作沿用原提交时机 |
| 搜索 | 保留现有常驻输入模型，调整 MD3 容器；独立验证后再考虑官方 SearchView | SearchBar 等 | 禁止绑定时抢焦点、重复 TextWatcher |
| 自定义对话框 | 统一 MaterialAlertDialogBuilder / 主题覆盖 | AlertDialog | 警示、错误和恢复路径全保留 |
| 主题/布局/字体编辑器 | 原 Activity 保留；外壳、表单、菜单统一 | 未来仅替换页面内容 | Intent、草稿、保存、返回结果保持 |

官方的 [Expressive 列表](https://github.com/material-components/material-components-android/blob/1.14.0/docs/components/List.md)支持按分组计算首/中/末项形状。接入 Preference 时须自行对接可见项分组和刷新；搜索结果/条件项变化后重新计算。普通设置行关闭滑动操作，避免列表横滑与返回手势竞争。

**推荐路线 A：View 完整改造。** 复用现有导航、Preference 和编辑器，统一设置 Activity 主题入口与组件适配；额外工作集中在 motion 和颜色切换状态恢复。

**备选路线 B：Fragment 中逐页嵌入 ComposeView。** 适用于确认长期迁移 Compose 后；保留旧 route 和 Activity，复用同一偏好源，设置 ViewCompositionStrategy，不能再建立另一套竞争的返回栈。首次引入需单独批准固定版本依赖及 Kotlin/Compose 编译配置。不能把换框架本身当作体验验收。

## 5. 主题模式与颜色引擎

### 5.1 独立于键盘主题

新增的主题控制域只覆盖主设置页及从它打开的应用自有设置/编辑器外壳。键盘、候选栏、浮动键盘、IME 窗口、键盘预览内容沿用现有 ThemeManager。系统文件选择器、外部浏览器和系统权限页由系统决定外观。

采用按 Activity 限定的主题应用策略及 `localNightMode`，避免全局 `AppCompatDelegate.setDefaultNightMode` 或 Application 级全量覆盖把 IME/透明浮层一起改变。主题编辑页的“界面壳”和“真实键盘预览”分别持有正确的主题上下文。

建议新增状态模型（逻辑模型，不是本轮代码）：

| 字段 | 例值 | 约束 |
| --- | --- | --- |
| mode | system / light / dark | 三态仅属于设置界面 |
| colorSource | legacy / system / preset | system 代表使用系统角色色，不伪装为自选规格 |
| presetId | legacy / sea_salt / forest / berry 等 | 稳定字符串；名称可本地化 |
| recipeId | cmf2026 / tonal2025 / vibrant2025 / neutral2025 / expressive2025 / classic2021 | 绑定合法 variant + spec，避免任意笛卡尔组合 |
| contrast | standard / medium / high | 首版预设可生成三档；系统模式按真实能力提供 |
| motion | system / reduced | 可放“更多外观选项”，是否增加独立开关待审 |
| schemaVersion | 1 | 显式数据结构版本，不持久化 enum ordinal |

新 key 建议统一 `settings_ui_*` 前缀；具体名单实施前确认。未知新值回落到兼容外观，不能崩溃，也不覆盖原始存储值。原 `disable_animation` 目前主要被 IME UI 消费，其语义不在此次顺手扩展；设置页优先响应系统减少动态效果，新开关单独存储。

升级策略：旧用户首次使用新界面默认 `mode=system`，颜色走**兼容配色**，尽量保持原主色与亮暗观感；不能宣称旧 Bridge 主题等于 SPEC_2021。新的组件形状/排版仍会变化，所保留的是默认色彩意图与行为。新安装建议 system + 可用时 Monet，否则固定品牌配色。只有用户主动选择 CMF 2026 时才切换到该配方。

「恢复默认外观」只重置 `settings_ui_*`，恢复到固定定义的兼容外观 + 跟随系统，不清除键盘主题或用户数据。首次安装推荐值与恢复默认值的区别应在文案中说明，也可在审阅时选择统一采用兼容默认以减少分支。

### 5.2 Monet、调色板样式、颜色规格是三件事

**系统 Monet**：Android 12+ 使用系统动态色角色并尊重系统/OEM 的配色选择；低版本或动态色不可用时回落至固定品牌方案，摘要解释实际生效方案。无需读取壁纸文件或新增壁纸权限。使用官方 DynamicColors 作为独立系统路径是合理的，并非应全面禁止。[Android 动态色说明](https://developer.android.com/develop/ui/views/theming/dynamic-colors)

**应用调色板**：固定的一个或两个种子色，结合 variant、明暗和对比度生成完整角色表；可在所有支持系统上使用。颜色来源和视觉风格分别建模，但用户只看到当前可用选项。

**颜色规格**：算法版本。CMF 是一种 variant，2026 是 spec；两者不能作为两个互不约束的自由选择器。官方 `SchemeCmf` 默认使用 2026，传入其他 spec 会拒绝；允许单种子，亦支持第二种子影响 tertiary。因此推荐预设中提供有设计意图的双色组合。[官方 SchemeCmf](https://github.com/material-foundation/material-color-utilities/blob/main/java/scheme/SchemeCmf.java)

| 用户选择 | 实际执行 | UI 应显示的规格 |
| --- | --- | --- |
| 系统动态色 / Monet | 系统提供的角色色 | 系统管理；不标为强制 2026 |
| CMF 2026 | CMF + SPEC_2026 | 2026 |
| 柔和 / 鲜明 / 中性 / 表现力 | TONAL_SPOT / VIBRANT / NEUTRAL / EXPRESSIVE + SPEC_2025 | 2025 |
| 经典方案（可选） | 对应支持的 variant + SPEC_2021 | 2021 |
| 兼容配色 | 原应用颜色适配为完整 MD3 角色 | 兼容；不虚构 MCU 规格 |

官方 `DynamicScheme` 对常见四类 variant 的 2026 请求会回退到 2025，另一些 variant 回到 2021。若未来提供高级规格选择，必须按能力筛选并校验**最终生效** spec，不能仅存下用户请求后显示“2026”。[官方规格回退逻辑](https://github.com/material-foundation/material-color-utilities/blob/main/java/dynamiccolor/DynamicScheme.java)

### 5.3 推荐落地：Monet 系统路径 + 官方算法预生成配色

首版建议两条颜色路径：

```text
系统路径：系统动态颜色 → 设置 Activity 的 Material 主题覆盖

预设路径：固定种子色/双色种子
          → 固定版本 MCU（variant + spec + contrast + PHONE）
          → 完整亮/暗角色表
          → 生成并核验 XML theme overlay
          → 设置 Activity / 对话框 / 控件
```

先规划 6 组具名预设，例如海盐、松林、莓果、琥珀、紫藤、石墨；名称与种子为设计候选，最终需看官方算法输出后定稿。每组提供 CMF 2026 及选定的其他合法风格，色卡显示主色、容器色、次色、第三色及选中标记。预览包含文字、开关、列表与按钮，不能仅用彩色圆点评价可读性。

**为何首版推荐预生成：** 用户当前要求多种样式、Monet 与 CMF 2026，可由这两条路径完整覆盖；固定配色在 API 23+ 可直接用资源 overlay，无需先解决任意运行时颜色注入所有旧 View 的问题。算法仍是官方 2026，只是提前计算，不能把预生成误叫作“近似 2026”。

生成过程应固定官方 MCU 的准确提交/发行版本、保存许可证与生成参数、保留测试向量；导出所有应用需要的 surface、on-color、outline、inverse、fixed、error 等角色及控件状态色。不得只导出 primary/secondary，也不得手工猜 tone 表后标作官方 2026。此生成工具的引入属于后续待批准的外部代码/依赖范围，本轮未下载或执行。

若以后需要“用系统色重新生成 CMF 2026”或任意用户选色，再评估独立 MCU 模块 + View 颜色适配器。公开系统色不保证可逆恢复壁纸原始种子；用系统强调色再生成时应准确命名为“基于系统强调色”，不宣称与系统 Monet 完全相同。也不能依赖 MDC 的受限内部颜色工具 API。

抽象层建议为 `SettingsColorProvider`，能力包含 `SystemColorProvider`、`GeneratedPresetProvider` 和未来 `RuntimeMcuProvider`。上层消费统一角色色与有效配方信息，不依赖 Compose 或 View 的具体实现。若采用 Compose，才额外映射为 ColorScheme；当前主线直接映射 Material theme attrs。

### 5.4 即时预览与保存

外观页顶部展示设置界面预览；主题模式三态直接可见；颜色方案选择打开底部弹层，大屏为合适宽度的对话框。窄屏或大字体用纵向单选项，色卡同时显示文字与选中状态。

推荐“选择即预览，应用后保存”：在弹层中更换临时配色只改变预览，不反复 recreate Activity；应用后原子写入一组偏好并刷新设置外壳；取消、下滑关闭或返回恢复原值。这样可以清楚区分预览和持久化，并减少频繁重建。主题模式单选可直接应用，但需保留当前 route、查询和列表位置。

View 切换亮暗可能重建 Activity，不能承诺仅靠 `setTheme()` 改变已创建控件。需在创建首帧前选择 windowBackground 与主题，恢复导航/列表/搜索状态，对切换交互做一次统一过渡。编辑器草稿、正在导入/导出的任务不能因换肤丢失；必要时在不可中断事务期间延后换肤。

## 6. 完整动效与预测性返回

### 6.1 动效语言

采用 M3 Expressive 的 spring token，把移动/尺寸/形状与颜色/透明度分开。spatial 可有克制的弹性，effects 不应过冲。下表时间是普通过渡的初始调优范围，不是官方统一常数或已经测得的耗时；spring 本身由参数与初速度决定，不硬写成固定 300ms。[官方 motion 说明](https://m3.material.io/styles/motion/overview/how-it-works)

| 场景 | 建议效果 | 完成 / 取消 / 降级 |
| --- | --- | --- |
| 首页标题随滚动收起 | 顶栏连续过渡，surface 层次变化 | 返回恢复滚动位置；不重播整页入场 |
| 进入子页 | 支持 seek 的横向层级过渡，配合淡入；普通点击约 250–350ms 起调 | 返回同一组可逆效果；不叠加 Animator 与 Transition 两套事务 |
| 开关、单选、色卡选中 | 组件原生状态反馈；fast effects / spatial | 中途重选向最新目标收敛；不重复写值 |
| 列表条件项显示/隐藏 | 轻量尺寸与透明度变化 | 保持焦点与阅读位置；批量更新不逐项弹跳 |
| 底部弹层 | Material 原生展开、收起、遮罩与手势反馈 | 返回取消只关顶层；取消预览不写值 |
| 颜色与亮暗切换 | 完整角色集一次应用；约 200–300ms 的统一轻量过渡 | 连续选择合并；不对长列表每行独立开颜色动画 |
| 搜索结果 | 轻微内容替换，优先输入响应 | 输入框和 IME 不随结果刷新重建 |
| 加载、成功、失败 | 合适的进度指示、明确结果与可重试动作 | 业务取消能力由原流程决定，不因动画强制取消备份 |
| 编辑器、Tabs、菜单、提示 | 采用对应组件反馈；特殊编辑页单独检查 | 草稿/所选 tab/选中对象恢复 |
| 减少动态效果 | 去掉大位移、弹跳与装饰转场，保留状态可辨性 | 系统动画关闭时不得继续强制播放自制 spring |

同屏通常只允许一个主导航/主题过渡，局部状态反馈可以并行；这是性能治理原则，不用“最多两个动画”限制组件内部实现。首版不新增页面入场瀑布、全屏圆形揭示或大面积模糊。

完整覆盖要包括独立编辑器、同步设置页和应用自有对话框，不能只验收首页。View 组件原生已有的动画优先复用；缺项逐项适配，不能用全面缩放所有按钮代替 Expressive 设计。

### 6.2 版本能力边界

| 系统 | 规划承诺 |
| --- | --- |
| API 23–32 | 普通返回与一致的非跟手转场；无平台预测性返回能力承诺 |
| API 33 | 系统返回接入；系统预测性返回按设备/开发者选项验证；不承诺 Fragment 逐帧跟手 |
| API 34 | 验证跨 Fragment 跟手、取消与完成；开发者选项/设备能力单独记录 |
| API 35+ | 验证默认系统 back-to-home、跨 Activity 与应用内预测性返回；重点覆盖 targetSdk 36 的实际行为 |

官方跨 Fragment 预测动画要求 Android 14+，支持 Animator 与 AndroidX Transition，要求 Fragment 1.7.0+、Transition 1.5.0+；旧 Animation 和平台 framework Transition 不属于这条兼容路径。当前 `navigateWithAnim` 使用 Animator，先验证既有能力，再决定替换，而不是先认定全部失效。[Fragment 动画兼容说明](https://developer.android.com/guide/fragments/animate)

Manifest 是否显式声明启用，需要结合 merged manifest、targetSdk 与实际系统确认；不能把添加一个属性当作完成预测性返回。依赖最终解析不满足要求时，再提交固定版本的变更建议。

### 6.3 返回的优先级与取消语义

| 当前状态 | 返回目标 | 手势进行中 | 完成后 / 取消后 |
| --- | --- | --- | --- |
| 系统 IME 接管返回 | 先处理系统输入法 | 服从平台输入法返回策略 | 不额外清查询或跳页 |
| 顶层对话框/配色弹层 | 关闭顶层 | 预览底下当前页 | 完成关闭并撤销未应用预览；取消保持弹层 |
| 首页有查询 | 退出搜索 | 可做结果层恢复常规列表的预览 | 完成才清查询；取消恢复原查询、焦点、位置 |
| 编辑器有未保存草稿 | 原有离开确认 | 不先出栈或保存 | 保留原确认策略，取消无损 |
| 普通子页 | 前一个真实目的页 | 交给 Fragment / Navigation 的可 seek 效果 | 完成仅 pop 一次；取消复位 |
| 首页且无查询/弹层 | 系统决定返回桌面或上一个任务 | 交还系统动画 | 不常驻注册吞返回的回调 |

表中具体顺序还需结合 IME、Dialog 各自 Window 验证，不能在 Activity 中硬编码一个与平台争夺优先级的万能拦截器。

推荐 `OnBackPressedDispatcher` + 生命周期约束；自定义搜索状态才按需处理 started/progressed/cancelled。**根页面不自己缩小到 90% 模拟桌面**；这种处理会抢走系统动画，也无法正确展示真实前一任务。工具栏箭头保持当前根页最小化语义，系统手势保留平台返回语义。[官方返回回调指导](https://developer.android.com/guide/navigation/custom-back/predictive-back-gesture)

若采用 MaterialSharedAxis 或组合 AndroidX Transition，须验证整组 transition 支持 seeking；普通正向动画可播放不等于反向手势可 seek。无法 seek 的实现采用已验证的 Animator 路径，不混入第二个 pop 回调。[预测性返回动画指导](https://developer.android.com/about/versions/14/features/predictive-back)

所有业务副作用只在相应确认动作完成后发生。手势中途取消不写偏好、不部署、不同步、不保存草稿。主题切换与导航过渡互斥协调：返回开始后延后新的主题应用，已应用的值不因手势取消而回滚。旋转、分屏、进程恢复时以已提交状态恢复，丢弃过期的动画进度。

## 7. 自适应、无障碍和性能验收

推荐按**当前窗口可用宽度**布局，保留用户当前目的页，不以设备名称或朝向硬编码：

| 窗口宽度起点 | 建议布局 |
| --- | --- |
| 小于 600dp | 单列，沿用页面层级 |
| 600–839dp | 加宽单列、合适最大宽度；在字号/内容确有空间时才启用双栏 |
| 840dp 及以上 | 左侧六个分类的列表或展开式导航区，右侧当前详情；同一个导航模型 |

这些是初始断点方案，可在大字体、分屏和折叠姿态下调整。右侧表单可限制约 640–720dp 的阅读宽度；主题网格/布局编辑器按任务利用更大空间，不给全部页面同一硬上限。六个长中文分组更适合带标签的侧栏，不强塞六个图标或六个底部导航按钮。官方的 [list-detail 模式](https://developer.android.com/develop/ui/compose/layouts/adaptive/list-detail)可参考其交互原则，当前 View 实现不直接照抄 Compose API。

双栏不是新增一套设置入口：保留同一 route、选择状态和历史语义；窄化后打开当前详情，返回恢复对应分类。外部 Activity 不在首期强拆进右栏；保持原 Intent 与结果契约。

无障碍验收：

- 交互目标至少 48dp；大字体下行高随内容增加。200% 字体、简繁英、横屏和窄分屏不截断关键名称/取值。
- 普通文本对比度至少 4.5:1，大文本与关键非文本图形按相应 3:1 要求检查；对 primary/onPrimary、各 container/onContainer、surface/onSurface 等实际组合逐一计算，不只看两个主色。
- 色卡有名称、选中标记与 TalkBack 状态；不靠色相区分。分组标题具有 heading 语义，装饰图标不重复朗读。
- 单个开关行只有清楚的一次语义动作；外接键盘、方向键与 TalkBack 焦点顺序和视觉顺序一致。弹层关闭恢复触发点焦点。
- 动态系统方案也需检查实际控件状态、错误提示与截图；兼容配色和每个预设的亮暗/对比度组合逐项校验。不静默把用户选的对比度偏好改写成另一个值；生成失败的配方不发布。
- 主题过渡中仍需保证关键文字可读；不能依靠长时间两层文字叠影完成换肤。

触控与对比度依据：[Android 无障碍指南](https://developer.android.com/guide/topics/ui/accessibility/apps)。

性能目标应在选定设备记录为可复现实验：首帧无错误主题闪屏；滑动/返回中无主线程 I/O；同一操作没有重复导航、重复写值；主题应用完成后不丢焦点/位置。60Hz 帧预算约 16.7ms、120Hz 约 8.3ms，使用 FrameMetrics/Perfetto 或相应性能工具记录 P95/P99 与卡顿帧。验收前再设机型阈值，不在本报告伪造“300ms 内必然完成”或“零掉帧”。

## 8. 分阶段实施与验收

所有阶段都以审阅批准为启动条件；本轮未开始 Phase 1 代码实施。

| 阶段 | 产出 / 改动范围 | 必须达到的验收 |
| --- | --- | --- |
| Phase 0：锁定基线 | 本报告、源码入口表；补真机截图与当前行为录像；核对 merged manifest / resolved dependencies | 六个入口、菜单、动态引擎配置、编辑器路径都有记录；前后比较依据明确 |
| Phase 1：入口与视觉骨架 | 首页排序、外观分区、M3E 主题与 Preference 行适配 | 旧入口名称和目标保持；搜索目录及定位覆盖通过；新旧数据差分无非预期变化 |
| Phase 2：主题与颜色 | 独立 SettingsUiPrefs；系统 Monet；固定 MCU 配方生成资源；主题三态与预览 | 所有配方完整、规格真实、亮暗可读；API 23 回退；旧键盘主题完全等价 |
| Phase 3：完整组件覆盖 | 分组、设置详情、编辑器外壳、同步页、菜单/对话框、加载/错误状态 | 每个页面有覆盖表；没有一半 Bridge 一半 M3 的遗漏；草稿/业务流程保持 |
| Phase 4：motion 与返回 | 可取消可打断动画；搜索、子页、弹层、编辑器、根页返回 | API 34+ 手势中途取消与完成各验收；API 23–33 普通返回正确；根页系统动画不被抢占 |
| Phase 5：自适应与无障碍 | 大屏详情布局、窗口缩放、TalkBack、200% 字体、低动态效果 | 双栏/单栏状态连续；标签不丢；对比度与触达通过；性能报告可复现 |

需要重点运行的现有目标测试包括 `SettingsSearchQueryTest`、`SettingsSearchIndexCoverageTest`、主题序列化及相关受影响测试。新增测试只覆盖真正的契约：偏好独立性、配方有效 spec、角色完整性/对比度、预览取消、返回完成仅消费一次等。不能写只复述实现的无效测试代替行为验证。

UI 阶段按交接门禁执行目标 JVM 测试、lint、Debug build；最终做全量本地单测门禁。真机覆盖当前主力 OnePlus、至少一个平台基准设备/模拟器、一个旧 API 与大屏窗口；录制手势从左右边缘开始、拖动取消、快速完成、三键返回、IME 打开、主题切换、旋转与后台恢复场景。记录 OS、构建、分辨率/刷新率、操作步骤与结果。

同步回归键盘输入、主题切换、浮动/单手/分体、文本编辑、剪贴板、Rime 部署同步与备份；设置 Activity 的上下文或资源变化仍可能影响这些共享组件。零方案场景应验证不能输入文字，不能用“能打字”作为通过依据。

不在规划阶段编造工期。建议用一个设置详情页 + 外观选择弹层 + 普通子页预测返回做垂直试验，再据其状态恢复、组件适配和设备结果估算剩余工作。

## 9. 风险、回滚与待审决策

### 9.1 主要风险

| 风险 | 缓解 | 回滚边界 |
| --- | --- | --- |
| 误用键盘主题偏好 | SettingsUiPrefs 独立；按 Activity 隔离；原 `follow_system_dark_mode` 等保持 | 恢复旧设置壳，键盘数据无需迁移 |
| 换肤重建丢失搜索/草稿/备份状态 | 首帧主题、SavedState、导航恢复；未应用预览不持久化；业务任务沿用现有持有者 | 关闭新换肤入口/回退旧壳，不动用户文件 |
| CMF 源码存在但依赖未包含 | 固定官方算法生成资源；明确版本/许可证/有效 spec | 切兼容配色；保留已选 recipe 值供后续恢复 |
| UI 标注规格与实际算法不符 | 配方白名单；验证最终 spec；禁止未经校验自由组合 | 停用对应配方，不冒称同名兼容方案 |
| View 组件动效覆盖不足 | 页面/状态逐项 checklist；小范围补 motion adapter | 按页面停用自制动效，正常导航保留 |
| 返回拦截抢走系统动画 | 回调只在需要时启用；每个状态单一责任；手势完成才 pop | 回退自定义转场/搜索预览，保留正确返回逻辑 |
| 系统/OEM Monet 差异 | 尊重系统原色；固定预设提供可重复视觉；真机检查 | 用户可选固定配色 |
| Preference 换壳丢 key/可见性/定位 | 保留原数据对象；分组规格、承载页、搜索同步；覆盖测试 | 逐页恢复旧 layout，不改存储 |
| 全应用主题覆盖误伤透明浮层/IME | 设置 Activity 明确名单；预览使用原主题上下文 | 恢复对应 Activity 主题配置 |
| 同期 Compose 与导航双栈迁移 | 首期 View；若未来引入 Compose 保留一个导航责任方 | 单页恢复 View，不整体重做返回栈 |

回滚建议采用内部构建级开关/可回退的页面样式适配，避免长期给用户暴露“旧设置/新设置”两套产品。新偏好可保留但被旧壳忽略；不回滚已有用户数据，不因回退恢复已删除功能。

### 9.2 待你审阅的决策表

| 决策 | 推荐 | 备选 / 影响 |
| --- | --- | --- |
| 技术路线 | View + Fragment + Preference 完整改造 | 局部 Compose；需新增依赖与更多互操作验证 |
| 首页排序 | 输入与候选、外观、键盘、便捷功能；其余保持 | 完全保持原序，只改视觉 |
| 新外观入口 | 外观 → 设置界面外观；与键盘外观分区 | 直接嵌入外观页，但需扩大通用分组页职责 |
| 主题作用域 | 所有应用自有设置/编辑器外壳；独立于键盘 | 若要求输入法本体也跟随，需另立需求 |
| 颜色方案 | 系统 Monet + 6 组固定 MCU 配色，含真实 CMF 2026 | 任意自选种子/系统色重生成 CMF，增加运行时颜色引擎工作 |
| 颜色规格呈现 | 以合法“风格配方”显示规格；CMF 固定 2026 | 高级规格选择器，但仍需按 variant 限制组合 |
| MCU 引入方式 | 固定官方版本的生成工具，审核后生成资源 | 引入固定版本/提交的隔离运行时模块；不使用受限内部 API |
| 旧用户默认 | 跟随系统 + 兼容配色 | 自动切 Monet/CMF 会明显改变原观感，需明确选择 |
| 新安装默认 | 跟随系统 + Monet 可用则启用 | 与旧用户统一兼容默认，更简单一致 |
| 预览保存 | 弹层内即时预览，「应用」保存，「取消」恢复 | 选择立即保存，需接受更频繁刷新并明确返回不撤销 |
| 大屏 | 同阶段规划，后阶段落实双栏 | 首版只最大宽度单列，双栏后续单独验收 |
| 动效偏好 | 系统减少动态效果优先；可增独立“减少动态效果” | 仅跟随系统，少一个设置项 |

上述表格是方案选择，不是依赖升级或实施授权。本次用户审阅后，下一步应先确定范围和颜色接入路线，再做垂直试验。

## 附录 A：源码核对位置

以下路径均相对 `fx-rime-only/`：

- `build-logic/convention/src/main/kotlin/Versions.kt`、`gradle/libs.versions.toml`、`app/build.gradle.kts`：SDK、依赖、Compose 配置现状。
- `app/src/main/AndroidManifest.xml`、`app/src/main/res/values/themes.xml`、`app/src/main/res/values-night/themes.xml`、`app/src/main/res/layout/activity_main.xml`：主题、Activity 与主界面容器。
- `app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainFragment.kt`、`MainActivity.kt`：首页、菜单、搜索与返回语义。
- 同目录 `settings/SettingsRoute.kt`、`settings/group/SettingsGroupSpecs.kt`、`settings/group/SettingsGroupFragment.kt`：路由与分组。
- 同目录 `settings/search/SettingsSearchIndex.kt`、`settings/search/SettingsSearchPreference.kt`：搜索、key 与别名。
- 同目录 `settings/behavior/KeyboardSettingsFragment.kt`、`KeyboardGroupFragment.kt`、`DataBackupFragment.kt`、`ClipboardSettingsFragment.kt`、`AdvancedSettingsFragment.kt`：设置入口和动作。
- 同目录 `settings/theme/ThemeFragment.kt`、应用 `data/theme/ThemePrefs.kt`、`ThemeManager.kt`：键盘主题与预览、日夜配置。
- 应用 `ui/main/modified/MyPreferenceFragment.kt`、`ui/common/PaddingPreferenceFragment.kt`、`data/prefs/ManagedPreferenceFragment.kt`：Preference 适配面。
- 应用 `utils/NavController.kt`：现有导航 Animator。
- `docs` 分支 `docs/maintainer/HANDOVER-rime-only.md` §0.6.3/0.6.4/0.6.7/0.6.8 与 §0.7.5：入口/搜索/主题回退经验与验证要求。

## 附录 B：静态搜索记录基线

本附录按当前源码提取 132 条 `SettingsSearchEntry` 的名称、原目标与定位 key，供实施逐条勾核。不是完整运行时入口树，也不是已确认所有条目当前可达的测试结果；动态 Rime 配置、各编辑器内部按钮/菜单、未索引项仍需 Phase 0 真机补录。标题从简体资源解析，缺项回退默认资源；所有旧记录的目标与 key 建议保持。

这里的 key 是**搜索定位 key**，不必然等于数据存储 key；“—”仅表示未配置定位 key，不表示此功能完全没有持久化数据。

| # | 名称 | 原目标（保持） | 定位 key（保持） |
| --- | --- | --- | --- |
| 1 | 全局选项 | `SettingsRoute.GlobalConfig` | — |
| 2 | 中州韵设置 | `rimeRoute(context)` | — |
| 3 | 附加组件 | `SettingsRoute.AddonList` | — |
| 4 | 输入与候选 | `groupRoute(SettingsGroupSpecs.ID_INPUT)` | — |
| 5 | 外观 | `groupRoute(SettingsGroupSpecs.ID_APPEARANCE)` | — |
| 6 | 便捷功能 | `groupRoute(SettingsGroupSpecs.ID_CONVENIENCE)` | — |
| 7 | 文本编辑 | `SettingsRoute.TextEditing` | — |
| 8 | 文本编辑风格 | `SettingsRoute.TextEditing` | `text_editing_style` |
| 9 | Foxy 光标移动步长 | `SettingsRoute.TextEditing` | `text_editing_cursor_step_dp` |
| 10 | Foxy 长按选取延迟 | `SettingsRoute.TextEditing` | `text_editing_cursor_long_press_delay` |
| 11 | 分体、浮动与单手键盘 | `SettingsRoute.KeyboardModes` | — |
| 12 | 启用浮动键盘 | `SettingsRoute.KeyboardModes` | `floating_mode_enabled` |
| 13 | 横屏时自动使用浮动键盘 | `SettingsRoute.KeyboardModes` | `auto_floating_landscape` |
| 14 | 启用单手键盘 | `SettingsRoute.KeyboardModes` | `one_hand_mode_enabled` |
| 15 | 靠右显示 | `SettingsRoute.KeyboardModes` | `one_hand_on_right_portrait` |
| 16 | 主题 | `SettingsRoute.Theme` | — |
| 17 | 图标主题 | `IconThemeListActivity::class.java` | — |
| 18 | 按键圆角半径 | `SettingsRoute.Theme` | `key_radius` |
| 19 | 编码区圆角半径 | `SettingsRoute.Theme` | `preedit_radius` |
| 20 | 工具栏上方圆角半径 | `SettingsRoute.Theme` | `toolbar_radius` |
| 21 | 启用按键边框 | `SettingsRoute.Theme` | `key_border` |
| 22 | 启用按键边框描边 | `SettingsRoute.Theme` | `key_border_stroke` |
| 23 | 使用 Gboard 风格的操作按键 | `SettingsRoute.Theme` | `special_key_oval_shape` |
| 24 | 文本编辑按钮圆角半径 | `SettingsRoute.Theme` | `text_editing_button_radius` |
| 25 | 剪贴板条目圆角半径 | `SettingsRoute.Theme` | `clipboard_entry_radius` |
| 26 | 候选栏高亮圆角半径 | `SettingsRoute.Theme` | `candidate_bar_highlight_radius` |
| 27 | 候选栏高亮边距 | `SettingsRoute.Theme` | `candidate_bar_highlight_inset` |
| 28 | 启用按键水波纹特效 | `SettingsRoute.Theme` | `key_ripple_effect` |
| 29 | 副标签存在时移动主字符 | `SettingsRoute.Theme` | `move_main_text_for_alt_label` |
| 30 | 标点位置 | `SettingsRoute.Theme` | `punctuation_position` |
| 31 | 导航栏背景 | `SettingsRoute.Theme` | `navbar_background` |
| 32 | 跟随系统夜间模式 | `SettingsRoute.Theme` | `follow_system_dark_mode` |
| 33 | 亮色模式主题 | `SettingsRoute.Theme` | `light_mode_theme` |
| 34 | 暗色模式主题 | `SettingsRoute.Theme` | `dark_mode_theme` |
| 35 | 虚拟键盘 | `SettingsRoute.VirtualKeyboard` | — |
| 36 | 键盘尺寸 | `keyboard(LAYOUT)` | — |
| 37 | 键盘高度 | `keyboard(LAYOUT)` | `keyboard_height_percent` |
| 38 | 键盘两侧边距 | `keyboard(LAYOUT)` | `keyboard_side_padding` |
| 39 | 键盘底部边距 | `keyboard(LAYOUT)` | `keyboard_bottom_padding` |
| 40 | 将按键范围扩展至边缘 | `keyboard(LAYOUT)` | `expand_keypress_area` |
| 41 | 自动分体键盘 | `SettingsRoute.KeyboardModes` | `split_keyboard_enabled` |
| 42 | 分体键盘校准 | `SettingsRoute.KeyboardModes` | — |
| 43 | 分体键盘时采用横屏布局参数 | `SettingsRoute.KeyboardModes` | `split_keyboard_use_landscape_layout` |
| 44 | 分体时复制中间字母键 | `SettingsRoute.KeyboardModes` | `split_keyboard_duplicate_middle` |
| 45 | 强制左右两半在中缝处对齐 | `SettingsRoute.KeyboardModes` | `split_keyboard_align_halves` |
| 46 | 按键行为 | `keyboard(BEHAVIOR)` | — |
| 47 | 按键时弹出显示字符 | `keyboard(BEHAVIOR)` | `popup_on_key_press` |
| 48 | 长按延迟 | `keyboard(BEHAVIOR)` | `keyboard_long_press_delay` |
| 49 | 符号划动方向（符号隐藏时） | `keyboard(BEHAVIOR)` | `swipe_symbol_behavior` |
| 50 | 键盘字母保持大写 | `keyboard(BEHAVIOR)` | `keep_keyboard_letters_uppercase` |
| 51 | 焦点改变时切换回键盘 | `keyboard(BEHAVIOR)` | `reset_keyboard_on_focus_change` |
| 52 | 空格键长按行为 | `keyboard(BEHAVIOR)` | `space_long_press_behavior` |
| 53 | 空格键标签样式 | `keyboard(BEHAVIOR)` | `space_key_label_mode` |
| 54 | 划动空格键以移动光标 | `keyboard(BEHAVIOR)` | `space_swipe_move_cursor` |
| 55 | 显示语言切换键 | `keyboard(BEHAVIOR)` | `show_lang_switch_key` |
| 56 | 按键反馈 | `keyboard(FEEDBACK)` | — |
| 57 | 按键时振动 | `keyboard(FEEDBACK)` | `haptic_on_keypress` |
| 58 | 按键松开时振动 | `keyboard(FEEDBACK)` | `haptic_on_keyup` |
| 59 | 重复按键时的振动反馈 | `keyboard(FEEDBACK)` | `haptic_on_repeat` |
| 60 | 按键振动时长 | `keyboard(FEEDBACK)` | `button_vibration_press_milliseconds` |
| 61 | 按键振动幅度 | `keyboard(FEEDBACK)` | `button_vibration_press_amplitude` |
| 62 | 按键音 | `keyboard(FEEDBACK)` | `sound_on_keypress` |
| 63 | 按键音音量 | `keyboard(FEEDBACK)` | `button_sound_volume` |
| 64 | 自定义按键音 | `keyboard(FEEDBACK)` | `custom_key_sound` |
| 65 | 工具栏 | `keyboard(TOOLBAR)` | — |
| 66 | 显示内嵌自动填充建议 | `keyboard(TOOLBAR)` | `inline_suggestions` |
| 67 | 输入密码时在工具栏上显示数字行 | `keyboard(TOOLBAR)` | `toolbar_num_row_on_password` |
| 68 | 工具栏大小 | `keyboard(TOOLBAR)` | `toolbar_height_percent` |
| 69 | 候选栏样式 | `keyboard(CANDIDATE)` | — |
| 70 | 横向候选词样式 | `keyboard(CANDIDATE)` | `horizontal_candidate_style` |
| 71 | 默认高亮第一个候选 | `keyboard(CANDIDATE)` | `highlight_first_candidate` |
| 72 | 显示候选项序号角标 | `keyboard(CANDIDATE)` | `show_candidate_index_badge` |
| 73 | 候选项序号角标位置 | `keyboard(CANDIDATE)` | `candidate_index_badge_position` |
| 74 | 候选项序号角标内容 | `keyboard(CANDIDATE)` | `candidate_index_badge_content` |
| 75 | 展开候选词样式 | `keyboard(CANDIDATE)` | `expanded_candidate_style` |
| 76 | 长候选词横向滚动 | `keyboard(CANDIDATE)` | `horizontal_candidate_overflow_scroll` |
| 77 | 语音输入 | `keyboard(VOICE)` | — |
| 78 | 显示语音输入按钮 | `keyboard(VOICE)` | `show_voice_input_button` |
| 79 | 首选语音输入 | `keyboard(VOICE)` | `preferred_voice_input` |
| 80 | 编辑按钮 | `keyboard(TOOLBAR)` | — |
| 81 | 键盘布局自定义 | `keyboard(EDITORS)` | — |
| 82 | 字体设定 | `FontsetEditorActivity::class.java` | — |
| 83 | 弹出字符设定 | `PopupEditorActivity::class.java` | — |
| 84 | 修改键盘定义 | `keyboard(EDITORS)` | — |
| 85 | 键盘布局管理 | `keyboard(EDITORS)` | `tool_text_layout_manager` |
| 86 | 切换键盘定义配置 | `keyboard(EDITORS)` | — |
| 87 | 数字键盘布局 | `keyboard(EDITORS)` | — |
| 88 | 在线编辑器 | `keyboard(EDITORS)` | — |
| 89 | 候选窗口 | `SettingsRoute.CandidatesWindow` | — |
| 90 | 快速预设 | `SettingsRoute.CandidatesWindow` | — |
| 91 | 显示候选窗口 | `SettingsRoute.CandidatesWindow` | `show_candidates_window` |
| 92 | 候选词字体大小 | `SettingsRoute.CandidatesWindow` | `candidates_window_font_size` |
| 93 | 候选窗口圆角半径 | `SettingsRoute.CandidatesWindow` | `candidates_window_radius` |
| 94 | 候选窗口位置 | `SettingsRoute.CandidatesWindow` | `virtual_keyboard_candidates_position` |
| 95 | 候选窗口边距 | `SettingsRoute.CandidatesWindow` | `candidates_window_padding` |
| 96 | 候选词列表方向 | `SettingsRoute.CandidatesWindow` | `candidates_window_orientation` |
| 97 | 候选窗口最小宽度 | `SettingsRoute.CandidatesWindow` | `candidates_window_min_width` |
| 98 | 候选项高亮圆角半径 | `SettingsRoute.CandidatesWindow` | `candidate_highlight_radius` |
| 99 | 物理键盘使用水平候选栏 | `SettingsRoute.CandidatesWindow` | `physical_keyboard_horizontal_candidate_bar` |
| 100 | 剪贴板 | `SettingsRoute.Clipboard` | — |
| 101 | 记录剪贴板历史 | `SettingsRoute.Clipboard` | `clipboard_enable` |
| 102 | 本地剪贴板历史上限 | `SettingsRoute.Clipboard` | `clipboard_limit_local` |
| 103 | 显示剪贴板提示 | `SettingsRoute.Clipboard` | `clipboard_suggestion` |
| 104 | 隐藏敏感信息（如密码） | `SettingsRoute.Clipboard` | `clipboard_mask_sensitive` |
| 105 | 粘贴后返回 | `SettingsRoute.Clipboard` | `clipboard_return_after_paste` |
| 106 | 远端剪贴板历史上限 | `SettingsRoute.Clipboard` | `clipboard_limit_remote` |
| 107 | 媒体历史上限 | `SettingsRoute.Clipboard` | `clipboard_limit_media` |
| 108 | 剪贴板提示超时 | `SettingsRoute.Clipboard` | `clipboard_item_timeout` |
| 109 | 远端剪贴板同步设置 | `SettingsRoute.Clipboard` | — |
| 110 | 表情和符号 | `SettingsRoute.Symbol` | — |
| 111 | 隐藏不支持的表情 | `SettingsRoute.Symbol` | `hide_unsupported_emojis` |
| 112 | 默认表情肤色 | `SettingsRoute.Symbol` | `default_emoji_skin_tone` |
| 113 | 自定义符号数据 | `SettingsRoute.Symbol` | — |
| 114 | 自定义表情数据 | `SettingsRoute.Symbol` | — |
| 115 | 自定义颜文字数据 | `SettingsRoute.Symbol` | — |
| 116 | 数据与备份 | `SettingsRoute.DataBackup` | — |
| 117 | 导出用户数据 | `SettingsRoute.DataBackup` | — |
| 118 | 导入用户数据 | `SettingsRoute.DataBackup` | — |
| 119 | 浏览用户数据目录 | `SettingsRoute.DataBackup` | — |
| 120 | 高级 | `SettingsRoute.Advanced` | — |
| 121 | 忽略系统光标位置 | `SettingsRoute.Advanced` | `ignore_system_cursor` |
| 122 | 隐藏快捷键配置 | `SettingsRoute.Advanced` | `hide_key_config` |
| 123 | 禁用动画 | `SettingsRoute.Advanced` | `disable_animation` |
| 124 | 忽略系统窗口边衬区 (WindowInsets) | `SettingsRoute.Advanced` | `ignore_system_window_insets` |
| 125 | 解决 vivo Origin OS 按键检测问题 | `SettingsRoute.Advanced` | `vivo_keypress_workaround` |
| 126 | 键盘高度百分比 | `SettingsRoute.Advanced` | `keyboard_height_percent_base` |
| 127 | 开发者 | `SettingsRoute.Developer` | — |
| 128 | 实时日志 | `SettingsRoute.Developer` | — |
| 129 | 详细记录日志 | `SettingsRoute.Developer` | `verbose_log` |
| 130 | 重启 Fcitx 实例 | `SettingsRoute.Developer` | — |
| 131 | 关于 | `SettingsRoute.About` | — |
| 132 | 许可 | `SettingsRoute.License` | — |

## 附录 C：本轮验证范围

本轮完成源码/资源/官方资料交叉核对与报告人工审阅。`git diff --check` 通过，`npm --prefix docs run build` 成功，报告页面被文档站生成；构建输出有 bundle 超过 500kB 的体积提示，没有阻止构建。报告新增文件另行检查空白与占位标记。

没有运行 Android 编译、JVM 测试、安装、真机手势、截图或性能测量。应用实现效果均待审阅后验证。没有 commit、push 或触发外部 workflow。
