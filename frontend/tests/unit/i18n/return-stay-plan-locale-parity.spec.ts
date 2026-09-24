import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const

function flatten(value: unknown, prefix = ''): string[] {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return prefix ? [prefix] : []
  return Object.entries(value).flatMap(([key, child]) => flatten(child, prefix ? `${prefix}.${key}` : key))
}

function localeKeys(locale: string): string[] {
  const file = resolve(process.cwd(), 'app', 'locales', locale, 'return_stay_plan.json')
  return flatten(JSON.parse(readFileSync(file, 'utf8'))).sort()
}

describe('return_stay_plan locale parity', () => {
  const japaneseKeys = localeKeys('ja')

  it.each(locales)('%s has the same keys as Japanese', (locale) => {
    const keys = localeKeys(locale)
    expect(japaneseKeys.length).toBeGreaterThan(0)
    expect(keys).toEqual(japaneseKeys)
  })
})
