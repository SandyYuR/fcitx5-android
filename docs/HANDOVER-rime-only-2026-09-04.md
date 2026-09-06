# 交接报告 — fx2-rime-fusion（rime 专版）2026-09-05

> 本文写给接手的下一个代理。仓库 `SandyYuR/fcitx5-android`（fcitx5-android 的 fork）。
> 目标分支 **`fx2-rime-fusion`**，基线 **`fx2`**（`3ad25fc9`，**必须保持不动**）。
> 本文只描述"领先 fx2 的全部改动"与"未完成的工作"。
>
> **2026-09-04 晚更新**：分支已把 `review-fx2-fixes` 的 6 个新提交 rebase 到本分支所有改动**之前**，
> 因此当时已有的 **37 个 rime 专版提交的 SHA 全部改写**、已 force-push。第 2 节有新旧对照与新 SHA。
> **2026-09-05 更新**：已同步当前工作区路径、worktree、未跟踪文件和分支状态；此前已解决的事项不再列入待处理问题。
> **2026-09-06 更新**：① 工作区重组——主仓库移入 `D:\GitHub\fx2-rime\fx2-rime-fusion\`，项目根下新增三个引擎相关 fork 的本地克隆，`日志/` 移出 git 工作区；② CI 的 rime 来源从 fxliang fork 换成 **SandyYuR 自己的 fork**（fcitx5-rime 已合并上游最新，prebuilt 保持 fxliang 终态快照），见第 0 节新表格；③ **rime 引擎更新流水线打通**——librime 从 `1.17.0-1d0df6e` 更新到 `1.17.0-3cbe4af`（上游最新，fxliang 补丁全保留），完整 runbook 见**第 0.5 节**。

---

## 0. 上手前必读（会踩的坑）

| 事项 | 说明 |
|---|---|
| 目录布局（2026-09-06 重组） | 项目根 `D:\GitHub\fx2-rime` 下四样东西：`fx2-rime-fusion/` = 主仓库工作区（本分支，git 命令在这里跑）；`fcitx5-rime/`、`prebuilt/`、`prebuilder/` = 三个引擎相关 fork 仓库的完整克隆（remote `origin` = SandyYuR 同名 fork，`upstream` = fxliang 终态）；`日志/` = 用户日志（已移出 git 工作区）。动手前先 `git status` 确认。 |
| worktree | 唯一 worktree：`D:/GitHub/fx2-rime/fx2-rime-fusion` → `fx2-rime-fusion`，与 `origin/fx2-rime-fusion` 同步。没有第二个 worktree，不要自行新增或切换到同一分支。 |
| 三个 fork 的分工与更新方式 | **`SandyYuR/fcitx5-rime`** = rime addon 源码（已合并上游 `fcitx/fcitx5-rime` 至 `1ec9515`：ascii mode 大小写图标、schema 自定义 ascii 名、xkb state 修复、app_options 示例修正；fxliang 的 profile-manager/schema 选择器/ShiftKeyBehavior 等定制全部保留）。**`SandyYuR/prebuilt`** = librime.a 等引擎二进制，**故意不合上游**——上游是官方无补丁流水线产物，合并会换掉 fxliang 定制引擎；其 master 由自己的 prebuilder 流水线产出（见下）。**`SandyYuR/prebuilder`** = 构建配方（已合并上游 3 提交 + 2026-09-06 引擎更新）。**addon 更新 = 在 fcitx5-rime fork 里 merge 上游 → push；引擎更新见下节专属流程。** |
| **rime 引擎更新流水线（2026-09-06 打通，已产出新引擎）** | 引擎 = `SandyYuR/prebuilt` 里的 `librime.a`，由 **`SandyYuR/prebuilder`** 的 CI（`ci.yml`，手动 `workflow_dispatch` 触发，约 15-90 分钟）构建并自动推回 prebuilt（"Auto update" 提交，bot 身份）。已配置 `BOT_TOKEN` secret（token 轮换后要重配）。**完整操作手册见第 0.5 节 runbook（含全部命令、SHA 对照与踩坑记录）**。当前引擎：**librime 1.17.0-3cbe4af**（上游 13 提交 + fxliang tabs/缓存/预测全套）。 |
| **CI 构建的 rime 来源已换成自己的 fork** | `prepare_personal_build.sh`（`4e1990e9` 起）把 `fcitx5-rime`、`prebuilt` 切到 **SandyYuR fork@master**，不再依赖 fxliang 账号存续；fcitx5 补丁不变（`fcitx5-alt-trigger-v4point1.patch`）。看 rime 行为直接读 `D:\GitHub\fx2-rime\fcitx5-rime\`（addon）/ `D:\GitHub\fx2-rime\librime-src\`（引擎源码，浅克隆+三补丁工作区） / `D:\GitHub\fx2-rime\prebuilt\`（引擎二进制）。仓库 pin 的 `4e996319` 仍只是占位。 |
| 日志文件 | 在项目根 `D:\GitHub\fx2-rime\日志\`（8 个用户日志/布局文件），不在 git 工作区内，不可能被误提交。 |
| 子模块未初始化 | 主仓库工作区里 `lib/fcitx5/src/main/cpp/fcitx5`、`plugin/rime/src/main/cpp/fcitx5-rime` 等仍是空 gitlink。fcitx5-rime 的源码看 `D:\GitHub\fx2-rime\fcitx5-rime\`；fcitx5 核心与 prebuilt 的其他内容仍可用 jsDelivr：`https://cdn.jsdelivr.net/gh/<owner>/<repo>@<sha>/<path>?x=N`（`?x=N` 绕缓存；raw.githubusercontent 拉大文件会超 30s 超时）。prebuilt 也有本地克隆 `D:\GitHub\fx2-rime\prebuilt\`。 |
| grep 工具 | `app/src/main/play/listings/en-US/graphics/icon/icon.png` 的失效符号链接已修正为指向 `app/src/fx/res/mipmap-xxxhdpi/ic_launcher.png`，现在可以从仓库根目录搜索。若以后再次出现 `os error 2`，先检查该链接目标是否仍存在。 |
| read 工具 | `offset`/`limit` 必须是明确数字（传 undefined 会报 "binding arguments must be lossless JSON"），`limit` ≤ 2000。 |
| GitHub API 返回会被截断 | `/actions/runs` 的 JSON 超 100000 字符会 `Unterminated string`。用 `per_page=3` 或正则抽 token，别整体 `JSON.parse`。 |
| 沙箱内 git 网络的坑 | 本机 pwsh 沙箱内 MSYS 程序（ssh/bash/sh）无法创建命名管道：**git push 走 SSH 必失败**（`couldn't create signal pipe`），git 凭证 helper 的 prompt 链也死。可用：`git -c http.sslBackend=openssl`（匿名 fetch/clone 公开仓库，schannel 后端会报 `SEC_E_NO_CREDENTIALS`）；**推送**用 `D:\GitHub\fx2-rime\push-with-gcm.ps1 -RepoDir <路径> -Remote origin -Branch <分支>`（pwsh 直接调 GCM exe 取凭证后 HTTPS 推送，token 不落盘）。 |
| CI 失败时的调试方法（2026-09-06 两次实战） | ① `Invoke-RestMethod`/curl 都因 schannel 凭证问题不可用，**拉 API/日志用 node**：`GH_TOKEN`（GCM exe 取）+ fetch，重定向要手动跟随；② annotations API 只给任务级摘要，`> Task :app:installProjectConfig FAILED` = `installProject<Config>`（`FcitxComponentPlugin.kt:76` 动态拼名，构建 `generate-desktop-file` cmake target）；③ **CMakeBuildInstallTask 的 providers.exec 会吞掉 cmake/ninja 输出**，真实编译错误只在完整 job 日志里（`actions/jobs/<id>/logs`）；④ 本机无编译器/msgfmt，改完 C++/po 后的自检：po 用严格解析器查重复条目（msgfmt 对重复 msgid 是硬错误）、C++ 用括号平衡检查 + **与两个父版本逐字符对照结尾标点**（`};);` 这类宏结尾模式最容易抄错）。 |

### 2026-09-06 合并上游后两次 CI 失败的记录（已修复）

1. **run #220 `installProjectConfig` 失败**：po 合并脚本的分块假设不成立——fxliang 的 ja.po 里部分条目之间只有一个换行（无空行），脚本把两条目粘成一块，上游同名条目再独立插入 → "All"/"Clear" 重复，msgfmt 硬错误。修复：`b88c57d` 删重复条目。
2. **run #221 `buildCMakeRelWithDebInfo` 失败**：手动解 `rimeengine.h` 冲突时把 `FCITX_CONFIGURATION` 块最后一个成员结尾写成 `false});`，丢了成员声明的分号（正确是 `false};);`）。run #220 死在 msgfmt 没暴露它。修复：`dad45bc`。
3. 教训：**解完冲突必须对照两个父版本（`git show <sha>:<path>`）核对合并块的每一个结尾标点**，"结构看起来对"不算数；po 合并后要跑重复检查；CI 挂了先拉完整 job 日志再动手，别靠猜。

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

### 0.5.1 本次实绩（所有 SHA 对照）

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
cd D:\GitHub\fx2-rime\fx2-rime-fusion
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
5. **BOT_TOKEN**：gho_ OAuth token 有 workflow 写权限（能推 ci.yml 改动），fork 的 Actions 显示 active 但 push 不触发 run——**别指望 push 触发，直接 dispatch**。
6. `librime-predict-leveldb` 子模块指向 fxliang 的仓库（停更），gitlink 永远保持 `0c30981a` 原样，**别动**。

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
fx2-rime-fusion    ef320bfb  ← 工作分支，= origin/fx2-rime-fusion（已同步）
                             领先 fx2 共 148 个提交，线性历史
                             767 files changed, +7597 / -37754
```

历史结构（自底向上）：
```
fx2 3ad25fc9
  └─ 95 个提交（审查修复 + 性能，与 review-fx2-fixes-split 共有，止于 85de19be）
      └─ 6 个 review-fx2-fixes 新提交  5fdcb0db 763f4dcf 9c411dc1 f3abf5b3 920fed60 3ec1f6b2
          └─ 47 个 rime 专版提交（SHA 已改写）  5d90019d … ef320bfb
```

`01c1070e`（原报告写的 tip）之后又加了 10 个提交：

| 提交 | 说明 |
|---|---|
| `5103b515` | docs: 交接报告同步 rebase 后的分支状态 |
| `83890f1a` | fix(macro): 应用动作选择器 id 与标签错位 |
| `77be0fb8` | feat: 语言键改为发送独立 Shift 敲击（第 4 节任务 4） |
| `fca0b3e5` | fix(布局编辑器): 草稿改存私有文件，修 TransactionTooLarge 崩溃（第 3.4 节末） |
| `a352280b` | feat(设置): 主设置页"输入法"改为"中州韵设置"，直达 rime 配置页 |
| `bf645157` | refactor(设置): 清理其余通往输入法列表页的入口（删 `AddMoreInputMethodsPrompt`） |
| `e0733388` | docs: 交接报告同步 `AddMoreInputMethodsPrompt` 已删除 |
| `6da44099` | docs: 交接报告补记布局编辑器草稿修复与分支现状 |
| `441650a6` | docs: 更正交接报告里的单测任务名与测试例数 |
| `ef320bfb` | perf(input): 修饰键保持时长 150ms 降到 50ms，并更正其依据 |

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

已验证 CI 状态（GitHub Actions）—— 注意前四行是 **rebase 前的旧 SHA** 的结果：

| 提交 | 说明 | CI |
|---|---|---|
| `54706725` | 移除 quickphrase 快速短语组件 | ✅ success |
| `b3da4853` | 首次启动时预置 rime 为默认启用输入法 | ✅ success |
| `03a70215` | 移除 androidkeyboard/imselector/spell/unicode 组件 | ✅ success |
| `eaa85316` | 包名改为 `...fx.rime` | ✅ success |
| `77be0fb8` | 语言键改为发送独立 Shift 敲击 | ✅ success（run #214，0 annotation） |
| `fca0b3e5` | 布局编辑器草稿改存私有文件 | ✅ success（run #215，0 annotation） |

⚠️ **CI 只跑 `assembleFxRelease`，不编译也不运行单元测试**，所以 `app/src/test/` 下那批测试（含
`LayoutDraftStoreTest`）在 CI 里从未被编译或执行过——绿灯只代表主源码编译通过。

来龙去脉：`63d41b69` 曾加过 "Run JVM unit tests" 步骤（`./gradlew :app:testFxDebugUnitTest`
+ 上传报告），但它带 `if: matrix.build_type == 'standard'` 条件；后来 `4f9a0a74`
（移除 nightly release 与 mainline 构建）把 `build_type` 这个 matrix 轴一起删了，单测步骤
**作为附带损失被删掉**，不是有意取消的。`app/build.gradle.kts` 里 `63d41b69` 加的
`testOptions { unitTests { isReturnDefaultValues = true } }` 还在，所以恢复只需改 `ci.yml`。

**任务名必须是 `testFxDebugUnitTest`**：AGP 9 默认
`android.onlyEnableUnitTestForTheTestedBuildType = true`，只为被测 build type（debug）生成单测
任务，`testFxReleaseUnitTest` **不存在**（这条注释就写在 `63d41b69` 加的 ci.yml 里）。这些测试都
是纯逻辑，build type 无关紧要。已向用户提议恢复该步骤，**等他点头**。

---

## 3. 领先 fx2 的 148 个提交（按主题归类）

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
测试：`587ca235` 修 `ThemeSerializationTest`；`63d41b69` 启用 `testFxDebugUnitTest`（后被 `4f9a0a74` 附带删除，详见第 2 节末尾）。

#### `fca0b3e5` 布局编辑器草稿改存私有文件（用户日志定位，2026-09-05）

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

新增 `app/src/test/.../data/LayoutDraftStoreTest.kt`（15 例）。⚠️ **CI 不跑单测，这个文件从未被
编译过**，见第 2 节末尾。

**与 `77be0fb8`（语言键 Shift）无冲突**，已核对：文件零重叠；`77be0fb8` 删掉的
`langSwitchKeyBehavior`/`LangSwitchBehavior` 全仓库零引用；保留的 `showLangSwitchKey` 仍被
`LayoutDataManager` 与编辑器的 `getDefaultLayout(showLangSwitch = true)` 使用；`LanguageKey` 的
KeyDef 与 JSON 序列化都没动，`77be0fb8` 只改按下它的运行时行为；编辑器预览键盘不接
key action listener，点预览不会触发 `sendStandaloneShiftTap`。且 `77be0fb8` 是 `fca0b3e5` 的祖先，
run #215 构建的就是两者合并后的树。

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
- 更稳的一层：CI 用的 **fxliang/fcitx5-rime**（见第 0 节 `prepare_personal_build.sh`）带 `fcitx5-alt-trigger-v4point1.patch`，给 `canAltTrigger` 加了 `InputMethodEngineV4Point1::supportsAltTrigger()` 钩子，而 `RimeEngine::supportsAltTrigger()` 默认返回 `false`（配置项 `ShiftKeyBehavior`，默认 `DisableFcitxToggle`）。即使以后 IM 变成多个，Shift_L 也仍归 rime。
- `RimeState::keyEvent` 不丢 release：release 会带上 `1 << 30`（IBUS_RELEASE_MASK）喂给 `process_key`，正是 librime `AsciiComposer` 判定"独立 Shift 敲击"所需（它只在 release 且 500ms 内才 toggle）。

**验证**：`./gradlew :app:assembleFxRelease`（本机无 JDK/Android SDK，靠 CI）；装机后按语言键应能看到 rime 的 `ascii_mode` 中/英翻转，长按弹出系统输入法菜单。

---

## 5. 需要知道的机制（省得重新摸索）

- **addon 打包链路**：addon 的 `.so` 由 cmake target 拷进 jniLibs；`.conf` 由 `install(... COMPONENT config)` 装到 `usr/share/fcitx5/addon/` → 进 APK assets → 显示在"附加组件"设置页。要让某个组件从设置页消失，除了不构建它，还要在 `app/build.gradle.kts` 的 `fcitxComponent.excludeFiles` 里列出它的 conf 路径。
- **`excludeFiles` 语义**（`build-logic/convention/src/main/kotlin/FcitxComponentPlugin.kt:49-58`）：`deleteFcitxComponentExcludeFiles` 任务在 install 之后对 `assetsDir.resolve(it).delete()`——**文件不存在也不会报错**，所以多列几条是安全的。
- **`generateDataDescriptor`** 依赖 `installFcitxComponent` + `deleteFcitxComponentExcludeFiles`（`AndroidAppConventionPlugin.kt:130-135`），descriptor 里不会包含被排除的文件。
- **`DataManager.sync()`** 只按 `descriptor.json` 的差异新增/更新/删除文件；用户自己放的、不在 assets 清单里的文件永远不会被覆盖或删除。
- **rime 的两个目录**：shared data = APK assets 解出来的 `<deviceProtectedDataDir>/usr/share/rime-data`（`RIME_DATA_DIR` 编译期宏 + 运行时 `StandardPaths::locate(Data, "rime-data/default.yaml")` 定位）；user data = `getExternalFilesDir(null)/data/rime`（由 `native-lib.cpp` 的 `setenv("FCITX_DATA_HOME", <extData>/data)` + `XDG_DATA_HOME` 决定）。
- **fcitx 环境变量**全在 `native-lib.cpp:522-546`（`LANG`/`FCITX_LOCALE`/`HOME`/`XDG_DATA_DIRS`/`FCITX_CONFIG_HOME`/`FCITX_DATA_HOME`/`FCITX_ADDON_DIRS`/`XDG_*`）。
- **rime-data 资源清单**在 `app/src/main/cpp/CMakeLists.txt:52-69`（default.yaml、essay、prelude、luna-pinyin、stroke），`COMPONENT prebuilt-assets`；`app/build.gradle.kts` 的 `generateDataDescriptor { symlinks.put("usr/share/rime-data/opencc", "usr/share/opencc") }` 建软链。
- **saved-instance Bundle 有硬上限**：Activity stop 时整份 Bundle 经 Binder 交给 system_server，
  **整个进程**共享约 1MB 事务预算，超了就是 `TransactionTooLargeException` 硬崩（见第 3.4 节
  `fca0b3e5`）。**任何"整份用户配置"都不要放进 `onSaveInstanceState` 或 Intent extra**，改用
  `LayoutDraftStore` 那套"文件 + Bundle 只放文件名"。现存需要留意的同类写法：
  `KeyEditorActivity` 的 `draft_key_data`（单个按键，正常几 KB，仅极端巨大 MacroKey 有理论风险）、
  `ButtonsCustomizerActivity` 的 `draft_buttons_config`（按钮表，很小）。

---

## 6. 用户日志的读法（这批日志很有用，别只看栈顶）

用户导出的 logcat 带 `--------- Device Info` / `Crash stacktrace` 头，正文是完整 logcat，**崩溃点
之前的时间线才是定位依据**。已归档在仓库根的 `日志/`（未跟踪，不要提交）。

- `fca0b3e5` 就是靠 `Bundle stats: draft_layout_json [size=537176]` 这一行 + 崩溃前 0.6s 的
  `KeyEditorActivity` 启动记录定位的：栈顶只说 `activityStopped` 失败，说不出为什么。
- 小米设备的固定噪声，**不是本 app 的问题，直接跳过**：`getMiuiFreeformStackInfo ... null`（每帧一条）、
  `ContentCatcherManager: failed to get ContentCatcherService`、
  `SettingTrigger: NoSuchFieldException: No field mContentExtensionEnabled`、`RenderInspector` 超时警告。
- 有用的自家 tag：`FcitxColdStart`、`[main] FcitxInputMethodService`、`FcitxClipboardSync`、
  `LayoutDataManager`、`LayoutEditor`、`LayoutDraftStore`。

---

## 7. 建议的下一步顺序

1. 向用户确认是否给 `ci.yml` 恢复 "Run JVM unit tests" 步骤（`./gradlew :app:testFxDebugUnitTest`，
   注意**不是** `...Release...`，理由见第 2 节末尾）——现在 `app/src/test/` 下 15 个测试文件在 CI
   里从未被编译或执行，绿灯不覆盖它们。**等他点头**。
2. 用户确认后再决定要不要删 `backup/fx2-rime-fusion-pre-merge` 与 `backup/fx2-rime-fusion-pre-rebase-20260904`。
