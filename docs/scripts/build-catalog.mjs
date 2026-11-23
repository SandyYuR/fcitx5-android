// Build one complete catalog from source files. Redirects are navigation aids, not extra documents.
import { readdirSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { isHistoricalSource } from '../.vitepress/search-shared.mjs'

export const docsRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

// Directory rules come first, then path keywords; dated reports keep the topic of what they examine.
// Pages that match no keyword fall back by area: maintenance records to 构建与审阅, user pages to 使用与维护.
const topicRules = [
  ['语音输入', /voice|asr|ai-assistant|js-plugin/],
  ['数据与备份', /backup|restore|data-directory|shutdown|share-import|shared-import/],
  ['剪贴板与网络', /clipboard|clipcascade|clearurls|websocket/],
  ['主题与设置', /theme|monet|md3|settings|font|appearance|design-system|icon/],
  ['键盘与编辑器', /editor|layout|keys\/|keyboard|symbol|swipe|foxy|kawaii|macrokey/],
  ['输入与候选', /candidate|responsiveness|rebind|status-hint|password|input-statistics|input-reliability|inline-suggestion|session/],
  ['性能与诊断', /perf\/|screen_perf|cold_io|performance|user_log|latency/],
  ['Rime 引擎', /engine|source_maintenance|rime-|implementation_2026|decoupling|syllable|evo-profile|redeploy|correctness-contracts/],
  ['构建与审阅', /build|release|merge|rebase|documentation|review|update|hosts|cleanup|unused|audit|squash|alignment/],
]

export function topicFor(path) {
  const p = path.toLowerCase()
  // Dated performance reports keep their own topic even when the slug names a feature (e.g. font, candidate, foxy).
  if (p.startsWith('maintainer/perf/')) return '性能与诊断'
  for (const [topic, pattern] of topicRules) if (pattern.test(p)) return topic
  return p.startsWith('maintainer/') ? '构建与审阅' : '使用与维护'
}

function classify(path) {
  const version = path.startsWith('legacy/') ? 'legacy' : 'current'
  let kind = '使用指南'
  if (path.startsWith('maintainer/history/')) kind = '直连版历史'
  else if (/^maintainer\/(audit|perf)\//.test(path)) kind = '验证证据'
  else if (path.startsWith('maintainer/implementation/')) kind = '实现协议'
  else if (path.startsWith('maintainer/changes/')) kind = '提交对照'
  else if (path.startsWith('maintainer/')) kind = '现行维护'
  else if (version === 'legacy') kind = /\/notes\/|\/planning\//.test(path) ? '旧版批注与方案' : '旧版原站'
  const maintenance = path.startsWith('maintainer/') || /^legacy\/fcitx\/(maintainer|notes|planning)\//.test(path)
  return { version, kind, maintenance, archived: isHistoricalSource(path), topic: topicFor(path) }
}

export function collectCatalog() {
  const entries = []
  const redirects = []
  function walk(directory) {
    for (const file of readdirSync(directory, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name, 'en'))) {
      if (file.name.startsWith('.') || ['node_modules', 'public', 'scripts', '__pycache__'].includes(file.name)) continue
      const absolute = join(directory, file.name)
      if (file.isDirectory()) { walk(absolute); continue }
      if (!file.isFile() || !file.name.endsWith('.md')) continue
      const source = relative(docsRoot, absolute).replaceAll('\\', '/')
      const text = readFileSync(absolute, 'utf8').replaceAll('\r\n', '\n')
      const frontmatter = text.match(/^---\n([\s\S]*?)\n---(?:\n|$)/)?.[1] ?? ''
      const redirect = frontmatter.match(/^redirect:\s*(.+)$/m)?.[1]?.trim()
      let url = '/' + source.replace(/\.md$/, '').replace(/(^|\/)index$/, '$1')
      if (redirect) { redirects.push({ source, url, target: redirect }); continue }
      const body = text.replace(/^---\n[\s\S]*?\n---(?:\n|$)/, '')
      const title = (body.match(/^#\s+(.+)$/m)?.[1] ?? (source === 'index.md' ? '中州鹅文档首页' : source))
        .replace(/\[([^\]]+)\]\([^)]+\)/g, '$1').replace(/[`*_]/g, '')
      const paragraphs = body.split(/\n\s*\n/)
      const first = paragraphs.find(p => !/^(?:#|[>|<]|:::|```|\||[-*]\s|\d+\.)/.test(p.trim()) && p.trim().length > 12)
      const description = (first ?? '').replace(/\[([^\]]+)\]\([^)]+\)/g, '$1')
        .replace(/[`*_]/g, '').replace(/\s+/g, ' ').trim().slice(0, 120)
      const date = source.match(/20\d\d-\d\d-\d\d/)?.[0] ?? ''
      const classification = classify(source)
      if (/^docKind:\s*reference\s*$/m.test(frontmatter)) {
        classification.kind = '上游参考'
        classification.archived = true
      }
      entries.push({ source, url, title, description, date, ...classification })
    }
  }
  walk(docsRoot)
  const urls = new Set()
  for (const entry of [...entries, ...redirects]) {
    if (urls.has(entry.url)) throw new Error(`Duplicate document route: ${entry.url}`)
    urls.add(entry.url)
  }
  for (const entry of redirects) {
    if (!entries.some(e => e.url === entry.target)) throw new Error(`Redirect lacks a canonical document: ${entry.source}`)
  }
  return { entries, redirects }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const catalog = collectCatalog()
  const generated = join(docsRoot, '.vitepress', 'generated')
  mkdirSync(generated, { recursive: true })
  writeFileSync(join(generated, 'catalog.json'), JSON.stringify(catalog, null, 2) + '\n')
  console.log(`Catalog: ${catalog.entries.length} documents, ${catalog.redirects.length} preserved routes.`)
}
