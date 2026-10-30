# 字体刷新遗留入口评估报告：`FontProviders` 的一次性布尔标志 `needsRefresh`

> **【2026-10-05 迁移注记】** 本文自 `rime-docs` 分支迁入文档站（`docs` 分支）`docs/maintainer/` 目录维护，`rime-docs` 分支已删除。评估基线 `e916245e`（2026-10-04），结论以该时点代码为准。

审阅基线：SandyYuR/fcitx5-android，分支 `fx-rime-only`，提交 `e916245e`（2026-10-04）。相关文档：`HANDOVER-rime-only.md` §3.4（`e916245e` 条目）与 §5。

## 1. 摘要与结论

**结论：本次不改。** 现有实现是**功能正确**的，稳态性能开销**可忽略**；唯一值得记录的代价是**耦合**——字体刷新的整树替换搭在了"主题切换"这条通道上。要动它，前提是先构造出能复现"不显示虚拟键盘"分支的真机场景，否则等于盲改一条压在输入法生命周期敏感逻辑上的路径。

- 保留理由 ①：立起这个标志的两条路径**都对应"字体集真的变了"**，没有误触发来源。
- 保留理由 ②：「谁先读谁拿走」的后果**已良性**——键盘侧另有一层版本号门控，键盘按键每次设字号都重读配置，候选侧**完全不读这个标志**（它走 `fontGeneration`）。
- 保留理由 ③：唯一被这个标志独占的工作，是**不显示虚拟键盘时**的 `replaceInputViews()` 整树替换，而它压在 `isInInputLifecycleCriticalPhase` / `canApplyPendingThemeNow()` 这套生命周期守卫上，并且 `pendingThemeUpdate` 还与**真实主题切换**共用。
- 保留理由 ④：真机验证（`e916245e`）只覆盖了"显示虚拟键盘"那一条分支，另一条分支**从未在真机上构造过**。

## 2. 对象：标志本身

`app/src/main/java/org/fcitx/fcitx5/android/input/font/FontProviders.kt`

| 行 | 内容 |
| --- | --- |
| `:62` | `private val refreshLock = Any()` |
| `:64` | `@Volatile private var needsRefresh = false` |
| `:83` | `needsRefresh = true`（在 `handleFontsetChanged()` 内） |
| `:97` | `needsRefresh = true`（在 `markNeedsRefresh()` 内） |
| `:105-109` | `fun checkAndClearRefreshFlag(): Boolean = synchronized(refreshLock) { val result = needsRefresh; needsRefresh = false; result }` |

这是一个**读一次就清零**的一次性信号，用 `refreshLock` 保证读+清原子。它**没有**配套的"只读不清"访问器了——原先那个 `fun needsRefresh(): Boolean = needsRefresh` 已随 `e916245e` 删除（它当时唯一的读取点是 `BaseKeyboard.currentRowsSignature()` 里的 `"|fontRefresh:"` 项，而那一项本身是负收益，详见 §5）。

## 3. 生产者：谁能立起它

只有两条路径，**都在 `FontProviders` 内部**，且**都是"字体集文件真的变了"**：

1. **`handleFontsetChanged()`**（`FontProviders.kt:77-85`）
   由 `ConfigProviders.addFontsetListener { handleFontsetChanged() }`（`FontProviders.kt:72-74`，在 `init` 块注册，即对象初始化时挂上）驱动；监听器由 `fontset.json` 的 `FileObserver` 触发（`ConfigProvider.kt:295` 的 `notifyListeners(fontsetListeners)`）。即"用户在外部改了这个文件"。
2. **`markNeedsRefresh()`**（`FontProviders.kt:91-99`）
   唯一业务调用方 `FontsetEditorActivity.kt:575`，紧跟在 `ConfigProviders.provider.writeFontsetPathMap(mergedMap)`（`:573`）之后，即"用户在设置页点了保存"。

两者都顺带做 `provider.clearCache()` 与清 `fontSizeResultCache`——**真正的缓存失效靠这一步**，与标志无关。

> 注意：对象初始化、注册监听本身**不会**立起标志（`addFontsetListener` 只登记回调，`FileObserver` 只在文件事件时回调），所以**冷启动不会带着一个已立起的标志**。

## 4. 消费者：两条互斥分支

唯一消费者是 `checkAndClearRefreshFlag()`，全仓两个调用点，分别落在 `onStartInputView` 的 **if / else 两条互斥分支**里。

### 分支 A —— 会显示虚拟键盘

`app/src/main/java/org/fcitx/fcitx5/android/input/FcitxInputMethodService.kt:1802-1811`

```kotlin
if (inputDeviceManager.evaluateOnStartInputView(info, this) ||
    inputDeviceManager.isPhysicalCandidateBarMode
) {
    inputView?.startInput(info, capabilityFlags, restarting)   // :1811
}
```

→ `InputView.kt:3553` 调 `keyboardWindow.checkAndApplyFontRefresh()`（`KeyboardWindow.kt:221-228`）：

```kotlin
fun checkAndApplyFontRefresh() {
    if (FontProviders.checkAndClearRefreshFlag()) {          // :222  消费标志
        keyboards.values.forEach { it.clearReusableRowsCache() }  // :225
        preloadFontsForKeyboard()                                 // :226
    }
}
```

**这条分支只重建按键行**，不整树替换。随后 `preloadFontsForKeyboard()`（`KeyboardWindow.kt:230-246`）把回调投到主线程，并**按版本号门控**：

```kotlin
val generation = FontProviders.fontGeneration
if (generation == lastRefreshedFontGeneration) return@execute   // :240
lastRefreshedFontGeneration = generation                        // :241
fontRefreshPending = true                                       // :242
applyPendingFontRefresh()                                       // :243
```

（`fontRefreshPending` 在 `KeyboardWindow.kt:137`，`lastRefreshedFontGeneration` 初值 `-1L` 在 `:138`，`applyPendingFontRefresh()` 在 `:248-252`。）`:235-238` 的注释明确写着：**旧 token 会被 `onStartInput` 立刻作废，从而静默杀掉这条刷新路径**——所以这里必须靠版本号而不是 token。

### 分支 B —— 不显示虚拟键盘（物理键盘 / 外接）

`FcitxInputMethodService.kt:1812-1828`

这条分支**根本不调 `startInput()`**，所以没有人会消费标志，于是它自己消费（`:1825-1827`）：

```kotlin
if (org.fcitx.fcitx5.android.input.font.FontProviders.checkAndClearRefreshFlag()) {
    pendingThemeUpdate = ThemeManager.activeTheme
}
```

然后 `finally`（`:1829-1833`）在 `contentView.post` 里放开临界区并尝试应用：

```
isInInputLifecycleCriticalPhase = false    // :1831
applyPendingThemeIfPossible()              // :1832
```

`applyPendingThemeIfPossible()`（`:449-458`）→ `canApplyPendingThemeNow()`（`:441-447`，五道守卫：`contentView` 已初始化 / 不在输入法生命周期临界区 / `currentInputBinding != null` / `isInputViewShown` / `decorView.isAttachedToWindow`）→ `replaceInputViews(theme)`（`:428-434`）→ `replaceInputView(theme)` + `replaceCandidateView(theme)`——**整个 `InputView` 与 `CandidatesView` 丢弃重建**，最后 `inputView?.syncImeFromCache()`。

**这才是「键盘整树刷新靠这条」的含义。**

### 曾经的第三条路已经拆掉

`FcitxInputMethodService.kt:1805-1810` 的注释记录了一个已被移除的写法：那个分支之上原本还有一次整树替换，它**读标志但不清零**，后果是**一旦改过字体，此后每一次焦点事件都会重建整棵树**，而且重建发生在 `canApplyPendingThemeNow()` 专门要避开的输入法生命周期临界区里。`e916245e` 之后这条已经不存在。

## 5. 与版本号机制的分工（不要混淆）

`e916245e` 引入的权威信号是 **`FontProviders.fontGeneration`**（`FontProviders.kt:120`，= `FontProviderApi.fontDataVersion`，由 `DefaultFontProvider` 在 worker publish 块里与两张表同锁递增）：

- **候选侧完全不读 `needsRefresh`**——候选栏 / 展开面板 / 浮动候选窗 / 候选条目 / 预编辑全按 `fontGeneration` 判断。
- **键盘侧两条都用**：`needsRefresh` 决定"要不要去清可复用行缓存并预载字体"，`fontGeneration` 决定"预载完成后要不要真的重建行"。
- `BaseKeyboard.currentRowsSignature()` 里的 `"|fontRefresh:"` 项已随 `e916245e` 删除。**删它是净收益**：`markNeedsRefresh()` 在 worker 发布新字体**之前**就立起标志，此时 `fontGeneration` 尚未前进，签名变化只会让按键行用**旧字体**白重建一次；等 worker 发布后 `"|fontGen:"` 再变一次又要重建一次。同处的 `"|fontGen:"` 才是有效的重建触发点。

## 6. 为什么「不改」是安全的

1. **没有误触发来源**：两条生产者路径都对应真实字体集变化（外部改文件 / 设置页保存）。
2. **「谁先读谁拿走」已无实质危害**：即便键盘侧先拿走，键盘侧照样靠 `fontGeneration` 正确重建；候选侧从来不看这个标志。这正是 `e916245e` 的修复思路。
3. **不会永久卡住**：标志只置位、不清零的窗口期结束后，下一次 `onStartInputView` 必然消费它（或按版本号重建）。没有任何路径要求它"被清零两次"。
4. 分支 B 目前是**唯一独占 `needsRefresh` 的工作**，而它压在生命周期守卫上——改它必须同时给出真机证据。

## 7. 残留成本（静态推断，未实测）

| 项 | 评估 |
| --- | --- |
| 稳态开销 | ≈ 0。`checkAndClearRefreshFlag()` 只在 `onStartInputView` 路径上求值，**不在打字热路径**；一次字体集变化只多两次 `@Volatile` 读 + 一次 `synchronized`。 |
| 每次保存的额外重建 | **已随 `"|fontRefresh:"` 删除而消除**（见 §5）。 |
| 真正剩余的成本 | **不是性能，是耦合**：分支 B 把"字体变了"伪装成"主题变了"（`pendingThemeUpdate = ThemeManager.activeTheme`），于是①字体重建会走主题那条整树替换通道；②`pendingThemeUpdate` 与真实主题切换**共用同一个槽**，两者几乎同时发生时会互相合并/顶掉——用户观感上通常无差别（都是整树替换一遍），但排查问题时容易误判。 |
| 可读性 | 中等偏差：一个 `@Volatile` 布尔 + 一把锁 + 两个调用点，但语义（"一次性"）与候选侧的版本号语义并存，容易被后续改动误用。 |

## 8. 若将来要改：前置条件与方案

**前置条件（缺一不可）**

1. 构造出**分支 B 的真机场景**：接物理键盘，或让 `inputDeviceManager.isPhysicalCandidateBarMode` 为真、同时虚拟键盘不显示；在此状态下改字体设定并保存，确认`replaceInputViews()` 确实发生、且确实是必要的（把日志打在 `:1825` 与 `replaceInputViews()` 入口即可）。
2. 确认分支 B 在**没有这个标志**时会退化成什么样：是"什么都不刷新、等下次虚拟键盘出现再刷"，还是"必须当场整树替换"。

**方案（按侵入性从低到高）**

- **方案 1（最小）**：保持标志不变，只把分支 B 的消费改成"读 `fontGeneration` 与 service 内新的 `lastReplacedFontGeneration` 比较"，把 `needsRefresh` 从这条分支彻底摘掉。之后若 A 分支也改用版本号，`needsRefresh` / `markNeedsRefresh()` / `checkAndClearRefreshFlag()` 三件套即可整体删除。
- **方案 2**：给分支 B 一个**专用槽**（如 `pendingFontTreeRefresh: Boolean`），不再借用 `pendingThemeUpdate`，从而解除与真实主题切换的耦合；生命周期守卫（`canApplyPendingThemeNow()`）复用同一套。
- **方案 3（不建议）**：直接删掉分支 B 的处理。**只有在方案 1 的第 2 步证明"不刷新也不会出错"之后才允许考虑**，否则会退回"物理键盘下改字体永远不生效"。

**必须守住的约束**

- `canApplyPendingThemeNow()` 的五道守卫一条都不能绕过，尤其 `isInInputLifecycleCriticalPhase`——`FcitxInputMethodService.kt:1805-1810` 的注释就是踩过这个坑留下的。
- 真实主题切换仍走 `onThemeChangeListener`（`:474-478`），不能被合并掉。
- 键盘行重建的版本号门控（`KeyboardWindow.kt:239-241`）不要改回"一次性 token"——那里的注释已说明旧写法会被 `onStartInput` 静默杀掉刷新。

## 9. 验证清单（改这条路径时必须全跑）

1. **A 分支**：虚拟键盘显示状态下改候选字号 → 候选栏即时换字号；改按键主字体 → 键帽即时换字号。
2. **B 分支**：物理键盘 / 不显示虚拟键盘状态下保存字体 → 再唤起虚拟键盘时确认字体已换、且没有闪退或多重建。
3. **并发**：几乎同时切换主题与保存字体 → 两个效果都在，且没有残留旧主题或旧字体。
4. **冷启动**：强杀输入法后首次弹出键盘 → 不该有任何多余重建（标志不该是已立起状态）。
5. 全程 `adb shell pidof <pkg>` 确认 IME 进程 PID 未变（区分"热刷新"与"被系统重启"）。

## 10. 未决问题

- 分支 B 的**触发频率**未知：`inputDeviceManager.isPhysicalCandidateBarMode` 在什么设备/设置组合下为真，目前没有实测数据。若它实际上是"几乎不进"的分支，方案 3 的可行性会显著上升。
- `pendingThemeUpdate` 被顶掉时的**用户可见后果**未验证：字体保存与主题切换在同一帧竞争时，是否存在"其中一项要等下一次焦点事件才生效"的窗口。
- 本报告的**性能结论均为静态推断**，未做任何 profile 或计时；若将来为此立项，先按 §9 的清单采集数据再谈优化。
