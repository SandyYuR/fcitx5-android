// Exercise the actual built index with the browser's tokenizer and search options.
import assert from 'node:assert/strict'
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import MiniSearch from 'minisearch'
import { docsRoot } from './build-catalog.mjs'
import { searchIndexOptions, searchOptions } from '../.vitepress/search-shared.mjs'

const chunks = join(docsRoot, '.vitepress/dist/assets/chunks')
const indexes = readdirSync(chunks).filter(name => /^@localSearchIndexroot\..+\.js$/.test(name))
assert.equal(indexes.length, 1, 'Build the site first; exactly one root search index is required.')
const { default: serialized } = await import(pathToFileURL(join(chunks, indexes[0])))
const started = performance.now()
const index = await MiniSearch.loadJSONAsync(serialized, searchIndexOptions)
const currentJson = readFileSync(join(docsRoot, '.vitepress/dist/search/current-root.json'), 'utf8')
const currentIndex = await MiniSearch.loadJSONAsync(currentJson, searchIndexOptions)
const loadMs = Math.round(performance.now() - started)
const catalog = JSON.parse(readFileSync(join(docsRoot, '.vitepress/generated/catalog.json'), 'utf8'))
const entries = new Map(catalog.entries.map(entry => [entry.url, entry]))
const pathFor = id => id.split('#')[0].replace(/^\/fcitx5-android\//, '/').replace(/\.html$/, '')
const search = (query, includeHistory = false) => (includeHistory ? index : currentIndex).search(query, searchOptions)
  .filter(hit => includeHistory || entries.get(pathFor(hit.id))?.archived === false)

const checks = [
  ['原子替换', '/maintainer/DIRECT_RIME_MAINTAINER_GUIDE_zh-CN'],
  ['首次解锁', '/troubleshooting/oem-startup'],
  ['合成中', '/features/keyboard/compose-override'],
  ['分体键盘', '/features/keyboard/split-keyboard'],
  ['宏', '/features/keys/macro-key'],
  ['Rime SDK', '/maintainer/engine-update'],
  ['AI 文本助手', '/features/ai-assistant'],
  ['颜文字', '/features/symbol-panel']
]
for (const [query, expected] of checks) {
  assert(search(query).some(hit => pathFor(hit.id) === expected), `Cannot find ${expected} with “${query}”`)
}
assert.equal(entries.get('/maintainer/changes/inherited').archived, true)
assert.equal(entries.get('/maintainer/changes/feature-coverage-2026-10-30').archived, true)
assert.equal(entries.get('/maintainer/changes/feature-coverage').archived, false)
assert(!search('继承功能').some(hit => pathFor(hit.id) === '/maintainer/changes/inherited'))
assert(search('继承功能', true).some(hit => pathFor(hit.id) === '/maintainer/changes/inherited'),
  'Archived commit indexes must remain searchable when requested.')
for (const id of Object.values(JSON.parse(currentJson).documentIds)) {
  assert.equal(entries.get(pathFor(id))?.archived, false, `Archive leaked into the default download: ${id}`)
}
assert(currentIndex.documentCount > 0 && currentIndex.documentCount < index.documentCount)
console.log(JSON.stringify({ queries_checked: checks.length, scope_checks: 5,
  index_documents: index.documentCount, current_documents: currentIndex.documentCount,
  current_index_bytes: Buffer.byteLength(currentJson), index_bytes: Buffer.byteLength(serialized), load_ms: loadMs, errors: 0 }))
