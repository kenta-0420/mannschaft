import { describe, expect, it } from 'vitest'
import type { components } from '~/types/generated'
import { toCalendarPanelEvent, toFlatScheduleEvent, type NestedScheduleResponse, type NestedScheduleDetailResponse } from './scheduleCalendar'

/**
 * F03.19 実機E2E 欠陥1 の再発防止。
 *
 * 欠陥は「API 応答の構造（content / time にネスト）と、画面が読む構造（平坦な title / startAt）の
 * 食い違い」であり、`res.data as EventDetail` という嘘のキャストで押し通されていたため
 * 型検査もテストもすり抜けた。ここでは
 *
 *  1. フィクスチャを **生成型（OpenAPI 由来）としても正しい**ことを `satisfies` で固定し
 *     （手書きの都合のよい形で「動くように見える」フィクスチャを作らない）、
 *  2. 変換後に題名・日時が実値になることを検証する
 *
 * の2段で守る。1 が無いと、フィクスチャ自体が実在しない平坦な形になり偽の緑になる。
 */
type GeneratedScheduleResponse = components['schemas']['ScheduleResponse']
type GeneratedScheduleDetailResponse = components['schemas']['ScheduleDetailResponse']

/** 実際の GET /api/v1/teams/{slug}/schedules/{id} 応答（ScheduleResponse）と同じ形。 */
const apiResponse = {
  id: 4321,
  content: {
    title: '練習試合',
    status: 'PUBLISHED',
    eventType: 'PRACTICE',
    location: '第2グラウンド',
    attendanceRequired: true,
  },
  time: {
    startAt: '2026-09-05T10:00:00',
    endAt: '2026-09-05T12:00:00',
    allDay: false,
  },
  scope: { scopeName: '一軍', scopeIconUrl: 'https://example.test/icon.png' },
  audit: { createdByDisplayName: '山田太郎' },
  myAttendanceStatus: 'YES',
} satisfies GeneratedScheduleResponse & NestedScheduleResponse

describe('toCalendarPanelEvent（詳細 GET → 詳細パネル）', () => {
  it('保存済みの説明文・色をチーム予定とカレンダー詳細へ渡す', () => {
    const detailResponse = {
      ...apiResponse,
      detail: { description: '集合は正門\n持ち物：水筒', color: '#a855f7', visibility: 'MEMBERS_ONLY' },
    } satisfies GeneratedScheduleDetailResponse & NestedScheduleDetailResponse
    expect(toFlatScheduleEvent(detailResponse).description).toBe('集合は正門\n持ち物：水筒')
    const panel = toCalendarPanelEvent(detailResponse, { scheduleId: 4321 })
    expect(panel.description).toBe('集合は正門\n持ち物：水筒')
    expect(panel.color).toBe('#a855f7')
  })

  it('詳細を持たない一覧応答は説明なしを保ち、色は既存カテゴリで補う', () => {
    const response = { ...apiResponse, academic: { eventCategory: { name: '練習', color: '#123456' } } }
    const panel = toCalendarPanelEvent(response, {})
    expect(panel.description).toBeNull()
    expect(panel.color).toBe('#123456')
  })

  it('予定の個別色をカテゴリ色より優先する', () => {
    const response = {
      ...apiResponse,
      detail: { description: null, color: '#a855f7' },
      academic: { eventCategory: { name: '練習', color: '#123456' } },
    }
    expect(toCalendarPanelEvent(response, {}).color).toBe('#a855f7')
  })

  it.each([null, ''])('説明文が %s、個別色がnullでも値を保ちカテゴリ色で補う', (description) => {
    const response = {
      ...apiResponse,
      detail: { description, color: null },
      academic: { eventCategory: { name: '練習', color: '#123456' } },
    }
    expect(toFlatScheduleEvent(response).description).toBe(description)
    expect(toCalendarPanelEvent(response, {}).color).toBe('#123456')
  })

  it('ネストした content / time から題名・日時を取り出す（欠陥1: スプレッドでは undefined になっていた）', () => {
    const panel = toCalendarPanelEvent(apiResponse, { scheduleId: 4321 })

    // 既定値（空文字・false）と偶然一致して偽の緑にならないよう、実値そのものを検証する。
    expect(panel.title).toBe('練習試合')
    expect(panel.startAt).toBe('2026-09-05T10:00:00')
    expect(panel.endAt).toBe('2026-09-05T12:00:00')
    expect(panel.location).toBe('第2グラウンド')
    expect(panel.status).toBe('PUBLISHED')
    expect(panel.attendanceRequired).toBe(true)
    expect(panel.myAttendance).toBe('YES')
    expect(panel.createdBy).toEqual({ displayName: '山田太郎' })
  })

  it('スコープ名・アイコンは応答を優先し、応答に無ければカレンダー行の値で補う', () => {
    const panel = toCalendarPanelEvent(apiResponse, { scopeType: 'TEAM', scopeId: 'ichigun', scopeName: '別名' })
    expect(panel.scopeName).toBe('一軍')
    expect(panel.scopeIconUrl).toBe('https://example.test/icon.png')
    expect(panel.scopeType).toBe('TEAM')
    expect(panel.scopeId).toBe('ichigun')

    const noScope = toCalendarPanelEvent({ ...apiResponse, scope: undefined }, { scopeName: '一軍（行から）' })
    expect(noScope.scopeName).toBe('一軍（行から）')
  })

  it('scheduleId は応答の予定 ID ではなくカレンダー行の値を使う（コメント欄の表示ガード）', () => {
    expect(toCalendarPanelEvent(apiResponse, { scheduleId: 99 }).scheduleId).toBe(99)
    expect(toCalendarPanelEvent(apiResponse, {}).scheduleId).toBeNull()
  })

  it('対象者情報は応答が持たないときだけカレンダー行の値で補う', () => {
    const fromRow = toCalendarPanelEvent(apiResponse, {
      targetMode: 'SELECTED_MEMBERS',
      targetCount: 3,
      targets: [{ userId: 1, displayName: '佐藤', avatarUrl: null, calendarColor: null }],
    })
    expect(fromRow.targetMode).toBe('SELECTED_MEMBERS')
    expect(fromRow.targetCount).toBe(3)
    expect(fromRow.targets).toHaveLength(1)

    const fromResponse = toCalendarPanelEvent(
      { ...apiResponse, targetMode: 'ALL_MEMBERS', targetCount: 20 },
      { targetMode: 'SELECTED_MEMBERS', targetCount: 3 },
    )
    expect(fromResponse.targetMode).toBe('ALL_MEMBERS')
    expect(fromResponse.targetCount).toBe(20)
  })

  it('作成者名が応答に無くても「作成者: 空欄」を作らない', () => {
    const panel = toCalendarPanelEvent({ ...apiResponse, audit: undefined }, {})
    expect(panel.createdBy.displayName).toBe('')
  })
})
