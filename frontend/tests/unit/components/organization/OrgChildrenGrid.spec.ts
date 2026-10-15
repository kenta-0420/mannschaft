import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import OrgChildrenGrid from '~/components/organization/OrgChildrenGrid.vue'
import type { ChildOrganization } from '~/types/organization'

/**
 * F01.2 OrgChildrenGrid.vue のユニットテスト。
 *
 * - 子組織カードが描画されること
 * - archived:true がバッジ表示されること
 * - 空時メッセージが i18n キーで表示されること
 * - hasNext:true のとき「もっと読み込む」ボタンが出ること
 */

beforeEach(() => {
  setActivePinia(createPinia())
})

describe('OrgChildrenGrid.vue', () => {
  it('子組織カードが描画される', async () => {
    const children: ChildOrganization[] = [
      {
        id: 12,
        name: 'FCマンシャフト ジュニアユース',
        nickname1: 'ジュニアユース',
        iconUrl: null,
        visibility: 'PUBLIC',
        memberCount: 32,
        archived: false,
      },
      {
        id: 13,
        name: 'FCマンシャフト ユース',
        nickname1: null,
        iconUrl: null,
        visibility: 'PUBLIC',
        memberCount: 28,
        archived: false,
      },
    ]

    const wrapper = await mountSuspended(OrgChildrenGrid, {
      props: { children, loading: false, hasNext: false },
    })

    const cards = wrapper.findAll('[data-testid="org-child-card"]')
    expect(cards).toHaveLength(2)
    expect(cards[0]!.text()).toContain('ジュニアユース')
    expect(cards[1]!.text()).toContain('FCマンシャフト ユース')
  })

  it('archived:true の子組織はバッジ表示される', async () => {
    const children: ChildOrganization[] = [
      {
        id: 99,
        name: '旧チーム',
        nickname1: null,
        iconUrl: null,
        visibility: 'PUBLIC',
        memberCount: 0,
        archived: true,
      },
    ]

    const wrapper = await mountSuspended(OrgChildrenGrid, {
      props: { children, loading: false, hasNext: false },
    })

    const archivedBadge = wrapper.find('[data-testid="org-child-archived"]')
    expect(archivedBadge.exists()).toBe(true)
    // ロケールに応じて値が変わるため、空文字でないことのみ検証
    expect(archivedBadge.text().length).toBeGreaterThan(0)
  })

  it('children が空のとき空状態メッセージが表示される', async () => {
    const wrapper = await mountSuspended(OrgChildrenGrid, {
      props: { children: [], loading: false, hasNext: false },
    })

    const empty = wrapper.find('[data-testid="org-children-empty"]')
    expect(empty.exists()).toBe(true)
    // i18n キー organization.no_children の値が描画される（ロケール非依存に存在のみ確認）
    expect(empty.text().length).toBeGreaterThan(0)

    // グリッドは描画されない
    expect(wrapper.find('[data-testid="org-children-grid"]').exists()).toBe(false)
  })

  it('hasNext:true のとき「もっと読み込む」ボタンが表示される', async () => {
    const children: ChildOrganization[] = [
      {
        id: 12,
        name: 'FC A',
        nickname1: null,
        iconUrl: null,
        visibility: 'PUBLIC',
        memberCount: 10,
        archived: false,
      },
    ]

    const wrapper = await mountSuspended(OrgChildrenGrid, {
      props: { children, loading: false, hasNext: true },
    })

    const loadMore = wrapper.find('[data-testid="org-children-load-more"]')
    expect(loadMore.exists()).toBe(true)
  })

  it('hasNext:false のとき「もっと読み込む」ボタンは非表示', async () => {
    const children: ChildOrganization[] = [
      {
        id: 12,
        name: 'FC A',
        nickname1: null,
        iconUrl: null,
        visibility: 'PUBLIC',
        memberCount: 10,
        archived: false,
      },
    ]

    const wrapper = await mountSuspended(OrgChildrenGrid, {
      props: { children, loading: false, hasNext: false },
    })

    expect(wrapper.find('[data-testid="org-children-load-more"]').exists()).toBe(false)
  })
})
