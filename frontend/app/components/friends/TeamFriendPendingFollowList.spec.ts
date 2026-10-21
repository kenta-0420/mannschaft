import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import PendingList from '~/components/friends/TeamFriendPendingFollowList.vue'
import jaRaw from '~/locales/ja/friends.json?raw'

const mocks = vi.hoisted(() => ({
  list: vi.fn(), unfollow: vi.fn(), confirm: vi.fn(), toast: vi.fn(), error: vi.fn(),
}))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => {
  let value: unknown = JSON.parse(jaRaw)
  for (const part of key.split('.')) value = (value as Record<string, unknown> | undefined)?.[part]
  return typeof value === 'string' ? value : key
} }))
mockNuxtImport('useFriendTeamsApi', () => () => ({ listPendingFollows: mocks.list, unfollow: mocks.unfollow }))
mockNuxtImport('useConfirmDialog', () => () => ({ confirmAction: mocks.confirm }))
mockNuxtImport('useUndoToast', () => () => ({ showUndoToast: mocks.toast }))
mockNuxtImport('useErrorHandler', () => () => ({ handleApiError: mocks.error }))

const row = { followId: 1, targetTeamId: 2, targetTeamName: '公開チーム', targetTeamSlug: 'other-team', nameHidden: false, followedBack: true, followedAt: '2026-01-01T12:00:00' }
const result = (data = [row], total = data.length) => ({ data, pagination: { page: 0, size: 20, totalElements: total, totalPages: Math.ceil(total / 20), hasNext: total > 20 } })
async function mountList() {
  const w = await mountSuspended(PendingList, { props: { teamId: 'own-team' } })
  await flushPromises()
  return w
}
async function acceptCancel(w: Awaited<ReturnType<typeof mountList>>) {
  await w.get('[data-testid="pending-follow-cancel"]').trigger('click')
  await mocks.confirm.mock.calls.at(-1)![0].onAccept()
  await flushPromises()
}

describe('CMP-261021-0020 フォロー中一覧', () => {
  beforeEach(() => { vi.clearAllMocks(); mocks.list.mockReset(); mocks.unfollow.mockResolvedValue(undefined) })

  it('AC15: 0件は空状態、403/5xx/通信断は取得エラーとし再試行で復旧する', async () => {
    for (const error of [{ statusCode: 403 }, { statusCode: 500 }, new Error('network')]) {
      mocks.list.mockRejectedValueOnce(error).mockResolvedValueOnce(result([]))
      const w = await mountList()
      expect(w.find('[data-testid="pending-follow-error"]').exists()).toBe(true)
      expect(w.find('[data-testid="pending-follow-empty"]').exists()).toBe(false)
      await w.findComponent({ name: 'DashboardErrorState' }).vm.$emit('retry')
      await flushPromises()
      expect(w.find('[data-testid="pending-follow-empty"]').exists()).toBe(true)
      w.unmount()
    }
  })

  it('AC16: 成功した空ページは最終有効ページへ一度だけ戻りfirstも同期する', async () => {
    mocks.list.mockResolvedValueOnce(result([row], 41)).mockResolvedValueOnce(result([], 21)).mockResolvedValueOnce(result([row], 21))
    const w = await mountList()
    w.findComponent({ name: 'Paginator' }).vm.$emit('page', { page: 8 })
    await flushPromises()
    expect(mocks.list.mock.calls.map(c => c[1].page)).toEqual([0, 8, 1])
    expect(w.findComponent({ name: 'Paginator' }).props('first')).toBe(20)
  })

  it('AC16: total0はpage0へ戻し、失敗時はページを補正しない', async () => {
    mocks.list.mockResolvedValueOnce(result([row], 41)).mockRejectedValueOnce({ statusCode: 500 })
    const w = await mountList()
    w.findComponent({ name: 'Paginator' }).vm.$emit('page', { page: 2 })
    await flushPromises()
    expect(mocks.list).toHaveBeenCalledTimes(2)
    mocks.list.mockResolvedValueOnce(result([], 0))
    await w.findComponent({ name: 'DashboardErrorState' }).vm.$emit('retry')
    await flushPromises()
    expect(mocks.list.mock.calls.at(-1)![1].page).toBe(2)
    await (w.vm as unknown as { refresh: () => Promise<void> }).refresh()
    expect(mocks.list.mock.calls.at(-1)![1].page).toBe(0)
  })

  it('AC17/24: 公開相手の取消は成立済み解除も明示し、toast操作で同じslugの再フォローを要求する', async () => {
    mocks.list.mockResolvedValueOnce(result()).mockResolvedValueOnce(result([]))
    const w = await mountList()
    expect(w.get('[data-testid="pending-follow-team-link"]').attributes('href')).toBe('/teams/other-team')
    expect(w.get('[data-testid="pending-follow-recovery"]').text()).toContain('もう一度フォロー')
    await acceptCancel(w)
    expect(mocks.confirm.mock.calls[0][0].message).toContain('フレンド関係も解除')
    expect(mocks.unfollow).toHaveBeenCalledWith('own-team', 2, { pastForwardHandling: 'KEEP' })
    expect(w.emitted('refresh')).toHaveLength(1)
    expect(w.find('[data-testid="pending-follow-row"]').exists()).toBe(false)
    expect(mocks.toast.mock.calls[0][0].undoLabel).toBe('もう一度フォロー')
    await mocks.toast.mock.calls[0][0].onUndo()
    expect(w.emitted('refollow')![0]).toEqual(['other-team'])
  })

  it('AC17: hidden相手は名前リンクや再フォロー操作を出さず、再フォローを約束しない', async () => {
    const hidden = { ...row, targetTeamName: null, targetTeamSlug: null, nameHidden: true }
    mocks.list.mockResolvedValueOnce({ ...result(), data: [hidden] }).mockResolvedValueOnce(result([]))
    const w = await mountList()
    expect(w.find('[data-testid="pending-follow-team-link"]').exists()).toBe(false)
    expect(w.get('[data-testid="pending-follow-hidden"]').text()).toBe('表示できないチーム')
    expect(w.get('[data-testid="pending-follow-recovery"]').text()).toContain('再フォローできない場合')
    await acceptCancel(w)
    expect(mocks.toast).not.toHaveBeenCalled()
    expect(w.emitted('refollow')).toBeUndefined()
  })

  it('AC18: 取消404 SOCIAL_103でも一覧を再取得し親へ両一覧同期を要求する', async () => {
    mocks.list.mockResolvedValueOnce(result()).mockResolvedValueOnce(result([]))
    mocks.unfollow.mockRejectedValueOnce({ statusCode: 404, data: { error: { code: 'SOCIAL_103' } } })
    const w = await mountList()
    await acceptCancel(w)
    expect(mocks.list).toHaveBeenCalledTimes(2)
    expect(w.emitted('refresh')).toHaveLength(1)
    expect(mocks.error).not.toHaveBeenCalled()
  })
  it('AC16: 補正先の取得失敗でもページを変更せず、元ページから再試行する', async () => {
    mocks.list.mockResolvedValueOnce(result([row], 41)).mockResolvedValueOnce(result([], 21))
      .mockRejectedValueOnce({ statusCode: 500 }).mockResolvedValueOnce(result([], 0))
    const w = await mountList()
    w.findComponent({ name: 'Paginator' }).vm.$emit('page', { page: 8 })
    await flushPromises()
    expect(mocks.list.mock.calls.map(c => c[1].page)).toEqual([0, 8, 1])
    expect(w.find('[data-testid="pending-follow-error"]').exists()).toBe(true)
    await w.findComponent({ name: 'DashboardErrorState' }).vm.$emit('retry')
    await flushPromises()
    expect(mocks.list.mock.calls.at(-1)![1].page).toBe(8)
    w.unmount()
  })

  it('AC16: 補正中に残り全件が消えた場合も、追加取得せずpage0へ戻る', async () => {
    mocks.list.mockResolvedValueOnce(result([row], 41)).mockResolvedValueOnce(result([], 21))
      .mockResolvedValueOnce(result([], 0)).mockResolvedValueOnce(result([], 0))
    const w = await mountList()
    w.findComponent({ name: 'Paginator' }).vm.$emit('page', { page: 8 })
    await flushPromises()
    expect(mocks.list.mock.calls.map(c => c[1].page)).toEqual([0, 8, 1])
    await (w.vm as unknown as { refresh: () => Promise<void> }).refresh()
    expect(mocks.list.mock.calls.at(-1)![1].page).toBe(0)
    w.unmount()
  })

})
