# 本会话构建版交付索引

当前产品为 **中州鹅**，`org.fcitx.fcitx5.android.fx.rime`，工作分支 `feat/direct-rime-prototype`，
[PR #4](https://github.com/SandyYuR/fcitx5-android/pull/4)。
操作入口见[用户手册](../manual/DIRECT_RIME_USER_GUIDE_zh-CN.md)，
开发入口见[维护手册](DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md)。

## 2026-10-19 日用反馈与合并整理

用户在 `9adb6949` 签名 Release 上反馈卡顿改善，随后确认“可以日常使用”。
共用外部 Rime 工作目录时出现过旧词和新学词不显示；强行停止其他输入法并重启后恢复，
用户确认无需改代码。这次处理补入用户手册，没有修改用户词典、学习选项或数据库。

本 PR 先收拢为三个交付提交，并整合默认分支 `92fd34ce` 之前新增的六项前端修复；
最后审阅追加一项依赖清理与静态链接核验，见[最终依赖审阅](DIRECT_RIME_FINAL_REVIEW_2026-10-19.md)；
10 月 20 日再补[密码框方案菜单修复](DIRECT_RIME_PASSWORD_SCHEMA_MENU_2026-10-20.md)，
随后完成[部署重载与发行标识调整](DIRECT_RIME_REDEPLOY_AND_IDENTITY_2026-10-20.md)，共六项功能交付提交；
另同步默认分支新增的低版本主题修复与 MD3 设置，冲突在功能分支内解决。
历史源码/实验提交由 `v0-archive-direct-rime-history-20261019` 保留；下面按时间顺序保留旧记录。
最新源码关系、分支整理、合并门槛和已知范围见[合并交付记录](DIRECT_RIME_MERGE_READINESS_2026-10-19.md)。
日用认可来自该用户的设备与方案，不能扩大为所有设备已无冷启动尖峰或快速发送边界问题。

## 本会话版本演进

| 阶段 | 作用与记录 |
| --- | --- |
| 最小直连实验 | 验证 JNI/librime、输入会话和 InputConnection；仅诊断界面，[原型记录](DIRECT_RIME_IMPLEMENTATION_2026-10-16.md) |
| 完整主应用 | 接回原键盘、候选、设置、主题与布局编辑器，[前端记录](DIRECT_RIME_FRONTEND_2026-10-16.md) |
| 方案与标签 | 注释由 Rime 决定、状态区互斥开关分组、方案菜单去重；空格键、英文模式与中州鹅名称 |
| 性能与迁移 | 纠正基线为含 Fcitx 的默认分支历史提交，按实际万象配置回放，[审计与原始数据](DIRECT_RIME_AUDIT_2026-10-17.md) |
| 旧后端清理 | `a49eb9b8` 删除旧原生后端、构建工程和子模块，[验证记录](DIRECT_RIME_CLEANUP_2026-10-17.md) |
| 无调用方清理 | `6c470b8b` 删除 FcitxUtils 等工具及旧 JNI 转换，[清单](DIRECT_RIME_UNUSED_CODE_AUDIT_2026-10-17.md) |
| 当前设置整理 | 模式/方案编码区提示、单一 Rime 设置入口、重写关于页、旧快捷方式兼容转向 |
| 用户工具包实测 | 默认分支 Fcitx 与直连版，使用用户 YAML、万象 v18.1.3 base + LTS，[触摸与录屏报告](DIRECT_RIME_SCREEN_PERF_2026-10-17.md) |
| 首键与重绑 | 启动期间按键登记、同应用普通文本 session 复用、非编辑绑定延迟创建、候选点选顺序，[响应性改进](DIRECT_RIME_RESPONSIVENESS_2026-10-18.md) |
| 首次选词与日志保存 | `9adb6949`：上下文库重开复用 MANIFEST/WAL、交互调度与首键需求保持，冻结日志快照后保存，[本轮诊断与修复](DIRECT_RIME_COLD_IO_AND_LOG_EXPORT_2026-10-19.md) |

## 已确认的运行结构

主应用通过单一 owner 直接调用 librime；不运行 Fcitx Core、fcitx5-rime 或 AndroidFrontend 原生层。
APK 仅打包直连桥接库和 C++ 运行库。原 Android 前端、键盘、连接和配置模型继续承担界面功能。
“没有 Fcitx 原生中间层”和“仓库中完全没有任何 Fcitx 命名/来源”不是同一结论。
当前保留项及原因在[维护手册](DIRECT_RIME_MAINTAINER_GUIDE_zh-CN.md)列明。

当前万象历史对照结果：候选事件 P50 约降低 6%、P95 约降低 7%，
其范围限于固定 Debug 模拟器回放，不是本轮设置调整的新性能结论。
另有用户工具包的触摸／画面对照，独立于上述 API 指标，见[新报告](DIRECT_RIME_SCREEN_PERF_2026-10-17.md)。
完整矩阵 14 轮通过，共 4,752 次触摸；常速候选 P50 119.7 → 119.0ms，CPU/PSS 基本持平，
未证明稳定整链路提速。计划 120ms 的快速轮实际约 164–166ms，不能算作达到该目标压力。
其固定语料包含长句、快速和抖动输入；个人长期词库、广泛真机、完整 compose 和按应用行为等价仍需继续验证。

## 验证证据

- 无调用方清理：471 项 JVM 单测、Debug/Release、18 组删除类的 DEX 核验，见
  [本地记录](direct-rime-unused-code-validation-2026-10-17.json)。
- 对应源码/记录提交 `4456c110` 的两个 CI 工作流全通过，含 6 项完整前端设备测试，见
  [CI 归档](direct-rime-unused-code-ci-2026-10-17.json)。
- 当前设置与提示版本的源码、APK 哈希、签名、构建及设备回归结果见
  [当前设置版验证](direct-rime-settings-validation-2026-10-17.json)。
- 测试工具提交 `e8fffb64` 的 [PR CI](https://github.com/SandyYuR/fcitx5-android/actions/runs/39025159601)
  与 [Direct Rime experiment](https://github.com/SandyYuR/fcitx5-android/actions/runs/39025159583) 全通过，
  包含 471 项 JVM 单测和 7 项前端设备测试。模式提示测试先等待编辑器输入连接稳定，
  避免将前一测试留下的可见键盘误认为本轮连接已经就绪；提示断言保留。

上轮产品源码 `e6d31718` 的 477 项 JVM、10 项完整前端设备测试与两个 CI 工作流全通过；
用户万象 base + LTS 的立即输入/候选点击前后对照也通过。
六轮编码观察值中位数 163.7 → 113.5ms、两键到候选 221.2 → 164.5ms，
点选总体持平且首次仍慢；范围限于重绑场景，不是进程冷启动/真机帧呈现结论。
[响应性报告与下载](DIRECT_RIME_RESPONSIVENESS_2026-10-18.md) ·
[哈希及验证](direct-rime-responsiveness-validation-2026-10-18.json)。

上一轮产品源码为 `9d5c7992`：修正主线程缓存读取等待后台调度，补齐服务队列/提交事件分段计时；
480 项 JVM、10 项完整前端设备回归、四 ABI 与三方 54 次真实候选点选均通过。
补测确认原 Fcitx 也有首次点选成本；尚未证明首次整体上屏有稳定改善，编辑器冷排版与
首次原生查询需要分别看。见[首次点选报告](DIRECT_RIME_CANDIDATE_TAP_2026-10-18.md)
及[最新验证记录](direct-rime-candidate-validation-2026-10-18.json)。

上一轮产品源码为 `12ab22cd`，修复非编辑器会话创建、候选大批任务占用，恢复并核验
Release profile，补齐 core CPU/缺页/调度和提交请求结果诊断。484 项 JVM、10 项完整前端
设备测试与两个 CI 工作流通过。详见[Foxy 后续修复与验证](DIRECT_RIME_FOXY_FIXES_2026-10-18.md)。

[上一轮 ARM64 Release 下载](https://github.com/SandyYuR/fcitx5-android/actions/runs/39134935586/artifacts/12139547499)，
SHA256 `fbd3aaa62b7bdfa054ddf726b35fab9b939bc1162d583644d3c5282319b564ec`。
该运行的 Release 构建成功，原启动比较未完整通过；不能将整轮标为通过。
使用仓库 Release 签名，可能不同于之前 CI Debug；换签名前按手册导出用户数据。
`candidate-9d5c7992`、`responsive-e6d31718` 与 `settings` 均为历史版本。
源码/签名/profile/验证摘要见[上一轮验证](perf/2026-10-18-foxy-fixes/validation.json)。
Foxy 新进程共同有效阶段 192 次选词正确；修正测试就绪判断后，返场/新进程 16 次与快速发送 8 次均通过。
首次慢选词及快速发送丢字尚未获得真机修复验收；这些模拟器结果不能替代它。

本轮真机验收的产品源码为 `9adb694932df4340bc48d359af2c788ad6809f3e`。新增手机日志的慢选词
主要在原生同步调用内部；独立万象探针进一步定位到 context_reorder 惰性开库与同步 IO。
通过 LevelDB 公共 API 复用有效 MANIFEST/WAL，保留学习、显式同步写及恢复逻辑；
临时提高同步交互任务优先级，并补齐 IO 分段。日志保存改为主线程冻结快照，完整私有暂存
后再选择目标，修复重复保存及 logcat 重启资源交错问题。

491 项 JVM、15 项常规前端设备测试、独立完整万象诊断、原生/IO/存储契约通过。
常规前端套件另有 1 项条件跳过，在独立工作流中实际执行。四 ABI Debug PR 合并构建通过。
Android 固定 prebuilt 的 400 条持久化重开校验通过；已有上下文库五轮首次选词 core
1.612–1.983ms、同线程 sync 均为 0，新建库仍正常同步创建。这是 CI Debug 组件诊断，
不是手机上屏时间或与上一轮相减的收益。后续真机两份日志的首次选词为 4.655/46.693ms，
用户反馈改善并认可日用；首键/绑定仍有冷成本，尚无受控真机快速发送与长期尾延迟结论。详情、CI、签名下载与限制见
[本轮报告](DIRECT_RIME_COLD_IO_AND_LOG_EXPORT_2026-10-19.md)及
[最终验证索引](perf/2026-10-19-cold-io/validation.json)。

[真机验收 ARM64 签名 Release 下载](https://github.com/SandyYuR/fcitx5-android/actions/runs/39180493901/artifacts/12155759113)，
解压安装 `direct.apk`。APK SHA256：
`966c17b7f8482e053f72f82802f621ee48bcb093d1189c7dd6c5b8886e4ad5ba`。
与上一轮 `12ab22cd` 使用相同 Release 证书；构建、ART profile、依赖与对齐核验通过。

工作区文件与四个临时性能分支的整理见[整理记录](DIRECT_RIME_WORKSPACE_CLEANUP_2026-10-17.md)。
