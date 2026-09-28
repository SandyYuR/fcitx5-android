# 靓企鹅·中州韵（Rime-only）

把 [Rime 中州韵](https://github.com/rime) 深度内置进 Android 输入法的版本：一个 APK 里同时包含 Fcitx5 底盘、Rime 引擎与键盘界面，**Rime 是唯一的输入引擎**。基于 [fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android) 的 `fx` 分支继续开发，全部改动由 vibe coding 完成。本项目不是 Fcitx5 或 Rime 的官方发行版。

| | |
|---|---|
| 中文应用名 | **靓企鹅·中州韵** |
| 其他语言应用名 | Fcitx5.fx.rime |
| 包名 | `org.fcitx.fcitx5.android.fx.rime` |
| 下载安装 | [GitHub Releases](https://github.com/SandyYuR/fcitx5-android/releases)（含带时间戳的 Nightly 预发布版） |
| 用户手册 | [靓企鹅·中州韵 简体中文用户指南](https://github.com/SandyYuR/fcitx5-android/blob/rime-docs/docs/RIME_ONLY_USER_GUIDE_zh-CN.md) |
| 开发分支 | `fx-rime-only`（文档集中在 [`rime-docs` 分支](https://github.com/SandyYuR/fcitx5-android/tree/rime-docs)） |

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

### 界面与自定义

- 三类符号数据都可以换成你自己的 JSON，设置页提供选择与导入。
- 键盘布局编辑器的子模式下拉框新增显式「默认」项，方案一旦有了专用布局，也不再丢失编辑默认布局的入口。
- 剪贴板窗口支持历史实时搜索：进入后工具栏中间变为搜索框，打字即时过滤，结果以卡片显示在键盘上方（只查本机历史，不外发数据）。
- 工具栏支持亮/暗主题一键切换，以及一键恢复 Monet 默认配色映射。
- 支持导入、选择并持久化自定义按键音（WAV/MP3/OGG/M4A/FLAC），音效不可用时回退系统音效。
- 宏按键的「应用操作」支持切换系统输入法与弹出 Rime 方案选单两个动作。
- 应用内「关于 → 当前版本」可检查更新并查看发布说明。

### 稳定性与性能

相对 fxliang 的 `fx` 分支，本分支在这些方面持续做了改进：

- **冷启动不再每次都全量部署**：原先 Rime 用数据目录的时间戳判断配置是否改动，而用户词典落盘、垃圾清理、同步临时目录等无关活动都会推新时间戳；现改为对配置文件求内容指纹，只有配置真的变了才部署。
- **部署期间的按键不再「漏」到输入框**：此前这些键既没进编码区、又被当作普通字符直接上屏，表现为「刚切回来打前几个字母，字母没进编码却直接出现在文字里」；现在按键只被丢弃，并在键盘上显示「正在部署」提示。
- Kawaii Bar 中央按钮行改为确定性布局，修复开关悬浮键盘、开关单手键盘或进出扩展窗口后中间按钮整排消失的问题。
- 候选栏改用结构 diff，减少额外帧延迟、重复 measure/layout 与每键分配；字体、键盘与候选栏的加载和刷新做了缓存与去重。
- 布局、主题、字体、剪贴板图片、分享与网络等 IO 工作移出主线程，并为缓存、ZIP、图片、HTTP 与同步数据设置边界与上限。
- 修复语音输入、剪贴板同步、备份迁移、键盘弹窗、多点触控、布局编辑器生命周期等问题，以及数字布局覆盖、`?123` 与 BACK 的层历史。
- 布局编辑器草稿改存私有文件（避免 `TransactionTooLargeException`），快照名经过白名单与 canonical path 校验，阻止路径穿越。

## 「Rime-only」的含义

- 原独立的 fcitx5-rime 插件**已并入主 APK**（静态链接 `librime.a`），不需要再安装插件。
- 原版的拼音、码表、custom phrase native 链路及其相关模块，以及其他语言/功能插件（anthy、chewing、hangul、jyutping、sayura、thai、unikey、text-editor、clipboard-filter）、Android 英文键盘 addon、第三方插件发现与运行时框架**均已移除**；`mainline` flavor 也已删除，只保留 `fx`。
- **保留**：OpenCC、Fcitx 核心底盘、剪贴板、主题、候选栏、语音输入、数据同步，以及 librime 自带的 Lua/octagram 等能力。

也就是说，「Rime-only」只表示**只提供 Rime 作为输入引擎**，不表示删掉应用的其他辅助功能。应用内不预置方案，也不附带 `essay.txt`（约 6 MB）。

## 更多帮助

安装、迁移、Rime 配置、布局与宏、主题、剪贴板同步、更新和**故障排查（第 11 节）**都在[用户指南](https://github.com/SandyYuR/fcitx5-android/blob/rime-docs/docs/RIME_ONLY_USER_GUIDE_zh-CN.md)里。常用的几条：

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

- **适配层**（tabs、schema 选单、Shift/alt-trigger 定制）来自 [SandyYuR/fcitx5-rime](https://github.com/SandyYuR/fcitx5-rime)。CI 运行 `prepare_personal_build.sh` 动态 checkout 其 master（当前 `9bf94e6`，含部署期按键吞掉与键盘内提示两处修复），并把 `fcitx5-alt-trigger-v4point1.patch` 应用到 Fcitx5 core。
- **librime 静态库**来自 [SandyYuR/prebuilt](https://github.com/SandyYuR/prebuilt)，由 [SandyYuR/prebuilder](https://github.com/SandyYuR/prebuilder) 固定官方 librime 提交（当前 `1.17.0-ef1a16a`）并按固定顺序应用 8 个定制补丁——fxliang 功能补丁、音节缓存、词典并行部署、用户词典缓存、词典文件重映射修复、忘记词汇连删同音词修复、万象（amzxyz）的 `rewrite` 滤镜（PR #1232），以及**必须排在末位**的配置指纹补丁——构建四 ABI 静态库后自动推回 prebuilt，主仓库再静态链接进 APK。
- 更新后需同步 `app/licenses/libraries/` 下的版本元数据，并验证 `RimeGetInputTabs` / `RimeSelectTab` 等定制 API、APK 构建与真机输入行为。
- ⚠️ 补丁顺序是契约的一部分，末位的指纹补丁必须始终最后应用；**不要**用官方 prebuilt 覆盖 SandyYuR 产物，否则会静默丢掉 tabs、音节缓存、用户词典缓存等定制。

需要改引擎、改 CI 或重写历史前，请先读[交接文档](https://github.com/SandyYuR/fcitx5-android/blob/rime-docs/docs/HANDOVER-rime-only.md)（`rime-docs` 分支）：里面有完整的引擎更新 runbook、故障经验与易错点。本分支以 fxliang 的 `fx` 分支、提交 `3ad25fc9` 为基线继续专用化，逐条改动明细见 `git log`。

## 致谢与许可证

感谢 [Fcitx5 for Android](https://github.com/fcitx5-android/fcitx5-android)、[fxliang/fcitx5-android](https://github.com/fxliang/fcitx5-android)、[Rime](https://github.com/rime) 及相关项目开发者。

许可证见 [LICENSE](LICENSE) 及应用内第三方库许可证清单。
