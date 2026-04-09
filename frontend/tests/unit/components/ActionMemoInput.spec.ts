import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import ActionMemoInput from '~/components/action-memo/ActionMemoInput.vue'

/**
 * F02.5 ActionMemoInput.vue のユニットテスト。
 *
 * - Enter で送信される
 * - Shift+Enter で改行のみ（送信されない）
 * - 空文字 / 5,001 文字超で送信ボタンが disabled
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
}

vi.mock('~/composables/useActionMemoApi', () => ({
  useActionMemoApi: () => apiMock,
}))

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 } }),
}))

beforeEach(() => {
  setActivePinia(createPinia())
  apiMock.createMemo.mockReset()
  if (typeof window !== 'undefined') {
    window.localStorage?.clear()
  }
})

describe('ActionMemoInput.vue', () => {
  it('空文字のとき送信ボタンは disabled', async () => {
    const wrapper = await mountSuspended(ActionMemoInput)
    const submitBtn = wrapper.get('[data-testid="action-memo-input-submit"]')
    expect((submitBtn.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('文字を入力すると送信ボタンが有効化される', async () => {
    const wrapper = await mountSuspended(ActionMemoInput)
    const textarea = wrapper.get('[data-testid="action-memo-input-textarea"]')
    await textarea.setValue('朝散歩')
    const submitBtn = wrapper.get('[data-testid="action-memo-input-submit"]')
    expect((submitBtn.element as HTMLButtonElement).disabled).toBe(false)
  })

  it('5,001 文字で送信ボタンは disabled', async () => {
    const wrapper = await mountSuspended(ActionMemoInput)
    const textarea = wrapper.get('[data-testid="action-memo-input-textarea"]')
    await textarea.setValue('a'.repeat(5001))
    const submitBtn = wrapper.get('[data-testid="action-memo-input-submit"]')
    expect((submitBtn.element as HTMLButtonElement).disabled).toBe(true)
  })

  it('Enter キーで送信が呼ばれる', async () => {
    apiMock.createMemo.mockResolvedValueOnce({
      id: 1,
      memoDate: '2026-04-09',
      content: 'enter test',
      mood: null,
      relatedTodoId: null,
      timelinePostId: null,
      tags: [],
      createdAt: '2026-04-09T08:00:00',
    })
    const wrapper = await mountSuspended(ActionMemoInput)
    const textarea = wrapper.get('[data-testid="action-memo-input-textarea"]')
    await textarea.setValue('enter test')
    await textarea.trigger('keydown', { key: 'Enter', shiftKey: false })
    // 送信完了まで待つ
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(apiMock.createMemo).toHaveBeenCalledTimes(1)
    expect(apiMock.createMemo).toHaveBeenCalledWith(
      expect.objectContaining({ content: 'enter test' }),
    )
  })

  it('Shift+Enter では送信されない（改行のみ）', async () => {
    const wrapper = await mountSuspended(ActionMemoInput)
    const textarea = wrapper.get('[data-testid="action-memo-input-textarea"]')
    await textarea.setValue('shift enter test')
    await textarea.trigger('keydown', { key: 'Enter', shiftKey: true })
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(apiMock.createMemo).not.toHaveBeenCalled()
  })

  it('IME 変換中の Enter では送信されない（isComposing = true）', async () => {
    const wrapper = await mountSuspended(ActionMemoInput)
    const textarea = wrapper.get('[data-testid="action-memo-input-textarea"]')
    await textarea.setValue('IME中')
    await textarea.trigger('keydown', { key: 'Enter', isComposing: true })
    await new Promise((resolve) => setTimeout(resolve, 0))
    expect(apiMock.createMemo).not.toHaveBeenCalled()
  })
})
