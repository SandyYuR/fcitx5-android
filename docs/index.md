---
layout: home
hero:
  name: 中州鹅
  text: 你的 Rime 手机键盘
  tagline: 直连 Rime 引擎，自由定制键盘、候选与主题，连接语音输入和日常工具。
  image:
    src: /logo.png
    alt: 中州鹅
  actions:
    - theme: brand
      text: 安装与第一次输入
      link: /guide/quick-start
    - theme: alt
      text: 查看全部功能
      link: /features/overview
features:
  - title: 你的方案，你的词库
    details: 直接使用 librime。导入自己熟悉的 Rime 方案，管理词典、开关、部署与同步。
    link: /features/rime-enhancements
    linkText: 了解方案与部署
  - title: 按习惯定制键盘
    details: 浮动、分体与单手模式；编辑键位、长按、滑动和宏，也能在电脑浏览器中编辑。
    link: /features/editor/layout-editor
    linkText: 打开布局指南
  - title: 候选与符号顺手可用
    details: 配置候选字号、注释与序号，浏览长候选；符号面板可选左侧分组或底部栏。
    link: /features/symbol-panel
    linkText: 查看符号与候选
  - title: 键盘与设置各有风格
    details: 自定义主题、字体、图标与 Monet 取色；设置页配色即时生效，动画单独调整。
    link: /features/theme/settings-appearance
    linkText: 调整界面外观
  - title: 多种语音输入方式
    details: 内置千问、豆包、腾讯云识别与翻译 JS 插件，也支持导入脚本或接入 AIDL 应用。
    link: /features/voice-input-js
    linkText: 开始语音输入
  - title: 数据有备份，工具能互通
    details: 剪贴板历史与远端同步、加密完整备份、局域网编辑和支持断点恢复的更新下载。
    link: /features/backup-restore
    linkText: 管理数据与备份
---

## 按你的需要开始

<div class="entry-grid">
  <a class="entry-card" href="./manual/DIRECT_RIME_USER_GUIDE_zh-CN"><span class="eyebrow">日常使用</span><strong>从第一次输入到个性化键盘</strong><p>安装、方案、布局、主题、语音和数据管理，一份手册串起主要操作。</p><span class="entry-link">阅读完整用户手册 →</span></a>
  <a class="entry-card" href="./maintainer/"><span class="eyebrow">开发维护</span><strong>找到实现、验证与演进记录</strong><p>现行构建手册、接口协议、逐提交文档对照和原始验证附件分区收录。</p><span class="entry-link">进入维护文档 →</span></a>
</div>

## 先分清版本，再查找资料

本站默认介绍 **中州鹅：Rime 直连版**。早期 **靓企鹅中州韵** 保留了 Fcitx 中间层，其原站文档、维护报告和后续批注独立归档。
同一个 `fx-rime-only` 分支名曾用于两种架构，不能仅凭旧文章中的分支名判断适用版本。

[版本区别与历史档案](/legacy/) · [全部文档目录](/contents) · [常见问题](/troubleshooting/faq) · [提交与文档核对记录](/maintainer/changes/)

项目继承 Fcitx5 for Android 与 fxliang fx 分支的 Android 前端成果，直接调用 librime，独立维护。来源与许可证见[致谢](/about/credits)。
