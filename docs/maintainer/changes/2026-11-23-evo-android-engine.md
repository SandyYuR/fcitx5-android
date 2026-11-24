# EVO Android 构建整理 · 2026-11-23

本次核查 `SandyYuR/librime-evo` 在同步 evo 上游后 Android 引擎 CI 失败，以及纯上游 `main` 无法直接手动启动该工作流的问题。首次按用户授权，以 amzxyz 当前 `main` 为基线重建 `build/android-engine-variants`，仅添加 Android 引擎构建与 provider APK 封装配方，Lua 与 Octagram 插件均使用 amzxyz 的仓库。同日后续用户反馈部署问题，要求恢复独立的部署失败传播修复并重新构建 APK；下面按阶段保留各次提交、范围与验证身份。

## 固定来源

| 对象 | 本次核查来源 |
| --- | --- |
| 整理前 build 分支 | `5aac2b4c540f93d7099cd12e29a064b19f3e11b3` |
| 重建后 build 分支 | [`b456baefa9ab46a77c4b2b9d13eccd55451d20d8`](https://github.com/SandyYuR/librime-evo/commit/b456baefa9ab46a77c4b2b9d13eccd55451d20d8) |
| fork `main` 与 amzxyz `main` | 均为 `720a0c3a55916335295fda19e56e39eb52844b6c` |
| Lua 插件 | `amzxyz/librime-evo-lua@bc9d39f0f6bdf7b552f342c2cfceb1fbc624a729` |
| Lua 插件固定运行时 | `LuaJIT/LuaJIT@14d8a7a27dc8c626ab9e7c7e9e50b6df6def4f03` |
| 新 Octagram 插件 | `amzxyz/librime-evo-octagram@f9652e7ed0e699d55588418ae492edddeaf28360` |
| 新 Octagram 源码包 SHA-256 | `8cb977805982d73ebba4eadb6d91f5c740251b747c71664b1b8e97c51bf12619` |
| 文档准备基线 | `docs@41dd2751e`（本次文档提交基线） |

重建提交的唯一父提交为上述 `720a0c3a55916335295fda19e56e39eb52844b6c`。已通过 SSH 和绑定旧远端完整 SHA 的 `--force-with-lease` 重写远端 `build/android-engine-variants`；推送前后核对 fork `main` 均保持该上游 SHA。本次内容是重新选择纯上游核心并修订构建配方，不是内容树不变的提交归并：相对上游只新增 20 个文件，全部位于 `.github/workflows/android-engine.yml` 和 `downstream/`，核心与桌面流程 diff 为零。

本机共享仓库的恢复引用 `refs/recovery/evo-android-20261123-before-rewrite` 固定指向旧 build 的 `5aac2b4c540f93d7099cd12e29a064b19f3e11b3`，旧 `37a694a` 和原源码仍由该引用可达。旧新映射在 `D:/GitHub/fx2-rime/.dsh-tmp/evo-ci-review-20261123/branch-rewrite.json`，最终相对上游的差异在同目录 `final-android-additions.patch`。恢复引用不是标签，记录与补丁仅保留本机，未上传；未修改发布标签。

重建提交的 Android 引擎 CI 已成功；下表按实际验证范围登记。文档编辑本身不代表已经提交或部署站点。

## 上游更新后为什么构建失败

整理前的 [Android CI 45169407573](https://github.com/SandyYuR/librime-evo/actions/runs/45169407573) 在 **Build ARM64 engine and pinned dependencies** 步骤失败。GitHub API 可核查步骤结论与退出状态，本次尚未取得该 job 的完整原始日志；下面的兼容性结论来自固定 SHA 的实际核心和插件源码。

上游 [`210489777bc471f6fc6da617d4c4176c045ba1e8`](https://github.com/amzxyz/librime-evo/commit/210489777bc471f6fc6da617d4c4176c045ba1e8) 在 `Grammar::Component` 中增加纯虚函数 `CreateContextual(Config*)`，并让 Poet 分别创建整句和前文语法评分。Android 来源锁仍使用 `lotem/librime-octagram@57d18b9f58e5284bd891d559f6bdd16cf60341e9`；其 `OctagramComponent` 只实现 `Create()`，创建该抽象组件时无法编译。

同次上游更新已在普通 CI、Release CI、Docker 和 README 中使用 `amzxyz/librime-evo-octagram`，Android 下游来源锁没有同步。新的 amzxyz 插件实现 `CreateContextual()`，并采用 Evo 的 `Rime::EvoGrammar/2` `.dlm` 格式。本轮更新 `repository`、完整提交、codeload URL 和实际下载摘要；现有 Octagram 补丁只添加路径映射与 Boost 编译定义，真实校验与试贴通过，未修改插件评分算法。Lua、LuaJIT 和 spdlog 的固定提交已与上游模块登记一致，继续保留。

## 工作流与源码分开检出

重建后的 build 分支只添加 `.github/workflows/android-engine.yml` 与 `downstream/`，核心 `src/`、测试和上游桌面构建文件沿用 amzxyz `main`。Android 配方仍固定 NDK `28.0.13004108`、CMake `3.31.6`、最低 API 23，构建 ARM64 自包含 DSO 和承载同一字节载荷的 provider APK，验证 ELF、动态依赖、导出、许可证和校验清单。

`main` 没有 Android 工作流文件；YAML 中列出 `push.branches: main` 不会把工作流复制过去。手动运行在默认 build 分支选择工作流，再以 `source_ref=main`、标签或完整 SHA 选择引擎输入。配方和引擎源码分别检出，`--source-root` 将源码导出和模块提交检查绑定到引擎 checkout；依赖锁、补丁与封装逻辑仍来自配方 checkout。

自动推送运行构建事件提交。本仓库 `main` 的 push Commit CI 成功完成后，默认 build 分支上的 `workflow_run` 构建该次 `head_sha`。job 条件同时要求 `conclusion=success`、原事件 `event=push`、`head_branch=main` 和 `head_repository.full_name=github.repository`，排除失败、PR 与其他仓库来源。这样同步纯上游 `main` 也可自动得到 Android 构建，不需要改动 `main` 的文件。清单记录实际引擎 SHA、源码树及单独的 `android_recipe_commit`，产物文件名和 artifact 名称依据实际引擎提交，避免把配方 SHA 当作引擎 SHA。

工作流权限仍为 `contents: read`，签名条件仍依据工作流执行分支。build 分支的手动 `source_ref=main` 和 `workflow_run` 运行生成临时测试签名，不发布 Release，不代替稳定发行证书。不同运行的临时证书不能原位升级覆盖；安装、替换或授权需单独处理，本次未安装 APK、清空应用或更改用户方案与模型。

## `37a694a` 的作用与本次处理

[`37a694a7b729ba026df5403d48f0e55fc34e4b2a`](https://github.com/SandyYuR/librime-evo/commit/37a694a7b729ba026df5403d48f0e55fc34e4b2a) 是此前方案诊断发现的独立部署修复后续整理，不是 Android 打包机制的一部分。它在保留部分可读配置数据的同时记录并传播本轮加载、Link 和输出失败，让 `ConfigFileUpdate` 与 `WorkspaceUpdate` 返回失败，避免错误配置和缺少编译文件却报告 `deploy/success`。原始复现、设备检查与旧新历史映射继续保留在 [2026-11-12 EVO 方案排查](2026-11-12-evo-profile-diagnosis.md)，不因本次重建改写旧证据。

该修复不改变 APK 封装流程，也不是引擎可编译的必要条件。当前 amzxyz 上游仍未实现同等失败传播，它仍有独立修复价值。首次 `b456bae` 按用户当时“先同步上游，再只添加 Android 构建工作流”的范围不叠加此核心补丁；该次构建核心保持上游行为，不能把它的 APK 构建成功当作旧部署报告问题已经解决。后续按用户追加授权恢复补丁，见下一阶段记录。

## 首次重建验证范围

| 检查 | 已确认范围 |
| --- | --- |
| 远端基线 | fork `main` 与 amzxyz `main` 固定为同一完整 SHA |
| 分支重建 | 远端 build 已由 `5aac2b4…` 重写为 `b456baef…`；唯一父为上游 `720a0c3…`，main 未改变，核心与桌面流程 diff 为零 |
| Octagram 来源校验 | 实际下载并核对新源码包 SHA-256；固定补丁校验与真实试贴通过 |
| Android 配置 | NDK 28 ARM64 CMake 配置通过，`BUILD_TOOLS=OFF` 禁用插件 `build_grammar` 工具 |
| Python 契约回归 | 26 项实际执行，失败、错误和跳过均为零 |
| 工作流 Bash | 7 个实际 `run` 块通过 `bash -n` 语法检查 |
| 工作流静态验证 | 官方 actionlint 1.7.12 下载校验通过，实际运行退出码为 0，无诊断 |
| 新引擎完整构建与封装 | [`Android CI 45175872518`](https://github.com/SandyYuR/librime-evo/actions/runs/45175872518) 已完成且成功，API 核对 `head_sha=b456baefa9ab46a77c4b2b9d13eccd55451d20d8`；包含完整 native 构建、最终链接、ELF／导出／页面对齐／许可证审计、临时测试签名 provider APK、签名与校验清单检查及两项 artifact 上传 |
| 新核心 Commit CI | [`45175872636`](https://github.com/SandyYuR/librime-evo/actions/runs/45175872636) 在本次收尾时仍排队，未记为通过；APK 验证依据独立 Android 工作流 |
| `source_ref=main` 与 `workflow_run` 事件 | 尚未真实调度这两类事件；已验证独立源码 checkout 路径与模块固定提交，完成 actionlint 及事件条件审查，不记为事件验收通过 |
| 设备与完整方案 | 本轮未执行；旧测试夹具和设备结果保持原提交身份 |

GitHub API 确认以下两项 artifact 尚未过期。这些摘要对应 **Actions 上传 artifact 的外层 ZIP**，不是其中原 APK 或 DSO 的文件摘要；原载荷以各自 artifact 内的 `SHA256SUMS` 和 manifest 核对。

| Actions artifact | ID／字节数 | 外层 ZIP SHA-256 |
| --- | --- | --- |
| `librime-evo-arm64-apk-b456baefa9ab46a77c4b2b9d13eccd55451d20d8` | `14819337970`／`2401129` | `11b5a44cf1438ff0510faef5c3697d946b0604c89f2e9953cdeb1962c663a344` |
| `librime-evo-arm64-so-b456baefa9ab46a77c4b2b9d13eccd55451d20d8` | `14819207999`／`2471607` | `a3770e6e00ef4752f8cfa89b10cbe3069ba1557abb4bd8e7711b0b25f4765735` |

本次 API 结论与 artifact 原始元数据保存在本机 `D:/GitHub/fx2-rime/.dsh-tmp/evo-ci-review-20261123/android-run-final.json`、`artifacts-final.json`；未上传本机证据目录，也未安装新引擎。

引擎、Lua、词典和模型的实际方案兼容仍需分别验证，尤其不能用旧 `.gram` 模型代替新 `.dlm` 模型验证。

## 反馈后恢复部署失败传播修复

用户在试用首次重建的引擎后反馈再次出现部署问题，明确要求将 `37a694a7b729ba026df5403d48f0e55fc34e4b2a` 恢复提交到 `build/android-engine-variants` 并生成新的引擎 APK，由用户再次试用。本轮在 build 分支无冲突地重新应用该补丁，正常快进提交并经 SSH 推送为 [`d7eab4859953a2a7ea701505f1dab652c46b7ef4`](https://github.com/SandyYuR/librime-evo/commit/d7eab4859953a2a7ea701505f1dab652c46b7ef4)，父提交为首次重建的 `b456bae`，main 仍为 `720a0c3`。提交共八个文件：原修复的六个文件内容与 `37a694a` 完全一致，另更新配方 README 与来源 manifest 说明。本轮不重写此前的构建成功身份。

补丁涉及五个生产文件及 `test/config_build_result_test.cc`。五项原生测试覆盖：缺失引用导致编译失败但部分配置仍可读、维护任务发出失败通知、旧 schema 不能掩盖本次失败且有效配置重试成功、旧默认配置不能掩盖失败，以及编译输出写入失败。这里的作用是准确传播加载、Link 和输出结果，不会补齐方案缺失文件，也不会清空旧产物；Lua、词典和模型仍按用户方案与既有固定来源管理。

本次 build 引擎包含该独有源码补丁，fork main 继续保持纯上游。自动 push 构建使用本次 build 提交；手动复现该修复时，工作流执行分支与 `source_ref` 均应选择 `build/android-engine-variants`，或将 `source_ref` 固定为 `d7eab4859953a2a7ea701505f1dab652c46b7ef4`。填写 `source_ref=main`、以及 main 成功 Commit CI 后的自动构建，仍会生成不含该补丁的上游引擎，不能用来验收此次恢复。

| 本轮检查 | 当前实际结果 |
| --- | --- |
| 修复来源与范围 | 原六个文件与 `37a694a` 一致；README 和 manifest 登记 build 的独立补丁，未更换依赖、词典或模型 |
| Python 契约回归 | 26 项实际执行，失败、错误和跳过均为零 |
| 五项配置结果原生测试 | 普通 Linux `make test` 将覆盖新增测试，本次实际 CI 结果尚待确认，不沿用旧运行结果 |
| 新 Android CI | [`45181130420`](https://github.com/SandyYuR/librime-evo/actions/runs/45181130420) 初稿时仍进行中，未记为成功 |
| 新 Commit CI | [`45181130501`](https://github.com/SandyYuR/librime-evo/actions/runs/45181130501) 初稿时仍排队，未记为通过 |

设备与完整方案的成功部署由用户后续试用确认，本轮不安装提供 APK，不替换方案、词典或模型，不改变设备数据或授权。

### 用户日志中的本次实际部署原因

用户随后提供的日志经本机只读核查，明确指向缺少 `wanxiang_mixedcode.dict.yaml` 或可用的 `wanxiang_mixedcode.table.bin`，对应部署单元失败。该日志已统计为 `0 success / 1 failure`，任务汇总为三项中的两项成功、一项失败，并正确进入失败与 profile 回退路径；它不是旧 `37a694a` 针对的配置 Link 失败却仍报告成功的情形。

恢复失败传播补丁保留独立价值，但不会生成缺失词典，也不能据此承诺解决这次缺文件问题。需要依据该方案的完整文件清单准备实际词典来源或可用编译产物，再验证部署与真实输入。这里仅记录诊断结论和所指文件名，未上传、复制到站点或附加原始用户日志；原始资料继续保留在用户本机。

## macOS CI 的托管 runner 容量问题

首次重建提交 `b456bae` 的 [Commit CI 45175872636](https://github.com/SandyYuR/librime-evo/actions/runs/45175872636) 后续结论为 failure，具体问题是 [macOS ARM64 job 137697105487](https://github.com/SandyYuR/librime-evo/actions/runs/45175872636/job/137697105487) 被取消且没有执行任何步骤。GitHub API 注释明确记录：

> The job was not acquired by Runner of type hosted even after multiple attempts

另有容量提示：

> Due to capacity constraints, jobs targeting macOS arm64 runners may experience longer queue times.

同一轮 Intel macOS 任务及其他已执行的构建／测试任务均成功；fork main `720a0c3` 的两路 macOS 构建均成功，amzxyz 上游同一提交的 [Commit CI 45096710186](https://github.com/amzxyz/librime-evo/actions/runs/45096710186) 也整体成功。因此这次红色结论来自 GitHub 托管 ARM64 runner 没有分配成功，不能写成 macOS 源码编译或测试失败。无需据此改动 macOS 代码或工作流；后续重新运行或新提交会再次尝试获取 runner，本次恢复补丁的推送也会自然触发新的 CI。
