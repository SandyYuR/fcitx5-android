import { defineConfig } from 'vitepress'

export default defineConfig({
  title: 'Fcitx5 for Android · fx',
  description: 'fxliang/fcitx5-android (fx 分支) 的最终用户文档 —— 聚焦相对上游新增与修改的功能',
  lang: 'zh-CN',
  lastUpdated: true,
  cleanUrls: true,

  // 部署到 https://fxliang.github.io/fcitx5-android/ 时使用此 base
  base: '/fcitx5-android/',

  head: [
    ['link', { rel: 'icon', type: 'image/png', href: '/fcitx5-android/logo.png' }],
    ['meta', { name: 'theme-color', content: '#5b73e8' }],
  ],

  themeConfig: {
    nav: [
      {
        text: '指南',
        items: [
          { text: '介绍（fx 是什么）', link: '/guide/introduction' },
          { text: '安装', link: '/guide/installation' },
          { text: '构建版本与插件兼容性', link: '/guide/builds-and-plugins' },
          { text: '从上游迁移', link: '/guide/migrate-from-upstream' },
          { text: '快速上手', link: '/guide/quick-start' },
        ],
      },
      {
        text: '功能',
        items: [
          { text: '功能总览', link: '/features/overview' },
          { text: '在线编辑器', link: '/features/online-editor' },
          { text: '键盘特性', link: '/features/keyboard/float-keyboard' },
          { text: '编辑器', link: '/features/editor/layout-editor' },
          { text: '主题增强', link: '/features/theme/theme-editor' },
          { text: '按键类型', link: '/features/keys/overview' },
          { text: 'Kawaii Bar', link: '/features/kawaii-bar' },
          { text: '剪贴板同步', link: '/features/clipboard-sync' },
          { text: '更新检查器', link: '/features/update-checker' },
        ],
      },
      { text: '疑难解答', link: '/troubleshooting/faq' },
      { text: '捐赠', link: '/about/donate' },
      {
        text: '关于',
        items: [
          { text: '参与文档修改', link: '/about/contribute-docs' },
          { text: '致谢与差异说明', link: '/about/credits' },
        ],
      },
      { text: 'GitHub', link: 'https://github.com/fxliang/fcitx5-android' },
    ],

    sidebar: {
      '/guide/': [
        {
          text: '入门',
          items: [
            { text: '介绍（fx 是什么）', link: '/guide/introduction' },
            { text: '安装', link: '/guide/installation' },
            { text: '构建版本与插件兼容性', link: '/guide/builds-and-plugins' },
            { text: '从上游迁移', link: '/guide/migrate-from-upstream' },
            { text: '快速上手', link: '/guide/quick-start' },
            { text: '核心概念', link: '/guide/concepts' },
          ],
        },
      ],
      '/features/': [
        {
          text: '总览',
          items: [
            { text: 'fx 功能总览', link: '/features/overview' },
            { text: '在线编辑器', link: '/features/online-editor' },
          ],
        },
        {
          text: '键盘',
          items: [
            { text: '浮动键盘', link: '/features/keyboard/float-keyboard' },
            { text: '分屏键盘', link: '/features/keyboard/split-keyboard' },
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
          text: '其他增强',
          items: [
            { text: '剪贴板同步（内置）', link: '/features/clipboard-sync' },
            { text: '更新检查器与镜像', link: '/features/update-checker' },
            { text: '共享导入与解压', link: '/features/shared-import' },
            { text: '文本编辑器插件', link: '/features/text-editor' },
            { text: 'Rime 集成增强', link: '/features/rime-enhancements' },
          ],
        },
      ],
      '/troubleshooting/': [
        {
          text: '疑难解答',
          items: [
            { text: '常见问题', link: '/troubleshooting/faq' },
            { text: 'OEM 关联启动', link: '/troubleshooting/oem-startup' },
            { text: '反馈问题', link: '/troubleshooting/report-issue' },
          ],
        },
      ],
      '/about/': [
        {
          text: '关于',
          items: [
            { text: '捐赠', link: '/about/donate' },
            { text: '参与文档修改', link: '/about/contribute-docs' },
            { text: '致谢与差异说明', link: '/about/credits' },
          ],
        },
      ],
    },

    socialLinks: [
      { icon: 'github', link: 'https://github.com/fxliang/fcitx5-android' },
    ],

    footer: {
      message:
        '本项目基于 <a href="https://github.com/fcitx5-android/fcitx5-android">fcitx5-android/fcitx5-android</a> 修改。源码与文档以 LGPL-2.1 发布。',
      copyright: '© fxliang & Fcitx5 for Android contributors',
    },

    editLink: {
      pattern: 'https://github.com/fxliang/fcitx5-android/edit/fx/docs/:path',
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
