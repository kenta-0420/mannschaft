import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { setActivePinia, createPinia } from 'pinia'
import ActionMemoEditDialog from '~/components/action-memo/ActionMemoEditDialog.vue'
import type { ActionMemo } from '~/types/actionMemo'

/**
 * F02.5 ActionMemoEditDialog.vue のユニットテスト（Phase 2）。
 *
 * - modelValue による開閉
 * - 空文字のとき保存ボタンが disabled
 * - 保存成功時に saved イベントが発火し、update:modelValue(false) が続く
 *
 * <p><b>Teleport 対応</b>: ダイアログ本体は {@code <Teleport to="body">} により
 * document.body 直下にレンダリングされるため、{@code wrapper.find} は使えず
 * {@code document.body.querySelector} で要素を取得する。</p>
 */

/** Teleport 先の body から testid で要素を取得するヘルパ */
function findByTestId<T extends Element = HTMLElement>(testId: string): T | null {
  return document.body.querySelector<T>(`[data-testid="${testId}"]`)
}
function getByTestId<T extends Element = HTMLElement>(testId: string): T {
  const el = findByTestId<T>(testId)
  if (!el) {
    throw new Error(`[data-testid="${testId}"] が見つかりません`)
  }
  return el
}

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
}

vi.mock('~/composables/useActionMemoApi', () => ({
  useActionMemoApi: () => apiMock,
}))

vi.mock('~/stores/useAuthStore', () => ({
  useAuthStore: () => ({ user: { id: 42 } }),
}))

function makeMemo(overrides: Partial<ActionMemo> = {}): ActionMemo {
  return {
    id: 4521,
    memoDate: '2026-04-09',
    content: '朝散歩した',
    mood: null,
    relatedTodoId: null,
    timelinePostId: null,
    tags: [],
    createdAt: '2026-04-09T08:00:00',
    ...overrides,
  }
}

beforeEach(() => {
  setActivePinia(createPinia())
  apiMock.updateMemo.mockReset()
})

afterEach(() => {
  // Teleport された DOM の後始末
  const dialog = findByTestId('action-memo-edit-dialog')
  dialog?.parentElement?.removeChild(dialog)
})

/**
 * 入力値を反映するユーティリティ。v-model を発火させる。
 */
function setTextareaValue(el: HTMLTextAreaElement, value: string) {
  el.value = value
  el.dispatchEvent(new Event('input', { bubbles: true }))
}

describe('ActionMemoEditDialog.vue', () => {
  it('modelValue = false のときダイアログは表示されない', async () => {
    await mountSuspended(ActionMemoEditDialog, {
      props: { modelValue: false, memo: null },
    })
    expect(findByTestId('action-memo-edit-dialog')).toBeNull()
  })

  it('modelValue = true でダイアログが表示され、memo.content が初期値', async () => {
    const memo = makeMemo({ content: '編集対象の本文' })
    await mountSuspended(ActionMemoEditDialog, {
      props: { modelValue: true, memo },
    })
    expect(findByTestId('action-memo-edit-dialog')).not.toBeNull()
    const textarea = getByTestId<HTMLTextAreaElement>(
      'action-memo-edit-dialog-textarea',
    )
    expect(textarea.value).toBe('編集対象の本文')
  })

  it('空文字に編集すると保存ボタンが disabled', async () => {
    const memo = makeMemo({ content: '元の本文' })
    const wrapper = await mountSuspended(ActionMemoEditDialog, {
      props: { modelValue: true, memo },
    })
    const textarea = getByTestId<HTMLTextAreaElement>(
      'action-memo-edit-dialog-textarea',
    )
    setTextareaValue(textarea, '')
    await wrapper.vm.$nextTick()
    const saveBtn = getByTestId<HTMLButtonElement>('action-memo-edit-dialog-save')
    expect(saveBtn.disabled).toBe(true)
  })

  it('保存成功時に saved イベントを emit し、ダイアログを閉じる', async () => {
    const memo = makeMemo({ content: '元の本文' })
    apiMock.updateMemo.mockResolvedValueOnce({
      ...memo,
      content: '更新後の本文',
    })
    const wrapper = await mountSuspended(ActionMemoEditDialog, {
      props: { modelValue: true, memo },
    })
    const textarea = getByTestId<HTMLTextAreaElement>(
      'action-memo-edit-dialog-textarea',
    )
    setTextareaValue(textarea, '更新後の本文')
    await wrapper.vm.$nextTick()
    const saveBtn = getByTestId<HTMLButtonElement>('action-memo-edit-dialog-save')
    saveBtn.click()
    // 非同期処理の完了を待つ
    await new Promise((resolve) => setTimeout(resolve, 0))
    await new Promise((resolve) => setTimeout(resolve, 0))
    await wrapper.vm.$nextTick()
    expect(apiMock.updateMemo).toHaveBeenCalledTimes(1)
    expect(apiMock.updateMemo).toHaveBeenCalledWith(
      4521,
      expect.objectContaining({ content: '更新後の本文' }),
    )
    const savedEmits = wrapper.emitted('saved')
    expect(savedEmits).toBeTruthy()
    const closeEmits = wrapper.emitted('update:modelValue')
    expect(closeEmits).toBeTruthy()
    // 最後の update:modelValue が false（閉じる）であること
    const lastClose = closeEmits?.[closeEmits.length - 1]
    expect(lastClose).toEqual([false])
  })

  it('キャンセルボタンで update:modelValue(false) を emit', async () => {
    const memo = makeMemo()
    const wrapper = await mountSuspended(ActionMemoEditDialog, {
      props: { modelValue: true, memo },
    })
    const cancelBtn = getByTestId<HTMLButtonElement>(
      'action-memo-edit-dialog-cancel',
    )
    cancelBtn.click()
    await wrapper.vm.$nextTick()
    const closeEmits = wrapper.emitted('update:modelValue')
    expect(closeEmits).toBeTruthy()
    expect(closeEmits?.[closeEmits.length - 1]).toEqual([false])
  })
})
