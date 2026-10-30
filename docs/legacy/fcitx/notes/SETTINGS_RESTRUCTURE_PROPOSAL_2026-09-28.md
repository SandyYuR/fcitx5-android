# 设置界面重构方案（2026-09-28 调查报告）

> **文档状态**：历史调查报告，2026-09-28 全量只读代码审阅结论。
>
> 本文由工作区根目录的 `settings-review/设置重构方案.md` 搬入文档分支，原文逐字保留（第九节之后为搬入时补充的实施状态对照）。当前事实以代码分支 `fx-rime-only` 的 `3d0af21e` 为准；文档源码位于 `docs` 分支的 `docs/maintainer/`。
>
> **配套图**（独立页面，在新标签页打开）：
>
> - <a href="/fcitx5-android/maintainer/settings-review/设置界面思维导图.html" target="_blank" rel="noopener">设置界面思维导图</a>（自研双视图：重构方案 / 现状问题，可切换）
> - <a href="/fcitx5-android/maintainer/settings-review/设置现状全景图.html" target="_blank" rel="noopener">设置现状全景图</a>（archify 生成，现状 10 入口架构）
> - <a href="/fcitx5-android/maintainer/settings-review/设置重构方案图.html" target="_blank" rel="noopener">设置重构方案图</a>（archify 生成，目标 6 入口架构；源数据 `现状.architecture.json` / `方案.architecture.json` 随附在同目录）
>
> **与交接报告的关系**：本报告是“当时看到了什么、打算怎么做”（方案）；交接报告 §0.6（0.6.3 设置首页重构、0.6.4 搜索跳转、0.6.6 候选预设/颜色本地化/导出按钮、0.6.7 外观模式回退、0.6.8 搜索索引覆盖测试）是“实际做了什么、踩了什么坑”（实施记录）。读方案提的某一条，先去交接 §0.6 查它后来的实施状态，再看本页末尾的“实施状态对照”。

---

# 设置界面重构方案

> 调查对象：`fx-rime-only` @ `f84084c3`（`fx-rime-only` 分支，ahead 2）
> 调查方式：全量只读代码审阅；结论均附文件与行为依据。
> 配套图见本页顶部的三个链接。

---

## 一、现状：设置一共有多少东西

入口在 `MainFragment.kt`（调查基线 `f84084c3` 下的 `app/src/main/java/org/fcitx/fcitx5/android/ui/main/MainFragment.kt`），分两个大类共 **10 项**，其下再展开 **30 余个页面**。

| 类别 | 入口项 | 实际页面 | 规模 |
| --- | --- | --- | --- |
| Fcitx | 全局选项 | `GlobalConfigFragment` | 引擎配置树自动生成，约 20 项 |
| Fcitx | 中州韵设置 | `InputMethodConfigFragment` | Rime 适配层配置，11 项 + 4 个跳转 |
| Fcitx | 附加组件 | `AddonListFragment` | 4 个 addon（仅 2 个可配置） |
| Android | 主题 | `ThemeFragment` | 2 个 tab：主题列表 + 「配置」15 项 |
| Android | 图标主题 | `IconThemeListActivity` | 46 个图标槽位 |
| Android | 虚拟键盘 | `KeyboardSettingsFragment` | 5 个分组页，共 26 项 + 6 个工具入口 |
| Android | 候选窗口 | `CandidatesSettingsFragment` | 10 项样式参数 |
| Android | 剪贴板 | `ClipboardSettingsFragment` | 8 项 + 独立同步设置页 |
| Android | 表情和符号 | `SymbolSettingsFragment` | 5 项 |
| Android | 高级 | `AdvancedSettingsFragment` | 5 项开关 + 3 个数据操作 |

另有 3 个页面只能从别处进入：主题编辑器（22 个颜色项）、Monet 颜色映射（21 项）、图标主题编辑器（46 槽）、按钮自定义器（12 种按钮）、分体键盘校准、网页编辑器（`ImeWebEditorBridgeServer`）。

---

## 二、核心问题诊断

### 1. 同类参数跨页拆散 —— 「设置在哪」无法预测

| 参数族 | 现在的位置 A | 现在的位置 B |
| --- | --- | --- |
| 键盘几何 | 虚拟键盘 → 布局与尺寸：高度、两侧边距、底部边距 | 主题 → 配置 tab：按键横向/纵向边距、按键圆角 |
| 按键视觉 | 主题 → 配置 tab：按键边框、描边、水波纹、标点位置 | 虚拟键盘 → 按键行为：按键时弹出显示字符 |
| 候选栏 | 候选窗口页：10 项样式 | 虚拟键盘 → 工具栏与候选栏：`horizontal_candidate_style`、`highlight_first_candidate`、`expanded_candidate_style`、`expanded_candidate_grid_span_count` |
| 语音 | 虚拟键盘 → 工具栏与候选栏：显示语音按钮、首选语音输入 | 无其它入口，但语义属「输入方式」而非「工具栏」 |

依据：`KeyboardGroupFragment.kt:388-414`、`ThemePrefs.kt:37-160`、`AppPrefs.kt:335-370`（调查基线 `f84084c3` 下的相对路径，原文如此）。

### 2. 「增强选项」是一个无法自解释的空容器

`GROUP_EDITORS` 的 `KEYS_BY_GROUP` 是 `emptySet()`，页内没有任何 ManagedPreference，全是运行时 `addTool()` 塞进去的 6 个跳转项：字体设定 / 弹出字符设定 / 修改键盘定义 / 切换键盘定义配置 / 数字键盘布局 / 在线编辑器。用户看到「增强选项」这四个字，无从知道里面是编辑器集合。

依据：`KeyboardGroupFragment.kt:100-126, 413`（同上基线，原文如此）。

### 3. 引擎三页直接翻译内部术语

三页均由 `PreferenceScreenFactory` 递归解析 Fcitx/Rime 的 `desc` 树生成，标题回落到 `descriptor.description ?: descriptor.name`。凡是上游没给 `Description` 的项，用户看到的字面就是 `HiddenNotifications`、`CustomXkbOption`、`Profiles`、`Current`、`Error` 这类内部键名。未实现类型还会渲染成 `"⛔ 未实现类型 '...'"`。

依据：`PreferenceScreenFactory.kt:67, 94-97, 256`（同上基线，原文如此）。

更值得注意的三个具体现象：

- **`Deploy` / `Synchronize` 与 `Deploy now` / `Synchronize now` 并存**，前者是上游自承的兼容遗留项（`rimeengine.h:142-147` 注释原文 "legacy key options for old app compatibility"），且默认被隐藏。
- **`notifications` 的「Hidden Notifications」要求用户预先知道内部 notification id** 才能填，属纯开发者用途。
- **「附加组件 → 中州韵」与「中州韵设置」是同一份 `RimeEngine::getConfig()`**，用户在两个入口看到同一批选项，无任何说明。

依据：`rimeengine.h:87-160`、`AddonListFragment.kt:96-124`（同上基线，原文如此）。

### 4. 默认值与交互互相打架

`follow_system_dark_mode` 默认 `true`，而列表点击主题走的是 `normal_mode_theme`——被注释明确标注为「internal preference which does not need UI」。结果是用户点任何主题都先撞上「"跟随系统夜间模式"已启用，不能手动更换主题。」的弹窗，必须先去找「禁用它」按钮。而真正承载手动选择的项**在界面上没有入口**。

依据：`ThemePrefs.kt:129-144`、`ThemeListFragment.selectTheme`（同上基线，原文如此）。

### 5. 有功能，但设置里搜不到

浮动键盘（`floating_mode_enabled`）与单手键盘（`one_hand_mode_enabled`）默认 `false`，**只能由工具栏按钮/状态区触发**，设置树里完全没有入口。用户若不碰巧点到那个图标，永远不会发现这两个功能；位置、尺寸、靠左靠右这些参数也只能靠拖拽隐式保存。

依据：`AppPrefs.kt:61-70`、`ButtonsCustomizerActivity` 的 `floating_toggle` / `one_handed_keyboard` 按钮定义（同上基线，原文如此）。

### 6. 外观类设置：预览本身是实时的（原判断有误，2026-09-28 更正）

> **更正说明**：本节初稿称「调圆角/边框/边距时预览不会更新，等于盲调」，该结论**错误**，
> 是在未自行验证的情况下采信了调查转述。实际的刷新链路如下，预览本来就是实时的。

改 `ThemePrefs` 任意一项 → `AppPrefs` 的 `OnSharedPreferenceChangeListener` 遍历**所有** provider
执行 `fireChange(key)`（`AppPrefs.kt:586-588`）→ `ThemeManager.onThemePrefsChange` 对非日期/夜间键
落在 `else` 分支调 `fireChange()` → `ThemeFragment` 的 `OnThemeChangeListener` 收到通知后调
`previewUi.setTheme(activeTheme)`；此时主题对象未变（`sameTheme == true`），于是走
`refreshStyle()` → `reloadLayout()` 重建整棵键盘视图，新值生效。

而 `BaseKeyboard.currentRowsSignature()` **已把外观参数纳入签名**（`keyBorder`、`keyBorderStroke`、
`keyRippleEffect`、`keyRadius`、`specialKeyOvalShape`、四个横纵边距），所以重建不会被行缓存挡住。

依据：`BaseKeyboard.kt:340-347`、`KeyboardPreviewUi.kt:640-645`、`AppPrefs.kt:586-588`（同上基线，原文如此）。

**仍然成立的一处**：两处缩放实现不一致——`ThemeFragment` 用 `scaleX/scaleY = 0.5f`
（不改测量尺寸，占位仍是全尺寸，视觉缩半），而 `CustomThemeActivity` / `MonetThemeEditorActivity`
走 `setSizeScale`（真改测量尺寸）。这是真实的实现分歧，但属外观一致性问题，不影响「所见即所得」。

### 7. 默认折叠的开关放在了别处

`hide_key_config` 默认 `true`，`PreferenceScreenFactory` 按类型名字符串匹配（`contains("Key")`）隐藏整族快捷键项。开关本身在「Android → 高级」，与它作用的「全局选项」不在同一上下文，用户不会想到去那里找。

依据：`PreferenceScreenFactory.kt:52, 85`、`AppPrefs.kt:81`（同上基线，原文如此）。

### 8. 可发现性靠猜

- 导出主题：只能长按主题卡片，界面上没有可见按钮。
- Monet 颜色编辑：`supportsCustomMappingEditor` 为 false 时**静默 return，无任何提示**。
- 二维码分享：截图源靠父 Fragment 的 View tag 跨层隐式查找。

依据：`ThemeListFragment.kt:68-75, 287-313`、`ThemeListAdapter` 长按监听。

---

## 三、目标信息架构

把 10 个一级入口收敛为 **6 个**，按「多久会改一次」分成两层。

```
设置
├─ 日常调整（默认展开）
│   ├─ 输入与候选      Rime 配置 · 候选栏 · 按键行为
│   ├─ 键盘            尺寸边距 · 工具栏按钮 · 按键反馈
│   ├─ 外观            主题 · 图标 · 字体 · 按键外观
│   └─ 便捷功能        剪贴板同步 · 表情符号 · 语音
└─ 按需进入（默认折叠）
    ├─ 数据与备份      导入导出 · 用户数据目录
    └─ 高级            引擎配置 · 实验开关 · 开发者
```

### 归位对照表

| 现有项 | 迁移到 | 处理 |
| --- | --- | --- |
| 主题（列表 tab） | 外观 | 保留，去掉与「配置」tab 的割裂 |
| 主题 → 配置 tab 的 15 项 | 外观 | 与主题列表合并为同页分区，不再用双 tab |
| 图标主题 | 外观 | 保留 |
| 增强选项 → 字体设定 | 外观 | 从空容器里取出，成为外观的明确子项 |
| 增强选项 → 弹出字符设定 | 外观 | 同上 |
| 虚拟键盘 → 布局与尺寸 | 键盘 | 保留 |
| 虚拟键盘 → 按键行为 | 输入与候选 | 按键行为与输入语义同族，与几何参数分开 |
| 虚拟键盘 → 按键反馈 | 键盘 | 保留（触感/音效是键盘物理属性） |
| 虚拟键盘 → 工具栏与候选栏（候选样式部分） | 输入与候选 | 与「候选窗口」页合并 |
| 虚拟键盘 → 工具栏与候选栏（语音部分） | 便捷功能 | 语音是一种输入方式，不是工具栏属性 |
| 虚拟键盘 → 增强选项（键盘定义/数字布局/在线编辑器） | 键盘 → 工具 | 空容器改为明确的「键盘工具」区 |
| 候选窗口 10 项 | 输入与候选 | 与候选栏样式合并；样式参数收敛为预设 |
| 剪贴板 | 便捷功能 | 保留；同步设置从独立 Activity 改为内嵌页 |
| 表情和符号 | 便捷功能 | 保留 |
| 高级 → 5 项开关 | 高级 → 兼容性 | 拆分：与设备相关的按条件显示 |
| 高级 → 导出/导入/浏览用户数据 | 数据与备份 | 独立成页 |
| 全局选项 / 中州韵设置 / 附加组件 | 高级 → 引擎配置 | 降为二级 |
| 浮动键盘 / 单手键盘 | 外观 | **新增显式入口**（当前完全缺失） |

---

## 四、该默认的默认

| 项 | 当前默认 | 建议 | 理由 |
| --- | --- | --- | --- |
| `follow_system_dark_mode` | `true`（且与手动选主题冲突） | 改为三态单选框：跟随系统 / 始终亮色 / 始终暗色 | 消除「点主题被弹窗拦住、手动选择却无入口」的死结 |
| `split_keyboard_enabled` | `true` + 按设备类型智能阈值 | 保持不变 | 已有 `SmartDefaultInitializer` 按平板/折叠屏/手机分类给阈值，做得好 |
| 候选窗口 10 项样式参数 | 逐个平铺，各给一个数值 | 收敛为「紧凑 / 标准 / 宽松 / 自定义」预设，细节收进自定义 | 普通用户不该先理解 `dp` 和 `sp` 才能调候选栏 |
| `clipboard_listening` | `true` | 保持可用，但首次开启时给一次明确说明 | 剪贴板历史属隐私敏感，默认开需要一次性知情 |
| `key_ripple_effect` / `key_border_stroke` | `false` | 保持不变 | 属装饰性选项，默认关合理 |
| `hide_key_config` | `true` | 保持不变，但开关移到「高级 → 引擎配置」 | 隐藏行为正确，只是开关放错了地方 |
| `disable_animation` / `ignore_system_window_insets` / `vivo_keypress_workaround` | 后者已按设备判断 | 保持条件默认，并让界面按设备能力显示 | 代码已有 `DeviceUtil` 判断能力，界面层应同步 |

---

## 五、该隐藏的隐藏

规则：**默认只显示普通用户在正常使用中可能想调的东西；其余降一级、折叠，或按设备能力出现。**

| 内容 | 隐藏方式 | 依据 |
| --- | --- | --- |
| Fcitx 快捷键族（`TriggerKeys` 等） | 默认隐藏（现状已如此），开关移入「高级 → 引擎配置」 | `hide_key_config` 默认 true，行为正确 |
| 引擎配置树里的开发者项（`ModifierOnlyKeyTimeout`、`AutoSavePeriod`、`HiddenNotifications`、`CustomXkbOption`） | 收进「高级 → 引擎配置 → 显示全部选项」二级折叠 | 这些项要求用户理解内部语义 |
| 标为 unimplemented 的占位行 | 不再渲染，或仅在开发者模式显示 | 当前会渲染成「⛔ 未实现类型」 |
| 系统兼容开关 | 按 `DeviceUtil` 判断结果决定是否显示；无关设备上完全隐藏 | 代码已有判断能力 |
| 候选窗口样式细节 | 折叠在「自定义」下 | 见第四节 |
| 主题 22 个颜色项 | 按语义分组折叠（背景 / 按键 / 候选 / 弹出 / 其它），默认只展开前两组 | 当前是一长串硬编码英文名 |
| `Deploy` / `Synchronize`（legacy） | 与 `Deploy now` 合并或仅在开发者模式显示 | 上游自承为兼容遗留 |
| 「附加组件 → 中州韵」 | 与「中州韵设置」合并为一个入口 | 两者是同一份配置 |
| 开发者 / 许可 / 关于 | 保持在「高级」末尾 | 现状已在菜单里，位置合理 |

---

## 六、命名改造

| 现名 | 问题 | 建议 |
| --- | --- | --- |
| 增强选项 | 空容器，无法自解释 | 拆解：字体/弹出样式 → 外观；键盘定义/数字布局/在线编辑器 → 键盘 → 键盘工具 |
| 主题 → 配置（tab 名） | 过泛，实际是按键外观参数 | 取消 tab，改为「外观」页内的「按键外观」分区 |
| 全局选项 | 引擎术语，用户不知改什么 | 高级 → 引擎配置 → 全局行为 |
| 中州韵设置 | 与「附加组件 → 中州韵」重复 | 合并为「Rime 引擎」 |
| 工具栏与候选栏 | 混装了候选样式与语音 | 拆散后该页不再存在 |
| 布局与尺寸（子页） | 仅含几何参数，尚可 | 并入「键盘」页 |

图标主题槽位弹窗当前直接展示内部键名（形如 `Key: capslock.none`），应改为中文名。主题编辑器的 22 个颜色项全为硬编码英文字符串，且显示名同时充当逻辑标识（`item.name == "Water Ripple"` 这类判断依赖它）——**本地化前必须先把显示名与存储标识解耦**，否则会改坏已保存的主题数据。

---

## 七、需要一并修的交互缺陷

按优先级：

1. ~~让预览真正实时~~ —— **2026-09-28 核实后撤销**：预览本来就是实时的（见第二节第 6 条的更正）。这条原排在首位，但它基于错误的调查结论，不必实施。
2. **补浮动/单手键盘的设置入口** —— ✅ 已完成（`KeyboardModesFragment`）。
3. **让导出主题有可见入口**，不再只靠长按。 —— 未做。
4. **Monet 编辑入口不可用时给出说明**，不再静默 return。 —— 未做。
5. **统一配置键命名**。网页编辑器（`ImeWebEditorBridgeServer`）已有一套别名映射到 `ThemePrefs` 的键，只改设置页会留下两套命名，且网页端还缺横屏边距、水波纹等项目。 —— 未做（`key_radius`/`key_border` 等键名未改动，别名映射仍然有效）。

---

## 八、实施建议

分四步走，每步可独立验证、可回退：

| 阶段 | 内容 | 风险 |
| --- | --- | --- |
| 1 | 纯信息架构调整：新增/合并路由、迁移设置项归属。**不改任何存储 key**，不动默认值 | 低 |
| --- | --- | --- |
| 2 | 默认值与预设：三态夜间模式、候选窗口预设 | 中，需处理存量用户已存的值 |
| 3 | 隐藏规则与命名：按设备能力显示、收拢开发者项、颜色项本地化 | 中，颜色项本地化前必须先解耦标识 |
| 4 | 预览实时化与交互补齐 | 中，涉及键盘视图刷新路径 |

**兼容性红线**：第 1 阶段只动 UI 归属，不触碰 `AppPrefs`/`ThemePrefs` 的 key；第 3 阶段改动颜色项显示名时，必须先确认持久化数据里的标识字段不受影响——主题以 JSON 持久化（`CustomThemeSerializer`，当前版本 2.1），字段名变更需要同步迁移逻辑。

**验证**：每阶段至少跑 `:app:testFxDebugUnitTest`（当前 31 文件 / 225 例）与 `:app:assembleFxDebug`；涉及主题/布局持久化时补 `LayoutJsonUtilsTest` 类覆盖的 JSON 往返用例；真机确认设置项迁移后旧值仍能正确读回。

---

## 九、本次调查的边界

- 全程只读代码，未运行 Gradle，未在真机上打开过任何设置页面。
- 引擎侧三页的实际渲染项由 `desc` 树动态决定，**列表随 addon 加载状态变化**；上文条目数依据本版 assets 中的 4 个 `.conf` 与构建脚本的排除配置推导。
- 「主题 → 配置」tab 的渲染顺序由 `ManagedPreferenceFragment` + 已注册 UI 项集合推导，未在真机核对。
- 所有文件行号对应调查时的 `f84084c3`。

---

## 十、实施状态对照（搬入时补充，基线 `3d0af21e`）

对照 `f84084c3..3d0af21e` 之间设置页相关的 25 个提交，方案各项的落实情况如下。细节（为什么这么做、踩了什么坑）见交接报告 §0.6 各小节，本节只给结论与提交号。

| 方案条目 | 状态 | 落实 |
| --- | --- | --- |
| 三、目标信息架构（两层 6 入口） | ✅ 已实施 | `8abbf722` 首页重构为两层分组并加入搜索与跳转定位；IA 微调（09-29）：「中州韵设置」与「全局选项」放「输入与候选」，「附加组件」与隐藏快捷键开关放「高级」，「引擎配置」中间层取消（交接 0.6.3） |
| 归位：弹出字符设定移入键盘 | ✅ 已实施 | `1464ff59`；分组小字与「键盘布局自定义」定稿（交接 0.6.3） |
| 归位：分体/浮动/单手归并 | ✅ 已实施 | `9ec6cc66` 分体/浮动/单手归并一页、键盘尺寸改名、文案统一（交接 0.6.10） |
| 归位：主题与候选栏可见性 | ✅ 已实施 | `4a957b3a`（交接 0.6.3） |
| 七.2 浮动/单手键盘入口 | ✅ 已实施 | `KeyboardModesFragment`（交接 0.6.3）；互斥逻辑必须用 `onPreferenceChange`（tree click 里读到旧值） |
| 搜索跳转滚动定位 | ✅ 已实施 | 09-28 ~ 09-29（交接 0.6.4）；高亮试过又移除——`scrollToPosition` 是瞬时定位，`onViewCreated` 时列表未 measure，只做滚动定位 |
| 四、候选窗口预设 | ✅ 已实施 | 紧凑/标准/宽松三组（`CandidatesPresets.kt`），是动作不是模式；`CandidatesPresetsTest` 钉住范围（交接 0.6.6） |
| 六、颜色项本地化 + 分组折叠 | ✅ 已实施 | `titleRes` + `id` 解耦，按语义 5 组、默认展开前 2 组（交接 0.6.6） |
| 七.3 导出主题可见入口 | ✅ 已实施 | 主题卡片左下角补按钮（交接 0.6.6；方案写作时写“未做”，是报告时间差） |
| 七.4 Monet 门禁提示 | ✅ 已实施 | 不再静默 return，改为明确提示（交接 0.6.6；同上，时间差） |
| 五、未实现类型不再渲染 | ✅ 已实施 | 改为不加入页面 + `Timber.w`（交接 0.6.3） |
| 0.6.9 宏键/布局显示文本优先 | ✅ 已实施 | `a1ffb91f`、`f2dc67ce`、`4be7dc55`、`3b85496a`（交接 0.6.9、0.6.12） |
| 四、三态夜间模式 | ❌ 未做（已回退） | 试做「外观模式」后按用户要求整体回退（交接 0.6.7）；回退教训：`normal_mode_theme.key` 进 `dayNightModePrefNames` 会打断列表点击换主题 |
| 五、开发者项二级折叠 | ❌ 未做 | 「显示全部选项」折叠未实施 |
| 七.5 统一配置键命名 | ❌ 未做 | `key_radius`/`key_border` 等键名未改动，网页端别名映射仍然有效 |
| 六、图标槽位中文名 | ❌ 未做 | 仍显示内部键名 |
| 二.8 二维码分享截图源 | ❌ 未做 | 仍靠父 Fragment View tag 跨层隐式查找 |

方案基线之后、方案之外的设置相关新增（供对照，不属方案实施）：`d84e20ee` 工具栏大小百分比、`06364f60` 分体按子布局配置与占位键、`c56b2d99` 奇数行复制中间键、`34fd116f` 横屏自动浮动、`d156052b` + `4f0d6fb8` 上下划动宏、`751480cd` 长候选横滚、`635d3aa2` + `fdb765a3` 序号角标、`ed38a31b` 双标签回退、`01d0dfee` 圆角接续、`43e16fb6` + `2ad85b93` 随机主题、`4eae6539` 划动标签脱离标点位置、`e916245e` 字体即时生效、`fbed2500` Foxy 文本编辑布局、`0ae64681` 空格长按重复、`3d0af21e` 分享导入 Loading 范围。对应细节见交接 0.6.10 ~ 0.6.22。
