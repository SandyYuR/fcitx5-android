# 设置页直通实现记录（2026-10-16，s-impl-settings，第十四包）

## 实现（DirectRimeEngine.kt:540-647，接现有页，不另造入口）

`FcitxDaemon.backend` 已是 `DirectRimeEngine`（直连默认，fallback 才走 fcitx5），
`connect/runOnReady` 共用同一 backend，故 `InputMethodConfigFragment`、
`GenericMultiSelectFragment`、`runAddonAction` 三处的 `FcitxAPI` 调用天然落到
直连引擎，无需改 daemon 与 UI。

| 接口 | 实现 | 对应 Fcitx 侧语义 |
|---|---|---|
| `getImConfig("rime")` | 返回含 `cfg`+`desc` 的树：desc 顶层节点 `Rime` 下三项 External——`SchemaSelector`（方案选择，URI `fcitx://multiselect/addon/rime/schema-selector?option=Items&min=1&app_proto=2`）、`DeployAction`（立即部署）、`SynchronizeAction`（立即同步） | `RimeEngineConfig` 三项（rimeengine.h:141-161），同名同 URI |
| `setImConfig` | 忽略（本页三项全是 External，点击跳转/执行不经 `PreferenceDataStore` 写 cfg） | 退出页面的空 save |
| `getAddonSubConfig("rime","schema-selector")` | `nativeGetSchemaList` 取 id/name 对，按 `GenericMultiSelectFragment.parseItems` 行格式拼 `Items` payload（`1\tid\tlabel` 选中，当前选中以 `lastSnapshot.schemaId` 判定） | 多选页数据源 |
| `setAddonSubConfig` | `schema-selector`：取首个选中行 id 走 `nativeSelectSchema`；`deploy`/`sync`：分别走 `nativeStartMaintenance(false)` / `nativeSyncUserData()` | 多选页写回 + `runAddonAction`（`setAddonSubConfig(addon,path,RawConfig())` 触发） |

desc 树形状约定（`ConfigDescriptor.parseTopLevel:345-356` + 字段扩展 `:198-219`）：
`desc.subItems[0]` 为顶层节点，其 `subItems` 为各配置项；每项至少 `Type`
（`ConfigType.parse`，本页用 `External`）+ `Description` 子节点，External 再加
`External`（URI）子节点；`parseMultiSelectRoute` 要求 URI 形如
`fcitx://multiselect/addon/<addon>/<path>?option=<opt>` 且 option 非空。

## 构建（第十四包）

- 命令：`appbuild` 下 `:app:assembleFxDebug -PbuildABI=arm64-v8a`
  （工具链环境变量见 `direct-rime-proto/HANDOVER_2026-10-16.md:136-144`）。
- 结果：`BUILD SUCCESSFUL in 21s`，见 `reports/direct-rime-build14.log`。
- 产物：`appbuild/app/build/outputs/apk/fx/debug/org.fxrime.ime-a21c1b2-arm64-v8a-debug.apk`，
  35,161,408 字节。

## 待真机验证（设备离线，`adb devices` 为空）

- `adb install -r`（Success 回执）；
- 点进中州韵设置：三项（方案选择/立即部署/立即同步）可见；
- 点方案选择：多选页列出 `nativeGetSchemaList` 的方案，勾选保存后切方案生效；
- 点立即部署/立即同步：分别触发维护线程/用户数据同步；
- 语言键三项（Shift DOWN+UP 成对、asciiMode 翻转、长按选单）同包回归。
