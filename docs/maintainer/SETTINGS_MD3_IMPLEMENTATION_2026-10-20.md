# MD3 设置界面实施记录与合并说明

> 2026-10-20。对应代码 [PR #5](https://github.com/SandyYuR/fcitx5-android/pull/5)，目标分支 `fx-rime-only`。用户在安装 `f3e4e210` 对应版本后反馈「感觉可以了」，要求整理合并材料，随后新增设置页跟随键盘主题取色的需求，本次一并交付。本文归档合并准备时的交付快照，最终合并状态与发布版本以该 PR 和仓库 Releases 为准。
>
> 原始设计见[2026-10-18 规划](/maintainer/SETTINGS_MD3_PLAN_2026-10-18)；当前操作入口以[设置界面外观与动画](/features/theme/settings-appearance)和[用户指南](/manual/RIME_ONLY_USER_GUIDE_zh-CN)为准。

> **代码已合并**：2026-10-20 19:45 UTC（北京时间 2026-10-21 03:45），PR #5 经 Squash 合入默认分支 `fx-rime-only`，合并提交为 `69fa5a9b8cff32c855373b1fc496a605c0ef753d`。下文保留合并准备快照；最终核对与合并结果见第 6 节。

## 1. 代码与分支

| 项目 | 本次记录 |
| --- | --- |
| MD3 体验确认版本 | `f3e4e21033ee1ddfeb3d24b5f47f28427b84b1c1` |
| 合并准备所同步的默认分支 | `53038c225a41372498c6aab2703c3ceab1e34b50`，Android 10 及以下主题圆角崩溃修复 |
| 整理后代码分支 HEAD | `b741ffa609927b1a6b682bff02dfbb729fb5f22f`，新增键盘主题取色并修复颜色资源加载时机 |
| 代码分支 | `codex/md3-settings-rebuild` → `fx-rime-only` |
| 文档分支 | `codex/docs-md3-settings-merge` → `docs` |

同步默认分支采用普通合并提交；已推送的迭代历史保留。代码 PR 建议使用 **Squash and merge**，标题可用「重建 MD3 设置与首次启动引导，完善主题和返回动画」。

合并顺序：先将代码 PR 合入 `fx-rime-only`，确认默认分支构建结果，再合入面向 `docs` 的配套文档 PR。代码合并成功后的默认分支 CI 会按已有机制生成 Nightly；文档合入 `docs` 后由 Docs 工作流发布。合并准备阶段没有创建正式 Release、语义化 tag，也没有直接发布文档站。

## 2. 已交付范围与相对规划的调整

| 规划项 | 实际交付 |
| --- | --- |
| 保留六入口与搜索 | 日常调整按输入与候选、外观、键盘、便捷功能排序；按需进入仍为数据与备份、高级；摘要单行显示，搜索保留直达与定位 |
| 标题与菜单 | 每页持有自己的标题、内容和菜单，整页参与转场；首页较短的可折叠标题，子页紧凑顶栏；保留更多菜单和编辑动作 |
| MD3 组件与颜色 | 设置 Activity 统一主题、系统栏和遮挡处理；分段圆角列表、Material 开关／复选框／按钮／对话框；编辑器外壳使用对应容器和前景色 |
| 设置界面外观 | 系统／浅色／深色、纯黑、跟随键盘主题、系统 Monet、7 个手动色系、6 种手动配色风格、3 档对比度；预览草稿经「应用」保存 |
| 外观入口位置 | 按用户反馈将「设置界面」分组放到外观页最底部；动画只在「设置界面外观」内保留入口，搜索路径同步 |
| 动画与预测性返回 | 5 种样式、独立返回选择、350 / 500 / 650 ms 三档速度、Android 14+ 预测性返回开关与恢复默认动作 |
| 首次启动引导 | 两步流程复用设置主题，采用 MD3 按钮、步骤进度、完成卡片和可滚动正文；保持原有启用、选择、跳过及通知入口 |
| 常见问题 | 首页更多菜单打开 `https://sandyyur.github.io/fcitx5-android/` |

保留 View / Fragment / Preference 架构。没有引入 Compose 或 Miuix 运行库；参考项目用于导航节奏、配色和选项组织。依赖声明增加了 **Fragment KTX 1.8.9** 的显式约束，以支持可按进度控制的预测性返回，不能把整个 PR 说成「依赖声明完全未变」。Material Components 继续使用已有的 1.14.0。

## 3. 需要保留的实现约束

### 页面转场

- `SettingsPage.kt` 在页面首次绘制前确定标题，并将标题、菜单和内容放进同一 `transitionGroup`，处理首页标题滞留和子页进入后再替换标题的跳变。
- `SettingsPageMotion.kt` 使用 Material / AndroidX Transition，前进横向或纵向的旧页必须完整移出视口。此前的四分之一退出距离会在旧页仍留在屏内时清理退出视图，导致突然消失。
- 返回继续使用前景整页退出和底层四分之一距离视差；保留框架的手势进度控制与取消恢复。
- 不在 Fragment 的 Transition 上长期绑定具体 View 目标，避免恢复页面后继续引用已销毁的旧 View。修改动画类型时需同时考虑进入、返回、取消及恢复。

### 主题与状态

- 应用自有设置／编辑器和首次引导复用 `SettingsUiActivity`；IME 窗口、键盘预览内容仍由 `ThemeManager` 管理。透明浮层和系统页面不套用设置主题。
- 设置模式在上下文绑定时确定，动态色和纯黑覆盖在 AppCompat 完成基础主题初始化后、内容创建前应用，避免主题覆盖、多余重建和预览不一致。
- 颜色查询须处理颜色字面量、资源引用和 `ColorStateList`；不要把主题资源 ID 当成颜色整数，也不要在主题缺少颜色角色时直接强制解析。这是布局、弹出字符等编辑器早期崩溃的主要修复点。
- 外观采用草稿与「应用」，动画立即保存。恢复默认外观和恢复默认动画是两个作用域，不可合并为同一个模糊入口。
- 偏好仍以独立的 `settings_ui_*` key 存储。旧 180 / 300 / 450 ms 按档位读取为 350 / 500 / 650 ms；原键盘主题、布局、备份和数据路径契约未改变。

### 跟随键盘主题配色

- `settings_ui_follow_keyboard_theme` 默认关闭，读取 `ThemeManager.activeTheme` 已解析的当前主题；保留原有 `palette`、`manualPalette` 和手动配色风格，关闭后恢复。
- `SettingsKeyboardColors` 从实际强调色、辅助按键色、选中态和背景色建立 HCT 色阶，使用 Material 的动态颜色角色与对比度规则生成前景和容器色；背景色阶控制彩度，不解码主题图片。主题模式、纯黑和对比度设置继续生效。
- 复用项目已有 Material Components 1.14.0 的色彩工具和资源加载器，没有新增运行时依赖。适配层集中调用该库的 library-group API，升级 Material 版本时应核对接口。`ResourcesLoader` 要求 Android 11+；旧系统显示版本要求，保留既有配色。
- 加载器限定在设置 Activity 与预览的上下文，避免更改应用／IME 的资源。AppCompat 完成资源与明暗配置初始化后，安装颜色资源和覆盖，再应用纯黑覆盖；提前在 `attachBaseContext` 装入的加载器可能被 AppCompat 的上下文包装丢失。预览使用对应的独立上下文。
- 主设置监听当前键盘主题，比较参与配色的颜色后重建并恢复导航与外观草稿；键盘尺寸等无关偏好不触发重建。从后台返回时也同步。独立编辑器创建时取色，编辑期间不因主题通知而重建，以保留未保存内容。

### 颜色资源与体积

保留全部 **222 套预设主题**及 **11,766 个颜色角色赋值**。独立颜色项已内联进主题样式，生成器位于 `tools/settings-ui-palettes/`；后续新增或更新配色要同时维护生成器、清单及生成的 XML。

可复核的同流程 arm64 签名 Release 记录：

| 版本 | APK 字节数 | 说明 |
| --- | ---: | --- |
| `0458aae4` | 12,941,492 | MD3 早期迭代、颜色资源优化前；不是改造前的原始 APK |
| `918d48b3` | 11,807,324 | 放缓动画、整理外观入口与首页摘要 |
| `f3e4e210` | 11,825,079 | 修复前进退出距离并加入 MD3 引导；比上一轮增加 17,755 字节 |
| `c6b54aa2` | 11,825,083 | 同步默认分支修复并整理 README，键盘取色开关加入前 |
| `b741ffa6` | 11,861,266 | 增加当前键盘主题取色；比上一行增加 36,183 字节，约 35.3 KiB |

`f3e4e210` 历史包版本为 `0.1.3-724-gf3e4e210`，SHA-256 为 `f2275a690e0c8313b16d35ff5c6e4c0a6a736fba18642c38dcb9fa2cafa63741`。

当前 `b741ffa6` 包版本为 `0.1.3-729-gb741ffa6`，versionCode 为 112，SHA-256 为 `83f482518978ec765a51c8a604933a625d317ef46bc8eecc7edd689de6b3d2bd`；签名证书与前述版本一致。资源表为 2,080,444 字节，222 套预设与 11,766 个原有角色赋值均保留。键盘取色复用现有依赖，新增体积来自实现与运行时颜色资源等保留内容；应按同架构、同构建流程比较实际产物。

## 4. 验证记录与边界

- `f3e4e210` 四 ABI Debug 构建通过；[PR CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/39429545939)与[签名 Release 构建](https://github.com/SandyYuR/fcitx5-android/actions/runs/39429586462)通过。
- 引导两步实际 View 的浅色未完成、深色完成状态画面已查看。临时截图工具没有加入项目或 APK。
- 2026-10-20 用户安装后反馈「感觉可以了」，并要求整理 PR 和文档准备合并。这是本次体验反馈，不能扩展为完整设备／系统版本矩阵已通过。
- Android 36 AOSP ATD 没有 KVM 和 SystemUI；转场只取得少量稀疏采样，不能当作连续动画、真实帧率或物理系统返回手势验收。
- 同步默认分支后的 `c6b54aa2` 的 [PR CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/39435500494)与[签名 Release 构建](https://github.com/SandyYuR/fcitx5-android/actions/runs/39435492158)通过，arm64 签名包为 11,825,083 字节。
- 新增键盘取色后的 `b741ffa6` 四 ABI Debug 构建、[PR CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/39440135516)和[签名 Release 构建](https://github.com/SandyYuR/fcitx5-android/actions/runs/39440183364)均通过，CI 包含仓库已有的单测；该合并准备阶段未另加或运行本地测试套件。用户后续要求最终检查时的重跑结果见第 6 节。
- 已查看键盘 MaterialLight 取色下的实际设置外观页和预览：强调色、容器色和固定角色均生效。设置 Activity 与应用上下文使用不同的 Resources，应用上下文中的对应颜色资源保持原值。
- 已查看深色模式中 MaterialLight → DeepBlue 的切换，设置页与预览同步更新；点击关闭键盘取色并「应用」后，原手动莓果选择保留，设置页恢复对应颜色。截图工具仅位于临时目录，不加入项目或安装包。
- 配套文档 `npm --prefix docs run build` 通过，锁定依赖文件未改变；文档 PR 为 [#6](https://github.com/SandyYuR/fcitx5-android/pull/6)，目标分支 `docs`。代码与文档均按用户此前要求保留中文草案状态。

## 5. 合并时核对

1. PR #5 的最新 HEAD 包含默认分支 `53038c22`，不存在冲突，最新 HEAD 对应的检查通过。
2. 文档 PR 的目标是 `docs`，用户指南、功能页、规划状态、交接报告与站点导航一起合并；文档构建命令为 `npm --prefix docs run build`。
3. 原规划保留为历史设计；实际分组位置、独立动画入口、速度档位和首次引导等，以本记录及用户手册为准。
4. 合并后按实际生成的合并 SHA / Nightly 更新发布记录；不把本分支提交或旧测试包预先称作默认分支发布版。

## 6. 最终核对与代码合并

- 用户要求「最后检查一遍，合并入默认分支」后，重新检查颜色资源加载顺序、主题监听与恢复、编辑器颜色查询、页面转场及首次引导，未发现阻塞合并的新问题。
- 最终本地 `:app:testFxDebugUnitTest :app:assembleFxDebug` 通过：**479 项单测，0 失败、0 错误、0 跳过**，四 ABI Debug 构建成功。文档 `npm --prefix docs run build` 再次通过。
- 代码 PR #5 已解除草案并采用 Squash 合并，默认分支提交为 `69fa5a9b8cff32c855373b1fc496a605c0ef753d`；其文件树与合并前核对的 `b741ffa6` 完全一致。合并前最新 PR CI 与签名 Release CI 均通过。
- 默认分支 [Commit CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/39486196495)已由合并自动触发；结果与 Nightly 产物以该次工作流为准。
- 配套文档由 [PR #6](https://github.com/SandyYuR/fcitx5-android/pull/6)交付至 `docs` 分支，其当前合并与发布状态以 PR 和 Docs 工作流为准。此前「保留草案」描述记录的是合并准备阶段。
