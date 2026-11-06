import { defineComponent } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import Dialog from './FriendFollowDialog.vue'
const mocks = vi.hoisted(() => ({ list: vi.fn(), follow: vi.fn(), error: vi.fn(), friends: vi.fn(), pending: vi.fn(), incoming: vi.fn() }))
mockNuxtImport('useFriendTeamsApi', () => () => ({ listSourceTeams: mocks.list, follow: mocks.follow, listFriends: mocks.friends, listPendingFollows: mocks.pending, listIncomingFollows: mocks.incoming }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string, counts?: Record<string, number>) => key === 'friends.request.status_counts' ? JSON.stringify(counts) : key }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), info: vi.fn(), error: mocks.error }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))
const source = (id: number) => ({ teamId: id, teamSlug: 'source-' + id, teamName: 'Source ' + id })
const result = (ids: number[]) => ({ data: ids.map(source), pagination: { page: 0, size: 20, totalElements: ids.length, totalPages: ids.length ? 1 : 0, hasNext: false } })
const shell = defineComponent({ props: ['visible'], template: '<div v-if="visible"><slot /><slot name="footer" /></div>' })
async function mountDialog(props = {}) {
  const w = await mountSuspended(Dialog, { props: { teamId: '', modelValue: true, targetTeam: { teamSlug: 'target-team', teamName: 'Target' }, ...props }, global: { stubs: { Dialog: shell } } })
  await flushPromises()
  return w
}
async function mountBusyDialog() {
  mocks.list.mockResolvedValue({ ...result([1, 2]), pagination: { ...result([1, 2]).pagination, totalElements: 21, totalPages: 2, hasNext: true } })
  mocks.follow.mockImplementation(() => new Promise(() => {}))
  const w = await mountDialog()
  w.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', source(1))
  await flushPromises()
  await w.get('[data-testid="friend-request-review"]').trigger('click')
  await w.get('[data-testid="friend-follow-submit"]').trigger('click')
  await flushPromises()
  return w
}
const countsResult = (total: number) => ({ data: [], pagination: { totalElements: total } })
async function requestSourceAStatus() {
  mocks.list.mockResolvedValue(result([1, 2]))
  mocks.follow.mockRejectedValueOnce({ statusCode: 409, data: { error: { code: 'SOCIAL_102' } } })
  mocks.pending.mockResolvedValue(countsResult(2))
  mocks.incoming.mockResolvedValue(countsResult(3))
  const w = await mountDialog()
  w.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', source(1))
  await flushPromises()
  await w.get('[data-testid="friend-request-review"]').trigger('click')
  await w.get('[data-testid="friend-follow-submit"]').trigger('click')
  await flushPromises()
  return w
}
describe('FR-02/05/08 申請確認', () => {
  beforeEach(() => { vi.clearAllMocks(); mocks.list.mockReset(); mocks.follow.mockReset(); mocks.friends.mockReset(); mocks.pending.mockReset(); mocks.incoming.mockReset() })
  it('0/1/複数の候補を総数で分岐し、1件は選択済みと明示する', async () => {
    for (const ids of [[], [1], [1, 2]]) {
      mocks.list.mockResolvedValueOnce(result(ids))
      const w = await mountDialog()
      expect(w.find('[data-testid="friend-source-auto"]').exists()).toBe(ids.length === 1)
      expect(w.get('[data-testid="friend-request-review"]').attributes('disabled') !== undefined).toBe(ids.length !== 1)
      w.unmount()
    }
  })
  it('選択→元から先の確認→送信の順だけで数値元pathへPOSTしcommentを送らない', async () => {
    mocks.list.mockResolvedValueOnce(result([1, 2]))
    mocks.follow.mockResolvedValueOnce({ followId: 10, mutual: false })
    const w = await mountDialog()
    w.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', source(2))
    await flushPromises()
    expect(w.get('[data-testid="friend-request-direction"]').text()).toContain('Source 2 (source-2) → Target (target-team)')
    expect(mocks.follow).not.toHaveBeenCalled()
    await w.get('[data-testid="friend-request-review"]').trigger('click')
    await w.get('[data-testid="friend-follow-submit"]').trigger('click')
    await flushPromises()
    expect(mocks.follow).toHaveBeenCalledWith('2', { targetTeamSlug: 'target-team', comment: undefined })
    expect(w.emitted('success')).toHaveLength(1)
    w.unmount()
  })
  it('キャンセルでPOSTせず、403は選択解除・再取得・明示再選択を必要とする', async () => {
    mocks.list.mockResolvedValue(result([1]))
    mocks.follow.mockRejectedValueOnce({ statusCode: 403 })
    const w = await mountDialog()
    await w.get('[data-testid="friend-request-review"]').trigger('click')
    await w.get('[data-testid="friend-follow-submit"]').trigger('click')
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect(w.find('[data-testid="friend-source-auto"]').exists()).toBe(false)
    expect(w.get('[data-testid="friend-request-review"]').attributes('disabled')).toBeDefined()
    expect(w.emitted('update:modelValue')).toBeUndefined()
    w.unmount()
    mocks.follow.mockClear()
    const cancel = await mountDialog()
    await cancel.findAll('button').find(button => button.text().includes('friend_feed.forward.cancel'))!.trigger('click')
    expect(mocks.follow).not.toHaveBeenCalled()
    expect(cancel.emitted('update:modelValue')).toEqual([[false]])
    cancel.unmount()
  })
  it('FR-08: 送信中のpageイベントは候補GETを増やさず選択元を維持する', async () => {
    const w = await mountBusyDialog()
    w.findComponent({ name: 'Paginator' }).vm.$emit('page', { page: 1 })
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(1)
    expect(w.findComponent({ name: 'Select' }).props('modelValue')).toEqual(source(1))
    w.unmount()
  })
  it('FR-08: 送信中は実Paginatorのpage2 buttonもnative disabledまたはinert配下に置く', async () => {
    const w = await mountBusyDialog()
    try {
      const page2 = w.findComponent({ name: 'Paginator' }).findAll('button').find(button => button.text() === '2')
      expect(page2).toBeDefined()
      const physicallyDisabled = page2!.attributes('disabled') !== undefined || page2!.element.closest('fieldset[disabled], [inert]') !== null
      expect(physicallyDisabled).toBe(true)
    } finally { w.unmount() }
  })
  it('FR-08: 送信中のSelect更新イベントでも確認した申請元を変えない', async () => {
    const w = await mountBusyDialog()
    w.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', source(2))
    await flushPromises()
    expect(w.findComponent({ name: 'Select' }).props('modelValue')).toEqual(source(1))
    expect(w.get('[data-testid="friend-request-direction"]').text()).toContain('Source 1 (source-1)')
    w.unmount()
  })
  it('申請元Aの件数取得後にBへ選び直すとAの件数を表示しない', async () => {
    mocks.friends.mockResolvedValue(countsResult(7))
    const w = await requestSourceAStatus()
    try {
      expect(w.get('p[role="status"]').text()).toBe('{"friends":7,"pending":2,"incoming":3}')
      expect(mocks.friends).toHaveBeenCalledWith('1', { page: 0, size: 1 })
      expect(mocks.friends).toHaveBeenCalledTimes(1)
      expect(mocks.pending).toHaveBeenCalledTimes(1)
      expect(mocks.incoming).toHaveBeenCalledTimes(1)
      w.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', source(2))
      await flushPromises()
      expect(w.find('p[role="status"]').exists()).toBe(false)
    } finally { w.unmount() }
  })
  it('申請元Aの状態取得エラーはBへ選び直した時に表示しない', async () => {
    mocks.friends.mockRejectedValueOnce(new Error('source A unavailable'))
    const w = await requestSourceAStatus()
    try {
      expect(w.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(true)
      w.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', source(2))
      await flushPromises()
      expect(w.findComponent({ name: 'DashboardErrorState' }).exists()).toBe(false)
    } finally { w.unmount() }
  })
  it('AからBを経てAへ戻っても古い状態取得世代の遅延件数を適用しない', async () => {
    let resolveOld!: (value: ReturnType<typeof countsResult>) => void
    mocks.friends.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve }))
    const w = await requestSourceAStatus()
    try {
      w.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', source(2))
      await flushPromises()
      w.findComponent({ name: 'Select' }).vm.$emit('update:modelValue', source(1))
      await flushPromises()
      resolveOld(countsResult(7))
      await flushPromises()
      expect(w.find('p[role="status"]').exists()).toBe(false)
    } finally { w.unmount() }
  })
  it('固定元409はalreadyFollowingだけで親へ一度の更新を要求する', async () => {
    mocks.follow.mockRejectedValueOnce({ statusCode: 409, data: { error: { code: 'SOCIAL_102' } } })
    const w = await mountDialog({ teamId: '1', sourceTeam: source(1) })
    try {
      await w.get('[data-testid="friend-request-review"]').trigger('click')
      await w.get('[data-testid="friend-follow-submit"]').trigger('click')
      await flushPromises()
      expect(w.emitted('alreadyFollowing')).toHaveLength(1)
      expect(w.emitted('refresh')).toBeUndefined()
      expect(mocks.friends).not.toHaveBeenCalled()
    } finally { w.unmount() }
  })
  it('受信の返しは元・先を固定し候補APIを取得しない', async () => {
    const w = await mountDialog({ teamId: '1', sourceTeam: source(1) })
    expect(mocks.list).not.toHaveBeenCalled()
    expect(w.get('[data-testid="friend-request-direction"]').text()).toContain('Source 1 (source-1) → Target (target-team)')
    expect(w.findComponent({ name: 'Select' }).exists()).toBe(false)
    w.unmount()
  })
})
