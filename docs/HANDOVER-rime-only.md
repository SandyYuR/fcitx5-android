# 交接报告 — fx-rime-only（靓企鹅·中州韵，rime 专版）

> 本文写给接手的下一个代理。仓库 `SandyYuR/fcitx5-android`（fcitx5-android 的 fork）。
> 目标分支 **`fx-rime-only`**（2026-09-07 由 `fx2-rime-fusion` 更名而来），计数基线 `3ad25fc9`。
> 日常操作规范见仓库根 **`agent.md`**（2026-09-08 新增，其信息源优先级**高于**本文）；本文的价值在 runbook 与故障经验，动态状态以 git 实测为准。
> 本文只描述"领先基线的全部改动"与"未完成的工作"。
>
> **2026-09-04 晚更新**：分支已把 `review-fx2-fixes` 的 6 个新提交 rebase 到本分支所有改动**之前**，
> 因此当时已有的 **37 个 rime 专版提交的 SHA 全部改写**、已 force-push。第 2 节有新旧对照与新 SHA。
> **2026-09-05 更新**：已同步当前工作区路径、worktree、未跟踪文件和分支状态；此前已解决的事项不再列入待处理问题。
> **2026-09-06 更新**：① 工作区重组——主仓库移入 `D:\GitHub\fx2-rime\fx2-rime-fusion\`，项目根下新增三个引擎相关 fork 的本地克隆，`日志/` 移出 git 工作区；② CI 的 rime 来源从 fxliang fork 换成 **SandyYuR 自己的 fork**（fcitx5-rime 已合并上游最新，prebuilt 保持 fxliang 终态快照），见第 0 节新表格；③ **rime 引擎更新流水线打通**——librime 从 `1.17.0-1d0df6e` 更新到 `1.17.0-3cbe4af`（上游最新，fxliang 补丁全保留），完整 runbook 见**第 0.5 节**。
> **2026-09-07 更新**：修复「切走再切回输入法，键盘好几秒弹不出来」——IME 退出路径改为直接调 `saveWithoutRime()`，不再触发 Rime 全量用户数据同步（见 3.4 节 `92561244`）。
> **2026-09-09 更新**：① prebuilder 合并 fxliang 3 个新提交（`ad491c7`，**0.5.4 节**新 runbook 实录），新引擎已构建落地 `prebuilt@9aa8104c`；② fcitx5-rime 当日晨已合并官方 5.1.16（`e74ddb6`，官方 updateUI 修复替代 `ce4c038` 定制，见第 0 节补注）；③ 主仓库新增 11 提交（`0a082669..a8c28d5a`），分支更名 `fx-rime-only`、恢复 nightly release、新增 `agent.md` 操作指南；④ librime 上游另有 2 个 streaming_chord 新提交待下次引擎更新（见 0.5.4 末尾）。
> **当前快照**：`HEAD = origin/fx-rime-only = a8c28d5a`，相对基线 `3ad25fc9` 领先 **177** 个提交；`fx2` 分支已于 09-07 重建推进至 `3ec76d37`（见第 2 节注），"fx2 保持不动"不再是事实。

---

## 0. 上手前必读（会踩的坑）

| 事项 | 说明 |
|---|---|
| 目录布局（2026-09-09 实测） | `D:\GitHub\fx2-rime\fx2-rime-fusion` 是主仓库（检出 `fx-rime-only`）；`fcitx5-rime/`、`prebuilt/`、`prebuilder/` 是引擎 fork；`librime-src/` 是补丁工作台；`日志/` 当前 9 个文件（8 txt + 1 json）。分别在目标仓库运行 `git status`。 |
| worktree | 唯一 worktree：`D:/GitHub/fx2-rime/fx2-rime-fusion` → `fx-rime-only`，与 `origin/fx-rime-only` 同步（目录名沿用旧分支名，未改）。没有第二个 worktree，不要自行新增或切换到同一分支。 |
| 三个 fork 的分工与更新方式 | 当前远端（2026-09-09 实测）：`SandyYuR/fcitx5-rime@e74ddb6`（官方 5.1.16 基线 + fxliang 全部定制，`b90bd7ca` 已在血统内）、`SandyYuR/prebuilt@9aa8104c`（09-09 新引擎）、`SandyYuR/prebuilder@ad491c7`（09-09 合并 fxliang 3 提交）。本地 remote-tracking ref 可能过时，使用前先 fetch（断连时走 SSH，见 0.5.3 第 8 条）。 |
| **rime 引擎更新流水线（2026-09-06 打通，09-09 第二次实战）** | 引擎 = `SandyYuR/prebuilt` 里的 `librime.a`，由 **`SandyYuR/prebuilder`** 的 CI（`ci.yml`，手动 `workflow_dispatch` 触发，约 15-90 分钟）构建并自动推回 prebuilt（"Auto update" 提交，bot 身份）。已配置 `BOT_TOKEN` secret（token 轮换后要重配）。**完整操作手册见第 0.5 节 runbook + 0.5.4 第二次实战**。当前引擎（09-09，`prebuilt@9aa8104c`）：**librime 1.17.0-3cbe4af + fxliang 补丁集**（tabs + syllabifier 缓存 + **para deploy 词典并行编译** + **userdict 缓存重写/跨 session 修复**）。版本号不变（pin 未动），主仓库 `librime.json` 无需更新，下次主仓库构建自动携带（prebuilt 浮动 master）。 |
| **CI 构建的 rime 来源** | `prepare_personal_build.sh` fetch/checkout SandyYuR 的 fcitx5-rime 与 prebuilt master（均浮动）；fcitx5-rime 适配层现为 `e74ddb6`（官方 5.1.16 + fxliang 定制）；主仓库 gitlink只是占位。`.gitmodules` 仍写官方 URL，license website 仍写 fxliang，属于元数据残留。 |
| 日志文件 | `D:\GitHub\fx2-rime\日志\` 当前 **9** 个文件（8 txt + 1 json）；新增三份与近期修复对应：`双击斜杠崩溃...09-08`（→`e0816fa0`）、`横屏状态切换悬浮...09-08`（→`c0a23af1`）、`剪贴板搜索框数字...09-09`（→`a8c28d5a`）。部分早期日志已移除或改名。 |
| 子模块未初始化 | 主仓库工作区里 `lib/fcitx5/src/main/cpp/fcitx5`、`plugin/rime/src/main/cpp/fcitx5-rime` 等仍是空 gitlink。fcitx5-rime 的源码看 `D:\GitHub\fx2-rime\fcitx5-rime\`；fcitx5 核心与 prebuilt 的其他内容仍可用 jsDelivr：`https://cdn.jsdelivr.net/gh/<owner>/<repo>@<sha>/<path>?x=N`（`?x=N` 绕缓存；raw.githubusercontent 拉大文件会超 30s 超时）。prebuilt 也有本地克隆 `D:\GitHub\fx2-rime\prebuilt\`。 |
| grep 工具 | `app/src/main/play/listings/en-US/graphics/icon/icon.png` 的失效符号链接已修正为指向 `app/src/fx/res/mipmap-xxxhdpi/ic_launcher.png`，现在可以从仓库根目录搜索。若以后再次出现 `os error 2`，先检查该链接目标是否仍存在。 |
| read 工具 | `offset`/`limit` 必须是明确数字（传 undefined 会报 "binding arguments must be lossless JSON"），`limit` ≤ 2000。 |
| GitHub API 返回会被截断 | `/actions/runs` 的 JSON 超 100000 字符会 `Unterminated string`。用 `per_page=3` 或正则抽 token，别整体 `JSON.parse`。 |
| 沙箱内 git 网络的坑 | 首选工作区外 `D:\GitHub\fx2-rime\ssh-push.ps1`（Windows OpenSSH）；备选 `push-with-gcm.ps1` 或 `api-push.mjs`。 |
| CI 失败时的调试方法（2026-09-06 两次实战） | ① `Invoke-RestMethod`/curl 都因 schannel 凭证问题不可用，**拉 API/日志用 node**：`GH_TOKEN`（GCM exe 取）+ fetch，重定向要手动跟随；② annotations API 只给任务级摘要，`> Task :app:installProjectConfig FAILED` = `installProject<Config>`（`FcitxComponentPlugin.kt:76` 动态拼名，构建 `generate-desktop-file` cmake target）；③ **CMakeBuildInstallTask 的 providers.exec 会吞掉 cmake/ninja 输出**，真实编译错误只在完整 job 日志里（`actions/jobs/<id>/logs`）；④ 本机无编译器/msgfmt，改完 C++/po 后的自检：po 用严格解析器查重复条目（msgfmt 对重复 msgid 是硬错误）、C++ 用括号平衡检查 + **与两个父版本逐字符对照结尾标点**（`};);` 这类宏结尾模式最容易抄错）。 |

### 2026-09-06 合并上游后两次 CI 失败的记录（已修复）

1. **run #220 `installProjectConfig` 失败**：po 合并脚本的分块假设不成立——fxliang 的 ja.po 里部分条目之间只有一个换行（无空行），脚本把两条目粘成一块，上游同名条目再独立插入 → "All"/"Clear" 重复，msgfmt 硬错误。修复：`b88c57d` 删重复条目。
2. **run #221 `buildCMakeRelWithDebInfo` 失败**：手动解 `rimeengine.h` 冲突时把 `FCITX_CONFIGURATION` 块最后一个成员结尾写成 `false});`，丢了成员声明的分号（正确是 `false};);`）。run #220 死在 msgfmt 没暴露它。修复：`dad45bc`。
3. 教训：**解完冲突必须对照两个父版本（`git show <sha>:<path>`）核对合并块的每一个结尾标点**，"结构看起来对"不算数；po 合并后要跑重复检查；CI 挂了先拉完整 job 日志再动手，别靠猜。

### 2026-09-07 九键音节选择器首次点击无响应（已修复：addon `ce4c038`）

**现象**（日志 `日志/音节选择器...2026-09-07T08_58_32Z.txt`）：打完字后点 tab 无反应（连点 29 次零响应），点一下"清除"就恢复。同日志里 BackSpace 后 tab 又能用。

**根因**：合并上游时，`8bb9234`（ascii 图标提交）顺手删除了 `rimestate.cpp` updateUI 里的 `emptyExceptAux` 逻辑，把面板序列化条件 `!keyRelease || !oldEmptyExceptAux || !newEmptyExceptAux` 收窄为 `!keyRelease`。上游自己没有 tab 功能所以无害，但 **fxliang 的 tab 功能隐藏依赖 release 时的序列化**：

- 每次 keyEvent（press 和 release）都跑 `updateUI(ic, isRelease)`，它总是 `setCandidateList(make_unique<RimeCandidateList>(...))` 换一个新列表；
- 新列表的 `tabLabels_/tabSpans_` **只在序列化时**（frontend `updateInputPanel()` → `tabActions()`）填充；
- press 走 `updateUI(false)` 会序列化（tabLabels_ 填好）；release 走 `updateUI(true)` 不 reset 面板但**换了列表且不再序列化** → UI 显示的还是旧 tabs，实际列表的 tabLabels_ 是空的；
- 点 tab（id≥0）→ `RimeState::selectTab` 的 `tabId >= labels.size()` **静默 return**（连 updateUI 都不调，日志零输出）；点"清除"（id=-1）→ `clearTabs()` 强制 `updateUI(false)` → 序列化 → tabLabels_ 重填 → 恢复。
- 日志佐证：BackSpace 只发 press 不发 release（列表未被污染）→ tab 能用；数字键（宏路径）press+release 齐全 → release 污染列表 → 全部点击死掉。

**修复**（`SandyYuR/fcitx5-rime@ce4c038`）：恢复"面板有内容（新旧任一非空）时 release 也序列化"的判定 + release 时清除残留 aux 的补全（用 e84ffa1 修正过的判空，含 clientPreedit）；上游 `lastMode_` 无条件显示 IM 信息的改进保留。

**教训（合并上游的暗礁）**：git 自动合并成功 ≠ 语义无损。上游删掉的"看似无关"代码可能正是本分支特性的隐藏依赖——尤其是这种"A 创建状态、B 消费状态"跨函数的时序依赖，git 完全看不出来。**合并 fcitx5-rime 上游后必须实测：打字→点 tab→选词全链路**（本次 CI 绿灯只证明能编译）。

**2026-09-09 后续**：`ce4c038` 的定制修复已被 **`e74ddb6`**（当日晨合并官方 fcitx/fcitx5-rime `ce38ca9`，版本 **5.1.16**）替代——上游 `8c952c1` "Further clean up the updateUI code with key release (#169)" 官方实现了 release 无条件序列化，`rimestate.cpp` 净删 21 行定制逻辑（`6737036..e74ddb6` 共 5 文件 +14/-35）。fxliang fork 全部定制（含 `b90bd7ca` schema_id info，07-30）均已在 `e74ddb6` 血统内，无待合并增量；官方与 fxliang 两侧上游均无新提交。⚠️ 官方重写 updateUI 后，**"打字→点 tab→选词"全链路真机回归仍未做**——最新主仓库 CI（run 34316104477，`a8c28d5a`，05:44 UTC）已自动采用 `e74ddb6`（浮动来源，晚于其推送 41 分钟），绿灯同样只证明能编译。

**推送通道备用**（github.com:443 曾多次断连，每次约 10 分钟）：**首选工作区外 `D:\GitHub\fx2-rime\ssh-push.ps1`**（走 SSH：关键发现是 mingw git 对含空格/反斜杠的 `GIT_SSH_COMMAND` 会用 MSYS `sh.exe -c` 包装（沙箱内必死 `couldn't create signal pipe`），而**正斜杠单 token 路径 `C:/Windows/System32/OpenSSH/ssh.exe` 让 git 直接 exec Windows 原生 ssh.exe**，绕开一切 MSYS；本机 `~/.ssh/config` 已配 ssh.github.com:443 + ed25519）；**备选 `api-push.mjs`**（走 api.github.com 的 Git Data API 推单文件提交，blob SHA 与本地比对确保内容一致，适合多文件改动时逐文件推或 github.com 整个不可达时）；dispatch 别忘 `DISPATCH_REF=fx-rime-only`（默认 master 会 422；09-09 起分支名由此前的 fx2-rime-fusion 更名）。SSH/API 推完的网络恢复后 `fetch + reset --hard origin/master` 对齐（提交 SHA 与本地不同但 tree 相同）。

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
2. **librime 上游待吃进 delta**（截至 09-09）：`74a7467e` + `74db0d18`（`streaming_chord_processor` 流式并击处理，全部在新文件 + `gears_module.cc` +3 行），**不碰任何补丁文件**——下次引擎更新直接 `git update-index --cacheinfo` bump gitlink 即可，四补丁预计干净应用，照 0.5.2 第 1-2 步先实测确认。

---

## 1. 用户给的长期约定（必须遵守）

原话：**「全部做，从 fx2 分支复制到另一个分支，在新复制的分支上面改动，每改好一处就推送上去一次，手动触发一次 ci，但是不要 release」**

落实为每次改动的固定流程：

1. 一处改动 = **一个独立提交**，提交信息用**中文**；
2. 推送 `fx-rime-only`（2026-09-07 由 `fx2-rime-fusion` 更名）；代码/构建改动会触发 CI，纯 `*.md` / `docs/**` / `.gitignore` 被 paths 排除；必要时再手动 `workflow_dispatch`；
3. 确认 CI 绿 + 有产物；
4. ~~**绝对不要创建 release / tag**~~ **2026-09-07 起按用户后续要求恢复 nightly release**（`20604d6b`）：`fx-rime-only` 构建成功后自动创建时间戳 nightly prerelease（`nightly_release` job，`needs: build_commit`，`if: github.ref == 'refs/heads/fx-rime-only'`）；**手动正式 release / 语义化 tag 仍禁止**；
5. ~~`fx2`、`review-fx2-fixes`、`backup/*` 全部**不要动**~~ `fx2` 已于 09-07 被重建推进（见第 2 节注），`review-fx2-fixes`/`backup/*` 的 refs 已不存在；对这些分支仍无明确要求不碰；
6. 已 push 的提交**不要 amend**，新改动开新提交。

Git/发布纪律的权威版本是 `agent.md` 第 11 节：**每次任务的 push/外部 workflow 触发都要以当次用户的明确要求为准**，不沿用历史授权。

CI 事实（2026-09-09 实测 `ci.yml`）：
- workflow 名 `Commit CI`，两个 job：`build_commit`（`ubuntu-22.04` × `arm64-v8a`）+ `nightly_release`（仅 `fx-rime-only`，见上）；
- push 触发**所有分支**（`branches: '*'`，tags 忽略语义化版本号）；
- 构建命令 `./gradlew :app:assembleFxRelease`，约 10 分钟；
- 产物 artifact 名 `app-ubuntu-22.04-arm64-v8a`，路径 `app/build/outputs/apk/fx/release/*.apk`；
- 纯 Markdown / `docs/**` 提交不触发 CI；当前 CI 只 assemble，不执行 JVM 单测。

---

## 2. 分支现状

2026-09-09 实测：`HEAD = origin/fx-rime-only = a8c28d5a`；相对 `3ad25fc9` 领先 **177** 个提交；工作树干净。分支 09-07 由 `fx2-rime-fusion` 更名为 `fx-rime-only`（更名无提交痕迹，旧 refs 已随删除消失）。

历史范围（左开右闭）：`3ad25fc9..85de19be` 95 个；`85de19be..3ec1f6b2` 6 个；`3ec1f6b2..4dff487a` 65 个，其中 `ef320bfb..4dff487a` 18 个；`4dff487a..a8c28d5a` 11 个（2026-09-07~09-09，见下表）。

### `01c1070e` 后的 28 个提交

| 提交 | 说明 |
|---|---|
| `5103b515` `e0733388` `6da44099` `441650a6` | 交接文档同步与口径修正 |
| `83890f1a` | 宏动作选择器修正 |
| `77be0fb8` `ef320bfb` | 语言键 Shift 与 50ms 延迟 |
| `fca0b3e5` | 草稿文件化，修 TransactionTooLarge |
| `a352280b` `bf645157` | 中州韵设置入口与列表入口清理 |
| `a8ab254e` `716accb3` `a47abdf3` | 交接、fork、CI 排障文档 |
| `eea62a38` | Play 图标链接修复 |
| `4e1990e9` | CI 改取 SandyYuR fork |
| `92208fd1` | librime 元数据更新 |
| `a27c9aad` `72478967` | 引擎 runbook |
| `b65fbb4d` `f961a85c` | 数字层记忆、?123/BACK 历史修复 |
| `88f7115d` | BACK 测试注释 |
| `92561244` | IME 退出跳过 Rime 全量同步 |
| `4b5f50c7` `1320214b` | 修复证据链文档 |
| `7557c2b4` | 九键 tab 根因文档 |
| `4ee31e44` | 草稿路径穿越硬化 |
| `bab4558c` | 简繁中文名改为“靓企鹅.中州韵” |
| `4dff487a` | Windows OpenSSH 推送说明 |

**rebase（2026-09-04 晚）**：应用户要求把 `review-fx2-fixes` 的 6 个提交插到本分支改动之前，做法
`git rebase --onto review-fx2-fixes 85de19be fx2-rime-fusion`，随后
`git push --force-with-lease`。旧 tip `281082cb` 曾保存于 backup ref；当前已无该 ref，但对象仍可按 SHA 读取。
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

### `4dff487a` 后的 11 个提交（2026-09-07 ~ 09-09）

| 提交 | 说明 |
|---|---|
| `0a082669` `136c23a1` | 交接文档更新并更名 rime-only、按历史改动修订 |
| `20604d6b` | **恢复 rime-only nightly release**（ci.yml `nightly_release` job +81 行；README 重写；推翻旧约定"不要 release"，见第 1 节） |
| `e51afd88` | 应用名定为 **靓企鹅·中州韵**（间隔号 ·，替换 `bab4558c` 的句点版本）+ README 功能说明 |
| `32abc890` | Rime-only 用户手册（`RIME_ONLY_USER_GUIDE_zh-CN.md`）与代码/性能审阅报告（`*_REVIEW_REPORT_2026-09-07.md`） |
| `aeb6c9a4` | 设置里"用户数据目录"点击改开**应用 DocumentsProvider 根目录**（避免 DocumentsUI 打不开 Android/data 深层路径），长按保留物理目录入口；**数据未搬迁**，只改入口 |
| `f892aa4f` | **`agent.md` 操作指南**：信息源优先级（本文档降至第 4 位）、验证门禁矩阵、引擎更新纪律、Git/发布纪律 |
| `4c51ca13` | 布局编辑器拖拽松手后按键不归位（停在原地错位显示）修复 |
| `e0816fa0` | 双击斜杠崩溃修复：推迟 `onLayoutCompleted` 内候选窗口跟随更新（日志 `双击斜杠崩溃...09-08`） |
| `c0a23af1` | 横屏切换悬浮键盘错乱修复：延迟刷新 + 按配置失效尺寸缓存（日志 `横屏状态切换悬浮...09-08`） |
| `a8c28d5a` | **剪贴板历史实时搜索**（工具栏入口 + 辅助栏结果；日志 `剪贴板搜索框数字...09-09`） |

以上全部 CI 绿；最新 run 34316104477（`a8c28d5a`，09-09 05:44 UTC）已自动采用 fcitx5-rime@`e74ddb6` 新适配层（浮动来源，晚于其推送 41 分钟）——但 updateUI 被官方重写，tab 全链路真机回归仍待做（见第 0 节补注）。

历史上存在 review/backup 分支；当前 `show-ref` 已无这些 refs。不要重建或破坏清理，需旧内容时按 SHA 查询。

**`fx2` 分支已推进（2026-09-07，旧规则作废）**：`origin/fx2 = 3ec76d37`（push 触发 CI run 34101224455 绿）——内容为 fxliang:fx 合并线 + README 更新 + A~G 审查修复/perf 全套（与 `fx-rime-only` 对应部分**内容等价、SHA 不同**，是平行血统，`85de19be` 不在其内）+ 3 个 cherry-pick 通用修复：`0431cff8`（布局草稿落盘，对应 `fca0b3e5`）、`002a4062`（IME 退出跳过 Rime 同步，对应 `92561244`）、`3ec76d37`（数字层记忆释放，对应 `b65fbb4d`）。`3ad25fc9` 仍只是**计数基线**，"fx2 必须保持不动"不再是事实；动 `fx2` 前仍需用户明确要求。

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

## 3. 领先基线的核心改动（截至 `4dff487a` 的 166 提交快照；其后 11 个见第 2 节表）

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

新增 `LayoutDraftStoreTest.kt`；经 `4ee31e44` 扩展后当前 **18 例**，但 CI 不运行 JVM 单测。

**后续安全硬化（`4ee31e44`）**：只接受 `draft-[A-Za-z0-9-]{1,64}.json`；所有文件访问校验 canonical parent；prune 不碰 foreign 文件。新增 3 例覆盖路径穿越、绝对路径和 prune 边界。

**与 `77be0fb8`（语言键 Shift）无冲突**，已核对：文件零重叠；`77be0fb8` 删掉的
`langSwitchKeyBehavior`/`LangSwitchBehavior` 全仓库零引用；保留的 `showLangSwitchKey` 仍被
`LayoutDataManager` 与编辑器的 `getDefaultLayout(showLangSwitch = true)` 使用；`LanguageKey` 的
KeyDef 与 JSON 序列化都没动，`77be0fb8` 只改按下它的运行时行为；编辑器预览键盘不接
key action listener，点预览不会触发 `sendStandaloneShiftTap`。且 `77be0fb8` 是 `fca0b3e5` 的祖先，
run #215 构建的就是两者合并后的树。

#### `b65fbb4d` 发送后跳回数字盘：宏 layer to 离开数字层时释放手动数字布局记忆（用户日志定位，2026-09-07）

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

**后续修正（`f961a85c`，2026-09-07，用户日志 `返回图层...09_22_07Z` 定位）**：`?123` 跳数字盘与 BACK 的
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

`NumericLayoutOverrideControllerTest.kt` 共 6 例；`f961a85c` 的 KeyboardWindow layerHistory 集成路径仍无测试。run #232 仅证明包含该改动的发布树可编译。

#### `92561244` IME 退出不再触发 Rime 全量同步（用户日志定位，2026-09-07）

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

---

### 3.5 用户可见名称（`bab4558c`，09-07 `e51afd88` 更新）

简繁中文应用名为 **“靓企鹅·中州韵”**（`e51afd88` 起用间隔号 ·，此前 `bab4558c` 版本为句点。）；默认英文及 de/es/ja/ko/ru 保持 `Fcitx5.fx.rime`。Play 中文 listing 与旧 release note 仍写“小企鹅输入法”，恢复发布前需统一。

---

## 4. 历史四项任务

用户原话：
> 「去掉附加组件里面的 Android 英文键盘，输入法选择器，拼写，unicode。输入法安装就是默认启用 rime，且只有 rime 可用，其他无关组件都清除。默认键盘就是 rime 的 default 键盘，语言切换键改成按下发送一次 shift 点击事件，利用 ascii mode 来做到切换中英文输入」

追加：
> 「快速输入组件也去掉」

拆成四项：

### ✅ 任务 1 — 清除无关组件（当前 SHA：`072c0e87` + `cefc481b`）

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

### ✅ 任务 2 — 首次启动只启用 rime（当前 SHA：`dc0db868`）

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
  `LayoutDraftStore` 那套“文件 + Bundle 只放文件名 + 名称白名单/canonical path”。现存需要留意的同类写法：
  `KeyEditorActivity` 的 `draft_key_data`（单个按键，正常几 KB，仅极端巨大 MacroKey 有理论风险）、
  `ButtonsCustomizerActivity` 的 `draft_buttons_config`（按钮表，很小）。

---

## 6. 用户日志的读法（这批日志很有用，别只看栈顶）

用户导出的 logcat 带 `--------- Device Info` / `Crash stacktrace` 头，正文是完整 logcat，**崩溃点之前的时间线才是定位依据**。当前 `日志/` 共 9 个文件（8 txt + `TextKeyboardLayout.行之26.json`）；新增 `双击斜杠崩溃...09-08`、`横屏状态切换悬浮...09-08`、`剪贴板搜索框数字...09-09` 分别对应 `e0816fa0`/`c0a23af1`/`a8c28d5a` 三次修复；“切输入法”“跳数字盘”等早期文件已不在目录中，以下文历史记录为准。

- `fca0b3e5` 就是靠 `Bundle stats: draft_layout_json [size=537176]` 这一行 + 崩溃前 0.6s 的
  `KeyEditorActivity` 启动记录定位的：栈顶只说 `activityStopped` 失败，说不出为什么。
- 小米设备的固定噪声，**不是本 app 的问题，直接跳过**：`getMiuiFreeformStackInfo ... null`（每帧一条）、
  `ContentCatcherManager: failed to get ContentCatcherService`、
  `SettingTrigger: NoSuchFieldException: No field mContentExtensionEnabled`、`RenderInspector` 超时警告。
- 有用的自家 tag：`FcitxColdStart`、`[main] FcitxInputMethodService`、`FcitxClipboardSync`、
  `LayoutDataManager`、`LayoutEditor`、`LayoutDraftStore`。

---

## 7. 建议的下一步顺序

1. 确认是否恢复 `testFxDebugUnitTest`；当前 **16 个测试文件、111 例**不在 CI 覆盖内，并应补 KeyboardWindow layerHistory 集成测试。
2. 决定是否同步 Play 中文标题及 fcitx5-rime license/.gitmodules 来源元数据。
3. 使用外部 fork 前先 fetch 并核对 `fcitx5-rime@e74ddb6`、`prebuilder@ad491c7`、`prebuilt@9aa8104c`（09-09 实测值）。
4. 当前没有 backup/review refs 可删；未经明确要求不要破坏旧对象、reflog 或 tags。
5. **真机回归待办**（两次上游合并都欠着）：① `e74ddb6` 官方重写 updateUI 后"打字→点 tab→选词"全链路；② 新引擎 `9aa8104c`（para deploy + userdict 缓存重写）装机冒烟——主仓库下次构建自动携带，无需改动；装后验证词典部署并行与跨 session 用户词缓存。
6. librime 上游 2 个新提交（streaming_chord）待下次引擎更新吃进，见 0.5.4 末尾——不碰补丁文件，预计只需 bump gitlink。
