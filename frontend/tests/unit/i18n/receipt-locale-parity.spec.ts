import { describe, it, expect } from 'vitest'
import { existsSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * receipt.json 6言語キー集合パリティ検証（F08.4 AC-20 / AC-21）。
 *
 * 正本: docs/features/F08.4_receipt.md §9.3「キー集合の機械的検証」。
 *
 * 本テストは実装より前に書かれており、意図的に red である。
 * 現状 `app/locales/{ja,en,zh,ko,es,de}/receipt.json` は 6 言語とも存在せず、
 * `nuxt.config.ts` の i18n `locales[].files` にも登録されていない。
 *
 * BOM 対策: 既存ロケールに BOM 付きファイルが混在している（ja/admin_console.json 等）。
 * BOM 付き JSON を素の `JSON.parse` へ渡すと落ちて偽赤になるため、読み込み時に必ず剥がす。
 */
const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const BOM = 0xfeff
const here = dirname(fileURLToPath(import.meta.url))
const localesDir = resolve(here, '../../../app/locales')
const nuxtConfigPath = resolve(here, '../../../nuxt.config.ts')

function receiptPath(lang: string): string {
  return resolve(localesDir, lang, 'receipt.json')
}

/** UTF-8 BOM を剥がして読み込む（BOM 付き JSON は JSON.parse が落ちるため）。 */
function readText(path: string): string {
  const raw = readFileSync(path, 'utf-8')
  return raw.charCodeAt(0) === BOM ? raw.slice(1) : raw
}

function readJson(path: string): unknown {
  return JSON.parse(readText(path))
}

function flatten(obj: unknown, prefix = ''): string[] {
  if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
    return Object.entries(obj as Record<string, unknown>).flatMap(([k, v]) =>
      flatten(v, prefix ? `${prefix}.${k}` : k),
    )
  }
  return [prefix]
}

function loadKeys(lang: string): Set<string> {
  return new Set(flatten(readJson(receiptPath(lang))))
}

describe('receipt.json ロケールキー集合パリティ（AC-20）', () => {
  it('6言語すべてに receipt.json が存在する', () => {
    const missing = LANGS.filter((lang) => !existsSync(receiptPath(lang)))
    expect(missing).toEqual([])
  })

  it('6言語すべての receipt.json がパース可能な JSON である', () => {
    const broken: string[] = []
    for (const lang of LANGS) {
      if (!existsSync(receiptPath(lang))) continue
      try {
        readJson(receiptPath(lang))
      } catch {
        broken.push(lang)
      }
    }
    expect(broken).toEqual([])
  })

  it('ja/receipt.json は1件以上のキーを持つ', () => {
    expect(loadKeys('ja').size).toBeGreaterThan(0)
  })

  for (const lang of LANGS.filter((l) => l !== 'ja')) {
    it(`${lang} は ja とキー集合が一致する（missing/extra なし）`, () => {
      const baseKeys = loadKeys('ja')
      const keys = loadKeys(lang)
      const missing = [...baseKeys].filter((k) => !keys.has(k))
      const extra = [...keys].filter((k) => !baseKeys.has(k))
      expect({ lang, missing, extra }).toEqual({ lang, missing: [], extra: [] })
    })
  }
})

describe('nuxt.config.ts への receipt.json 登録（AC-21）', () => {
  it('i18n の locales[].files に 6 言語ぶんの receipt.json が登録されている', () => {
    const config = readText(nuxtConfigPath)
    // ファイルを置くだけでは i18n はロードしない。files[] への登録が必須。
    const unregistered = LANGS.filter(
      (lang) => !config.includes(`'${lang}/receipt.json'`),
    )
    expect(unregistered).toEqual([])
  })

  it('receipt.json の登録はちょうど 6 件である（重複登録がない）', () => {
    const config = readText(nuxtConfigPath)
    const occurrences = config.match(/'(?:ja|en|zh|ko|es|de)\/receipt\.json'/g) ?? []
    expect(occurrences).toHaveLength(6)
  })
})
