# 构建版本与数据共存

## 主应用与诊断工具

| 项目 | 包名 / 用途 |
| --- | --- |
| 中州鹅 Release 主应用 | `org.fcitx.fcitx5.android.fx.rime`，完整键盘和设置，日常使用 |
| 中州鹅 Debug 主应用 | `org.fcitx.fcitx5.android.fx.rime.debug`，可与 Release 共存，数据独立 |
| 旧 Fcitx 版 | 与主应用同包名，同签名时覆盖升级，不能同时安装 |
| 旧直连测试包 | `io.github.sandyyur.rime.direct`，与当前主包数据隔离，需迁移 |
| 引擎诊断工具 | `org.fcitx.fcitx5.android.fx.rime.lab`，仅用于验证引擎 |
| 上游 / fxliang | 通常为 `org.fcitx.fcitx5.android` / `org.fcitx.fcitx5.android.fx`，与主包不同 |

安装包以实际 manifest 中的包名和签名为准，不用 Debug/Release 文件名推断是否可并存。
主应用已内置 Rime，不加载独立引擎插件 APK。

## 用户数据边界

应用私有数据和各自的应用外部目录按包名隔离；更换包名不会自动搬移数据。
覆盖升级通常保留数据，卸载会清除私有数据，操作前导出完整备份。
外部 Rime 目录可能被多个输入法访问，这不代表可以同时把它当工作库打开。

LevelDB 对用户词库加独占锁。共用工作目录时，可能出现以前学习和新学习的词都不进入候选。
应强行停止其他使用者，重启本输入法验证，再分开工作目录，通过同步目录交换用户词典。
关闭键盘或划掉后台页面通常不会结束输入法进程；不要删除 `LOCK` 文件绕过占用。

## 备份互通性

优先在旧应用导出，再在中州鹅导入并检查。完整备份格式、主题及布局字段可能随版本变化；
不支持时分开迁移 Rime 用户文件和布局/主题，不能保证任意 fork 双向完全兼容。

参见[迁移说明](/guide/migrate-from-upstream)和[用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN)。
