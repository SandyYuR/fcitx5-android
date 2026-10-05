# 从上游迁移到靓企鹅·中州韵

如果你已经在使用 [上游 fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 或 [fxliang fx 构建](https://github.com/fxliang/fcitx5-android)，并有大量自定义数据（Rime 配置、词库、主题、布局、SharedPreferences 等），不需要从零开始——旧应用导出的备份 **可以直接被本版导入**。

::: tip 兼容关键
本版的导入器接受 `metadata.json` 里包名以 `org.fcitx.fcitx5.android` 开头的备份（含上游、fxliang fx 构建、本版自身），并自动重命名包名相关的 SharedPreferences 文件。无需手工编辑 zip。
:::

## 方式一：导入完整用户数据（推荐）

### 第一步：在旧应用中导出

1. 打开旧应用 → **设置 → 高级 → 导出用户数据**
2. 选择保存位置，得到形如 `fcitx5-android_2026-06-03T12-34-56Z.zip` 的备份文件
3. （可选）将该 zip 传到电脑 / 云盘做一份额外备份

备份里包含：SharedPreferences（全部 UI 开关）、数据库（剪贴板历史等）、外部配置（布局 / Popup / 主题 / 字体集等 JSON 与资源）、Rime 用户目录（`default.custom.yaml`、方案、用户词库）、`metadata.json`。

::: warning 备份与隐私
备份 ZIP **未加密**，其中可能包含剪贴板历史与同步设置。不要上传到公开网盘、聊天群或 issue；如需云盘中转请先加密。
:::

### 第二步：安装并启用本版

按 [安装](/guide/installation) 完成安装与首次启用，**确认键盘可以正常弹出**后再导入。

### 第三步：在本版中导入

1. **数据与备份 → 导入用户数据**
2. 选择前面导出的 zip，确认覆盖提示
3. 应用会停止 Fcitx、覆盖本地设置与输入历史，然后要求重启
4. 重启后检查：Rime 用户目录、当前方案、键盘布局、主题和剪贴板

::: tip 先导一份空白基线
导入是**覆盖语义**，不是合并。建议在导入前先在本版里导出一份空白基线备份作为安全网；确认迁移结果无误后再卸载旧应用——卸载旧应用不会影响本版数据，反之亦然。
:::

### 注意事项

- **其他引擎的配置不会生效**：备份里可能带着旧版的拼音词典等引擎数据，本版不会加载已删除的引擎；不要把旧版拼音词典、码表入口或插件说明当作本版功能。
- **剪贴板数据库版本**：来自更新版本的数据库可能无法导入，应先把本版升级到不低于备份来源的版本。
- **没有版本号校验**：导入逻辑只看包名前缀。导入后若 Rime 部署失败或配置不生效，请在 **中州韵设置** 中重新部署一次。
- **不会自动转换布局格式**：布局 JSON 的扩展字段（`displayText`、`weight`、`rowHeightPercent`、分体布局等）向后兼容，旧版布局可直接解析。

## 方式二：只迁移 Rime 配置

如果不想覆盖整个应用，可只搬 Rime 用户文件：

1. 在 **中州韵设置** 中打开用户数据目录（应用外部文件目录下的 `data/rime`）；
2. 从旧应用的 Rime 目录复制需要的 `*.yaml`、用户词典及其他 Rime 文件过来；
3. 在同目录准备 `default.custom.yaml`，patch `schema_list`（本版不预置方案，必须显式列出，见 [快速上手](/guide/quick-start)）；
4. 回到 **中州韵设置** 执行 **部署**。

不要覆盖你不理解的编译产物或运行数据库；优先复制自己维护的 YAML 和词典源文件，让 Rime 重新部署生成运行数据。

## 私有目录隔离说明

Android 系统保证不同包名应用的私有目录完全隔离：

```
/data/data/org.fcitx.fcitx5.android/          ← 上游
/data/data/org.fcitx.fcitx5.android.fx/       ← fxliang fx 构建
/data/data/org.fcitx.fcitx5.android.fx.rime/  ← 靓企鹅·中州韵（本版）
```

所以迁移过程是「从一份目录读 zip → 写入另一份」；卸载其中任何一个，都只会清除它自己的目录。

## 反向迁移（本版 → 上游 / fxliang）

本版导出的备份同样以 `org.fcitx.fcitx5.android` 前缀写入 `metadata.packageName`，可直接被上游 / fxliang 导入。上游 **会读取** 通用部分：SharedPreferences、Rime 配置、字体、自定义词库；本版特有的文件上游不读取，留在目录里无害。

## 相关页面

- [构建版本与数据共存](/guide/builds-and-plugins)
- [安装](/guide/installation)
- [Rime 引擎（内置）](/features/rime-enhancements)
- [键盘布局编辑器](/features/editor/layout-editor)
