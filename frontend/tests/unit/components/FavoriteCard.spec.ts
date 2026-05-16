import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import FavoriteCard from '~/components/favorites/FavoriteCard.vue'
import type { UserFavoriteItem } from '~/types/favorite'

/**
 * F02.9 FavoriteCard.vue のユニットテスト。
 *
 * <p>1 件のお気に入りカードの表示分岐と emit を検証する。
 * UNAVAILABLE 時のアクション抑止、canEdit による編集ボタン制御、
 * ARIA ラベル付与、各イベント emit を主観点とする。</p>
 *
 * <p>i18n はテスト環境では英語が選択されるため、ラベル文字列は en/common.json
 * の値（Open/Edit/Remove 等）を期待値として用いる。</p>
 *
 * テストケース一覧:
 *  FAV-CARD-001: AVAILABLE + canEdit=true で開く・編集・削除ボタンすべて表示
 *  FAV-CARD-002: canEdit=false のとき編集ボタンが非表示
 *  FAV-CARD-003: UNAVAILABLE のとき開く・編集が隠れ、削除のみ表示・メッセージ表示
 *  FAV-CARD-004: 開くボタンクリックで open イベントが emit される
 *  FAV-CARD-005: 編集ボタンクリックで edit イベントが emit される
 *  FAV-CARD-006: 削除ボタンクリックで remove イベントが emit される
 *  FAV-CARD-007: ARIA ラベルに entity.name が含まれる
 */

function createItem(overrides: Partial<UserFavoriteItem> = {}): UserFavoriteItem {
  return {
    favoriteId: 'fav-001',
    entityType: 'TEAM',
    entityId: '123',
    displayOrder: 0,
    createdAt: '2026-05-15T00:00:00Z',
    entity: {
      name: 'Test Team',
      description: null,
      iconUrl: null,
      pageUrl: '/teams/123',
      status: 'AVAILABLE',
      canEdit: true,
      editableFields: [],
    },
    ...overrides,
  }
}

describe('FavoriteCard.vue', () => {
  it('FAV-CARD-001: AVAILABLE + canEdit=true で開く・編集・削除ボタンすべて表示', async () => {
    const item = createItem()
    const wrapper = await mountSuspended(FavoriteCard, { props: { item } })

    const openBtn = wrapper.find(`[aria-label="Open ${item.entity.name}"]`)
    const editBtn = wrapper.find(`[aria-label="Edit ${item.entity.name}"]`)
    const removeBtn = wrapper.find(`[aria-label="Remove ${item.entity.name}"]`)

    expect(openBtn.exists()).toBe(true)
    expect(editBtn.exists()).toBe(true)
    expect(removeBtn.exists()).toBe(true)
  })

  it('FAV-CARD-002: canEdit=false のとき編集ボタンが非表示', async () => {
    const item = createItem({
      entity: {
        ...createItem().entity,
        canEdit: false,
      },
    })
    const wrapper = await mountSuspended(FavoriteCard, { props: { item } })

    const editBtn = wrapper.find(`[aria-label="Edit ${item.entity.name}"]`)
    expect(editBtn.exists()).toBe(false)
  })

  it('FAV-CARD-003: UNAVAILABLE のとき開く・編集が隠れ、削除のみ表示・メッセージ表示', async () => {
    const item = createItem({
      entity: {
        ...createItem().entity,
        status: 'UNAVAILABLE',
      },
    })
    const wrapper = await mountSuspended(FavoriteCard, { props: { item } })

    const openBtn = wrapper.find(`[aria-label="Open ${item.entity.name}"]`)
    const editBtn = wrapper.find(`[aria-label="Edit ${item.entity.name}"]`)
    const removeBtn = wrapper.find(`[aria-label="Remove ${item.entity.name}"]`)

    expect(openBtn.exists()).toBe(false)
    expect(editBtn.exists()).toBe(false)
    expect(removeBtn.exists()).toBe(true)
    // unavailable メッセージ（en）
    expect(wrapper.text()).toContain('no longer available')
  })

  it('FAV-CARD-004: 開くボタンクリックで open イベントが emit される', async () => {
    const item = createItem()
    const wrapper = await mountSuspended(FavoriteCard, { props: { item } })

    const openBtn = wrapper.find(`[aria-label="Open ${item.entity.name}"]`)
    await openBtn.trigger('click')

    expect(wrapper.emitted('open')).toBeTruthy()
    expect(wrapper.emitted('open')).toHaveLength(1)
  })

  it('FAV-CARD-005: 編集ボタンクリックで edit イベントが emit される', async () => {
    const item = createItem()
    const wrapper = await mountSuspended(FavoriteCard, { props: { item } })

    const editBtn = wrapper.find(`[aria-label="Edit ${item.entity.name}"]`)
    await editBtn.trigger('click')

    expect(wrapper.emitted('edit')).toBeTruthy()
    expect(wrapper.emitted('edit')).toHaveLength(1)
  })

  it('FAV-CARD-006: 削除ボタンクリックで remove イベントが emit される', async () => {
    const item = createItem()
    const wrapper = await mountSuspended(FavoriteCard, { props: { item } })

    const removeBtn = wrapper.find(`[aria-label="Remove ${item.entity.name}"]`)
    await removeBtn.trigger('click')

    expect(wrapper.emitted('remove')).toBeTruthy()
    expect(wrapper.emitted('remove')).toHaveLength(1)
  })

  it('FAV-CARD-007: ARIA ラベルに entity.name が含まれる', async () => {
    const item = createItem({
      entity: {
        ...createItem().entity,
        name: 'マイチーム',
      },
    })
    const wrapper = await mountSuspended(FavoriteCard, { props: { item } })

    const allButtons = wrapper.findAll('button[aria-label]')
    const actionLabels = allButtons
      .map((b) => b.attributes('aria-label') ?? '')
      .filter((l) => /Open|Edit|Remove/.test(l))

    expect(actionLabels.length).toBeGreaterThan(0)
    // 全アクションラベルに entity.name が含まれる
    actionLabels.forEach((label) => {
      expect(label).toContain('マイチーム')
    })
  })
})
