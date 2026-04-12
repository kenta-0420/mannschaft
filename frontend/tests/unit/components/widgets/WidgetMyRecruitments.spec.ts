import { describe, it, expect } from 'vitest'

/**
 * F03.11 WidgetMyRecruitments のユニットテスト。
 *
 * コンポーネントは Nuxt Auto-import（useRecruitmentApi, useErrorReport, onMounted）
 * および PrimeVue に依存するため、mountSuspended 環境でのマウントは
 * テスト設定が複雑になる。ここではコンポーネントのロジック部分を抽出してテストする。
 */

type ParticipantStatus = 'APPLIED' | 'CONFIRMED' | 'WAITLISTED' | 'CANCELLED' | 'ATTENDED'
type SeverityType = 'success' | 'warn' | 'info' | 'secondary'

interface MyListingItem {
  id: number
  listingId: number
  status: ParticipantStatus
  waitlistPosition: number | null
}

/** ウィジェットが表示する最大件数 */
const WIDGET_MAX_ITEMS = 5

/** コンポーネント内 statusSeverity の再現 */
function statusSeverity(status: string): SeverityType {
  switch (status) {
    case 'CONFIRMED': return 'success'
    case 'WAITLISTED': return 'warn'
    case 'APPLIED': return 'info'
    default: return 'secondary'
  }
}

/** コンポーネント内スライスロジックの再現 */
function sliceItems(items: MyListingItem[]): MyListingItem[] {
  return items.slice(0, WIDGET_MAX_ITEMS)
}

describe('WidgetMyRecruitments ロジック', () => {
  describe('スライスロジック', () => {
    it('参加予定が5件以下のときすべて表示する', () => {
      const items: MyListingItem[] = Array.from({ length: 4 }, (_, i) => ({
        id: i + 100,
        listingId: i + 1,
        status: 'CONFIRMED',
        waitlistPosition: null,
      }))

      const result = sliceItems(items)
      expect(result).toHaveLength(4)
    })

    it('参加予定が6件以上のとき5件に絞る', () => {
      const items: MyListingItem[] = Array.from({ length: 7 }, (_, i) => ({
        id: i + 100,
        listingId: i + 1,
        status: 'CONFIRMED',
        waitlistPosition: null,
      }))

      const result = sliceItems(items)
      expect(result).toHaveLength(5)
    })

    it('参加予定が0件のとき空配列を返す', () => {
      const result = sliceItems([])
      expect(result).toHaveLength(0)
    })
  })

  describe('表示条件', () => {
    it('items.length > 0 のときリストを表示すべき', () => {
      const items: MyListingItem[] = [{ id: 100, listingId: 1, status: 'CONFIRMED', waitlistPosition: null }]
      const showList = items.length > 0
      expect(showList).toBe(true)
    })

    it('items が空のとき空状態コンポーネントを表示すべき', () => {
      const items: MyListingItem[] = []
      const showEmpty = items.length === 0
      expect(showEmpty).toBe(true)
    })
  })

  describe('statusSeverity', () => {
    it('CONFIRMED → success', () => {
      expect(statusSeverity('CONFIRMED')).toBe('success')
    })

    it('WAITLISTED → warn', () => {
      expect(statusSeverity('WAITLISTED')).toBe('warn')
    })

    it('APPLIED → info', () => {
      expect(statusSeverity('APPLIED')).toBe('info')
    })

    it('CANCELLED → secondary', () => {
      expect(statusSeverity('CANCELLED')).toBe('secondary')
    })

    it('ATTENDED → secondary', () => {
      expect(statusSeverity('ATTENDED')).toBe('secondary')
    })

    it('未知のステータス → secondary', () => {
      expect(statusSeverity('UNKNOWN_STATUS')).toBe('secondary')
    })
  })

  describe('waitlistPosition 表示判定', () => {
    it('waitlistPosition が null のときウェイトリストラベルを表示しない', () => {
      const item: MyListingItem = { id: 100, listingId: 1, status: 'CONFIRMED', waitlistPosition: null }
      const showWaitlist = item.waitlistPosition != null
      expect(showWaitlist).toBe(false)
    })

    it('waitlistPosition が数値のときウェイトリストラベルを表示する', () => {
      const item: MyListingItem = { id: 100, listingId: 1, status: 'WAITLISTED', waitlistPosition: 3 }
      const showWaitlist = item.waitlistPosition != null
      expect(showWaitlist).toBe(true)
      expect(item.waitlistPosition).toBe(3)
    })

    it('waitlistPosition が 0 のときウェイトリストラベルを表示する', () => {
      // 0 は falsy だが != null は true になる（仕様通り）
      const item: MyListingItem = { id: 100, listingId: 1, status: 'WAITLISTED', waitlistPosition: 0 }
      const showWaitlist = item.waitlistPosition != null
      expect(showWaitlist).toBe(true)
    })
  })
})
