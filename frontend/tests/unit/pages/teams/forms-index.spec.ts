import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import TeamFormsIndexPage from '~/pages/teams/[slug]/forms/index.vue'

/**
 * CMP-260922-2045 第2陣 G5 の根治テスト（組織版と対になるチーム版）。
 *
 * `pages/teams/[slug]/forms/index.vue` は公開中テンプレート取得が失敗しても
 * `templates` を空配列にリセットするだけで、「公開中のフォームはありません」という
 * 空状態（DashboardEmptyState）へそのまま落ちていた。エラー専用状態
 * （DashboardErrorState / forms-list-error-state）で修正した。
 *
 * 検証観点:
 *   TFI-001 取得失敗時に forms-list-error-state が描画される
 *   TFI-002（対照）取得成功・0件時は forms-list-error-state を出さない
 *   TFI-003 再試行が初回表示と同じ取得関数（loadPublishedTemplates）を呼ぶ
 */

const listTemplates = vi.fn()
const listMySubmissions = vi.fn(async () => ({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } }))
const listTemplateSubmissions = vi.fn(async () => ({ data: [], meta: { total: 0, page: 0, size: 20, totalPages: 0 } }))

vi.mock('~/composables/useFormApi', () => ({
  useFormApi: () => ({ listTemplates, listMySubmissions, listTemplateSubmissions }),
}))

vi.mock('~/composables/useRoleAccess', () => ({
  useRoleAccess: () => ({
    loadPermissions: vi.fn(async () => ({ ok: true })),
    permissions: { value: [] },
    roleName: { value: 'ADMIN' },
    loading: { value: false },
  }),
}))

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-1' } }))

beforeAll(async () => {
  listTemplates.mockResolvedValue({ data: [] })
  const warmup = await mountSuspended(TeamFormsIndexPage)
  warmup.unmount()
})

describe('pages/teams/[slug]/forms/index.vue — 取得失敗時のエラー状態', () => {
  beforeEach(() => {
    listTemplates.mockReset()
  })

  it('TFI-001: 取得失敗時に forms-list-error-state が描画される', async () => {
    listTemplates.mockRejectedValue(new Error('network error'))
    const wrapper = await mountSuspended(TeamFormsIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="forms-list-error-state"]').exists()).toBe(true)
  })

  it('TFI-002（対照）: 取得成功・0件時はエラー状態を出さない', async () => {
    listTemplates.mockResolvedValue({ data: [] })
    const wrapper = await mountSuspended(TeamFormsIndexPage)
    await flushMicrotasks()

    expect(wrapper.find('[data-testid="forms-list-error-state"]').exists()).toBe(false)
  })

  it('TFI-003: 再試行が初回表示と同じ取得処理（listTemplates）を呼ぶ', async () => {
    listTemplates.mockRejectedValueOnce(new Error('network error'))
    listTemplates.mockResolvedValueOnce({ data: [] })
    const wrapper = await mountSuspended(TeamFormsIndexPage)
    await flushMicrotasks()
    expect(wrapper.find('[data-testid="forms-list-error-state"]').exists()).toBe(true)

    await wrapper.find('[data-testid="forms-list-error-state-retry"]').trigger('click')
    await flushMicrotasks()

    expect(listTemplates).toHaveBeenCalledTimes(2)
    expect(wrapper.find('[data-testid="forms-list-error-state"]').exists()).toBe(false)
  })
})

async function flushMicrotasks() {
  for (let i = 0; i < 5; i++) {
    await Promise.resolve()
  }
}
