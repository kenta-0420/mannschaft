// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * F01.2.1 8-D — team_affiliations.json（チーム側「所属組織」「権限グループ」画面）の i18n。
 *
 *   TAL-01 6言語すべてにあり、キー集合が ja と一致する
 *   TAL-02 nuxt.config.ts に6言語とも登録されている
 *   TAL-03 値が空文字でない
 */
const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const FILE = 'team_affiliations.json'

function flatten(value: unknown, prefix = ''): string[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return prefix ? [prefix] : []
  return Object.entries(value).flatMap(([k, v]) => flatten(v, prefix ? `${prefix}.${k}` : k))
}

function read(locale: string): Record<string, unknown> {
  const raw = readFileSync(resolve(process.cwd(), 'app', 'locales', locale, FILE), 'utf8')
  return JSON.parse(raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw)
}

function leaves(value: unknown, prefix = ''): [string, unknown][] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return [[prefix, value]]
  return Object.entries(value).flatMap(([k, v]) => leaves(v, prefix ? `${prefix}.${k}` : k))
}

describe('team_affiliations.json', () => {
  const jaKeys = flatten(read('ja')).sort()

  it('TAL-01: 6言語のキー集合が ja と一致', () => {
    expect(jaKeys.length).toBeGreaterThan(30)
    for (const locale of locales) {
      expect(flatten(read(locale)).sort(), locale).toEqual(jaKeys)
    }
  })

  it('TAL-02: nuxt.config.ts に登録', () => {
    const config = readFileSync(resolve(process.cwd(), 'nuxt.config.ts'), 'utf8')
    for (const locale of locales) {
      expect(config, locale).toContain(`'${locale}/${FILE}'`)
    }
  })

  it('TAL-03: 空文字の値がない', () => {
    for (const locale of locales) {
      const empty = leaves(read(locale)).filter(([, v]) => typeof v !== 'string' || v.trim() === '')
      expect(empty, locale).toEqual([])
    }
  })
})
