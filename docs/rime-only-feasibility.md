# 将 fx2 分支裁剪为「Rime 专用输入法」可行性报告

> 调研基线：分支 `fx2`（commit 3ad25fc9）。报告落稿于本地新分支 `fx2-rime-only`。
> 所有关键结论均附仓库内文件证据；未验证处已明确标注。

---

## 0. 结论（TL;DR）

**可行，且比直觉容易。** 三个关键事实：

1. **rime 插件 APK 里没有一行 Kotlin/Java 业务代码、没有自己的界面**：它只是「native addon 动态库 + rime 数据资源 + plugin.xml 声明」。AboutActivity 等公共壳来自 `lib/plugin-base`。并入主 APK 不涉及任何 UI/逻辑迁移。
2. fcitx5 核心通过 `FCITX_ADDON_DIRS`（= 主 APK nativeLibraryDir + 各插件 nativeLibraryDir）用 dlopen 加载 `libFcitx5*.so`（`app/src/main/cpp/native-lib.cpp` startupFcitx :527-753；`androidaddonloader.cpp` :30-100）。**主 APK 自己的 nativeLibraryDir 本来就在这个路径里**，rime 的 addon so 打进主 APK 后核心天然能找到，插件机制对 rime 而言可以被完全旁路。
3. Kotlin 侧对内置拼音引擎（fcitx5-chinese-addons/libime/lua）的耦合面很小（全仓 grep 仅 6 个文件引用），pinyin/table 设置页是几个独立 Fragment，设置项大头由 configdesc 自动生成、引擎删除后自动消失。

三档方案：

| 方案 | 内容 | 工作量 | 风险 | 收益 |
|---|---|---|---|---|
| A 仅裁剪 | 删 11 个其他插件模块 + CI 裁剪 | ~0.5 天 | 极低 | 仓库/CI 整洁，主 APK 不变 |
| **B Rime 内置单引擎（推荐）** | A + rime 并入主 APK + 删内置拼音/码表/libime/lua | 1–2 天 | 中低 | 单 APK、体积净减、设置页干净 |
| C 激进融合 | B + 删整套第三方插件框架（+按你决定删 fork 功能） | +1 天 | 中 | 代码大幅瘦身；与上游 rebase 成本上升 |

---

## 1. 现状架构（调研事实）

### 1.1 插件机制的真实形态

这套机制里**没有 DexClassLoader、没有独立 PluginManager 类**。插件 = 独立安装的普通 APK，app 只做三件事：

- **发现**：`PackageManager.queryIntentActivities` 查 `${applicationId}.plugin.MANIFEST`（`core/data/DataManager.kt:38, :89-224`），本 fork 额外兼容上游 fcitx5-android 的两个 action（`app/build.gradle.kts` ORIGINAL_PLUGIN_MANIFEST_ACTION）。`<queries>` 声明在 `app/src/main/AndroidManifest.xml:35-53`。
- **校验**：签名比对（`DataManager.hasSameSignature() :232-248`）或「允许原版插件」前缀白名单（:132-143）；解析插件包内 `res/xml/plugin.xml` 校验 `apiVersion == 0.1`（`PluginDescriptor.kt:42`）。
- **加载**：
  - 数据路：`DataManager.sync() :250-352` 读取 app + 各插件 assets 里的 `descriptor.json`（构建期由 `build-logic/.../DataDescriptorPlugin.kt` 生成），按 DataHierarchy diff 把差异文件拷入 device-protected dataDir。
  - native 路：启动时把插件 `nativeLibraryDir` 冒号拼接传给 `startupFcitx`（`core/Fcitx.kt:434-444`），native 侧 `setenv("FCITX_ADDON_DIRS", ...)` 后由 `AndroidSharedLibraryLoader` dlopen（`native-lib.cpp:585`；`androidaddonloader.cpp`）。
  - IPC 路：仅 `plugin.xml hasService=true` 的插件（rime 不是）才会 bindService（`core/FcitxPluginServices.kt:20-106`）。

rime 插件的声明链：`plugin/rime/src/main/AndroidManifest.xml` 只剩 icon/label；真正的插件身份由依赖的 `lib/plugin-base` manifest 合并出（其 `AndroidManifest.xml:15-35`：AboutActivity 挂 `${mainApplicationId}.plugin.MANIFEST` action + 防止 R8 裁剪 plugin.xml 的 meta-data + queries）。`plugin.xml` 内容极简：`apiVersion 0.1 / domain fcitx5-rime / description`。

### 1.2 rime 插件的真实内容

`plugin/rime/` 全部内容 = CMake 脚本 + `default.yaml` + 4 个 rime 子模块的 yaml/dict 资源 + 图标/字符串。native 侧：导入 prebuilt 的 `librime.a`（附 glog/leveldb/lua/marisa/opencc/yaml-cpp 静态依赖），编译 `fcitx5-rime` 子模块为 addon so，安装 rime-data 到 `usr/share/rime-data`；数据侧唯一特殊点是一条软链 `usr/share/rime-data/opencc -> usr/share/opencc`（`plugin/rime/build.gradle.kts` 的 `generateDataDescriptor.symlinks`），指向 **app 已内置安装的 opencc 数据**（`app/src/main/cpp/CMakeLists.txt:85-90`）。rime 的 native 构建不依赖 libime/chinese-addons/lua 模块——删它们不影响 rime 编译。rime addon 对 fcitx5 核心的声明依赖（rime-addon.conf）只有 `Dependencies: core` + 可选的 `notifications、dbus`：Android 上 notifications 由 app 自带 androidnotification 满足、dbus 走 NO_DBUS 分支，**与 clipboard/quickphrase/unicode/spell/imselector 等核心 addon 无任何依赖关系**（fx2 全量数据流从插件 APK 的 nativeLibraryDir 与 assets 两条路进核心，见 1.1）。另一注意点：`prepare_personal_build.sh` 构建时会把 fcitx5-rime 子模块切到 fxliang fork 的 master（非 git pin 的上游 commit），profile-manager（多配置目录）等 fx2 定制大概率来自该 fork——裁剪时子模块链路保持现状即可。

### 1.3 主 APK 本身内置了整套拼音引擎（裁剪主战场）

`app/build.gradle.kts` 依赖 `:lib:fcitx5-lua`、`:lib:libime`、`:lib:fcitx5-chinese-addons`；`copy-fcitx5-modules`（`app/src/main/cpp/CMakeLists.txt:65-82`）把 fcitx5 核心模块（clipboard/imselector/quickphrase/spell/unicode）、luaaddonloader、chinese-addons 全家（**pinyin/table/scel2org5/chttrans/fullwidth/pinyinhelper/punctuation**）直接拷进主 APK；预置数据含 spell 英文词典、chaizi.dict、pinyinhelper、libime table、opencc（:85-90）。`native-lib` 还链接 `LibIME::Pinyin/Table` 并静态编译 `customphrase.cpp` 实现拼音自定义短语 JNI（`native-lib.cpp:1232-1290`）。

即：**你即便不装任何插件，内置拼音也一直在**。删掉这条链路（连同 pinyin-lm、table-data 两个数据插件）是主要收益来源。

### 1.4 设置 UI 的耦合点（都很薄）

- 大多数引擎设置页由 configdesc 自动生成，删引擎自动消失。
- 需手工删的 Kotlin 页面：`SettingsRoute.kt` 的 TableInputMethods/PinyinCustomPhrase/PinyinDict/Punctuation 路由、`PreferenceScreenFactory.kt:123-135` 入口、`PinyinDictionaryFragment`/`PinyinCustomPhraseFragment`/`TableInputMethodFragment`/`PunctuationEditorFragment`、`data/pinyin/*`（搜狗词库→libime 转换）、`data/table/*`、`native-lib.cpp` 的 customphrase JNI。
- `input/status/StatusAreaEntry.kt:63-88` 的 chttrans/punc/fullwidth 图标映射有 fallback 分支，可留可删。
- `app/src/main/AndroidManifest.xml` MainActivity 的 `.dict/.scel/.txt` 导入 intent-filter 全是为拼音/码表词库服务，可删。

### 1.5 分支已有的 rime 倾斜（现成融合基础）

- `native-lib.cpp:445-460` `saveWithoutRime()`：设置保存时跳过 rime addon（对应 commit c24fce8a）。
- `Fcitx.kt:79` / `FcitxAPI.kt:46-52`：`saveNonRimeState()` 专用路径。
- `ConfigDescriptor.kt:186`：`ETy.RimeUserDataDir`——rime 的「打开用户数据目录」外部动作已在通用配置 UI 渲染。
- `AppPrefs.kt:247`：数字键盘布局支持 `rime:wanxiang` 这类 `ime:submode` 写法。
- `prepare_personal_build.sh`：构建前更新 fxliang 的 fcitx5-rime、给 **fcitx5 核心打 `fcitx5-alt-trigger-v4point1.patch`**、更新 prebuilt——fork 本来就维护针对 rime 的核心补丁链。

### 1.6 CI 与构建

`ci.yml` 三个 build_type（standard / plugins / mainline），`assembleReleasePlugins` 聚合 12 个插件 APK 并逐个收集上传；`prepare_personal_build.sh` 在构建前跑。插件 APK 收集是**目录遍历式**（plugin/*/build/outputs），删掉插件目录后该 job 无需改动。裁剪后 CI 可缩为单 APK 构建；个人 fork 可顺手删 `fdroid.yml`/`pull_request.yml`（`publish.yml` 只发布 build-logic/common/plugin-base 到 GitHub Packages，与 app 构建解耦，可留）。另：**mainline flavor** 仅在 `-PincludeMainlineFlavor=true` 时启用、作用只是产出与上游同包名的变体，对个人版用户无意义，可整体删除（连同无 Fx 前缀的任务别名与 APK 兼容拷贝逻辑，`app/build.gradle.kts:98-159`）。

### 1.7 ⚠️ 本地构建前置

当前工作副本**未初始化任何 C++ 子模块**（`lib/fcitx5`、`libime`、`prebuilt`、`plugin/rime` 的 cpp 目录均为空）。动 native 之前必须 `git submodule update --init`（prebuilt 仓库很大）；Windows 下建议走仓库自带的 nix/WSL 路径（`flake.nix`/`shell.nix`）。

---

## 2. 「与 rime 无关的功能」清单与分类

### 2.1 直接可删：其他语言引擎插件（独立 APK，互不影响）

anthy / chewing / hangul / jyutping / sayura / thai / unikey / clipboard-filter / table-data / pinyin-lm / text-editor(+language-textmate)。不安装即无影响；从构建删除只动 `settings.gradle.kts` + `.gitmodules` + CI。table-data 与 pinyin-lm 纯粹是给内置拼音/码表供数据的，随 2.2 一并删。注意 **jyutping** 的 native 层依赖 libime + fcitx5-chinese-addons（还要 `Fcitx5ModulePunctuation`，见其 CMakeLists），本就属删除项，与 2.2 同批处理即可，无额外成本；其余语言插件只依赖 fcitx5 prefab，删除互不牵连。

### 2.2 主 APK 内置非 rime 引擎（方案 B 主战场）

| 组件 | 作用 | 移除动作 |
|---|---|---|
| fcitx5-chinese-addons | 内置拼音/双拼/码表 + chttrans/fullwidth/punctuation/pinyinhelper/scel2org5 | 从 copy 列表删 7 个 so；删 gradle 依赖与 includeLibs 条目 |
| libime | 拼音/码表算法（native-lib 也链接它做自定义短语解析） | 删依赖 + find_package + customphrase 静态库 + JNI |
| fcitx5-lua | lua addon 加载器（rime 用的是 librime 内置静态 lua，不依赖它） | 删 copy 与依赖 |
| 预置数据 | chaizi.dict、pinyinhelper、libime/table、（可选）spell en_dict | 逐项删 install；**opencc 必须保留**（rime 软链指向它） |

### 2.3 必须保留的引擎无关底盘

fcitx5 核心 + androidfrontend/androidnotification 安卓模块；核心 addon：clipboard、quickphrase（app 有编辑 UI）；主题/键盘布局编辑器/候选栏/字体（含 fork 的候选注释独立字体、QR 分享）——全部与引擎无关，是你的核心竞争力，保留。（**2026 决策更新：androidkeyboard、unicode、imselector、spell 已按用户要求移除**，中英切换改走 rime ascii_mode + Shift 点击。）

### 2.4 fork 特色功能（与 rime 无关，需你拍板）

| 功能 | 位置 | 引擎依赖 | 移除难度 | 默认建议 |
|---|---|---|---|---|
| 语音输入（ASR provider 体系） | `input/voice/*`、`common/ipc/VoiceInputIpc.kt`、KawaiiBar/IdleUi/Prefs 挂钩（~164 处引用） | 无 | 中 | **保留** |
| 剪贴板云同步（ClipCascade/OneClip） | `clipboardsync/*` 15 文件 + manifest 服务/Activity/Tile | 无 | 中 | **保留** |
| 检查更新/镜像 | `ui/main/update/*` 9 文件 + REQUEST_INSTALL_PACKAGES | 无 | 低 | **保留** |
| webeditor 在线编辑 | `behavior/webeditor`、`ImeWebEditorBridgeServer` | 无 | 小 | 保留 |

> 你说「与 rime 无关的功能全部去除」。若指**输入引擎层**（即去掉拼音/码表/其他语言），上表四项与引擎无关、且互相独立，默认建议保留；若指字面全删，列入方案 C 可选清单即可，技术上均可拆。

---

## 3. 方案 B 实施细节（推荐）

1. **native 合并**：把 `plugin/rime/src/main/cpp/CMakeLists.txt` 的目标（librime 静态导入 + fcitx5-rime 子目录 + rime-data 安装）并入 `app/src/main/cpp/CMakeLists.txt`；`default.yaml`、essay/prelude/luna/stroke 的 install 并入 app 的 prebuilt-assets。
2. **数据描述符**：opencc 软链移入 app 的 `generateDataDescriptor.symlinks`（app 已安装 opencc 数据，链路不变）。
3. **gradle**：`app/build.gradle.kts` 删 `:lib:libime`/`:lib:fcitx5-lua`/`:lib:fcitx5-chinese-addons` 依赖与 `fcitxComponent.includeLibs` 对应项；`settings.gradle.kts` 删全部其他插件模块。
4. **app CMake / native-lib**：删 `find_package(libime/fcitx5-lua/fcitx5-chinese-addons)`（app CMakeLists:16-25）、copy 列表 7+1 个目标（:65-82）、`pinyin-customphrase` 静态库（:42-45）与 `LibIME::*` 链接（:48-62）、相关 prebuilt 资产 install（**保留 opencc 那行**）；`native-lib.cpp` 同步删词典转换/自定义短语 JNI（:1183-1260）、`LIBIME_MODEL_DIRS` 与 lua 路径环境变量（:552-587）及 libime/customphrase 头文件 include（:36-41）。
5. **Kotlin**：删 1.4 列出的 Fragment/路由/数据管理器（含 `CustomActionExecutor` 路由表与 `ConfigDescriptor.ETy` 中拼音/码表分支）与 manifest 导入 intent-filter；`fcitxComponent.excludeFiles` 中拼音/码表条目随之清空。
6. **插件页**：`PluginFragment` 可隐藏（方案 B）或整页删除（方案 C）。
7. **三个容易漏的融合点**：① `native-lib.cpp:605-609` 会把每个插件的 domain 注册为 gettext 翻译扩展域——rime 内置后 extDomains 为空，需手动把 `fcitx5-rime` 加入注册列表，否则 rime 设置项的中文翻译失效；② rime addon 声明的可选依赖 notifications 由 app 自带 androidnotification addon 满足（部署/维护提示），dbus 因核心 `ENABLE_DBUS=OFF` 走 `FCITX_RIME_NO_DBUS` 分支、无需处理；③ app 侧 4 处 rime 专属挂钩必须原样保留：状态区 rime 图标/submode（`StatusIconMapping.kt:44-46`、`TextKeyboard.kt:491-492` 的 `fcitx-rime:` 前缀）、设置里的 Rime 部署/同步按钮（通用 AddonAction → setSubConfig deploy/sync，`PreferenceScreenFactory.kt:234-241`）、Rime 用户数据目录入口（`PreferenceScreenFactory.kt:403-436`）、`saveNonRimeState` 旁路。

验收：单 APK；IM 列表只出现 rime schema；拼音/码表/自定义短语/标点入口消失；rime 部署、用户目录（RimeUserDataDir 外部动作）、自定义 schema（万象等）正常；`saveNonRimeState` 不回归。

## 4. 方案 C 追加（可选）

删 `DataManager.detectPlugins`/签名校验/白名单、`PluginFragment`、manifest `<queries>` 与 ORIGINAL_* 兼容层、`lib/plugin-base` 与 `lib/common` 中插件运行时（`FcitxPluginService`/`PluginMessage`/ClearUrls）。注意：`VoiceInputIpc`、`FcitxRemoteConnection` 同在 `lib/common`，被语音与剪贴板同步使用——**保留语音/同步就保留这两个文件**。删插件框架后，已安装的 rime 插件 APK 变成无害孤儿（更新说明里提示卸载即可）。

---

## 5. 风险清单与对策

| 风险 | 说明 | 对策 |
|---|---|---|
| 旧安装兼容 | 老用户装过 rime 插件 APK | 方案 B 保留 detect 逻辑则插件照常被加载（同签名），兼容无痛；方案 C 下为孤儿包，提示卸载 |
| rime-data 归属切换 | descriptor diff 会把 `usr/share/rime-data/*` 从插件文件改为 app 文件，重装这些默认 yaml | **rime 用户数据与模板目录是分离的**：用户数据在 `<外部存储>/data/rime`（fcitx5-rime 取 PkgData/rime 为 user_data_dir，rimeengine.cpp:249-262；native-lib.cpp 把 extData 传为 HOME/FCITX_DATA_HOME），descriptor 同步只重装 `dataDir/usr/...` 下的只读模板，不碰用户数据——风险很低，Phase 2 真机回归一次即可 |
| fcitx5 核心补丁 | `prepare_personal_build.sh` 的 alt-trigger 补丁必须继续生效 | 该脚本与裁剪正交，保持不动 |
| 本地构建前置 | 子模块未 init，native 不可编译 | `git submodule update --init`；建议 WSL/nix 构建 |
| 上游同步 | 方案 C 删除面大，rebase 冲突多 | 删除动作拆成边界清晰的小 commit；或接受长期独立分支定位 |
| Room schema | 删 pinyin/table 相关表需迁移 | 方案 B 第一版**不删表只删 UI**，零迁移风险 |
| 英文键盘 spell | 英文键位联想依赖 spell 模块 | 保留 spell 与 en_dict（收益小，不值得删） |

### 5.1 FAQ：rime 的 Lua 脚本 / 八股文（octagram）语法模型会受裁剪影响吗？

**不会，且有硬证据。** rime 方案常用的增强能力全部编译在 `librime.a` 内部，属于 librime 的内置插件，与 fcitx5 层被裁的模块（`:lib:fcitx5-lua`、`:lib:libime`、`:lib:fcitx5-chinese-addons`）零关系：

- `plugin/rime/licenses/libraries/` 清单明确列出本构建打包了 **librime-lua**（hchunhui/librime-lua）、**librime-octagram**（lotem/librime-octagram，即八股文语法模型）、**librime-predict**（fxliang/librime-predict-leveldb）；
- `plugin/rime/src/main/cpp/CMakeLists.txt:15-28`：librime.a 与 glog/leveldb/**lua_static**/marisa/opencc/yaml-cpp 全部来自 `PREBUILT_DIR` 静态库，以 `WHOLE_ARCHIVE` 方式链接，插件随 librime 自注册；
- 被删的 `:lib:fcitx5-lua` 是 **fcitx5 自己的 Lua addon 加载器**（服务于 fcitx5 lua 插件），与 librime-lua 完全是两套东西；libime/chinese-addons 同理只在 fcitx5 引擎层。

裁剪后 rime 方案需要的东西一样不缺：librime 内置的 lua/octagram/predict 随静态库打入主 APK；语法模型文件（如万象的 .gram）由用户方案自带、放 rime 用户目录；**opencc 数据保留在 app 安装列表**（简繁转换依赖）；`prepare_personal_build.sh` 补丁链保留。方案 B 验收时建议加两条实测：① 带 lua 脚本与 `grammar` 段的方案正常出候选；② 简繁切换（opencc）正常。

## 6. 预期收益

- **体积**：减去 chinese-addons 全家 so + libime 数据 + lua addon，打入 librime addon（原在插件 APK 里那一份）。主 APK 预计净减；设备总占用（主 APK + rime 插件 APK）必净减。具体数值 Phase 2 出包后实测。
- **启动/运行**：核心不再加载 lua/pinyin/table/punctuation 等 addon；无插件扫描。
- **体验**：设置页只剩 rime 与通用项；免装第二个 APK；「插件」页消失。
- **维护**：仓库少 12 个插件模块、15+ 子模块引用；CI 三合一。

## 7. 建议执行顺序

1. **Phase 0**：init 子模块 → 本地跑通 `:app:assembleFxDebug`（基线可复现）。
2. **Phase 1 = 方案 A**：删其他插件模块 + CI/.gitmodules 清理；可选顺手删 mainline flavor、APK 兼容拷贝逻辑与 fdroid.yml（独立 commit，可随时合并回 fx2）。
3. **Phase 2 = 方案 B**：rime 并入主 APK → 逐层删内置拼音链路（native → gradle → Kotlin → 设置入口），每步独立 commit、出包自测。
4. **Phase 3 = 方案 C（可选）**：删插件框架；（你确认后）删语音/更新等 fork 功能。

> 待你拍板：① 语音输入 ② 剪贴板云同步 ③ 检查更新——默认建议全保留（与引擎无关）。

---

## 附录：调研核对过的关键证据

- 插件发现/校验/合并：`core/data/DataManager.kt`、`core/data/PluginDescriptor.kt`、`core/data/PluginLoadFailed.kt`、`core/FcitxPluginServices.kt`、`lib/plugin-base/src/main/AndroidManifest.xml`
- 启动链：`core/Fcitx.kt`（nativeStartup :429-469）、`app/src/main/cpp/native-lib.cpp`（startupFcitx :527-753、saveWithoutRime :445-460）、`app/src/main/cpp/androidaddonloader/androidaddonloader.cpp`
- rime 插件本体：`plugin/rime/build.gradle.kts`、`plugin/rime/src/main/cpp/CMakeLists.txt`、`plugin/rime/src/main/cpp/default.yaml`、`plugin/rime/src/main/res/xml/plugin.xml`、`plugin/rime/src/main/AndroidManifest.xml`
- 内置引擎与资产：`app/build.gradle.kts`（fcitxComponent/依赖）、`app/src/main/cpp/CMakeLists.txt`（copy-fcitx5-modules/install）
- 设置耦合：`ui/main/settings/SettingsRoute.kt`、`PreferenceScreenFactory.kt`、`ui/main/settings/*Pinyin*Fragment`、`TableInputMethodFragment`、`data/pinyin/*`、`data/table/*`、`input/status/StatusAreaEntry.kt`
- 构建与 CI：`build-logic/.../DataDescriptorPlugin.kt`、`AndroidPluginAppConventionPlugin.kt`、`.github/workflows/ci.yml`、`prepare_personal_build.sh`
- fork 特性盘点：`input/voice/*`、`clipboardsync/*`、`ui/main/update/*`、`ui/main/settings/behavior/webeditor`、`app/src/main/AndroidManifest.xml`
