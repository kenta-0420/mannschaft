import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * settings.json 6言語キー集合パリティ検証（AC-18）。
 *
 * アカウント設定ページ($t化)で追加した settings.* キーが ja/en/zh/ko/es/de の
 * 全6言語に欠落なく存在することを保証する。missing/extra が出るとビルドは通っても
 * 実画面で生キー文字列が表示される（言語によって翻訳が欠ける）回帰を防ぐ。
 */
const LANGS = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const here = dirname(fileURLToPath(import.meta.url))
const localesDir = resolve(here, '../../../app/locales')

function flatten(obj: unknown, prefix = ''): string[] {
  if (obj && typeof obj === 'object' && !Array.isArray(obj)) {
    return Object.entries(obj as Record<string, unknown>).flatMap(([k, v]) =>
      flatten(v, prefix ? `${prefix}.${k}` : k),
    )
  }
  return [prefix]
}

function loadKeys(lang: string): Set<string> {
  const raw = readFileSync(resolve(localesDir, lang, 'settings.json'), 'utf-8')
  return new Set(flatten(JSON.parse(raw)))
}

describe('settings.json ロケールキー集合パリティ', () => {
  const baseKeys = loadKeys('ja')

  it('ja は1件以上のキーを持つ', () => {
    expect(baseKeys.size).toBeGreaterThan(0)
  })

  for (const lang of LANGS.filter((l) => l !== 'ja')) {
    it(`${lang} は ja とキー集合が一致する（missing/extra なし）`, () => {
      const keys = loadKeys(lang)
      const missing = [...baseKeys].filter((k) => !keys.has(k))
      const extra = [...keys].filter((k) => !baseKeys.has(k))
      expect({ lang, missing, extra }).toEqual({ lang, missing: [], extra: [] })
    })
  }
})
