# f5a 输入法精简与 Rime 整合实施方案

> 本文是《可行性报告》（`docs/rime-only-feasibility.md`）的落地版：把我的第一手核对与三个并行子代理（插件机制 / rime 插件 / 构建系统）的报告交叉验证后，整理成可直接执行的方案。
> 工作分支：`fx2-rime-only`（自 fx2 @ 3ad25fc9 切出）。基线 commit 3ad25fc9，所有结论附证据位置。

---

## 1. 目标形态

1. **单 APK**：`org.fcitx.fcitx5.android.fx.rime` 一个包搞定，不再需要单独安装 rime 插件 APK（包名与 fx2 产物 `org.fcitx.fcitx5.android.fx` 刻意区分，便于同一设备共存安装试用）。
2. **引擎唯一**：fcitx5 核心之上只有 rime addon（librime.a 内置 librime-lua / librime-octagram 八股文 / librime-predict，全部静态编入，随包携带）。
3. **设置页干净**：拼音词典/码表/自定义短语/标点编辑/其他语言引擎相关页面全部消失；保留 rime 配置（通用 configdesc 渲染）、主题、键盘布局、候选栏等通用项。
4. **fork 特色不丢**：Kawaii 工具栏、键盘布局编辑器、QR 分享、候选注释独立字体、webeditor 等纯 UI 特性原样保留（与引擎零耦合）。
5. **数据无感迁移**：rime 用户数据在 `<外部存储>/data/rime`，与被重装的模板目录分离；覆盖安装后方案、词库、部署产物不受影响。

## 2. 四源调研交叉验证后的关键事实

| # | 事实 | 来源 |
|---|---|---|
| 1 | rime 插件 APK 零 Kotlin/Java/JNI/Activity（27 个文件全是 CMake/资源/manifest），AboutActivity 来自 `:lib:plugin-base` | B（全量 27 文件核对）+ 自查 |
| 2 | 插件加载 = `FCITX_ADDON_DIRS`（app+插件 nativeLibraryDir 冒号拼接）dlopen + descriptor.json 资产合并，无代码注入；主 APK 自身目录本就在 addon 搜索路径 → rime so 打进主 APK 即被发现 | A + B + 自查 |
| 3 | librime 为 prebuilt 静态库（1.12.0），**librime-lua / librime-octagram（八股文）/ librime-predict 内置**（licenses 清单实证），与被裁的 fcitx5-lua/libime/chinese-addons 零关系 | 自查 + B + C |
| 4 | rime addon 官方依赖仅 `core` + 可选 `notifications、dbus`；notifications 由 app 自带 androidnotification 满足，dbus 走 NO_DBUS 分支；与 clipboard/quickphrase/unicode/spell/imselector 零依赖 | B（rime-addon.conf 实证） |
| 5 | rime 用户数据在 `<外部存储>/data/rime`，与模板目录分离；descriptor 同步只重装只读模板 | B（rimeengine.cpp:249-262、native-lib.cpp:549-583） |
| 6 | app 主 APK 本身内置 chinese-addons 全家 + libime + lua（copy-fcitx5-modules 7+1 个 so + customphrase JNI + 预置数据）——裁剪主战场 | C + 自查 |
| 7 | Kotlin 侧对拼音引擎耦合仅 6 文件；设置页大头由 configdesc 自动生成，删引擎自动消失 | C + 自查 |
| 8 | 唯一 native 层依赖 chinese-addons 的其他插件是 jyutping（本就在删除清单） | C |
| 9 | 英文直接输入由 app 内置 androidkeyboard 的 keyboard-us 条目提供，与 chinese-addons 无关 | C（androidkeyboard.cpp:167） |
| 10 | 构建时 `prepare_personal_build.sh` 把 fcitx5-rime 切到 fxliang fork master 并给核心打 alt-trigger 补丁——rime 专属补丁链，全程保留 | C + 自查 |

## 3. 精简与保留总清单

### 3.1 删除项（按层）

**层 1 · 其他引擎插件模块（Phase 1）**

- 模块与目录：`:plugin:anthy / chewing / hangul / jyutping / sayura / thai / unikey / clipboard-filter / text-editor(+language-textmate)`（9 个；jyutping 依赖 libime+chinese-addons，反正要删）
- CI：`fdroid.yml`、`pull_request.yml` 可删；plugins job 在 Phase 2 后整体移除

**层 2 · 主 APK 内置非 rime 引擎（Phase 2）**

- gradle：`:lib:libime`、`:lib:fcitx5-lua`、`:lib:fcitx5-chinese-addons` 三个依赖 + `fcitxComponent.includeLibs` 对应项 + `excludeFiles` 中拼音/码表条目（`app/build.gradle.kts:197-233`）
- app CMake（`app/src/main/cpp/CMakeLists.txt`）：`find_package(libime/fcitx5-lua/fcitx5-chinese-addons)`（:16-25）、`pinyin-customphrase`（:42-45）、`native-lib` 的 `LibIME::*` 链接（:48-62）、copy 列表 7+1 目标（:65-82）、chaizi/pinyinhelper/libime 数据 install（**保留 opencc 那行 :90**）
- native-lib.cpp：词典转换/自定义短语 JNI（:1183-1260）、`LIBIME_MODEL_DIRS` 与 lua 路径环境变量（:552-587）、libime/customphrase 头 include（:36-41）
- Kotlin：`data/pinyin/*`、`data/table/*`、`PinyinDictionaryFragment`、`PinyinCustomPhraseFragment`、`TableInputMethodFragment`、`PunctuationEditorFragment`、`TableFilesSelectionUi`、`SettingsRoute` 对应路由、`PreferenceScreenFactory` 拼音/码表分支、`CustomActionExecutor` 路由表对应项、`ConfigDescriptor.ETy` 拼音/码表分支、MainActivity `.dict/.scel/.txt` 导入 intent-filter
- 数据插件：`plugin/table-data`、`plugin/pinyin-lm` 模块 + 子模块

### 3.2 必须保留

- fcitx5 核心 + androidfrontend/androidkeyboard/androidnotification（rime 的 notifications 依赖靠 androidnotification 满足）。注意：**androidkeyboard 不是「非 rime 输入法引擎」**——它是虚拟键盘的英文直输 addon，提供 `keyboard-us` 条目（`androidkeyboard.cpp:167`），是工具栏一键 EN 切换的落点、密码/数字框直输的通道；rime 内部的 Shift 临时英文与它无关，删掉它会直接破坏中英切换 UX
- 核心 addon：clipboard、quickphrase、unicode、spell（英文联想）、imselector
- **rime 专属挂钩 4 处**：状态区 rime 图标/submode（`StatusIconMapping.kt:44-46`、`TextKeyboard.kt:491-492` 的 `fcitx-rime:` 前缀）、部署/同步按钮（AddonAction → setSubConfig deploy/sync）、Rime 用户数据目录入口（`PreferenceScreenFactory.kt:403-436`）、`saveNonRimeState` 旁路（`native-lib.cpp:445-460`）
- opencc 数据安装 + `usr/share/rime-data/opencc -> usr/share/opencc` 软链（移入 app 的 descriptor symlinks）
- `prepare_personal_build.sh` 补丁链（fcitx5-rime 切 fxliang fork、核心 alt-trigger 补丁、prebuilt 更新）
- opencc/librime-lua/librime-octagram/librime-predict（见 FAQ，已证与裁剪正交）

### 3.3 需要你拍板的决策点

| # | 决策 | 默认建议 |
|---|---|---|
| 1 | 方案档位 | **B**（单 APK + 内置 rime + 删内置拼音链路）；做完 B 后可视心情再决定是否上 C |
| 2 | 语音输入 | 保留（与引擎无关，164 处引用但目录独立） |
| 3 | 剪贴板云同步 | 保留 |
| 4 | 检查更新 | 保留 |
| 5 | mainline flavor | 删除（对个人版无意义） |
| 6 | `:lib:fcitx5-lua` | 删（rime 用的是 librime 内置 lua；无 fcitx5 lua addon 使用方） |
| 7 | spell/en_dict 英文联想 | 保留（收益太小不值得删） |
| 8 | keyboard-us 英文直输条目（androidkeyboard addon） | 保留（EN 一键切换依赖；若坚持 IM 列表只显示 rime，可只隐藏条目，**addon 本体不可删**） |

## 4. 分阶段实施（commit 级）

### Phase 0 · 基线（前置条件）

- `git submodule update --init`（prebuilt 仓库大，约数 GB；Windows 建议 WSL/nix 路径，仓库自带 flake.nix/shell.nix）
- 本地跑通 `:app:assembleFxDebug` 与 `:plugin:rime:assembleFxDebug`，真机安装冒烟，记录现状（截图存档）
- 验证标准：基线 APK 功能与线上版本一致
- **性能基线**（同机、万象同配置）：冷启动→首键可用耗时、首键延迟、连续输入 P95/P99、稳态内存、deploy 耗时；logcat 时间戳 + Perfetto 采集，Phase 2 完成后复测对比（§7）

### Phase 1 = 方案 A（纯减法，约 0.5 天）

Commit 1：删 9 个其他引擎插件模块（settings.gradle.kts + 目录 + .gitmodules + git rm --cached）
Commit 2：删 mainline flavor、无 Fx 前缀任务别名与 APK 兼容拷贝（app/build.gradle.kts:98-159、161-164）
Commit 3：CI 精简（删 fdroid.yml/pull_request.yml、mainline job）

验证：CI 绿 + APK 正常输入。此阶段主 APK 不变，可随时整组合并回 fx2。

### Phase 2 = 方案 B（核心，1–2 天 + 真机回归）

按 commit 拆分，每步独立可回滚：

1. **rime 并入主 APK**：
   - `app/src/main/cpp/CMakeLists.txt` 增补：librime 静态导入（照抄 `plugin/rime/src/main/cpp/CMakeLists.txt:17-38`，含 WHOLE_ARCHIVE）+ `add_subdirectory(fcitx5-rime)` + rime-data/default.yaml install 列表（:39-58）
   - opencc 软链移入 app `generateDataDescriptor.symlinks`；`plugin/rime/licenses/libraries/*.json` 迁入 app 的 aboutlibraries 目录（关于页许可不缺失）
   - `native-lib.cpp:605-609` 手动注册 `fcitx5-rime` 翻译域（extDomains 为空后翻译不失效）
   - 删 `:plugin:rime` 模块（settings.gradle.kts + 目录 + manifest 兼容 action 引用）
2. **删内置拼音 native 链路**：app CMakeLists find_package/copy 列表/pinyin-customphrase/LibIME 链接、native-lib.cpp 词典 JNI（:1183-1260）与 `LIBIME_MODEL_DIRS`/lua 环境变量（:552-587）、libime 头 include（:36-41）；**保留 opencc install**
3. **删 gradle 依赖与 lib 模块**：`:lib:libime`、`:lib:fcitx5-lua`、`:lib:fcitx5-chinese-addons`（settings.gradle.kts + app/build.gradle.kts + `.gitmodules`）+ 删 `plugin/table-data`、`plugin/pinyin-lm`
4. **删 Kotlin 拼音/码表 UI**：`data/pinyin`、`data/table` 包、4 个 Fragment、`SettingsRoute`/`PreferenceScreenFactory`/`ConfigDescriptor.ETy`/`CustomActionExecutor` 对应分支、manifest 导入 intent-filter
5. **收尾**：`PluginFragment` 隐藏并从主设置页移除入口；翻译域注册 `fcitx5-rime`（§2 融合点①）

### Phase 3 = 方案 C（可选，+1 天）

- 删插件框架：`DataManager.detectPlugins`/签名白名单、`PluginFragment`、manifest `<queries>`/ORIGINAL_* 兼容层、`lib/plugin-base`、`lib/common` 插件运行时（`FcitxPluginService`/`PluginMessage`/ClearUrls；**保留** VoiceInputIpc/FcitxRemoteConnection）
- 按你对三个决策点的结论删/留语音、同步、更新功能
- 代价：与上游 rebase 冲突面变大——把删除做成清晰 commit 序列

## 5. 验收清单（Phase 2 完成标准）

1. 单 APK；启动后 IM 列表只出现 rime schema；
2. **带 lua 脚本 + grammar（八股文）的方案（如万象）出候选正常**；简繁切换（opencc）正常；
3. rime 部署/同步按钮、用户数据目录入口、状态区 rime 图标/submode 正常；
4. `saveNonRimeState` 不回归（设置页退出不触发 rime 同步）；
5. 设置页无拼音/码表/自定义短语/标点编辑/词典导入残留入口；
6. 覆盖安装到装有旧版 app + rime 插件的设备：rime 用户数据（词库、用户方案）完好；
7. 英文键位、剪贴板、快捷短语等通用功能正常。

## 6. 风险登记册（调研后已收敛）

| 风险 | 等级 | 对策 |
|---|---|---|
| rime 用户数据丢失 | **低**（已实证目录分离） | Phase 2 真机回归确认一次 |
| 翻译域失效（rime 设置中文变英文） | 低 | 实施清单第 1/5 步显式注册 fcitx5-rime 域 |
| opencc 数据误删 | 低 | 已在 CMake 清单标注「必须保留」行 |
| fcitx5 核心补丁失效 | 低 | `prepare_personal_build.sh` 与裁剪正交，不动 |
| profile-manager 原生实现来源 | 低 | 来自 fxliang fork（构建时浮动切换），子模块链路保持现状 |
| 上游同步成本 | 中 | 删除动作拆小 commit；或定位为长期独立分支 |
| 体积/行为回归 | 低 | Phase 2 出包后对比 APK 大小并跑验收清单 |

## 7. 性能视角：精简解决「轻」，不直接解决「快」

> 动机校准：若主要痛点是「打字不够流畅」，需要明确本次精简在性能上的作用边界，避免产生错误预期。

**能改善（启动 / 内存 / 体积）**

- 启动即载的 addon 集变小：`androidkeyboard` 无 OnDemand 标记（`androidkeyboard.conf.in.in`，启动即载）；quickphrase / unicode / clipboard 被 `native-lib.cpp:92-94` 启动时显式拉起；chinese-addons / libime 删除后少 dlopen 若干 .so
- 内存峰值与 APK 体积下降：不再携带拼音词典 / 模型与转换工具链

**不改变（逐键流畅度）**

- rime 激活时，拼音 / 码表引擎不在击键路径上，删除它们对逐键延迟收益 ≈ 0
- UI / JNI 管线不变：按键仍是 KeyEvent → JNI → fcitx5 core → rime → 候选跨 JNI → Kotlin 刷新候选栏
- 万象自身的逐键成本与本仓库无关：万象官方性能报告实测 ProcessKey P50=0.48ms、P95=14.3ms、P99=36.4ms、max≈68ms；TransSeg（翻译+过滤）占作文耗时 98%，是万象侧唯一值得优化的环节（`rime-wanxiang/docs/doc/profile-analysis.md`）

**结论与动作**

- 精简的价值在「轻」（体积 / 启动 / 内存 / 维护面）；「快」需要测量后另立专项
- Phase 0 增加性能基线（见 §4 Phase 0）；同机、同万象配置与 Trime（同文）对照跑同一组指标，定位差距在 shell（UI / 分发层）还是 schema（lua / grammar）
- 若差距在 schema：优化方向是万象的 rime 配置（lua 插件开关、grammar 权衡），与本仓库代码无关
- 若差距在 UI 层：候选栏增量刷新、JNI 批量传递等 app 改造属于新专项，工作量另估，不并入本方案

## 8. 工作量与收益小结

- Phase 0：半天（含大仓库子模块下载）
- Phase 1：0.5 天 · Phase 2：1–2 天（含真机回归）· Phase 3：+1 天（可选）
- 收益：单 APK、体积净减（chinese-addons/libime/lua 出、librime 入）、启动少加载 8+ addon、设置页只剩 rime 相关、免装第二个 APK、CI 三合一、仓库少 11 个插件模块与 15+ 子模块。

## 9. 待你确认（开工前置）

1. 方案档位（默认推荐 B，做完可评估是否继续 C）；
2. 语音输入 / 剪贴板同步 / 检查更新：留 or 删（默认全留）；
3. mainline flavor 删除（默认删）；
4. 允许我执行 `git submodule update --init`（prebuilt 较大）并开始 Phase 0。

---

## 附录 A：证据索引（按来源）

- 自查 + 交叉验证：`docs/rime-only-feasibility.md`（本分支 4 个 commit）
- 子代理 A（插件机制）：`DataManager.kt` 全链路、`FcitxPluginServices.kt`、plugin-base manifest 合并、无插件 fallback（TextKeyboard default 布局 / Not Available 占位）、内置 addon 清单
- 子代理 B（rime 模块）：librime.a prebuilt（Rime 1.12.0 + lua/octagram/predict 内置）、rime-addon.conf 依赖声明、用户数据目录 `<extFiles>/data/rime`、翻译域注册、5 个耦合点、profile-manager 出自 fxliang fork 的判断
- 子代理 C（构建系统）：mainline flavor 可删、jyutping 依赖、CI 目录遍历免改、keyboard-us 独立、opencc 陷阱、native-lib JNI/环境变量清单、fork 功能四层分类
- 本轮补充：万象官方性能报告（`rime-wanxiang/docs/doc/profile-analysis.md`）、`androidkeyboard.conf.in.in` 无 OnDemand（启动即载）、`notifications.conf.in.in` OnDemand=True、`native-lib.cpp:92-94` 启动显式拉起 quickphrase/unicode/clipboard

## 附录 B：与可行性报告的差异（本方案 refinements）

1. Phase 1 暂时保留 table-data/pinyin-lm（它们是内置拼音的数据源，随 Phase 2 一起删更干净）；
2. Phase 2 增加 aboutlibraries 许可清单迁移（plugin/rime/licenses → app）；
3. Phase 2 增加翻译域注册与 4 处 rime 挂钩的显式清单（来自 B 的深挖）；
4. 验收清单补 lua/grammar/opencc 三条实测。
## 附录 C：同文（Trime）按键管线调研（对照 fcitx5-android）

调研版本：osfans/trime `develop` 分支。

**Trime 逐键流程**：

1. 入口：软键 → `ime/keyboard` 触点 → `postRimeJob{ processKey }`；硬键 → `onKeyDown/onKeyUp → forwardKeyEvent`（`TrimeInputMethodService.kt:717-744`）
2. 线程：所有 rime 调用走单线程执行器 `rime-main`（`RimeDispatcher.kt:68-101`）——**注释明言 adapted from fcitx5-android 的 FcitxDispatcher**，即两家的线程模型同源
3. 处理（`Rime.kt:317-349` processKeyInner）：`getRimeStatus`（En 提示）→ `processRimeKey`（JNI 直调 librime process_key，`rime_jni.cc:298-303`）→ `emitResponse`
4. **响应打包**：`getRimeResponse(pagingMode)`（`rime_jni.cc:423-455`）一次 JNI 调用返回 commit + composition + 候选（paged/bulk）+ status；源码注释：paging 模式下布局选项在服务端一并查询，「避免每键额外一次 rime option 往返」
5. 分发：`MutableSharedFlow<RimeMessage>`（buffer 15, DROP_OLDEST，`Rime.kt:474-478`）→ UI 收集；候选渲染为 RecyclerView（`ime/candidates/CandidateViewHolder.kt`）

**fcitx5-android 逐键流程（对照）**：

1. 入口：`TextKeyboard.kt:1285 onAction` → `Fcitx.kt:83 sendKey` → JNI → `androidfrontend.cpp:376-388 AndroidFrontend::keyEvent` → `activeIC_->keyEvent()`
2. 中间层：**fcitx5 core 事件分发**（addon 链、输入上下文状态机、IM 管理）→ fcitx5-rime addon → librime
3. 事件回流：native 侧为每种事件单独 JNI 推送（`native-lib.cpp:622-727`，commit/preedit/候选/状态等十余种）→ `FcitxEvent.kt` SharedFlow → UI 组件各自收集
4. 未被消费的按键经 `keyEventCallback` 回 Kotlin，生成 Android KeyEvent 发给编辑器（`FcitxInputMethodService.kt:877-899`）

**结论**：

- 两家**相同**：线程模型（同源的单原生线程执行器）、候选栏渲染技术（同为 RecyclerView）、librime 及 lua/octagram 插件捆绑
- 两家**不同**：①fcitx5-android 每键多穿过一整层 fcitx5 core 分发；②每键事件推送为多条独立 JNI 消息，Trime 为单次打包响应（Trime 在 `rime_jni.cc:438-444` 明确做过此优化）③librime 版本与编译方式（prebuilt 1.12.0 vs 源码构建）
- 因此「同文更流畅」的候选解释排序：①每键 marshalling/事件次数差异（多事件 vs 单包）②fcitx5 分发链长度 ③librime 版本差异。均为 µs–ms 级，需 §7 的 Perfetto 基线实测确认
- **可借鉴的具体优化**：在 native 侧把每次按键产生的多条 fcitx 事件合并为一次批量 JNI 投递（模仿 Trime 的 RimeResponse 打包），列为性能专项候选——这是架构内可实现的，不需要抛弃 fcitx5 core（详见附录 D 的完整优化清单）

## 附录 D：打字跟手性（快速连击流畅度）优化研究

逐键延迟链路（全部为本仓库实测代码锚点）：

1. **DOWN 即时反馈（无短板）**：`CustomGestureView.kt:168-175` 同步执行高亮、hotspot、触觉、音效
2. **UP 触发**：`CustomGestureView.kt:204-227` ACTION_UP → performClick（保证滑动/长按语义，不可提前）→ `TextKeyboard.kt:1285 onAction` → `CommonKeyActionListener.kt:134 postFcitxJob{ sendKey }`
3. **引擎段**：postFcitxJob 顺序队列（`FcitxInputMethodService.kt:422-428`）→ FcitxDispatcher 单线程 → JNI → fcitx5 core → rime（万象实测 P50 0.48ms / 触发作文均值 3.9ms）
4. **事件回流**：native 每事件独立 JNI 推送（`native-lib.cpp:622-727`，commit/preedit/候选/状态）→ `Fcitx.kt:402-406` tryEmit（eventFlow buffer 15 DROP_OLDEST）
5. **主线程 UI 段（短板集中区）**：候选更新 → 快照比对（`HorizontalCandidateComponent.kt:328-334`，每键两次 asList 分配）→ `HorizontalCandidateViewAdapter.kt:79 notifyDataSetChanged()` **全量重绑** → FlexboxLayoutManager **双布局**（默认 AutoFillWidth 且候选数 < maxSpanCount 时 `secondLayoutPassNeeded=true`，`HorizontalCandidateComponent.kt:357-362`；AppPrefs.kt:348-352 默认值即 AutoFillWidth）→ `view.post{ ensureActiveCandidateVisible }`（:371-373）**再推迟一帧**；legacy CandidateListEvent 路径还额外 post 一层（:273-278）

**已确认瓶颈（按影响排序）**：

| # | 瓶颈 | 证据 | 影响 |
|---|---|---|---|
| 1 | 候选栏每键 notifyDataSetChanged 全量重绑 | HorizontalCandidateViewAdapter.kt:59-80 | 每键重绑全部可见 ViewHolder；连击时主线程负载线性叠加 |
| 2 | Flexbox 双布局 | HorizontalCandidateComponent.kt:357-362 | 默认配置下常见场景每键两次 measure/layout |
| 3 | 叠加的 view.post 帧延迟 | :273-278（legacy post）+ :371-373（ensureVisible post） | 候选显示最坏晚 1-2 帧（16-33ms），直接伤害跟手感 |
| 4 | 每键多条事件各自唤醒主线程 | native-lib.cpp:622-727 | 线程切换与分配放大（附录 C 的 native 打包方案） |

**优化项清单**：

- **P0 候选栏细粒度刷新**：HorizontalCandidateViewAdapter 改前后缀 diff（notifyItemRangeInserted/Removed/Changed），复用已有 stableIds 与 contentEquals 守卫；改动局限于一个 adapter 类
- **P1 消除双布局**：AutoFillWidth 下预计算并缓存候选宽度（text+font 为 key），省掉第二次 measure/layout
- **P1 压缩帧延迟**：ensureActiveCandidateVisible 合并进同一次布局 pass（RecyclerView.OnLayoutCompletedListener）；legacy 路径在主线程时同步执行
- **P2 native 事件合并**：每键的 commit/preedit/候选/状态在 native 侧打包为一次 JNI 投递（模仿 Trime `getRimeResponse` 的单包响应，见附录 C）
- **P2 分配削减**：asList() 快照比对改数组 contentEquals；CandidateWord 缓存复用

**测量方案（并入 Phase 0 基线）**：androidx.tracing 埋点点位——`KeyView ACTION_UP`、`sendKey 进入 JNI`、`fcitx 事件到达 HandleFcitxEvent`、`主线程收集开始/结束`、`notifyDataSetChanged`；Perfetto 中量化「UP→候选可见」的帧延迟分布（P50/P95）。埋点已落地（commit `a6edbc27`），Perfetto 采集需真机。

**P2 native 事件合并评估结论（暂缓，测量门控）**：与 Trime 不同，fcitx5-android 的 commit/preedit/候选/状态事件来自注册进 fcitx5 core 的回调（`native-lib.cpp:583-659`），由 core 在按键处理过程中自行触发，native 侧不存在现成的「每键响应包」可打包。实现单包投递需要：① native 增加按按键会话的事件缓冲与冲刷点（sendKey JNI 返回边界）；② 新增复合 JNI 事件类型与 Kotlin 拆包层，并保持子事件顺序语义（commit 与候选的先后顺序并非固定）；③ 回归验证全部事件消费方。预期收益仅为每键 ~3 次 JNI 边界跨越与少量 vararg 数组分配（µs 级，见 §7 分析），且 §7 已将「JNI 批量传递」划为新专项（不并入本方案）。结论：待 Phase 0 真机 Perfetto 基线证明该段占比可观后再立项实施。

**明确不做**：字母键 fire-on-DOWN（破坏滑动/长按语义）；关闭 UP 触觉反馈（行为偏好）；重写 Compose/View 层（收益不成比例）。
