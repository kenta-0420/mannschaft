import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import TagPicker from '~/components/action-memo/TagPicker.vue'

/**
 * F02.5 TagPicker.vue のユニットテスト（Phase 4）。
 *
 * - 既存タグの表示
 * - 検索フィルタ
 * - 選択 + チップ表示
 * - 除去
 * - 新規作成
 */

// === Mocks ===
const apiMock = {
  createMemo: vi.fn(),
  fetchMemos: vi.fn(),
  getMemo: vi.fn(),
  updateMemo: vi.fn(),
  deleteMemo: vi.fn(),
  linkTodo: vi.fn(),
  getSettings: vi.fn(),
  updateSettings: vi.fn(),
  publishDaily: vi.fn(),
  fetchWeeklySummaries: vi.fn(),
  getWeeklySummary: vi.fn(),
  getTags: vi.fn(),
  createTag: vi.fn(),
  updateTag: vi.fn(),
  deleteTag: vi.fn(),
  addTagsToMemo: vi.fn(),
  removeTagFromMemo: vi.fn(),
  getMoodStats: vi.fn(),
}

vi.mock('~/composables/useActionMemoApi', () => ({
  useActionMemoApi: () => apiMock,
}))

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 } }),
}))

const mockTags = [
  { id: 1, name: '運動', color: '#5DADE2', deleted: false },
  { id: 2, name: '勉強', color: '#FF6B6B', deleted: false },
  { id: 3, name: '読書', color: null, deleted: false },
  { id: 4, name: '夜更かし', color: null, deleted: true },
]

function setupStore() {
  const store = useActionMemoStore()
  store.tags = [...mockTags]
  return store
}

beforeEach(() => {
  setActivePinia(createPinia())
  apiMock.getTags.mockReset()
  apiMock.createTag.mockReset()
  apiMock.getTags.mockResolvedValue(mockTags)
})

describe('TagPicker.vue', () => {
  it('アクティブなタグのみサジェスト候補に表示される（削除済みは除外）', async () => {
    setupStore()
    const wrapper = await mountSuspended(TagPicker, {
      props: { modelValue: [] },
    })

    // 検索フィールドにフォーカスしてドロップダウンを表示
    const searchInput = wrapper.get('[data-testid="tag-picker-search"]')
    await searchInput.trigger('focus')

    // ドロップダウンが表示される
    const dropdown = wrapper.find('[data-testid="tag-picker-dropdown"]')
    expect(dropdown.exists()).toBe(true)

    // アクティブなタグ3件が表示される
    expect(wrapper.find('[data-testid="tag-option-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="tag-option-2"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="tag-option-3"]').exists()).toBe(true)

    // 削除済みタグは表示されない
    expect(wrapper.find('[data-testid="tag-option-4"]').exists()).toBe(false)
  })

  it('検索クエリでタグがフィルタされる', async () => {
    setupStore()
    const wrapper = await mountSuspended(TagPicker, {
      props: { modelValue: [] },
    })

    const searchInput = wrapper.get('[data-testid="tag-picker-search"]')
    await searchInput.trigger('focus')
    await searchInput.setValue('運')

    // 「運動」のみが候補に残る
    expect(wrapper.find('[data-testid="tag-option-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="tag-option-2"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="tag-option-3"]').exists()).toBe(false)
  })

  it('選択済みタグがチップとして表示される', async () => {
    setupStore()
    const wrapper = await mountSuspended(TagPicker, {
      props: { modelValue: [1, 3] },
    })

    // 選択済みタグのチップが表示される
    expect(wrapper.find('[data-testid="tag-chip-1"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="tag-chip-3"]').exists()).toBe(true)

    // 未選択のタグのチップは表示されない
    expect(wrapper.find('[data-testid="tag-chip-2"]').exists()).toBe(false)
  })

  it('チップの × ボタンでタグが除去される', async () => {
    setupStore()
    const wrapper = await mountSuspended(TagPicker, {
      props: { modelValue: [1, 2] },
    })

    // タグ 1 の除去ボタンを押下
    await wrapper.get('[data-testid="tag-chip-remove-1"]').trigger('click')

    // update:modelValue が [2] で emit される
    const emitted = wrapper.emitted('update:modelValue')
    expect(emitted).toBeTruthy()
    expect(emitted![emitted!.length - 1]).toEqual([[2]])
  })

  it('10個選択済みの場合に上限メッセージが表示される', async () => {
    setupStore()
    const wrapper = await mountSuspended(TagPicker, {
      props: { modelValue: [1, 2, 3, 10, 11, 12, 13, 14, 15, 16] },
    })

    // 上限メッセージが表示される
    expect(wrapper.find('[data-testid="tag-picker-max-reached"]').exists()).toBe(true)

    // 検索入力欄は表示されない
    expect(wrapper.find('[data-testid="tag-picker-search"]').exists()).toBe(false)
  })

  it('新規作成フォームでタグが作成される', async () => {
    setupStore()
    apiMock.createTag.mockResolvedValue({ id: 99, name: '新タグ', color: '#FF0000', deleted: false })

    const wrapper = await mountSuspended(TagPicker, {
      props: { modelValue: [] },
    })

    // 検索フィールドにフォーカスしてドロップダウンを表示
    const searchInput = wrapper.get('[data-testid="tag-picker-search"]')
    await searchInput.trigger('focus')

    // 新規作成ボタンを押下
    const createBtn = wrapper.find('[data-testid="tag-picker-create-button"]')
    expect(createBtn.exists()).toBe(true)

    // mousedown で作成フォーム表示
    await createBtn.trigger('mousedown')

    // 作成フォームが表示される
    const createForm = wrapper.find('[data-testid="tag-picker-create-form"]')
    expect(createForm.exists()).toBe(true)

    // タグ名を入力して送信
    const nameInput = wrapper.get('[data-testid="tag-picker-new-name"]')
    await nameInput.setValue('新タグ')
    await wrapper.get('[data-testid="tag-picker-create-submit"]').trigger('click')

    // store.createTag が呼ばれたことを確認
    expect(apiMock.createTag).toHaveBeenCalledWith({ name: '新タグ', color: '#6366F1' })
  })
})
