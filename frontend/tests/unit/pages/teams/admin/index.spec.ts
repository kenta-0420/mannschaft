import { describe, it, expect, beforeAll, beforeEach, vi } from 'vitest'
import { ref } from 'vue'
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
const access = {
  roleName: ref<string | null>('ADMIN'),
  isAdmin: ref(true),
  isAdminOrDeputy: ref(true),
}
mockNuxtImport('useRoleAccess', () => () => ({
  ...access,
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

beforeEach(() => {
  access.roleName.value = 'ADMIN'
  access.isAdmin.value = true
  access.isAdminOrDeputy.value = true
})

describe('pages/teams/[slug]/admin/index.vue — カードが実リンクとして描画される', () => {
  it('TAC-001: 遷移先ありのカードが a[href] として描画される（NuxtLink 文字列渡しの罠の再発防止）', async () => {
    const wrapper = await mountSuspended(TeamAdminConsolePage)
    await flushMicrotasks()

    // to を持つカードは reservations/budget/paymentRequests/members/settings の5件（approvals は to: null）。
    const anchors = wrapper.findAll('a[href]')
    expect(anchors.length).toBe(5)
  })

  it('AC1: ADMINの設定カードから同じチームの設定ハブへ進める', async () => {
    const wrapper = await mountSuspended(TeamAdminConsolePage)
    await flushMicrotasks()

    const anchors = wrapper.findAll('a[href]')
    const hrefs = anchors.map(a => a.attributes('href'))
    expect(hrefs).toContain('/teams/team-000001/admin/settings')
  })

  it('AC3: DEPUTYの既存シフト設定への入口を維持する', async () => {
    access.roleName.value = 'DEPUTY_ADMIN'
    access.isAdmin.value = false
    const wrapper = await mountSuspended(TeamAdminConsolePage)
    await flushMicrotasks()

    const hrefs = wrapper.findAll('a[href]').map(a => a.attributes('href'))
    expect(hrefs).toContain('/teams/team-000001/settings/shift')
    expect(hrefs).not.toContain('/teams/team-000001/admin/settings')
  })

  it('AC4: 権限が未確定の場合に設定ハブのリンクを公開しない', async () => {
    access.roleName.value = null
    access.isAdmin.value = false
    access.isAdminOrDeputy.value = false
    const wrapper = await mountSuspended(TeamAdminConsolePage)
    await flushMicrotasks()

    expect(wrapper.findAll('a[href]')).toHaveLength(0)
  })

  it('AC3: SYSTEM_ADMINは既存の入口を維持し、新しいスコープ管理者ハブへ案内しない', async () => {
    access.roleName.value = 'SYSTEM_ADMIN'
    const wrapper = await mountSuspended(TeamAdminConsolePage)
    await flushMicrotasks()

    const hrefs = wrapper.findAll('a[href]').map(a => a.attributes('href'))
    expect(hrefs).toContain('/teams/team-000001/settings/shift')
    expect(hrefs).not.toContain('/teams/team-000001/admin/settings')
  })
})

async function flushMicrotasks() {
  await new Promise(resolve => setTimeout(resolve, 0))
}
