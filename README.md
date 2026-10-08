# 靓企鹅·中州韵（Rime-only）

把 [Rime 中州韵](https://github.com/rime) 深度内置进 Android 输入法的版本：一个 APK 里同时包含 Fcitx5 底盘、Rime 引擎与键盘界面，**Rime 是唯一的输入引擎**。基于 [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android) 的 `fx` 分支继续开发，全部改动由 vibe coding 完成。本项目不是 Fcitx5 或 Rime 的官方发行版。

| | |
|---|---|
| 中文应用名 | **靓企鹅·中州韵** |
| 其他语言应用名 | Fcitx5.fx.rime |
| 包名 | `org.fcitx.fcitx5.android.fx.rime` |
| 下载安装 | [GitHub Releases](https://github.com/SandyYuR/fcitx5-android/releases)（含带时间戳的 Nightly 预发布版） |
| 用户手册 | [靓企鹅·中州韵 文档站 · 用户指南](https://sandyyur.github.io/fcitx5-android/manual/RIME_ONLY_USER_GUIDE_zh-CN) |
| 开发分支 | `fx-rime-only`（文档集中在 [`docs` 分支的文档站](https://sandyyur.github.io/fcitx5-android/)） |

> ⚠️ **本版不预置任何输入方案。** 装好、启用之后键盘能正常弹出，但**打不出汉字**——这是预期状态，不是故障。必须自己放入方案并部署一次才能使用，步骤如下。

## 快速上手

1. **安装**：从 [Releases](https://github.com/SandyYuR/fcitx5-android/releases) 下载 APK 安装（CI 构建的是 `arm64-v8a`）。包名与原版及旧版不同，可以共存安装，但数据互相隔离、不会自动迁移。
2. **启用**：在系统「设置 → 语言和输入法」中启用**靓企鹅·中州韵**。
3. **放入方案**：打开应用主页 → **中州韵设置**，进入用户数据目录（`data/rime`），把方案文件（`*.schema.yaml` 及其依赖的 `*.dict.yaml`）复制进去。
4. **声明方案**：在同一目录的 `default.custom.yaml` 中 patch `schema_list`：

   ```yaml
   patch:
     schema_list:
       - schema: 你的方案名
   ```

5. **部署**：回到**中州韵设置**执行**部署**，等键盘上的「正在部署」提示消失即可开始输入；之后可用方案选单（默认 `Control+grave` 或 `F4`）切换方案。
6. **可选增强**：想让候选排序更好，可自备 `essay.txt`（Rime 官方词频表）放进同一个用户数据目录。方案以 `use_preset_vocabulary: true` 声明依赖它时，**缺少这个文件不会导致部署失败**，只是词典会少一批预置短语与词频。

方案引用的 `symbols`（标点）、`key_binder`（按键绑定）等公共预设资源已随 APK 提供，无需自行补装。升级应用不会删除你放在用户数据目录中的方案。

## 功能亮点

### 输入

- **中英文切换**：语言键**短按**向 Rime 发送一次独立 Shift（交给 `ascii_composer`/`switch_key` 处理）；**长按弹出 Rime 方案选单**，点选即切换方案。想切换到别的 Android 输入法，请长按**工具栏**上的语言按钮——两者不是同一个入口。
- **候选词手势**：按住候选向上滑弹出选字窗，滑到哪个字抬手即提交该字；按住向下滑呼出该候选的操作菜单（如「忘记词汇」）。未按住时的上下滑动仍归候选列表自身，不影响展开候选面板翻页。
- **退格上滑清空**：长按退格进入连续删除后，直接向上滑进提示条区域，可一次性清空当前输入框的全部文字（含已上屏正文）。
- **候选显示**：候选正文与注释可分别配置字体和字号，另有可选的「默认高亮第一个候选」。
- **符号 / 表情 / 颜文字面板**：采用 Foxy 输入法的布局与数据（左侧分组栏 + 右侧网格、「最近」分组、颜文字单列），内置约 6900 条去重条目——符号 4773、表情 1926、颜文字 999。
- **空格键**：可在布局编辑器里为它配置划动动作（与设置里的「划动空格键以移动光标」互斥，开关优先）；长按行为新增「重复输入空格」，按住即连续输入、抬手即停。
- **宏按键的显示文本**：显式填写的「显示文本」优先于「标签」并**原样显示**（不再被 Shift 强制改成小写）；留空时回落到标签，Shift 大写行为照旧。
- **按键可分别配置上划与下划宏**：在按键编辑器中，宏按键及支持方向标签的功能键分别提供「上划事件」和「下划事件」；两者可以同时存在，快捷键宏可用于为 Enter 配置不同方向的提交组合键。

### 界面与自定义

- **设置首页可搜索**：顶部搜索框支持中文名、英文别名与拼音，多个词之间是「都要命中」的关系；点结果直达对应设置页并自动滚动定位到该项。首页本身也重构为「日常调整 / 按需进入」两层共六入口，原「高级 → 引擎配置」这一层已取消。
- **按键类型下拉框带中文说明**：布局编辑器里选择按键类型时，每一项都有一段本地化说明。
- **主题与候选栏**：主题「配置」页的颜色项按语义分组折叠；候选栏新增紧凑 / 标准 / 宽松三组预设。
- 三类符号数据都可以换成你自己的 JSON，设置页提供选择与导入。
- 键盘布局编辑器的子模式下拉框新增显式「默认」项，方案一旦有了专用布局，也不再丢失编辑默认布局的入口。
- 剪贴板窗口支持历史实时搜索：进入后工具栏中间变为搜索框，打字即时过滤，结果以卡片显示在键盘上方（只查本机历史，不外发数据）。
- 工具栏支持亮/暗主题一键切换，以及一键恢复 Monet 默认配色映射。
- 支持导入、选择并持久化自定义按键音（WAV/MP3/OGG/M4A/FLAC），音效不可用时回退系统音效。
- 宏按键的「应用操作」支持切换系统输入法与弹出 Rime 方案选单两个动作。
- 应用内「关于 → 当前版本」可检查更新并查看发布说明。

## 相对 fxliang 的主要改动

本分支以 [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android) 的 `fx` 分支、提交 `3ad25fc9` 为基线继续专用化，全部改动由 vibe coding 完成；截至 2026-10-05 领先基线 **214 个提交**（随时增长，以 `git rev-list --count 3ad25fc9..HEAD` 现取为准）。下面按主题列出改动的全貌（逐条明细见 `git log --oneline 3ad25fc9..HEAD`）。

### 一、裁剪为 Rime 专版

把「Rime 只是众多输入法之一」改成「Rime 是唯一的输入引擎」，主 APK 里只留一条输入链路：

- **fcitx5-rime 并入主 APK**：`librime.a` 静态链接、rime-data 资源随包分发、`opencc` 用软链指向包内数据。Rime 从「插件 APK」变成主包内置 addon，**不再需要单独安装插件**。
- **删除拼音 / 码表链路**：native 侧移除 libime、pinyin、table、customphrase（**保留 opencc**），并删除对应的 gradle 依赖与 `lib/` 模块、拼音与码表管理 UI，以及 AIDL 的 `reloadPinyinDict`。
- **删除 9 个其他语言与功能插件**：anthy、chewing、hangul、jyutping、sayura、thai、unikey、text-editor、clipboard-filter。
- **删除整套插件框架**：`DataManager.detectPlugins`、签名白名单、`PluginFragment`、`FcitxPluginServices`、`lib/plugin-base`、`ClearUrlsPluginRuntime` 等；`MainService` 改继承 `Service`，出站过滤由内置的 `HostClipboardFilter` 承担。
- **移除 `mainline` flavor** 与任务别名 / APK 兼容拷贝，CI 精简为单一 `ci.yml`（删掉 fdroid、pull_request、nix、publish 四个无用 workflow），构建失败时把编译错误输出为 annotations。
- **包名改为 `org.fcitx.fcitx5.android.fx.rime`**，可与旧版并存安装（数据互相隔离，**不自动迁移**）。
- 另外移除 quickphrase 快速短语组件，以及 androidkeyboard、imselector、spell、unicode 四个组件。

### 二、引擎与构建链

- **构建不再依赖 fxliang 账号**：rime 源码与 prebuilt 改用自有 fork，CI 运行 `prepare_personal_build.sh` 动态 checkout。
- **librime 补丁链**（构建期依次应用，共 8 个）：fxliang 功能补丁、音节缓存、词典并行部署、用户词典缓存、词典文件重映射修复、忘记词汇连删同音词修复、万象（amzxyz）的 `rewrite` 滤镜，以及**必须排在末位**的配置指纹补丁。补丁顺序是契约的一部分。
- **配置指纹替代时间戳**：原版 Rime 用数据目录时间戳判断配置是否改动，而用户词典落盘、垃圾清理、同步临时目录等无关活动都会推新时间戳，导致冷启动每次全量部署；现在对配置文件求内容指纹，只有配置真的变了才部署。
- **部署期间的按键不再「漏」到输入框**：此前这些键既没进编码区、又被当作普通字符直接上屏，表现为「刚切回输入法打前几个字母，字母没进编码却直接出现在文字里」；现在按键只被丢弃，并在键盘上显示「正在部署」提示。
- **JNI 边界净化非法 UTF-8**：Rime 返回的候选栏标签／注释可能带孤立续字节，直接交给 `NewStringUTF` 会让 CheckJNI 直接 `abort` 整个进程（表现为打字途中输入法突然消失、且 Java 侧无堆栈）；现在逐字节校验并把非法序列替换为 `U+FFFD`，最坏只是标签多一个不可读字符。
- 引擎启停状态机、数据目录 descriptor 原子写入、词典重建时 `table.bin` 损坏等问题一并修复。

### 三、输入与交互

- **中英文切换改为发送独立 Shift**：语言键短按向 Rime 发一次 Shift，交给 `ascii_composer`/`switch_key` 处理；**长按弹出 Rime 方案选单**，点选即切换方案（切换系统输入法改到工具栏的语言按钮）。
- **候选词手势**：按住候选向上滑弹出选字窗、滑到哪个字抬手即提交；按住向下滑呼出该候选的操作菜单（如「忘记词汇」）。未按住时的滑动仍归候选列表自身。
- **退格相关**：长按连删后上滑可一次性清空输入框（含已上屏正文）；长按删空编码后持续拦截退格直到抬指，保护段内被吞的重复退格不再触发按键反馈。
- **符号 / 表情 / 颜文字面板**换成 Foxy 输入法的布局与数据（左侧分组栏 + 右侧网格、「最近」分组、颜文字单列），内置约 6900 条去重条目——符号 4773、表情 1926、颜文字 999；三类数据均可替换为用户自己的 JSON。
- **剪贴板历史实时搜索**：进入剪贴板后工具栏中间变为搜索框，打字即时过滤，结果以卡片显示在键盘上方（只查本机历史，不外发数据）。

### 四、键盘与布局

- **内置布局**：随包提供一批社区贡献的布局（大同-9+18+26keys、九键布局、行之-26键、行之-万象九键v2、QM-朝花、Sandy-数字行26+万象九键等），以及内置主题与图标主题；内置资源支持内容更新，但**只替换从未被用户修改过的文件**。
- **空格键**：可在布局编辑器里配置划动动作（与设置里的「划动空格键以移动光标」互斥，开关优先级更高）；长按行为新增「重复输入空格」，按住即连续输入、抬手即停，节奏与退格连删一致。
- **宏按键的显示文本**：「显示文本」优先于「标签」，填了就**按原样显示**（不再被 Shift 强制改写成小写）；留空则回落到标签，Shift 大写行为照旧。编辑器预览与保存也遵循同一优先级。
- **26 键字母布局的宏键**去掉了冗余的「显示文本」字段（取值与标签完全相同），并在编辑器里为按键类型下拉框补上本地化中文说明。
- **子模式下拉框补「默认」项**：方案一旦有了专用布局，仍能回到「默认」项编辑那份被所有无专用布局方案共用的默认布局。
- **分体键盘布局可按子布局单独配置**：编辑器里「方案下拉框」的正下方多了一个 **「分体键盘布局」勾选框**——勾上就进入**当前选中那个子布局**（默认布局、或某个方案）的分体布局编辑；该子布局还没有分体布局时，会先问一句「要从它当前的排列复制一份开始编辑吗」，不静默新建。磁盘上就写在每个子布局对象内部，`__variant__:split` 与它的 `default` 平级：

  ```json
  "rime": {
    "default": [...],
    "__variant__:split": [...],
    "倉頡五代": { "default": [...], "__variant__:split": [...] }
  }
  ```

  于是「给倉頡五代单独配一套分体键位、其它方案沿用默认那份」这件事在文件里一眼可见。**没配分体布局的布局行为与以前完全一致**——存出来的 JSON 逐字节不变。只有「普通」和「分体」两种排列：横屏不再单独占一种形态，屏幕变宽只是把同一套行拉伸得更宽，真需要另一套行的只有分体。
- **按键级「分体键盘在本键之后断开」**：分体默认按宽度取每行的几何中点断开，遇到空格桥接键或不等宽键难以预测；在按键编辑里勾选该项即可指定该行的断点（同一行勾多个以最后一个为准，且该行不再自动复制中间键）。
- **分体键盘不再强制压缩按键**：设置「键盘自定义 → 分体键盘」新增「强制左右两半在中缝处对齐」，默认开启（与旧行为一致）。关闭后两侧各自靠外沿固定、**每一枚键都保持它在合体状态下（也就是布局文件里写的）那个宽度**，中缝不再是预留的固定宽度而是两侧排完后**挤剩下的空间**，代价是各行断点位置可能不齐——但按键不会再为了让两侧等宽而被压窄。配合「空白占位键」即可把某一侧往中缝方向推：**分体布局的每一行都适用**，占位键占多宽就推多远、放在半边的哪个位置都行，且**不会影响同行其它键的宽度**（`SplitRowWidths` 里承载键按池内归一化、占位键按绝对宽度位移，`breakpointWidths` 让占位键不参与断点计算，18 条单测在 `SplitRowWidthsTest` 钉住这些不变量）。唯一例外是含空格键的那一行——中缝由空格自己承担，不走这套位移逻辑。
- **新增「空白占位键」按键类型**：`"type": "PlaceholderKey"`，只占位置、**完全不接收触摸**（不是"点了没反应"——`behaviors` 与 `popup` 都为空，`applyBehaviorPopupBindings` 据此把视图置为不可交互，因此不震动、无按压高亮，也不会吃掉落在它上面的滚动）。主副字符都可留空，默认形态就是键盘上一块完全看不见的空白；打开「自定义颜色」（写入 `"transparent": false`）则画成纯装饰键——有底色、有间距、可单独配色，但依然点不动。字符按原样显示，不参与 Shift/Caps 大小写与标点映射。
- 修复 `?123` 与 BACK 的层历史、宏 `layer to` 离开数字层时的手动记忆释放、拖拽后按键不归位等一批布局编辑器与键盘问题。
- 布局草稿改存私有文件（避免 `TransactionTooLargeException`），快照名经过白名单与 canonical path 校验，阻止路径穿越。

### 五、设置与外观

- **设置首页重构为两层六入口**，原「高级 → 引擎配置」这一层已取消（少一次点击），并**新增顶部搜索框**：支持中文名、英文别名与拼音，多词之间是「都要命中」的关系；点结果直达对应设置页并自动滚动定位到该项。
- **主题「配置」页的颜色项**按语义分组折叠；**候选栏新增紧凑 / 标准 / 宽松三组预设**；Monet 编辑门禁不再静默失效而是明确提示；主题卡片补上「导出」可见入口。
- 主设置页「输入法」改名「**中州韵设置**」并直达 Rime 配置页；「虚拟键盘 → 键盘工具」改名「键盘自定义」。

### 六、稳定性与性能

- **候选栏**改用前后缀结构 diff 与增量刷新，减少额外帧延迟、重复 measure/layout 与每键分配；字体、键盘与候选栏的加载刷新做了缓存与去重。
- **IO 移出主线程**：布局解析与保存、主题与壁纸解码、图标主题导入导出、剪贴板图片与分词、分享接收与二维码长图、网络与同步数据等；并为缓存、ZIP、图片、HTTP、同步数据设置边界与上限。
- Kawaii Bar 中央按钮行改为确定性布局，修复开关悬浮键盘、开关单手键盘或进出扩展窗口后中间按钮整排消失的问题；修复自定义图标按钮左右多出的空隙。
- 修复语音输入、剪贴板同步、备份与迁移、键盘弹窗与多点触控、布局编辑器生命周期、引擎守护与事件流等问题；剪贴板同步的历史增量不再跳过游标、前台服务类型与空闲降频修正。
- 日志与隐私：release 版的语音与剪贴板原文不再写入日志，热路径不再构造被丢弃的 DEBUG 字符串。

### 七、品牌与分发

- 中文应用名定为**靓企鹅·中州韵**（仅简繁中文，其余语言保持 `Fcitx5.fx.rime`）。
- **CI**：`push` 触发 `arm64-v8a` Release 构建，`pull_request` 触发无签名的 JVM 单测与 Debug APK 构建；`fx-rime-only` 构建成功后自动创建带时间戳的 **Nightly 预发布版**并附带 APK。
- 文档集中在 [`docs` 分支的文档站](https://sandyyur.github.io/fcitx5-android/)（用户指南、维护者交接与审阅报告；2026-10-05 起，原 `rime-docs` 分支已并入），代码分支保持纯代码历史。

## 「Rime-only」的含义

- 原独立的 fcitx5-rime 插件**已并入主 APK**（静态链接 `librime.a`），不需要再安装插件。
- 原版的拼音、码表、custom phrase native 链路及其相关模块，以及其他语言/功能插件（anthy、chewing、hangul、jyutping、sayura、thai、unikey、text-editor、clipboard-filter）、Android 英文键盘 addon、第三方插件发现与运行时框架**均已移除**；`mainline` flavor 也已删除，只保留 `fx`。
- **保留**：OpenCC、Fcitx 核心底盘、剪贴板、主题、候选栏、语音输入、数据同步，以及 librime 自带的 Lua/octagram 等能力。

也就是说，「Rime-only」只表示**只提供 Rime 作为输入引擎**，不表示删掉应用的其他辅助功能。应用内不预置方案，也不附带 `essay.txt`（约 6 MB）。

## 更多帮助

安装、迁移、Rime 配置、布局与宏、主题、剪贴板同步、更新和**故障排查（第 11 节）**都在[用户指南](https://sandyyur.github.io/fcitx5-android/manual/RIME_ONLY_USER_GUIDE_zh-CN)里。常用的几条：

- **键盘不出现**：确认已在系统中启用本输入法，并用系统输入法切换器重新选择。
- **有键盘但没有候选**：先按上面「快速上手」放入方案并部署。
- **修改 YAML 后没有变化**：Rime 配置一般需要重新部署；确认改的是当前 profile 的用户目录。
- **想回退版本**：先导出完整用户数据，再卸载旧包、安装新包并导入（卸载会清除应用私有数据）。

## 开发与维护

### 构建

需要 Java 17、Android SDK/NDK 与 CMake：

```bash
git submodule update --init --recursive
./prepare_personal_build.sh
./gradlew :app:assembleFxDebug    # 或 :app:assembleFxRelease
```

产物分别在 `app/build/outputs/apk/fx/debug/` 与 `app/build/outputs/apk/fx/release/`。debug 变体包名为 `org.fcitx.fcitx5.android.fx.rime.debug`，**可与 Release 版共存安装**，适合真机对照测试。当前没有 `mainline` flavor，也没有 `assembleMainline` 任务。

Rime 共享数据位于应用内部 `usr/share/rime-data`（只含 `default.yaml` 等 prelude 通用预设资源）；用户数据位于该包 external files 目录下的 `data/rime`。

### CI 与发布

- push 触发 Commit CI：Ubuntu 22.04 / `arm64-v8a` 构建 `:app:assembleFxRelease`；另有独立 `unit_test` job 运行 `:app:testFxDebugUnitTest`，其失败会在提交上显示红叉但**不阻塞出包**。lint 与 instrumentation 不在 CI 覆盖范围内。
- `fx-rime-only` 构建成功后自动创建带时间戳的 Nightly prerelease，并附带 APK。
- Release 签名依赖仓库 secrets：`SIGNING_KEY`、`KEY_ALIAS`、`KEY_PASSWORD`。

### Rime 引擎来源

- **适配层**（tabs、schema 选单、Shift/alt-trigger 定制）来自 [SandyYuR/fcitx5-rime](https://github.com/SandyYuR/fcitx5-rime)。CI 运行 `prepare_personal_build.sh` 动态 checkout 其 master（当前 `107502d`，官方 5.1.17 基线 + fxliang 全部定制 + 部署期按键吞掉、键盘内部署提示与非法音节 tab 过滤等修复），并把 `fcitx5-alt-trigger-v4point1.patch` 应用到 Fcitx5 core。
- **librime 静态库**来自 [SandyYuR/prebuilt](https://github.com/SandyYuR/prebuilt)，由 [SandyYuR/prebuilder](https://github.com/SandyYuR/prebuilder) 固定官方 librime 提交（当前 `1.17.0-ef1a16a`）并按固定顺序应用 8 个定制补丁——fxliang 功能补丁、音节缓存、词典并行部署、用户词典缓存、词典文件重映射修复、忘记词汇连删同音词修复、万象（amzxyz）的 `rewrite` 滤镜（PR #1232），以及**必须排在末位**的配置指纹补丁——构建四 ABI 静态库后自动推回 prebuilt，主仓库再静态链接进 APK。
- 更新后需同步 `app/licenses/libraries/` 下的版本元数据，并验证 `RimeGetInputTabs` / `RimeSelectTab` 等定制 API、APK 构建与真机输入行为。
- ⚠️ 补丁顺序是契约的一部分，末位的指纹补丁必须始终最后应用；**不要**用官方 prebuilt 覆盖 SandyYuR 产物，否则会静默丢掉 tabs、音节缓存、用户词典缓存等定制。

需要改引擎、改 CI 或重写历史前，请先读[交接报告](https://sandyyur.github.io/fcitx5-android/maintainer/HANDOVER-rime-only)（文档站 `docs` 分支）：里面有完整的引擎更新 runbook、故障经验与易错点。本分支以 fxliang 的 `fx` 分支、提交 `3ad25fc9` 为基线继续专用化，逐条改动明细见 `git log`。

## 致谢与许可证

感谢 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android)、[fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android)、[Rime](https://github.com/rime) 及相关项目开发者。

许可证见 [LICENSE](LICENSE) 及应用内第三方库许可证清单。
