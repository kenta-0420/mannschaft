import { defineComponent } from 'vue'
import { flushPromises } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import Dialog from './FriendFollowDialog.vue'
const mocks = vi.hoisted(() => ({ list: vi.fn(), follow: vi.fn(), error: vi.fn() }))
mockNuxtImport('useFriendTeamsApi', () => () => ({ listSourceTeams: mocks.list, follow: mocks.follow }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
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
describe('FR-02/05/08 申請確認', () => {
  beforeEach(() => { vi.clearAllMocks(); mocks.list.mockReset(); mocks.follow.mockReset() })
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
  it('受信の返しは元・先を固定し候補APIを取得しない', async () => {
    const w = await mountDialog({ teamId: '1', sourceTeam: source(1) })
    expect(mocks.list).not.toHaveBeenCalled()
    expect(w.get('[data-testid="friend-request-direction"]').text()).toContain('Source 1 (source-1) → Target (target-team)')
    expect(w.findComponent({ name: 'Select' }).exists()).toBe(false)
    w.unmount()
  })
})
