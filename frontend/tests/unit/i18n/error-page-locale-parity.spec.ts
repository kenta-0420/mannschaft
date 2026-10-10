// @vitest-environment node
import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * CMP-261007-2053: error.vue 用文言（common.json の error_page.*）の 6 言語パリティ（AC-11）。
 *
 * 前提とするキー（common.json 直下の error_page 名前空間）:
 * - error_page.not_found_title / error_page.not_found_description
 * - error_page.generic_title / error_page.generic_description
 * - error_page.back_home
 *
 * 値に裸の @ を含むと vue-i18n がリンク記法として解釈しビルド/描画が壊れるため禁止する。
 */
const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const REQUIRED_KEYS = [
  'not_found_title',
  'not_found_description',
  'generic_title',
  'generic_description',
  'back_home',
] as const

const here = dirname(fileURLToPath(import.meta.url))
const localesDir = resolve(here, '../../../app/locales')

function loadErrorPage(lang: string): Record<string, unknown> | undefined {
  const json = JSON.parse(readFileSync(resolve(localesDir, lang, 'common.json'), 'utf-8')) as Record<string, unknown>
  const ns = json.error_page
  return ns && typeof ns === 'object' && !Array.isArray(ns) ? (ns as Record<string, unknown>) : undefined
}

describe('CMP-261007-2053 error_page 文言の 6 言語パリティ（AC-11）', () => {
  for (const lang of LANGS) {
    it(`AC-11: ${lang}/common.json に error_page の必須キーが非空文字列で揃っている`, () => {
      const ns = loadErrorPage(lang) ?? {}
      const missing = REQUIRED_KEYS.filter((k) => typeof ns[k] !== 'string' || (ns[k] as string).trim() === '')
      expect({ lang, missing }).toEqual({ lang, missing: [] })
    })

    it(`AC-11: ${lang}/common.json の error_page の値に裸の @ を含まない`, () => {
      const ns = loadErrorPage(lang)
      expect(ns, `${lang} に error_page がある`).toBeDefined()
      const bare = Object.entries(ns ?? {}).filter(
        ([, v]) => typeof v === 'string' && /@/.test(v.replace(/\{'@'\}/g, '')),
      )
      expect({ lang, bare }).toEqual({ lang, bare: [] })
    })
  }

  it('AC-11: error_page のキー集合が 6 言語で一致する（missing/extra なし）', () => {
    const base = Object.keys(loadErrorPage('ja') ?? {}).sort()
    expect(base.length).toBeGreaterThan(0)
    for (const lang of LANGS) {
      expect({ lang, keys: Object.keys(loadErrorPage(lang) ?? {}).sort() }).toEqual({ lang, keys: base })
    }
  })

  it('AC-7: en の not_found_title は Nuxt 既定の英語文言「Page not found」と異なる独自文言である', () => {
    const title = loadErrorPage('en')?.not_found_title
    expect(typeof title).toBe('string')
    expect(title).not.toMatch(/^page not found$/i)
  })
})
