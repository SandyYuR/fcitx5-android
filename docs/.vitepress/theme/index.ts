import { defineAsyncComponent, h } from 'vue'
import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import DocContext from './components/DocContext.vue'
import RouteRedirect from './components/RouteRedirect.vue'
import './style.css'

export default {
  extends: DefaultTheme,
  Layout: () => h(DefaultTheme.Layout, null, {
    'doc-before': () => h(DocContext)
  }),
  enhanceApp({ app }) {
    app.component('DocsCatalog', defineAsyncComponent(() => import('./components/DocsCatalog.vue')))
    app.component('RouteRedirect', RouteRedirect)
  }
} satisfies Theme
