import { describe, it, expect, beforeAll, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import TeamAdminConsolePage from '~/pages/teams/[slug]/admin/index.vue'

/**
 * ALIC-3 の根治テスト（アリシゼーション・殿が実機で確定。組織版と対になるチーム版）。
 *
 * `pages/teams/[slug]/admin/index.vue` は `<component :is="card.to ? 'NuxtLink' : 'div'">`
 * と**文字列**で NuxtLink を渡していたため、Vue がコンポーネントを解決できず
 * `<nuxtlink>` という未知タグとして描画され href が無かった。`pages/villages/[id]/admin/index.vue` /
 * `components/ActivityItem.vue` と同じ作法（`#components` から NuxtLink を明示 import
 * してコンポーネント実体を渡す）で修正した。
 *
 * 検証観点:
 *   TAC-001 遷移先ありのカードが実リンク（a[href]）として描画される
 *   TAC-002 「設定」カードの href が想定の遷移先を指す
 */

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'team-000001' } }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const loadPermissions = vi.fn(async () => {})
mockNuxtImport('useRoleAccess', () => () => ({
  isAdminOrDeputy: { value: true },
  loadPermissions,
}))

const getAdminActionRequired = vi.fn(async () => ({
  totalPending: 0,
  domains: [],
}))
mockNuxtImport('useScopeTabApi', () => () => ({
  getAdminActionRequired,
}))

beforeAll(async () => {
  const warmup = await mountSuspended(TeamAdminConsolePage)
  warmup.unmount()
})

describe('pages/teams/[slug]/admin/index.vue — カードが実リンクとして描画される', () => {
  it('TAC-001: 遷移先ありのカードが a[href] として描画される（NuxtLink 文字列渡しの罠の再発防止）', async () => {
    const wrapper = await mountSuspended(TeamAdminConsolePage)
    await flushMicrotasks()

    // to を持つカードは reservations/budget/paymentRequests/members/settings の5件（approvals は to: null）。
    const anchors = wrapper.findAll('a[href]')
    expect(anchors.length).toBe(5)
  })

  it('TAC-002: 「設定」カードの href が /teams/team-000001/settings/shift を指す', async () => {
    const wrapper = await mountSuspended(TeamAdminConsolePage)
    await flushMicrotasks()

    const anchors = wrapper.findAll('a[href]')
    const hrefs = anchors.map(a => a.attributes('href'))
    expect(hrefs).toContain('/teams/team-000001/settings/shift')
  })
})

async function flushMicrotasks() {
  await new Promise(resolve => setTimeout(resolve, 0))
}
