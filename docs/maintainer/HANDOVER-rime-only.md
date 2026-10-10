# 交接报告 — fx-rime-only（靓企鹅·中州韵，rime 专版）

> **【2026-10-05 迁移注记】** 本文档已随全部维护者文档从 `rime-docs` 分支迁入文档站 **`docs` 分支**（VitePress 站点源码，本文件位于 `docs/maintainer/`）；`rime-docs` 分支已删除。本文的 runbook、机制说明与历史记录仍然有效；文中出现的 SHA、提交计数、分支/工作区快照均为写作时点事实，一律以 git 实测为准。

> 本文写给接手的下一个代理。仓库 `SandyYuR/fcitx5-android`（fcitx5-android 的 fork）。
> 目标分支 **`fx-rime-only`**（2026-09-07 由 `fx2-rime-fusion` 更名而来；本地主仓库目录亦于 2026-09-10 由 `fx2-rime-fusion` 更名为 `fx-rime-only`，与分支同名），计数基线 `3ad25fc9`。
> 日常操作规范见本机工作区根 **`D:\GitHub\fx2-rime\AGENTS.md`**（2026-09-08 首建于仓库根，09-10 文档分拆时移出仓库，DSH 每次会话自动加载，其信息源优先级**高于**本文）；本文的价值在 runbook 与故障经验，动态状态以 git 实测为准。
> 本文只描述"领先基线的全部改动"与"未完成的工作"。
>
> **2026-09-04 晚更新**：分支已把 `review-fx2-fixes` 的 6 个新提交 rebase 到本分支所有改动**之前**，
> 因此当时已有的 **37 个 rime 专版提交的 SHA 全部改写**、已 force-push。第 2 节有新旧对照与新 SHA。
> **2026-09-05 更新**：已同步当前工作区路径、worktree、未跟踪文件和分支状态；此前已解决的事项不再列入待处理问题。
> **2026-09-06 更新**：① 工作区重组——主仓库移入 `D:\GitHub\fx2-rime\fx2-rime-fusion\`，项目根下新增三个引擎相关 fork 的本地克隆，`日志/` 移出 git 工作区；② CI 的 rime 来源从 fxliang fork 换成 **SandyYuR 自己的 fork**（fcitx5-rime 已合并上游最新，prebuilt 保持 fxliang 终态快照），见第 0 节新表格；③ **rime 引擎更新流水线打通**——librime 从 `1.17.0-1d0df6e` 更新到 `1.17.0-3cbe4af`（上游最新，fxliang 补丁全保留），完整 runbook 见**第 0.5 节**。
> **2026-09-07 更新**：修复「切走再切回输入法，键盘好几秒弹不出来」——IME 退出路径改为直接调 `saveWithoutRime()`，不再触发 Rime 全量用户数据同步（见 3.4 节 `92561244`）。
> **2026-09-09 更新**：① prebuilder 合并 fxliang 3 个新提交（`ad491c7`，**0.5.4 节**新 runbook 实录），新引擎已构建落地 `prebuilt@9aa8104c`；② fcitx5-rime 当日晨已合并官方 5.1.16（`e74ddb6`，官方 updateUI 修复替代 `ce4c038` 定制，见第 0 节补注）；③ 主仓库新增 11 提交（`0a082669..a8c28d5a`），分支更名 `fx-rime-only`、恢复 nightly release、新增 `agent.md` 操作指南；④ librime 上游 streaming_chord 新提交待下次引擎更新（09-10 起并入 0.5.5 表统一跟踪）；⑤ **`fx2` 分支连同其 release/CI 已按用户要求全部删除**（见第 2 节注），仓库现存分支仅 `fx-rime-only`、`pr/fx2-integrated`、`docs`、`master`。
> **2026-09-10 更新**：① prebuilder 合并 fxliang `4fdb494`（userdict 外部更新失效修复，自动合并零冲突）并 bump librime pin `3cbe4afb`→`35f23e97`（四补丁栈实测全部干净应用 + 回环零差异），已推送 `SandyYuR/prebuilder@446d1ea`，CI run 34472331394 绿，新引擎落地 `prebuilt@9e631eb9`（四 ABI .a 全更新，`rime_api.h` blob 与补丁产物一致、tabs/para 全符号在列），主仓库已接 `librime.json → 1.17.0-35f23e9`（**0.5.6 节**第三次实战）；② 全链路其余检查点零新增（fcitx5-rime 两侧上游、librime 上游仅纯 CI 提交、fxliang 官方 prebuilt 禁合，见 0.5.5 表）。
> **2026-09-10 文档分拆**：应用户要求，项目文档集中到 **`rime-docs`** 分支维护（孤儿分支，只含文档文件；上游遗留的 `docs` 分支是 GitHub Pages 文档站，勿混淆）。`fx-rime-only` 已重写历史：剥离 31 个纯文档提交、从 6 个混合提交中移除文档部分（README.md 的代码性改动保留），重写后 154 个提交（基线 `3ad25fc9` 之上）。**本文档内引用的 fx-rime-only SHA 均为重写前历史**——完整保存在 tag `archive/pre-doc-split`（指向重写前 tip `b3da998e`，184 提交全量）与本地 `backup/pre-doc-split` 分支；重写后的新 SHA 以 git 实测为准。`agent.md` 移出仓库，落地本机 `D:\GitHub\fx2-rime\AGENTS.md`（DSH 自动加载，内容已同步本次分拆）。
> **2026-09-10 提交标题重写**：应用户要求，`fx-rime-only` 历史第二次重写——全部提交标题统一为 `类型(模块): 内容` 格式（旧标题多为 `fix(C32)` 这类审查编号，模块不可见），并把 19 组同模块同类型的相邻提交合并，154 → 128 个提交；随后按用户要求将 CI release 描述改为「注意：此版仅可使用Rime输入方案（插件已合并）」。**源码零改动**（重写前后 tree hash 完全相等）。当前 129 个提交（含 release 描述修改）。旧→新 SHA 对照：合并组正文自带合并清单；完整映射表在本机 `D:\GitHub\fx2-rime\日志\提交SHA映射-标题重写-2026-09-10.txt`（2026-09-16 复核：原写的工作区根 `_retitle_map_old_new.txt` 已移入该位置）。
> **2026-09-19 更新**：① 修复「长按忘记词汇一次删掉两个同音词」——上游 librime 的 `delete_notifier` 是多播信号，多个继承 `Memory` 的 translator 各自订阅、且旧代码在信号分发中途重建 composition，后续订阅者因此删到另一个候选；新增**第 6 个补丁** `librime-defer-composition-refresh-on-delete.patch` 把重建推迟到分发结束，详见 **0.5.8 节**；CI run 35435515145 绿，引擎落地 `prebuilt@6b5b2ee6`，**pin 未变**（仍 `8d8276f4`，`librime.json` 无需动）；② 候选词手势定型为**按住后滑动**（`fx-rime-only` `2a19ddd3`，CI run 35460755786 绿，Nightly `0.1.3-617-g2a19ddd3`）：按住上滑弹选字窗提交单字、按住下滑呼出操作菜单、按住不动仍是原有长按菜单、未按住时滑动归列表自身——修正了先前直接上滑实现会让展开候选面板无法翻页的问题，设计不变式见本节下一条。
> **2026-09-20 更新（历史第四次重写 + 内置布局更新）**：① `fx-rime-only` 第四次重写历史（纪元③ → 纪元④）：把「两个内置布局更新」从 `e45f041a` 里拆出来做成独立提交 `6bdf2a86`（大同 + Sandy + `BundledPresets.assetSizes`），并合并两组同类提交——`b9358dc0`+`a3da6483` → `caef1547`（键盘布局编辑两个修复）、`09b0e0e6`+`75fbf97c` → `8843b858`（引擎启停，同时消解 `09b0e0e6` 的叙述时序倒置）。14 提交 → 13 提交，逐条 `git diff` 比对树全部为空，最终树相对纪元③**只多** Sandy 布局与 `assetSizes` 两项。**尚未推送**（远程仍是纪元③ `59a3a1ab`）。SHA 对照与新机制见第 2 节。② 内置布局内容更新：大同布局去掉 4 处 `keyboard_height_percent` 覆盖（14611 → 14191），Sandy 布局的 `⌨` 键长按改接 Rime 方案选单宏（45949 → 45963）。**更新内置资源必须同步登记 `assetSizes` 的旧字节数**，详见第 5 节新条目。
> **2026-09-20 更新（第六次引擎实战：合入万象 PR #1232）**：bump librime pin `8d8276f4`→**`74bd5dc4`**，并新增**第 7 个补丁** `librime-pr1232-rewrite-filter.patch`——上游 [rime/librime#1232](https://github.com/rime/librime/pull/1232)「rewrite 改写工具」（作者 **amzxyz / 万象**）。分两轮落地：① `SandyYuR/prebuilder@6e202a0`，CI [run 35498252597](https://github.com/SandyYuR/prebuilder/actions/runs/35498252597) 绿，产物 `prebuilt@a8423ad2`（arm64 `.a` 19,311,312 → 19,775,858），主仓库 `ab06dbfd` 接指针；② **作者于同日 force-push 重写该提交**（`e3c91382`→`16f72b0c`，加 starter filter），跟进为 `prebuilder@2886a2c`，CI [run 35501800444](https://github.com/SandyYuR/prebuilder/actions/runs/35501800444) 绿，产物 **`prebuilt@6c226341`**（19,775,858 → 19,778,482），主仓库该 commit **已 amend 重写为 `45550041` 并 force-push**。`fx-rime-only@45550041` 与 `rime-docs` 均已推送。**两个新坑：① 本机 `prebuilder` 是 `autocrlf=true`，worktree 里的补丁是 CRLF，直接 `git apply` 会假失败——必须先归一化为 LF；② 上游 PR 作者可能 force-push，引用其 head SHA 前必须重新核对（本次就是靠 `git merge-base --is-ancestor` 发现「旧 head 不是新 head 的祖先」才确认被重写）**。完整实录见 **0.5.10 节**。
> **2026-09-16 快照更新（纪元③ 记录，SHA 已被纪元④ 部分改写）**：`fx-rime-only` 相对基线 `3ad25fc9` 共 **143** 个提交（09-10 晚为 129，其后新增 14 个：中央工具栏确定性布局修复、剪贴板实时搜索、内置布局/主题/图标主题三连、CI 单测 job 与 setup-android 修复、候选栏双高亮修复等）；逐提交明细与工作树状态一律以 git 实测为准（标题已自描述，本文不再维护提交清单表格，见第 2 节）。`fx2` 已删除，基线 `3ad25fc9` 仍是祖先，计数口径不变。`提交SHA映射-标题重写-2026-09-10.txt` 已从工作区根移入 `日志\` 目录（第 16 行注写的 `D:\GitHub\fx2-rime\_retitle_map_old_new.txt` 为旧路径）。
> **2026-09-24 更新（第七至十一次引擎实战）**：适配层前进到 `dcd6162`（版本号 5.1.17），fcitx5 core 前进到 `27cca6e0`（5.1.23），librime pin 前移至 `ef1a16aa`，PR #1232 补丁跟进至 `8530499b`。五条引擎元数据改动（pin bump + 四次补丁更新）**已按用户要求合并为一条 `a560aabe`**，中间 SHA 不再存在于分支历史，追产物来源请用 prebuilder 侧 SHA。详见 0.5.13~0.5.15。
> **2026-09-26 更新（状态机改动回退，仅作对照）**：`e7e32462` 回退 `8843b858` 对 Fcitx/FcitxDispatcher/FcitxLifecycle/FcitxDaemon 等 8 个文件的改动，用于排查间歇性「打不出编码 + 状态区空白 + 切换才恢复」是否由该提交引入。**注意这是对照包而非正式修复**：它同时带回了 `8843b858` 原本修掉的三个 P0 缺陷（STARTING 不可停止、启动失败卡非 STOPPED、stop 阻塞主线程）。当前分支**处于这次回退后的状态**——接手时若要处理卡键问题，先读本节末尾的说明。
> **2026-09-28 更新（第十二次引擎实战 + 两个用户可见功能）**：① 新增**第 8 个补丁** `librime-detect-modifications-fingerprint.patch`，修掉「每次冷启动都全量部署、部署期按键直接上屏」——详见 **0.5.16 节**；② 适配层新增两处配套改动 `583e908`（部署期按键吞掉）与 `9bf94e6`（部署提示改为键盘内显示），主仓库 gitlink 前进到 `9bf94e6`；③ 符号/表情/颜文字面板整体换成 Foxy 风格（`4f93612f`），④ 布局编辑器子模式下拉框补「默认」项（`afc57f4b`）。三者均在 `fx-rime-only` 且已推送。
> **2026-10-05 更新（文档迁移 + 近期改动补记）**：① 全部文档自 `rime-docs` 分支迁入文档站 `docs` 分支（`docs/manual/`、`docs/maintainer/`、`docs/archive/` 三个分区），`rime-docs` 分支按用户要求删除；文档站随 push 自动构建发布到 GitHub Pages（`github-pages` 环境需允许 `docs` 分支部署）。② 补记 10-04~10-05 尚未入账的三条：候选栏高亮改三层结构（`18619aef`，见 0.6.10 节新增条目——内距默认 0→8、`ITEM_HORIZONTAL_PADDING_DP` 10→4 且语义变为「格子↔高亮」四边外间距）；工具栏 100% 高度下图标尺寸回归修复（`3664ef31`，恢复 24dp/10dp 设计值）；内置图标主题安装后刷新主题列表（`49c7c169`，修复全新安装时内置图标主题落盘晚于首次扫描、设置页列表为空，安装后触发重扫并串行化首次扫描与重扫）。用户指南 §8.1 候选栏高亮描述已同步修订。
> **2026-10-10 更新（方向性划动标签脱离「标点位置」，`4eae6539`）**：用户报障「个别按键配了下滑操作，主题把标点位置设为上方居中后标签跑到上方、手势也变成只能上滑」。两条独立分叉一起修：① `KeyView` 的方向性标签分支不再读 `punctuationPositionForKey()`——上滑标签恒在上、下滑标签恒在下（紧凑高度退同侧角落），`shouldTriggerAltBySwipe` 只按标签方向判定；② 旧单槽 `swipe` / `swipeLabel` / `altLabel` 的方向判定统一到 `LayoutJsonUtils.legacySwipeTargetsUp`（只有「同侧且唯一」才算上滑），读/迁移/编辑三处共用，`punctuationPosition` 与 `swipeSymbolDirection` 都不再参与；**无线索的旧 `swipe` 保持旧槽位**，运行时仍按「符号划动方向（符号隐藏时）」决定。详见 **0.6.13 节**。
> **2026-10-10 更新（10-07~10-10 一批改动补记）**：主仓库本批 15 个提交（`751480cd`..`4eae6539`）此前未入账，现补记。① 候选栏：长候选横向滚动（`751480cd`）+ 候选项序号角标开关（`635d3aa2`）→ **0.6.14**；② 键盘：同一按键分别配置上/下划动宏（`d156052b` + `4f0d6fb8`）→ **0.6.15**（**方向判定规则已在 0.6.13，本节只补字段/编辑器/迁移面**）；③ 键盘标签：双标签高度回退布局统一（`ed38a31b`）→ **0.6.16**；④ 音节选择栏：超出六项改为可滚动（`098ec596`）→ **0.6.17**（**本文多处"音节 tab 全部展示、不可滑动"随之过时，已在 0.6.17 / 第 132 行 / §0.6.10 / §7 第 6 条就地修正**）；⑤ 弹出键盘：多字符候选改走 CommitAction（`b94106da` + `598897b7`）→ **0.6.18**；⑥ PR 工作流与设备按键兼容开关（`0077c401` / `2609dc2d` / `897ece50`）→ **0.6.19**；⑦ 弹出菜单跟随主题整体背景色（`d786dd78`）→ **0.6.20**；⑧ 随机主题评分与即时应用（`43e16fb6`）→ **0.6.21**；⑨ 编码区圆角与工具栏上方圆角接续（`01d0dfee`）→ **0.6.22**。`4eae6539` 即上一段，已由 0.6.13 覆盖（SHA 已补入该段）。**除个别提交外均只做了单测与出包，真机回归未做**（逐条注明）。

---

## 0. 上手前必读（会踩的坑）

| 事项 | 说明 |
|---|---|
| 目录布局（2026-09-09 实测，09-10 目录更名，09-16 复核文件数） | `D:\GitHub\fx2-rime\fx-rime-only` 是主仓库（检出 `fx-rime-only`）；`fcitx5-rime/`、`prebuilt/`、`prebuilder/` 是引擎 fork；`librime-src/` 是补丁工作台；`rime-docs-worktree/` 是文档 worktree；`布局主题/` 是用户自己的布局/主题/图标主题资料（非 git 内容）；`日志/` 当前 2 个文件（见下方"日志文件"行）。分别在目标仓库运行 `git status`。 |
| worktree | 两个 worktree（09-14 实测）：`D:/GitHub/fx2-rime/fx-rime-only` → `fx-rime-only`（主仓库，与 `origin/fx-rime-only` 同步），`D:/GitHub/fx2-rime/rime-docs-worktree` → `rime-docs`（文档分支）。**文档改动只在 rime-docs worktree 里做**，不要在同一个 worktree 里切分支（切到 rime-docs 会把代码工作树和子模块目录一起掀掉）。 |
| 三个 fork 的分工与更新方式 | **2026-09-28 实测快照（仍应以 fetch 复核实测为准）**：`SandyYuR/fcitx5-rime` → **`9bf94e6`**（官方 5.1.17 基线 + fxliang 全部定制；演进链 `e74ddb6` → `fc3f98b` 移除辅助栏"清除" → `dcd6162` 版本号 5.1.17 → `583e908` 部署期按键吞掉 → `9bf94e6` 部署提示键盘内显示）、`SandyYuR/prebuilt` → **`1126616c`**（Auto update；此前经历 `a1865519`/`eb5bc82e`/`8ad0193f`/`750f6e4f` 等多次）、`SandyYuR/prebuilder` → **`4121ce6`**（配置指纹补丁，pin 仍 `ef1a16aa`）。**三者 SHA 一律以 fetch 实测为准**；本地 remote-tracking ref 可能过时，使用前先 fetch（断连时走 SSH，见 0.5.3 第 8 条）。 |
| **rime 引擎更新流水线（2026-09-06 打通，09-10 第三次实战）** | 引擎 = `SandyYuR/prebuilt` 里的 `librime.a`，由 **`SandyYuR/prebuilder`** 的 CI（`ci.yml`，手动 `workflow_dispatch` 触发，约 15-90 分钟）构建并自动推回 prebuilt（"Auto update" 提交，bot 身份）。已配置 `BOT_TOKEN` secret（token 轮换后要重配）。**完整操作手册见第 0.5 节 runbook + 0.5.4/0.5.6/0.5.16 实战**。当前引擎（2026-09-28，`prebuilt@1126616c`）：**librime 1.17.0-ef1a16a + 8 个定制补丁**（tabs + syllabifier 缓存 + para deploy 词典并行编译 + userdict 缓存三连修复 + mapped-file remap 修复 + 忘记词汇连删同音词修复 + 万象 PR #1232 rewrite 滤镜 + 配置指纹）。补丁清单与顺序见 0.5.16；`librime.json` 的 `artifactVersion` = `1.17.0-ef1a16a`。⚠️ **`librime-detect-modifications-fingerprint.patch` 必须始终排在最后**（见 0.5.16 的教训 3）。 |
| **CI 构建的 rime 来源** | `prepare_personal_build.sh` fetch/checkout SandyYuR 的 fcitx5-rime 与 prebuilt master（均浮动）；fcitx5-rime 适配层现为 `9bf94e6`（官方 5.1.17 + fxliang 定制 + 部署期按键/提示两处修复），主仓库 gitlink 已按该 SHA 对齐（不再是占位）。`.gitmodules` 仍写官方 URL、`fcitx5-rime.json` 的 website 上游仍写 fxliang（本机 `prepare_personal_build.sh` 会 sed 成 SandyYuR），属于元数据残留。 |
| 日志文件 | `D:\GitHub\fx2-rime\日志\` 当前 **3** 个文件（2026-09-28 实测）：`org.fcitx.fcitx5.android.fx.rime-2026-09-28T09_31_06Z.txt` 与 `…T10_23_36Z.txt`（09-28 冷启动/部署期问题定位用，即 0.5.16 的原始证据：部署耗时、按键回吐上屏的时间线），以及 `提交SHA映射-标题重写-2026-09-10.txt`。更早的日志（`候选高亮…09-14`、`工具栏不显示…`、`双击斜杠…` 等）已随问题解决移出目录，对应修复的分析以下文记录为准，目录内容以实测为准。 |
| 子模块（09-14 已初始化） | 主仓库 7 个 gitlink 已全部检出：用 `git submodule update --init --recursive --depth 1 --jobs 4`，并把 GitHub URL 改写成 SSH（`git -c url.ssh://git@ssh.github.com:443/.insteadOf=https://github.com/`）——HTTPS 拉大文件在本机会被限速（25 分钟只下 19MB，SSH 浅克隆 7 分钟完成）。`plugin/rime/src/main/cpp/fcitx5-rime` 与 `lib/fcitx5/src/main/cpp/prebuilt` 已切到 `SandyYuR` fork 的 master（等同 `prepare_personal_build.sh` 的结果），fcitx5 核心的 `fcitx5-alt-trigger-v4point1.patch` 已应用；**prebuilt 是稀疏检出**（只留 `*/arm64-v8a/**` + `opencc/data/**` + `toolchain-versions.json`，约 34MB）。要构建其它 ABI 先 `git sparse-checkout disable`。完整工具链路径见 `AGENTS.md` 第 5 节。 |
| grep 工具 | `app/src/main/play/listings/en-US/graphics/icon/icon.png` 的失效符号链接已修正为指向 `app/src/fx/res/mipmap-xxxhdpi/ic_launcher.png`，现在可以从仓库根目录搜索。若以后再次出现 `os error 2`，先检查该链接目标是否仍存在。 |
| read 工具 | `offset`/`limit` 必须是明确数字（传 undefined 会报 "binding arguments must be lossless JSON"），`limit` ≤ 2000。 |
| GitHub API 返回会被截断 | `/actions/runs` 的 JSON 超 100000 字符会 `Unterminated string`。用 `per_page=3` 或正则抽 token，别整体 `JSON.parse`。 |
| 沙箱内 git 网络的坑 | 首选工作区外 `D:\GitHub\fx2-rime\ssh-push.ps1`（Windows OpenSSH）；备选 `push-with-gcm.ps1` 或 `api-push.mjs`。 |
| 本机构建与签名（09-14 起可用） | 本机已装完整工具链（JDK 17 / Android SDK / NDK / CMake / ECM / gettext，路径与必需环境变量见 `AGENTS.md` 第 5 节）。一键脚本在工作区根：`build-debug.ps1`（debug APK、`-Test` 跑单测、`-Install` 装设备）、`build-release.ps1`（用 `D:\GitHub\fcitx5-signing` 的密钥签 release，产物证书与 CI 官方包一致，可 `adb install -r` 覆盖）。**不必再靠 CI 才能编译**；GitHub 的 secret 只写不可读，别想着把签名密钥从 CI 拉回来。 |
| CI 失败时的调试方法（2026-09-06 两次实战） | ① `Invoke-RestMethod`/curl 都因 schannel 凭证问题不可用，**拉 API/日志用 node**：`GH_TOKEN`（GCM exe 取）+ fetch，重定向要手动跟随；② annotations API 只给任务级摘要，`> Task :app:installProjectConfig FAILED` = `installProject<Config>`（`FcitxComponentPlugin.kt:76` 动态拼名，构建 `generate-desktop-file` cmake target）；③ **CMakeBuildInstallTask 的 providers.exec 会吞掉 cmake/ninja 输出**，真实编译错误只在完整 job 日志里（`actions/jobs/<id>/logs`），或者干脆**本地复现**（09-14 起本机有编译器与 msgfmt，见上一行）；④ po 用严格解析器查重复条目（msgfmt 对重复 msgid 是硬错误）、C++ 用括号平衡检查 + **与两个父版本逐字符对照结尾标点**（`};);` 这类宏结尾模式最容易抄错）。 |

### 2026-09-06 合并上游后两次 CI 失败的记录（已修复）

1. **run #220 `installProjectConfig` 失败**：po 合并脚本的分块假设不成立——fxliang 的 ja.po 里部分条目之间只有一个换行（无空行），脚本把两条目粘成一块，上游同名条目再独立插入 → "All"/"Clear" 重复，msgfmt 硬错误。修复：`b88c57d` 删重复条目。
2. **run #221 `buildCMakeRelWithDebInfo` 失败**：手动解 `rimeengine.h` 冲突时把 `FCITX_CONFIGURATION` 块最后一个成员结尾写成 `false});`，丢了成员声明的分号（正确是 `false};);`）。run #220 死在 msgfmt 没暴露它。修复：`dad45bc`。
3. 教训：**解完冲突必须对照两个父版本（`git show <sha>:<path>`）核对合并块的每一个结尾标点**，"结构看起来对"不算数；po 合并后要跑重复检查；CI 挂了先拉完整 job 日志再动手，别靠猜。

### 2026-09-07 九键音节选择器首次点击无响应（已修复：addon `ce4c038`）

**现象**（日志 `日志/音节选择器...2026-09-07T08_58_32Z.txt`）：打完字后点 tab 无反应（连点 29 次零响应），点一下"清除"就恢复。同日志里 BackSpace 后 tab 又能用。

> ⚠️ 下文的"清除"按钮**已于 2026-09-20 由适配层 `fc3f98b` 移除**（它曾固定占用纵向辅助栏底部、挤压音节 tab 高度，见第 0 节末尾）。本条 bug 的真正修复是 `e74ddb6`，与"清除"按钮是否存在无关，故移除不影响该场景。

**根因**：合并上游时，`8bb9234`（ascii 图标提交）顺手删除了 `rimestate.cpp` updateUI 里的 `emptyExceptAux` 逻辑，把面板序列化条件 `!keyRelease || !oldEmptyExceptAux || !newEmptyExceptAux` 收窄为 `!keyRelease`。上游自己没有 tab 功能所以无害，但 **fxliang 的 tab 功能隐藏依赖 release 时的序列化**：

- 每次 keyEvent（press 和 release）都跑 `updateUI(ic, isRelease)`，它总是 `setCandidateList(make_unique<RimeCandidateList>(...))` 换一个新列表；
- 新列表的 `tabLabels_/tabSpans_` **只在序列化时**（frontend `updateInputPanel()` → `tabActions()`）填充；
- press 走 `updateUI(false)` 会序列化（tabLabels_ 填好）；release 走 `updateUI(true)` 不 reset 面板但**换了列表且不再序列化** → UI 显示的还是旧 tabs，实际列表的 tabLabels_ 是空的；
- 点 tab（id≥0）→ `RimeState::selectTab` 的 `tabId >= labels.size()` **静默 return**（连 updateUI 都不调，日志零输出）；点"清除"（id=-1）→ `clearTabs()` 强制 `updateUI(false)` → 序列化 → tabLabels_ 重填 → 恢复。
- 日志佐证：BackSpace 只发 press 不发 release（列表未被污染）→ tab 能用；数字键（宏路径）press+release 齐全 → release 污染列表 → 全部点击死掉。

**修复**（`SandyYuR/fcitx5-rime@ce4c038`）：恢复"面板有内容（新旧任一非空）时 release 也序列化"的判定 + release 时清除残留 aux 的补全（用 e84ffa1 修正过的判空，含 clientPreedit）；上游 `lastMode_` 无条件显示 IM 信息的改进保留。

**教训（合并上游的暗礁）**：git 自动合并成功 ≠ 语义无损。上游删掉的"看似无关"代码可能正是本分支特性的隐藏依赖——尤其是这种"A 创建状态、B 消费状态"跨函数的时序依赖，git 完全看不出来。**合并 fcitx5-rime 上游后必须实测：打字→点 tab→选词全链路**（本次 CI 绿灯只证明能编译）。

**2026-09-09 后续**：`ce4c038` 的定制修复已被 **`e74ddb6`**（当日晨合并官方 fcitx/fcitx5-rime `ce38ca9`，版本 **5.1.16**）替代——上游 `8c952c1` "Further clean up the updateUI code with key release (#169)" 官方实现了 release 无条件序列化，`rimestate.cpp` 净删 21 行定制逻辑（`6737036..e74ddb6` 共 5 文件 +14/-35）。fxliang fork 全部定制（含 `b90bd7ca` schema_id info，07-30）均已在 `e74ddb6` 血统内，无待合并增量；官方与 fxliang 两侧上游均无新提交（09-16 ls-remote 复核仍无）。⚠️ 官方重写 updateUI 后，**"打字→点 tab→选词"全链路真机回归仍未做**（见第 7 节）。

**2026-09-20 后续**：适配层在 `e74ddb6` 之上前进到 **`fc3f98b`**（移除纵向辅助栏"清除"按钮，见本节末），主仓库 gitlink 已对齐（`1364e59c`）。该提交只删代码、不改序列化时序，与本条 bug 的修复无关联。

**推送通道备用**（github.com:443 曾多次断连，每次约 10 分钟）：**首选工作区外 `D:\GitHub\fx2-rime\ssh-push.ps1`**（走 SSH：关键发现是 mingw git 对含空格/反斜杠的 `GIT_SSH_COMMAND` 会用 MSYS `sh.exe -c` 包装（沙箱内必死 `couldn't create signal pipe`），而**正斜杠单 token 路径 `C:/Windows/System32/OpenSSH/ssh.exe` 让 git 直接 exec Windows 原生 ssh.exe**，绕开一切 MSYS；本机 `~/.ssh/config` 已配 ssh.github.com:443 + ed25519）；**备选 `api-push.mjs`**（走 api.github.com 的 Git Data API 推单文件提交，blob SHA 与本地比对确保内容一致，适合多文件改动时逐文件推或 github.com 整个不可达时）；dispatch 别忘 `DISPATCH_REF=fx-rime-only`（默认 master 会 422；09-09 起分支名由此前的 fx2-rime-fusion 更名）。SSH/API 推完的网络恢复后 `fetch + reset --hard origin/master` 对齐（提交 SHA 与本地不同但 tree 相同）。

### 2026-09-13/14 Kawaii Bar 中央按钮行整排空白（已修复：`07206012`，含可复用的真机定位方法）

**现象**（用户反馈 + 本机真机复现）：开关悬浮键盘、开关单手键盘，或进出状态区/剪贴板窗口之后，Kawaii Bar **中间那一排按钮整排消失**；左右两个固定按钮（状态区、收起键盘）照常在，中间区域**点也没反应**。强制结束进程、收起再打开键盘、或切一次扩展窗口后恢复——所以首次报告被描述成"偶发、过一会儿又好了"。

**必现路径**（用户提供，实测必现）：点悬浮键盘按钮进入悬浮 → 再点一次退出悬浮 → 中间整排消失。注意用户的自定义按钮顺序是 `undo, redo, cursor_move, clipboard, one_handed_keyboard, floating_toggle`，**悬浮按钮在最右（不是默认的第 4 个）**——先读设备上的 `Android/data/<pkg>/files/config/ButtonsLayout.json` 再点，否则会点到剪贴板。

**定位方法（可复用）**：`adb` 截图 + 逐列统计工具栏条的"墨迹"（与条内主色做阈值比较），得到每个图标的数量与位置；同一位置前后对比即可区分"整排消失"与"画歪了"。当时还用三点排除法确定失效范围：① 消失时中间区域**点击无效**（点悬浮/单手/剪贴板位置都无反应）；② 横向拖动拖不出任何按钮；③ 工具栏背景与左右固定按钮照常绘制。→ 失效的是**中央按钮行本身**（子视图没有被布局进可视区），不是容器、配色或绘制顺序。测量脚本与前后截图当时放在工作区 `D:\GitHub\fx2-rime\.adb-debug\`（本机临时目录，非仓库内容；`analyze.ps1` 做条带墨迹统计、`findbar.ps1` 扫条带位置、`verify-bar-full.ps1` 跑多场景回归）。

**根因**：中央行原实现是 `RecyclerView + FlexboxLayoutManager + Adapter`。它缓存 flex lines、滚动锚点与测量缓存，而 `onBindViewHolder` 又用 `recyclerView.width`（帧宽度）算按钮宽度；悬浮往返让行宽变化（悬浮更窄），派生状态与新宽度不一致，子 View 没被排进可视区；此后每次布局又从"已有子 View"反推锚点，坏状态自我延续，只有整行重建（切扩展窗口/重开键盘/重启进程）才能恢复。`9be829bb` 只增加了"补救触发点"，因此只能修一部分。

**修复（`07206012`）**：删除那一套 RecyclerView/Flexbox/Adapter，改为确定性 `ViewGroup`（`input/bar/ui/idle/KawaiiBarRowLayout.kt`）：每次 `onMeasure`/`onLayout` 都按**当前 measure spec** 重算宽度与位置，不保留任何跨帧派生状态；按钮只创建一次、不回收（也就不存在"无图标的空 holder"）；保留均分 ↔ 横向拖动/惯性滚动、40dp 最小宽与 2dp 间距。同时把 `IdleUi` 中间区域可见性收敛到唯一入口 `applyCenterVisibility()`，顺带修掉"语音状态 + 页面切换时 `animator` 被留在 GONE"这条与悬浮无关但症状相同的空白路径。

**不变式（改这块必须守住）**：Kawaii Bar 中央按钮行**不得保留跨帧/跨宽度的派生布局状态**。再看到"需要加一个重建触发点/rebind 才能恢复"的补丁，就是在破坏这个不变式，不要照做。

**验证**：真机（OnePlus PJD110，arm64）用墨迹法跑 8 组场景——基线 / 悬浮开 / 悬浮关（旧构建此处 8 图标 → 2 图标，新构建保持 8 图标）/ 单手开 / 单手关 / 状态区开合×2 / 悬浮来回×3 / 收起再打开键盘——全部与基线一致；消失态下点剪贴板按钮能正常打开窗口（可点击性恢复）。本地 `:app:testFxDebugUnitTest` 当时 121 例全绿（09-16 全仓 20 文件 / 133 例）。

**教训（测试腐坏）**：`9be829bb` 引入的 `NumberRowTest` 读取了基类不存在的 `textSize`，这个编译错误**潜伏了一整天没人发现**——因为 CI 只跑 `:app:assembleFxRelease`、**从不编译 `app/src/test/**`**，单测源集事实上处于"编译不过"的状态，任何 JVM 单测都跑不起来（已修：改成先断言实际类型再读属性）。装上本机工具链后第一次 `testFxDebugUnitTest` 就撞出来了。**改测试后必须本地跑一次 `build-debug.ps1 -Test`**；是否把单测纳入 CI 见第 7 节。

### 2026-09-19 自定义 SVG 图标把 Kawaii Bar 按钮撑宽（左右各多一块空隙）

**现象**（用户反馈）：在 Kawaii Bar 上新建一个按钮、图标导入自定义 SVG 后，该按钮**左右各出现一块异常空隙**，把整排按钮挤窄。

**复现需要的设备事实**（先读，别猜）：`adb-shell 'cat /storage/emulated/0/Android/data/<pkg>/files/config/ButtonsLayout.json'` —— 用户当时 13 个中间按钮，`custom_1` 的 `icon` 为 `file:button_icons/classification-290.svg`；图标本体也读出来看（该 SVG 的根标签只有 `viewBox="0 0 1024 1024"`，**没有 `width`/`height`**，尺寸写在 `style="width: 1em;height: 1em"` 里）。

**根因**（两层叠加，缺一不可）：
1. `ButtonIconFile.loadSvgDrawable` 用 `svg.renderToPicture()`。androidsvg 只有在根元素有**非百分比** `width`/`height` 时才拿它当画布尺寸；该 SVG 的宽高只写在 `style` 属性里，而 androidsvg 的 `processStyleProperty` **根本不处理 width/height**（只有 fill/stroke/font 那一批），于是退回内置 `DEFAULT_PICTURE_WIDTH/HEIGHT = 512×512`。渲染出 512×512 位图 → `BitmapDrawable` 的 intrinsic 尺寸 = `bitmap.getScaledWidth(targetDensity)`，在 density 640 的设备上是 `512 * 640 / 160 = 2048px ≈ 128dp`。
2. 中央按钮行的按钮是 `wrap_content + minimumWidth = 40dp` 的 `ToolButton`，而 `KawaiiBarRowLayout` 在均分模式下虽然给子 View EXACTLY 宽度，**均分阈值判定**用的是 `(availableWidth - spacing*count) / count >= minButtonWidth`；按钮被撑宽后该行进入滚动模式，按各自测量宽度排列 → 这个按钮独宽约 148dp，24dp 的图标在 `CENTER_INSIDE` 下居中绘制，左右各空出约 62dp。**空隙不是 padding 或 margin 造成的**，改 2dp 间距、改 `minimumWidth` 都不会消失。

**定位捷径（下次照抄）**：这类"某个按钮特别宽"的问题，先怀疑 **drawable 的 intrinsic 尺寸**，而不是布局代码——布局读的是 `measuredWidth`，而 `wrap_content` 的测量结果直接来自 intrinsic 尺寸。核对方式：`BitmapDrawable.getIntrinsicWidth()` 返回 `bitmap.getScaledWidth(targetDensity)`（AOSP `computeBitmapSize()`），所以**位图像素数与 intrinsic 宽度差一个 density 倍率**，别只看位图是 512 就以为只有 512px。

**修复**（`ButtonIconFile.kt` / 新增 `IconSize.kt`）：
- SVG 改为**直接给定 viewPort** 渲染到标准图标尺寸（`svg.renderToCanvas(canvas, RectF(0f, 0f, size, size))`），既绕开 `renderToPicture()` 的 512×512 兜底，也省掉一张大位图；宽高比由 SVG 自己的 `preserveAspectRatio` 保持。
- `loadDrawable` 出口统一归一化（`fitToStandardIconSize`）：按 **intrinsic 尺寸**判断是否需要缩放，纯函数 `fitIconSize(width, height, targetSize)` 放在 `IconSize.kt` 里做 JVM 单测（`IconSizeTest`）。
- 删除 `IconThemeManager.normalizedDrawable` 及其两处调用点（`IconThemeManager.resolveIconDrawableInfo`、`StatusAreaEntryUi.showConfiguredIcon`）——出口已经保证尺寸，调用方不必也不能再各归一化一遍；**新增取用自定义文件图标的界面同样不要再写归一化**。

**验证**：本地 `IconSizeTest` 6 例绿、全量 `:app:testFxDebugUnitTest` 绿、`:app:assembleFxDebug` 出包且签名与已装 debug 版一致；`:app:lintFxDebug` 仍为既有 157 个 error，本次改动文件只有 `LogNotTimber` 一类警告（本仓库 lint 未纳入门禁）。**真机视觉回归（装新 debug 包后看按钮是否与内置图标等宽）待用户安装确认**——容器内无 install 通道，`pm install` 被设备策略拦截。

### 2026-09-20 纵向辅助栏"清除"按钮移除（适配层 `fc3f98b`）

**现象**（用户反馈）：万象九键方案 + 左侧辅助选择栏，组字时的音节选择按钮**全部同时展示**，可选音节一多每个按钮就被压得很小，难以点按（用户预期是固定几个、超出可滑动）。

**根因**（两层，靠读代码定位；当时 logcat 只有系统层输出，应用自有日志已滚出缓冲，日志对本问题无用）：
1. 适配层 `rimecandidate.cpp` 的 `tabActions()` 在全部音节 tab 之后追加一个分隔符（id=-2）和一个 id=-1 的"清除"按钮。
2. App 侧 `BaseKeyboard.updateAuxBarActions()` 用 `takeWhile { !isSeparator }` / `drop(size + 1)` 把动作切成 scrollable / pinned 两组 → "清除"被固定进 pinned 区（纵向辅助栏贴底）；而纵向辅助栏（`AuxBarPosition.Left/Right`）自 `7a480550` 起不再走 RecyclerView，改为纯 `LinearLayout` **按 item 数量均分容器高度**（`relayoutVerticalAuxBarItems()`：`itemHeight = height / count`，**无下限**），外层也没有任何滚动容器 → 音节越多每个 tab 越矮，item 内的 `AutoScaleTextView(Proportional)` 又按比例缩字。两者叠加 = 按钮越来越小、越来越挤。

**修复**（`SandyYuR/fcitx5-rime@fc3f98b`，主仓库 gitlink `1364e59c`）：`tabActions()` 不再追加分隔符与"清除"，全部 tab 落回 scrollable 组；配套删除**只服务于该按钮**的管线——`TAB_ACTION_CLEAR` 常量、`triggerTabAction` 的 `id == TAB_ACTION_CLEAR` 分支、`RimeState::clearTabs()` 及其声明（同一提交 `7671adc` 引入，此后无其它调用方；全仓库 `grep` 确认 App 侧从未引用，已零残留）。

**副作用与开放问题**：删除后适配层不再暴露 `clear_tabs` 这条 C API 的调用路径（App 侧本来就无引用），**即界面上不再有"一键清空音节约束"的入口**。音节约束绑定在 composition 上，正常打字/提交会自然推进，用户装机实测（2026-09-20）未发现不便；但若日后出现"需要显式清空约束"的场景，须重新引入入口（不能只靠 App 侧过滤，因为动作来源就是适配层）。

**注意**：第 0 节「九键音节选择器首次点击无响应」里"点一下清除就恢复"是当时那条 bug 的**恢复手段**，不是修复本身；那条 bug 早已由 `e74ddb6`（官方 `8c952c1` 实现 release 无条件序列化）真正修掉，所以移除"清除"不影响该场景。

**教训（可复用）**：这是"适配层多塞了一个 UI 动作"引发的布局退化——适配层往 tab 列表尾部追加非音节动作，App 侧按分隔符分组后把固定动作排进 pinned 区，两边单独看都合理，组合起来就抢占了内容区高度。**往后在 `tabActions()` 里追加任何非 tab 动作，都要先想 App 侧的分组语义**（scrollable vs pinned）。

**验证**：`:app:assembleFxDebug` 出包（8m23s）；用 `strings`/字节匹配核对产物——`librime.so` 含插件字面量（`fcitx-rime-separator`、`Schema Selector`、`fcitx-rime-deploy`，证明该 so 就是适配层本体且检测方法有效）但**不再含"清除"**；APK 内无独立 `libfcitx5-rime.so`，适配层静态链入 `librime.so`。用户装机确认辅助栏行为正常（2026-09-20）。**注意**：本次只解决了"清除按钮抢高度"——当时**音节 tab 仍全部展示、按数量均分高度（无最小行高、不可滑动）**；⚠️ **该状态已于 2026-10-08 `098ec596` 改变：超过六项的音节 tab 改为可滚动（视口约 6.5 行），见 §0.6.17**（本条其余内容仍有效）。

---

## 0.5 rime 引擎更新 runbook（2026-09-06 首次实战打通，照此复制）

> 引擎 = `SandyYuR/prebuilt` 里的 `librime.a`（四 ABI）。它**不跟上游自动更新**，要更新就照本节走一遍。
> 首战成果：librime `1.17.0-1d0df6e`（2026-07-28）→ **`1.17.0-3cbe4af`**（上游 master tip，含 13 个新提交），fxliang 补丁集全保留。全程约 1.5 小时人工 + 1 小时 CI。

### 0.5.0 架构认知（动手前必须懂）

```
SandyYuR/prebuilder（配方仓库，.gitmodules 里 librime → rime/librime 上游本尊）
  ├─ librime gitlink（pin 一个上游 SHA，不需要 fork librime！）
  ├─ patches/librime*.patch（fxliang 三补丁，构建期 git apply 到检出的源码上）
  └─ .github/workflows/ci.yml（手动 workflow_dispatch 触发，archlinux 容器，
       构建四 ABI 全部静态库 → 用 BOT_TOKEN 推回 SandyYuR/prebuilt "Auto update"）
SandyYuR/prebuilt（产物仓库，引擎二进制的唯一事实源）
主仓库 CI（prepare_personal_build.sh 把 prebuilt 子模块切到 SandyYuR/prebuilt@master → 链接进 APK）
```

- **为什么不 fork librime**：pin 的 SHA 永远是上游存在的 commit，CI 从 `rime/librime` 本尊检出（CI 日志可证：`Submodule 'librime' (https://github.com/rime/librime) registered`）。fxliang 架构 = pin 上游 + 构建期打补丁，没有 librime fork 的位置。
- **为什么 prebuilt 不合上游**：上游 prebuilt 是官方无补丁流水线产物，合并=静默换掉 fxliang 定制引擎。
- 本地 `D:\GitHub\fx2-rime\librime-src\` 只是补丁适配工作台（含 state1/2/3 提交与合并中的文件），**成果已全部物化进 prebuilder 的补丁文件**，不需要推送到任何远端；更新前 `git reset --hard && git clean -fd` 清场即可复用。

### 0.5.1 首次实绩（2026-09-06，所有 SHA 对照；09-09 第二次见 0.5.4）

| 环节 | 提交/位置 | 说明 |
|---|---|---|
| prebuilder 引擎更新 | `SandyYuR/prebuilder@509a029` | librime gitlink → `3cbe4afb`；重生成 `librime.patch` 与 `librime-perf-syllabifier-...patch`（适配上游 CandidatePreview 基线）；ci.yml 推送目标 `fxliang/*` → `SandyYuR/prebuilt`，删除推 fxliang/fcitx5-android 元数据的两个死步骤 |
| prebuilder secret | Actions secret `BOT_TOKEN` | 值 = 本机 GCM 的 GitHub token（gho_ 开头 OAuth，有 contents:write + workflow 权限）。**token 轮换后失效，用 `set-secret.mjs` 重设** |
| 引擎构建 | prebuilder run #1（workflow_dispatch，job 101480898181） | 11:46 → 12:00 UTC，绿灯；`does not apply` 0 条 = 三补丁干净应用 |
| 引擎产物 | `SandyYuR/prebuilt@3f2e22b` | "Auto update"（bot 提交），librime.a 19,155,724 bytes（arm64） |
| 主仓库接入 | `92208fd1`（`librime.json` → `1.17.0-3cbe4af`） | run #223 绿灯，APK 已带新引擎 |

### 0.5.2 操作步骤

**第 1 步：侦察上游 delta**
```powershell
cd D:\GitHub\fx2-rime\librime-src
git -c http.sslBackend=openssl fetch origin master --tags
git log --oneline <旧pin>..origin/master          # 新提交数
git diff --name-only <旧pin>..origin/master       # 改动文件
# 重点看是否动了：syllabifier.* / prism.* / user_dictionary.* / rime_api* / context.*
# （不动这些 = 补丁大概率干净应用，可跳到第 4 步）
```

**第 2 步：实测补丁可应用性**（prebuilder `src/Rules/Librime.hs` 定义应用顺序）
```powershell
git checkout -q origin/master; git reset --hard -q; git clean -fdq   # 先清场！
git apply --check D:\GitHub\fx2-rime\prebuilder\patches\librime.patch
git apply --check D:\GitHub\fx2-rime\prebuilder\patches\librime-perf-syllabifier-cache-repeated-QuerySpelling-iterat.patch
git apply --check D:\GitHub\fx2-rime\prebuilder\patches\librime-userdict-cache.patch
# 失败的用 --reject 定位：git apply --reject <patch>，得到 <file>.rej + 干净应用的其余文件
```
本次实况：patch1 拒 4 文件（`rime_api.h`/`rime_api_impl.h`/`context.cc`/`script_translator.h`，全是"锚点后插入"型——上游在同一锚点插了 CandidatePreview）；patch2 拒 1 文件（`syllabifier_test.cc`，上游加了 CanonicalizeSyllabifierTest）；patch3 干净。

**第 3 步：手工合并 .rej + 中间状态法重生成补丁**（核心，别跳验证）
```powershell
# 3a. 逐个 .rej 手工并入（参照两个父版本 git show <pin>:<path> / git show upstream/master:<path>）
#     合并完删 .rej；补丁文件里每个 hunk 的内容都要有落点
# 3b. 在 patch1 合并结果上继续 --reject 应用 patch2，再手工并（顺序必须与 Librime.hs 一致）
# 3c. 应用 patch3（通常干净）
# 3d. 提交中间状态并导出新补丁：
git add -A; git commit -m "state3: master + patch1' + patch2' + patch3"
git apply -R <旧patch3> 后再提交 state2      # 反向剥掉 patch3
git apply -R --reject <旧patch2>; git checkout master -- test/syllabifier_test.cc; 提交 state1
git diff <master> <state1> --output=<新patch1>       # → prebuilder/patches/librime.patch
git diff <state1> <state2> --output=<新patch2>       # → prebuilder/patches/librime-perf-....patch
#     ⚠️ 别用分支名引用 state！三条 state 都在同一分支上时，分支名指向最后一条。
#     用 git rev-parse --short HEAD 记下每条 state 的 SHA 再操作。
# 3e. 回环验证（必须零差异）：
git checkout <master>; git reset --hard -q; git clean -fdq
git apply <新patch1>; git apply <新patch2>; git apply <旧patch3>
git add -A; git diff --cached <state3的SHA> --stat    # 输出必须为空
```

**第 4 步：改 prebuilder 并推送**
```powershell
cd D:\GitHub\fx2-rime\prebuilder
Copy-Item <新patch1> patches\librime.patch -Force       # 注意 LF：确认 git index 内 blob 无 CR
Copy-Item <新patch2> patches\librime-perf-....patch -Force
git update-index --cacheinfo "160000,<新pin全SHA>,librime"   # pwsh 里参数必须整体加引号！
git add -A; git commit -m "更新 librime 至上游 master <sha>（适配补丁到 <上游新特性> 基线）"
D:\GitHub\fx2-rime\push-with-gcm.ps1 -RepoDir $PWD -Remote origin -Branch master
```

**第 5 步：触发引擎构建并等待**
```powershell
# fork 的 push 触发可能不生效（fork 默认静默），用 API 手动 dispatch：
$env:GH_TOKEN = <GCM token>; $env:DISPATCH_REPO='SandyYuR/prebuilder'; $env:DISPATCH_WF='ci.yml'
node D:\GitHub\fx2-rime\dispatch-any.mjs
```
等 15-90 分钟。失败诊断：拉 job 日志搜 `does not apply`（补丁问题）/ `error:`（编译问题）；shake 的 `cmd_` 静默，`git apply` 的报错会带出来。绿灯后确认 `SandyYuR/prebuilt` 收到新 "Auto update" 提交。

**第 6 步：主仓库接入 + 验证新引擎**
```powershell
cd D:\GitHub\fx2-rime\prebuilt; git -c http.sslBackend=openssl fetch origin master; git checkout -q origin/master
# 二进制验证（ASCII 字符串 Contains，C API 符号不会被裁剪）：
#   fxliang 补丁符号 RimeGetInputTabs/RimeSelectTab 应在；本次新增的 RimeGetCandidatePreview 也应在
cd D:\GitHub\fx2-rime\fx-rime-only
# 更新 app/licenses/libraries/librime.json 的 artifactVersion = "<rime_version>-<短sha>"
#   （版本号看 librime-src/CMakeLists.txt 的 set(rime_version X.Y.Z)；短 sha = 新 pin 前 7 位）
git add ...; git commit -m "chore: 更新 librime 引擎版本元数据至 <ver>-<sha>"
push-with-gcm.ps1 ...   # push 触发 APK CI（约 11 分钟），等 run 绿
```

### 0.5.3 本次踩的坑（下次省时间）

1. **state 分支命名陷阱**：三条 state 提交都落在 `state3` 分支上，分支名≠state3 内容；用 `git diff --cached state3` 验证回环时对比的是分支 tip（state1），得出"8 文件不一致"的假阳性。**一律用显式 SHA**。
2. **pwsh 的 `git update-index --cacheinfo a,b,c` 逗号会被解析成数组** → 整个参数加引号。
3. **`api.mjs` 只打印前 3000 字符**，tree API 的 librime 条目在截断区——要写专用小脚本拿全量。
4. **引擎 .a 验证**：`SharedBlockCache` 等 static 函数名查不到是正常的（内联）；归档成员名是 `user_dictionary.cc.o/` 后缀；可靠判据是导出 C API 符号。
5. **BOT_TOKEN**：gho_ OAuth token 有 workflow 写权限（能推 ci.yml 改动）。fork 的 push 触发行为**不稳定**：09-06 那次 push 没触发 run（当时结论"别指望 push，直接 dispatch"），09-09 那次 push 又触发了（见第 7 条双跑坑）——**dispatch 前一律先查 `actions/runs`**。
6. `librime-predict-leveldb` 子模块指向 fxliang 的仓库（停更），gitlink 永远保持 `0c30981a` 原样，**别动**。
7. **push 与 dispatch 双跑会竞争推 prebuilt**（2026-09-09 实测）：fork 的 push **也会**触发 CI（并非总静默），手动 dispatch 前先查 `actions/runs`，已有 push 触发的 run 就取消其一——两个 run 都会向 prebuilt 推 "Auto update"，后推的 non-FF 必红（本次取消了 push 触发的 34334798941，保留 dispatch 的 34334861641）。
8. **github.com:443 断连时**（每次约 10 分钟）：fetch 可走 SSH——`$env:GIT_SSH_COMMAND='C:/Windows/System32/OpenSSH/ssh.exe'` 后 `git fetch git@github.com:<owner>/<repo>.git master`（本机 `~/.ssh/config` 已配 ssh.github.com:443）；api.github.com 通常不受影响，可先用 API 验证远端（tree 的 blob SHA、contents 的 base64——`rime_api.h` 的 blob 直接对照补丁 post-image 即可证明新补丁生效）。

### 0.5.4 2026-09-09 合并 fxliang/prebuilder 3 提交（第二次实战，当前引擎）

**背景**：fxliang/prebuilder 领先 3 提交——`f1c99f4`（userdict cache 补丁大重写，-886/+306）、`cd8505b`（**新增 para deploy 补丁**：词典部署并行编译；`LibRime.hs` 应用顺序变为 librime → syllabifier → **para** → userdict，另 `git clean -xdf` 收窄为 `git clean -xdf test`）、`6f4eb56`（userdict 跨 session 修改失效修复 + tabs 去重键改 label+span+source + `get_candidate_code` 支持 wrapped/uniquified 候选）。三者均基于旧 pin `1d0df6e` 编写，而我们已 pin `3cbe4afb`——补丁必须适配新基线。

**A/B 参照树法解补丁冲突**（比手工解冲突标记更稳，推荐沿用）：

```powershell
# 1. merge：仅 patches/librime.patch 冲突（4 处，全是锚点/上下文的基线差异，语义双方一致）
# 2. 在 librime-src 建两棵参照树，提取上游"语义增量"：
git checkout --detach 1d0df6e; git apply <merge-base版librime.patch>; git add -A; git commit   # → A
git checkout --detach 1d0df6e; git apply <upstream版librime.patch>; git add -A; git commit   # → B
git diff <A> <B> --output=delta.patch   # 只含 6f4eb56 的真实语义变化（本次 rime_api.h +3 行、rime_api_impl.h 26 行改写）
# 3. 在新基线上重放并重生成：
git checkout --detach 3cbe4afb; git apply <我们适配版librime.patch>; git add -A; git commit   # state1
git apply delta.patch; git add -A; git commit                                                   # state2
git diff 3cbe4afb <state2的SHA> --output=新librime.patch
```
好处：hunk 行数与 index blob 全由 `git diff` 重算（`@@ -521,6 +521,50`、post-blob `030ff78a` 等），杜绝手数行数；post-blob 一定是新的。

**验证链**（全部通过）：① 四补丁（librime/syllabifier/para/userdict）在 3cbe4afb **全部干净应用**（userdict 补丁重写后 hunk 恰好避开上游 RIME_DLL 签名改动）；② 全栈回环零差异（干净 3cbe4afb 依序应用 4 补丁 == state4）；③ 语义 diff 双向核对（vs 我方 HEAD 只见上游语义变化，vs upstream 只见基线锚点差异）；④ CI run 34334861641 绿；⑤ 产物 `prebuilt@9aa8104c`：四 ABI .a 全部更新（arm64 19,155,724 → 19,233,430 字节），`rime_api.h` blob `030ff78a` 与新补丁 post-image **完全一致**，arm64 .a 含 `RimeGetInputTabs`/`RimeSelectTab`/`RimeGetCandidateCode`/`RimeGetCandidatePreview`/`CompileDictionary`（para 补丁符号）。

**本次要点**：
1. **`librime.json` 版本号不用改**——pin 未动（仍 `3cbe4afb`），`artifactVersion` 仍 `1.17.0-3cbe4af`，只有 .a 内容变了；主仓库 CI 浮动拉 prebuilt master，**下一次主仓库构建自动带新引擎**，无需任何主仓库改动。注意这意味着版本号无法区分 09-06/09-09 两版引擎，要靠 prebuilt 的 Auto update SHA。
2. **librime 上游待吃进 delta**（09-10 并入 0.5.5 表统一跟踪）：`streaming_chord_processor` 流式并击（`74a7467e` + `74db0d18`，新文件 + `gears_module.cc` +3 行，**不碰任何补丁文件**）与纯 CI 的 `35f23e97`（仅改 6 个 workflow 文件的 action 版本，**与引擎构建无关，不能触发引擎更新**）——下次引擎更新直接 `git update-index --cacheinfo` bump gitlink 即可，四补丁预计干净应用，照 0.5.2 第 1-2 步先实测确认。

### 0.5.5 2026-09-10 rime 引擎链路全仓库核对（未合并，未推送）

**结论先行**：fxliang/prebuilder 当日新增 1 提交（`4fdb494`，见下行 ①）；其余四个检查点**全部无动作**（见下表）——fcitx5-rime 两侧上游零新增、librime 上游只有 1 个纯 CI 提交、fxliang 的新 prebuilt **不能合并**。工作区状态：**4 个本地仓库全部干净、零未推送、零未跟踪异常**（prebuilder `ad491c7`=origin/master；fcitx5-rime `e74ddb6`=origin/master；prebuilt `9aa8104c`=origin/master；主仓库干净 `## fx-rime-only...origin/fx-rime-only`）。

| 检查点 | 结果（均为 09-10 fetch 实测） |
|---|---|
| ① fxliang/prebuilder：`4fdb494`（09-10 17:15 +0800，`fix(user_dict): invalidate cache after external updates`，+360/-5，仅动 `librime-userdict-cache.patch`；librime pin **仍是 `1d0df6e`，没动**；其 CI run 34459709019 已绿） | **唯一待合并项**。机制：`TickCount` 持久化到 leveldb `/tick` 元数据 + 写路径每次 `advance_tick`；新增回归测试 `DeleteFromAnotherInstanceInvalidatesCache`（`ASSERT_TRUE(ud->Reload())` × 多处）。注：6f4eb56 修的是"同进程他 session 写后读"，这次补的是"**外部进程**改库后失效"，是同系列的第二个洞。**工作台预检通过**：在干净 `3cbe4afb` 上按 CI 顺序应用 `librime → syllabifier → para → 新 userdict`，`git apply --check` **零退出**——两个月来新 userdict 第一次在 3cbe4afb 基线干净（Context 待定，见下）。建议合并时机：**与 streaming_chord 一起吃**（见行 ④），一次 bump pin 一次构建，别为单补丁单独跑一次 15-90 分钟 CI。 |
| ② fcitx5-rime 上游：fxliang（`b90bd7ca`，07-30）与官方（`ce38ca9`，`fcitx-up` SSH 断连，已用 HTTPS `ls-remote` 验证 HEAD 一致） | **零新增**。`b90bd7ca` 已被 `merge-base --is-ancestor` 确认在 `e74ddb6` 血统内；官方 HEAD 仍 `ce38ca9`。适配层现状 = 最新，无动作。 |
| ③ fxliang/prebuilt：`f6758200`（09-09，tabs header +3 行，对应上游 librime.patch 新 API）与 `bbe671fe`（09-10，仅四 ABI .a 各 +~200-400 字节，无 header 变化） | **禁止合并**（旧规则重申 + 本次实锤）：① 两个 "Auto update" 都是官方流水线无定制产物，合并 = 静默换掉我们的 fxliang 定制引擎；② 反向证据确凿：`bbe671fe` 相对我们 `9aa8104c` 的 `rime_api.h` diff 显示**我们多出 `RimeCandidatePreview` 整块结构体**（官方 pip 里的 CandidatePreview 补丁对应的公共头定义，我们有、他们没有——两侧上游对同一特性的实现路径已经分叉）；③ `bbe671fe` .a 反而比我们小约 27KB（无定制代码）；④ `4fdb494` 的补丁修复尚未进入官方 prebuilt（`f6758200..bbe671fe` header 零变化）。 |
| ④ rime/librime 上游：`35f23e97`（09-10，仅改 6 个 workflow 的 GitHub Action 版本，CI 配置，与源码零关系）+ 待吃的 streaming_chord 两提交 | `35f23e97` **不触发引擎更新**（`3cbe4afb..origin/master` 的源码文件仍只有 `gears_module.cc` + 2 个新文件）。下次更新时三者一起吃：pin `3cbe4afb`→`35f23e97`（含 streaming_chord 功能 + CI 配置，构建不受影响），照 0.5.2 第 1-2 步实测。**【09-16 已执行此更新】且上游继续前进到 `8d8276f4`（又 +8 提交），后续更新注意事项见第 7 节第 6 条。** |
| ⑤ 顶层 pins 全量对照（`git ls-tree` 我方 vs fxliang，30+ gitlinks） | 除 `librime`（我们 `3cbe4afb` vs 他们 `1d0df6e`）与各自定制的 `ci.yml`/`LibRime.hs`/`build.cfg` 外**全部一致**——无第三方依赖可趁机更新，依赖更新面为零。 |

**Context（为什么新 userdict 一直干净不了、这次却干净了）**：09-06 首战时旧 userdict 补丁是按上游旧版编写、锚点全部错位；09-09 合并时新版 userdict（`6f4eb56` 版）索引头写的是 `1d0df6e` 时代的 blob（`2944c2db..d9712ffa`），但 hunk 内容恰好落在 3cbe4afb 未改动的行区间 → `--check` 干净。上游 `1d0df6e`→`3cbe4afb` 之间对 `user_dictionary.*` 的唯一改动（`kDiscardThreshold` + `RIME_DLL` 签名）与补丁行不重叠，这是根本原因——**不是运气，是可复现的判断方法**：`git diff <旧pin>..<新pin> -- <补丁文件>` 与补丁 hunk 无交集 ⇒ 大概率干净，但仍必须实测。

**待合并时操作**（沿用 0.5.4 的 A/B 参照树法或直接 `--check`）：① 取 `upstream/master:patches/librime-userdict-cache.patch`；② 在 `3cbe4afb`（或新 pin）上依序应用验证；③ 本次无需重生成 `librime.patch`（4fdb494 只动 userdict 文件，与 tabs 无交集——但仍需 `git diff` 双向核对确认）；④ 提交信息注明"合并 fxliang/prebuilder `4fdb494`（userdict 外部更新失效修复），四补丁栈在 `<pin>` 干净应用"；⑤ 推送 + dispatch + 取消重复 run（见 0.5.3 第 7 条）。

### 0.5.6 2026-09-10 第三次实战（4fdb494 合并 + pin bump + 主仓库接入，一气呵成）

**prebuilder 侧**（`SandyYuR/prebuilder@446d1ea`，本地领先远端 3 提交后推送 `ad491c7..446d1ea`）：① `git merge upstream/master`——merge-base 恰为 `6f4eb56`（纯线性后继），ort **自动合并零冲突**（仅 userdict 补丁文件 +360/-5），merge 信息原样保留；② `git update-index --cacheinfo` bump `librime` gitlink `3cbe4afb`→`35f23e97`（单 gitlink 提交）。注意：fetch 曾两次断连（origin 443 超时 / upstream 连接重置），但 `upstream/master@{18:50}` 经 API 确认即远端 tip（`4fdb494` 当日唯一新增）后才 merge——**fetch 失败时用 reflog 时间 + API 双重确认再动手，不硬 merge 过期 ref**。

**工作台验证**（`librime-src`，干净 `35f23e97`，已恢复原状）：四补丁按 CI 顺序 `apply` 全零退出；从零重放 == S4 **回环零差异**。唯一注记：`4fdb494` 自带的测试文件 `test/user_dictionary_test.cc` 有 332 行行尾空格 warning（上游补丁原文问题），`apply` 通过且 CI 本来就 `git clean -xdf test`，无影响。

**引擎产物**（`prebuilt@9e631eb9`，CI run 34472331394 绿，push 触发的重复 run 34472292686 依惯例取消）：四 ABI `.a` 全更新（arm64 19,233,430 → 19,308,390 字节，约 +75KB——streaming_chord 新 processor + userdict 失效逻辑）；**header 零变化**（`9aa8104c..9e631eb9` 的 `rime_api.h` diff 为空——两次上游增量都不碰公共头）；工作台 `35f23e97`+四补丁产出的 `rime_api.h` blob `030ff78a` 与出货 header **逐字节一致**；arm64 `.a` 含全部定制符号（`RimeGetInputTabs`/`RimeSelectTab`/`RimeGetCandidateCode`/`RimeGetCandidatePreview`/`CompileDictionary`）。本地 prebuilt 已 FF 到 `9e631eb9`。

**主仓库接入**（`793a4a6f`）：pin 动了所以 `librime.json → 1.17.0-35f23e9` + README 同步（含补丁清单措辞更新：功能/音节缓存/**词典并行部署**/用户词典缓存）。`rime_version` 仍 `1.17.0`（上游未发新版），短 SHA 取新 pin 前 7 位。**下一个主仓库 CI 自动带新引擎**。

**教训**：`35f23e97` 这类"纯 CI 提交"单独看确实不值得更新引擎，但它正好把之前待吃的 streaming_chord 一起打包——**bump 前先看 `git diff --name-only <旧pin>..<新pin>`，把排队的 delta 一次性评估**（见 0.5.5 表行 ④）。

### 0.5.7 2026-09-17 第四次实战（用户报障 → 定位上游缺陷 → 新增补丁 + pin bump + fcitx5-rime 合并）

**起因**：用户部署万象方案的自定义短语词库报错，日志 `日志/部署org.fcitx.fcitx5.android.fx.rime-2026-09-17T10_59_44Z.txt` 显示 `custom_phrase` 编译到最后一步 `building prism...` 时 `invalid metadata` → `dictionary 'custom_phrase' failed to compile`；同一份词库在 PC 的 Weasel 上不报错。**先给结论：词库写法没问题，是上游 librime 的缺陷。**

**根因**：`MappedFile::Allocate()` 容量不足时 `Resize()`（内部 `Close()` 解除映射）→ `OpenReadWrite()` 重新映射，**映射基址变化，此前通过 `Allocate()`/`CreateArray()` 取得的裸指针全部失效**（文件内的 `OffsetPtr` 因为存的是相对偏移而自愈，不受影响）。而 `Table::Build()` 在 `OnBuildFinish()` 之后仍拿扩容前的 `metadata_` 去 `strncpy(metadata_->format, kTableFormatLatest, ...)`，写入落到已解除映射的内存 → 文件头 `format` 全零 → 下次 `Table::Load()` 的 `strncmp(metadata_->format, "Rime::Table/")` 失败。`Prism::Build()`、`ReverseDb::Build()`、`CopyString()`/`CreateString()` 有同型缺陷。

**触发条件（关键，别误判成词库问题）**：`Table::Build()` 的 `estimated_file_size = 4096 + 32*音节数 + 64*词条数` **完全没算 marisa 字符串表镜像**，小词典时该镜像的固定开销占绝对主导（本次 7 词条实测约 4.8KB）。日志自证：`estimated file size: 4736` → `resize file to: 9472`（=4736×2）→ `ShrinkToFit` 后实际 **5036 字节**。**词条少 + 单条文本长 ⇒ 必然扩容 ⇒ 必现**（两条上百字的执勤模板正好踩中）。Windows/Weasel 上重新映射常拿到同一地址而侥幸不报错，Android/Linux 地址一变就必现——**"PC 上不报错"不能反证词库正确**。注意这不只影响 custom_phrase：**任何"需要重建 table"的场景（首次部署、词典内容变更、改短语后重新部署）都会踩**；反过来，像 wanxiang 那种 checksum 未变、直接复用已有 `table.bin` 的词典不会走这条路，所以之前没暴露。

**修复与验证**：新增 `patches/librime-fix-mapped-file-remap.patch`（**第 5 个补丁**，排在 `librime-userdict-cache.patch` 之后）：在 `MappedFile` 增加 `OffsetOf()`/`Rebase()` 重定位辅助，并修正 `Table::Build`/`OnBuildFinish`/`BuildHeadIndex`/`BuildTrunkIndex`/`BuildTailIndex`/`BuildEntryList`、`Prism::Build`、`ReverseDb::Build`、`CopyString`/`CreateString`，共 5 文件（+111/−38）。验证两条：① 用 NDK clang++ 以 Android 目标对 4 个改动文件做 `-fsyntax-only`，**全部零退出**（技巧：`RIME_ENABLE_LOGGING` 未定义时 `common.h` 走 librime 自带的 `no_logging.h`，于是不需要 glog；只需给 `marisa.h`、4 个 boost 头、`rime/build_config.h` 写最小 stub 即可，本机无 MSVC/g++ 也能验语法）；② 在干净 `8d8276f4` 上依序重放 5 个补丁**全部零退出**，且 `mapped_file.h/cc`、`table.cc`、`reverse_lookup_dictionary.cc` 与工作台分支 `fix/mapped-file-remap` 的 diff **零差异**（`prism.cc` 因同时被 perf 补丁改过，单独确认新代码在文件内即可）；③ **运行时复现验证**（用 JDK 跑 `.verify/RemapRepro.java`，按日志反推的真实尺寸建模：`estimated=4736`、metadata 68、syllabary 28、head index 76、entries 56、字符串表镜像 4808）：缺陷版在 `Resize(9472)` 重新映射后**丢失 15 次写操作**、文件头 `format` 为空 → `strncmp` 校验失败（即用户看到的 `invalid metadata`）；修复版同样扩容但**零丢失**、`format` 为 `Rime::Table/4.0` → 校验通过。模拟出的 `Resize(9472)` 与最终 5036 字节和现场日志逐项吻合。

**pin bump**：`35f23e97` → `8d8276f4`（再 +10 提交）。`git diff --name-only 35f23e97..8d8276f4` 只有 `.dockerignore`、4 个 workflow、`Dockerfile`、`deps/opencc`——**`src/` 与 `plugins/` 零变更**，纯 CI 配置 + opencc gitlink 1.4.2。**opencc 变更确认不影响本产物**：CI `submodules: true` 是非递归的（ci.yml 注释明说不要 librime 的递归子模块），`librime/deps/opencc` 根本不会被 checkout，opencc 由 prebuilder 自建并经 `CMAKE_FIND_ROOT_PATH` 提供；且 librime 侧 `src/` 零变更 ⇒ 不会引入新的 opencc API 要求。于是 0.5.5/0.5.6 遗留的"评估 opencc 升级对简繁转换的影响"**就此解除**，本仓库 `AGENTS.md` 第 7.2 节的相应警告已同步改写。

**fcitx5-rime 同步更新**：官方 `fcitx/fcitx5-rime` 新增 `00be19a`（drop librime 1.7 compatibility code，#171），内容只是删掉 `FCITX_RIME_NO_DELETE_CANDIDATE` 条件编译。我们跑 librime 1.17.0、从未定义该宏，**行为零变化**，纯清理。把 `fcitx-up/master` 合入 `SandyYuR/fcitx5-rime` master **零冲突**（ort 自动合并 4 文件），新 master `9b6abe0`；主仓库子模块指针对齐到它。顺带修正 `app/licenses/libraries/fcitx5-rime.json` 的 `artifactVersion`：5.1.14 → 5.1.16（`CMakeLists.txt` 早就是 5.1.16，上次合漏的元数据）。

**未做（有意排除，不是遗漏）**：`fcitx/fcitx5` 上游领先本地 `442edbc9` 共 **25 提交**（GitHub compare API 实测），但内容集中在 Wayland launcher coroutine、cairo SVG pattern、pixel buffer 尺寸、UnixFD 传参、macOS CI、翻译更新，外加一次 `Bump C++ standard to 23` 又自行 revert——**对 Android 目标无实质影响**，而升 core 必须重验 `fcitx5-alt-trigger-v4point1.patch` 的落点。本地 core 版本 5.1.22 恰好满足 fcitx5-rime 的 `REQUIRED_FCITX_VERSION 5.1.22`，没有版本压力，留作专门任务单独做。

**下次务必注意**：prebuilder `ci.yml` 的 "Detect librime related changes" 步骤**只监视 `librime` 与 `librime-predict-leveldb` 两个 gitlink 路径**。**如果只改 `patches/**` 而不动 `librime` gitlink，`needs_update` 会是 false，下游不会跟进**。本次因为同时 bump 了 pin 才正常触发；将来做"纯补丁修复"时要记得连 gitlink 一起动（或改成手动 dispatch）。

**执行结果（09-17 当日闭环）**：CI [run 35227272085](https://github.com/SandyYuR/prebuilder/actions/runs/35227272085) **success**（13:28:19 → 13:46:52，18 分 33 秒）；`SandyYuR/prebuilt` 由 `9e631eb9` 前进到 **`5593312a`**，四个 ABI 的 `librime.a` 全部更新（arm64 19,308,390 → 19,310,814，**+2,424 字节**，恰为新增重定位代码的增量；其余依赖库零变化，符合"只动 librime 源码"的预期），`toolchain-versions.json` 的 `prebuilder` 字段变为 `2032af4...`，据此反查产物来源。用 `.verify/check-prebuilt.ps1` 核对：四 ABI 齐全、来源匹配、`RimeGetInputTabs`/`RimeSelectTab`/`RimeGetCandidateCode`/`RimeGetCandidatePreview`/`CompileDictionary` 五个定制符号全在。主仓库已把 prebuilt gitlink 指向 `5593312a`、fcitx5-rime gitlink 指向 `9b6abe0`，并同步元数据（`librime.json` → `1.17.0-8d8276f`、`fcitx5-rime.json` → `5.1.16`）与 README 的引擎版本/补丁清单。

**新踩的坑：prebuilt 子模块是 shallow + partial clone，bump 指针后 checkout 会走 HTTPS 拉 blob 而失败**。`lib/fcitx5/src/main/cpp/prebuilt` 在 `.gitmodules` 里标了 `shallow = true`，实际还是 promisor/partial clone（`fetch` 只取 commit 与 tree，blob 留到 checkout 时按需拉）。于是 `git checkout <新sha>` 会向 promisor remote 请求缺失 blob，而该 remote URL 是 HTTPS —— 本机 schannel 拿不到凭据，报 `could not fetch <blob> from promisor remote` 和 `unable to read tree`。**解法**（已写进该子模块的仓库级配置，可复用）：

~~~powershell
$env:GIT_SSH='C:/Windows/System32/OpenSSH/ssh.exe'; $env:GIT_SSH_VARIANT='ssh'
git -C lib/fcitx5/src/main/cpp/prebuilt config 'url.ssh://git@ssh.github.com:443/.insteadOf' 'https://github.com/'
git -C lib/fcitx5/src/main/cpp/prebuilt fetch --depth 1 gh master      # remote.gh 已指向 SandyYuR/prebuilt
git -C lib/fcitx5/src/main/cpp/prebuilt checkout <新sha>
~~~

两个关键点：① **`fetch` 成功不代表 `checkout` 会成功**——blob 是 checkout 阶段才拉的，必须两步分别验证；② 直接向 GitHub 拉四个 ABI 的大 `.a` 容易 `early EOF`，必要时改从已完整更新的独立克隆 `D:\GitHub\fx2-rime\prebuilt` 走本地传输，或只做浅拉取（`--depth 1`）。

### 0.5.8 2026-09-19 第五次实战（用户报障 → 定位上游缺陷 → 新增第 6 个补丁，纯补丁不动 pin）

**起因**：用户报障——在候选词上长按点「忘记词汇」，结果**连带把同音的另一个词也忘了**（"忘一个，丢两个"）。日志（debug 版实测，输入 `ni zhe`、只长按第 1 个候选点**一次**）：
`15:35:49.897 deleting entry: '你这'` → 64ms 后 `15:35:49.961 deleting entry: '逆着'`，两次删除之间**没有任何新的点击事件**（只有弹窗失焦/销毁），故排除应用层重复调用。

**根因（先验证再改代码，前两次判断都被证据推翻）**：
1. `Context::delete_notifier_` 是 `boost::signals2::signal`，**多播**；
2. `Memory` 构造函数订阅一次，而 `ScriptTranslator`、`TableTranslator`（`reverse_lookup_translator` 只继承 `Translator`，不订阅）**都继承 `Memory`**，每个实例各订阅一次；
3. 同一方案里多个 translator 常配置**同一个 `user_dict`**（同语言）→ 共享一份词库（这是复现的必要条件）；
4. `Context::DeleteCandidate` 先 `seg.selected_index = index`，**只发一次**通知，但 N 个订阅者**各跑一遍** `OnDeleteEntry`；
5. 旧代码每个订阅者末尾都立刻 `ctx->RefreshNonConfirmedComposition()` → **在信号分发中途重建 composition**，`TranslateSegments` 把 `selected_index` 重置为 0 → 下一个订阅者读到的是重建后的**另一个**候选，把它也删了。

**修复**：新增 `patches/librime-defer-composition-refresh-on-delete.patch`（**第 6 个补丁**，排在 `librime-fix-mapped-file-remap.patch` 之后，3 文件 +26/−1）——把重建从"每个订阅者内部"推迟到"全部分发完成后"：
- `src/rime/gear/memory.cc`：`OnDeleteEntry` 改调 `ctx->requestCompositionRefresh()`，不在分发中途刷新；
- `src/rime/context.h`：新增 `requestCompositionRefresh()` 与私有标记 `composition_refresh_pending_`；
- `src/rime/context.cc`：`DeleteCandidate` 分发前清标记，分发后若被请求则统一 `RefreshNonConfirmedComposition()` 一次。

**验证**：① Node 逻辑模型（`.verify` 之外，临时脚本）复现——旧实现删掉 `你这`+`逆着`（**与真机日志逐字一致**），新实现只删 `你这`；另覆盖单订阅者无回归、语言不匹配不删词、标记不残留；② 干净 `8d8276f4` 依序重放 **6 个补丁全部零退出**，且 `context.cc`/`context.h`/`memory.cc` 三个 blob 与工作台一致；③ CI [run 35435515145](https://github.com/SandyYuR/prebuilder/actions/runs/35435515145) **success**（约 18 分），`SandyYuR/prebuilt` `e7e50893` → **`6b5b2ee6`**（四 ABI `.a` 全更新，arm64 19,310,814 → 19,311,312，+498 字节）；④ APK 内 `librime.so` 含 `rime::Context::requestCompositionRefresh()` 符号（`llvm-nm` 实测），旧产物无此符号。主仓库 gitlink → `6b5b2ee6`；**pin 未变**（仍 `8d8276f4`），`librime.json` 无需改。

**过程中的三个教训（下次省时间）**：
1. **先核对日志再改代码**。第一次修复做成"删全同文来源（`GetGenuineCandidates`）"，前提是"一个词删不干净"；但日志两次删的是**不同的词**，方向就错了。该错误补丁的产物 `e7e50893` 一度被推上 prebuilt——**绝不能把主仓库指针 bump 到它**。
2. **配方（`LibRime.hs`）的 `do` 块只有最后一条 `cmd_` 带逗号**。把新行插在带逗号那行之后 → GHC `parse error on input '('`，`Build everything` **6 秒即挂**（配方编译阶段，与补丁无关）。已修正：逗号移到列表末行。
3. **判断补丁行尾要用对象库字节，不要用 `Out-String` 测量**。一度误判"补丁 CRLF 导致 CI 失败"，实测 `git cat-file -p <sha>:patches/...` 为 1370 字节、零 CRLF，判断作废。另注意：**取消 CI run 后 `Push to prebuilt` 可能已经执行完**，要核对产物父链确认拿到的是哪一版补丁构建的。

### 0.5.9 2026-09-19 候选词手势定型：按住后滑动（选字 / 操作菜单）

**起因**：用户反馈——先前移植的直接「上滑弹选字窗」用起来**展开候选列表没法滑动翻页**了。原因不在选字逻辑，而在触发方式：`CustomGestureView.swipeEnabled` 路径在 `ACTION_DOWN` 就派发 `GestureType.Down`、监听者随即 `requestDisallowInterceptTouchEvent(true)`，**手指刚按下触摸就被候选条目夺走**，父级 RecyclerView 收不到滚动事件。

**修法（纪元③ `2a19ddd3` → 纪元④ `87ed7f06`，5 文件 +126/−16）**：`CustomGestureView` 新增 **`holdSwipeEnabled`**「按住后滑动」模式，与 `swipeEnabled` 走独立分支、阈值复用 `swipeThresholdY`：

- `ACTION_DOWN`：**不派发 Down、不夺拦截**，只起一个 `longPressDelay` 计时（`holdSwipeJob`）；未进入已按住时的 `ACTION_MOVE` 全部放行给父容器（只更新 `swipeLastX/Y`，供进入已按住后起算，避免按住瞬间误触发），因此展开面板照常翻页；
- 计时到期：置 `holdSwipeArmed = true`、给长按触感反馈、**此时才补发 `GestureType.Down`**，监听者在这一刻调 `requestDisallowInterceptTouchEvent(true)` 接管；
- `ACTION_MOVE`（已按住）：只走 Y 轴 `consumeSwipe` 并派发 `Move`；
- `ACTION_UP`：**只在已按住时才配对派发 `Up`**（未按住时本视图从未发过 Down）；已按住且 `gestureConsumed == false`（原地没怎么动）→ 回落 `performLongClick()`，保住「长按弹菜单」语义；已按住时**不再触发 `performClick`**（否则按住后滑动会被当成选词）。`ACTION_CANCEL` 同一配对规则。
- `BaseInputView.bindCandidateGesture(view, text, resolveIndex)`：`resolveIndex` 在触发时才求值（与既有 click/长按监听一致，避免 DiffUtil 不 rebind 时下标停在旧起点）。`Move` 上用 `directionLocked` 保证一次按住只走一个方向：`totalY < 0` 弹选字窗（`totalY` 累计位移，由 `consumeSwipe` 维护），`totalY > 0` 调 `showCandidateActionMenu(...)`。

**不变式（改候选词手势必须守住）**：候选条目**不得在 `ACTION_DOWN` 就派发 Down 或夺走父容器拦截**——一旦这么做，展开候选面板立即失去滚动能力。要区分「轻滑」与「按住后滑」，只能在按满判定时间后再接管。这与第 0 节 Kawaii Bar 那条同源：**手势/布局都不得依赖"按下瞬间"的抢占式状态**。

**验证与遗留**：
- 已做：`:app:testFxDebugUnitTest` 全绿、`:app:assembleFxDebug` 出包、`git diff --check` 干净、CI [run 35460755786](https://github.com/SandyYuR/fcitx5-android/actions/runs/35460755786) 三个 job 全绿（Nightly `0.1.3-617-g2a19ddd3`，纪元③ 的 run）。lint 仍为存量失败（`MissingTranslation` 123、`NewApi` 23 等），本次只新增 2 条 `ClickableViewAccessibility` warning，无新增 error。**纪元④ 重写后该 run/ Nightly tag 对应的是旧 `2a19ddd3`**；重写后的同一内容已重新本地验证（`build-debug.ps1 -Test` 与出包均为 exit 0），但**尚未推送、因此没有对应 CI run**。
- **未做（重要）**：**真机手势回归没做**。改动期间 `adb devices` 一直为空，设备离线。手感相关项（默认 300 ms 判定是否合适、按住后滑动是否跟手、展开面板翻页是否确实恢复、按住不动抬手是否稳定弹菜单）**必须真机确认**，符号与单测通过不等于交互可用。
- 已知细微遗留：同一候选词在一次删除分发里会被两个订阅者各删一次（日志同一毫秒两条 `deleting entry`）。功能无害（同一精确键、词库只减 1 条），但会把 `commits` 从 `-N` 覆写为 `-1`，影响该词被重新输入「复活」时的初始权重。若要收敛需再加幂等守卫补丁（第 7 个）。

**顺带修正的文档口径**：本仓库 `README.md` 与用户指南 5.6 节已按定型后的手势改写；此前 0.5.8 节摘要里「同任务新增候选词上滑选字（`ed6422a8`）」的描述已被本次取代，`ed6422a8` 仍是历史提交，但其「直接上滑」的交互**不再是当前行为**。

### 0.5.10 2026-09-20 第六次实战（合入万象作者 PR #1232「rewrite」滤镜 + bump pin，方案 B）

**起因**：用户发现上游新 PR [rime/librime#1232](https://github.com/rime/librime/pull/1232)「feat: 新增高效自定义滤镜组件rewrite(改写工具)」（作者 **amzxyz**，即**万象**输入方案作者），要求合入本项目，并选择**方案 B：连带把 pin 一起更新**（而非保守地只在旧 pin 上打补丁）。

**PR 是什么**：单提交 `e3c91382`，+2572/−0，8 文件——新增 `dict/rewrite_pack.{cc,h}`、`gear/rewriter.{cc,h}`、`lever/rewrite_compiler.{cc,h}`，并改 `gear/gears_module.cc`（注册 `rewriter` 组件）、`lever/deployment_tasks.cc`（`SchemaUpdate::Run` 内编译 `.rwp`）。**base 是 `74bd5dc4`（上游当时 tip），我们原 pin 是 `8d8276f4`（落后 2 提交）**，这两个提交是 `b2a5c5ea`（chord_composer factory dispatch）与 `74bd5dc4`（ascii_composer `commit_raw_input`）。

**关键语义（回归必看）**：`RewriteCompiler::Compile()` 在方案**未声明 `rewriter` 段时直接 `return true`**、无任何副作用 → 对既有方案零影响。但一旦声明了 `rewriter`，**编译失败会让整个部署失败**（`SchemaUpdate::Run` 返回 false）。`.rwp` 写在 `deployer_->staging_dir`，按源校验和判断是否 up-to-date。schema id 做了路径合法性校验（拒绝绝对路径/含父目录）。

**工作台实测（`librime-src`，全在临时 worktree 里做，未污染主工作区）**：

| 实验 | 结果 |
|---|---|
| 既有 6 补丁 → `8d8276f4` / `74bd5dc4` | 均全部干净 |
| 6 补丁 + cherry-pick PR → 两基线 | 均**无冲突**（自动合并 `gears_module.cc`、`deployment_tasks.cc`） |
| 配方式普通 `git apply` PR diff → 两基线 | 均 exit=0 |

**两条基线都能干净合**，不必为这个 PR 强行升级引擎；选方案 B 是因为补丁写在 `74bd5dc4` 上，bump 后**正好落在其原始创作基线**，无上下文漂移。

**⚠️ 本机 CRLF 陷阱（新踩，务必记住）**：`prebuilder` 是 `core.autocrlf=true` 的 Windows checkout，`git ls-files --eol` 显示补丁在 worktree 为 `w/crlf`、索引里为 `i/lf`。**直接在 worktree 里对补丁 `git apply` 会得到假失败**（`librime-perf-deploy-...` 与 `librime-userdict-cache` 两个补丁初次就报 `patch does not apply`）。把行尾归一化为纯 LF 后**全部 exit=0**。CI 是 Linux、拿到的一直是 LF，所以历史上从未暴露。**结论：本机任何 `git apply` 前先确认补丁为纯 LF**（或用 `git cat-file blob` 取索引版本）。这与 0.5.8 教训 ③「判断补丁行尾要用对象库字节」互补：那次教训是**别误判**，这次是**别被本地转换坑**。

**改动落地**：
- `prebuilder@6e202a0`（已推送）：新增第 7 个补丁 `patches/librime-pr1232-rewrite-filter.patch`（由 `git diff 74bd5dc4 e3c91382` 直出，**纯 LF、无邮件头**，87237 字节），`src/Rules/LibRime.hs` 在序列**末尾**追加 `git apply`（注释说明 additive 语义），`librime` gitlink `8d8276f4`→`74bd5dc4`。注意逗号移到新的末行（0.5.8 教训 ②）。
- CI [run 35498252597](https://github.com/SandyYuR/prebuilder/actions/runs/35498252597) **绿**（约 16 分钟），产物落地 **`prebuilt@a8423ad2`**。
- 主仓库 `ab06dbfd`：prebuilt 指针 `6b5b2ee6`→`a8423ad2`、`librime.json` `1.17.0-8d8276f`→`1.17.0-74bd5dc`、README 补丁清单加入万象补丁说明与版本号。**已提交到本地，尚未推送**（远程仍是纪元③ `59a3a1ab`，见第 2 节）。

**产物核对（四层，不能只看文件在）**：
1. arm64 `librime.a` 19,311,312 → **19,775,858** 字节（+464,546，即 rewrite 三个新模块）；
2. 二进制字符串检查：新 `.a` 含 `RewritePack`/`RewriteCompiler`/`rewriter`，**旧 `.a` 三个都没有**；既有定制 API（`RimeGetInputTabs`/`RimeSelectTab`/`RimeGetCandidateCode`/`RimeGetCandidatePreview`）在新旧产物都在；
3. 出货 `rime_api.h` blob `030ff78a`（22607 字节）与工作台「`74bd5dc4` + 7 补丁依序重放」产物**逐字节一致**；
4. 回环零差异：7 补丁方案 vs「6 补丁 + cherry-pick PR」结果树 `git diff --quiet` exit=0。

**⚠️ 符号检查方法（避免误判）**：`RimeGetInputTabs` / `RimeSelectTab` / `RimeGetCandidateCode` / `RimeGetCandidatePreview` 在 `rime_api_impl.h` 里是 **`static` 函数**，通过 `s_api.get_input_tabs = &RimeGetInputTabs` **挂进 API vtable**，**不是独立导出符号**——`llvm-nm` 搜不到是**正常的**，不能用 `nm` 判定定制是否存在。可靠做法：`llvm-nm --defined-only` 找 C++ 符号（如 `rime::RewriteCompiler`），或直接在二进制里搜名字字符串；APK 里的 `librime.so` 被 strip，只能靠字符串表/动态符号表判断。

**本机 debug 包验证**：`build-debug.ps1` 出包 25.64 MB（arm64-v8a，9 个原生库），版本号含 `16-gab06dbfd`；`adb install -r` 到设备 `e09303ba` 成功。APK 内 `lib/arm64-v8a/librime.so` 字符串表含 `RewritePack`/`RewriteCompiler`/`rewriter`。

**未做（重要）**：**真机输入回归没做**。本次是 JNI/C++ 引擎替换，且 bump 顺带引入两个上游按键域行为变更——`ascii_composer` 新增 `commit_raw_input`（Shift+Return / Shift+KP_Enter / Shift+space）与 `chord_composer` factory dispatch，与我们自己的 Shift/alt-trigger 定制**同属按键域**，风险面重叠。必须真机覆盖：① 打字→点音节 tab→选词；② 语言键短按 Shift 切换、**长按弹方案选单**（注意：工具栏语言按钮长按才是系统输入法选择器，两者不同，见 AGENTS.md 第 6 节）；③ **Shift+Return / Shift+空格 / Shift+KP_Enter**（上游新行为）；④ 并击（chord_composer）；⑤ 首次部署与重新部署（`SchemaUpdate::Run` 新增编译调用会让部署失败路径变多）；⑥ 忘记词汇（第 6 补丁路径，见 0.5.8）。**编译通过、符号在列都不等于交互可用**。

**遗留的幂等守卫**：见 0.5.9 末尾——同一次删除分发里同一候选被两个订阅者各删一次，若要收敛需再加幂等补丁（**若加，将是第 8 个补丁**；注意本节新增的是第 7 个，序号别混）。

#### 0.5.10 续 作者 force-push 后的跟进（同日第二轮）

**发现方式（重要，可复用）**：用户提醒「PR 好像被强推了」。核对 `GET /repos/rime/librime/pulls/1232` 得 head `e3c91382` → **`16f72b0c`**，`updated_at` 变了；决定性判据是 **`git merge-base --is-ancestor e3c91382 16f72b0c` 返回失败**——若为纯追加则应为成功。**引用外部 PR 的 head SHA 前必须重新核对，不能沿用上一轮记录的值。**

**差异范围极小**：`e3c91382 → 16f72b0c` 只改 `src/rime/dict/rewrite_pack.cc`（+169/−7），**其余 7 文件与全部头文件零变化**；base 仍是 `74bd5dc4`。改动量 +2572 → **+2734**。

**新版加了什么（starter filter，性能优化）**：
- 每个 stage 附带 **8KB 位图**（`kStarterFilterBitCount = 1<<16`，双 FNV seed 布隆式），记录"哪些字符能作为词条起始"；
- **关键：那 16 字节早就预留了**——旧版 `WriteStageRecord` 末尾写的是 `WriteU64(out, 0); WriteU64(out, 0)`（offset 48/56 补零），新版才真正填 `starter_filter_offset/size`。**记录布局未移位**，故结构兼容；
- 新增 `kStageFlagHasStarterFilter = 1U << 1`。`Open()` 校验：置位则过滤表尺寸必须精确等于 8KB 且在文件范围内；**未置位则 offset/size 必须为 0**（即对旧 pack 的兼容分支）；
- 新增 `ValidUtf8CharSize()`（严格 UTF-8 校验）与 `SkipUnmatchable()`：**跳过连续"不可能起始"的字符段，省掉 marisa `Agent` 查询**——性能收益来源；
- 畸形 key → `starter_filter.fill(0xff)` **饱和整个表**（注释明说 "saturating disables skipping but preserves exact behavior"），保证行为与旧版一致；
- 新增 `kBuildIdRevision = 2` 折进 `ComputeBuildId()`；**`IsUpToDate()` 现在要求 `HasStarterFilter` 置位**，否则返回 false。

**兼容性结论**：`kFormatVersion` **仍是 4**（未升版本号）；但 `IsUpToDate` 的新条件 + build id 多了 revision，会让**已部署的旧 `.rwp` 判为过期 → 下次部署自动重建**。这是安全的自动迁移路径，不需手工清缓存。

**落地与验证**：
- `prebuilder@2886a2c`（已推送，仅改补丁文件 +171/−9）：补丁由 `git diff 74bd5dc4 16f72b0c` 重出（纯 LF、93379 字节、2806 行）；`LibRime.hs` 无需改（文件名与位置不变）。
- 验证：新补丁在干净 `74bd5dc4` 上单独 `--check` exit=0；6 补丁 + 新补丁依序应用 **7/7 exit=0**；与「6 补丁 + cherry-pick `16f72b0c`」结果树回环对比 **`git diff --quiet` exit=0 零差异**。
- CI [run 35501800444](https://github.com/SandyYuR/prebuilder/actions/runs/35501800444) **绿**（约 18 分钟），产物 **`prebuilt@6c226341`**。arm64 `.a` **19,775,858 → 19,778,482**（+2,624，即过滤表代码）；**`rime_api.h` blob 仍是 `030ff78a`**（头部零变化，符合预期）。
- 二进制字符串检查：新 `.a` 含 `RewritePack`/`RewriteCompiler`/`rewriter` 与既有四个定制 API。
- 主仓库该指针 commit：**amend 重写为 `45550041`**（`ab06dbfd` → `45550041`，prebuilt 指针 `a8423ad2`→`6c226341`，正文补记 force-push 与 starter filter），用 `--force-with-lease` 带显式期望值推送。**改历史前先建回退点 `backup/pre-startfilter-bump` → `ab06dbfd`**（纪律见第 2 节）。
- 本机 debug 包：`build-debug.ps1` 出包 25.64 MB，版本号含 `16-g45550041`，`adb install -r` 到设备 `e09303ba` 成功；APK 内 `librime.so` 动态符号表可见 `rime::RewriteCompiler::Compile()`、`rime::RewritePack::Open()` 等。

**教训**：跟踪**未合并的上游 PR** 时，作者随时可能 force-push。若已把其提交产出打进了产物链，跟进成本是一条完整流水线（CI 15-20 分钟 + prebuilt + 主仓库指针 + 重出包）；**建议：要么等 PR 合并/稳定再合，要么明确接受可能连续跟进**。本次两轮之间隔约 1 小时。

### 0.5.11 2026-09-20 第七次实战（同一 PR 二次 force-push → 补丁原地升级，pin 不变）

**起因**：用户报「PR #1232 好像又更新了」。核对发现 prebuilder 的 `librime-pr1232-rewrite-filter.patch` 停在 `2886a2c2`（跟进的是**上一次** force-push 的 starter filter 版），而 PR head 已是 `bf704201`（09-20 11:12 UTC），**晚于配方提交时间 09:14 UTC**——作者第二次 force-push，把 pack 的加速结构整体换掉了。**pin 未变**（仍上游 `74bd5dc4`），只换补丁。

**新版改了什么（RWP4 → RWP5，硬性不兼容）**：

| 项 | 旧版（上一轮接入） | 新版（`bf704201`） |
| --- | --- | --- |
| magic / `kFormatVersion` | `RIMERWP4` / 4 | `RIMERWP5` / **5** |
| `kStageRecordSize` | 64 | **72**（新增 dispatch 表偏移与大小字段） |
| 加速结构 | 每 stage 附 8KB「起始字符位图」 | 换成 code point → **直接分派表**寻址 |
| 新增 | — | `kDispatchPhraseStarter`(`1U<<31`) 标记短语起始、`kDispatchKeyMask` 取编码后 key id |
| 删除 | `kStageFlagHasStarterFilter` + 位图 | — |
| `kBuildIdRevision` | 2 | 1 |

规模 8 文件 +2817 行（旧版 +2734）。`Compile()` 在方案未声明 `rewriter` 段时提前返回，**既有方案不受影响**；但已生成的 `.rwp` 会因版本号不匹配被 `IsUpToDate()` 判为过期，**重新部署该方案即可重建，无需迁移数据**。

**做了什么**：① 从 PR 拉取新补丁（`https://api.github.com/repos/rime/librime/pulls/1232` 带 `Accept: application/vnd.github.v3.patch`，注意 `pull/1232.patch` 直链易被限流返回 HTML）；② 生成 prebuilder 风格的替换补丁（**在已提交的 6 补丁 base 上**再应用 PR 补丁，`git diff --cached`）覆盖 `patches/librime-pr1232-rewrite-filter.patch`（+553/−470）；③ 提交 `04d488c` 推送 prebuilder，CI [run 35517987608](https://github.com/SandyYuR/prebuilder/actions/runs/35517987608) **success**（约 18 分）；④ 主仓库 prebuilt 指针 `6c226341` → **`f4225ada`**（arm64 `librime.a` 19,778,482 → 19,821,890 字节）；⑤ 主仓库把原引擎提交 **重写**（`45550041` → `56af1ae5`，仅 prebuilt gitlink 一处差异），并 cherry-pick 其后的 `77ec794f` → `1364e59c`，**只本地提交，未推送**。

**验证**：① 按 `LibRime.hs` 配方顺序在干净 `74bd5dc4` 上依序重放**全部 7 个补丁零退出**；② 6 个纯新增文件（`rewrite_pack.cc/.h`、`rewriter.cc/.h`、`rewrite_compiler.cc/.h`）与 PR head 真实文件 `cmp` **逐字节一致**；③ 回环重放与逐字节比对树 `diff -rq` **零差异**；④ `:app:assembleFxDebug` 构建成功（3m24s），APK 内 `librime.so` 含 `RIMERWP5` magic 与 RWP5 专有报错串（`key count exceeds the RWP5 direct-dispatch limit`、`character dispatch table exceeds the RWP5 limit`）。

**三个教训**：
1. **上游 force-push 后，先核对 prebuilder 配方是否跟上**（比提交时间与 PR head 时间）。这次 PR head（11:12 UTC）比配方（09:14 UTC）晚，属于"配方滞后"，不必改配方结构，只需换补丁文件内容。判断"有没有更新"不能只比补丁大小，要落到 `kFormatVersion` 这类语义特征上（本次 93,379 → 95,672 字节）。
2. **用 `git diff` 生成补丁前必须先把基线提交掉**。第一次在"已应用 6 补丁但未提交"的树上直接 `git diff --cached`，把前 6 个补丁的改动一并卷进来，得到 40 文件 / 6765 行的废补丁。正确做法：`git add -A && git commit`（base 6 补丁）→ 应用 PR 补丁 → `git diff --cached`，得到干净的 8 文件 / 2889 行。
3. **定制 C API 在 `.a` 里是内部链接符号（`_ZL`），别用 `nm -D` 查**。`RimeGetInputTabs`/`RimeSelectTab`/`RimeGetCandidatePreview` 都查不到动态表（查得 0），会被误判成"补丁丢了"。用宽松 `strings librime.a | grep -c` 可稳定得到 4 处，**且新旧产物数值一致**（old=4 / new=4）才说明无回归。另：APK 里只有 `librime.so`，没有独立的 `libfcitx5-rime.so`，适配层已静态链接进去。

### 0.5.12 2026-09-21 第八次实战（同一 PR 第三次 force-push：RWP5 内部加固，base 前进但 pin 不动）

**起因**：用户报「好像又更新了」。核对发现 PR head 从 `bf704201` 再变为 `abbdacea`，且**这次 `base` 也前进了**（`74bd5dc4` → 上游 `1809d072`，master 已到 `14f14cba`，中间夹着 streaming_chord 系列与 key_binder 修复）。与上次不同：**格式仍为 RWP5**（magic/`kFormatVersion` 5、`kStageRecordSize` 72 均未变），只是 RWP5 内部的精修，**与已接入版本二进制兼容**。

**新版改了什么（+2890 行 / 旧 +2817）**：
1. **安全加固**（本次最主要）：新增 `ValidatePhraseTrieBounds()`——校验 Darts 短语 trie 的 unit 数为 256 的整数倍、在 uint32 范围内，并逐个检查 `i ^ DartsUnitOffset(unit)` 的转移基址不越界；越界时 `LOG(ERROR) "contains a malformed phrase trie in section ...; redeploy the schema to rebuild the pack."` 并拒绝加载。原因是 Darts 查询会把状态索引与偏移 XOR 后直接索引数组，恶意/损坏的 `.rwp` 可造成映射区外的非受检访问。
2. **配置项重构**：`key_projection` → **`key_xlit`**（新增 `BuildKeyXlit()` 与 `"key_xlit converted source key ..."` 诊断）；`prefer_types` → **`promote_on_types`**；`Mode::kAppend` → `kDerive`；`CommentMode` 拆成 `CommentSource`（`kComment` 归入 `kInherit`）。
3. `AppliesToSegment()` 由内联改为记录 segment 范围（`has_segment_range_`）。
4. 新增配置键：`candidate_type`/`comment_source`/`comment_template`/`enable_sentence`/`insert_count`/`insert_position`/`promote_on_types`。

⚠️ **第 2 条是用户可见的配置项改名**：已有方案若写了 `key_projection` / `prefer_types`，升级后会失效，需改成 `key_xlit` / `promote_on_types`。本次未改用户指南（尚无用户实际使用 rewrite 段的记录），若后续有反馈要补进 `RIME_ONLY_USER_GUIDE_zh-CN.md`。

**做了什么**：① 拉取新补丁；② 在已提交的 6 补丁 base 上生成 prebuilder 风格替换补丁（+268/−195）覆盖 `patches/librime-pr1232-rewrite-filter.patch`；③ 提交 `487683c` 推送 prebuilder，CI [run 35558511253](https://github.com/SandyYuR/prebuilder/actions/runs/35558511253) **success**；④ 主仓库 prebuilt 指针 `f4225ada` → **`a1865519`**（arm64 `librime.a` 19,821,890 → 19,822,336 字节）；⑤ 按要求**在 `56af1ae5` 上重写**（→ `f8119628`，仅 prebuilt gitlink 一处差异），并 cherry-pick 其后的 `1364e59c` → `746271df`，**只本地提交，未推送**。

**验证**：① 配方顺序重放全部 7 个补丁零退出；② 6 个纯新增文件与 PR head（`abbdacea`）真实文件 `cmp` 逐字节一致；③ 回环重放与逐字节比对树零差异；④ 新 `.a` 含 RWP5 magic 及本次新特性特征串（`ValidatePhraseTrieBounds`/`key_xlit`/`promote_on_types`/`BuildKeyXlit` 共 6 处）；⑤ 既有定制 API 符号计数与上一版**一致**（`RimeGetInputTabs`/`RimeSelectTab`/`RimeGetCandidatePreview` 均 4 处）。

**三个教训**：
1. **PR 的 `base` 前进 ≠ 必须 bump pin**。这次 base 从 `74bd5dc4` 跳到 `1809d072`，看起来该跟上游；但补丁**在现 pin 上实测仍干净应用**（`git apply --check` 零退出）。判断依据是「补丁能否应用到当前 pin」，不是「PR 的 base 是什么」。
2. **同一次 PR 的 force-push 未必是破坏性变更**。前两次分别是 RWP4→RWP5 的结构替换（不兼容）；这次仍 RWP5，只是内部加固 + 改名（二进制兼容，既有 `.rwp` 无需重部署）。**先比 `kMagic`/`kFormatVersion`/`kStageRecordSize` 这类结构常量再下结论**，不要因为补丁变大就假定需要重新部署。
3. **base 前进时要注意上游新增提交**（本次夹了 streaming_chord 三连 + key_binder 修复）。它们不在定制补丁范围内、也不在 pin 变动范围内——**pin 不动就不会进入产物**，不要误以为「base 变了就等于上游已合入」。

### 0.5.13 2026-09-21 第九次实战（PR 第四次更新：架构改为共享 store + 首次 bump pin）

**起因**：用户要求「再次跟进 PR #1232，顺便看 librime 其他新改动有无可合并」。核对发现 PR head 从 `abbdacea` 变为 **`7e503855`**，规模由 8 文件 +2890 行扩到 **10 文件 +4518 行**；同时上游 master 已到 `ef1a16aa`（比上次多 7 个提交）。

**PR 新版改了什么（架构级重构）**：从「每方案一个 `.rwp`」改为「staging 下单一共享 `rewriter.rwp` + 部署期事务」：
1. **新增 `src/rime/dict/rewrite_store.{cc,h}`**（+1569 行）：`RewriteStore` / `RewriteStoreReader` / `RewriteStoreWriter`，按 stage id 去重存储各方案数据；带 workspace 事务（`BeginWorkspace` / `AbortWorkspace` / `RetainSchemas` / `Compact`）；用 mutex 串行化 store 与 workspace 访问。
2. `RewriteCompiler` 新增 `BeginWorkspace` / `AbortWorkspace` / `FinalizeWorkspace` 三个静态方法，`Compile()` 改为写入共享 store。
3. `WorkspaceUpdate::Run` 包裹整个部署：开始 `BeginWorkspace`，结束 `ReleaseStagesForDeployment` + `FinalizeWorkspace`，失败则 `AbortWorkspace` 回滚。
4. `SchemaUpdate::Run` 改为在词典编译前后各调一次 `compile_rewriter()`，且**方案无 `translator/dictionary` 时也编译**。
5. `ResolveDataFile` 拒绝绝对路径与 `..` 组件，只接受数据根目录下的相对路径。
6. 格式仍为 **RWP5**（`kFormatVersion` 5、`kStageRecordSize` 72 未变），既有 `.rwp` 二进制兼容。

**上游可合并项（本次一并 bump pin：`74bd5dc4` → `ef1a16aa`）**：

| 提交 | 内容 | 对本项目 |
| --- | --- | --- |
| `1809d0725d` | `fix(key_binder): restore period after paging` | **直接相关**（见下） |
| `899089a6cf` / `3381859eed` / `662761f01f` | streaming_chord 三连：dual role keys、delimiter after open chords、chord with action suffix | 无影响（本项目未启用 chord） |
| `14f14cba9d` | `fix(streaming_chord)`：>1 个 dual role 键的修复 | 无影响 |
| `82bb921a6b` | `chore: fix tests on windows`（`engine.cc` 的 `ApplySchema` 支持原位重载） | 无影响（不改变行为） |
| `ef1a16aa2c` | `docs: chording architecture & configuration` | 无影响 |

**key_binder 修复为何相关**：`plugin/rime/src/main/cpp/default.yaml` 启用了 `key_bindings:/paging_with_comma_period`（`{when: paging, accept: comma, send: Page_Up}` 与 `{when: has_menu, accept: period, send: Page_Down}`）。旧实现用 `last_key_` 只记上一次按键，在「句号翻页后接字母」时会误吞句号；新实现改为记录「真正执行了翻页」的键（`last_paging_key_`）与次数（`paging_keystroke_count_`），**仅在句号确实用作翻页键、且恰好按过一次、后接字母时**才把句号还原给 ascii_composer（用于敲域名）。另有 `key_event.modifier() != 0` 提前返回、以及未命中绑定时清记录的改动。**这是用户可感知的输入行为修复**。

**补丁冲突与手工合并（本次重点）**：新 PR 补丁在**纯上游基线**上干净应用，但与我们的 `librime-perf-deploy-compile-independent-dictionaries-in-para.patch` **冲突**（`deployment_tasks.cc` 两个 hunk 被拒）。原因：该补丁把 `WorkspaceUpdate::Run` 的 schema 循环**内联**（`process_schema`）并按依赖关系收集 `SchemaCompileUnit` 并行编译词典，**不再经过 `SchemaUpdate::Run`**，而新版恰好把 rewriter 编译挂在那里。合并方式：
- 在内联路径的 schema 配置加载成功后调用 `compile_rewriter(schema_id)`——位置刻意选在 `translator/dictionary` 分支判断**之前**，从而同时覆盖「有词典」与「无词典」两条分支，与上游 `SchemaUpdate::Run` 的两处调用等价；
- 保留 `BeginWorkspace` / `FinalizeWorkspace` 对 `WorkspaceUpdate::Run` 的包裹，`active_schema_ids` 在 `process_schema` 内收集；
- **额外修一处泄漏**：`MaybeCreateDirectory(deployer->staging_dir)` 失败会直接 `return false`，此时 workspace 事务仍是活动状态，会让**同一进程后续每次部署**都因 `"rewrite workspace transaction already active"` 失败。已在早退分支补 `AbortWorkspace(deployer)`。上游原版无此早退路径（其 `SchemaUpdate` 内部自己创建目录），内联路径才有，属于合并引入的新风险点。

**做了什么**：① 拉取新补丁并逐字节核对；② 手工合并后生成 prebuilder 风格补丁（10 文件 / 4681 行）覆盖 `patches/librime-pr1232-rewrite-filter.patch`；③ **`git update-index --cacheinfo` 把 prebuilder 的 librime gitlink 从 `74bd5dc4` 前移到 `ef1a16aa`**；④ 提交 `0fa3e9e` 推送 prebuilder，CI [run 35633474637](https://github.com/SandyYuR/prebuilder/actions/runs/35633474637) **success**；⑤ 主仓库 prebuilt 指针 `a1865519` → **`eb5bc82e`**（arm64 `librime.a` 19,822,336 → 20,149,324 字节）；⑥ `librime.json` 与 README 引擎版本同步到 `1.17.0-ef1a16a`；⑦ **按要求新建提交（不重写历史），只本地提交，未推送**。该提交后与 0.5.14 的提交**一并合并为 `a560aabe`**（见 0.5.14 末注），本节记录的是合并前的中间状态。

**验证**：① 6 个定制补丁在新 pin 上依序应用全部零退出；② 新补丁在「`ef1a16aa` + 6 补丁」树上干净应用；③ 8 个纯新增文件与 PR head（`7e503855`）真实文件 `cmp` **逐字节一致**，`gears_module.cc` 亦一致；④ 回环重放与逐字节比对树零差异；⑤ 新 `.a` 含 RWP5 magic 与 `RewriteStore`/`RewriteStoreWriter`/`ValidatePhraseTrieBounds`/`key_xlit` 符号；⑥ **`key_binder.cc.o` 由 125,136 增至 125,184 字节**（key_binder 修复确已编入产物）；⑦ 既有定制 API 计数与上一版一致（均 4 处）；⑧ 四 ABI 归档齐全；⑨ `:app:assembleFxDebug` 成功（9m26s，25.71 MiB），APK 内 `librime.so` 含 `RewriteStore` 系列符号与 `RIMERWP5`，`rime-data` 仍为 4 个通用预设文件（无方案残留）；⑩ `:app:testFxDebugUnitTest` 通过（2m39s）。

**三条教训**：
1. **「PR 更新」可能带来架构级变化，不能只看行数**。前三次都是同一套文件里的精修，这次新增了 `rewrite_store.{cc,h}` 把「每方案一包」换成「共享 store + 事务」，`deployment_tasks.cc` 的挂钩点也从 `SchemaUpdate::Run` 单点变成「`WorkspaceUpdate::Run` 首尾包裹 + `SchemaUpdate` 内两处调用」。**先看 `git diff --stat` 的文件清单有没有新增文件**，再看结构常量。
2. **冲突往往来自我们自己的补丁，而非上游**。新 PR 补丁在纯上游干净、在「纯上游 + 我们的 perf 补丁」上失败——定位手法是先在两棵纯基线上分别 `git apply --check`，逐步排除，确认冲突源是 `librime-perf-deploy` 的内联改写。**「上游补丁应用不了」不等于上游有问题**。
3. **合并引入的早退路径要单独审事务/锁的生命周期**。上游的 `BeginWorkspace` 与 `FinalizeWorkspace` 之间没有 `return`，但我们的内联路径有 `MaybeCreateDirectory` 早退。**凡是把「成对 acquire/release」跨接进自己的代码路径，都要把该函数内所有 `return` 数一遍**，逐个补上回滚。

**符号检查方法再次确认**：定制 C API（`RimeGetInputTabs` / `RimeSelectTab` / `RimeGetCandidatePreview`）在 `.a` 里是 **`static` 内部链接**（`_ZL` 前缀），`nm -D` 与 APK 内 strip 过的 `librime.so` 都查不到，**查得 0 是正常的**。可靠做法：`strings librime.a | grep -c` 与上一版对比数值（本次 old=4 / new=4），或用 `strings librime.so | grep -oE "_ZL[0-9]+Rime(GetInputTabs|SelectTab|GetCandidatePreview)[A-Za-z]*"` 看到 `_ZL` 名字。

### 0.5.14 2026-09-23 第十次实战（PR 第五次更新：抽内部头 + preset 目录，pin 不变）

**起因**：用户再次要求跟进 PR #1232。核对发现 PR head 从 `7e503855` 变为 **`c4cdc749`**，规模 10 文件 +4518 行 → **13 文件 +4546 行**；同时 PR 的 `base` 恰好是 `ef1a16aa`——**与我们当前 pin 相同**，且上游 master 自上次接入后**一个提交都没前进**。所以本次**只需换补丁，pin 不动**：`librime.json` 与 README 版本行保持 `1.17.0-ef1a16a`，只更新 prebuilt 指针。

**补丁变化**：
1. **新增 `src/rime/dict/rewrite_internal.h`**（+197 行）：把 `RewritePack` / `RewriteStore` 共用的二进制布局辅助（`ReadU32`/`WriteU32`、`boost::align` 对齐、`boost::endian`、临时文件创建等）抽成独立内部头。`rewrite_pack.cc` 因而由 1502 行降到 1078 行——**这是纯粹的重构，不是功能增删**。
2. **新增 `src/rime/lever/rewrite_preset.{cc,h}`**（+183 行）：`RewritePresetCatalog` / `RewritePresetStage`，支持方案以 **preset 名**引用一组预置 rewrite 数据，而不是逐个列 `files`。
3. **`kFormatVersion` 由 5 回落为 1**。这不是「回退到旧 RWP4 结构」——`kStageRecordSize` 仍 72，dispatch 短语起始位（`kDispatchPhraseStarter`）与 preedit 标志都还在，属于作者重构期间**重置了格式号**。影响：上次接入生成的 RWP5 包会因版本号不匹配被判过期（`version != kFormatVersion` → `LOG(ERROR)` 提示 `redeploy`），重新部署该方案即可重建。
4. **保持不变**：`ValidatePhraseTrieBounds`（Darts 短语 trie 边界校验）与 `ValidateStageRecord` 两个安全校验仍在；`RewriteCompiler` 的 `BeginWorkspace` / `AbortWorkspace` / `FinalizeWorkspace` 静态接口、以及 `RewriteStore` 共享 store 架构均未变。

⚠️ **作者的 WIP 自述**：「本次提交暂未处理 OpenCC 原始 txt 数据在共享目录中的获取、安装及打包。preset 当前依赖 `<共享目录>/opencc` 下的 `presets.yaml` 与相关 txt 数据；该数据部署机制将另行提交，在此之前缺少对应文件时 preset 不可用。」——**方案未声明 preset 时不受影响**；声明了但因缺文件而不可用属于上游已知缺口，不是我们的合并问题。

**冲突与合并**：新补丁在**纯上游基线**上依然干净，但仍与我们的 `librime-perf-deploy-compile-independent-dictionaries-in-para.patch` **冲突**（`deployment_tasks.cc` 两个 hunk 被拒，与 0.5.13 完全相同的位置）。合并照抄 0.5.13 的四步：① `BeginWorkspace` + `active_schema_ids` 声明；② `process_schema` 内收集 schema id；③ schema 配置加载成功后调 `compile_rewriter(schema_id)`（置于 `translator/dictionary` 分支**之前**，覆盖有/无词典两条路径）；④ `#endif` 之后、`finished updating schemas` 之前插入 `FinalizeWorkspace` 包裹。外加**沿用上次发现的泄漏修复**：`MaybeCreateDirectory` 早退时补 `AbortWorkspace(deployer)`。

**做了什么**：① 拉取新补丁；② 手工合并后生成 prebuilder 风格补丁（13 文件 / 4722 行）覆盖 `patches/librime-pr1232-rewrite-filter.patch`（+1592/−1551）；③ 提交 `e4d99c8` 推送 prebuilder（**pin 未改**），CI [run 35808587609](https://github.com/SandyYuR/prebuilder/actions/runs/35808587609) **success**；④ 主仓库 prebuilt 指针 `eb5bc82e` → **`8ad0193f`**（arm64 `librime.a` 20,149,324 → 20,321,046 字节）；⑤ **新建提交（不重写历史，只动 prebuilt gitlink 一个文件）**，只本地提交、未推送。

> **合并说明（2026-09-24）**：本会话针对 PR #1232 的**全部**引擎跟进提交（pin bump、第四次至第六次补丁更新，先后共 5 条）同为引擎元数据改动，按用户要求**合并为一条 `a560aabe`**（`feat(引擎): 引擎 pin 前移至 ef1a16aa，rewrite 补丁跟进 PR #1232 至 8530499b`，相对父提交 `acb1d3b9` 净变化 3 文件 +3/−3）。0.5.13、0.5.14、0.5.15 三节正文记录的中间 SHA 已被合并取代，**不再存在于分支历史**；prebuilder 侧的 `0fa3e9e` / `e4d99c8` / `c2ef978` 与 CI run 编号仍然有效，是追溯产物来源的可靠依据。

**验证**：① 6 个定制补丁在 pin 上依序应用全部零退出；② 新补丁在「`ef1a16aa` + 6 补丁」树上干净应用；③ **12 个纯新增/替换文件与 PR head（`c4cdc749`）真实文件 `cmp` 逐字节一致**；④ 回环重放与逐字节比对树零差异；⑤ 新 `.a` 含 `RewriteStore`(128)/`RewritePresetCatalog`(21)/`RewritePack`(86)/`rewrite_internal`(32) 符号；⑥ 既有定制 API 计数与上一版一致（均 4 处）；⑦ 四 ABI 归档齐全；⑧ `:app:assembleFxDebug` 成功。

**三条教训**：
1. **PR 的 `base` == 我们的 pin 时，pin 一定不用动**——这次是首次遇到「PR base 自己前进来对齐我们的 pin」。判断链条很短：先比 PR base 与当前 pin，相等即跳过 pin 决策；再确认上游 master 有没有新提交（本次无）。
2. **`kFormatVersion` 变化要看「是否伴随结构变化」**，不能只看到数字变小就判定「回退」。本次版本号 5→1 但 `kStageRecordSize` 仍 72、dispatch 标志仍在，说明是重构期重置。**判据是结构常量集合是否一致，不是版本号本身的大小方向**。
3. **「文件行数大变动」可能只是搬家**。`rewrite_pack.cc` 少了 424 行、多出一个 197 行的 `rewrite_internal.h`，净变化很小——先看新增/删除文件清单，再看逐文件行数差，避免把纯重构误判成重写。

### 0.5.15 2026-09-24 第十一次实战（PR 由单提交变 3 提交：两处查询性能修复）

**起因**：用户报告「PR 又有更新」。核对发现 PR head 从 `c4cdc749` 变为 **`8530499b`**，规模 13 文件 +4546 行 → **13 文件 +5235 行**；同时 PR **结构发生变化——由单提交改为 3 个提交**（`commits: 3`）。base 仍为上游 `ef1a16aa`（等于我们的 pin），上游 master 依旧未前进，所以**依旧只换补丁、pin 不动**。

**三个提交**：
1. `c4cdc749` `feat: 新增高效自定义滤镜组件 rewrite（改写工具）`——13 文件 +4546 行，内容与上次接入的版本一致。
2. `6e31439b` `fix: 根据前缀结果递进查询`——`rewrite_pack.cc` +132/−18，查询改为按已匹配前缀的结果递进，减少无效查找。
3. `8530499b` `fix: 优化长句转换性能`——8 文件 +666/−91，`rewrite_pack.cc` 改动最大（+404 行），`rewriter.cc/.h`、`rewrite_compiler.cc`、`rewrite_store.cc/.h`、`rewrite_preset.cc` 同步调整。**这是本次的主体**。

`kFormatVersion` 仍为 1、`kStageRecordSize` 仍 72、`ValidatePhraseTrieBounds` 与 `RewritePresetCatalog` 均保留。

**冲突与合并**：新补丁在纯上游基线干净，仍与 `librime-perf-deploy-compile-independent-dictionaries-in-para.patch` 冲突——`deployment_tasks.cc` 的 `BeginWorkspace` 与 `FinalizeWorkspace` **两个 hunk 被拒，位置与上两轮完全一致**（已是第三次同点冲突）。合并直接沿用 0.5.13/0.5.14 的五步：① `BeginWorkspace` + `active_schema_ids` 声明；② `process_schema` 内收集 schema id；③ schema 配置加载成功后调 `compile_rewriter`（置于 `translator/dictionary` 分支**之前**，覆盖有/无词典两条路径）；④ `#endif` 后插入 `FinalizeWorkspace` 包裹；⑤ `MaybeCreateDirectory` 早退时补 `AbortWorkspace` 防事务泄漏。

**做了什么**：① 拉取新补丁（注意 `pull/1232.patch` 直链易被限流，用 `Accept: application/vnd.github.v3.patch` 走 API）；② 手工合并后生成 prebuilder 风格补丁（13 文件 / 5411 行，+800/−111）覆盖 `patches/librime-pr1232-rewrite-filter.patch`；③ 提交 `c2ef978` 推送 prebuilder（**pin 未改**），CI [run 35947584015](https://github.com/SandyYuR/prebuilder/actions/runs/35947584015) **success**；④ 主仓库 prebuilt 指针 `8ad0193f` → **`750f6e4f`**（arm64 `librime.a` 20,321,046 → 20,467,248 字节）；⑤ **新建提交（只动 prebuilt gitlink）**，只本地提交、未推送。该提交与前述各次引擎跟进提交**一并合并为 `a560aabe`**（见上一节末注）。

**验证**：① 6 个定制补丁在 pin 上依序应用全部零退出；② 新补丁在「`ef1a16aa` + 6 补丁」树上干净应用；③ 12 个纯新增/替换文件与 PR head（`8530499b`）真实文件 `cmp` 逐字节一致（`gears_module.cc` 亦一致）；④ 回环重放与逐字节比对树零差异；⑤ 新 `.a` 含 `RewriteStore`(136)/`RewritePresetCatalog`(21)/`RewritePack`(88)/`ValidatePhraseTrieBounds` 符号；⑥ 既有定制 API 计数与上一版一致（均 4 处）；⑦ 四 ABI 归档齐全。

**三条教训**：
1. **PR 可以从「单提交」变成「多提交」**——这次 `commits` 由 1 变 3。看 `git log`/`commits` 字段和补丁里的 `^From ` 行数（本次 3 行、`Subject: [PATCH n/3]`），别以为一个 PR 永远只有一个提交。
2. **同一冲突点可能连续三轮重复出现**，此时合并步骤可以固化成模板（本次五步与前两轮逐字相同）。把「改哪个文件、插在哪、为什么」写进交接文档后，后续同类更新基本是机械套用。
3. **`raw.githubusercontent.com` 并发下载可能静默失败**。本次 12 个文件批量下载时 `gears_module.cc` 缺失，导致逐字节比对误报 `DIFFER`；重新单独下载后 `cmp` 为零差异。**逐字节比对报差异时，先确认目标文件真的下全了**（`ls -l` 看大小），再怀疑内容。

### 0.5.16 2026-09-28 第十二次实战（用户报障 → 定位上游缺陷 → 新增第 8 个补丁 + 适配层两处配套）

**起因**：用户报告「从其它输入法切回、拉起键盘立即打字时，**最前面几个字母没进编码区，而是直接上屏**」。

**根因（两层，必须分开记）**：

1. **误判层——每次冷启动都做全量部署**。`DetectModifications`（`src/rime/lever/deployment_tasks.cc`）用**时间戳**判断配置是否改动：取 `user_data_dir`/`shared_data_dir` **目录自身 mtime** 与直下非 `user.yaml` 的 `*.yaml` mtime 的最大值，与 `user.yaml` 的 `var/last_build_time`（秒级 `time(NULL)`）比较。而同一目录里大量**与配置无关**的活动都会推新该 mtime：userdb 落盘、`CleanupTrash` 把 `*.bin`/`*.kct.old` 搬进 `trash/`、**sync 期间瞬时创建再删除的 `.temp.userdb/`**、`installation.yaml` 被重写。更关键的是 `var/last_build_time` 在 `WorkspaceUpdate` **中途**就写，其后仍要跑 `user_dict_upgrade` 与 `cleanup_trash`（`rime_api_impl.h` 的调度顺序）——**跨秒即自我触发**。Android 上冷启动本就每次进部署，于是「每次启动都全量部署」。
2. **漏键层——部署期按键漏给编辑器**。部署期（用户日志实测约 **1.3s**：键盘在 +230ms 已可见、+1540ms 才认键）内 `RimeState::keyEvent` 在 `is_maintenance_mode()` 处**裸 `return`**（既不处理也不 `filterAndAccept()`，2017 年 `747429e` 起就如此），未接受的键经 `AndroidFrontend::keyEvent` 的 `!accepted()` 回吐给 Java 层，最终由 `FcitxInputMethodService` 的兜底分支 `commitText(unicode)` **直接上屏**。⚠️ **这不是丢键，是把键送到了错误的地方**——所以表现为「字母出现在文字里」而不是「字母没反应」。

**修复（librime 侧，新补丁 `librime-detect-modifications-fingerprint.patch`）**：改为对「两数据目录直下的 `*.yaml`（排除 `user.yaml`）」的 `名称:大小:mtime tick` 求 **FNV-1a 指纹**，存入 `var/workspace_fingerprint`（带 `fp1-` 前缀，避免纯数字摘要被 YAML 解析成数值）；**目录自身 mtime 与其它非配置文件不再参与判定**；记录点移到 `WorkspaceUpdate` 末尾以消除时序竞态；`var/last_build_time` 保留写以兼容旧配置与外部工具。

**修复（适配层侧，两个提交）**：

- `583e908` **部署期按键改为吞掉**：`src/rimestate.cpp` 的 `RimeState::keyEvent` 在 `is_maintenance_mode()` 与 `!session` 两处原先是裸 `return`，现改为 `event.filterAndAccept()`；**按下与抬起都吞掉**（避免编辑器收到无对应 DOWN 的 UP）。
- `9bf94e6` **部署提示改为键盘内显示，覆盖自动部署**：原先 "under maintenance" 提示受 `allowNotificationUntil_` 门控，而冷启动走 `rimeStart`→`start_maintenance`、**不经过 `deploy()`**，所以自动部署时该提示**根本不显示**；即便放开门控也只会弹系统 Toast（`androidnotification.cpp:71-86` 的 `showTip` 把 `timeout`/`appIcon` 全部丢弃后直调 `showToast`），它不随键盘生命周期、可能弹在别的 app 上，且每次冷启动都会弹。现改为在 `notify()` 的 `deploy/start` 分支调用新增的 `setDeployStatusMessage(_("Deploying..."))`，把文字写进 `InputPanel` 的 `overlayMessage`——适配层 `updateInputPanel()` 在 `ip.empty()` 时正把它当 aux-up 发出（`androidfrontend.cpp:66-74`），因此显示在键盘的预编辑/候选区，`success`/`failure` 时清除。新增 msgid `Deploying...` 已同步 `po/fcitx5-rime.pot` 与 `po/zh_CN.po`、`po/zh_TW.po`（其余语言回落英文）。

**两条链路正交，别混**：指纹补丁只**消除误判**；真实改配置或首次安装后仍会部署，那时才走适配层这条。因此**两个修复都不能省**。

**三个易错点（部署提示部分）**：① `deployStatusMessage_` 必须存成成员并在 `activate()` 里补挂——冷启动部署常**早于第一个 IC 获焦**，那时 `foreachFocused` 遍历不到任何东西，不补挂则永远看不到提示；② `RimeEngine::reset()` 里的 `inputPanel().reset()` 会连带清掉它，必须重新写回；③ 清除时只删自己写的那条（比对 `overlayMessage()` 内容），否则会误删输入法切换提示（**共用同一字段**）。

**做了什么**：① 补丁在「`ef1a16aa` + 7 补丁」树上生成（**必须最后应用**，见教训 3）；② prebuilder 提交 `4121ce6`（pin 不变，仍 `ef1a16aa`）；③ 主仓库 prebuilt 指针 `750f6e4f` → **`1126616c`**（主仓库提交 `b37267e5`）；④ 适配层两个提交推送到 `SandyYuR/fcitx5-rime`，主仓库 gitlink `dcd6162` → **`9bf94e6`**（主仓库提交 `babd5c13`）。**本次两条链路均已推送**（与前几轮「只本地提交」不同）。

**验证**：① NDK clang 语法校验（需 `-std=c++20`）0 error；② 新归档中 8 个定制补丁全部存在，本次新增的 `workspace_fingerprint` / `fp1-` 字样**仅在 `1126616c` 产物中出现**（`750f6e4f` 中为 0）；③ `:app:assembleFxDebug` 出包成功，产物 `librime.so` 内可检索到新字面量 `Deploying...`，`zh_CN` 的 `fcitx5-rime.mo` 含 `正在部署`；④ `:app:testFxDebugUnitTest` **31 文件 / 225 例全绿**；⑤ `9bf94e6` 是 `5692166` 的改写版——原提交有一行 81 字符超出适配层 `.clang-format` 的 `ColumnLimit: 80`，导致 Check clang-format 失败、Build and test 因 needs 依赖被跳过，故把格式修正折进该提交（tree 与原文一致）。

**三条教训**：

1. **「没反应」与「上屏了」是两条不同的链路，先分清再定位**。用户描述的是「字母没进编码区」，很容易误判成丢键，实际是维护模式把键**回吐**给输入框当普通字符。**判据是输入框里有没有出现这些字母**。
2. **时间戳判修改必然被无关活动污染，而「写在中途」比「被污染」更致命**。即使目录 mtime 完全可控，`last_build_time` 在中途写入、之后还有收尾步骤这一点就足以跨秒自触发。**凡是用时间戳做「是否需要重建」判据，先问：写标记点之后还有没有会改输入的操作。**
3. **新补丁若依赖「前面全部补丁的产物状态」，它在配方里的位置就是契约的一部分**。指纹补丁是照 `WorkspaceUpdate` 在其它补丁（尤其 para deploy 的内联改写）之后的形态写的，因此**必须排在 `LibRime.hs` 最后**——插入到中间会静默错位。这一条已同步进 `AGENTS.md` 第 7.2 节。

---

### 0.5.17 2026-10-02 适配层过滤非法音节 tab（pin 不变，第十三次跟进）

**起因**：用户报「万象方案下展开音节 tab 面板显示 � 乱码」。这与 0.6.9 那次 JNI 崩溃**同源但方向相反**——那次是进程被 CheckJNI `abort()`，这次是同一批脏数据被 `sanitizeUtf8` 净化成 `U+FFFD` 后**正常显示出来**（兜底生效的可见证据）。

**根因**：librime 的 `get_input_tabs` 把 `TableTranslator` 码表里的多字节 code（如带声调拼音 `ā = C4 81`）**按字节而不是按字符**切前缀当 label，可能切断多字节序列、留下孤立续字节（日志里的 `0x81`）。这种 label 到 JNI 侧只能被净化成 `U+FFFD`，回传 `select_tab` 也是无意义的坏约束。

**改法**（适配层 `SandyYuR/fcitx5-rime` 的 `107502d`，提交 `fix(候选): 过滤非法 UTF-8 的音节 tab，避免展开面板显示 � 乱码`）：`src/rimecandidate.cpp` 的 `RimeCandidateList::tabActions()` 新增 `#include <fcitx-utils/utf8.h>`，循环内 `if (labels[i] == nullptr || !utf8::validate(std::string(labels[i]))) { continue; }`；**并把第二个循环的边界从原始返回条数 `i < count` 改为过滤后的条数 `i < tabLabels_.size()`**——否则 `action.setId(static_cast<int>(i))` 与过滤后的下标错位，`triggerTabAction(id) → selectTab(id, ...)` 会**选错 tab**。

**做了什么**：主仓库 gitlink `plugin/rime/src/main/cpp/fcitx5-rime` `9bf94e6` → **`107502d`**（主仓库提交 `4b560f0d chore(引擎): fcitx5-rime 指针更新至 107502d，过滤非法音节 tab 乱码`）；prebuilder pin 与 prebuilt 指针**均未动**（纯适配层改动，不必重跑 librime 配方）。

**分工（不要混）**：适配层 `utf8::validate` 过滤是**治本于显示**；JNI 侧 `sanitizeUtf8` 是**防崩溃兜底**，两条独立防线都不能删。彻底治本仍需上游把 `get_input_tabs` 改成按字符边界切。`app/licenses/libraries/fcitx5-rime.json` 的 `artifactVersion` 同步为 `5.1.17`。

---

## 0.6 设置界面与符号面板重构（2026-09-27 ~ 10-03）

> 本节是细节记录（**为什么这么做、踩了什么坑**）。`AGENTS.md` 第 12 节只保留其中的**不变式**（不许做什么），两者互补：改这块代码前先读本节。

### 0.6.1 符号 / 表情 / 颜文字面板换成 Foxy 风格（2026-09-27）

**旧实现已整体删除**：`PickerData`（硬编码 3 组数据）+ `PickerTabsUi` / `PickerPagesAdapter` / `PickerPageUi` / `PickerPaginationUi` 四个文件。改为读取 Foxy 输入法（`com.fxliang.foxy`，Foxy 反编译产物）的 catalog JSON，并按 Foxy 的布局模型重建面板。

- **数据**：`app/src/main/assets/bundled/symbols/{symbols,emoji,kaomoji}.json`（60KB / 28KB / 28KB），解析器 `input/picker/SymbolCatalog.kt`。格式 `{multiLine, groups:[{names:{zh,zh-Hant,en}, symbols:[...]}]}`——**`names` 是多语言字典**，组名要走 locale 回退链：完整标签 → `zh-TW/HK/MO` 特判 `zh-Hant` → 语言主标签 → 首个值。
- **UI**：`input/picker/SymbolPanelUi.kt`（左侧竖向分组栏 + 右侧网格 + 「最近」分组）。**颜文字必须单列**（`columns = 1`）——Foxy 的 `multiLine=true` 就是这个语义，条目最长 63 字符，多列会被 `AutoScaleTextView` 压到看不清。
- **面板独占整个键盘区域，底部不再有键盘行**（2026-09-28 修正）。早期版本底部保留了一行 `ABC , <切换> 空格 . 回车`，那是照抄旧 `PickerLayout` 结构的错误：面板按键本身是「点一下即时上屏」，空格/回车/逗号毫无意义，还白吃掉约 1/4 键盘高度。**键盘级操作只剩左栏（宽 84dp，同 Foxy 的 `b(84)`）底部两枚小键**：`⌨` 返回文字键盘、`⌫` 退格（长按连删），字形与 Foxy 的 `ly.j` / `ly.k` 一一对应。
- ⚠️ **不要在面板里加第三枚「切换面板」键**（09-28 用户明确要求删除）。面板互切**和 Foxy 一样属于布局/宏的配置**，不是面板内建的：Foxy 的 `ly` 只有 `⌨`/`⌫` 两枚，互切由布局文件的按键（`k5("emoji")` 等）驱动。本项目对应两个入口：① LayoutSwitchKey 的 `Emoji` / `Kaomoji` 目标；② 宏的「切层」动作指向这三个面板。两条通道已接通，因此面板内不需要也不允许硬编码切换键。
- **工具栏保持主键盘原样，只把最左侧按钮换成返回箭头**（09-28 修正）。这一点**不能**靠 `ExtendedInputWindow` 实现——那会让整个工具栏切到 `KawaiiBarStateMachine.State.Title` 标题栏形态（按钮整片换掉）。正解：`PickerWindow` 继承 `InputWindow.SimpleInputWindow`，再由 `KawaiiBarComponent.onWindowAttached` 调 `IdleUi.setBackToKeyboardMode(window is PickerWindow)`，只替换最左图标；点击行为在主键盘工具栏的 `menuButton` 监听里按「当前窗口是不是 PickerWindow」判定。**判据来自 Foxy 源码**：`ly` 只是 `cv` 内部一个 overlay（`cv.java:150-156` 只 `setVisibility`），**根本不是窗口**，所以 Foxy 全程不碰工具栏；它的 `⌨` 走 `a(new k5("default"))` 回默认键盘、`⌫` 走 `a(new wz(j30.BACKSPACE, 0))` 发退格。
- 列数/尺寸只在 `PickerWindowPreset.kt` 调：符号 6 列/18f、表情 6 列/22f、颜文字 1 列/15f。
- **有意偏离 Foxy 的两点**：① 用 `RecyclerView` 复用视图，而不是像 Foxy 的 `ly.c()` 那样一次性把整份 catalog（符号 4773 条）全 `new` 成 View；② 保留长按弹出（emoji 肤色），Foxy 面板无此交互。
- ⚠️ **「最近使用」的存储键是历史值，不要跟着枚举改名**：`SymbolCatalogType.recentKey` = `Symbol` / `Emoji` / `Emoticon`。它既是 SharedPreferences 的键（`picker_recently_used`），也是旧版 `filesDir/recently_used/` 迁移文件的文件名（`RecentlyUsed.migrate`），改了会**静默丢**存量用户的最近使用记录。颜文字那项仍写 `Emoticon` 就是 09-28 改名的遗留。
- ⚠️ **颜文字面板的 Key 已由 `Emoticon` 改名为 `Kaomoji`**（09-28，用户要求）。旧名**已经写进用户数据**（布局按键的 `subLabel`、宏的 `target`、`lastPickerType` 偏好），所以：`PickerWindow.Key.ofName` 必须继续接受 `Emoticon`（见 `LEGACY_KAOMOJI`），`CommonKeyActionListener` 读 `lastPickerType` 也改用 `ofName` 而非 `valueOf`，否则存量用户偏好会抛异常并静默退回 Emoji；布局编辑器 `SWITCH_TARGET_OPTIONS` 与宏的切层候选/白名单都要放行旧名（网页编辑器同理，见 `pickerLegacyLayerTargets`）；网页编辑器「按键对话框」还需在 `subLabel` 不在预设项内时把它追加成一个选项，否则 `<select>` 回落成「默认」，用户一保存就悄悄改了配置（app 侧 `createSwitchTargetSpinner` 本就有该兜底）。
- `PickerWindow.Key` 枚举（`Symbol`/`Emoji`/`Kaomoji`）是**窗口标识**，被 `AppPrefs.lastSymbolLayout`/`lastPickerType`、`PickerSwitchAction`、`NumberKeyboard`、`KeyboardWindow` 等多处引用，不能顺手删。
- `bundled/symbols` 已加进 `app/build.gradle.kts` 的 `generateDataDescriptor` **excludes**：这些 catalog 只随 APK 分发、由 `SymbolCatalogs` 直接从 assets 读，**不参与 dataDir 资源同步**，也不走 `BundledPresets`（因此与 `assetSizes` 那套版本标记无关）。
- `InputView` 的背景模糊策略已改为「任何 `PickerWindow` 都整层模糊」：新面板含大片非按键区域（分组栏、行间空白），再按按键区域裁剪会留下未模糊的缺口。

### 0.6.2 符号 catalog 支持用户自定义（2026-09-27，对应 Foxy 的「符号布局」设置）

三类数据可各自换成用户 JSON：

- 路径 `config/symbol_catalogs/<symbols|emoji|kaomoji>/<名>.json`，由 `UserConfigFiles.symbolCatalogDir` / `symbolCatalogFile` / `listSymbolCatalogFiles` 解析；选择存于 `AppPrefs.symbols.symbolCatalog{Symbols,Emoji,Kaomoji}`，默认 `UserConfigFiles.SYMBOL_CATALOG_BUILTIN`（= Foxy 的 `__builtin__`）。
- **回退语义是「自定义不可用就用内置」，不是「变空」**：文件不存在 / 解析异常 / 解析后零有效分组，都在 `SymbolCatalogs.load` 里回退内置（对齐 Foxy `lv0.c`）。改动这里必须保持该行为，否则用户换个坏文件就整个面板空白。
- `SymbolCatalogs.get` 的缓存键是 **"种类|所选文件"**，且 `SymbolPanelUi.rebuild()` 每次重新取 catalog——因为用户可能在设置页切了数据源。切换时必须 `SymbolCatalogs.invalidate()`，否则面板仍显示旧内容。
- 设置页入口在 `SymbolSettingsFragment`（原文件只有一行继承，已扩展为带三项 catalog 选择 + SAF 导入）。**导入先解析校验再落盘**，避免把坏文件装进去。
- **`UserConfigFiles.isValidCatalogName` 是安全边界**：文件名会拼进路径，必须拒绝路径分隔符 / 控制字符 / 超长名（对齐 Foxy `mv0.a` 的 `.json` 后缀 + length > 5）。已有单测 `SymbolCatalogNameTest` 覆盖穿越用例。
- 列目录时**不能**用 `canonicalPath != absolutePath` 判软链接：Android 上 `getExternalFilesDir` 返回路径本身常含软链接（`/storage/emulated/0`），那样会把所有正常文件判成软链接。改用「规范化后的文件路径必须以规范化目录路径开头」。
- 自定义 catalog **不参与 `BundledPresets`/`assetSizes`**，也与 dataDir 同步无关：它们纯属用户数据。
- **术语**：这套 catalog 的中文一律用「自定义」（简）/「自訂」（繁），**不要写「自备」**——`Custom` 在本项目其它字符串（`custom_key_sound`、`icon_theme_custom_resource` 等）都译作「自定义」，混用会造成同一概念两个词。
- **三项标题 2026-09-29 按用户要求统一加前缀**：`symbol_catalog_{symbols,emoji,kaomoji}_title` → 「自定义符号数据 / 自定义表情数据 / 自定义颜文字数据」（繁中「自訂…」，英文 `Custom symbol data` / `Custom emoji data` / `Custom kaomoji data`）。**只有 `values`、`values-zh-rCN`、`values-zh-rTW` 三处有这组字符串**，其余 5 个 locale 本就没有，别去找。用户指南 §5.9 的路径同步为「表情和符号 → 自定义符号数据 / 自定义表情数据 / 自定义颜文字数据」。

### 0.6.3 设置首页重构为「两层 + 6 入口 + 搜索框」（2026-09-28）

首页结构由 `MainFragment` 决定：第 0 项是常驻搜索框，下面是「日常调整」（输入与候选 / 键盘 / 外观 / 便捷功能）与「按需进入」（数据与备份 / 高级）两个分类。

1. **分组页 = 一张规格表 + 一个通用 Fragment**：`settings/group/SettingsGroupSpecs.kt` 定义分组与条目，`SettingsGroupFragment` 渲染。要加/改分组只动规格表，**别新建 Fragment**——分组内容全是「带图标的跳转项」，没有各自独有的状态。**「键盘」不在规格表里**：它直接复用既有的 `VirtualKeyboard` 页（那边已是子分组列表），再套一层分组页会产生两个重复入口。
2. **搜索索引是静态目录，必须手工维护**：`settings/search/SettingsSearchIndex.kt` 是一张 `SettingsSearchEntry` 列表，**新增/改名/移动设置项时必须同步登记**，否则该项搜不到。不用反射遍历 PreferenceScreen 的原因：设置项分散在三种载体（ManagedPreference、引擎动态配置树、独立 Activity），且静态目录才能给条目补同义词/英文别名。
3. **匹配规则抽在 `SettingsSearchQuery`（纯函数，有单测）**：按空白切词、**每个词都要命中**（AND 语义）、`Locale.ROOT` 小写转换、空查询返回 `false`。`SettingsSearchQueryTest` 钉住这四条——尤其「空查询不匹配一切」，否则调用方会在没搜东西时渲染出整份目录。
4. **搜索时用 `isVisible` 逐条切换，不增删 Preference**：增删会触发整份列表重新绑定，正在输入的 EditText 有丢焦点、软键盘收起的风险。`MainFragment.collectNormalPreferences` 递归收集常规条目（含分类与子项），不依赖「隐藏 PreferenceGroup 会自动隐藏子项」这一未在文档中承诺的行为。
5. **`SettingsSearchPreference` 的绑定阶段**：TextWatcher 必须**复用同一实例且注册前先 remove**（直接 `doAfterTextChanged` 会每绑定一次加一个 listener，刷新几轮后一次按键触发多次搜索）；文本只在**与 `query` 不一致时**才补写，且注册 watcher 必须在那次补写**之前**。`onBindViewHolder` 中**不得**在绑定阶段 `requestFocus`，否则一进设置页就弹键盘——首焦点由布局层 `focusable`/`focusableInTouchMode`/`descendantFocusability="beforeDescendants"` 挡掉（`preference_settings_search.xml`）。
6. **`KeyboardModesFragment` 的互斥逻辑必须用 `onPreferenceChange`，不能用 `onPreferenceTreeClick`**：`SwitchPreference.onClick` 先调 `super.onClick()`（派发 tree click）再切换取值，在 tree click 里读到的是**旧值**，判断必然出错。浮动与单手键盘互斥（开其一自动关另一个），「靠右显示」只在单手开启时可用。
7. **`AdvancedSettingsFragment` 已瘦身**：用户数据导入/导出/浏览目录移到 `DataBackupFragment`（纯动作、无 managed preference，故直接继承 `PaddingPreferenceFragment`）。键盘分组有 `GROUP_CANDIDATE`(5) 与 `GROUP_VOICE`(6)：候选栏样式与语音从 `GROUP_TOOLBAR` 拆出，`KEYS_BY_GROUP` 与 `groupTitleRes` 必须同步。
8. **「未实现类型」不再渲染给用户**：`PreferenceScreenFactory.general` 原先为上游有、本应用未实现的配置类型渲染一行「⛔ 未实现类型 'xxx'」。那是开发者诊断信息，现改为**不加入页面 + `Timber.w` 记一条**（开发者仍可在「实时日志」看到）。实现上 `when` 分支现在返回 `Preference?`，由局部变量 `built` 承接后再 `built?.apply { ... }`。

**IA 调整（09-29）**：「中州韵设置」与「全局选项」放「输入与候选」，「附加组件」与「隐藏快捷键配置」放「高级」，**「引擎配置」这一层已取消**（路径少一次点击）；「虚拟键盘 → 键盘工具」→「键盘自定义」→ 再改「键盘布局自定义」（末次改名因为该组内容全是布局定义类工具，见 `KeyboardGroupFragment.GROUP_EDITORS`）。「弹出字符设定」由「外观」移入该组。四条重复路径已消除（按键行为、候选栏样式、中州韵设置、附加组件各只剩一个入口）。

### 0.6.4 搜索跳转的滚动定位与高亮（2026-09-28 ~ 09-29）

点搜索结果先 `MainViewModel.requestPreferenceScroll(key)` 存键，再导航；目标页在自己的 `onViewCreated` 里调 `scrollToPendingPreference(viewModel)` 消费。

- **键由 `SettingsSearchIndex.PREFERENCE_KEYS` 按标题统一映射**（集中一张表，改键名只动一处），条目也可自带 `preferenceKey` 覆盖。**只登记确实会单独成行的项**：分组/页面级条目与纯动作项（如「导出用户数据」「实时日志」——它们的 Preference 压根没有 key）登记了也找不到目标行，只会让键挂着不被消费。
- **不要自己写 ViewHolder 轮询等高**：`PreferenceFragmentCompat.scrollToPreference` 已公开提供，内部会在目标项尚未进入 adapter 时注册 `ScrollToPreferenceObserver` 等待，且在列表未就绪时把动作存进 `mSelectPreferenceRunnable` 于 `bindPreferences()` 后执行。调用点必须是 **`onViewCreated`（super 之后）**，不能用 `onCreatePreferences`——那时 `listView` 还可能为空。
- **键只在目标页命中时才消费**（`consumePendingPreferenceScrollKey` 校验键相同才清空）。这样「同一标题存在于多页、A 页没有 B 页才有」的场景不会把键提前清掉。另外目标项若 `isVisible == false`（如「首选语音输入」要等语音按钮打开）也算处理完毕，要主动消费——否则下次进本页会莫名滚动。
- **Activity 型目标不记键**（图标主题、字体设定等不是 PreferenceFragment，没人消费）。**`ThemeFragment` 例外需特殊处理**：它的「配置」tab 由 `FragmentStateAdapter` 惰性创建，目标项若属于该 tab，得先在 `onResume` 里 `maybeOpenConfigTab()` 切过去——判断依据是「键是否登记在 `ThemePrefs.managedPreferences`」，不硬编码键名。
- 已接入的页：`ManagedPreferenceFragment` 的子类（含 `KeyboardGroupFragment`、`ThemeSettingsFragment`、候选窗口/剪贴板/符号/高级）自动生效；`KeyboardModesFragment`、`DeveloperFragment`、`ThemeFragment`、`KeyboardSettingsFragment`、`FcitxPreferenceFragment`（引擎页）手工接入。

**⚠️ 高亮试过又移除（09-29）—— 这段教训值得留着，避免后人重走**。曾用 `foreground` 叠半透明 `colorPrimary` 淡出高亮目标行，**真机上用户始终看不见**。两轮修正都无效：① 补上「等 adapter 位置 + 等 ViewHolder」的两级轮询、延长时长、改用实色起步、加 `Timber.w` 诊断；② 换 `postOnAnimation` 上限 180 帧。最终按要求删除，现在只做滚动定位。若将来重做，先看清这两条 API 事实：

- `scrollToPreference` 内部是 `RecyclerView.scrollToPosition`（**瞬时定位，不是平滑滚动**，别按平滑推断）；
- 调用点 `onViewCreated` 时列表尚未 measure，`getPreferenceAdapterPosition` 会返 `NO_POSITION`——官方 API 对这种情况会挂 `ScrollToPreferenceObserver` 兜住，**所以滚动正常、只有附加的高亮会静默丢失**。想加高亮必须自己把这两级等待都做进轮询。

另有一条通用教训：`ColorDrawable.setAlpha` 是**直接改写颜色的 alpha 通道**（不是与原 alpha 相乘），行为在不同系统版本上有差异；要稳定就用每帧写入成品颜色。**更重要的教训是流程**：本项目当时无真机（`adb devices` 为空），UI 观感类改动**无法自我验证**，连续两轮靠猜推进都失败——这类改动应当先向用户说明「无法验证」，而不是反复盲调参数。

### 0.6.5 空格键的划动动作（2026-09-28）

为空格键加「布局编辑器里可配划动动作」时定的规则：**开关（`AppPrefs.keyboard.spaceSwipeMoveCursor`）优先级更高**——开启时光标手势完全接管、配置的划动动作**不生效**；关闭且配了动作才由动作接管；两者都不成立时不参与手势（`swipeEnabled = false`，否则手指在空格上移动会取消长按，语音的「按住说话」会半途中断）。

**两个坑**：

1. 通用 `Behavior.Swipe` 绑定会把 `swipeThresholdX` 改写成 `disabledSwipeThreshold`（`BaseKeyboard.applyBehaviorPopupBindings`），直接废掉光标模式的横向移动。因此**空格键的手势只能在 `createKeyView` 里自建、不能走 Swipe 分支**。
2. 手势监听器必须**每次事件重读开关**，因为缓存行（`reusableRowsCache`）会带着旧模式沿用——开关切换时同时要刷新 `swipeEnabled`、两轴阈值**和** `gestureBaselines`，漏了基线就会在下次重绑时恢复旧值。

优先级规则抽在 `SpaceSwipeMode.kt` 的 `resolveSpaceSwipeEnabled`，由 `SpaceSwipeModeTest` 钉住；划动动作与标签的 JSON 往返由 `LayoutJsonUtilsTest` 覆盖（漏任一侧都会让用户配好的动作在保存/重载后静默消失）。仅 `SpaceKey` 支持划动动作，`MiniSpaceKey` 保持纯光标语义。

### 0.6.6 候选预设 / 颜色项本地化 / 导出按钮（2026-09-28 第二轮）

- **候选预设**（`behavior/CandidatesPresets.kt`）：紧凑/标准/宽松三组，一次写好 7 个纯数值外观参数。**是动作不是模式**——不做「当前属于哪个预设」判定，那要给每个参数引入「谁写的」这种持久化难题。范围须与 `AppPrefs.Candidates` 的 min/max 一致，由 `CandidatesPresetsTest` 钉住（越界会被 SeekBar 夹到边界，表现为「预设没生效」且无日志）。刷新界面用**重建 PreferenceScreen**：`notifyChanged()` 是 `protected` 调不到，而 `SeekBarPreference.value = x` 会再写一次偏好。
- **颜色项本地化 + 分组折叠**：22 个（`CustomThemeActivity`）+ 21 个 Monet 项原先只有单个英文 `name`，**同时充当显示文案与 map 键**，所以没法翻译。已拆成 `titleRes` + `id`；并按语义分 5 组、默认只展开前 2 组（`groupRes` + `EXPANDED_GROUP_COUNT`，**同组项必须相邻**，否则组头会重复出现；条目要 add 进组容器、否则折叠只藏住组头）。**前提**：这些名字**不参与持久化**（主题 JSON 存 `waterRippleColor` 这类属性名），故改名不影响已存主题。
- **导出补可见入口**（主题卡片左下角，原先只能长按触发）；Monet 编辑门禁不再静默 `return`，改为明确提示。
- **`hide_key_config` 终态在「高级」本页**（`AppPrefs.advanced` 的 `switch`，由 `switchNoUi()` 改为普通 `switch()`）。它控制「全局选项」里 Fcitx 快捷键族的显隐。**改位置必须同步 `SettingsSearchIndex` 的跳转目标**——搬过两次都漏改，表现是搜索仍指向旧页。
- **索引归属要跟着分组走**：条目里的面包屑（`P_*`）与 `route` 是硬编码的，分组一改必须同批更新，否则搜索结果指向已不存在的页面。

### 0.6.7 主题「外观模式」试做又整体回退（2026-09-28 ~ 09-29）

**做过的**：把 `follow_system_dark_mode` 的界面从「跟随系统夜间模式」开关改写为「外观模式（跟随系统 / 手动指定）」，模式名写在开关标题上、摘要随取值切换，并把 `normal_mode_theme` 从内部偏好提升为带 UI 的 `themePreference(...)`（跟随系统开启时置灰）。

**用户随后明确要求「改回以前的样子和逻辑」，已全部撤销**：`ThemePrefs`、`ThemeSettingsFragment`、`ThemeManager.setNormalModeTheme` 与 HEAD 逐字一致；`normal_mode_theme` 重新是 `ManagedThemePreference` 内部偏好（**不**在 `dayNightModePrefNames` 里）；新增的 6 条文案（`follow_system_day_night_theme_summary_manual`、`switch_to_manual_mode`、`light_mode_theme_summary`、`dark_mode_theme_summary`、`normal_mode_theme`、`normal_mode_theme_summary`）在 8 种语言中删净；`theme_message_follow_system_day_night_mode_enabled` 还原旧文案；`SettingsSearchIndex` 里 `normal_mode_theme` 条目删除、`follow_system_day_night_theme` 别名去掉「外观」相关词；用户指南 §3.4 改回「手动选择主题」。

**⚠️ 回退时才发现的一条因果（很隐蔽，务必记住）**：把 `normal_mode_theme.key` 加进 `dayNightModePrefNames` 会**打断主题列表的点击换主题**。`setNormalModeTheme()` 先把 `_activeTheme` 直接写成目标主题，随后 `setValue()` 触发偏好回调；该回调若发现键在集合里，就走「重算生效主题」分支 `activeTheme = evaluateActiveTheme()`——此时值已相同，setter 提前 return、**不再 `fireChange()`**，于是列表勾选与键盘样式都不刷新。**用户现象就是「点主题项换不了主题」**。它不在集合里时走 `else` 分支照常 `fireChange()`，所以原本是好的。

> 另有两条曾被写错的结论，已就地更正：① 方案文档曾称「外观页预览不实时、调圆角等于盲调」——**是错的**。改 `ThemePrefs` 任一项会让 `ThemeManager` 通知 `ThemeFragment` 走 `refreshStyle()` 重建；`BaseKeyboard.currentRowsSignature()` 也已纳入 `keyBorder`/`keyRadius`/`keyHorizontalMargin`/`keyRippleEffect`。**教训：转述前自己走一遍链路。** ② 搜索高亮曾以为「平滑滚动导致等不到」，实际是 `scrollToPosition` 瞬时定位（见 0.6.4）。

### 0.6.8 搜索索引的静默失败，由 `SettingsSearchIndexCoverageTest` 守住

人工审计发现 **13 项从未登记**（分体键盘横屏布局、候选方向/最小宽度/高亮圆角、物理键盘水平候选栏、远端与媒体剪贴板上限、剪贴板提示超时、边框描边、Gboard 风格操作键、文本编辑按钮圆角、剪贴板条目圆角、键盘高度基准）；用户搜不到且**无任何提示**。三个断言各守一类：

- **覆盖**：带 UI 的偏好项都已登记。**必须只扫 `buildEntries` 的条目块**——`PREFERENCE_KEYS` 里也有同名资源，只扫全文件的话，**删掉条目后名字仍留在映射表里、测试照样通过**（初版就是绿的，靠反向验证才发现）。收紧后立刻抓出第 14 个遗漏 `haptic_on_repeat`。
- **孤儿键**：映射表里有、却无条目引用的键（实例 `verbose_log`；它的标题由 `DeveloperFragment` 用 `setTitle()` 设置，覆盖断言扫不到，两者互补）。
- **键名拼错**：映射值必须是真实偏好键。写错一个字母时 `findPreference` 返回 null、`PreferenceScrollHelper` 会**保留键**，表现为「跳对页面但不滚动」且无日志。
- 另断言**条目数与映射条数下限**，否则正则失效时差集断言恒真、测试空转变绿。解析用严格字面量匹配，**只漏匹配、不多匹配**；确有不希望被搜到的项时加进 `INTENTIONALLY_UNSEARCHABLE` 并写明理由，别为变绿放宽断言。当前该集合为空。**条目数已因 10-07~10-10 一批新设置项过期，不再写死数字**（本条旧记「61 项全部已登记」已不适用）：新增设置项包括 `horizontal_candidate_overflow_scroll`、`show_candidate_index_badge`、`candidate_index_badge_position`、`move_main_text_for_alt_label`、`preedit_radius`、`toolbar_radius`（共 6 条已登记进 `SettingsSearchIndex`）；**以实测现取**——`grep -c "SettingsSearchEntry(" …/SettingsSearchIndex.kt` 数 `buildEntries` 里的条目（`SettingsSearchIndexCoverageTest` 会守住覆盖/孤儿/拼写/下限四类断言）。
- ⚠️ **反向验证的陷阱（本轮真踩到）**：`.\gradlew.bat ... | Select-String ... | Select-Object -First N` 会提前关闭管道、**杀掉 gradle**，而上一轮的 `test-results/*.xml` 还在，于是读到**旧的成功结果**，看起来像「注入了错误却没失败」。正解：输出重定向到文件（`*> out.txt`），看 `$LASTEXITCODE`，并核对 XML 的 `LastWriteTime`。**别用会截断的管道跑测试——它会伪造成功。**

---

### 0.6.9 宏键显示文本优先级与 JNI UTF-8 净化（2026-09-29）

#### 宏键 displayText 优先于 label

起因是用户反馈「宏键设成大写显示文本，键盘上却显示小写，跟字母键一样，不够自定义」。

**根因**：MacroKey 的外观就是 `KeyDef.Appearance.AltText`，而 `TextKeyboard.updateAlphabetKeys()` 对所有 `AltText` 键统一做 Shift/Caps 大小写改写——用户填的 `A` 在 Shift 关闭时被强制改成 `a`，与 AlphabetKey 无从区分。

**改法**：`KeyDef.Appearance.AltText` 新增 `keepDisplayTextCase: Boolean`（默认 false，故 AlphabetKey 等既有键不受影响）；`MacroKey` 新增 `displayText` 参数，非空即 `keepDisplayTextCase = true`。`updateAlphabetKeys()` 里该标记走**独立分支**：

```kotlin
if (keyDef.keepDisplayTextCase) {
    it.mainText.text = if (displayIsSingleLetter && (displayUppercase || keepLettersUppercase))
        displayText.uppercase() else displayText
    return@forEach
}
```

⚠️ **只应用「大写」那一支，不应用「小写」那一支**——这正是修复点。别为了「严格原样」把大写支也删掉，否则用户要的「按 Shift 字母仍变大写」的兼容行为会丢。

**解析侧的坑**：`LayoutJsonUtils.createKeyDef` 里 `label` 与 `displayText` 必须**分别保留**。原先 `resolveDisplayText` 的默认值直接传 `baseLabel`，于是「displayText 未命中当前子模式」与「用户根本没填」无法区分，会误把 label 当显式显示文本。现在传空串默认值、再 `.takeIf { it.isNotEmpty() }`，把「未命中」归为「未设置」，由 `MacroKey` 回落 label。

**同步改的三处**（漏任一处就会出现「编辑器所见 ≠ 键盘所打」）：保存侧 `keyDefToJson` 写出 `displayText`（不写空串）；布局列表预览 `KeyboardLayoutAdapter.buildKeyLabel` 与 `TextKeyboardLayoutEditorActivity.buildKeyLabelForEditor` 都改为显示文本优先（displayText 为子模式 Map 时取 `default` 项）。`buildPopup` 也改用「实际显示的文本」。

回归用例 5 个在 `LayoutJsonUtilsTest`（优先级、回落、往返保存、未设置不写空串、子模式未命中回落）。

#### JNI 边界净化非法 UTF-8（崩溃修复）

**现象**：打字途中输入法整个消失，`/data/app/.../libnative-lib.so`、线程 `fcitx-main`、signal 6，**Java 侧拿不到任何堆栈**。

**墓碑**：

```
JNI DETECTED ERROR IN APPLICATION: input is not valid Modified UTF-8: illegal start byte 0x81
    input: '<0x81>'
    in call to NewStringUTF
    from void org.fcitx.fcitx5.android.core.Fcitx.sendKeySymToFcitx(int,int,int,boolean,int)
```

**根因**：Rime 候选栏 tab 的 label 里带了**孤立的续字节 0x81**。CheckJNI 校验非法 Modified UTF-8 时直接 `abort()` 整个进程。用 NDK `llvm-addr2line` 还原（`libnative-lib.so` / `libandroidfrontend.so` 的 Debug 产物）：

```
#10 NewStringUTF                                          jni.h:840
#11 JString::JString(JNIEnv*, const char*)                jni-utils.h:64
#13 fcitxCandidateActionToObject  ← act.text              object-conversion.h:169
#14 inputPanelCallback 的 tabs 循环                       native-lib.cpp:609
#19 AndroidFrontend::updateInputPanel                     androidfrontend.cpp:404
#36 fcitx::InputContext::keyEvent
#43 Java_..._sendKeySymToFcitx
```

注意这是**上游数据脏**（Rime `get_input_tabs()` 的返回值），不是我们的代码写错。但桥接层不该因此让整个进程 abort。

**改法**：`jni-utils.h` 新增 `sanitizeUtf8()`——逐字节校验，非法序列替换为 `U+FFFD`，合法序列（含 4 字节 emoji）原样通过；覆盖过长编码、UTF-16 代理对半区、超出 `U+10FFFF`。`JString` 构造函数与 `getFcitxTranslation` 两个出口都改走它，并打一条 `Fcitx5Jni` WARN 便于定位脏数据来源。

⚠️ **`JString(const char*)` 是唯一入口**，全仓库只有它和 `getFcitxTranslation` 两处 `NewStringUTF`；新增任何 `NewStringUTF` 调用都必须过净化，否则同类崩溃会从新路径复发。

**验证方式**（真机无 adb 时也可做）：把**真实的** `jni-utils.h` 配上最小 `jni.h` 桩（`NewStringUTF` 桩模拟 CheckJNI 严格校验、非法即记录「这里真机会 abort」），用 NDK clang `--target=aarch64-linux-android28` 交叉编译成可执行文件推到设备实跑——26 项用例全过，含崩溃现场那个 0x81、各种截断序列与 emoji 通行性。

#### 内置 26 键字母布局去掉冗余显示文本

按用户要求，把「26 键字母布局」里**当作字母使用**的宏键的 `displayText` 字段删掉（22 个层、68 处）。判据与注意事项：

- **判据是「该层字母覆盖满 26 个」**，不是「布局名字里有 26」。用户明确叮嘱 18 键之类非 26 键布局不要动——大同布局的 `wanxiang_zrm_18keys`（18 键，4 个宏字母键）与万象九键v2 的 `符号` 层（借字母当符号，3 个）都被自动排除，共 7 个键保持原样。
- **删除前必须逐项确认 `displayText == label`**，否则删掉会改变键面显示。「是字母」判定要求 `tap` 恰好是**单个** `{"type":"tap"}` 步骤、**单个** fcitx 键、且该键名是单个 ASCII 字母。
- 改法是**按字节范围剔除成员**（含前导逗号），不是 `JSON.parse` + `stringify` 重排——后者会把整份手写格式化的文件重排，diff 无法审阅。
- 验证四道：① 仍能解析；② 文件恰好缩短被删字段的字节数、行尾（CRLF/LF）不变；③ 语义深比对差异叶子**全部**位于 `displayText`；④ 重扫 22 个 26 键层，字母宏键携带 `displayText` 的为 0。
- ⚠️ 这类内置资源改动**不会自动下发**给存量用户：`BundledPresets.assetSizes` 未登记这几个文件，已存在的 `config/` 布局不会被覆盖。要生效需卸载重装或清数据（会连用户自定义布局一起清，先备份）。

#### Nightly 正文去掉固定品牌头

`.github/workflows/ci.yml` 的 release notes 生成段原先硬编码三行固定文案（`## 靓企鹅·中州韵（Rime-only） <tag>` 与两行 Rime-only 注意事项），按用户要求删除，正文只留 `Commit` / `Built at` / `### Changes`。发布页**标题**仍由 softprops 步骤的 `name:` 字段提供，不受影响——改这里别顺手把 `name:` 也删了。

---

### 0.6.10 键盘形态与视图树（2026-10-02 ~ 10-03）

> 本节对应主仓库 10-02/10-03 的六个提交。**这批提交本身零测试**，`AGENTS.md` §12 只留不变式。

#### 按键挤成一行：视图树不能在布局回调里重建（`9862e7b8`）

**现象**：浮动/分体切换与拖动宽度时，整套按键挤成一行。

**根因（两条同源，都是 reload 时机）**：`BaseKeyboard.reloadLayout()` 是 `removeAllViews()` + 重新 `addView` 整套行，在错误时机调用会让新行**漏测量** → 高度 0 → 所有行塌成一行。

1. **横屏进入调整模式**：`InputView.toggleAdjustingMode()` 强制退出浮动后**缺少** `toggleFloatingMode()` 已有的延迟重建。横屏下「浮动窄宽度」与「停靠全宽」常落在 `SplitKeyboardStateManager.shouldUseSplitKeyboard(viewWidthPx)` 阈值的**同一侧**，`BaseKeyboard.onSizeChanged` 里的 split 重判（`shouldSplit != lastSplitLandscapeState`）不成立 → 不重建 → 行仍按浮动窄宽度烘焙，自愈不了。
2. **浮动态来回拖动宽度**：原 `onSizeChanged` 在**布局过程中**同步调 `reloadLayout()`，在当前 measure/layout 途中改视图树，刚加回来的行这一趟没被测量 → 0 高度。

**改法**：`InputView` 侧复用 `floatingToggleRefreshPending` + `keyboardView.doOnNextLayout { ... }`；`BaseKeyboard` 新增 `private var pendingSplitReload = false`，`onSizeChanged` 里只置标志并 `post` 出去，**在 `post` 块内按最新 `width` 重判**（不用闭包里的过期决定）并加 `isAttachedToWindow` 守卫。历史提交 `1a883904` 是同一课。

#### 「横屏时自动使用浮动键盘」（`34fd116f`）

**偏好键**：`AppPrefs.internal` 的 `val autoFloatingLandscape = bool("auto_floating_landscape", false)`，**默认 false**，注册在 `internal` 而非 `keyboard`。

**核心不变式**：自动浮动**绝不写回**用户手动形态偏好。`toggleFloatingMode(persist: Boolean = true)` 新增 `persist` 参数，三处落盘全部加守卫（`autoFloatingActive` / `oneHandModeEnabledPref` / `floatingModeEnabledPref`）。理由：自动切换若落盘，**一次横屏就把用户手动选择的形态永久改写成浮动**，转回竖屏再也恢复不了停靠。另加 `private var autoFloatingActive` 区分自动/手动。

**两条方向变化路径都要覆盖，缺一不可**：① 重建路径 `restoreFloatingAndOneHandState()` 末尾——仅在 `autoFloatingLandscapePref.getValue() && isLandscapeOrientation && !isFloating && !isOneHanded` 时直接置 `isFloating = true; autoFloatingActive = true`，**不走 `toggleFloatingMode`**（init 期视图未成型，也不应写回偏好）；② 复用路径 `applyAutoFloatingForOrientation()` + `onConfigurationChanged()`——进横屏 `toggleFloatingMode(persist = false)`，回竖屏仅当 `autoFloatingActive && isFloating` 时还原。不介入条件：`isAdjustingMode`，以及用户已手动开浮动/单手。**漏一条路径的表现是「转屏只在一半场景生效」。**

#### 分体键盘按子布局单独配置 + 空白占位键（`06364f60`，34 files / +3419-297）

**布局变体**：新增 `enum class LayoutVariant(val jsonKey: String?)`（只有 `Docked(null)` 与 `Split("split")`）、`LayoutVariantResolver`（`variantEntry` 只在 `rowsArrayOf(entry) != null` 时算找到，**畸形变体跳过继续回退，不让键盘退化成空布局**）、`SplitRowWidths`（`MIN_GAP = 0.02f`、恒等式「承载键合计恒为 `1 - gap`」、占位键计 0 宽度）。

**键协议（持久化契约，权威在 `LayoutJsonUtils`）**：`const val VARIANT_SUBMODE_PREFIX = "__variant__:"`；`data class EntryKey(layoutName, subLayout, variant)` + `parseEntryKey` / `entryKeyOf` 唯一权威，**别处不得自己 substring**；`splitLayoutNameFrom` 按**第一个**冒号切。四条迁移要求：格式向后兼容、不要改前缀字符串（**改字符串等于静默丢配置**）、认不出的形态键退化 Docked 并原样写回、所有枚举子布局处必须过 `subLayoutLabelsOf` + `isReservedSubModeLabel`。

**新增偏好**：只有 `splitKeyboardAlignHalves`（`split_keyboard_align_halves`，**默认 true = 旧行为**）。

**占位键**：`class PlaceholderKey(displayText = "", altText = "", percentWidth = 0.1f, transparentBackground = true, ...)`，**`behaviors = emptySet()` + `popup = null`** —— `applyBehaviorPopupBindings` 因此置 `isEnabled=false`/`isClickable=false`，触摸根本不进入该视图。

#### 分体奇数字母行复制中间键（`c56b2d99`）

`asdfghjkl`(9)、`zxcvbnm`(7) 键数为奇数无法对半分体，中间那枚只落一侧。做法：把**几何中间**的字符键复制一份，两半各一枚，**不写死 g/v**。判定链：`splitRowsForLayout(source)` 按 source **实例身份**记忆（无需复制时**直接返回 `source` 本身**，保持 `reusableRowsCache` 身份命中）→ `duplicateMiddleKeyForSplit(row)`（跳过 `row.size < 3` / 偶数 / 含空格键；`isSplitDuplicatableKey` 只允许 `AlphabetKey` 与**纯字符输出**的 `MacroKey`——`steps.all { it is MacroStep.Text || it is MacroStep.Tap }`）→ 开关进 `currentRowsSignature`（`|splitDup:`）。偏好 `split_keyboard_duplicate_middle` **默认 true**。⚠️ 后来由 `06364f60` 补上 `if (row.any { it.splitAfter }) return row`：**手动分界与自动复制互斥**。

#### 工具栏大小百分比（`d84e20ee`）

新增 `app/src/main/java/org/fcitx/fcitx5/android/input/bar/ToolbarMetrics.kt`（object，全纯函数可 JVM 单测）。常量：`BASE_HEIGHT_DP = 40`、`BASE_ICON_DP = 24`、`BASE_ICON_PADDING_DP = 10`、`BUTTON_FOOTPRINT_DP = 44`、`MIN_BUTTON_WIDTH_DP = 40`、`MIN_ICON_GAP_DP = 2`、`BASE_LABEL_SIZE = 16f`、`MIN_PERCENT = 80`、`MAX_PERCENT = 200`、`DEFAULT_PERCENT = 100`。数值表：100% → 图标 24dp / 留白 10dp / 合计 44dp（**与原实现逐像素一致**）；200% → 图标 40dp / 留白 2dp / 合计 44dp。

**初版踩的坑**：200% 时让格子跟着变宽，中间行可见按钮从 8 个掉到 3 个（退回横向滚动）。故定下「**横向足迹恒定、放大只吃图标留白**」。

偏好键 `toolbarHeightPercent`（`toolbar_height_percent`，**默认 100、范围 80..200、步进 10**）。改高度必须走 `InputView.applyToolbarHeight()` 重设 `keyboardView` 的 ConstraintLayout 约束 LayoutParams（**光 `requestLayout` 不够**）。

**候选项字号刻意独立**：字号只来自「字体设定」的 `cand_font`；格子尺寸是 `HorizontalCandidateComponent` 的 `ITEM_MIN_WIDTH_DP = 40` / `ITEM_HORIZONTAL_PADDING_DP = 10`（⚠️ 10-04 `18619aef` 起 10→4 且语义变为「格子↔高亮」的四边外间距，见下下条「候选栏高亮三层结构」），且必须与 `predictRowOverflow` 的宽度预测**共用同一函数**（一旦分叉，候选行会在「挤一行」与「换行/滚动」之间抖动）。`ToolbarMetricsTest` 里刻意**没有**「候选项字号随工具栏缩放」的用例。

#### 候选栏高亮圆角与边距（`2d63c248`）

两个**主题偏好**（不是 `AppPrefs.candidates`）：`ThemePrefs.candidateBarHighlightRadius`（`candidate_bar_highlight_radius`，默认 4，0–48 dp，此前写死 `cornerRadius = 8f` 的 px 值）与 `candidateBarHighlightInset`（`candidate_bar_highlight_inset`，默认 0，0–24 dp）。只作用于**工具栏候选条**的 `CandidateItemUi.activeBackground`；独立候选窗口由 `AppPrefs.candidates.candidateHighlightRadius`（默认 2，0–48 dp）控制。`CandidateItemUi` **故意不注册偏好监听**——候选项每次编码更新都会重建，构造时读值即可，**不要把值缓存成静态量**。刷新语义是「必须重建候选项」。

⚠️ **10-04 `18619aef` 已重构为三层结构**，本条中的「默认 0」与「按格子宽铺满」不再是现状，见下条。

#### 候选栏高亮三层结构（`18619aef`）

候选项由两层（`root` → `text`）改为三层：`root`(格子) → `content`(高亮层) → `text`，高亮背景从根视图移到紧包文字的 content 上（做法对齐 boomker/fcitx5-android 的 `e2cd625`）。修复两个观感问题：文字距高亮边框过近且随候选长短变化（旧实现间距 = 格子内边距 − InsetDrawable 内缩量，短候选只剩 ~2dp）；高亮上下贴边。要点：

- **格子边缘 ↔ 高亮固定 4dp（上下左右同值）**：`ITEM_HORIZONTAL_PADDING_DP` 10→4，语义从「item 水平内边距」变为 root 的四边外间距；
- **高亮边框 ↔ 文字的左右间距 = `candidateBarHighlightInset`，默认 0→8（0–24dp）**，与候选长短、`flexGrow` 拉伸无关；
- 间距由 `CandidateItemUi.configureHighlightSpacing(outerPadding, highlightPadding)` 注入，**只由水平候选栏适配器调用**（九宫格展开页不调用 → 高亮铺满整格，与历史行为一致）；两个数值必须与 `HorizontalCandidateComponent.predictRowOverflow` 共用同一函数，否则候选行在「挤一行」与「滚动」间抖动；
- `CandidateItemUi` 仍不注册偏好监听（构造时读值，不要缓存成静态量）。

#### 纵向辅助选择栏按行高百分比分配（`584bddaa`）

原缺陷：纵向辅助栏**无条件**按 item 数量整除容器高度，布局编辑器给的行高百分比被完全忽略。改法：新增与 `auxBarScrollableItemViews` **一一对应**的 `auxBarScrollableItemRowHeights`（`null` = 无自定义行高）；`resolveHeightPercents` 归一化到总和 100（未配置项平分剩余），**最后一项用剩余高度补齐**，被压成 0 的项**显式写高度 0**（不退回 `WRAP_CONTENT`）。横向（Top/Bottom）不在范围内。`resolveRowHeightPercents(rows)` 被改写为对它的复用（主键盘行为不变，是抽取而非改语义）。⚠️ 本条当时写「**tabs 整列都是 null、继续按数量均分**」——**2026-10-08 `098ec596` 后已不成立**：纵向 tabs 改走 `RecyclerView`、超过六项可滚动（见 §0.6.17），`rowHeightPercent` 分配只作用于自定义辅助按键。

#### 退格手势上滑分两义（`2501375f`）

`checkBackspaceClearPopupHit` 命中后按 `composing` 分流：合成中发 `KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_Escape))`（**只清编码区、不动已上屏正文**），非合成才 `executeClearAll()`。提示条文本对应为**字面量 `"Esc"`** 与 `R.string.backspace_swipe_to_clear`——**字面量不经过 strings 资源**，故该提交没有新增字符串。

---

### 0.6.11 Foxy 风格文本编辑布局（2026-09-30，`fbed2500`）

新增 `app/src/main/java/org/fcitx/fcitx5/android/input/editing/` 下 `FoxyTextEditingUi.kt` / `TextEditingCursorPadKey.kt` / `TextEditingKeySpec.kt` / `TextEditingLayout.kt` / `TextEditingStyle.kt`（连同 `TextEditingWindow.kt` / `TextEditingButton.kt` / `TextEditingUi.kt` 改造，19 files / +1164-104）。

**偏好键**：`text_editing_style`（默认 `TextEditingStyle.Default`，另一值 `FoxySwipe`）、`text_editing_cursor_step_dp`（18，4..80）、`text_editing_cursor_long_press_delay`（300，100..1200，步 10）、四个提示文案键 `text_editing_cursor_prompt_move` / `_long_press` / `_selecting` / `_release_selection`（**默认空串 = 用内置文案，且刻意不登记进搜索索引**）。`text_editor` 布局的底部行高 46dp。

**不变式（写进 `AGENTS.md` §12 的那条）**：`text_editor` 布局是文本编辑底部栏与键盘布局编辑器的**唯一契约源**，底部栏必须走 `TextEditingLayoutLoader.load()` / `fromRows` 解析同一份布局，编辑器预览与真机键盘**共用同一 `KeyDef` 解析器**——所以「编辑器里看着对、真机不对」只可能是解析分支分叉，不是渲染问题。三条改错就会坏：① 配置布局存在时 `updateSelection` **不得**接管剪切键的 enabled/alpha；② `createConfiguredBottomBar` 每键 weight 必须 `percentWidth.coerceAtLeast(0.5f)`（**weight 为 0 会让键宽塌成 0**）；③ `executeTextEditingAction` 是**白名单**（`else -> Unit`），新增 `KeyAction` 子类若不登记会被静默丢弃。

---

### 0.6.12 空格键长按重复与按键类型下拉框（2026-09-29，`0ae64681` / `4be7dc55`）

#### 空格键「长按重复输入空格」（`0ae64681`）

`SpaceLongPressBehavior` 新增 `Repeat(R.string.space_behavior_repeat)`，**插在 `None` 之后、`Enumerate` 之前**，最终顺序为 `None, Repeat, Enumerate, ToggleActivate, ShowPicker, VoiceInput, VoiceInputHold`。

**绝不用 `KeyDef.Behavior.Repeat` 实现。** `KeyDef` 只在布局加载时构造一次，行缓存签名里不含该维度；把它写进 behaviors 会让「改设置不重建布局」，开关静默失效。正解是 `BaseKeyboard.applySpaceRepeatBinding(view)`，发与空格 `Behavior.Press` **同一个 keysym**（`KeyAction.SymAction(KeySym(FcitxKeyMapping.FcitxKey_space))`）。

三个调用点必须齐全：① `applyBehaviorPopupBindings` **之后**的 `refreshSpaceRepeatBinding`（该函数开头会清 `repeatEnabled` / `onRepeatListener`，顺序反了等于白设）；② 行复用路径要显式再调一次；③ compose 重绑时必须 `removeSpaceKeyEntry(oldView)`，否则旧视图无界泄漏。`MiniSpaceKey` 一并生效。

⚠️ `CustomGestureView.resetState()` **必须无条件重置**——按 `*Enabled` 条件重置会留下永久空转的 `repeatJob`、`repeatStarted` 卡在 true、`shouldPerformClick` 恒 false，按键彻底不响应。

**偏好存的是枚举常量名**（`ManagedPreferenceCategory.enumList` 的 `decode(raw) = enumValueOf(raw)`），不是序号，所以中间插入新值不会让已有偏好失效。

由 `SpaceKeyDefTest`（`spaceKeyBehavesAsTapAndLongPressOnly` / `spaceKeyNeverRegistersSwipeBehavior` / `spaceKeyNeverRegistersRepeatBehavior` / `swipeLabelSwitchesToAltTextAppearance`）钉住。

#### 按键类型下拉框的「说明文案」按位置对齐（`4be7dc55`）

`KeyboardEditorUiBuilder.KEY_TYPES` 12 项顺序逐字为 `"AlphabetKey","CapsKey","LayoutSwitchKey","CommaKey","LanguageKey","SpaceKey","SymbolKey","ReturnKey","BackspaceKey","NumPadKey","MiniSpaceKey","MacroKey"`——**这些英文名会写进 JSON，必须保持原样**；`KEY_TYPE_LABELS` 按**位置**一一对应，**读取选中值永远取 `KEY_TYPES[position]`**。两者错位时**不会改选中值、也不报错**，只是显示成另一种中文说明，外观上完全看不出来。`describeKeyType(index)` 对越界只回退英文名，不抛异常。新增 `R.string.text_keyboard_layout_key_type_format`（en `%1$s (%2$s)`、中文 `%1$s（%2$s）`），**只有 values / zh-rCN / zh-rTW 三份**。与 `subModeSpinnerSelectionMap`（0.6.x 子模式下拉框）同一纪律。

同提交还给 `symbol_catalog_{symbols,emoji,kaomoji}_title` 加了「自定义」前缀（zh-rCN「自定义符号数据 / 自定义表情数据 / 自定义颜文字数据」）。

---

### 0.6.13 方向性划动标签不再受「标点位置」影响（2026-10-10，`4eae6539`）

**现象（用户报障）**：布局里给个别按键配了下滑动作（如 `{"type":"MacroKey","swipeDownLabel":"撤销",...}`），主题设置把「标点位置」设为**上方居中**后，这些键的副标签跑到键的上方，划动手势也**只认上滑**——用户按下滑不再触发自己配置的动作。

**根因（两处独立的分叉，都必须修）**：

1. **渲染**：`KeyView.resolveLayoutMode()` 的方向分支里，**单侧**方向标签的摆放位置是拿 `punctuationPositionForKey()`（主题「标点位置」，非字母键也返回 `Bottom`）算出来的，`shouldTriggerAltBySwipe()` 又照该摆放位置反推触发方向。于是主题一改，下滑标签被画到上方、动作也跟着翻成上滑。**双标签**（上下都配）不受影响，所以现象只出在「个别按键」。
2. **数据**：旧版单槽划动（`swipe` / `altLabel` / `swipeLabel`）没有方向字段，方向由 `punctuationPosition` + `swipeSymbolDirection` 联合推断（`LayoutDataManager.legacyToDownForMigration` 与 `KeyEditorActivity.legacySwipeToDown`）。改了主题后，迁移/编辑会把同一个键解读成另一个方向。

**修法**：

- `KeyView` 的方向分支**彻底不读「标点位置」**：新枚举 `DirectionalUpTopCenter` / `DirectionalUpTopRight` / `DirectionalDownBottom` / `DirectionalDownBottomRight` / `DirectionalTopBottom` / `DirectionalCorners`——上滑标签恒在上（居中，键高不够时退右上角）、下滑标签恒在下（居中，键高不够时**退右下角**，不再被翻到上方），双标签在空间不足时改为**各自退到一个角**（此前只保留右上角，会把下滑标签整条吞掉）。`shouldTriggerAltBySwipe` 只按标签方向判定。`KeyView` 里的 `ImageAltTextKeyView`（图标键）同步镜像。
- **方向判定统一到一条规则**：`LayoutJsonUtils.legacySwipeTargetsUp(hasUpMacro, hasDownMacro, hasUpLabel, hasDownLabel)` —— **只有「同侧且唯一」的槽位才算上滑**，其余（默认、两侧都有、只有下侧）都按下滑。读（`createKeyDef`）、迁移（`migrateDirectionalSwipeFields`）、编辑（`KeyEditorActivity.legacySwipeToDown`）三处共用，`punctuationPosition` 与 `swipeSymbolDirection` **都不再参与方向解析**。
- **无方向线索的旧 `swipe` 不迁移**：既没有标签、也没有方向字段时保持旧槽位，运行时继续由「符号划动方向（符号隐藏时）」决定（默认 `Auto` = 上下都响应）。钉死到某一侧会悄悄丢掉另一个方向——这批键是「符号隐藏时划动唤出符号」的原用途。
- **编辑器清掉 MacroKey 的 `altLabel`**：`appendDirectionalSwipeLabels` 原先只 `remove("swipeLabel")`，MacroKey 的旧字段会与新方向标签并存于同一份 JSON。

**留下的不变式**：**「标点位置」只作用于字母键的标点副标签**。凡是带方向性划动标签/动作的键型（`CapsKey` / `LayoutSwitchKey` / `SymbolKey` / `ReturnKey` / `BackspaceKey` / `MacroKey` 的带标签形态），标签位置与触发方向都只由布局配置决定。改这块前先读 `DirectionalSwipeLabelDefTest` 与 `LayoutJsonUtilsTest` 钉住的不变式：标签落槽、标签不凭空生成动作、显式上下宏走 `overrideDefaults`、旧单槽方向规则。

测试：`LayoutJsonUtilsTest`（legacy 规则 / 迁移跟随同侧 / 解析落槽 / 无线索保留旧槽）、新增 `DirectionalSwipeLabelDefTest`；全量 `:app:testFxDebugUnitTest`（388 项）与 `:app:assembleFxDebug` 通过。**真机回归未做**（容器内无设备）：需要人工核对「下滑键在三种标点位置下标签都在下方且下滑可触发」。

---

### 0.6.14 候选栏：长候选横向滚动 + 候选项序号角标（2026-10-07 / 10-09，`751480cd` / `635d3aa2`）

两个**候选栏样式**开关（`AppPrefs.candidates`，路由「键盘 → 候选栏」，搜索索引已登记）。两条互不重叠，合并为一条。

#### 长候选横向滚动（`751480cd`）

**偏好**：`horizontal_candidate_overflow_scroll`「长候选词横向滚动」（`R.string.horizontal_candidate_overflow_scroll`，默认 **true**，带摘要文案 `…_summary`「保留候选词完整宽度，通过横向滚动查看超出部分」）。**默认开启 = 新行为**；关闭后恢复旧的等比缩放（`AutoScaleTextView.Mode.Proportional`）。

**实现**（`CandidateItemUi.kt` / `HorizontalCandidateComponent.kt` / `HorizontalCandidateViewAdapter.kt` / `InputView.kt` 热更新）：候选文字改为**自然宽度**（`AutoScaleTextView.Mode.None`），套一层 `CandidateOverflowViewport`（`FrameLayout`）作为视口：视口宽度仍约束在候选格子内，文字超出部分通过子视图 `translationX` 平移查看。新增手势类 `ScrollableCandidateGestureView` 支持横向拖拽（`scrollByDistance`），候选更新或视口尺寸变化时 `followTextUpdate()` 自动滚到尾部（看到候选的结尾）。`InputView.kt` 加运行时热更新入口（改开关即时生效，无需重启键盘）。**判据/边界**：`hasOverflow = overflowEnabled && maxScroll > 0f`，`scrollByDistance` 把 `translationX` 夹在 `[-maxScroll, 0]`；`onMeasure` 在 overflow 模式下以 `UNSPECIFIED` 量出整段文字宽、再 `resolveSize` 把视口收回槽位。

**测试**：新增 **androidTest** `CandidateOverflowTest.kt`（`androidTest` 源集，非 JVM 单测）——断言文字保持自然宽、视口内展示尾部（`translationX == -(text.width - viewport.width)`）。

#### 候选项序号角标开关（`635d3aa2`）

**偏好**：`show_candidate_index_badge`「显示候选项序号角标」（`R.string.show_candidate_index_badge`，默认 **false**）+ `candidate_index_badge_position`「候选项序号角标位置」（`R.string.candidate_index_badge_position`，默认 `TopLeft`，**仅在角标开启时可改**——`enableUiOn = { showCandidateIndexBadge.getValue() }`）。

**四位置枚举**（`input/candidates/CandidateIndexBadgePosition.kt`）：`TopLeft` / `TopRight` / `BottomRight` / `BottomLeft`（`ManagedPreferenceEnum`，文案复用 `top_left`/`top_right`/`bottom_right`/`bottom_left`）。

**实现**（`CandidateItemUi.kt`）：`indexBadge` 是叠在格子根视图上的小 `TextView`（`textSize = 10f`，颜色 `theme.candidateLabelColor`，随高亮态 `refreshIndexBadgeColor()` 刷新）；`setIndexBadge(number, position)` 幂等（同 number+position 直接 return），按位置映射 `FrameLayout.LayoutParams.gravity`（`TOP/BOTTOM` × `START/END`），水平留 `INDEX_BADGE_HORIZONTAL_MARGIN_DP = 2`。`HorizontalCandidateComponent` / `HorizontalCandidateViewAdapter` 负责把「候选在列表中的序号（从 1 起）」与位置下发到每个 `CandidateItemUi`。

**测试**：新增 **androidTest** `CandidateIndexBadgeTest.kt`（同样在 `androidTest` 源集，非 JVM 单测）——覆盖四角内边距、旧 margin 复位等。

**真机回归未做**（两项都只有 JVM/androidTest 与出包；横向拖拽手感、角标在长/短候选与四角下的可见性需装机确认）。设置搜索索引已同步（`SettingsSearchIndex` 加 3 条：`horizontal_candidate_overflow_scroll`、`show_candidate_index_badge`、`candidate_index_badge_position`）。

---

### 0.6.15 键盘：同一按键分别配置上/下划动宏（2026-10-07 / 10-08，`d156052b` + `4f0d6fb8`）

> **本节只补「字段 / 编辑器 / 迁移」这三面缺失；方向判定规则（`legacySwipeTargetsUp`、标点位置只影响字母键、无线索旧 `swipe` 保持旧槽）已在 §0.6.13，不重复。**

**起因**：原先一个按键只有一个划动事件，无法让 Enter 等键的上划与下划分别执行不同的快捷键宏。

**新增字段（`KeyDefPreset.kt` / `KeyDef.kt` / `LayoutJsonUtils.kt`）**：`swipeUp` / `swipeDown`（独立上/下划动宏动作）与 `swipeUpLabel` / `swipeDownLabel`（独立上/下滑标签文本）；`KeyDef.Appearance.Text` 新增 `altText1`（第二副标签），`Appearance` 新增 `altText1`，`Appearance` 新增标记 `directionalSwipeLabels`（`altText`/`altText1` 是否为独立物理上/下标签）。`Behavior.Swipe` 扩容为 `action` / `downAction` / `upMacro` / `downMacro` / `legacyMacro` / `overrideDefaults`（`overrideDefaults = 显式上/下宏存在时置位`，使功能键方向宏**不受全局划动方向偏好阻断**）。

**「自动」划动方向（`SwipeSymbolDirection.kt`）**：枚举加 `Auto`（`R.string.swipe_auto`「自动」），`checkY` 在 `Auto` 时任意非零位移都成立；**符号划动方向默认值改为 `Auto`**，使升级后未明确设方向的 MacroKey 上下划都能触发。**保留 `SpaceKey` 原有专用划动语义**（空格键不走这套，见 §0.6.5）。

**编辑器（`KeyEditorActivity.kt`）**：把单一「划动事件」拆成 **「上划事件」/「下划事件」**（`R.string.text_keyboard_layout_macro_swipe_up_event` / `…_swipe_down_event`），可分别配置如 `Ctrl+Shift+Enter` 与 `Shift+Enter`；标签编辑器加 **「上划标签」/「下划标签」**（`…_swipe_up_label` / `…_swipe_down_label`）。非宏键（回车/退格/大小写/符号/布局切换）也接入同一套上/下划编辑器。

**迁移（`migrateDirectionalSwipeFields`，`LayoutJsonUtils.kt`，在 `LayoutDataManager` 两处调用）**：旧单槽 `swipe`/`swipeLabel`/`altLabel` 按 `legacySwipeTargetsUp` 判定目标方向后迁入 `swipeUp`/`swipeDown` 与对应标签，并**移除旧字段**（`KeyEditorActivity.appendDirectionalSwipeLabels` 同时清 MacroKey 的旧 `altLabel`，避免新旧字段并存）。`4f0d6fb8` 是这条迁移与 MacroKey 上下划标签的修复：**保留已存在的 `swipeUp`/`swipeDown` 宏动作不改绑**、给 MacroKey 补独立上/下滑标签、按符号划动方向迁移旧 `altLabel` 并删旧字段、符号位置为「隐藏」时隐藏上下滑标签。

**测试**：`LayoutJsonUtilsTest`（方向选择 / Shortcut 往返 / 旧字段迁移）、新增 `SwipeSymbolDirectionTest`；`:app:testFxDebugUnitTest` 通过、`build-debug.ps1` 完整 arm64-v8a Debug 构建通过、`git diff --check` 干净。**真机回归未做**：`d156052b` 正文明确「真机上划/下划 Enter 输入行为尚未验证」；需装机核对「同一键上划/下划分别触发不同宏、旧布局升级后方向正确」。

---

### 0.6.16 键盘标签：双标签高度回退布局统一（2026-10-08，`ed38a31b`）

**新增主题设置**（`ThemePrefs`，路由 Theme，搜索索引已登记）：`move_main_text_for_alt_label`「副标签存在时移动主字符」（`R.string.move_main_text_for_alt_label`，**默认 true**）。

**改了什么**（`KeyView.kt`，+467/−131）：统一 MacroKey 与普通按键的单副标签布局；**保留双标签上下显示**，仅在「主字符 + 一个副标签」都无法容纳时才回退到右上角，并新增**圆角安全边距** `cornerLabelHorizontalSafeInset = max(dp(3), min((radius*0.5f).roundToInt(), dp(12)))` / `cornerLabelTopSafeInset = max(dp(1), min((radius*0.2f).roundToInt(), dp(4)))`（旧的固定 3dp/1dp 在大圆角键上会把字形压到圆角上）。`move_main_text_for_alt_label` 控制主字符是否随副标签位移（关时不位移、主字符居中）。

**测试**：该提交**本身零测试**（只有 strings / ThemePrefs / KeyView / 搜索索引）。**真机回归未做**（纯布局观感改动，需装机核对不同键高/圆角下双标签与回退形态）。

---

### 0.6.17 音节选择栏：超过六项改为可滚动（2026-10-08，`098ec596`）

**这是本文多处「音节 tab 全部展示、按数量均分、不可滑动」描述的失效点**（见本节末「过时描述修正」）。

**新增 `input/keyboard/AuxBarTabLayout.kt`**（纯函数，可 JVM 单测）：

- `VERTICAL_AUX_BAR_TAB_MAX_FULL_ITEMS = 6`、`VERTICAL_AUX_BAR_TAB_VISIBLE_ROWS = 6.5f`；
- `resolveVerticalAuxBarTabItemHeights(containerHeight, itemCount)`：**≤ 6 项**沿用旧的整数均分（余数摊到前几项，总和 = 容器高）；**> 6 项**每项高度 = `floor(containerHeight / 6.5)`（下限 1px），即视口只显示约 **6.5 行**，第 7 项露出一半，列表可自然滚动。

**`BaseKeyboard.kt` 改法**：纵向（Left/Right）辅助栏的 **tabs 改走 `RecyclerView`**（`LinearLayoutManager.VERTICAL`，`isNestedScrollingEnabled = true`，挂 `auxBarScrollableRvLayoutListener` 在尺寸变化时重算行高 `relayoutVerticalAuxBarTabs()`）；自定义辅助按键仍走 `LinearLayout` + `rowHeightPercent`（§0.6.10 的 `584bddaa` 逻辑不变），两者**共享同一 scrollable 区域、按可见性切换**，pinned 区恒在底部。`AuxBarAdapter.updateActions` 返回值改为 `Boolean`（列表变化时让调用方 `scrollToPosition(0)`），新增 `setVerticalItemHeights`。

**测试**：新增 JVM 单测 `AuxBarTabLayoutTest.kt`（无效输入返回空、≤6 项均分且总和=容器高、>6 项返回 6.5 行高）。**真机回归未做**（需装机核对滚动流畅度与第 7 项的半露效果）。

**过时描述修正（三处已就地改）**：

- 文首/第 0 节（原「音节 tab 仍然全部展示、按数量均分高度（无最小行高、不可滑动）」）——已改；
- §0.6.10 `584bddaa` 条目（原「tabs 整列都是 null、继续按数量均分」）——已改；
- §7 第 6 条（原「音节选择器 tabs 仍整列均分、逐像素不变」）——已改。

---

### 0.6.18 弹出键盘：多字符候选改用 CommitAction 提交（2026-10-08，`b94106da` + `598897b7`）

**起因**：长按弹出键盘里的候选若是**多字符**字符串（emoji、组合字符等），原走 `KeyAction.FcitxKeyAction`——但 `FcitxKeyAction` 只取 `act[0]` 算 scancode，多字符不是合法 fcitx 按键名，fcitx 侧解析失败**静默丢弃**，点候选没反应。

**两步落地**：

1. `b94106da`：`PopupKeyboardUi` / `LongPressPopupKeyboardUi` 的 `onTrigger` 由恒 `FcitxKeyAction` 改为**按 `codePointCount == 1` 分流**——单码点仍 `FcitxKeyAction`，多码点改 `CommitAction`（参考 MacroKey 的 text 操作）。
2. `598897b7`：把判据抽成新文件 **`input/popup/PopupKeyAction.kt`** 的 `popupKeyAction(text, isValidFcitxKey)`：**单码点且 `isValidFcitxKey(text)`**（`Key.parse` 成功、非 `Key.None`、且 `portableString == text`）才用 `FcitxKeyAction`，否则一律 `CommitAction`（含空串）。两处 `onTrigger` 统一调用它。**判据比第一步更严**：单码点但**不是合法按键名**（如某些符号）也改走 `CommitAction`，不再被 fcitx 静默丢弃。

**测试**：新增 JVM 单测 `PopupKeyActionTest.kt`（合法单码点走 Fcitx、多码点/非法单码点/空串走 Commit）。**真机回归未做**（需装机核对长按弹出多字符候选（emoji/组合字符）能正常上屏）。

---

### 0.6.19 PR 工作流 + 设备按键兼容开关（2026-10-08，`0077c401` / `2609dc2d` / `897ece50`）

#### 新增 PR CI 工作流（`0077c401`）

新增 **`.github/workflows/pr.yml`**（workflow 名 `PR CI`）：`pull_request` 触发（目标分支 `fx-rime-only`，paths 排除 `*.md` / `.gitignore` / `docs/**`），两个 job——

- `unit_test`「JVM unit tests」：`./gradlew :app:testFxDebugUnitTest`；
- `debug_build`「Debug APK build」：装 `cmake;3.22.1` 与 `extra-cmake-modules`/`gettext`、跑 `./prepare_personal_build.sh` 后 `./gradlew :app:assembleFxDebug`。

均 `ubuntu-22.04`、`contents: read`。**注意：第 1032 行曾记「删掉 pull_request workflow」，本次是重新引入**（旧的是被删的 mainline 时代 CI；`pr.yml` 是新写的、只服务本分支），**不是自相矛盾**——第 3.1 节与 §1/§2 已同步注明。

#### vivo / 小米按键兼容开关默认值调整（`2609dc2d` + `897ece50`）

**背景**：`AppPrefs.vivoKeypressWorkaround`（键 `vivo_keypress_workaround`）原默认值随 SDK 与厂商浮动，且**老用户升级后默认值不会重算**（SharedPreferences 里没存过才用默认）。

- `2609dc2d`：默认值**收窄为「仅 `DeviceUtil.isVivoOriginOS` 才默认开启」**（去掉 `isMIUI` 与 `SDK >= VANILLA_ICE_CREAM` 两项）；并**新增 `AppPrefsMigration.kt`**（`object`，`apply(sharedPreferences)`）在 `FcitxApplication` 初始化 `AppPrefs` 之后调用，用 `app_prefs_migration_version` 做一次性迁移——**版本 1**：把 `vivo_keypress_workaround` 按 `DeviceUtil.isVivoOriginOS` 覆写，让存量用户也吃到新默认。
- `897ece50`：把默认值**扩回「vivo 或小米」**（`DeviceUtil.isVivoOriginOS || DeviceUtil.isMIUI`），迁移版本 **1 → 2**、常量改名 `KEYPRESS_WORKAROUND_MIGRATION_VERSION`，迁移逻辑同步为 `isVivoOriginOS || isMIUI`。

**关键点**：这是一次**有意的默认值纠偏**——先用迁移版本 1 收窄到 vivo，随即（同批）用版本 2 扩回 vivo+小米；因为迁移是**按版本号单向推进**的，最终升级路径落点就是「vivo 或小米默认开启」。改这类开关**必须连带 bump 迁移版本号**，否则存量用户的旧值不会被纠正。

**测试**：`0077c401` 无测试（CI 配置）；`2609dc2d`/`897ece50` 无测试（纯默认值/迁移逻辑）。**真机回归未做**（需在 vivo OriginOS 与小米设备上核对默认开启后的按键行为）。

---

### 0.6.20 弹出菜单跟随主题使用整体背景色（2026-10-09，`d786dd78`）

**新增 `input/popup/ThemedPopupMenu.kt`**：一套跟随主题配色的 `ListPopupWindow` 封装——背景用 `GradientDrawable`（`cornerRadius = 4dp`，`setColor(theme.backgroundColor)`，即**主题整体背景色**，不再用 `popupBackgroundColor`）；条目文字 `theme.keyTextColor`、按下高亮 `theme.keyPressHighlightColor`、分隔线由 `keyTextColor` 24% alpha 生成；宽 `196dp`（不超过屏宽 − 24dp）、行高 `48dp`。支持 `Item.Action`（label/onClick/enabled/bold）与 `Item.Divider`。

**改用主题整体背景色的调用点**：

- `RimeSchemaMenuDialog.kt`：自绘圆角卡片（`cornerRadius = 28dp`，`setColor(theme.backgroundColor)`）+ 单选按钮（`buttonTintList` 选中 `accentKeyBackgroundColor`、未选 `keyTextColor`），替代原 `AlertDialog` 的 `setSingleChoiceItems`；
- `CandidateCharacterPopup.kt`：背景 `popupBackgroundColor → backgroundColor`、文字 `popupTextColor → keyTextColor`；
- `PopupMenuUi.kt`：`paint.color` 与背景 `accentKeyBackgroundColor → backgroundColor`、图标 tint `accentKeyTextColor → keyTextColor`；
- `StatusAreaWindow.kt`：状态区菜单改用 `ThemedPopupMenu`（原 `PopupMenu` + `buildSpannedString`）；
- `BaseInputView.kt`：候选操作菜单改用 `ThemedPopupMenu`；`PopupEntryUi` / `PopupKeyboardUi` / `LongPressPopupKeyboardUi` 同步。

**测试**：该提交**本身零测试**。**真机回归未做**（观感类改动，需装机核对各弹出菜单与主题一致性）。

---

### 0.6.21 随机主题：配色评分与即时应用入口（2026-10-09，`43e16fb6`）

**新增文件**：`data/theme/ThemeRandomizer.kt`（+369）、`data/theme/ThemeAestheticScore.kt`（+354）；图标 `ic_random_theme_24.xml`。

**评分模型（`ThemeAestheticScore.kt`，从网站 `ime.lutrip.com/theme.html` 精确移植）**：`scoreTheme(candidate): ThemeScore`，总分 100，四个维度——

- **对比度（contrast）**：按 9 组前景/背景配对（各自权重 2~6）算 WCAG 对比度比，加权平均后 ×40；若最低对比度 < 2.0 则整体 ×0.25、< 3.0 则 ×0.55（硬惩罚）；
- **色相（hue）**、**饱和度（saturation）**、**明度（lightness）**：其余分项。
`ThemeScore(total, contrast, hue, saturation, lightness)`。`ThemeRandomizer`：`MAX_ATTEMPTS = 100`、`EARLY_STOP_SCORE = 88`（提前停止）、5 种配色方案 `analogous/complementary/triadic/split-comp/mono`，`generateBestRandomTheme` 取评分最高者。

**设置项 / 文案**（默认 `values` + `zh-rCN`/`zh-rTW` 三份）：`random_theme`「随机主题」、`random_theme_score`「美学评分：%1$d/100（对比度 %2$d，色相 %3$d，饱和度 %4$d，明度 %5$d）」、`random_theme_score_short`「评分 %1$d/100」、`random_theme_copy`「复制为自定义主题」、`random_theme_generate`「重新随机」、`random_theme_toast`、`random_theme_name_reserved`「此名称保留给随机主题使用」。

**入口与机制**：

- `ThemeManager`：随机主题存于独立 slot（`theme_random_slot`），**名称固定 `RANDOM_THEME_NAME = "随机主题"`**（主题列表里被过滤掉、不重复出现；`random_theme_name_reserved` 即阻止用户用该名）；`randomizeTheme()` 重新随机并即时应用（`applyRandomTheme` 会关掉「跟随系统夜间模式」再 `setNormalModeTheme`）；
- `ButtonAction.RandomThemeAction`（工具栏/键盘按钮，`ButtonsCustomizerActivity` 加 `ButtonDefinition("random_theme", …)`）；
- `CustomThemeActivity` / `ThemeListAdapter` / `ThemeListFragment` / `ThemeThumbnailUi`：随机主题卡片显示评分，「重新随机」「复制为自定义主题」入口。

**测试**：新增 JVM 单测 `ThemeRandomizerTest.kt`、`RandomThemeActionTest.kt`。**真机回归未做**（需装机核对随机主题即时应用、评分显示与「复制为自定义主题」流程）。

---

### 0.6.22 主题：编码区圆角与工具栏上方圆角接续（2026-10-10，`01d0dfee`）

**新增两个主题设置**（`ThemePrefs`，路由 Theme，0–48dp、**默认 0**（保持既有外观），8 份 strings 与搜索索引已同步）：`preedit_radius`「编码区圆角半径」、`toolbar_radius`「工具栏上方圆角半径」。

**新增 `input/bar/ContinuousCornerGeometry.kt`**（+231，纯几何，可 JVM 单测）：把编码区（预编辑）胶囊的**底部反向圆角**与键盘卡片**上方圆角**做成一条连续弧线——胶囊主体左缘相对卡片左缘位移 `preeditBodyOffset = (toolbarRadius - inset).coerceAtLeast(0) + preeditRadius`（`inset` = 侧边距/单手留白），反向弧向左/右外扩 `preeditRadius`，正好收在卡片上圆角与顶边的切点（两弧在该点同切于卡片顶边）；反向弧水平半径**严格等于 `preeditRadius`**，垂直半径不够高时才 `flareVerticalRadius` 收敛到半高。`clampRadius` 把半径夹在 `min(w,h)/2`。

**其它要点**：编码区高度不变（胶囊仍在原文字行高内让出弧线，不额外加高）；**卡片圆角做在 `keyboardView` 上而非工具栏条**（`keyBorder` 打开时工具栏背景透明，做在条上不可见且会露出下层底色缺口）；浮动形态取与既有 10dp 圆角的较大值，四角同半径时仍走 `setRoundRect`（避免吃掉浮动键盘高度阴影）；模糊层 `KeyBlurMaskView` 与主题预览 `KeyboardPreviewUi` 裁剪同步改为卡片形状。

**坑（已记）**：`PreeditShapeDrawable` 里必须写 `paint.color = this.color`——在 `Paint` 的 `apply` 作用域里裸写 `color` 会解析成 `Paint.color` 自己（编译为 `setColor(getColor())`），画笔静默保留默认黑色；提交正文记明已用 `javap`/`jadx` 核对字节码与最终 APK 的 DEX。

**测试**：新增 JVM 单测 `ContinuousCornerGeometryTest.kt`；`:app:testFxDebugUnitTest` 全量 **379 例**通过、`:app:assembleFxDebug` 成功。**真机回归未做**（提交正文明确「未做真机验证，adb 无设备」）。

---

## 1. 用户给的长期约定（必须遵守）

原话：**「全部做，从 fx2 分支复制到另一个分支，在新复制的分支上面改动，每改好一处就推送上去一次，手动触发一次 ci，但是不要 release」**

落实为每次改动的固定流程：

1. 一处改动 = **一个独立提交**，提交信息用**中文**；
2. 推送 `fx-rime-only`（2026-09-07 由 `fx2-rime-fusion` 更名）；代码/构建改动会触发 CI，纯 `*.md` / `docs/**` / `.gitignore` 被 paths 排除；必要时再手动 `workflow_dispatch`；
3. 确认 CI 绿 + 有产物；
4. ~~**绝对不要创建 release / tag**~~ **2026-09-07 起按用户后续要求恢复 nightly release**（`20604d6b`）：`fx-rime-only` 构建成功后自动创建时间戳 nightly prerelease（`nightly_release` job，`needs: build_commit`，`if: github.ref == 'refs/heads/fx-rime-only'`）；**手动正式 release / 语义化 tag 仍禁止**；
5. ~~`fx2`、`review-fx2-fixes`、`backup/*` 全部**不要动**~~ **`fx2` 分支及其衍生 release/CI 已于 09-09 按用户要求删除**（见第 2 节注）；`review-fx2-fixes`/`backup/*` 的 refs 早已不存在；现存的 `pr/fx2-integrated`（用户明确选择保留）、`docs`、`master` 分支无明确要求不碰；
6. 已 push 的提交**不要 amend**，新改动开新提交；
7. **重要功能性改动必须及时更新文档**（2026-09-10 用户约定）：新功能、用户可见行为/配置/数据路径变化、引擎与流水线变化、CI/发布流程变化，须在**同一任务内**同步更新对应文档——用户可见行为 → 文档站 `docs/manual/RIME_ONLY_USER_GUIDE_zh-CN.md`；引擎更新/流水线/故障经验 → 本文档（`docs/maintainer/`）；风险状态 → 审阅报告（同目录）；构建与产品概览 → `README.md`（随代码改动提交在 `fx-rime-only`）。文档提交推送到文档站 `docs` 分支（2026-10-05 起；原 `rime-docs` 分支已删除）；当改动影响操作规范/工作流本身时，一并更新本机 `D:\GitHub\fx2-rime\AGENTS.md`。

Git/发布纪律的权威版本是本机 `D:\GitHub\fx2-rime\AGENTS.md` 第 11 节：**每次任务的 push/外部 workflow 触发都要以当次用户的明确要求为准**，不沿用历史授权。

CI 事实（2026-09-15 实测 `ci.yml`，`a2db421a` 加单测 job 后；2026-10-08 新增 `pr.yml`）：
- **`ci.yml`**（workflow 名 `Commit CI`）三个 job：`build_commit`（`ubuntu-22.04` × `arm64-v8a`）+ `unit_test`（独立 JVM 单测 job，失败不阻塞 Nightly）+ `nightly_release`（仅 `fx-rime-only`，见上）；
- push 触发**所有分支**（`branches: '*'`，tags 忽略语义化版本号）；
- 构建命令 `./gradlew :app:assembleFxRelease`，约 10 分钟；
- 产物 artifact 名 `app-ubuntu-22.04-arm64-v8a`，路径 `app/build/outputs/apk/fx/release/*.apk`；
- 纯 Markdown / `docs/**` 提交不触发 CI。
- **`pr.yml`（2026-10-08 `0077c401` 新增，workflow 名 `PR CI`）**：`pull_request` 触发（目标分支 `fx-rime-only`，paths 同样排除 `*.md`/`.gitignore`/`docs/**`），两个 job——`unit_test`（`:app:testFxDebugUnitTest`）+ `debug_build`（`./prepare_personal_build.sh` 后 `:app:assembleFxDebug`）。⚠️ **§3.1 曾记「删掉 pull_request workflow」，那是 mainline 时代的旧 CI；本次是重新引入一份新写的工作流**，两者不矛盾。详见 §0.6.19。

---

## 2. 分支现状

2026-09-10 晚实测：工作树干净，基线 `3ad25fc9` 之上 129 个提交。分支 09-07 由 `fx2-rime-fusion` 更名，本地目录 09-10 同步更名为 `fx-rime-only`。**提交标题已于 09-10 全部重写为 `类型(模块): 内容` 格式，逐提交明细直接看 `git log --oneline`**——本文原有的两张逐提交表格已删除（内容被自描述标题取代），仅保留 git log 里看不出来的历史事件与机制说明。

> 2026-09-20 复核：基线 `3ad25fc9` 之上 172 个提交（纪元④）。此前 09-16 记录的 143 个属纪元③、其 SHA 在纪元④ 部分改写，计数与 SHA 一律以 git 实测为准。

### 历史 SHA 的四个纪元（引用旧材料里的 SHA 前必读）

`fx-rime-only` 的提交 SHA 已重写三次，旧材料（用户日志、CI run、release notes、更早的交接记录）里的 SHA 分属四个纪元：

| 纪元 | 内容 | 查看方式 |
|---|---|---|
| ① 分拆前完整历史 | 184 提交（153 代码 + 31 文档），tip `b3da998e`；本文档与两份审阅报告里的绝大多数 SHA | tag `archive/pre-doc-split`；本地另有 `backup/pre-doc-split` 分支 |
| ② 分拆后、标题重写前 | 154 提交，tip `143fe9fa`；仅中间材料引用 | 本地 `backup/pre-retitle` 分支（未推送） |
| ③ 标题重写后 | 129 提交（标题重写 + 19 组合并 + release 描述修改），tip `a3da6483`；0.5.9 节与 CI run 35460755786、Nightly `0.1.3-617` 属于此纪元 | 本地 `backup/pre-layout-split` 分支 |
| ④ 当前历史（2026-09-20） | 基线之上 **172** 提交（纪元③ 173，其中 `08e48eac` 之后的 14 条重排为 13 条）；改动为：布局更新拆出为独立提交、两组同类提交合并 | `git log fx-rime-only` |

**旧 SHA 追溯链**：旧 SHA → 在 `archive/pre-doc-split`（或 `backup/pre-retitle`、`backup/pre-layout-split`）下 `git show` 得到标题与正文 → 按标题（或合并清单）在当前 `git log` 定位。19 个合并组的新提交正文自带被合并成员的旧短 SHA 与旧标题；`fix(C32)` 这类审查编号完整保留在正文里（标题已改为模块化描述）；纪元④ 两次合并的成员旧 SHA 见下方历史事件。

**纪元④ 的 SHA 对照（仅此区间受影响，`08e48eac` 之前一律不变）**：

| 纪元③ SHA | 纪元④ SHA | 说明 |
|---|---|---|
| `ed6422a8` | `2f9d84cf` | 候选词上滑选字窗（内容不变） |
| `86d7c6c6` | `1539e99b` | prebuilt 指针（内容不变） |
| `04b9864d` | `d1d4eba0` | 工具栏图标归一（内容不变） |
| `e45f041a` | `87ed7f06` | 候选词按住后滑动（**剥离布局改动**，message 去掉布局段） |
| `ca67f689` | `8a149332` | Rime 不预置方案（内容不变） |
| `6f6571cd` | `4cc4c8e4` | 高度基准 RealSize（内容不变） |
| `41937738` | `927b90e4` | 日志脱敏（内容不变） |
| `fcf1240d` | `91afccc3` | 主题编辑器渐变缓存（内容不变） |
| `3f18e8cb` | `815aa4c3` | 候选分页 native 空判（内容不变） |
| `6eb05ede` | `41032347` | 数据目录原子写（内容不变） |
| `b9358dc0` + `a3da6483` | `caef1547` | 两条键盘布局编辑修复合并为一条 |
| `09b0e0e6` + `75fbf97c` | `8843b858` | 引擎启停：suspend 适配并入状态机修复 |
| —（新增） | `6bdf2a86` | 内置布局更新独立提交（大同 + Sandy + `assetSizes`） |

> 注意 `09b0e0e6` 在纪元③里**位置本身是错的**：它声称"适配 stopFcitx/restartFcitx 改为挂起函数"，但其子提交 `75fbf97c` 才真正把这两个函数改成 `suspend`（该时点 `FcitxDaemon` 仍是 `fun stopFcitx()`）。它能编译（非 suspend 函数也能放进 `launch`），只是叙述与代码时序倒置；纪元④ 合并两条后该问题自然消解。

### 关键历史事件

- **2026-09-20 布局拆分与同类项合并（纪元③ → 纪元④）**：应用户要求把 `e45f041a` 里的两个内置布局改动**单独拿出来放一个提交**，并顺带整理同类项。做法与结果：
  - **拆出**：`6bdf2a86`（内置布局更新）含三项——大同布局去 4 处 `keyboard_height_percent` 覆盖、Sandy 布局换新版、`BundledPresets.assetSizes` 登记 Sandy 旧字节数；`87ed7f06` 剥离布局后只留 Kotlin + README。
  - **合并 A**：`09b0e0e6`+`75fbf97c` → `8843b858`（引擎启停）。两者文件零重叠，合并后调用方适配与 suspend 化成为一个自洽提交，并消解 `09b0e0e6` 的叙述时序倒置。
  - **合并 B**：`b9358dc0`+`a3da6483` → `caef1547`（键盘布局编辑两个"未落盘编辑被静默丢弃"缺陷，模块相同、文件零重叠）。
  - **不合并**：`ed6422a8`+`e45f041a` 虽同打 `feat(候选词)`，但后者**推翻**了前者的交互（直接上滑 → 按住后滑动），合并会丢失这次设计反复的来龙去脉，故保持独立。
  - 其余提交（候选词上滑选字窗、prebuilt 指针、工具栏图标、Rime 方案、键盘高度、日志脱敏、主题编辑器、候选分页、数据目录）模块主题各异，各自独立；13 条中 2 条为合并产物、11 条为单一来源（含新增的布局提交）。
  - **验证方法**：临时分支上 `git cherry-pick` 逐条重放，再对每个非布局提交做 `git diff <新> <旧>`（排除布局路径）**必须为空**；最终树相对纪元③ 只多 Sandy 布局与 `assetSizes` 两项。本地 `build-debug.ps1 -Test` 与出包均 exit 0。
  - **两个可复用操作细节**：① 备份分支必须**在切到临时分支之前**建（`git branch backup/xxx` 取的是当时的 HEAD；先 `git switch -c tmp/xxx <base>` 再建备份，备到的就是临时分支的位置），本次顺序正确，`backup/pre-layout-split` 落在 `a3da6483`；② 合并两条提交用连续两次 `git cherry-pick -n`，暂存区天然就是两条的合并结果，`commit` 时补一份合并后的 message 即可。
- **rebase（2026-09-04 晚）**：应用户要求把 `review-fx2-fixes` 的 6 个提交插到本分支所有改动之前（`git rebase --onto review-fx2-fixes 85de19be`）并 force-push；仅 1 处冲突（`BaseInputView.kt` 的 `setupFcitxEventHandler()`：C31 断连兜底与 Phase 0 trace 改同一段，两者都保留）。此后 A~G 审查修复以「评审项逐项独立提交」落地（正文带根因/位置/级别），即 `fix(A1)`~`fix(G5)`、`perf(D2)`~`perf(E11)` 系列——09-10 标题重写后这批提交按模块合并为 19 组，正文全保留。

2026-09-07~09-09 的 11 个提交（nightly 恢复 `686c47c7`、应用名两连 `80338944`/`d4eedecb`、用户手册与审阅报告、剪贴板搜索 `e3d69269` 等）明细见 `git log`；用户日志对应的三个修复（双击斜杠崩溃 `d1e5bafc`、横屏悬浮错乱 `1a883904`、剪贴板搜索 `e3d69269`）的根因分析见第 3.4 节。09-09 起 CI 自动采用 fcitx5-rime@`e74ddb6` 适配层，但官方重写 updateUI 后 **tab 全链路真机回归仍待做**（见第 0 节补注）。

历史上存在 review/backup 分支；当前 `show-ref` 已无这些 refs。不要重建或破坏清理，需旧内容时按 SHA 查询。**现有三个本地 backup 分支是 SHA 追溯用的，未经明确要求不要删除**：`backup/pre-doc-split`（纪元①）、`backup/pre-retitle`（纪元②）、`backup/pre-layout-split`（纪元③ tip `a3da6483`）。

**`fx2` 分支已删除（2026-09-09，按用户要求，含全部衍生资产）**：删除时 `origin/fx2 = 3ec76d37`（09-07 曾被重建推进），内容为 fxliang:fx 合并线 + README 更新 + A~G 审查修复/perf 全套（与 `fx-rime-only` 对应部分**内容等价、SHA 不同**，是平行血统）+ 3 个 cherry-pick 通用修复（`0431cff8` 草稿落盘、`002a4062` IME 退出跳同步、`3ec76d37` 数字层记忆释放——分别对应本分支 `fca0b3e5`/`92561244`/`b65fbb4d`）——**无独有改动，删之无损失**。一并删除的衍生资产：CI run `34101224455`（该分支唯一 run）、release `383945931`「靓企鹅-Sandy版（带各个插件）」与 `380373220`「向fxliang提交PR前的测试版」及两个 nightly tag（远端 tag 一并清掉；本地克隆中对应 tag 亦已删）。其提交对象在 GC 前仍可按 SHA 访问。`pr/fx2-integrated` 分支及其 09-01 CI run 按用户选择**保留**。基线 `3ad25fc9` 仍是 `fx-rime-only` 的祖先，计数口径不受影响。

### CI 与 JVM 单测（机制说明）

✅ **2026-09-15 起 CI 已有独立 `unit_test` job**（`a2db421a`），编译并运行 `app/src/test/**`（`:app:testFxDebugUnitTest`），失败不阻塞 Nightly 出包（刻意不写进 `nightly_release` 的 needs）——09-13 那类"测试编译错误潜伏一整天"的窗口已关闭。**2026-10-08 `0077c401` 又新增 `pr.yml`（`PR CI`）**：`pull_request`（目标 `fx-rime-only`）触发 `unit_test` + `debug_build` 两个 job，把单测与 Debug 构建前置到 PR 阶段（与 `ci.yml` 的 push 触发并存，见 §1/§0.6.19）。`testOptions { unitTests { isReturnDefaultValues = true } }` 仍在。**任务名必须精确为 `testFxDebugUnitTest`**：AGP 9 默认 `onlyEnableUnitTestForTheTestedBuildType = true`，只为被测 build type（debug）生成单测任务，`testFxReleaseUnitTest` 不存在。

---

## 3. 领先基线的核心改动（语义分组；小节内旧 SHA 已就地标注当前对应，逐提交明细看 `git log`）

> 本节按主题概括改动。09-10 起 `git log` 标题已自描述（`类型(模块): 内容`），此处不再逐条列 SHA，仅保留 git log 里看不出来的结构与取舍。

### 3.1 精简为 rime 专版（本轮核心）

- **fcitx5-rime 并入主 APK**：librime 静态链接 + rime-data 资源 + opencc 软链，rime 从"插件 APK"变成主包内置 addon。
- **删除内置拼音/码表链路**：native（libime/pinyin/table/customphrase，**保留 opencc**）、gradle 依赖与 lib 模块（含 `plugin/pinyin-lm`、`plugin/table-data`）、拼音/码表管理 UI 与 AIDL `reloadPinyinDict`。
- **删除 9 个其他语言/功能插件模块**（anthy/chewing/hangul/jyutping/sayura/thai/unikey/text-editor/clipboard-filter）与整套插件检测/运行时框架（`DataManager.detectPlugins`、签名白名单、`PluginFragment`、`FcitxPluginServices`、`lib/plugin-base`、`FcitxPluginService`/`PluginMessage`/`ClearUrlsPluginRuntime`；`MainService` 改继承 `Service`，出站过滤走 `HostClipboardFilter`）。
- **移除 mainline flavor** 及任务别名/APK 兼容拷贝；CI 精简为单一 `ci.yml`（删 fdroid/pull_request/nix/publish），编译错误输出成 annotations。⚠️ 这里的「删 pull_request workflow」是 mainline 时代的旧 CI；**2026-10-08 `0077c401` 重新引入了一份新写的 `pr.yml`**（见 §1/§0.6.19），与本次删除不矛盾。
- **包名 `org.fcitx.fcitx5.android.fx.rime`**（`appIdFxSuffix = ".fx.rime"`），可与 fx2 并存安装；APK 文件名同步替换。
- 首批三任务（quickphrase 移除、预置 rime 默认启用、androidkeyboard/imselector/spell/unicode 裁剪）详见第 4 节。

### 3.2 文档（已迁至文档站 docs 分支）

现存历史设计文档两份：`docs/archive/rime-only-feasibility.md`（可行性报告）、`docs/archive/rime-integration-plan.md`（实施方案，含附录 C 按键管线调研、附录 D 打字跟手性研究），与用户指南、审阅报告等同住文档站 `docs` 分支（2026-10-05 自 `rime-docs` 分支迁入，后者已删除）。⚠️ 两份文档早期"建议保留 androidkeyboard/unicode/spell/imselector"的结论已被加注推翻，读时注意注解。

### 3.3 性能（perf）

- Phase 0 埋点（androidx.tracing）；候选栏路径优化（宽度预计算、去帧延迟、每键分配削减、前后缀结构 diff、增量刷新去递归 view.post）；模糊遮罩与水波纹绘制优化。
- 主线程搬迁一大批（主题/图标/壁纸/布局解析/分享接收/剪贴板图片/ClearURLs 规则/截图查询等）；列表与视图复用（行 chip、textKeys 缓存、ButtonsBarUi viewType）；网络客户端复用与流式上限。
- "P2 native 事件合并"评估结论：**暂缓，需测量门控**。

### 3.4 修复（fix）与根因分析

带编号（A/B/C/D/E/F/G + 数字）对应一次代码审查清单，按主题覆盖：布局 JSON 健壮性、图标主题与 ZIP 上限、备份与迁移、编辑器 Activity 生命周期、语音输入、按键与弹窗、剪贴板同步与内嵌 HTTP 服务、内存泄漏、引擎守护与事件流。各修复的根因/位置/级别分析**完整保留在当前历史的提交正文里**（09-10 重写未删；审查编号在正文可搜）。本节各小节的 SHA 已按当前历史换算：`fca0b3e5`→`238f61af`（草稿改存私有文件）、`b65fbb4d`→`9a23ea27`（宏 layer to 释放数字层记忆）、`92561244`→`2c1b9cb5`（IME 退出跳过 Rime 全量同步）、后续修正 `f961a85c`→`589df187`（?123 记入层历史）、`5dbe1f6c` 双高亮修复为当前历史原生 SHA，按标题可在 `archive/pre-doc-split` 或当前 git log 追溯。

#### `fca0b3e5` 布局编辑器草稿改存私有文件（用户日志定位，2026-09-05；当前历史 SHA `238f61af`）

用户报告"自定义键盘布局编辑过程中崩溃"，日志 `小企鹅转中洲鹅布局崩溃...2026-09-04T15_24_57Z.txt`
（版本 `0.1.3-585-g54706725`）：

```
java.lang.RuntimeException: android.os.TransactionTooLargeException:
    data parcel size 540248 bytes
Bundle stats:
  draft_layout_json [size=537176]
at IActivityClientController$Stub$Proxy.activityStopped
```
日志 1011–1014 行的因果链：`Large Bundle: length=540104` → `Binder transaction failure ... error: -28`
→ `FAILED BINDER TRANSACTION (parcel size = 540248)` → VM 退出。

**根因**：`TextKeyboardLayoutEditorActivity.onSaveInstanceState` 把整份未保存布局 JSON
（`exportCurrentJsonString`，pretty print）放进 saved-instance Bundle。该 Bundle 在 Activity stop
时经 Binder 交给 system_server，而**整个进程**的事务预算只有约 1MB。用户这份布局 537KB，直接超限。
**与"小企鹅转中洲鹅"这个布局本身无关，只跟布局体积有关**：时间线 23:24:53.28 打开
`KeyEditorActivity`（点了一个按键）→ 编辑器 stop → 必崩，所以表现为"编辑时随机崩溃"。

**改法**：草稿落到 `noBackupFilesDir/layout-editor-drafts/` 下的私有文件，Bundle 里只放文件名。
新增 `data/LayoutDraftStore.kt` 封装 adopt/write/read/delete/pruneStale。要点：

- 无未保存改动（`hasChanges()` 为 false）时**根本不写**快照——保存后、以及大多数 stop 都走这条；
- 快照用 `formatJsonCompact`（与落盘格式一致），比 pretty print 小很多；
- 内容哈希未变则跳过重写，避免每次 stop 都落盘；`adopt()` 会清哈希（文件内容未知，不可跳过）；
- 保存成功 / 切换布局文件 / `isFinishing` 时删快照；进程被直接杀掉留下的快照按 7 天在下次进
  编辑器时清理（`onCreate` 协程末尾，不挡首屏）；
- 只有写文件失败（目录不可写、无空间）才退回 Bundle 内联，且限 32K 字符（`INLINE_MAX_CHARS`）；
- `loadState()` 是异步的：新增 `stateLoaded` 门控，它完成前的 stop 只**透传**旧草稿引用，不会用
  空布局覆盖或误删；`captureDraftReference()` 在 `onCreate` 里同步执行，早于那个协程；
- 草稿的读取与解析移到 `Dispatchers.IO`（同 D7）。

顺带修掉：`previewSubModeLabel` 之前被 Bundle 里的 null 无条件覆盖，现改为仅在有值时恢复，缺失
时交回 `buildSubModeSpinner` 依 IME 重新推导。

新增 `LayoutDraftStoreTest.kt`；经 `4ee31e44` 扩展后当前 **18 例**（该安全硬化在当前历史中对应 `56e450b8` fix(布局编辑器): 校验草稿快照文件名，阻断路径穿越）。

**后续安全硬化（`4ee31e44`）**：只接受 `draft-[A-Za-z0-9-]{1,64}.json`；所有文件访问校验 canonical parent；prune 不碰 foreign 文件。新增 3 例覆盖路径穿越、绝对路径和 prune 边界。

**与 `77be0fb8`（语言键 Shift）无冲突**，已核对：文件零重叠；`77be0fb8` 删掉的
`langSwitchKeyBehavior`/`LangSwitchBehavior` 全仓库零引用；保留的 `showLangSwitchKey` 仍被
`LayoutDataManager` 与编辑器的 `getDefaultLayout(showLangSwitch = true)` 使用；`LanguageKey` 的
KeyDef 与 JSON 序列化都没动，`77be0fb8` 只改按下它的运行时行为；编辑器预览键盘不接
key action listener，点预览不会触发 `sendStandaloneShiftTap`。且 `77be0fb8` 是 `fca0b3e5` 的祖先，
run #215 构建的就是两者合并后的树。

#### `b65fbb4d` 发送后跳回数字盘：宏 layer to 离开数字层时释放手动数字布局记忆（用户日志定位，2026-09-07；当前历史 SHA `9a23ea27`）

用户报告（QQ 聊天，稳定复现）：「使用 layoutswitch 切换到数字键盘层后，再使用宏按键定义的
layer to 切换到 rime 文本布局，此时输入文字，然后点击 app 的发送按键，输入框文字会清空，
键盘也会跳回至数字键盘」。日志 `跳数字盘1/2...2026-09-05T14_3*.txt` + 用户布局
`TextKeyboardLayout.行之26.json`。

日志证据链（`跳数字盘1` 为例）：

- 全程 **0 条 `NumberKeyboard.reloadLayout`**，只有 `TextKeyboard.reloadLayout` → 证明 `?123`
  被 `numeric_layout_override`（=数字 层）短路，数字盘由 TextKeyboard 渲染；
- `22:31:17.378 TextKeyboard.reloadLayout rows=4`（无宏日志、纯触摸）= 按 `?123`（数字层 4 行）；
- `22:31:18.336 TO target=符号` → rows=5（符号层 5 行）→ `22:31:19.577 TO target=rime` → rows=4；
- 打字、`22:31:22.921 CommitStringEvent`，随后 `22:31:23.699 onStartInput restarting=true`
  （QQ 清空输入框触发的原地重启）→ `22:31:23.718 TextKeyboard.reloadLayout rows=4` = **跳回数字层**；
- 用户只能再按一次 `㞢`（TO rime）逃回文字盘（`22:31:27.442`），下一轮发送再次跳回。

**根因**（两条路径共同触发）：

1. `?123` 走 `switchLayout(Number)` → `activateManualNumericLayout("数字")`，在
   `NumericLayoutOverrideController` 留下 manualKey="数字"；
2. 宏 `layer to rime` 走 `handleLayerSwitchAction`，只设 `latchedLayerKey`，**manualKey 原样残留**——
   释放手动槽位的只有 ABC 键（switchLayout 的 fromUserKey 分支）和真实 IME 变化两条路；
3. 点发送 → `onStartInput(restarting=true, inputClass 未变)`：
   - `isNumericLayoutShowing()` 因 manualKey!=null **误报**“数字层在屏”（屏幕上是 rime 文字盘）→
     `shouldKeepCurrentLayoutOnStartInput` 判定偏差、走保留分支；
   - 保留分支 `clearForcedLayoutKey()` → `force(null)` 回落 manualKey="数字" → 键盘跳回数字层。
   （日志里的 `reloadLayout rows=4` 正是这一步；rime 层也是 4 行，靠用户“再按一次㞢 才回文字盘”
   与 `TO rime` 后必跟一次 reloadLayout 反推出跳的确实是数字层。）

**改法**：锁存式切层（`layer to` / BACK 弹出实际层）视同 ABC 键/切输入法一样的“显式离开数字盘”
手势，释放手动数字布局记忆：

- `NumericLayoutOverrideController.releaseManualOnLayerSwitch(target)`：manualKey==target（重申
  同一数字层）不算离开、保持记忆；否则 `releaseManual()`。另加 `isManualNumericShowing()`
  （forcedKey==manualKey 才算真在屏）；
- `TextKeyboard.releaseManualNumericLayoutOnLayerSwitch()`：只清状态不自带 refresh，随后调用方的
  `setForcedLayoutKey` 一次完成重排并纠正瞬时的 forcedLayoutKey 不同步；
- `KeyboardWindow.handleLayerSwitchAction` 的 TO 分支与 BACK 分支（仅弹出实际层时）调用之。

**后续修正（`f961a85c`，2026-09-07，用户日志 `返回图层...09_22_07Z` 定位；当前历史 SHA `589df187`）**：`?123` 跳数字盘与 BACK 的
历史栈脱节——`switchLayout(Number)` 重定向到手动数字布局时**不压 layerHistory**，导致 ① 干净状态
下数字盘上 BACK 空弹栈 no-op（用户预期回到文字盘）；② 先 `TO A`→`TO B`→`?123` 后 BACK 弹出的是
进数字盘之前的旧记录 A。修法：

- `switchLayout` 的 Number 重定向分支：`fromUserKey` 且手动数字布局未在屏（读于 activate 之前，
  新增 `TextKeyboard.isManualNumericLayoutShowing()`）时，把离开前的有效层（oneShot ?: latched）
  压入 layerHistory，使 BACK 可撤销这次跳转；重复按 `?123` 不重复压栈；
- `handleLayerSwitchAction` BACK 分支：弹出为空但 `releaseManualNumericLayout()` 成功（手动数字
  布局在屏）时释放手动槽，回落基础文字层。

**保持不变**（已逐一核对）：数字编辑框的 session 覆盖跨层保留；`?123` 后不切层直接打字再发送
（支付宝式逐字 restartInput）仍保留数字盘；OSL 单次层结束后仍回落数字盘；数字编辑框（session，
非手动）上的 BACK 空/有历史均不动 session 覆盖。

`NumericLayoutOverrideControllerTest.kt` 共 6 例；`f961a85c`（当前 SHA `589df187`）的 KeyboardWindow layerHistory 集成路径仍无测试。run #232 仅证明包含该改动的发布树可编译。

#### `92561244` IME 退出不再触发 Rime 全量同步（用户日志定位，2026-09-07；当前历史 SHA `2c1b9cb5`）

用户报告"切到系统其他输入法再切回来，键盘好几秒弹不出来"，日志
`日志/切输入法1...2026-09-05T14_06_26Z.txt` 与 `切输入法2...14_06_56Z.txt`
（版本 `0.1.3-602-gef320bfb`，两份完全同构）的时间线：

```
22:06:06.333  onFinishInputView / onFinishInput（用户切走，本 IME 的 IMS 被 Android 销毁）
22:06:06.361  [main]      FcitxDaemon stop fcitx → Fcitx.stop() → FcitxDispatcher stop()
              [fcitx-main] nativeExit() → "Running save..." → "Rime Sync user data"
22:06:06.382  rime 工作线程启动（sync_user_data 排了 3 个部署任务）
22:06:06.384  "Unloading addon rime" → ~RimeEngine → finalize() 阻塞 join 工作线程
22:06:06.385→17.713  合并 11 个用户词典 × sync/00000001+00000002 两份快照再回写（11.3s，
                        其中 replacer 4.0s、enreplacer 4.6s；仅 00000001 旧快照就白耗 4.7s）
22:06:17.721  [main] "Skipped 1378 frames!"（主线程 runBlocking 整整卡了 11.36s）
22:06:17.728  新 IMS 的 service.onCreate 才开始跑 → 键盘迟到 11+ 秒
```

**根因链**：切走 → IMS `onDestroy` → `FcitxDaemon.disconnect`（最后一个客户端）→
`Fcitx.stop()` → `FcitxDispatcher.stop()` 在**主线程** `runBlocking` 等 `runningLock`；持锁的
fcitx-main 线程正在 `nativeExit()` → `Fcitx::exit()` → `eventLoop().exec()` 触发 fcitx5 唯一的
退出事件 `Instance::save()` → `AddonManager::saveAll()` 落到 rime 插件 `RimeEngine::save()` =
`sync_user_data()`（异步排部署任务），随后 `resetGlobalPointers()` → `~Instance` → 卸载 rime 插件 →
`~RimeEngine` 的 `api_->finalize()`（librime `Deployer::JoinWorkThread`）**同步等**工作线程跑完。
fcitx-main 出不来 → 主线程的 `runBlocking` 出不来 → 切回来时新服务的 `onCreate` 排在主线程
队列里出不去。日志 2 同样卡法：38.452 stop → 49.871 "Skipped 1386 frames" → 49.883 onCreate。
（冷启动本身很快：onCreate 13ms、onCreateInputView 71ms，全被前面的退出卡住吃掉了。）

**改法**：`native-lib.cpp` `Fcitx::exit()` 删掉 `eventLoop().exec()`，直接调已有的
`saveWithoutRime()`（= `Instance::save()` 去掉 rime：`loadedAddonNames()` 就是 `saveAll()`
遍历的 `loadOrder_`，`addon(name)` 默认不强制加载，已对照 fcitx5@442edbc9 核实）。安全性已核对：
整个构建里注册过退出事件的只有 fcitx5 core 的这一个 save（clipboard 模块、app 自带
androidfrontend/androidnotification、SandyYuR fcitx5-rime fork 均无 `addExitEvent`）；事件循环的
清理（日志里的 "UVLoop close" 两行）在 `~UVLoop()` 析构里，本来就独立于 `exec()`。

**代价（有意为之）**：退出时不再自动刷新 `sync/` 快照备份。用户词典数据本身落 leveldb
（finalize 关库即持久），不会丢；完整同步仍可用：状态栏菜单"同步"（userTriggered 路径）、
关机广播 `ACTION_SHUTDOWN → save()`。**迁移设备前记得手动点一次"同步"**。

**顺带收益**：「重启 Fcitx 实例」（DeveloperFragment/远程服务）、设置页最后一个客户端退出，
同样不再被 11s 卡住；退出同步改写 installation.yaml 引发的下次启动 "modifications detected"
450ms maintenance 部署预计随之消失（待验证）。另外建议用户清理
`rime/sync/00000001/`（旧安装的残留快照，当前 installation 是 00000002，每次同步都在白做
一遍 4.7s 的合并）。

**验证方法**（装新 APK 后复现"切走→秒切回"）：
- 切走时日志应**没有** `Rime Sync user data` / `starting work thread` / `deploy start`；
  `FcitxDispatcher stop()` 到主线程恢复应在 ~200ms 内，且无 `Skipped N frames`；
- 切回后 `service.onCreate begin` 到 `onStartInputView` 约 130ms，rime 引擎约 1s 就绪；
- 手动点状态栏"同步"仍会跑完整 3 任务部署并弹部署成功通知。

#### `5dbe1f6c` 候选栏双高亮：结构 diff 位移后高亮重绑打到错误位置（用户日志定位，2026-09-14）

用户报告"连续按键移动候选时，上一个候选和当前候选同时高亮"，且强调**不是每次按键都出现**——日志
`日志/候选高亮...2026-09-14T10_32_47Z.txt`（OnePlus PJD110，debug 构建版本
`nightly-0.1.3-454-...-135-g07206012`）里两轮标点场景各 5 次移动，**只有第 4 次按键**（cursor 2→3）
出现双高亮。这个"偶发但每次复现都在同一步"的特征正是定位钥匙。

**日志读法**（第 6 节方法的又一次实战）：fcitx 引擎侧 `PagedCandidateEvent` 每一步都正确（cursorIndex
0→1→2→3→4、候选数组内容不变），状态机 `CandidatesUpdated didn't change the state` 也正常——引擎与
事件分发无嫌疑，问题只能在 **Kotlin 侧候选栏的 UI 增量刷新**。候选内容两轮完全相同（`、､/／÷` 五个
标点，带〔全角〕〔半角〕注释），唯一随按键变化的是 cursorIndex，第 4 步恰好是
`HorizontalCandidateComponent.ensureActiveCandidateVisible` **首次触发窗口滑动**的那一步（前 3 个
候选项已占满首行，高亮移到第 4 个时窗口前移一位）。

**根因**：`HorizontalCandidateViewAdapter.updateCandidates` 用前后缀结构 diff 发
`notifyItemRangeInserted/Removed`（性能优化 `73497f6e` 引入）——RecyclerView 收到 insert/remove 只
**位移既有 ViewHolder，不重新 bind**；而高亮重绑却按**位移前**的旧下标发
`notifyItemChanged(oldActive)`。插入/删除发生在旧高亮项之前时，真正还带着 active 背景的 ViewHolder
已经移到别的位置，这条通知打在错误的位置上，旧高亮无人取消 → 与新高亮同屏。
完整链路：cursor 3 时窗口化 `updateCandidates([､,/,／,÷], active=2, offset=1)`；cursor 4 时恢复整列
`updateCandidates([、,､,/,／,÷], active=4, offset=0)`——恢复动作以 `notifyItemRangeInserted(0,1)`
在队首补回 `、`，前一状态里的 active（`／`，ViewHolder 视觉态仍在）从位置 2 位移到 3，而
`notifyItemChanged(oldActive=2)` 重绑的是位移后位置 2 上的 `/`（本来就不活跃）→ `／` 高亮残留 +
`÷` 新高亮 = 双高亮。**注意该缺陷与 d1e5bafc（onLayoutCompleted 推迟 drain）无关**——drain 时序
正确，错的只是 diff 后的重绑坐标。窗口"滑出再滑回"的结构位移是必要条件，所以只有窗口边界那一步
出问题（第 4 次），前 3 次与第 5 次都正常。

**改法（`5dbe1f6c`）**：结构 diff 抽成纯 JVM 可测的 `CandidateUpdatePlan`（notify 计划 =
changed/insert/remove 四元组 + **位移后坐标系**的两个高亮重绑位置）；计划阶段计算旧 active
ViewHolder 在 insert/remove 之后的新位置（保留前缀/被替换区间/匹配后缀三分支映射），并跳过会被
结构通知覆盖的范围；`updateCandidates` 按计划发通知。"内容相同仅元数据变化"路径保留
`notifyDataSetChanged` 兜底（结构计划此时零操作，会导致 ViewHolder 样式滞留）。

**验证**：新增 `CandidateUpdatePlanTest` 12 例（含按日志复现的
`headInsertOfNewItemShiftsPreviousActiveRebindPosition`，旧逻辑下该用例的期望值必错），
`:app:testFxDebugUnitTest` 133 例全绿；debug APK 真机（同日志设备）复现路径实测通过后提交推送。

**不变式（改这块必须守住）**：对带"结构 diff + 局部 notify"的 RecyclerView 适配器，**高亮/选中态的
重绑位置必须在 insert/remove 位移之后的新坐标系里计算**；把"被位移的视觉态"当作"会被重绑的状态"
是这类双高亮/双选中的通病。凡结构 notify 与状态 notify 混用的适配器都要按此检查。

---

#### `e916245e` 字体与字号保存后不即时生效：一次性布尔标志 + 三条独立缓存路径（用户报障，2026-10-04）

用户报告"改完 **应用主页 → 外观 → 字体设定** 保存后，字体/字号不即时生效，必须强制结束软件再打开才能换上；而且**只换一部分**，打几个字又会好一些"。真机（OnePlus PJD110）复现确认。

**根因**（三条互相独立的路径叠在一起）：

1. **刷新信号是一次性布尔，且被键盘侧先消费**。`FontProviders.needsRefresh()` 当时只有两个消费点：`KeyboardWindow.checkAndApplyFontRefresh()`（由 `InputView.startInput()` 调用）与 `FcitxInputMethodService` 里 `onCreateInputView` 那条路径。`InputView.startInput()` 先跑键盘那条，标志被读走并清零后**候选侧读到的恒为 false**——于是只有恰好被 RecyclerView 回收重建的那部分 ViewHolder 换上新字。这就是"只换一部分、再打几个字又都换过来"的来源。
2. **版本号只覆盖字体，只改字号时永不前进**。`DefaultFontProvider` 的 publish 块只比较 typeface map，于是 `preloadFontsForKeyboard()` 的 fontGeneration 门控、`InputView.auxBarSingleRowMinHeight`、`PreeditUi` 的光标条缓存等**所有按版本号判定的地方都看不见字号变化**。
3. **多处基准字号是构造期快照**：`KeyView.baseMainTextSizeSp` / `baseAltTextSizeSp`、`CandidateItemUi.textSize`、`PopupEntryUi` 与 `LabeledCandidateItemUi` 的一次性 setup——复用旧实例时永远拿不到新值。

**改法**：把刷新判定从"一次性布尔"换成**单调递增的字体数据版本号** `FontProviders.fontGeneration`（= provider 的 `fontDataVersion`，`FontProviderApi.fontDataVersion` 默认 `0L`）。它**不再需要被"消费"**，任何一侧都能按"我上次用的是哪个版本"自行判断。worker（`FcitxFontPreload`）把字号解到局部 `freshSizes`，在 publish 块里**同一把锁内先换两张表再 `fontGeneration++`**，保证"观察到新版本号 ⇒ 新字号与新字体都已就位"，不会出现新字体配旧字号的中间态。所有基准字号由构造期快照改成"按版本号重读"。

覆盖到的视图：`HorizontalCandidateViewAdapter` / `HorizontalCandidateComponent`（`applyConfiguredTypeface` → `refreshConfiguredFont`，字号与字体一起重读；两道"候选没变就早退"的去重补上版本号比较，否则只改字体设定会被整个吃掉）、`CandidateItemUi`（新增 `applyConfiguredTextSize()` / `refreshConfiguredFont()`；注释字号写在富文本 span 里而 `CandidateViewHolder.update` 在内容不变时跳过重渲染，故加 `renderedCommentSignature` 比对强制重渲染）、`PagingCandidateViewAdapter`（字体变化时即使 `(offset, generation)` 都没变也必须 `refresh()`——`PagingDataAdapter.refresh()` 是 final，拦不住它的 notify，只能自己放行）、`LabeledCandidateItemUi` / `PagedCandidatesUi`、`PopupEntryUi`（实例在 `PopupComponent.freeEntryUi` 池里循环复用，构造期读一次不够，补在每次 `setText()`）、`PreeditUi`、`KeyView`。候选侧本来就没有"字体设定已保存"的事件入口（`InputBroadcastReceiver` 的回调全是空默认实现），所以 `onStartInput` 里搭上键盘侧同一趟 `preloadFontsAsync` 的回调，版本号真正前进后重新下发当前候选。另新增 `FontProviders.DEFAULT_CANDIDATE_FONT_SIZE = 20f`，替掉候选栏宽度预测里散落的字面量 `20f`。

**同提交一并清掉的死代码**（换成版本号后旧入口的一部分已无人使用，三处纯删除共 20 行）：`AutoScaleTextView.clearFontCache()`（全仓 476 个 `.kt`/`.java` 中调用点数为 0，是 `FontProviders.clearCache()` 的唯一调用者）、`FontProviders.clearCache()`、`FontProviders.needsRefresh(): Boolean` 以及 `BaseKeyboard.currentRowsSignature()` 里的 `"|fontRefresh:"` 项。**注意保留 `FontProviderApi.clearCache()` / `DefaultFontProvider.clearCache()`**——那才是真正生效的缓存失效路径，仍由 `handleFontsetChanged()`（`FontProviders.kt:78`）与 `markNeedsRefresh()`（`FontProviders.kt:92`）调用。删掉 `"|fontRefresh:"` 是净收益：`markNeedsRefresh()` 在 worker 发布新字体**之前**就立起标志，此时 `fontGeneration` 尚未前进，签名变化只会让按键行用**旧字体**白重建一次，等 worker 发布后 `"|fontGen:"` 再变一次又要重建；删掉后一次保存只重建一次且必然是新字体。`"|fontGen:"` 与其上方注释才是有效的重建触发点，必须保留。

**未改动的部分**：`needsRefresh` 字段、`markNeedsRefresh()`、`checkAndClearRefreshFlag()`、`handleFontsetChanged()` 仍保留。它们的两个消费者落在**互斥分支**上——虚拟键盘会显示时走 `InputView.startInput()` → `KeyboardWindow.checkAndApplyFontRefresh()`（只清可复用行缓存 + 重建按键行）；不显示虚拟键盘（物理键盘/外接）时 `startInput()` 根本不被调用，改由 `FcitxInputMethodService.kt:1825` 自行消费该标志并置 `pendingThemeUpdate`，触发 `replaceInputViews()` **整树替换**（`new InputView` + `new CandidatesView`）。后者压在生命周期敏感逻辑上（`isInInputLifecycleCriticalPhase` / `canApplyPendingThemeNow()`），`pendingThemeUpdate` 还与真实主题切换共用，且真机验证只覆盖了前一个分支，故本次不动。专项评估见 `FONT_REFRESH_LEGACY_FLAG_REPORT_2026-10-04.md`。

**验证（真机，OnePlus PJD110 / arm64-v8a debug 包）**：全程 `adb shell pidof` 显示 IME 进程 **PID 未变（`12319`）**，证明走的是热刷新路径而非强杀重启。逐项实测：候选字号 22→40 sp、按键主字体 23→36 sp、预编辑字体 14→32 sp、注释字体 12→30 sp **保存后不重启输入法即按新字号渲染**（注释那项由用户手动确认通过）；展开候选面板同样按 40 sp 渲染。**长按弹出气泡那条未取到决定性证据**：气泡用的是 `AutoScaleTextView` 自适应缩放，字号变化被它掩盖，`uiautomator dump` 也读不到气泡内的 TextView，只验证到代码路径接上。JVM 侧 `:app:compileFxDebugKotlin` + `:app:testFxDebugUnitTest`（351 例）与 `:app:assembleFxDebug -PbuildABI=arm64-v8a` 均通过。

**不变式（改这块必须守住）**：**字体刷新信号必须是可重复读取的版本号，不能是一次性标志**——一次性标志在"多个消费者 + 竞态顺序"下必然有人读不到，且症状是"只换一部分"这种极难复现的半失效态。凡是"某一侧先消费掉、另一侧才读"的跨组件通知，都要按此检查。

---

### 3.5 用户可见名称（`bab4558c`，09-07 `e51afd88` 更新）

简繁中文应用名为 **“靓企鹅·中州韵”**（`e51afd88` 起用间隔号 ·，此前 `bab4558c` 版本为句点。当前历史对应 `80338944` 句点版 → `d4eedecb` 间隔号版）；默认英文及 de/es/ja/ko/ru 保持 `Fcitx5.fx.rime`。Play 中文 listing 与旧 release note 仍写“小企鹅输入法”，恢复发布前需统一。

---

## 4. 历史四项任务

用户原话：
> 「去掉附加组件里面的 Android 英文键盘，输入法选择器，拼写，unicode。输入法安装就是默认启用 rime，且只有 rime 可用，其他无关组件都清除。默认键盘就是 rime 的 default 键盘，语言切换键改成按下发送一次 shift 点击事件，利用 ascii mode 来做到切换中英文输入」

追加：
> 「快速输入组件也去掉」

拆成四项：

### ✅ 任务 1 — 清除无关组件（纪元① SHA：`072c0e87` + `cefc481b`；当前历史对应 `480cdd26` 与 `47ee56fd`）

**`072c0e87` 移除 androidkeyboard / imselector / spell / unicode：**
- `app/build.gradle.kts`：cmake targets 去掉 `"androidkeyboard"`；新增 `fcitxComponent { excludeFiles = [...] }` 排除 `imselector.conf`/`spell.conf`/`unicode.conf`。
- `app/src/main/cpp/CMakeLists.txt`：删 `add_subdirectory(androidkeyboard)`、`Fcitx5::Module::Unicode` 链接、`copy-fcitx5-modules` 里 imselector/spell/unicode 的拷贝、spell 词典 install。
- 删目录 `app/src/main/cpp/androidkeyboard/`（4 个文件）。
- `lib/fcitx5/build.gradle.kts`：去掉 imselector/spell/unicode 的 cmake target 与 prefab。
- `native-lib.cpp`：删 unicode include / `p_unicode` / `triggerUnicode()` / JNI。
- Kotlin：`Fcitx.kt`/`FcitxAPI.kt` 删 `triggerUnicode`，`CommonKeyActionListener.kt`/`KeyAction.kt` 删 `UnicodeAction`，`KeyDefPreset.kt` 删 unicode 长按与逗号键弹窗里的 Unicode 项。

**`cefc481b` 移除 quickphrase（37 文件，-1161 行）：**
- 构建：`lib/fcitx5/build.gradle.kts` 去 target+prefab；`app/src/main/cpp/CMakeLists.txt` 去 `Fcitx5::Module::QuickPhrase` 链接与 `fcitx5::quickphrase` 拷贝；`app/build.gradle.kts` `excludeFiles` 增加 `quickphrase.conf` 与 `usr/share/fcitx5/data/quickphrase.d/{emoji,emoji-eac,latex}.mb`。
- native：`native-lib.cpp` 删 include / `p_quickphrase` / `triggerQuickPhrase()` / `triggerQuickPhraseInput` JNI（6 处）。
- Kotlin 删除：`data/quickphrase/`（7 文件）、`QuickPhraseEditFragment.kt`、`QuickPhraseListFragment.kt`。
- Kotlin 改动：`Fcitx.kt`、`FcitxAPI.kt`、`AddonSubconfig.kt`（删 `reloadQuickPhrase`）、`FcitxRemoteService.kt`、`CommonKeyActionListener.kt`、`KeyAction.kt`（删 `QuickPhraseAction`）、`KeyDefPreset.kt`（删 `QuickPhraseKey` 与逗号弹窗项）、`TextKeyboard.kt`（`SpecialKeyViews` 去 quickphrase 字段，6 处）、`PreferenceScreenFactory.kt`、`SettingsRoute.kt`（删 `QuickPhraseList`/`QuickPhraseEdit` 路由）、`ConfigDescriptor.kt`（`ETy` 去 `QuickPhrase`，去 `"QuickPhrase","Editor"` 映射）、`CustomActionExecutor.kt`（`ROUTE_MAP` 去 `quick_phrase_list`）、`IconTheme.kt`（去 `keys.quickphrase` 槽位）、`MacroEditorActivity.kt`（去动作 id/标签/映射 3 处）。
- 资源：`values/keyboard_26_ids.xml` 去 `button_quickphrase`；`values/strings.xml` 去 6 条；7 个语言目录（de/es/ja/ko/ru/zh-rCN/zh-rTW）各去对应条目。
- AIDL：`IFcitxRemoteService.aidl` 去 `reloadQuickPhrase()`。

**故意保留的无害残留**（别再动）：`IconThemeEditorActivity.kt:617` 的 `"keys.quickphrase" -> R.drawable.ic_baseline_format_quote_24` 图标映射、`lib/fcitx5/.../cmake/FindFcitx5Module.cmake:6` 的 `FCITX5_MODULE_NAMES` 列表（只是接口别名工厂，列了不等于构建）、`app/src/main/play/release-notes/*.txt` 里的历史发布说明。

### ✅ 任务 2 — 首次启动只启用 rime（纪元① SHA：`dc0db868`；当前历史对应 `870ab1c8`）

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

### ✅ 任务 4 — 语言切换键改成"发一次 Shift 点击"（**已完成**）

`072c0e87` 的提交信息里写了"中英切换后续改由语言键发送 Shift 点击走 rime ascii_mode"，但当时**只写了这句话、没有落代码**，语言键仍在轮换输入法。现已实现。

行为（键盘上的 🌐 与工具栏/状态区的"语言切换"按钮完全一致）：

| 手势 | 行为 |
|---|---|
| 单击 | 发一次**独立 Shift 敲击**（down → 50ms → up），交给引擎处理；rime 侧由 `ascii_composer/switch_key` 的 `Shift_L` 决定，默认 `inline_ascii`，想要整体切中英应在 `default.custom.yaml` 改成 `commit_text` 或 `commit_code` |
| 长按 | 弹**系统**输入法切换菜单（`InputMethodManager.showInputMethodPicker()`），不再是 app 内自绘的 `InputMethodPickerDialog` |

实现要点：

1. `FcitxInputMethodService.sendStandaloneShiftTap()`（新增，单一入口，两处共用）
   - 走 `sendSimulatedKeyEvent`（`InputDevice.SOURCE_KEYBOARD` + `FLAG_FROM_SYSTEM` → `forwardKeyEvent(preserveModifierState = true)`），**不能**用 `sendSimulatedKeyEventOrFallback`（那条路只到应用的 InputConnection，fcitx 收不到）；
   - 按住 `STANDALONE_MODIFIER_HOLD_MS = 50L`。这个值**只有上限、没有下限**：librime `AsciiComposer` 要求修饰键在 press 后 500ms 内 release（`toggle_duration_limit`），fcitx5 core 的 modifier-only 热键要求在 `ModifierOnlyKeyTimeout`（默认 250ms）内 release；而区分"独立修饰键"与"修饰键+字母"靠的是**事件顺序**不是时长（`AsciiComposer` 一收到非修饰键就清 `shift_key_pressed_`），顺序由 `forwardKeyEvent` 的自增 timestamp 索引 + `postFcitxJob` 顺序队列保证，与墙钟时间无关。所以取短值：这段延迟是语言键纯粹的手感损失（toggle 只在 release 时发生），同时也是紧接着敲字母可能被共享的 simulated-Shift 状态波及的窗口；50ms 不是给引擎留的安全余量（引擎侧没有下限），只是取真人敲键按住时长区间的下沿，让 down/up 对外仍像一次真实敲击。
   - 用 `standaloneShiftTapJob` 串行化，连点两次得到两次独立敲击而不是嵌套的 down-down-up-up；`delay` 外面套 `try/finally` 保证任何取消路径都补发 up（修饰键按住状态是全局的，漏掉 up 会让后续按键都带 Shift）。
   - 与之对齐：`BaseKeyboard.sendFcitxKeyTap` 的 `keyHoldDelayMs` 原本是 `if (isMod) 150L else 50L`，现在**取消修饰键分支**、所有键统一 `50L`。那是宏（`executeMacro` 的 Tap 步骤）与快捷键（`executeShortcut`）路径，**语言键不经过它**，两处只是取值一致；它原来的注释 "keep press time longer so Rime can recognize standalone Shift" 与上面那条是同一个误解，已改写。分支去掉后 `isModifierKey()`（private，唯一调用方就是该分支）成了死代码，一并删除——`MacroEditorActivity` 里那个同名方法是另一份实现，仍在用，别混淆。宏里每步都会累加这个时长，修饰键从 150ms 降到 50ms 对长宏的整体执行速度有肉眼可见的收益。
2. `CommonKeyActionListener`：`is LangSwitchAction ->` 调 `service.sendStandaloneShiftTap()`；`is ShowInputMethodPickerAction ->` 改成 `InputMethodUtil.showPicker()`。
   注意 `showInputMethodPicker()` 那个私有方法**还要留**——空格长按的 `SpaceLongPressBehavior.ShowPicker` 仍用它弹 app 内对话框。
3. `ButtonAction.LanguageSwitchAction`：`execute()` 同上；`onLongPress()` 改成 `InputMethodUtil.showPicker()`。
4. 删掉 `LangSwitchBehavior.kt`（整个 enum）、`AppPrefs.langSwitchKeyBehavior`、`KeyboardGroupFragment` 的 `"lang_switch_key_behavior"`、以及 8 个 `strings.xml` 里的 `lang_switch_key_behavior` / `lang_switch_behavior_next_ime_app`。
   **保留**：`show_lang_switch_key`（显示开关）、`space_behavior_enumerate` / `space_behavior_activate`（`SpaceLongPressBehavior` 还在用）、`KeyAction.LangSwitchAction`、`KeyDefPreset.LanguageKey`。
   `switchToNextIME` 已无调用方，但留着没删。（`AddMoreInputMethodsPrompt` 已在“主设置页输入法项改成中州韵设置”那一轮删掉。）

**为什么 Shift 能到 rime（已核对上游源码）**：
- fcitx5 core 的 hotkey watcher 与引擎在**同一 phase**、且排在引擎**之前**，`Hotkey/AltTriggerKeys` 默认就是 `Shift_L`；但每个分支都要过 `Instance::canTrigger()`（`currentGroup().inputMethodList().size() > 1`）。本分支只有 rime 一个 IM，所以 core 不会 filter，press/release 都会落到引擎。
- 更稳的一层：CI 用的 **SandyYuR/fcitx5-rime**（历史源自 fxliang，见第 0 节 `prepare_personal_build.sh`）带 `fcitx5-alt-trigger-v4point1.patch`，给 `canAltTrigger` 加了 `InputMethodEngineV4Point1::supportsAltTrigger()` 钩子，而 `RimeEngine::supportsAltTrigger()` 默认返回 `false`（配置项 `ShiftKeyBehavior`，默认 `DisableFcitxToggle`）。即使以后 IM 变成多个，Shift_L 也仍归 rime。
- `RimeState::keyEvent` 不丢 release：release 会带上 `1 << 30`（IBUS_RELEASE_MASK）喂给 `process_key`，正是 librime `AsciiComposer` 判定"独立 Shift 敲击"所需（它只在 release 且 500ms 内才 toggle）。

**验证**：`./gradlew :app:assembleFxRelease`（本机 09-14 起已有完整工具链，可直接 `build-debug.ps1` 出包）；装机后按语言键应能看到 rime 的 `ascii_mode` 中/英翻转，长按弹出系统输入法菜单。当前历史实现提交为 `26fc1454` feat(键盘): 语言键改为发送独立 Shift 敲击。

---

## 5. 需要知道的机制（省得重新摸索）

- **addon 打包链路**：addon 的 `.so` 由 cmake target 拷进 jniLibs；`.conf` 由 `install(... COMPONENT config)` 装到 `usr/share/fcitx5/addon/` → 进 APK assets → 显示在"附加组件"设置页。要让某个组件从设置页消失，除了不构建它，还要在 `app/build.gradle.kts` 的 `fcitxComponent.excludeFiles` 里列出它的 conf 路径。
- **`excludeFiles` 语义**（`build-logic/convention/src/main/kotlin/FcitxComponentPlugin.kt:49-58`）：`deleteFcitxComponentExcludeFiles` 任务在 install 之后对 `assetsDir.resolve(it).delete()`——**文件不存在也不会报错**，所以多列几条是安全的。
- **`generateDataDescriptor`** 依赖 `installFcitxComponent` + `deleteFcitxComponentExcludeFiles`（`AndroidAppConventionPlugin.kt:130-135`），descriptor 里不会包含被排除的文件。
- **`DataManager.sync()`** 只按 `descriptor.json` 的差异新增/更新/删除文件；用户自己放的、不在 assets 清单里的文件永远不会被覆盖或删除。
- **rime 的两个目录**：shared data = APK assets 解出来的 `<deviceProtectedDataDir>/usr/share/rime-data`（`RIME_DATA_DIR` 编译期宏 + 运行时 `StandardPaths::locate(Data, "rime-data/default.yaml")` 定位）；user data = `getExternalFilesDir(null)/data/<当前profile>`（由 `native-lib.cpp` 的 `setenv("FCITX_DATA_HOME", <extData>/data)` + `XDG_DATA_HOME` 决定；profile-manager 支持 `data/rime` 之外的多个配置目录，`5cc57c0d` 起"用户数据目录"入口长按直达**当前** profile 目录）。
- **fcitx 环境变量**全在 `native-lib.cpp:539-560`（`LANG`/`LANGUAGE`/`FCITX_LOCALE`/`HOME`/`XDG_DATA_DIRS`/`FCITX_CONFIG_HOME`/`FCITX_DATA_HOME`/`FCITX_ADDON_DIRS`/`XDG_*`）；gettext 翻译域注册在 `:563-565`（`fcitx5`/`fcitx5-rime`/`fcitx5-android`），rime 内置后手动注册的 `fcitx5-rime` 域就在这里。
- **rime-data 资源清单**在 `app/src/main/cpp/CMakeLists.txt`（`plugin/rime/src/main/cpp/CMakeLists.txt` 有一份需同步维护的镜像清单），`COMPONENT prebuilt-assets`；`app/build.gradle.kts` 的 `generateDataDescriptor { symlinks.put("usr/share/rime-data/opencc", "usr/share/opencc") }` 建软链。
- **本版不预置任何输入方案，也不附带 `essay.txt`**（2026-09-20 起）：清单只装 `default.yaml` + prelude 的 `key_bindings/punctuation/symbols`。三条不能踩的线：① `default.yaml` 是 `RimeEngine` 构造时 `locate("rime-data/default.yaml")` 定位 sharedDataDir 的锚点，删了直接抛异常；② `key_bindings/punctuation/symbols.yaml` 是**非可选**公共依赖，第三方方案普遍以 `punctuator: import_preset: symbols`、`key_binder: import_preset: default` 引用，删了用户自带方案会失效；③ `default.yaml` 里 `schema_list` 必须写成**空列表 `[]`**，写成 `schema_list:`（无值）会被 yaml-cpp 解析成 Null，`WorkspaceUpdate` 记录 "schema list not defined." 并判定部署失败（空列表则部署成功且无方案）。
- **`essay.txt` 缺失是静默降级而非部署失败**：方案以 `use_preset_vocabulary: true` 声明依赖它，但 `PresetVocabulary::OpenReadOnly` 失败只记一条 ERROR，`GetNextEntry` 随即返回 false，`ChecksumComputer::ProcessFile` 对不存在的文件直接 return——部署照常成功，只是词典少一批预置短语与词频。**用户可自备**：`vocabulary` 资源以 `user_data_dir` 为 root、`shared_data_dir` 为 fallback（`service.cc:167-170`），放进用户数据目录即生效。
- **内置资源（`BundledPresets`）的更新机制**：`assets/bundled/` 下的内置布局/主题/图标主题在首次启动时解包到用户目录（布局 → `config/`，主题 → `theme/`，图标主题 → `icon_themes/`），以"asset 路径 + 字节数"记进 SharedPreferences 做幂等标记，**目标文件已存在时绝不覆盖**（用户删掉的文件也不会被塞回）。要让存量用户拿到更新，必须把该资源加进 `assetSizes` 并**登记上一版的字节数**——`installAsset` 只在 `dest.length()` 等于登记值时才原位替换，长度不等即视为用户自己改过而保留用户版本；**未登记的资源只在目标文件不存在时才写出**。所以漏登记 = 更新只对全新安装生效，且 `git status`、单测、APK 出包都看不出来。2026-09-20 Sandy 布局 45949 → 45963，登记值填 **45949**；大同布局 14611 → 14191，登记值保持 **14611** 不动。，`app/src/main/assets/usr/share/rime-data/` 里被移出清单的文件不会自动消失，会被 `generateDataDescriptor` 收进 descriptor 和 APK。改清单后必须手工删掉残留（该目录被 `.gitignore` 忽略，`git status` 看不出来），再用 `unzip`/`ZipFile` 核对 APK 内的实际打包内容。
- **验证体积必须 `clean` 后打包**：assets 增减后直接 `assembleFxDebug` 走增量合并会虚高体积（本轮实测增量 29.45 MB vs clean 后 25.58 MB，差异来自旧的压缩产物未重建）。
- **saved-instance Bundle 有硬上限**：Activity stop 时整份 Bundle 经 Binder 交给 system_server，
  **整个进程**共享约 1MB 事务预算，超了就是 `TransactionTooLargeException` 硬崩（见第 3.4 节
  `fca0b3e5`）。**任何"整份用户配置"都不要放进 `onSaveInstanceState` 或 Intent extra**，改用
  `LayoutDraftStore` 那套“文件 + Bundle 只放文件名 + 名称白名单/canonical path”。现存需要留意的同类写法：
  `KeyEditorActivity` 的 `draft_key_data`（单个按键，正常几 KB，仅极端巨大 MacroKey 有理论风险）、
  `ButtonsCustomizerActivity` 的 `draft_buttons_config`（按钮表，很小）。
- **字体刷新的判定机制（2026-10-04 起）**：唯一权威信号是 **`FontProviders.fontGeneration`**（= `FontProviderApi.fontDataVersion`，由 `DefaultFontProvider` 在 worker publish 块里与两张表**同锁递增**），它是**单调版本号、可重复读取**，各消费者按"我上次用的是哪个版本"自行判断。**不要再引入"读一次就清零"的一次性标志做跨组件通知**——旧设计就栽在这里，症状是极难复现的"字体只换一部分"。所有字体相关的基准字号必须**按版本号重读**，不要构造期快照。仍保留的一次性布尔 `needsRefresh` / `markNeedsRefresh()` / `checkAndClearRefreshFlag()` 只服务两条互斥分支（会显示虚拟键盘时的按键行重建；不显示虚拟键盘时的 `replaceInputViews()` 整树替换），与候选侧无关；专项评估见 `FONT_REFRESH_LEGACY_FLAG_REPORT_2026-10-04.md`。

---

## 6. 用户日志的读法（这批日志很有用，别只看栈顶）

用户导出的 logcat 带 `--------- Device Info` / `Crash stacktrace` 头，正文是完整 logcat，**崩溃点之前的时间线才是定位依据**（0.5.16 的"部署期按键上屏"就是靠时间线里键盘可见时刻与认键时刻相差约 1.3s 定位的）。当前 `日志/` 实测 3 个文件：两份 09-28 的 logcat（对应 0.5.16）与 `提交SHA映射-标题重写-2026-09-10.txt`（SHA 追溯链）；其余历史日志（`候选高亮`→`5dbe1f6c`、`双击斜杠崩溃`→`d1e5bafc`、`横屏状态切换悬浮`→`1a883904`、`剪贴板搜索框数字`→`e3d69269` 等）已移出目录，对应修复的分析以下文记录为准。

- `fca0b3e5` 就是靠 `Bundle stats: draft_layout_json [size=537176]` 这一行 + 崩溃前 0.6s 的
  `KeyEditorActivity` 启动记录定位的：栈顶只说 `activityStopped` 失败，说不出为什么。
- 小米设备的固定噪声，**不是本 app 的问题，直接跳过**：`getMiuiFreeformStackInfo ... null`（每帧一条）、
  `ContentCatcherManager: failed to get ContentCatcherService`、
  `SettingTrigger: NoSuchFieldException: No field mContentExtensionEnabled`、`RenderInspector` 超时警告。
- 有用的自家 tag：`FcitxColdStart`、`[main] FcitxInputMethodService`、`FcitxClipboardSync`、
  `LayoutDataManager`、`LayoutEditor`、`LayoutDraftStore`。

---

## 7. 建议的下一步顺序

1. **单测是否纳入 CI（已落地）**：2026-09-15 起 CI 新增独立 `unit_test` job（`a2db421a`），跑 `:app:testFxDebugUnitTest`，失败在提交上显示红叉但**刻意不 gate Nightly**（不写进 `nightly_release` 的 needs）；2026-10-08 起 `pr.yml`（`0077c401`）也在 PR 阶段跑同一任务 + Debug 构建。**测试文件/用例计数已因 10-07~10-10 一批改动过期，不再写死数字**（旧记「41 文件 / 351 例」为 2026-10-03 快照，已不适用）：本批新增 **7 个 JVM 单测文件**——`AuxBarTabLayoutTest`（`098ec596`）、`DirectionalSwipeLabelDefTest` + `SwipeSymbolDirectionTest`（`d156052b`/`4f0d6fb8`）、`PopupKeyActionTest`（`598897b7`）、`ThemeRandomizerTest` + `RandomThemeActionTest`（`43e16fb6`）、`ContinuousCornerGeometryTest`（`01d0dfee`），并改了 `LayoutJsonUtilsTest`；另新增 **2 个 `androidTest`** 文件（`CandidateOverflowTest` / `CandidateIndexBadgeTest`，**在 `androidTest` 源集、不计入 `:app:testFxDebugUnitTest`**）。**别照抄任何数字**，用 `Get-ChildItem -Recurse app/src/test -Filter *.kt` 与数 `@Test` 现取。仍欠 KeyboardWindow layerHistory 集成测试。
2. 决定是否同步 Play 中文标题及 fcitx5-rime license/.gitmodules 来源元数据。（现状：`.gitmodules` 仍写官方 `fcitx/fcitx5-rime` URL，`fcitx5-rime.json` 的 `website` 上游仍写 fxliang、靠本机 `prepare_personal_build.sh` 的 sed 改成 SandyYuR——两边都不在仓库里固化。）
3. 使用外部 fork 前先 fetch 并核对（**旧快照一律勿照抄**：2026-09-16 时 `fcitx5-rime@e74ddb6`、`prebuilt@9e631eb9`、`prebuilder@446d1ea`；**2026-10-03 实测**：`fcitx5-rime@107502d`、`prebuilt@1126616c`、`prebuilder@4121ce6`（其 `librime` gitlink 仍 `ef1a16aa`）；三者均随每次引擎更新前进，**引用前必须 fetch 复核**，现取命令 `git -C D:\GitHub\fx2-rime\fx-rime-only ls-tree HEAD <path>`）。
4. 本地 SHA 追溯只能靠 **tag**：`refs/tags/archive/pre-doc-split`（`f719df11`）、`refs/tags/backup-pre-squash`（`1feab280`）、`refs/tags/backup/pre-date-fix`（`fe263507`）、`refs/tags/backup/pre-squash-alphabet`（`f28c5d6a`）——⚠️ **2026-10-03 核实：`backup/pre-doc-split`、`backup/pre-retitle`、`backup/pre-layout-split` 三个分支已不存在**（这里曾是它们，现已只剩 tag），而纪元② tip `143fe9fa`、纪元③ tip `a3da6483` 的提交对象虽仍在本地对象库、**已无任何分支/tag 指向**，`git gc --prune` 后会永久丢失。另留 `backup/fx-rime-only-20260920-59a3a1ab`（09-20 上游历史重写的等价内容已在重写后历史中，确认无用后可删）。当前没有 review refs 可删；未经明确要求不要破坏旧对象、reflog 或 tags。
5. **真机回归待办**：① `e74ddb6` 官方重写 updateUI 后"打字→点 tab→选词"全链路；② 引擎多次前进后的装机冒烟（当前 prebuilt 是 09-28 的 `1126616c` 一系：para deploy + userdict 缓存 + rewrite 滤镜 + 配置指纹）；③ **`fc3f98b` 移除"清除"按钮后的辅助栏真机回归**——用户 09-20 已装机确认可用，但**未刻意覆盖"音节 tab 极多"与"取消约束"两类边界场景**；④ **09-28 的部署期改动**（配置指纹 + 按键吞掉 + 键盘内「正在部署」提示）**尚未做真机回归**——本次只跑了单测与出包，未验证 "切回输入法→立即打字" 的实际手感，也未覆盖"首次安装/真实改配置后部署"这条仍会走部署的路径；⑤ **10-07~10-10 这批 15 个提交全部未做真机回归**（逐条见 §0.6.14~0.6.22）：重点核对——长候选横向拖拽手感与序号角标可见性（§0.6.14）、同一键上/下划分别触发不同宏且旧布局升级方向正确（§0.6.15）、双标签/回退在不同键高与圆角下的形态（§0.6.16）、音节 tab 滚动流畅度与第 7 项半露（§0.6.17）、长按弹出多字符候选能上屏（§0.6.18）、vivo/小米兼容开关默认开启后的按键行为（§0.6.19）、各弹出菜单与主题一致性（§0.6.20）、随机主题即时应用/评分/复制流程（§0.6.21）、编码区与工具栏圆角接续的观感（§0.6.22）。

> **已实机验证（2026-09-28 用户确认）**：符号/表情/颜文字面板的 Foxy 重写（`4f93612f`）与布局编辑器子模式下拉框「默认」项（`afc57f4b`）**均已装机验证通过**。注意 `afc57f4b` 的提交正文写着"未做真机回归"，那是**提交时的状态**，此后已补做——引用提交正文时别把它当成当前结论。
6. **纵向辅助栏均分高度无下限（已部分修复，用户明确要求暂不改的那部分仍存在）**：`BaseKeyboard.relayoutVerticalAuxBarItems()` 的 `itemHeight = height / count` 没设最小行高，外层也不是滚动容器，所以 Left/Right 辅助栏在 item 很多时全部压缩展示、无法滑动（横向 Top/Bottom 走 RecyclerView 不受影响）。09-17 的 `7a480550` 是有意为之（修悬浮 resize 时按钮间留空隙），**要改必须同时满足"保留 resize 均分"和"恢复最小行高 + 可滚动"，别只回退该提交**。⚠️ **2026-10-02 `584bddaa` 已给「自定义按键」加了按 `rowHeightPercent`（1f..100f）分配的能力**；⚠️ **2026-10-08 `098ec596` 已把音节 tab 从这条路径移出——纵向 tabs 改走 `RecyclerView`、超过六项可滚动（视口约 6.5 行，见 §0.6.17）**。因此本条「无下限」现在只针对**未配置行高的自定义辅助按键**那条路径（tabs 已不适用），改之前先分清走的是哪条。
7. **librime 上游待吃进已从 2 个涨到 10 个提交**（`35f23e97..8d8276f4`，2026-09-16 实测）：streaming_chord 两个（`74a7467e`+`74db0d18`，09-10 就排队了）+ 纯 CI/构建环境一批（runner 镜像、release action、Docker）。⚠️ 其中 `2479df58` **把 opencc 依赖升到 1.4.2**、Docker 提交动了构建环境——不再是"只 bump gitlink"的无风险更新，照 0.5.2 第 1-2 步先实测补丁可应用性，并评估 opencc 升级对简繁转换行为的影响，再决定 bump。
