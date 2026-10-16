# 设置页缺口盘点（2026-10-16，s-survey-settings）

## 设置页数据源（Fcitx 侧现状）

`InputMethodConfigFragment`（`fx-rime-only/.../settings/im/InputMethodConfigFragment.kt:18-24`）：

- `obtainConfig` → `fcitx.getImConfig(args.uniqueName)`，uniqueName 为 `"rime"`
 （入口路由见 `SettingsGroupSpecs.kt:87`）。
- `saveConfig` → `fcitx.setImConfig(args.uniqueName, newConfig)`。
- 渲染基类 `FcitxPreferenceFragment`（`FcitxPreferenceFragment.kt:104`）：
  `raw = fcitx.runOnReady { obtainConfig(this) }`，空 `RawConfig` 即空页面。

`Fcitx.kt:161-167`：`getImConfig` 直通 native `getFcitxInputMethodConfig`，
Fcitx 链路下返回的是 fcitx5-rime 的 `RimeEngineConfig` 树。

`RimeEngineConfig` 内容（`fcitx5-rime/src/rimeengine.h:111-167`，`FCITX_CONFIGURATION`）：

| 项 | 类型 | 说明 |
|---|---|---|
| PreeditMode | enum | 预编辑模式 |
| InputState（SharedStatePolicy） | enum | 共享输入状态 |
| PreeditCursorPositionAtBeginning | bool | 嵌入预编辑光标位置 |
| SwitchInputMethodBehavior | enum | 切输入法时动作 |
| UserDataDir | ExternalOption | 用户数据目录（xdg-open） |
| DataDirManager | ExternalOption | 数据目录管理 |
| SchemaSelector | ExternalOption | 方案选择（multiselect，option=Items，min=1） |
| Deploy / Synchronize | KeyList | 部署/同步触发键 |
| DeployAction / SynchronizeAction | ExternalOption | 立即部署/立即同步 |
| ShiftKeyBehavior | enum | Shift 键行为（默认 DisableFcitxToggle） |
| LatinModeNameFromSchema | bool | 拉丁模式名取自方案 |

## 引擎侧现状（DirectRimeEngine）

`DirectRimeEngine.kt:538-545`，8 个配置接口**全部空实现**：

```kotlin
override suspend fun getGlobalConfig(): RawConfig = RawConfig()
// ... getAddonConfig / getAddonSubConfig 同为空
override suspend fun getImConfig(key: String): RawConfig = RawConfig()
override suspend fun setImConfig(key: String, config: RawConfig) {}
```

`RawConfig` 结构（`Types.kt:79-109`）：`name/comment/value/subItems` 树，
`getOrCreate(name)` 可增量搭树，构造一棵返回即可被设置页渲染。

## native 已有能力（RimeBridge.kt）

| 接口 | 行号 | 对应 Fcitx 侧能力 |
|---|---|---|
| nativeGetSchemaList | :101 | SchemaSelector（方案列表） |
| nativeSelectSchema | :104 | 方案选择写回 |
| nativeStartMaintenance | :133 | DeployAction（立即部署） |
| nativeSyncUserData | :139 | SynchronizeAction（立即同步） |
| nativeGetOption / nativeSetOption | :97 / :94 | 开关类选项（如 ascii_mode 相关） |

## 实现口径结论

缺口在引擎侧：`getImConfig("rime")` 返回空 `RawConfig`，设置页无内容可渲。
实现口径：用 native 能力构造 `RawConfig` 树，最小覆盖——

1. 方案列表（SchemaSelector 等价）：`nativeGetSchemaList` 读 id/name 对，
   `setImConfig` 写回走 `nativeSelectSchema`；
2. 部署/同步动作（DeployAction / SynchronizeAction 等价）：
   `nativeStartMaintenance` / `nativeSyncUserData`。

接到现有 `InputMethodConfigFragment`，不另造入口、不新增路由。
`RawConfig` 树的精确形状（ExternalOption/Multiselect 的 name 约定）留待实现步
对照 `FcitxPreferenceFragment` 的渲染分支确定。
