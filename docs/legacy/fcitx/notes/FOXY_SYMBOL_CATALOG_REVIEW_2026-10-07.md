# Foxy 风格符号面板搬运评估（当前实现复盘）

> **文档状态**：当前维护研究稿，2026-10-07 更新。
>
> 本文由工作区根目录的 `foxy符号键盘搬运评估.md` 搬入文档分支并重写。当前事实以代码分支 `fx-rime-only` 的 `d156052b25aae54f4219f799416c9475e1a19128` 为准；文档源码位于 `docs` 分支的 `docs/maintainer/`，不再使用已经删除的 `rime-docs` worktree。

## 结论

Foxy 符号数据和交互模型已经落地，不再是“评估后等待确认”的功能：

- `4f93612f`（2026-09-28）引入 Foxy 风格面板、三份 catalog、最近使用、直达面板和用户自定义数据；当前实现已经不再依赖旧的 `PickerData.kt`、`PickerTabsUi`、分页式 picker。
- 当前 APK 内置 `symbols.json`、`emoji.json`、`kaomoji.json`，解析器沿用 Foxy 的 `multiLine`、`groups[].names`、`groups[].symbols` 格式，并实现语言回退和失败回退。
- UI 已按“左侧分组栏 + 右侧可滚动内容”重建，符号/表情使用六列，颜文字使用一列；面板独占键盘区域，RecyclerView 复用条目视图。
- 用户可以在设置页为三类数据分别导入、选择 JSON。导入先解析校验，再原子写入；非法文件名、越界软链接和解析失败都有边界处理。
- 仍需维护的不是“能否搬运”，而是数据来源/授权确认、catalog 格式兼容性、超大数据集的运行时回归，以及与布局目标名和旧用户数据的兼容。

这份结论证明的是当前代码和资源已经存在，不等于证明 Foxy 原始数据的授权已经完成，也不等于已经做过独立的内存、帧时间或真机输入性能基准。

## 1. 核对快照

| 项目 | 当前值 |
| --- | --- |
| 代码 worktree | `fx-rime-only` |
| 代码分支 / HEAD | `fx-rime-only` / `d156052b25aae54f4219f799416c9475e1a19128` |
| 文档 worktree | `docs` 分支 |
| 文档分支 / HEAD | `docs` / `6fe57146fbb7ec43f51f7f19e69d65d1e44023e9` |
| Foxy 面板提交 | `4f93612f`，2026-09-28 |
| 提交计数基线 | `git rev-list --count 3ad25fc9..HEAD` = `218`（代码仓库核对时） |

代码仓库当时仅有用户已有的 `lib/fcitx5/src/main/cpp/fcitx5` 子模块修改；本文研究没有将其作为符号面板改动，也没有覆盖它。

## 2. 内置 catalog 统计

统计对象是当前 APK 资源 `app/src/main/assets/bundled/symbols/` 中的 JSON，使用 `JSON.parse` 读取 `groups[].symbols`，分别统计组数、原始条目数和组内去重条目数：

| 文件 | `multiLine` | 组数 | 原始条目 | 组内去重 |
| --- | ---: | ---: | ---: | ---: |
| `symbols.json` | `false` | 62 | 4773 | 4207 |
| `emoji.json` | `false` | 10 | 1926 | 1926 |
| `kaomoji.json` | `true` | 19 | 999 | 795 |
| 合计 | - | 91 | 7698 | 6928 |

“约 6900 条”是三类 catalog 各自去重后的合计；它不是把所有类别跨文件做一次全局去重，也不是当前页面一次性创建的 View 数量。

## 3. 数据格式与加载

### 3.1 Foxy 兼容格式

当前模型在 `app/src/main/java/org/fcitx/fcitx5/android/input/picker/SymbolCatalog.kt` 中定义：

```json
{
  "multiLine": false,
  "groups": [
    {
      "names": {
        "zh": "英文标点",
        "zh-Hant": "英文標點",
        "en": "Western Punctuation"
      },
      "symbols": ["1", "2", "3"]
    }
  ]
}
```

`names` 是多语言字典，不是单一组名。解析器的回退顺序是：

1. `zh-TW`、`zh-HK`、`zh-MO` 先尝试 `zh-Hant`；
2. 语言主标签，例如 `zh`、`en`；
3. 字典中的第一个值；
4. 没有组名或没有条目的组被过滤掉。

JSON 使用 `ignoreUnknownKeys = true` 和宽松解析，允许上游增加不影响当前显示的字段；用户文件还沿用本项目用户 JSON 配置的逐行 `//` 注释剥离规则。

### 3.2 内置与用户来源

三类内置路径分别是：

- `bundled/symbols/symbols.json`
- `bundled/symbols/emoji.json`
- `bundled/symbols/kaomoji.json`

用户文件位于应用 external files 目录下的：

```text
config/symbol_catalogs/symbols/<name>.json
config/symbol_catalogs/emoji/<name>.json
config/symbol_catalogs/kaomoji/<name>.json
```

`SymbolCatalogs` 按“种类 + 所选文件”缓存；切换数据源或语言变化时由 `invalidate()` 清缓存。没有选择用户文件时使用内置资源；用户文件不存在、解析失败或解析后没有有效组时回退内置，而不是让面板因为一个坏文件变空。内置资源本身读取失败时才返回空 catalog。

### 3.3 设置页导入流程

`SymbolSettingsFragment` 为每类 catalog 提供内置项、用户文件选择和 SAF 导入：

1. 从系统文件选择器读取文本；
2. 剥离行注释并调用同一解析器；
3. 要求至少有一个可用分组；
4. 根据显示名生成安全文件名，重名自动追加序号；
5. 先写临时文件，再改名落盘；
6. 选择新文件并使 catalog 缓存失效。

`UserConfigFiles` 只允许 `symbols`、`emoji`、`kaomoji` 三个固定子目录；文件名必须是安全的 `.json` 名称，长度上限为 120 个字符，不能包含路径分隔符、控制字符或 Windows 保留字符。列目录时还检查 canonical path，排除指向 catalog 目录之外的软链接。

## 4. 当前 UI 与兼容行为

实现位于 `app/src/main/java/org/fcitx/fcitx5/android/input/picker/`：

- `SymbolPanelUi` 使用左侧 84dp 分组栏、右侧 RecyclerView；网格项复用 `TextKeyView`，不会为 4773 个条目一次性创建常驻 View。
- `PickerWindowPreset` 固定符号六列、表情六列、颜文字一列。颜文字的 `multiLine = true` 与长文本语义是采用一列的依据。
- 面板独占整个键盘区域，底部不再嵌入 `ABC / 空格 / 回车` 那一行；左栏底部只保留返回文字键盘的 `⌨` 和退格 `⌫`。
- 点击条目立即提交并加入最近使用；单个半角/全角数字不会占满“最近”分组。表情仍可根据策略过滤不支持的 glyph、应用肤色，并支持长按肤色选择。
- 三个窗口目标名是 `Symbol`、`Emoji`、`Kaomoji`。`Emoticon` 作为旧目标别名继续解析，避免已有布局、宏和 `lastPickerType` 偏好静默失效。
- 面板之间的跳转由布局按键和宏配置完成，不在面板内部硬编码；这保留了布局编辑器和宏对 picker 的可组合性。

## 5. 测试与已核对证据

当前仓库已有两组针对性单测：

- `app/src/test/java/org/fcitx/fcitx5/android/input/picker/SymbolCatalogParseTest.kt`：组和条目解析、`multiLine`、简繁中文、英文和未知 locale 回退、空组过滤、未知字段容忍、空 `names`。
- `app/src/test/java/org/fcitx/fcitx5/android/input/config/SymbolCatalogNameTest.kt`：正常 Unicode 文件名、路径穿越、非 JSON 扩展名、控制/保留字符、120 字符上限和 `__builtin__` 哨兵。

本次研究已实际完成的资源核对是上表的 JSON 统计，命令退出码为 0。文档构建和 Android 单测是否通过，以本次任务后续验证记录为准；不要把旧报告中的“建议增加测试”当成当前测试结果。

## 6. 与旧稿的差异

旧稿中的以下判断已经失效：

- “`PickerTabsUi` 无法容纳 62 个 tab，因此需要先设计二级分组”——当前实现使用可滚动的左侧分组栏，62 个符号组已由 catalog 驱动。
- “代码仍硬编码在 `PickerData.kt`”——当前入口是 `SymbolCatalog.kt`、`SymbolPanelUi.kt` 和 `PickerWindowPreset.kt`，旧 picker 文件已删除。
- “emoji 不搬、颜文字尚未落地”——三类内置资源都已经进入 APK，颜文字以一列长文本面板显示。
- 旧 Foxy 反编译目录和旧源码行号——它们只适合作为原始研究背景，不能作为当前代码证据。

## 7. 剩余维护事项

1. **数据来源与授权**：确认三份 JSON 的再分发许可、署名和许可证文件要求，并把结论放入发布检查清单。代码中的格式兼容不替代法律/项目许可审查。
2. **数据兼容性**：继续保持 `multiLine`、多语言 `names` 和未知字段容忍；若 Foxy 格式增加必需字段，应先增加版本兼容测试再更新解析器。
3. **资源回归**：每次替换内置 JSON 后重新统计组数/条目数、检查空组和异常长条目，并在真机观察首次打开、切组和长按弹窗。
4. **内存与帧时间**：RecyclerView 解决了常驻 View 膨胀，但当前报告没有提供大 catalog 在低端设备上的内存、首开耗时或滚动帧率基线；这些指标应单独测量，不要从“使用 RecyclerView”推导性能结论。
5. **路径与迁移**：用户自定义文件的实际保存位置是应用 external files 下的 `config/symbol_catalogs/`，不要在新文档或脚本中恢复旧的 `rime-docs`、工作区根目录或 Foxy 反编译路径。

## 8. 维护结论

符号面板搬运工作已经从评估阶段进入维护阶段。后续改动应围绕 catalog 格式兼容、用户文件安全、旧目标名兼容和可测量的运行时回归进行；若要继续修改面板结构，应先保持现有三类数据源、导入回退和最近使用行为不变，再单独评估 UI 或性能影响。
