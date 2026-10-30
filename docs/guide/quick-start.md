# 第一次使用中州鹅

完成“安装并启用 → 导入完整方案 → 部署 → 输入”四步即可开始。**APK 不预装中文方案、完整词典或语言模型**；已经使用过其他版本的用户，先[备份和迁移](/guide/migrate-from-upstream)。

## 第一步：安装并启用

按[安装说明](/guide/installation)下载完整主应用，打开后按引导启用并选择“中州鹅”。不要把仅用于测试的 `.lab` 引擎诊断 APK 当作输入法。
Debug 与 Release 是数据独立的两个应用；刚装 Debug 时也要重新准备方案。

## 第二步：导入 Rime 方案

打开 **输入与候选 → 中州韵设置**，选择方案 ZIP，按提示选择应用目录或可访问的外部目录。ZIP 根目录应直接包含 `default.yaml` 或 `default.custom.yaml` 以及完整依赖，不能只有一个外层仓库目录。
也可以在当前用户数据目录手动放入方案、词典、Lua 和模型等文件。只放一个 `.schema.yaml` 通常不足以运行复杂方案。

在 `default.custom.yaml` 中指定方案：

```yaml
patch:
  schema_list:
    - schema: your_schema
```

把 `your_schema` 改成真实 `schema_id`。如果导入的配置已经包含正确方案列表，不需要重复添加。
多个输入法不要共用正在写入的 Rime 工作目录，参见[数据共存](/guide/builds-and-plugins)。

## 第三步：部署后测试输入

在中州韵设置执行部署，等待完成后在普通输入框输入编码，确认出现中文候选并能上屏。
修改 YAML、词典或 Lua 后重新部署；部署期间等待引擎恢复，不继续按键。
没有候选时先核对方案列表、依赖和部署日志，再查看[常见问题](/troubleshooting/faq)。

## 第四步：切换语言和调整键盘

- 语言键短按发送独立 Shift，由方案的 `ascii_composer` 决定中英文切换；长按打开 Rime 方案选单。
- 展开状态区管理方案开关；候选注释由方案本身决定。
- Rime 方案和屏幕键盘布局分别配置。到虚拟键盘设置调整布局、分体、浮动或单手模式。
- 外观页调整主题和字体；设置界面配色修改即时生效，见[外观指南](/features/theme/settings-appearance)。

## 可选：使用万象方案

万象用户从[官方仓库](https://github.com/amzxyz/rime-wanxiang)按相应版本说明准备 base 全拼、词典、Lua 和所需模型。“启用简码”不等于“改用双拼”。
历史测试采用的固定版本和模型只用于复现实验，不是日常使用的强制版本；升级前保留个人补丁与用户词库。
更多说明见[用户手册](/manual/DIRECT_RIME_USER_GUIDE_zh-CN#导入方案与万象)。

## 接下来

[定制键盘](/features/editor/layout-editor) · [启用语音](/features/voice-input-js) · [符号面板](/features/symbol-panel) · [加密备份](/features/backup-restore) · [功能总览](/features/overview)
