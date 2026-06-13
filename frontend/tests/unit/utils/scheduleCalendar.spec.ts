import { describe, it, expect } from 'vitest'
import {
  toCalendarEventItem,
  toCalendarEventItems,
  toFlatScheduleEvent,
  type NestedScheduleResponse,
} from '~/utils/scheduleCalendar'

/**
 * scheduleCalendar 変換ユーティリティのユニットテスト。
 *
 * バックエンドの ScheduleResponse（content/time/scope/academic/audit ネスト）を
 * カレンダー描画用の平坦な CalendarEventItem / EventDetailPanel 用 FlatScheduleEvent へ
 * 正しく変換することを検証する。
 *
 * 回帰の対象: 以前は `res.data as CalendarEventItem[]` の嘘キャストで title/startAt が
 * undefined になりカレンダーにイベントが描画されなかった本番バグ。
 */
describe('scheduleCalendar 変換ユーティリティ', () => {
  const nestedFull: NestedScheduleResponse = {
    id: 42,
    content: {
      title: 'プリンスリーグ関東 第3節 vs 横浜FCユース',
      status: 'PUBLISHED',
      eventType: 'MATCH',
      location: '味の素フィールド西が丘',
      attendanceRequired: true,
    },
    time: {
      startAt: '2026-04-06T13:00:00',
      endAt: '2026-04-06T15:00:00',
      allDay: false,
    },
    scope: { scopeName: 'FC東京U-18', scopeIconUrl: 'https://example/icon.png' },
    academic: { eventCategory: { id: 7, name: '公式戦', color: '#ef4444' } },
    audit: { createdByDisplayName: 'コーチA' },
    myAttendanceStatus: 'YES',
  }

  describe('toCalendarEventItem', () => {
    it('CAL-001: ネスト構造から平坦な CalendarEventItem へ全フィールド変換する', () => {
      const item = toCalendarEventItem(nestedFull, 'TEAM')
      expect(item.id).toBe(42)
      expect(item.title).toBe('プリンスリーグ関東 第3節 vs 横浜FCユース')
      expect(item.startAt).toBe('2026-04-06T13:00:00')
      expect(item.endAt).toBe('2026-04-06T15:00:00')
      expect(item.allDay).toBe(false)
      expect(item.color).toBe('#ef4444')
      expect(item.isPersonal).toBe(false)
      expect(item.eventType).toBe('MATCH')
      expect(item.scopeType).toBe('TEAM')
      expect(item.scopeName).toBe('FC東京U-18')
      expect(item.scopeIconUrl).toBe('https://example/icon.png')
    })

    it('CAL-002: title/startAt は嘘キャスト時の undefined ではなく実値が入る（回帰防止）', () => {
      const item = toCalendarEventItem(nestedFull, 'TEAM')
      expect(item.title).not.toBeUndefined()
      expect(item.startAt).not.toBeUndefined()
      expect(item.title.length).toBeGreaterThan(0)
    })

    it('CAL-003: time が欠落しても落ちず空文字へフォールバックする', () => {
      const minimal: NestedScheduleResponse = { id: 1, content: { title: 'x' } }
      const item = toCalendarEventItem(minimal, 'ORGANIZATION')
      expect(item.startAt).toBe('')
      expect(item.endAt).toBe('')
      expect(item.allDay).toBe(false)
      expect(item.color).toBeNull()
      expect(item.scopeType).toBe('ORGANIZATION')
    })

    it('CAL-004: endAt が無い場合は startAt へフォールバックする', () => {
      const noEnd: NestedScheduleResponse = {
        id: 2,
        content: { title: 'y' },
        time: { startAt: '2026-04-06T10:00:00', allDay: false },
      }
      const item = toCalendarEventItem(noEnd, 'TEAM')
      expect(item.endAt).toBe('2026-04-06T10:00:00')
    })
  })

  describe('toCalendarEventItems', () => {
    it('CAL-005: 配列を一括変換する', () => {
      const list = toCalendarEventItems([nestedFull, { id: 99, content: { title: 'b' } }], 'TEAM')
      expect(list).toHaveLength(2)
      expect(list[0]!.title).toBe('プリンスリーグ関東 第3節 vs 横浜FCユース')
      expect(list[1]!.title).toBe('b')
      expect(list[1]!.scopeType).toBe('TEAM')
    })
  })

  describe('toFlatScheduleEvent', () => {
    it('CAL-006: 詳細 GET のネスト構造を EventDetailPanel 用の平坦型へ変換する', () => {
      const flat = toFlatScheduleEvent(nestedFull)
      expect(flat.id).toBe(42)
      expect(flat.title).toBe('プリンスリーグ関東 第3節 vs 横浜FCユース')
      expect(flat.location).toBe('味の素フィールド西が丘')
      expect(flat.startAt).toBe('2026-04-06T13:00:00')
      expect(flat.endAt).toBe('2026-04-06T15:00:00')
      expect(flat.allDay).toBe(false)
      expect(flat.status).toBe('PUBLISHED')
      expect(flat.eventType).toBe('MATCH')
      expect(flat.attendanceRequired).toBe(true)
      expect(flat.myAttendance).toBe('YES')
      expect(flat.createdBy.displayName).toBe('コーチA')
    })

    it('CAL-007: 詳細 GET で eventCategory が null の場合 categoryName/Color は null（BE現仕様）', () => {
      const noCat: NestedScheduleResponse = {
        ...nestedFull,
        academic: { eventCategory: null },
      }
      const flat = toFlatScheduleEvent(noCat)
      expect(flat.categoryName).toBeNull()
      expect(flat.categoryColor).toBeNull()
    })

    it('CAL-008: createdByDisplayName 欠落でも createdBy.displayName は空文字で安全', () => {
      const minimal: NestedScheduleResponse = { id: 3, content: { title: 'z' } }
      const flat = toFlatScheduleEvent(minimal)
      expect(flat.createdBy.displayName).toBe('')
      expect(flat.attendanceRequired).toBe(false)
      expect(flat.myAttendance).toBeNull()
    })
  })
})
