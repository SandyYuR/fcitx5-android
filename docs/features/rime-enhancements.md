# Rime 集成增强

::: warning 待补充
本页内容正在撰写修订中。
:::

使用 [Rime](https://rime.im/) 插件时，可以获得以下增强体验。

## 主要能力

- **方案选择器**：状态区直接展示当前 Rime 方案，点击切换
- **状态区多选**：通用的多选下拉，Rime 方案选择即是其典型场景
- **Shift_L Toggle 开关**：可选择禁用 fcitx5 默认的 Shift_L 切换中英行为（配合 fxliang/fcitx5-rime 的 patch）
- **更清晰的设置页**：Rime 相关配置以可视化的方式呈现

## 配置文件兼容性

- 桌面端 Rime 配置（`default.custom.yaml`、词库、方案）放入应用私有目录的 `rime/` 即可直接使用
- 通过 Rime 的"重新部署"机制刷新

## 注意事项

- 国产 ROM 用户务必为 Rime 插件开启 **关联启动 / 自启动**：[OEM 关联启动](/troubleshooting/oem-startup)
- 删除应用前请备份 `rime/` 目录

## 相关页面

- [OEM 关联启动](/troubleshooting/oem-startup)
- [Kawaii Bar 增强](/features/kawaii-bar)
- [候选窗增强](/features/candidate-window)
