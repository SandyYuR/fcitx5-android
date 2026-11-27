# 引擎入口、输入类型移植与 EVO 字符集准入核对（2026-11-26）

本记录对应应用未提交分支 `port/wanxiang-engine-features`。字符集准入只核对新版外置 EVO 自身已有的实现，本轮不向内置 librime 或其他非 EVO 引擎加入该能力；早期越界实施已全部撤回，`librime-src` 只保留 SDK 来源元数据。本轮没有发布 SDK 或修改正式 SDK 锁。唯一设备为 AOSP API 35 / x86_64 的 `rime`（`emulator-5574`）。

## 来源与适配范围

| 来源提交 | 借鉴行为 | 中州鹅适配 |
| --- | --- | --- |
| [wanxiang-ime 63a0450a](https://github.com/amzxyz/wanxiang-ime/commit/63a0450a16d27fdb640761d3fd93bb7d76423970) | 归并引擎入口，展示内置引擎实际来源 | 保留 `fx` flavor 与现有管理页；APK 原样携带本次构建 SDK 清单，开发 SDK 显示基线提交及工作树摘要，旧清单缺字段时明确未知 |
| [wanxiang-ime db879d13](https://github.com/amzxyz/wanxiang-ime/commit/db879d13bbfd166503f917fd5cac4552e875e8a7) | 只列已启用且已部署的输入类型，即时切换运行态 | 保留 EVO 能力门禁和唯一 owner；校验实际非空 table / prism，维护与引擎 / 工作目录目标失效时拒绝旧命令，正常编辑器焦点变化不使设置失效 |
| [wanxiang-ime 3c3d948e](https://github.com/amzxyz/wanxiang-ime/commit/3c3d948e0eb1fa9b45a06f51c4f65a165d244f32) | 只保存对应方案的 reset 偏好 | 逐行编辑当前方案 custom，保留 enabled、options、注释、键序、换行及末尾换行状态；原子写入后运行态拒绝或取消时恢复原字节 |
| [librime-evo 9e56553c](https://github.com/amzxyz/librime-evo/commit/9e56553c21a3041bd50bb16ea2428d33765e4c71) | 系统单汉字在候选构建前按策略准入 | 只随新版外置 EVO 引擎提供，经 `RewriteResource` 读取 `build/rewrites` 编译的 TSV；内置引擎和其他非 EVO 引擎继续不支持，不新增实现 |

最新外置 EVO 另从 `origin/main` 固定到 `76ed0c013cc0633e14bebd90c10a74dc412708bc` 进行验证。该分支中对应字符集实现为变基后的等价提交，原指定提交不是其祖先；核心源码补丁的稳定 patch-id 为 `dab7ec3174a274a6a2c33fde875ee9da7154f630`。

## 行为边界

引擎变体入口移动到“设置首页 → 中州韵引擎 → 中州韵引擎设置 → 引擎变体”。设置搜索和页面导航测试同步更新。内置来源来自 APK 的 `bundled/rime-sdk-manifest.json`，与当前外置引擎选择分开；开发包不能仅凭基线 SHA 宣称是干净发行源码。

输入类型菜单取方案声明、有效 enabled 和 native 已部署可用棱镜的交集。启用列表保持完整，只将选择保存为当前方案 custom 的 reset。运行态通过已有 owner / JNI 队列切换，不以 set_option 返回真代替能力识别，不触发重部署。目标带 profile、规范路径和引擎身份，不绑定编辑器会话代次；局部会话代次只决定是否刷新当前输入展示。失败或取消恢复完整原文件，新创建的失败 custom 会撤回。native 初始化、停止及部署清理内存选择，再从有效 reset 重新校验恢复。

字符集准入只由新版外置 EVO 提供：仅当方案显式配置 `translator/charset` 与 `charset/data` 时生效，未使用 EVO 或未配置的方案行为不变。它只过滤系统词典中恰为一个有效 Han 码点的词条，多字词、用户词与非汉字不检查；活跃规则 base 与 addlist 取并集、blacklist 否决，命中 `exclude_tags` 或资源不可用时放行。数据来自 EVO 自身的 `build/rewrites` 编译产物，具体语义以 EVO 自带 `docs/charset.zh-CN.md` 为准。

本轮撤回了早期向内置 librime 加入的字符集实现及其专用测试，没有性能对照数据。重建开发 SDK 后检查 `librime.a`，`charset` 相关符号为 0，可据此确认内置引擎不提供该能力。

完整用法见[中州韵增强功能](../../../features/rime-enhancements.md)，引擎管理见[引擎变体](../../../features/rime-engine-variants.md)，事务要求见[正确性契约](../../implementation/correctness-contracts.md)。

## 固定构建输入

| 对象 | 标识 |
| --- | --- |
| 应用基线 | `2cd8d0f55046479206ec3adddb39426223d3bc0c` 加本轮工作树改动 |
| 内置引擎基线 | `83fbd0810e8c24db87f3b9dc9acce193401c7c4f`（撤回字符集实施后重建，`librime.a` 无 `charset` 符号） |
| 实际开发 SDK 源码树 | `b48eb4b8baf16a1bc7b70ca1299bcd0ceffbabc1`（撤回字符集实施后重建） |
| SDK engine_family / ABI | `librime` / `x86_64`，`development=true` |
| Debug APK SHA-256 | `a43f87f4a587fb73927837bc13f710476a1798c175b802443972dd258f37645c` |
| APK 内 direct-rime ELF build ID | `4a0608a424418c668890918cf30493eaed42829e` |
| 共享预设子模块 | `082425ea0684bca36474415d4a0e8db9b016487e` |

应用通过 `-PlocalRimeSdkDir=D:/GitHub/fx2-rime/.build/rime/sdk` 仅在 Debug 消费该 SDK。正式 Release 锁与来源未改；尚未发布可供常规 Release 使用的新 SDK，也未据此声称四 ABI 发行包已升级。

## 验证结果

- 第一轮应用相关 JVM：70 项全过；补充 JNI 后取消、发布与维护撤回后 owner 达到 10 项，累计 75 项通过。用户随后在实际设置页发现弹窗焦点变化使输入会话代次失效，见后续现场修正；先前 JVM 通过不代表覆盖这一真实交互。
- SDK 归档 / 清单测试：11 项全过。Android 构建与打包工具 Python 测试：22 项全过。
- 同源 JNI 桥接 C++ 契约在 `rime` 上通过，输入统计契约 5 组通过。此处是桥接 mock API 契约，不替代真实候选测试。
- 字符集准入不设内置实现：早期针对内置引擎的 AndroidTest 与原生资源 / 策略 / 分页测试属于越界范围，已随实现一起撤回，不构成本轮交付内容。
- x86_64 Debug 构建、APK 原生库白名单和同次构建链接审计通过。
- 引擎入口设备用例 1 项通过；新版外置 EVO 经受校验共享库导入及安装后的真实 provider 路径各 1 项通过，验证实际编码切换、缺棱镜拒绝、reset 恢复与新编辑器隔离。
- 最终焦点修正后 owner 11 项、repository 25 项通过，相关 JVM 合计 76 项，失败、错误、跳过均为零。实际长期 profile 的方案页往返切换也通过，见下节。
- 撤回内置字符集实现后，用重建的开发 SDK 重新出包并安装到 `rime`：`libdirect-rime.so` 的 `Charset` 符号为 0，APK 白名单与同次链接审计通过；应用相关 JVM 五组复跑 73 项（repository 25、owner 11、入口导航 22、SDK 元数据 12、设置搜索 3）全部通过，失败、错误、跳过均为零。
- 新包在实际 `rime-wanxiang-evo` 工作目录的方案页复测：输入类型菜单只列 enabled ∩ 已部署的 14 项，小鹤双拼切换即时生效、重新打开时勾选同步、只改 `input_types/reset`；切回全拼后 `wanxiang.custom.yaml` 与验证前逐字节一致。
- 文档门禁结果附于本页收尾证据。

Windows 上首次桥接契约运行受到本机临时目录访问错误阻塞：`temp_directory_path: not a directory`，独立本机探针报告 `Access is denied`。严格边界 ACL 诊断未修改任何 ACL。相同源码改为 NDK 编译后在唯一 rime 运行并实际通过，没有把 Windows 失败标为成功。新版 EVO 的 LuaJIT 本机生成器同样遇到读取权限问题，转由相同源码的 Android 生成器在自有设备临时目录生成，不改变 Windows ACL 或依赖版本。

## 实际设置页焦点修正（2026-11-27）

初版虽通过临时方案的 native / repository 测试，但把设置目标错误绑定到输入会话 generation。用户在真实方案页打开弹窗、点击选择时，Android 的编辑器焦点变化推进代次，文件事务在 native 调用前被“输入会话已改变，请重新打开方案列表”拒绝。不能把先前夹具通过当作实际设置页通过。

最终修正保留 engine / profile / canonical 目录、维护、能力与取消检查，设置 native 调用传 expectedGeneration=-1；不改变按键、候选和编辑器写入的代次保护。事务前后仍检查取消与配置目标，失败时恢复原配置与相同绑定的运行选项；局部 publication generation 只用于跳过过期展示刷新。

在唯一 rime 的真实 rime-wanxiang-evo 工作目录中，沿首页 → 中州韵引擎设置 → 启用的输入方案 → 万象拼音完成“自然码 → 小鹤双拼 → 自然码”。摘要、重新打开后的勾选与有效 reset 同步更新。中途只改 reset，往返后 custom 完整字节 SHA-256 与测试前相同：1c0177da82f904011654974fbc8dc0b158a0f50a7f7cbe4f74377270bd1101fb。复测时段没有同类切换异常。

同一工作目录另有缺失资源问题：方案引用 data/charset.txt，但目录没有完整源表。已从设备原有、与官方固定版本相同的 charset.reverse.bin 恢复全部 98,768 条 key→原始属性映射（82 种属性字符串），导出 UTF-8 TSV 为 796,741 字节，SHA-256 为 97cbd22dce0675b08aac7aa4f477edf33ca52d3fdc059329bb64252082e5774f。原二进制 SHA-256 为 dbf8049c1f2f616a0a2ebd7b439cc94ea188805fd2c4b8dba2c4cabdf55de9e8。枚举未按 Unicode 范围筛选；全部索引重建后与原二进制逐字节相同，随后使用实际 librime ReverseDb::Lookup 对全部 98,768 条逐项核对通过。恢复的是完整映射，未声称恢复源注释、顺序或权重。

确认缺失后只补入该源表，再通过真实设置页重新部署。设备 UTC 日志 2026-11-26 16:42:55（本机 2026-11-27 00:42:55）显示 finished updating schemas: 1 success, 0 failure；2 tasks ran: 2 success, 0 failure。rewrite:charset 及全部所需字典构建完成。键盘测试中全拼 nihao 显示“你好”候选，测试内容随后清空。资源恢复前当前选择已经是全拼；保留该选择，部署前后 custom 完整字节相同，SHA-256 为 e83d76f7010f1240dc7ee9380e2bf637af16b8e588efa483d03798628dc1f2ec。

最终独立专项复核同时核对了真实 native 门禁、配置撤回、焦点变化回归与实际设置页证据，既有三个检查点均通过。该结论仅覆盖设置事务专项。

## 设备数据与发布状态

设备原 prefs 与引擎授权 state 已备份；使用同签名 `adb install -r` 更新原 Debug 包。测试 profile、repository、引擎执行副本只在本轮自有 cache / codeCache 内创建，设备测试未清空或卸载主应用，也未改用其它 AVD。长期方案仅补齐上述缺失的完整源表；恢复前后引擎授权与 profile state 相同，85 项偏好仅进程 pid 改变，默认输入法保持 Fcitx。

原 EVO provider 使用 `CN=evo test` 证书，本机 debug 证书不匹配。首先通过测试自有 repository 的受校验共享库导入路径验证；之后用户明确授权卸载签名不同的旧插件并安装本次 APK，已保留旧 APK，按原包名安装新版 `1.17.0-evo-76ed0c01`。新版插件签名 SHA-256 为 `86f8c54c1917b6ef2c71a43ae8e5100b1b5164b06702113289e4c89b2257b3eb`，载荷 SHA-256 为 `94bc5291104712d02e8f36d4f632d5af1fd0a5d20868c257f65a06f26b1fd5da`。安装后真实插件发现、探测、受管加载及输入类型测试 1 项通过。用户重新授权后仍使用原 `rime-wanxiang-evo` 工作目录。

新版 EVO 来自 main `76ed0c013cc0633e14bebd90c10a74dc412708bc`；外置共享库为 `x86_64` / API 23 / 16 KiB 对齐，版本 `1.17.0`。测试 APK SHA-256 为 `7ba411aedf9543e314d8b018b4aacb60799facd84f37513a0c226fca4c06bd61`，使用本机测试签名。

本轮代码和文档均未提交、未推送、未发布。

## 验证材料

- [真实方案页往返切换记录](./settings-binding-verification.txt)、[切换到小鹤后的页面](./settings-binding-after-flypy.png)。未附用户完整 prefs、授权 state 或方案内容。
- [完整字符集恢复与部署核对](./charset-restoration.txt)、[实际候选页面](./resource-candidates-final.png)、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/charset-export-native-verify.log" download>真实 ReverseDb 全量校验</a>、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/resource-deploy-evidence.log" download>真实部署完成日志</a>、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/final-data-check.log" download>最终配置数据核对</a>。
- <a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/input-types-settings-binding-tests.log" download>最终 owner / repository 测试日志</a>、[owner JUnit](./input-types-owner.xml)、[repository JUnit](./input-types-repository.xml)。
- <a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/app-target-tests-final.log" download>其余应用目标测试</a>、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/search-index-test.log" download>设置搜索测试</a>、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/sdk-archive-test.log" download>SDK 归档测试</a>、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/android-build-tools-test.log" download>构建工具测试</a>。
- <a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/engine-entry-device-fixed.log" download>引擎入口设备测试</a>。
- <a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/evo-main-input-types-device.log" download>新版 EVO 导入设备验证</a>、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/evo-installed-plugin-device.log" download>安装后 provider 验证</a>。
- <a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/native-engine-device.log" download>桥接引擎契约</a>、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/native-statistics-device.log" download>输入统计契约</a>、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/native-bridge-tests.log" download>Windows 首轮失败</a>。
- <a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/evo-only-sdk-build.log" download>撤回字符集后 SDK 重建</a>、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/evo-only-build.log" download>最终 Debug 构建</a>、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/evo-only-tests.log" download>撤回后应用目标测试</a>、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/settings-binding-apk-audit.log" download>APK 白名单</a>、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/settings-binding-link-audit.log" download>同次链接审计</a>。
- <a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/source-files.sha256" download>源码与验证文件摘要</a>、<a href="/fcitx5-android/maintainer/audit/wanxiang-engine-features-2026-11-26/checksums.sha256" download>证据摘要</a>。
