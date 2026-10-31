<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'
import { useData, useRouter } from 'vitepress'

const { site, localeIndex } = useData()
const router = useRouter()
const dialog = ref<HTMLDialogElement>()
const input = ref<HTMLInputElement>()
const trigger = ref<HTMLButtonElement>()
const query = ref('')
const includeHistory = ref(false)
const selected = ref(0)
const loading = ref(false)
const error = ref(false)
const index = shallowRef<any>()
const metadata = shallowRef(new Map<string, any>())
let indexLocale = ''

function pagePath(id: string) {
  const path = id.split('#')[0].replace(/\.html$/, '')
  return path.startsWith(site.value.base) ? '/' + path.slice(site.value.base.length) : path
}
function archived(id: string) {
  const path = pagePath(id)
  return /^\/(legacy\/|maintainer\/(history|audit|perf)\/)/.test(path)
    || metadata.value.get(path)?.kind === '上游参考'
}
const matches = computed(() => {
  if (!index.value || !query.value.trim()) return []
  return index.value.search(query.value.trim(), {
    fuzzy: 0.2, prefix: true, boost: { title: 4, text: 2, titles: 1 }
  }).filter((hit: any) => includeHistory.value || !archived(hit.id))
    .sort((a: any, b: any) => Number(archived(a.id)) - Number(archived(b.id)) || b.score - a.score)
})
const results = computed(() => matches.value.slice(0, 50))
watch([query, includeHistory], () => {
  selected.value = 0
  try { sessionStorage.setItem('docs-search-query', query.value) } catch {}
})

async function loadIndex() {
  if (loading.value || (index.value && indexLocale === localeIndex.value)) return
  loading.value = true
  error.value = false
  try {
    const [{ default: MiniSearch }, { default: indexes }, { default: catalog }] = await Promise.all([
      import('minisearch'), import('@localSearchIndex'), import('../../generated/catalog.json')
    ])
    const data = await indexes[localeIndex.value]()
    metadata.value = new Map(catalog.entries.map(entry => [entry.url, entry]))
    index.value = MiniSearch.loadJSON(data.default, { fields: ['title', 'titles', 'text'], storeFields: ['title', 'titles'] })
    indexLocale = localeIndex.value
  } catch { error.value = true }
  finally { loading.value = false }
}
function open() {
  if (dialog.value?.open) return
  dialog.value?.showModal()
  nextTick(() => input.value?.focus())
  void loadIndex()
}
function close() {
  dialog.value?.close()
  trigger.value?.focus({ preventScroll: true })
}
function keyboard(event: KeyboardEvent) {
  if (event.isComposing) return
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault()
    selected.value = Math.max(0, Math.min(results.value.length - 1, selected.value + (event.key === 'ArrowDown' ? 1 : -1)))
    nextTick(() => document.getElementById(`doc-search-result-${selected.value}`)?.scrollIntoView({ block: 'nearest' }))
  } else if (event.key === 'Enter' && results.value[selected.value]) {
    event.preventDefault()
    const href = results.value[selected.value].id
    close()
    void router.go(href)
  }
}
function shortcut(event: KeyboardEvent) {
  if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    open()
  }
}
onMounted(() => {
  try { query.value = sessionStorage.getItem('docs-search-query') || '' } catch {}
  window.addEventListener('keydown', shortcut)
})
onBeforeUnmount(() => window.removeEventListener('keydown', shortcut))
</script>

<template>
  <div class="VPNavBarSearch">
    <button ref="trigger" class="doc-search-trigger" type="button" aria-label="搜索文档" @click="open">
      <span aria-hidden="true">⌕</span><span class="doc-search-label">搜索文档</span><kbd>Ctrl K</kbd>
    </button>
    <Teleport to="body">
      <dialog ref="dialog" class="doc-search-dialog" aria-labelledby="doc-search-title" @cancel.prevent="close" @click="event => { if (event.target === dialog) close() }">
        <form method="dialog" @submit.prevent>
          <div class="doc-search-heading"><h2 id="doc-search-title">搜索文档</h2><button type="button" aria-label="关闭搜索" @click="close">关闭</button></div>
          <input ref="input" v-model="query" type="search" aria-label="搜索文档" placeholder="功能、设置项或关键词" autocomplete="off" aria-controls="doc-search-results" :aria-activedescendant="results.length ? `doc-search-result-${selected}` : undefined" @keydown="keyboard" />
          <label class="doc-search-scope"><input v-model="includeHistory" type="checkbox" />包含历史文档与上游参考</label>
        </form>
        <p v-if="loading" role="status">正在加载搜索…</p>
        <p v-else-if="error" role="alert">搜索加载失败。<button type="button" @click="loadIndex">重试</button></p>
        <p v-else-if="!query.trim()">输入关键词查找当前使用指南和维护文档。</p>
        <p v-else-if="!results.length" role="status">无匹配结果。可减少关键词，或勾选历史文档与上游参考。</p>
        <ul id="doc-search-results" class="doc-search-results" role="listbox" aria-label="搜索结果">
          <li v-for="(hit, i) in results" :id="`doc-search-result-${i}`" :key="hit.id" role="option" :aria-selected="selected === i">
            <a :href="hit.id" :class="{ selected: selected === i }" @focus="selected = i" @mouseenter="selected = i" @click="close">
              <span class="doc-search-breadcrumb">{{ [...(hit.titles || [])].join(' › ') }}</span>
              <strong>{{ hit.title }}</strong><span v-if="archived(hit.id)" class="doc-search-archive">历史／参考</span>
            </a>
          </li>
        </ul>
        <p class="doc-search-footer" aria-live="polite">{{ results.length ? `显示 ${results.length} 条，共 ${matches.length} 条；` : '' }}↑↓ 选择 · Enter 打开 · Esc 关闭</p>
      </dialog>
    </Teleport>
  </div>
</template>

<style scoped>
.doc-search-trigger { display:flex; align-items:center; gap:8px; padding:9px 12px; margin-left:24px; border-radius:8px; background:var(--vp-c-bg-soft); color:var(--vp-c-text-2); font-size:13px; }
.doc-search-trigger > span:first-child { font-size:23px; line-height:20px; }
kbd { padding:0 5px; border:1px solid var(--vp-c-divider); border-radius:4px; font-size:11px; }
.doc-search-dialog { width:min(760px,calc(100vw - 32px)); max-height:85dvh; margin:8vh auto auto; padding:20px; border:1px solid var(--vp-c-divider); border-radius:12px; background:var(--vp-c-bg); color:var(--vp-c-text-1); box-shadow:0 20px 80px #0004; }
.doc-search-dialog::backdrop { background:#0008; }
.doc-search-heading { display:flex; align-items:center; justify-content:space-between; gap:12px; margin-bottom:12px; }
.doc-search-heading h2 { font-size:18px; font-weight:600; }
.doc-search-dialog button { color:var(--vp-c-brand-1); padding:8px; border-radius:6px; }
.doc-search-dialog input[type=search] { width:100%; height:46px; padding:8px 12px; border:1px solid var(--vp-c-divider); border-radius:6px; background:var(--vp-c-bg-alt); color:inherit; font-size:16px; }
.doc-search-dialog :focus-visible { outline:2px solid var(--vp-c-brand-1); outline-offset:2px; }
.doc-search-scope { display:flex; align-items:center; gap:8px; min-height:44px; font-size:13px; color:var(--vp-c-text-2); }
.doc-search-scope input { width:17px; height:17px; appearance:auto; accent-color:var(--vp-c-brand-1); }
.doc-search-results { list-style:none; padding:0; margin:8px 0; max-height:52dvh; overflow-y:auto; }
.doc-search-results a { display:block; padding:12px; margin:6px 2px; border:1px solid var(--vp-c-divider); border-radius:7px; }
.doc-search-results a.selected { border-color:var(--vp-c-brand-1); background:var(--vp-c-brand-soft); }
.doc-search-results strong { font-size:15px; line-height:1.65; }
.doc-search-breadcrumb { display:block; font-size:12px; color:var(--vp-c-text-2); }
.doc-search-archive { margin-left:8px; font-size:11px; color:var(--vp-c-text-2); }
.doc-search-footer { margin-top:12px; color:var(--vp-c-text-2); font-size:12px; }
@media(max-width:767px) { .doc-search-label,kbd { display:none; } .doc-search-trigger { margin-left:12px; padding:8px; } .doc-search-dialog { padding:14px; margin-top:4vh; } }
</style>
