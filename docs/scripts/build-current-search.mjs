// Derive the default index from VitePress's real headings and URLs after its build.
// The full index remains available for the optional history search.
import { mkdirSync, readFileSync, readdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { pathToFileURL } from 'node:url'
import MiniSearch from 'minisearch'
import { docsRoot } from './build-catalog.mjs'
import { searchIndexOptions } from '../.vitepress/search-shared.mjs'

const chunks = join(docsRoot, '.vitepress/dist/assets/chunks')
const destination = join(docsRoot, '.vitepress/dist/search')
const catalog = JSON.parse(readFileSync(join(docsRoot, '.vitepress/generated/catalog.json'), 'utf8'))
const entries = new Map(catalog.entries.map(entry => [entry.url, entry]))
mkdirSync(destination, { recursive: true })
let count = 0
for (const name of readdirSync(chunks)) {
  const locale = name.match(/^@localSearchIndex([^.]+)\..+\.js$/)?.[1]
  if (!locale) continue
  const { default: full } = await import(pathToFileURL(join(chunks, name)))
  const index = await MiniSearch.loadJSONAsync(full, { ...searchIndexOptions, autoVacuum: false })
  const ids = Object.values(JSON.parse(full).documentIds)
  const archived = ids.filter(id => {
    const path = id.split('#')[0].replace(/^\/fcitx5-android\//, '/').replace(/\.html$/, '')
    return entries.get(path)?.archived !== false
  })
  index.discardAll(archived)
  await index.vacuum({ batchSize: 1000, batchWait: 0 })
  const current = JSON.stringify(index)
  writeFileSync(join(destination, `current-${encodeURIComponent(locale)}.json`), current)
  console.log(JSON.stringify({ locale, current_documents: index.documentCount,
    current_bytes: Buffer.byteLength(current), full_bytes: Buffer.byteLength(full) }))
  count++
}
if (!count) throw new Error('No VitePress search indexes found; build the site first.')
