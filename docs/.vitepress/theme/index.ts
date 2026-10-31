import { defineAsyncComponent, h } from 'vue'
import DefaultTheme from 'vitepress/theme'
import type { Theme } from 'vitepress'
import DocContext from './components/DocContext.vue'
import RouteRedirect from './components/RouteRedirect.vue'
import HomeStartNote from './components/HomeStartNote.vue'
import HomeKeyboardPreview from './components/HomeKeyboardPreview.vue'
import './style.css'

export default {
  extends: DefaultTheme,
  Layout: () => h(DefaultTheme.Layout, null, {
    'doc-before': () => h(DocContext),
    'home-hero-actions-after': () => h(HomeStartNote),
    'home-hero-image': () => h(HomeKeyboardPreview)
  }),
  enhanceApp({ app }) {
    app.component('DocsCatalog', defineAsyncComponent(() => import('./components/DocsCatalog.vue')))
    app.component('RouteRedirect', RouteRedirect)
  }
} satisfies Theme
