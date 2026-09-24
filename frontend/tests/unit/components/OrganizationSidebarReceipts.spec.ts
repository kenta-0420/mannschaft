import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { ref, computed } from 'vue'

/**
 * CMP-260907-0851 組織サイドバーの領収書導線のユニットテスト。
 *
 * <p>領収書の 2 画面（`/admin/receipts` / `/admin/receipt-settings`）は
 * スコープ配下ではなく横断ルートに置かれており、これまでアプリ内のどこからも
 * 辿り着けなかった。組織サイドバーからの導線が「決済(payment)モジュール有効」
 * かつ「DEPUTY_ADMIN 以上」のときにだけ出ることを固定する。</p>
 *
 * テストケース一覧:
 *  ORG-SB-RCP-001: payment 有効 + ADMIN なら領収書・発行者設定リンクが絶対パスで出る
 *  ORG-SB-RCP-002: payment 無効なら領収書リンクは出ない（死んだ導線を作らない）
 *  ORG-SB-RCP-003: 一般メンバー（MEMBER）には領収書リンクは出ない
 */

const roleName = ref<string | null>('ADMIN')
vi.mock('~/composables/useRoleAccess', () => ({
  useRoleAccess: () => ({
    roleName,
    isAdmin: computed(() => roleName.value === 'ADMIN'),
    isAdminOrDeputy: computed(
      () => roleName.value === 'ADMIN' || roleName.value === 'DEPUTY_ADMIN',
    ),
    loadPermissions: vi.fn().mockResolvedValue(undefined),
  }),
}))

const enabledModules = ref<{ moduleSlug: string; isEnabled: boolean }[]>([])
vi.mock('~/composables/useOrganizationModuleApi', () => ({
  useOrganizationModuleApi: () => ({
    getOrganizationModules: () => Promise.resolve(enabledModules.value),
  }),
}))
vi.mock('~/composables/useModuleApi', () => ({
  useModuleApi: () => ({
    getTeamModules: () => Promise.resolve({ data: enabledModules.value }),
  }),
}))

const OrganizationSidebar = (await import('~/components/OrganizationSidebar.vue')).default

async function mountSidebar() {
  const wrapper = await mountSuspended(OrganizationSidebar, { props: { orgId: 'my-org' } })
  // onMounted 内のモジュール取得完了を待つ。
  await new Promise(resolve => setTimeout(resolve, 0))
  await wrapper.vm.$nextTick()
  return wrapper
}

function receiptLinks(html: string): string[] {
  return ['/admin/receipts', '/admin/receipt-settings'].filter(p => html.includes(`href="${p}"`))
}

describe('OrganizationSidebar 領収書導線', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    roleName.value = 'ADMIN'
    enabledModules.value = [{ moduleSlug: 'payment', isEnabled: true }]
  })

  it('ORG-SB-RCP-001: payment 有効 + ADMIN なら領収書2画面へのリンクが出る', async () => {
    const wrapper = await mountSidebar()
    expect(receiptLinks(wrapper.html()).sort()).toEqual([
      '/admin/receipt-settings',
      '/admin/receipts',
    ])
  })

  it('ORG-SB-RCP-002: payment 無効なら領収書リンクは出ない', async () => {
    enabledModules.value = [{ moduleSlug: 'payment', isEnabled: false }]
    const wrapper = await mountSidebar()
    expect(receiptLinks(wrapper.html())).toEqual([])
  })

  it('ORG-SB-RCP-003: 一般メンバーには領収書リンクは出ない', async () => {
    roleName.value = 'MEMBER'
    const wrapper = await mountSidebar()
    expect(receiptLinks(wrapper.html())).toEqual([])
  })
})
