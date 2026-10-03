import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { ref } from 'vue'
import SafetyTeamPage from '~/pages/teams/[slug]/safety.vue'

/**
 * CMP-260922-2045 第2陣 G3 の根治テスト。
 *
 * `pages/teams/[slug]/safety.vue` は安否確認履歴の取得が失敗しても `checks` を
 * 空配列にリセットするだけで、空状態（「安否確認の履歴はありません」）へそのまま
 * 落ちていた。エラー専用状態（DashboardErrorState / safety-team-list-error-state）
 * で修正した。
 *
 * 検証観点:
 *   ST-001 取得失敗時に safety-team-list-error-state が描画される
 *   ST-002（対照）取得成功・0件時はエラー状態を出さない
 *   ST-003 再試行は初回と同じ取得処理（listSafetyChecks）を呼ぶ
 */

const listSafetyChecks = vi.fn()

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-1' } }))
mockNuxtImport('useSafetyCheckApi', () => () => ({ listSafetyChecks }))
mockNuxtImport('useNotification', () => () => ({ success: vi.fn(), error: vi.fn(), warn: vi.fn(), info: vi.fn() }))
mockNuxtImport('useRoleAccess', () => () => ({ isAdminOrDeputy: ref(false), loadPermissions: vi.fn(async () => {}) }))
mockNuxtImport('useDatetime', () => () => ({ formatDateTime: (d: string) => d }))

beforeAll(async () => {
  listSafetyChecks.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(SafetyTeamPage)
  warmup.unmount()
})

describe('pages/teams/[slug]/safety.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listSafetyChecks.mockReset()
  })

  it('ST-001: 取得失敗時に safety-team-list-error-state が描画される', async () => {
    listSafetyChecks.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(SafetyTeamPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="safety-team-list-error-state"]').exists()).toBe(true)
  })

  it('ST-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listSafetyChecks.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(SafetyTeamPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="safety-team-list-error-state"]').exists()).toBe(false)
  })

  it('ST-003: 再試行は初回と同じ取得処理を呼ぶ', async () => {
    listSafetyChecks.mockRejectedValueOnce(new Error('network error'))
    const wrapper = await mountSuspended(SafetyTeamPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="safety-team-list-error-state"]').exists()).toBe(true)

    listSafetyChecks.mockResolvedValueOnce({ data: [] })
    const callsBefore = listSafetyChecks.mock.calls.length
    await wrapper.find('[data-testid="safety-team-list-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listSafetyChecks.mock.calls.length).toBe(callsBefore + 1)
    expect(wrapper.find('[data-testid="safety-team-list-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
