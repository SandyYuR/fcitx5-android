// Use the same deterministic CJK terms when building and querying the index.
// Whole-sentence tokens make words in the middle of Chinese text unsearchable.
export function tokenizeSearch(text, fieldName) {
  const cjk = /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]/u
  const runs = text.split(/([\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]+)/u)
  const terms = []
  for (const run of runs) {
    if (!cjk.test(run)) {
      terms.push(...(run.match(/[\p{L}\p{N}\p{M}_]+/gu) || []))
      continue
    }
    const characters = Array.from(run)
    for (let i = 1; i < characters.length; i++) terms.push(characters[i - 1] + characters[i])
    // One-character searches use prefix matching against the bigrams. Index the
    // final character as well, without doubling every posting with unigrams.
    // Queries of two or more characters need only their contiguous bigrams.
    if (fieldName || characters.length === 1) terms.push(characters[characters.length - 1])
  }
  return terms
}

export const searchIndexOptions = {
  fields: ['title', 'titles', 'text'],
  storeFields: ['title', 'titles'],
  tokenize: tokenizeSearch
}

export const searchOptions = {
  combineWith: 'AND',
  // Fuzzy CJK characters would match unrelated words and greatly expand searches.
  fuzzy: term => /[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]/u.test(term) ? false : 0.2,
  prefix: term => Array.from(term).length === 1
    || !/[\p{Script=Han}\p{Script=Hiragana}\p{Script=Katakana}\p{Script=Hangul}]/u.test(term),
  boost: { title: 4, text: 2, titles: 1 }
}

export function isHistoricalSource(source) {
  const path = source.replace(/^\//, '')
  if (/^(legacy\/|maintainer\/(history|audit|perf)\/)/.test(path)) return true
  // The maintained coverage table describes current code.
  // Dated audits, old coverage snapshots and commit indexes are reference records.
  return path.startsWith('maintainer/changes/')
    && path !== 'maintainer/changes/feature-coverage.md'
}
