import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import EventDetailPanel from '~/components/schedule/EventDetailPanel.vue'

/**
 * F08.10 入口④ EventDetailPanel.vue ユニットテスト
 *
 * 観点:
 *   EDP-001: TEAM スコープ予定では「この試合を記録」ボタンが描画される
 *   EDP-002: organization スコープ予定ではボタンを描画しない（他用途を壊さない）
 *   EDP-003: 既存 match があれば作成せず live を開く（二重起票防止）
 *   EDP-004: 既存が無ければプリフィルして作成 → live へ遷移
 */

const mockNavigate = vi.fn()
const mockResolveContext = vi.fn()
const mockResolveBySchedule = vi.fn()
const mockCreateMatch = vi.fn()

mockNuxtImport('navigateTo', () => (...args: unknown[]) => mockNavigate(...args))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
mockNuxtImport('useDatetime', () => () => ({
  formatDate: (s: string) => s,
  formatDateTime: (s: string) => s,
  buildOffsetDateTimeStr: (d: Date) => d.toISOString(),
}))
mockNuxtImport('useScheduleApi', () => () => ({
  getSchedule: vi.fn().mockResolvedValue({ data: {} }),
  cancelScheduledTask: vi.fn(),
}))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn() }))
mockNuxtImport('useEventDelegationApi', () => () => ({
  fetchDelegations: vi.fn().mockResolvedValue({ total: 0 }),
}))
mockNuxtImport('useMatchOrgContext', () => () => ({ resolveContext: mockResolveContext }))
mockNuxtImport('useMatchApi', () => () => ({
  resolveMatchBySchedule: mockResolveBySchedule,
  createMatch: mockCreateMatch,
}))
// F03.16 予定コメントスレッド。本テストの関心事ではないため、常に空のスレッドを返すスタブに固定する。
mockNuxtImport('useScheduleComments', () => () => ({
  listComments: vi.fn().mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } }),
  getMeta: vi.fn().mockResolvedValue({ data: { scheduleId: 123, commentsEnabled: true, canPost: false, canPostReason: 'ROLE' } }),
  listReplies: vi.fn().mockResolvedValue({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } }),
  mentionCandidates: vi.fn().mockResolvedValue({ data: [] }),
  createComment: vi.fn(),
  updateComment: vi.fn(),
  deleteComment: vi.fn(),
  updateSettings: vi.fn(),
}))

function baseEvent() {
  return {
    id: 123,
    scheduleId: 123,
    title: '対 相手FC',
    description: null,
    location: '市民グラウンド',
    startAt: '2026-07-01T10:00:00+09:00',
    endAt: '2026-07-01T12:00:00+09:00',
    allDay: false,
    status: 'PUBLISHED',
    categoryName: null,
    categoryColor: null,
    createdBy: { displayName: '監督' },
    attendanceRequired: false,
    myAttendance: null,
    attendanceStats: null,
  }
}

// 記録ボタンは pi-play アイコン付き（パネル内で唯一）。i18n は実インスタンスが英語ラベルに
// 解決するため、キー文字列でなくアイコンで特定する。
function findRecordButton(wrapper: { findAll: (s: string) => Array<{ html: () => string; trigger: (e: string) => Promise<void> }> }) {
  return wrapper
    .findAll('button')
    .find((b) => b.html().includes('pi-play'))
}

describe('EventDetailPanel.vue（入口④）', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    mockResolveContext.mockReset()
    mockResolveBySchedule.mockReset()
    mockCreateMatch.mockReset()
  })

  it('EDP-001: TEAM スコープ予定では記録ボタンを描画する', async () => {
    const wrapper = await mountSuspended(EventDetailPanel, {
      props: { event: baseEvent(), scopeType: 'team', scopeId: 'team-uuid', canEdit: false },
    })
    expect(findRecordButton(wrapper)).toBeTruthy()
  })

  it('EDP-002: organization スコープ予定では記録ボタンを描画しない', async () => {
    const wrapper = await mountSuspended(EventDetailPanel, {
      props: { event: baseEvent(), scopeType: 'organization', scopeId: 'org-uuid', canEdit: false },
    })
    expect(findRecordButton(wrapper)).toBeFalsy()
  })

  it('EDP-003: 既存 match があれば作成せず live を開く', async () => {
    mockResolveContext.mockResolvedValue({ orgId: 7, teamId: 42 })
    mockResolveBySchedule.mockResolvedValue({ id: 'm-existing' })

    const wrapper = await mountSuspended(EventDetailPanel, {
      props: { event: baseEvent(), scopeType: 'team', scopeId: 'team-uuid', canEdit: false },
    })
    await findRecordButton(wrapper)!.trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    expect(mockResolveBySchedule).toHaveBeenCalledWith(7, 42, 123)
    expect(mockCreateMatch).not.toHaveBeenCalled()
    expect(mockNavigate).toHaveBeenCalledWith('/teams/team-uuid/matches/m-existing/live')
  })

  it('EDP-004: 既存が無ければプリフィルして作成 → live へ遷移', async () => {
    mockResolveContext.mockResolvedValue({ orgId: 7, teamId: 42 })
    mockResolveBySchedule.mockResolvedValue(null)
    mockCreateMatch.mockResolvedValue({ id: 'm-new' })

    const wrapper = await mountSuspended(EventDetailPanel, {
      props: { event: baseEvent(), scopeType: 'team', scopeId: 'team-uuid', canEdit: false },
    })
    await findRecordButton(wrapper)!.trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    expect(mockCreateMatch).toHaveBeenCalledWith(7, 42, {
      kind: 'PRACTICE',
      opponentName: '対 相手FC',
      scheduleId: 123,
      // BE の CreateMatchRequest.kickoffAt は LocalDateTime（タイムゾーンなし）のため、
      // ScheduleResponse.startAt の OffsetDateTime からオフセットを除去した値を送る（#1513）。
      kickoffAt: '2026-07-01T10:00:00',
      venue: '市民グラウンド',
    })
    expect(mockNavigate).toHaveBeenCalledWith('/teams/team-uuid/matches/m-new/live')
  })
})
