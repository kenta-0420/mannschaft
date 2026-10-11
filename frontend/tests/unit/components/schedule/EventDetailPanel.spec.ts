import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, mockNuxtImport } from '@nuxt/test-utils/runtime'
import type { DOMWrapper } from '@vue/test-utils'
import EventDetailPanel from '~/components/schedule/EventDetailPanel.vue'
import { toFlatScheduleEvent } from '~/utils/scheduleCalendar'

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
 *   EDP-009: 組織の解決中（親組織が複数かまだ分からない間）に押しても作成されない
 *   EDP-010: 初回の組織取得が失敗し、押した時点の再取得で親組織が2つ返っても、選ぶまで作成されない
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
function findRecordButton(wrapper: { findAll: (selector: string) => DOMWrapper<Element>[] }) {
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

  it.each(['team', 'organization'] as const)('CMP-260902-0058: %s の詳細GETから保存済み説明文を表示する', async (scopeType) => {
    const response = {
      id: 123,
      content: { title: '集合案内', eventType: 'OTHER', attendanceRequired: false },
      time: { startAt: '2026-07-01T10:00:00+09:00', endAt: '2026-07-01T12:00:00+09:00' },
      detail: { description: '集合は正門\n持ち物：水筒', color: '#a855f7', visibility: 'MEMBERS_ONLY' },
    }
    const wrapper = await mountSuspended(EventDetailPanel, {
      props: { event: toFlatScheduleEvent(response), scopeType, scopeId: 'scope-1', canEdit: false },
    })
    expect(wrapper.text()).toContain('集合は正門')
    expect(wrapper.text()).toContain('持ち物：水筒')
    wrapper.unmount()
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
    await new Promise((r) => setTimeout(r, 0)) // 親組織の解決完了を待つ
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
    await new Promise((r) => setTimeout(r, 0)) // 親組織の解決完了を待つ
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

  it('EDP-009: 組織の解決中に押しても、代表親組織で作成されない（解決完了まで押せない）', async () => {
    // 解決が終わらない状態を作る（親組織が複数かどうかまだ分からない）
    mockResolveContext.mockImplementation(() => new Promise(() => {}))
    mockResolveBySchedule.mockResolvedValue(null)
    mockCreateMatch.mockResolvedValue({ id: 'm-new' })

    const wrapper = await mountSuspended(EventDetailPanel, {
      props: { event: baseEvent(), scopeType: 'team', scopeId: 'team-uuid', canEdit: false },
    })
    const button = findRecordButton(wrapper)!
    expect(button.attributes('disabled')).toBeDefined()
    await button.trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    expect(mockCreateMatch).not.toHaveBeenCalled()
    expect(mockResolveBySchedule).not.toHaveBeenCalled()
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('EDP-010: 初回の取得が失敗し、再取得で親組織が2つ返っても、選択するまで作成されない', async () => {
    // 初回（マウント時）は取得失敗（composable は警告を出して null を返す）、2回目以降は親組織が2つ
    mockResolveContext.mockResolvedValueOnce(null)
    mockResolveContext.mockImplementation(async (_slug: string, sel?: { orgId?: number | null }) => ({
      orgId: sel?.orgId ?? 11,
      orgInvalid: false,
      teamId: 42,
      organizations: MULTI_ORGS,
    }))
    mockResolveBySchedule.mockResolvedValue(null)
    mockCreateMatch.mockResolvedValue({ id: 'm-new' })

    const wrapper = await mountSuspended(EventDetailPanel, {
      props: { event: baseEvent(), scopeType: 'team', scopeId: 'team-uuid', canEdit: false },
    })
    await new Promise((r) => setTimeout(r, 0))
    // 取得失敗の後なのでセレクタはまだ無く、押せる（再取得させるため）
    expect(wrapper.find('[data-testid="match-org-select"]').exists()).toBe(false)

    await findRecordButton(wrapper)!.trigger('click')
    await new Promise((r) => setTimeout(r, 0))

    // 再取得で親組織が2つと分かった: 代表親組織(11)で作らず、セレクタを出して選択待ちになる
    expect(mockCreateMatch).not.toHaveBeenCalled()
    expect(mockNavigate).not.toHaveBeenCalled()
    const select = wrapper.find('[data-testid="match-org-select"]')
    expect(select.exists()).toBe(true)
    expect((select.element as HTMLSelectElement).value).toBe('')

    // 選んで押すと、その組織で作成される
    await select.setValue('22')
    await new Promise((r) => setTimeout(r, 0))
    await findRecordButton(wrapper)!.trigger('click')
    await new Promise((r) => setTimeout(r, 0))
    expect(mockCreateMatch).toHaveBeenCalledWith(22, 42, expect.anything())
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
