# 从上游迁移到 fxliang

如果你已经在使用 [上游 fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) 并有大量自定义数据（Rime 配置、词库、主题、SharedPreferences 等），不需要从零开始 —— 上游导出的备份 **可以直接被 fxliang 主程序（fx / mainline 均可）导入**。

::: tip 兼容关键
- **上游 → fxliang**：fxliang 的导入只校验 `metadata.json` 里包名是否以 `org.fcitx.fcitx5.android` 开头，因此上游、fx、mainline 三个来源都接受；SharedPreferences 文件名差异由导入端自动重命名
- **fxliang → 上游**：fxliang 导出时统一把 `metadata.packageName` 写成 `org.fcitx.fcitx5.android`（并同步改名 SharedPreferences XML），上游的精确等值校验由此直接通过

结果就是 **三者备份互相通用**，无需手工编辑 zip。
:::

## 第一步：从上游导出备份

在 **上游** fcitx5-android 应用中：

1. 打开应用 → **设置 → 高级 → 导出用户数据**
2. 选择保存位置，得到形如 `fcitx5-android_2026-06-03T12-34-56Z.zip` 的备份文件
3. （可选）将该 zip 传到电脑 / 云盘做一份额外备份

### 备份里包含的内容

| 区域 | 包含 |
|------|------|
| `shared_prefs/` | 应用所有 SharedPreferences（全部 UI 开关、自定义偏好） |
| `databases/` | 数据库（如剪贴板历史等） |
| `external/config/*.json` | 键盘布局 / Popup 预设 / Kawaii Bar 布局 / 字体集等 JSON 配置 |
| `external/fonts/` | 自定义字体 |
| Rime 用户目录 | 你的 `default.custom.yaml`、方案文件、用户词库 |
| `metadata.json` | 包名 + 版本号 + 导出时间戳（供导入端校验） |

::: warning 剪贴板历史不迁移
导入流程出于隐私考虑 **不会还原剪贴板历史**（即便备份中有），其他数据照常还原。
:::

## 第二步：安装 fxliang 主程序

按你选择的策略装一个 fxliang 主程序：

- **想保留上游、双装**：装 **fx 构建**（包名 `org.fcitx.fcitx5.android.fx`）
- **想替换上游**：装 **mainline 构建**（包名 `org.fcitx.fcitx5.android`），覆盖上游

详细差异见 [构建版本与插件兼容性](/guide/builds-and-plugins)。

::: tip 推荐：先用 fx 构建迁移
fx 构建与上游并存，可让你在确认迁移结果无误后再决定是否卸载上游 —— 一旦切换出问题随时回退。
:::

## 第三步：在 fxliang 中导入

在刚装好的 fxliang 主程序中：

1. **设置 → 高级 → 导入用户数据**
2. 选择前面导出的 zip 文件
3. 应用会：
   - 暂停 Fcitx 服务
   - 校验 `metadata.json` 的包名前缀
   - **自动重命名 SharedPreferences**：上游里的 `org.fcitx.fcitx5.android_preferences.xml` 会改名为当前构建对应的文件名（fx 构建 → `org.fcitx.fcitx5.android.fx_preferences.xml`），无需用户介入
   - 复制 databases / external / Rime 数据到当前构建的私有目录
   - 弹出"请重启应用"通知并退出
4. 重新打开 fxliang —— 你看到的应该是上游里的所有自定义

## 第四步：检查与补全 fx 特有项

fx 新增的几类配置（上游备份里 **没有** 这些文件）：

- `config/TextKeyboardLayout.json`（fx 之后的版本中很可能存在，但上游较老备份没有）
- `config/PopupPreset.json`
- `config/KawaiiBarButtonsLayout.json`
- `config/fontset.json`

::: tip 缺失即默认
这些文件如果备份里没有，**导入后保持空缺，应用启动时使用内置默认值**，不会出错。你可以在 [键盘布局编辑器](/features/editor/layout-editor) / [主题编辑器](/features/theme/theme-editor) 等可视化编辑器中按需创建自己的版本。
:::

## 私有目录隔离说明

Android 系统保证不同包名应用的私有目录完全隔离：

```
/data/data/org.fcitx.fcitx5.android/        ← 上游 / mainline 构建
/data/data/org.fcitx.fcitx5.android.fx/     ← fxliang fx 构建
```

所以：

- **上游与 fx 构建在同一台手机上有两份独立的数据目录**，迁移过程是"从一份目录读 zip → 写入另一份"
- 卸载上游时只会清除上游目录，不会动 fxliang 目录
- 同理，卸载 fxliang 也不会动上游

## 注意事项

- **没有版本号校验**：导入逻辑只看包名前缀，不强制版本号匹配。理论上很旧的上游备份也能导入；但 **Rime 数据格式或某些配置 schema 跨大版本可能有破坏性变化**，导入后若 Rime 部署失败、配置不生效，请按 [Rime 集成增强](/features/rime-enhancements) 重新部署一次。
- **不会自动转换 layout / popup 格式**：fx 内部对 layout JSON 的 schema 做过若干扩展（如 `displayText` 多模式格式、`weight`、`rowHeightPercent`），但这些扩展都向后兼容 —— 上游的 layout JSON 可以直接被 fx 解析与渲染。
- **大文件 ok**：单个压缩包解压上限 1 GiB，对个人备份足够。
- **导入是覆盖语义**：若 fxliang 端已经有自定义数据，导入会覆盖（建议先在 fxliang 端做一次 **导出用户数据** 作为安全网）。

## 反向迁移（fxliang → 上游）

::: tip 默认即兼容
fxliang **导出时统一把 `metadata.packageName` 写成上游的规范包名 `org.fcitx.fcitx5.android`**，并把 SharedPreferences XML 一并改名为上游期望的文件名。无论你用 fx 构建还是 mainline 构建导出，得到的 zip 都能 **直接被上游导入**，不需要任何手工处理。
:::

| 反向迁移路径 | 是否可行 |
|-------------|:--------:|
| fxliang **fx** 构建 → 上游 | ✅ 默认 |
| fxliang **mainline** 构建 → 上游 | ✅ 默认 |

### 进入上游后会怎样

- 上游 **会读取** 通用部分：SharedPreferences、Rime 配置、字体、自定义词库 —— 与上游能力对应的数据照常生效
- fx 引入的 `TextKeyboardLayout.json` / `PopupPreset.json` / `KawaiiBarButtonsLayout.json` / `fontset.json` 等文件 **上游不读取**，留在私有目录里相当于无害的多余文件

### 旧版本导出的备份（兼容性提示）

如果你拿着 **改动前** 的 fxliang fx 构建导出的 zip（其中 `metadata.packageName = "org.fcitx.fcitx5.android.fx"`）想给上游吃，会被上游严格的等值校验拒绝。解决办法：

1. **升级 fxliang 到含此特性的版本后重新导出** —— 最简单
2. **借道 mainline 构建中转**：在 fxliang mainline 中导入再导出
3. **手改 metadata.json**：解压 zip → 把 `metadata.packageName` 字段改成 `org.fcitx.fcitx5.android`，并把 `shared_prefs/org.fcitx.fcitx5.android.fx_preferences.xml` 改名为 `shared_prefs/org.fcitx.fcitx5.android_preferences.xml` → 重新打包

## 相关页面

- [构建版本与插件兼容性](/guide/builds-and-plugins)
- [安装](/guide/installation)
- [Rime 集成增强](/features/rime-enhancements)
- [键盘布局编辑器](/features/editor/layout-editor)
