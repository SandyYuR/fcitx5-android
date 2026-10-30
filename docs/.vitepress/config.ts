import { defineConfig } from 'vitepress'
import { legacyMaintainerItems, legacySidebar } from './legacy-navigation'

export default defineConfig({
  title: '中州鹅',
  description: '中州鹅：Android 中文输入法，直连 librime，提供 Rime 方案、键盘定制、语音插件、剪贴板同步与维护文档',
  lang: 'zh-CN',
  lastUpdated: true,
  cleanUrls: true,
  transformPageData(pageData) {
    if (pageData.relativePath.startsWith('legacy/fcitx/')) {
      pageData.title = `旧版 Fcitx · ${pageData.title}`
    }
  },

  // 部署到 https://sandyyur.github.io/fcitx5-android/ 时使用此 base
  base: '/fcitx5-android/',

  head: [
    ['link', { rel: 'icon', type: 'image/png', href: '/fcitx5-android/logo.png' }],
    ['meta', { name: 'theme-color', content: '#24292e' }],
    ['script', { defer: '', src: 'https://busuanzi.ibruce.info/busuanzi/2.3/busuanzi.pure.mini.js' }],
  ],

  themeConfig: {
    nav: [
      {
        text: '指南',
        items: [
          { text: '介绍（这个版本是什么）', link: '/guide/introduction' },
            { text: '直连版迁移与当前状态', link: '/guide/direct-rime-migration' },
          { text: '安装', link: '/guide/installation' },
          { text: '快速上手', link: '/guide/quick-start' },
          { text: '构建版本与数据共存', link: '/guide/builds-and-plugins' },
          { text: '从上游迁移', link: '/guide/migrate-from-upstream' },
          { text: '核心概念', link: '/guide/concepts' },
        ],
      },
      {
        text: '功能',
        items: [
          { text: '功能总览', link: '/features/overview' },
          { text: 'Rime 引擎（内置）', link: '/features/rime-enhancements' },
          { text: '键盘特性', link: '/features/keyboard/float-keyboard' },
          { text: '编辑器', link: '/features/editor/layout-editor' },
          { text: '主题增强', link: '/features/theme/theme-editor' },
          { text: '设置界面外观与动画', link: '/features/theme/settings-appearance' },
          { text: '按键类型', link: '/features/keys/overview' },
          { text: 'Kawaii Bar', link: '/features/kawaii-bar' },
          { text: '剪贴板同步', link: '/features/clipboard-sync' },
          { text: '外部语音输入', link: '/features/voice-input' },
          { text: 'JS 语音插件', link: '/features/voice-input-js' },
          { text: '备份与恢复', link: '/features/backup-restore' },
          { text: '更新检查器', link: '/features/update-checker' },
        ],
      },
      { text: '手册', link: '/manual/DIRECT_RIME_USER_GUIDE_zh-CN' },
      {
        text: '旧版文档',
        items: [
          { text: '旧版原站首页', link: '/legacy/fcitx/' },
          { text: '全部 64 篇原站文档', link: '/legacy/fcitx/contents' },
          { text: '原站维护报告（13 篇）', link: '/legacy/fcitx/maintainer/' },
          { text: '旧版完整用户手册', link: '/legacy/fcitx/manual/RIME_ONLY_USER_GUIDE_zh-CN' },
        ],
      },
      {
        text: '维护',
        items: [
          { text: '维护分类索引', link: '/maintainer/' },
          { text: '2026-10-30 审阅与合入记录', link: '/maintainer/review-2026-10-30' },
          { text: '本批修复与合入准备', link: '/maintainer/review-2026-10' },
          { text: '中文实现协议', link: '/maintainer/implementation/' },
          { text: '139 项旧审阅对照', link: '/maintainer/DIRECT_RIME_LEGACY_REVIEW_COMPARISON_2026-10-24' },
          { text: '维护手册与 librime 更新', link: '/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN' },
          { text: '完整直连历史索引', link: '/direct-rime' },
          { text: '旧版维护报告（13 篇）', link: '/legacy/fcitx/maintainer/' },
        ],
      },
      { text: '疑难解答', link: '/troubleshooting/faq' },
      {
        text: '关于',
        items: [
          { text: '致谢与差异说明', link: '/about/credits' },
          { text: '参与文档修改', link: '/about/contribute-docs' },
          { text: '支持与捐赠', link: '/about/donate' },
        ],
      },
      { text: 'GitHub', link: 'https://github.com/SandyYuR/fcitx5-android' },
    ],

    sidebar: {
      '/legacy/fcitx/': legacySidebar,
      '/guide/': [
        {
          text: '入门',
          items: [
            { text: '介绍（这个版本是什么）', link: '/guide/introduction' },
            { text: '直连版迁移与当前状态', link: '/guide/direct-rime-migration' },
            { text: '安装', link: '/guide/installation' },
            { text: '快速上手', link: '/guide/quick-start' },
            { text: '构建版本与数据共存', link: '/guide/builds-and-plugins' },
            { text: '从上游迁移', link: '/guide/migrate-from-upstream' },
            { text: '核心概念', link: '/guide/concepts' },
          ],
        },
      ],
      '/features/': [
        {
          text: '总览',
          items: [
            { text: '功能总览', link: '/features/overview' },
            { text: 'Rime 引擎（内置）', link: '/features/rime-enhancements' },
          ],
        },
        {
          text: '键盘',
          items: [
            { text: '浮动键盘', link: '/features/keyboard/float-keyboard' },
            { text: '分体键盘', link: '/features/keyboard/split-keyboard' },
            { text: '单手模式', link: '/features/keyboard/one-handed' },
            { text: '调整模式', link: '/features/keyboard/adjust-mode' },
            { text: 'Compose Override', link: '/features/keyboard/compose-override' },
            { text: '滑动操作', link: '/features/keyboard/swipe-actions' },
          ],
        },
        {
          text: '按键类型',
          items: [
            { text: '总览', link: '/features/keys/overview' },
            { text: 'AlphabetKey', link: '/features/keys/alphabet-key' },
            { text: 'MacroKey', link: '/features/keys/macro-key' },
            { text: 'Fcitx 键名速查', link: '/features/keys/fcitx-keys' },
            { text: 'LayoutSwitchKey', link: '/features/keys/layout-switch-key' },
            { text: 'SymbolKey', link: '/features/keys/symbol-key' },
            { text: 'CapsKey', link: '/features/keys/caps-key' },
            { text: 'BackspaceKey', link: '/features/keys/backspace-key' },
            { text: 'ReturnKey', link: '/features/keys/return-key' },
            { text: 'SpaceKey', link: '/features/keys/space-key' },
            { text: 'CommaKey', link: '/features/keys/comma-key' },
            { text: 'LanguageKey', link: '/features/keys/language-key' },
          ],
        },
        {
          text: '编辑器',
          items: [
            { text: '键盘布局编辑器', link: '/features/editor/layout-editor' },
            { text: 'Popup 编辑器', link: '/features/editor/popup-editor' },
            { text: 'MacroKey 编辑器', link: '/features/editor/macrokey-editor' },
            { text: '字体集编辑器', link: '/features/editor/fontset-editor' },
          ],
        },
        {
          text: '主题',
          items: [
            { text: '设置界面外观与动画', link: '/features/theme/settings-appearance' },
            { text: '主题编辑器', link: '/features/theme/theme-editor' },
            { text: 'Monet 编辑器', link: '/features/theme/monet' },
            { text: '磨砂按键', link: '/features/theme/frosted-blur' },
            { text: 'QR 分享与导入', link: '/features/theme/share-import' },
          ],
        },
        {
          text: '候选与状态栏',
          items: [
            { text: '候选窗增强', link: '/features/candidate-window' },
            { text: 'Kawaii Bar 增强', link: '/features/kawaii-bar' },
          ],
        },
        {
          text: '其他功能',
          items: [
            { text: '剪贴板同步（内置）', link: '/features/clipboard-sync' },
            { text: '外部语音输入', link: '/features/voice-input' },
            { text: 'JS 语音插件（导入与开发）', link: '/features/voice-input-js' },
            { text: 'Foxy JavaScript Plugin Guide（英文原文）', link: '/features/voice-input-js-en' },
            { text: '备份与恢复', link: '/features/backup-restore' },
            { text: '更新检查器与镜像', link: '/features/update-checker' },
            { text: '共享导入与解压', link: '/features/shared-import' },
            { text: '文本编辑底部栏', link: '/features/text-editor' },
            { text: '在线编辑器', link: '/features/online-editor' },
          ],
        },
      ],
      '/manual/': [
        {
          text: '用户手册',
          items: [
            { text: '中州鹅用户手册', link: '/manual/DIRECT_RIME_USER_GUIDE_zh-CN' },
            { text: '历史 Fcitx 用户手册', link: '/manual/RIME_ONLY_USER_GUIDE_zh-CN' },
          ],
        },
      ],
      '/maintainer/': [
        {
          text: '当前批次与审阅',
          items: [
            { text: '维护分类索引', link: '/maintainer/' },
            { text: '2026-10-30 审阅与合入记录', link: '/maintainer/review-2026-10-30' },
            { text: '本批修复与合入准备', link: '/maintainer/review-2026-10' },
            { text: '当前 CI 与文档迁移验收', link: '/maintainer/audit/merge-prep-2026-10-27/' },
            { text: '139 项旧审阅对照', link: '/maintainer/DIRECT_RIME_LEGACY_REVIEW_COMPARISON_2026-10-24' },
            { text: '维护手册', link: '/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN' },
            { text: 'Windows 本机构建环境', link: '/maintainer/LOCAL_BUILD_ENVIRONMENT_zh-CN' },
            { text: '代码与文档存放约定', link: '/maintainer/DIRECT_RIME_DOCUMENTATION_SPLIT_2026-10-21' },
          ],
        },
        {
          text: '中文实现协议',
          items: [
            { text: '实现说明索引', link: '/maintainer/implementation/' },
            { text: 'ClipCascade 接收限额', link: '/maintainer/implementation/clipcascade-receive-limits' },
            { text: '自定义 hosts 下载', link: '/maintainer/implementation/hosts-downloads' },
            { text: '加密备份格式', link: '/maintainer/implementation/protected-backup-format' },
            { text: '整包恢复事务', link: '/maintainer/implementation/user-data-restore' },
          ],
        },
        {
          text: '本批原始验收', collapsed: true,
          items: [
            { text: '直连触屏基线', link: '/maintainer/audit/direct-rime-2026-10-25/' },
            { text: '布局回调与输入服务', link: '/maintainer/audit/queued-layout-2026-10-25/' },
            { text: '候选菜单与窗口焦点', link: '/maintainer/audit/sync-candidates-2026-10-25/' },
            { text: '提示绘制与 API 转发', link: '/maintainer/audit/status-hints-2026-10-27/' },
            { text: '编辑器保存与取消', link: '/maintainer/audit/editor-saves-2026-10-25/' },
            { text: '编辑器返回与重建', link: '/maintainer/audit/editor-results-2026-10-26/' },
            { text: '草稿一致性', link: '/maintainer/audit/draft-persistence-2026-10-26/' },
            { text: 'ClearURLs 过滤', link: '/maintainer/audit/clearurls-2026-10-25/' },
            { text: 'WebSocket 接收预算', link: '/maintainer/audit/websocket-receive-2026-10-26/' },
            { text: '持久下载与发布', link: '/maintainer/audit/hosts-downloads-2026-10-27/' },
            { text: '剩余项、语音与备份', link: '/maintainer/audit/remaining-checklist-2026-10-26/' },
            { text: '跨进程恢复与 WAL', link: '/maintainer/audit/user-data-restore-2026-10-27/' },
            { text: '关机学习读回', link: '/maintainer/audit/native-shutdown-2026-10-27/' },
            { text: 'perf 变基与行标题栏', link: '/maintainer/audit/perf-rebase-2026-10-27/' },
          ],
        },
        {
          text: '引擎与历史交付', collapsed: true,
          items: [
            { text: 'librime 源码维护', link: '/maintainer/DIRECT_RIME_SOURCE_MAINTENANCE_2026-10-21' },
            { text: '10 月 21 日引擎更新', link: '/maintainer/DIRECT_RIME_ENGINE_UPDATE_2026-10-21' },
            { text: '10 月 21 日交付', link: '/maintainer/DIRECT_RIME_RELEASE_READY_2026-10-21' },
            { text: '依赖与最后审阅', link: '/maintainer/DIRECT_RIME_FINAL_REVIEW_2026-10-19' },
            { text: 'MD3 界面检查', link: '/maintainer/DIRECT_RIME_MD3_AUDIT_2026-10-21' },
            { text: 'MD3 共享设置', link: '/maintainer/MD3_UI_EXTRACTION_2026-10-21' },
            { text: '部署重载与发行标识', link: '/maintainer/DIRECT_RIME_REDEPLOY_AND_IDENTITY_2026-10-20' },
            { text: '原 Fcitx 触摸对照', link: '/maintainer/DIRECT_RIME_SCREEN_PERF_2026-10-17' },
            { text: '冷路径与日志导出', link: '/maintainer/DIRECT_RIME_COLD_IO_AND_LOG_EXPORT_2026-10-19' },
            { text: '完整直连历史索引', link: '/direct-rime' },
          ],
        },
        {
          text: '旧版原站维护报告（13 篇）', collapsed: true,
          items: [
            { text: '旧版完整文档目录', link: '/legacy/fcitx/contents' },
            ...legacyMaintainerItems,
          ],
        },
        {
          text: '历史设计与维护记录', collapsed: true,
          items: [
            { text: '10 月 19 日整理与归档', link: '/maintainer/DIRECT_RIME_MERGE_READINESS_2026-10-19' },
            { text: 'MD3 设置实施', link: '/maintainer/SETTINGS_MD3_IMPLEMENTATION_2026-10-20' },
            { text: '旧 Fcitx 交接报告', link: '/maintainer/HANDOVER-rime-only' },
            { text: '原可行性方案（只读）', link: '/maintainer/archive/ARCHIVE_DIRECT_RIME_FEASIBILITY_ORIGINAL_2026-10-02' },
            { text: '原再评估（只读）', link: '/maintainer/archive/DIRECT_RIME_REEVALUATION_2026-10-07' },
          ],
        },
      ],
      '/troubleshooting/': [
        {
          text: '疑难解答',
          items: [
            { text: '常见问题', link: '/troubleshooting/faq' },
            { text: 'OEM 自启动与后台限制', link: '/troubleshooting/oem-startup' },
            { text: '反馈问题', link: '/troubleshooting/report-issue' },
          ],
        },
      ],
      '/about/': [
        {
          text: '关于',
          items: [
            { text: '致谢与差异说明', link: '/about/credits' },
            { text: '参与文档修改', link: '/about/contribute-docs' },
            { text: '支持与捐赠', link: '/about/donate' },
          ],
        },
      ],
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/SandyYuR/fcitx5-android' },
    ],

    footer: {
      message:
        '本项目基于 <a href="https://github.com/fxliang/fcitx5-android">fxliang/fcitx5-android</a> 的 fx 分支继续开发，上游为 <a href="https://github.com/fcitx5-android/fcitx5-android">fcitx5-android/fcitx5-android</a>。源码与文档以 LGPL-2.1+ 发布。',
      copyright:
        '© SandyYuR & Fcitx5 for Android contributors<br><span id="busuanzi_container_page_pv">本页访问量 <span id="busuanzi_value_page_pv"></span> 次</span>',
    },

    editLink: {
      pattern: 'https://github.com/SandyYuR/fcitx5-android/edit/docs/docs/:path',
      text: '在 GitHub 上编辑此页',
    },

    search: {
      provider: 'local',
      options: {
        locales: {
          root: {
            translations: {
              button: { buttonText: '搜索文档', buttonAriaLabel: '搜索文档' },
              modal: {
                noResultsText: '无匹配结果',
                resetButtonTitle: '清除',
                footer: {
                  selectText: '选择',
                  navigateText: '切换',
                  closeText: '关闭',
                },
              },
            },
          },
        },
      },
    },

    docFooter: {
      prev: '上一页',
      next: '下一页',
    },

    outline: {
      label: '本页目录',
    },

    lastUpdatedText: '最后更新',
    returnToTopLabel: '返回顶部',
    darkModeSwitchLabel: '主题',
    sidebarMenuLabel: '菜单',
  },
})
