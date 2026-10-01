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
 *   EDP-005: 予定に組織が無く親組織が複数（実際の呼び出し元と同じ props＝organizationId 無し）なら、
 *            既定を置かず、選ぶまで作成できない（F01.2.1 §9.2 F3）
 *   EDP-006: セレクタで選んだ組織で試合を作り、live に org を引き継ぐ
 *   EDP-007: 親組織が1つ（organizationId 無し）なら、その組織で作成できる
 *   EDP-008: 予定の組織（organizationId）がチームの親組織でなければ、代表親組織へ落とさず作成を止める
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
    mockResolveContext.mockResolvedValue({ orgId: 7, orgInvalid: false, teamId: 42, organizations: [{ id: 7, slug: 'x', name: '組織X' }] })
    mockResolveBySchedule.mockResolvedValue({ id: 'm-existing' })

    const wrapper = await mountSuspended(EventDetailPanel, {
      props: { event: baseEvent(), scopeType: 'team', scopeId: 'team-uuid', canEdit: false },
    })
    await findRecordButton(wrapper)!.trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    expect(mockResolveBySchedule).toHaveBeenCalledWith(7, 42, 123)
    expect(mockCreateMatch).not.toHaveBeenCalled()
    expect(mockNavigate).toHaveBeenCalledWith({
      path: '/teams/team-uuid/matches/m-existing/live',
      query: { org: '7' },
    })
  })

  it('EDP-004: 既存が無ければプリフィルして作成 → live へ遷移', async () => {
    mockResolveContext.mockResolvedValue({ orgId: 7, orgInvalid: false, teamId: 42, organizations: [{ id: 7, slug: 'x', name: '組織X' }] })
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
    expect(mockNavigate).toHaveBeenCalledWith({
      path: '/teams/team-uuid/matches/m-new/live',
      query: { org: '7' },
    })
  })

  const MULTI_ORGS = [
    { id: 11, slug: 'x', name: '組織X' },
    { id: 22, slug: 'y', name: '組織Y' },
  ]
  // 実際の呼び出し元（calendar.vue）は organizationId を渡さない。選択が無ければ代表親組織 X(11) が既定になる BE 側の振る舞いを模す。
  function multiParentContext(): void {
    mockResolveContext.mockImplementation(async (_slug: string, sel?: { orgId?: number | null }) => ({
      orgId: sel?.orgId ?? 11,
      orgInvalid: false,
      teamId: 42,
      organizations: MULTI_ORGS,
    }))
  }

  it('EDP-005: 予定に組織が無く親組織が複数なら、選ぶまで作成できない（既定の代表親組織で作らない）', async () => {
    multiParentContext()
    mockResolveBySchedule.mockResolvedValue(null)
    mockCreateMatch.mockResolvedValue({ id: 'm-new' })

    const wrapper = await mountSuspended(EventDetailPanel, {
      props: { event: baseEvent(), scopeType: 'team', scopeId: 'team-uuid', canEdit: false },
    })
    await new Promise((r) => setTimeout(r, 0))

    // セレクタは出ていて、まだ何も選ばれていない
    const select = wrapper.find('[data-testid="match-org-select"]')
    expect(select.exists()).toBe(true)
    expect((select.element as HTMLSelectElement).value).toBe('')
    // 記録ボタンは無効で、押しても作成されない
    const button = findRecordButton(wrapper)!
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(mockCreateMatch).not.toHaveBeenCalled()
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('EDP-006: セレクタで選んだ組織で試合を作り、live に org を引き継ぐ', async () => {
    multiParentContext()
    mockResolveBySchedule.mockResolvedValue(null)
    mockCreateMatch.mockResolvedValue({ id: 'm-new' })

    const wrapper = await mountSuspended(EventDetailPanel, {
      props: { event: baseEvent(), scopeType: 'team', scopeId: 'team-uuid', canEdit: false },
    })
    await new Promise((r) => setTimeout(r, 0))
    await wrapper.find('[data-testid="match-org-select"]').setValue('22')
    await new Promise((r) => setTimeout(r, 0))
    await findRecordButton(wrapper)!.trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    expect(mockCreateMatch).toHaveBeenCalledWith(22, 42, expect.anything())
    expect(mockNavigate).toHaveBeenCalledWith({
      path: '/teams/team-uuid/matches/m-new/live',
      query: { org: '22' },
    })
  })

  it('EDP-007: 親組織が1つ（organizationId 無し）なら、その組織で作成できる', async () => {
    mockResolveContext.mockResolvedValue({
      orgId: 7,
      orgInvalid: false,
      teamId: 42,
      organizations: [{ id: 7, slug: 'x', name: '組織X' }],
    })
    mockResolveBySchedule.mockResolvedValue(null)
    mockCreateMatch.mockResolvedValue({ id: 'm-new' })

    const wrapper = await mountSuspended(EventDetailPanel, {
      props: { event: baseEvent(), scopeType: 'team', scopeId: 'team-uuid', canEdit: false },
    })
    await new Promise((r) => setTimeout(r, 0))
    expect(wrapper.find('[data-testid="match-org-select"]').exists()).toBe(false)
    await findRecordButton(wrapper)!.trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    expect(mockCreateMatch).toHaveBeenCalledWith(7, 42, expect.anything())
  })

  it('EDP-008: 予定の組織がチームの親組織でなければ、代表親組織へ落とさず作成を止める', async () => {
    mockResolveContext.mockResolvedValue({
      orgId: null,
      orgInvalid: true,
      teamId: 42,
      organizations: [{ id: 11, slug: 'x', name: '組織X' }],
    })

    const wrapper = await mountSuspended(EventDetailPanel, {
      props: {
        event: { ...baseEvent(), organizationId: 999 },
        scopeType: 'team',
        scopeId: 'team-uuid',
        canEdit: false,
      },
    })
    await findRecordButton(wrapper)!.trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    expect(mockResolveContext).toHaveBeenCalledWith('team-uuid', { orgId: 999 })
    expect(mockCreateMatch).not.toHaveBeenCalled()
    expect(mockResolveBySchedule).not.toHaveBeenCalled()
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  // 是正4【P2】: 個人予定は scheduleId=null で渡され、コメント欄自体が描画されないこと（設計書 §AC-17。
  // 個人予定は本人からの全 API も 404 のため、コメント欄を出すと空表示＋エラー通知が出てしまう）。
  it('是正4 scheduleId=null（個人予定）ではコメント欄を描画しない', async () => {
    const wrapper = await mountSuspended(EventDetailPanel, {
      props: {
        event: { ...baseEvent(), scheduleId: null },
        scopeType: 'team',
        scopeId: 'team-uuid',
        canEdit: false,
      },
    })
    expect(wrapper.find('[data-testid="schedule-comment-section"]').exists()).toBe(false)
  })

  it('是正4 scheduleId が数値（共有予定）ではコメント欄を描画する', async () => {
    const wrapper = await mountSuspended(EventDetailPanel, {
      props: { event: baseEvent(), scopeType: 'team', scopeId: 'team-uuid', canEdit: false },
    })
    expect(wrapper.find('[data-testid="schedule-comment-section"]').exists()).toBe(true)
  })
})
