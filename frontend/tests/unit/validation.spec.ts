import { describe, it, expect } from 'vitest'
import { ref } from '#imports'
import { useRelativeTime } from '~/composables/useRelativeTime'

describe('ERR-002: useRelativeTime バリデーション', () => {
  it('引数なしで呼び出すと relativeTime 関数を返す', () => {
    const { relativeTime, formatRelative } = useRelativeTime()
    expect(typeof relativeTime).toBe('function')
    expect(typeof formatRelative).toBe('function')
  })

  it('たった今 - 30秒前はたった今と表示される', () => {
    const { relativeTime } = useRelativeTime()
    const thirtySecondsAgo = new Date(Date.now() - 30 * 1000).toISOString()
    expect(relativeTime(thirtySecondsAgo)).toBe('たった今')
  })

  it('5分前 - 5分前は正しく表示される', () => {
    const { relativeTime } = useRelativeTime()
    const fiveMinutesAgo = new Date(Date.now() - 5 * 60 * 1000).toISOString()
    expect(relativeTime(fiveMinutesAgo)).toBe('5分前')
  })

  it('2時間前 - 2時間前は正しく表示される', () => {
    const { relativeTime } = useRelativeTime()
    const twoHoursAgo = new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString()
    expect(relativeTime(twoHoursAgo)).toBe('2時間前')
  })

  it('3日前 - 3日前は正しく表示される', () => {
    const { relativeTime } = useRelativeTime()
    const threeDaysAgo = new Date(Date.now() - 3 * 24 * 60 * 60 * 1000).toISOString()
    expect(relativeTime(threeDaysAgo)).toBe('3日前')
  })

  it('Ref 引数で ComputedRef を返す', () => {
    const dateRef = ref(new Date(Date.now() - 30 * 60 * 1000).toISOString())
    const result = useRelativeTime(dateRef)
    expect(result.value).toMatch(/\d+分前/)
  })

  it('string 引数で ComputedRef を返す', () => {
    const dateStr = new Date(Date.now() - 2 * 60 * 60 * 1000).toISOString()
    const result = useRelativeTime(dateStr)
    expect(result.value).toBe('2時間前')
  })

  it('7日以上前は日付形式で表示される', () => {
    const { relativeTime } = useRelativeTime()
    const tenDaysAgo = new Date(Date.now() - 10 * 24 * 60 * 60 * 1000).toISOString()
    // 7日以上前は toLocaleDateString('ja-JP') 形式（例: 2026/3/25）
    expect(relativeTime(tenDaysAgo)).toMatch(/\d{4}\/\d{1,2}\/\d{1,2}/)
  })
})
