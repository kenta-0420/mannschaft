import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises } from '@vue/test-utils'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import EventDetailPanel from './EventDetailPanel.vue'

const mocks = vi.hoisted(() => ({ api: vi.fn(), events: vi.fn() }))
mockNuxtImport('useApi', () => () => mocks.api)
mockNuxtImport('useEventDelegationApi', () => () => ({ fetchDelegations: mocks.events }))
mockNuxtImport('useDatetime', () => () => ({ formatDate: (s: string) => s, formatDateTime: (s: string) => s }))
mockNuxtImport('useMatchOrgContext', () => () => ({ resolveContext: vi.fn().mockResolvedValue(null) }))
mockNuxtImport('useMatchApi', () => () => ({ resolveMatchBySchedule: vi.fn(), createMatch: vi.fn() }))
mockNuxtImport('useScheduleApi', () => () => ({ getSchedule: vi.fn().mockResolvedValue({ data: {} }) }))
mockNuxtImport('useNotification', () => () => ({}))

function event(id = 123) {
  return {
    id, scheduleId: null, title: '練習', description: null, location: null,
    startAt: '2026-10-17T12:00:00+09:00', endAt: '2026-10-17T13:00:00+09:00',
    allDay: false, status: 'SCHEDULED', categoryName: null, categoryColor: null,
    createdBy: { displayName: '' }, attendanceRequired: false, myAttendance: null, attendanceStats: null,
  }
}
const perms = (roleName = 'ADMIN') => ({ data: { roleName, permissions: [] } })
const summary = (total: number) => ({ data: { delegations: [], total, page: 0, size: 1 } })
function deferred() {
  let resolve!: (value: unknown) => void
  const promise = new Promise((res) => { resolve = res })
  return { resolve, promise }
}
async function panel(overrides = {}) {
  const wrapper = await mountSuspended(EventDetailPanel, {
    props: { event: event(), scopeType: 'organization', scopeId: 'org-a', canEdit: true, ...overrides },
    global: { stubs: { ScheduleTargetAudience: true } },
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  mocks.api.mockReset().mockImplementation((url: string) => Promise.resolve(
    url.endsWith('/me/permissions') ? perms() : summary(7),
  ))
  // 同じ数値のeventデータがあっても混在させない。
  mocks.events.mockReset().mockResolvedValue({ total: 99 })
})

describe('予定詳細の代理出席件数（CMP-261017-1629）', () => {
  it('scheduleId=nullでもevent.idを予定APIへ渡し、実data.totalを表示する', async () => {
    const wrapper = await panel()
    expect(mocks.api).toHaveBeenCalledWith('/api/v1/schedules/123/delegations', { query: { page: 0, size: 1 } })
    expect(mocks.events).not.toHaveBeenCalled()
    expect(wrapper.get('[data-testid="schedule-delegation-count"]').text()).toContain('7')
    expect(wrapper.text()).not.toContain('99')
    expect(mocks.api).toHaveBeenCalledTimes(2)
  })

  it.each([{ skipDelegations: true }, { canEdit: false }, { scopeId: '' }])(
    '対象外%sでは権限APIも代理APIも呼ばない', async (overrides) => {
      await panel(overrides)
      expect(mocks.api).not.toHaveBeenCalled()
      expect(mocks.events).not.toHaveBeenCalled()
    },
  )

  it('同じ予定の表示情報だけが再描画されても取得を重複発行しない', async () => {
    const wrapper = await panel()
    await wrapper.setProps({ event: { ...event(), title: '翻訳後も同じ予定' } })
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledTimes(2)
  })

  it.each(['MEMBER', 'SUPPORTER', 'GUEST'])('canEdit=true固定でも%sは管理者APIを呼ばない', async (role) => {
    mocks.api.mockResolvedValue(perms(role))
    const wrapper = await panel()
    expect(mocks.api).toHaveBeenCalledTimes(1)
    expect(wrapper.find('[data-testid="schedule-delegation-count"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="schedule-delegation-error"]').exists()).toBe(false)
  })

  it.each(['ADMIN', 'DEPUTY_ADMIN', 'SYSTEM_ADMIN'])('%sは既存管理者判定どおり取得できる', async (role) => {
    mocks.api.mockImplementation((url: string) => Promise.resolve(url.endsWith('/me/permissions') ? perms(role) : summary(3)))
    const wrapper = await panel()
    expect(wrapper.get('[data-testid="schedule-delegation-count"]').text()).toContain('3')
  })

  it('成功0件と失敗を区別し、再試行では権限取得から一度だけ実行する', async () => {
    mocks.api.mockImplementation((url: string) => url.endsWith('/me/permissions')
      ? Promise.resolve(perms()) : Promise.reject(new Error('network')))
    const wrapper = await panel()
    expect(wrapper.find('[data-testid="schedule-delegation-error"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="schedule-delegation-count"]').exists()).toBe(false)
    mocks.api.mockImplementation((url: string) => Promise.resolve(url.endsWith('/me/permissions') ? perms() : summary(0)))
    await wrapper.get('[data-testid="schedule-delegation-error-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.find('[data-testid="schedule-delegation-error"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="schedule-delegation-count"]').exists()).toBe(false)
    expect(mocks.api).toHaveBeenCalledTimes(4)
  })

  it('権限取得失敗を表示し、件数APIを呼ばず再試行できる', async () => {
    mocks.api.mockRejectedValue(new Error('permissions unavailable'))
    const wrapper = await panel()
    expect(wrapper.find('[data-testid="schedule-delegation-error"]').exists()).toBe(true)
    expect(mocks.api).toHaveBeenCalledTimes(1)
    mocks.api.mockImplementation((url: string) => Promise.resolve(url.endsWith('/me/permissions') ? perms() : summary(2)))
    await wrapper.get('[data-testid="schedule-delegation-error-retry"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="schedule-delegation-count"]').text()).toContain('2')
    expect(mocks.api).toHaveBeenCalledTimes(3)
  })

  it('予定変更で旧件数を消し、遅延した旧応答で新予定を上書きしない', async () => {
    const old = deferred()
    mocks.api.mockImplementation((url: string) => url.endsWith('/me/permissions') ? Promise.resolve(perms())
      : url.includes('/123/') ? old.promise : Promise.resolve(summary(4)))
    const wrapper = await panel()
    await wrapper.setProps({ event: event(456) })
    await flushPromises()
    expect(wrapper.get('[data-testid="schedule-delegation-count"]').text()).toContain('4')
    old.resolve(summary(8))
    await flushPromises()
    expect(wrapper.get('[data-testid="schedule-delegation-count"]').text()).toContain('4')
    expect(mocks.api).toHaveBeenCalledTimes(4)
  })

  it.each([{ skipDelegations: true }, { canEdit: false }])('取得中に%sへ変更すると旧応答を表示しない', async (overrides) => {
    const old = deferred()
    mocks.api.mockImplementation((url: string) => url.endsWith('/me/permissions') ? Promise.resolve(perms()) : old.promise)
    const wrapper = await panel()
    await wrapper.setProps(overrides)
    old.resolve(summary(8))
    await flushPromises()
    expect(wrapper.find('[data-testid="schedule-delegation-count"]').exists()).toBe(false)
    expect(mocks.api).toHaveBeenCalledTimes(2)
  })

  it('表示済み件数も次の予定取得開始時に消し、次の成功結果まで表示しない', async () => {
    const wrapper = await panel()
    expect(wrapper.get('[data-testid="schedule-delegation-count"]').text()).toContain('7')
    const next = deferred()
    mocks.api.mockImplementation((url: string) => url.endsWith('/me/permissions') ? Promise.resolve(perms()) : next.promise)
    await wrapper.setProps({ event: event(456) })
    expect(wrapper.find('[data-testid="schedule-delegation-count"]').exists()).toBe(false)
    await flushPromises()
    next.resolve(summary(0))
    await flushPromises()
    expect(wrapper.find('[data-testid="schedule-delegation-count"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="schedule-delegation-error"]').exists()).toBe(false)
  })

  it('scopeType切替中の旧権限応答から旧管理者APIを発行しない', async () => {
    const old = deferred()
    mocks.api.mockImplementation((url: string) => url.includes('/organizations/') ? old.promise
      : Promise.resolve(url.endsWith('/me/permissions') ? perms('MEMBER') : summary(4)))
    const wrapper = await panel()
    await wrapper.setProps({ scopeType: 'team', scopeId: 'team-a' })
    await flushPromises()
    old.resolve(perms())
    await flushPromises()
    expect(mocks.api).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="schedule-delegation-count"]').exists()).toBe(false)
  })
})
