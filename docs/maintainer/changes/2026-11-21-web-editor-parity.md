# 2026-11-21 网页编辑器与输入法源码对齐

本轮以输入法默认分支的实际代码核对独立网页编辑器，重点是键盘预览、布局与按键编辑、资源导入导出和草稿。后续副标签核对增补了输入法编辑桥的主题偏好字段。下文记录验证时的工作区状态；用户随后授权提交与推送，对应源码提交见本页末尾。

## 固定来源

| 对象 | 本轮来源 |
| --- | --- |
| 输入法默认分支 | `fx-rime-only`，基线 `899eedbf1b7eb8475f7f2a3e162e6ec014763be5`；副标签修订增补编辑桥偏好读写 |
| 网页编辑器基线 | `site`，`af6e0d8a3d426d42ae0e8b1c7074466e0cbfde70`；本轮修订在工作区中 |
| 文档基线 | `docs`，`c6431d610a432917c6d0e65c01202f3c3aa05ce9` |
| 本机设备 | 唯一 AVD `rime`，API 35，`emulator-5574`；1080 × 2280、density 440 |

开始核对时三个工作区均无未提交修改。主仓库与网页编辑器已获取远端并核对默认分支与 `site`；此记录不以旧报告代替本次验证。

## 源码与修订

| 范围 | 输入法生产依据 | 网页修订 |
| --- | --- | --- |
| 层与数字子布局 | `TextKeyboard`、`NumericLayoutResolver`、`LayoutJsonUtils` | 新建层子布局并选择复制来源；保留 `__layer__:` 存储键，显示可读层名；数字层说明与自动发现规则一致 |
| 排列与高度 | `LayoutVariantResolver`、`TextKeyboard` 的高度读取、`TextKeyboardLayoutEditorActivity.openRowSettings` | 普通与分体共用子布局高度和辅助栏；删除分体只删除该排列；新增整行高度、分体断点及整行连贯设置 |
| 键面与文本编辑栏 | `KeyDefPreset`、`KeyView`、`TextEditingWindow` | 修订键形、字号、标签与显式显示文本，区分竖横屏度量；文本编辑栏按专用高度与按钮权重规则显示 |
| 完整按键保存 | `LayoutJsonUtils.resolveDisplayTextMap`、`keysymToName`、`KeyboardEditorUiBuilder` | 保留空名称回退与显示空格、数字键的原始 keysym 和自定义键面；普通属性编辑不静默改写它们 |
| 宏 | `LayoutJsonUtils.parseMacroStep`／`parseKeyRef`、`BaseKeyboard.executeShortcut`、`MacroInputController` | Fcitx 与 Android 引用保留类型；允许合法自定义键名、旧单修饰键结构、自由切层目标和稳定 App ID |
| 符号目录 | `SymbolCatalog`、`SymbolPanelUi` | 增加侧栏／底栏预览；目录修改加入撤销与草稿，保留名称、条目及兼容字段 |
| 图片与图标包 | `Theme.BackgroundImage`、`IconTheme` 的资源使用规则 | 图片预览、Canvas 导出共用裁剪与旋转结果；图标 ZIP 重导入后保留相对资源 |
| 交互稳定性 | 浏览器实际布局与操作 | 属性窗与预览测量经动画帧合并，避免重复测量导致 ResizeObserver 警告 |

输入法源码链接固定到本轮 SHA：[键盘实现](https://github.com/SandyYuR/fcitx5-android/tree/899eedbf1b7eb8475f7f2a3e162e6ec014763be5/app/src/main/java/org/fcitx/fcitx5/android/input/keyboard)、[布局解析](https://github.com/SandyYuR/fcitx5-android/blob/899eedbf1b7eb8475f7f2a3e162e6ec014763be5/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/utils/LayoutJsonUtils.kt)、[布局编辑器](https://github.com/SandyYuR/fcitx5-android/blob/899eedbf1b7eb8475f7f2a3e162e6ec014763be5/app/src/main/java/org/fcitx/fcitx5/android/ui/main/settings/behavior/TextKeyboardLayoutEditorActivity.kt)。网页修改主要位于 `js/app.js`、`js/layout-workspace.js`、符号面板模块及页面样式；未提交文件不能用基线 GitHub 链接冒充修订代码。

## 验证

以下为副标签四字段增补前的已完成验证，后续副标签模型与编辑桥修订的最终检查结果待补录。原始日志与截图保留在本机 `.verify/editor-review-20261121/`。

- 前一阶段 Node 测试 175 项通过，失败、取消、跳过均为 0，日志为 `node-tests.log`；当时的 JavaScript 语法检查通过。
- 通过 CUA／CDP 在真实浏览器执行 8 组检查，脚本为网页仓库 `tests/browser-parity.js`：属性窗草稿、应用与历史；层创建、来源复制、共享高度与仅删除分体；行设置及非法输入；NumPad 和宏完整保存；空白 Popup、符号历史／草稿、两种面板风格和预览方向。
- 图片实际 Canvas 像素检查通过：80 × 40 的四色来源图，40 × 40 裁剪并旋转 90° 后，左上为蓝、右上为红；主预览与导出裁剪结果一致，主题 ZIP 重导入成功。图标 ZIP 的引用路径与拒绝歧义由 Node 事务测试验证。
- 发现的 ResizeObserver 循环警告已修订合并测量回调，复测 JavaScript 错误为 0。
- 实际 QR 流程通过 LZMA 压缩、二维码与 PNG 长图生成，长图为 816 × 1117。普通 localhost／HTTP 静态入口先探测编辑桥，未连接手机时正常进入本地编辑，不再将 HTML 404 响应显示成多块加载错误。原始浏览器结果保留在本机 `.verify/editor-review-20261121/browser-results.json`。
- 360、390、768、980、1080、1440、1920 七种窗口宽度均无横向溢出；390 × 480 短窗口中按键样例和操作可见。原始数据为 `responsive-results.json`，网页截图为 `desktop-editor.jpg` 与 `mobile-editor.jpg`。
- 本机 Python 没有 Playwright 模块，未安装额外依赖，`tests/workspace-browser.py` 整套流程尚未执行；上述实际浏览器操作不冒称该脚本通过。

本机设备只进行现有安装包的文本／数字布局观察和导航，没有重新安装或写入布局文件。安装包版本为 `nightly-0.1.3-857-gd2745125-20261120-141731-2-g899eedbf1`，保留用户长期使用的水彩主题；观察截图为 `rime-keyboard.png`、`rime-number.png`。没有执行完整 Android 前端行为验收，也没有执行 IME 编辑桥的写入测试。

前一阶段文档五项门禁通过：站点构建；323 页、24996 条链接和 1651 个资源链接检查；64 个历史页面、13 份维护报告及 83 个原文件完整性；271 个目录文档、45 条保留路由；8 组搜索查询及 5 组范围检查。错误均为 0，另通过 `git diff --check`。

## 后续：副标签偏好与确认版图标

对照 `KeyView` 与 `ThemePrefs`，网页双入口共用 `themeAppSync`，增补 `moveMainTextForAltLabel`、`altLabelOnSide`、`altLabelSide`、`showKeyAltLabels` 四字段，默认 true／false／right／true。它们进入网页历史／草稿及 `ImeWebEditorBridgeServer` 的 GET／PUT 偏好读写，不写入主题 JSON；网页本地动作提示与真实副标签显隐分别控制。

最终 Node 测试 181 项通过，失败、取消、跳过均为 0，日志为 `alt-label-node-tests.log`。最终 CUA 浏览器检查 8 组通过（基础流程 6 组、副标签 1 组、分享 1 组），JavaScript 错误为 0，原始结果为 `alt-label-final-browser.json`：单下滑标签与字母底部标点居中并采用相同主字符位置，移动主字符开关、双上下标签居中、物理左／右侧和独立显隐通过；切换动作圆点时主字符包围盒与字号完全不变，布局 JSON 不变。实际分享长图仍为 816 × 1117；图片裁剪像素和资源包验证保留上节的前阶段结果，不重复扩大验证范围。

编辑桥的 `BridgeThemePrefsTest` 4 项与 `BridgePhoneSaveTest` 1 项通过，JUnit 失败、错误、跳过均为 0；ARM64 Debug 构建、本次 APK 白名单验证与链接审计通过。APK SHA-256 为 `ec0b439b5773b84550da46710f923096d7729d3133a30a903bd52a0059710cc9`，未安装。源码、测试和 SDK 锁摘要及原始日志保留在 `.dsh-tmp/webeditor-theme-sync-20261121/verification.json` 与同目录附件，JVM 复用既定共享 Robolectric 缓存和本机测试 init。

按用户指定，将 `.design/zhongzhou-goose-20261031/approved/logo.svg` 精确复制为网页 `favicon.svg`，只替换网页图标。SVG 为 1254 方形画布、6874 字节，仅含静态矢量元素；源与目标 SHA-256 均为 `dbeb3e0c66e85272ca1f69d4d5790e96a8e937b565e546ba961056e702970bab`。用户说明和品牌维护句已同步，尚未发布。

## 范围与限制

网页提供按输入法字段和度量规则生成的预览；自定义字体、成套主题的字体绑定和完整 Monet 动态取色仍在应用内设置。实际输入方案状态、系统字号和屏幕尺寸会影响设备效果。

分享长图复用普通／分体键盘的几何和图片裁剪结果；符号面板、文本编辑触控板及展开状态区尚未完整绘制，在分享图中明确标为示意。编辑桥只提供当前方向的部分度量，另一方向的留白、全局高度和字体缩放不能宣称与个人设备逐像素一致。

新增桥字段已完成 JVM 与 Debug 门禁，浏览器验证不能作为完整 Android 前端验收。新 APK 未安装，现有旧安装包不支持这四项偏好读写；本轮没有进行装机后的四字段同步测试，只观察已有安装包，没有写入布局。设备操作遵守唯一 AVD、既有安装签名和数据保护边界，不清空应用数据、不改用户方案。

用户说明已同步到[在线编辑器](/features/online-editor)、[布局编辑器](/features/editor/layout-editor)和宏 KeyRef 相关页面，功能映射补充到[现行覆盖表](./feature-coverage)。

## 后续：字体完整显示与文本编辑标题背景

本次继续核对时，输入法默认分支 HEAD 为 `8bb9fe7af7c0d11387fcdf2d00ae1881b043bde6`，文档 HEAD 为 `8f9b120a07de9746d0dcd03d03147ff9ce6b9ded`，网页编辑器仍基于 `af6e0d8a3d426d42ae0e8b1c7074466e0cbfde70` 的本地修订。此处记录本轮字体与标题修订，保留前文各阶段的固定来源和结果。

主键文字先使用正常行高与可见溢出（`normal`／`visible`），再按 DOM 实际字体度量拟合。Canvas 同时使用字体边界与字形边界的 ascent／descent 及 actual bounding width 检查宽度、高度，拟合后显式设置 `lineHeight`，避免 `g/j/p/q/y` 下伸部和 `É` 等上伸部被过紧的行盒裁切。常规缩小达到 7 sp 最低字号后，若仍超出可用宽高，继续按原生比例缩放；取消 8 sp 的可用高度下限，始终按实际键内容高度拟合。

文本编辑标题按原生 `TitleUi` 使用 `altKeyTextColor`，`ToolButton` 默认透明，hover 与 focus-visible 不添加网页通用按钮的底色、边框或阴影。启用按键边框时，标题透明并显露 `backgroundColor`／图片背景；关闭边框时标题使用 `barColor`，下面的文本编辑内容区继续使用 `keyboardColor`。

- Node 测试 182 项全部通过，失败、取消、跳过均为 0；原始日志为 `text-preview-node-tests.log`。
- 真实 CUA 浏览器累计 10 组检查通过，JavaScript 错误为 0。`runTextPreview` 在 `gjpqyÉ中` 七个字形、五行、竖横屏两个方向的 70 个常规字形之外，追加 10% 键盘高度、10 行、0.1 权重的八字长 Macro 场景共 20 个键。桌面与实际 390 × 844 手机视口各 90 项包围盒宽高均完整，无横向溢出；手机检查使用 CDP Emulation，完成后已清除。最终证据分别为 `text-preview-final-bounds.json`、`text-preview-mobile-bounds.json`。
- light／dark／auto 与 hover／focus-visible 的六种标题按钮状态均为透明背景、0 边框、无阴影，记录在 `text-title-interaction.json`。
- 比例缩放修订后再次运行 182 项 Node 测试全部通过，日志仍为 `text-preview-node-tests.log`；再次复测 Canvas 裁剪像素、主题 ZIP 重导入及 QR PNG 长图生成通过，长图为 816 × 1117，最终证据为 `text-preview-final-assets.json`。网页加载用户主题后的标题截图为 `text-editor-title-fixed.jpg`，字形夹具截图为 `key-glyphs-fixed.jpg`。页面资源缓存标记同步更新为 2107。
- `tests/workspace-browser.py` 已增加对应回归，但本机没有 Python Playwright，未执行该脚本；上述结果来自实际 CUA 浏览器操作。

本次修订仅涉及网页，没有重新构建或安装 Android APK。前文 `ec0b439b…` APK 校验属于原阶段源码，不能作为当前输入法 HEAD `8bb9fe7af7c0d11387fcdf2d00ae1881b043bde6` 的出包证明。

文档门禁分别保存到同一证据目录的 `text-preview-doc-build.log`、`text-preview-doc-links.log`、`text-preview-doc-legacy.log`、`text-preview-doc-catalog.log`、`text-preview-doc-search.log`；最终 diff 检查另存 `text-preview-doc-diff.log`。

比例缩放复核后的文档门禁另存 `text-preview-doc-build-final.log`、`text-preview-doc-links-final.log`、`text-preview-doc-legacy-final.log`、`text-preview-doc-catalog-final.log`、`text-preview-doc-search-final.log`，diff 检查另存 `text-preview-doc-diff-final.log`，保留前轮日志。

## 源码提交

用户于 2026-11-21 核验后授权提交与推送。本轮输入法变更为[同步键盘副标签主题偏好](https://github.com/SandyYuR/fcitx5-android/commit/b50c9dfe1e1b690e8debaa10ef6c5c666ab89a4e)，位于 `fx-rime-only` 分支；网页变更为[对齐输入法预览并完善布局与资源编辑](https://github.com/SandyYuR/f5a-see-me/commit/c67a3003029c1432f39e7a1309f134248e9dccc5)，位于 `site` 分支。本页和用户说明随 `docs` 分支维护。

提交前已核对远端分支和提交差异，验证过的网页、编辑桥与测试源码摘要保持一致。站点部署及 APK 发布状态以各自现有流程的实际结果为准；提交源码没有重新安装本机应用。

首次推送后，网页部署成功，Node 检查通过；远端浏览器回归在 JSON 应用后的大小写断言处失败：普通状态键面正确显示 `k`，测试仍期待原始 JSON 的 `K`。随后[修正普通按键小写预览断言](https://github.com/SandyYuR/f5a-see-me/commit/388da62ab2b3e8e63467c795ee11b9f8cdcc7719)，精确检查主字符，保留 JSON 与属性窗原值。原失败日志保存在本机 `ci-browser-first.log`，没有将失败记为通过。

后续回归通过了草稿、布局、宏、图标等五组操作，又在手机属性窗样例的 `M`／`m` 断言处失败；[区分手机属性原值与键面大小写](https://github.com/SandyYuR/f5a-see-me/commit/fff3d44ab8ea9bee9ef303f1d11168228ab2a871)后，[完整网页检查](https://github.com/SandyYuR/f5a-see-me/actions/runs/44908647752)通过，包含此前本机无法直接运行的 Python Playwright 脚本。两轮原始失败日志分别为 `ci-browser-first.log`、`ci-browser-second.log`。随后[补充异步初始化就绪等待](https://github.com/SandyYuR/f5a-see-me/commit/171e0e15d711a72d4e45100f0edc987c0dec8ec0)，使直接派发事件的回归辅助脚本等待草稿恢复与初始化完成。所有补充仅调整测试，产品源码未改。

输入法提交的[Commit CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/44907014443)通过；本机应用仍未重新安装，前文设备验证范围保持不变。
