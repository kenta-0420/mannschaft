import { describe, expect, it } from 'vitest'
import { ref } from 'vue'

// useRelativeTime は useNuxtApp().$i18n.locale を読む。実の Nuxt 環境の i18n ロケールを切り替えて検証する。
import { useRelativeTime } from './useRelativeTime'

// 文言は現在ロケールのメッセージを読むため、setLocale でメッセージを読み込んでから検証する。
const setLocale = async (v: string) => {
  await useNuxtApp().$i18n.setLocale(v as never)
}

const MIN = 60 * 1000
const HOUR = 60 * MIN
const DAY = 24 * HOUR
const ago = (ms: number) => new Date(Date.now() - ms).toISOString()
const JAPANESE = /[ぁ-んァ-ヶ一-龥]/

describe('useRelativeTime i18n', () => {
  it('ja: 現行どおり日本語の相対表示（分前・時間前・日前）になる', async () => {
    await setLocale('ja')
    const { relativeTime } = useRelativeTime()
    expect(relativeTime(ago(5 * MIN))).toBe('5分前')
    expect(relativeTime(ago(3 * HOUR))).toBe('3時間前')
    expect(relativeTime(ago(3 * DAY))).toBe('3日前')
    expect(relativeTime(ago(10 * 1000))).toBe('たった今')
  })

  it('ja: 7日以上は日付形式（YYYY/M/D）', async () => {
    await setLocale('ja')
    const { relativeTime } = useRelativeTime()
    expect(relativeTime(ago(30 * DAY))).toMatch(/^\d{4}\/\d{1,2}\/\d{1,2}$/)
  })

  it.each(['en', 'de', 'es'] as const)('%s: 日本語が出ない', async (loc) => {
    await setLocale(loc)
    const { relativeTime } = useRelativeTime()
    for (const ms of [10 * 1000, 5 * MIN, 3 * HOUR, 3 * DAY, 30 * DAY]) {
      expect(relativeTime(ago(ms))).not.toMatch(JAPANESE)
    }
  })

  it.each(['zh', 'ko'] as const)('%s: 日本語の「分前」にならない', async (loc) => {
    await setLocale(loc)
    const { relativeTime } = useRelativeTime()
    expect(relativeTime(ago(5 * MIN))).not.toMatch(/分前/)
  })

  it('en: 英語表記になる', async () => {
    await setLocale('en')
    const { relativeTime } = useRelativeTime()
    expect(relativeTime(ago(5 * MIN))).toBe('5 minutes ago')
    expect(relativeTime(ago(3 * HOUR))).toBe('3 hours ago')
  })

  it('de: ドイツ語表記になる', async () => {
    await setLocale('de')
    const { relativeTime } = useRelativeTime()
    expect(relativeTime(ago(5 * MIN))).toBe('vor 5 Minuten')
  })

  it('Ref 渡しの computed も現在ロケールで出す', async () => {
    await setLocale('en')
    const r = useRelativeTime(ref(ago(5 * MIN)))
    expect(r.value).toBe('5 minutes ago')
  })

  it('空文字・不正値は空文字', async () => {
    await setLocale('en')
    const { relativeTime } = useRelativeTime()
    expect(relativeTime('')).toBe('')
    expect(relativeTime('not-a-date')).toBe('')
  })
})
