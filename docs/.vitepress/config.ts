import { defineConfig } from 'vitepress'
import { nav, sidebar } from './navigation'

export default defineConfig({
  title: '中州鹅',
  description: '中州鹅：Android 中文输入法，直连 librime，提供 Rime 方案、键盘定制、语音插件、剪贴板同步与维护文档',
  lang: 'zh-CN',
  lastUpdated: true,
  cleanUrls: true,
  transformPageData(pageData) {
    if (pageData.relativePath.startsWith('legacy/fcitx/')) {
      pageData.title = `靓企鹅中州韵（历史） · ${pageData.title}`
      pageData.frontmatter.editLink = false
    } else if (/^maintainer\/(history|audit|perf)\//.test(pageData.relativePath)) {
      pageData.title = `中州鹅历史记录 · ${pageData.title}`
    }
  },

  // 部署到 https://sandyyur.github.io/fcitx5-android/ 时使用此 base
  base: '/fcitx5-android/',

  head: [
    ['link', { rel: 'icon', type: 'image/png', href: '/fcitx5-android/logo.png' }],
    ['meta', { name: 'theme-color', content: '#176b68' }],
    ['script', { defer: '', src: 'https://busuanzi.ibruce.info/busuanzi/2.3/busuanzi.pure.mini.js' }],
  ],

  themeConfig: {
    nav,
    sidebar,

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
        // Search keeps history accessible while distinguishing it from current instructions.
        _render(src, env, md) {
          const html = md.render(src, env)
          if (env.frontmatter?.search === false || env.frontmatter?.redirect) return ''
          const path = env.relativePath || ''
          const label = /^legacy\/fcitx\/(notes|planning)\//.test(path) ? '【靓企鹅中州韵·批注与方案】' : path.startsWith('legacy/fcitx/') ? '【靓企鹅中州韵·原站】' : /^maintainer\/(history|audit|perf)\//.test(path) ? '【中州鹅·历史记录】' : ''
          return label ? html.replace(/(<h1[^>]*>)/, `$1${label}`) : html
        },
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
      level: [2, 3],
    },

    lastUpdatedText: '最后更新',
    returnToTopLabel: '返回顶部',
    darkModeSwitchLabel: '主题',
    sidebarMenuLabel: '菜单',
  },
})
