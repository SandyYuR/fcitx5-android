// Guard discoverability and archival preservation, not just successful Markdown compilation.
import { readFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { createHash } from 'node:crypto'
import assert from 'node:assert/strict'
import { collectCatalog, docsRoot } from './build-catalog.mjs'

const actual = collectCatalog()
const generated = JSON.parse(readFileSync(join(docsRoot, '.vitepress/generated/catalog.json'), 'utf8'))
assert.deepEqual(generated, actual, 'Catalog is stale; rebuild after adding or changing a document.')
const page = readFileSync(join(docsRoot, '.vitepress/dist/contents.html'), 'utf8')
const maintenance = readFileSync(join(docsRoot, '.vitepress/dist/maintainer/catalog.html'), 'utf8')
for (const entry of actual.entries) {
  assert(page.includes(`href="/fcitx5-android${entry.url}"`), `Not reachable from the complete directory: ${entry.source}`)
  if (entry.maintenance) assert(maintenance.includes(`href="/fcitx5-android${entry.url}"`), `Missing maintenance entry: ${entry.source}`)
}

const migration = JSON.parse(readFileSync(join(docsRoot, 'maintainer/site-reorganization-2026-10-30.json'), 'utf8'))
assert.equal(new Set(migration.pages.map(entry => entry.destination)).size, migration.pages.length,
  'Multiple original documents resolve to one destination; preserve them separately.')
for (const entry of migration.pages) {
  assert(existsSync(join(docsRoot, entry.destination)), `Original document lost: ${entry.source}`)
  if (entry.source !== entry.destination) {
    const alias = actual.redirects.find(row => row.source === entry.source)
    assert(alias, `Old route lost: ${entry.source}`)
    const destination = actual.entries.find(row => row.source === entry.destination)
    assert(destination, `Archived destination is not a canonical document: ${entry.destination}`)
    assert.equal(alias.target, destination.url, `Old route points to the wrong document: ${entry.source}`)
    const text = readFileSync(join(docsRoot, entry.destination), 'utf8').replaceAll('\r\n', '\n')
    const digest = createHash('sha256').update(text).digest('hex')
    assert.equal(digest, entry.sha256_archived_text, `Relocated historical text changed: ${entry.destination}`)
  }
}

const review = JSON.parse(readFileSync(join(docsRoot, 'maintainer/changes/commit-map-2026-10-30.json'), 'utf8'))
assert.equal(review.commits.length, review.scope.all_commits)
assert.equal(review.commits.filter(c => c.phase === 'direct').length, review.scope.direct_commits)
assert.equal(review.commits.filter(c => c.phase === 'inherited').length, review.scope.inherited_commits)
assert.equal(new Set(review.commits.map(c => c.sha)).size, review.commits.length, 'Duplicate commit rows')
for (const commit of review.commits) {
  assert(/^[a-f0-9]{40}$/.test(commit.sha))
  assert(actual.entries.some(entry => entry.source === commit.document), `Commit points to a missing or redirected document: ${commit.sha}`)
}
for (const feature of review.feature_coverage) {
  assert(existsSync(join(docsRoot, feature.document)), `Feature page missing: ${feature.document}`)
  assert(feature.source_files.length, `No source entry for ${feature.document}`)
}
console.log(JSON.stringify({ documents: actual.entries.length, maintenance_documents: actual.entries.filter(e => e.maintenance).length,
  preserved_routes: actual.redirects.length, original_pages_accounted_for: migration.pages.length,
  commits_mapped: review.commits.length, current_feature_pages: review.feature_coverage.length, errors: 0 }))
