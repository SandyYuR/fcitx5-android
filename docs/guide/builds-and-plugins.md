# 构建版本与数据共存

靓企鹅·中州韵只有**一个构建版本**、**没有插件**。这一页说明它与上游、fxliang 版本在同一台设备上的共存关系，以及用户数据的边界。

## 主程序：唯一构建

| 维度 | 靓企鹅·中州韵 |
|------|------|
| 包名 | `org.fcitx.fcitx5.android.fx.rime` |
| Rime 引擎 | **内置**（静态链接 `librime.a`，无需插件 APK） |
| mainline 构建 / 插件 APK | **不存在**（已随 Rime-only 化裁剪） |
| debug 变体 | 包名 `org.fcitx.fcitx5.android.fx.rime.debug`，可与 Release 版并存 |
| 下载来源 | [SandyYuR/fcitx5-android Releases](https://github.com/SandyYuR/fcitx5-android/releases)（含时间戳 Nightly 预发布） |
| 应用内更新检查 | 默认查询本仓库的 Release |

## 与上游 / fxliang 的并存关系

三个版本的包名互不相同，**可以在同一台设备上并存**：

| 版本 | 包名 | 与本版 |
|------|------|--------|
| 上游 fcitx5-android | `org.fcitx.fcitx5.android` | ✅ 并存，互不影响 |
| fxliang fx 构建 | `org.fcitx.fcitx5.android.fx` | ✅ 并存，互不影响 |
| **靓企鹅·中州韵** | `org.fcitx.fcitx5.android.fx.rime` | —— |

并存时注意：

- 三者的应用私有目录**完全隔离**，各自的布局、主题、Rime 数据互不共享；
- **数据不会自动迁移**——从旧版本换到本版请走 [从上游迁移](/guide/migrate-from-upstream)；
- 系统的「默认输入法」同一时刻只能选一个，可在系统输入法切换器里随时换。

::: info 为什么没有「插件兼容性矩阵」
本版把 fcitx5-rime 直接编入主 APK，并删除了整套插件框架（插件发现、签名白名单、PluginFragment 等）。不存在需要主程序加载的外部插件 APK，自然也没有签名匹配、第三方插件放行这些概念。如果你需要拼音、码表或其他语言方案，请使用 fxliang 版本或上游。
:::

## 用户数据边界

- 用户数据（Rime 方案与用户词典、布局 JSON、主题、剪贴板历史、偏好设置）存放在本版自己的私有目录；Rime 用户数据目录位于应用外部文件目录下的 **`data/rime`**，可在 **中州韵设置** 中打开；
- **升级应用（同包名覆盖安装）不会删除用户数据**，也不会删除你放入的方案；
- **卸载应用会清空全部私有数据**——卸载前请先 **数据与备份 → 导出用户数据**；
- 备份 ZIP **未加密**且可能包含剪贴板历史等敏感内容，请妥善保管。

## 备份互通性

本版的导入器接受以 `org.fcitx.fcitx5.android` 开头的构建变体备份（含上游、fxliang fork 与本版），并会自动处理包名相关的偏好文件名。也就是说：

- 从上游 / fxliang 迁入：旧应用导出的备份可直接导入本版；
- 从本版导出的备份同样可供上游 / fxliang 导入。

完整流程与注意事项见 [从上游迁移](/guide/migrate-from-upstream)。

## 相关页面

- [安装](/guide/installation)
- [从上游迁移](/guide/migrate-from-upstream)
- [反馈问题](/troubleshooting/report-issue)
- [关于：致谢与差异说明](/about/credits)
