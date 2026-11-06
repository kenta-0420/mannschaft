import { flushPromises } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import IncomingList from './TeamFriendIncomingFollowList.vue'
const mocks = vi.hoisted(() => ({ list: vi.fn() }))
mockNuxtImport('useFriendTeamsApi', () => () => ({ listIncomingFollows: mocks.list }))
mockNuxtImport('useAuthStore', () => () => ({ user: { timezone: 'Asia/Tokyo' }, loadFromStorage: vi.fn(), isAuthenticated: false }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))
const row = { followId: 1, sourceTeamId: 2, sourceTeamName: 'Sender', sourceTeamSlug: 'sender-team', followedBack: false, nameHidden: false, followedAt: '2026-10-25T01:00:00Z' }
const result = (data: unknown[]) => ({ data, pagination: { totalElements: data.length, totalPages: data.length ? 1 : 0 } })
async function mountList() { const w = await mountSuspended(IncomingList, { props: { teamId: '3', sourceTeam: { teamId: 3, teamSlug: 'own-team', teamName: 'Recipient' } } }); await flushPromises(); return w }
describe('FR-09/10 受信申請', () => {
  // 日時表記は UI ロケール依存（CMP-261104-0335）。テスト環境の既定は en のため、ja 表記を検証するここでは ja に明示する。
  const i18n = () => useNuxtApp().$i18n as unknown as { locale: { value: string } }
  let originalLocale = 'ja'
  beforeEach(() => { mocks.list.mockReset(); originalLocale = i18n().locale.value; i18n().locale.value = 'ja' })
  afterEach(() => { i18n().locale.value = originalLocale })
  it('元→受信先を明示し、返しは申請元を確認ダイアログに渡す', async () => {
    mocks.list.mockResolvedValue(result([row]))
    const w = await mountList()
    expect(w.text()).toContain('Sender → Recipient')
    expect(w.get('time').attributes('datetime')).toBe(row.followedAt)
    await w.get('[data-testid="incoming-follow-respond"]').trigger('click')
    expect(w.emitted('respond')).toEqual([[{ teamSlug: 'sender-team', teamName: 'Sender' }]])
    w.unmount()
  })
  it('UTCの受信Instantはdatetimeに保持し本文は本人timezoneのTokyo10時に表示する', async () => {
    mocks.list.mockResolvedValue(result([row]))
    const w = await mountList()
    try {
      expect(w.get('time').attributes('datetime')).toBe('2026-10-25T01:00:00Z')
      expect(w.get('time').text()).toBe('2026/10/25 10:00')
    } finally { w.unmount() }
  })
  it('伏せ字行は維持しプロフィールリンクと返し操作を出さない', async () => {
    mocks.list.mockResolvedValue(result([{ ...row, nameHidden: true, sourceTeamName: null, sourceTeamSlug: null }]))
    const w = await mountList()
    expect(w.findAll('[data-testid="incoming-follow-row"]')).toHaveLength(1)
    expect(w.find('[data-testid="incoming-follow-hidden"]').exists()).toBe(true)
    expect(w.find('[data-testid="incoming-follow-link"]').exists()).toBe(false)
    expect(w.find('[data-testid="incoming-follow-respond"]').exists()).toBe(false)
    w.unmount()
  })
  it('失敗を0件と混同せず再試行で復旧する', async () => {
    mocks.list.mockRejectedValueOnce({ statusCode: 403 }).mockResolvedValueOnce(result([]))
    const w = await mountList()
    expect(w.find('[data-testid="incoming-follow-empty"]').exists()).toBe(false)
    w.findComponent({ name: 'DashboardErrorState' }).vm.$emit('retry')
    await flushPromises()
    expect(w.find('[data-testid="incoming-follow-empty"]').exists()).toBe(true)
    w.unmount()
  })
})
