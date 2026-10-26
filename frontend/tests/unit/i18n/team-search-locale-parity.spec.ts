// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const nonJapaneseLocales = locales.filter((locale) => locale !== 'ja')
const japaneseKana = /[ぁ-んァ-ヶ]/

interface TargetMessages {
  searchBar: Record<string, unknown>
  scopeLabels: Record<string, unknown>
  teamHub: {
    guide: Record<string, unknown>
  }
}

function loadMessages(locale: string): TargetMessages {
  const file = resolve(process.cwd(), 'app', 'locales', locale, 'common.json')
  return JSON.parse(readFileSync(file, 'utf8')) as TargetMessages
}

function flatten(value: unknown, prefix = ''): Record<string, string> {
  if (typeof value === 'string') return { [prefix]: value }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}

  return Object.entries(value).reduce<Record<string, string>>(
    (result, [key, child]) => ({
      ...result,
      ...flatten(child, prefix ? `${prefix}.${key}` : key),
    }),
    {},
  )
}

function targetMessages(locale: string): Record<string, string> {
  const messages = loadMessages(locale)
  return flatten({
    searchBar: messages.searchBar,
    scopeLabels: messages.scopeLabels,
    guide: messages.teamHub.guide,
  })
}

describe('team search locale messages', () => {
  const japanese = targetMessages('ja')
  const expectedKeys = Object.keys(japanese).sort()

  it.each(locales)('%s has every team search message with a non-empty value', (locale) => {
    const messages = targetMessages(locale)

    expect(Object.keys(messages).sort()).toEqual(expectedKeys)
    expect(Object.values(messages).every((value) => value.trim().length > 0)).toBe(true)
  })

  it.each(nonJapaneseLocales)('%s does not fall back to Japanese copy', (locale) => {
    const messages = targetMessages(locale)

    expect(messages).not.toEqual(japanese)
    expect(Object.values(messages).some((value) => japaneseKana.test(value))).toBe(false)
  })
})
