import { describe, expect, it } from 'vitest'

// V2（CMP-261110-0426）: 未来時刻を「たった今」と出さない。過去側は従来どおり。
import { useRelativeTime } from './useRelativeTime'

const setLocale = async (v: string) => {
  await useNuxtApp().$i18n.setLocale(v as never)
}

const MIN = 60 * 1000
const HOUR = 60 * MIN
const DAY = 24 * HOUR
const ago = (ms: number) => new Date(Date.now() - ms).toISOString()
const ahead = (ms: number) => new Date(Date.now() + ms).toISOString()
const JAPANESE = /[ぁ-んァ-ヶ一-龥]/

describe('useRelativeTime 未来時刻', () => {
  it('ja: 未来は「あと n 分/時間/日」を出す', async () => {
    await setLocale('ja')
    const { relativeTime } = useRelativeTime()
    expect(relativeTime(ahead(5 * MIN))).toBe('あと5分')
    expect(relativeTime(ahead(3 * HOUR))).toBe('あと3時間')
    expect(relativeTime(ahead(2 * DAY))).toBe('あと2日')
  })

  it('ja: 1分未満の未来は「たった今」ではなく「まもなく」', async () => {
    await setLocale('ja')
    const { relativeTime } = useRelativeTime()
    expect(relativeTime(ahead(30 * 1000))).toBe('まもなく')
  })

  it('en: 未来は in n ... の英語表記', async () => {
    await setLocale('en')
    const { relativeTime } = useRelativeTime()
    expect(relativeTime(ahead(5 * MIN))).toBe('in 5 minutes')
    expect(relativeTime(ahead(3 * HOUR))).toBe('in 3 hours')
    expect(relativeTime(ahead(2 * DAY))).toBe('in 2 days')
  })

  it.each(['en', 'de', 'es'] as const)('%s: 未来表示に日本語が出ない', async (loc) => {
    await setLocale(loc)
    const { relativeTime } = useRelativeTime()
    for (const ms of [30 * 1000, 5 * MIN, 3 * HOUR, 2 * DAY]) {
      expect(relativeTime(ahead(ms))).not.toMatch(JAPANESE)
    }
  })

  it('zh/ko: その言語の未来表記になる（日本語の「あと」にならない）', async () => {
    await setLocale('zh')
    expect(useRelativeTime().relativeTime(ahead(5 * MIN))).toBe('5分钟后')
    await setLocale('ko')
    expect(useRelativeTime().relativeTime(ahead(5 * MIN))).toBe('5분 후')
  })

  it('過去側は従来どおり（回帰）', async () => {
    await setLocale('ja')
    const { relativeTime } = useRelativeTime()
    expect(relativeTime(ago(30 * 1000))).toBe('たった今')
    expect(relativeTime(ago(5 * MIN))).toBe('5分前')
    expect(relativeTime(ago(3 * HOUR))).toBe('3時間前')
    expect(relativeTime(ago(2 * DAY))).toBe('2日前')
  })
})
