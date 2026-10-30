# 安装

## 下载主应用

日常安装选择成功的 Commit CI 生成的签名主应用，或 [Releases](https://github.com/SandyYuR/fcitx5-android/releases) 的中州鹅 Nightly。
默认分支已合入直连版，历史 Fcitx 发行包仍保留；安装时核对 Release 的提交、包名与签名，历史归档不是当前下载推荐。
Actions 产物需要登录 GitHub，且有保留期限。

日常使用完整 `:app` 主应用。名称为“中州鹅引擎诊断”、包名带 `.lab` 的 APK 用于引擎诊断。
按设备 ABI 选择安装包，常见设备使用 arm64-v8a。最低 Android 6.0/API 23。

## 升级前备份

Release 主包名为 `org.fcitx.fcitx5.android.fx.rime`，与本仓库旧 Fcitx 版相同。
同包名、同签名且版本允许时可覆盖安装；换签名、换包名或卸载前先导出完整备份。
旧直连测试包的数据按[迁移说明](/guide/migrate-from-upstream)恢复。

## 启用与首次输入

1. 安装后打开应用，按首次引导在系统中启用并选择“中州鹅”。
2. 按需授予存储、振动、通知或剪贴板相关权限。
3. 在“输入与候选 → 中州韵设置”导入完整 Rime 方案，或打开当前用户数据目录手动放置。
4. 设置 `schema_list`，执行部署，等待完成后测试中文输入。

本版不预置中文方案、完整词典或语言模型。键盘能弹出但没有中文候选时，先检查是否已导入并部署。
多个 Rime 输入法分别使用独立工作目录，参见[构建版本与数据共存](/guide/builds-and-plugins)。

键盘无法保持运行时，查看 [OEM 自启动与后台限制](/troubleshooting/oem-startup)。


## Debug 与 Release 共存

Debug 完整主应用使用 `.debug` 包名后缀，与 Release 独立安装、独立保存数据。首次启用 Debug 也需重新导入方案并部署；不能把它当成会共享 Release 数据的覆盖升级。
