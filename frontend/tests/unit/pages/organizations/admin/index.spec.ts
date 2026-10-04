import { describe, it, expect, beforeAll, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import OrgAdminConsolePage from '~/pages/organizations/[slug]/admin/index.vue'

/**
 * ALIC-3 の根治テスト（アリシゼーション・殿が実機で確定）。
 *
 * `pages/organizations/[slug]/admin/index.vue` は `<component :is="card.to ? 'NuxtLink' : 'div'">`
 * と**文字列**で NuxtLink を渡していたため、Vue がコンポーネントを解決できず
 * `<nuxtlink>` という未知タグとして描画され href が無かった（実機で org-000004 の
 * ADMIN で確認・6カード全て遷移しない）。`pages/villages/[id]/admin/index.vue` /
 * `components/ActivityItem.vue` と同じ作法（`#components` から NuxtLink を明示 import
 * してコンポーネント実体を渡す）で修正した。
 *
 * 検証観点:
 *   OAC-001 遷移先ありのカードが実リンク（a[href]）として描画される
 *   OAC-002 「設定」カードの href が想定の遷移先を指す
 */

mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-000004' } }))
mockNuxtImport('useI18n', () => () => ({ t: (key: string) => key }))

const loadPermissions = vi.fn(async () => {})
const access = { isAdmin: true }
mockNuxtImport('useRoleAccess', () => () => ({
  isAdminOrDeputy: { value: true },
  isAdmin: { get value() { return access.isAdmin } },
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
  const warmup = await mountSuspended(OrgAdminConsolePage)
  warmup.unmount()
})

describe('pages/organizations/[slug]/admin/index.vue — カードが実リンクとして描画される', () => {
  it('OAC-001: 遷移先ありのカードが a[href] として描画される（NuxtLink 文字列渡しの罠の再発防止）', async () => {
    const wrapper = await mountSuspended(OrgAdminConsolePage)
    await flushMicrotasks()

    // to を持つカードは budget/payments/paymentRequests/members/settings/teamAffiliation/pointCards の7件（approvals は to: null）。
    const anchors = wrapper.findAll('a[href]')
    expect(anchors.length).toBe(7)
  })

  it('OAC-002: 「設定」カードの href が /organizations/org-000004/settings/faq-settings を指す', async () => {
    const wrapper = await mountSuspended(OrgAdminConsolePage)
    await flushMicrotasks()

    const anchors = wrapper.findAll('a[href]')
    const hrefs = anchors.map(a => a.attributes('href'))
    expect(hrefs).toContain('/organizations/org-000004/settings/faq-settings')
  })
})

describe('pages/organizations/[slug]/admin/index.vue — チーム加盟の設定カード', () => {
  it('OAC-003: ADMIN には「チーム加盟の設定」カードが出て、設定画面を指す', async () => {
    access.isAdmin = true
    const wrapper = await mountSuspended(OrgAdminConsolePage)
    await flushMicrotasks()
    const hrefs = wrapper.findAll('a[href]').map(a => a.attributes('href'))
    expect(hrefs).toContain('/organizations/org-000004/settings/team-affiliation')
  })

  it('OAC-004: DEPUTY_ADMIN にはそのカードを出さない', async () => {
    access.isAdmin = false
    const wrapper = await mountSuspended(OrgAdminConsolePage)
    await flushMicrotasks()
    const hrefs = wrapper.findAll('a[href]').map(a => a.attributes('href'))
    expect(hrefs).not.toContain('/organizations/org-000004/settings/team-affiliation')
    access.isAdmin = true
  })
})

async function flushMicrotasks() {
  await new Promise(resolve => setTimeout(resolve, 0))
}
