# 更新检查器与镜像

中州鹅内置 **GitHub Release 更新检查器**，直接从本仓库 [SandyYuR/fcitx5-android Releases](https://github.com/SandyYuR/fcitx5-android/releases) 拉取最新版本并安装。

## 能力

- 检查本仓库的最新 GitHub Release（`releases` 列表最新一条，**含 Nightly 预发布**）
- 查看 Release 说明并下载主程序 APK
- 校验资产 SHA-256 摘要（Release 附带时）
- 调用系统安装器完成升级（保留配置）
- **镜像规则**：用正则替换改写下载地址，可配置代理 / 镜像加速器；保存前会用示例 URL 做连通性测试
- **自定义 hosts**：可从 `https://` 来源拉取 hosts 映射，改写 GitHub API 与下载的 DNS 解析，改善直连失败

## 速度显示

- 下载速度采用 **平滑算法（去抖）** 显示，避免数字剧烈跳变
- 进度条 + 实时速率

## 版本号识别

- 从 Release 资产文件名中提取 `git describe` 完整版本号
- 在 UI 中显示完整字符串（含 commit hash 段），而非仅显示纯 tag
- 不依赖 Release 名称的特定格式；Nightly 预发布也能正确识别

## 自定义 hosts 下载与恢复

Android 10 及以上通过 hosts 映射直接下载时，任务交给 WorkManager 持久执行。
退出更新页面后下载可以继续，重新打开页面可查看状态；取消下载需要使用页面或通知中的取消入口。
普通下载和较旧 Android 版本仍使用系统 DownloadManager。

服务端提供有效强 ETag 时可以断点续传；验证器缺失、变化或响应不适合续传时会重新下载。
下载上限为 512 MiB，并预留空间用于发布副本。Release 提供 SHA-256 时会核对摘要，文件完成校验后才发布。

Android 可能延后后台工作；强行停止应用后，需要重新打开应用才有机会继续执行。
已结束任务的重试使用新的任务身份，避免旧结果覆盖新下载。
实现和恢复范围见[自定义 hosts 下载任务](../maintainer/implementation/hosts-downloads.md)。

## 配置入口

进入 **应用主页 → 右上角菜单 → 关于 → 当前版本**：

- 立即检查（结果有短时缓存，避免触发 GitHub 匿名速率限制）
- 镜像规则配置
- 自定义 hosts 开关与来源

## 与其他渠道的区别

- 本检查器**只追踪本仓库**（SandyYuR/fcitx5-android）的 Release，不会切换到 fxliang 或上游
- 如需使用其他发行版（fxliang / 上游），属于**不同包名的并存应用**，请分别安装，见 [构建版本与数据共存](/guide/builds-and-plugins)

## 相关页面

- [安装](/guide/installation)
- [反馈问题](/troubleshooting/report-issue)
