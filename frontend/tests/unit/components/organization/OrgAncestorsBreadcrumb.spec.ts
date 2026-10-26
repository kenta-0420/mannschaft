import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import OrgAncestorsBreadcrumb from '~/components/organization/OrgAncestorsBreadcrumb.vue'
import type { AncestorOrganization } from '~/types/organization'

/**
 * F01.2 OrgAncestorsBreadcrumb.vue のユニットテスト。
 *
 * - 通常の祖先がリンクで表示されること
 * - hidden:true がプレースホルダで表示されリンクでないこと
 * - 空配列のとき何も描画されないこと
 */

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('OrgAncestorsBreadcrumb.vue', () => {
  it('通常の祖先がリンク（NuxtLink）で表示される', async () => {
    const ancestors: AncestorOrganization[] = [
      {
        id: 1,
        name: '全国〇〇連盟',
        nickname1: null,
        iconUrl: null,
        visibility: 'PUBLIC',
        hidden: false,
      },
      {
        id: 3,
        name: '関東支部',
        nickname1: '関東',
        iconUrl: null,
        visibility: 'PRIVATE',
        hidden: false,
      },
    ]

    const wrapper = await mountSuspended(OrgAncestorsBreadcrumb, {
      props: { ancestors, currentOrgName: '東京クラブ' },
    })

    const links = wrapper.findAll('[data-testid="org-ancestor-link"]')
    expect(links).toHaveLength(2)
    // nickname1 優先
    expect(links[0]!.text()).toContain('全国〇〇連盟')
    expect(links[1]!.text()).toContain('関東')
    // 現組織は強調表示（リンクなし）
    const current = wrapper.get('[data-testid="org-ancestor-current"]')
    expect(current.text()).toBe('東京クラブ')
  })

  it('hidden:true の祖先はプレースホルダで表示されリンクではない', async () => {
    const ancestors: AncestorOrganization[] = [
      {
        id: 7,
        hidden: true,
      },
      {
        id: 3,
        name: '関東支部',
        nickname1: '関東',
        iconUrl: null,
        visibility: 'PRIVATE',
        hidden: false,
      },
    ]

    const wrapper = await mountSuspended(OrgAncestorsBreadcrumb, {
      props: { ancestors, currentOrgName: '東京クラブ' },
    })

    const hiddenEls = wrapper.findAll('[data-testid="org-ancestor-hidden"]')
    expect(hiddenEls).toHaveLength(1)
    // 非公開組織には id ベースのリンクが含まれないこと
    expect(hiddenEls[0]!.find('a').exists()).toBe(false)

    // 通常祖先は1件
    const links = wrapper.findAll('[data-testid="org-ancestor-link"]')
    expect(links).toHaveLength(1)
  })

  it('ancestors が空のとき nav 要素ごと描画されない', async () => {
    const wrapper = await mountSuspended(OrgAncestorsBreadcrumb, {
      props: { ancestors: [], currentOrgName: '東京クラブ' },
    })

    const nav = wrapper.find('[data-testid="org-ancestors-breadcrumb"]')
    expect(nav.exists()).toBe(false)
  })
})
