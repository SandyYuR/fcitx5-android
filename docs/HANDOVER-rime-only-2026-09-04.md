# 交接报告 — fx2-rime-fusion（rime 专版）2026-09-04

> 本文写给接手的下一个代理。仓库 `SandyYuR/fcitx5-android`（fcitx5-android 的 fork）。
> 目标分支 **`fx2-rime-fusion`**，基线 **`fx2`**（`3ad25fc9`，**必须保持不动**）。
> 本文只描述"领先 fx2 的全部改动"与"未完成的工作"。
>
> **2026-09-04 晚更新**：分支已把 `review-fx2-fixes` 的 6 个新提交 rebase 到本分支所有改动**之前**，
> 因此本分支 **37 个提交的 SHA 全部改写**、已 force-push。第 2 节有新旧对照与新 SHA。

---

## 0. 上手前必读（会踩的坑）

| 事项 | 说明 |
|---|---|
| 工作区分支 | `D:\GitHub\code review` 当前已 checkout `fx2-rime-fusion`（与 origin 同步）。动手前先 `git status` 确认。 |
| 存在第二个 worktree | `D:/GitHub/code review-fx2` → `codex/fx2-nonblocking-font-coldstart`。不要在两个 worktree 里同时切同一分支。 |
| 未跟踪文件 | 根目录有 `_myreview.diff`、`org.fcitx.fcitx5.android.fx.rime-2026-09-04T09_05_50Z.txt`（用户给的 logcat）。不要提交它们。 |
| 子模块未初始化 | 本地 `lib/fcitx5/src/main/cpp/fcitx5`、`plugin/rime/src/main/cpp/fcitx5-rime` 等都是空的 gitlink，`git grep` 查不到内容。要看源码用 jsDelivr：`https://cdn.jsdelivr.net/gh/<owner>/<repo>@<sha>/<path>?x=N`（`?x=N` 变化用于绕缓存；raw.githubusercontent 拉大文件会超 30s 工具超时）。 |
| **CI 实际构建的 rime 源码不是仓库 pin 的那个** | `prepare_personal_build.sh` 会把 `fcitx5-rime` 切到 **fxliang/fcitx5-rime@master**、`prebuilt` 切到 **fxliang/prebuilt@master**，并给 fcitx5 打 `fcitx5-alt-trigger-v4point1.patch`。所以看 rime 行为要读 **fxliang fork**，仓库里 pin 的 `4e996319`（上游 bump version）只是占位。 |
| grep 工具在仓库根会报错 | `app/src/main/play/listings/en-US/graphics/icon/icon.png` 是坏条目（os error 2），`grep` 传 `path=<repo root>` 会 exit 2。用 `git grep`（走 pwsh）或把 `path` 缩小到子目录。 |
| read 工具 | `offset`/`limit` 必须是明确数字（传 undefined 会报 "binding arguments must be lossless JSON"），`limit` ≤ 2000。 |
| GitHub API 返回会被截断 | `/actions/runs` 的 JSON 超 100000 字符会 `Unterminated string`。用 `per_page=3` 或正则抽 token，别整体 `JSON.parse`。 |

---

## 1. 用户给的长期约定（必须遵守）

原话：**「全部做，从 fx2 分支复制到另一个分支，在新复制的分支上面改动，每改好一处就推送上去一次，手动触发一次 ci，但是不要 release」**

落实为每次改动的固定流程：

1. 一处改动 = **一个独立提交**，提交信息用**中文**；
2. `git push origin fx2-rime-fusion` —— push 本身就会触发 CI（`.github/workflows/ci.yml` 的 `on: push: branches: ['*']`），**不需要**额外 `workflow_dispatch`；
3. 确认 CI 绿 + 有产物；
4. **绝对不要创建 release / tag**（分支上的 nightly release job 已经删掉了，别加回来）；
5. `fx2`、`review-fx2-fixes`、`review-fx2-fixes-split`、`backup/*` 全部**不要动**；
6. 已 push 的提交**不要 amend**，新改动开新提交。

CI 事实：
- workflow 名 `Commit CI`，唯一 job `build_commit`，`ubuntu-22.04` × `arm64-v8a`；
- 构建命令 `./gradlew :app:assembleFxRelease`，约 10 分钟；
- 产物 artifact 名 `app-ubuntu-22.04-arm64-v8a`，路径 `app/build/outputs/apk/fx/release/*.apk`；
- 失败时有 "Emit compile errors as annotations" 步骤把 `e: `/`error: ` 行输出成 annotation；
- 路径过滤排除 `*.md` 与 `docs/**`，所以**纯文档提交不会触发 CI**（含本文件）。

---

## 2. 分支现状

```
fx2                3ad25fc9  ← 基线，未改动
fx2-rime-fusion    01c1070e  ← 工作分支，= origin/fx2-rime-fusion（已同步）
                             领先 fx2 共 138 个提交，线性历史
                             761 files changed, +6869 / -37531
```

历史结构（自底向上）：
```
fx2 3ad25fc9
  └─ 95 个提交（审查修复 + 性能，与 review-fx2-fixes-split 共有，止于 85de19be）
      └─ 6 个 review-fx2-fixes 新提交  5fdcb0db 763f4dcf 9c411dc1 f3abf5b3 920fed60 3ec1f6b2
          └─ 37 个 rime 专版提交（SHA 已改写）  5d90019d … 01c1070e
```

**rebase（2026-09-04 晚）**：应用户要求把 `review-fx2-fixes` 的 6 个提交插到本分支改动之前，做法
`git rebase --onto review-fx2-fixes 85de19be fx2-rime-fusion`，随后
`git push --force-with-lease`。旧 tip `281082cb` 保存在
**`backup/fx2-rime-fusion-pre-rebase-20260904`**（本地分支，未推送）。
只有 1 处冲突：`BaseInputView.kt` 的 `setupFcitxEventHandler()`，`3ec1f6b2`（C31 断连兜底
`try/catch FcitxDisconnectedException`）与 `be92f13a`（Phase 0 `trace("collectFcitxEvent")`）
改同一段——已**两者都保留**（先 try 取 flow，再在 `events.collect` 内部包 trace）。
其余 13 个文件全部自动合并。已验证：`git diff 旧tip 新tip` 恰好等于那 6 个提交的内容
（14 文件 +590/-88），无冲突标记残留。

常用新旧 SHA 对照（其余按提交标题一一对应，标题未改）：

| 提交标题 | 旧 SHA | 新 SHA |
|---|---|---|
| docs: rime 专版交接报告（2026-09-04） | `281082cb` | `01c1070e` |
| 移除 quickphrase 快速短语组件 | `54706725` | `cefc481b` |
| fix: 首次启动时预置 rime 为默认启用输入法 | `b3da4853` | `dc0db868` |
| 移除 androidkeyboard/imselector/spell/unicode 组件 | `03a70215` | `072c0e87` |
| feat: 包名改为 `...fx.rime` | `eaa85316` | `6be71bf5` |
| perf: Phase 0 性能埋点 (androidx.tracing) | `be92f13a` | `bb3a578b`（含冲突解决） |
| feat: 将 fcitx5-rime 并入主 APK | `b7742505` | `127e7924` |

其他分支（都别碰）：`review-fx2-fixes` `3ec1f6b2`、`review-fx2-fixes-split` `85de19be`、`backup/fx2-rime-fusion-pre-merge` `6129c833`、`backup/fx2-rime-fusion-pre-rebase-20260904` `281082cb`（都等用户确认后再删）、`backup/fx2-local`、`fx`、`review-fx`、`fx2-rime-only`。

已验证 CI 状态（GitHub Actions）—— 注意这些是 **rebase 前的旧 SHA** 的结果；rebase 后新 SHA 的
CI 由 force-push 触发，接手时用 `gh`/API 复查 `01c1070e`：

| 旧提交 | 说明 | CI |
|---|---|---|
| `54706725` | 移除 quickphrase 快速短语组件 | ✅ success |
| `b3da4853` | 首次启动时预置 rime 为默认启用输入法 | ✅ success |
| `03a70215` | 移除 androidkeyboard/imselector/spell/unicode 组件 | ✅ success |
| `eaa85316` | 包名改为 `...fx.rime` | ✅ success |

---

## 3. 领先 fx2 的 138 个提交（按主题归类）

### 3.1 精简为 rime 专版（本轮核心，约 20 提交）

- `b7742505` **把 fcitx5-rime 并入主 APK**：librime 静态链接 + rime-data 资源 + opencc 软链。rime 从"插件 APK"变成主包内置 addon。
- `2b5f71ae` 删内置拼音 native 链路（libime / pinyin / table / customphrase），**保留 opencc**。
- `a0966f68` 删 gradle 依赖与 lib 模块（`lib/libime`、`lib/fcitx5-lua`、`lib/fcitx5-chinese-addons`、`plugin/pinyin-lm`、`plugin/table-data`），CI 去掉 plugins 构建。
- `24b74492` 删拼音/码表 UI（`data/pinyin`、`data/table`、相关 Fragment/Route、JNI GlobalRef 注册、AIDL `reloadPinyinDict`）。
- `68059b76` 删 9 个其他语言/功能插件模块（anthy/chewing/hangul/jyutping/sayura/thai/unikey/text-editor/clipboard-filter）。
- `0916d513` `f06f77a2` `03d05aa2` `8e81536f` 删整套插件检测/运行时框架：`DataManager.detectPlugins`、签名白名单、`PluginFragment`、`FcitxPluginServices`、`lib/plugin-base`、`FcitxPluginService`/`PluginMessage`/`ClearUrlsPluginRuntime`（`MainService` 改继承 `Service`，出站过滤走 `HostClipboardFilter`）。
- `826657d3` 移除 mainline flavor 及其任务别名/APK 兼容拷贝。
- `576d543c` `1a2d7d5f` `39c60cf9` `63d41b69` CI 精简：只留 `ci.yml`（删 fdroid/pull_request/nix/publish），删 nightly release 与 mainline 构建，编译错误输出成 annotations。
- `eaa85316` **包名 `org.fcitx.fcitx5.android.fx.rime`**（`appIdFxSuffix = ".fx.rime"`），可与 fx2 并存安装；APK 文件名同步替换。
- `03a70215` `b3da4853` `54706725` — **今天这三个是本轮任务的产出，详见第 4 节**。

### 3.2 文档（不触发 CI）

`7933d505` `0b079cf6` `22accb8c` `cc504a58` `4ad2855d` `6fc12079` `897dc60a` `77aa1447` `db56acd4` — 现存两份：
- `docs/rime-only-feasibility.md`：Rime 专用化裁剪可行性报告；
- `docs/rime-integration-plan.md`：实施方案（含附录 C 按键管线调研、附录 D 打字跟手性研究）。
- ⚠️ 这两份文档里早期"建议保留 androidkeyboard/unicode/spell/imselector"的结论**已在 `03a70215` 里加注推翻**，读的时候注意注解。

### 3.3 性能（perf，约 35 提交）

Phase 0 埋点（`be92f13a`，androidx.tracing）；候选栏路径：`f0487b40`(P1a 预计算宽度省第二次 measure) `01192969`(P1b 去多余帧延迟) `03c60934`(P2 削减每键分配) `cf5aa7ef`(前后缀 diff 替代 notifyDataSetChanged) `58b952db`(增量刷新去递归 view.post)；模糊/水波纹 `39f880cb` `9c588066` `5a184f4e`；主线程搬迁一大批 `d95e7ded` `be655834` `8711cbe0` `b5a905d6` `4f3d52fa` `c2cdb022` `3e03523b` `5f1af44b` `2c0fc33c` `ec81b4b8` `9b7b3146` `bb2bf7ae`；列表/视图复用 `1ab77310` `4d0e9521` `01f565d2` `386cc951` `39bef309` `aee75076`；网络 `6d891ce7` `141eb7e7` `bdfe8a38` `79dcb15d` `2150103e`。
`a9a64d5d` 记录了"P2 native 事件合并"评估结论：**暂缓，需测量门控**。

### 3.4 修复（fix，约 75 提交）

带编号（A/B/C/D/E/F/G + 数字）对应一次代码审查清单，覆盖：布局 JSON 健壮性（A1/A2/A3/A5/C22）、图标主题与 ZIP 上限（A6/A7/A10/8711cbe0）、备份与迁移（B5–B9/B12/fefa55f2）、编辑器 Activity 生命周期（B1/B2/B11/B3/B4/59d7b099）、语音输入（C1–C6/62049fc6）、按键与弹窗（C7–C14/C24/6824a935）、剪贴板同步与 HTTP 服务（C16–C21/C28/F7/F8/A8/d5832dea）、泄漏（F1/F4/F5/E10/92c21e59）、`1655bebe`（FlexboxLayoutManager `onLayoutCompleted` 消费 `pendingEnsureVisible`）、`7f6875db`（删悬空 `@Volatile`）、`6fc66749`（`MainService.onBind` 空实现）、`04bde6f7`（删残留 `callingPackage`）、`c1cbb213`（注册未声明 Activity）。
测试：`587ca235` 修 `ThemeSerializationTest`；`63d41b69` 启用 `testDebugUnitTest`（注意 CI 现在**没有**跑单测步骤，只有 `assembleFxRelease`）。

---

## 4. 今天这一轮的四项任务（用户原话与进度）

用户原话：
> 「去掉附加组件里面的 Android 英文键盘，输入法选择器，拼写，unicode。输入法安装就是默认启用 rime，且只有 rime 可用，其他无关组件都清除。默认键盘就是 rime 的 default 键盘，语言切换键改成按下发送一次 shift 点击事件，利用 ascii mode 来做到切换中英文输入」

追加：
> 「快速输入组件也去掉」

拆成四项：

### ✅ 任务 1 — 清除无关组件（`03a70215` + `54706725`，均已 push、CI 绿）

**`03a70215` 移除 androidkeyboard / imselector / spell / unicode：**
- `app/build.gradle.kts`：cmake targets 去掉 `"androidkeyboard"`；新增 `fcitxComponent { excludeFiles = [...] }` 排除 `imselector.conf`/`spell.conf`/`unicode.conf`。
- `app/src/main/cpp/CMakeLists.txt`：删 `add_subdirectory(androidkeyboard)`、`Fcitx5::Module::Unicode` 链接、`copy-fcitx5-modules` 里 imselector/spell/unicode 的拷贝、spell 词典 install。
- 删目录 `app/src/main/cpp/androidkeyboard/`（4 个文件）。
- `lib/fcitx5/build.gradle.kts`：去掉 imselector/spell/unicode 的 cmake target 与 prefab。
- `native-lib.cpp`：删 unicode include / `p_unicode` / `triggerUnicode()` / JNI。
- Kotlin：`Fcitx.kt`/`FcitxAPI.kt` 删 `triggerUnicode`，`CommonKeyActionListener.kt`/`KeyAction.kt` 删 `UnicodeAction`，`KeyDefPreset.kt` 删 unicode 长按与逗号键弹窗里的 Unicode 项。

**`54706725` 移除 quickphrase（37 文件，-1161 行）：**
- 构建：`lib/fcitx5/build.gradle.kts` 去 target+prefab；`app/src/main/cpp/CMakeLists.txt` 去 `Fcitx5::Module::QuickPhrase` 链接与 `fcitx5::quickphrase` 拷贝；`app/build.gradle.kts` `excludeFiles` 增加 `quickphrase.conf` 与 `usr/share/fcitx5/data/quickphrase.d/{emoji,emoji-eac,latex}.mb`。
- native：`native-lib.cpp` 删 include / `p_quickphrase` / `triggerQuickPhrase()` / `triggerQuickPhraseInput` JNI（6 处）。
- Kotlin 删除：`data/quickphrase/`（7 文件）、`QuickPhraseEditFragment.kt`、`QuickPhraseListFragment.kt`。
- Kotlin 改动：`Fcitx.kt`、`FcitxAPI.kt`、`AddonSubconfig.kt`（删 `reloadQuickPhrase`）、`FcitxRemoteService.kt`、`CommonKeyActionListener.kt`、`KeyAction.kt`（删 `QuickPhraseAction`）、`KeyDefPreset.kt`（删 `QuickPhraseKey` 与逗号弹窗项）、`TextKeyboard.kt`（`SpecialKeyViews` 去 quickphrase 字段，6 处）、`PreferenceScreenFactory.kt`、`SettingsRoute.kt`（删 `QuickPhraseList`/`QuickPhraseEdit` 路由）、`ConfigDescriptor.kt`（`ETy` 去 `QuickPhrase`，去 `"QuickPhrase","Editor"` 映射）、`CustomActionExecutor.kt`（`ROUTE_MAP` 去 `quick_phrase_list`）、`IconTheme.kt`（去 `keys.quickphrase` 槽位）、`MacroEditorActivity.kt`（去动作 id/标签/映射 3 处）。
- 资源：`values/keyboard_26_ids.xml` 去 `button_quickphrase`；`values/strings.xml` 去 6 条；7 个语言目录（de/es/ja/ko/ru/zh-rCN/zh-rTW）各去对应条目。
- AIDL：`IFcitxRemoteService.aidl` 去 `reloadQuickPhrase()`。

**故意保留的无害残留**（别再动）：`IconThemeEditorActivity.kt:617` 的 `"keys.quickphrase" -> R.drawable.ic_baseline_format_quote_24` 图标映射、`lib/fcitx5/.../cmake/FindFcitx5Module.cmake:6` 的 `FCITX5_MODULE_NAMES` 列表（只是接口别名工厂，列了不等于构建）、`app/src/main/play/release-notes/*.txt` 里的历史发布说明。

### ✅ 任务 2 — 首次启动只启用 rime（`b3da4853`，已 push、CI 绿）

`app/src/main/java/.../core/Fcitx.kt` 的 `onFirstRun()` 加：

```kotlin
runCatching { setEnabledInputMethods(arrayOf("rime")) }
    .onFailure { Timber.w(it, "Failed to seed rime as the default input method") }
```

**为什么必须加**：fcitx5 核心 `Instance::buildDefaultGroup()` 在全新配置时会无条件塞一个 `keyboard-us` 条目，而本分支已经把 androidkeyboard addon 删了 → 该条目指向不存在的 IM，`listInputMethods()` 会返回空条目、有崩溃风险。
**为什么放这里是安全的**：`Instance::initialize()` 里 addon/IM 条目加载发生在 `ReadyEvent` 之前，`onFirstRun()`（由 `AppPrefs.internal.firstRun` 门控）执行时 IM 列表已就绪。
副作用：日志里会有一条无害的 `instance.cpp:1454 Couldn't find keyboard-us`。

### ✅ 任务 3 — 默认键盘 = rime 的 default 键盘（**无需改代码，已确认**）

没有用户布局 json 时（`ConfigProviders`/`UserConfigFiles` 返回 null），`TextKeyboard.getLayout()` 会落到代码内置的 `getDefaultLayout(showLangSwitch)`——就是标准 QWERTY，且在 `showLangSwitchKey`（默认 true）时带 `LanguageKey`。**已经是要求的状态，不要为此改动任何文件。**

### ⬜ 任务 4 — 语言切换键改成"发一次 Shift 点击"（**未开始，下一个接手的从这里做**）

目标：按一下语言键 = 向 rime 发一次独立的 Shift 按下+抬起，靠用户 rime 配置里的 `ascii_mode` 切中英；同时删掉现在那个"语言切换键行为"偏好（三选一：枚举/切换激活/切下一个输入法 App），因为只剩 rime 一个 IM，这个偏好没意义。

**改动清单（行号基于 rebase 后的 `fx2-rime-fusion@01c1070e`，已复核）**

1. `app/src/main/java/.../input/keyboard/CommonKeyActionListener.kt`
   - `:65` 删 `private val langSwitchKeyBehavior by kbdPrefs.langSwitchKeyBehavior`
   - `:155-177` 把 `is LangSwitchAction -> { when (langSwitchKeyBehavior) { ... } }` 整段换成发一次 Shift 点击（`:178` 是 `is ShowInputMethodPickerAction`，到这行为止）
   - 清理随之不用的 import（`AddMoreInputMethodsPrompt`、`InputMethodUtil`、`switchToNextIME`——**动手前用 grep 确认文件内确实没有别的用处**；`InputMethodPickerDialog` 要留，长按还用）
2. `app/src/main/java/.../input/action/ButtonAction.kt`（状态栏/工具栏上的"语言切换"按钮）
   - `:26` 删 `import ...LangSwitchBehavior`
   - `:359-378` `LanguageSwitchAction.execute()` 里的 `when (behavior)`（`:359` 取 pref，`:375` 是最后一个分支 `NextInputMethodApp`）换成同一套 Shift 点击逻辑
   - `onLongPress` 保持不变（仍弹 `InputMethodPickerDialog`）
3. 删文件 `app/src/main/java/.../input/keyboard/LangSwitchBehavior.kt`（整个 enum）
4. `app/src/main/java/.../data/prefs/AppPrefs.kt`
   - `:21` 删 import
   - `:243-247` 删 `langSwitchKeyBehavior = enumList(...)`（**保留 `:228` 的 `show_lang_switch_key` 开关**）
5. `app/src/main/java/.../ui/main/settings/behavior/KeyboardGroupFragment.kt`
   - `:400` 从 `GROUP_BEHAVIOR` 集合里删 `"lang_switch_key_behavior",`
6. `app/src/main/res/values/strings.xml` 删 `lang_switch_key_behavior`、`lang_switch_behavior_next_ime_app` 两条
   - **必须保留** `show_lang_switch_key`；**必须保留** `space_behavior_enumerate` / `space_behavior_activate`（`SpaceLongPressBehavior` 还在用）
   - 各语言目录同名两条一并删（`values-de:610,611`、`values-es:596,597`、`values-ja:269`、`values-ko/ru/zh-rCN/zh-rTW` 同理，用 `git grep -n 'lang_switch_key_behavior\|lang_switch_behavior_next_ime_app'` 定位）
7. **不要动**：`KeyAction.kt:80` 的 `data object LangSwitchAction`（还要用）、`KeyDefPreset.kt:389` 的 `Behavior.Press(KeyAction.LangSwitchAction)`（就是那个语言键）

**怎么发 Shift（照抄仓库里已验证的写法，不要自己发明）**

关键事实：只有走"物理键盘路径"rime 才认得独立的 Shift。`FcitxInputMethodService.sendSimulatedKeyEvent(keyCode, scanCode, action, fromMacro = false)`（`FcitxInputMethodService.kt:1345`，public）内部用 `InputDevice.SOURCE_KEYBOARD` + `KeyEvent.FLAG_FROM_SYSTEM` 造事件，然后 `forwardKeyEvent(event, preserveModifierState = true)`。**不要**用 `sendSimulatedKeyEventOrFallback`（那是走 `currentInputConnection.sendKeyEvent`，rime 收不到）。

现成参考实现在 `BaseKeyboard.kt`：
- `:115` `private val shiftKeyCode = KeyEvent.KEYCODE_SHIFT_LEFT`
- `:116` `private val shiftScanCode by lazy { mapFcitxToScanCode("Shift_L", shiftKeyCode) }`
- `:2642` `sendFcitxKeyTap()`，`:2654` `val keyHoldDelayMs = if (isMod) 150L else 50L`，`:2663`/`:2667`/`:2671` 是 Shift down → `delay` → Shift up 的实际写法：修饰键按住 **150ms** 后再抬，rime 才能识别为"独立 Shift 敲击"。

在 `CommonKeyActionListener` / `ButtonAction` 里没有 `BaseKeyboard` 的那两个私有字段，自己取 scancode：
`org.fcitx.fcitx5.android.core.ScancodeMapping.keyCodeToScancode(KeyEvent.KEYCODE_SHIFT_LEFT)`（`KEYCODE_SHIFT_LEFT` = 59）。

建议形状（放在 `service.lifecycleScope.launch { }` 里，因为要 `delay`）：

```kotlin
is LangSwitchAction -> {
    // rime 专版：语言键发一次独立的 Shift 敲击，由 rime 的 ascii_mode 切中英。
    // 必须走 sendSimulatedKeyEvent 的物理键盘通道，rime 才认独立 Shift；
    // 修饰键需要约 150ms 的按住时长（同 BaseKeyboard.sendFcitxKeyTap）。
    val keyCode = KeyEvent.KEYCODE_SHIFT_LEFT
    val scanCode = ScancodeMapping.keyCodeToScancode(keyCode)
    service.lifecycleScope.launch {
        service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_DOWN)
        delay(150)
        service.sendSimulatedKeyEvent(keyCode, scanCode, KeyEvent.ACTION_UP)
    }
}
```

`fromMacro` 留默认 `false`（`true` 会额外清 kawaii bar 焦点状态，这里不需要）。两处（键盘上的键、状态栏按钮）逻辑一致，可以抽个小 helper，也可以各写一遍——按仓库现有风格，重复两遍更省事、也不引入新抽象。

**验证**：`./gradlew :app:assembleFxRelease` 能过（本地没装 Android SDK 就靠 CI）；装机后按语言键应能在 rime 状态栏看到 `ascii_mode` 的 `中文 → 英文` 开关翻转。

---

## 5. 待处理问题：用户反馈"rime 无法成功部署"

用户提供 `org.fcitx.fcitx5.android.fx.rime-2026-09-04T09_05_50Z.txt`（版本 `0.1.3-585-g54706725`，即 rebase 前 quickphrase 提交的产物；对应 rebase 后的 `cefc481b`）。

### 已查明的事实

```
17:05:29.856 rimeengine.cpp:983  Rime deploy(): restartRime(fullcheck=true)
17:05:29.859 rimeengine.cpp:401  Rime Start: fullcheck=1, currentDataDir=rime
17:05:29.859 rimeengine.cpp:407  Rime data directory:
             "/storage/emulated/0/Android/data/org.fcitx.fcitx5.android.fx.rime/files/data/rime"
17:05:29.863 rimeengine.cpp:455  Rime Start: maintenanceMode=1
17:05:29.863 rimeengine.cpp:843  Notification: 0 deploy start
17:05:29.943 rimeengine.cpp:843  Notification: 0 deploy failure   ← 仅 80ms
```

1. **部署 80ms 就失败** → 不是编译超时/内存不足，是编译前的前置错误（文件缺失 / YAML 解析失败 / 目录不可写）。
2. **方案能读到**：状态栏有 `wanxiang`、`wanxiang_english`，以及 `ascii_mode/ascii_punct/full_shape/emoji/tone_hint/toneless_hint/super_tips/charset_filter/char_priority/english` 全套开关 → 说明 `build/` 里有已编译的 `wanxiang.schema.yaml`。
3. **打字没候选**：`preedit=y`、`preedit=j` 正常，但 `PagedCandidateEvent(candidates=[])` 恒空 → 词典 `*.table.bin`/`*.prism.bin` 没加载。

推断：用户 rime 用户目录是从 fx2 的目录复制来的（包名从 `...fx` 变成 `...fx.rime`，数据目录是两套），**`build/` 复制过来了但源 YAML/词典没复制全**，于是重新部署一开始就失败、词典也加载不出来。

**已排除是本轮改动造成**：`git diff --name-only 6be71bf5..cefc481b`（旧 SHA：`eaa85316..54706725`）里除两份 `docs/*.md` 外没有任何 rime/opencc/prebuilt/submodule 路径；rime addon 的依赖只有 `notifications`/`dbus`（见 fxliang fork 的 `src/rime-addon.conf.in.in`），与被删的 spell/unicode/quickphrase/imselector 无关。

### 给用户的建议（已答复，供参考）

对比新旧目录、完整重新复制整个 `rime` 目录、删掉新目录里的 `build/` 和 `installation.yaml` 再重新部署、确认剩余空间。

### 诊断盲区与建议的下一步改动（**已向用户提议，等他确认**）

`fcitx5-rime` 把 `fcitx_rime_traits.log_dir` 设成空串 → librime 走 `google::LogToStderr()`（见 librime 1.12.0 `src/rime/setup.cc:76-82`），而 Android 应用进程的原生 stderr 默认指向 `/dev/null`。**整份 logcat 里没有一条 librime 自己的日志**（已确认：无任何 glog 格式行；`app/src/main/cpp` 下没有任何 `dup2`/stderr 重定向代码）。所以"具体哪个文件、哪行 YAML 出错"全被丢掉了。

建议改动：在 `app/src/main/cpp/native-lib.cpp` 里把原生 stderr 接进 logcat（`pipe()` + `dup2(STDERR_FILENO)` + 读取线程 → `__android_log_write`），做成独立提交、push、跑 CI。仓库里已有 `nativestreambuf.h`（`native_streambuf` 把 `std::ostream` 写进 android log，按首字符猜日志级别）可作风格参考，但它只接 fcitx 自己的 `fcitx::Log` 流，**接不到 librime 的 stderr**。
⚠️ 用户还没点头，**动手前先问**。

---

## 6. 需要知道的机制（省得重新摸索）

- **addon 打包链路**：addon 的 `.so` 由 cmake target 拷进 jniLibs；`.conf` 由 `install(... COMPONENT config)` 装到 `usr/share/fcitx5/addon/` → 进 APK assets → 显示在"附加组件"设置页。要让某个组件从设置页消失，除了不构建它，还要在 `app/build.gradle.kts` 的 `fcitxComponent.excludeFiles` 里列出它的 conf 路径。
- **`excludeFiles` 语义**（`build-logic/convention/src/main/kotlin/FcitxComponentPlugin.kt:49-58`）：`deleteFcitxComponentExcludeFiles` 任务在 install 之后对 `assetsDir.resolve(it).delete()`——**文件不存在也不会报错**，所以多列几条是安全的。
- **`generateDataDescriptor`** 依赖 `installFcitxComponent` + `deleteFcitxComponentExcludeFiles`（`AndroidAppConventionPlugin.kt:130-135`），descriptor 里不会包含被排除的文件。
- **`DataManager.sync()`** 只按 `descriptor.json` 的差异新增/更新/删除文件；用户自己放的、不在 assets 清单里的文件永远不会被覆盖或删除。
- **rime 的两个目录**：shared data = APK assets 解出来的 `<deviceProtectedDataDir>/usr/share/rime-data`（`RIME_DATA_DIR` 编译期宏 + 运行时 `StandardPaths::locate(Data, "rime-data/default.yaml")` 定位）；user data = `getExternalFilesDir(null)/data/rime`（由 `native-lib.cpp` 的 `setenv("FCITX_DATA_HOME", <extData>/data)` + `XDG_DATA_HOME` 决定）。
- **fcitx 环境变量**全在 `native-lib.cpp:522-546`（`LANG`/`FCITX_LOCALE`/`HOME`/`XDG_DATA_DIRS`/`FCITX_CONFIG_HOME`/`FCITX_DATA_HOME`/`FCITX_ADDON_DIRS`/`XDG_*`）。
- **rime-data 资源清单**在 `app/src/main/cpp/CMakeLists.txt:52-69`（default.yaml、essay、prelude、luna-pinyin、stroke），`COMPONENT prebuilt-assets`；`app/build.gradle.kts` 的 `generateDataDescriptor { symlinks.put("usr/share/rime-data/opencc", "usr/share/opencc") }` 建软链。

---

## 7. 建议的下一步顺序

1. 确认 `git status` 干净（除那两个未跟踪文件）、`fx2-rime-fusion` 与 origin 同步（当前 `01c1070e`）。
2. **复查 force-push 触发的 CI**（新 tip `01c1070e` 是 docs 提交，按路径过滤不触发；实际要看的是它前一个代码提交 `cefc481b`）。冲突解决动了 `BaseInputView.kt`，务必确认这一轮构建是绿的。
3. 做**任务 4**（语言键 → Shift 点击）：一个中文提交 → push → 等 CI 绿。
4. 向用户确认是否加 **stderr→logcat** 那个诊断改动；同意的话独立提交 → push → 等 CI 绿，然后让用户复现一次导日志。
5. 用户确认后再决定要不要删 `backup/fx2-rime-fusion-pre-merge` 与 `backup/fx2-rime-fusion-pre-rebase-20260904`。
