// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { placeholderNames } from './localeResidue'

/**
 * F01.2.1 ALIC-1 AC-H02 — 委任経路の本文プレビューに出す注記
 * 「元のページ・添付は組織のメンバーだけが開けます」の i18n。
 *
 *   キー `announcement.preview.delegated_source_note`（announcement.json）が6言語にあり、
 *   値が空でなく、補間（プレースホルダ集合）が ja と一致する。
 */
const LOCALES = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const KEY = 'delegated_source_note'

function note(locale: string): unknown {
  const path = resolve(__dirname, `../../../app/locales/${locale}/announcement.json`)
  const json = JSON.parse(readFileSync(path, 'utf8')) as { announcement?: { preview?: Record<string, unknown> } }
  return json.announcement?.preview?.[KEY]
}

describe('委任注記の i18n（AC-ALIC1-H02）', () => {
  it.each(LOCALES)('%s: announcement.preview.delegated_source_note が空でない', (locale) => {
    const value = note(locale)
    expect(typeof value === 'string' && value.trim().length > 0, `${locale} に ${KEY} が無いか空`).toBe(true)
  })

  it.each(LOCALES.filter(l => l !== 'ja'))('%s: 補間が ja と一致する', (locale) => {
    const ja = note('ja')
    const value = note(locale)
    expect(typeof ja).toBe('string')
    expect(typeof value).toBe('string')
    expect([...placeholderNames(String(value))].sort()).toEqual([...placeholderNames(String(ja))].sort())
  })
})
