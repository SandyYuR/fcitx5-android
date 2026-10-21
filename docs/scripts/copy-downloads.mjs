// Keep documentation evidence at the relative URLs used by Markdown links.
// VitePress renders Markdown and copies public/, but does not emit linked ZIP/JSON files.
import { existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join, relative, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const docs = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const dist = join(docs, '.vitepress', 'dist')
let copied = 0
let bytes = 0
function publish(directory) {
  for (const entry of readdirSync(directory, { withFileTypes: true })) {
   if (entry.name.startsWith('.') || ['node_modules', '__pycache__'].includes(entry.name)) continue
   if (directory === docs && ['scripts', 'public'].includes(entry.name)) continue
   const source = join(directory, entry.name)
   if (entry.isDirectory()) { publish(source); continue }
   const path = relative(docs, source)
   if (/\.(md|mdx)$/i.test(path) || /^package(?:-lock)?\.json$/.test(path)) continue
   if (!entry.isFile()) throw new Error(`Download must be a regular file: ${path}`)
   const target = join(dist, path)
   const data = readFileSync(source)
   if (existsSync(target) && !readFileSync(target).equals(data)) throw new Error(`Download would replace generated content: ${path}`)
   mkdirSync(dirname(target), { recursive: true })
   writeFileSync(target, data)
   copied++
   bytes += data.length
  }
}
publish(docs)
console.log(`Published ${copied} documentation downloads (${bytes} bytes).`)
