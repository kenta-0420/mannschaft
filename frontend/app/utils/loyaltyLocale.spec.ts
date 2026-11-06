// @vitest-environment node
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

function flatten(value: Record<string, unknown>, prefix = ''): Record<string, string> {
  return Object.fromEntries(
    Object.entries(value).flatMap(([key, item]) => {
      const path = prefix ? `${prefix}.${key}` : key
      return typeof item === 'string'
        ? [[path, item]]
        : Object.entries(flatten(item as Record<string, unknown>, path))
    }),
  )
}
const locales = Object.fromEntries(
  ['ja', 'en', 'zh', 'ko', 'es', 'de'].map((language) => {
    const data = JSON.parse(
      readFileSync(new URL(`../locales/${language}/common.json`, import.meta.url), 'utf8'),
    ) as { loyalty: Record<string, unknown> }
    return [language, flatten(data.loyalty)]
  }),
)
describe('チームポイントの6言語（AC20/31）', () => {
  for (const [language, translations] of Object.entries(locales)) {
    it(`${language}: 全キー・置換変数が一致し、日本語の仮訳を残さない`, () => {
      expect(Object.keys(translations).sort()).toEqual(Object.keys(locales.ja!).sort())
      for (const [key, value] of Object.entries(translations)) {
        expect(value.match(/\{\w+\}/g)?.sort() ?? []).toEqual(
          locales.ja![key]!.match(/\{\w+\}/g)?.sort() ?? [],
        )
        expect(value).not.toContain('???')
        if (language !== 'ja') expect(value).not.toMatch(/[\u3040-\u30ff]/)
      }
    })
  }
})
