# 更新检查器与镜像

fx 内置了 **GitHub Release 更新检查器**，可直接从 [fxliang/fcitx5-android Releases](https://github.com/fxliang/fcitx5-android/releases) 拉取最新版本并安装，支持镜像下载。

## 能力

- 检查 fxliang 仓库的最新 GitHub Release
- 下载主程序与插件 APK
- 调用系统安装器完成升级（保留配置）
- **镜像下载源**：可配置代理 / 镜像加速器地址

## 速度显示

- 下载速度采用 **平滑算法（去抖）** 显示，避免数字剧烈跳变
- 进度条 + 实时速率

## 版本号识别

fx 改进了版本识别逻辑：

- 从 Release 资产文件名中提取 `git describe` 完整版本号
- 在 UI 中显示完整字符串（含 commit hash 段），而非仅显示纯 tag
- 不依赖 Release 名称的特定格式

## 配置入口

进入 **设置 → 关于 → 检查更新**：

- 立即检查
- 镜像源配置（自定义 URL）
- 自动检查频率

## 与 F-Droid 的区别

- 本检查器仅追踪 fxliang 仓库的 Release，**不会自动切换到上游**
- 如需切换到上游 / F-Droid 渠道，请手动卸载并重装（详见 [安装](/guide/installation)）

## 相关页面

- [安装](/guide/installation)
- [反馈问题](/troubleshooting/report-issue)
