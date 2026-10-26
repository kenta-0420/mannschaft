import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import type { UserFavoriteItem, FavoriteEntityType } from '~/types/favorite'

/**
 * F02.9 FavoriteQuickEditDialog.vue のユニットテスト。
 *
 * <p>クイック編集ダイアログが entityType に応じてフォーム・保存先 API を
 * 切り替えること、KB_PAGE のリダイレクト、VILLAGE の保存無効化、
 * キャンセル動作を主観点とする。</p>
 *
 * <p>navigateTo / 各更新 API をモック化する。</p>
 *
 * テストケース一覧:
 *  FAV-DLG-001: modelValue=null のときダイアログ非表示
 *  FAV-DLG-002: TEAM modelValue で TEAM 用フォーム（name/description/iconUrl）が表示される
 *  FAV-DLG-003: ORGANIZATION modelValue で ORG 用フォーム（name/description）が表示される
 *  FAV-DLG-004: BLOG_AUTHOR modelValue で表示名・自己紹介フォームが表示される
 *  FAV-DLG-005: KB_PAGE modelValue で navigateTo が呼ばれて即時クローズ
 *  FAV-DLG-006: VILLAGE modelValue で保存ボタンが disabled
 *  FAV-DLG-007: キャンセルボタンクリックで update:modelValue が null で emit される
 *  FAV-DLG-008: TEAM の name が空のとき保存ボタンが disabled
 */

// === モック ===
const navigateToMock = vi.fn()
vi.mock('#app', async (importOriginal) => {
  const actual = await importOriginal<typeof import('#app')>()
  return {
    ...actual,
    navigateTo: (...args: unknown[]) => navigateToMock(...args),
  }
})

const updateTeamMock = vi.fn()
const updateOrganizationMock = vi.fn()
const updateMyProfileMock = vi.fn()

vi.mock('~/composables/useTeamApi', () => ({
  useTeamApi: () => ({ updateTeam: updateTeamMock }),
}))
vi.mock('~/composables/useOrganizationApi', () => ({
  useOrganizationApi: () => ({ updateOrganization: updateOrganizationMock }),
}))
vi.mock('~/composables/useSocialProfileApi', () => ({
  useSocialProfileApi: () => ({ updateMyProfile: updateMyProfileMock }),
}))

const notificationMock = {
  success: vi.fn(),
  error: vi.fn(),
  info: vi.fn(),
  warn: vi.fn(),
}
vi.mock('~/composables/useNotification', () => ({
  useNotification: () => notificationMock,
}))

const FavoriteQuickEditDialog = (
  await import('~/components/favorites/FavoriteQuickEditDialog.vue')
).default

function createItem(
  entityType: FavoriteEntityType,
  overrides: Partial<UserFavoriteItem> = {},
): UserFavoriteItem {
  return {
    favoriteId: 'fav-001',
    entityType,
    entityId: '123',
    displayOrder: 0,
    createdAt: '2026-05-15T00:00:00Z',
    entity: {
      name: 'Sample',
      description: null,
      iconUrl: null,
      pageUrl: '/sample',
      status: 'AVAILABLE',
      canEdit: true,
      editableFields: [],
    },
    ...overrides,
  }
}

describe('FavoriteQuickEditDialog.vue', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    navigateToMock.mockReset()
    updateTeamMock.mockReset()
    updateOrganizationMock.mockReset()
    updateMyProfileMock.mockReset()
    notificationMock.success.mockReset()
    notificationMock.error.mockReset()
  })

  it('FAV-DLG-001: modelValue=null のときダイアログが表示されない', async () => {
    const wrapper = await mountSuspended(FavoriteQuickEditDialog, {
      props: { modelValue: null },
    })

    // role="dialog" が存在しない
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('FAV-DLG-002: TEAM modelValue で TEAM 用フォーム（name/description/iconUrl）が表示される', async () => {
    const item = createItem('TEAM', {
      entity: {
        name: 'My Team',
        description: 'desc',
        iconUrl: 'http://example.com/i.png',
        pageUrl: '/teams/123',
        status: 'AVAILABLE',
        canEdit: true,
        editableFields: [],
      },
    })
    const wrapper = await mountSuspended(FavoriteQuickEditDialog, {
      props: { modelValue: item },
    })

    expect(wrapper.find('[role="dialog"]').exists()).toBe(true)
    const inputs = wrapper.findAll('input[type="text"]')
    const textareas = wrapper.findAll('textarea')
    // name + iconUrl の input が 2 つ + description textarea が 1 つ
    expect(inputs.length).toBe(2)
    expect(textareas.length).toBe(1)
    // プリフィル値の検証
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('My Team')
    expect((textareas[0]!.element as HTMLTextAreaElement).value).toBe('desc')
    expect((inputs[1]!.element as HTMLInputElement).value).toBe('http://example.com/i.png')
  })

  it('FAV-DLG-003: ORGANIZATION modelValue で ORG 用フォーム（name/description）が表示される', async () => {
    const item = createItem('ORGANIZATION', {
      entity: {
        name: 'My Org',
        description: 'org desc',
        iconUrl: null,
        pageUrl: '/organizations/123',
        status: 'AVAILABLE',
        canEdit: true,
        editableFields: [],
      },
    })
    const wrapper = await mountSuspended(FavoriteQuickEditDialog, {
      props: { modelValue: item },
    })

    const inputs = wrapper.findAll('input[type="text"]')
    const textareas = wrapper.findAll('textarea')
    // ORG は name input 1 つ + description textarea 1 つ（iconUrl なし）
    expect(inputs.length).toBe(1)
    expect(textareas.length).toBe(1)
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('My Org')
    expect((textareas[0]!.element as HTMLTextAreaElement).value).toBe('org desc')
  })

  it('FAV-DLG-004: BLOG_AUTHOR modelValue で表示名・自己紹介フォームが表示される', async () => {
    const item = createItem('BLOG_AUTHOR', {
      entity: {
        name: 'Author Name',
        description: 'about me',
        iconUrl: null,
        pageUrl: '/authors/123',
        status: 'AVAILABLE',
        canEdit: true,
        editableFields: [],
      },
    })
    const wrapper = await mountSuspended(FavoriteQuickEditDialog, {
      props: { modelValue: item },
    })

    const inputs = wrapper.findAll('input[type="text"]')
    const textareas = wrapper.findAll('textarea')
    expect(inputs.length).toBe(1)
    expect(textareas.length).toBe(1)
    expect((inputs[0]!.element as HTMLInputElement).value).toBe('Author Name')
    expect((textareas[0]!.element as HTMLTextAreaElement).value).toBe('about me')
  })

  it('FAV-DLG-005: KB_PAGE modelValue で navigateTo が呼ばれて即時クローズ', async () => {
    const item = createItem('KB_PAGE', {
      entity: {
        name: 'KB Page',
        description: null,
        iconUrl: null,
        pageUrl: '/kb/articles/42',
        status: 'AVAILABLE',
        canEdit: true,
        editableFields: [],
      },
    })
    const wrapper = await mountSuspended(FavoriteQuickEditDialog, {
      props: { modelValue: item },
    })

    expect(navigateToMock).toHaveBeenCalledWith('/kb/articles/42?mode=edit')
    // update:modelValue(null) で閉じる emit
    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted?.[0]).toEqual([null])
  })

  it('FAV-DLG-006: VILLAGE modelValue で保存ボタンが disabled', async () => {
    const item = createItem('VILLAGE', {
      entity: {
        name: 'My Village',
        description: null,
        iconUrl: null,
        pageUrl: '/villages/1',
        status: 'AVAILABLE',
        canEdit: true,
        editableFields: [],
      },
    })
    const wrapper = await mountSuspended(FavoriteQuickEditDialog, {
      props: { modelValue: item },
    })

    // 保存ボタン = bg-blue-600 を持つ button を特定
    const buttons = wrapper.findAll('button')
    const saveBtn = buttons.find((b) => (b.element.className ?? '').includes('bg-blue-600'))
    expect(saveBtn).toBeTruthy()
    expect((saveBtn!.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('FAV-DLG-007: キャンセルボタンクリックで update:modelValue が null で emit される', async () => {
    const item = createItem('TEAM')
    const wrapper = await mountSuspended(FavoriteQuickEditDialog, {
      props: { modelValue: item },
    })

    const buttons = wrapper.findAll('button')
    const cancelBtn = buttons.find((b) => (b.element.className ?? '').includes('border-gray-300'))
    expect(cancelBtn).toBeTruthy()
    await cancelBtn!.trigger('click')

    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    const last = emitted![emitted!.length - 1]
    expect(last).toEqual([null])
  })

  it('FAV-DLG-008: TEAM の name が空のとき保存ボタンが disabled', async () => {
    const item = createItem('TEAM', {
      entity: {
        name: '',
        description: null,
        iconUrl: null,
        pageUrl: '/teams/123',
        status: 'AVAILABLE',
        canEdit: true,
        editableFields: [],
      },
    })
    const wrapper = await mountSuspended(FavoriteQuickEditDialog, {
      props: { modelValue: item },
    })

    const buttons = wrapper.findAll('button')
    const saveBtn = buttons.find((b) => (b.element.className ?? '').includes('bg-blue-600'))
    expect(saveBtn).toBeTruthy()
    expect((saveBtn!.element as HTMLButtonElement).disabled).toBe(true)
  })
})
