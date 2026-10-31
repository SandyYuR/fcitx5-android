<script setup lang="ts">
import { computed, ref, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { withBase } from 'vitepress'
import catalog from '../../generated/catalog.json'

const props = withDefaults(defineProps<{ scope?: 'all' | 'maintenance' | 'history' | 'evidence' | 'legacy-notes' }>(), { scope: 'all' })
const query = ref('')
const version = ref('all')
const kind = ref('all')
const topic = ref('all')
let restoring = false
let scrollY = 0
let storageKey = ''
let catalogSearch = ''
function restoreFilters() {
  restoring = true
  catalogSearch = window.location.search
  const params = new URLSearchParams(catalogSearch)
  query.value = params.get('q') || ''
  version.value = ['current', 'legacy'].includes(params.get('version') || '') ? params.get('version')! : 'all'
  kind.value = kinds.value.includes(params.get('kind') || '') ? params.get('kind')! : 'all'
  topic.value = topics.value.includes(params.get('topic') || '') ? params.get('topic')! : 'all'
  nextTick(() => { restoring = false })
}
function rememberScroll() { scrollY = window.scrollY }
watch([query, version, kind, topic], () => {
  if (restoring || typeof window === 'undefined') return
  const url = new URL(window.location.href)
  for (const [key, value] of [['q', query.value], ['version', version.value], ['kind', kind.value], ['topic', topic.value]]) {
    if (value && value !== 'all') url.searchParams.set(key, value)
    else url.searchParams.delete(key)
  }
  catalogSearch = url.search
  window.history.replaceState(window.history.state, '', url)
})
onMounted(() => {
  storageKey = `docs-catalog:${window.location.pathname}:${props.scope}`
  restoreFilters()
  window.addEventListener('popstate', restoreFilters)
  window.addEventListener('scroll', rememberScroll, { passive: true })
  try {
    const saved = JSON.parse(sessionStorage.getItem(storageKey) || 'null')
    if (saved?.search === catalogSearch && Number.isFinite(saved.scroll)) nextTick(() => window.scrollTo(0, saved.scroll))
  } catch {}
})
onBeforeUnmount(() => {
  window.removeEventListener('popstate', restoreFilters)
  window.removeEventListener('scroll', rememberScroll)
  try { sessionStorage.setItem(storageKey, JSON.stringify({ search: catalogSearch, scroll: scrollY })) } catch {}
})
const firstPages = ['/guide/quick-start', '/guide/installation', '/manual/DIRECT_RIME_USER_GUIDE_zh-CN', '/features/overview']
const priority = (url: string) => { const index = firstPages.indexOf(url); return index < 0 ? firstPages.length : index }
const scoped = computed(() => catalog.entries.filter(entry => {
  if (props.scope === 'maintenance') return entry.maintenance
  if (props.scope === 'history') return entry.kind === '直连版历史' && !entry.source.endsWith('/index.md')
  if (props.scope === 'evidence') return entry.kind === '验证证据'
  if (props.scope === 'legacy-notes') return entry.kind === '旧版批注与方案' && !entry.source.endsWith('/index.md')
  return true
}))
const kinds = computed(() => [...new Set(scoped.value.map(e => e.kind))])
const topics = computed(() => [...new Set(scoped.value.map(e => e.topic))])
const results = computed(() => {
  const terms = query.value.toLocaleLowerCase().trim().split(/\s+/).filter(Boolean)
  return scoped.value.filter(entry => (version.value === 'all' || entry.version === version.value)
    && (kind.value === 'all' || entry.kind === kind.value)
    && (topic.value === 'all' || entry.topic === topic.value)
    && terms.every(term => `${entry.title} ${entry.description} ${entry.source} ${entry.topic}`.toLocaleLowerCase().includes(term)))
    .sort((a, b) => priority(a.url) - priority(b.url))
})
const groups = computed(() => [
  { name: '中州鹅 · 现行指南与维护', entries: results.value.filter(e => e.version === 'current' && !['直连版历史', '验证证据'].includes(e.kind)) },
  { name: '中州鹅 · 直连版历史与证据', entries: results.value.filter(e => e.version === 'current' && ['直连版历史', '验证证据'].includes(e.kind)) },
  { name: '靓企鹅中州韵 · 含 Fcitx 的历史版本', entries: results.value.filter(e => e.version === 'legacy') }
].filter(group => group.entries.length))
function reset() { query.value = ''; version.value = kind.value = topic.value = 'all' }
</script>

<template>
  <section class="docs-catalog" aria-label="文档目录筛选">
    <div class="catalog-controls">
      <label class="catalog-search">查找文档<input v-model="query" type="search" placeholder="标题、主题、日期或文件名" autocomplete="off" /></label>
      <div class="catalog-filters">
        <label v-if="scope === 'all' || scope === 'maintenance'">项目版本<select v-model="version"><option value="all">全部版本</option><option value="current">中州鹅 · 直连 Rime</option><option value="legacy">靓企鹅中州韵 · 含 Fcitx</option></select></label>
        <label>文档类型<select v-model="kind"><option value="all">全部类型</option><option v-for="item in kinds" :key="item" :value="item">{{ item }}</option></select></label>
        <label>主题<select v-model="topic"><option value="all">全部主题</option><option v-for="item in topics" :key="item" :value="item">{{ item }}</option></select></label>
      </div>
      <div class="catalog-status"><span aria-live="polite">找到 {{ results.length }} 篇 / 共 {{ scoped.length }} 篇</span><button type="button" @click="reset">清空筛选</button></div>
    </div>
    <p v-if="!results.length" class="catalog-empty">没有匹配的文档。试试减少关键词，或切换项目版本与文档类型。</p>
    <section v-for="group in groups" :key="group.name" class="catalog-group" :aria-label="group.name">
      <div class="catalog-group-title">{{ group.name }}<span>{{ group.entries.length }} 篇</span></div>
      <ul class="catalog-list">
        <li v-for="entry in group.entries" :key="entry.url">
          <div class="catalog-entry-heading"><a :href="withBase(entry.url)">{{ entry.title }}</a><span class="catalog-kind">{{ entry.kind }}</span></div>
          <p v-if="entry.description">{{ entry.description }}</p>
          <div class="catalog-entry-meta"><span>{{ entry.topic }}</span><span v-if="entry.date">{{ entry.date }}</span><code>{{ entry.source }}</code></div>
        </li>
      </ul>
    </section>
  </section>
</template>
