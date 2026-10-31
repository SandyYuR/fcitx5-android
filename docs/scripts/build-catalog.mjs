// Build one complete catalog from source files. Redirects are navigation aids, not extra documents.
import { readdirSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

export const docsRoot = resolve(dirname(fileURLToPath(import.meta.url)), '..')

function topicFor(path) {
  const p = path.toLowerCase()
  if (/voice|asr/.test(p)) return '语音输入'
  if (/backup|restore|data-directory|shutdown/.test(p)) return '数据与备份'
  if (/clipboard|clipcascade|clearurls|websocket/.test(p)) return '剪贴板与网络'
  if (/theme|monet|md3|settings|font|appearance/.test(p)) return '主题与设置'
  if (/editor|layout|keys\/|keyboard|symbol|swipe|foxy/.test(p)) return '键盘与编辑器'
  if (/candidate|responsiveness|rebind|status-hint|password/.test(p)) return '输入与候选'
  if (/perf\/|screen_perf|cold_io|performance|user_log/.test(p)) return '性能与诊断'
  if (/engine|source_maintenance|rime-enhancements|implementation_2026|decoupling/.test(p)) return 'Rime 引擎'
  if (/build|release|merge|rebase|documentation|review|update|hosts|cleanup|unused|audit/.test(p)) return '构建与审阅'
  return '使用与维护'
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
  return { version, kind, maintenance, topic: topicFor(path) }
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
      if (/^docKind:\s*reference\s*$/m.test(frontmatter)) classification.kind = '上游参考'
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
