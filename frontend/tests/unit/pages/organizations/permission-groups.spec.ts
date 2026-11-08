import { describe, expect, it, vi } from 'vitest'
import { mockNuxtImport, mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { computed, ref } from 'vue'
import OrgPermissionGroupsPage from '~/pages/organizations/[slug]/permission-groups.vue'
import { expectedPermissionLabel } from '../../helpers/permissionLabelExpectations'

/**
 * CMP-261011-0035 ALIC-2 — /organizations/{slug}/permission-groups の権限名表示。
 * 権限グループは永続シェル（useOrgShellContext）の permissionGroups から渡り、一覧は本物を描画する。
 *
 *   AC-ALIC2-02 組織ページでも権限コードではなく i18n の訳を出す（ja）。コード文字列は DOM に出さない
 *   AC-ALIC2-09 件数は不変（回帰）。組織側は対象ロール・編集ボタンを出さない（回帰）
 */
const CODES = ['BUDGET_VIEW', 'MANAGE_VOTES', 'PROXY_INPUT_EXECUTE']

vi.mock('~/composables/useOrgShellContext', () => ({
  useOrgShellContext: () => ({
    isAdmin: computed(() => true),
    roleName: computed(() => 'ADMIN'),
    permissionGroups: ref([
      { id: 11, name: '会計の係', description: '予算を見る', permissions: CODES, createdAt: '2026-01-01T00:00:00Z' },
    ]),
  }),
}))
mockNuxtImport('useRoute', () => () => ({ params: { slug: 'org-1' }, query: {}, path: '/organizations/org-1/permission-groups' }))

/** テスト環境の既定ロケールに依存しないよう、明示的に切り替えて再描画を待つ。 */
async function switchLocale(w: { vm: unknown }, locale: 'ja' | 'en'): Promise<void> {
  const i18n = (w.vm as { $i18n: { locale: string, setLocale?: (l: string) => Promise<void> } }).$i18n
  if (i18n.setLocale) await i18n.setLocale(locale)
  else i18n.locale = locale
  for (let i = 0; i < 10; i++) {
    await flushPromises()
    await new Promise(r => setTimeout(r, 25))
  }
}

async function mountPage() {
  const w = await mountSuspended(OrgPermissionGroupsPage)
  await flushPromises()
  await switchLocale(w, 'ja')
  return w
}

describe('pages/organizations/[slug]/permission-groups.vue — 権限名の表示（ALIC-2）', () => {
  it('AC-ALIC2-02: 組織の権限を ja の訳で表示し、権限コードの文字列は DOM に出さない', async () => {
    const w = await mountPage()
    const text = w.text()
    expect(text).toContain('会計の係')
    for (const code of CODES) {
      const label = expectedPermissionLabel('ja', code)
      expect(label, `ja の teamAffiliations.permission_names に ${code} の訳が無い`).toBeTypeOf('string')
      expect(text).toContain(label)
      expect(text).not.toContain(code)
    }
  })

  it('AC-ALIC2-09（回帰）: 件数は不変、組織側は対象ロール・編集ボタンを出さない', async () => {
    const w = await mountPage()
    expect(w.find('[data-testid="group-count-11"]').text()).toContain('3')
    expect(w.find('[data-testid="group-target-role-11"]').exists()).toBe(false)
    expect(w.find('[data-testid="group-edit-11"]').exists()).toBe(false)
  })
})
