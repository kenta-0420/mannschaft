// @vitest-environment node
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const locales = ['ja', 'en', 'zh', 'ko', 'es', 'de'] as const
const expectedKeys = [
  'communication',
  'content_affiliation',
  'count',
  'empty',
  'feed',
  'more_count',
  'schedule',
  'todo',
  'visible_count',
]

function accordionMessages(locale: string): Record<string, string> {
  const file = resolve(process.cwd(), 'app', 'locales', locale, 'dashboard.json')
  const messages = JSON.parse(readFileSync(file, 'utf8')) as {
    dashboard?: { personal_accordion?: Record<string, string> }
  }
  return messages.dashboard?.personal_accordion ?? {}
}

describe('dashboard personal accordion locale parity', () => {
  it.each(locales)('%s has every accordion message', (locale) => {
    const messages = accordionMessages(locale)
    expect(Object.keys(messages).sort()).toEqual(expectedKeys)
    expect(Object.values(messages).every((value) => value.length > 0)).toBe(true)
  })
})
